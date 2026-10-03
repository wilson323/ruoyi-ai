# 原生递归子任务恢复（2026-10-02）

状态：PENDING_VALIDATION。父协调会话持有共享 Maven 构建和运行包，本文不声称运行生效。

## 已读事实与修改窗口

- 仓库：ruoyi-ai；入口：ChildResumeDispatcher / ChildPreflight / SubagentScopeMiddleware。
- 服务边界：官方 HarnessAgent / 官方 subagentFactory 与原运行权限；无第二套执行内核。
- 状态：AgentStateStore 的 agent_state 版本、原 user/session 与实际 SDK TOOL_SUSPENDED 事件凭据。
- 六行缺口：父审批原调用快照已有；二次 ASK 原测试已有；嵌套父缺失 factory/祖先凭据；冷恢复无法重建中间父；父结果没有逐级回送；完成文本未与已保存正文逐字比对。
- 证据等级：源码 A；测试由主协调会话串行执行；无 HTTP/业务验收证据。

## 本次修复

1. ParentCall 增加原 factory 与 ancestor 链，保留旧四参/六参 root 构造兼容；仅由服务器原 actor 的 factory 绑定发行。
2. 只读 preflight 沿祖先验证真实存储槽、user/session、版本、原调用未完成与 factory 指纹；拒绝重复 session 与缺失祖先。
3. Scope 在子 actor 推理前装饰其自身原官方 manager；冷恢复逐级从原官方工厂重建中间父并登记实际对象。
4. Dispatcher 将叶结果只回送原中间父 spawn call，再将中间父真实最终输出逐级回送根；遇二次 ASK 保留暂停。中间父最终检查点也进入原 ChildCompletion 流，用于后续 sibling cold replay。
5. 完成需实际 SDK GenerateReason 合法、保存版本推进、完成 checkpoint user/session 一致、实际最终正文与保存的最后 assistant 正文一致。

## 原始反例与版本边界

首轮正式 compile 失败：KernelScopeKey.of 返回 KernelScopeKey.Scope，新增 helper 曾误声明为外层类型。原始日志 /tmp/ipd-parallel-recovery-first-20261002.log，已纠正类型。

第二轮实际 SDK recursive 测试发现：内建 general-purpose factory 构建 leafSubagent，subagents=false，middle 的 spawn-2 真实返回 ERROR，因此只有一个 child 注册。不是恢复层缺少一个状态条件；默认官方 leaf 不能假充递归执行。

初步诊断曾用官方 custom factory 验证递归；最终测试已切换正式 ProjectAgentChildConsumers + 官方默认 general-purpose factory。消费者为每个 leaf 注册公开官方 AgentSpawnTool(root manager,root taskRepository,权威祖先链计算的child depth)，不能共享父工具实例（否则固定depth=0会绕过SDK深度限制）。生产 Kernel 的消费者绑定仍由协调会话负责。

测试：ProjectAgentRecursiveChildPauseTest、ProjectAgentChildRepeatPauseTest、ProjectAgentChildPreflightTest、ProjectAgentAuthoritativeChildExecutionTest。包括篡改祖先 spawn id 拒绝，preflight 0 actor creates / 0 checkpoint writes。

未执行 Git commit/push、DDL、运行包重载；保留原在途修改。

## 新增真实执行证据

- 独立 `/tmp/ipd-recovery-native-sdk203` javac 编译：退出 0，输出仅写 /tmp，不触碰共享 Maven target；classpath 来自已有 Surefire 报告中的实际 2.0.3 依赖。
- NativeRecoveryRunner 调用实际测试方法、实际官方 Harness/Toolkit/factory/filesystem/checkpoint，退出 0。模型使用确定性离线 fixture，不能据此声称真实供应商/业务运行验收。
- `ACTUAL_SDK203_RECURSIVE_COLD_DOUBLE_ASK=PASS`：正式 Consumers + 默认 general-purpose 两层 spawn、冷恢复、第二次 ASK、第二次批准、中间父与根各自唯一原 spawn SUCCESS，原文件副作用一致。
- `ACTUAL_SDK203_DEPTH4_STOP=PASS`：第三层已绑定 Toolkit 的原生 agent_spawn 实际调用，SDK WARN `depth=4,max=3` 并返回官方停止输出，NO_NETWORK 模型禁止调用。
- 原始日志：`recovery-native-sdk203-20261002.log`。
- 新增 Consumers.closeChildren / AutoCloseable：实际 child identity 去重，SDK 2.0.3 DefaultAgentManager 无 child-close 方法；全部关闭尝试，异常聚合传播，幂等重复调用失败仍重抛原异常。Kernel 的清理绑定由协调会话完成。
- 再次诊断并修复：root parentNeedsPause 原先仅匹配直接 child；叶 ASK 使中间父暂停但 root 原 spawn 却 SUCCESS。现在沿服务器原 nonce/user/session/call 祖先链判定，根也形成实际 TOOL_SUSPENDED。
- 冷恢复从已验证中间父 checkpoint 重建 RuntimeContext 后曾丢失本 Scope 私有 token；现在只有原 native provenance 与已登记实际 actor 检验完成后重发私有 token，不放宽一般 authorize。

正式 Maven / 全量相关回归 / 加载运行包 / HTTP 业务验收仍由协调会话持有，不在此报告中冒充已通过。

## 最终源冻结验证

- 最新独立 javac：自身全部 main / 专用 test 与主协调最新 Kernel 源编译退出 0；主协调持有 Maven target，本子任务没有写共享 target。
- `recovery-native-sdk203-final-20261002.log`：实际 SDK 2.0.3 默认 general-purpose + production ChildConsumers 递归 cold 双 ASK、两次批准、原生最大深度 3、生产 EventBridge 根正文只收到一次 `child proof`、中间父 `spawn-2` 工具结果不进入根正文；退出 0。
- Dispatcher 的中间父事件 source 严格取原 `ancestor.sessionId/factory.name`，不损失服务器 actor locator；Consumers.bind 与 closeChildren 同监视器互斥，避免迟到注册遗漏关闭。
- `recovery-authoritative-native-20261002.log`：真实官方 factory→registered child→strict checkpoint load→native write_file 副作用→父继续；退出 0。旧夹具缺 frozen policy / governance.childLineage 的合法签发接线已补齐，未删断言或放宽 guard。
- `recovery-production-docker-pause-20261002.log`：新增 ProjectAgentProductionPauseReleaseTest 真跑主协调最新 production Kernel、官方 Docker provider / python:3.13-alpine、实际 RunHandle 关闭语义与 snapshot 字节回读；退出 0。离线模型夹具与内存业务 store 不证明数据库 epoch事务/真实模型业务验收。
- 核验顺序：真实 SANDBOX_ARCHIVED 收据→SDK `_sandbox_state` 保存→实际 handle 暂停关闭→关闭后 requireActiveOwnership 必抛 OwnershipLost→原 agent_state 与 sandbox resume metadata 保留。
- 实际 snapshotId：2015c149-6556-4b6d-af65-a5e72e7a5336；SHA256：9963c051547683a502ca0ef8802b790373e7f1bf217b0ab7ea61ef8a9413c71e。精确原容器 ID 的 `docker ps --all --filter id=9c45cd8948fbf165690ab5f478911374cb94450656a5570eec83482127af915c --format '{{.ID}}'` 退出 0、空结果，官方 provider 已回收该容器。
- 源 main / test 已向协调会话声明冻结；后续全模块 Maven、候选构建、运行加载、DB/HTTP/真实业务验收由协调会话执行。该阶段总体仍 PARTIAL。
