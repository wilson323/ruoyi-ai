package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.AiExecutionEngine;
import org.ruoyi.ipd.service.ai.NodeAgentResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R221 接线对账哨兵（spec §2.1 第三条）：防「矩阵登记了但没人实现」假绿。
 * 豁免名单 = 分批接线期未接码，只减不增（棘轮）；清零后删豁免集。
 *
 * <p><b>R236 棘轮到底</b>：剩余 61 码全接线后 {@code WIRED} = 全 67 码（LC01/LC03 退役后目录已由 69 收缩），
 * {@code EXEMPT} 仅余 LC04，按棘轮约定豁免集只减不增（保留常量本身以锁死「禁止回调」语义）。
 * 并新增四条对账，把原先靠人肉核对的规则表机制化（AGENTS.md 病根③根除）：
 * ①无两个执行器重复认领同一码；②动态深度码不得归零交付物执行器；③命名约定 {@code IPD-<code>}
 * 与种子 SQL 逐码对账；④前端 execMode 静态映射与 {@code ActionCatalog} 逐码跨仓对账。
 *
 * <p>接线口径事实源：{@code docs/ipd-系统说明/R236-生命周期节点智能体接线设计-20260927.md} §7.3。
 */
@Tag("dev")
class ExecutorCoverageSentinelTest {

    /**
     * R232-W14 批次（兄弟车道，本轮不碰其码集）：首切片 4 码 + 对账执行器 K01-K04。
     * R236 批次：剩余 61 码全接线；2026-10-03 LC01/LC03 退役后目录由 69 → 67。
     */
    private static final Set<String> CATALOG_CODES = ActionCatalog.ALL.stream()
        .map(ActionDef::code)
        .collect(Collectors.toUnmodifiableSet());

    /**
     * <b>⚠ 随「算钱」层下线保留的豁免码</b>。
     *
     * <p>LC03（上市后6个月终算：回款达成率+奖金池）已于 2026-10-03 从 {@code ActionCatalog}
     * 整体退役（69 → 67），不再需要豁免；LC04（双PM贡献度评定对账）的执行器
     * {@code Lc04ContributionReconcileExecutor} 及其对账 Service 已随奖金池 / 回款台账一并删除——
     * 该动作的业务内容<b>整个就是算钱</b>，不存在可保留的「非算钱」残余；但其动作码仍留在目录里
     * （文档侧 LC04 保留有效，后端实现待确认），故仅 LC04 仍登记为豁免。
     *
     * <p>生产影响：引擎遇 LC04 任务时 {@code executorByCode} 取不到执行器，
     * 走既有 fail 分支「无已接线执行器」（{@code AiExecResult.fail}）——<b>显式报错，不会静默算错</b>。
     */
    private static final Set<String> EXEMPT = Set.of("LC04");

    /**
     * 节点智能体种子 SQL 中**已退役但尚未清理**的建行码。
     *
     * <p>LC01（上市后销售与回款跟踪）已随「回款台账」从目录退役，本执行器也让出了该码，但
     * {@code docs/script/sql/update/20260927-ipd-node-agents.sql} 是**已应用的迁移**，按本轮纪律
     * 禁止改写（改了会造成新装库/老库分叉）。历史遗留的 {@code IPD-LC01} 建行由
     * {@code 2026-10-03-ipd-retire-lc01-lc03-draft.sql}（待 owner 拍板、未 apply）负责 DELETE。
     */
    private static final Set<String> RETIRED_SEEDED_CODES = Set.of("LC01");

    /** 实际已接线码集 = 目录码 − 豁免码。 */
    private static final Set<String> WIRED = CATALOG_CODES.stream()
        .filter(c -> !EXEMPT.contains(c))
        .collect(Collectors.toUnmodifiableSet());

    /** 消费 LLM 的执行器码集 = 必须有 {@code IPD-<code>} 智能体行的码（其余档位零 LLM，建行即假配置）。 */
    private static final Set<String> LLM_CONSUMING = union(GenerateExecutor.CODES, AgentEvidenceExecutor.CODES);

    /**
     * 构造签名以磁盘现态为准（R236：GenerateExecutor 5 参——+NodeAgentResolver；AgentEvidenceExecutor 4 参）。
     * 原含 Lc03/Lc04 两个对账执行器（各 4 参），随「算钱」层下线移除（见 EXEMPT 注释 / D-4）。
     */
    private static List<AiActionExecutor> executors() {
        return List.of(
            new LightDirectExecutor(null),
            new DeepDirectExecutor(null, null, null),
            new GenerateExecutor(null, null, null, null, null),
            new GatePrepExecutor(null, null, null, null, null, null),
            new KpiSharedReconcileExecutor(null, null, null, null),
            new AgentEvidenceExecutor(null, null, null, null));
    }

    @SafeVarargs
    private static Set<String> union(Set<String>... sets) {
        Set<String> out = new HashSet<>();
        for (Set<String> s : sets) {
            out.addAll(s);
        }
        return Set.copyOf(out);
    }

    @Test
    void wiredCodesMatchExecutorsUnion() {
        Set<String> union = executors().stream()
            .map(AiActionExecutor::supportedActionCodes)
            .flatMap(Set::stream)
            .collect(Collectors.toUnmodifiableSet());
        assertThat(union).isEqualTo(WIRED);
    }

    /**
     * R236 新增：无两个执行器重复认领同一码。
     * 各执行器码集由 {@code ActionCatalog} 派生，重复认领会被 {@code AiExecutionEngine} 的
     * {@code executorByCode} map **静默覆盖**（谁生效取决于 Spring 注入顺序）——不会红，只会错。
     */
    @Test
    void noTwoExecutorsClaimSameCode() {
        Map<String, String> owner = new HashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (AiActionExecutor ex : executors()) {
            String name = ex.getClass().getSimpleName();
            for (String code : ex.supportedActionCodes()) {
                String prev = owner.put(code, name);
                if (prev != null) {
                    conflicts.add(code + "（" + prev + " vs " + name + "）");
                }
            }
        }
        assertThat(conflicts).as("同一动作码被多个执行器认领（引擎 map 会静默覆盖）").isEmpty();
    }

    /**
     * R236 新增（契约 §7 B3）：动态深度码不得归 {@code LightDirectExecutor}。
     * {@code ActionCatalog.expectedDepth} 可随模板把 LIGHT 提升为 DEEP（现况仅 V11/SOLUTION），
     * 而 {@code StageActionService} 按**实例** depth 判定、DEEP 强制 ≥1 交付物（BR-IPD-03）；
     * LightDirectExecutor 不产交付物 → 该类实例必死于校验 → 退避 → DEAD。
     * 数据驱动枚举全部模板，日后新增动态深度码同样被拦。
     */
    @Test
    void dynamicDepthCodesNotAssignedToLightDirect() {
        List<String> templates = List.of("ALL", "HW", "SW", "SOL", "SOLUTION", "OVERSEAS", "BIOCV");
        List<String> offenders = new ArrayList<>();
        for (ActionDef def : ActionCatalog.ALL) {
            if (!"LIGHT".equals(def.depth())) {
                continue;
            }
            boolean escalates = templates.stream()
                .anyMatch(t -> "DEEP".equals(ActionCatalog.expectedDepth(def, t)));
            if (escalates && LightDirectExecutor.CODES.contains(def.code())) {
                offenders.add(def.code());
            }
        }
        assertThat(offenders)
            .as("目录 LIGHT 但可被模板提升为 DEEP 的码归了零交付物执行器，SOLUTION 类项目必死 BR-IPD-03")
            .isEmpty();
    }

    /**
     * R236 新增（契约 §7 B6）：{@code GenerateExecutor} 直接按 {@code def.ownerRole()} 查
     * {@code project_member} 在任成员发草稿待审通知，不做角色扇出。该简化仅当
     * ownerRole 均为可直接查成员表的单角色时成立：{@code BOTH} 需展开为双 PM，
     * {@code GROUP_LEADER} 挂在 {@code product_group.leader_person_id} 而非成员表——
     * 两者均会「查不到人 → log.warn 跳过」= 静默通知不到责任人。故锁死当前口径，
     * 目录一旦变更就在此显式失败，而非上线后无人收到待审通知。
     */
    @Test
    void generateCodesHaveSingleNotifiableOwnerRole() {
        Set<String> notifiable = Set.of("MARKET_PM", "RD_PM");
        List<String> offenders = ActionCatalog.ALL.stream()
            .filter(d -> GenerateExecutor.CODES.contains(d.code()))
            .filter(d -> !notifiable.contains(d.ownerRole()))
            .map(d -> d.code() + "→" + d.ownerRole())
            .sorted()
            .toList();
        assertThat(offenders)
            .as("AI_GENERATE 动作出现了 GenerateExecutor 不能直接通知的责任角色，需补扇出/组长解析")
            .isEmpty();
    }

    /**
     * R236 新增（契约 §1 裁决 B）：命名约定 {@code IPD-<code>} 是隐式契约，改名即静默解绑 →
     * 与种子 SQL 逐码对账。只校验**消费 LLM 的 39 码**（GenerateExecutor 24 + AgentEvidenceExecutor 15）；
     * 其余码执行器为确定性逻辑，种子不得为其建行（建行即装饰性假配置）。
     *（R232-LC03：LC03 改由 Lc03SettlementReconcileExecutor 确定性对账，零 LLM，不再建行。
     * LC04 同：Lc04ContributionReconcileExecutor 确定性对账，零 LLM，不再建行。
     * 2026-10-03：LC01 退役，AgentEvidenceExecutor 让出该码 → 消费 LLM 码集 40 → 39。）
     *
     * <p>双向用一次集合相等断言表达：缺行 → 该节点永久走降级路径；多行 → 零 LLM 档位的装饰性假配置。
     * 已退役但种子未清理的 {@code IPD-LC01} 行按 {@link #RETIRED_SEEDED_CODES} 显式登记，不算「多行」。
     */
    @Test
    void seedSqlRowsMatchLlmConsumingCodesExactly() throws IOException {
        Path sql = locateSeedSql();
        // 部分检出/CI 稀疏检出可能没有 docs 目录：跳过而非假绿通过
        Assumptions.assumeTrue(sql != null, "未定位到 docs/script/sql/update 下的 R236 节点智能体种子，跳过对账");
        Set<String> expected = new HashSet<>(LLM_CONSUMING);
        expected.addAll(RETIRED_SEEDED_CODES);
        assertThat(seededCodes(Files.readString(sql, StandardCharsets.UTF_8)))
            .as("种子 %s 实际建行的码集须 = 消费 LLM 的执行器码集 ∪ 已退役待清理码", sql.getFileName())
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** 同一 {@code agent_name} 不得重复建行（幂等守卫会吞掉第二条，故重复只在文件层面暴露）。 */
    @Test
    void seedSqlHasNoDuplicateRow() throws IOException {
        Path sql = locateSeedSql();
        Assumptions.assumeTrue(sql != null, "未定位到 R236 节点智能体种子，跳过对账");
        String content = Files.readString(sql, StandardCharsets.UTF_8);
        int inserts = 0;
        int from = 0;
        while ((from = content.indexOf("INSERT INTO agent_info", from)) >= 0) {
            inserts++;
            from += 1;
        }
        assertThat(inserts)
            .as("INSERT 语句数须等于去重后的建行数，否则文件内有重复/漏号的行")
            .isEqualTo(seededCodes(content).size());
    }

    /**
     * 解析种子中**真正建行**的动作码。只认幂等守卫
     * {@code WHERE NOT EXISTS (SELECT 1 FROM agent_info WHERE agent_name = 'IPD-X')} 这一处字面量：
     * 文件文末的回验查询 ⑥（应有码清单）与 ⑦（反向越界清单）会列出全 69 码含被排除码，
     * 故 naive {@code content.contains("'IPD-X'")} 会同时造成正向假绿与反向假红。
     */
    private static Set<String> seededCodes(String content) {
        Matcher m = SEED_ROW_PATTERN.matcher(content);
        Set<String> out = new TreeSet<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** 建行标记：幂等守卫中的 agent_name 字面量（{@link NodeAgentResolver#NAME_PREFIX} 为契约前缀）。 */
    private static final Pattern SEED_ROW_PATTERN = Pattern.compile(
        "WHERE NOT EXISTS \\(SELECT 1 FROM agent_info WHERE agent_name = '"
            + NodeAgentResolver.NAME_PREFIX + "([A-Z0-9]+)'\\)");

    /** 遗留 MINOR#2 行为锁（真实现非 mock）：可自动派发集 = AI 档 ∧ 已接线 ∧ supportsSchedule。 */
    @Test
    void scheduleWiredCodesExcludeFillTableAndHumanGate() {
        AiExecutionEngine engine = new AiExecutionEngine(null, executors(), null, null);
        assertThat(engine.wiredActionCodes()).isEqualTo(WIRED);
        // R236 排除面：AgentEvidence 15（LLM 产物即 DONE 门禁证据，无独立人审环节 → 仅 PASSIVE 人触发，契约 §7 B4）、
        // DeepDirect 4（需人确认对话填表载荷）、Kpi 4（防调度每日堆台账）、
        // Lc04 对账 1（同 Kpi，防调度堆台账）、
        // GatePrep 5（HUMAN_GATE）；
        // 只剩 GenerateExecutor 24（草稿待人审，日级 dedup 通知）+ LightDirectExecutor 14（纯确定性）。
        Set<String> expected = union(GenerateExecutor.CODES, LightDirectExecutor.CODES);
        assertThat(engine.scheduleWiredActionCodes())
            .containsExactlyInAnyOrderElementsOf(expected)
            .hasSize(38);
    }

    @Test
    void everyAiActionIsWiredOrExplicitlyExempt() {
        List<String> uncovered = ActionCatalog.ALL.stream()
            .filter(d -> !"HUMAN_GATE".equals(d.execMode()))
            .map(ActionDef::code)
            .filter(c -> !WIRED.contains(c) && !EXEMPT.contains(c))
            .toList();
        assertThat(uncovered).as("AI 档动作既未接线也未豁免（静默漏）").isEmpty();
    }

    @Test
    void exemptionRatchetOnlyShrinks() {
        // 棘轮基线：R232-W14 批次后豁免数 = 69 - 8 = 61；R236 批次接线 61 码 → 豁免清零；
        // 「算钱」层下线新增 LC04 一码豁免；2026-10-03 LC01/LC03 退役后目录由 69 → 67，
        // 实际接线 = 67 − 1（LC04）= 66。豁免集只减不增（棘轮）。
        assertThat(CATALOG_CODES).hasSize(67);
        assertThat(EXEMPT).hasSize(1);
        assertThat(WIRED).hasSize(66);
    }

    /**
     * R236 新增：前端 {@code ACTION_EXEC_MODE}（69 行静态映射）↔ 后端 {@code ActionCatalog} 的
     * {@code code → execMode} 逐码对账。
     *
     * <p>前端为何要存这份副本：{@code /stage-actions} 的 VO 不带 execMode（只有已产生 AI 任务时
     * {@code AiAgentTaskView} 才带），而节点徽标须在「尚无 AI 任务」时也能显示执行档位。代价是同一真值
     * 在两仓各存一份——后端改档位而前端不同步只会静默漂移（页面徽标显示错的档位，无任何报错），
     * 故在此机制化对账。跨仓定位复用 {@code StateMachineGuardRulesExportTest} 的同一约定
     * （{@code IPD_FE_SHARED_DIR} 系统属性 &gt; 环境变量 &gt; 默认路径），不另造第二套探测逻辑。
     * 前端仓不在本机 / CI 稀疏检出时跳过而非假绿。
     */
    @Test
    void frontEndExecModeMapMatchesActionCatalog() throws IOException {
        Path enums = locateFrontEndEnums();
        Assumptions.assumeTrue(enums != null,
            "未定位到前端仓 ipd-enums.ts（可用 -D" + FE_ENV_KEY + "= 指向检出），跳过跨仓对账");
        String content = Files.readString(enums, StandardCharsets.UTF_8);
        int start = content.indexOf("ACTION_EXEC_MODE");
        Assumptions.assumeTrue(start >= 0, "前端 ipd-enums.ts 内未见 ACTION_EXEC_MODE 常量，跳过跨仓对账");
        int end = content.indexOf("};", start);
        Map<String, String> front = new HashMap<>();
        Matcher m = FE_EXEC_MODE_PATTERN.matcher(
            content.substring(start, end < 0 ? content.length() : end));
        while (m.find()) {
            front.put(m.group(1), m.group(2));
        }
        Map<String, String> back = ActionCatalog.ALL.stream()
            .collect(Collectors.toMap(ActionDef::code, ActionDef::execMode));

        List<String> drift = new ArrayList<>();
        back.forEach((code, mode) -> {
            String actual = front.get(code);
            if (actual == null) {
                drift.add(code + " 前端缺失（后端=" + mode + "）");
            } else if (!actual.equals(mode)) {
                drift.add(code + " 后端=" + mode + " 前端=" + actual);
            }
        });
        front.keySet().stream()
            .filter(code -> !back.containsKey(code))
            .forEach(code -> drift.add(code + " 前端多出（后端目录无此码）"));
        Collections.sort(drift);
        assertThat(drift)
            .as("前端 ACTION_EXEC_MODE 须与后端 ActionCatalog 的 code→execMode 逐码全等，"
                + "否则节点徽标会显示错的执行档位且不报错")
            .isEmpty();
        assertThat(front).as("对账须真的解析到前端映射，空集等于假绿").hasSize(back.size());
    }

    /** 前端映射块内的一行：{@code C01: 'AI_GENERATE',}（一行可含多码，故用全局匹配而非按行解析）。 */
    private static final Pattern FE_EXEC_MODE_PATTERN =
        Pattern.compile("([A-Z][A-Z0-9]*):\\s*'(AI_DIRECT|AI_GENERATE|HUMAN_GATE)'");

    /** 前端仓 {@code _shared} 目录定位键，与 {@code StateMachineGuardRulesExportTest} 保持同一约定。 */
    private static final String FE_ENV_KEY = "IPD_FE_SHARED_DIR";
    private static final String FE_DEFAULT_DIR =
        "/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/_shared";

    /** 定位前端 {@code ipd-enums.ts}；不存在返回 null 让 {@link Assumptions} 跳过。 */
    private static Path locateFrontEndEnums() {
        String dir = System.getProperty(FE_ENV_KEY,
            System.getenv().getOrDefault(FE_ENV_KEY, FE_DEFAULT_DIR));
        Path p = Path.of(dir, "ipd-enums.ts");
        return Files.isRegularFile(p) ? p : null;
    }

    /** 从模块 basedir 逐级上溯定位种子文件（Surefire cwd = 模块目录，故需向上找 repo 根）。 */
    private static Path locateSeedSql() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/script/sql/update/20260927-ipd-node-agents.sql");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            if (Files.isDirectory(dir.resolve("docs/script/sql/update"))) {
                return null; // 目录在而文件不在 = 真缺失，返回 null 让 Assumptions 跳过而非误报路径
            }
        }
        return null;
    }
}
