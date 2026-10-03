# E 运行写权独立复核

裁决：未闭环（PARTIAL）。数据库执行锁机制本身存在，但其生产覆盖范围不足。只读现态源码与测试；未修改源码、target、配置、服务或数据库，未执行双JVM实验。

## 原生产路径证据

1. WorkflowStarter.resumeRuntime:121-143 先按uuid取实例后直接调用Engine.resume，没有SUCCESS/终态筛除。
2. WorkflowEngine.resume:253-256 重建状态/已完成节点后调用runtimeService.updateStatus(DOING)，此时尚未到app.execute/acquireRun。
3. WorkflowExecutionPlan.execute:84-88 的try-with-resources仅覆盖executeLocked，返回前关闭RunLease。WorkflowEngine.exe:111-114随后才设SUCCESS并updateOutput，业务终态写在锁释放后。并且Engine随后saveWorkflowMessage仍是库写。
4. Engine.run/resume的catch统一调用errorWhenExe；:137-148调用saveWorkflowMessage和updateStatus(FAIL)。GET_LOCK失败的非持有者也走此catch，能将仍在执行的活实例标FAIL，更新remark/message。RuntimeService.updateStatus:96-108没有expected-status/CAS或lease参数；updateOutput:78-93同样普通updateById。
5. JdbcCheckpointSaver.acquireRun:42-71 用独立Connection的GET_LOCK(database+uuid哈希)持锁；requireHeld检查IS_USED_LOCK是否等于CONNECTION_ID，close释放并关闭连接。生产构造必需DataSource；单参数构造走进程内TEST_LOCKS。不能把单JVM Semaphore测试称双JVM验收。
6. Plan.executeLocked:99-122验证恢复checkpoint，但没有业务实例终态条件；终点checkpoint恢复时已完成集合覆盖节点，可跳过所有runner后再保存END并返回，Engine重复写成功及结束消息。START/恢复业务状态约束必须在持锁后fresh runtime回读处校验，不能只按传入runtime快照。
7. 额外独立发现：RuntimeService.failZombieDoingRuntimes:130-140按更新时间筛DOING，直接updateStatus(FAIL)，不持同一run锁；其注释允许重启Duration.ZERO。双副本或长节点期间运维调用会把活持锁实例当僵尸。搜索未见当前自动启动调用，因此不宣称已发生误杀，但公共生产方法具备此风险。

## 最小修复建议（待协调者授权实施）

将既有RunLease所有权提升到Engine运行/恢复业务编排外层，不新建锁表/运行store。Plan提供接受已持有lease的执行入口，保留原execute兼容单测并避免Engine内重复GET_LOCK；Node/检查点已有requireHeld继续保留。

Engine恢复流程：获取lease → fresh runtime回读及验证可恢复状态/未删除 → 构建与恢复上下文 → DOING写 → Plan节点/检查点 → requireHeld → SUCCESS/output/结束消息提交 → 释放。首次run的新runtime创建可先发生，updateInput/DOING及后续业务写都应进入其lease范围。

获取锁失败、终态恢复拒绝、丢失所有权均只能返回错误给当前请求/日志，不能写共享runtime状态、node、checkpoint、业务消息。执行者内部失败仅在仍持锁且未成功提交终态时写FAIL及业务错误消息；完成之后的SSE发送或release异常不能把SUCCESS覆写FAIL。不能只加一个catch按报错文案判断锁失败；需要显式所有权/提交阶段区分。

僵尸清理复用同run lease：竞争失败跳过；取得后fresh回读DOING及更新时间再CAS更新，避免筛选快照过时及活执行者误伤。锁保护不能只靠接口前置status判断。

注意GET_LOCK是连接级咨询锁，业务mapper写走其他连接；requireHeld与写之间并非数据库 fencing。至少保证持锁范围并在写前校验；若提出强“锁连接瞬断后所有写均不可能”的保证，必须有原子fencing/事务数据库证据，不能用requireHeld单次调用宣称已实现。不要为这一点擅增Schema。

## 验证设计

确定性Engine级测试需要真实production编排对象+mock persistence记录调用序，而非只测Plan：锁拒绝调用应没有runtime updateInput/status/output、node创建/更新、checkpoint.put及saveWorkflowMessage；SUCCESS恢复同样零写；最后updateOutput与消息应发生在lease.close前；失锁后不写FAIL；成功后SSE/release失败不改终态。

双JVM实验必须两独立PID、同一真实数据库/同一runtimeUuid，使用生产DataSource锁及Mapper，不能两个同JVM saver。JVM A在纯读节点屏障持锁，JVM B调用完整Engine.resume；B拒绝后逐表回读runtime(status/output/remark/update_time)、runtime_nodes、checkpoint、workflow_message不变，A完成只落一次终态。结束后B再恢复SUCCESS验证零写。

故障实验用隔离QA实例：A写检查点后硬停止进程，B重新取得锁并只续未完成纯读节点；有inFlight副作用则拒绝自动重放并保持原runtime/副作用/消息计数。另让B持锁运行时A调用failZombieDoingRuntimes(Duration.ZERO)，应跳过该活实例。输出须保存双方PID/包哈希/时间/真实调用路径、前后DB结果及checkpoint摘要。未真实执行前状态PENDING_VALIDATION。

## 输入版本
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowEngine.java` SHA-256 `d427bfcf3d2315496e7f76ce5f0c640c2a5ccc65be732f25529a23854cfcabd9`
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowExecutionPlan.java` SHA-256 `0df5de54ea77bf8454c5ecf9fbae4414b6e594c616149c61d076ea0cf9a65a32`
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/checkpoint/JdbcCheckpointSaver.java` SHA-256 `e73b9769172fdf777cade148cd7bb64fe7bdaeaba2915cb507ed5ce0dc15f2af`
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/service/WorkflowRuntimeService.java` SHA-256 `4453d10de9aecdebfcc57ca037872cc5f8411ae7aa5e9129b5ebba772d6f0996`
- `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowStarter.java` SHA-256 `f8615200e722c22d828384c096a14b9f48468eeb3331c6198f22b8c44ad8403b`
