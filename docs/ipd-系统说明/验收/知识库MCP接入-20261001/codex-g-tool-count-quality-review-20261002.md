# G 工具次数独立复核

裁决：本样本次数虚报不成立。2026-10-02实时ipd_dev只读回核run2106082915170418689；artifact77f8283b1a6849798dc6b39ce4da4f9b正文声明project_knowledge_search7、MCP6、总13，与真实TOOL_CALL/TOOL_RESULT/SOURCE一致。三类各13条且toolCallId逐一对应，本地7、远端6。原codex-fresh-rework-poll-20261002T180500210471Z.json sources也是13条，不是14。seq本地SOURCE18–21、46–48；MCP22–25、44–45。数据库精简原回读见codex-g-tool-count-readback-20261002.json。

本次只读，无源码、target、outbound、运行/产物写入；DRAFT/docnull不自动apply，次数核对通过也不替代其余独立正文验收。

## 完成门当前合同及确定性风险

ProjectAgentCompletionGate.noteTool(String)只置searchInvoked布尔，没有计数。RunHandle.onToolCall与onToolResult都调用noteTool，直接在noteTool累加会把同一实际调用双算。noteSource提供toolCallId，当前先按hits过滤，失败无hits会直接返回；将SOURCE条数当调用次数会漏没有来源事件的工具或失败。真实调用、返回、来源命中是三个不同统计口径，不能统一算一项。

reject只核正文非空、Gate越权、来源身份、无命中披露和小数百分数引用。纯整数当前明确不核以避免章节号/年份误伤；不能将任意正文整数用blanket regex拒绝来校验工具次数。没有该样本失败复现，不应修改完成门制造新约束。

## 建议的最小可审查切片（暂不实施）

如后续取得真实虚报反例，应以TOOL_CALL中非空toolCallId去重作为调用次数，TOOL_RESULT同id仅补终态，SOURCE同id仅补检索状态。保留工具协议名，失败调用仍计入attempt，明确总调用/成功返回/命中来源的统计名称。服务端生成固定结构的调用摘要供右栏或产物元数据展示，不让模型承担当权威总数。

必要接口是noteToolCall(toolCallId,toolName)与noteToolResult(toolCallId,toolName,state)，RunHandle两现回调分别接线；模型声明若要校验，应先定义明确结构化claim字段并和服务端ledger比较。现正文没有统一count claim结构，仅CompletionGate+Test无法完整实现该合同；仅noteSource去重只能校验来源事件摘要，不能假称真实工具调用计数。

正反测试应覆盖同id CALL/RESULT不双算、重复SOURCE不双算、失败调用仍计attempt、无SOURCE工具、并发顺序、缺id不编造、未知tool区分、正文步骤/年份不误拒、不同统计口径不混用。若只是要本次页面显示真实次数，优先已有事件timeline聚合，不扩新增执行轨。

当前不建议源码修复：证据反例已被实时DB推翻；主协调者可继续其余正文质量审查，不以本报告批准产物或完成业务动作。
