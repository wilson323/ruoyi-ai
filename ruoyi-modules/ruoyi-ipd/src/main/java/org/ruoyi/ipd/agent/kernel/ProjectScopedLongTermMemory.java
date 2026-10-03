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
 * <p><b>失败语义</b>：合法空结果不注入记忆；抽取、配置和 DB 失败向主运行传播脱敏错误，禁止静默降级。
 */
public final class ProjectScopedLongTermMemory implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(ProjectScopedLongTermMemory.class);
    private static final Duration EXTRACT_TIMEOUT = Duration.ofSeconds(30);

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
     */
    @Override
    public Mono<Void> record(List<Msg> messages) {
        return Mono.<Void>defer(() -> {
            log.info("[ipd-memory] record triggered run={} messages={}",
                runId, messages == null ? 0 : messages.size());
            if (messages == null || messages.isEmpty()) return Mono.empty();
            if (model == null) return Mono.error(new IllegalStateException("Memory extraction model is unavailable"));
            String transcript = render(messages);
            if (transcript.isBlank()) return Mono.empty();
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
                log.info("[ipd-memory] run={} project={} person={} extracted={} saved={}",
                    runId, projectId, personId, items.size(), saved);
            }).subscribeOn(Schedulers.boundedElastic()).then());
        }).timeout(EXTRACT_TIMEOUT).onErrorMap(e -> memoryFailure("record", e));
    }

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
            .timeout(EXTRACT_TIMEOUT)
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
