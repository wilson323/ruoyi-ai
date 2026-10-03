# 官方能力独立验收矩阵

生成来源：2026-10-02 独立验证智能体直接读取官方源码与 Maven 2.0.3 sources.jar。此文件是证据附件，不是计划或新状态源。当前状态 PENDING_VALIDATION；以下“默认装配”仅描述 SDK，不能证明项目已生效。

版本事实：项目根 POM `agentscope.version=2.0.3`；参照仓根 POM `revision=2.0.4-SNAPSHOT`。版本差异必须按 API 逐项对照。2.0.3 sources.jar 已证实计划、技能管理、技能审核、技能策展、teams、channel、异步工具 API 均存在；2.0.3 build 无条件注册 WebFetchTool/WebSearchTool，参照版本新增 disableWebTools 开关，不能因缺少开关推断没有工具。

源码锚点统一为 `/Users/mac/Documents/agentscope-java/agentscope-harness/src/main/java/io/agentscope/harness/agent/HarnessAgent.java`；基础 ReAct 开关在 `agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java`。下列验收必须同时在项目最终装配和已加载运行包验证。

范围区分：本矩阵核 HarnessAgent 官方能力与 ReAct 装配 API。官方仓库中的可选 Model provider、协议扩展、远端服务与部署形态不是彼此同时使用的开关；已安装 artifact、API 存在、provider 已接线和真实运行验收是四种不同证据。Vault/Sandbox/Temporal/远端 Teams 等服务没有当前连通证据，不自动引入或宣称齐备；缺具体 provider 时标待接线，不能静默禁用能力或提供空实现。SDK 版本差异按源码适配，不擅自升级依赖。

| 能力 | 官方装配条件 / 源码锚点 | 必须验证的业务扩展与反例 |
|---|---|---|
| 文件工具 | 默认开启；disableFilesystemTools=false；build 注册原生工具 | 受控 filesystem 路径、跨项目/用户越权零效果；禁止启动目录兜底 |
| Shell | disableShellTool=false，但内置 ShellExecuteTool 仅 filesystem instanceof AbstractSandboxFilesystem 时注册（2.0.3 build 2668+） | LocalFilesystemSpec 本身不提供 shell；必须真实 Sandbox provider；ToolBase/checkPermissions 或 Middleware 执行拦截，未审批命令不执行，不能靠提示词 |
| Web | 2.0.3 build 2671+ 无条件注册 WebFetchTool/WebSearchTool；参照 build 的 !disableWebTools 分支 | permission 为业务出站审批；工具可发现与无权执行分别验证，不能用 ToolsConfig 全删证明能力启用 |
| 工作区上下文 | 默认 WorkspaceContextMiddleware，build 2539+ | 只读取可信隔离业务工作区；工程 AGENTS.md 不作业务事实 |
| @路径展开 | 默认 AtPathExpansionMiddleware | 跨工作区及符号链接逃逸拒绝；合法文件实际展开 |
| Memory 工具/Hook | 默认 memory 配置与 hooks | 用户隔离、敏感值过滤、工程 MEMORY.md 不作业务事实；写后同域读、异域拒绝 |
| Transcript | 默认 ObjectStore/FilesystemTranscriptStore；build 2559+ | 原始内部推理不长期存储；会话身份隔离；真实追加和恢复 |
| Session State | stateStore 注入/default JSON store | stateStore 实写实读；不得替代业务 run epoch/审批/版本；seal 清理不能冒充持久恢复 |
| 压缩 | compactionConfig 默认非空 | 真实触发压缩且 provenance/审批不丢失；禁止替代模型降级 |
| 工具结果卸载 | ToolResultEvictionConfig.defaults() | 大结果实际卸载与可读回；路径隔离 |
| 动态技能/默认技能 | 默认开启；disableDynamicSkills/defaultWorkspaceSkills=false | 只披露授权技能；owner 未拍板草案不晋升；不可通过禁用动态发现保留业务权威 |
| 技能管理 | skillManageToolEnabled=false；须 enableSkillManageTool | draft 生成与更新实际执行；autoPromote 不得绕过 owner |
| 技能晋升闸门 | enableSkillPromotionGate(gate, visibilityFilter) | 请求进入真实 owner 审核；未批准 Defer，拒绝 Reject；审批后 Approved，禁止恒拒绝空实现 |
| 技能策展 | skillCuratorEnabled=false；须 enableSkillCurator；依赖 manage | 后台实际运行、候选来源可追溯，仍通过同一 promotion gate |
| Plan Mode | planModeEnabled=false；须 enablePlanMode | enter/write/exit 真执行；官方计划文件为执行态，业务批准仍来自本项目审批；计划不能自批业务 |
| 子智能体 | 默认声明及动态加载 | 子任务继承身份、权限、owner、能力治理；子智能体不能直接调用未批准业务工具 |
| 子任务库 | taskRepository 或官方 workspace 默认 | 委派与完成状态真实写读；业务 action 状态不可由 SDK 自动完成 |
| Meta Tool | ReAct enableMetaTool=false；须 enableMetaTool(true) | 实际工具发现/选择/调用，选择后仍进同一权限闸门 |
| Task List | ReAct taskListEnabled=false；须 enableTaskList | 真实 add/update/read；不可把 task-list 完成映射为业务 Gate 放行 |
| Pending Tool Recovery | enablePendingToolRecovery=false；须显式启用 | 恢复已批准/未批准/未知效果三种反例；副作用不能重复 |
| MessageBus/Inbox | 默认 workspace bus，或 distributedStore.messageBus；build 2520+ | 真实发送/接收、身份隔离；只存在类或目录不算启用 |
| 异步工具 | 必须 messageBus + asyncToolTimeout；build 2679+ | 超时转异步、wait 回读、异常与取消；不得 ToolsConfig 全拒绝 wait_async_results |
| Teams | 必须 teamsModeClient 与 teamsModeContext 非空；build 2632+ | Local/Remote TeamClient 实供，成员角色权限、任务分配/消息真实流转；未提供 provider 为未接线 |
| Channel/Gateway | channel(Channel) 延迟初始化 gateway；不是 builder 默认业务路由 | ChatUiChannel 可实例化不证明 HTTP/SSE 已接；真实请求用 Person 权限身份、无第二运行轨 |
| MCP | 工具配置/provider 注册及 mcpServerRegistrationListener | listTools 实发现，稳定 client name，协议名调用；后台连接失败明确报错，不能回落手写 JSON-RPC |
| Artifact Delivery | artifactDeliveryTarget != null 且文件工具开启；build 2539/2733 | target 必须适配既有 ai_documents 审核链，绑定 action 后落库；生成不能自动审核/动作批准 |
| 本地/远端/Sandbox FS | filesystem(Local/Remote/Sandbox spec)、filesystemRoute | 真实 provider 和环境探测；任选运行模式必须保留可用能力，不用空 provider 或假 Sandbox |
| Distributed Store | distributedStore(store) 注入 | 实供 state/task/bus 后端、并发与身份隔离；没有 provider 不算部署可用 |
| Trace/Hook | trace 默认 true；hook/middleware 官方扩展点 | trace 真产生且不含凭据/内部推理；审计事件与业务请求一致 |
| ExecutionConfig | model/tool defaults 注入（否则无重试/超时套件） | 有界超时与失败输出；模型 fallback API 存在不代表必须降级，按本次指令禁止降级 |

## 当前确定性证据

- `scripts/check-agentscope-capabilities-open.py --self-test`：10 组正反控通过，包含共享 builder 配置 helper 的禁用反例。
- 初始主树扫描：4 个 main 消费者，36 处禁用，退出 1。消费者：Project kernel、Chat kernel、CodingServiceImpl、PocKernelSupport。扫描只约束“不得关闭”，不会把未配置 Teams/channel/provider 判成已接线。
- 新 `OfficialCapabilitiesBusinessGateRegressionTest` 验证已登记 EXECUTE 能力仍服从 DENY 与未批准 ASK，旁路 callAsync 必須零 delegate 触达。2026-10-02 13:36:28 -07:00 串行单模块 Maven：该新测试与 KernelGovernedToolTest 合计 9 tests，Failures=0，Errors=0，Skipped=0，BUILD SUCCESS。原始日志 `/tmp/ipd-capabilities-chat-validation.log`。此为工具层反例单测，不是 HTTP/DB 业务验收。
- 当前运行探针：127.0.0.1:16039 PID 91326；lsof 读取的 JAR 为 `.codex/ipd-dev/backups/ruoyi-admin.codex-takeover-20261002-10130f7efb0e.jar`。这是探针时刻事实，不代表已加载本轮新增代码。

owner 技能反例、真实多角色 HTTP/DB、Channel/Teams provider 和运行包对齐尚未验收；不得用该文档或扫描绿替代。

## 本轮初稿独立复核（修复后必须回读，不能当最终状态）

1. `OwnedTool.callAsync` 使用 agent 默认 state 的权限，而官方 `RuntimeContext.resolveAgentState` 优先 call-scoped state；跨会话授权可能被借用，新增确定性反例进入 `OfficialCapabilitiesAcceptanceTest`。
2. `callAsync` 二次调用 `PermissionEngine.checkPermission(this)` 会再次进入已有 KernelGovernedTool 的 govern，追加第二份 ledger/ALLOW pending ticket；必须按一次调用保证裁决与执行消费关系。
3. 官方 Harness wrappedCall/stream 先 acquireForCall/start Sandbox，再进入 inner agent middleware；仅 onAgent owner 断言不能保证容器启动前持有 owner。
4. 临时 stateStore 仅允许父精确 user/session，官方 SessionSandboxStateStore 以 user=null、打包 sandbox session 存储；子任务也派生 session。全量能力需要在原 run owner 下识别合法 SDK namespace，保持 fail-closed，不能删除隔离检查。
5. 官方 HarnessSkillMiddleware.applyVisibility 对 filter exception/null 回退为 pass-through；业务身份不匹配抛异常会反而放行。身份不符必须返空并由外部 guard 明确报错；子 factory 的 promotion/visibility 扩展点复制还需现核，name-only filter 不足以防同名草案替换。
6. 默认 Transcript 的 SessionTranscriptWriter.deriveEntries 会将 ThinkingBlock 原文加入 textParts；memory 提示词不能阻止 transcript 记录。须经官方 transcriptStore 等扩展点去内部推理并保留正文/工具证据，不能关闭该能力。

普通 Skill verify 最终退出 1：env-probe/skill-lint 通过；C4 两个历史 docs 验收探针缺 userId，生产 RuntimeContext 通过，其他契约项通过。`--self-red` 退出 0。不把历史探针红项归因为生产缺陷。

## 定向真实能力验收结果

2026-10-02 13:48:54 -07:00，单模块独占 Maven（无 clean、无 -am）：`OfficialCapabilitiesAcceptanceTest,ProjectAgentSafeTranscriptStoreTest`，退出 0，BUILD SUCCESS，4 tests / 0 failures / 0 errors / 0 skipped；Surefire XML 单独回读一致。原始日志 `/tmp/ipd-independent-official-acceptance-20261002.log`。

- Docker 原生 FilesystemTool+ShellExecuteTool 经项目官方权限 wrapper 执行：授权 write_file 写入、read_file 读回、execute cat/marker 真实成功。
- 宿主 sibling canary 未挂载、read_file 不可读取原文，Shell test 验证不存在；宿主文件内容不变。
- 本测试自建容器 `9e0a8675b054fa99cec75eb5d4552adf6592d849b879deded0243360a25100db`，SDK close 后 docker inspect 查无对象；后续 `docker ps --filter name=agentscope-sandbox` 为空。没有删除他人资源。
- agent 默认 BYPASS 与本次 call-scoped DONT_ASK 同时存在时，wrapper 正确拒绝本次调用，delegate 零触达。
- SafeTranscript 同时挂官方 Middleware 与 TranscriptStore：在 SDK writer 写本地 context/log 前去除 ThinkingBlock，保留可见正文；真实本地 JSONL 原文件不含内部推理 canary、已知凭据值，仍含正文和 `[REDACTED]`。该测试使用明确的模型 fixture，证明生产者/存储管线，不能证明真实模型商业产物正确。

前一轮真实 Docker 测试 DENY 根因为测试误把 PermissionRule.ruleContent="*" 当通配符；官方 ToolBase.matchRule 明确仅 null 匹配整工具名。改为真实 SDK 显式授权后成功，未改业务裁决或允许失败代替成功。

SafeTranscriptStore 采用官方 FilesystemTranscriptStore，不可变 segment 放在可信 workspace/.ipd-transcripts 并按 TranscriptRef 分桶，以避免短命沙箱回收后的异步上传失效。准确已知密钥值由生产装配者提供；空集合不能证明真实密钥脱敏，不宣称识别未知自由文本秘密。Kernel 最终接入与已加载运行包、多角色 HTTP/DB、owner 技能发布仍需后续验收。

## 本轮增补：Deadline、Async 与真实环境阻塞

静态扫描自检扩至11项（含 enableAgentTracingLog(false)）；当前四个生产 builder 消费者的字面关闭项为0。只证明未发现静态禁用，不证明provider实际挂载或能力有效。

Kernel明确 `.asyncToolTimeout(Duration.ofSeconds(30))`，官方从真实AbstractFilesystem创建WorkspaceMessageBus和WorkspaceAsyncToolRegistry。新增 `OfficialAsyncToolAcceptanceTest` 使用真实LocalFilesystem+原生持久化bus/registry，短50ms测试阈值，延迟真实IO完成后回读官方inbox。该fixture不代表生产Docker工具的异步终态或run ownership集成已验收；生产30秒未改。测试尚未编译/执行。

Deadline拆成持续模型取消、准备超时不得重置、真实Kernel/Docker共享250ms预算三个断言。final deadline在构造起算，onAgent不重置；持续模型fixture绕开Docker准备耗时，仍直接测试生产middleware取消真实Flux订阅。新增/修改测试尚未执行。

当前磁盘 `/System/Volumes/Data` 容量100%，只余167MiB（本轮df原始回读）。暂停Maven/build/大写入，属于BLOCKED_ENVIRONMENT，不能以既往4项绿替代本轮整体验证。独立agent owned /tmp日志20K+36K与源码摘录132K均非运行依赖、合计188K；未删除证据或他人资源。

本轮root隔离源码编译+70项真实执行：64通过、6失败、0跳过，原始 `/tmp/ipd-official-isolated-root-20261002/tests.log`。6项均KernelTest；当前不是全绿。实际Docker日志14:05:13.367 started→14:05:14.899 PRE_CALL→14:05:17.627 PRE_REASONING→14:05:20.981 POST_CALL，约7.6秒。非deadline fixture旧3/8秒运行预算和2秒text观测不足，因此统一非deadline30秒运行、40秒完成观测，stream cancellation等待真实text最多15秒后dispose，仍严格核CANCEL。250ms运行测试预算不变，只允许10秒资源关闭终态观察并严格断言模型零订阅、零正文；另直接生产DeadlineMiddleware的250ms真实取消测试补充实测耗时小于1秒。root修watchdog回原spec.timeout、interrupt先于close/seal；整合后待单次隔离重跑。不以fixture修改或64通过声明全部生效。

独立原始回读修复隔离结果：`/tmp/ipd-official-isolated-root-20261002/tests-repair.log` 33102ms、70启动/70成功/0失败/0跳过，JVM关闭active requests=0。`sources-repair.sha256`按实际splitlines为35项（末行无换行）；当前回算其中Kernel与ToolGovernance两项已变化，因此该绿仅归属冻结manifest，不外推后续HITL、AGUI、子session/parent transcript修改。新异步fixture该轮真实通过；生产30s异步工具与run终态仍待集成。

独立政策复核待修：当前canonical ALLOWED检查最近assistant、相同id/name/input、无同id ToolResult，能防历史/已消费结果/异参；但RuntimeContext.resolveAgentState优先ctx自带state，须核本次user/session对应canonical状态，不能只靠live owner。delegate完成前同id并发重入须原子claim或明确可证明的原生单次dispatch合同。尚未完成对应反例运行。

官方MemoryBackgroundTasks为进程全局计数，MAX outermost completion concat drain只能覆盖正常completion前已dispatch的memory任务，不能证明整个root/child/async树静止。cancel/error跳过concat、wait超时后的release仍有风险；官方维护错误只log.warn，所以quiescent不等于维护成功。需要原生内存文件效果、失败事件、截止与owner失效反例，不能以装配或计数归零判全能力通过。
