# Cursor任务2结果：MCP失败诊断

状态：`PENDING_VALIDATION`。单测合同已过。当前运行包未加载这版诊断，v3 两次失败的异常原文无法追回，probe 成功不等于生产全部成功。未提交、未重载、未改总计划。

## 六行缺口

- 仓库：`ruoyi-ai`。正式 HEAD `29b64b97c1729335abe22955b06a7597d68a9fab`。这三个 Java 文件不在 HEAD，索引里已有未提交版本。
- 入口：`ProductLineMcpTool.callAsync` → `invokeOnce` / `ProductLineMcpQuery.invoke`。生产打开仍是 `ManagedMcpAsyncClient.streamableHttp` → `McpClient.async`。
- 服务边界：只解释握手、列工具、调用、远端 `isError`、超时和取消。未改 `ManagedMcpAsyncClient`，未改任务1来源/完成门。
- 表和字段：无 DDL。来源事件 Map 增加 `reasonCode`。`FAILED` 不再写 `hits=0`。
- 现状与目标：v3 运行 `2106064774604263425` 的 seq 42、57 是 `retrievalStatus=FAILED`、`hits=0`，preview 只有「连接或协议调用失败，请稍后重试」，没有阶段。本刀补上可区分的安全原因码。
- 证据等级：A 级源码、单测、库内事件回读、一次 20 秒官方客户端重放。v3 根因仍是未知，因为已加载包的 catch 没有留下异常类型。

## 行为

失败正文带 `阶段` 和 `原因`。来源事件在 `FAILED` 时写 `reasonCode`、`mcpFailureStage`、`mcpFailureReason`。超时另写 `mcpErrorTypes` 和 `timeoutSeconds=20`。取消记 `CANCELLED`，远端 `isError=true` 记 `REMOTE_IS_ERROR` 且不回传工具正文。异常 message、地址和凭据不进入模型正文或来源字段。

`hits` 只在 `SUCCESS` 为 1、`NO_HIT` 为 0。`FAILED` 不写 `hits`，避免被当成零命中。

## 给任务5的 reasonCode

只读来源事件里的 `reasonCode`，不要从 preview 反推。取值只可能是：

`TIMEOUT`、`CANCELLED`、`PROTOCOL_OR_TRANSPORT`、`REMOTE_IS_ERROR`、`NO_TOOLS`、`AMBIGUOUS_TOOLS`、`UNSUPPORTED_SCHEMA`、`EMPTY_RESULT`、`INVALID_QUERY`、`NO_ENDPOINT`、`NO_CLIENT`。

阶段只可能是 `LOCAL`、`INITIALIZE`（握手）、`LIST_TOOLS`、`CALL_TOOL`。`TIMEOUT` 时 `timeoutSeconds` 为 20。没有诊断标记时不要编原因码。

## 验证

1. 隔离意图失败：`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=ProductLineMcpQueryTest -Dproject.build.directory=/tmp/cursor-task2-mcp-20261002/target test`。`-Dproject.build.directory` 没有生效，编译写进了主模块 `ruoyi-modules/ruoyi-ipd/target`。退出码 0。`ProductLineMcpQueryTest` 19 项，失败 0，错误 0，跳过 0。结束时间 `2026-10-02T17:22:43Z`。之后主 target 的修改时间又变过，说明还有别的写入者。
2. 库回读 run `2106064774604263425` seq 42 与 57：`FAILED`、`hits=0`、无 `reasonCode`、preview 79 字且无 `阶段=`、无 URL。问题文本 21 字，SHA-256 `e31520919dbaaee2e1035154afae9f331d4a3cba8aa28278b217693e3e7a3074`。问题原文未写入本结果。
3. 同一服务、同一问题、超时 20 秒的官方客户端重放，第 1 次即返回。`initialize=OK`，`listTools=OK`，`toolCount=1`，`requiredStringArgs=1`，`call=OK`，`isError=false`，`contentBlocks=1`，`textChars=10278`，`hasSourceName=true`，`elapsedMs=2893`。标准输出没有地址和问题。过程中 SDK 记录了 `McpTransportException`，SSE 状态 405，`Method not allowed`；这次调用仍然成功。不能把这次 405 写成 v3 两次失败的根因。
4. 未加载 PID `13520`。未重放新的项目运行。未批准产物 `69b9c84f9fe34eb088c9d0c7920a093e`。

## 差异

相对索引的未暂存差异：3 文件，`+315/-16`。这三个文件相对 HEAD 是新增，索引里另有 `+893`。工作区 SHA-256：

- `ProductLineMcpQuery.java` `a35e2cb9d1e067ff00aee35999ccdd017724afa96cc01da4a44ac9e8014ada05`
- `ProductLineMcpTool.java` `96314ac1f266bf99cbe21b91d13f39739755356171167faf151e839c423bd080`
- `ProductLineMcpQueryTest.java` `5124e6129bf66b438b34ec9782fcef5a2774309ed947802fd3b33c208eb22a65`

## 未验证

- 新包未装入 `127.0.0.1:16039`，不能说界面或新运行已经能看到 `reasonCode`。
- v3 两次 `FAILED` 发生在握手、列工具还是调用，仍然未知。
- probe 只覆盖 v3 用过的那一个服务和那一个问题，不覆盖其余 16 个服务，也不证明资料正确。
- 主 target 被这次单测写过，和其他编译任务有交集。源码没有改任务1、目录、内核或 `ManagedMcpAsyncClient`。

## 2026-10-02 19:20Z 补充：405 与带帧的 INITIALIZE

状态仍是 `PARTIAL`。没有改 Java。没有新的项目运行。没有把 probe 成功写成生产全部成功。

当前监听是本机现查，不是总计划里的旧进程号。`127.0.0.1:16039` 为 java `31351`，启动 `2026-10-02 12:10:28`（本机时区），jar `ruoyi-admin.codex-takeover-20261002-a44e858e9c72.jar`，SHA-256 `a44e858e9c72ccb2da0bf54a5ad2eeddd0026872a920feb250ce07a37e4a1470`。`15666` 为 node `38773`。该进程启动后，库里还没有新的项目智能体运行。最后一条 C02 运行 `2106096765173243906` 的 `create_time` 是 `2026-10-03 02:59:33`（库时钟，早于这次启动）。

405 的请求已经能从 mcp-core `0.17.2` 源码对上，它不是带帧那次 `INITIALIZE` 的根因。

1. `HttpClientStreamableHttpTransport.sendMessage` 在 POST 响应带上 `mcp-session-id` 后，调用 `reconnect(null).subscribe()`，另开一条 `GET`，`Accept: text/event-stream`。
2. `ResponseSubscribers` 的 SSE 行解析遇到不是 `data:` / `id:` / `event:` / `:` 的行，就 `sink.error(McpTransportException)`，文案是 `Invalid SSE response. Status code:` 加上状态码和该行。405 正文若是普通的 `Method not allowed`，会走这里。
3. 这条 GET 的 `onErrorComplete` 会先 `handleException`，再把错误吞掉。`LifecycleInitializer.handleException` 用 warn 打出 `Handling exception`。POST 的初始化、列工具和调用不走这条 GET。
4. 源码里 `statusCode == 405` 后「改走请求响应」的分支，只在已经发出 SSE 事件时才会执行。普通文本 405 在进这个分支之前就被行解析打成异常，所以日志里仍能看到 405，同一次工具调用仍可以成功。

带帧的失败是另一次运行。`2106092487658545153` 的 SOURCE seq 8 为 `FAILED` / `INITIALIZE` / `PROTOCOL_OR_TRANSPORT` / `IllegalStateException`，6 条帧是：

- `McpJsonInternal#lambda$createDefaultMapper$2:67`
- `McpJsonInternal#createDefaultMapper:62`
- `McpJsonInternal#getDefaultMapper:30`
- `McpJsonMapper#getDefault:97`
- `HttpClientStreamableHttpTransport$Builder#build:825`
- `ManagedMcpAsyncClient#streamableHttp:49`

`Builder.build` 在 `jsonMapper == null` 时调用 `McpJsonMapper.getDefault()`。`createDefaultMapper` 第 67 行把 ServiceLoader 初始化失败包成 `IllegalStateException`。这是客户端构造失败，发生在协议握手之前。当前已加载类的 `streamableHttp` 在 `build` 之前执行了 `JacksonMcpJsonMapperSupplier.get()` 并传入 `jsonMapper`；`create` 还传入了 `JacksonJsonSchemaValidatorSupplier`。该供应商直接 `new JacksonMcpJsonMapper(new ObjectMapper())`，不走 ServiceLoader。因此当前这个包的打开路径不会再进入上述 `getDefault` 抛错点。这不证明这个进程上已经有一次成功的真实人员运行。

同日更晚、但仍早于 `31351` 的两次运行，MCP 来源已经成功，完成门仍拒绝，没有产物：

| 运行 | MCP 来源 | 终态 |
|---|---|---|
| `2106095444625989634` | `REMOTE_APPLICATION` / `SUCCESS`，引用 17201 字 | `FAILED` / `COMPLETION_REJECTED` |
| `2106096765173243906` | seq 18 同样 `REMOTE_APPLICATION` / `SUCCESS`，引用 13513 字 | `FAILED` / `COMPLETION_REJECTED` |

这两条 ERROR 只有 `errorCode` 和固定文案「没有取得可交付正文、检索依据不足或结论越权，产物未生成」，没有 `completionReason`。不能从这条文案反推出是空正文、越权、来源身份还是数字对不上。H5 仍要等当前包上的 H4 回读通过。
