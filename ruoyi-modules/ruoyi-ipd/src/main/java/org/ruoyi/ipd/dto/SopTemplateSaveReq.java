package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * P1-3.3 SOP 草稿编辑入参（白名单 record：仅 title/content 可提交；
 * id/version/status/actionCode 均不可由客户端注入，id 走路径参数）。
 * <p>校验规则（service 层执行）：title 去空格 2-128 字；content 非空 ≥2 字。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SopTemplateSaveReq(String title, String content) {
}
