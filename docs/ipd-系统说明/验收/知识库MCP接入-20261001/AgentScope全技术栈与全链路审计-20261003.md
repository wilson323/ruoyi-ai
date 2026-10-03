# AgentScope 全技术栈与全链路审计及后续待办

日期：2026-10-03 01:54 PDT 附近的只读取证快照。状态：**PARTIAL，部分闭环；不能判定完整实现或生产就绪。**

本报告是原总计划的审计证据附件，不是新执行计划、事项台账或状态源。执行顺序只认 `/Users/mac/.cursor/projects/Users-mac-Documents-ruoyi-ipd-web/canvases/ipd-execution-plan.canvas.tsx`；实施事项回原看板和 `开发计划-看板镜像.md`。下文 A–J 仅为本报告检查项索引，映射原六计划 P1–P6，不取代它们。

## 1. 范围、方法与证据边界

范围：`ruoyi-ai` 后端、`ruoyi-ipd-web` 正式 Vue/Ant Design 前端、`agentscope-java` 本机官方源码。对照附件“12356 官方化落地”记录，但不把记录中的成功声明作为本次验收证据。采用 ai-native-sdlc 和本项目 agentscope-harness 技能，三个只读专业分区分别审计官方能力、内核恢复、资源治理，主协调核 Git/POM/JAR/监听/DB。

本次未修改产品源码、配置、数据库或总计划；未提交推送、重载服务、运行模型、发送外部消息或并发构建。执行了一次计划上下文检查（PASS，明确不证明运行/模型/业务）。zvec 语义检索返回 `Failed to open zvec collection storage`，随后用原生 rg 与已定位文件读取；未重建索引。

已直接核对：源码与官方版本、实际消费者、JAR 内嵌 SDK 和关键 class、当前监听与进程打开文件、指定运行/产物/文档/记忆的数据库状态。没有本次完整浏览器旅程、全量测试、真实故障注入或全部 Service 实例检查。全局完整性结论因此不能升级 VERIFIED。缺口能证明“不完整”，不能据此计算未经全量验收的完成百分比。

## 2. 当前基线与旧结论校准

| 对象 | 当前实证 |
|---|---|
| 官方源码 | HEAD `e9721285c63a37c10b1d07aa540408e57ba56ab2`，clean；`pom.xml:30` 为 2.0.4-SNAPSHOT；有 v2.0.3 tag，无 v2.0.4 tag |
| 项目 SDK | 后端根 `pom.xml:16` 固定 2.0.3；官方 tag 与 HEAD 的 core/harness/extensions 差异 617 文件、64751 插入、5870 删除，不能直接混用 |
| 后端 | HEAD `67cd2b50e082a0340c2f6074cf006e52e53e6dab`；Kernel/Configuration/RunService/子恢复/记忆/AGUI 等大量未提交变化 |
| 前端 | HEAD `8c085857f17ebe15ea1c3066ba22243e5fba89e5`；本次 git diff --stat 无差异 |
| 监听 | java PID 58389 监听 127.0.0.1:16039；node PID 38773 监听 127.0.0.1:15666 |
| 打开 JAR | PID 58389 打开 `/Users/mac/Documents/ruoyi-ai/ruoyi-admin/target/ruoyi-admin.jar`；磁盘文件 SHA256 `76b38bb7f828cd38d54d05ebcd22bd11d0ba90dcd251ce2ad9b8cce8084cac07` |
| 内嵌 SDK | core/harness/anthropic/ollama/redis/dashscope/mysql/rag-simple/openai/agui 共10件，全部2.0.3；**缺 POM 已声明的 gemini** |
| class 对账 | JAR 与 target 相等：ProjectAgentKernel、RunHandle、ArtifactVerifier、LTM middleware、Configuration；不等：RunService、ChatOfficialCapabilities、ChatKernel |

上述 JAR 证据只证明当前磁盘文件及 PID 打开路径；没有从 JVM 反读全部已加载 class，也未证明运行中 JAR 从未原位替换。target 相等不代表所有源码都已编译，本次未建立全类 source→target→JAR→JVM 一致性清单。

DB 使用已有 mysql-client.cnf，只读查询指定 ID，不读取或打印凭据、提示词和正文：

| runId | run.status | artifact.status | ai_documents.status |
|---|---|---|---|
| 2106272487481290754 | SUCCEEDED | APPLIED | ARCHIVED（文档2106278921682038786） |
| 2106296461246337026 | VERIFYING | DRAFT | 无关联文档 |
| 2106300308379406337 | VERIFYING | DRAFT | 无关联文档 |

最后一个 run 的记忆现有8行，status=0（候选）；旧报告记4行已漂移。“run ARCHIVED”混淆运行和文档状态；一个文档 ARCHIVED 不能证明动作负责人批准或 Gate 放行。记忆真实落库证明该路径发生过，不证明跨运行召回、撤权、失败恢复或知识正确性全部通过。

## 3. 官方技术栈全景与本项目采用矩阵

“接线”指找到实际消费者；“待验”指没有本次同版本真实行为证据；“未采用”不同于“必须新增”。全量启用要求必须逐能力落实，不能仅删 disable；替代 provider、独立平台产品和后续版本仍须有明确应用边界。

| 层/能力 | 本项目判断 | 证据与剩余问题 |
|---|---|---|
| core ReAct/RuntimeContext/AgentState/事件/消息 | 已接线、待全链验 | HarnessAgent 底层复用官方；业务状态仍归IPD |
| 模型 registry、流式与executionConfig | 已接线 | AgentScopeModelFactory:18–70，Kernel:485–486；provider支持不等于当前模型每项能力有效 |
| provider 扩展 | POM已较全、包不齐 | Anthropic/OpenAI/DashScope/Ollama已在包；Gemini只在POM；OpenAI official属于后续版本新增，不可直接要求2.0.3装入 |
| 重试、超时、fallback | 接线但失败语义缺口 | MODEL_DEFAULTS/TOOL_DEFAULTS已挂；maxRetries会受合并优先级影响；fallback装配错误仅warn继续 |
| 图/音/视频内容块 | 后端AGUI转换已有 | ProjectAgentAguiInput:112–120；浏览器上传、模型支持、存储与重放未本次验 |
| structured output、thinking/reasoning选项 | 未发现完整业务消费 | factory主要传temperature/maxTokens/timeout；需要schema的场景不能只解析随意JSON |
| Toolkit、schema、permission、async/parallel | 已接线、待验 | 业务parallel(false)是并发决策；官方工具能力与具体业务授权分开 |
| MCP | 官方单轨已接 | ProductLineMcpTool:221–261使用ManagedMcpAsyncClient/McpClientWrapper/listTools，不能沿旧记录说仍手写JSON-RPC |
| workspace/filesystem/shell/web | sandbox接线、逐工具待验 | Docker network none不等于整个Web HTTP工具都禁网；Web search需合法provider/凭据；competitor技能仍遵循禁联网合同 |
| skills/管理/curator/晋升 | 冻结与草案接线，发布链缺口 | SkillGovernance始终Defer，未发现owner审批到正式发布消费者 |
| plan/task/meta | 已显式启用 | Kernel:453–499；PLAN.md只作模型工作视图，不代替业务计划和批准 |
| child/Teams/messageBus/inbox/background | 已装配、运行待验 | 官方协作与子恢复存在未提交代码；工程多Agent与产品子任务是两回事 |
| memory/session/long-term | 已接线并有候选落库 | 两处onErrorResume(Mono.empty)及record独立subscribe形成降级/确认缺口 |
| compaction/tool-result eviction | 官方底座已有 | 压缩显式装配；结果卸载有官方默认，不能再列成“全无”；阈值触发及泄密/资源验收仍需真实证据 |
| state/store/checkpoint | 官方Redis+IPD适配已有 | session保存不证明任意崩溃续跑；AGUI批准恢复与外部效果恢复必须分开 |
| sandbox/snapshot/transcript/shutdown | Docker和本地snapshot接线 | 生命周期、release竞态归因、真实restore、排空/清理待验；不能只凭官方源码断言竞态全归官方 |
| artifact delivery | 官方SPI接入现有文档链 | servicebridge为ArtifactDeliveryTarget业务适配，不是Service平台 |
| 完成门/Verifier | 有实现，但远非完整质量系统 | CompletionGate数值语义不足；Verifier只标题/占位，ACTION_RULES为空；reverify无HTTP/UI入口 |
| AG-UI | 已接官方事件与持久投影 | 前端只消费CUSTOM ipd_event；interrupt另有表单；完整事件映射仍需逐类证据 |
| OTel/trace/audit | middleware与日志接线，真导出未证 | 未找到项目内SDK/exporter注册；也未核外部javaagent，不能断言整个运行绝无exporter |
| 应用ACL RAG | 方向符合官方 | 旧core Knowledge/RAG已deprecated转application layer；不应为全量再开第二条RAG |
| 模型cache | 未显式启用，遵循安全默认 | 2.0.3非空context默认不缓存，直接开启会有身份/凭据/config隔离问题，属性能评估 |
| 存储providers | MySQL/Redis采用，其余未采用 | Postgres/JDBC/OSS/COS等是可替代供应商；MongoDB为后续新增，不能同时强装所有库 |
| 记忆providers | 应用LongTermMemory实现采用 | Mem0/Bailian/ReMe未见消费；不是必须再造第二记忆链 |
| RAG providers | 现有应用检索采用 | Bailian/Dify/RagFlow/Haystack/simple按业务资料源判定，避免第二知识事实源 |
| 云sandbox providers | 当前Docker采用 | Kubernetes/AgentRun/Daytona/E2B未见消费；同一合同下替换部署形态，非全部同时运行要求 |
| 协议A2A/AgentProtocol/chat completions web | 未见项目智能体完整消费 | AGUI存在不能代替全部互联协议；需明确合法入口与身份/能力协商 |
| gateway/channel/scheduler | 未见官方完整生产消费者 | Reactor Scheduler/Spring业务cron/WebSocket不等于官方持久调度/channel |
| studio/training/评测 | 未见完整平台消费 | 先建立项目行为评测集；JEV judge为后续版本，不能冒称2.0.3漏接 |
| Spring starters/Higress/Nacos/Aistio | 未见完整生产消费者 | 手动Spring装配可以成立；需分别核服务发现、代理治理、部署实际需求 |
| Service平台 | 未建立完整接入证据 | 官方gateway/dataplane/scheduler/common及Managed/External/Hosted/Issue/Inbox/Team/Workflow/Automation/Channel/Vault/Temporal/workspace/release等，须独立逐入口盘点 |
| examples/BOM/distribution | 参考/依赖/发布工具 | 示例与打包模块不是要求在IPD用户界面全部复制的业务功能 |

## 4. 全链路判断

现有链路：真实Person → ProjectAgentController → RunPlanner/冻结配置 → RunService/Executor/ownership → HarnessAgent → 模型/知识/MCP/内建工具/子协作 → 事件与产物版本 → CompletionGate+Verifier → SUCCEEDED或VERIFYING → 原apply → ai_documents → 人工审核/动作批准/Gate。

该架构主方向符合官方嵌入SDK及扩展点思路。IPD权限、版本、事务、审核、幂等和Gate是应用责任，不应删除来证明“官方化”。现阶段断点集中在：配置和实际运行包不一致、必备能力失败仍继续、技能晋升止于草案、质量标准和复检产品入口不足、崩溃与外部效果恢复未完整证明、全协议/多模态/生态消费者未覆盖，以及一条生成/定档路径被扩大成全项目结论。

当前语义容易误判：SDK流结束≠运行事务成功；运行成功≠合格产物；机器校验通过≠人员审核；文档ARCHIVED≠动作批准；动作批准≠Gate放行；有checkpoint≠外部业务副作用可恢复；有工具schema≠工具真实可用。

## 5. 后续完整待办（按原P1–P6归属）

P0在本报告表示阻断“完整实现”声明的优先问题，不表示已经证实发生生产事故。所有实施须先归原卡allowedPaths及唯一写窗口；以下是建议拆分与验收，不构成这次代码实施授权。

### A. 基线、范围与运行一致性（原P1/P6）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| A01 | P0 | 固定2.0.3 release与2.0.4-SNAPSHOT双栏能力清单 | 每项有版本/API/消费者/provider/错误语义/原卡和证据 |
| A02 | P0 | 冻结一个整合候选，逐文件评审现有dirty改动与归属 | HEAD+dirty diff hash+资源清单可复现，不夹未知依赖 |
| A03 | P0 | 消除RunService/ChatKernel/ChatCapabilities源码、target与JAR差异 | 全关键class同一候选，编译/包对账；不只查8个类 |
| A04 | P0 | Gemini进入合法候选与实际classpath | SPI/provider解析正负例；无凭据如实待验，不编配置 |
| A05 | P0 | 加载可追溯immutable包并核真实进程配置 | PID/启动时间/打开inode/JAR SHA/嵌套模块/SDK/技能一致 |
| A06 | P1 | 清理总览/迁移矩阵/Skill/导航的旧禁开与Service口径冲突 | 最新总画布为权威，历史标注；context check仍PASS |
| A07 | P1 | 纠正run/document状态和旧4条记忆等历史报告口径 | 状态明确到表/对象/取证时间，不覆写原始历史 |

### B. 模型、输入与官方执行参数（原P2/P3）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| B01 | P0 | 必备fallback配置/装配失败显式报告，消除warn后无声跳过 | 合法、缺席、失败、撤权四态可辨；已有owner授权的回退模型按冻结合同执行 |
| B02 | P1 | 主模型故障触发官方fallback真验收 | 故障注入→切换原因/模型指纹/实际调用/用量/结果可追溯；不能静默改选择 |
| B03 | P1 | 各provider能力矩阵及当前MiniMax实际合同 | 流、工具、多模态、schema、reasoning、usage、cancel逐项成功/拒绝 |
| B04 | P1 | 真多模态入口与附件治理 | 浏览器→既有AGUI输入→授权解析→支持模型→产物→刷新恢复；大小/类型/URL/权限负例 |
| B05 | P1 | 结构化输出用于确需schema的意图/计划/元数据场景 | 官方responseFormat或已验证结构化API；非法/截断/不支持明示 |
| B06 | P1 | 明确模型/工具重试、超时、步数、并发及计量合同 | 以ExecutionConfig有效值实测；不靠maxRetries注释；副作用不能自动重复 |
| B07 | P2 | cache创建开销与隔离评估 | 不盲开；确需cacheId时验证身份/config/凭据轮换隔离，无secret输出 |

### C. 工具、技能、计划与沙箱（原P2/P3）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| C01 | P1 | 对每个实际内建工具登记真实消费者/权限/错误/恢复 | schema、调用、业务回读齐全，非只删disable |
| C02 | P1 | 文件/shell/web-fetch/web-search逐项真实验 | Docker镜像与provider可用；失败显式；无宿主回退；competitor禁联网不被绕过 |
| C03 | P1 | Plan/TaskList/Meta工具与现有INTENT及HITL一致 | 计划退出批准、重复批准、取消、刷新一致；PLAN.md不成为业务权威 |
| C04 | P1 | 动态技能发现/渐进加载/管理/curator全过程 | 选定正文/hash/resource与实际使用同一版本，当前run不可被新版本替换 |
| C05 | P1 | owner审批→批准/拒绝→正式目录/映射→下次运行装载 | 审批绑定草案hash、版本与真实owner；Defer回执不算完成 |
| C06 | P1 | 工具权限与参数逃逸负例 | 路径穿越/符号链/私网URL/敏感文件/未选toolId拒绝，合法授权操作可执行 |
| C07 | P1 | snapshot take/restore与release竞态实证 | 真文件变更跨申请与重启保持；竞态最小复现分官方/适配层责任，不凭日志归因 |
| C08 | P1 | transcript/大结果卸载/压缩真实阈值验收 | 不泄秘密/内部推理，不丢来源和批准；超长结果可检索，失败可解释 |

### D. 知识、MCP与记忆（原P3）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| D01 | P0 | 消除LTM读写onErrorResume(empty)静默降级 | 无记忆与故障分开；DB/模型/超时失败可见且可恢复 |
| D02 | P0 | record fire-and-forget改成可确认的生命周期 | 运行不得把未写完描述为已完成；取消/重启/幂等确认有证据 |
| D03 | P1 | 候选记忆与可召回/晋升合同 | 候选不晋升业务事实；本人同项目召回/跨人跨项目拒绝/撤权/来源保留 |
| D04 | P1 | MCP配置→稳定client→listTools→schema→call真链 | 401/空工具/超时/断连/非法schema明确；展示名不作硬等闸门 |
| D05 | P1 | 能力包toolIds与MCP市场编号一致性 | 市场编号不能误提交成toolIds；未纳入能力包不能执行 |
| D06 | P1 | RAG五态与前端可见故障 | 正常/部分成功/无结果/无权/故障区分；向量失败不伪装未命中 |
| D07 | P1 | 嵌入配置与真实向量检索验收 | 既有Ollama模型名/1024维实测，正文补充不冒充向量成功，不建第二库 |
| D08 | P1 | 来源与权限精确回读 | 已审核项目文档/系统产品库符合ACL；年份单位主体可核；撤权失效 |

### E. 子协作、并发、恢复与资源（原P2/P5）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| E01 | P1 | 根→子→孙真实委派与汇总 | 同一权限/快照/预算，lineage不串，子不能自批/定档 |
| E02 | P1 | Teams/messageBus/inbox/background消费者真实验 | 真实消息发送/接收/回执/重复处理/取消，非只有provider注册 |
| E03 | P1 | 双副本争锁/epoch迟到/租约失效/Redis断连 | 唯一writer；旧epoch不能写事件、产物、状态或执行副作用 |
| E04 | P1 | 普通执行阶段crash恢复 | 模型前后/工具前后/产物前后/终态前后冷启动切点逐个验；无法恢复有安全入口 |
| E05 | P1 | AGUI消费意图与递归暂停恢复 | 多interrupt、多批准、重复resume、并发cancel、撤权、重启原lineage一致 |
| E06 | P0 | 外部效果幂等及结果未落库窗口对账 | 已执行未记结果不得重复执行；claim/幂等/outbox/回执一致；synthetic error不是业务补偿 |
| E07 | P1 | 状态清理矩阵（成功/失败/取消/等待/VERIFYING） | Redis/session/容器/句柄/预算不提前删除、不无限遗留 |
| E08 | P1 | 优雅shutdown/超时取消/后台排空 | 无迟到写、重复应用或资源泄漏，重启仍能查证旧结果 |

### F. Quality、产物与业务完成（原P4）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| F01 | P0 | VERIFYING缺口列表、恢复选择与复检HTTP/UI入口 | 当前两驻留run有合法可用路径；原API合同棘轮检查通过，不开第二run轨 |
| F02 | P1 | 缺口补证/新产物版本/新尝试规则 | 不直接改已结束run；不可只反复校验相同不可编辑正文；版本和意见绑定 |
| F03 | P1 | 动作级质量规则与不同产物形态 | 按69动作合同逐批制定；HTML/纯文本/附件不能被统一Markdown标题规则误拒 |
| F04 | P1 | claim→source语义校验 | 数值+单位+年份+主体+口径+出处；无检索编造/同数不同单位负例；人审仍保留 |
| F05 | P0 | 复检/取消终态CAS与事件的持久一致性 | 3次事件写失败后可自动对账补偿；两次reload不能冒称原子 |
| F06 | P1 | VERIFYING成功后的receipt/checkpoint及需求回写闭环 | 不残留恢复检查点；回写失败有可重试记录、不悄悄算业务完成 |
| F07 | P1 | deliver_artifact/apply并发/重复/权限真验 | 原ai_documents列与链，文件hash/version同源，重复无重复业务效果 |
| F08 | P1 | 审核退回→关联新尝试→复审→动作批准→Gate | 真实身份批准，提交人不能自批，机器成功不替代业务闸门 |

### G. 协议、前端与多人体验（原P5）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| G01 | P1 | AGUI所有标准事件映射矩阵 | 原生事件→持久投影→UI有实际消费者；未支持明确，不靠CUSTOM统称全支持 |
| G02 | P1 | SSE断线/重放/LastEventId/Unicode/终态顺序 | 无丢帧重复；刷新恢复相同run，原生与投影不双渲染 |
| G03 | P1 | 既有三栏、输入加号、预览、历史搜索真浏览器验 | 不新增发送轨；生成即预览、HTML隔离、历史跨刷新可见 |
| G04 | P1 | 多人多项目及撤权/负责人非成员权限 | 正反例HTTP+DB；查看不隐含运行/取消/定档权限 |
| G05 | P1 | 双标签1007不重连/1006可恢复与后台运行分离 | 浏览器断开不误取消；模式切换不混会话 |

### H. 观测、行为评测与交付门禁（原P6）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| H01 | P1 | OTel SDK/exporter/collector或javaagent真接入 | root/child/model/tool/error实际span导出，run关联、脱敏、关闭资源正确 |
| H02 | P1 | 统一审计与真实用量账本 | 主/回退/压缩/记忆/子模型全部计量，未知不填0；无秘密和原始内部推理 |
| H03 | P1 | 固定跨版本行为评测集 | 正常/缺输入/来源不足/错事实/无权/撤权/恢复/重复apply留出用例，原始结果可复核 |
| H04 | P1 | 正负控验证禁用/双轨/孤儿API/权限键门禁 | 能自证会红；SKIP/零测试与通过分开 |
| H05 | P0 | 同候选完整回归和单一构建窗口 | 后端真测试数；前端type/Vitest/build并核TS诊断和声明；旧红根因独立分诊 |

### I. 未采用生态能力与官方平台范围（原P1/P2/P5/P6）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| I01 | P1 | 按总画布已扩大目标列Service全部入口 | Managed/External/Hosted、注册、Issue、Inbox、Team、Workflow、Automation、Channel、workspace/release逐入口实现/服务/鉴权/消费者/证据 |
| I02 | P1 | Vault/remote sandbox/Temporal与SDK版本合同 | 是实际平台还是仅参考必须明确；无凭据/服务标未闭环，不用servicebridge目录名代替 |
| I03 | P2 | A2A/AgentProtocol/chat-completions互联入口 | 能力协商、认证、撤权、超时、重放、权限映射；不增加未经治理业务入口 |
| I04 | P2 | gateway/channel/官方scheduler采用矩阵 | 区分业务cron与SDK调度；外发渠道不得自动发送，无必要渠道可明确未采用 |
| I05 | P2 | 存储/RAG/记忆/云沙箱provider选型表 | 明确采用/替代/未采用理由；不为清单填满同时造多套事实源 |
| I06 | P2 | Studio/training/JEV、Higress/Nacos/Aistio及starter矩阵 | 2.0.3与后续版本分开；平台资源与实际consumer核验，升级单独评估 |

### J. 最终同版本验收（原P6）

| ID | 优先级 | 待办 | 验收 |
|---|---|---|---|
| J01 | P0 | 同包真实Person完整用户旅程 | 能力选择→澄清/计划→模型/知识/MCP/工具/子任务→质量→定档→审核/退回→动作批准/Gate→刷新/重启；HTTP/DB/Redis/浏览器同链 |
| J02 | P1 | 覆盖全49页/69动作/六阶段及产品生命周期 | 复用原AC清单逐项签证，概念/计划首片不能代替研发/发布/反馈/迭代/退市 |
| J03 | P0 | 候选回滚、配置/数据兼容与试恢复 | 回滚实际试过、证据可回读；全部必要验收无关键失败才可报对应范围VERIFIED |

建议推进依赖：先A01–A05确保审同一版本；并行B/C/D与E合同，优先D01/D02、F01/F05/F06；知识/技能/MCP实际闭环后完成F03/F04/F07/F08；随后G/H与J。I按总画布原目标登记，不另造第二轨，不用未采用provider拖延核心已授权修复，也不能用核心局部绿替代平台全入口验收。

## 6. 反思与裁决依据

1. 过去以开关、依赖、源码存在衡量“完整应用”，漏掉provider、消费者、错误、生命周期及运行包。今后每项至少核“API存在→装配→实际消费→权限→成功/失败→持久回读→恢复”。
2. 缺口清单不断被新代码推翻，但旧禁开和“唯一红域”等绝对表述仍留在文档，导致重复实现。必须沿同一总画布校准，不以旧清单直接动代码。
3. 并行工作产生有效补丁，也产生混合工作区和包漂移。局部53测试绿不能证明同一完整候选绿；“兄弟改的”不解除系统级失败。
4. 质量规则是应用责任，不是官方框架自动提供。标题/占位检查只能证明格式，数字存在只能证明字符串，不能证明年份、单位、事实和业务合同正确。
5. recovery、pending tool recovery、snapshot和状态持久化各解决不同问题。必须按真实失败窗口验证，尤其已发生外部效果不能靠重跑补齐。
6. 用户“全量启用”不是删除权限，owner审批不是永远延期。正确闭环是官方能力可用、业务授权明确、被允许的操作真执行、未被允许的操作真拒绝，失败可见且可恢复。

最终裁决：**本项目已广泛采用官方核心与Harness，方向不是全面偏离官方；但尚未完整实现最新全量目标，且运行版本、失败语义、质量/复检、技能发布、通用恢复、生态入口和全业务验收存在明确未闭环项。**

## 7. 关键源码导航

后端统一根：`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/`。

- `kernel/AgentScopeProjectAgentKernel.java:442–516`：fallback与官方能力装配。
- `kernel/ProjectScopedLongTermMemory.java:126–149`：错误吞并。
- `kernel/ProjectAgentLongTermMemoryMiddleware.java:76–90`：召回/异步记录。
- `kernel/ProjectAgentSkillGovernance.java:42–91`：晋升延期与冻结执行。
- `kernel/ProductLineMcpTool.java:221–261`：官方MCP消费。
- `kernel/ProjectKnowledgeRetriever.java:51–77`：应用层检索。
- `service/ProjectAgentArtifactVerifier.java:77–115`：通用规则及空动作规则。
- `service/ProjectAgentCompletionGate.java:33,168–172,408–435`：数值来源检查边界。
- `service/ProjectAgentRunService.java:1007–1085`：复检/终态事件/回写。
- `service/ProjectAgentRunRecovery.java:28–66`：普通失联恢复边界。
- `service/ProjectAgentRunHandle.java:469–590`：首跑事务终态。
- `kernel/ProjectAgentChildResumeDispatcher.java:32,103`：递归恢复。
- `servicebridge/ProjectAgentArtifactDelivery.java`：产物SPI而非Service平台。
- 前端 `/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/api/ipd/project-agent-agui.ts:13–38`：事件投影。
- 前端 `apps/web-antd/src/api/ipd/project-agent.ts:22,387,416,443`：VERIFYING可识别，但未找到复检调用与缺口列表专属消费者。
- 官方 `v2.0.3:agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java:4774–4780`：pending recovery是补synthetic error结果，不能当业务副作用恢复。
- 官方 `agentscope-core/src/main/java/io/agentscope/core/rag/Knowledge.java:29–34`：旧RAG废弃方向。

行号为本次读取快照；共享工作区可能继续变化，后续按符号重新核，不把本报告当长期运行状态。
