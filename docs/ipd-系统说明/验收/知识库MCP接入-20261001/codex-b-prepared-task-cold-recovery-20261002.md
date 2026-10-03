# PreparedTask 冷恢复隔离候选

裁决 PARTIAL。

六行缺口：
- 仓库：ruoyi-ai；仅 B 原 service-e972 temp 两新路径。
- 入口：可信 Service host 调度登记/恢复/原 Outcome 领取；无新 HTTP Task API。
- 边界：原 official AgentStateStore supportsVersioning/getVersioned/saveIfVersion，与真实 Redisson351 装配。
- 存储：原 StateStore 命名空间；Identity(deployment/tenant/person/project/task/attempt/generation/existing idem)、phase、Outcome SHA，无工具/client/token/modelContext正文。
- 现状→目标：原 preparations 只本机 map；新窄 CAS coordination 可冷重建，真实 host authority 必须提供，未伪授权。
- 证据：源码+真实隔离 Redis、官方 OutcomeTool/State，A级候选证据；不是运行主包验收。

3 tests/3成功/0失败/0跳过。关闭首个真实 Redisson client，再新 client 与新 dispatch object，官方 Redis 状态恢复成功；第二独立 client 同时领取两份官方 AgentTaskOutcomeTool.submit 写入的 State，原 SDK CAS 只有一个领取结果。原 State.take 两线程仅一次非空。重复同 identity 登记无重建覆盖，冲突 task identity 拒，撤权恢复拒；无版本库直接拒，不无条件降级。

工具/客户端/令牌必须由 TrustedDeployment.rebuild 从可信已批准 host 配置重新生成，且重建前后 authorize；Typed AgentTaskToolContext/taskId 校验，不用 persisted task metadata 当 Person 授权。测试 FixtureDeployment 明确仅假服务器授权接口，不是 operator/Person委托完成。真实产品没有可核已批准 deployment operator→Person/project 的委托入口，本项 PENDING，不新增DDL或第二登记库。

claim 只协调提交权：官方 State.take 后 CAS 持久 OUTCOME_CLAIMED+hash，不直接 finish，不将 WAIT/blocked/failed改成功。CAS胜者到 official finish 之间崩溃需官方状态对账；当前不自动重试成功、不以receipt假定远端已finish。恢复客户端与对象测试不等于真实应用JVM冷启；此候选未接入原Kernel/Starter主生命周期。claim为可信host内部typed调用，不能作为对外接收任意Outcome的端点。

隔离 Redis 仅127.0.0.1随机端口、无密码、无appendonly/save；finally停止所建进程，未连接应用Redis、未写DB/main/index/target，未修改SDK。
