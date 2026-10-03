# 官方 Task 单原运行消费候选

裁决 PARTIAL。只使用 pinned e972 官方公开 API，未修改 SDK。5 新文件 temp-only。Relay 6/6、Authority 5/5、原 Adapter 5/5，合计16成功/0失败/0跳过；新增测试首轮 Mockito 恢复 throwing stub 失败原日志保留，doReturn 修正仅夹具。

官方 HarnessAgentTaskStarter 自己构建完整 TaskEnvelope/modelContext、Team discussion 与原 CollaborationTools；公开 onAgent middleware 只调度原 ProjectAgentRunService，不调用 next 或外层模型。实际原 ReAct consumer 3 次 stub Model.stream，调用原 issue.get 和 task.submit_result；官方 Starter 读取同一 Outcome.State 返回真实正文。FAILED/CANCELLED/撤权不报成功，waiting 保持 waiting；真实 Reactor dispose 调原 run cancel。逐工具订阅时重查权限并校验 typed task ID/token，元数据 strict/outputSchema/readOnly/returnDirect 保留。凭据仅临时 typed context，正文验证递归协议 credential 字段已清除；不声称任意自由文本秘密自动可识别。

## root 窄接线

1. Configuration 将 ServiceAdapter 的 AgentTaskStarter 换为 ProjectAgentOfficialTaskExecution；注入真实 trusted binding、授权 carrier factory、原 runs/access 与 preparations。旧原型 Starter 不能当完整 Task consumer。
2. Planner 创建前按 server-generated idempotencyKey 调 findPlannedToolIds，存在可信登记才把官方 collaboration IDs 并入本次实际 execution tools 冻结；普通请求无登记不受影响，不从 message/metadata 自批。
3. Kernel 原 agent 初次 stream 之前，按可信 actor/runrow 调 preparations.install(actor,row,toolkit,runtimeContext)，其后全工具原治理包装；不重写 person/project/native session，不另 agent.call。
4. Service authority/session observer 仍需真实持久 state 与当前成员接线。此候选未修改主 Kernel/Planner/Configuration，测试 RunService Answer 明确模拟该 hook，不能算生产已消费。

## 未闭环

Team waiting pendingID 为控制面 fixture；真实 Service pending 验证未做。Transient pre-create registry 重启/重复幂等结果接续、child TaskRepository、WAITING_APPROVAL恢复、真实 HTTP取消/SSE、部署注册与配套SDK升级待集成。保持官方功能，不以禁用这些能力代替完成。业务产物审核/Gate与 Task success 分离，未apply/review/DB写入。
