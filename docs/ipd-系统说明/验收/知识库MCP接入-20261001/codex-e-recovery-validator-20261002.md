# E 恢复独立复核

裁决：PARTIAL。只读生产代码、Cursor4探针及/tmp/engine-recovery-probe-result.json；没有Maven、主target写入、服务重载或业务库写入。

## 已确认与实质缺陷

Cursor4 Python按fail→resume串行subprocess.run；parallel第二JVM仅verify，未resume。可证明检查点跨JVM持久恢复及未知MailSend拒绝，不能证明同时owner竞争。

生产JdbcCheckpointSaver.acquireRun：真实DataSource连接执行GET_LOCK(database+runtimeUuid SHA键,0)，requireHeld用IS_USED_LOCK与CONNECTION_ID核owner。锁丢失可在节点开始前、节点结果提交前、checkpoint save前检测。mock Semaphore仅JVM内，不得用于双副本结论。

发现1（A源码，优先修复）：WorkflowEngine.resume:255在WorkflowExecutionPlan.execute:86 acquireRun之前updateStatus(DOING)。锁失败异常进入WorkflowEngine.errorWhenExe:145，无owner条件写FAIL，且先写消息。竞争拒绝者仍能修改真实owner运行，违反拒绝零写。即使节点没有重放，状态仍可能被污染。

发现2（A源码）：WorkflowEngine.exe:112的app.execute在返回前try-with-resources已释放RunLease；随后updateOutput成功终态与会话消息没有该lease。另一JVM可在释放和终态写之间进入。JdbcCheckpointSaver没有持久fence token，WorkflowRuntimeService的更新也未带owner约束，IS_USED_LOCK是检查而非原子fence。

未改源码。最小修复应将恢复准备状态、节点执行、成功/失败终态及消息的owner写全部纳入同一租约边界；拿锁失败仅反馈本次尝试不能恢复，不修改原运行。不能仅catch错误或新增HTTP接口来遮掩。不能通过二次独立拿同名GET_LOCK绕过连接所有权。细节由原恢复owner设计，G只验证。

## 可直接执行的无害双JVM锁实验

原目录WorkflowLeaseReadonlyProbe.java已准备，构造生产JdbcCheckpointSaver(null,真实DataSource)，只调用acquireRun/requireHeld/close，不访问mapper，不建工作流，不发模型/邮件，不写业务表。使用唯一随机runtimeUuid，避免碰真实运行锁。

A窗口结束后，在独立临时目录javac该探针，classpath使用Cursor4的临时aiflow-classes与lib/*或A已确认新包的classes快照。凭据只从现mysql-client.cnf传IPD_PROBE_DB_USER/PASSWORD环境，绝不打印。不要将本探针编入主target。

步骤：
1. JVM A以hold模式启动，stdin PIPE；等stdout出现LEASE_HELD。
2. JVM B以contend同uuid启动；必须退出0且打印LEASE_REJECTED。A仍存活。
3. 向A stdin写换行；A必须退出0并打印LEASE_RELEASED。
4. 新JVM B以acquire同uuid启动；必须打印LEASE_HELD和LEASE_RELEASED并退出0。
5. 新UUID再启动A hold，确认LEASE_HELD后仅终止该探针进程；新B acquire应成功，验证连接结束释放锁。不能kill16039或任意其他PID。

此实验直接验证生产MySQL租约跨JVM排斥、正常释放及进程死亡释放；不升级为WorkflowEngine完整恢复验收。

## Engine竞争故障的最小后续实验

复用原WorkflowEngineRecoveryProbe真实mapper和停用QA定义机制，独立新增一个仅Start/End的fixture；保留现runtime297–303，不删除、不用真实业务项目。第一个JVM在生产lease内以屏障暂停，第二个JVM对同runtime执行生产resume。记录第二进程开始前后runtime状态、remark、输出、node attempt数、checkpoint行数、消息数。成功条件：拒绝者零业务写，owner状态不被改，释放后单次恢复产物一致。当前源码预计在状态/消息回读上失败，必须保留这个反例作为修复门禁。

该实验需原恢复owner只在原证据探针类增加屏障钩子，不给业务源码加测试屏障，不新增resume接口/DDL，不调用副作用节点。最终再核实际A新包已加载，但本探针不重载运行服务。
