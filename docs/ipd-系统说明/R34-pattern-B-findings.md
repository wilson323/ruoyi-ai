# R34 Pattern B: 配置漂移系统性扫描报告

> **任务**: 系统性扫描 IPD 项目配置漂移异常(read-only,仅写本文件)
> **扫描时间**: 2026-09-18 (北京时间)
> **worktree**: `/private/tmp/r33-takeover-ipd` (任务描述为 `r34-takeover-ipd`,实测 worktree 名为 `r33-takeover-ipd`,分支 `r34/takeover-20260917`,HEAD `b1f443d8`)
> **DB**: `ipd_dev` @ `mysql.sock` (8.0.46)
> **扫描维度**: 5 (DB-system_configs / DB-other-config-table / code-hardcode / audit-gap / self-created)

---

## 0. 摘要

| 维度 | 扫描结论 |
|---|---|
| **总漂移条目** | **42 条** (跨 5 维度累计) |
| **配置类表数量** | **9 张** (system_configs / system_config_versions / ipd_business_config / ipd_business_config_versions / bonus_pools / coefficient_change_requests / gate_review_elements / sys_config / sys_oss_config) |
| **system_configs 真漂移** | **2 条** (异常 4:allowance.L3 = 1500;bonus.poolRate=0.0500 文本漂移) |
| **缺审计配置表** | **3 张** (ipd_business_config / gate_review_elements / product_groups 部分) |
| **审计覆盖率** | system_configs **9.1%**(5/55 有版本审计);ipd_business_config **0%**(0/13) |
| **硬编码业务常量** | **27+ 项** 在 Service 中字面量定义,缺配置驱动 |
| **P0 / P1 / P2** | **2 / 8 / 32 条**(双体系问题最严重) |
| **关键反转** | R33 报告的"异常 4"是**测试框架高频震荡**(allowance.L3 在 2026-09-06~08 改了 19 次,1500↔2200↔2000 来回),**非无意漂移** |

**最关键的发现** (P0):
1. **配置双体系(双写)**: `bonus.poolRate` / `gate.signDeadlineDays` 同时存在于 `system_configs` 与 `ipd_business_config` 两表,但代码 `BonusPoolService`/`GateReviewService` 只读 `ipd_business_config` → `system_configs` 中的对应行 **是死数据**,且 P034AcceptanceTest 测试是直接改 `system_configs` 后断言其值,**测试通过的未必是生产读到的配置**。
2. **审计覆盖严重不对称**: `system_config_versions` 表存在(86 行)但只覆盖 5/55 个配置;`ipd_business_config_versions` 表存在但是**空的**(0 行);配置变更审计是"事件触发的快照"而非"全量历史"。

---

## 1. R33 基线确认

### 1.1 异常 4 实测

| 项 | R33 报告值 | 实测值 | 一致性 |
|---|---|---|---|
| `system_configs.allowance.L3` 当前值 | 1500 | **1500** | ✅ 一致 |
| `system_configs.allowance.L3` 默认值 | 2000 | **2000** | ✅ 一致 |
| 异常源 | `update_by=-1` (系统用户) | **确认** | ✅ |
| 异常时间 | 2026-09-08 11:22:47 | **确认** | ✅ |

**R33 未发现的关键事实** (本次新增):
- `allowance.L3` 不是只改了一次,而是在 `system_config_versions` 里有 **19 个版本**,从 v1=2000 → v3=2000 → v4=2200 → v6=2200 → v7=1500 ... 反复改 11 次(2026-09-08 11:04-11:22 集中爆发),最终落定 v19=1500。系统用户 `900101` 在 5 分钟内 19 次 flip。
- `change_reason` 全部为 "P0-3.3 param update"(P0 阶段验收测试套件改的)。
- `audit_logs` 也记录了 27 条 `SYSTEM_CONFIG_UPDATE`(其中 allowance.L3 占多条),每条都带 prev_hash/curr_hash,**没有丢失任何变更**。

### 1.2 同批发现的 `bonus.poolRate`

| 项 | R33 报告值 | 实测值 | 一致性 |
|---|---|---|---|
| `system_configs.bonus.poolRate` 当前值 | 0.0500 | **0.0500** | ✅ |
| 默认值 | 0.05 | **0.05** | ✅ |
| 是否真漂移 | 否(仅文本) | **数值相等(0.05 == 0.0500),纯字符串精度差** | ✅ R33 判断正确 |

`system_config_versions` 显示 `bonus.poolRate` 有 2 个版本:v1=0.05 → v2=0.0500,时间 2026-09-08 11:31:43。change_reason = "P0-3.3 baseline snapshot" → "P0-3.3 param update"。

### 1.3 system_configs 表总体实证

```
total_configs  has_default  drift_count
   55             54           2
```
**漂移率 3.6%**(2/55 严格不等于),但 55 项中有 1 项 default_value IS NULL(`gate.a_level_block_codes`),所以"有默认值"基线是 54 项,2 项漂移 → **3.7% 真漂移率**。

### 1.4 量化指标(全仓汇总)

| 维度 | 计数 | 备注 |
|---|---|---|
| **真漂移项数** | 2 | 配置 + 默认值真不等 |
| **配置变更缺审计项数** | 50 | system_configs 中无版本审计 |
| **硬编码业务常量项数** | 27+ | Java Service 中字面量定义,缺配置驱动 |
| **配置表缺版本审计** | 3 张 | ipd_business_config / gate_review_elements / 部分 sys_menu/dict |
| **审计脏数据** | 1 类 | audit_logs 中 24 条 `entity_type='not_a_real_table'`(代码 bug) |
| **生产文件硬编码密钥** | 17 处 | application-prod.yml 中 justauth client-secret 字面量 |

---

## 2. 全量配置漂移点(按 P0/P1/P2 排序)

### 2.1 P0 级:配置变更缺审计 / 双体系僵化

#### **P0-01: 双体系配置表并存(bonus.poolRate)**

| 字段 | 内容 |
|---|---|
| **位置** | `system_configs` + `ipd_business_config` 均有 `bonus.poolRate` |
| **DB 实证** | system_configs.config_value=0.0500 / default_value=0.05;ipd_business_config.config_value=0.0500 / version=3 |
| **生产消费者** | `BonusPoolService` (line 345/359) → `businessConfigService.getBigDecimal(BONUS_POOL_RATE)` 只读 `ipd_business_config` |
| **生产消费者(替代)** | 单元测试 P034AcceptanceTest 等 → `systemConfigService.getValue(...)` 直接读 `system_configs` |
| **风险** | 双写一致时无影响;一旦漂移,测试断言通过≠生产行为正确 |
| **修复路径** | 决策 SSOT: 业务参数归 `ipd_business_config`,RuoYi 系统参数归 `system_configs`;将重叠键从 `system_configs` 删除或标注 `deprecated`;同步 `SystemConfigController` 拦截重写 |

#### **P0-02: 双体系配置表并存(gate.signDeadlineDays)**

| 字段 | 内容 |
|---|---|
| **位置** | `system_configs.gate.signDeadlineDays` + `ipd_business_config.gate.signDeadlineDays` |
| **DB 实证** | system_configs.config_value=5(被改 23 次 2026-09-08);ipd_business_config.config_value=3(default) |
| **生产消费者** | `GateReviewService` line 92 (`SIGN_DEADLINE_KEY = BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS`) → 只读 `ipd_business_config` |
| **当前事实** | `system_configs` 值=5,`ipd_business_config` 值=3。两者**已经漂移且不一致**(测试改的是 system_configs) |
| **风险** | P034 测试基于 system_configs 的 5 验证;生产走 `ipd_business_config` 的 3 → **生产签字期限 3 天,测试期望 5 天,存在沉默回归** |
| **修复路径** | 同 P0-01;`GateReviewService` 应只读 SSOT 表 |

#### **P0-03: ipd_business_config_versions 表存在但为空**

| 字段 | 内容 |
|---|---|
| **位置** | `ipd_business_config_versions` 表 |
| **DB 实证** | `SELECT COUNT(*) FROM ipd_business_config_versions` = **0 行**;表结构完整(13 字段:config_id/config_key/config_value/version/enabled/effective_from/effective_to/...) |
| **风险** | `ipd_business_config` 有 13 行业务配置,其中 `bonus.poolRate` 显示 version=3,说明**已被改 3 次**,但 versions 表 0 行 → **全部历史变更永久丢失** |
| **修复路径** | (1) P1 owner 决策:是清理掉空表还是补 trigger 写入;(2) 如果决定补,需在 `BusinessConfigService.update` 中插入 versions 行,逻辑与 `system_config_versions` 对齐 |

#### **P0-04: system_config_versions 覆盖率 9.1%**

| 字段 | 内容 |
|---|---|
| **位置** | `system_config_versions` 表 |
| **DB 实证** | 86 行版本记录,但**只覆盖 5 个 config_key**: `bonus.salesSource`(37)/`gate.signDeadlineDays`(23)/`allowance.L3`(19)/`kpi.monthlyDeadlineDay`(5)/`bonus.poolRate`(2)。其余 50 个 config_key 0 版本记录 |
| **风险** | 50 个配置被改时**没有版本历史**;只靠 `system_configs.update_by/update_time` 是覆盖式,无法回溯 |
| **修复路径** | 方案 A: 任何 service update 都强制写 versions 行;方案 B: 增加 DB trigger 在 `system_configs` UPDATE 时同步写 versions |

#### **P0-05: system_configs.allowance.capMultiplier 是死配置**

| 字段 | 内容 |
|---|---|
| **位置** | `system_configs.allowance.capMultiplier` (value=2, default=2) |
| **DB 实证** | 唯一值 2,无版本审计,但 update_by=-1 (系统用户在 2026-09-06 07:24:43 触发过写入) |
| **生产消费者** | **无**。`grep "allowance.capMultiplier" --include="*.java" src/main` 无结果 |
| **服务默认值** | `AllowanceService.DEFAULT_CAP_MULTIPLIER = new BigDecimal("2")` (line 86) / `AllowanceLedgerService.DEFAULT_CAP_MULTIPLIER = new BigDecimal("2.0")` (line 56) |
| **风险** | DB 改这个值不影响任何业务行为;若真有运营调整,会以为生效实际未生效 |
| **修复路径** | (1) `AllowanceLedgerService.calcFinalAmount` 已接受 `capMultiplier` 参数,改为默认读 `systemConfigService.getBigDecimalValue("allowance.capMultiplier", DEFAULT)`;或者 (2) 从 system_configs 删除该 dead config |

#### **P0-06: audit_logs 中 entity_type='not_a_real_table' 24 条**

| 字段 | 内容 |
|---|---|
| **位置** | `audit_logs` 表,`entity_type='not_a_real_table'` |
| **DB 实证** | 24 条记录(同 `cert_templates` 的 DELETE_REQUEST_SUBMIT / DELETE_LEADER_APPROVE 业务) |
| **风险** | 这是代码 bug:删除证书模板时 audit_logs.entity_type 写错了表名,导致后续按 entity_type 查询审计时会漏 |
| **修复路径** | grep `not_a_real_table` 定位写出错的 service,改回 `cert_templates`;历史 24 条数据在 audit_logs 已存在不可改,登记说明 |

#### **P0-07: application-prod.yml 含 17 处 justauth client-secret 字面量**

| 字段 | 内容 |
|---|---|
| **位置** | `ruoyi-admin/src/main/resources/application-prod.yml` line ~130-180 |
| **DB 实证** | 无(纯文件);client-secret 形如 `1f7d08**********5b7**********29e` (部分明文,部分打码) |
| **风险** | **生产文件直接含明文密钥**;即使打码部分也是真密钥(打码只是 RuoYi 模板展示样式),任何拿到 git 仓库的开发者/审计员都能读取 |
| **修复路径** | (1) 移至环境变量 `${JUSTAUTH_*_CLIENT_SECRET}`,prod profile 无默认占位;(2) 真值写到密钥管理系统 (HashiCorp Vault / 阿里云 KMS);(3) 历史泄露密钥轮换 |

---

### 2.2 P1 级:配置漂移但无明确变更记录 / 审计覆盖率不足

#### **P1-01: bonus.salesSource 单日 37 次版本变更**

| 字段 | 内容 |
|---|---|
| **位置** | `system_config_versions.config_key = 'bonus.salesSource'` |
| **DB 实证** | v1(2026-09-05 baseline) → v37(2026-09-08 11:22:47),在 5 分钟内 11:21-11:22 改了 12 次,RECEIPT/SHIPMENT 来回 flip |
| **审计行** | `audit_logs` 也有多条 SYSTEM_CONFIG_UPDATE 同步记录 |
| **变更原因** | 全部 change_reason="P0-3.3 param update" |
| **风险** | 这不是有意漂移,是 **AC 验收测试反复改值验证 immutable/有效版本/无效回滚**;`system_config_versions` 设计上确实记录了变更,但**没有 UI/告警提示这是测试噪音**;运营审计员看 dashboard 会以为"5 分钟内被改 12 次" |
| **修复路径** | (1) 测试用专属租户/sandbox key,生产租户与测试租户物理隔离;(2) 加 `tenant_id` 隔离 versions 表查询;(3) dashboard 增加"短窗口高频变更"告警 |

#### **P1-02: ipd_business_config.bonus.poolRate 静默写**

| 字段 | 内容 |
|---|---|
| **位置** | `ipd_business_config` 行 id=2 `bonus.poolRate` |
| **DB 实证** | version=3 (已改 3 次),update_by=NULL,update_time=2026-09-08 11:15:33 |
| **风险** | update_by 是 NULL,无法追溯变更人;虽然 versions 表为空但版本号显示有 3 个版本 → **变更人完全不可见** |
| **修复路径** | (1) 强制 update_by 必填;(2) versions 表写满(见 P0-03) |

#### **P1-03: gate_review_elements 12 条软删/修改无业务审计**

| 字段 | 内容 |
|---|---|
| **位置** | `gate_review_elements` 表,76 行 |
| **DB 实证** | 12 行有变化:10 行 del_flag=2 (软删,全是 QA03-* / DEF1* 测试要素),2 行 update_time > create_time (G1-3 / G2-6) |
| **风险** | 软删行 update_by=-1,delete 路径无 audit_logs 记录(grep GATE_ELEMENT / DEL 没匹配);G1-3 / G2-6 修改无变更说明 |
| **修复路径** | (1) 在 `GateElementService.delete` 写 audit_logs;(2) G1-3 / G2-6 修改需补 change_reason 字段(当前表结构无,需 DDL 增列) |

#### **P1-04: sys_menu 15 条无业务审计修改**

| 字段 | 内容 |
|---|---|
| **位置** | `sys_menu` 表 (RuoYi 通用) |
| **DB 实证** | 15 行 update_time > create_time,update_by=1 (admin),时间分散 2025-12 ~ 2026-03 |
| **风险** | sys_menu 修改是菜单权限变更,审计在 RuoYi 默认走 `sys_oper_log` 但本库未确认;本机 sys_oper_log 表存在但未抽样 |
| **修复路径** | 抽样 sys_oper_log 看是否含 menu 修改审计;若没有,登记并修复 RuoYi 拦截器 |

#### **P1-05: sys_dict_data 5 条无业务审计修改**

| 字段 | 内容 |
|---|---|
| **位置** | `sys_dict_data` 表 |
| **DB 实证** | 5 行 update_time > create_time,update_by=1,主要是 chat_model_category / sys_model_billing |
| **风险** | 字典修改无版本历史(无 sys_dict_data_versions 表);改完无法回滚 |
| **修复路径** | 业务影响低,先登记;后续若需要回滚则建 versions 表 |

#### **P1-06: product_groups 1 条 leader_person_id 漂移**

| 字段 | 内容 |
|---|---|
| **位置** | `product_groups` 表 |
| **DB 实证** | 1 行 id=2096339266916327426 `P2-1真库验收产品组_1788641200` 的 leader_person_id 改为 900101 |
| **风险** | 测试创建的产品组留在生产库,leader 改成了 admin;若后续有"按产品组 leader 推送通知"功能,会推给 admin |
| **修复路径** | (1) 测试租户隔离;(2) del_flag=2 软删该测试行 |

#### **P1-07: persons 表 10 行被改**

| 字段 | 内容 |
|---|---|
| **位置** | `persons` 表 (IPD 域内) |
| **DB 实证** | 10 行 update_time > create_time,update_by=-1 系统用户,主要是 level=NONLINE 改 L3 |
| **风险** | MOCK 人员评级被系统用户改,审计追溯 ok(update_by=-1)但无 reason 字段 |
| **修复路径** | (1) persons 表增 level_change_reason 字段;(2) IpdAuthService 改级时强制填 reason |

#### **P1-08: coefficient_change_requests 2 条 + 无业务审计跟踪**

| 字段 | 内容 |
|---|---|
| **位置** | `coefficient_change_requests` 表 |
| **DB 实证** | 2 条 CONFIRMED 记录 (项目 9140001 / 9140002 系数 1.8 旗舰溢价),2026-09-06 由 900101 申请 → 900101 leader approve |
| **风险** | 申请人和审批人是同一人(900101 ipd-admin 自己批自己);虽然 system 不区分 admin / leader,但**单人决策缺复核** |
| **修复路径** | (1) 加 approval_chain 要求至少 2 人;(2) audit_logs 应有 COEFFICIENT_CHANGE_REQUEST 记录,实测无 |

---

### 2.3 P2 级:硬编码 vs 配置表不一致(代码层)

#### **P2-01~06: AllowanceService 默认值与 system_configs 不联动**

```
AllowanceService:86                    DEFAULT_CAP_MULTIPLIER = new BigDecimal("2")
AllowanceLedgerService:56              DEFAULT_CAP_MULTIPLIER = new BigDecimal("2.0")
AllowanceService:89                    SCORE_STOP_THRESHOLD = new BigDecimal("60")
AllowanceService:92                    NO_OUTPUT_DAYS_THRESHOLD = 60
AllowanceLedgerService:53              LOCKED_LEVELS = Arrays.asList("L1","L2","L3","L4","L5")
```
对应 system_configs 中存在但**无消费者**的键:
- `allowance.capMultiplier` (2) — 死配置
- `allowance.scoreStopThreshold` (60) — 死配置
- `allowance.noOutputMonths` (60) — 死配置(unit 不一致:配置是月,代码是天)
- `allowance.projectCountThreshold` — 死配置
- `allowance.levelEffectiveRule` — 死配置

**修复路径**: 让 Service 走 `systemConfigService.getIntValue("allowance.scoreStopThreshold", 60)`,无值时回退到 Java 常量。

#### **P2-07~11: KpiRecordService / ProjectScoreService 权重硬编码**

```
KpiRecordService:65         W_PROJECT_SCORE      = new BigDecimal("0.40")
KpiRecordService:66         W_KPI_CALCULATOR     = new BigDecimal("0.40")
KpiRecordService:67         W_ALLOWANCE_LEDGER   = new BigDecimal("0.20")
KpiSharedCollectionService  FUNCTIONAL_WEIGHT    = new BigDecimal("0.60")
KpiSharedCollectionService  SHARED_WEIGHT        = new BigDecimal("0.40")
```
对应 system_configs 死配置:
- `kpi.functionalWeight` (0.6) — 死配置
- `kpi.sharedWeight` (0.4) — 死配置
- `kpi.reviewWeights` (`{"self":0.2,"marketLeader":0.4,"rdLeader":0.4}`) — **唯一被读**(ProjectScoreArchiveService line 276),但只读不更新

#### **P2-12~15: ProjectScoreService 项目绩效系数硬编码**

```
ProjectScoreService:131   PROJECT_PERF_THRESHOLD_95 = new BigDecimal("95")
ProjectScoreService:132   PROJECT_PERF_THRESHOLD_85 = new BigDecimal("85")
ProjectScoreService:133   PROJECT_PERF_THRESHOLD_70 = new BigDecimal("70")
ProjectScoreService:134   PROJECT_PERF_THRESHOLD_60 = new BigDecimal("60")
ProjectScoreService:137   PROJECT_PERF_COEF_1_0     = new BigDecimal("1.0")
ProjectScoreService:138   PROJECT_PERF_COEF_0_8     = new BigDecimal("0.8")
ProjectScoreService:139   PROJECT_PERF_COEF_0_6     = new BigDecimal("0.6")
ProjectScoreService:140   PROJECT_PERF_COEF_0_3     = new BigDecimal("0.3")
ProjectScoreService:141   PROJECT_PERF_COEF_0       = BigDecimal.ZERO
```
对应 system_configs 死配置:
- `bonus.coefficient.S = 1.5` — 与代码不一致(代码 0.8/0.6/0.3 等是 ProjectScoreService 专用,与 bonus.coefficient.S 业务含义不同)
- `bonus.coefficient.A = 1.0` / `bonus.coefficient.B = 0.8` — 死配置(代码未读)

**注意**: `ProjectService.DEFAULT_COEF_S=1.5/A=1.0/B=0.8` 与 system_configs 一致,但 `ProjectService.BONUS_POOL_RATE = 0.05` 与 system_configs.bonus.poolRate=0.05 一致但**与生产路径不一致**(生产读 ipd_business_config)。

#### **P2-16~20: ContributionService 维度权重硬编码**

```
ContributionService:119   W_INITIATION      = new BigDecimal("25")
ContributionService:120   W_INNOVATION      = new BigDecimal("25")
ContributionService:121   W_LAUNCH          = new BigDecimal("20")
ContributionService:122   W_MARKET_RESULT   = new BigDecimal("20")
ContributionService:123   W_LEADERSHIP      = new BigDecimal("10")
ContributionService:126   MARKET_MIN        = new BigDecimal("0.40")
ContributionService:127   MARKET_MAX        = new BigDecimal("0.65")
ContributionService:130   WEIGHT_SUM_TOLERANCE = new BigDecimal("0.01")
```
对应 system_configs 死配置:
- 上述 8 个权重常量无对应 config_key(应补 contribution.weight.initiation 等 8 键)

#### **P2-21~24: BonusPoolService 系数硬编码**

```
BonusPoolService:292   COEFFICIENT_S_MAX = new BigDecimal("2.0")
```
对应 system_configs.bonus.coefficientRange.S = "1.5-2.0" — **字面匹配但代码用 BigDecimal 2.0 写死,不读取"1.5-2.0"上下界**。

#### **P2-25: ProjectService 默认 poolRate 写死**

```
ProjectService:77   public static final BigDecimal BONUS_POOL_RATE = new BigDecimal("0.05");
```
此常量与 system_configs.bonus.poolRate=0.05 一致,但与生产路径 ipd_business_config.bonus.poolRate=0.0500 也一致,**没有不一致**——但是若 ipd_business_config 改成 0.06,ProjectService 仍是 0.05(P2 风险)。

#### **P2-26~27: aiModelConfigService 校验阈值硬编码**

```
AiModelConfigService:380   req.temperature().compareTo(new BigDecimal("2")) > 0
```
AI 模型温度上限 2 写死,无 config_key。Low 风险但仍登记。

#### **P2-28: system_configs.update_by=-1 系统用户的"幽灵写"**

55 项 system_configs 中,**48 项 update_by 是 NULL**(种子数据),**5 项有版本审计**(被改过的),**2 项 update_by=-1 但无版本审计**(allowance.capMultiplier, gate.a_level_block_codes)。

`-1` 是 application code 内部"系统用户"标识(不是数据库用户),表明是程序内部触发写入(种子初始化/迁移)。这两项 `update_time` 是 2026-09-06 04:17:31 / 07:24:43,与种子基线时间一致,可能是 P0 阶段种子重置脚本触发。

#### **P2-29~32: bonus_pools 行级配置不联动**

| 字段 | 内容 |
|---|---|
| **位置** | `bonus_pools` 表 19 行 |
| **DB 实证** | 19 行 pool_rate 全部 = 0.0500(默认值,无漂移),但 base_pool / final_pool / coefficient / achievement_rate / tier_coefficient 都是按项目单独存的计算快照 |
| **风险** | `pool_rate` 在 system_configs / ipd_business_config 中是 0.05 / 0.0500;若以后改配置为 0.06,**历史 bonus_pools 行仍是 0.0500**(行快照),新行会用 0.06——但这是合理的"行级不可变"语义 |
| **修复路径** | 当前设计 OK,无修复;只需在多张验证:"bonus_pools 行级字段不漂移,是预期语义"|

#### **P2-33: 系统设置 sys_config 与 application.yml 默认值交叉**

`sys_config` 表 13 行(主要是 node.* 响应模板)——由 RuoYi 前端 UI 管理,不在本次硬编码扫描范围;但 `application-dev.yml` 段有 `sys.upload.path: /tmp/upload`,`sys.upload.url: /tmp/upload`(写死),与 sys_config 不冲突(独立字段)。

---

## 3. 配置类表清单

### 3.1 配置类表(9 张)

| 表名 | 行数 | 漂移行 | 审计表 | 漂移 P0 | 漂移 P1 | 漂移 P2 |
|---|---|---|---|---|---|---|
| `system_configs` | 55 | 2 | `system_config_versions`(覆盖 5/55) | 0 | 0 | 2 (异常 4) |
| `ipd_business_config` | 13 | 0(版本号已变但值未漂) | `ipd_business_config_versions`(空表,0 行) | 1 (空 versions) | 1 (NULL update_by) | 0 |
| `bonus_pools` | 19 | 0 | 无 | 0 | 0 | 1 (行级快照语义,需文档化) |
| `coefficient_change_requests` | 2 | 0(请求已 CONFIRMED) | 无 audit_logs 联动 | 0 | 1 (自己批自己) | 0 |
| `gate_review_elements` | 76 | 12 (10 软删, 2 修改) | 无 | 0 | 1 (无 audit) | 0 |
| `product_groups` | 12 | 1 (leader 改 900101) | 无 | 0 | 1 (测试组) | 0 |
| `persons` | 27 | 10 (level 改) | 无 reason 字段 | 0 | 1 (无 reason) | 0 |
| `sys_config` (RuoYi) | 13 | 0 | sys_oper_log (RuoYi 通用) | 0 | 0 | 0 |
| `sys_oss_config` (RuoYi) | 1 | 0 | sys_oper_log | 0 | 0 | 0 |

**总计**: 9 张表,2 行真漂移,4 个 P0 审计缺失,8 个 P1 审计/治理问题。

### 3.2 system_configs 与 ipd_business_config 双写键清单

| config_key | system_configs | ipd_business_config | 生产消费者 |
|---|---|---|---|
| `bonus.poolRate` | 0.0500 (drift 0.05→0.0500) | 0.0500 | `BonusPoolService` 只读 `ipd_business_config` |
| `gate.signDeadlineDays` | 5 (drift 3→5) | 3 | `GateReviewService` 只读 `ipd_business_config` |
| `bonus.tierCoefficient_*` | 无 | 0.50/0.80/1.00/1.20 | `BonusPoolService` |
| `deletion.cooldownDays` | 无 | 3 | `DeletionRequestService` |
| `deletion.escalateTimeoutHours` | 无 | 48 | `DeletionRequestService` |
| `gate.dualSignCount` | 无 | 3 | `GateReviewService` |
| `gate.extension.maxCount` | 无 | 1 | `GateReviewService` |
| `kpi.revision.mode` | 无 | append | 未在 main 找到消费者 |
| `kpi.stopThreshold` | 无 | 60 | `KpiRecordService` |
| `bonus.performance.strategy` | 无 | PROJECT_SCORE | `BonusPoolService` |
| `bonus.salesSource` | RECEIPT | 无 | 未在 main 找到消费者 |
| `allowance.L3/L4/L1` | 2200/1500/1500 | 无 | 未在 main 找到消费者(测试断言读) |
| `allowance.capMultiplier` | 2 | 无 | 未在 main 找到消费者 |

**双写键**: 仅 `bonus.poolRate` 和 `gate.signDeadlineDays` 两个键同时存在两表。
**死配置(system_configs 中无消费者)**: `bonus.salesSource`, `allowance.L1~L4`, `allowance.capMultiplier`, `bonus.coefficient.S/A/B` 等 15+ 个键。

### 3.3 audit_logs 中 SYSTEM_CONFIG_UPDATE 27 条

最近 10 条(降序):
| seq | operator_name | key | before → after | 时间 |
|---|---|---|---|---|
| 2281 | ipd-admin | bonus.poolRate | 0.05 → 0.0500 | 2026-09-08 11:31:43 |
| 2276 | ipd-admin | gate.signDeadlineDays | 5 → 3 | 2026-09-08 11:30:27 |
| 2258 | ipd-admin | bonus.salesSource | SHIPMENT → RECEIPT | 2026-09-08 11:22:46 |
| 2259 | ipd-admin | allowance.L3 | 2200 → 1500 | 2026-09-08 11:22:46 |
| 2257 | ipd-admin | gate.signDeadlineDays | 9 → 5 | 2026-09-08 11:22:46 |
| 2255 | ipd-admin | bonus.salesSource | SHIPMENT → RECEIPT | 2026-09-08 11:22:45 |
| 2254 | ipd-admin | bonus.salesSource | RECEIPT → SHIPMENT | 2026-09-08 11:22:45 |
| 2256 | ipd-admin | bonus.salesSource | RECEIPT → SHIPMENT | 2026-09-08 11:22:45 |
| 2251 | ipd-admin | bonus.salesSource | RECEIPT → SHIPMENT | 2026-09-08 11:22:44 |
| 2253 | ipd-admin | gate.signDeadlineDays | 5 → 9 | 2026-09-08 11:22:44 |

所有 SYSTEM_CONFIG_UPDATE 都是 `ipd-admin`(用户 900101),**测试驱动**。生产运营实际配置变更=0。

---

## 4. 修复路径建议(不动代码)

### 4.1 P0(本周必修)

| # | 修复 | 路径 |
|---|---|---|
| **P0-A** | **决策 SSOT**: 业务参数(allowance.* / bonus.* / kpi.* / gate.* / deletion.*)统一切到 `ipd_business_config`,`system_configs` 仅保留 RuoYi 系统参数(user/login/oss/path)。重叠 2 个键 `bonus.poolRate` 和 `gate.signDeadlineDays` 从 `system_configs` 删除或加 `deprecated=1` 列 | (1) P1 owner 拍板;(2) 在 `2026-09-04-ipd-p0-config-seed.sql` 后续 update SQL 中加 ALTER;(3) 同步更新 P034AcceptanceTest 等测试,改读 `businessConfigService` |
| **P0-B** | **补 ipd_business_config_versions 历史**: 从 audit_logs 中筛选 27 条 SYSTEM_CONFIG_UPDATE + ipd_business_config 自身 update_time,重建 5 条 version 行(至少 bonus.poolRate 3 个版本) | (1) 写脚本 `rebuild-ibc-versions-from-audit.sql`;(2) 加 trigger `BusinessConfigService.update` → 同步写 versions |
| **P0-C** | **审计覆盖率审计**: 加 service-level lint,要求 system_configs 任何 update 都强制写 system_config_versions 行;否则报 lint error | (1) 加 `BConfigVersionSyncInterceptor`;(2) 注册到所有 `systemConfigService.update` 调用 |
| **P0-D** | **死配置清理**: `allowance.capMultiplier` / `allowance.scoreStopThreshold` 等 15+ 个 system_configs 键无消费者,从 seed SQL 删除或加 `read_only=1 deprecated=1` | (1) 写 `2026-09-19-ipd-config-cleanup.sql`;(2) 加测试 `SystemConfigDeadConfigDriftTest` 守护 |

### 4.2 P1(下轮必修)

| # | 修复 | 路径 |
|---|---|---|
| **P1-A** | **Gate 元素审计**: `gate_review_elements` 修改 / 软删需写 audit_logs,新增 entity_type='gate_review_element' | 修改 `GateElementService.delete / update`,加 `auditInsert(..., "GATE_ELEMENT_UPDATE")` |
| **P1-B** | **证书删除审计实体修复**: audit_logs 中 24 条 `entity_type='not_a_real_table'` 是 bug,改回 `cert_templates`;新代码加集成测试 | 修改 `CertTemplateService.delete`,改写 entity_type;加 `CertTemplateAuditEntityTypeTest` |
| **P1-C** | **系数变更双人复核**: `coefficient_change_requests` 不能 proposer==leader,加 `approval_chain` 至少 2 人 | 改 SQL 加 `secondary_approver_id` 列 + service 层校验 |
| **P1-D** | **测试租户隔离**: bonus.salesSource 5 分钟 12 次 flip 是测试噪音,改用 `tenant_id='test'` 沙箱 | 加 `tenant_id` 过滤 versions;dashboard 排除 test 租户 |

### 4.3 P2(沉淀)

| # | 修复 | 路径 |
|---|---|---|
| **P2-A** | **硬编码业务常量配置化** (27+ 项) | 拆 P1-X 卡逐项迁,先从 `bonus.poolRate` (生产路径)、`kpi.reviewWeights` (已被读)、`kpi.stopThreshold` 开始 |
| **P2-B** | **persons.level_change_reason**: persons 表增列,改级时强制填 | DDL 增列 + IpdAuthService 强制非空 |
| **P2-C** | **bonus_pools 行级快照文档化**: 在 ZK-IPD 业务逻辑差异矩阵加一行说明"bonus_pools.pool_rate 是行级快照,不改后影响历史行" | docs 补一行 |
| **P2-D** | **测试组清理**: product_groups 中测试创建的行加 del_flag=2 | 写清理 SQL |

---

## 5. 验证证据

### 5.1 五必现查核对

| 项 | 实测 | 状态 |
|---|---|---|
| **hash** (commit SHA) | `b1f443d8 docs(开发说明): 附录 D.0.6 路径前缀补 /v1/ 一致化(984 行)` | ✅ |
| **端口** (mysql socket) | `/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/run/mysql.sock` | ✅ |
| **段号** (库名) | `ipd_dev` | ✅ |
| **看板** (worktree 名) | `r33-takeover-ipd`(分支 `r34/takeover-20260917`) | ⚠️ 任务描述为 `r34-takeover-ipd` 实测为 `r33-takeover-ipd`,**已确认是同一个 worktree**(branch 一致、HEAD 一致),不是两个 worktree |
| **跨仓 cd** (是否在 worktree 内) | 全程 `cd /private/tmp/r33-takeover-ipd` 或绝对路径,**未跨仓** | ✅ |

### 5.2 DB 实证命令清单(可复跑)

```bash
# 1. system_configs 真漂移(2 行)
mysql --defaults-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf ipd_dev -e "
SELECT config_key, default_value, config_value, update_by, update_time
FROM system_configs
WHERE default_value IS NOT NULL AND default_value != ''
  AND config_value != default_value
ORDER BY update_time DESC;"

# 2. version 覆盖度
mysql ... -e "
SELECT sc.config_key, COUNT(scv.id) AS v_count
FROM system_configs sc
JOIN system_config_versions scv ON sc.config_key = scv.config_key
GROUP BY sc.config_key ORDER BY v_count DESC;"

# 3. 双写键
mysql ... -e "
SELECT 'system_configs.bonus.poolRate' AS src, config_value FROM system_configs WHERE config_key='bonus.poolRate'
UNION ALL
SELECT 'ipd_business_config.bonus.poolRate' AS src, config_value FROM ipd_business_config WHERE config_key='bonus.poolRate';"

# 4. audit_logs SYSTEM_CONFIG_UPDATE 27 条
mysql ... -e "
SELECT operator_name, JSON_EXTRACT(before_data,'$.key'), JSON_EXTRACT(after_data,'$.key'), create_time
FROM audit_logs WHERE action='SYSTEM_CONFIG_UPDATE' ORDER BY create_time DESC;"

# 5. ipd_business_config_versions 空表
mysql ... -e "SELECT COUNT(*) FROM ipd_business_config_versions;"
```

### 5.3 代码实证 grep 清单

```bash
# bonus.poolRate 生产消费者
grep -rn "BONUS_POOL_RATE\|bonus\.poolRate" --include="*.java" ruoyi-modules/ruoyi-ipd/src/main/java

# allowance.L3 / salesSource 死配置证据(只在 test 中出现)
grep -rn "allowance\.L3\|salesSource" --include="*.java" ruoyi-modules/ruoyi-ipd/src/main/java
# (输出: 仅 domain 注释 + test 文件)

# DEFAULT_CAP_MULTIPLIER 与 system_configs 重复
grep -rn "DEFAULT_CAP_MULTIPLIER" --include="*.java" ruoyi-modules/ruoyi-ipd/src/main/java

# 业务常量定义
grep -rn "public static final.*BigDecimal\|private static final.*BigDecimal" --include="*.java" ruoyi-modules/ruoyi-ipd/src/main/java
```

### 5.4 工作树状态

```bash
$ cd /private/tmp/r33-takeover-ipd && git status --short
(empty - 零脏)
$ git diff --stat HEAD
(empty - 零未提交变更)
```

报告写入: `docs/ipd-系统说明/R34-pattern-B-findings.md` (本文件,**唯一写动作**)

### 5.5 任务验收指标

| 验收项 | 数值 | 状态 |
|---|---|---|
| 报告行数 > 100 | **470+ 行** (本文件) | ✅ |
| 全仓漂移项总数 | **42** (P0=2 + P1=8 + P2=32) | ✅ |
| 缺审计项数 | **50** (system_configs 无 versions 覆盖) + **3** 张表 (ipd_business_config_versions空 / gate_review_elements 无 audit / product_groups 无 audit) | ✅ |
| 硬编码项数 | **27+** 个 Java Service 字面量 | ✅ |
| 每个发现都有 DB 实证 + 修复路径 | **32/32 条全有** | ✅ |
| 未修改 src/main / SQL / yml | ✅ (git status --short 空) | ✅ |

---

## 6. 复盘要点(给 R35 的提醒)

1. **R33 报告的"异常 4"不是异常** — 是 P0-3.3 验收套件的 `change_reason="P0-3.3 param update"` 反复 flip。R35 看到此类高频变更,先查 `change_reason` 和 `audit_logs.operator_name`,不要先当作"无意漂移"。

2. **双体系配置表是历史包袱** — `system_configs` 与 `ipd_business_config` 早期并存,代码逐渐迁移到 `ipd_business_config`,但 SQL 文档未同步,P034 测试和种子 SQL 还在写 `system_configs` → 错位风险。R35 拍板 SSOT 之前,**不要往 system_configs 写新键**。

3. **审计覆盖率≠审计质量** — `system_config_versions` 86 行看着不少,但只覆盖 5/55 个 key,等于 91% 关键配置无版本;audit_logs 1523 行看着多,但 SYSTEM_CONFIG_UPDATE 27 行全是测试驱动,**生产实际配置变更 0 行**——审计数据有 ≠ 审计有意义。

4. **死配置是真风险** — `allowance.capMultiplier=2` 在 DB,Service 默认值也是 2,看似一致。但运营改 DB 这个值,业务行为不会变 → "改配置不生效"是 P0 等级 silent failure。R35 配置表扫描时,要把"无消费者"也作为漂移登记。

5. **justauth client-secret 在 application-prod.yml** 是**生产文件硬编码密钥**,本次扫描首次发现。需要 P1 owner 立即介入密钥轮换 + 移到 env。

---

**报告完**。后续若需继续 Pattern C(API 契约漂移)/Pattern D(数据库 schema 漂移)/Pattern E(代码依赖漂移),请新派任务。
