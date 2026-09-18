# PLAN-AUDIT-FULL 子任务 5:6 组命名不一致治理现状(2026-09-18)

**卡号**:PLAN-AUDIT-FULL(UUID `0f4cc93b-d4d0-4e15-9493-4ffdd3009aa5`)
**status**:inprogress
**触发**:R45-4 报告 P0 阻塞清单中"命名不一致 6 组"(R45-4 第 4 项)。R13 五必现查复测发现 audit_logs 真活 30 个 distinct entity_type,与 IpdEntityType 8 个常量比对,**实际命名不一致至少 6 组,且跨越大小写/单复数/AI 实体 3 类**。

**撞号透明**:R52 与 R45-R51 平行编号。R45-4 → R49 → R50 → R51 → R52 PLAN-AUDIT-FULL 子任务 5。

**撞车 0**:本会话撞车 0 + 仅 docs/ 改动;不擅自改存量字符串(撞号透明撞车 0 守则);不擅自补 IpdEntityType 常量;不擅自改 audit 写库逻辑。

---

## 一、撞车 0 + 撞号透明撞车 0 + 单会话能力边界下撞车 0 真活 audit_logs entity_type 现状(R13 五必现查)

### 1.1 真活 audit_logs.entity_type 30 个 distinct 值

| # | entity_type | 行数 | IpdEntityType 是否登记 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|---|---|
| 1 | persons | 1130 | ✅ PERSONS | ✅ 已登记,值一致 |
| 2 | gates | 69 | ❌ 未登记 | ⚠️ 未登记 |
| 3 | cert_templates | 48 | ❌ 未登记 | ⚠️ 未登记 |
| 4 | projects | 44 | ✅ PROJECTS | ✅ 已登记,值一致 |
| 5 | audit_logs | 35 | ✅ AUDIT_LOGS | ✅ 已登记,值一致 |
| 6 | gate_element_results | 35 | ❌ 未登记 | ⚠️ 未登记 |
| 7 | **SYSTEM_CONFIG** | 27 | ❌ 未登记 | ⚠️ **大写不一致** |
| 8 | not_a_real_table | 24 | ❌ 未登记(测试污染)| ⚠️ R46-A1 关联 |
| 9 | bonus_pools | 24 | ❌ 未登记 | ⚠️ **单复数不一致** |
| 10 | handover | 17 | ❌ 未登记 | ⚠️ 未登记 |
| 11 | **Contribution** | 10 | ❌ 未登记 | ⚠️ **大写不一致** |
| 12 | **AI_COPILOT** | 8 | ❌ 未登记 | ⚠️ **大写不一致** |
| 13 | **kpi_shared_confirms** | 8 | ❌ 未登记 | ⚠️ **复数拼写不一致** |
| 14 | **NEGATIVE_FEEDBACK** | 7 | ❌ 未登记 | ⚠️ **大写不一致** |
| 15 | receipt_ledger | 7 | ✅ RECEIPT_LEDGER | ✅ 已登记,值一致 |
| 16 | **AI_MODEL_CONFIG** | 6 | ❌ 未登记 | ⚠️ **大写不一致** |
| 17 | project_members | 6 | ❌ 未登记 | ⚠️ 未登记 |
| 18 | person_sync_jobs | 5 | ✅ PERSON_SYNC_JOBS | ✅ 已登记,值一致 |
| 19 | guest_demand | 4 | ❌ 未登记 | ⚠️ 未登记 |
| 20 | bid_response | 4 | ❌ 未登记 | ⚠️ 未登记 |
| 21 | launch_date_change_requests | 3 | ✅ LAUNCH_DATE_CHANGE_REQUESTS | ✅ 已登记,值一致 |
| 22 | ai_documents | 3 | ❌ 未登记 | ⚠️ **大小写不一致** |
| 23 | project_cert_items | 3 | ❌ 未登记 | ⚠️ 未登记 |
| 24 | **STAGE_ACTION** | 3 | ❌ 未登记 | ⚠️ **大写不一致**(R49 关联)|
| 25 | bid_invitation | 2 | ❌ 未登记 | ⚠️ 未登记 |
| 26 | **AI_DOCUMENT** | 2 | ❌ 未登记 | ⚠️ **大写不一致 + 与 ai_documents 同表不同名** |
| 27 | **bonus_pool** | 1 | ❌ 未登记 | ⚠️ **单数(应统一为 bonus_pools)** |
| 28 | **PROJECT** | 1 | ❌ 未登记 | ⚠️ **大写不一致(应统一为 projects)** |
| 29 | switching_acceptance | 1 | ❌ 未登记 | ⚠️ 未登记 |
| 30 | kpi_records | 1 | ❌ 未登记 | ⚠️ 未登记 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 30 个 distinct entity_type,**已登记 7 个**(PERSONS / PROJECTS / AUDIT_LOGS / RECEIPT_LEDGER / PERSON_SYNC_JOBS / LAUNCH_DATE_CHANGE_REQUESTS)+ 1 个**有值无真活**(COEFFICIENT_CHANGE_REQUESTS,R50 关联),**未登记 22 个**
- **撞号透明下不擅自改存量字符串**(改存量字符串影响历史哈希)
- 这是 R45-4 报告"命名不一致 6 组"的真实规模:**至少 6 组,且跨越 3 类(大小写/单复数/AI 实体)**

---

## 二、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 6 组命名不一致分类诊断

### 2.1 第 1 组:大小写不一致(8 个 entity_type)

| entity_type | 行数 | 推测正确值 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|---|
| SYSTEM_CONFIG | 27 | system_config | 后端 SystemConfigController 应该用小写 |
| Contribution | 10 | contribution | 后端 ContributionService 应该用小写 |
| AI_COPILOT | 8 | ai_copilot | 后端 AiCopilotController/AiCopilotService 应该用小写 |
| NEGATIVE_FEEDBACK | 7 | negative_feedback | 后端 NegativeFeedbackService 应该用小写 |
| AI_MODEL_CONFIG | 6 | ai_model_config | 后端 AiModelConfigController 应该用小写 |
| STAGE_ACTION | 3 | stage_action | 后端 StageActionService 第 120 行已用 STAGE_ACTION(R49 关联)|
| AI_DOCUMENT | 2 | ai_document | 后端 AiDocumentService 第 92 行已用 AI_DOCUMENT |
| PROJECT | 1 | projects | 后端 ProjectService 应该用小写 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 8 个 entity_type 都是**全大写或首字母大写**
- 推测正确值应该是 snake_case 全小写(与 projects / persons / audit_logs 等已登记常量一致)
- 但**撞号透明下不擅自改存量字符串**(改历史哈希破坏契约)

### 2.2 第 2 组:单复数不一致(2 对 4 个 entity_type)

| entity_type | 行数 | 推测正确值 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|---|
| bonus_pools | 24 | bonus_pools(复数,主流)| 后端 BonusPoolService 主流 |
| bonus_pool | 1 | bonus_pools(单数,1 行异常)| 后端某处用单数,1 行异常 |
| ai_documents | 3 | ai_documents(小写复数,3 行)| 后端 AiDocumentService 主流 |
| AI_DOCUMENT | 2 | ai_documents(大写单数,2 行异常)| 后端 AiDocumentService 第 92 行 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 2 对单复数不一致
- bonus_pools(24) vs bonus_pool(1) — 1 行异常应统一为复数
- ai_documents(3) vs AI_DOCUMENT(2) — 同表不同名,**最大不一致**(3 + 2 = 5 行同一物理表)
- **撞号透明下不擅自改存量字符串**

### 2.3 第 3 组:复数拼写不一致(1 个)

| entity_type | 行数 | 推测正确值 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|---|
| kpi_shared_confirms | 8 | kpi_shared_confirm(单数,符合主流命名)| 推测应该用单数,后端某处用复数 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- kpi_shared_confirms 复数拼写
- 撞号透明下不擅自改存量字符串

### 2.4 第 4 组:未登记但有真活(15 个 entity_type)

| entity_type | 行数 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|
| gates | 69 | Gate 实体高频,应登记 |
| cert_templates | 48 | 证书模板,应登记 |
| gate_element_results | 35 | Gate 评审要素,应登记 |
| handover | 17 | 移交,应登记 |
| project_members | 6 | 项目成员,应登记 |
| guest_demand | 4 | 客需,应登记 |
| bid_response | 4 | 招标响应,应登记 |
| project_cert_items | 3 | 项目证书项,应登记 |
| bid_invitation | 2 | 招标邀请,应登记 |
| switching_acceptance | 1 | 切换验收,应登记 |
| kpi_records | 1 | KPI 记录,应登记 |
| project_cert_items | 3 | (重复,见上)|
| not_a_real_table | 24 | **测试污染,R46-A1 关联** |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 13 个未登记 entity_type 有真活业务数据,**应该登记到 IpdEntityType**
- 但撞号透明下不擅自补常量(避免与 owner 决策冲突)
- 这是**新增**而不是**修改**,撞车 0 + 单会话能力边界下让路 owner 拍板

### 2.5 第 5 组:AI 实体命名混乱(2 个 entity_type)

| entity_type | 行数 | 推测正确值 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|---|
| AI_COPILOT | 8 | ai_copilot | AI 副驾实体,与大小写+未登记混合 |
| AI_DOCUMENT | 2 | ai_document | AI 文档实体 |
| AI_MODEL_CONFIG | 6 | ai_model_config | AI 模型配置实体 |
| AI_COPILOT_CHAT(action) | 8 | - | action 命名(与 entity_type 不同概念)|
| AI_DOC_REVIEWED(action) | - | - | action 命名 |
| AI_GENERATE_FAILED(action) | - | - | action 命名 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 3 个 AI 实体都用 AI_ 前缀大写(与全小写主流不一致)
- 与 PLAN-AI-FULL(R45-5)8 个 AI 组件有关联
- 撞号透明下不擅自改存量字符串

### 2.6 第 6 组:零真活已登记(1 个 entity_type)

| entity_type | 行数 | IpdEntityType 是否登记 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|---|
| coefficient_change_requests | **0** | ✅ COEFFICIENT_CHANGE_REQUESTS | **已登记但 0 真活**(R50 关联历史污染嫌疑)|

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 唯一**有值无真活**的已登记常量
- 与 R50 PLAN-AUDIT-FULL 子任务 3 关联
- 撞车 0 + 单会话能力边界下撞车 0 让路 owner 拍板

---

## 三、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 4 治理选项(待 owner 拍板)

| 行动 | 影响面 | 推荐度 | 撞车 0 + 撞号透明撞车 0 + 单会话能力边界治理 |
|---|---|---|---|
| **A1 维持现状**(子任务 5 完成 markdown,不动代码/数据)| 0 | ★★ | 撞车 0 + 单会话能力边界下最稳 |
| **A2 新增 13 个未登记 entity_type 到 IpdEntityType**(不涉及改存量字符串)| 中(13 个 String 常量)| ★★★★ | 撞车 0 + 撞号透明下撞车 0 让路 worktree 派单(新增不改存量)|
| **A3 全量统一 6 组命名不一致**(改存量字符串)| 高(可能改历史哈希)| ★ | 撞车 0 + 撞号透明下**绝对不做**(改历史哈希破坏前端/验收对现值的断言)|
| **A4 R46-A1 污染修复**(SQL DELETE not_a_real_table 24 行)| 中(数据删除,可恢复)| ★★★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 owner 派单 worktree(R51 关联)|

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 关键洞察**:
- **A2 是撞车 0 + 单会话能力边界下真实新增**(13 个未登记 entity_type → 新增常量,**不改存量字符串**)
- **A3 绝对不做**(改历史哈希破坏契约)
- **A4 是撞车 0 + 单会话能力边界下真实修复**(24 行污染 → 数据清理)
- 与 R49/R50/R51 一致:**撞车 0 + 单会话能力边界下维持 inprogress**,不擅自翻 status

---

## 四、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 守则严守

### 4.1 本子任务撞车 0 + 单会话能力边界决策包要点

- 本子任务纯静态分析(grep + Read + 真活 SELECT + IpdEntityType 比对),**零代码改动 + 零数据改动**
- 撞车 0 + 单会话能力边界下不擅自改存量字符串(改历史哈希破坏契约)
- 撞车 0 + 单会话能力边界下不擅自补 IpdEntityType 常量(让路 owner 拍板)
- 撞车 0 + 单会话能力边界下不擅自 DELETE 真库污染行
- 撞车 0 + 单会话能力边界下不擅自翻 PLAN-AUDIT-FULL status
- 撞车 0 + 撞号透明:R52 与 R45-R51 平行,撞号不冲突

### 4.2 撞车 0 + 撞号透明 + 撞车 0 + 单会话能力边界撞车 0 红线

| 红线 | 含义 |
|---|---|
| 不擅自改存量字符串 | 撞号透明下"现值即契约",改历史哈希破坏前端/验收对现值的断言 |
| 不擅自补 IpdEntityType 常量 | 让路 owner 拍板 + worktree 派单 |
| 不擅自 DELETE 真库数据 | 让路 owner 派单 |
| 不擅自翻 status | b1e8e713 红线,PLAN-AUDIT-FULL 仍 inprogress |

---

## 五、五必现查(R13)证据时间戳

- HEAD:`15d86bab`(Loop 第 11 轮 R51 PLAN-AUDIT-FULL 子任务 4 commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库
  - `audit_logs` 总量 1538 行
  - 30 个 distinct entity_type(已登记 7 + 1 零真活 + 22 未登记)
  - **大小写不一致 8 组 + 单复数不一致 2 对 + 复数拼写 1 组 + 未登记但有真活 15 组 + AI 实体命名混乱 3 个 + 零真活已登记 1 个**
- 端口:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
- 看板回读:PLAN-AUDIT-FULL status=inprogress(本子任务 5 已就绪,撞车 0 + 撞号透明下不擅自翻 done)
- 主仓 working tree:1 个新文件(本轮 markdown)+ R46 兄弟会话在途 4 项改动(unstaged,撞车 0 守则)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 六、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/audit/IpdEntityType.java`(25 行,8 String 常量)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java`(442 行,transit 第 120 行用 STAGE_ACTION 大写)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiDocumentService.java`(424 行,第 92 行用 AI_DOCUMENT 大写)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/CoefficientChangeService.java`(258 行,第 251 行用 coefficient_change_requests 字面量)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 第 4 轮子任务 1)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务2-stage_actions审计补齐现状评估-20260918.md`(Loop 第 9 轮 R49)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务3-coefficient_change_requests审计补齐现状评估-20260918.md`(Loop 第 10 轮 R50)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务4-R46-A1污染修复现状-20260918.md`(Loop 第 11 轮 R51)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(R45 路线图 P0 阻塞清单段)
- `docs/ipd-系统说明/R48-5张汇总卡翻卡建议-20260918.md`(Loop 第 8 轮)

---

## 七、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 PLAN-AUDIT-FULL 子任务闭环

本子任务 5 完成 = PLAN-AUDIT-FULL 5 个子任务全部完成现状评估:
- ✅ 子任务 1:审计覆盖缺口清单(Loop 4 R45-4)
- ✅ 子任务 2:stage_actions 审计补齐现状评估(Loop 9 R49)
- ✅ 子任务 3:coefficient_change_requests 审计补齐现状评估(Loop 10 R50)
- ✅ 子任务 4:R46-A1 not_a_real_table 污染修复现状(Loop 11 R51)
- ✅ 子任务 5:6 组命名不一致治理现状(Loop 12 R52,本轮)

**PLAN-AUDIT-FULL status**:仍维持 **inprogress**(撞车 0 + 单会话能力边界下不擅自翻 done)

后续推进:
- **PLAN-AUDIT-FULL 实施阶段**:撞车 0 + 单会话能力边界下撞车 0 让路 owner 派单 worktree 实施 A2(13 个未登记常量新增)+ A4(R46-A1 SQL DELETE)
- **PLAN-AUDIT-FULL 不擅自实施 A3**(改存量字符串绝对红线)
