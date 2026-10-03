# capability D 接续只读核验

状态：PARTIAL。沿总画布概念与计划，未修改计划、源码、运行或业务数据；未取消、重启、定档或审核。

核验时间 UTC：2026-10-02T08:25:55.881109+00:00

## 数据库原始回读

```text
NOW()	id	project_id	action_code	status	error_code	finished_at
2026-10-02 16:25:55	2105935561859538946	9140005	C02	RUNNING	NULL	NULL
MAX(seq)	MAX(create_time)	quiet_seconds
43	2026-10-02 16:19:17	398
id	status
2105727025791688706	REJECTED
```

mysql 退出码：0。数据库 NOW 使用服务端时区。

## 运行诊断

java 63087 监听 127.0.0.1:16039；node 86995 监听 127.0.0.1:15666；健康口返回 401，仅证明服务响应。jcmd Thread.print 成功，快照位于 /tmp/ipd-capability-d-threads.txt。观察到 ipd-project-agent-loop-2、boundedElastic-1 处于线程池等待，未发现项目检索方法的阻塞栈；不能由此确定根因。

磁盘候选包 inode 284310711，内嵌 IPD kernel 与当前 target/classes SHA256 同为 63ebc4c40790d8b2c10d2e61d07c660d9bebf46ef344748b7fa0368b5355b120，含 InlineKnowledgeSearchTool。进程同时打开 jar.original inode 284292026 和 jar inode 284310711；磁盘一致不证明 JVM 已加载对应类，本轮未取得内存类字节证据。

## 验收限制

运行未终态，检索结果、新产物、定档、人员审核均未验收。既有定向测试本轮未重跑，不计为新增证据。下一安全动作是保留原运行并核实其终态或异步执行诊断；禁止把工具调用事件当检索成功。
