# Cursor任务4结果：工作流跨 JVM 恢复

2026-10-02。裁决：**部分闭环**。生产 `WorkflowEngine.run/resume` 加生产 runtime/checkpoint 仓储，已在两个独立 JVM 上完成只读恢复和未知副作用拒绝。没有 HTTP resume 端点，16039 未重载，双副本锁边界未验。

## 六行缺口

| 项 | 内容 |
|---|---|
| 仓库 | ruoyi-ai |
| 入口 | `WorkflowEngine.run/resume`。`WorkflowStarter.resumeRuntime` 不是 Controller。`/workflow/runtime` 无 resume |
| 服务边界 | aiflow 的 `WorkflowRuntimeService`、`WorkflowRuntimeNodeService`、`JdbcCheckpointSaver`。不调用 127.0.0.1:16039，不重载 PID 13520 |
| 表和字段 | 新增停用的 `t_workflow` 136–138 及对应 node/edge/runtime/checkpoint。保留 runtime 297/298 |
| 现状与目标 | 旧探针是合成 runner + JDBC mapper 代理。本次改为真实 Start/End 节点和真实 MyBatis mapper |
| 证据等级 | A：独立 JVM 退出码、MySQL 回读、检查点解码 |

## 代码

`WorkflowEngine.run` 在 `sseEmitter == null` 时不再调用 `startSse`。HTTP 入口仍总是传入连接；空连接此前会在 `run` 的 try 之外对 null emitter 同步，并先写 Redis `user:asking:{userId}`。`resume` 本来就允许空连接。

未新增 resume 接口。未改 WfState / WorkflowExecutionPlan。

## 探针

命令（退出码 0）：

`python3 docs/ipd-系统说明/验收/知识库MCP接入-20261001/run-workflow-engine-recovery-probe.py --jar ruoyi-admin/target/ruoyi-admin.jar --classes ruoyi-modules/ruoyi-aiflow/target/classes --execute --out /tmp/engine-recovery-probe-result.json`

依赖库来自 admin jar `729e587af6504d12ebdf20e4320141f688408fafeda15511482931e2ad8e1003`。`WorkflowEngine.java` 只编译进临时目录，不写主 Maven target。临时目录 `/var/folders/8l/6tnv8ssd2vs0h8463rnv66b00000gn/T/ipd-engine-recovery-z5lefc0m`。

定义行 `is_enable=0`、`is_public=0`。探针在内存中把 `isEnable` 设为 true 后调用 `run`，因此没有走 HTTP「工作流已停用」分支。MailSend 的 `node_config` 为 `{}`，恢复 JVM 的 `started=[]`，没有进入 `MailSendNode`。展示模板的 `ConfigService` 返回空，回退内置文案，未读 `sys_config`。

| 运行 | workflow | runtime | 两个 JVM | 库回读 |
|---|---|---|---|---|
| replay | 136 | 301 `67cf7daa1d3e41dc88165cd06f2dd11f` | fail 后 resume，均退出 0 | status=3。A 成功 1 行；B 成功 2 行，旧 `f26e33070fc64fae9a0ffea2c6505176` 保留，新 `fc66a5257f8244a4abb99b97754a9534`；C 的输入是 `engine-A-B`，探针在 C 读上游时看到的 B attempt 等于新行。检查点 completed=A,B,C，next=`__END__` |
| effect | 137 | 302 `f6054264a01242d4a2ddc4f9591b84de` | fail 后 resume，均退出 0 | 只有 A。B 是 MailSend，无 runtime node。检查点 completed=A，inFlight=B。resume 后 status=4，备注含「副作用节点结果尚未确认，禁止自动重放」 |
| parallel | 138 | 303 `5171517718d543cca6a9a95c481f7a11` | fail 后第二个 JVM 只读核对，未 resume | B8、B9 无 runtime node。检查点 completed 只有 A，inFlight 含 B0–B9。B1–B7 已进入 `runNode`，节点回调已把 output 写成 status=3，但完成检查点被故障挡住 |

297 `fprobe75d1aa2a346847f18646a9fa43`、298 `fprobe1833437250a841a9897b4beb34` 仍在，所属定义 131/132 仍 `is_enable=0`。失败尝试留下停用定义 133–135，未删除。

## 未验证

- 没有外部 resume URL。未用 16039 做 HTTP 恢复，也未把这次 Engine 补丁装进 PID 13520。
- 两次 JVM 串行执行，没有同时持锁的双副本对打。
- 已启动的并行分支会在检查点失败前写下节点输出。未启动的只有超过 8 线程池后仍在队列中的 B8、B9。
- 未重跑 aiflow 全量 143，避免写主 Maven target。
- 旧 synthetic 探针证据仍然有效，但不能算这次 Engine 闭环。
