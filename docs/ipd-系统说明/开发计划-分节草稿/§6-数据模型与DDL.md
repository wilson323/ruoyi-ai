# §6 数据模型与 DDL 变更 — 分节草稿

> **状态**：草稿（供主计划 §6 合入前评审）　**产出日期**：2026-09-28
> **范围**：仅数据模型设计与 DDL 草案，**不修改任何既有文件、不对真库执行任何写操作**。
> **执行纪律**：本文所有 SQL 均标注「**待 owner apply**」——AI 产出 SQL、owner 人工执行；
> 未 apply 前严禁在 Java 实体/Mapper/Service 中加对应映射（`kb-partA-ddl-draft-20260928.sql` 头部纪律前车之鉴：真库 Unknown column 会连锁打红全部查询）。
> **事实源**：真库只读探针（MySQL 8.0.46 @ 127.0.0.1:13306 / `ipd_dev`，2026-09-28 实查）+ 主计划 §0.1/§2 + `ActionCatalog.java`（69 动作编译期 SSOT）+ `IPD系统_六阶段标准动作清单_v3.md`（业务权威）。

---

## 6.1 现状表结构核对（真库实查）

### 6.1.1 探针命令与输出（只读，凭证走 `--defaults-extra-file` 不上命令行）

```bash
cd /Users/mac/Documents/ruoyi-ai && mysql \
  --defaults-extra-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf ipd_dev \
  -e "SHOW COLUMNS FROM stage_actions; SHOW COLUMNS FROM project_stages;
      SHOW INDEX FROM stage_actions; SHOW INDEX FROM project_stages;
      SELECT COUNT(*), COUNT(DISTINCT project_id), COUNT(DISTINCT action_code) FROM stage_actions;
      SELECT action_code, COUNT(*), COUNT(DISTINCT project_id) FROM stage_actions
        WHERE action_code IN ('A01','A02','A1','A2') GROUP BY action_code;"
```

### 6.1.2 `stage_actions` 关键列（SHOW COLUMNS 实查 30 列）

| 列 | 类型 | 空 | 键 | 默认 | 备注 |
|---|---|---|---|---|---|
| id | bigint | NO | PRI | NULL | 雪花（应用侧 ASSIGN_ID） |
| project_id | bigint | NO | MUL | NULL | idx_sa_project / idx_sa_project_code |
| stage_id | bigint | NO | MUL | NULL | idx_sa_stage |
| action_code | varchar(8) | NO | MUL | NULL | idx_sa_code / idx_sa_project_code |
| action_name | varchar(128) | NO | | NULL | |
| owner_role | varchar(16) | NO | | NULL | MARKET_PM\|RD_PM\|BOTH\|GROUP_LEADER |
| depth | varchar(8) | NO | | NULL | DEEP\|LIGHT |
| status | varchar(16) | NO | | NOT_STARTED | NOT_STARTED\|IN_PROGRESS\|DONE\|DELAYED\|NA |
| confirmed_at / confirmed_by | datetime / bigint | YES | | NULL | 完成确认 |
| history_mark | varchar(32) | YES | | NULL | HISTORICAL_MISSING=历史缺失 |
| is_blocking | char(1) | NO | | 0 | 阻断标记（门禁消费） |
| actual_done_at | datetime | YES | | NULL | |
| far_value / frr_value | decimal(10,6) | YES | | NULL | D11 FAR/FRR |
| cert_no / cert_passed_at | varchar(64) / datetime | YES | | NULL | V02 证书 |
| algo_type | varchar(16) | YES | | NULL | FINGERPRINT\|FACE\|PALM\|VEIN\|MULTI |
| is_bio_feature | char(1) | NO | | 0 | |
| due_date | datetime | YES | | NULL | |
| sop_id | bigint | YES | | NULL | |
| version | int | NO | | 0 | 乐观锁（@Version） |
| create_dept / create_by / create_time / update_by / update_time | 审计列 | | | | 与 BaseEntity 对齐 |
| tenant_id | varchar(20) | YES | | 000000 | 已登记 `tenant.excludes` |
| del_flag | char(1) | YES | | 0 | @TableLogic |
| remark | varchar(500) | YES | | NULL | |

**小阶段相关列：无**（无 `sub_stage*` / `parent_code` / `sort_order` 之外的层级列）。

### 6.1.3 `project_stages` 关键列（SHOW COLUMNS 实查 17 列）

`id(bigint PRI)` / `project_id(bigint MUL,idx_stages_project)` / `stage_code(varchar16: CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE)` / `stage_name(varchar64)` / `sort_order(int)` / `status(varchar16: NOT_STARTED|IN_PROGRESS|DONE)` / `gate_id(bigint)` / `started_at` / `completed_at` / 审计列 / `tenant_id('000000')` / `del_flag(char1)` / `remark`。

**小阶段相关列：无**。索引仅 PRIMARY + `idx_stages_project`（无 (project_id, stage_code) 唯一键，本节不动它——存量治理另立项）。

### 6.1.4 规模与脏数据实查

| 探针 | 输出 |
|---|---|
| `SELECT COUNT(*), COUNT(DISTINCT project_id), COUNT(DISTINCT action_code) FROM stage_actions` | **19655 行 / 290 项目 / 73 唯一 code** |
| 69 真动作行数（正则 `^(LC[0-9]{2}\|[CPDVK][0-9]{2}\|L[0-9]{2})$`） | **19613 行** |
| 脏数据分组 | `A01` 3 行/3 项目、`A02` 3 行/3 项目、`A1` 18 行/3 项目、`A2` 18 行/3 项目（合计 **42 行**） |
| `SELECT COUNT(*) FROM project_stages` | **1746 行** |
| `SHOW TABLES LIKE 'ipd_sub%'` / `LIKE 'ipd_action%'` | **均无**（两张新表名未被占用，CREATE 安全） |

### 6.1.5 口径差异登记（实查 vs 任务书/主计划）

| 项 | 任务书/主计划记 | 实查 | 归因与处置 |
|---|---|---|---|
| `stage_actions` 列数 | 29 | **30** | 差 `remark`（计数口径）；以实查为准，不影响设计 |
| `project_stages` 列数 | 16 | **17** | 同上 |
| 脏数据行数 | 「各仅 3 个项目」 | A01/A02 各 3 行、**A1/A2 各 18 行** | 项目数确为各 3；行数按实查 42 行登记 |

**结论**：`stage_actions`（29/30 列）与 `project_stages`（16/17 列）均**无小阶段字段**；22 小阶段 × 69 动作归属只能由**新增元数据表**承载（见 6.2），或改存量表（对比分析见 6.3，不推荐）。

---

## 6.2 两张新元数据表 DDL 草案（**待 owner apply**）

### 6.2.0 设计原则

1. **元数据表 ≠ 实例表**：两表是 22 小阶段目录与 69 动作映射的**全局 SSOT**，与项目实例（`stage_actions` 19655 行）解耦；目录行只由 owner 经 DDL 维护，应用侧**只读**（无写端点，防多写者）。
2. **动作归属唯一且派生**：`action_code → sub_stage_code` 是 69 对 69 的纯函数；按 §2.8 不变量①，`ipd_action_skill_map.action_code` 加**唯一键**锁死。
3. **脏数据库级排除**：`action_code` 加 CHECK REGEXP，`A01/A02/A1/A2` 在 schema 层即不可插入（不变量⑤）。
4. **仓库惯例对齐**：id 雪花（应用侧 `ASSIGN_ID`，DDL 不设 auto_increment）、审计列同 `BaseEntity`、`tenant_id varchar(20) DEFAULT '000000'`、`del_flag char(1) DEFAULT '0'`（与 `stage_actions`/`project_stages` 同口径，非 kpi 系的 tinyint）。
5. **不用外键**：`docs/script/sql/update/**` 全目录 grep `FOREIGN KEY` **零命中**（仓库 0 前例）；且 `del_flag` 软删 + FK RESTRICT 相互打架。参照完整性由唯一键 + seed 合同单测锁定（6.5）。

### 6.2.1 `ipd_sub_stage` — 22 小阶段目录

```sql
-- =====================================================================
-- 文件：2026-09-28-ipd-sub-stage-ddl.sql（见 6.4 落盘命名）
-- 状态：DO NOT APPLY — 待 owner apply
-- 依据：主计划 §2.1-2.7（22 小阶段拆解表）；事实基线 §0.1
-- 语法：MySQL 8.0（真库 8.0.46）
-- =====================================================================

CREATE TABLE IF NOT EXISTS ipd_sub_stage (
    id           bigint       NOT NULL                     COMMENT '主键（应用侧雪花 ASSIGN_ID）',
    code         varchar(32)  NOT NULL                     COMMENT '小阶段编码（CONCEPT-S1..KPI-S1，全局唯一）',
    name         varchar(64)  NOT NULL                     COMMENT '小阶段名称（主计划 §2 表名）',
    stage_code   varchar(16)  NOT NULL                     COMMENT '所属大阶段 CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI（KPI=常驻跨阶段）',
    sort_order   int          NOT NULL DEFAULT 0           COMMENT '阶段内排序（六阶段从 1 连续；KPI-S1=99 常驻豁免）',
    is_gate      char(1)      NOT NULL DEFAULT '0'         COMMENT '是否承载大阶段 Gate（1=是）',
    gate_code    varchar(8)   DEFAULT NULL                 COMMENT 'Gate 编码 G1..G5（is_gate=1 必填，唯一）',
    skill_hint   varchar(255) DEFAULT NULL                 COMMENT 'pm-skills 插件级引导提示（skill/command 级待 §3 定稿）',
    is_resident  char(1)      NOT NULL DEFAULT '0'         COMMENT '常驻小阶段（1=跨阶段 KPI 归集，不参与顺序推进门禁）',
    owner_role   varchar(16)  DEFAULT NULL                 COMMENT '主导角色 MARKET_PM|RD_PM|BOTH|GROUP_LEADER',
    remark       varchar(500) DEFAULT NULL                 COMMENT '备注',
    tenant_id    varchar(20)  DEFAULT '000000'             COMMENT '租户ID（单企业部署恒 000000，登记 tenant.excludes）',
    del_flag     char(1)      DEFAULT '0'                   COMMENT '删除标志（0正常 1已删）',
    create_by    bigint       DEFAULT NULL                 COMMENT '创建者',
    create_dept  bigint       DEFAULT NULL                 COMMENT '创建部门',
    create_time  datetime     DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
    update_by    bigint       DEFAULT NULL                 COMMENT '更新者',
    update_time  datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sub_stage_code (code),
    UNIQUE KEY uk_sub_stage_stage_sort (stage_code, sort_order),
    CONSTRAINT chk_sub_stage_code CHECK (code REGEXP '^(CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI)-S[0-9]{1,2}$'),
    CONSTRAINT chk_sub_stage_flag CHECK (is_gate IN ('0','1') AND is_resident IN ('0','1'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IPD 小阶段目录（22 行元数据，主计划 §2 SSOT 落库）';
```

**键与索引设计**：

| 设计 | 说明 |
|---|---|
| `uk_sub_stage_code (code)` UNIQUE | 小阶段码全局唯一；查询/映射 join 键；Java `getByCode` 收口 |
| `uk_sub_stage_stage_sort (stage_code, sort_order)` UNIQUE | 同大阶段内排序号唯一（不变量②「连续从 1」的可库锁部分；「连续」由单测锁，见 6.5） |
| `chk_sub_stage_code` CHECK | 编码词表库级锁定（防手滑写 `CONPTE-S1`） |
| `chk_sub_stage_flag` CHECK | is_gate/is_resident 只允许 '0'/'1' |
| 不设 FK | 见 6.2.0 原则 5 |
| 无 `version` 列 | 元数据只读场景，无并发写；如未来开管理端编辑再补（单独 DDL） |

**tenant.excludes 登记**：**需要**。理由：① 与 `stage_actions`/`project_stages` 等 26+2 张 IPD 表同口径（单企业私有部署、`tenant_id` 恒 '000000'、无多租户语义）；② 目录读取会出现在 AG-UI / 定时任务等**无租户上下文**的异步路径（`t_workflow_checkpoint` 前车之鉴：不登记则拦截器追加 `WHERE tenant_id=?` 表现为「查不到小阶段」）。登记位置：`ruoyi-admin/src/main/resources/application.yml` 的 **`tenant.excludes`** 列表（与 `stage_actions` 相邻段落，带注释，见 Track A Task A1）。**两表都要登记。**

**seed 来源**：主计划 §2.1-2.7 拆解表（code/名称/sort/Stage/Gate/主导角色逐行转录）；`skill_hint` 仅填 §0.4 已实证的 **9 插件级**名称，skill/command 级待 §3 定稿（6.4）。

### 6.2.2 `ipd_action_skill_map` — 69 动作 × 小阶段 × pm-skill 映射

```sql
-- =====================================================================
-- 文件：2026-09-28-ipd-action-skill-map-ddl.sql（与上表可合一份，见 6.4 命名）
-- 状态：DO NOT APPLY — 待 owner apply
-- =====================================================================

CREATE TABLE IF NOT EXISTS ipd_action_skill_map (
    id             bigint       NOT NULL                     COMMENT '主键（应用侧雪花 ASSIGN_ID）',
    action_code    varchar(8)   NOT NULL                     COMMENT '动作编码（69 真动作；A01/A02/A1/A2 由 CHECK 排除）',
    sub_stage_code varchar(32)  NOT NULL                     COMMENT '归属小阶段（→ ipd_sub_stage.code）',
    skill_names    json         DEFAULT NULL                 COMMENT 'pm-skills 技能/命令 JSON 数组（§3 定稿后补齐；NULL=未定稿）',
    sort_order     int          NOT NULL DEFAULT 0           COMMENT '小阶段内动作排序（从 1 连续）',
    remark         varchar(500) DEFAULT NULL                 COMMENT '备注（seed 标注待 §3）',
    tenant_id      varchar(20)  DEFAULT '000000'             COMMENT '租户ID（登记 tenant.excludes）',
    del_flag       char(1)      DEFAULT '0'                   COMMENT '删除标志（0正常 1已删）',
    create_by      bigint       DEFAULT NULL                 COMMENT '创建者',
    create_dept    bigint       DEFAULT NULL                 COMMENT '创建部门',
    create_time    datetime     DEFAULT CURRENT_TIMESTAMP    COMMENT '创建时间',
    update_by      bigint       DEFAULT NULL                 COMMENT '更新者',
    update_time    datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_map_action_code (action_code),
    KEY idx_map_sub_stage (sub_stage_code),
    CONSTRAINT chk_map_action_code CHECK (action_code REGEXP '^(LC[0-9]{2}|[CPDVK][0-9]{2}|L[0-9]{2})$'),
    CONSTRAINT chk_map_sub_stage_code CHECK (sub_stage_code REGEXP '^(CONCEPT|PLAN|DEV|VALID|LAUNCH|LIFECYCLE|KPI)-S[0-9]{1,2}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='IPD 动作×小阶段×pm-skill 映射（69 行元数据）';
```

**键与索引设计**：

| 设计 | 说明 |
|---|---|
| `uk_map_action_code (action_code)` UNIQUE | **不变量①的库锁**：每个动作恰好归属一个小阶段（69 行 69 码，插第二个归属直接违反唯一键） |
| `idx_map_sub_stage (sub_stage_code)` | 按小阶段取动作（门禁/引导查询路径） |
| `chk_map_action_code` CHECK | **不变量⑤的库锁**：`A01`/`A02`/`A1`/`A2` 词形上插不进（`A1`/`A2` 只有 2 字符、`A01`/`A02` 前缀不在 `[CPDVK]`/`LC`/`L` 白名单） |
| `skill_names json` | MySQL json 列**存储端强制合法 JSON**（非法文本插不入）；MyBatis-Plus 实体映射 `String`，读写文本透明（与 `agent_info.skill_names` 的 JSON 数组文本同消费姿势）。选 json 而非 varchar：把「JSON 必须合法」从应用校验下沉到 DB |
| `uk_map_action_code` 不含 `del_flag` | 目录行不可软删重建（防影子映射）；真要删改走 owner DDL 硬删 + 合同单测重跑 |
| 不设 FK（含不设 `sub_stage_code → ipd_sub_stage.code` FK） | 见 6.2.0 原则 5；孤儿防护由 seed 合同单测（sub_stage_code ⊆ 目录 code 集）锁定 |

**tenant.excludes 登记**：**需要**（同 6.2.1 理由，登记键 `tenant.excludes`，两表一起）。

**seed 来源**：69 行 `action_code → sub_stage_code → sort_order` 取自主计划 §2 拆解表的分组（与 `§3-pm-skills映射.md` §3.2 映射表的子阶段归属列**逐行一致**，已对账）；`skill_names` 一律 `NULL`，`remark='待 §3 定稿后补齐 skill_names'`——§3.2 已列每动作绑定候选（如 C01→`interview-script`/`summarize-interview`/`market-sizing`），§3 定稿后按行搬入即可，**不在本稿臆填**。

### 6.2.3 表名/列名与动作名称口径（防漂移设计）

映射表**故意不存动作名称**——名称 SSOT 在 `ActionCatalog`（编译期，源自 v3 业务权威）与 `stage_actions.action_name`（实例快照）。主计划 §2 表内的动作名称/深度与 v3 存在漂移（见 6.4.3），映射表只存 `action_code` 使 69 行 seed **对名称漂移免疫**；join 出名称时以 `ActionCatalog` 为准。

---

## 6.3 为何不改 `stage_actions` 加列（对比分析与推荐）

**问题**：给 19655 行的 `stage_actions` 加 `sub_stage_code` 列，还是新增 69 行映射表按 `action_code` 派生归属？

| 维度 | 方案 A：新增 `ipd_action_skill_map`（**推荐**） | 方案 B：`ALTER TABLE stage_actions ADD COLUMN sub_stage_code` |
|---|---|---|
| 存量迁移 | **零迁移**：不动 19655 行，无 ALTER、无锁表、无回填、无停机窗口 | ALTER 本身 MySQL 8 可 INSTANT，但**回填需 UPDATE 19655 行**（19613 真动作 + 42 脏数据）；回填脚本本身是风险物 |
| 事实源 | 69 行单点事实；目录变更一处改 | **双事实源**：目录 + 19655 行各存一份；目录调整要全表回填，漏一列就是漂移 |
| 脏数据 | CHECK 库级排除 A01/A02/A1/A2 | 42 行脏数据的 `sub_stage_code` 填什么？引入第三态（NULL/特殊值），全链路都要教 |
| 单写者 | `stage_actions` 写路径（`transit`/`recordFields`/`confirm`/`instantiate`/逾期调度）**一行不动**；新表只读 | 写路径扩散：`StageActionService` 实例化/完成校验、`ActionCatalog` 挂载、逾期扫描全要感知新列 |
| ORM 兼容 | `StageAction` 实体（含 `@Version` 乐观锁、`@TableLogic`）不动；查询 = 69 行维表内存归并（无 N+1） | 实体加字段（`@TableName(autoResultMap)` 可容）；但既有 6+ 个测试类 `new StageActionService(...)` 的数据组合语义全变 |
| 回滚 | `DROP TABLE` ×2 + 摘 `tenant.excludes` 两行，业务零影响 | `DROP COLUMN` + 回填回滚语义难证（19655 行快照） |
| 查询成本 | join/归并一次 69 行维表（可忽略） | 少一次 join（收益可忽略） |
| 未来弹性 | 动作归属唯一（不变量①）时完备 | 仅当未来出现「同一动作按模板挂不同小阶段」才需要行级列——§2 明确归属唯一，**前提不成立** |

**推荐：方案 A。** `stage_actions` 不加列、不回填、不动实体与写路径；小阶段归属一律经 `ipd_action_skill_map` 按 `action_code`（Z 别名经 `ActionCatalog.resolveCode` 归一）派生。唯一让步是查询多一次 69 行内存归并——与 19655 行的迁移风险相比可忽略。

---

## 6.4 seed 脚本（**待 owner apply**，与 DDL 同批执行）

### 6.4.1 落盘命名（Task A1，`docs/script/sql/update/`）

| 文件 | 内容 | 状态 |
|---|---|---|
| `2026-09-28-ipd-sub-stage-skill-map-ddl.sql` | 两张 CREATE TABLE（6.2.1 + 6.2.2） | **待 owner apply** |
| `2026-09-28-ipd-sub-stage-seed.sql` | 22 行小阶段 INSERT（下 6.4.2） | **待 owner apply** |
| `2026-09-28-ipd-action-skill-map-seed.sql` | 69 行映射 INSERT（下 6.4.3） | **待 owner apply** |

执行顺序：DDL → 两个 seed → `tenant.excludes` 登记 → 只读核验（6.4.4）。

### 6.4.2 `ipd_sub_stage` 22 行 seed（主计划 §2 逐行转录）

```sql
-- 2026-09-28-ipd-sub-stage-seed.sql — 状态：待 owner apply（DDL 未 apply 前禁止执行）
INSERT INTO ipd_sub_stage (id, code, name, stage_code, sort_order, is_gate, gate_code, skill_hint, is_resident, owner_role, remark) VALUES
(1, 'CONCEPT-S1', '市场洞察', 'CONCEPT', 1, '0', NULL, 'pm-product-discovery + pm-market-research', '0', 'MARKET_PM', 'seed：主计划 §2.1'),
(2, 'CONCEPT-S2', '竞争与客群', 'CONCEPT', 2, '0', NULL, 'pm-market-research', '0', 'MARKET_PM', 'seed：主计划 §2.1'),
(3, 'CONCEPT-S3', '商业论证', 'CONCEPT', 3, '0', NULL, 'pm-product-strategy', '0', 'MARKET_PM', 'seed：主计划 §2.1'),
(4, 'CONCEPT-S4', '合规与立项', 'CONCEPT', 4, '1', 'G1', 'pm-toolkit', '0', 'BOTH', 'seed：主计划 §2.1，承载 G1 立项 Go/No-Go'),
(5, 'PLAN-S1', '需求定义', 'PLAN', 1, '0', NULL, 'pm-product-strategy', '0', 'BOTH', 'seed：主计划 §2.2'),
(6, 'PLAN-S2', '技术方案', 'PLAN', 2, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.2'),
(7, 'PLAN-S3', '计划与资源', 'PLAN', 3, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.2'),
(8, 'PLAN-S4', '合规与差异化确认', 'PLAN', 4, '1', 'G2', 'pm-product-strategy', '0', 'BOTH', 'seed：主计划 §2.2，承载 G2 差异化确认'),
(9, 'DEV-S1', '设计与联调准备', 'DEV', 1, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.3'),
(10, 'DEV-S2', '迭代开发', 'DEV', 2, '1', 'G3', 'pm-execution + pm-ai-shipping', '0', 'RD_PM', 'seed：主计划 §2.3，承载 G3 开发双周评审'),
(11, 'DEV-S3', '变更与成本', 'DEV', 3, '0', NULL, 'pm-execution', '0', 'BOTH', 'seed：主计划 §2.3'),
(12, 'VALID-S1', '设计验证', 'VALID', 1, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.4'),
(13, 'VALID-S2', '认证与适配', 'VALID', 2, '0', NULL, 'pm-execution + pm-toolkit', '0', 'MARKET_PM', 'seed：主计划 §2.4'),
(14, 'VALID-S3', '试产与量产准入', 'VALID', 3, '0', NULL, 'pm-execution', '0', 'RD_PM', 'seed：主计划 §2.4'),
(15, 'VALID-S4', '客户与交付', 'VALID', 4, '0', NULL, 'pm-go-to-market', '0', 'MARKET_PM', 'seed：主计划 §2.4'),
(16, 'LAUNCH-S1', 'GTM 策略', 'LAUNCH', 1, '0', NULL, 'pm-go-to-market', '0', 'MARKET_PM', 'seed：主计划 §2.5'),
(17, 'LAUNCH-S2', '销售赋能', 'LAUNCH', 2, '0', NULL, 'pm-go-to-market + pm-marketing-growth', '0', 'MARKET_PM', 'seed：主计划 §2.5'),
(18, 'LAUNCH-S3', '上市执行', 'LAUNCH', 3, '1', 'G4', 'pm-go-to-market', '0', 'BOTH', 'seed：主计划 §2.5，承载 G4 GTM 就绪'),
(19, 'LIFECYCLE-S1', '上市追踪', 'LIFECYCLE', 1, '0', NULL, 'pm-data-analytics', '0', 'MARKET_PM', 'seed：主计划 §2.6'),
(20, 'LIFECYCLE-S2', '复盘与结算', 'LIFECYCLE', 2, '1', 'G5', 'pm-data-analytics + pm-toolkit', '0', 'BOTH', 'seed：主计划 §2.6，承载 G5 上市后 90 天复盘'),
(21, 'LIFECYCLE-S3', '状态与退出', 'LIFECYCLE', 3, '0', NULL, 'pm-toolkit', '0', 'MARKET_PM', 'seed：主计划 §2.6，与 GROUP_LEADER 共担 LC09'),
(22, 'KPI-S1', '共担KPI归集（常驻）', 'KPI', 99, '0', NULL, 'pm-data-analytics', '1', 'GROUP_LEADER', 'seed：主计划 §2.7 常驻跨阶段');
```

说明：
- `stage_code='KPI'` 是**设计决策**：K01-K04 在 `stage_actions` 仍挂 LIFECYCLE（`ActionCatalog` 挂载口径不变），小阶段层给 KPI-S1 独立伪阶段 + `is_resident=1`，使「每阶段 sort 连续从 1」「Gate 必为 sort 最大」两条不变量（6.5）无需为常驻行破例。备选（挂 LIFECYCLE + sort=99）会使 LIFECYCLE 的 sort 集合变成 {1,2,3,99}，连续性校验被迫打洞，不取。
- `owner_role` 取主计划 §2「主导角色」列单值；LIFECYCLE-S3 的「MARKET_PM / GROUP_LEADER」以 MARKET_PM 落值、remark 登记共担。
- `skill_hint` 为 §0.4 实证的插件名组合；skill/command 级提示待 §3 定稿后 UPDATE（单独 DDL 小包，不阻塞 A 轨）。

### 6.4.3 `ipd_action_skill_map` 69 行 seed（`skill_names` 待 §3 定稿后补齐）

```sql
-- 2026-09-28-ipd-action-skill-map-seed.sql — 状态：待 owner apply（DDL 未 apply 前禁止执行）
-- skill_names 一律 NULL：§3-pm-skills映射.md §3.2 已列每动作绑定候选，定稿后按行 UPDATE 为 JSON 数组
INSERT INTO ipd_action_skill_map (id, action_code, sub_stage_code, skill_names, sort_order, remark) VALUES
(1, 'C01', 'CONCEPT-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(2, 'C04', 'CONCEPT-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(3, 'C02', 'CONCEPT-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(4, 'C03', 'CONCEPT-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(5, 'C06', 'CONCEPT-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(6, 'C07', 'CONCEPT-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(7, 'C08', 'CONCEPT-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(8, 'C09', 'CONCEPT-S3', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(9, 'C05', 'CONCEPT-S4', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(10, 'C10', 'CONCEPT-S4', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(11, 'C12', 'CONCEPT-S4', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(12, 'C11', 'CONCEPT-S4', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(13, 'P01', 'PLAN-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(14, 'P02', 'PLAN-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(15, 'P03', 'PLAN-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(16, 'P04', 'PLAN-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(17, 'P05', 'PLAN-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(18, 'P06', 'PLAN-S2', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(19, 'P07', 'PLAN-S2', NULL, 5, '待 §3 定稿后补齐 skill_names'),
(20, 'P08', 'PLAN-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(21, 'P09', 'PLAN-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(22, 'P11', 'PLAN-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(23, 'P10', 'PLAN-S4', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(24, 'P12', 'PLAN-S4', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(25, 'P13', 'PLAN-S4', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(26, 'D01', 'DEV-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(27, 'D02', 'DEV-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(28, 'D03', 'DEV-S1', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(29, 'D10', 'DEV-S1', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(30, 'D04', 'DEV-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(31, 'D07', 'DEV-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(32, 'D11', 'DEV-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(33, 'D09', 'DEV-S2', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(34, 'D05', 'DEV-S2', NULL, 5, '待 §3 定稿后补齐 skill_names'),
(35, 'D06', 'DEV-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(36, 'D08', 'DEV-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(37, 'V01', 'VALID-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(38, 'V04', 'VALID-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(39, 'V11', 'VALID-S1', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(40, 'V02', 'VALID-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(41, 'V10', 'VALID-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(42, 'V12', 'VALID-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(43, 'V05', 'VALID-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(44, 'V06', 'VALID-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(45, 'V03', 'VALID-S4', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(46, 'V09', 'VALID-S4', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(47, 'V07', 'VALID-S4', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(48, 'V08', 'VALID-S4', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(49, 'L01', 'LAUNCH-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(50, 'L02', 'LAUNCH-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(51, 'L03', 'LAUNCH-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(52, 'L04', 'LAUNCH-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(53, 'L05', 'LAUNCH-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(54, 'L06', 'LAUNCH-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(55, 'L07', 'LAUNCH-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(56, 'L08', 'LAUNCH-S3', NULL, 4, '待 §3 定稿后补齐 skill_names'),
(57, 'LC01', 'LIFECYCLE-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(58, 'LC05', 'LIFECYCLE-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(59, 'LC06', 'LIFECYCLE-S1', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(60, 'LC02', 'LIFECYCLE-S2', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(61, 'LC03', 'LIFECYCLE-S2', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(62, 'LC04', 'LIFECYCLE-S2', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(63, 'LC07', 'LIFECYCLE-S3', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(64, 'LC08', 'LIFECYCLE-S3', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(65, 'LC09', 'LIFECYCLE-S3', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(66, 'K01', 'KPI-S1', NULL, 1, '待 §3 定稿后补齐 skill_names'),
(67, 'K02', 'KPI-S1', NULL, 2, '待 §3 定稿后补齐 skill_names'),
(68, 'K03', 'KPI-S1', NULL, 3, '待 §3 定稿后补齐 skill_names'),
(69, 'K04', 'KPI-S1', NULL, 4, '待 §3 定稿后补齐 skill_names');
```

覆盖核对：12+13+11+12+8+9+4 = **69**，与 `ActionCatalog.ALL` 逐码相等（由 Task A1 的 seed 合同单测持续锁定）。

### 6.4.3.1 动作名称/深度口径差异登记（§2 表 vs v3/ActionCatalog，已与 §3 草稿口径一致）

§3 草稿口径规则 1 明确「code 与名称逐字取自 v3，与主计划 §2 不一致处以 v3 为准」。映射 seed 只存 code（6.2.3）不受影响，但评审需知差异存在：

| code | 主计划 §2 表述 | v3 / ActionCatalog（权威） | 差异 |
|---|---|---|---|
| P02 | 渠道商对接名单确认 | 需求优先级排序与版本规划 | **名称不同** |
| P03 | 总体技术方案与系统架构设计（深） | 同名（**轻**） | 深度不同 |
| P04 | ID/结构/硬件/固件方案设计（深） | 同名（**轻**） | 深度不同 |
| D01/D02/D03 | 智能锁通信协议评审 / 联动场景用例设计 / 固件联调计划（深） | 详细设计 / 首版BOM冻结与采购下单 / 手板 EVT样机制作（**轻**） | **名称+深度不同** |
| V11 | 平台兼容性与SDK-API对接验证（深） | 同名（**轻**，解决方案模板升级深管） | 深度不同 |

处置：映射与门禁只消费 `action_code`/`is_blocking`/`status`（实例列），对名称/深度漂移免疫；名称展示一律 `ActionCatalog`；差异合并主计划时由 owner 定稿（见 6.6 未证明项 2）。

### 6.4.4 apply 后只读核验（owner 执行，探针只读）

```bash
mysql --defaults-extra-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf ipd_dev \
  -e "SHOW TABLES LIKE 'ipd_sub_stage'; SHOW TABLES LIKE 'ipd_action_skill_map';
      SELECT COUNT(*) FROM ipd_sub_stage;            -- 期望 22
      SELECT COUNT(*), COUNT(DISTINCT action_code) FROM ipd_action_skill_map;  -- 期望 69 / 69
      SELECT stage_code, COUNT(*) FROM ipd_sub_stage GROUP BY stage_code;      -- 期望 4/4/3/4/3/3/1"
```

另注意：若 ipd_dev 连接用户非 ALL 权限，需按 `2026-09-09-ipd-grant-c-batch-4-dml.sql` 先例补表级 GRANT 登记件（**待 owner apply**，含 GRANT 的 DML 同样禁止 AI 执行）。

---

## 6.5 校验不变量的落库约束（主计划 §2.8 五条 → 机制映射）

| # | 不变量（§2.8 原文） | 库锁（唯一键/CHECK/FK） | 单测锁（`@Tag("dev")`，Task A1 seed 合同） | 运行时锁 |
|---|---|---|---|---|
| ① | 每个 `action_code`（69 个）恰好归属一个 `sub_stage_code` | `uk_map_action_code` UNIQUE（第二归属插不进） | seed 解析后断言 69 行 / 69 唯一码 / 与 `ActionCatalog.ALL` 集合相等 | 服务 join 按 code 取唯一行 |
| ② | 每阶段的小阶段 `sort_order` 连续从 1 开始 | `uk_sub_stage_stage_sort` 只能锁「不重复」，「连续」库锁不了（CHECK 表达式做不了跨行） | 按 `stage_code` 分组断言 sort 集合 = {1..n}（KPI-S1=99 例外单列断言） | `IpdSubStageService.listAll()` 排序口径固化 |
| ③ | 承载 Gate 的小阶段必须是该阶段 `sort_order` 最大者（KPI-S1 除外） | 无（跨行约束） | 断言 5 个 Gate 行 `gate_code` = G1..G5 且 sort=阶段最大，**例外登记 `{DEV-S2, LIFECYCLE-S2}`**（见下） | — |
| ④ | 阻断动作（38 个）所在小阶段未完成时，禁止推进到下一小阶段 | 无（业务规则） | `SubStageGateServiceTest` 6 例（放行/拦截/NA/历史缺失/缺行/常驻拒绝） | `SubStageGateService.assertAdvanceAllowed`（40001 GATE_NOT_PASSED，fail-closed） |
| ⑤ | 4 条脏数据 `A01/A02/A1/A2` 不得出现在任何小阶段映射 | `chk_map_action_code` CHECK（词形库级排除） | 断言 69 码与脏数据集合零交集（测试内显式点名 4 码） | `ActionCatalog.resolveCode` 归一（Z 别名不入表） |

**不变量③的矛盾与例外登记（待 owner 拍板）**：主计划 §2.3 的 DEV-S2 承载 **G3**，但同阶段 DEV-S3(sort=3) 在其后；§2.6 的 LIFECYCLE-S2 承载 **G5**，但 LIFECYCLE-S3(sort=3) 在其后——按 §2.8 原文两处**自相矛盾**。本稿按 §2 拆解表落库（Gate 归属不动），合同单测把 `{DEV-S2, LIFECYCLE-S2}` 国化为**显式例外集**（GATE_SORT_MAX_EXEMPT），任何新增例外都会让单测红。两条出路（owner 二选一）：
1. **维持归属，修订不变量③措辞**为「Gate 小阶段是该阶段 sort 最大的**门禁型**小阶段」（G3 双周评审是滚动门、G5 后随收尾行）→ 例外集并入主计划文本；
2. **维持不变量，调整排序**（DEV-S3 提前或 Gate 移位）→ 改 §2 拆解表后重生成 seed，例外集清空。

**为何不用外键**：本目录 0 前例（6.2.0）；①⑤ 已有 CHECK+唯一键，映射→目录的参照完整性由 seed 合同单测断言（`sub_stage_code ⊆ 目录 code 集`、每小阶段 ≥1 动作）——比 FK 更适合软删（`@TableLogic`）+ 人工 DDL 流。

**测试红线**（规约对齐）：所有新测试类必须 `@Tag("dev")`（根 pom surefire `<groups>${profiles.active}</groups>`，无 tag = 静默跳过假绿）；mock 数据组合必须真库可产生（如 LIGHT 动作不得造 DELAYED、is_blocking 只 '0'/'1'）。

---

## 6.6 未证明项 / 待决清单

1. **不变量③ vs §2.3/§2.6 矛盾**（DEV-S2/G3、LIFECYCLE-S2/G5 非 sort 最大）——已国化例外，措辞修订或排序调整**待 owner 拍板**（6.5）。
2. **动作名称/深度漂移**（P02/P03/P04/D01-D03/V11 等，6.4.3.1）——名称以 v3/ActionCatalog 为准已免疫映射层，但主计划 §2 文本的最终措辞**待 owner 定稿**。
3. **skill_names / skill_hint 的 skill·command 级取值**——§3 草稿已列候选，**待 §3 定稿**后按 69 行 UPDATE（本稿一律 NULL，不臆填）。
4. **真库 apply 未执行**（红线：SQL 禁止 AI 执行）——22/69 行数、CHECK 约束、`tenant.excludes` 生效均未经真库验证；apply 后须跑 6.4.4 只读核验 + Task A1 seed 合同单测。
5. **表级 GRANT 是否需要**——取决于 ipd_dev 连接用户权限，apply 时按 c-batch 先例核对。
6. **AG-UI 工具与主 run 链的深度接线**（把引导卡片注入 `AgUiCopilotRun` 的 done 帧）需改既有文件，超出本草稿授权；Track A4 以独立 `guide-events` 端点下发事件序列，运行态（Inspector tool call 走查）未验。
7. **列数计数口径**：实查 30/17 vs 任务书 29/16（remark 计数差，6.1.5），无设计影响。
8. **`project_stages` 无 (project_id, stage_code) 唯一键**——存量问题，本节不动；是否补唯一键由存量治理另立项。
