# E 租约边界最小修补

状态：PENDING_VALIDATION。未Maven、未主target、未重载、未运行业务探针。

六行缺口：
- 仓库：ruoyi-ai/aiflow。
- 入口：WorkflowEngine.run/resume/exe与WorkflowExecutionPlan.execute。
- 服务边界：复用JdbcCheckpointSaver生产RunLease，GET_LOCK只获取一次，原execute调用者保持兼容。
- 表字段：已有runtime/node/checkpoint/message；无DDL、无新owner/fence列。
- 现状目标：拿锁前DOING及拒绝后FAIL污染owner，成功写在lease close之后；改为Engine外层取得租约，准备、节点、成功/失败消息与状态写都在该租约内。
- 证据等级：A源码与diff检查；单测新增未执行；跨JVM生产锁与运行包仍待A/B验证。

源码修改：WorkflowEngine.java、WorkflowExecutionPlan.java；新增WorkflowEngineLeaseBoundaryTest.java。before哈希见codex-e-lease-before-20261002.json。

Engine在读取运行身份后获取同一租约，租约内进行恢复重建与DOING准备、executeUnderLease、成功终态和消息；失败时重新requireHeld才写失败。未拿到或丢失租约，reportUnownedFailure只日志和现连接反馈，不更新原runtime或持久消息。Plan既有execute自行获取租约不变，新增executeUnderLease供Engine持已有租约调用，不二次GET_LOCK。原未知副作用拒绝和checkpoint机制不变。

测试覆盖拒绝竞争恢复零runtime/node交互、准备前lease丢失不写失败并释放。A需统一运行WorkflowEngineLeaseBoundaryTest及aiflow全量；B需原真实mapper独立跨JVM锁竞争与状态回读。

边界：MySQL advisory lock与IS_USED_LOCK仍不是数据库原子fence。本刀保守阻止检测到失锁后的失败/终态晚写，但节点已开始期间lease连接死亡、其他连接上的写与失锁瞬间的竞态未由此证明完全闭合。原checkpoint对未知副作用仍拒绝重放；外部效果无幂等证据时不得自动重试。没有引入新fence列或声称完整双副本生产验收。若严格原子fence需要结构变更，必须回原合同/授权，不把本刀包装成已经具备。

## 闭包补丁与冻结

扩授权后修改WorkflowStarter/WorkflowRuntimeService：Starter排除非DOING/FAIL，Engine在同租约内getByUuidForResume fresh回读并核id/workflowId/status，拒绝终态零写，输入/完成节点重建使用fresh。SUCCESS持久后通知异常仅warn，不降FAIL。Zombie注入生产JdbcCheckpointSaver，同锁busy跳过，fresh核DOING/未删除/时间阈值，再按id/uuid/status/updateTime条件CAS更新并计真实影响行。现fake测试适配条件更新而非updateById，增加busy owner不处置；Engine测试增加fresh成功拒旧失败快照和成功通知故障不降态。仍待A编译与B探针。
