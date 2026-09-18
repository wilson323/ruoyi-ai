# PLAN-AUDIT-FULL 子任务 1:审计覆盖缺口清单(2026-09-18,主协调撞车 0 + 纯静态分析)

**卡号**:PLAN-AUDIT-FULL(全局待办根治计划:审计覆盖 75→100%)
**UUID**:`0f4cc93b-d4d0-4e15-9493-4ffdd3009aa5`
**status**:inprogress
**触发**:R45 路线图 loop 第 4 轮,撞车 0 + 纯静态分析 + 不擅自动代码

## 一、统计基线(2026-09-18 真库现查,R13 五必现查)

| 指标 | 数值 | 来源 |
|---|---|---|
| IPD 业务表总数 | 38 | `information_schema.TABLES WHERE TABLE_SCHEMA='ipd_dev' AND TABLE_TYPE='BASE TABLE'`,去掉基线 `sys_*` + `chat_*` + `_ipd_schema_history` 等 |
| 后端代码引用 entity_type | 42 | `grep -rn 'entityType' ruoyi-modules/ruoyi-ipd/src/main/java/`,其中 4 个非实体操作类型(`accept`/`admin_assign`/`modify_conditions`/`select`) |
| 真库 audit_logs 实际 entity_type | 30 | `SELECT DISTINCT entity_type FROM audit_logs` |
| 真库 audit_logs 实际业务 entity_type | 27 | 去掉污染 `not_a_real_table` 24 行 + 命名大小写不一致 |
| 测试污染 | 1 个 | `not_a_real_table` 24 行(R46 已识别为 P0 真异常) |
| 命名大小写不一致 | 4 组 | `bonus_pool`(1)/ `bonus_pools`(24) + `PROJECT`(1)/ `projects`(44) + `STAGE_ACTION`(3)/ `stage_action`(隐) + `Contribution`(10)/ `contribution`(隐) + `AI_DOCUMENT`(2)/ `ai_documents`(3) + `AI_COPILOT`(8)/ `AI_MODEL_CONFIG`(6)/ `NEGATIVE_FEEDBACK`(7)/ `SYSTEM_CONFIG`(27) |

## 二、38 张 IPD 业务表审计覆盖现状

### ✅ 已覆盖(27 张 — 真库 audit_logs 有实际行)

| # | 表名 | entity_type | 真库行数 | 来源 |
|---|---|---|---|---|
| 1 | persons | persons | 1130 | PersonService/Controller(11 处) |
| 2 | projects | projects | 44 | ProjectService(8 处) |
| 3 | gates | gates | 69 | GateService(4 处) |
| 4 | cert_templates | cert_templates | 48 | CertTemplateService |
| 5 | gate_element_results | gate_element_results | 35 | GateElementService(3 处) |
| 6 | audit_logs | audit_logs | 35 | AuditEvent 自审计 |
| 7 | system_configs | SYSTEM_CONFIG | 27 | SystemConfigService |
| 8 | bonus_pools | bonus_pools | 24 | BonusPoolService(4 处) |
| 9 | handover | handover | 17 | HandoverService(6 处) |
| 10 | contributions | Contribution | 10 | ContributionService(5 处) |
| 11 | ai_documents | AI_COPILOT | 8 | AiDocumentService |
| 12 | kpi_shared_confirms | kpi_shared_confirms | 8 | KpiSharedService |
| 13 | negative_feedbacks | NEGATIVE_FEEDBACK | 7 | NegativeFeedbackService |
| 14 | receipt_ledger | receipt_ledger | 7 | ReceiptService(IpdEntityType) |
| 15 | ai_documents | AI_MODEL_CONFIG | 6 | AiModelConfigService |
| 16 | project_members | project_members | 6 | ProjectMemberService |
| 17 | person_sync_jobs | person_sync_jobs | 5 | PersonSyncService(4 处) |
| 18 | guest_demand | guest_demand | 4 | GuestDemandService(2 处) |
| 19 | bid_response | bid_response | 4 | BidResponseService(2 处) |
| 20 | launch_date_change_requests | launch_date_change_requests | 3 | LaunchDateService(1 处) |
| 21 | ai_documents | ai_documents | 3 | AiDocumentService(2 处) |
| 22 | project_cert_items | project_cert_items | 3 | ProjectCertService(3 处) |
| 23 | stage_actions | STAGE_ACTION | 3 | StageActionService |
| 24 | bid_invitation | bid_invitation | 2 | BidInvitationService(5 处) |
| 25 | ai_documents | AI_DOCUMENT | 2 | AiDocumentService |
| 26 | bonus_pool | bonus_pool | 1 | 命名不一致(应统一为 bonus_pools) |
| 27 | switching_acceptance | switching_acceptance | 1 | SwitchingAcceptanceService(1 处) |
| 28 | kpi_records | kpi_records | 1 | KpiRecordService(7 处) |

### ❌ 未覆盖(11 张 — 真库无 audit_logs 行)

| # | 表名 | 真库行数 | 推断 | 撞车 0 治理建议 |
|---|---|---|---|---|
| 1 | products | 51 | 代码引用 `products` 1 处,真活未触发 | 待 owner 决策:补 AuditLogService.append 或接受现状 |
| 2 | stage_actions | 2298 | 代码无引用,StageAction 流转无审计 | **P0 阻塞**:2298 行无审计,业务合规盲区 |
| 3 | project_stages | 234 | 代码无引用 | 派生表(从 projects 派生),可能不需要独立审计 |
| 4 | gate_reviews | 29 | 代码引用 `gate_review` 6 处,真活未触发 | 待 owner 决策:补 audit 或接受 |
| 5 | gate_review_elements | 76 | 代码无引用 | 派生表(从 gate_reviews 派生) |
| 6 | gate_arbitrations | 3 | 代码无引用 | 仲裁表,需审计 |
| 7 | requirements | 2 | 代码引用 `requirement_change` 4 次 | **撞车 0 命名不一致**:真库是 requirements,代码是 requirement_change |
| 8 | handover_records | 2 | 代码引用 `handover_record` 3 次,真库是 `handover` 17 次 | **撞车 0 命名不一致**:真库已有 handover,代码还在写 handover_record |
| 9 | deliverables | 1 | 代码无引用 | 附件表,需审计 |
| 10 | coefficient_change_requests | 2 | 代码引用 `coefficient_change` 3 次 + `coefficient_change_requests` 1 次,真库 0 行 | **P0 阻塞**:代码 4 次引用,真活未触发 |
| 11 | ipd_business_config | 13 | 代码无引用 | 配置表,需审计 |

### ⚠️ 测试污染(1 个 — R46 已识别)

| entity_type | 真库行数 | 来源 | 处置 |
|---|---|---|---|
| `not_a_real_table` | 24 | R46-A1 P0 真异常 | R46 路径 1(SQL 清理)待 owner 拍板 |

## 三、撞车 0 + 命名不一致清单(6 组)

| 代码引用 | 真库实际 | 真库行数 | 撞车 0 治理建议 |
|---|---|---|---|
| `bonus_pool`(单数) | `bonus_pools`(复数) | 1 vs 24 | 撞车 0 维持现状(存量字面量不改) |
| `coefficient_change`(单数) | `coefficient_change_requests`(全) | 0 vs 0 | 撞车 0 维持现状(两种命名并存) |
| `handover_record`(单数) | `handover`(单数) | 0 vs 17 | 撞车 0 维持现状(代码引用 vs 真库实际不一致) |
| `kpi_record`(单数) | `kpi_records`(复数) | 0 vs 1 | 撞车 0 维持现状 |
| `launch_date_change`(单数) | `launch_date_change_requests`(全) | 0 vs 3 | 撞车 0 维持现状 |
| `requirement_change`(单数) | `requirements`(复数) | 0 vs 2 | 撞车 0 维持现状 |

**撞车 0 守则严守**(参照 `IpdEntityType.java:7-9` javadoc):
> 存量调用点 entityType 命名不统一——`persons`/`receipt_ledger`/`person_sync_jobs`/`handover` 与 `person` 混用。本枚举把**现值**固化为常量供新调用点引用;**不改写存量字符串**——entityType 进哈希(canonicalOf),改存量字符串只影响新行不影响历史,但会破坏前端/验收对现值的断言,统一改名须单独裁决(现值即契约)。

## 四、撞车 0 + 推进优先级

### P0(合规盲区 — 急需 owner 决策)

1. **stage_actions 表无审计**(2298 行业务流转无审计)— P0 阻塞
2. **coefficient_change_requests 表无审计**(代码 4 处引用,真活未触发)— P0 阻塞
3. **R46-A1 not_a_real_table 污染清理**(24 行)— P0 阻塞

### P1(已识别缺口 — 待 owner 决策补 audit)

1. **products 表无审计**(代码 1 处引用)
2. **gate_reviews 表无审计**(代码 6 处引用)
3. **requirements 表无审计**(代码 4 处引用,但 entity_type 不一致)
4. **deliverables 表无审计**(代码 0 处引用)
5. **gate_arbitrations 表无审计**(代码 0 处引用)
6. **ipd_business_config 表无审计**(代码 0 处引用)

### P2(派生表 — 可接受现状)

1. **project_stages 派生自 projects**(代码 0 处引用,可接受)
2. **gate_review_elements 派生自 gate_reviews**(代码 0 处引用,可接受)

## 五、撞车 0 行动建议(待 owner 拍板)

| 行动 | 影响面 | 推荐度 |
|---|---|---|
| **A1 维持现状 + 接受 11 张表无审计** | 低(撞车 0 风险 0) | ★★ |
| **A2 补 stage_actions 审计(代码 0 处引用)** | 中(2298 行业务流转需补 audit) | ★★★★★ |
| **A3 清理 not_a_real_table 污染(R46-A1)** | 低(24 行 SQL DELETE) | ★★★★★ |
| **A4 统一命名不一致**(6 组) | 中(撞车 0 风险,但破坏契约) | ★ |
| **A5 全 49 张表统一补 audit** | 高(可能撞车风险 + 工程量大) | ★★★ |

## 六、撞车 0 守则严守

- 本缺口清单**纯静态分析**(grep + 真库 SELECT),零代码改动
- 不擅自补 audit(撞车 0 + 单会话能力边界)
- 不擅自改命名(IpdEntityType.java:7-9 现值即契约)
- 不擅自清理污染(R46-A1 待 owner 拍板)
- 看板卡 status 维持 inprogress(撞车 0 不擅自翻 done)

## 七、五必现查(R13)证据时间戳

- HEAD:主仓 `e0b28e32`(loop 第 3 轮 commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库
- grep:后端 `ruoyi-modules/ruoyi-ipd/src/main/java/` 387 个 java 文件
- 端口:后端 16039 / 看板 62250 / 前端 vite 15666
- 看板回读:PLAN-AUDIT-FULL status=inprogress
- 跨仓 cd:主仓 working tree 完全干净,前端仓兄弟会话 M 改动 2 个不碰

## 八、相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/audit/IpdEntityType.java`(实体类型常量类)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/audit/IpdAuditAspect.java`(审计 AOP)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AuditEventData.java`(审计事件数据)
- `docs/ipd-系统说明/R46-loop验证修复-20260918.md`(兄弟会话产物,A1 异常清单)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(本会话 loop 第 2 轮)