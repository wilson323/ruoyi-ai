# 通用工作流检查点故障恢复：本机合成真库探针

2026-10-02。裁决：本机合成真库跨进程恢复闭环；完整 WorkflowEngine HTTP 恢复尚未验收。

使用最终候选 admin SHA256 `1bf9484af62cf8611246cf2febbe3036505442007f61b2f73ed22b18726eb789` 内的生产 WorkflowExecutionPlan、JdbcCheckpointSaver、WfState，真实本机 MySQL DataSource 与 GET_LOCK，四个独立 JVM。JDBC mapper 适配仅承担现表读写，节点 runner 是合成确定性只读函数，无模型、邮件或外部工具调用；不能把本探针扩成全部运行层通过。

首次故障在 B 输出已 commit 后、检查点 put 前注入。只读路径第二进程仅执行 B、C，A 未重复；恢复内存过滤旧 B，C 引用 retry-B。DB 回读显示旧 B 与新 B 两个成功尝试均保留。未知副作用路径以 MailSend 类型标记合成只读 runner，第二进程拒绝恢复且 calls=[]；不代表真实邮件系统副作用已测试。

保留的探针：

- `fprobe75d1aa2a346847f18646a9fa43`：runtime 297，4 节点尝试（A、旧B、新B、C），9 checkpoint。
- `fprobe1833437250a841a9897b4beb34`：runtime 298，2 节点尝试（A、B），4 checkpoint。

两个工作流均 `is_enable=0`，未公开，不供 HTTP 执行。写面为带探针标识的 t_workflow/t_workflow_node/t_workflow_runtime/t_workflow_runtime_node/t_workflow_checkpoint 新增行，无 DDL、删除、历史覆盖。源 SQL、退出码、四进程输出和独立 DB 回读见同目录 JSON。

执行命令：`python3 run-workflow-recovery-db-probe.py --jar /Users/mac/Documents/ruoyi-ai/ruoyi-admin/target/ruoyi-admin.jar --execute --out <绝对证据路径>`。每次执行使用新 UUID 并保留历史；不得盲目重复。编译仅写独立 /tmp，不触及 Maven target。

HTTP 事实：生产 `/workflow/run` SSE 依赖框架 ThreadContext User；当前无外部 resume 端点。IPD Person 不是该账户体系，故未冒充它进行 WorkflowEngine HTTP 恢复。完整引擎服务重建、节点组件真实 process、网络断流与真实外部副作用仍待对应验收。
