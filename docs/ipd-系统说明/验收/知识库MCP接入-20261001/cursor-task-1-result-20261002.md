PENDING_VALIDATION。来源身份和完成门的单测已通过；127.0.0.1:16039 仍是旧包，业务页面还没生效。

## 六行缺口

| 项 | 内容 |
|---|---|
| 仓库 | ruoyi-ai。前端 HEAD 未改。 |
| 入口 | `ProjectAgentCompletionGate.reject`、`ProjectKnowledgeVectorSearch.search`、`AiDocEmbeddingService.embedAsync` |
| 服务边界 | 项目智能体完成门和项目资料检索。未改 ProductLineMcpQuery / ProductLineMcpTool。 |
| 表和字段 | `knowledge_fragment.id/fid/doc_id/knowledge_id`，`knowledge_attach.name/doc_id`。这两张表没有 `del_flag`。项目文档仍以当前 `REVIEWED` 且未逻辑删除的 `ai_documents` 为准。 |
| 现状与目标 | 同名文件按 documentId 区分；「评审通过后才能…」不再误拒。向量出处回查当前附件。配置 `{}` 走内置向量，只填一键才关闭。 |
| 证据等级 | A：单测与只读库计数。运行包未加载，不能写成已生效。 |

## 修了什么

1. 完成门不再把来源压成标题集合。`documentId=template-doc` 被写成 `PROJECT_DOCUMENT/REVIEWED` 会拒绝；同名的 `documentId=123` 仍可放行。「不是完整版本但来自项目已审核文档」这种无关否定也会拒绝。「评审通过后才能进入下一阶段」放行；「但评审通过」仍拒绝。
2. 向量命中必须经 `findLiveFragment` 对上当前片段和附件名称。对不上就记「原始出处缺失或已失效」，不把知识库 ID 写成文档号或来源名，也不把命中正文抄进失败说明。
3. `embedAsync`：两键都空走内置 `qwen3-embedding:0.6b` 且不解密对话密钥；只填一键返回 null。单测改为在调用线程做完再断言。

## SOURCE 字段合同（给任务 5）

`sourceEvidence` 每一项：

| 字段 | 知识库片段 | 已审核项目文档 |
|---|---|---|
| sourceType | `KNOWLEDGE_FRAGMENT` | `PROJECT_DOCUMENT` |
| documentId | 当前 `knowledge_fragment.doc_id` | `ai_documents.id` 的十进制字符串 |
| knowledgeId | 当前 `knowledge_id` 字符串 | null |
| fragmentId | 当前片段主键字符串，必填 | null |
| sourceName | 当前附件 `knowledge_attach.name` | 文档标题 |
| reviewStatus | `NOT_PROJECT_DOCUMENT` | `REVIEWED` |

知识库行缺 documentId、knowledgeId、fragmentId、sourceName，或 reviewStatus 为 `REVIEWED` 时，不进入 `sourceEvidence`。完成门只认带 hits 且 retrievalStatus 为 `SUCCESS` 或 `PARTIAL` 的事件。

## 验证

- 2026-10-02T10:26:32-07:00，仓库根，退出码 0。`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dproject.build.directory=/tmp/cursor-task1-target -Dtest=ProjectAgentCompletionGateTest,AiDocEmbeddingServiceTest,ProjectKnowledgeVectorSearchTest,ProjectKnowledgeFragmentTextSearchTest,ProjectKnowledgeSearchToolTest,ProjectKnowledgeRetrieverFailureTest,AgentScopeProjectAgentKernelTest test`。Tests run: 78，Failures: 0，Errors: 0，Skipped: 0。其中 `AiDocEmbeddingServiceTest` 19，`AgentScopeProjectAgentKernelTest` 11。
- 2026-10-02T10:28:32-07:00，同一模块只跑 `ProjectAgentCompletionGateTest`，退出码 0。Tests run: 16，Failures: 0。
- `-Dproject.build.directory` 没有生效。两次日志都是 `Compiling ... to target/classes`，`/tmp/cursor-task1-target` 不存在。class 写进了主树 `ruoyi-modules/ruoyi-ipd/target`。没有 `clean`，没有杀进程。
- 只读 `ipd_dev`：`catalog-sample-10pct` 片段 84，附件 0。没有插入或删除。
- 改前 `embedAsyncSkipsWhenRagOff` 的 Surefire 原文没有留下。现行合同是 `AiDocEmbeddingService` 类注释：两键全缺走内置，只填一键关闭。

## 未验证

- 新包没有加载到 127.0.0.1:16039。项目 2103659612308828162、文档 2106061816428781569、运行 2106064774604263425 没有用这次代码再跑。
- 没有浏览器验收，没有提交，没有推送，没有改总画布。

## 交集

`ProjectAgentConfiguration.java` 只加了构造参数 `fragmentMapper::findLiveFragment`。相对 HEAD 的 diff 只有这一处。

未改 `ProductLineMcpQuery.java`、`ProductLineMcpTool.java`、`AgentScopeProjectAgentKernel.java`。工作树里这三份相对 HEAD 的差异是其他分区已有补丁。

相对 HEAD 的 `git diff --stat` 含接续补丁，不只是这一刀：上述 10 个本分区文件合计约 +740/−44。本刀之后 `ProjectAgentCompletionGate.java` 的 SHA-256 是 `c30c23477ee702626ce04093ad873a1ad7eef1ffa81e42d0bcf2a4be0184b89f`。

HEAD 现查：后端 `29b64b97c1729335abe22955b06a7597d68a9fab`，前端 `2797223d4c68490e9eeb9b4c75a718f937ce3c3a`。
