# G 新包真实再做质量准备

DRAFT_ONLY：实际脚本/源码已读，未登录、未出站、未创建运行、未定档。原runtime-acceptance与handoff2-context-v3-runtime只是参考，不作为现态输入。全部动作等主协调者确认新包加载后执行。

## 脚本风险与实际合同

runtime-acceptance-20261002.py仅Person登录+GET，PROJECT=9140005，不能直接替代本次项目。handoff2-context-v3-runtime-20261002.py硬编码项目2103659612308828162、PREVIOUS2106061095016882177、DOCUMENT2106061816428781569、base=DOCUMENT及固定idem。现再做必须fresh回读，不原样跑create。poll在nextSeq<=cursor时退出不证明终态；apply自动三并发且未先质检，不直接用。reject仍带旧根因，不能重复退回已经退回链头或把旧评语当当前质量结论。

现RunService.create先plan/validateRework后newRun。validateRework:712-729要求previous本人同项目同动作终态、target属于该previous已APPLIED产物，base只做ID语法校验。当前源没有创建前fresh base/status校验；AiDocumentService.reviseGeneratedAuthorized:286-299在apply时锁链头校base/project/docType/未归档。故创建前stale-base拒绝并非本报告已证合同；现权威是apply CAS，A需对原规格比对后决定是否增加创建前拒绝，不擅升级为缺陷。任何源码修复应回原shared writer，不由本质量准备改服务。

## 新包后最小请求序列

1. 先确认加载PID/不可变JAR SHA和组合包包含本轮B/C01/D/E/G产物；核项目智能体开关与本次Person会话。以真实本人GET本项目详情、agent-capabilities、agent-runs?actionCode=C02及文档列表/versions，不在证据打印token/密码/模型endpoint。
2. 定位本次明确待再做的已定档产物：从本人已结束运行与APPLIED artifact的documentId建立previousRunId→targetDocumentId。再GET该target的完整versions，按versionNo取当前head H，核project/docType/status/父链；baseVersionId=H.id。targetDocumentId保留属于previous产物的那一版ID，不能自动换成不属于previous的最新head。历史ID只能作查询锚点。确认H已退回且有真实reviewComment后才再做；若H已归档或已由其他人升级，停此请求并重新核链，不再写旧base。
3. 从当前capabilities选择market-research/v1、当前启用modelConfigId；C02 skill=competitor-analysis-ipd，C01只在另独立动作验证，不混进这次C02。toolIds严格来自本包当前available且与项目product_lines.mcp_service_id一致的选项；优先本次project_knowledge_search和明确选定MCP服务，不照抄旧FastGPT ID或展示名猜映射。冻结快照核name/version/hash等于本包classpath清单，不改DB技能绑定。
4. POST /projects/{P}/agent-runs，请求关联为fresh previous/target/base完整三元组，message引用当前退回意见（不植入工程AGENTS规约），idempotencyKey用本轮唯一8–64字符。示意：market-research/v1、C02、currentModel、[competitor-analysis-ipd]、currentSelectedToolIds、previousRunId=R、targetDocumentId=D、baseVersionId=H.id。同键同payload重发应同run；同键改变message应冲突且不多占位。
5. 轮询GET run及增量events，空页等待而非当终态，直到明确terminal或超时；过滤THINK/REASONING原文，不长期保存模型内部推理。核工具调用必须属于冻结选中列表；失败无权/部分成功/无结果区分，MCP失败不能计远端可引用命中；来源身份逐项回原库（项目REVIEWED/未删除文档、知识fragment/document/knowledge/当前附件名），不得把同名模板充已审核项目文档。
6. 独立质检artifact正文/sha：知识模板不是本项目事实，系统产品库不是全部“不允许引用”，别产品参数不套当前产品；缺客户、报价、竞品名单写未取得，不凑数字；不出现工程规约/假工具数量/展示名映射要求/动作完成或Gate已过。run成功也须人工正文裁决，不自动apply。质量失败时保留DRAFT/失败证据，不定档、不审批。
7. 质量过关才单次POST /agent-runs/{newRun}/artifacts/{artifactId}/apply。回读ai_documents新版本应同原链、parent=H.id、versionNo+1、GENERATED（用户待审核）、内容SHA等于本次artifact、模型与用量来自本run；APPLIED关联及deliverable只一份。再次同apply应同document无重复版本；三并发仅在此确定正例后单独验证幂等，不用失败稿制造档案。

## 最小拒绝边界

创建前现源码明确拒绝项需核：缺一个返工关联字段、previous他人/不同项目/不同动作/未结束、target不属于previous已定档产物、C03未列适用动作、其他包skill、不可用模型/未纳当前包tool。负例须回读run/artifact/doc/配额前后零新增；禁止以失败HTTP码代替DB零占位。

创建前陈旧base/归档head另作合同边界观察：当前源码没有前置校验；不得预设必须零run占位，不据此改服务或判创建缺陷。A先核原规格是否要求创建拒绝；本次正常再做始终使用fresh base避免无意义调用。apply阶段的锁链头CAS拒绝是现行权威。

apply阶段再次校fresh head，若其他请求已推进head，必须拒绝且文档、artifact APPLIED及deliverable零新增。用户已结束旧run不能原地返工，必须新run保留关联证据。业务文档审核/动作负责人批准/Gate签署均不由此验证自动执行。

原历史v3-business-review判DO_NOT_APPLY_OR_APPROVE，原因是模板被错标已审核而项目已审核文档为0，远端MCP失败。新包只有重新取得当前SOURCE/当前正文/原库身份后才可改变这个裁决。

## 已实现未运行脚本

仅新增codex-fresh-rework-runtime-20261002.py；复用既有accounts/login/redact。inventory/create均fresh读取prev/target链，并SELECT只读核HTTP与DB链头、previous身份及APPLIED关系；create本轮时间戳idem，需手工传新包SHA和现模型/tool。poll空页等待到terminal，记录SOURCE身份/citation摘要SHA、STEP MODEL_CALL用量、ARTIFACT contentHash，不保存思考或原始正文。apply独立模式须A质量裁决JSON(review er键实际reviewer=A、verdict=ALLOW_APPLY、runId/artifactId/contentSha256)，先核当前成功运行/产物事件hash，再单次apply；没有自动并发/重试。AST解析通过，未执行任何网络登录、出站、数据库查询或写入。
SHA256 2df417e01c79cf7da106426f7c78e2339cf5db647f7a3a06c8c3bcb3821e9c83

## 安全诊断包单轮 helper 参数扩展（只实现不运行）

六行：ruoyi-ai原证据helper；原create/inventory/poll/apply CLI；仅codex-fresh-rework-runtime-20261002.py；previousRunId/targetDocumentId/baseVersionId与SOURCE安全诊断；目标在新包加载后对当前待审核链头作一次既有单轨查询诊断；现源码及既有回读A级，本次无执行。

新增--previous-run-id/--target-document-id（默认仍原v2，参数十进制校验后才拼只读SQL）；fresh terminal/C02/project/APPLIED target关系与HTTP/DB当前head五字段一致仍保留。普通create仍要求REJECTED+真实退回意见，仅--diagnostic-only允许GENERATED或REJECTED。诊断create仅接受一个既有FastGPT-mcp服务编号，保留market-research/v1、C02、competitor-analysis-ipd、current head base与新timestamp幂等键。请求明确仅实际查询该服务一次「竞品」，只报告查询状态与资料缺失，不宣称动作/Gate完成；prompt约束不是强制工具次数闸门，A须回读SOURCE实际次数再认诊断有效。

脚本没有自动apply/review。--diagnostic-only与apply组合在登录前拒绝；普通apply仍须独立A质量裁决并手动artifact ID，这次诊断不授予该裁决。poll保留直至真实terminal/空页不结束，额外采集白名单reasonCode/mcpFailureStage/mcpFailureReason/mcpErrorTypes/mcpSdkFrames，以捕获新包真实throw site，不保存内部思考。前single/4parallel成功不证明历史失败修复。

后续A参数PREV2106082915170418689/TARGET2106085620907540481，inventory/create/poll需一致传--diagnostic-only；由A确认loaded新包sha及当前模型/唯一服务。before脚本SHA256 2df417e01c79cf7da106426f7c78e2339cf5db647f7a3a06c8c3bcb3821e9c83；after89a338f97abd3394d9084a2979613a86b3f7fc029e3059997ae91c69002e761b。AST parse通过；未导入执行helper、未登录/出站/创建run/DB写/生产源码/target。状态PENDING_RUNTIME_VALIDATION。
