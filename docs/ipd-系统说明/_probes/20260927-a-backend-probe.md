# ruoyi-ai 后端「工作流」代码盘点 — 探针报告

| 字段 | 值 |
| --- | --- |
| 探针 ID | `20260927-a-backend-probe` |
| 任务时间 | 2026-09-27 (Sun) |
| 工作区根 | `/Users/mac/Documents/ruoyi-ai` |
| 当前 HEAD | `e4e0c379` R232 批次7 P2-04 后端 |
| 工作流盘点数 | A 类(2 模块) + B 类(3 子流) + 守卫层 1 个 |
| 输出策略 | 只读 / 不跑构建 / 不连库 |
| 输出字数 | <1500 行 |

> 重要边界：本仓库不包含前端 `apps/web-antd` 目录，三方对齐矩阵的「前端组件数」一栏直接登记为「仓库外」，详见 §1.2.3 与 §5。

---

## 1. A 类通用工作流

### 1.1 `ruoyi-workflow` 模块（Warm-Flow BPMN 引擎）

**实证四件套**
- 路径：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-workflow/`
- 模块规模：78 个 `.java` 文件
- 顶层包：`org.ruoyi.workflow`
- 模块 HEAD commit：`7b8cfe02a15750417101d6c37768a0e2531c0312`（git log -1）

#### 1.1.1 目录骨架（按职责分组）

| 子目录 | 文件数（采样） | 说明 |
| --- | --- | --- |
| `controller/` | 6 | FlwDefinition / FlwCategory / FlwInstance / FlwSpel / FlwTask / TestLeave |
| `service/IFlw*.java` | 9 | 接口层 |
| `service/impl/Flw*.java` | 9 | 实现层 |
| `mapper/` | 5 | FlwInstance/Task/Category/Spel + FlwInstanceBizExt |
| `domain/{vo,bo}/` | 多 | BPMN 视图/BO |
| `handler/` | 2 | WorkflowPermissionHandler / FlowProcessEventHandler |
| `listener/` | 1 | WorkflowGlobalListener |
| `config/` | 1 | WarmFlowConfig |
| `rule/` | 1 | SpelRuleComponent |
| `common/{enums,constant}/` | 9 | ButtonPermission / TaskStatus / TaskAssignee 等 |

#### 1.1.2 关键 Service 实现行数（wc -l 实证）

| 文件 | 行数 |
| --- | --- |
| `service/impl/WorkflowServiceImpl.java` | **188** |
| `service/impl/FlwDefinitionServiceImpl.java` | **269** |
| `service/impl/FlwInstanceServiceImpl.java` | **500** |
| `service/impl/FlwTaskServiceImpl.java` | **860** |

#### 1.1.3 关键 Controller 端点（仅端点签名，行号已用 `grep -nE "@RequestMapping\|@XxxMapping"` 现查）

`FlwInstanceController` — `@RequestMapping("/workflow/instance")` (L34)

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L46 | `@GetMapping` | `/pageByRunning` |
| L57 | `@GetMapping` | `/pageByFinish` |
| L67 | `@GetMapping` | `/getInfo/{businessId}` |
| L77 | `@DeleteMapping` | `/deleteByBusinessIds/{businessIds}` |
| L87 | `@DeleteMapping` | `/deleteByInstanceIds/{instanceIds}` |
| L97 | `@DeleteMapping` | `/deleteHisByInstanceIds/{instanceIds}` |
| L108 | `@PutMapping` | `/cancelProcessApply` |
| L120 | `@PutMapping` | `/active/{id}` |
| L131 | `@GetMapping` | `/pageByCurrent` |
| L141 | `@GetMapping` | `/flowHisTaskList/{businessId}` |
| L151 | `@GetMapping` | `/instanceVariable/{instanceId}` |
| L162 | `@PutMapping` | `/updateVariable` |
| L174 | `@PostMapping` | `/invalid` |

`FlwTaskController` — `@RequestMapping("/workflow/task")` (L35)

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L47 | `@PostMapping` | `/startWorkFlow` |
| L60 | `@PostMapping` | `/completeTask` |
| L71 | `@GetMapping` | `/pageByTaskWait` |
| L83 | `@GetMapping` | `/pageByTaskFinish` |
| L94 | `@GetMapping` | `/pageByAllTaskWait` |
| L105 | `@GetMapping` | `/pageByAllTaskFinish` |
| L116 | `@GetMapping` | `/pageByTaskCopy` |
| L126 | `@GetMapping` | `/getTask/{taskId}` |
| L136 | `@PostMapping` | `/getNextNodeList` |
| L148 | `@PostMapping` | `/terminationTask` |
| L161 | `@PostMapping` | `/taskOperation/{taskOperation}` |
| L174 | `@PutMapping` | `/updateAssignee/{userId}` |
| L186 | `@PostMapping` | `/backProcess` |
| L197 | `@GetMapping` | `/getBackTaskNode/{taskId}/{nowNodeCode}` |
| L207 | `@GetMapping` | `/currentTaskAllUser/{taskId}` |

#### 1.1.4 备注
- Warm-Flow BPMN 为 RuoYi-Vue-Plus 自带的请假示例组件 `TestLeave*`，是 demo 占位。生产用法由 `WorkflowServiceImpl` 重新封装。
- 测试用例 `TestLeaveServiceImpl` 也在内（admin/TestLeave 端点未在本探针内列出，作用为 demo）。

---

### 1.2 `ruoyi-aiflow` 模块（图驱动 AI 工作流引擎）

**实证四件套**
- 路径：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/`
- 模块规模：107 个 `.java` 文件
- 顶层包：`org.ruoyi.workflow`
- 模块 HEAD commit：`2425c7194ac4d1b4a884f8c3447169ebd9564d52`（git log -1）
- 备注：本模块独立于 Warm-Flow（BPMN），是 AI 智能体用的「节点-边」图引擎。

#### 1.2.1 目录骨架

| 子目录 | 说明 |
| --- | --- |
| `controller/` 与 `controller/admin/` | 4 个 Controller（WorkflowController / WorkflowRuntimeController / AdminWorkflowController / AdminWorkflowComponentController） |
| `service/` | 6 个 Service（Workflow/WorkflowEdge/WorkflowNode/WorkflowComponent/WorkflowRuntime/WorkflowRuntimeNode） |
| `workflow/node/*` | 9 个节点实现（extends AbstractWfNode），目录见 §1.2.3 |
| `workflow/` | WfNodeFactory、WfComponentNameEnum、WfState 等核心运行时 |
| `workflow/def` `workflow/edge` `workflow/data` | 节点定义 / 边 / 上下文数据结构 |
| `entity/` `mapper/` `dto/` | MyBatis-Plus 实体层 |
| `helper/` `util/` `base/` `config/` `enums/` `cosntant/` | 工具与常量 |

#### 1.2.2 节点子类清单（`extends AbstractWfNode`，grep -c 现查）

| 节点类 | 文件 | wc -l 行数 | 包路径 |
| --- | --- | --- | --- |
| `AbstractWfNode`（基类） | `workflow/node/AbstractWfNode.java` | **240** | `org.ruoyi.workflow.workflow.node` |
| `EndNode` | `workflow/node/EndNode.java` | **41** | `org.ruoyi.workflow.workflow.node` |
| `StartNode` | `workflow/node/start/StartNode.java` | **49** | `…node.start` |
| `LLMAnswerNode` | `workflow/node/answer/LLMAnswerNode.java` | **56** | `…node.answer` |
| `ImageNode` | `workflow/node/image/ImageNode.java` | **68** | `…node.image` |
| `MailSendNode` | `workflow/node/mailSend/MailSendNode.java` | **244** | `…node.mailSend` |
| `GoogleSearchNode` | `workflow/node/googleSearch/GoogleSearchNode.java` | **91** | `…node.googleSearch` |
| `HttpRequestNode` | `workflow/node/httpRequest/HttpRequestNode.java` | **446** | `…node.httpRequest` |
| `KnowledgeRetrievalNode` | `workflow/node/knowledgeRetrieval/KnowledgeRetrievalNode.java` | **284** | `…node.knowledgeRetrieval` |
| `SwitcherNode` | `workflow/node/switcher/SwitcherNode.java` | **437** | `…node.switcher` |

> 节点总规模（含 Config / Enum / Helper 文件）：24 个 Java 文件，总 2386 行（`workflow/node/*/*.java` wc -l 实证）。

#### 1.2.3 三方对齐矩阵

| 维度 | 数量/值 | 实证来源 |
| --- | --- | --- |
| `WfComponentNameEnum` 枚举值 | **11** | `WfComponentNameEnum.java` 行号 9-29 |
| `WfNodeFactory.create` switch case | **9** 个有效 case | `WfNodeFactory.java` 行号 21-29 |
| `extends AbstractWfNode` 节点子类 | **9**（含 EndNode） | §1.2.2 列表 |
| 前端 `apps/web-antd/src/views/aiflow/components/` | **仓库外不存在** | `find`/`ls` 双双空返回 |

**枚举值清单（`WfComponentNameEnum.java` L9-29，11 项）**：
1. `START("Start")` L9
2. `END("End")` L11
3. `LLM_ANSWER("Answer")` L13
4. `DALLE3("Dalle3")` L15
5. `TONGYI_WANX("Tongyiwanx")` L17
6. `FAQ_EXTRACTOR("FaqExtractor")` L19
7. `KNOWLEDGE_RETRIEVER("KnowledgeRetrieval")` L21
8. `SWITCHER("Switcher")` L23
9. `GOOGLE_SEARCH("Google")` L25
10. `MAIL_SEND("MailSend")` L27
11. `HTTP_REQUEST("HttpRequest")` L29

**switch case 缺失项（命名层注册但工厂无实现）**：
- `DALLE3` — 枚举 L15 注册，工厂无 case（落 default → `wfNode = null`，运行时炸 NPE 风险）
- `FAQ_EXTRACTOR` — 枚举 L19 注册，工厂无 case（同上）

**switch case 命中项（`WfNodeFactory.java` L21-29）**：
- START → StartNode
- LLM_ANSWER → LLMAnswerNode
- TONGYI_WANX → ImageNode
- KNOWLEDGE_RETRIEVER → KnowledgeRetrievalNode
- END → EndNode
- MAIL_SEND → MailSendNode
- HTTP_REQUEST → HttpRequestNode
- SWITCHER → SwitcherNode
- GOOGLE_SEARCH → GoogleSearchNode

**矩阵结论**：
1. 节点子类 9 个 == 工厂 case 9 个（完全对齐）
2. 枚举值 11 个 > 工厂 case 9 个（缺口 2：DALLE3 / FAQ_EXTRACTOR）
3. 工厂 default 分支（L30-31）只是空块 `{}`，未抛错——意味着 enum 名写错的节点会被静默创建为 `null`（死代码风险，见 §3）

#### 1.2.4 库注册准入闸 `WorkflowComponentService.getAllEnable()`

| 维度 | 值 |
| --- | --- |
| 路径 | `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/service/WorkflowComponentService.java` |
| 文件行数 | **127** |
| `getAllEnable` 方法 | **L102-108**（行号已 grep -n 现查） |
| 方法签名 | `public List<WorkflowComponent> getAllEnable()` |
| 过滤逻辑（实证） | L103-105：`lambdaQueryChain(...).eq(IsEnable, true).eq(IsDeleted, false).orderByAsc(DisplayOrder, Id).list()` |
| 注释 L101 | `// @Cacheable(cacheNames = WORKFLOW_COMPONENTS)` —— 注解被注释掉，未启用缓存 |
| 准入闸调用方 | L112 `getStartComponent()`、L120 `getComponent(Long id)`（同一类内） |
| commit hash | `0687b49542d4cb2e79e5c8dfef58886872cedd19` |

> ⚠️ 准入闸为「查询时硬过滤」（is_enable=true && is_deleted=false），但代码上方 `@Cacheable` 注解被注释掉，意味着管理员每次 toggle `is_enable` 后旧查询会立即命中新值（无缓存过期问题），但**有相应成本**：高频列表查询全部走 DB。

---

## 2. B 类 IPD 业务工作流

> 模块根：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/`
> 模块 HEAD commit：`e4e0c37906197af10657c99383609e3589d13cd7`（git log -1）

### 2.1 Gate 双签盲签（AC-GATE-03/04/05/10/21 等）

#### 2.1.1 Service / 接口 / Mapper / 领域模型

| 角色 | 路径 | 行数 | commit hash |
| --- | --- | --- | --- |
| Service 实现 | `service/GateReviewService.java` | **1028** | `2528d8707d91199e97b31be727b22fa55e8386c6` |
| Service 接口 | `service/IGateReviewService.java` | **108** | `f987e0288b15b777c116f697b39744a56140f5c7` |
| Mapper | `mapper/GateReviewMapper.java` | (未细查) | — |
| 领域模型 | `domain/GateReview.java` | **97** | — |
| 领域模型 | `domain/GateArbitration.java` | **74** | — |
| 领域模型 | `domain/GateReviewObserver.java` | **63** | — |
| Mapper | `mapper/GateArbitrationMapper.java` | — | — |
| Mapper | `mapper/GateReviewObserverMapper.java` | — | — |

#### 2.1.2 关键方法行号（`grep -nE "^\s+public.*\b(name)\b\s*\("` 现查）

`GateReviewService.java`（1028 行）：

| 方法签名 | 行号 | javadoc 引用规范 |
| --- | --- | --- |
| `public void setStateMachineGuard(StateMachineGuard)` | L119 | R24 接线 |
| `public List<Gate> listByProject(Long projectId)` | L158 | P0-10.23 补齐 |
| `public void setProjectMapper(ProjectMapper)` | L184 | 测试口 |
| `public void setClock(java.time.Clock)` | L190 | 测试口 |
| `public GateReview sign(Long gateId, String decision, String opinion, IpdActor actor)` | **L207** | AC-GATE-03/04 双签矩阵 |
| `public Map<String, Object> view(Long gateId, IpdActor actor)` | **L236** | AC-GATE-03/04 双签盲签视图 |
| `private void settle(Gate, String status, IpdActor, List<GateReview>)` | L296 | AC-GATE-05 落终态+通知 |
| `public Gate reopen(Long gateId, IpdActor actor)` | **L442** | AC-GATE-06/07/07b |
| `public int scanTimeout(IpdActor operator)` | **L497** | AC-GATE-08，BR-GATE-04 D17 |
| `public int scanRemind(IpdActor operator)` | **L550** | AC-GATE-09 期限前 1 天催办 |
| `public GateArbitration arbitrate(Long gateId, String decision, String opinion, IpdActor actor)` | **L587** | AC-GATE-10 中段 |
| `public GateArbitration finalRuling(Long gateId, String decision, String opinion, IpdActor actor)` | **L628** | AC-GATE-10 尾段 |
| `public int inviteObservers(Long gateId, List<Long> observerIds, String role, IpdActor actor)` | **L683** | MEDIUM-1.3 列席人员 |
| `public GateReviewObserver recordOpinion(Long gateId, Long observerId, String opinion, IpdActor actor)` | **L770** | MEDIUM-1.3 |
| `public List<GateReviewObserver> listObservers(Long gateId, IpdActor actor)` | **L799** | MEDIUM-1.3 |
| `public Gate extendDeadline(Long gateId, int days, IpdActor actor)` | **L812** | AC-GATE-21 期限延长 |

#### 2.1.3 Controller 端点（GateReviewController.java 180 行）

`@RequestMapping("/api/v1/gates/{gateId}")` (L37)

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L72 | `@PostMapping` | `/sign` |
| L81 | `@GetMapping` | `/review` |
| L88 | `@PostMapping` | `/reopen` |
| L100 | `@PostMapping` | `/extend-deadline` |
| L113 | `@PostMapping` | `/arbitrate` |
| L122 | `@PostMapping` | `/final-ruling` |
| L131 | `@PostMapping` | `/observers/invite` |
| L143 | `@PostMapping` | `/observers/{observerId}/opinion` |
| L153 | `@GetMapping` | `/observers` |

commit hash：`5e3723c4f73bbe0f1cdeed0747ad58241fd49b72`（MEDIUM-1.3 列席人员端点同 commit 落库）

#### 2.1.4 关联扫描 Controller（不走 `{gateId}` 路由前缀）

`GateSignScanController.java` (43 行) — commit `d4365d6aa339a4201d0288aff7541a2f813d9941`

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L32 | `@PostMapping` | `/api/v1/gates/sign/scan-timeout` |
| L38 | `@PostMapping` | `/api/v1/gates/sign/scan-remind` |

`GatePrecheckController.java` (42 行) — commit `34f97b9fc309796f465b05a365a6648dbada82e3`

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L29 | `@RequestMapping` | `/api/v1/gates/{gateId}` |
| L37 | `@PostMapping` | `/precheck` |

`GateElementController.java` (140 行+) — commit `510b269cdc3bd28a4b14c525432566c6a865dd3a`（Gate 要素元数据 CRUD）

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L27 | `@RequestMapping` | `/api/v1/gate-elements` |
| L35 | `@GetMapping` | （list） |
| L43 | `@PostMapping` | （create） |
| L52 | `@PostMapping` | `/{id}/update` |
| L61 | `@PostMapping` | `/{id}/disable` |
| L69 | `@PostMapping` | `/{id}/publish` |
| L77 | `@PostMapping` | `/{id}/archive` |
| L85 | `@PostMapping` | `/{id}/copy` |
| L93 | `@PostMapping` | `/{id}/revert` |
| L104 | `@GetMapping` | `/manage` |
| L116 | `@PostMapping` | `/{id}/duplicate` |
| L128 | `@PostMapping` | `/{id}/restore` |

`GateElementResultController.java` — commit `26d442e18cc44fd2136fca947125545df377c58f`

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L32 | `@RequestMapping` | `/api/v1/gates/{gateId}` |
| L72 | `@GetMapping` | `/elements` |
| L79 | `@PostMapping` | `/element-results` |
| L90 | `@GetMapping` | `/legacy` |
| L97 | `@PostMapping` | `/element-results/{resultId}/close` |
| L127 | `@PostMapping` | `/submit` |

`GateMaterialController.java` (43 行) — commit `60bde6b4a130c651ff2d3aa9917ab1b301162028`

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L30 | `@RequestMapping` | `/api/v1/gates/{gateId}/materials` |
| L38 | `@GetMapping` | （list） |

---

### 2.2 阶段流转（Stage Action 实例 + 状态机）

#### 2.2.1 Service / 接口 / Mapper / 调度器

| 角色 | 路径 | 行数 | commit hash |
| --- | --- | --- | --- |
| Service 实现 | `service/StageActionService.java` | **586** | `0ce016e2b4a3fe7428ecfd53ed165e089fd967ea` |
| Service 接口 | `service/IStageActionService.java` | **124** | `2528d8707d91199e97b31be727b22fa55e8386c6` |
| 调度器 | `service/StageActionOverdueScheduler.java` | **38** | `0ce016e2b4a3fe7428ecfd53ed165e089fd967ea` |
| Mapper | `mapper/StageActionMapper.java` | — | — |
| 扫描入口 Controller | `controller/StageActionOverdueScanController.java` | **32** | `0ce016e2b4a3fe7428ecfd53ed165e089fd967ea` |
| 主体 Controller | `controller/StageActionController.java` | **143** | `27d6655fab460c2f629f825d6c244a81ada23023` |

> ⚠️ 用户原指令写「StageActionService.advance」，但实证 `grep -n "advance" StageActionService.java` **零命中**。本服务的状态机迁移入口实际是 **`transit(id, target, reason, operator)`（L112）**，所有「advance」语义被合并到 `transit`（详见 P1-4.3 javadoc L42-47：仅 `/transit` 入口，禁止 PATCH status）。

#### 2.2.2 关键方法行号

`StageActionService.java`（586 行）：

| 方法签名 | 行号 | 备注 |
| --- | --- | --- |
| `public void setNotificationService(NotificationService)` | L73 | R219 卡④ setter 注入 |
| `public void setProjectMemberMapper(ProjectMemberMapper)` | L78 | 同上 |
| `public void setProductGroupMapper(ProductGroupMapper)` | L83 | 同上 |
| `public StageAction getById(Long id)` | L87 | — |
| `public List<StageAction> listByProject(Long projectId)` | L95 | — |
| `@Transactional public StageAction transit(Long id, String target, String reason, String operator)` | **L112** | **状态迁移唯一入口（P1-4.3）** |
| `@Transactional public StageAction recordFields(Long id, Date actualDoneAt, BigDecimal farValue, BigDecimal frrValue, String certNo, Date certPassedAt, String algoType, String operator)` | **L175** | P1-4.1/P1-8.2 字段登记 |
| `private ActionDef canonicalizeAction(StageAction a)` | L252 | Z 系编码归一 |
| `private static void assertRate01(BigDecimal v, String label)` | L264 | FAR/FRR 取值 |
| `private static String fieldsSnapshot(StageAction a)` | L270 | 审计 JSON |
| `private void validateCompletion(StageAction a, ActionDef def, boolean deep)` | L281 | 深管附件 / 轻管日期 / 数值 |
| `@Transactional public int ensureBioComplianceMount(Long projectId, IpdActor actor)` | **L312** | AC-PROD-13 / P1-8.1 |
| `public boolean hasBioFeatureActions(Long projectId)` | L358 | — |
| `private Long resolveConceptStageId(Long projectId)` | L372 | — |
| `@Transactional public Deliverable addDeliverable(Long actionId, String fileName, Long ossId, String operator)` | **L386** | BR-IPD-03 附件挂载 |
| `@Transactional public int instantiate(Long projectId, Long stageId, String stage, IpdActor actor)` | **L414** | PERF-03 批量实例化 |
| `private static String statusSnapshot(StageAction a)` | L448 | 审计 JSON |
| `private static Long actorIdOf(String operator)` | L462 | — |
| `private Project assertProjectWritable(Long projectId)` | L476 | P1-2.2 状态门禁 |
| `private void assertProjectWritableInGroup(Long projectId, IpdActor actor)` | L501 | R212-②③ 横向越权 |
| `public int notifyOverdueActions()` | **L520** | R219 卡④ 逾期通知 |

`StageActionOverdueScheduler.java`（38 行）：

| 方法签名 | 行号 | 备注 |
| --- | --- | --- |
| `@Scheduled(cron = "0 50 9 * * ?") public void dailyOverdueScan()` | **L34** | 每日 09:50 跑逾期通知 |

#### 2.2.3 Controller 端点

`StageActionController.java`（143 行）— commit `27d6655fab460c2f629f825d6c244a81ada23023`

`@RequestMapping("/api/v1/stage-actions")` (L34)

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L43 | `@GetMapping` | （list） |
| L57 | `@PostMapping` | `/{id}/transit` |
| L70 | `@PostMapping` | `/{id}/fields` |
| L83 | `@PostMapping` | `/{id}/deliverables` |
| L100 | `@PostMapping` | `/instantiate` |
| L113 | `@PostMapping` | `/ensure-bio-compliance` |
| L128 | `@PostMapping` | `/{id}/ai-execute` |

`StageActionOverdueScanController.java`（32 行）— commit `0ce016e2b4a3fe7428ecfd53ed165e089fd967ea`

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L27 | `@PostMapping` | `/api/v1/stage-actions/overdue-scan`（手动兜底，超管验收复测用） |

---

### 2.3 AI 评审 / 发布后评审

#### 2.3.1 Service

| 角色 | 路径 | 行数 | commit hash |
| --- | --- | --- | --- |
| `AiExecReviewHook`（R221 EVENT hook） | `service/AiExecReviewHook.java` | **186** | `25432f5d229541c81da0892b625914884cd306bd` |
| `PostLaunchReviewService`（G5 上市 90 天复盘） | `service/PostLaunchReviewService.java` | **220** | `f987e0288b15b777c116f697b39744a56140f5c7` |

#### 2.3.2 关键方法行号

`AiExecReviewHook.java`（186 行）：

| 方法签名 | 行号 | 备注 |
| --- | --- | --- |
| `void setTrigger(AiExecutionTrigger trigger)` | L70 | 测试注入 |
| `void setEngine(AiExecutionEngine engine)` | L74 | 测试注入 |
| `public void onDocumentReviewed(Long docId, String contentMd)` | **L85** | R221 人审通过闭环（afterCommit 防 UnexpectedRollback） |
| `private void closeLinkedDocuments(Long docId, String contentMd)` | L102 | 闭环主体 |
| `public void onBootstrapped(Long projectId)` | **L135** | bootstrap 尾唤醒 |
| `private void closeOne(AiAgentTask task, String contentMd)` | L149 | OSS 上传→addDeliverable→transit(DONE) |
| `private Long stageIdOf(Long stageActionId)` | L164 | — |
| `private void wakeSameStageSuccessors(Long projectId, Set<Long> closedStages)` | L170 | 同项目同阶段 NOT_STARTED 后继唤醒 |

`PostLaunchReviewService.java`（220 行）：

| 方法签名 | 行号 | 备注 |
| --- | --- | --- |
| `public record ReviewData(BigDecimal actualRevenue, String customerFeedback, String kpiAchievement, String lessons)` | **L80** | record DTO |
| `@Transactional public PostLaunchReview scheduleReview(Long projectId, Date launchDate, IpdActor operator)` | **L92** | AC-GATE-13 G5 通过 +90d 待办 |
| `@Transactional public PostLaunchReview completeReview(Long reviewId, ReviewData data, IpdActor operator)` | **L144** | AC-GATE-28 复盘完成 |
| `public PostLaunchReview findPendingByProject(Long projectId, IpdActor operator)` | **L188** | R-NEW-ARCH-1 入口 |
| `private Long pickMarketPm(Long projectId)` | L209 | 取项目主 MARKET_PM |

#### 2.3.3 Controller 端点

`PostLaunchReviewController.java`（115 行）— commit `d95b085c62d1a93a8cebc3c7516ec63906018302`

`@RequestMapping("/api/v1/post-launch-reviews")` (L43)

| 行号 | 方法注解 | 端点 |
| --- | --- | --- |
| L83 | `@GetMapping` | `/pending` |
| L93 | `@PostMapping` | （schedule） |
| L105 | `@PostMapping` | `/{id}/complete` |

---

### 2.4 状态机守卫层（StateMachineGuard）

#### 2.4.1 接口

| 角色 | 路径 | 行数 | commit hash |
| --- | --- | --- | --- |
| 接口 | `service/StateMachineGuard.java` | **96** | `2baf69352a55dd8e9d33e57ec2afd78755daf064` |

接口方法清单：

| 方法签名 | 行号 |
| --- | --- |
| `void registerRule(StateTransitionRule rule)` | L34 |
| `boolean removeRule(String key)` | L42 |
| `boolean isAllowed(String entityType, String fromState, String toState, String trigger)` | L53 |
| `void preCheck(String entityType, String fromState, String toState, String trigger)` | L71 |
| `void postCommit(String entityType, String fromState, String toState, String trigger, Long operatorId, Long entityId, Date occurredAt)` | L94 |

#### 2.4.2 默认实现位置

- javadoc 提示：`org.ruoyi.ipd.service.impl.DefaultStateMachineGuard`
- 路径未在本探针 grep 范围展开（不读实现细节）。

#### 2.4.3 接线关系
- `GateReviewService` 内嵌 `private StateMachineGuard stateMachineGuard;` + `setStateMachineGuard(...)` L119（R24 接线，按 KpiRecordService 样板）。
- 守卫实体类型常量：`GATE_REVIEW_ENTITY_TYPE = "gate_review"`（GateReviewService L116）。

---

## 3. 死代码 / 空实现候选

> 本节列出**疑似的壳/空实现**，未做调用链追踪，仅基于「行号+签名」静态推断。

| 位置 | 现象 | 行号 | 风险 |
| --- | --- | --- | --- |
| `WfNodeFactory.create` default 分支 | `default -> {}` 空块，**未抛异常**，仅返回 `null` | WfNodeFactory.java **L30-31** | 枚举名写错的节点被静默 `null`，调用方 `wfNode.checkout()` NPE |
| `WorkflowComponentService.getAllEnable` | `@Cacheable` 注解被注释（`// @Cacheable` L101） | WorkflowComponentService.java **L101** | 高频查询全走 DB；toggle 后无缓存失效问题但成本高 |
| 枚举 `DALLE3` (L15) 与 `FAQ_EXTRACTOR` (L19) | 工厂 switch 无对应 case | WfComponentNameEnum.java L15/L19 | 见 §1.2.3 |
| 兄弟会话新增的 `PublicPortalController` 端点 `supplement/withdraw` | 当前探针未读实现细节；仅确认 path（影响「游客需求」业务流，与 Gate 双签 / 阶段流转 / AI 评审**无重叠**） | PublicPortalController.java +31 行 | 见 §4 |

---

## 4. 兄弟会话在途编辑边界

### 4.1 已修改（M）文件

| 文件 | 改动性质 | 是否影响工作流盘点 |
| --- | --- | --- |
| `docs/ipd-系统说明/log.md` | 文档 | 否 |
| `docs/ipd-系统说明/开发计划-看板镜像.md` | 文档 | 否 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/PublicPortalController.java` | +31 行：新增 `supplement`/`withdraw` 两个 `@PostMapping`（路径 `/api/v1/public/demands/{code}/supplement` + `/withdraw`） | **否**：与 Gate 双签、阶段流转、AI 评审三组业务工作流**无路径/Service 重叠**（路径前缀 `/api/v1/public/...`，与 `/api/v1/gates/*`、`/api/v1/stage-actions/*`、`/api/v1/post-launch-reviews/*`、`/api/v1/ai-agent-tasks/*` 全部正交）。 |

### 4.2 未跟踪（??）文件

| 文件 | 类别 | 是否影响工作流盘点 |
| --- | --- | --- |
| `docs/ipd-系统说明/调研/R232-CopilotKit前端融合可行性调研-20260927.md` | 调研文档 | 否（文档） |
| `docs/ipd-系统说明/验收/R234-权限码前后端对账与403生效核查-20260927.md` | 验收文档 | 否（文档） |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/controller/PublicPortalSupplementWithdrawTest.java` | 测试 | 否（针对 PublicPortalController，与工作流无关） |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/AiCardBlindSignContractTest.java` | 测试 | **可能影响**：文件名含「BlindSign」，**疑似**与 Gate 双签盲签契约测试有关。**未读**该文件内容；该测试**未引用** §2.1 列出的任何 Service/接口签名，**仅从文件命名推断**为 AI 评审+双签契约测试。来源未知。 |
| `.qoder/` | 工具目录 | 否 |

---

## 5. 限制与未验证项

1. **前端 aiflow 组件目录缺失**：仓库根不含 `apps/`、`ruoyi-ui/`、`web-antd/` 等前端目录（`ls` 实证）。三方对齐矩阵的「前端组件数」一栏**来源未知**，本探针只能登记为「仓库外」。如需对照前端组件清单，应额外同步 `ZK-IPD` 或 `ruoyi-ipd-web` 工作区。
2. **AiCardBlindSignContractTest.java** 未读内容，无法确认其覆盖范围与 GateReviewService 的关系。**来源未知**。
3. **`StageActionService.advance` 不存在**：用户原指令假设存在 `advance`，实证 0 命中。实际状态迁移入口为 `transit`（L112）。本探针已据实证修正。
4. **未跑构建 / 未连库**：所有行号来自 `wc -l` / `grep -n` / `Read` 现查，未触发 `mvn compile` 或数据库交互（3306/13306 双实例坑规避）。
5. **commit hash 仅取每个文件的最后一次提交**：未做「文件创建 commit」溯源。
6. **Gateway 类 Controller（ApiV1Response）的路径前缀核验**未深入（仅看了 `@RequestMapping` 注解，未 trace `@RestControllerAdvice` / `SaTokenConfig`）。
7. **DefaultStateMachineGuard 实现**（接口注释提示位置 `org.ruoyi.ipd.service.impl`）未 grep 行号 — 仅作为引用登记。
8. **PostLaunchReviewService 实际行数 = 220**（与用户指令 221 差 1）—— `wc -l` 现查。
9. **`AiExecReviewHook` 行数 = 186**（与用户指令一致）。注意 javadoc L42-43 提到 R228 修复波的循环依赖破环（@Lazy + @Autowired 字段注入替代构造器环），是 START commit 之后的演进。
10. **未对 `Mapper.java` 行数做穷举**：仅 grep `-name` 确认存在，未读实现方法。

---

## 探针结束
