package org.ruoyi.ipd.workbench;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.service.WorkbenchService;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * taskType 数据源契约登记一致性测试（B 批次机制 1，2026-09-08 立）。
 *
 * <p>防「写入侧与查询侧语义不对偶」病根的登记面漂移：
 * <ul>
 *   <li>契约登记文件（docs/ipd-系统说明/workbench-tasktype-契约登记.yaml）的现役 16 类键集
 *       必须与 {@link WorkbenchService#ALL_TASK_TYPES}（spec 页03:165 权威枚举）完全一致</li>
 *   <li>已实现类的五项必需契约字段（aggregator/table/write_timing/anchor/pending_expr）必须登记齐</li>
 *   <li>已实现类必须有聚合器源文件，且源文件内含 {@code return "<taskType>"} 字面量</li>
 *   <li>关键状态常量（包私有防漂移常量）与登记文本交联——常量值必须出现在登记文件中</li>
 *   <li>未实现类必须显式登记 {@code table} 字段（无数据源写 null）与非空 {@code notes}，
 *       表头「未实现 N 类」的 N 必须等于实际条数</li>
 *   <li>登记的表名必须与真实库一致：有表名的必须真实存在；登记为待建（{@code table: null}）的
 *       必须点名 {@code pending_tables}，且这些表必须确实不存在——否则「表其实早已建成却仍登记为待建」
 *       这类漂移不会被任何断言发现（2026-10-03 实测：表头写 8 而实际 7、4 类「待建」对应的 5 张表
 *       早在 2026-09-09 就已 apply 落库，两条都无人报红）</li>
 * </ul>
 *
 * <p>登记文件刻意用零依赖的轻量行扫描解析（顶层键 + 两格缩进字段），不引 snakeyaml——
 * 本仓 IPD 模块 pom 未声明该依赖，不为测试新增编译期依赖。新增一类任务 = 先登记契约再写聚合器，
 * 本测试即门禁。
 */
@Tag("dev")
class WorkbenchTaskContractDriftTest {

    private static final String YAML_REL = "../../docs/ipd-系统说明/workbench-tasktype-契约登记.yaml";
    private static final String YAML_REL_FROM_ROOT = "docs/ipd-系统说明/workbench-tasktype-契约登记.yaml";
    private static final String YAML_ABS = "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/workbench-tasktype-契约登记.yaml";
    private static final String AGGREGATOR_DIR = "src/main/java/org/ruoyi/ipd/workbench/";
    /**
     * 真实库表名基准（仓内快照，mysqldump 产物）。
     *
     * <p>登记「某张表在不在」必须有一把可核对的尺子；本文件即离线的尺子——2026-10-03 实测它与
     * 现役库 ipd_dev 逐表一致（166 张表名零差）。表改名 / 新增 / 删除后须重新生成，
     * 否则本测试会拿旧基准放行：它是「登记 ↔ 真库」这条链上唯一可离线复现的一环。
     */
    private static final String SCHEMA_BASELINE_REL = "../../docs/ipd-系统说明/schema-baseline-20261003.sql";
    private static final String SCHEMA_BASELINE_REL_FROM_ROOT = "docs/ipd-系统说明/schema-baseline-20261003.sql";
    private static final String SCHEMA_BASELINE_ABS = "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/schema-baseline-20261003.sql";

    private static String yamlText;
    private static Map<String, Map<String, String>> contract; // taskType -> field -> value
    private static Set<String> liveSchemaTables; // 真实库表名（基准快照解析结果）

    @BeforeAll
    static void loadContract() throws IOException {
        Path path = Path.of(YAML_REL);
        if (!Files.isRegularFile(path)) {
            path = Path.of(YAML_REL_FROM_ROOT);
        }
        if (!Files.isRegularFile(path)) {
            path = Path.of(YAML_ABS);
        }
        assertThat(Files.isRegularFile(path))
            .as("契约登记文件必须存在（B 批次机制 1）: 尝试过 %s / %s / %s", YAML_REL, YAML_REL_FROM_ROOT, YAML_ABS)
            .isTrue();
        yamlText = Files.readString(path);
        contract = parseTopLevelEntries(yamlText);

        Path baseline = Path.of(SCHEMA_BASELINE_REL);
        if (!Files.isRegularFile(baseline)) {
            baseline = Path.of(SCHEMA_BASELINE_REL_FROM_ROOT);
        }
        if (!Files.isRegularFile(baseline)) {
            baseline = Path.of(SCHEMA_BASELINE_ABS);
        }
        assertThat(Files.isRegularFile(baseline))
            .as("真实库表名基准必须存在（登记↔真库核对的前提）: 尝试过 %s / %s / %s",
                SCHEMA_BASELINE_REL, SCHEMA_BASELINE_REL_FROM_ROOT, SCHEMA_BASELINE_ABS)
            .isTrue();
        liveSchemaTables = parseCreatedTables(Files.readString(baseline));
        assertThat(liveSchemaTables)
            .as("基准必须解析出表名（集合为空=解析器失效，不是「库里没有表」）")
            .isNotEmpty();
    }

    /** 轻量行扫描：顶层键（无缩进 + 冒号结尾）与两格缩进字段。注释行/分隔线跳过。 */
    private static Map<String, Map<String, String>> parseTopLevelEntries(String text) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        String current = null;
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.replaceFirst("#.*$", "").trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!rawLine.startsWith(" ") && line.endsWith(":")) {
                current = line.substring(0, line.length() - 1);
                out.putIfAbsent(current, new LinkedHashMap<>());
            } else if (current != null && rawLine.startsWith("  ") && !rawLine.startsWith("   ")) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    out.get(current).put(line.substring(0, colon).trim(),
                        line.substring(colon + 1).trim());
                }
            }
        }
        out.remove("meta");
        return out;
    }

    /** 从 mysqldump 风格基线里抽 CREATE TABLE 的表名。 */
    private static Set<String> parseCreatedTables(String sql) {
        Set<String> out = new LinkedHashSet<>();
        for (String rawLine : sql.split("\n", -1)) {
            String line = rawLine.trim();
            if (!line.startsWith("CREATE TABLE ")) {
                continue;
            }
            int open = line.indexOf('`');
            int close = open < 0 ? -1 : line.indexOf('`', open + 1);
            if (close > open) {
                out.add(line.substring(open + 1, close));
            }
        }
        return out;
    }

    /**
     * 把登记值拆成表名列表：去引号，按 ∪ 拆（仓库既有的「多数据源」写法），
     * null 与空值都视为「未登记表」。
     */
    private static List<String> tableNames(String rawValue) {
        if (rawValue == null) {
            return List.of();
        }
        String value = rawValue.replace("\"", "").trim();
        if (value.isEmpty() || "null".equals(value)) {
            return List.of();
        }
        return Arrays.stream(value.split("∪"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }

    @Test
    @DisplayName("契约键集 == WorkbenchService.ALL_TASK_TYPES（奖金池退役后现役16类，双向零差）")
    void contractKeysMatchAllTaskTypes() throws Exception {
        Field field = WorkbenchService.class.getDeclaredField("ALL_TASK_TYPES");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> all = (List<String>) field.get(null);
        assertThat(all).hasSize(16).doesNotContain("bonus_lock");
        Set<String> codeSide = Set.copyOf(all);
        Set<String> yamlSide = contract.keySet();
        assertThat(yamlSide).as("登记文件多出的键").containsExactlyInAnyOrderElementsOf(codeSide);
        assertThat(codeSide).as("代码枚举多出的键").containsExactlyInAnyOrderElementsOf(yamlSide);
    }

    @Test
    @DisplayName("已实现恰 9 类；每类五项必需契约字段登记齐")
    void implementedEntriesHaveRequiredFields() {
        Map<String, Map<String, String>> implemented = contract.entrySet().stream()
            .filter(e -> "true".equals(e.getValue().get("implemented")))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(implemented).as("B 批次立卡时已实现 9 类").hasSize(9);
        for (Map.Entry<String, Map<String, String>> e : implemented.entrySet()) {
            for (String required : List.of("aggregator", "table", "write_timing", "anchor", "pending_expr")) {
                assertThat(e.getValue().get(required))
                    .as("taskType=%s 缺契约字段 %s（先补登记）", e.getKey(), required)
                    .isNotNull()
                    .isNotEmpty();
            }
            assertThat(List.of("ALLOCATE", "SUBMIT"))
                .as("taskType=%s write_timing 值域 ALLOCATE|SUBMIT", e.getKey())
                .contains(e.getValue().get("write_timing"));
        }
    }

    @Test
    @DisplayName("已实现类的聚合器源文件存在且含 return \"<taskType>\" 字面量")
    void implementedAggregatorsExistWithLiteral() throws IOException {
        for (Map.Entry<String, Map<String, String>> e : contract.entrySet()) {
            if (!"true".equals(e.getValue().get("implemented"))) {
                continue;
            }
            Path src = Path.of(AGGREGATOR_DIR + e.getValue().get("aggregator") + ".java");
            if (!Files.isRegularFile(src)) {
                src = Path.of("/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/" + AGGREGATOR_DIR
                    + e.getValue().get("aggregator") + ".java");
            }
            assertThat(Files.isRegularFile(src))
                .as("taskType=%s 登记聚合器源文件必须存在: %s", e.getKey(), src)
                .isTrue();
            assertThat(Files.readString(src))
                .as("taskType=%s 聚合器必须返回登记的字面量", e.getKey())
                .contains("return \"" + e.getKey() + "\"");
        }
    }

    @Test
    @DisplayName("关键状态常量与登记文本交联（防常量值与登记漂移）")
    void keyConstantsAppearInContract() {
        // 同包包私有防漂移常量（各聚合器自带，与配套 Service 同值）——值必须出现在登记文本中
        Map<String, String> constants = Map.of(
            "KeyGateAggregator.GATE_PENDING", KeyGateAggregator.GATE_PENDING,
            "KeyGateArbitrationAggregator.GATE_REJECTED", KeyGateArbitrationAggregator.GATE_REJECTED,
            "KeyGateArbitrationAggregator.ST_ACTIVE_PROJECT", KeyGateArbitrationAggregator.ST_ACTIVE_PROJECT,
            "KpiFillAggregator.KF_EDITING", KpiFillAggregator.KF_EDITING,
            "HandoverAggregator.HS_DRAFT", HandoverAggregator.HS_DRAFT,
            "HandoverAggregator.HS_CONFIRMED", HandoverAggregator.HS_CONFIRMED,
            "CloseoutAggregator.TYPE_SELF", CloseoutAggregator.TYPE_SELF,
            "CloseoutAggregator.TYPE_LEADER", CloseoutAggregator.TYPE_LEADER);
        for (Map.Entry<String, String> e : constants.entrySet()) {
            assertThat(e.getValue()).as("%s 值", e.getKey()).isNotEmpty();
            assertThat(yamlText)
                .as("%s=%s 必须出现在契约登记文本中（改常量须同步登记）", e.getKey(), e.getValue())
                .contains(e.getValue());
        }
    }

    @Test
    @DisplayName("未实现恰 7 类；每条须显式登记 table 字段与非空 notes，表头计数与实际条数一致")
    void unimplementedEntriesHaveRequiredFields() {
        Map<String, Map<String, String>> unimplemented = contract.entrySet().stream()
            .filter(e -> "false".equals(e.getValue().get("implemented")))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(unimplemented).as("未实现槽位数（表头「未实现 N 类」的 N）").hasSize(7);
        assertThat(yamlText)
            .as("表头计数必须等于实际未实现条数（2026-10-03：表头写 8 而实际 7，此前无断言可发现）")
            .contains("未实现 " + unimplemented.size() + " 类");
        for (Map.Entry<String, Map<String, String>> e : unimplemented.entrySet()) {
            assertThat(e.getValue())
                .as("taskType=%s 未实现槽位必须显式写 table 字段（无数据源写 null）；"
                    + "字段缺失会让「表已建却登记成待建」无从核对", e.getKey())
                .containsKey("table");
            assertThat(e.getValue().get("notes"))
                .as("taskType=%s 未实现槽位必须登记原因（notes）", e.getKey())
                .isNotNull()
                .isNotEmpty();
        }
    }

    @Test
    @DisplayName("登记的表名必须真实存在；登记为待建的必须点名 pending_tables 且这些表确实不存在")
    void declaredTablesMatchLiveSchema() {
        for (Map.Entry<String, Map<String, String>> e : contract.entrySet()) {
            String taskType = e.getKey();
            List<String> declared = tableNames(e.getValue().get("table"));
            if (!declared.isEmpty()) {
                for (String table : declared) {
                    assertThat(liveSchemaTables)
                        .as("taskType=%s 登记的 table=%s 必须存在于真实库（基准 schema-baseline-20261003.sql）",
                            taskType, table)
                        .contains(table);
                }
                continue;
            }
            List<String> pending = tableNames(e.getValue().get("pending_tables"));
            assertThat(pending)
                .as("taskType=%s 登记 table=%s，等于断言「数据源表尚不存在」。必须用 pending_tables 点名"
                    + "候选表，否则「表其实早已建成」这类漂移不会被任何断言发现"
                    + "（2026-10-03 实测：4 条如此，对应 5 张表早已 apply 落库）", taskType,
                    e.getValue().get("table"))
                .isNotEmpty();
            for (String table : pending) {
                assertThat(liveSchemaTables)
                    .as("taskType=%s 的 pending_tables=%s 已存在于真实库——请把 table 改为该表并删除待建标注",
                        taskType, table)
                    .doesNotContain(table);
            }
        }
    }
}
