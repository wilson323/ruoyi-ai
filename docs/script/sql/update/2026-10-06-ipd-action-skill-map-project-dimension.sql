-- =====================================================================
-- ipd_action_skill_map 项目维度绑定（2026-10-06，owner 三项决策之一）
-- 状态：owner 已在会话中拍板落实（项目级绑定优先、无项目级绑定时回退全局默认）
-- 依据：2026-10-06 会话指令（技能绑定增加项目维度）；
--       背景 = 同一 action_code 跨项目语义/技能不同（如 D02 老项目=联动场景用例设计/MARKET_PM，
--       新项目=首版BOM冻结与采购下单/RD_PM），全局唯一绑定无法按项目区分。
-- 语法：MySQL 8.0（真库 8.0.46）；本脚本不可重复执行（ALTER 无 IF NOT EXISTS），回滚见同日 -rollback 文件
-- =====================================================================

-- project_id 哨兵设计：0=全局默认绑定；>0=项目级绑定（解析时优先）。
-- 用 NOT NULL DEFAULT 0 而非 NULL：MySQL 唯一索引对多行 NULL 不去重，
-- NULL 方案会让「全局默认行」可重复插入，破坏一码一默认的不变量。
ALTER TABLE ipd_action_skill_map
    ADD COLUMN project_id bigint NOT NULL DEFAULT 0
        COMMENT '项目维度：0=全局默认绑定；>0=项目级绑定（运行装配时项目级优先，无项目级绑定回退全局默认）'
        AFTER sub_stage_code,
    DROP INDEX uk_map_action_code,
    ADD UNIQUE KEY uk_map_action_project (action_code, project_id),
    ADD KEY idx_map_project (project_id);

-- 存量 69 行由 DEFAULT 0 自动归为全局默认绑定，语义与变更前完全一致；
-- 项目级绑定行由 owner 审核后的 seed SQL 另行写入（本表无 Java 写入者，写入即拍板，不随运行自动晋升）。
