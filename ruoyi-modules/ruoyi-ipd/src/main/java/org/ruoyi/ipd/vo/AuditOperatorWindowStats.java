package org.ruoyi.ipd.vo;

import lombok.Data;

/**
 * AI-P3 #7 审计异常检测：窗口内按操作人聚合的只读统计行（非表实体，不落库）。
 *
 * <p>由 {@code AuditLogMapper.selectOperatorWindowStats} 的 GROUP BY 查询产出，
 * 两列 SUM 分别喂给规则 A（短时间大量写入）与规则 B（非常规角色敏感操作）。
 * 阈值判定在 Java 侧（{@code AuditAnomalyScanService}），SQL 只做无阈值聚合，
 * 避免「改阈值 = 改 SQL」的隐蔽耦合。
 */
@Data
public class AuditOperatorWindowStats {
    /** 操作人 id（可为 null：匿名/系统行聚合组） */
    private Long operatorId;
    private String operatorName;
    private String operatorRole;
    /** 窗口内写型动作数（CREATE/UPDATE/DELETE/APPROVE/REJECT） */
    private long writeCount;
    /** 窗口内非超管执行的敏感动作数（PERMANENT_DELETE/REBUILD_CHAIN/TRANSFER_SUPER_ADMIN） */
    private long sensitiveOffRoleCount;
}
