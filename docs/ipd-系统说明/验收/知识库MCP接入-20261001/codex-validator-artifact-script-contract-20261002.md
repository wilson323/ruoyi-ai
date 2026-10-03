# 附件验收脚本合同审查

范围：只读生产源码与本机 Maven 官方 2.0.3 source jar；仅改独立验收脚本。未创建业务运行。

- 官方 `agentscope-harness-2.0.3-sources.jar` 的 `PlanModeTools.java`：`plan_enter` 输入空对象；`plan_write` 必填字符串 `content`，完整替换计划正文；`plan_exit` 可选字符串 `summary`，自身权限检查固定 ASK。PLAN 模式其他写操作禁止，因此测试文件必须先写，确认离开 PLAN 后再交付。
- `ProjectAgentRunPlanner.plan/loadSkills`：`actionCode` 可空、空 `skillNames` 不自动加载市场技能；只有非空 actionCode 才查原动作绑定。市场技能 competitor-analysis-ipd 明确要求 C02 检索、无名单停止比较并出缺项，与纯附件测试目标不符。脚本已改 `skillNames: []`，保留原能力包，不写 actionCode。这不关闭 SDK 动态技能能力，也不改 owner 技能授权。
- `AgentRunResumeReq`：外层 `expectedPauseSeq` 与 `aguiInput`。恢复原 run/thread 字符串 ID；`messages/tools/context/resume` 为数组，`state/forwardedProps` 空对象。中断回答仅 `resume[{interruptId,status:"resolved",payload:{approved:true}}]`，不往 state/metadata 放权限字段。
- `ProjectAgentAguiCheckpointGuard` 会验证中断 metadata 原 toolName/toolInput/toolContent、检查点调用状态、原 run ownership；脚本只接受服务端持久 reason=tool_call 且 metadata.toolName=plan_exit，不审批其他业务工具。`approved` 只响应本次测试计划权限，不代替文档审核/Gate/owner 审批。
- 下载按真实字符串 versionId，并核对原 ARTIFACT 的 attachmentOrigin、contentHash 与 attachment.byteLength。下载成功必须 HTTP200 octet-stream；错误 JSON 不当文件。非 owner 要求 HTTP403。

限制：模型是否依指令真实调用工具、候选 HTTP 是否接受空技能、恢复链与下载授权，必须等实际加载候选执行验证。当前语法检查不能证明运行态闭环；浏览器刷新和真实 MyBatis 事务证据仍待执行。
