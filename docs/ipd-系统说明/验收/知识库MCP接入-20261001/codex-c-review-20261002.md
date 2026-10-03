# C 分区独立复核：产线 MCP 调用及诊断

裁决：`PARTIAL`。SDK 单轨及本次安全诊断实现可以认可；发现的失败分类边界缺陷已按协调者独占授权修复，隔离针对性探针通过；正式 Maven、运行加载、实际取消和全部服务验收仍未验证。记录时间：2026-10-02T17:38:53Z。

## 六行缺口

- 仓库：`/Users/mac/Documents/ruoyi-ai`，前端不在本分区修改范围。
- 入口：`ProductLineMcpTool.bind/callAsync` → `invokeOnce` → `ProductLineMcpQuery.invoke`。
- 服务边界：共享 `ManagedMcpAsyncClient` → AgentScope `McpAsyncClientWrapper` → 官方 MCP async SDK；此次只读复核，不修改运行配置。
- 表和字段：SOURCE 事件 `retrievalStatus/hits/reasonCode/mcpFailureStage/mcpErrorTypes/timeoutSeconds/citationText`，无 DDL。
- 现状与目标：复核安全原因码、实际协议参数、超时取消及 probe 证明范围；不是重做来源完成门或市场目录。
- 证据等级：A 级源码、未暂存 diff、现有 Surefire XML；probe JSON 是已有执行产物，本复核未重新调用外部服务、未重跑共享 Maven、未核实加载包。

## 认可的实现

1. 按 `product_lines.mcp_service_id` 与本次 `toolIds` 选源，没有中文展示名相等闸门。生产调用确实走 `ManagedMcpAsyncClient.streamableHttp`，没有手写 JSON-RPC，也没有第二个项目运行入口。
2. 工具名及参数来自 `listTools`：只有唯一工具和唯一必填 string 属性才调用；传递的是协议声明的参数名，不是硬编码 `query`。不支持的 schema 与多个工具直接失败，不猜测。
3. 异常正文只输出阶段、白名单原因码、类简单名；异常 message 不回传。远端 `isError=true` 的正文不进 preview/citation。失败不再写 `hits=0`，空资料与失败区分。成功 citation 保留完整正文，preview 仅 1000 字。
4. 阻塞协议阶段调度至 boundedElastic；开启和结束前检查 ownership。正常和协议失败走 try-with-resources 关闭 session；initialize 失败也主动 close。共享客户端 close 有独立 1 秒 graceful 超时和强制关闭路径，状态为异步清理，不能把调用 close 等同于已经关闭成功。

## 发现：失败分类依赖未信任正文（P2，已通知协调者）

候选定位：`ProductLineMcpQuery.java:140-141,222,334` 和 `ProductLineMcpTool.java:170,200`。

`AMBIGUOUS_TOOLS` 失败正文拼接远端的 `tool.name`。`finish` 又以正文是否包含 `NO_DATA` 判空资料，而非先确认失败前缀。如果远端工具名含「该产线知识库没有返回资料」，同一返回值虽然以 CALL_FAILED 开头并携带 AMBIGUOUS_TOOLS，却被记录为 NO_HIT/hits=0，并以普通 ToolResultBlock.text 返回。与明确的「失败不应伪装零命中」契约冲突。类似地，工具名若含 `阶段=CALL_TOOL；原因=TIMEOUT`，`readDiagnostic` 的首次 regex 匹配会把错误阶段/原因写入 SOURCE。

这是根据确定分支可复核的源码缺陷；没有声称真实目录服务已经返回这种名称，也未运行该复现。远端协议不合规或异常元数据应失败，不能改变本地失败分类。最小修复候选：多个工具失败只输出安全固定说明，删除远端工具名拼接；同时让 CALL_FAILED 前缀先于 NO_DATA 分类。较完整方案是结构化结果传递状态及诊断，正文仅展示。需补回归覆盖该边界。初次只读评审未修改源码；随后授权修复见末节。

## 超时、取消的准确口径

- TIMEOUT=20 秒分别用于 initialize、listTools、callTool 的 block 和 SDK request/initialization timeout；不是整个工具调用统一 20 秒预算，三个阶段串行理论上可能累计更长。`timeoutSeconds=20` 应解释为失败阶段的配置值。
- 单测主动抛 `CancellationException`/TimeoutException 证明诊断映射，不能证明订阅真实 dispose 后的网络请求取消、资源释放和无迟到事件。
- 实际运行取消后 ownership 已失效，迟到 finish 被阻止是正确边界；不应要求被取消运行继续写 SOURCE 以制造 CANCELLED 原因码。若需证明实际取消，应使用可控挂起协议服务、dispose、等待 close 完成并回读无迟到事件；本复核未执行。
- try-with-resources 的 close 若抛异常，会被外层映射为 INITIALIZE；当前生产 ManagedMcpAsyncClient 的正常 close 异步返回，未发现此路径真实发生，因此仅作为泛化 Session 接口限制，不升级为当前生产根因。

## 现有证据的局限

- 读取 `cursor-task-2-result-20261002.md/json` 与 `handoff2-context-v3-mcp-actual-query-probe-20261002.json`。一次官方客户端重放确实发现一个工具并获得一个内容块；后者记录 query SHA 和 20 秒配置。不能证明其余服务、内容正确性、完成门通过、新诊断已经在 16039 生效。
- Cursor 结果报告更详的 initialize/list/call、isError=false 和 10278 字正文；一次成功期间的 SSE 405 不能归因为历史 v3 两次失败的根因。历史失败没有阶段诊断，根因仍未知。
- 当前 Surefire XML 是 19 tests / 0 failures / 0 errors / 0 skipped，与 Cursor 报告一致。未独立重跑，不把它称为本次独立执行通过。`-Dproject.build.directory` 未隔离主 target 的限制已明确披露。

## 完整候选路径和读取时哈希

| 文件（均位于 `/Users/mac/Documents/ruoyi-ai/`） | SHA-256 |
|---|---|
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProductLineMcpQuery.java` | `a35e2cb9d1e067ff00aee35999ccdd017724afa96cc01da4a44ac9e8014ada05` |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProductLineMcpTool.java` | `96314ac1f266bf99cbe21b91d13f39739755356171167faf151e839c423bd080` |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProductLineMcpQueryTest.java` | `5124e6129bf66b438b34ec9782fcef5a2774309ed947802fd3b33c208eb22a65` |
| `ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/mcp/service/core/ManagedMcpAsyncClient.java` | `a1e9a7eee9f1bbc2839ffce33df7b0d1adbc0ec2cbb3524fa96000537901372d` |

适用规则已读：后端 AGENTS、`/Users/mac/.agents/skills/ai-native-sdlc/SKILL.md`、后端 `.claude/skills/agentscope-harness/SKILL.md`。未触碰 Git 索引、服务进程、总计划、看板和其他分区；本分区新增复核文件和隔离探针结果，并在后续独占授权内修改三个 Java 文件。

## 协调授权后的修复与隔离验证

协调者收到上述缺陷后明确授予 C 独占 Query/Tool/对应 tests，其他写入者暂停这些路径。本节更新后，前文哈希为修复前基线，未修改原历史结果。

- 多工具失败不再拼接远端工具名，避免工具名进入失败正文与机器诊断。
- 来源分类先判断本地 CALL_FAILED 前缀，失败不能因正文含 NO_DATA 变成空命中。
- readDiagnostic 只接受本地失败前缀且诊断标记位于正文结尾；保留既有 String 合同，未扩展公开 API 或事件枚举。
- 新增两项回归：远端工具名同时含 NO_DATA、伪阶段码及 URL/令牌时，仍 ERROR/FAILED/AMBIGUOUS_TOOLS/LIST_TOOLS、无 hits/timeoutSeconds；普通远端正文不能生成诊断，前部伪诊断不能遮住本地尾部标记。旧多工具用例仍断言零调用，改为不回传未信任工具名。

验证：git diff --check 退出 0。独立临时目录 javac 编译 Query、Tool、对应测试及最小反射执行器，编译退出 0，六项显式选定测试全部 PASS、执行退出 0。覆盖新增两项、旧多工具、异常保密、阶段/取消/超时映射及反应线程隔离/关闭。依赖 classpath 读取已有 Surefire XML；不运行 Maven、不写主 target。不是完整 JUnit/Surefire 生命周期执行，不替代协调者串行 Maven。

真实证据：`codex-c-isolated-probe-20261002.json`，含命令方法、六个方法名、原始标准输出、返回码、修复后源哈希及临时目录。javac 的已有 deprecated/unchecked 提示已保留，未放宽编译配置。实际订阅 dispose、远端网络取消及已加载 16039 尚未验证。
