package org.ruoyi.ipd.vo;

/**
 * KPI 规则轻量视图（W1-KPI / paiban-02 方案 B，零 DB 变更）。
 *
 * <p>不新建 kpi_rules 表：复用现有 KPI 表数据源，把
 * kpi_rule_snapshots 最新快照的 rule_json 顶层键值 / system_configs 的
 * kpi.* 键拍平为 {ruleKey, ruleValue} 列表。
 *
 * <p>数值/字符串统一为 String（与 /api/v1 包络 BigNumberSerializer 约定一致，
 * 防前端 BigInt 截断）。
 */
public record KpiRuleView(String ruleKey, String ruleValue) { }
