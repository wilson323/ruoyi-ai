# E 租约补丁独立 Validator

裁决：`PARTIAL`。从实际源码认可租约闭包、fresh终态拒绝、成功通知失败不降态和Zombie锁/CAS；失败通知异常阻断FAIL写入仍是实质缺口。时间：2026-10-02T17:54:27.601756+00:00。独立复核读原 `codex-e-lease-patch-20261002.md` 仅作为定位，以下结论直接绑定源码。未写源码、target、配置，未执行Maven；A正在全量验证。

## 认可

- Engine.run外层获取一次RunLease，输入准备与Plan.executeUnderLease、SUCCESS updateOutput、catch的FAIL路径在同一try-with-resources内。catch先requireHeld；未取得或检测已丢锁走reportUnownedFailure，只影响本次连接，不改原runtime/node。
- Engine.resume拿锁后fresh读getByUuidForResume，核id/workflowId以及DOING/FAIL；旧FAIL快照指向fresh SUCCESS时返回。Starter的预读筛选是提前优化，Engine锁内fresh才承担并发边界。
- SUCCESS持久调用后完成通知单独catch RuntimeException，仅warn，外层不会因此降FAIL。现单测直接私有exe+静态通知失败mock覆盖此正反边界，不证明数据库已经持久SUCCESS。
- Plan保留execute自行获锁合同，Engine使用executeUnderLease不二次GET_LOCK；节点开始、afterNode持久回调与checkpoint save前均requireHeld；未知副作用inFlight仍拒自动重放。
- RuntimeService僵尸处置@Resource注入JdbcCheckpointSaver，与Starter注入和两处Engine构造一致。每个候选获取同一runtimeUuid锁，fresh核DOING/删除/超时后按id/uuid/status/updateTime/isDeleted CAS，disposed累计实际行数。busy捕获跳过，无直接updateById。
- JdbcCheckpointSaver生产构造@Autowired注入DataSource，MySQL GET_LOCK连接与IS_USED_LOCK/CONNECTION_ID核验绑定。单参数无DataSource构造仅测试JVM信号量，不代表跨JVM锁证据。

## 实质失败路径（P1，已通知协调者）

`WorkflowEngine.errorWhenExe:152-165` 当前顺序：取模板 → saveWorkflowMessage → sendErrorAndComplete → updateStatus(FAIL)。模板 `WorkflowMessageUtil.getNodeMessageTemplate` 会调用ConfigService；sendErrorAndComplete最终 `delSseRequesting` 直接Redis.delete，没有catch。因此配置/Redis异常可在仍持租约且业务已失败时跳过FAIL写入。异常逃到外层reportUnownedFailure，产生「所有权无法继续」描述，原DOING可能遗留。新成功通知best-effort保护没有对称覆盖该失败出口。

最小修复建议：租约有效时先以不依赖模板/连接通知的安全基础错误描述落FAIL；随后模板/消息/通知独立best effort，并保留原业务异常。若状态写自身失败，不冒充成功落FAIL，记录真实原因。需writer安排，不在A全量期间改源码。

必要回归：业务runner或输入准备失败且通知抛异常时仍写一次FAIL；模板查配置抛异常同样落FAIL；失锁后绝不写FAIL/持久消息；通知失败不能触发第二次终态写。当前LeaseBoundary 4项仅覆盖busy、fresh SUCCESS、成功通知失败、准备前失锁，尚无上述失败出口正例和失败诊断不掩盖原因负例。

## 仍未证明的并发边界与测试遗漏

- 执行期间锁连接死亡：Plan进入runner前有requireHeld，runner内runNode异步/流式输出callback仍直接updateOutput；检测失锁后afterNode/checkpoint可拒绝，但不能阻止已开始节点的回调/外部效果。原报告已准确披露MySQL advisory lock非原子fence，此复核不升级为完全防晚写。
- RuntimeService.updateStatus/updateOutput未校验updateById影响行数。若行消失或写0行，Engine成功状态的内存设置与现单测mock不能证明真实终态持久。必须由运行态回读验收，不能以方法返回代替保存成功。
- Zombie fake已支持条件update并通过条件parser，但现4项没有候选→fresh期间变SUCCESS/刷新时间、或fresh→CAS期间版本条件变化而返回0的竞态负例。补这些场景才能证明新增fresh/CAS防错逻辑真的会红；真实跨JVMbusy探针仍必要。
- 租约取得后准备失败但仍持锁应落FAIL的正例，最后节点完成至SUCCESS写前失锁零终态写，RunLease.close失败后不降成功，同需针对验证。不能把busy零交互负例扩成所有执行路径。
- 这4类业务工作流文件不新建AgentScope客户端/第二Harness或第二MCP调用口；本刀是在既有业务DAG执行器上扩大租约边界，不是AgentScope SDK升级或服务切换。

## 读取时SHA-256

- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowEngine.java`：`912bc3cbc8311ad773a2b5265820a49dd0a4e10b7488b83d695ea08011ce0c14`
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowExecutionPlan.java`：`92e49240d208040e183a75663566c8f800b7add9bc9113b5c95ea472e4fb5e02`
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowStarter.java`：`f5c986bfc4f413d5c62e0c219e93091741c04a99c22a0ba2d230a9bb556be6bb`
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/service/WorkflowRuntimeService.java`：`668bd29b944676055679c6a88da806ef278e9aa48561263be7254ee19ca728eb`
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/checkpoint/JdbcCheckpointSaver.java`：`e73b9769172fdf777cade148cd7bb64fe7bdaeaba2915cb507ed5ce0dc15f2af`
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/test/java/org/ruoyi/workflow/workflow/WorkflowEngineLeaseBoundaryTest.java`：`81cd70137c4fefe04ac2d5e0b85bae8ac4a2df148f3e7bb7f861053483247ac1`
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/test/java/org/ruoyi/workflow/service/WorkflowRuntimeZombieDisposeTest.java`：`f4ddc8bf1a0c4bc450e79fc489786e5ae84ad8be33d7d55dbcc74bf15fdd6738`

未宣称测试已经执行通过；全量Maven结果应由A绑定以上哈希或解释后续漂移。

## G 冻结后的失败出口修复独立复核

时间：2026-10-02T17:57:21.392377+00:00。前文P1是修复前发现，本节复核当前源码；该P1在源码层已处理，测试执行仍待A。

`errorWhenExe` 现先从原业务异常生成基础错误描述，再调用 `updateStatus(FAIL)`，之后模板、saveWorkflowMessage、sendErrorAndComplete分别独立catch RuntimeException。模板查配置、消息或Redis/连接通知故障不会再阻断前面的FAIL调用；原业务错误不被通知异常覆盖。状态写自身失败仍会外抛，不把它吞成已持久失败。

run与resume两个调用口仍在同一RunLease try内，并且catch在进入errorWhenExe前requireHeld；该private方法没有新增绕过租约的生产调用者。未拿锁或失锁的原分支不调用它，也不写runtime/node。这次修复没有改变fresh状态门、成功通知隔离、Plan执行、Zombie CAS或SDK路径。

新增测试 `failurePersistsBeforeTemplateOrMessageOrConnectionFailure` 在模板mock被调用时先verify原始FAIL updateStatus已发生，再令模板、保存消息、通知连续失败；最终verify一次状态写和零其他runtime交互。它能使原先「模板失败阻断FAIL」实现失败，保留原业务文本，覆盖本次修复的真实顺序。直接私有方法mock测试不证明调用者有租约，也不证明数据库已落FAIL；租约边界仍由run/resume源码和原busy/lost-lease测试分开覆盖。

验证：只读代码、调用口及新增测试；git diff --check退出0。未执行Maven、不写target，未宣称测试已运行。此前advisory lock非原子fence、runner内部晚回调及updateById行数未校验的限制仍保留，不因本次修复升级完成裁决。

冻结读取哈希：
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowEngine.java`：`6199293036001af05f48be3e62f299035f40c98f9be2f01c2ac75ccb01a62815`
- `/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-aiflow/src/test/java/org/ruoyi/workflow/workflow/WorkflowEngineLeaseBoundaryTest.java`：`e35ff3a3113cbebf603cf2191a90bccc41dfc02d092a4a77f300f7037ee5697a`

## 跨 JVM Engine 探针独立验收

时间：2026-10-02T18:07:06.015240+00:00。裁决：Start/End QA竞争与恢复切片闭环；全项目生产恢复仍PARTIAL。读取codex-e-engine-two-jvm-competition-20261002.json与两份Java，未重跑写probe/Maven/target。

- 独立SHA核验7ddc60b6125a备份JAR与JSON完整哈希一致；内部ruoyi-aiflow包、临时lib提取包均与aiflowJarSha256一致；Engine/CheckpointSaver/Plan/RuntimeService四个class与loadedClassHashes一致；两份Java与sourceHashes一致。这证明候选包身份，未自行断言HTTP进程已加载或反射运行全部Spring wiring。
- CompetitionProbe使用生产WorkflowEngine和实际WorkflowRuntimeService/NodeService，baseMapper绑定真实MyBatis mapper、SqlSession autocommit。仅ConfigService提供null模板测试bean；未替换Engine、Plan或MySQL RunLease。owner的acquireRun先super取得真实MySQL锁，打印ENGINE_OWNER_HELD并阻塞stdin，然后才把lease返回Engine；contender日志显示GET_LOCK竞争失败且wfState未创建。
- before/fingerprint和after读取runtime/node/checkpoint完整列并长度编码hash，contender与terminal均assert相等、退出0。因此是这些表当前fixture行零变更切片；未包含消息/审计/其他表，也不是所有数据库零写的全局断言。
- owner_release退出0且打印ENGINE_RECOVERY_SUCCEEDED；第二owner被终止退出143，随后独立recover退出0成功。owner是在取得锁、进入Engine准备之前暂停，不是「节点执行途中崩溃」或「失锁僵尸晚写」验证。准备阶段另用FaultSaver在B节点输出commit后故障注入产生已有FAIL/checkpoint，再恢复。
- 只读MySQL抽查两个UUID：runtime304/305均status3、is_deleted0、定义is_enable0，runtime输出SHA均ae4d7b17f543c60c42b1ff53bdcde540b42babcc49eba12bbb14b615802b032f，分别4节点行/9checkpoint行。节点SHA与JSON最终回读一致，均status3。每个B有2个执行行，这是Start/End读计算节点在输出已写但checkpoint失败后重放；此样本不能证明外部副作用exactly-once。

独立回读的首次第二SELECT误写workflow_node_id，MySQL1054；第一SELECT已成功取得runtime状态。读拥有实体@TableField核实列名node_id后只读纠正查询退出0，未隐藏该诊断，也没有任何DB写操作。

认可限定：真实MySQL、两JVM、production Engine+Plan+repo的busy拒绝零fixture写、释放/进程退出后恢复、终态重复恢复零fixture写、Start/End结果落库。没有SSE/聊天session，saveWorkflowMessage现TODO；fixture用户0且在内存将停用QA定义设为可执行，不走HTTP业务授权，因此不是实际用户业务流程验收。没有模型/tool/邮件/外部效果、原子fence、节点途中owner死亡、无幂等外部重放或完整Spring容器启动证据。该结果不能扩大为全项目生产就绪。
