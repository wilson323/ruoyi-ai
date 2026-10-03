package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.memory.LongTermMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ruoyi.ipd.domain.IpdAgentMemory;
import org.ruoyi.ipd.mapper.IpdAgentMemoryMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 项目+人作用域的长期记忆，实现官方 SPI {@link LongTermMemory}。
 *
 * <p><b>官方接口、业务自填</b>：本仓不引入 Mem0 / 百炼 / ReMe 任何一个后端
 * （百炼是云托管，违反单企业私有部署的数据不出域约束），只用官方两个方法
 * {@link #record} / {@link #retrieve}，存储落在自有 MySQL。
 *
 * <p><b>为什么是 per-run 实例</b>：官方 SPI 的两个方法
 * （{@code record(List<Msg>)} / {@code retrieve(Msg)}）<b>都没有作用域参数</b>，
 * 而官方 {@code RuntimeContextAware} 只有 {@code setRuntimeContext} 没有 getter，
 * 从事件里读不回作用域；{@code record} 又走异步调度线程，ThreadLocal 不可靠。
 * 而 IPD 每次运行都新建 {@code HarnessAgent}（{@code AgentScopeProjectAgentKernel#buildManagedAgent}），
 * 于是**把作用域绑在实例构造期**是最短且无竞争的实现路径——不依赖任何隐式上下文传递。
 *
 * <p><b>权威性红线</b>：召回文本一律带「非权威个人工作笔记」标注；
 * IPD 权限 / 动作审批 / 文档审核 / Gate 链路<b>不查本表</b>（AGENTS.md:77）。
 *
 * <p><b>失败语义（2026-10-03 run 2106378468009717761 实测后修订）</b>：合法空结果不注入记忆。
 * 抽取、配置和 DB 失败<b>不再改写业务终态</b>——该轮回答在抽取启动前已经推送给用户并落库，
 * 记忆是回答<b>之后</b>的副作用，失败不得把已交付的结果倒改成失败（旧实现正是如此：
 * 抽取 30 秒超时 → 主流抛错 → FAILED/STREAM_ERROR，对外文案「模型输出中断」与事实相反）。
 * 改为：失败由 {@link ProjectAgentLongTermMemoryMiddleware} 写 {@code MEMORY_RECEIPT} 持久回执
 * （状态、错误类别、是否可重试），既不吞掉也不伪装成功，可按回执补写。
 * 召回仍在调用前发生，其失败仍应向主运行传播——它影响的是本轮回答质量，不是事后效果。
 */
public final class ProjectScopedLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(ProjectScopedLongTermMemory.class);

    /**
     * 单个流片段之间的空闲期限：每来一片自动重置（javap 实测 reactor-core 3.8.7 的
     * {@code Flux.timeout(Duration)} 语义即「两片之间最长间隔」）。<b>第一片也用同一个期限</b>。
     *
     * <p>取值依据与一次自我修正：初版设 5 秒，依据是「健康调用耗时几乎全花在持续吐字上」。
     * 修复后真实服务连续 3 次运行的后台模型调用实测为 3/9/14、5/7/11、3/8/10 秒，**最长 14 秒**，
     * 且零重试（5 秒期限一次都没被触发）。但这些只是<b>总耗时</b>，不等于片间间隔，
     * 也没有首 token 延迟的实测值；短抽取提示词完全可能出现 &gt;5 秒的首 token 等待。
     * 那样会误杀一次<b>健康</b>调用——比原缺陷隐蔽，因为它只表现为少写一条记忆、不再报错。
     * 故放宽到 10 秒：仍比旧值 30 秒快 3 倍（挂死检测从 30 秒降到 10 秒），
     * 又对健康调用留出足够余量。
     *
     * <p>误判的爆炸半径已被上一处修复封顶：最坏 10s×2 + 0.5s = 20.5 秒后写 WRITE_FAILED 回执，
     * <b>不再改写业务终态</b>。宁可少写一条可重试的记忆，不可把已交付的回答判成失败。
     */
    private static final Duration EXTRACT_IDLE_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 整轮抽取（含重试）的兜底总期限——<b>安全网，不是发现机制</b>。
     *
     * <p>分工必须说清，否则会把两个期限的作用搞反：
     * <ul>
     *   <li>{@link #EXTRACT_IDLE_TIMEOUT} 抓「死流」：连一片数据都不来，10 秒内暴露并进入重试。
     *       挂死的发现速度<b>只由它决定</b>。</li>
     *   <li>本总期限只抓「活着但异常慢」：每片都来、但整体拖很久。它对挂死发现速度<b>毫无贡献</b>。</li>
     * </ul>
     * 因此把它设小没有任何好处，只会让健康但偏慢的抽取被误杀。
     *
     * <p>取值依据：换包后真实服务实测两次记忆抽取耗时 14 秒与 11 秒（按 inputTokens 463/487 与
     * MEMORY_RECEIPT 落库时刻双重佐证归属）。空闲 10s × 1 次重试 + 0.5s 退避的理论上界是 20.5 秒，
     * 本总期限取 45 秒，使其在健康区间<b>永远不会被触及</b>，只在真正失控时才兜底。
     * 初版 25 秒时余量仅 4.5 秒——一次 20 秒的健康抽取就会被砍成假的 WRITE_FAILED 回执，
     * 业务终态虽不受影响（那正是本次修复的价值），但会误导后续「按回执补写」的判断。
     */
    private static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(45);

    /**
     * 抽取的<b>重试</b>次数（不含首次）。总模型调用数 = 本值 + 1。
     *
     * <p>命名刻意用「重试」而不是「尝试」：Reactor 的 {@code Retry.fixedDelay(n, …)} 里 n 是重试次数，
     * 曾因按「总次数」理解而把最坏耗时算成 20.5s、实际却是 31s，超出兜底总期限而被中途截断。
     * 当前取值使最坏耗时 = 2×10s + 1×0.5s = 20.5s &lt; {@link #EXTRACT_TIMEOUT} 的 25s。
     *
     * <p>重放安全：抽取只读模型、只写按内容 SHA 去重的表，重放不产生第二行。
     */
    private static final int EXTRACT_RETRIES = 1;
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

    /** 抽取结果的行分隔符与前缀，模型必须严格照此输出。 */
    private static final String ITEM_PREFIX = "MEM|";
    private static final Set<String> VALID_KINDS = Set.of(
        IpdAgentMemory.KIND_PREFERENCE, IpdAgentMemory.KIND_FACT, IpdAgentMemory.KIND_OBSERVATION);

    /** 拒绝入库的敏感词——抽取后仍做一次兜底扫描，不依赖模型自觉。 */
    private static final List<String> FORBIDDEN = List.of(
        "password", "passwd", "secret", "apikey", "api_key", "token", "私钥", "口令", "密码");

    private final Long projectId;
    private final Long personId;
    private final Long runId;
    private final IpdAgentMemoryMapper mapper;
    private final Model model;

    public ProjectScopedLongTermMemory(Long projectId, Long personId, Long runId,
                                       IpdAgentMemoryMapper mapper, Model model) {
        this.projectId = Objects.requireNonNull(projectId, "projectId");
        this.personId = Objects.requireNonNull(personId, "personId");
        this.runId = runId;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.model = model;
    }

    /**
     * 运行正常结束后由官方 {@code MiddlewareBase} 适配调用：抽取可复用事实与用户偏好并入库。
     *
     * <p>幂等：同一段来源文本经 SHA-256 摘要后在作用域内唯一，重放不产生第二行。
     * 因此空闲挂死后按 {@link #EXTRACT_RETRIES} 有限重试是安全的。
     */
    @Override
    public Mono<Void> record(List<Msg> messages) {
        return Mono.<Void>defer(() -> {
            log.info("[ipd-memory] record triggered run={} messages={}",
                runId, messages == null ? 0 : messages.size());
            if (messages == null || messages.isEmpty()) { lastOutcome = new RecordOutcome(0, 0, null); return Mono.empty(); }
            if (model == null) {
                lastOutcome = new RecordOutcome(0, 0, new IllegalStateException("Memory extraction model is unavailable"));
                return Mono.error(lastOutcome.failure());
            }
            String transcript = render(messages);
            if (transcript.isBlank()) { lastOutcome = new RecordOutcome(0, 0, null); return Mono.empty(); }
            return extract(transcript).flatMap(items -> Mono.fromRunnable(() -> {
                int saved = 0;
                for (String[] item : items) {
                    if (!VALID_KINDS.contains(item[0]) || !acceptable(item[1])) continue;
                    IpdAgentMemory memory = IpdAgentMemory.builder()
                        .projectId(projectId).personId(personId).runId(runId)
                        .kind(item[0]).content(truncate(item[1], 2000)).sourceDigest(digest(item[1]))
                        .status(IpdAgentMemory.STATUS_CANDIDATE).delFlag("0").build();
                    saved += mapper.insertIgnoreDuplicate(memory);
                }
                lastOutcome = new RecordOutcome(items.size(), saved, null);
                log.info("[ipd-memory] run={} project={} person={} extracted={} saved={}",
                    runId, projectId, personId, items.size(), saved);
            }).subscribeOn(Schedulers.boundedElastic()).then());
        }).timeout(EXTRACT_TIMEOUT)
          .onErrorMap(e -> {
              if (lastOutcome == null || lastOutcome.failure() == null) lastOutcome = new RecordOutcome(0, 0, e);
              return memoryFailure("record", e);
          });
    }

    /** 抽取与入库的结果快照；{@link #record} 终止后读取，由调用方写持久回执。 */
    public record RecordOutcome(int extracted, int saved, Throwable failure) {
        public boolean written() { return failure == null; }
    }

    /**
     * 最近一次 {@link #record} 的结果。{@code record} 的信号终止先于调用方读到本字段，
     * 读到的必然是本次而非上一次的结局。
     */
    public RecordOutcome lastOutcome() { return lastOutcome; }
    private volatile RecordOutcome lastOutcome;

    /**
     * 运行开始前召回本人在本项目的记忆，供模型参考。
     *
     * <p>返回文本<b>始终带非权威标注</b>；无记忆时返回 {@code null}，由 SDK 跳过注入。
     */
    @Override
    public Mono<String> retrieve(Msg query) {
        return Mono.fromCallable(() -> {
                List<IpdAgentMemory> rows =
                    mapper.recallForScope(projectId, personId, IpdAgentMemory.RECALL_LIMIT);
                if (rows == null || rows.isEmpty()) {
                    return null;
                }
                return renderRecall(rows);
            })
            .onErrorMap(e -> memoryFailure("retrieve", e))
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 只重试「传输/超时」类故障：它们换个连接通常就成功。认证、鉴权、额度、参数错误重试
     * 只会把同一个确定性失败重复打三遍并推迟回执，必须第一次就如实记账。
     */
    private static boolean isRetryableExtractionFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.util.concurrent.TimeoutException
                || current instanceof java.io.IOException
                || current instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            if (current.getCause() == current) break;
        }
        return false;
    }

    private IllegalStateException memoryFailure(String operation, Throwable failure) {
        // 异常消息和 cause 可能含连接串、模型请求或凭据；只传播操作和异常类别。
        log.warn("[ipd-memory] operation={} failed run={} errorType={}",
            operation, runId, failure.getClass().getSimpleName());
        return new IllegalStateException("Long-term memory " + operation + " failed ("
            + failure.getClass().getSimpleName() + ")");
    }

    /** 召回文本的固定外壳——「非权威」四个字必须由代码保证，不依赖模型或下游遵守。 */
    private String renderRecall(List<IpdAgentMemory> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("【非权威个人工作笔记】以下内容是本人此前与本项目智能体交互时沉淀的偏好与观察，")
          .append("仅供理解你的表达习惯与关注点参考。\n")
          .append("它不是业务事实、不构成权限、审批或 Gate 依据；与业务库表冲突时以业务库表为准。\n");
        for (IpdAgentMemory row : rows) {
            sb.append("- [").append(row.getKind()).append("] ").append(row.getContent()).append('\n');
        }
        return sb.toString();
    }

    /** 调模型抽取，每行一条 {@code MEM|KIND|CONTENT}。 */
    private Mono<List<String[]>> extract(String transcript) {
        String prompt = """
            你在从一段人机对话里抽取「值得下次记住」的信息。

            只抽这三类：
            - PREFERENCE：用户的表达偏好（要表格/要简短/关注成本…）
            - FACT：可复用的项目事实（会议结论、已确认的方案偏好…）
            - OBSERVATION：对用户关注点的观察

            严禁抽取：密码、密钥、token、身份证号等任何凭据或敏感标识。
            严禁抽取：审批结论、权限授予、Gate 判定、金额承诺等任何具业务权威的内容。

            严格按每行一条输出，格式为：MEM|KIND|CONTENT
            没有值得记的就输出 MEM|NONE|空。不要输出任何其它文字。
            待抽取的对话：
            """.trim() + "\n" + truncate(transcript, 6000);

        List<Msg> input = List.of(
            Msg.builder().role(MsgRole.USER).textContent(prompt).build());
        return reactor.core.publisher.Flux.defer(() -> model.stream(input, List.<ToolSchema>of(), null))
            // javap 实测 reactor-core 3.8.7：Flux.timeout(Duration) 本身就是「两片之间的空闲期限」，
            // 每来一片自动重置。旧值 30 秒即空转 30 秒；调到 5 秒让传输停顿立刻暴露。
            .timeout(EXTRACT_IDLE_TIMEOUT)
            .retryWhen(reactor.util.retry.Retry.fixedDelay(EXTRACT_RETRIES, RETRY_BACKOFF)
                .filter(ProjectScopedLongTermMemory::isRetryableExtractionFailure)
                .doBeforeRetry(signal -> log.warn("[ipd-memory] extraction retry run={} attempt={} errorType={}",
                    runId, signal.totalRetries() + 1, signal.failure().getClass().getSimpleName())))
            .collect(StringBuilder::new, this::appendText)
            .map(out -> {
                List<String[]> items = new ArrayList<>();
                for (String line : out.toString().split("\\R")) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith(ITEM_PREFIX)) continue;
                    String[] parts = trimmed.substring(ITEM_PREFIX.length()).split("\\|", 2);
                    if (parts.length == 2 && !"NONE".equals(parts[0].trim().toUpperCase(Locale.ROOT))) {
                        items.add(new String[] {parts[0].trim().toUpperCase(Locale.ROOT), parts[1].trim()});
                    }
                }
                return items;
            });
    }

    private void appendText(StringBuilder sink, ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return;
        }
        for (var block : response.getContent()) {
            if (block instanceof TextBlock text) {
                sink.append(text.getText());
            }
        }
    }

    /** 敏感内容的兜底扫描：模型不可靠时这道闸仍然有效。 */
    private boolean acceptable(String content) {
        // 中文密度高：「要表格」三个字就是一条完整偏好。此处曾用 length()<4 的拉丁字母中心阈值，
        // 会把绝大多数中文记忆误杀——阈值只取 2（排除单字噪声），质量交给抽取 prompt 约束。
        if (content == null || content.isBlank() || content.length() < 2) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        return FORBIDDEN.stream().noneMatch(lower::contains);
    }

    private String render(List<Msg> messages) {
        StringBuilder sb = new StringBuilder();
        for (Msg message : messages) {
            if (message == null || message.getContent() == null) {
                continue;
            }
            for (var block : message.getContent()) {
                if (block instanceof TextBlock text && text.getText() != null) {
                    sb.append(text.getText().trim()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String digest(String content) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }
}
