package org.ruoyi.ipd.dto;

import java.math.BigDecimal;

/**
 * P4-4.1 项目绩效汇总行（AC-INC-34 项目级口径）
 *
 * <p>单项目一行，金额单位为「元（人民币）」保留 2 位。
 * <p>字段语义：
 * <ul>
 *   <li>{@code allowanceFinalAmount}：该项目下全体人员当月津贴 finalAmount 合计（多项目叠加后）</li>
 *   <li>{@code avgWeightedScore}：该项目下项目绩效评定 weightedScore 平均值（按 personId × pmRole 去重）</li>
 * </ul>
 *
 * <p>不重复计数口径：
 * <ul>
 *   <li>allowance_ledgers 自然键 = (personId, projectId, month) —— SELECT SUM(finalAmount) GROUP BY projectId WHERE month=?</li>
 *   <li>project_scores 自然键 = (projectId, personId, pmRole) —— SELECT AVG(weightedScore) GROUP BY projectId WHERE status='CONFIRMED'</li>
 * </ul>
 *
 * <p><b>奖金池列已随「算钱」层下线移除</b>：原 {@code bonusFinalPool} / {@code bonusRowCount}
 * 两列直读 {@code bonus_pools} 表，属算钱口径，故一并删除。表本身保留在库中（不做 DDL），
 * 仅本 DTO 不再暴露对应列——前端若仍消费这两列需同步改版。
 */
public record ReportSummaryRow(
    Long projectId,
    String projectCode,
    String projectName,
    String month,
    BigDecimal allowanceFinalAmount,
    BigDecimal avgWeightedScore,
    Integer allowanceRowCount,
    Integer scoreRowCount
) {
}
