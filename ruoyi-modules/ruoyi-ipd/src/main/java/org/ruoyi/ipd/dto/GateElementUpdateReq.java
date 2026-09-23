package org.ruoyi.ipd.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * CODE-01：更新 Gate 评审要素请求白名单。
 * gateCode/elementCode 不收——编码是要素身份，不可改；id 来自 path。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GateElementUpdateReq(
    String elementName,
    String passStandard,
    String isVeto,
    Integer sortOrder,
    String enabled,
    /** 双否决位：仅 isVeto='1' 时有效；定义层标记，评审侧 P2-5.2 消费。 */
    String vetoDualRequired,
    /** 阈值 JSON 配置（键非空、值均为整数），如 {"minCustomerVerifications":3}。 */
    String thresholdJson) {

    public org.ruoyi.ipd.domain.GateElement toPatch(Long id) {
        return org.ruoyi.ipd.domain.GateElement.builder()
            .id(id).elementName(elementName).passStandard(passStandard)
            .isVeto(isVeto).sortOrder(sortOrder).enabled(enabled)
            .vetoDualRequired(vetoDualRequired).thresholdJson(thresholdJson)
            .build();
    }
}
