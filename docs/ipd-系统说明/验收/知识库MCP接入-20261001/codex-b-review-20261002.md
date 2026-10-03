# B 分区独立复核 — 2026-10-02

裁决：PARTIAL。当前完成门有确定性误拒与漏拒，不能认可来源身份门已收口。未修改源码、配置、主 target、运行包、画布、镜像或 log。

## 范围与证据

已读 ai-native-sdlc 2.0.0、后端 AGENTS、cursor-task-1-result-20261002.md/json，以及现态源码和相对 HEAD 差异。完成门 SHA-256 与报告一致：c30c23477ee702626ce04093ad873a1ad7eef1ffa81e42d0bcf2a4be0184b89f。

独立验证用 JDK 17 javac 编译磁盘完成门原源码到临时目录，不依赖主 target/Maven。javac 与 java 退出码均为 0。
Probe 源码和 class 目录：`/var/folders/8l/6tnv8ssd2vs0h8463rnv66b00000gn/T/codex-b-gate-sry3aupu`，其中 `Probe.java` 是原始复现输入。

## 确定缺陷

1. P2：`ProjectAgentCompletionGate.java:169` 用 contains 对 documentId 前缀匹配。知识 ID `12`、正式 ID `123` 同时存在时，合法正文 `正式.md documentId=123 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED` 实测为 `COMPLETION_REJECTED`。
2. P2：`:172-173` 总取整行第一个 sourceType/reviewStatus。合法同一行两个来源：`正式.md documentId=123 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED；模板.md documentId=template-doc sourceType=KNOWLEDGE_FRAGMENT reviewStatus=NOT_PROJECT_DOCUMENT` 实测为 `COMPLETION_REJECTED`。
3. P1：同一个机制可漏掉后面的错误分类：`模板.md documentId=template-doc sourceType=KNOWLEDGE_FRAGMENT reviewStatus=NOT_PROJECT_DOCUMENT；模板.md documentId=template-doc sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED` 实测返回 null。整行已有正确知识标记又让 `specificallyDeniesReviewed` 跳过标题核查，后一个错误分类被放行。

三例共用真实 noteSource 结构（SUCCESS/hits=2、带知识和正式逐条 sourceEvidence）；知识行含 fragmentId。当前已提交给协调者，修复待共享写窗口确认。

## 向量来源认可范围及限制

认可：当前 scoped candidate SQL 限 tenant=0、当前项目库或 user_id=0 且未挂项目系统库；查询知识库 ID 是代码装配；实时附件 join 阻止无附件向量充当来源；故障正文与可引用 citationText 分离，失效向量正文不会进入失败说明。该认可仅为源码层，不证明运行包/HTTP/数据库或浏览器通过。

待补强：`ProjectKnowledgeFragmentMapper.java:96` 在 fragmentKey 为空时任取同文档片段并将主键作为 fragmentId；`ProjectKnowledgeVectorSearch.formatHit` 仍引用向量 row.content，未对照当前片段正文。向量策略现态通过 fid 构造 id，但旧/缺 fid 载荷及检索缓存仍不能用当前任意片段补身份；现单测 sourcedAndUnsourcedVectorsKeepOnlySourcedCitation 正在允许缺 id 的 known 行。建议拒绝空 fragmentKey，补同文档另片段不能补身份的案例。是否改引用为当前 content 需按合同核定，不在本次只读阶段擅改。

权限不夸大：向量 search 对有 personId 不重复走 owned/share 门禁，是为了与项目 scoped SQL 同语义；retrieval 服务可选桥会另装 sensitivity/scope profile。当前只认可来源候选范围未被本补丁扩大，未做 revoked-member 或运行时权限回归。

## 异步测试语义

AiDocEmbeddingServiceTest 全部在 setup 打开 completeEmbedOnCallerForTest。认可它解决调用未完成就断言的竞态，并验证两键缺失走内置/不解密对话密钥；它验证的是调用线程执行分支，不能据此宣称生产 executor 排队、并发、退出或审核变化期间的异步行为已验。未发现该测试开关在生产构造自动打开；没有运行主 target 测试。

## 最小修复与候选闭包

完成门最小修复只需 CompletionGate + CompletionGateTest：精确 token 解析 documentId；按每次引用的局部区间取字段，每条身份独立检查，不复用整行第一个字段；多条同 ID 也逐项验；标题兜底只在未给显式 ID 的引用区间使用。补上述三例及同名不同 ID、Markdown 表格、标签顺序交换正例。

B 候选依赖闭包需同步编译：ProjectAgentCompletionGate、ProjectKnowledgeSearchTool、ProjectKnowledgeVectorSearch、ProjectKnowledgeFragmentHit、ProjectKnowledgeFragmentTextSearch、ProjectKnowledgeRetriever、ProjectKnowledgeFragmentMapper、AiDocEmbeddingService、ProjectAgentConfiguration 以及相应单测。RetrievalContext 新增 citationText/sources 与构造兼容属于共享合同，不能只装 CompletionGate 单文件。AgentScopeProjectAgentKernel 原生 SOURCE 构造是另分区改动，须与协调者核对其调用 sourceEvidence 的现态；不能将其差异归 Cursor1。

未运行业务 HTTP/浏览器、未加载新包、未写数据库、未重跑 Maven。原报告 78/16 单测是执行者历史证据，本报告不将其冒认为本次独立测试。

## 授权实施追加

协调者确认 Cursor 主协调停止新增写入后，授权上述 B 精确路径独占修改。当前状态 PENDING_VALIDATION：已改源码与单测，未跑 Maven、未装包。

六行缺口：仓库 ruoyi-ai；入口完成门与知识向量核对；边界 B 已授权文件；字段 documentId/sourceType/reviewStatus 与 fid/id；目标精确身份及多条引用不串行、拒绝缺片段键；证据当前源码/隔离 javac、后续串行 Maven。

修改前 test 哈希：CompletionGateTest=2b999fbee17cd0e5fc0a6a8fe4365208c565faf6c26ee398689d94a5337962ce；VectorSearchTest=468925dd0e56513631d26736d64ee7f134a4693b583be9a5d9cbee7eff85120f。生产源码修改前哈希见原 Cursor JSON，本次现查一致。

完成门改按字段组逐条核对精确 documentId，重复字段开启下一条引用，分句不共享身份；兼容 sourceType/status 前置、中文全角分隔符。新增上述 3 反例与交换顺序/无分号相邻来源单测。隔离 ProbeExpanded.java 验证 7 个用例：field-order=null、adjacent-valid=null、adjacent-mislabel=COMPLETION_REJECTED、existing-natural-mislabel=COMPLETION_REJECTED、prefix-collision=null、valid-two-sources=null、mislabel-hidden-by-first-token=COMPLETION_REJECTED。javac/java 退出均 0，git diff --check 退出 0。

向量 A 级依据：WeaviateVectorStoreStrategy:378-390 读协议 fid 并作为 row.id；没有 fid 就没有该片段身份。VectorSearch 现拒绝 null/blank row.id，不查同文档其他片段；Mapper 删除空键任取片段分支并要求键非空。正确命中单测补真实片段 id，新增缺键不能调用实时 identity lookup 的负例。正文仍来自本次向量 payload；没有本刀证据证明向量正文和当前关系库正文版本一致，保留该限制，不声称已覆盖。未更改生产权限、配置或共享 Kernel。

修改后 SHA-256：
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGate.java`：`d0dc20beb85534c9a56c56df6c636b7041f1bf837266f5ac9bc2488b10b8a404`
- `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGateTest.java`：`911a9cc35420c2bc3870fcf6bb41db3c6b6eafe691bc6cb6b814566e61a0d091`
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeVectorSearch.java`：`3480af1f0b744bd7a225a3cd970af2ea9fcab01dc65961571f49afab952ce982`
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/ProjectKnowledgeFragmentMapper.java`：`044890db8be06badfa9110df542e1341ddf1f25584d744fa2422660569460943`
- `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeVectorSearchTest.java`：`b5558d71bb4d4121e6ce2f2fc0b48ad26075c777bcfb29972839b3b9e348c8c5`
