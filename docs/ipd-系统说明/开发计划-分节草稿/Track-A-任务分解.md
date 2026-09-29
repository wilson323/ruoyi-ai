# Track A 后端任务分解 — 分节草稿

> **状态**：草稿（供主计划 Track A 合入前评审）　**产出日期**：2026-09-28
> **范围**：小阶段查询接口 + AG-UI 工具下发 + 阻断门禁的后端实现步骤。**本稿只产出文档，不落任何 Java/SQL 文件、不执行任何 DDL、不改任何既有文件**。
> **依赖**：`§6-数据模型与DDL.md`（表结构/seed 已定稿）；主计划 §0.1 事实基线、§2.1-2.8（22 小阶段 + 5 不变量）；`ActionCatalog.java`（69 动作编译期 SSOT）。
> **纪律**：所有 SQL 标注「**待 owner apply**」；新测试一律 `@Tag("dev")`；每步给真实代码（无占位符/TBD/TODO）。

---

## A0. 全局规约（每个 Task 都适用）

### A0.1 构建与复核命令（错峰、单模块、无 `-am`、无 `clean`）

```bash
export PATH="$HOME/tools/maven/bin:$PATH"
export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"
cd /Users/mac/Documents/ruoyi-ai
# 复核模板（每个 Task 末尾只换 -Dtest= 类名）：
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=XxxTest test
```

- **禁止** `-am` / `clean` / `install`（本仓假红陷阱：多会话并发构建污染 `target/`；错峰单会话执行）。
- **禁止改 pom**；禁止 git 操作。

### A0.2 测试规约

- 新测试类必须 `@Tag("dev")`——根 pom surefire `<groups>${profiles.active}</groups>`，无 tag = 静默跳过**假绿**。
- mock 夹具必须是真库写入路径**可产生**的数据组合（深度/阻断/状态/BioCV 标记与 `ActionCatalog` 一致；`is_blocking`/`is_gate`/`is_resident` 只 '0'/'1'；轻管动作不造 `DELAYED`；动作名称取 `ActionCatalog.byCode(code).name()` 真名）。
- 测试风格对齐本模块金样板：JUnit5 + Mockito（`mock()` 手工装配 或 `@ExtendWith(MockitoExtension.class)` + `@Mock`/`@InjectMocks`）+ AssertJ + `@DisplayName`。

### A0.3 接口契约（不可用框架 code200/msg）

- 响应一律 `ApiV1Response<T>` 包络：`code=0` 成功（`ApiV1ErrorCode.OK`）、`message`（="ok"）、`data`、`timestamp`、`traceId`；**不得**用基线 `R<T>`（code=200/msg）。
- 业务异常 `IpdBusinessException(ApiV1ErrorCode, String)`；Controller 位于 `org.ruoyi.ipd.controller` 包内由 `IpdServiceExceptionAdvice`（basePackages 收口）自动转包络。
- **字符串 ID**：所有 `Long` id 输出前 `String.valueOf(...)`（P0-4.1 大整数保真；`ApiV1Response` 全局 BigNumber 序列化是兜底，VO 层显式字符串是本 Track 契约）。
- 鉴权：`@SaCheckPermission(value = IpdPermissionCode.*, type = IpdAuthSession.LOGIN_TYPE)` + 方法内 `ipdPermission.requireInternal()`；**只复用既有权限码**（读=`OPERATION_STAGE_ACTION`，写=`OPERATION_STAGE_ACTION_EXECUTE`）——`IpdPermissionCode` 是既有文件，本 Track 不改。

### A0.4 文件纪律

- 本 Track **新建**文件清单（全部在 `ruoyi-modules/ruoyi-ipd` 内，另加 3 个 SQL）见各 Task 的 Files 节。
- **唯一允许修改的既有文件**：`ruoyi-admin/src/main/resources/application.yml`——仅在 `tenant.excludes` 列表尾部**追加**两行（Task A1.2，带注释）。这是任务书多租户规约明确授权的登记动作，除此之外不得动任何既有文件。
- **禁止对真库执行写操作**；DDL/seed 由 owner 人工 apply（`docs/script/sql/update/**` 先例：无 Flyway/Liquibase）。

### A0.5 依赖顺序

A1（DDL+seed+租户登记+seed 合同测试）→ A2（实体/Mapper/Service）→ A3（查询接口）→ A4（AG-UI 工具下发，消费 A3 的 VO 与 Controller）→ A5（阻断门禁，消费 A2 Service + A3 Controller 追加 advance 端点）。A1 的 seed 合同测试**不依赖 DB**（hermetic 解析 SQL 文件），可在 apply 前跑绿。

---

## Task A1 — 建表 DDL + seed + tenant.excludes 登记 + seed 合同测试

**Goal**：两张元数据表的 DDL/seed 落盘（待 owner apply）、多租户排除登记、以及锁住 §2.8 不变量①②③⑤的 hermetic 合同测试。

**Files**（新建 3 SQL + 1 测试类；修改 1 处 YAML 列表）：

| 路径 | 动作 |
|---|---|
| `docs/script/sql/update/2026-09-28-ipd-sub-stage-skill-map-ddl.sql` | 新建（**待 owner apply**） |
| `docs/script/sql/update/2026-09-28-ipd-sub-stage-seed.sql` | 新建（**待 owner apply**） |
| `docs/script/sql/update/2026-09-28-ipd-action-skill-map-seed.sql` | 新建（**待 owner apply**） |
| `ruoyi-admin/src/main/resources/application.yml` | **追加** `tenant.excludes` 两行（A1.2） |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/seed/SubStageSeedSqlContractTest.java` | 新建 |

**Interfaces**：无 Java 产出；SQL 契约 = §6.2/§6.4 已定稿内容 + A1.3 测试锁定的行格式正则。

### A1.1 三个 SQL 文件（内容组装，逐行引用 §6，不复制第二份口径）

1. `2026-09-28-ipd-sub-stage-skill-map-ddl.sql`：文件头（见下）+ **正文 = §6.2.1 的 `ipd_sub_stage` CREATE TABLE 与 §6.2.2 的 `ipd_action_skill_map` CREATE TABLE 两个代码块逐字合并**（两表一份文件）。
2. `2026-09-28-ipd-sub-stage-seed.sql`：文件头 + **正文 = §6.4.2 的 22 行 INSERT 逐字**。
3. `2026-09-28-ipd-action-skill-map-seed.sql`：文件头 + **正文 = §6.4.3 的 69 行 INSERT 逐字**（`skill_names` 一律 NULL，`remark='待 §3 定稿后补齐 skill_names'`）。

三个文件统用此头部（对齐 `kb-partA-ddl-draft-20260928.sql` 头部纪律）：

```sql
-- =====================================================================
-- IPD 六阶段小阶段化（Track A1，2026-09-28）
-- 状态：DO NOT APPLY — 待 owner apply（AI 产出 SQL、owner 人工执行；
--       DDL 未 apply 前严禁启动带实体映射的写路径，禁跑 seed）
-- 依据：docs/ipd-系统说明/开发计划-分节草稿/§6-数据模型与DDL.md §6.2/§6.4
-- 语法：MySQL 8.0（真库 8.0.46）；幂等：CREATE TABLE IF NOT EXISTS / INSERT 带显式 id
-- =====================================================================
```

**行格式红线**：seed 行格式被 A1.3 合同测试正则逐行锁定，改行格式必须同步改测试正则（防漂移单向锁）。

### A1.2 `tenant.excludes` 登记（唯一允许的既有文件修改）

在 `ruoyi-admin/src/main/resources/application.yml` 的 **`tenant.excludes`** 列表**尾部**追加（与 `saved_items` 等超前登记先例同款——表未 apply 不报错，apply 后即受保护）：

```yaml
    # Track A1（2026-09-28）：IPD 小阶段目录与动作技能映射——含 tenant_id 默认 000000 但单企业私有部署语义，
    # 未登记则租户拦截器追加 WHERE tenant_id=?，AG-UI/定时任务等无租户上下文路径读空（t_workflow_checkpoint 前车之鉴）
    - ipd_sub_stage
    - ipd_action_skill_map
```

两表都必须登记（§6.2.1/§6.2.2 理由）；键名引用 `tenant.excludes`（行号会漂，不引用行号）。

### A1.3 `SubStageSeedSqlContractTest`（hermetic，无需 DB，锁 §2.8 不变量①②③⑤）

```java
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
    /** §2.8 不变量③例外登记（§2.3 DEV-S2/G3、§2.6 LIFECYCLE-S2/G5 与「sort 最大」自相矛盾，待 owner 拍板） */
    private static final Set<String> GATE_SORT_MAX_EXEMPT = Set.of("DEV-S2", "LIFECYCLE-S2");
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
                assertThat(g.sortOrder()).as("例外 %s 非 sort 最大（待 owner 拍板）", g.code())
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
    @DisplayName("不变量①⑤：69 映射行 = ActionCatalog 全集、动作码唯一、零脏数据")
    void mapCoversAllCatalogCodesWithoutDirty() throws IOException {
        List<MapRow> rows = parseMaps();
        assertThat(rows).hasSize(69);
        Set<String> mapCodes = new HashSet<>();
        for (MapRow r : rows) {
            assertThat(mapCodes.add(r.actionCode())).as("动作码唯一: %s", r.actionCode()).isTrue();
            assertThat(DIRTY_CODES).as("脏数据码禁入映射: %s", r.actionCode())
                .doesNotContain(r.actionCode());
        }
        Set<String> catalogCodes = ActionCatalog.ALL.stream().map(ActionDef::code).collect(java.util.stream.Collectors.toSet());
        assertThat(mapCodes).containsExactlyInAnyOrderElementsOf(catalogCodes);
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
```

**验证**（A1.1-A1.3 完成后，单模块单测，无需 DB）：

```bash
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=SubStageSeedSqlContractTest test
```

**失败路径**：
- 测试红在「seed 行与合同正则一致」→ 行格式漂移，回看 §6.4.2/§6.4.3 是否被改写。
- 测试红在 sort 连续/Gate sort 最大 → §2.8 不变量②③被破坏；若源于 §2.3/§2.6 原有矛盾，只能动 `GATE_SORT_MAX_EXEMPT`（须 owner 拍板，见 §6.5）。
- owner apply 时报 `CREATE command denied` → 按 `2026-09-09-ipd-grant-c-batch-4-dml.sql` 先例补表级 GRANT 登记件（同样**待 owner apply**）。
- owner apply 后 6.4.4 只读核验行数不等于 22/69 → seed 被部分执行，`DELETE` 后重灌（硬删口径，映射行不走软删）。

**回滚**：`DROP TABLE IF EXISTS ipd_sub_stage; DROP TABLE IF EXISTS ipd_action_skill_map;`（待 owner apply）+ 撤掉 A1.2 的 YAML 两行 + 删 3 个 SQL 与测试类。业务零影响（两表尚无消费方）。

**DONE 定义**：3 个 SQL 文件落盘带「待 owner apply」头部；YAML 追加两行；合同测试单模块跑绿。

---

## Task A2 — 实体 + Mapper + Service（MyBatis-Plus，对齐 `StageActionService` 金样板）

**Goal**：`ipd_sub_stage` / `ipd_action_skill_map` 两表的只读访问层（应用侧**不开写端点**，目录唯一写者 = owner DDL seed）。

**Files**（全部新建）：

| 路径（相对 `ruoyi-modules/ruoyi-ipd`） | 内容 |
|---|---|
| `src/main/java/org/ruoyi/ipd/domain/IpdSubStage.java` | 实体 |
| `src/main/java/org/ruoyi/ipd/domain/IpdActionSkillMap.java` | 实体 |
| `src/main/java/org/ruoyi/ipd/mapper/IpdSubStageMapper.java` | Mapper |
| `src/main/java/org/ruoyi/ipd/mapper/IpdActionSkillMapMapper.java` | Mapper |
| `src/main/java/org/ruoyi/ipd/service/IpdSubStageService.java` | 目录服务 |
| `src/main/java/org/ruoyi/ipd/service/IpdActionSkillMapService.java` | 映射服务 |
| `src/test/java/org/ruoyi/ipd/service/IpdSubStageServiceTest.java` | 测试 |
| `src/test/java/org/ruoyi/ipd/service/IpdActionSkillMapServiceTest.java` | 测试 |

**Interfaces**：
- 消费（既有）：`BaseEntity`、`BaseMapperPlus`、`ApiV1ErrorCode`、`IpdBusinessException`。
- 产出：`IpdSubStageService.listAll(): List<IpdSubStage>`（大阶段序 + 阶段内 sort 序）、`getByCode(String): IpdSubStage`（未命中抛 50001）、`stageRank(String): int`（包级可见排序权重）；`IpdActionSkillMapService.listAll(): List<IpdActionSkillMap>`（subStageCode+sort 序）、`listBySubStage(String): List<IpdActionSkillMap>`、`static parseSkillNames(String): List<String>`（NULL/空→空列表，非法 JSON 抛 90001 fail-loud）。

### A2.1 `IpdSubStage.java`

```java
package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * IPD 小阶段目录（22 行元数据，主计划 §2 SSOT 落库）。
 * <p>只读目录：唯一写者 = owner DDL seed（docs/script/sql/update/2026-09-28-ipd-sub-stage-skill-map-ddl.sql，
 * 待 owner apply），应用侧不开写端点（防多写者）。字段口径见 §6-数据模型与DDL.md §6.2.1。
 * <p>tenant_id 列不在实体映射（表已登记 tenant.excludes，插入走 DDL 默认 '000000'；与 StageAction 同款）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName(value = "ipd_sub_stage", autoResultMap = true)
public class IpdSubStage extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 小阶段编码（CONCEPT-S1..KPI-S1，全局唯一，uk_sub_stage_code） */
    private String code;
    private String name;
    /** 所属大阶段 CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI（KPI=常驻跨阶段伪阶段） */
    private String stageCode;
    /** 阶段内排序（六阶段从 1 连续；KPI-S1=99 常驻豁免） */
    private Integer sortOrder;
    /** 是否承载大阶段 Gate（1=是） */
    private String isGate;
    /** Gate 编码 G1..G5（is_gate=1 必填，全局唯一） */
    private String gateCode;
    /** pm-skills 插件级引导提示（skill/command 级待 §3 定稿后 UPDATE） */
    private String skillHint;
    /** 常驻小阶段（1=跨阶段 KPI 归集，不参与顺序推进门禁） */
    private String isResident;
    /** 主导角色 MARKET_PM|RD_PM|BOTH|GROUP_LEADER */
    private String ownerRole;
    private String remark;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
```

### A2.2 `IpdActionSkillMap.java`

```java
package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;
import org.ruoyi.common.mybatis.core.domain.BaseEntity;

/**
 * IPD 动作 × 小阶段 × pm-skill 映射（69 行元数据，不变量①：action_code 唯一归属）。
 * <p>只读映射：唯一写者 = owner DDL seed（待 owner apply）。故意不存动作名称——
 * 名称 SSOT 在 ActionCatalog（编译期）与 stage_actions.action_name（实例快照），对名称漂移免疫（§6.2.3）。
 * <p>skill_names 为 JSON 数组文本（存储列 json 强制合法）；NULL = §3 未定稿。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName(value = "ipd_action_skill_map", autoResultMap = true)
public class IpdActionSkillMap extends BaseEntity {

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    /** 动作编码（69 真动作，uk_map_action_code；A01/A02/A1/A2 由 CHECK 排除） */
    private String actionCode;
    /** 归属小阶段（→ ipd_sub_stage.code） */
    private String subStageCode;
    /** pm-skills 技能/命令 JSON 数组文本；NULL=待 §3 定稿后补齐 */
    private String skillNames;
    /** 小阶段内动作排序（从 1 连续） */
    private Integer sortOrder;
    private String remark;

    /** 软删除标志（0正常 1已删） */
    @TableLogic
    @TableField("del_flag")
    private String delFlag;
}
```

### A2.3 两个 Mapper（对齐 `StageActionMapper` 同款）

```java
package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.IpdSubStage;

@Mapper
public interface IpdSubStageMapper extends BaseMapperPlus<IpdSubStage, IpdSubStage> {
}
```

```java
package org.ruoyi.ipd.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.IpdActionSkillMap;

@Mapper
public interface IpdActionSkillMapMapper extends BaseMapperPlus<IpdActionSkillMap, IpdActionSkillMap> {
}
```

### A2.4 `IpdSubStageService.java`

```java
package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.mapper.IpdSubStageMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 小阶段目录服务（22 行元数据只读）。
 * <p>排序口径固化（§2.8 不变量②的运行时面）：大阶段顺序 CONCEPT→…→LIFECYCLE，KPI 常驻恒最后，
 * 阶段内按 sort_order 升序。目录行由 owner DDL 维护，本服务无写方法（单写者纪律）。
 */
@Service
@RequiredArgsConstructor
public class IpdSubStageService {

    /** 大阶段固定顺序（KPI=常驻伪阶段恒最后，见 §6.4.2 设计决策）。 */
    static final List<String> STAGE_ORDER =
        List.of("CONCEPT", "PLAN", "DEV", "VALID", "LAUNCH", "LIFECYCLE", "KPI");

    private final IpdSubStageMapper subStageMapper;

    /** 全量目录（22 行），按大阶段顺序 + 阶段内 sort_order 升序。 */
    public List<IpdSubStage> listAll() {
        List<IpdSubStage> rows = new ArrayList<>(subStageMapper.selectList(null));
        rows.sort(Comparator.comparingInt((IpdSubStage s) -> stageRank(s.getStageCode()))
            .thenComparingInt(IpdSubStage::getSortOrder));
        return rows;
    }

    /** 按小阶段码取目录行；未命中抛 50001（fail-loud，与 IpdResources 语义一致）。 */
    public IpdSubStage getByCode(String code) {
        IpdSubStage row = subStageMapper.selectOne(
            new LambdaQueryWrapper<IpdSubStage>().eq(IpdSubStage::getCode, code));
        if (row == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "小阶段不存在: " + code);
        }
        return row;
    }

    /** 大阶段排序权重；未知阶段排最后（排序仅影响展示序，不吞数据不抛错）。 */
    static int stageRank(String stageCode) {
        int idx = STAGE_ORDER.indexOf(stageCode);
        return idx < 0 ? Integer.MAX_VALUE : idx;
    }
}
```

### A2.5 `IpdActionSkillMapService.java`

```java
package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.mapper.IpdActionSkillMapMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 动作技能映射服务（69 行元数据只读）。
 * <p>归并序稳定：listAll 按 subStageCode + sort_order 升序（大阶段展示序由 IpdSubStageService 主导）。
 * skill_names 解析 fail-loud：JSON 非法抛 90001，不静默吞坏数据（silent-failure 红线）。
 */
@Service
@RequiredArgsConstructor
public class IpdActionSkillMapService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final IpdActionSkillMapMapper skillMapMapper;

    /** 全量映射（69 行），按小阶段码 + 小阶段内 sort_order 升序。 */
    public List<IpdActionSkillMap> listAll() {
        List<IpdActionSkillMap> rows = new ArrayList<>(skillMapMapper.selectList(null));
        rows.sort(Comparator.comparing(IpdActionSkillMap::getSubStageCode)
            .thenComparingInt(IpdActionSkillMap::getSortOrder));
        return rows;
    }

    /** 某小阶段的动作映射，按 sort_order 升序。 */
    public List<IpdActionSkillMap> listBySubStage(String subStageCode) {
        return skillMapMapper.selectList(new LambdaQueryWrapper<IpdActionSkillMap>()
            .eq(IpdActionSkillMap::getSubStageCode, subStageCode)
            .orderByAsc(IpdActionSkillMap::getSortOrder));
    }

    /**
     * skill_names JSON 数组文本 → List&lt;String&gt;。
     * NULL/空 → 空列表（§3 未定稿口径，前端按「无绑定技能」渲染）；JSON 非法抛 90001。
     */
    public static List<String> parseSkillNames(String skillNamesJson) {
        if (skillNamesJson == null || skillNamesJson.isBlank()) {
            return List.of();
        }
        try {
            List<String> names = JSON.readValue(skillNamesJson, new TypeReference<List<String>>() { });
            return names == null ? List.of() : List.copyOf(names);
        } catch (JsonProcessingException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "skill_names JSON 非法: " + skillNamesJson);
        }
    }
}
```

### A2.6 `IpdSubStageServiceTest.java`

```java
package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.mapper.IpdSubStageMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class IpdSubStageServiceTest {

    private IpdSubStageMapper mapper;
    private IpdSubStageService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IpdSubStageMapper.class);
        service = new IpdSubStageService(mapper);
    }

    private static IpdSubStage sub(long id, String code, String name, String stage, int sort,
                                   String isGate, String gateCode, String isResident) {
        return IpdSubStage.builder().id(id).code(code).name(name).stageCode(stage)
            .sortOrder(sort).isGate(isGate).gateCode(gateCode).isResident(isResident)
            .ownerRole("MARKET_PM").skillHint("pm-toolkit").build();
    }

    @Test
    @DisplayName("listAll 按大阶段顺序 + 阶段内 sort 升序（乱序输入），KPI 常驻恒最后")
    void listAllSortsByStageRankThenSortOrder() {
        when(mapper.selectList(any())).thenReturn(List.of(
            sub(22L, "KPI-S1", "共担KPI归集（常驻）", "KPI", 99, "0", null, "1"),
            sub(2L, "CONCEPT-S2", "竞争与客群", "CONCEPT", 2, "0", null, "0"),
            sub(5L, "PLAN-S1", "需求定义", "PLAN", 1, "0", null, "0"),
            sub(1L, "CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0", null, "0")));

        List<IpdSubStage> out = service.listAll();

        assertThat(out).extracting(IpdSubStage::getCode)
            .containsExactly("CONCEPT-S1", "CONCEPT-S2", "PLAN-S1", "KPI-S1");
    }

    @Test
    @DisplayName("stageRank：未知大阶段排最后（权重最大），不抛错")
    void stageRankUnknownStageLast() {
        assertThat(IpdSubStageService.stageRank("CONCEPT")).isEqualTo(0);
        assertThat(IpdSubStageService.stageRank("KPI")).isEqualTo(6);
        assertThat(IpdSubStageService.stageRank("UNKNOWN"))
            .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("getByCode 命中返回目录行")
    void getByCodeHit() {
        when(mapper.selectOne(any())).thenReturn(
            sub(1L, "CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0", null, "0"));
        IpdSubStage row = service.getByCode("CONCEPT-S1");
        assertThat(row.getName()).isEqualTo("市场洞察");
    }

    @Test
    @DisplayName("getByCode 未命中抛 50001 NOT_FOUND（fail-loud，不返回 null）")
    void getByCodeMissThrowsNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.getByCode("NOPE-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND);
                assertThat(ex.getMessage()).contains("NOPE-S1");
            });
    }
}
```

### A2.7 `IpdActionSkillMapServiceTest.java`

```java
package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.mapper.IpdActionSkillMapMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class IpdActionSkillMapServiceTest {

    private IpdActionSkillMapMapper mapper;
    private IpdActionSkillMapService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IpdActionSkillMapMapper.class);
        service = new IpdActionSkillMapService(mapper);
    }

    private static IpdActionSkillMap map(long id, String code, String subStage, int sort, String skills) {
        return IpdActionSkillMap.builder().id(id).actionCode(code).subStageCode(subStage)
            .sortOrder(sort).skillNames(skills).remark("待 §3 定稿后补齐 skill_names").build();
    }

    @Test
    @DisplayName("listAll 按 subStageCode + sort_order 升序（乱序输入）")
    void listAllSortsBySubStageThenSort() {
        when(mapper.selectList(any())).thenReturn(List.of(
            map(2L, "C02", "CONCEPT-S2", 1, null),
            map(3L, "C04", "CONCEPT-S1", 2, null),
            map(1L, "C01", "CONCEPT-S1", 1, "[\"interview-script\"]")));

        List<IpdActionSkillMap> out = service.listAll();

        assertThat(out).extracting(IpdActionSkillMap::getActionCode)
            .containsExactly("C01", "C04", "C02");
    }

    @Test
    @DisplayName("parseSkillNames：NULL 与空串 → 空列表（§3 未定稿口径，不回 null）")
    void parseSkillNamesNullAndBlank() {
        assertThat(IpdActionSkillMapService.parseSkillNames(null)).isEmpty();
        assertThat(IpdActionSkillMapService.parseSkillNames("")).isEmpty();
        assertThat(IpdActionSkillMapService.parseSkillNames("  ")).isEmpty();
    }

    @Test
    @DisplayName("parseSkillNames：JSON 数组文本 → 按序列表")
    void parseSkillNamesValidArray() {
        assertThat(IpdActionSkillMapService.parseSkillNames("[\"interview-script\",\"market-sizing\"]"))
            .containsExactly("interview-script", "market-sizing");
        assertThat(IpdActionSkillMapService.parseSkillNames("[]")).isEmpty();
    }

    @Test
    @DisplayName("parseSkillNames：JSON 非法抛 90001（fail-loud，不静默吞坏数据）")
    void parseSkillNamesInvalidJsonFailsLoud() {
        assertThatThrownBy(() -> IpdActionSkillMapService.parseSkillNames("{\"bad\""))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR);
                assertThat(ex.getMessage()).contains("skill_names JSON 非法");
            });
    }
}
```

**验证**（A2 全部完成后）：

```bash
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=IpdSubStageServiceTest,IpdActionSkillMapServiceTest test
```

**失败路径**：
- 编译错 `BaseMapperPlus` 找不到 → import 错包，正确为 `org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus`（对照 `StageActionMapper`）。
- 测试红在排序 → `listAll` 内存排序 comparator 与 `STAGE_ORDER` 口径不一致。
- **若在 DDL 未 apply 前误跑了带 Spring 上下文的测试并报 `Table 'ipd_dev.ipd_sub_stage' doesn't exist`** → 属预期（表未建）；本 Task 两个测试类为纯 Mockito 单测不受影响，勿为绕错去动真库建表（建表只能 owner apply）。

**回滚**：删 A2 新建的 8 个文件即可，零影响（尚无调用方）。

**DONE 定义**：8 个文件落盘、两测试类单模块跑绿。

---

## Task A3 — 查询接口 `GET /api/v1/ipd/stage/sub-stages`

**Goal**：返回 22 小阶段 + 每动作技能映射；`code=0/message` 包络、字符串 ID。

**Files**（全部新建）：

| 路径（相对 `ruoyi-modules/ruoyi-ipd`） | 内容 |
|---|---|
| `src/main/java/org/ruoyi/ipd/vo/SubStageView.java` | 小阶段视图 record |
| `src/main/java/org/ruoyi/ipd/vo/ActionSkillView.java` | 动作技能视图 record |
| `src/main/java/org/ruoyi/ipd/controller/SubStageController.java` | 控制器（A4/A5 追加端点于此） |
| `src/test/java/org/ruoyi/ipd/controller/SubStageControllerTest.java` | 测试 |

**Interfaces**：
- 消费：`IpdSubStageService.listAll()`、`IpdActionSkillMapService.listAll()`、`IpdActionSkillMapService.parseSkillNames`、`ActionCatalog.byCode(...).name()`（动作名 SSOT，对 §2 名称漂移免疫）、`IpdPermission.requireInternal()`。
- 产出：`GET /api/v1/ipd/stage/sub-stages` → `ApiV1Response<List<SubStageView>>`。
- **包位置红线**：控制器必须在 `org.ruoyi.ipd.controller` 包（`PermissionAdviceCoverageTest` 锁定 `@RestController` 全落 advice basePackages 覆盖范围）。

### A3.1 `SubStageView.java` / `ActionSkillView.java`

```java
package org.ruoyi.ipd.vo;

import java.util.List;

/**
 * 小阶段引导视图（GET /api/v1/ipd/stage/sub-stages）。
 * id 为字符串（P0-4.1 大整数保真）；actions 已按 sort_order 升序并解析 skill_names。
 */
public record SubStageView(
    String id,
    String code,
    String name,
    String stageCode,
    Integer sortOrder,
    String isGate,
    String gateCode,
    String skillHint,
    String ownerRole,
    List<ActionSkillView> actions) {
}
```

```java
package org.ruoyi.ipd.vo;

import java.util.List;

/**
 * 动作技能视图：动作码 + 目录真名（ActionCatalog 派生）+ 归属小阶段 + 技能列表。
 * skillNames 为已解析列表（NULL/未定稿 → 空列表，不回 null）。
 */
public record ActionSkillView(
    String actionCode,
    String actionName,
    String subStageCode,
    List<String> skillNames,
    Integer sortOrder) {
}
```

### A3.2 `SubStageController.java`（A3 版；A4/A5 会追加端点与依赖）

```java
package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdSubStageService;
import org.ruoyi.ipd.vo.ActionSkillView;
import org.ruoyi.ipd.vo.SubStageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小阶段引导接口 /api/v1/ipd/stage/sub-stages（Track A3）。
 * <p>22 小阶段 + 每动作技能映射（69 归属经 ipd_action_skill_map 只读归并，内存 join 无 N+1）。
 * 响应一律 ApiV1Response code0/message 包络 + 字符串 ID（A0.3 契约）。
 */
@RestController
@RequestMapping("/api/v1/ipd/stage/sub-stages")
@RequiredArgsConstructor
public class SubStageController {

    private final IpdSubStageService subStageService;
    private final IpdActionSkillMapService skillMapService;
    private final IpdPermission ipdPermission;

    /** 小阶段引导全量查询（22 行 + 动作技能归并），需 ipd:stage-action:list 权限 */
    @GetMapping
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<SubStageView>> list() {
        ipdPermission.requireInternal();
        return ApiV1Response.ok(buildGuide(subStageService.listAll(), skillMapService.listAll()));
    }

    /** 目录 + 映射内存归并（包级可见，A4 事件下发复用）；调用方保证 listAll 已按序。 */
    static List<SubStageView> buildGuide(List<IpdSubStage> subStages, List<IpdActionSkillMap> maps) {
        Map<String, List<IpdActionSkillMap>> bySub = new LinkedHashMap<>();
        for (IpdActionSkillMap m : maps) {
            bySub.computeIfAbsent(m.getSubStageCode(), k -> new ArrayList<>()).add(m);
        }
        List<SubStageView> out = new ArrayList<>();
        for (IpdSubStage s : subStages) {
            List<ActionSkillView> actions = new ArrayList<>();
            for (IpdActionSkillMap m : bySub.getOrDefault(s.getCode(), List.of())) {
                actions.add(new ActionSkillView(
                    m.getActionCode(),
                    ActionCatalog.byCode(m.getActionCode()).name(),
                    m.getSubStageCode(),
                    IpdActionSkillMapService.parseSkillNames(m.getSkillNames()),
                    m.getSortOrder()));
            }
            out.add(new SubStageView(
                String.valueOf(s.getId()), s.getCode(), s.getName(), s.getStageCode(),
                s.getSortOrder(), s.getIsGate(), s.getGateCode(), s.getSkillHint(),
                s.getOwnerRole(), actions));
        }
        return out;
    }
}
```

### A3.3 `SubStageControllerTest.java`（A3 版；A5 追加一个 @Mock 字段）

```java
package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdSubStageService;
import org.ruoyi.ipd.vo.ActionSkillView;
import org.ruoyi.ipd.vo.SubStageView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SubStageControllerTest {

    private static final IpdActor ACTOR = new IpdActor(11L, "市场PM-甲", "MARKET_PM", 900001L);

    @Mock
    private IpdSubStageService subStageService;
    @Mock
    private IpdActionSkillMapService skillMapService;
    @Mock
    private IpdPermission ipdPermission;
    @InjectMocks
    private SubStageController controller;

    @Test
    @DisplayName("A3：list 返回 code0/message 包络 + 小阶段归并动作技能映射 + 字符串 ID")
    void listMergesSubStagesAndSkillMaps() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(subStageService.listAll()).thenReturn(List.of(
            IpdSubStage.builder().id(1846876543210987521L).code("CONCEPT-S1").name("市场洞察")
                .stageCode("CONCEPT").sortOrder(1).isGate("0").gateCode(null)
                .skillHint("pm-product-discovery + pm-market-research").isResident("0")
                .ownerRole("MARKET_PM").build(),
            IpdSubStage.builder().id(1846876543210987522L).code("CONCEPT-S2").name("竞争与客群")
                .stageCode("CONCEPT").sortOrder(2).isGate("0").gateCode(null)
                .skillHint("pm-market-research").isResident("0").ownerRole("MARKET_PM").build()));
        when(skillMapService.listAll()).thenReturn(List.of(
            IpdActionSkillMap.builder().id(1846876543210987601L).actionCode("C01")
                .subStageCode("CONCEPT-S1").skillNames("[\"interview-script\",\"market-sizing\"]")
                .sortOrder(1).build(),
            IpdActionSkillMap.builder().id(1846876543210987602L).actionCode("C02")
                .subStageCode("CONCEPT-S2").skillNames(null).sortOrder(1).build()));

        ApiV1Response<List<SubStageView>> resp = controller.list();

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getMessage()).isEqualTo("ok");
        List<SubStageView> data = resp.getData();
        assertThat(data).hasSize(2);
        assertThat(data.get(0).id()).isEqualTo("1846876543210987521");
        assertThat(data.get(0).code()).isEqualTo("CONCEPT-S1");
        List<ActionSkillView> actions = data.get(0).actions();
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).actionCode()).isEqualTo("C01");
        assertThat(actions.get(0).actionName()).isEqualTo("市场机会与痛点调研");
        assertThat(actions.get(0).skillNames()).containsExactly("interview-script", "market-sizing");
        // skill_names NULL → 空列表（§3 未定稿口径），不回 null
        assertThat(data.get(1).actions().get(0).skillNames()).isEmpty();
    }

    @Test
    @DisplayName("A3：小阶段无映射行时动作列表为空而非 null")
    void subStageWithoutMapsYieldsEmptyActions() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(subStageService.listAll()).thenReturn(List.of(
            IpdSubStage.builder().id(1846876543210987523L).code("KPI-S1").name("共担KPI归集（常驻）")
                .stageCode("KPI").sortOrder(99).isGate("0").gateCode(null)
                .skillHint("pm-data-analytics").isResident("1").ownerRole("GROUP_LEADER").build()));
        when(skillMapService.listAll()).thenReturn(List.of());

        ApiV1Response<List<SubStageView>> resp = controller.list();

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData().get(0).actions()).isEmpty();
    }
}
```

**验证**（含 advice 覆盖防漂移回归——新增 @RestController 必须过 `PermissionAdviceCoverageTest`）：

```bash
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=SubStageControllerTest,PermissionAdviceCoverageTest test
```

**失败路径**：
- `PermissionAdviceCoverageTest` 红 → 控制器没落 `org.ruoyi.ipd.controller` 包（或误用 `@RestControllerAdvice` 词形）。
- `list()` 断言 code≠0 → 误用了基线 `R`；IPD 契约是 `ApiV1Response.ok`（code=0）。
- `ActionCatalog.byCode` 抛 `IllegalArgumentException` → 映射行含目录外动作码（seed 合同测试应已拦；核对 A1 是否跑绿）。

**回滚**：删 4 个新建文件（A4/A5 未开工时零影响）。

**DONE 定义**：4 个文件落盘；两测试类单模块跑绿。

---

## Task A4 — AG-UI 工具下发（小阶段引导卡）

**Goal**：把「小阶段引导卡」翻译为 AG-UI 事件序列下发（复用 `AgUiEvents`/`AgUiFrameTranslator`，不改这两个既有文件）。

**Files**（1 新建 + 1 追加端点 + 1 新建测试 + 1 追加用例）：

| 路径（相对 `ruoyi-modules/ruoyi-ipd`） | 动作 |
|---|---|
| `src/main/java/org/ruoyi/ipd/copilotkit/SubStageGuideTool.java` | 新建 |
| `src/main/java/org/ruoyi/ipd/controller/SubStageController.java` | **追加** `GET /guide-events` 端点（Track 内自建文件，非既有文件） |
| `src/test/java/org/ruoyi/ipd/copilotkit/SubStageGuideToolTest.java` | 新建 |
| `src/test/java/org/ruoyi/ipd/controller/SubStageControllerTest.java` | **追加** `guideEventsBuildsAgUiSequence` 用例 |

**Interfaces**：
- 消费（既有，只读复用）：`AgUiFrameTranslator(threadId, runId)` 的 `onDelta(String)`/`onDone(Map)`；`AgUiEvents.stateDelta(List<Object>)`；卡片协议 `{type,version,data,sourceRefs}`（`AgUiFrameTranslator.onDone` javadoc 锁定：TOOL_CALL_START 的 toolCallName = card.type，TOOL_CALL_RESULT content = JSON({version,sourceRefs})）。
- 产出：`SubStageGuideTool.cardFrame(SubStageView, List<String>): Map<String,Object>`；
  `SubStageGuideTool.translateGuide(String threadId, String runId, String introText, SubStageView guide, List<String> sourceRefs, List<Object> progressPatch): List<Map<String,Object>>`；
  端点 `GET /api/v1/ipd/stage/sub-stages/guide-events?subStageCode=&projectId=` → `ApiV1Response<List<Map<String,Object>>>`。
- 事件序合同（测试锁定）：`[RUN_STARTED, TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END, TOOL_CALL_START, TOOL_CALL_ARGS, TOOL_CALL_END, TOOL_CALL_RESULT, (STATE_DELTA), RUN_FINISHED]`——RUN_FINISHED 恒末帧。

### A4.1 `SubStageGuideTool.java`

```java
package org.ruoyi.ipd.copilotkit;

import org.ruoyi.ipd.vo.SubStageView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小阶段引导 AG-UI 工具（Track A4）：把「小阶段 × 动作 × pm-skill 引导卡」翻译为 AG-UI 事件序列。
 *
 * <p>复用 {@link AgUiFrameTranslator} 四帧翻译：onDelta → 文本开场（TEXT_MESSAGE_*），
 * onDone{card} → TOOL_CALL_START/ARGS/END/RESULT 组 + RUN_FINISHED；STATE_DELTA 进度补丁
 * 插在 RUN_FINISHED 前（RUN_* 成对与末帧契约不破，见 AgUiFrameTranslator javadoc）。
 *
 * <p>纯函数、无状态、不落存储（与 AgUiCopilotRun 同一 C08 红线）；不改 AgUiEvents/AgUiFrameTranslator。
 */
public final class SubStageGuideTool {

    /** AG-UI 工具名（TOOL_CALL_START toolCallName = card.type，与前端工具注册名一致）。 */
    public static final String TOOL_NAME = "sub-stage.guide";
    /** 卡片协议版本（TOOL_CALL_RESULT content={version,sourceRefs}）。 */
    public static final int CARD_VERSION = 1;

    private SubStageGuideTool() {
    }

    /** 卡片帧载荷（AgUiFrameTranslator.onDone 的 card 键契约：{type,version,data,sourceRefs}）。 */
    public static Map<String, Object> cardFrame(SubStageView guide, List<String> sourceRefs) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", TOOL_NAME);
        card.put("version", CARD_VERSION);
        card.put("data", guide);
        card.put("sourceRefs", sourceRefs == null ? List.of() : sourceRefs);
        return card;
    }

    /**
     * 翻译一次引导下发为完整 AG-UI 事件序列：
     * [RUN_STARTED, TEXT_MESSAGE_START, TEXT_MESSAGE_CONTENT, TEXT_MESSAGE_END,
     *  TOOL_CALL_START, TOOL_CALL_ARGS, TOOL_CALL_END, TOOL_CALL_RESULT, (STATE_DELTA), RUN_FINISHED]。
     *
     * @param threadId      AG-UI 线程 ID
     * @param runId         AG-UI run ID
     * @param introText     开场引导文本（TEXT_MESSAGE_* 承载）
     * @param guide         小阶段引导卡（card.data）
     * @param sourceRefs    来源引用（card.sourceRefs，形如 ipd_sub_stage/CONCEPT-S1）
     * @param progressPatch RFC 6902 进度补丁；null/空则不发 STATE_DELTA
     */
    public static List<Map<String, Object>> translateGuide(String threadId, String runId, String introText,
                                                           SubStageView guide, List<String> sourceRefs,
                                                           List<Object> progressPatch) {
        AgUiFrameTranslator tx = new AgUiFrameTranslator(threadId, runId);
        List<Map<String, Object>> out = new ArrayList<>(tx.onDelta(introText));
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("status", "ok");
        done.put("card", cardFrame(guide, sourceRefs));
        out.addAll(tx.onDone(done));
        if (progressPatch != null && !progressPatch.isEmpty()) {
            // RUN_FINISHED 恒为末帧（AgUiFrameTranslator 契约）——STATE_DELTA 插在其前
            out.add(out.size() - 1, AgUiEvents.stateDelta(progressPatch));
        }
        return out;
    }
}
```

### A4.2 `SubStageController` 追加 `GET /guide-events` 端点

追加 import（类头 import 区）：`org.ruoyi.ipd.common.IpdBusinessException`、`org.ruoyi.ipd.copilotkit.SubStageGuideTool`、`org.springframework.web.bind.annotation.RequestParam`、`java.util.UUID`。追加方法：

```java
    /** AG-UI 小阶段引导工具事件序列（A4）：返回完整 AG-UI 事件数组，前端按帧流式播放。 */
    @GetMapping("/guide-events")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<List<Map<String, Object>>> guideEvents(@RequestParam String subStageCode,
                                                                @RequestParam(required = false) Long projectId) {
        ipdPermission.requireInternal();
        SubStageView guide = buildGuide(subStageService.listAll(), skillMapService.listAll()).stream()
            .filter(v -> v.code().equals(subStageCode))
            .findFirst()
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "小阶段不存在: " + subStageCode));
        List<String> sourceRefs = new ArrayList<>();
        sourceRefs.add("ipd_sub_stage/" + guide.code());
        for (ActionSkillView a : guide.actions()) {
            sourceRefs.add("ipd_action_skill_map/" + a.actionCode());
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subStageCode", guide.code());
        if (projectId != null) {
            value.put("projectId", String.valueOf(projectId));
        }
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("path", "/subStageGuide");
        op.put("value", value);
        List<Object> progressPatch = new ArrayList<>();
        progressPatch.add(op);
        String intro = "小阶段「" + guide.name() + "」共 " + guide.actions().size() + " 个动作，按序执行技能引导。";
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            UUID.randomUUID().toString(), UUID.randomUUID().toString(),
            intro, guide, sourceRefs, progressPatch);
        return ApiV1Response.ok(events);
    }
```

同时把 `org.ruoyi.ipd.common.ApiV1ErrorCode` 补进 import（orElseThrow 用）。

### A4.3 `SubStageGuideToolTest.java`

```java
package org.ruoyi.ipd.copilotkit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.vo.ActionSkillView;
import org.ruoyi.ipd.vo.SubStageView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class SubStageGuideToolTest {

    private static SubStageView guideFixture() {
        return new SubStageView("1846876543210987521", "CONCEPT-S1", "市场洞察", "CONCEPT", 1,
            "0", null, "pm-product-discovery + pm-market-research", "MARKET_PM",
            List.of(new ActionSkillView("C01", "市场机会与痛点调研", "CONCEPT-S1",
                List.of("interview-script"), 1)));
    }

    private static List<Object> progressPatchFixture() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("subStageCode", "CONCEPT-S1");
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("op", "add");
        op.put("path", "/subStageGuide");
        op.put("value", value);
        List<Object> patch = new ArrayList<>();
        patch.add(op);
        return patch;
    }

    @Test
    @DisplayName("A4：事件序合同——RUN_STARTED 首帧、RUN_FINISHED 末帧、STATE_DELTA 在 TOOL_CALL_* 后 RUN_FINISHED 前")
    void translateGuideEmitsContractSequence() {
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            "thread-1", "run-1", "小阶段「市场洞察」共 1 个动作，按序执行技能引导。",
            guideFixture(), List.of("ipd_sub_stage/CONCEPT-S1", "ipd_action_skill_map/C01"),
            progressPatchFixture());

        assertThat(events).isNotEmpty();
        assertThat(events.get(0).get("type")).isEqualTo("RUN_STARTED");
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("RUN_FINISHED");

        int start = indexOfType(events, "TOOL_CALL_START");
        int args = indexOf(events, "TOOL_CALL_ARGS");
        int end = indexOf(events, "TOOL_CALL_END");
        int result = indexOf(events, "TOOL_CALL_RESULT");
        int state = indexOf(events, "STATE_DELTA");
        int finished = indexOf(events, "RUN_FINISHED");
        assertThat(start).isGreaterThanOrEqualTo(0);
        assertThat(start).isLessThan(args);
        assertThat(args).isLessThan(end);
        assertThat(end).isLessThan(result);
        assertThat(result).isLessThan(state);
        assertThat(state).isLessThan(finished);
    }

    @Test
    @DisplayName("A4：TOOL_CALL_START toolCallName=sub-stage.guide，RESULT content 含 version+sourceRefs")
    void toolCallCarriesCardContract() {
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            "thread-1", "run-1", "引导开始", guideFixture(),
            List.of("ipd_sub_stage/CONCEPT-S1"), progressPatchFixture());

        Map<String, Object> start = events.get(indexOfType(events, "TOOL_CALL_START"));
        assertThat(start.get("toolCallName")).isEqualTo(SubStageGuideTool.TOOL_NAME);
        String argsJson = String.valueOf(events.get(indexOfType(events, "TOOL_CALL_ARGS")).get("delta"));
        assertThat(argsJson).contains("\"code\":\"CONCEPT-S1\"");
        String content = String.valueOf(events.get(indexOfType(events, "TOOL_CALL_RESULT")).get("content"));
        assertThat(content).contains("\"version\":" + SubStageGuideTool.CARD_VERSION);
        assertThat(content).contains("ipd_sub_stage/CONCEPT-S1");
    }

    @Test
    @DisplayName("A4：progressPatch 为空时不发 STATE_DELTA，RUN_FINISHED 仍末帧")
    void emptyProgressPatchSkipsStateDelta() {
        List<Map<String, Object>> events = SubStageGuideTool.translateGuide(
            "thread-1", "run-1", "引导开始", guideFixture(), List.of("ipd_sub_stage/CONCEPT-S1"), List.of());

        assertThat(indexOf(events, "STATE_DELTA")).isEqualTo(-1);
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("RUN_FINISHED");
    }

    private static int indexOf(List<Map<String, Object>> events, String type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.equals(events.get(i).get("type"))) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfType(List<Map<String, Object>> events, String type) {
        int idx = indexOf(events, type);
        assertThat(idx).as("事件存在: %s", type).isGreaterThanOrEqualTo(0);
        return idx;
    }
}
```

### A4.4 `SubStageControllerTest` 追加用例

```java
    @Test
    @DisplayName("A4：guide-events 返回 AG-UI 事件序列并带 sourceRefs")
    void guideEventsBuildsAgUiSequence() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        when(subStageService.listAll()).thenReturn(List.of(
            IpdSubStage.builder().id(1846876543210987521L).code("CONCEPT-S1").name("市场洞察")
                .stageCode("CONCEPT").sortOrder(1).isGate("0").gateCode(null)
                .skillHint("pm-product-discovery + pm-market-research").isResident("0")
                .ownerRole("MARKET_PM").build()));
        when(skillMapService.listAll()).thenReturn(List.of(
            IpdActionSkillMap.builder().id(1846876543210987601L).actionCode("C01")
                .subStageCode("CONCEPT-S1").skillNames(null).sortOrder(1).build()));

        ApiV1Response<List<Map<String, Object>>> resp = controller.guideEvents("CONCEPT-S1", 1001L);

        assertThat(resp.getCode()).isEqualTo(0);
        List<Map<String, Object>> events = resp.getData();
        assertThat(events.get(0).get("type")).isEqualTo("RUN_STARTED");
        assertThat(events.get(events.size() - 1).get("type")).isEqualTo("RUN_FINISHED");
        String content = String.valueOf(events.stream()
            .filter(e -> "TOOL_CALL_RESULT".equals(e.get("type"))).findFirst().orElseThrow()
            .get("content"));
        assertThat(content).contains("ipd_sub_stage/CONCEPT-S1", "ipd_action_skill_map/C01");
    }
```

（该测试类需补 import：`java.util.Map`、`static org.assertj.core.api.Assertions.assertThat` 已有。）

**验证**：

```bash
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=SubStageGuideToolTest,SubStageControllerTest test
```

**失败路径**：
- 事件序断言红在 RUN_FINISHED 非末帧 → STATE_DELTA 插入位置错（必须 `out.add(out.size()-1, ...)`）。
- `toolCallName` 断言红 → card.type 未用 `TOOL_NAME` 常量。
- `guideEvents` 抛 50001 → subStageCode 不存在（契约：fail-loud，不回空事件序列）。

**回滚**：删 `SubStageGuideTool.java`、`SubStageGuideToolTest.java`，从 `SubStageController`/`SubStageControllerTest` 撤掉追加方法（A3 版本即为干净基线）。

**DONE 定义**：工具类 + 端点 + 两测试落盘；验证命令跑绿；事件序合同锁定。

**未证明项（A4）**：与 `AgUiCopilotRun` 主 run 链的深度接线（把引导卡注入对话 done 帧）需改既有文件，超出本 Track 授权——本 Task 以独立 `guide-events` 端点下发事件序列，运行态 Inspector 走查由 owner 在 apply 后联调验证（§6.6 项 6）。

---

## Task A5 — 阻断门禁（阻断动作未完成禁止跳小阶段）

**Goal**：§2.8 不变量④的运行时锁——目标小阶段之前的所有小阶段中，`is_blocking=1` 的动作未完成（且非历史缺失）时禁止推进，`40001 GATE_NOT_PASSED` fail-closed。

**业务规则（事实源 §0.1）**：
- 「前序」= 目标小阶段之前的**顺序**小阶段（常驻 `KPI-S1` 不参与顺序推进）；
- 「已满足」= 状态 `DONE`/`NA`（`SETTLED_STATUSES`）或 `history_mark=HISTORICAL_MISSING`（历史缺失不伪造 DONE、门禁视为已满足——`StageAction` 既有语义）；
- 实例缺行（历史项目未全量实例化）不拦——与 HISTORICAL_MISSING 同哲学，不造伪数据；
- 推进是**校验型**动作：方案 A（§6.3）不设项目级小阶段游标列，`advance` 端点校验通过即返回 `advanced=true`，前端以校验结果驱动 UI 游标（单写者纪律：`stage_actions` 写路径零改动）。

**Files**：

| 路径（相对 `ruoyi-modules/ruoyi-ipd`） | 动作 |
|---|---|
| `src/main/java/org/ruoyi/ipd/service/SubStageGateService.java` | 新建 |
| `src/main/java/org/ruoyi/ipd/controller/SubStageController.java` | **追加** `POST /advance` + 第 4 依赖 `SubStageGateService` |
| `src/test/java/org/ruoyi/ipd/service/SubStageGateServiceTest.java` | 新建 |
| `src/test/java/org/ruoyi/ipd/controller/SubStageAdvanceEndpointTest.java` | 新建 |
| `src/test/java/org/ruoyi/ipd/controller/SubStageControllerTest.java` | **追加** `@Mock SubStageGateService` 字段（构造器第 4 依赖） |

**Interfaces**：
- 消费：`IpdSubStageService.listAll()`（顺序小阶段）、`IpdActionSkillMapService.listAll()`（动作归属）、`StageActionMapper.selectList(...)`（项目实例，`ActionCatalog.resolveCode` 归一 Z 别名）。
- 产出：`SubStageGateService.assertAdvanceAllowed(Long projectId, String targetSubStageCode): void`（不通过抛 `IpdBusinessException(GATE_NOT_PASSED, ...)`）；包级可见 `pendingBlockingActions(Long, String): List<String>`（待完成阻断动作码，升序）；端点 `POST /api/v1/ipd/stage/sub-stages/advance?projectId=&targetSubStageCode=` → `ApiV1Response<Map<String,Object>>`（`{projectId:String, targetSubStageCode, advanced:true}`）。

### A5.1 `SubStageGateService.java`

```java
package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小阶段推进门禁（§2.8 不变量④，主计划 §0.1 业务规则）：
 * 目标小阶段之前的顺序小阶段中，is_blocking=1 的动作未完成即拒绝推进（40001 GATE_NOT_PASSED，fail-closed）。
 *
 * <p>已满足 = DONE/NA 或 history_mark=HISTORICAL_MISSING（历史缺失不伪造 DONE，门禁视为已满足——
 * 与 StageActionService 语义一致）；实例缺行（历史项目未全量实例化）不拦。
 * <p>常驻小阶段（is_resident=1，KPI-S1）与未知码不可作为推进目标（10001 PARAM_INVALID）。
 * <p>校验型动作：方案 A 无项目级游标列，本服务不写任何行（单写者纪律：stage_actions 写路径零改动）。
 */
@Service
@RequiredArgsConstructor
public class SubStageGateService {

    /** 已满足状态集：完成或豁免 */
    static final Set<String> SETTLED_STATUSES = Set.of("DONE", "NA");
    /** 历史缺失标记（StageActions.history_mark 既有值） */
    static final String HISTORY_MISSING = "HISTORICAL_MISSING";

    private final IpdSubStageService subStageService;
    private final IpdActionSkillMapService skillMapService;
    private final StageActionMapper stageActionMapper;

    /** 推进前校验：前序阻断动作未完成则抛 40001（message 点名全部待完成动作码）。 */
    public void assertAdvanceAllowed(Long projectId, String targetSubStageCode) {
        List<String> pending = pendingBlockingActions(projectId, targetSubStageCode);
        if (!pending.isEmpty()) {
            throw new IpdBusinessException(ApiV1ErrorCode.GATE_NOT_PASSED,
                "小阶段门禁未通过：阻断动作未完成 " + String.join(",", pending));
        }
    }

    /** 待完成阻断动作码（升序、去重语义由映射唯一性保证）；放行返回空列表。 */
    List<String> pendingBlockingActions(Long projectId, String targetSubStageCode) {
        if (projectId == null || targetSubStageCode == null || targetSubStageCode.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "projectId 与 targetSubStageCode 必填");
        }
        List<IpdSubStage> ordered = subStageService.listAll().stream()
            .filter(s -> !"1".equals(s.getIsResident()))
            .toList();
        int targetIdx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getCode().equals(targetSubStageCode)) {
                targetIdx = i;
                break;
            }
        }
        if (targetIdx < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "目标小阶段不存在或为常驻小阶段（常驻不参与顺序推进）: " + targetSubStageCode);
        }
        Set<String> beforeCodes = new HashSet<>();
        for (int i = 0; i < targetIdx; i++) {
            beforeCodes.add(ordered.get(i).getCode());
        }
        if (beforeCodes.isEmpty()) {
            return List.of();
        }

        Map<String, StageAction> byCode = new HashMap<>();
        List<StageAction> rows = stageActionMapper.selectList(
            new LambdaQueryWrapper<StageAction>().eq(StageAction::getProjectId, projectId));
        for (StageAction row : rows) {
            // Z01-Z05 别名归一为权威码（P1-8.2 / AC-IPD-17）
            byCode.put(ActionCatalog.resolveCode(row.getActionCode()), row);
        }

        List<String> pending = new ArrayList<>();
        for (IpdActionSkillMap m : skillMapService.listAll()) {
            if (!beforeCodes.contains(m.getSubStageCode())) {
                continue;
            }
            StageAction row = byCode.get(ActionCatalog.resolveCode(m.getActionCode()));
            if (row == null) {
                continue; // 实例缺行（历史项目未全量实例化）不拦
            }
            if (!"1".equals(row.getIsBlocking())) {
                continue;
            }
            if (SETTLED_STATUSES.contains(row.getStatus())) {
                continue;
            }
            if (HISTORY_MISSING.equals(row.getHistoryMark())) {
                continue;
            }
            pending.add(m.getActionCode());
        }
        Collections.sort(pending);
        return pending;
    }
}
```

### A5.2 `SubStageController` 追加 `POST /advance`（+ 构造器第 4 依赖）

构造器依赖追加 `private final SubStageGateService subStageGateService;`（`@RequiredArgsConstructor` 自动扩为 4 参）。追加 import：`org.ruoyi.ipd.service.SubStageGateService`、`org.springframework.web.bind.annotation.PostMapping`。追加方法：

```java
    /**
     * 小阶段推进校验（A5，§2.8 不变量④）：前序阻断动作未完成抛 40001。
     * 校验型端点——方案 A 无项目级小阶段游标列，通过即返回 advanced=true（前端驱动 UI 游标）。
     */
    @PostMapping("/advance")
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION_EXECUTE, type = IpdAuthSession.LOGIN_TYPE)
    public ApiV1Response<Map<String, Object>> advance(@RequestParam Long projectId,
                                                      @RequestParam String targetSubStageCode) {
        ipdPermission.requireInternal();
        subStageGateService.assertAdvanceAllowed(projectId, targetSubStageCode);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectId", String.valueOf(projectId));
        body.put("targetSubStageCode", targetSubStageCode);
        body.put("advanced", true);
        return ApiV1Response.ok(body);
    }
```

`SubStageControllerTest` 追加字段（第 4 依赖，`@InjectMocks` 自动改走 4 参构造）：

```java
    @Mock
    private SubStageGateService subStageGateService;
```

（import 补 `org.ruoyi.ipd.service.SubStageGateService`。既有两个用例不打 gate 桩，strict stubs 不报未使用——mock 字段无 stub 不算多余桩。）

### A5.3 `SubStageGateServiceTest.java`

```java
package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SubStageGateServiceTest {

    private IpdSubStageService subStageService;
    private IpdActionSkillMapService skillMapService;
    private StageActionMapper stageActionMapper;
    private SubStageGateService service;

    @BeforeEach
    void setUp() {
        subStageService = mock(IpdSubStageService.class);
        skillMapService = mock(IpdActionSkillMapService.class);
        stageActionMapper = mock(StageActionMapper.class);
        service = new SubStageGateService(subStageService, skillMapService, stageActionMapper);
    }

    /** 目录夹具：CONCEPT-S1..S4 + PLAN-S1 + 常驻 KPI-S1（字段值与 §6.4.2 seed 一致）。 */
    private static List<IpdSubStage> catalog() {
        return List.of(
            sub("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0", null, "0"),
            sub("CONCEPT-S2", "竞争与客群", "CONCEPT", 2, "0", null, "0"),
            sub("CONCEPT-S3", "商业论证", "CONCEPT", 3, "0", null, "0"),
            sub("CONCEPT-S4", "合规与立项", "CONCEPT", 4, "1", "G1", "0"),
            sub("PLAN-S1", "需求定义", "PLAN", 1, "0", null, "0"),
            sub("KPI-S1", "共担KPI归集（常驻）", "KPI", 99, "0", null, "1"));
    }

    private static IpdSubStage sub(String code, String name, String stage, int sort,
                                   String isGate, String gateCode, String isResident) {
        return IpdSubStage.builder().code(code).name(name).stageCode(stage).sortOrder(sort)
            .isGate(isGate).gateCode(gateCode).isResident(isResident).ownerRole("MARKET_PM").build();
    }

    private static IpdActionSkillMap map(String actionCode, String subStageCode, int sort) {
        return IpdActionSkillMap.builder().actionCode(actionCode).subStageCode(subStageCode)
            .sortOrder(sort).skillNames(null).build();
    }

    /** 实例夹具：名称取 ActionCatalog 真名，深度/阻断/BioCV 与目录一致（真库可产生组合）。 */
    private static StageAction action(String code, String depth, String isBlocking,
                                      String status, String historyMark, String isBio) {
        return StageAction.builder().projectId(1001L).actionCode(code)
            .actionName(ActionCatalog.byCode(code).name())
            .ownerRole(ActionCatalog.byCode(code).ownerRole())
            .depth(depth).status(status).historyMark(historyMark)
            .isBlocking(isBlocking).isBioFeature(isBio).build();
    }

    @Test
    @DisplayName("不变量④放行：前序阻断动作全清（DONE/NA）→ 不抛")
    void advanceAllowedWhenAllBlockingSettled() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2),
            map("C02", "CONCEPT-S2", 1), map("C03", "CONCEPT-S2", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "DONE", null, "0"),
            action("C04", "DEEP", "1", "NA", null, "0")));

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("不变量④拦截：前序阻断动作 NOT_STARTED → 40001 且 message 点名动作码")
    void advanceBlockedByNotStartedBlockingAction() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "NOT_STARTED", null, "0"),
            action("C04", "DEEP", "1", "DONE", null, "0")));

        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                assertThat(ex.getMessage()).contains("C01");
            });
    }

    @Test
    @DisplayName("不变量④拦截：跨多小阶段点名全部待完成码（C12），非阻断码不入 message")
    void pendingListsAllBlockingCodesAcrossSubStages() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2),
            map("C02", "CONCEPT-S2", 1), map("C03", "CONCEPT-S2", 2),
            map("C06", "CONCEPT-S3", 1), map("C07", "CONCEPT-S3", 2),
            map("C08", "CONCEPT-S3", 3), map("C09", "CONCEPT-S3", 4),
            map("C05", "CONCEPT-S4", 1), map("C10", "CONCEPT-S4", 2),
            map("C12", "CONCEPT-S4", 3), map("C11", "CONCEPT-S4", 4)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "DONE", null, "0"),
            action("C04", "DEEP", "1", "DONE", null, "0"),
            action("C02", "DEEP", "1", "DONE", null, "0"),
            action("C03", "DEEP", "1", "DONE", null, "0"),
            action("C06", "DEEP", "1", "DONE", null, "0"),
            action("C07", "DEEP", "1", "DONE", null, "0"),
            action("C08", "DEEP", "1", "DONE", null, "0"),
            action("C09", "DEEP", "1", "DONE", null, "0"),
            action("C05", "LIGHT", "0", "NOT_STARTED", null, "0"),
            action("C10", "DEEP", "0", "NOT_STARTED", null, "0"),
            action("C12", "DEEP", "1", "NOT_STARTED", null, "1"),
            action("C11", "DEEP", "1", "NOT_STARTED", "HISTORICAL_MISSING", "0")));

        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "PLAN-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                assertThat(ex.getMessage()).contains("C12");
                assertThat(ex.getMessage()).doesNotContain("C05").doesNotContain("C10").doesNotContain("C11");
            });
    }

    @Test
    @DisplayName("NA 与 HISTORICAL_MISSING 均视为已满足（历史缺失不伪造 DONE）")
    void naAndHistoricalMissingSettleGate() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "NA", null, "0"),
            action("C04", "DEEP", "1", "NOT_STARTED", "HISTORICAL_MISSING", "0")));

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非阻断动作未完成不拦截（C05/C10 均 LIGHT/DEEP 非阻断）")
    void nonBlockingActionsDoNotBlock() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2),
            map("C02", "CONCEPT-S2", 1), map("C03", "CONCEPT-S2", 2),
            map("C06", "CONCEPT-S3", 1), map("C07", "CONCEPT-S3", 2),
            map("C08", "CONCEPT-S3", 3), map("C09", "CONCEPT-S3", 4),
            map("C05", "CONCEPT-S4", 1), map("C10", "CONCEPT-S4", 2),
            map("C12", "CONCEPT-S4", 3), map("C11", "CONCEPT-S4", 4)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "DONE", null, "0"),
            action("C04", "DEEP", "1", "DONE", null, "0"),
            action("C02", "DEEP", "1", "DONE", null, "0"),
            action("C03", "DEEP", "1", "DONE", null, "0"),
            action("C06", "DEEP", "1", "DONE", null, "0"),
            action("C07", "DEEP", "1", "DONE", null, "0"),
            action("C08", "DEEP", "1", "DONE", null, "0"),
            action("C09", "DEEP", "1", "DONE", null, "0"),
            action("C05", "LIGHT", "0", "NOT_STARTED", null, "0"),
            action("C10", "DEEP", "0", "NOT_STARTED", null, "0"),
            action("C12", "DEEP", "1", "DONE", null, "1"),
            action("C11", "DEEP", "1", "DONE", null, "0")));

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "PLAN-S1"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("实例缺行跳过（历史项目未全量实例化不拦，不造伪数据）")
    void missingInstanceRowsAreSkipped() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("目标为首个小阶段无前序，直接放行（不查实例）")
    void firstSubStageHasNoPredecessors() {
        when(subStageService.listAll()).thenReturn(catalog());
        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S1"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("常驻小阶段与未知码不可作为推进目标（10001 PARAM_INVALID）")
    void residentAndUnknownTargetRejected() {
        when(subStageService.listAll()).thenReturn(catalog());
        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "KPI-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "NOPE-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
    }
}
```

### A5.4 `SubStageAdvanceEndpointTest.java`

```java
package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.IpdActionSkillMapService;
import org.ruoyi.ipd.service.IpdSubStageService;
import org.ruoyi.ipd.service.SubStageGateService;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SubStageAdvanceEndpointTest {

    private static final IpdActor ACTOR = new IpdActor(11L, "市场PM-甲", "MARKET_PM", 900001L);

    @Mock
    private IpdSubStageService subStageService;
    @Mock
    private IpdActionSkillMapService skillMapService;
    @Mock
    private IpdPermission ipdPermission;
    @Mock
    private SubStageGateService subStageGateService;
    @InjectMocks
    private SubStageController controller;

    @Test
    @DisplayName("A5：门禁放行 → code0 + advanced=true + 字符串 projectId")
    void advancePassesWhenGateAllows() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);

        ApiV1Response<Map<String, Object>> resp = controller.advance(1001L, "CONCEPT-S2");

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getMessage()).isEqualTo("ok");
        Map<String, Object> body = resp.getData();
        assertThat(body.get("projectId")).isEqualTo("1001");
        assertThat(body.get("targetSubStageCode")).isEqualTo("CONCEPT-S2");
        assertThat(body.get("advanced")).isEqualTo(true);
    }

    @Test
    @DisplayName("A5：门禁拒绝（40001）原样上抛，由 IpdServiceExceptionAdvice 转包络")
    void advancePropagatesGateNotPassed() {
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
        doThrow(new IpdBusinessException(ApiV1ErrorCode.GATE_NOT_PASSED,
            "小阶段门禁未通过：阻断动作未完成 C01"))
            .when(subStageGateService).assertAdvanceAllowed(1001L, "CONCEPT-S2");

        assertThatThrownBy(() -> controller.advance(1001L, "CONCEPT-S2"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                assertThat(ex.getMessage()).contains("C01");
            });
    }
}
```

**验证**（A5 全部完成后，含 A3/A4 回归）：

```bash
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=SubStageGateServiceTest,SubStageAdvanceEndpointTest,SubStageControllerTest test
```

**失败路径**：
- `SubStageControllerTest` 编译错（构造器参数不匹配）→ A5.2 的第 4 依赖字段没补。
- `SubStageGateServiceTest` 红在放行/拦截 → 核对 SETTLED_STATUSES 口径与 `history_mark` 判定顺序（先状态后历史缺失，两者都跳过）。
- 真库联调时误把 Z 别名实例行判为未完成 → `resolveCode` 归一漏了实例侧（两侧都要归一：map 侧与 row 侧）。

**回滚**：删 `SubStageGateService.java`、`SubStageGateServiceTest.java`、`SubStageAdvanceEndpointTest.java`，从 `SubStageController`/`SubStageControllerTest` 撤掉 A5.2 追加项（回到 A4 版）。

**DONE 定义**：门禁服务 + advance 端点 + 两测试落盘；验证命令跑绿；不变量④有运行时锁与测试锁双保险。

---

## A6. 依赖顺序、总验收与未证明项

### A6.1 依赖顺序（一句话）

**A1（DDL/seed/租户登记/seed 合同）→ A2（实体·Mapper·Service）→ A3（查询接口）→ A4（AG-UI 工具，复用 A3 的 VO/Controller）→ A5（阻断门禁，复用 A2 Service 并给 A3 Controller 扩 advance）**——每步以前一步的产出为依赖，A4/A5 对 A3 Controller 是 Track 内自建文件的追加而非既有文件修改。

### A6.2 总验收（全 Track 完成后，错峰单会话单模块）

```bash
export PATH="$HOME/tools/maven/bin:$PATH"
export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"
cd /Users/mac/Documents/ruoyi-ai
mvn -o -pl ruoyi-modules/ruoyi-ipd \
  -Dtest=SubStageSeedSqlContractTest,IpdSubStageServiceTest,IpdActionSkillMapServiceTest,SubStageControllerTest,SubStageGuideToolTest,SubStageGateServiceTest,SubStageAdvanceEndpointTest,PermissionAdviceCoverageTest \
  test
```

8 个测试类全绿 + owner apply 后跑 §6.4.4 只读核验（22/69 行）= Track A 完成。

### A6.3 Track A 未证明项

1. **真库 apply 未执行**（红线）：DDL/seed/GRANT/`tenant.excludes` 生效均未经真库验证——SQL 禁止 AI 执行，apply 后须跑 §6.4.4 只读核验。
2. **不变量③矛盾**（§2.3/§2.6 与 §2.8 自相矛盾）：`GATE_SORT_MAX_EXEMPT={DEV-S2, LIFECYCLE-S2}` 为显式例外登记，**待 owner 拍板**（§6.5 两条出路）。
3. **skill_names 待 §3 定稿**：69 行映射 seed 的 skill_names 一律 NULL，§3 定稿后按行 UPDATE（单独 DDL 小包，不阻塞本 Track）。
4. **AG-UI 运行态未验**：`guide-events` 事件序由单测锁定，CopilotKit Inspector 的 tool call 走查需 apply 后联调（§6.6 项 6）。
5. **advance 无游标语义**：方案 A 不设项目级小阶段状态列，`advanced=true` 仅代表「允许推进」的校验结果；若产品要求可查询「当前处于哪一小阶段」，需另行立项（派生计算或游标表，超出本 Track）。
6. **动词/路径契约未与前端对表**：`GET /api/v1/ipd/stage/sub-stages`、`GET .../guide-events`、`POST .../advance` 的最终路径以 Track B 前端联调为准（路径变更仅动 `SubStageController` 的 `@RequestMapping`）。
