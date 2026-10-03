# C 当前真实运行 MCP 失败独立诊断

裁决：PARTIAL。定位到生产客户端打开/initialize包围的失败阶段，唯一异常根因仍未知。未外呼、未写DB/源码/target、未apply产物。

run：2106082915170418689。只读SOURCE全列表：项目检索seq18 PARTIAL；19/20/21/46/47/48 SUCCESS；MCP seq22/23/24/25/44/45 FAILED。合计项目7、MCP6、总13。早先独立复核口算把6 SUCCESS写成7，造成8+6=14说法，现明确纠正；库与poll数量没有冲突，不得据此拒定档。

6条MCP安全诊断均PROTOCOL_OR_TRANSPORT、INITIALIZE、IllegalStateException；同一FastGPT服务标识。httpReason字段不存在，timeoutSeconds不存在，hits不存在，citation为空元数据。不能因此判断405、超时、未授权、网络不通或API兼容失败。阶段码是本地try范围名称，不是完整协议trace。

当前生产路径callAsync在boundedElastic执行invokeOnce；open使用ManagedMcpAsyncClient.streamableHttp，再wrapper.initialize().block(20秒)。失败catch不记录异常message/stack，只保留类链。takeover日志此run出现范围494–6106未找到IllegalStateException/McpTransportException/NoSuchMethodError/ClassNotFoundException/block()/blockFirst/MCP/mcp_client词项；该检查仅此日志片段，不代表所有日志系统不存在错误。没有读取输出私密URL/key/query/message。

新包ZIP核SDK agentscope-core2.0.3、mcp-core0.17.2、reactor-core3.7.13。官方2.0.3 sources.jar中McpAsyncClientWrapper.initialize:73-92实际执行client.initialize后.then(client.listTools())并缓存。因此INITIALIZE可以包含隐式列工具失败，不能把它限定为握手失败。mcp-core0.17.2 McpAsyncClient.listToolsInternal:652-653在远端能力未声明tools时返回IllegalStateException，wrapper.listTools/callTool也有not initialized分支；本次无cause message/stack或安全协议响应字段，不知道唯一throw site，不能把候选源码分支当真实根因。也没有NoSuchMethodError证据，不能先升级SDK或构建第二客户端轨。

缺失的A证据：客户端构建、原生initialize与wrapper隐式listTools分别完成的阶段；异常具体安全throw site/stack摘要；协议HTTP状态/兼容字段（排除URL、凭据、异常原文）。后续若协调者授权诊断补丁，应在官方共享SDK扩展边界提供安全分段诊断，再由新运行验证。此回合只读不补代码、不重试外部。源实现本次定位只证明阶段与捕获设计，不证明远端可用或资料质量。

证据：codex-c-real-mcp-diagnosis-20261002.json保存SELECT安全列、13行、退出0、日志行范围与白名单计数，没有正文/凭据。DB时间范围查证先误用created_at返回1054，继承BaseEntity后核create_time，成功读到2026-10-03 02:04:31–02:06:55（库时钟），不直接当用户本地时间。产物质量与DRAFT决定归协调者A；本诊断没有自动定档或批准。

## 后续已授权安全诊断补丁（已冻结，待测试）

协调者授Query/Tool/Test局部Outcome修复。保留String invoke/protocolFailure兼容委托，生产改用Outcome传递本地诊断与SDK帧；帧不拼模型文本、不读远端toolName、不输出异常message/URL/参数/文件名。SOURCE仅FAILED时增加非空mcpSdkFrames。类名精确限McpAsyncClientWrapper/McpAsyncClient/HttpClientStreamableHttpTransport，方法名只合法ASCII标识，line>=-1；因果8层、首64帧/层、最终8条且去重，循环去重，异常简单名/方法名限100字符。未知帧留空。

仅异常为IllegalStateException、message精确等于本机0.17.2固定字符串、且同异常有McpAsyncClient#listToolsInternal帧时记TOOLS_CAPABILITY_MISSING；取消/超时原判据优先，未知仍PROTOCOL_OR_TRANSPORT。该码只是后续诊断能力，不把当前6次失败升格为能力声明缺失根因。前端原白名单尚不翻译新码，会保持未知原因空白；不得据它宣称页面已有新原因文案。

新增3项测试源码：安全帧只进SOURCE且私密message/未知class/特殊方法不泄漏；精确message无真实SDK帧不能升级、多层包装可保留、超时优先；循环/20层原因有限遍历、无帧不造帧、帧最多8。现有远端工具名污染和FAILED优先级测试保留。git diff --check退出0；本轮没运行Maven/target/外呼，测试尚未执行。冻结before/after hash见codex-c-safe-sdk-diagnostic-patch-20261002.json，交A统一测试与仅工具受控运行验收。

## B 独立源码复核安全诊断补丁

裁决：认可本补丁的有限、安全诊断边界；未发现需阻断的新增泄漏或FAILED/NO_HIT混同。独立读取Query/Tool/Test实际文件，三文件SHA与codex-c-safe-sdk-diagnostic-patch-20261002.json冻结after逐项一致（7179d779…、a156902c…、5d50b334…）。24测试通过由A原执行结果提供，本复核没有重复Maven/target/出站。

Query:189–234以IdentityHashMap按对象身份去重因果循环，最多8异常，每层最多首64帧，最终最多8去重帧；精确3个SDK class白名单，method合法ASCII且1–100字符，line>=-1。输出不含文件名、模块、异常message或参数；errorTypes简单类名也限1–100字符，链最多8。没有把整个stack串行化。Tool.finish只把sdkFrames放FAILED来源事件，模型得到Outcome.text而非frames；异常正文只有固定安全描述/安全枚举/有限简单类名。成功的知识正文仍按原业务传入模型，这是正常检索内容，不是错误诊断text旁路。remote isError固定REMOTE_IS_ERROR，不把其远端正文变诊断。

能力缺失码必须同一个IllegalStateException同时命中SDK固定message与McpAsyncClient#listToolsInternal帧；无帧、未知类、不同message都不升级。独立读本机mcp-core0.17.2 sources.jar：listToolsInternal:653确实抛该固定字符串，故判据有原源码依据；callTool:590也用该字符串但没有listToolsInternal帧时保守归通用失败，不伪造命中。stack是本机Exception所持数据，限定判据不能证明远端服务当前真实能力状态，尤其不能回推历史6次失败唯一根因。

取消/超时按原外层先出现者锁定reason，仅PROTOCOL_OR_TRANSPORT才可升级capmissing；InterruptedException还恢复thread interrupt。故外层timeout包内层capmissing保留TIMEOUT，既有CANCELLED也不会被覆盖。原行为是外层优先，并非无条件扫描全部内层取消压过外层timeout，不应改变合同描述。

Query.invokeOutcome仍区分NO_TOOLS/UNSUPPORTED_SCHEMA/REMOTE_IS_ERROR/EMPTY_RESULT；Tool.finish先CALL_FAILED识别再NO_DATA，FAILED不因正文含未命中词变NO_HIT，失败citationText空/chars0、无hits，NO_HIT hits0。新增测试实际覆盖SOURCE-only帧、特殊方法/未知class排除、固定message无SDK帧不升级、超时优先、循环/深链/8帧限额；本轮不是运行态验收。需后续新包受控调用回读真实mcpSdkFrames才判真实根因，当前没有升级SDK/第二客户端/访问权变更理由。


## 088 包失败后的精确工厂帧补充

裁决 PENDING_VALIDATION。run 2106088393019568130 SOURCE seq8 的 INITIALIZE/IllegalStateException 且 mcpSdkFrames=null，只能证明原精确三类白名单未保留帧，不能确定真实抛错点。已直接核 owning sources JAR 0.17.2 mapper 的无默认provider/初始化失败抛错、transport Builder默认mapper调用，以及3.7.13 BlockingSingleSubscriber的block限制/超时包装与Mono#block。共享Managed工厂未显式传mapper，但仅作为候选链，不当根因。

仅Query与Test补6个精确类：transport $Builder、McpJsonMapper、McpJsonInternal、BlockingSingleSubscriber、Mono、ManagedMcpAsyncClient。无泛包和任意内类匹配，未知邻类仍空，frame仅class/method/line且source-only。新增1项测试；未运行Maven，git diff --check退出0。冻结hash及候选源码行见 codex-c-safe-sdk-factory-frames-20261002.json。下一次受控工具调用若仍无帧，不能据此升级原因码。
