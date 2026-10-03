package org.ruoyi.ipd.agent.dto;

import io.agentscope.core.agui.model.RunAgentInput;

/** 原运行中断响应；身份、权限和 SDK 检查点从服务端回读。 */
public record AgentRunResumeReq(Long expectedPauseSeq, RunAgentInput aguiInput) { }
