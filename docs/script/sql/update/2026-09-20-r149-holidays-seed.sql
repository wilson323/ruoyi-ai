-- =====================================================================
-- R149 batch1：奖金池窗口月数 + NPS 最小样本 + 2026 法定节假日 seed（仅 commit 不 apply）
-- 日期：2026-09-20（R149 batch1 后端交付）
-- 数据源：2026 年法定假参考通常作息，实际生产请以国务院办公厅当年放假通知为准；
--         超管可通过「参数维护」端点覆盖（system_configs.config_key 唯一键）
-- 关联变更：
--   - bonus.windowMonths：BonusPoolService.readWindowMonths() 默认 6
--   - nps.minSample     ：KpiSharedCollectionService.readMinSample() 默认 30
--   - calendar.holidays ：Workdays.add(Date, int, Collection<LocalDate>) R149 B1 新增重载
-- =====================================================================

-- R149 A1：奖金池基数采样窗口月数（默认 6 个月；ZK-IPD §三.2.1 历史口径）
INSERT IGNORE INTO system_configs (id, config_key, config_value, value_type, default_value, description, tenant_id, create_time)
VALUES (1948092001, 'bonus.windowMonths', '6', 'NUMBER', '6',
        'R149 A1：奖金池基数采样窗口月数（上市后连续 N 个月实际回款；ZK-IPD §三.2.1；缺省 6）',
        '000000', now());

-- R149 A3：NPS 最小有效样本阈值（默认 30；AC-KPI-11 历史口径）
INSERT IGNORE INTO system_configs (id, config_key, config_value, value_type, default_value, description, tenant_id, create_time)
VALUES (1948092002, 'nps.minSample', '30', 'NUMBER', '30',
        'R149 A3：NPS 最小有效样本数（不达标则不计入 K03 分母，仅标记待补充；AC-KPI-11；缺省 30）',
        '000000', now());

-- R149 B1：法定节假日 + 调休补班日历（JSON 形式按年聚合）
INSERT IGNORE INTO system_configs (id, config_key, config_value, value_type, default_value, description, tenant_id, create_time)
VALUES (1948092003, 'calendar.holidays', '{"year":2026,"comment":"R149 B1 seed：法定假 + 调休补班；运行期可由超管覆盖","holidays":[{"date":"0101","name":"元旦"},{"date":"0217","name":"春节"},{"date":"0218","name":"春节"},{"date":"0219","name":"春节"},{"date":"0220","name":"春节"},{"date":"0221","name":"春节"},{"date":"0222","name":"春节"},{"date":"0223","name":"春节"},{"date":"0404","name":"清明"},{"date":"0405","name":"清明"},{"date":"0406","name":"清明"},{"date":"0501","name":"劳动节"},{"date":"0502","name":"劳动节"},{"date":"0503","name":"劳动节"},{"date":"0619","name":"端午"},{"date":"0620","name":"端午"},{"date":"0621","name":"端午"},{"date":"0925","name":"中秋"},{"date":"0926","name":"中秋/国庆"},{"date":"0927","name":"中秋/国庆"},{"date":"0928","name":"中秋/国庆"},{"date":"0929","name":"中秋/国庆"},{"date":"0930","name":"中秋/国庆"},{"date":"1001","name":"国庆"},{"date":"1002","name":"国庆"},{"date":"1003","name":"国庆"},{"date":"1004","name":"国庆"},{"date":"1005","name":"国庆"},{"date":"1006","name":"国庆"},{"date":"1007","name":"国庆"}],"workdays":[{"date":"0208","name":"春节调休补班"},{"date":"0214","name":"春节调休补班"},{"date":"0403","name":"清明调休补班"},{"date":"0426","name":"劳动节调休补班"},{"date":"1010","name":"国庆调休补班"}]}', 'JSON',
        '{"year":2026,"holidays":[],"workdays":[]}',
        'R149 B1：法定节假日 + 调休补班（按年聚合 JSON）；Workdays.add(Date, int, Collection<LocalDate>) 跳过 holidays 集合；调休 workdays 仅作追溯',
        '000000', now());
