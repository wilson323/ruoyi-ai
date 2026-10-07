package org.ruoyi.ipd.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.ActionDef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seed SQL 合同测试（Track A1）：解析 22 行小阶段 seed 与 69 行映射 seed，
 * 锁定主计划 §2.8 不变量 ①②③⑤（④运行时锁在 SubStageGateServiceTest）。
 * hermetic：直接读 docs/script/sql/update/ 下的 seed 文件，不连库——DDL 未 apply 也能跑绿。
 * 行格式正则与 §6.4.2/§6.4.3 的 INSERT 行逐字对齐；改 seed 行格式必须同步改本正则。
 */
@Tag("dev")
class SubStageSeedSqlContractTest {

    private static final Path REPO_ROOT = findGitRoot(Paths.get(System.getProperty("user.dir")));
    private static final Path SUB_STAGE_SEED = REPO_ROOT.resolve(
        "docs/script/sql/update/2026-09-28-ipd-sub-stage-seed.sql");
    private static final Path MAP_SEED = REPO_ROOT.resolve(
        "docs/script/sql/update/2026-09-28-ipd-action-skill-map-seed.sql");

    /** (id, 'code', 'name', 'stage', sort, 'is_gate', gate_code|NULL, 'skill_hint', 'is_resident', 'owner_role', 'remark') */
    private static final Pattern SUB_STAGE_ROW = Pattern.compile(
        "^\\((\\d+), '([^']*)', '([^']*)', '([^']*)', (\\d+), '([^']*)', (NULL|'[^']*'), '([^']*)', '([^']*)', '([^']*)', '([^']*)'\\)[,;]?$");
    /** (id, 'action_code', 'sub_stage_code', skill_names|NULL, sort, 'remark') */
    private static final Pattern MAP_ROW = Pattern.compile(
        "^\\((\\d+), '([^']*)', '([^']*)', (NULL|'[^']*'), (\\d+), '([^']*)'\\)[,;]?$");

    /** §2.8 不变量⑤：脏数据码词形上不得出现在任何小阶段映射（真库实查 A01/A02/A1/A2 共 42 行） */
    private static final Set<String> DIRTY_CODES = Set.of("A01", "A02", "A1", "A2");

    /**
     * 2026-10-03 已退役动作码：LC01（上市后销售与回款跟踪）/ LC03（上市后6个月终算）。
     *
     * <p>这两条已从 {@code ActionCatalog}（69 → 67）与 {@code GuideScriptCatalog} 删除，但
     * {@code 2026-09-28-ipd-action-skill-map-seed.sql} 是**已应用的迁移**，按本轮纪律禁止改写其内容
     * （改了会造成「新装库没有、老库还有」的分叉）。故映射 seed 仍保留这 2 行，由
     * {@code docs/script/sql/update/2026-10-03-ipd-retire-lc01-lc03-draft.sql}（待 owner 拍板、未 apply）
     * 负责 DELETE。本常量把这段「已知差异」显式登记，避免断言退化成「现状放行」。
     */
    private static final Set<String> RETIRED_ACTION_CODES = Set.of("LC01", "LC03");
    /**
     * §2.8 不变量③例外登记（主计划 §2.8 现文：Gate 行 = 含评审动作的小阶段，不要求 sort 最大；
     * LIFECYCLE-S2/G5 为业务事实例外——90 天复盘先于停产退出；G3 已回正落 DEV-S3（sort 最大，非例外））。
     * 任何新增例外必红。
     */
    private static final Set<String> GATE_SORT_MAX_EXEMPT = Set.of("LIFECYCLE-S2");
    private static final Set<String> STAGES =
        Set.of("CONCEPT", "PLAN", "DEV", "VALID", "LAUNCH", "LIFECYCLE", "KPI");

    record SubStageRow(long id, String code, String name, String stageCode, int sortOrder,
                       String isGate, String gateCode, String skillHint, String isResident,
                       String ownerRole, String remark) { }

    record MapRow(long id, String actionCode, String subStageCode, String skillNames,
                  int sortOrder, String remark) { }

    // ---------------------------------------------------------------- 回归一：小阶段 seed 形状

    @Test
    @DisplayName("不变量②：22 小阶段码唯一、七大阶段齐全、各阶段 sort 连续从 1（KPI-S1=99 常驻豁免）")
    void subStageSortContiguousPerStage() throws IOException {
        List<SubStageRow> rows = parseSubStages();
        assertThat(rows).hasSize(22);
        Set<String> codes = new HashSet<>();
        for (SubStageRow r : rows) {
            assertThat(codes.add(r.code())).as("小阶段码唯一: %s", r.code()).isTrue();
            assertThat(STAGES).contains(r.stageCode());
            assertThat(Set.of("0", "1")).contains(r.isGate());
            assertThat(Set.of("0", "1")).contains(r.isResident());
        }
        assertThat(rows.stream().map(SubStageRow::stageCode).distinct().toList())
            .containsExactlyInAnyOrderElementsOf(STAGES);

        Map<String, List<SubStageRow>> byStage = new LinkedHashMap<>();
        for (SubStageRow r : rows) {
            byStage.computeIfAbsent(r.stageCode(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<SubStageRow>> e : byStage.entrySet()) {
            if ("KPI".equals(e.getKey())) {
                assertThat(e.getValue()).hasSize(1);
                SubStageRow kpi = e.getValue().get(0);
                assertThat(kpi.code()).isEqualTo("KPI-S1");
                assertThat(kpi.sortOrder()).isEqualTo(99);
                assertThat(kpi.isResident()).isEqualTo("1");
                continue;
            }
            List<Integer> sorts = e.getValue().stream().map(SubStageRow::sortOrder).sorted().toList();
            assertThat(sorts).as("阶段 %s sort 连续从 1", e.getKey())
                .containsExactlyElementsOf(intRange(1, e.getValue().size()));
        }
    }

    @Test
    @DisplayName("不变量③：Gate 行恰好 G1..G5 且为该阶段 sort 最大者（例外集显式登记，新增例外必红）")
    void gateRowsCarryStageSortMax() throws IOException {
        List<SubStageRow> rows = parseSubStages();
        Map<String, Integer> maxSort = new HashMap<>();
        for (SubStageRow r : rows) {
            maxSort.merge(r.stageCode(), r.sortOrder(), Math::max);
        }
        List<SubStageRow> gates = rows.stream().filter(r -> r.gateCode() != null).toList();
        assertThat(gates).hasSize(5);
        assertThat(gates.stream().map(SubStageRow::gateCode))
            .containsExactlyInAnyOrder("G1", "G2", "G3", "G4", "G5");
        for (SubStageRow g : gates) {
            assertThat(g.isGate()).as("Gate 行 %s is_gate=1", g.code()).isEqualTo("1");
            if (GATE_SORT_MAX_EXEMPT.contains(g.code())) {
                assertThat(g.sortOrder()).as("例外 %s 非 sort 最大（主计划 §2.8 业务事实例外）", g.code())
                    .isLessThan(maxSort.get(g.stageCode()));
                continue;
            }
            assertThat(g.sortOrder()).as("Gate 行 %s 必为阶段 sort 最大", g.code())
                .isEqualTo(maxSort.get(g.stageCode()));
        }
        assertThat(rows.stream().filter(r -> r.gateCode() == null))
            .allMatch(r -> "0".equals(r.isGate()));
    }

    // ---------------------------------------------------------------- 回归二：映射 seed 形状

    @Test
    @DisplayName("不变量①⑤：映射行 = ActionCatalog 全集 ∪ 已退役码（LC01/LC03 待清理脚本 DELETE）、动作码唯一、零脏数据")
    void mapCoversAllCatalogCodesWithoutDirty() throws IOException {
        List<MapRow> rows = parseMaps();
        Set<String> mapCodes = new HashSet<>();
        for (MapRow r : rows) {
            assertThat(mapCodes.add(r.actionCode())).as("动作码唯一: %s", r.actionCode()).isTrue();
            assertThat(DIRTY_CODES).as("脏数据码禁入映射: %s", r.actionCode())
                .doesNotContain(r.actionCode());
        }
        Set<String> catalogCodes = ActionCatalog.ALL.stream().map(ActionDef::code).collect(java.util.stream.Collectors.toSet());
        // 既不多（新增动作未进 seed / 多建行）也不少（目录动作在 seed 缺行）
        assertThat(mapCodes).containsExactlyInAnyOrderElementsOf(catalogCodes);
        assertThat(rows).hasSize(catalogCodes.size());
        // 2026-10-07 ③刀清污：LC01/LC03 已于 2026-10-03 退役（回款台账/奖金池两域拆除），
        // 映射种子里的两行孤儿引用已删除。契约随之反转——由「预期种子里含退役码」
        // 改为「**退役码不得再出现**」，否则重跑旧种子会静默复活。
        assertThat(mapCodes).doesNotContainAnyElementsOf(RETIRED_ACTION_CODES);
    }

    @Test
    @DisplayName("映射参照完整 + 小阶段内 sort 连续从 1 + skill_names 仅 NULL 或 JSON 数组文本")
    void mapReferentialAndShape() throws IOException {
        List<SubStageRow> subs = parseSubStages();
        List<MapRow> rows = parseMaps();
        Set<String> subCodes = subs.stream().map(SubStageRow::code).collect(java.util.stream.Collectors.toSet());
        Map<String, List<MapRow>> bySub = new LinkedHashMap<>();
        for (MapRow r : rows) {
            assertThat(subCodes).as("映射指向已登记小阶段: %s", r.subStageCode()).contains(r.subStageCode());
            if (r.skillNames() != null) {
                assertThat(r.skillNames()).as("skill_names 仅 NULL 或 JSON 数组").startsWith("[");
            }
            bySub.computeIfAbsent(r.subStageCode(), k -> new ArrayList<>()).add(r);
        }
        for (Map.Entry<String, List<MapRow>> e : bySub.entrySet()) {
            List<Integer> sorts = e.getValue().stream().map(MapRow::sortOrder).sorted().toList();
            assertThat(sorts).as("小阶段 %s 动作 sort 连续从 1", e.getKey())
                .containsExactlyElementsOf(intRange(1, e.getValue().size()));
        }
    }

    // ---------------------------------------------------------------- 解析器（格式即合同）

    private static List<SubStageRow> parseSubStages() throws IOException {
        List<SubStageRow> out = new ArrayList<>();
        for (String line : valueLines(SUB_STAGE_SEED)) {
            Matcher m = SUB_STAGE_ROW.matcher(line);
            assertThat(m.matches()).as("小阶段 seed 行与合同正则一致: %s", line).isTrue();
            out.add(new SubStageRow(Long.parseLong(m.group(1)), m.group(2), m.group(3), m.group(4),
                Integer.parseInt(m.group(5)), m.group(6),
                "NULL".equals(m.group(7)) ? null : unquote(m.group(7)),
                m.group(8), m.group(9), m.group(10), m.group(11)));
        }
        return out;
    }

    private static List<MapRow> parseMaps() throws IOException {
        List<MapRow> out = new ArrayList<>();
        for (String line : valueLines(MAP_SEED)) {
            Matcher m = MAP_ROW.matcher(line);
            assertThat(m.matches()).as("映射 seed 行与合同正则一致: %s", line).isTrue();
            out.add(new MapRow(Long.parseLong(m.group(1)), m.group(2), m.group(3),
                "NULL".equals(m.group(4)) ? null : unquote(m.group(4)),
                Integer.parseInt(m.group(5)), m.group(6)));
        }
        return out;
    }

    /** 取 INSERT ... VALUES 之后的数据行（跳过注释/空行/语句头）；无法识别的行直接断言失败。 */
    private static List<String> valueLines(Path file) throws IOException {
        assertThat(Files.exists(file)).as("seed 文件存在: %s", file.toAbsolutePath()).isTrue();
        List<String> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("--") || line.startsWith("INSERT") || line.startsWith("/*")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    private static String unquote(String s) {
        return s.substring(1, s.length() - 1);
    }

    private static List<Integer> intRange(int fromInclusive, int toInclusive) {
        List<Integer> out = new ArrayList<>();
        for (int i = fromInclusive; i <= toInclusive; i++) {
            out.add(i);
        }
        return out;
    }

    /** 自适应定位 git root（同 PermissionAdviceCoverageTest 模式），兼容 surefire 与 IDE 单跑 working dir */
    private static Path findGitRoot(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return current;
            }
            current = current.getParent();
        }
        return start.toAbsolutePath().normalize();
    }
}
