# AgentScope 官方能力落实与用户技能审核（2026-10-03）

裁决：部分闭环。已实现源码与界面接线并完成模块回归；未完成同候选运行验收及全部适用能力旅程，不能认定生产就绪。

## 用户裁决与实现

用户明确：“用户自己来审核”。发起该运行的真实 Person 可查看新技能全文、资源清单及摘要，选择“同意使用”或“不采用”。模型与工程助手不能代批；审核不能替代业务文档审核、动作批准和 Gate。

同意后经官方 2.0.3 SkillSecurityScanner、SkillPromoter 发布，全资源字节与摘要回读核对；只有本人当前项目后续运行可从同一技能目录选用。既有运行仍按原摘要恢复。候选、决定、发布成功或失败均记在原运行 STEP，不新增业务表，不改动作技能映射，不建第二运行链。发布失败明确不可用。

主要接线为 ProjectAgentController、ProjectAgentRunService、ProjectAgentSkillReviewService、ProjectAgentSkillBundle、ProjectAgentSkillPublisher、ProjectAgentSkillCatalog、FrozenProjectAgentSkills、ProjectAgentRunPlanner 及前端原运行右侧 skills-review-panel.vue。

## 本轮确认缺口修复

- 记忆抽取走统一计量模型；失败明确传播，记录等待持久化回执；暂停、失败及无顶层成功结果不抽取记忆。
- 已配置备用模型装配失败不再只告警后继续；缺字段明确失败。
- 副驾和文档生成检索故障不再冒充空命中；需求归属条件更新防覆盖。
- VERIFYING 取消和复核以真实行锁、版本重查及同事务保护；前端仍使用原运行 API。
- 官方 2.0.3 父 Harness Hook 已接线，纠正旧错误解释；没有混入 2.0.4 专有枚举。

## 已获得证据

- 新版 IPD 模块回归：4048 项，0 失败、0 错误、26 条件跳过。日志 /tmp/ipd-full-stack-ipd-all-v2-20261003.log。
- chat 模块既定回归范围：351 项通过，无跳过。日志 /tmp/ipd-full-stack-chat-all-20261003.log；此结果不覆盖后述新增自动发现测试。
- 前端：1945 通过、37 条件跳过；类型检查与构建通过。日志 /tmp/ipd-full-stack-fe-all-v2-20261003.log 及 /tmp/ipd-user-skill-review-fe-{type,build}-20261003.log。
- MySQL 独立连接证明同一行锁互斥、超时及回滚释放；没有写数据。不能以此替代业务服务事务回滚和 HTTP 验收。
- 能力防禁用静态检查：4 消费者、0 违例；计划入口检查及两仓 git diff --check 通过。静态检查不能证明所有能力可运行。

## 候选与未闭环项

固定候选：.codex/ipd-dev/backups/ruoyi-admin.codex-full-stack-20261003-14289e0848b6.jar。
SHA256：14289e0848b63e5065e73546a59e97c0199dc15fda5f6e1e4a1ad5096b89fac7。
2520 个冻结输入文件无漂移，11 个 AgentScope 库均为 2.0.3。对应原目录 source-manifest 与 candidate JSON。候选尚未由本轮加载。

先前主模块 target 与冻结候选有 55 个自动生成类字节差异。冻结全 reactor 测试尝试因 common-core 缺 JUnit engine 失败；直接候选测试第一次发现 4413 项，4361 成功、26 失败、26 跳过。失败包含源码定位工作目录问题及 8 个旧 MiniMax 凭据用法测试，原始日志 /tmp/ipd-full-stack-actual-candidate-tests-20261003.log 保留。正在分模块独立核定，不能把模块绿结果宣称为候选全绿。

本地 GET /api/v1/auth/me 使用现有探针会话返回 401，真实 Person 用户审核与浏览器旅程仍待验证。原安全加载脚本受 2 条 WAITING_APPROVAL 约束，不代用户批准、取消或清理以绕过窗口。另有 2 条 VERIFYING 仍待对账。

完整后续范围仍沿两份既有全技术栈审计及 R242、唯一总画布推进：同候选与运行一致性、全部崩溃点/外部效果恢复、通用 MCP、检索与事实质量、全能力真实验收、业务联合旅程、全项目及外围适用性。没有把局部绿测扩大为全项目已完成。

本轮未执行 Git 提交、推送、DDL、生产写入或业务批准；保留其他未提交工作。

## 最终实际候选测试补充（2026-10-03）

真实仓库各模块 cwd 运行冻结候选生产 JAR 与该模块冻结 test-classes，仅补对应模块原 Surefire 测试依赖。执行前 IPD 1273、chat 461 个源码文件与冻结副本逐文件摘要一致，未混入其他模块 test-classes。此前冻结副本缺少 docs/scripts/admin 配置等资料导致的定位失败，已由正确 cwd 消除；旧失败日志保留。

- IPD：发现 4048 项，4022 成功、0 失败、26 条件跳过、0 中止。原始日志 `/tmp/ipd-full-stack-actual-candidate-ruoyi-ipd-realmodulecwd-20261003.log`。JUnit 已完成，但原临时 launcher 成功分支缺少显式退出，非 daemon 后台线程使 JVM 留存；仅清理本轮已完成测试 JVM 后进程退出 143。不能将此记录写成进程正常退出 0。随后仅修复 `/tmp` helper 成功分支 `System.exit(0)` 并编译，继续 chat。
- chat：发现 365 项，357 成功、8 失败、无跳过或中止，进程退出 1。原始日志 `/tmp/ipd-full-stack-actual-candidate-ruoyi-chat-realmodulecwd-20261003.log`。8 项均为 `org.ruoyi.integration.MinimaxIntegrationTest`：旧测试将环境密钥原值直接 `setApiKey`，当前安全引用策略在模型调用前以 `Invalid model API key reference; reference is not allowlisted` 拒绝。未打印或清空凭据，未排除或修改失败测试，不以此证明 IPD MiniMax 出站故障。

两模块合计发现 4413 项：4379 成功、8 失败、26 跳过、0 中止。IPD 与原绿色 v2 日志的 501 个顶层类及 4048 项范围一致；chat 原绿色日志仅 351 项、77 个类，本次自动发现 365 项、80 个类，额外 `KernelFinalResponseTest`、`ManagedMcpAsyncClientTest`、`MinimaxIntegrationTest` 共 14 项（6 成功、8 失败）。原始 Maven 命令未保留，不能推造筛选原因，也不能把原绿色范围等同自动发现全范围通过。

实际候选测试结论仍为部分闭环，候选主状态保持 `BUILT_NOT_LOADED`。本轮没有加载候选，真实 Person 审核、HTTP/浏览器旅程及后台线程完整生命周期仍待验收；不能宣称全候选测试通过。


## 接续现场核验与智普备用配置（2026-10-03，本轮仍未全项目验收）

原探针401已通过本地真实Person登录恢复；HTTP读取本人运行和能力目录返回200/code0，他人运行保持不可见。当前加载包SHA256为a3ed503a39ae74a070285b3fc7459f213e5aa38ef8c50009ab2ac3c78bfd2e83，不能沿用原冻结候选的已加载结论。没有找到此前声称的“安全加载脚本受WAITING_APPROVAL约束”实现；实查启动脚本不含该守卫，不据此虚构审批阻塞或绕过审批。

用户指定官方智普GLM-5.3-Flash作备用。通过原模型更新接口修改既有备用记录，HTTP200/code0及数据库回读确认：主MiniMax-M3仍为唯一全局启用模型；原备用关联fallbackFor仍指向MiniMax-M3，备用provider为zhipu、model为glm-5.3-flash、官方基址为https://open.bigmodel.cn/api/paas/v4。未增加模型记录、未新增预算，密钥加密保存，原加密配置已私密备份，不在证据中包含凭据。备用记录全局未启用是保留主模型选择，不表示备用关联被关闭。

官方真实调用第一次因本机Python默认证书链缺失失败；使用certifi可信CA后，关闭思考的请求被官方400/1210拒绝。按官方GLM-5.3-Flash规则启用思考并使用low后，官方返回429/1113“余额不足或无可用资源包”。原始脱敏结果：/tmp/ipd-zhipu-direct-probe-20261003.json。因此未取得成功回复、工具调用、流式或真实故障切换证据；待用户补充对应账户API资源。不能用本地替身宣称通过。

本轮阶段模块回归：common-chat日志12项；chat原Maven范围359项全通过；IPD在后续恢复/模型冻结改动之前4055项、0失败错误、26跳过。以上是阶段源码测试，不覆盖最新全部修改或新候选。前端最新全测1946通过、37跳过，类型检查与构建退出0，未见TS诊断。日志：/tmp/ipd-continuation-{common-chat,chat,ipd,fe-type,fe-tests,fe-build}-20261003.log。仍需新冻结候选自动发现测试及加载后业务验证。

当前两个等待用户的官方中断运行：实际checkpoint、interrupt、冻结技能摘要、归档沙箱内容与收据相符，Redis租约不存在且状态无过期。两个待复核运行仍需事务与冷恢复核对。没有代替用户批准、取消或清理等待运行。旧运行未冻结完整模型参数，不能据当前模型配置推断其原模型参数没有变化。


### 接续首候选与加载保护

首接续候选冻结2528文件，SHA c06cf4819df563f835eb05230281b1a0b3f1e72de1d1af71fa1e3eed441e67c6，32模块package退出0；SDK11件全2.0.3。生产依赖全部从该JAR解出、对应冻结test-classes在真实模块cwd自动发现：chat365成功无跳过；IPD4079发现、4053成功、26条件跳过，均0失败/中止且正常退出0。原始日志/tmp/ipd-continuation-actual-candidate-ruoyi-{chat,ipd}-20261003.log。

加载保护在发送SIGTERM前检测当前RunHandle源码摘要漂移，断言拒绝加载；没有停止旧58551进程或启动第二实例。新的在途正文恢复修改保留，独立以当前实际源码复现空replace保留旧正文、重复满页重复拼接、冷回读失败只剩尾段仍成功三个阻断问题；/tmp/ipd-runhandle-independent-review-20261003/probe.log。该复现是替身store，不冒称真实DB故障验收。新修复与统一候选仍在验证，旧绿测试不覆盖它。

Harness初查只剩原沙箱guard测试RuntimeContext缺userId，补齐原测试身份后verify PASS，正反样本self-red PASS；没有放宽生产合同或静态检查。前端构建产物scrollbarRef声明明确Ref而非any（声明构建缓存命中），实际skills-review-panel bundle含本轮刷新失败保护文案。

### 续接：主备模型身份计量与完整 MCP 参数校验

主备配置 ID 和全部调用参数绑定进第二版冻结摘要，实际模型开始/结束事件及既有用量账本取服务端冻结身份；备用用量不再归到主模型。独立实际源码编译及36项专测通过，证据 `/tmp/ipd-model-identity-accounting-20261003/final-tests.log`。没有真实智普成功调用，不能将专测当余额或资源包恢复证据。

同一 MCP transport 的工具列表响应保留完整原始参数声明；查询前完整校验组合约束及本地引用，拒绝外部解析和声明摘要漂移。目录发现仅诊断，任意 toolName/arguments 被拒绝；只保留原单工具知识查询。40项独立回归通过，旧版4项缺陷复现均失败，证据 `/tmp/ipd-mcp-safe-slice-20261003/all-green.log`。通用调用授权、资源消费、每次运行持久冻结 schema 和 resources-only SDK 初始化限制仍未闭环。

上一候选实际 JAR 回归唯一失败是旧包外工具测试误把已合法的 web_search 当未知工具；修正为 unknown-project-tool，并新增原生工具不可用明确拒绝断言。新冻结候选 `/tmp/ipd-continuation-candidate-20261003-035104` 含2532项输入，正在构建；仍运行旧进程58551，此时不声明加载或业务验收通过。上一失败日志保留。

### 现场补充的根源回查

实际DB只读回查：run 2106296461246337026 属900103，VERIFYING v9，79条文本事件2749字符，草稿177字符；run 2106300308379406337 属900103，VERIFYING v2，157条文本事件797字符，草稿758字符。第二条原文明确仅提澄清问题、尚不产出报告。不是正文丢失同根因，不能靠重放修复；新补输出分类合同，保留原Verifier标准和原产物记录。完整回读私有证据 `/tmp/ipd-run7-run8-current-evidence-20261003.json`；未以当前900101权限操作二者。新候选chat367全通过，IPD4099中4070通过、3失败、26跳过，失败是模型身份计量签名/夹具接续待核，不声明全绿或加载。


### 2026-10-03 04:24 PDT：统一候选复验修复（未加载）

第五版候选 SHA-256 `3f73d636403b4c171702bf8f1c6928b30a35f6e52b3afdfc8c1025bb3fe17d41` 实际 JAR：聊天模块 367/367、公共聊天模块 12/12；IPD 4129 项中 4092 成功、11 失败、26 跳过，失败原始日志保留。22 项选定真实 MySQL/Redis 依赖测试通过，不覆盖全业务或故障恢复。

11 项已查证：恢复工具集合校验把服务端自动注入的澄清工具误当作客户端必须重报工具，生产窄修比较外部冻结集合，未知/重复工具及客户端伪造 schema 仍拒绝或覆盖为服务端定义；其余为可信 SOURCE 接口和文档输出合同变更后的旧测试夹具，保留原断言要求。独立使用第五版实际 JAR 分别复验 28、25、11 项全部通过。第六版 `/tmp/ipd-continuation-candidate-20261003-042214` 冻结 2536 个输入重新构建，尚未宣称全量通过或运行生效。

最新前端实际全量：1951 成功、37 跳过；强制类型检查及生产构建退出 0、未发现 TS 诊断。日志 `/tmp/ipd-output-contract-fe-tests-final-20261003.log`、`/tmp/ipd-output-contract-fe-type-free-text-20261003.log`、`/tmp/ipd-output-contract-fe-build-free-text-20261003.log`。原工程门禁和五组正反控均通过，不能替代运行或业务验收。

运行边界仍为旧 PID 58551 / 原 SHA `a3ed503a39ae74a070285b3fc7459f213e5aa38ef8c50009ab2ac3c78bfd2e83`，16039 单监听；run7/8 和原四个驻留记录未重写、未批准。准备的安全重载脚本尚未执行，要求源码无漂移、统一实际 JAR 全量无失败、22 项真实依赖通过、无正在执行运行及旧 PID/SHA 精确相符。


### 2026-10-03 04:28 PDT：统一实际包通过并首次加载，发现运行配置阻断

第六版 SHA `b28cec594c48d8a63f73c08c1439374c53fee0e6993c301569b40a7594706572`：367 聊天 + 4103 IPD + 12 公共聊天 = 4482 成功，26 跳过、零失败；同包真实依赖 22/22。日志在 `/tmp/ipd-continuation-candidate-20261003-042214/`。原 PID 58551 正常 SIGTERM 后等待 Spring 生命周期退出超过 30 秒保护窗，保护脚本拒绝启动新实例；旧进程实际退出后复查端口空、源码无漂移，启动不可变备份 JAR，新 PID 2477 单监听 16039，真实 Person 能力目录 HTTP200/code0，原四个驻留运行状态/版本/配置哈希未变。

首次真实普通问答 `2106345205031575554` 装配失败（BUILD / IpdBusinessException），未调用模型。原始运行日志明确 `host not in allowlist host=open.bigmodel.cn`：智普备用模型官方域名未纳入原本机出站域名列表，而已配置备用模型装配失败必须显式阻断。没有忽略备用模型或退回旧副驾。现只向本机 YAML 现有列表添加用户明确指定的官方域名 `open.bigmodel.cn`，原配置在私有目录备份，未改通用校验实现、未添加通配规则。重新加载同一已验证 JAR 中，尚未宣称真实问答通过。


### 2026-10-03 04:55 PDT 统一候选与真实暂停恢复

候选 c31d74bafc112965cec30d8b1a67029e130d91d049737a83d1492a5cae88cb7f 已加载 PID12735，仅127.0.0.1:16039；2538项源零漂移。实际包4491测试成功、26跳过、0失败；真实数据库/Redis22成功；前端1953成功37跳过，强制类型检查0。原暂停运行2106345905601953794在JVM重启后页面原回答入口提交既有用户选择GLM-5.3-Flash，原同次运行SUCCEEDED、83事件、0ARTIFACT。pauseSeq实际HTTP为数值52。证据/tmp/ipd-continuation-clarification-resume-proof-20261003.json。历史run7/8未改，业务审核/批准未执行。用户新追加全局漂移事项沿原总画布分区现核修复，不以审计摘要代当前证据；不删除.codex运行依赖与回滚包。


### 2026-10-03 Gate存量定义受控纠偏（本机实际完成）

当前Gate和逐项判定表0行已通过事务锁定核实，全部97原定义记录保存完整前像/后像后保留原ID和总行数。原错误零填充33只归档停用（不删除），原规范33恢复可见并逐字段与已owner确认DOC05对齐；事务提交后独立DB复查7/6/5/8/7要素和5/4/0/3/2否决，共33/14；未执行任何业务审批或签署。完整可恢复前后像及已执行proof在.codex/ipd-dev/backups/gate-definition-correction-20261003-{before,after,proof}.json（私有，不入库）。第一次守卫把驱动空tuple误比较空list而安全回滚，独立COUNT仍0后修len检查才执行；首次错误未写库。原HTTP列表额外暴露2draft+enabled1，实际35行，这不是33发布集合重复；已有评审适用集已过滤published，原列表同款遗漏正在补。当前源码超出已加载c31d包，不把新seed/权限代码称已加载。


### 2026-10-03 审计结论现核与守卫纠偏

旧报告“IPD prod静默零测试”不能直接沿用：原IPD pom已有groups override及failIfNoTests=true（2026-09-29原修复），当前实际JAR运行全4152发现，不仅Tag(dev)。本轮第一新包0beb实际3红保留，仅旧通知/状态守卫fixture缺合法本人绑定。进一步现核batch-04本人交接合同未要求同主组，现DB Person900103组900001在项目9140005主组9120002有在任MARKET_PM绑定，禁止把代办组长sameGroup套进本人交接；初版新增此限制尚未加载，正在窄撤并正反回归。相关私有A证据/tmp/ipd-handover-existing-membership-evidence-20261003.json。源、专项绿、统一包和已加载运行分别登记，不以audit自述裁决。

### 统一候选包加载与对象权限实际验收（2026-10-03 接续）
- 32 模块构建成功；实际 JAR 测试 4508 通过、26 跳过、0 失败；同包真实数据库/Redis 22 通过。2540 输入文件无漂移，已加载 PID 39389，SHA b612d4e999392d66280fd4e034385298c76370ec65e3a365de152ac2eb50c2e6；旧 c31d 包保留回滚。
- 普通真实登录后 GET gate-elements HTTP200/code0，33 项；启动种子新增0、跳过33。独立复核97行逐字段与纠偏后备份一致，历史/批准未改。
- 实际组长跨组奖金冻结与分配 HTTP403；实际市场PM对非成员项目发起移交 HTTP403。每次请求前后奖金行、移交数量、审计数量完全相同，未执行资金或角色批准。证据 /tmp/ipd-object-authorization-loaded-http-20261003.json。
- 前端现有全量1961通过/37跳过，类型检查与构建通过；随后五处异步生命周期根因修复正在实施，不能把该旧结果作为新修复验收。

### 前端异步生命周期与论文参考查证
五处异步资源修复原配置定向69/69；临时撤销护栏13目标失败，56保留通过，真实源码SHA未改。统一1978通过37跳过、强制type和build退出0，日志无TS诊断；Vite实际返回三处变更生产模块HTTP200。当前浏览器旧会话在后端重载后失效，页面显示“登录状态已变化，请重新操作”，不能将模块HTTP/组件测试当全页面旅程通过。
论文核查：EvoOntology https://arxiv.org/html/2609.15779v1 的主要结果六模型、分析子集四模型，归因/类型化补丁/配对门控及成本结果限定其基准。不是本项目增益证据，也不证明静态图必然无效或图结构必须替代当前IPD权威。参考用于原概念/映射/约束/证据的边界查证；未安装插件/新建库/新业务MCP/自动修改审批。全局agent设置按用户明确决定保持不动，描述超限警告不称已解除。

### 索引与计量统一包实际加载验收
候选278c766e7f7de808a5731af8e288786333422c186ed8d0e5a66d9f3d27748ba6已由59205加载；2540输入加载前0漂移，实际JAR4521通过26跳过0失败及真实MySQL/Redis22通过。旧77b859候选4141通过/1缺事务管理器测试bean装配失败原红保留，补原独立context夹具，不放宽生产必需依赖。
已审核版本2105450857645502465原接口真实维护：匿名401、外组项目不可见404（首helper误预期403，独立证实缓存不变后修测试期望）；首次授权重建503，gateway日志未保留真实cause，只可证cache完整保留。相同原文直接native/api/embed200/1024/1.28s，实际SDK短文本1024/1.48s；据新诊断原接口一次重试200，单片真实1024模型qwen3-embedding:0.6b，原文档整行/版本/审核状态/总数不变。私有完整备份reviewed-document-index-before/after-20261003.json可恢复，不重审核、不新版本。首次45秒故障不能断言TCCL或SDK默认重试；同步publisher时限已独立发现确定性缺口继续修。
wiki70文件94内部md链接零断链，37路径改动，48raw快照SHA不变；反探针恰1断链报失败。
官方后台维护实际新测试2项发现1红1绿：LocalFilesystem文件marker齐全；原官方Sandbox archive会话成功但日记忆未落档，quiescence=true不等于成功。修生命周期及实际文件验收继续，不能以旧全量绿宣称此能力已验。

---

## 2026-10-03 07:20 后台记忆超时根因修复（续接 R242）

> **运行态身份现查于 2026-10-03T14:50:16-07:00**：PID `18680`，
> 包 `ruoyi-admin.codex-memoryfix2-20261003-1955ddf247fe.jar`，
> SHA-256 `1955ddf247fe341be533aefb087d7811964afa987a6f01afadc097b0dfbadb46`，
> 回滚包 `ruoyi-admin.codex-memoryfix-20261003-b47e52eed7eb.jar`。
> 同日 07:27 的 59285 / b47e52eed7eb（空闲期限 5s 版）已被本包取代，理由见文末「自我修正」。
> **本文上文各段出现的历史 PID / SHA 是当时观测值，不是当前事实**（24423/1cf3c734 已被本次换包取代；
> 更早的 58551/2477/12735/39389/50240/59205 早已退出）。

### 一、被修复的真实失败（runId 2106378468009717761）

日志逐秒还原（`/tmp/ipd-continuation-runtime-20261003.log`，已剥 ANSI）：

| 时刻 | 事实 | 证据 |
|---|---|---|
| 21:39:02 | 主模型调用 `9d811210` **COMPLETE** | 事件 `MODEL_CALL phase=END outcome=COMPLETE inputTokens=11192` |
| 21:39:02 | 答案「运行验收连接正常。」**已推送并落库** | `TEXT_DELTA` seq=11/13/15/17/19 + `TEXT_MESSAGE_END` |
| 21:39:02 | 收尾并发发起两个后台模型调用 `5c972682`、`93ae5117`（inputTokens 均 394） | 两条 `MODEL_CALL phase=START` |
| 21:39:12 | `5c972682` COMPLETE（10 秒） | — |
| 21:39:32 | `93ae5117` **CANCELLED**（30 秒期限到） | `MODEL_CALL phase=END outcome=CANCELLED` |
| 21:39:32 | 整轮改判 **FAILED / STREAM_ERROR**，对外文案「模型输出中断，请稍后重试」 | `ERROR {"message":"模型输出中断，请稍后重试","errorCode":"STREAM_ERROR"}` |

对照组（SUCCEEDED 的 2106345905601953794 第二轮）同样并发两个后台调用，各耗时 9–10 秒全部完成。
**同代码、同并发、同提示长度，结果不同 → 挂起是偶发传输停顿，不是记忆代码的确定性缺陷。**

### 二、四个被证实并修复的缺陷

1. **后台副作用绑架业务终态**（机械位置：`ProjectAgentLongTermMemoryMiddleware:70-71` 的 `concatWith`）
   记忆写入的 Mono 被直接接在主事件流尾部，失败即冒泡 → 整轮 FAILED。改为错误不逃逸，
   写 `MEMORY_RECEIPT` 持久回执（status / errorType / retryable / extracted / saved）。

2. **错误分类只看顶层类型**（`AgentScopeProjectAgentKernel.error()`）
   `err instanceof TimeoutException` 判据不穿 cause 链。真实的记忆超时已被 `onErrorMap`
   包成 `IllegalStateException`，落 else 分支 → 误报「模型输出中断」。
   脱敏包装的目的是对外文案不泄密，不能反过来把原因也一起掩盖。
   改为 `classifyStreamFailure()` 走完整异常链，日志增打 `rootErrorType`。

3. **无空闲期限，30 秒空转才发现**（`ProjectScopedLongTermMemory`）
   javap 实测 reactor-core 3.8.7：`Flux.timeout(Duration)` 本身就是「两片之间最长间隔」，
   每来一片自动重置。旧值 30 秒。改为 5 秒，并加**有限 3 次重试**；
   仅对 TimeoutException / IOException / HttpTimeoutException 重试——
   认证与额度类确定性失败重试只会把同一失败打三遍并推迟回执。

4. **排空预算与健康耗时完全重合**（`ProjectAgentBackgroundMemoryLifecycle.drain()`）
   按 modelCallId 精确配对 START/END 统计的 9 次成功后台调用：
   **3/3/4/5/5/5/9/10/10 秒**，其中 2 次 ≥ 10 秒，而预算正好 10 秒。
   提到 30 秒。**不放宽校验**——这道门禁守的是归档完整性
   （`verifyArchive` 逐字节比对会话与上传物），放松会发布坏归档。

### 三、SDK 2.0.3 实测（javap，非 2.0.4 源码）

- 官方 `StaticLongTermMemoryHook.handlePostCall` **自身就用 `onErrorResume` 吞掉 `record` 失败**，
  异步模式下还在专用调度器 `long-term-memory-record` 上订阅、主流不等它。
  **让记忆失败致命的是本仓自己的中间件绕过了 SDK 这层隔离，不是 AgentScope 的设计。**
- `MemoryBackgroundTasks` / `SessionTree` 的 await 是**进程级静态计数器**，无 per-run 句柄；
  `awaitQuiescence` 返回 false 时 SDK 只返回布尔，唯一调用点 `HarnessAgent.close()` **直接丢弃返回值**。
  是本仓的 `drain()` 主动检查并抛错。
- 2.0.3 与 2.0.4-SNAPSHOT 在这块**无差异**。
- 2.0.3 无「后台任务失败是否致命」的配置开关，非致命是硬编码。

### 四、真实服务验证（新包 PID 59285 上跑通）

runId `2106391297689497602`，正常 Person 会话，非默认 admin 身份：

```
seq 11-19  TEXT_DELTA      答案推送给用户
seq 28     MEMORY_RECEIPT  {"saved":1,"status":"WRITTEN","extracted":1,"retryable":false}
seq 30     RUN_FINISHED    {"status":"SUCCEEDED"}
```

`ipd_agent_memory` 实际入库 1 行（PREFERENCE，带 source_digest 去重摘要）。
**回执落在答案之后、终态之前——这正是修复要保证的次序。**

### 五、自证能红

把 `classifyStreamFailure` 临时还原为旧的一行判据后重跑：
`wrappedTimeoutIsClassifiedAsRunTimeout`、`deeplyWrappedTimeoutIsClassifiedAsRunTimeout` **两条确定性失败**；
恢复修复后 31/31 全绿。

### 六、真依赖层（默认被跳过的 26 项）

`mvn -pl ruoyi-modules/ruoyi-ipd test` 默认 26 项跳过，
全部是「需显式开关的真基础设施测试」：24 项真 MySQL + 2 项真 Redis 所有权。
**「4212 全绿」并不包含真库真锁层。** 打开开关后：4213 项、0 失败、0 跳过、
P131 真库 20 项通过、Redis 所有权 2 项通过、
Qa04 5 项因 `qa04_runner` 口令在本仓私有配置中无记录而 `Access denied`（重置口令属未授权 DDL，未执行）。

### 七、仍未闭环（不得宣称生产就绪）

- 记忆写入**失败**路径未在真实服务中自然复现（挂起偶发），该路径目前只有确定性测试覆盖。
- SDK 2.0.3 无 per-run 后台等待句柄，**每运行后台资源并发隔离仍未解决**。
- `ProjectAgentRunOwnership.held()` 内 `join()` 无超时，Redis 往返卡住会无限阻塞（本轮未修，非本次病因）。
- Qa04 5 项真库测试受阻于口令缺失。
- 全业务动作 / 六阶段 / 官方完整能力的端到端验收未做。

---

## 2026-10-03 07:50 自我修正：记忆流空闲期限依据不足

**问题**：初版把空闲期限设成 5 秒，依据是「健康调用的耗时几乎全花在持续吐字上，两片之间不会空这么久」。
换包后在真实服务上连续跑 3 次，后台模型调用实测 **3/9/14、5/7/11、3/8/10 秒，最长 14 秒**，
且 `extraction retry` 计数为 0（5 秒期限一次都没被触发）。

**但这些是「总耗时」，不是「片间间隔」，更不是「首 token 延迟」。**
Reactor 的 `Flux.timeout(Duration)` 对**第一片**同样生效，而抽取提示词很短、
首 token 完全可能超过 5 秒。那样会掐断一次**健康**调用——比原缺陷更隐蔽：
原缺陷是整轮误判失败且文案说反；误杀只会表现为少写一条记忆，安静发生、无告警。

**这是靠「设参数时没有对应实测」引入的风险，靠实测发现，不是靠推理。**

**修正**：空闲期限 5s → **10s**；重试 3 次 → **1 次**；兜底总期限 20s → **25s**。
挂死检测仍从 30 秒降到 10 秒（快 3 倍），同时对健康调用留出余量。
最坏耗时 2×10s + 1×0.5s = 20.5s < 25s，不变式成立。

**顺带修掉一个二阶 bug**：Reactor `Retry.fixedDelay(n, …)` 的 n 是**重试次数**，
总调用数 = n + 1。按「总尝试次数」理解会把最坏耗时算成 20.5s、实际是 31s，
超出兜底总期限被中途截断。已把常量改名为 `EXTRACT_RETRIES` 并在注释里写死该语义，
测试同步把期望调用数从 4 改为 2。

**修正后验证**：全模块 4212 通过 / 0 失败 / 26 跳过；重出包换包后
runId `2106398396146348034`、`2106398465771794433` 均 SUCCEEDED 且回执落库，`extraction retry` 计数 0。
