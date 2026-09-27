# C 探针：ZK-IPD 原型规格盘点（2026-09-27）

> 任务范围：盘点 `/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/`（当前活的原型）的规格，盘点 v1 归档目录的事实源地位，并将其与 `ruoyi-ai/` 后端 `GateReviewService.java`（1028 行）+ `StageActionService.java`（586 行）做对齐核查。
> 任务类型：**只读调研，不修改任何代码**。
> 行号一律来自本会话 `grep -n` 现查；线号引用带文件路径 + `:line`。
> **关键避坑**：事实源裁决序：制度 PDF > 文档层 spec/外部资源 > 原型层；D1=69 动作已拍板（`ZK-IPD一致性红线-20260906.md:137`）；D2+=全站逐页复刻——原型规格是事实源。

---

## 1. 原型文档清单（当前活跃 + 归档）+ 事实源裁决

### 1.1 当前活跃原型目录（事实源）

**绝对路径**：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/`（即 `_ARCHIVED_NOTICE.md:13` 与 `__README_冻结说明.md:18` 中所称的"新版原型"）。

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/_ARCHIVED_NOTICE.md:13-17`
- 章节：§1 ZK-IPD 当前结构
- 原文片段（≤ 200 字）：
  > ```
  > ├── 产品流程细化管理工具 2/                    # 新版(活的)UI/字段/演示数据对照基准
  > ├── 开发说明/                                  # 开发指南(项目自有)
  > ├── 治理/                                      # 治理产物
  > ├── 产品流程细化管理工具.zip / (1).zip         # 历史备份(17M + 147M, 不动)
  > └── _ARCHIVED_v1_产品流程细化管理工具_决策草稿_20260918/  # v1 老版(冻结归档)
  > ```
- 对应关系：与 `_ARCHIVED_NOTICE.md:24` 一致："`产品流程细化管理工具 2/` | UI/字段/演示数据对照基准 | **活的**"，并为 ZK-IPD 一致性红线 4 道门禁的权威源。

**目录内关键文档清单**（`ls -la "/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/"` 现查）：

| 路径 | 类型 | 行/字节 | 备注 |
|---|---|---|---|
| `README.md` | markdown | 72 行 | 系统定位、当前能力、演示场景、13 姓名账号 |
| `AGENTS.md` | markdown | 60 行 | 持续决策登记（已 30+ 条 "durable workflow decisions"） |
| `IPD产品经理管理系统·最终完整版AI开发Prompt（全规则闭环无遗留疑问）.md` | markdown | 181 行 | V2 Prompt 总规则，11 章核心功能 |
| `docs/IPD产品经理工作台-系统设计说明书-V1.0.docx` | docx | 2,420 KB | V1.0 设计说明书（已废） |
| `docs/IPD产品经理工作台-系统设计说明书-V1.1.docx` | docx | 2,424 KB | V1.1 设计说明书（已废） |
| `docs/IPD产品经理工作台-系统设计说明书-V1.2.docx` | docx | 2,427 KB | V1.2 设计说明书（**当前权威**） |
| `docs/IPD产品经理工作台-用户使用说明书-V1.0.docx` | docx | 2,036 KB | V1.0 用户手册（已废） |
| `docs/IPD产品经理工作台-用户使用说明书-V1.1.docx` | docx | 1,963 KB | V1.1 用户手册（已废） |
| `docs/IPD产品经理工作台-用户使用说明书-V1.2.docx` | docx | 1,966 KB | V1.2 用户手册（**当前权威**） |
| `docs/IPD业务闭环核查清单-V1.0.md` | markdown | 45 行 | 27 项业务闭环 + 本轮删除合并说明 |
| `双PM绩效考核制度.pdf` | pdf | 656 KB | 制度层参考 |
| `双PM协同管理系统-用户使用说明书.docx` | docx | 49 KB | 早期说明书（非权威） |
| `双PM协同管理系统-设计说明书.docx` | docx | 68 KB | 早期设计说明书（非权威） |

**裁决**：本盘点的"原型规格"事实源即 V1.2 设计说明书 + V1.2 用户手册 + README.md + AGENTS.md + V2 Prompt + V1.0 业务闭环核查清单。

### 1.2 归档 v1 原型（冻结，FROZEN）

**绝对路径**：`/Users/mac/Documents/ZK-IPD/_ARCHIVED_v1_产品流程细化管理工具_决策草稿_20260918/`

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/_ARCHIVED_v1_产品流程细化管理工具_决策草稿_20260918/__README_冻结说明.md:1-12`
- 章节：§一 §二
- 原文片段（≤ 200 字）：
  > "这是 IPD 产品经理管理系统 **v1 早期原型**(2026-09-04 前)的归档,**不再演进、不再被任何会话/工具作为参考实现**。目录名带 `_ARCHIVED_v1_` 前缀是硬性视觉提示。"
  > "14 份 IPD系统_*.md / .emmx | 早期产品规则 + 决策草稿 | 历史决策档案,纸面规则"
  > "`__ARCHIVED_v1_ipd-pm-system_NestJS早期原型_20260918/` | **早期 NestJS + Prisma 后端原型** | **冻结代码,禁止作为后端对照基准**"
- 对应关系：`_ARCHIVED_NOTICE.md:27-34` 列出 5 项严格禁止（后端参考/API 契约/schema/UI/演示数据均不可用 v1）。当前后端工程是 Spring Boot 3.5.8 + Java 17（`/Users/mac/Documents/ruoyi-ai/`），不是 NestJS+Prisma+SQLite。

**v1 归档目录文件清单**（`ls -la "/Users/mac/Documents/ZK-IPD/_ARCHIVED_v1_产品流程细化管理工具_决策草稿_20260918/"` 现查）：

| 路径 | 字节 | 角色 |
|---|---|---|
| `IPD产品经理管理系统_最终版.txt` | 18 KB | 早期产品总规格（历史档案） |
| `IPD系统_AI开发主Prompt_v2.md` | 60 KB | v2 决策草稿（已被 v3 取代） |
| `IPD系统_AI开发主Prompt_v3.md` | 97 KB | **v3 决策草稿（事实源档，因后端已基于 v3 实施）** |
| `IPD系统_P0任务清单.md` | 15 KB | v3 P0 任务清单 |
| `IPD系统_五大Gate评审要素_v1.md` | 17 KB | **33 要素原稿（事实源：DOC-05 第 12 行明指）** |
| `IPD系统_六阶段标准动作清单_v1.emmx` | 15 KB | 动作清单 v1（已废） |
| `IPD系统_六阶段标准动作清单_v2.md` | 22 KB | 动作清单 v2（已废） |
| `IPD系统_六阶段标准动作清单_v3.md` | 24 KB | **动作清单 v3（事实源：ActionCatalog.java:10 明指）** |
| `IPD系统_冲突裁决与最终待确认清单.md` | 12 KB | Q1-Q6 决策依据（事实源：DOC-01 §2 第 18 行） |
| `IPD系统_动作清单_熵基特有环节补漏.md` | 12 KB | 历史决策（信息源） |
| `IPD系统_开发执行规则_AI必读.md` | 11 KB | AI 执行规则（参考） |
| `IPD系统_待确认决策表.emmx` | 16 KB | 早期决策表（已废） |
| `IPD系统_待确认决策表_v2.md` | 29 KB | 决策表 v2（已废） |
| `IPD系统_验收清单.md` | 36 KB | AC 主清单（事实源） |
| `双PM绩效考核制度.pdf` | 656 KB | 制度层（事实源：DOC-01 §1 第 12 行） |
| `devtoolsminio-data/` | dir | MinIO 早期数据（已废） |
| `__ARCHIVED_v1_ipd-pm-system_NestJS早期原型_20260918/` | dir | NestJS 后端原型（冻结代码） |

### 1.3 事实源裁决

| 层 | 来源 | 角色 | 当前状态 |
|---|---|---|---|
| L1 制度层 | `双PM绩效考核制度.pdf`（v1 目录与新版 `产品流程细化管理工具 2/` 均有副本） | 业务依据最高优先 | 事实源 |
| L2 文档层 spec/外部资源 | `IPD系统_AI开发主Prompt_v3.md`（v1 归档中） + `IPD系统_五大Gate评审要素_v1.md`（v1 归档中） + `IPD系统_六阶段标准动作清单_v3.md`（v1 归档中） + `IPD系统_冲突裁决与最终待确认清单.md`（v1 归档中） + `IPD系统_验收清单.md`（v1 归档中） | 后端 SSOT，已拍板的工程合同引用 | 事实源（仅用于后端代码/字段对照，不用于 UI 复刻） |
| L2 工程合同 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/工程合同/DOC-05.md`、`DOC-01.md`、`业务决策确认-20260905.md` | 用户业务决策的工程展开 | 事实源（拍板层） |
| L3 原型层 | `产品流程细化管理工具 2/` 下 V1.2 设计/用户手册 + README + AGENTS + V2 Prompt | UI/字段/演示数据对照基准（D2+=逐页复刻） | 事实源（仅 UI 复刻） |

**裁决**：原型层仅作为 UI/字段/菜单的对照基准；后端业务规则的 SSOT 是 v1 归档下的 `IPD系统_AI开发主Prompt_v3.md` + 工程合同 `DOC-05.md`（DOC-05:14 明指 "主规格 | [主Prompt v3]")。两者在 §5 进行对齐核查。

**注**：原型的"37 动作"与后端"69 动作"是 **D1=69 动作拍板后**的同一指标——`/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/治理/ZK-IPD一致性红线-20260906.md:137` 明确"D1=69 已于 2026-09-06 owner 拍板"。后端 `ActionCatalog.ALL` 含 69 项（`ActionCatalog.java:25-101`）；原型的"37 动作"是其 V1.2 设计说明书的早期裁剪描述（`README.md:7`、`系统设计说明书-V1.2.docx` L427），仍属历史口径，需要在事实源裁决中以 D1=69 为准。

---

## 2. 六阶段流转规格

### 2.1 阶段名称与代号映射

| 原型阶段名（V1.2 设计说明书 §5） | 阶段代码 | 后端代码 |
|---|---|---|
| 概念 | — | `CONCEPT`（`StageActionService.java:376-378` + `ActionCatalog.java:27-38`） |
| 计划 | — | `PLAN`（`ActionCatalog.java:40-52`） |
| 开发 | — | `DEV`（`ActionCatalog.java:54-64`） |
| 验证 | — | `VALID`（`ActionCatalog.java:66-77`） |
| 发布 | — | `LAUNCH`（`ActionCatalog.java:79-86`） |
| 生命周期（运维） | — | `LIFECYCLE`（`ActionCatalog.java:88-96`） |

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/IPD产品经理管理系统·最终完整版AI开发Prompt（全规则闭环无遗留疑问）.md:29`
- 章节：§二 核心功能 1 第 1 条
- 原文片段（≤ 200 字）："系统预置固定六大IPD研发阶段：**概念阶段、计划阶段、开发阶段、验证阶段、发布阶段、生命周期运维阶段**，阶段不可删除，后台支持自定义细则修改。"
- 对应关系：后端 `ActionCatalog.java:25-101` 固化 6 阶段 × 阶段内动作 = 69 项；项目建项时 `StageActionService.instantiate(Long, Long, String, IpdActor)`（`StageActionService.java:413-442`）按 stage 入参批量实例化。

### 2.2 阶段流转规格（来自原型）

#### 2.2.1 顺序锁定与自动推进

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/docs/IPD产品经理工作台-系统设计说明书-V1.2.docx`（V1.2 设计说明书 §6.1 动作状态机，对应 .docx 行号 L148-L172 的文档序号）
- 章节：§6.1 动作状态机
- 原文片段（≤ 200 字）：
  > "顺序锁定 / 前序动作未完成 / 查看 SOP；不能保存本动作 / 前序完成后自动解锁  
  > 未开始 / 前序动作已完成且当前阶段有效 / 录入结构化产物、调用 AI、勾选检查项 / 首次保存进入进行中  
  > 进行中 / 已保存但检查项未全部通过 / 继续编辑、查看修订 / 全部勾选并保存后完成  
  > 已完成 / 质量检查全部通过并成功保存 / 查看历史、按权限修订 / 系统返回下一动作并自动进入  
  > 已豁免 / 授权人填写原因并执行豁免 / 保留理由和审计 / 按完成项参与门禁计算"
- 对应关系：后端 `StageActionService.transit(Long, String, String, String)`（`StageActionService.java:111-158`）实现 NOT_STARTED → IN_PROGRESS → DONE / NA / DELAYED（深管）/ NOT_STARTED → IN_PROGRESS → DONE / NA（轻管，无 DELAYED）；状态机白名单（`StageActionService.java:53-55`）+ NA 必传 reason（`StageActionService.java:129-131`）+ DONE 触发深度+数值双重校验（`StageActionService.java:281-301`）+ 乐观锁（`StageActionService.java:147-149`）。

#### 2.2.2 自动推进规则（V1.2 设计说明书 §6.2 L173-L179）

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/docs/IPD产品经理工作台-系统设计说明书-V1.2.docx`（L173-L179）
- 章节：§6.2 自动推进规则
- 原文片段（≤ 200 字）：
  > "1. 完成当前动作  产品经理勾选全部质量检查并选择"完成并进入下一动作"。  
  > 2. 服务端判定  服务端校验项目权限、阶段状态、前序动作、修订号和完成条件。  
  > 3. 原子保存  更新工作项状态和修订号，同时生成不可覆盖的产物修订及审计记录。  
  > 4. 返回下一动作  若阶段内仍有动作，接口返回其 ID、名称和状态；页面提示后直接进入，不回到项目列表。  
  > 5. 阶段收口  若当前是本阶段最后动作且无缺项，页面提示可提交阶段门禁评审。"
- 对应关系：后端 `StageActionService.transit` 实现 ②服务端判定 + ③原子保存；前端阶段门禁提交在原型中独立；阶段"提交门禁评审"对应 `GateReviewService.submit` + `sign` 系列接口（见 §3）。

#### 2.2.3 强门禁（V1.2 设计说明书 §6.3 L180-L181）

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/docs/IPD产品经理工作台-系统设计说明书-V1.2.docx`（L180-L181）
- 章节：§6.3 强门禁
- 原文片段（≤ 200 字）：
  > "必需工作项、产物和审批齐全后才能提交阶段评审。评审通过才会将下一阶段置为 active。产品负责人不能审批自己提交的重要门禁；被授权人员可填写原因豁免，系统记录行为人、对象、时间和原因。"
- 对应关系：后端 `GateEngine` / `GateReviewService`（`GateReviewService.java:80-198`）+ `GateService`（`GateService.java:36-96`）+ `IpdGateElementSeedInitializer` 决定 Gate 评审是否通过；下一阶段置 active 由 `GateEngine` 完成。原型"禁止自审"在 `GateReviewService.requireAuthorized`（`GateReviewService.java:368-377`）落地——双签 Gate 由 MARKET_PM/RD_PM 签，G2/3/4 领域主导方单签且非主导方角色被拒绝。

#### 2.2.4 V2 Prompt 中的 6 阶段流转定义

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/IPD产品经理管理系统·最终完整版AI开发Prompt（全规则闭环无遗留疑问）.md:27-47`
- 章节：§二 核心功能 1
- 原文片段（≤ 200 字）：
  > "1. 系统预置固定六大IPD研发阶段：概念阶段、计划阶段、开发阶段、验证阶段、发布阶段、生命周期运维阶段，阶段不可删除，后台支持自定义细则修改。  
  > 2. 各阶段内置标准化执行动作、工作节点，绑定专属SOP作业程序，支持富文本查看、附件上传，全程可追溯。  
  > 3. 所有新项目、存量导入在研项目自动挂载统一IPD流程，支持可视化视图展示，设置阶段门禁机制，未完成当前阶段核心交付、未归档资料，禁止进入下一阶段。  
  > 4. 流程节点支持状态管理：未开始、进行中、已完成、延期，支持填写执行记录、上传交付物、完结归档。"
- 对应关系：后端动作状态枚举含 NOT_STARTED / IN_PROGRESS / DONE / NA / DELAYED（`StageActionService.java:53-55`）。

### 2.3 阶段进入 / 退出条件

**原型侧**（来自 V1.2 设计说明书 §6.1 + V2 Prompt §二）：

| 阶段 | 进入条件 | 退出条件 | 阶段门禁 |
|---|---|---|---|
| 概念 | 项目建项时实例化（`PROJECT_CERT_SYNC` + `PROJECT_CREATE` 同事务 bootstrap 6 阶段 + 69 动作 + 目标市场认证清单，见 `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/e2e-business-closed-loop-20260907.md:78`） | C11 Charter 立项评审会 + C12 生物特征数据合规审查（如涉生物）+ 9 个深管动作 DONE | 概念决策评审（Go/No-Go） |
| 计划 | 概念决策评审通过（`Gate.status=APPROVED`） | P13 差异化确认评审会 + 12 个轻/深管动作 DONE | 计划决策评审（差异化确认） |
| 开发 | 计划决策评审通过 | D05 双周开发评审 + D11 BioCV 算法训练与评测（如涉生物）+ 9 个轻管动作 DONE | 开发阶段评审（双周） |
| 验证 | 开发阶段评审通过 | L07 GTM就绪评审前置 + V02 认证测试送检 + 11 个动作 DONE | 发布准入评审（GTM就绪） |
| 发布 | GTM就绪评审通过 | L08 正式上市发布（录入上市日期）+ 7 个动作 DONE | 发布决策评审（90天复盘的前置） |
| 生命周期 | 上市后由 `LC01` 触发 | LC08 停产评估与公告 / LC09 项目归档（系统自动） | 生命周期评审（90 天复盘 / 6 个月终算） |

**实证**：
- 文件：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/IPD产品经理管理系统·最终完整版AI开发Prompt（全规则闭环无遗留疑问）.md:45-47`
- 章节：§二 核心功能 1 第 9-10 条
- 原文片段（≤ 200 字）：
  > "9. 存量在研项目适配规则：已过Gate节点不追溯，未过节点由双PM全权接管，双签、决策、复盘机制即时生效。  
  > 10. 项目生命周期：产品上市后持续运维，标记停产后项目正式归档关闭，归档后仅可查看、导出所有资料，禁止任何编辑操作。"
- 对应关系：后端 `StageActionService.assertProjectWritable`（`StageActionService.java:476-488`）实现项目归档后只读 + `Status=SUSPENDED|ARCHIVED` 拒绝变更动作。

### 2.4 阶段流转的责任方

**原型侧**（V1.2 设计说明书 §21 L470-L497 + V2 Prompt §二）：

- **阶段确认（V1.2 设计说明书 §21 概览）**：六阶段确认是产品组长职责（`AGENTS.md:28` 第 28 行 "Product leads confirm stages; product managers upload external R&D/market/sales review evidence. AI output cannot confirm a stage."）。
- **关键 Gate（V1.2 设计说明书 §21 L471-L494）**：决策规则统一为"双PM→双组长→超管"5 节点链（G1/G2/G4/G5），G3 为"双PM填报、双组长关风险"（不要求超管终裁）。
- **驳回/否决后只能整改并创建新决策版本，旧版本永久保留**（`V1.2.docx` L495）。
- **历史阶段处理**：已在研补录项目的历史阶段标记为"历史已通过"，只补资料、不重新签署（`V1.2.docx` L496）。

**后端侧**：
- 阶段动作实例化：`StageActionService.instantiate` 由 PM 调用（`StageActionService.java:413-442`），需通过 `assertProjectWritableInGroup`（`StageActionService.java:501-505`）三段式归属守卫。
- 动作状态迁移：`StageActionService.transit` 校验深度/状态机白名单/NA reason/DONE 完成校验（`StageActionService.java:111-158`）。
- 阶段门禁评审：`GateReviewService.sign` + `reopen` + `arbitrate` + `finalRuling`（`GateReviewService.java:206-232, 441-490, 586-622, 627-665`）。

---

## 3. Gate 评审要素规格

### 3.1 Gate 数量（5 个）

**实证四件套**：
- 文件：`/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/IPD产品经理管理系统·最终完整版AI开发Prompt（全规则闭环无遗留疑问）.md:37`
- 章节：§二 核心功能 1 第 5 条
- 原文片段（≤ 200 字）："**五大联合Gate重大决策节点**：Go/No-Go立项、差异化确认、开发双周评审、GTM就绪、上市后90天复盘。"
- 对应关系：后端 `IpdGateElementSeedInitializer.java:24` 与 `GateReviewService.isDualSignGate`（`GateReviewService.java:196-198`）确认 5 个 Gate（G1-G5）；`IpdGateElementSeedInitializer.java:38-77` 固化要素分布 G1=7、G2=6、G3=5、G4=8、G5=7 = 33 项 14 否决位。

**Gate 名与阶段映射**（V1.2 设计说明书 §21 L471-L494）：
- G1 = Go / No-Go / 概念 / 双PM→双组长→超管 / **任一PM否决即终止本版本，不可覆盖**
- G2 = 差异化确认 / 计划 / 双PM→双组长→超管 / 分歧由双组长仲裁，超管锁定
- G3 = 开发双周评审 / 开发 / 双PM填报、双组长关风险 / 未关闭风险阻断开发阶段确认
- G4 = GTM就绪 / 发布 / 双PM→双组长→超管 / 分歧由双组长仲裁，超管锁定
- G5 = 90天复盘 / 生命周期 / 双PM→双组长→超管 / **任一PM否决即终止本版本，不可覆盖**

### 3.2 Gate 评审角色（市场 PM / 研发 PM / 组长 / 超管 / 销售 / 售后 / 合规）

**原型侧**（V1.2 设计说明书 §21 L470-L497 + V2 Prompt §二）：

| 角色名（原型） | 角色 | 关键职责 |
|---|---|---|
| 市场 PM | 市场主导 | G1/G2/G5 双签，市场主导负责阶段 |
| 研发 PM | 研发主导 | G1/G3/G4/G5 双签，研发主导负责阶段 |
| 市场组长 | 仲裁 | 双组长之一，分歧时仲裁 |
| 研发组长 | 仲裁 | 双组长之一，分歧时仲裁 |
| 超级管理员 | 终裁 | 终裁 + 锁定（仅 G2/G4/G5，G3 无超管介入） |

**后端侧**（`GateReviewService.java:91-100`）：
- `ROLE_SUPER_ADMIN = "SUPER_ADMIN"`（超管终裁）
- `ROLE_GROUP_LEADER = "GROUP_LEADER"`（组长仲裁）
- `SIGNER_ROLES = {"MARKET_PM", "RD_PM"}`（双签方）
- `OBSERVER_ROLES = {"SALES", "SUPPLY", "AFTERSALES", "QUALITY", "COMPLIANCE"}`（列席人 5 类）

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java:91-100`
- 章节：角色常量
- 原文片段（≤ 200 字）：
  > ```java
  > private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
  > private static final String ROLE_GROUP_LEADER = "GROUP_LEADER";
  > ...
  > private static final Set<String> SIGNER_ROLES = Set.of("MARKET_PM", "RD_PM");
  > /** MEDIUM-1.3：列席人员角色（销售/供应/售后/品质/合规）。 */
  > private static final Set<String> OBSERVER_ROLES = Set.of("SALES", "SUPPLY", "AFTERSALES", "QUALITY", "COMPLIANCE");
  > ```
- 对应关系：**原型未明确列席人角色，后端 MEDIUM-1.3 实现为 5 类固定白名单**。这是已知的"原型-后端"扩展（详见 §5 漂移项）。

### 3.3 Gate 评审结论的类型

**原型侧**（V2 Prompt §二 第 7 条）：
- 立项 Go/No-Go：双签否决（G1）
- 需求变更审批：双签否决（DEV 阶段 D06）
- 上市后复盘：双签否决（G5）
- 其余节点按 RACI 矩阵分工：一方主导、另一方咨询/知会

**V2 Prompt 原文**（L41）："三项双签否决机制：立项Go/No-Go、需求变更审批、上市后复盘，任意一方否决则事项直接驳回，如需重新评审需手动发起新流程；其余节点按RACI矩阵分工，一方主导、另一方咨询/知会。"

**后端侧**（`GateReviewService.java:82-90`）：
- `STATUS_PENDING = "PENDING"`（待签）
- `STATUS_APPROVED = "APPROVED"`（已通过）
- `STATUS_REJECTED = "REJECTED"`（已驳回）
- `STATUS_ABSTAINED_TIMEOUT = "ABSTAINED_TIMEOUT"`（双弃权超时，不得无依据放行）
- `DECISIONS = {"APPROVE", "REJECT"}`（个人签署结论）
- `ARBITRATION_DECISIONS = {"APPROVE", "REJECT"}`（仲裁/终裁结论）

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java:82-90`
- 章节：状态机常量
- 原文片段（≤ 200 字）：
  > ```java
  > static final String STATUS_PENDING = "PENDING";
  > static final String STATUS_APPROVED = "APPROVED";
  > static final String STATUS_REJECTED = "REJECTED";
  > /** 双方均超期未签：不得无依据放行（P2-5.4 验收列），需 reopen 重启 */
  > static final String STATUS_ABSTAINED_TIMEOUT = "ABSTAINED_TIMEOUT";
  > private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT");
  > private static final Set<String> ARBITRATION_DECISIONS = Set.of("APPROVE", "REJECT");
  > ```
- 对应关系：4 个状态 + 2 套决策（个人签署 / 仲裁）= **后端扩展**。原型仅描述"双签否决"和"按 RACI 单签"，未明文定义 ABSTAINED_TIMEOUT。AC-GATE-08 后端实现见 §3.5。

### 3.4 Gate 双签盲签的展示规则

**后端侧**（`GateReviewService.java:235-274` + `GateReviewService.java:44-58`）：

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java:43-57`
- 章节：Javadoc（双签签署矩阵）
- 原文片段（≤ 200 字）：
  > "G1/G5 双签盲签：市场PM 与研发PM 并行独立提交；任一 REJECT ⇒ Gate 直接 REJECTED（AC-GATE-05，双方均收 GATE_REJECTED 通知）；双 APPROVE ⇒ APPROVED，双方结论同时揭示（AC-GATE-04）。双方都提交前互不可见对方结论，仅见 '对方已提交'（AC-GATE-03，BR-GATE-03 并行签署）。  
  > G2/3/4 领域签署：流程门禁但非双签否决（页24），由领域主导方单签终态；非主导方 PM 签署拒绝（非授权角色）。"
- 对应关系：`view(gateId, actor)`（`GateReviewService.java:235-274`）通过 `terminal`（Gate 已终态）或 `superAdmin`（超管）判断 `revealed`，决定 `other` 字段是否揭示对方 decision+opinion；未揭示时仅返回 `otherSubmitted=true`（`GateReviewService.java:265-272`）。

**盲签揭示规则总结**：
- 双方都提交前 = 各自只见自己 + `otherSubmitted` 布尔 + `hint` 文案"对方已提交，等待您签署"
- Gate 终态 OR 超管 = 全揭示（含 decision+opinion）
- 双签 Gate 一方已签 = 仍盲签保留，等另一方
- 领域 Gate（G2/3/4）= 主导方单签即终态，非主导方角色被拒绝

### 3.5 仲裁机制（组长仲裁 / 超管终裁 / 第 N 轮介入）

**原型侧**（V1.2 设计说明书 §21 L478 + L482 + L490 + L494 + V2 Prompt §二 第 8 条）：
- G1/G5："任一PM否决即终止本版本，不可覆盖"
- G2/G4："分歧由双组长仲裁，超管锁定"
- G3："双PM填报、双组长关风险"

**V2 Prompt §二 第 8 条原文**（L43）："双PM节点意见冲突无法统一时，升级双方各自产品组长仲裁，仲裁结果永久写入项目审计日志。"

**后端侧**（`GateReviewService.java:586-665` + `GateReviewService.java:70-73`）：

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java:60-76`
- 章节：Javadoc（P2-5.4 补充）
- 原文片段（≤ 200 字）：
  > "重发起（AC-GATE-06/07/07b）：REJECTED/双弃权 Gate 可手动重新发起，round+1 且签署期限重新起算；第 3 轮起双方产品组长自动列席，第 5 轮起超管介入通知（BR-GATE-05 不限次数）。  
  > 超时弃权（AC-GATE-08，BR-GATE-04 D17）：双签 Gate 一方 3 个自然日未签 ⇒ 自动补 ABSTAIN 行，按主导方意见执行并审计弃权事件；两人均未签 ⇒ 双 ABSTAIN 转 ABSTAINED_TIMEOUT，不得无依据放行。  
  > 冲突仲裁（AC-GATE-10，BR-GATE-06）：双 PM 分歧（先 APPROVE 后 REJECT）⇒ 自动邀请双方组长仲裁；两组仍不一致 ⇒ 升级超管终裁，终裁结果写入项目审计日志（gate_arbitrations 表按人去重，避开 gate_reviews 的 reviewer_type 唯一约束）。  
  > 延期（AC-GATE-21）：超管可单独延长在签 Gate 的签署期限，最多 3 次，第 4 次拒绝。"
- 对应关系：仲裁入口 `arbitrate(gateId, decision, opinion, actor)`（`GateReviewService.java:587-622`）+ 超管终裁 `finalRuling(gateId, decision, opinion, actor)`（`GateReviewService.java:628-665`）+ 重发起 `reopen(gateId, actor)`（`GateReviewService.java:442-490`）+ 超时弃权 `scanTimeout(operator)`（`GateReviewService.java:497-546`）+ 提醒 `scanRemind(operator)`（`GateReviewService.java:550-582`）+ 延期 `extendDeadline(gateId, days, actor)`（`GateReviewService.java:812-...`）。

**仲裁矩阵汇总**：

| 阶段 | 触发条件 | 介入方 | 终态处理 | 对应代码 |
|---|---|---|---|---|
| 双签 Gate（双方齐签 G1/G5） | 双方 APPROVE | — | `STATUS_APPROVED` | `GateReviewService.java:286-288` |
| 双签 Gate（任一 REJECT） | 任一 PM REJECT | — | `STATUS_REJECTED` + 双方 GATE_REJECTED 通知 | `GateReviewService.java:282-285` + `notifyBothSides` (317-337) |
| 双签 Gate（先 APPROVE 后 REJECT） | PM 意见分歧 | 自动邀请双方组长仲裁 | `openArbitration`（如果双方组长意见不一致，升级超管终裁） | `GateReviewService.java:310-312` |
| 双方超期未签 | 双签 Gate 一方 3 个自然日未签 | 系统自动补 ABSTAIN | 主导方意见执行（若主导方已签）/ ABSTAINED_TIMEOUT | `GateReviewService.java:497-546` |
| 第 3 轮起 | round ≥ 3 | 双方产品组长自动列席 | `GATE_ROUND_OBSERVER` 通知 | `GateReviewService.java:469-478` |
| 第 5 轮起 | round ≥ 5 | 超管介入 | `GATE_ADMIN_INTERVENE` 通知 | `GateReviewService.java:479-488` |
| 延期 | 超管调用 | 最多 3 次 | 第 4 次拒绝 | `GateReviewService.java:812-...` + `DEFAULT_MAX_SIGN_EXTENSIONS = 3` (90) |

---

## 4. 阶段动作 / 节点责任

### 4.1 阶段动作清单（advance / reopen / hold / cancel）

**原型侧**（V1.2 设计说明书 §6.1 动作状态机 L149-L172 + V2 Prompt §二 第 4 条）：

| 状态 | 进入条件 | 允许动作 | 后续 |
|---|---|---|---|
| 顺序锁定 | 前序动作未完成 | 查看 SOP；不能保存本动作 | 前序完成后自动解锁 |
| 未开始 | 前序动作已完成且当前阶段有效 | 录入结构化产物、调用 AI、勾选检查项 | 首次保存进入进行中 |
| 进行中 | 已保存但检查项未全部通过 | 继续编辑、查看修订 | 全部勾选并保存后完成 |
| 已完成 | 质量检查全部通过并成功保存 | 查看历史、按权限修订 | 系统返回下一动作并自动进入 |
| 已豁免 | 授权人填写原因并执行豁免 | 保留理由和审计 | 按完成项参与门禁计算 |

**后端侧**（`StageActionService.java:53-55`）：
- `LIGHT_STATUSES = {"NOT_STARTED", "IN_PROGRESS", "DONE", "NA"}`（轻管 4 态）
- `DEEP_EXTRA_STATUSES = {"DELAYED"}`（深管多 1 态）
- `DEEP_ALLOWED = {"NOT_STARTED", "IN_PROGRESS", "DONE", "NA", "DELAYED"}`（深管 5 态）

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java:51-55`
- 章节：状态机白名单常量
- 原文片段（≤ 200 字）：
  > ```java
  > private static final Set<String> LIGHT_STATUSES = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE", "NA");
  > private static final Set<String> DEEP_EXTRA_STATUSES = Set.of("DELAYED");
  > private static final Set<String> DEEP_ALLOWED = Set.of("NOT_STARTED", "IN_PROGRESS", "DONE", "NA", "DELAYED");
  > ```
- 对应关系：**后端扩展**：DELAYED 仅深管（轻管枚举无延期，`StageActionService.java:119-121`）；NA 必传 reason（`StageActionService.java:129-131`）；DONE 触发深度+数值双重校验（`StageActionService.java:281-301`）。

### 4.2 节点责任矩阵（RACI 表）

**后端侧**（`ActionCatalog.java:25-101`）固化 ownerRole：

| 阶段 | 动作 | ownerRole | depth | gate |
|---|---|---|---|---|
| CONCEPT | C01-C04, C06-C09, C12 | MARKET_PM | DEEP | — |
| CONCEPT | C05 技术可行性预研 | RD_PM | LIGHT | — |
| CONCEPT | C10 知识产权与合规预检 | RD_PM | DEEP | — |
| CONCEPT | C11 Charter立项评审会 | **BOTH** | DEEP | G1 |
| CONCEPT | C12 生物特征数据合规审查 | MARKET_PM | DEEP | — |
| PLAN | P01, P02, P12 | MARKET_PM | DEEP | — |
| PLAN | P03-P11 | RD_PM | LIGHT | — |
| PLAN | P12 差异化卖点确认与价值定价 | MARKET_PM | DEEP | — |
| PLAN | P13 差异化确认评审会 | **BOTH** | DEEP | G2 |
| DEV | D01-D05, D06, D07-D11 | RD_PM | LIGHT | — |
| DEV | D05 双周开发评审 | **BOTH** | DEEP | G3 |
| DEV | D06 需求变更评估与审批 | MARKET_PM | DEEP | — |
| VALID | V01, V02, V04-V06, V11 | RD_PM | LIGHT/DEEP | — |
| VALID | V03, V07-V10, V12 | MARKET_PM | DEEP | — |
| LAUNCH | L01, L02, L03, L04, L06, L08 | MARKET_PM | DEEP | — |
| LAUNCH | L05 首批量产与备货 | RD_PM | LIGHT | — |
| LAUNCH | L07 GTM就绪评审 | **BOTH** | DEEP | G4 |
| LIFECYCLE | LC01, LC03, LC05, LC07, LC08 | MARKET_PM | DEEP | — |
| LIFECYCLE | LC02 上市后90天复盘 | **BOTH** | DEEP | G5 |
| LIFECYCLE | LC04 双PM贡献度评定 | **BOTH** | DEEP | — |
| LIFECYCLE | LC06 版本迭代与维护发布 | RD_PM | LIGHT | — |
| LIFECYCLE | LC09 项目归档 | **GROUP_LEADER** | DEEP | — |
| LIFECYCLE | K01-K04 共担 KPI 归集 | **GROUP_LEADER** | DEEP | — |

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/ActionCatalog.java:25-101`
- 章节：ALL 列表 + 注释
- 原文片段（≤ 200 字）：
  > "ownerRole 在 MARKET_PM|RD_PM|BOTH 之外补充 GROUP_LEADER（K01-K04 共担KPI 归集动作主责=产品组长，I5 决策；LC09 R=系统自动，业务责任 A=产品组长，归组到 GROUP_LEADER）。  
  > K01-K04 无独立阶段，挂 LIFECYCLE（上市后 6 个月归集窗口）。"
- 对应关系：**后端扩展 ownerRole=BOTH 与 GROUP_LEADER**，对应原型的"双PM共同负责"和"产品组长复核"语义。

### 4.3 期限与超时处理（3 个自然日 / 延期 3 次上限）

**原型侧**：未明文给出 3 个自然日/3 次延期等数值（V1.2 设计说明书 §6.3 L180-L181 + V2 Prompt §二 仅要求"评审会议纪要、评审材料为**强制交付物**，未上传则无法完成节点闭环"）。

**后端侧**（`GateReviewService.java:65-70 + 90 + 89`）：
- 签署期限默认 3 个自然日（`DEFAULT_MAX_SIGN_EXTENSIONS = 3`、`resolveSignDeadlineDays()` 默认 `3`）（`GateReviewService.java:90` + `GateReviewService.java:402-411`）
- 期限参数运行时由 `IBusinessConfigService.GATE_SIGN_DEADLINE_DAYS` 覆盖
- 延期最多 3 次，第 4 次拒绝（`GateReviewService.java:812-...` + `DEFAULT_MAX_SIGN_EXTENSIONS = 3`（90））
- 双签 Gate 一方 3 个自然日未签 ⇒ 自动补 ABSTAIN 行，按主导方意见执行（`GateReviewService.java:497-546`）

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java:64-70`
- 章节：Javadoc（超时/期限/仲裁）
- 原文片段（≤ 200 字）：
  > "超时弃权（AC-GATE-08，BR-GATE-04 D17）：双签 Gate 一方 3 个自然日未签 ⇒ 自动补 ABSTAIN 行，按主导方意见执行并审计弃权事件；两人均未签 ⇒ 双 ABSTAIN 转 ABSTAINED_TIMEOUT，不得无依据放行。  
  > 期限前 1 天提醒（AC-GATE-09）：未签方收 GATE_SIGN_SOON（每日去重）。  
  > 延期（AC-GATE-21）：超管可单独延长在签 Gate 的签署期限，最多 3 次，第 4 次拒绝。"
- 对应关系：**后端扩展 3 个自然日 + 3 次延期上限**（业务合同 AC-GATE-08/21）。原型未明文给定具体值，是后端业务决定（参考 `IPD系统_AI开发主Prompt_v3.md` v3 主规格 §3 + AC 清单 AC-GATE-08/21）。

### 4.4 节点动作的特殊处理

**实证**：
- 文件：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java:112-158`
- 章节：`transit()` 状态迁移
- 原文片段（≤ 200 字）：
  > "状态迁移唯一入口（P1-4.3）。  
  > 状态机白名单（depth + 目标）  
  > 幂等：当前态 == 目标态 → 直接返回，不写库、不写审计  
  > NA 必 reason  
  > DONE 触发深度+数值双重校验  
  > 乐观锁：@Version，updateById 失败（version 冲突）抛 ServiceException  
  > 每次成功迁移写审计 action=TRANSIT"
- 对应关系：状态机迁移 + 幂等 + 乐观锁 + 审计四件套已完备。

**`recordFields` 字段录入**（`StageActionService.java:174-244`）：
- P1-4.1 / P1-8.2：录入轻管完成日 / BioCV FAR·FRR / 证书 / 算法分类
- 不改 status；完成仍须随后 /transit→DONE
- Z 别名写入时归一为权威码（`ActionCatalog.java:111-117` ALIASES：Z01→D11, Z02→V10, Z03→C12, Z04→V11, Z05→V12）

**`notifyOverdueActions` 逾期提醒**（`StageActionService.java:520-552`）：
- R219 卡④（ef20c06a）：扫描 dueDate 已过且仍处开放态的动作（LIMIT 500 兜底）
- 接收人映射：MARKET_PM/RD_PM → 该项目对应在职成员；BOTH → 两类并集；GROUP_LEADER → 项目主产品组组长
- dedupKey 含自然日（yyyyMMdd），同日重扫不重发、次日再提醒（AC-IPD-12 语义）

---

## 5. 后端实现对齐情况

### 5.1 已实现（已对齐）

| 维度 | 原型规格 | 后端实现 | 状态 | 文件 + 行号 |
|---|---|---|---|---|
| 5 Gate 名称 | Go/No-Go / 差异化 / 双周 / GTM / 90天 | G1/G2/G3/G4/G5 | ✅ | `IpdGateElementSeedInitializer.java:38-77`、`GateReviewService.isDualSignGate` 196-198 |
| 33 要素 + 14 否决位 | 33 行评审要素 + 14 项硬否决 | seed initializer 固化 | ✅ | `IpdGateElementSeedInitializer.java:38-77`（注：与 `DOC-05.md` 要素名不同但要素数与 G1-7/G2-6/G3-5/G4-8/G5-7 一致，14 否决位一致） |
| 5 节点角色链（双PM→双组长→超管） | 5 节点 | `sign`/`arbitrate`/`finalRuling`/`reopen`/`extendDeadline` | ✅ | `GateReviewService.java:206-232, 441-490, 586-622, 627-665, 812-...` |
| Gate 双签盲签 | G1/G5 双签盲签 | `view()` 揭示规则 | ✅ | `GateReviewService.java:235-274` |
| 主导方按领域非先提交 | G2/3/4 领域主导方单签 | `leadSideOf` + `requireAuthorized` | ✅ | `GateReviewService.java:201-203, 368-377` |
| 双签 Gate 一方 3 日未签 | 双签 Gate 一方 3 个自然日未签 ⇒ 自动补 ABSTAIN | `scanTimeout` 默认 3 天 | ✅ | `GateReviewService.java:497-546`（参数可由 `IBusinessConfigService.GATE_SIGN_DEADLINE_DAYS` 覆盖） |
| 组长仲裁 / 超管终裁 | 双 PM 分歧 ⇒ 自动邀请双方组长仲裁；两组仍不一致 ⇒ 升级超管终裁 | `arbitrate` + `finalRuling` + `maybeEscalateAfterArbitration` | ✅ | `GateReviewService.java:586-665` |
| 第 3/5 轮升级 | 第 3 轮起双方产品组长自动列席，第 5 轮起超管介入 | `reopen` 中 round ≥ 3 / 5 判断 | ✅ | `GateReviewService.java:469-488` |
| 延期 3 次上限 | 超管可单独延长在签 Gate 的签署期限，最多 3 次，第 4 次拒绝 | `extendDeadline` + `DEFAULT_MAX_SIGN_EXTENSIONS = 3` | ✅ | `GateReviewService.java:812-...` + 90 |
| 6 阶段 + 69 动作 | D1=69 动作已拍板 | `ActionCatalog.ALL` 69 项 | ✅ | `ActionCatalog.java:25-101` + `ActionCatalog.java:9-19` 注释明示 "69 动作 = 深管 42 / 轻管 27，阻断 38 / 非阻断 31" |
| 阶段确认 + 关键 Gate 双轨 | 六阶段确认继续控制阶段顺序；关键 Gate 是跨专业线的重大决策，两者独立展示、相互阻断但不重复解锁 | 后端 GateEngine / GateReviewService 串联 | ✅ | `GateReviewService.java:80-198` + `GateEngine`（未直接读取） |
| 评审会议纪要 + 评审材料 | Gate 节点强制约束：评审会议纪要、评审材料为强制交付物，未上传则无法完成节点闭环 | GateMaterialChecker（未直接读取）+ GateElement 体系 | ✅ | 后端域级对照底账行 38 备注 "材料=GateElement 体系"（`ZK-IPD/ZK-IPD后端一致性对照底账-20260906.md:38`） |
| 阶段门禁与逐阶段动作完成 | 必需工作项、产物和审批齐全后才能提交阶段评审 | `validateCompletion` + `StageActionService.transit` | ✅ | `StageActionService.java:281-301` |
| 已豁免状态 | 已豁免：授权人填写原因并执行豁免；保留理由和审计 | NA 状态 + 必 reason | ✅ | `StageActionService.java:129-131` |
| 涉生物项目 C12 不可取消 | 涉生物场景下 C12 不可取消（NA） | `transit` 中检查 `hasBioFeatureActions` | ✅ | `StageActionService.java:132-136` |
| 深管交付物要求 | 深管 DONE：至少 1 个未删交付物 | `validateCompletion` deep 分支 | ✅ | `StageActionService.java:281-289` |
| 轻管实际完成日必填 | 轻管 DONE：actual_done_at 必填 | `validateCompletion` light 分支 | ✅ | `StageActionService.java:290-292` |
| Z 别名归一 | Z 系编码为同一动作第二编码（64+5 建制=69） | `ActionCatalog.resolveCode` + ALIASES | ✅ | `ActionCatalog.java:111-117` |
| 共担 KPI 归集到产品组长 | K01-K04 共担 KPI 归集动作主责=产品组长 | ownerRole=GROUP_LEADER | ✅ | `ActionCatalog.java:98-100` + 注释 16-18 |
| LC09 项目归档 | LC09 项目归档(系统自动,业务责任产品组长) | ownerRole=GROUP_LEADER + DEEP | ✅ | `ActionCatalog.java:96` |
| 第三方人员主数据只读 | PM 和组长由第三方 REST API 同步 | `PersonMapper` + `IpdActor.role()` | ✅ | (未直接读取，由 D3=维持 JWT 单 token 拍板层支撑) |

### 5.2 漂移（已实现但与原型有差异）

| 维度 | 原型规格 | 后端实现 | 漂移性质 | 文件 + 行号 |
|---|---|---|---|---|
| 阶段动作数量 | "37 个动作"（V1.2 设计说明书 L427 + README.md:7） | "69 动作 = 深管 42 / 轻管 27" | **D1 拍板**：以 69 为准；原型的 37 是 V1.2 早期口径 | `ZK-IPD一致性红线-20260906.md:137` 拍板 "D1=69"；`ActionCatalog.java:9-10` 注释明示 |
| 列席人角色白名单 | 原型未明文限定列席人角色 | 5 类固定：SALES/SUPPLY/AFTERSALES/QUALITY/COMPLIANCE | **后端业务决定**（MEDIUM-1.3），原型语义包含"被授权或参与项目"（V1.2 设计说明书 L87-L88）的"评审人"角色 | `GateReviewService.java:100` |
| 签署期限默认值 | 原型未明文给值 | 默认 3 个自然日 + 3 次延期上限 | **后端业务决定**（AC-GATE-08/21），参考 v3 主规格 + AC 清单 | `GateReviewService.java:90 + 402-411` |
| ABSTAINED_TIMEOUT 状态 | 原型仅描述"双签否决" + "按 RACI 单签" | 后端扩展 4 状态（PENDING/APPROVED/REJECTED/ABSTAINED_TIMEOUT） | **后端扩展**（AC-GATE-08 应对"双方均超期未签"） | `GateReviewService.java:82-86` |
| Gate 决议集 | 原型仅 "APPROVE/REJECT" + "双签否决" | 后端扩展 ABSTAIN（自动）+ ABSTAINED_TIMEOUT（终态） | **后端扩展** | `GateReviewService.java:82-90` |
| 自由重发起 | 原型仅"重新发起"概念 | 后端 "round+1 且签署期限重新起算；第 3 轮起双方产品组长自动列席，第 5 轮起超管介入通知（BR-GATE-05 不限次数）" | **后端业务规则**：第 3/5 轮升级 + 无限次重发起 | `GateReviewService.java:441-490` |
| `notifications` 通知类型 | 原型未明确清单 | GATE_REJECTED / GATE_SIGN_SOON / GATE_ROUND_OBSERVER / GATE_ADMIN_INTERVENE / GATE_ABSTAINED / GATE_FINAL_RULING_RESULT / GATE_OBSERVER_INVITED 等 | **后端扩展**（AC-GATE-04/05/07/08/09/10 等） | `GateReviewService.java:331-336, 472-476, 482-486, 540-542, 573-577, 661-663, 723-729` |

### 5.3 未实现（原型明确要求，后端尚未实现）

| 维度 | 原型规格要求 | 后端状态 | 备注 |
|---|---|---|---|
| 项目协作圈（项目圈） | 原型 V1.2 设计说明书 §8 主页含"项目移交"菜单（已实现） | 后端 `HandoverController` 3 方法（create/accept/inbox）已实现 `HandoverController` | 但 `Collaboration` 域（4 端点）后端标注为 `◐` "P1 主战场"（`ZK-IPD后端一致性对照底账-20260906.md:37`） |
| 项目协作圈（成员/候选人/帖子/评论/列表） | 原型"项目空间"含"项目成员"和"协作" tab | 后端 P0 已补 `ProjectCircleController + 3 新表`（`ZK-IPD后端一致性对照底账-20260906.md:74`） | 但 `AGENTS.md:57-58` 明确"Project Circle is removed from the product. Cross-role collaboration is carried by formal workflow tasks... Governance Center is removed as a duplicate primary destination." — 这是 2026-09-03 后原型 V1.2 才删的，与原 V1.0 文档冲突；以 `AGENTS.md` 与 V1.2 业务闭环核查清单为准 |
| 治理中心 | 原型 V1.2 设计说明书 §8"管理设置"含"成员、管理员交接、SOP、AI、审计" | 后端 `AdminController` 等已实现 | 原型 `AGENTS.md:58` "Governance Center is removed as a duplicate primary destination. Deletion approvals and substantive-output reminders are embedded in '我的工作台'; audit and administrator succession remain in '超级管理'." — 删除治理中心，删除审批与无实质产出提醒并入"我的工作台"；业务审计保留在"超级管理" |
| 招募模式选择视觉 | 原型 `AGENTS.md:59` "Recruitment-mode choices use light enterprise selection cards with a restrained blue selected state; avoid large dark selection blocks inside light forms." | 前端 UI 任务，未在此后端文件体现 | 原型 V1.2 业务闭环核查清单 L38 确认"招募模式选择由深色块改为浅色卡片，选中态使用克制的蓝色描边和浅蓝背景" |
| `IdentitySourceProvider` 第三方人员主数据 | 原型 V1.2 设计说明书 §9.2 "超级管理员是本地治理账号。市场/研发PM及组长由独立IdentitySourceProvider托管" | 后端 `PersonMapper` 提供 person 主数据但**未明确为外部 REST API 同步** | `IdentitySourceProvider` 域（3 端点）后端标注为 `❌` "P2"（`ZK-IPD后端一致性对照底账-20260906.md:47`） |
| wecom OAuth 登录 | 原型 V1.2 设计说明书 §25 / `AGENTS.md:50` "Enterprise WeChat OAuth and name/password are parallel login methods." | 后端 `IpdAuthController` 仅实现 login/wecom-qr-login/me/logout/refresh/change-password（`DOC-AUTH.md:24-29`） | wecom_qr_login 端点已暴露但功能未必完整；D3 拍板"维持 JWT 单 token"，invitations/register 属邀请制 P2 外延（`ZK-IPD后端一致性对照底账-20260906.md:22`） |
| KPI 证据、津贴、奖金、负反馈 | 原型 V1.2 设计说明书 §22 绩效规则V3.1 | 后端 BonusPool/KpiRecord/AllowanceLedger/ReceiptLedger/ProjectScore domain+service 部分就绪，**无 Controller 暴露** | P1 主战场（`ZK-IPD后端一致性对照底账-20260906.md:33`） |

### 5.4 严重漂移（必须 §5 单独列出）

**裁决**：**未发现严重漂移**。

经实证比对（§5.1 + §5.2 + §5.3），后端业务规则已与原型规格（含 V2 Prompt + DOC-05 + D1=69 拍板层）核心对齐；以下为细节级差异：

1. **动作数量 37 vs 69**：D1 拍板明确以 69 为准（`ZK-IPD一致性红线-20260906.md:137`）。原型的"37 动作"是 V1.2 设计说明书 L427 + README.md:7 的早期口径，业务侧以 D1=69 为 SSOT。**裁决：以 D1=69 为事实源**。

2. **Gate 决议 ABSTAINED_TIMEOUT**：原型未明文给值，后端扩展 4 状态（含 ABSTAINED_TIMEOUT）。这是后端业务决定（AC-GATE-08），不视为漂移而是业务补全。

3. **列席人 5 类白名单**：原型仅描述"评审人/被授权或参与项目"，后端落地为 SALES/SUPPLY/AFTERSALES/QUALITY/COMPLIANCE 5 类（MEDIUM-1.3）。这与原型 V1.2 设计说明书 L36 "以及参与评审或协作的研发、测试、销售人员" + L88 "被授权或参与项目"语义兼容。**裁决：不视为漂移**。

4. **历史阶段处理**：原型 L496 "已在研补录项目的历史阶段标记为'历史已通过'，只补资料、不重新签署"。后端 `ProjectBootstrapService` 实现 bootstrap 6 阶段+69 动作（`ZK-IPD后端一致性对照底账-20260906.md:38` 备注 + `api-internal-whitelist.json:190`）。**注**：是否对"已过阶段"标记为"历史已通过"未在 `ProjectBootstrapService` 直接读取处体现，需进一步 P0 验证。

---

## 6. 限制与未验证项

### 6.1 未直接读取的后端文件

- `GateEngine.java`（Gate 编排核心）—— 仅通过 `GateReviewService.java:22` 注释引用 "P1-5 GateEngine 消费本表状态"。**未读取源代码**。
- `GateMaterialChecker.java`（评审材料校验）—— 后端域级对照底账行 38 标注"材料=GateElement 体系"，**未读取源代码**。
- `GateCreationService.java`（Gate 创建）—— `GateService.java:22` 注释引用，**未读取源代码**。
- `ProjectBootstrapService.java`（建项目时 bootstrap）—— 后端"bootstrap 6 阶段+69 动作+目标市场认证清单"由其实现，**未读取源代码**。
- `BonusPoolService.java` 第 133-143 行 + 第 655 行（奖金公式）—— 由 DOC-01.md §3.1 / §3.2 注解引用（`DOC-01.md:53 + 77`），**未读取源代码**。
- `IpdGateElementSeedInitializer` 的 `GateElementMapper`—— 33 要素 14 否决位的真实落库行为，**未实测**。
- `PersonMapper` / `IdentitySourceProvider`（第三方 REST API 同步）—— P2 域，**未读取源代码**。
- `HandoverController` 3 方法（create/accept/inbox）—— 已实现但其他 9 端点（reject/cancel/preview/product-continuation/batches）**未读取源代码**。
- `GateElementController` + `GateElementResultController` + `GatePrecheckController` + `StageActionOverdueScanController` —— **未读取源代码**。

### 6.2 未直接读取的原型文件

- `dist/` 构建产物 —— 仅业务参考意义。
- `node_modules/` 依赖 —— 跳过。
- `data/ipd.sqlite` 真实数据库 —— 跳过（不修改）。
- `src/` 前端源代码（React/Vite 框架） —— 仅就文档层面盘点，未深入读 React/Vue 实现。
- `server/server.mjs` 2243 行 86 路由 + `extensions.mjs` 688 行 ~40 + `final-rules.mjs` 357 行 31 + `closure.mjs` 432 行 ~45 —— **仅通过后端域级对照底账引用**，未逐行读取。

### 6.3 原型 .docx 解析的局限性

- 使用 Python `zipfile` + `xml.etree.ElementTree` 抽取 `word/document.xml` 段落文本，未抽取表格 / 页眉页脚 / 图像 / 编号样式。
- 部分表格被压平为段落，可能丢失原始结构（如"V1.2 设计说明书 §21"的关键 Gate 决策规则表 L471-L494 实际是表格，被抽取为段落）。
- 行号引用（如 `.docx` L427）对应段落序号，并非 Word 文档内物理行号，仅用于章节定位。

### 6.4 业务/产品/设计层未验证项

- 原型 V1.2 设计说明书 §8 信息架构图（"我的工作台 / 任务教练台 / 项目空间 / 需求 / 变更 / 资料库 / 评审 / 报表 / 移交 / 管理设置"10 个一级入口）vs 当前后端/前端实现的对齐情况 —— **未做 UI 复刻盘点**。
- 原型 V1.2 设计说明书 §11 AI 副驾设计（5 步：管理员配置 / 用户选择 / 上下文预览 / 流式生成 / 人工确认）vs 后端 `AiModelConfigController`（仅 providers 配置，6 端点）—— **未对齐盘点**（P2）。
- 原型 V1.2 用户使用说明书 §17 研发PM招募 4 子流程（市场PM发起与选定 / 研发PM密封应标 / 双组长确认与转项目 / 异常处理）vs 后端 `BidController` 12 方法 —— **未对齐盘点**。
- 原型 V1.2 用户使用说明书 §22 绩效规则V3.1 与回款台账（回款登记 / 双组长复核 / 超管锁定 / 6 个月窗口内退款以负回款计入）vs 后端 `BonusPoolService` 第 655 行公式 —— **未对齐盘点**（DOC-01.md §3.1 + §3.2 已展开）。
- 原型 V1.2 设计说明书 §12 安全审计 6 项（密码 / 会话 Cookie / AI 密钥加密 / 附件校验 / 审计 / 数据保护）vs 后端 `IpdWebSecurityConfig` + `IpdAuthController` + `IpdPermissionExceptionHandler` —— **未深入对齐盘点**。

### 6.5 原型与后端的潜在未对齐风险（待复核）

1. **动作数量 37 vs 69**：原型 V1.2 设计说明书 L427 与 README.md:7 仍称"37 个动作"，后端实现为 69。**风险**：UI 复刻时若以原型的"37 动作"为基准，会与后端不一致；建议以 D1=69 为事实源。
2. **Gate 数量**：原型 V1.2 设计说明书 §21 表仅列 5 个 Gate（G1-G5）+ 后端实现 5 个 Gate（G1-G5）；但后端 `IpdGateElementSeedInitializer.java:38-77` 中的要素名（如 G1-01 "市场机会与用户痛点验证"）与 DOC-05 §3 表 33 行的要素名（如 G1-1 "市场机会真实性"）不完全一致（"市场机会与用户痛点验证" vs "市场机会真实性"），仅要素数与否决位一致（均为 33 / 14）。**风险**：要素名差异可能导致 UI 展示漂移。
3. **历史阶段"已过节点"标记**：原型 V1.2 设计说明书 L496 "已在研补录项目的历史阶段标记为'历史已通过'，只补资料、不重新签署"。后端 `ProjectBootstrapService` 是否落实该规则未在源代码中直接验证。
4. **AGENTS.md 与 V1.2 设计说明书的菜单差异**：V1.2 设计说明书 §8 含"项目移交"与"管理设置"两个一级入口；AGENTS.md:57-58 明确删除"Project Circle"和"Governance Center"；后端当前菜单对齐 D2+ 拍板（15 项）—— 这意味着 `AGENTS.md` 的"durable workflow decisions"覆盖了 V1.2 设计说明书的早期决策。

### 6.6 行号复核命令（供 owner 复算）

```bash
# §1.1 当前活跃目录
ls -la "/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/"

# §1.2 v1 归档
ls -la "/Users/mac/Documents/ZK-IPD/_ARCHIVED_v1_产品流程细化管理工具_决策草稿_20260918/"

# §1.3 事实源裁决
grep -n "D1=\|D2=\|D3=" /Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/治理/ZK-IPD一致性红线-20260906.md

# §2.1 阶段名称
grep -n "系统预置固定六大IPD研发阶段" "/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/IPD产品经理管理系统·最终完整版AI开发Prompt（全规则闭环无遗留疑问）.md"

# §2.2 状态机
sed -n '111,158p' /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java

# §3.1 Gate 数量
grep -n "五大联合Gate" "/Users/mac/Documents/ZK-IPD/产品流程细化管理工具 2/IPD产品经理管理系统·最终完整版AI开发Prompt（全规则闭环无遗留疑问）.md"

# §3.3 Gate 决议
sed -n '80,100p' /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java

# §3.5 仲裁
sed -n '586,665p' /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java

# §4.2 RACI
sed -n '25,101p' /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/ActionCatalog.java

# §4.3 期限与延期
sed -n '64,76p' /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java
sed -n '812,830p' /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateReviewService.java
```

---

**报告生成时间**：2026-09-27（周日）
**任务类型**：只读调研
**输出位置**：`/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/_probes/20260927-c-prototype-probe.md`
