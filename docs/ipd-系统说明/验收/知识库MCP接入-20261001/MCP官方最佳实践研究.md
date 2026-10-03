# MCP 官方最佳实践与 IPD 适用性研究

日期：2026-10-01（用户时区）。状态：DRAFT_ONLY。只研究官方规范、官方发布文档与版本化 AgentScope 文档；本文件未改变实现、配置、数据库或任务状态。

版本说明：新增第 8 节以官方当前 `2026-07-28` 为设计基线；前文 `2025-03-26` 的握手、会话与取消说明只适用于已探测服务的旧协议兼容，不作为最新版实现要求。不能只把旧引用 URL 改成 2026 URL 后保留原语义。

## 裁决

三产线的协议发现已闭环，项目运行接入尚未闭环。当前证据不能支持“系统设计已经最佳”。适合本项目的设计是：服务端按真实 Person、项目与产品线生成有权使用的候选能力；模型在候选中按当前问题选择；执行前再验证权限与运行绑定；引用按实际返回证据呈现。这是本研究对项目治理要求的设计推导，不能冒充 MCP 强制要求。

## 1. 模型选库与服务端候选

MCP 工具允许模型选择调用，但协议不规定唯一交互模型。FastGPT 官方身份代理文档说明工具目录只是元数据，执行时仍检查成员及应用访问权；发布 URL 中的 key 是执行凭据。因而模型理解产品线可以用于相关性判断，不能授予库访问权限。服务端候选应先做权限过滤，避免把全部集团能力和端点交给模型；调用时仍作二次校验。

出处：[MCP 2026-07-28 工具规范](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)、[FastGPT MCP Server](https://doc.fastgpt.io/en/guide/build/publish/mcp_server)。适用限制：集团部署版本和身份代理是否启用未知；不能把当前 FastGPT 文档特性写成该实例事实。IPD Person 与 FastGPT 成员身份也不能仅靠名称匹配。共享发布身份是否适合本项目须由业务治理确定。

## 2. 渐进披露能力

AgentScope Java v2.0.3 的 Toolkit、ToolGroup 与按需激活机制提供减少模型上下文中工具数量的官方路径。可先披露授权候选的名称和业务范围，再激活本次需要的组。知识库选择应复用既有项目运行与能力包标识，不引入第二套聊天内核或独立市场编号。

出处：[AgentScope Java v2.0.3 工具文档](https://github.com/agentscope-ai/agentscope-java/blob/v2.0.3/docs/v2/en/docs/building-blocks/tool.md)。适用限制：工具组控制可见性，不构成业务访问控制；本次未验证项目所加载 JAR 的全部工具组 API。现有少量能力也可直接披露，只有模型混淆、上下文成本或能力规模的实测证据才足以证明动态激活必要，不能为形式而增加层次。

## 3. 只读元数据不构成保证

官方协议把 readOnlyHint 等标记定义为提示，未受信服务的声明不能作为安全裁决。此次三工具均没有 annotations/readOnlyHint。必须由宿主维护经核实的能力策略；不能因为名字包含产品知识就默认无副作用，更不能自动补一个 true 后宣称获得保证。

出处：[MCP 官方 ToolAnnotations schema](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2025-06-18/schema.mdx)、[MCP 官方风险说明](https://blog.modelcontextprotocol.io/posts/2026-03-16-tool-annotations/)。适用限制：服务工作流的日志、计费、模型调用与业务写入尚未审计；“知识问答”即使不改业务数据，也可能产生外部执行记录。2025-06-18 schema 仅用于解释提示语义，实际连接仍按已协商 2025-03-26。

## 4. 回答与原始引用分离

FastGPT 官方文档说明 MCP 调用的是应用，不能由工具名称推出它仅返回原始向量片段。项目应把远端答案和可核实引用分开保存：答案注明来自远端应用；原文出处只接受返回的真实文档、片段和定位信息。若仅有答案文本，就保留“未提供原文出处”的证据等级，不把它包装成本地向量命中，不凭答案补造文件名和费用率原句。

出处：[FastGPT MCP Server](https://doc.fastgpt.io/en/guide/build/publish/mcp_server)、[MCP 工具结果规范](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)。适用限制：本次没有 tools/call，实际结果结构与引用能力未知；MCP 标准 content 也不自动证明内容可靠。分离存储是本项目的证据设计建议，不是对集团返回契约的既有事实声明。

## 5. 已探测 2025 服务的超时、取消与生命周期（旧版兼容）

仅对本次探测采用的 2025-03-26 连接，旧版规范要求先初始化与能力协商，再发送 initialized；旧版取消使用 notifications/cancelled，不能由 HTTP 断开推断已取消。该行为不适用于 2026-07-28：新版取消握手，HTTP 关闭请求 SSE 即取消信号。两版均应限制最大等待时间，宿主按请求 ID 隔离迟到响应，防止停止后把运行改回成功；取消也不证明已完成的外部副作用可以回滚。

出处：[MCP 生命周期](https://modelcontextprotocol.io/specification/2025-03-26/basic/lifecycle)、[MCP 取消](https://modelcontextprotocol.io/specification/2025-03-26/basic/utilities/cancellation)、[MCP 传输](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)。适用限制：取消可选且存在竞态；initialize 不可由客户端取消。三端点未返回会话头，只能说明这次未观察到，不能推断整个部署永远无状态。取消、超时与权限撤销仍需真实故障验收。

## 6. 与本次探测事实对照

原始证据：`/Users/mac/.codex/visualizations/2026/10/02/01a0faab-5186-73c0-8995-be189cfd9cf5/mcp-probe.json`。

| 已验证事实 | 设计含义与剩余限制 |
|---|---|
| 门禁、考勤、智能视频 initialize 200，initialized 202，tools/list 200 | Streamable HTTP 可连接；不证明 tools/call 或项目运行已接线 |
| 协商 2025-03-26；SSE 包装 JSON-RPC；服务版本 1.0.0 | 客户端须兼容该实际版本，不照抄更新规范中的字段 |
| 各一工具，required ques:string | 参数契约简单，但运行归属、权限和路由应由宿主绑定 |
| 考勤、视频 ques 描述误写门禁梯控 | 不能按参数描述自动分产线；保留受控映射并告警漂移 |
| 未返回只读标记或输出 schema | 只读策略与结果校验不能由目录推断 |
| Python 本机 CA 失败，系统 curl 严格证书验证成功 | 属于客户端信任链差异；Java 运行需单独验证，不禁用 TLS 检查 |

剩余验收应覆盖：授权候选与跨线拒绝、候选内语义选库、真实工具结果和引用、撤权后拒绝、取消后迟到结果、超时和有界重试。还需确认剩余 14 端点；不因三端点成功把 17 条配置全部记为可运行。

## 7. 结合本机《最佳实践》官方镜像

本次只阅读以下一套中文 v2 镜像中的相关段落，没有完整审阅该目录，也没有重复读取英语版或旧镜像。镜像是设计参考，不能证明当前依赖或运行包已提供对应实现。

| 本地阅读路径与段落 | 适用于 IPD 的设计 | 不适用或需要验证的部分 |
|---|---|---|
| [FinXScope 4.7](/Users/mac/Documents/最佳实践/AgentScope-Java-docs-complete/v2/zh/blogs/usecases/finxscope.md:204)，实际读 190–225 行，重点 204–217 | 案例将 MCP 配置、健康和连接生命周期集中管理；以统一 Knowledge 接口屏蔽多知识源差异，并透传身份上下文。IPD 可借鉴“统一治理、后端适配多源”，保留已有 ProjectAgent 运行单轨和原有证据链 | 金融案例中的点金、百炼、企业平台 McpClientRegistry 与 SkillsHub 不是本项目已安装能力。案例称动态工具自动注入，不意味着可以绕过 IPD 能力包、Person 和本次运行授权；不得照搬信贷权限或三层技能平台 |
| [工具权限契约](/Users/mac/Documents/最佳实践/AgentScope-Java-docs-complete/v2/zh/docs/building-blocks/tool.md:50)，实际查看 44–55、306–315 行 | `checkPermissions` 明确是执行前运行时检查，支持本研究“候选过滤后再次校验”结论。命名空间避免跨服务工具重名 | 315 行明确描述 readOnlyHint 自动放行；这与 MCP 提示不保证真实行为并不矛盾，而是需要宿主信任策略的整合风险。IPD 不能无条件相信远端标记；三端点当前也没有此标记 |
| [Skill 与 ToolGroup 按需披露](/Users/mac/Documents/最佳实践/AgentScope-Java-docs-complete/v2/zh/docs/building-blocks/tool.md:486)，实际读 486–590 行 | 未激活工具不进入模型 schema；候选较多时可减少选库混淆。对 17 产线适合按运行相关候选构造组，再由模型选择 | `reset_tools` 是整体覆盖，不是增量，遗漏组会停用；basic 永远可见。因此不能把无权能力注册到 basic，也不能把组激活视为授权。当前 IPD 不启用动态技能的边界仍有效，不因示例引入第二技能轨 |
| [Harness 架构与状态](/Users/mac/Documents/最佳实践/AgentScope-Java-docs-complete/v2/zh/docs/harness/architecture.md:15)，实际读 15–70 行 | RuntimeContext 承载本次身份但不持久化；AgentStateStore 承载跨调用状态，按用户与会话隔离。由此应区分“恢复推理上下文”与“重新核验当前 Person 权限”，避免恢复旧授权。组件按需使用，符合最小改造 | 框架状态快照不能替代 IPD 数据库中运行、产物、审核和定档事实。默认长期记忆、文件工作区、Plan Mode 和工具卸载阈值不能原样启用；本项目明确不用 enablePlanMode，也不新建 PLAN.md。镜像中的默认值需核实际 v2.0.3 源码/JAR 后才能形成配置决定 |

综合建议是增强现有项目执行口上的知识源适配、权限候选和生命周期治理，而不是复制金融平台或整套 Harness。统一接口也必须保留本地原始召回与 FastGPT 应用答案的证据等级差异；只屏蔽技术差异，不能抹平来源、权限与引用真实性。

## 8. 最新版 2026-07-28 深度差异与设计裁决

### 8.1 版本状态及研究边界

官方版本页明确 `2026-07-28` 为 **Current**，含义为可使用且仍可接受向后兼容改进；没有把它标为 Draft。这里保留官方状态名 Current，不改称 Final。changelog 个别文字仍出现“this draft”，不能据此推翻专门版本状态页。

出处：[官方 Versioning 当前状态](https://modelcontextprotocol.io/docs/2026-07-28/learn/versioning)、[2026-07-28 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)。适用限制：这是在线官方规范状态，不证明集团 FastGPT 或 AgentScope Java 2.0.3 已支持。已有三端点仅证明接受 2025-03-26；没有用新请求探测，也不能断言服务不支持新旧双时代。

### 8.2 版本协商已从握手变为逐请求

新版取消 initialize/initialized 和协议会话。请求携带版本、客户端能力等 `_meta`；HTTP 同时携带版本头。`server/discover` 是服务端必实现、客户端可选的发现 RPC；版本不支持返回 `UnsupportedProtocolVersionError`，客户端选择双方支持版本。IPD 应明确兼容层所用协议时代，不把旧 SDK 包装成最新版客户端。

出处：[Versioning and Compatibility](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)。适用限制：双时代客户端可兼容老服务；只有现代客户端直接连老服务或旧客户端连仅现代服务都可能失败。回退需识别错误体，不能把所有 400 当版本不兼容，更不能在认证失败时改变身份重试。对 AgentScope 2.0.3 的支持范围本研究保持 UNKNOWN，升级决定需核依赖、源代码及本地协议验证。

### 8.3 传输和取消发生实质变化

2026 HTTP 每个请求独立 POST，响应 JSON 或该请求的 SSE；取消 SSE 请求通过关闭其响应流，不再发送旧版取消通知。要求 `MCP-Protocol-Version`、`Mcp-Method`，调用工具另需 `Mcp-Name`；请求体与头必须一致。取消处理必须按时代分支，不能照用第 5 节旧版行为。

出处：[2026 Streamable HTTP](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports/streamable-http)、[2026 Cancellation](https://modelcontextprotocol.io/specification/2026-07-28/basic/patterns/cancellation)。适用限制：规范要求服务器收到关闭后停止工作，不等于外部模型或已经完成的副作用能被撤销。IPD 仍应保留本地终态保护、最大超时与迟到结果隔离；协议取消不能替代业务补偿。

新版移除协议会话、GET 通知流和 SSE 断线续传；长期通知改为 `subscriptions/listen`。响应断流后新的尝试必须是新请求 ID。对知识查询可在已核无业务副作用、预算允许时有界重试；对应用执行不能把新请求 ID 当作业务幂等保证。

出处：[2026 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)。适用限制：IPD 前端现有运行 SSE 是应用协议，不能因 MCP 变化一并移除其恢复策略；两层流应分别管理。

### 8.4 权限候选与工具目录缓存

新版工具目录不能随连接状态变化，但可按请求携带授权变化。目录缓存有 `ttlMs`/`cacheScope`；因此 IPD 候选目录可以先按授权过滤，再让模型选择。工具组的上下文激活与远端目录授权是不同层；共享目录缓存必须防止跨用户能力泄露，撤权后调用仍需拒绝。

出处：[2026 Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)。适用限制：模型相关性候选还含项目范围，不能只用 OAuth scope 替代项目成员判断；最新工具标记仍只是可信性受限的提示。现有 FastGPT 目录未返回新版缓存字段，不应补造协议合规声明。

### 8.5 Authorization 更新及集团实际限制

HTTP 授权仍是可选能力；采用时按受保护资源元数据发现授权服务、最小 scope 和目标 resource 授权。新版加强授权响应 issuer 校验，持久化客户端凭据按 issuer 隔离；动态客户端注册已弃用，优先 Client ID Metadata Documents 或预注册。

出处：[2026 Authorization](https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization)、[2026 changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog)。适用限制：不能把 FastGPT URL key 或专有身份代理称为已经遵循 OAuth；其集团部署是否实现这些机制未知。IPD Person 身份不可由模型自由传入代理头。配置认证机制的变更必须先确认部署支持和授权，不在本研究中修改凭据。

### 8.6 MRTR、结构化结果与最终判断

新版结果明确区分 `complete` 与 `input_required`；需要额外输入以 MRTR 返回，不是旧式服务器主动 RPC。知识接入适配必须防止把待补充输入结果写成检索成功。原文引用建议用经校验的结构化结果或资源链接携带，仍与远端答案分离；结构符合 schema 不证明事实准确。

出处：[2026 Tools](https://modelcontextprotocol.io/specification/2026-07-28/server/tools)。适用限制：三端点未验证 tools/call，不能推测支持 MRTR 或原文输出；2026 Tasks 已移至官方扩展，并非所有知识服务自动具有持久任务能力。

最终裁决：新版最佳实践加强了逐请求身份、授权目录与无会话执行，支持本项目“服务端权限候选 + 模型语义选库 + 执行时校验”的方向。但直接注册全部 17 服务、相信 readOnlyHint、仅把远端答案标为本地原文命中，或未验证 SDK 就声称符合最新版，都不是已证实的最佳设计。当前应该把最新版作为设计审查基线，把 2025 探针作为兼容证据，两者分别验收。

## 9. 本机 AgentScope 源码与 2.0.3 发布 API 对照

实际源码仓库 `/Users/mac/Documents/agentscope-java` 的 HEAD 为 `e9721285c63a37c10b1d07aa540408e57ba56ab2`，根 POM 第 30 行为 `2.0.4-SNAPSHOT`；[依赖 BOM](/Users/mac/Documents/agentscope-java/agentscope-dependencies-bom/pom.xml:83) 指定 `mcp.version=0.17.2`。该源码不能直接当作 IPD 所加载 2.0.3 的 API 证据。

本次对本机发布包 `/Users/mac/.m2/repository/io/agentscope/agentscope-core/2.0.3/agentscope-core-2.0.3.jar` 执行 `javap`，核实以下公开 API：

| API | 发布 2.0.3 是否存在 | IPD 适用与边界 |
|---|---|---|
| `McpClientBuilder.create(name).streamableHttpTransport(url)` | 是 | 适用于已探测 `/mcp` 的 Streamable HTTP，不能因为响应为 SSE 改用旧 SSE transport |
| `.timeout(Duration)`、`.initializationTimeout(Duration)` | 是 | 分离工具请求与旧时代初始化等待；具体值由当前运行预算设定，不能无限等待 |
| `.protocolVersions(String...)`、`.buildAsync()` | 是 | 可以声明协商版本；字符串参数本身不实现 2026 无握手、请求头、MRTR 或取消语义，因此不能强塞 2026 后宣布兼容 |
| `Toolkit.registration().mcpClient(wrapper).enableTools(names).disableTools(names).group(name).apply()` | 是 | 可以只登记宿主批准的工具白名单并分组，避免简单 `registerMcpClient` 全量注册；模型只在该候选中选择 |
| `Toolkit.removeMcpClient(name)` | 是 | 用于已有客户端移除；仍需依据实例所有权处理共享客户端和在途请求 |
| `McpClientBuilder.propagateMeta(false)`、`Toolkit.closeMcpClients()` | 本次发布 JAR 的公开列表未发现 | 当前 SNAPSHOT 源码已有，不能向 2.0.3 调用方推荐为可编译 API；若需元数据隔离或统一关闭，需核该发布包实际包装器及所有权实现 |

源码对照：[McpClientBuilder](/Users/mac/Documents/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/tool/mcp/McpClientBuilder.java:497) 使用 SDK `McpClient.async(...)` 并配置 request/initialization timeout；[Toolkit](/Users/mac/Documents/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/tool/Toolkit.java:653) 委托给 [McpClientManager](/Users/mac/Documents/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/tool/McpClientManager.java:73)；[McpServerRegistrar](/Users/mac/Documents/agentscope-java/agentscope-harness/src/main/java/io/agentscope/harness/agent/tools/McpServerRegistrar.java:233) 将 HTTP 配置映射到 streamableHttpTransport。

在该源码树按文件名定位未发现 `McpClientRegistry` 类，实际责任组件是上述 Builder、Manager 与 Registrar。FinXScope 案例中的同名企业注册中心是设计案例概念，不能写成开源库的现成公开 API。

明确适用方案是：在 IPD 既有 ProjectAgent 单轨中，由宿主解析本次授权候选后构造白名单注册，使用既有运行身份与事件证据；必要时保留业务包装工具来校验权限、绑定本次运行和标准化来源，不把全部远端工具无条件放给模型。上述 API 存在不证明运行已接入、权限已生效、2026 协议已支持或产物链已闭环。本节没有请求业务端点，也没有改业务代码。
## 10. 附件全系统建议与当前 AI 入口对照（仅研究）

附件 `/Users/mac/.codex/attachments/dab9795b-bf40-44d7-bf50-01e4a658f937/已粘贴的文本.txt` 明确原作者未读取本机源码，其审计语句只能作为待核线索。本节只读精确源码，未请求模型、未改配置，没有证明进程加载这些代码。

| 入口与直接证据 | 装配、参数和治理事实 | 附件建议的适用裁决 |
|---|---|---|
| [ProjectAgentModelAssembler.assemble](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentModelAssembler.java:54)、[Kernel.buildAgent](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java:186) | ai_model_configs 经 Catalog 形成 KernelModelRequest，Assembler 将 provider/model/endpoint/key 交 ModelRegistry。该 record 只有四字段，不含 temperature/maxTokens/网络timeout；Kernel spec.timeout 是整个事件流限制。Toolkit 绑定本次选中工具，当前源码含 ProductLineMcpTool.bind；关闭文件/shell/记忆/子智能体/动态技能 | 可以补有效模型参数与配置版本合同；不能声称已走 AiGateway 同款 SSRF/预算预占。当前有 KernelGovernedTool、READ_ONLY 和选中集合检查，但内存效果账本不等于持久业务对账 |
| [AiCopilotService.chatStream](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiCopilotService.java:212)、[AiGateway.chat](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ai/AiGateway.java:87)、[stream](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ai/AiGateway.java:198) | 副驾同步/流式均进 Gateway；显式 timeout/maxTokens/temperature，流式 temperature 0.50。Gateway 在 LangChain4j OpenAI 装配前 ssrfCheck、预算预占，之后结算账本；记账面依赖 AiCallScope | 共享前置裁决、错误和用量合理；不能把副驾迁入 ProjectAgent。Gateway 注释“终结两套”不能证明 ProjectAgent 已合流 |
| [AiGenerationService](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiGenerationService.java:141) | cfg JSON 取 maxTokens/temperature，检索增强和模板渲染后调 Gateway；瞬时失败补一次调用，每次经过 Gateway。AiDocumentService 是文档链，不是模型出站入口 | 分开尝试级用量与最终业务结果；保留既有文档权威，不因附件建议新增正文表 |
| [AiSuggestionService](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiSuggestionService.java:251)、[GatePrecheckService](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GatePrecheckService.java:285) | currentEnabled 配置，带 scene/actor 的 AiCallScope，调用 Gateway。建议 temperature 0.50，Gate 独立 arbitration 参数；降级由业务服务决定 | 可统一失败分类与来源格式；保留建议降级、Gate 不替代真人批准的差异 |
| [BidResponseCheckService](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/BidResponseCheckService.java:101)、[BidAiCompareService](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/BidAiCompareService.java:123) | Gateway 带 bid_check/bid_compare scene、actor，temperature 0.20；宁可失败不造结果 | 不统一所有入口温度或失败展示；保留材料权限和业务 scene |
| [ChatServiceFacade](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/chat/impl/ChatServiceFacade.java:241)、[WorkflowStarter](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowStarter.java:57) | Facade 分普通模型、Agent、workflow；普通模型由 provider buildStreamingChatModel；workflow 将 User、UUID、inputs 交 Starter/Engine，另有 resumeRuntime | 平台 User/tenant/session 不能冒充 IPD Person/project。这里只验证编排入口，未穷尽节点出站，不断言全 flow 已治理 |
| [CodingHarnessApplicationService.createRun](/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/service/coding/harness/app/CodingHarnessApplicationService.java:216) | budgetPolicy.enforce、sessionGate、稳定幂等 run ID、重复参数校验、ModelRoute、scheduler admission，然后持久化。具备独立 owner/session/run 合同 | 复用领域独立组件和不变量合理；不能复制 Coding 状态机、文件工具、PlanMode 或整套效果账本。附件“收编六组件”必须先证明消费者与领域兼容 |

共享治理建议保持窄合同：身份和作用域由各入口适配；模型配置 ID/摘要、有效参数、能力与 deadline 显式记录；出站 host/DNS、适用预算与尝试级用量共用规则；工具/技能编号与版本、执行时权限、来源证据明确；本地停止、远端取消和业务回读分开。密钥不入事件，模型不授予权限，未设置预算保持不限额，未报告用量不写作已确认的零。模型成功不等于文档审核、应用或阶段完成。

这是一套合同建议，不是合并执行入口、增加数据库或 copy Coding Harness 的实施指令。验收应验证真实消费者、参数生效、撤权/停用后拒绝、预算拒绝无出站、迟到结果不覆盖终态、来源可回读。Flow 节点、embedding/rerank、全部平台厂商适配器尚未逐一深读，不能称“全后端出站穷尽审计”。附件 W/U/G 编号须映射现有唯一计划，不另建台账。


## 11. 官方 building-blocks 全链组件对齐（2026-10-02，只读）

目录证据：本次成功读取官方 [llms.txt](https://java.agentscope.io/llms.txt)，其 SDK Building Blocks 当前实际只有七页：Agent、Message & Event、Middleware、Model、Permission System、Tool、Context & AgentState。Memory 属 Harness；RAG 与 Agent State Store 属 Integration；MCP 是 Tool 的接入能力；Hook 是生命周期接口，不应误称为目录中独立页面。以下是组件对齐，不是全官网逐页通读或全链验收完成声明。

版本边界：本机 `/Users/mac/Documents/agentscope-java/pom.xml:30` 为 2.0.4-SNAPSHOT，参考 HEAD e9721285；实际生产依赖仍是 2.0.3。需要调用 API 时以本机 Maven 2.0.3 发布 JAR/source 为准，不能将 SNAPSHOT 或随站更新的文档特性自动算作运行包能力。本文前述 MCP 注册与 SDK API 核对仍适用。

下表项目源码路径统一相对 `/Users/mac/Documents/ruoyi-ai`；文档镜像统一相对 `/Users/mac/Documents/最佳实践/AgentScope-Java-docs-complete/v2/zh/`。行号是本次读取时快照，其他执行者后续修改应重新定位。表中仅文件名的 Kernel/Tool/Query/Prompt/Retriever 默认位于 `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/`；Planner 位于相邻 `service/ProjectAgentRunPlanner.java`；IpdCopilotAccess 位于 `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/`；KernelScopeKey 位于 `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/chat/kernel/`。

| 组件 / 官方入口 | 当前实际接线与证据 | 未接线、限制或不适用 |
|---|---|---|
| [Model](https://java.agentscope.io/v2/en/docs/building-blocks/model.md) | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentModelAssembler.java:74` 调用共享 AgentScopeModelFactory.context，ModelRegistry 解析；正式构造 :44 复用 AiGateway 出站 guard。镜像 model.md:7–34 描述模型/提供商契约。 | 模型配置、预算与权限仍由业务持有；不能为完整组件清单另造模型网关。 |
| [Agent](https://java.agentscope.io/v2/en/docs/building-blocks/agent.md) | `AgentScopeProjectAgentKernel.java:226–249` 装配 HarnessAgent；:167–181 执行 streamEvents。镜像 agent.md:7–27 列 Agent/ReActAgent 及调用职责。 | ProjectAgent 单轨不要求把副驾、文档生成等入口合为一个 Agent。 |
| [Msg / Event](https://java.agentscope.io/v2/en/docs/building-blocks/message-and-event.md) | `AgentScopeProjectAgentKernel.java:165` USER Msg；EventBridge :428–430 对模型调用事件投影，业务事件与产物保存仍为现有 AgentRunStore/ArtifactVersionStore。镜像 message-and-event.md:14–63、134–189。 | 事件流成功不等于人工审核或阶段完成；正文/思考必须分类，不能将推理标签入文档。 |
| [Middleware / Hook](https://java.agentscope.io/v2/en/docs/building-blocks/middleware.md) | `AgentScopeProjectAgentKernel.java:230/:277–299` 以 DeadlineMiddleware 拦模型调用并保持整轮截止时间。镜像 middleware.md:7–45、251–263。参考源码 `/Users/mac/Documents/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/middleware/MiddlewareBase.java:71`。 | 不为复用而再复制 Coding 的整套 harness；Hook 观察不能授予业务权限。 |
| [Tool](https://java.agentscope.io/v2/en/docs/building-blocks/tool.md) | `AgentScopeProjectAgentKernel.java:209–223` 既有 ToolPolicyEngine 经 KernelToolGovernance 包装本轮工具，Toolkit 串行。镜像 tool.md:44–55、306–315。 | 已选 ID、实际登记、实际调用、命中须分层读回；目录 available 不是远端健康证明。 |
| MCP（同 Tool 页面） | `ProductLineMcpTool.java:70–78` 数据库服务绑定+请求选择；:173 使用 AgentScope McpClientBuilder；Query 使用远端 listTools 的真实名称/schema。镜像 tool.md:486–590。 | 仅当前服务旧协议兼容调用成功；不证明 2026-07-28 全规范。远端回答和原文出处分别判级。 |
| [Permission](https://java.agentscope.io/v2/en/docs/building-blocks/permission-system.md) | `IpdCopilotAccess.java:32–64` 真实 Person/租户/项目成员；Planner :123–132 能力包限制；Kernel :209 工具业务裁决。镜像 permission-system.md:7–26。 | SDK ALLOW/DENY/ASK 不替代项目权与真实人的 Gate 批准。共享远端凭据的授权范围需业务依据，不能自行新增 ACL。 |
| [Context](https://java.agentscope.io/v2/en/docs/building-blocks/context.md) | `KernelScopeKey.java:49–69` 四维键收口；Kernel :167 同一 RuntimeContext；ProjectAgentPrompt :30 起注入授权项目事实。镜像 context.md:7–32、338–346。 | 项目信息是事实来源，不是跨项目访问授权；不能用全局最后活跃 session 状态。 |
| AgentState（同 Context 页面） | Kernel :239 显式 InMemoryAgentStateStore。参考源码 `/Users/mac/Documents/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/state/InMemoryAgentStateStore.java:46`。 | 本轮 SDK 状态不跨重启；现有运行/产物持久化不是完整 SDK 推理现场恢复。 |
| [Session / State Store](https://java.agentscope.io/v2/en/integration/session/overview.md) | 每轮 runId 作为独立 session，现有运行表为业务权威；Kernel 注释 :74、:239。 | 不接 MysqlAgentStateStore 是当前短运行选择；不能为凑目录新建第二张业务文档/运行表。需要跨重启推理续跑时才评估。 |
| [Memory](https://java.agentscope.io/v2/en/docs/harness/memory.md) | Kernel :235–237 禁 Memory tools/hooks/transcript；业务上下文由当前项目事实与既有资料传入。 | 长期 SDK 文件记忆未接线，不应隐式打开并混入他人项目资料。业务历史不自动等于 SDK Memory。 |
| [RAG](https://java.agentscope.io/v2/en/integration/rag/overview.md) | `ProjectKnowledgeSearchTool.java:110–111` 调现有检索；`ProjectKnowledgeRetriever.java:55–95` 组合既有项目与产品向量资料。参考源码 `/Users/mac/Documents/agentscope-java/agentscope-core/src/main/java/io/agentscope/core/rag/Knowledge.java:34`。 | 当前业务检索适配不是完整迁到 SDK Knowledge 接口的声明；不要求为接口统一再造知识索引/存储。 |
| [Skill / Workspace](https://java.agentscope.io/v2/en/docs/harness/skill.md) | Kernel :243–245 禁动态技能/默认工作区技能；业务 Planner 读取既有动作映射并注入已校验正文。 | Skill 市场内容不随运行自动批准；目录与动作编号仍单一。 |
| [Subagent / Plan](https://java.agentscope.io/v2/en/docs/harness/subagent.md) | Kernel :241–242 关闭动态/静态 subagents；IPD 澄清和计划留既有 STEP/业务状态。 | 本项目明确不启 SDK PlanMode 和第二计划文件；官方提供不代表本场景必须启用。 |
| 生命周期 | 实际 2.0.3 AgentBase:269/294 注册/注销；ReActAgent:1093 将 event sink cancel 关联 lifecycle Disposable；Kernel :173–179 取消与总截止处理。 | 测试必须验证活跃请求数回基线；单纯业务 terminal 与 agent.close 不足以证明 SDK 请求已释放。 |

对齐裁决：以官方组件承担推理、消息、工具调用和会话内状态，以 IPD 既有业务服务承担权限、审核、持久运行、文档与验收。组件缺席只在真实需求无法满足时成为待办；禁止为了“全组件”增加第二执行轨、第二业务状态源或自动权限晋升。
