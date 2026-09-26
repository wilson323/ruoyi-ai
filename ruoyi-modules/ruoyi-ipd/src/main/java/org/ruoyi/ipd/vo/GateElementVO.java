package org.ruoyi.ipd.vo;

import org.ruoyi.ipd.domain.GateElement;

import java.util.Date;

/**
 * View: GateElement 的对外暴露视图，移除内部字段（delFlag / createBy / updateBy / createDept / params）。
 * 对应接口：/api/v1/gate-elements
 */
public record GateElementVO(
    Long id,
    String gateCode,
    String elementCode,
    String elementName,
    String passStandard,
    String isVeto,
    Integer sortOrder,
    String enabled,
    String status,
    Integer version,
    String vetoDualRequired,
    String thresholdJson,
    Date signDueAt,
    Integer signExtensionCount,
    Date createTime,
    Date updateTime
) {
    public static GateElementVO from(GateElement e) {
        // R219（看板卡 a995a9e3）：对外契约统一 '1'/'0' 编码——真库存量 14+19 条 'Y'/'N' 脏行
        // （老代 seed 字面量）经归一化输出，前端不再需要双编码兼容；存量清洗 SQL 待 owner apply。
        return new GateElementVO(
            e.getId(),
            e.getGateCode(),
            e.getElementCode(),
            e.getElementName(),
            e.getPassStandard(),
            org.ruoyi.ipd.service.GateElementService.normalizeFlag(e.getIsVeto()),
            e.getSortOrder(),
            e.getEnabled(),
            e.getStatus(),
            e.getVersion(),
            org.ruoyi.ipd.service.GateElementService.normalizeFlag(e.getVetoDualRequired()),
            e.getThresholdJson(),
            e.getSignDueAt(),
            e.getSignExtensionCount(),
            e.getCreateTime(),
            e.getUpdateTime()
        );
    }
}