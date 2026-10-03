package org.ruoyi.ipd.agent.dto;

/** 用户对原运行冻结技能候选的审核；身份和技能正文不能由请求提供。 */
public record AgentSkillReviewReq(Boolean approved, String sha256, String comment) { }
