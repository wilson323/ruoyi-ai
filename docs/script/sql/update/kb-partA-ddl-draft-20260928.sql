-- =====================================================================
-- 文件：kb-partA-ddl-draft-20260928.sql
-- 状态：待 owner 拍板，未 apply，禁止执行。
-- 性质：草案（Part A 最小 DDL 切片）。设计依据见
--       docs/ipd-系统说明/知识库结构与属性最佳实践-20260928.md §6。
-- 纪律：本文件未 apply 前，严禁在 Java 实体/Bo/Vo/Mapper 中加对应字段——
--       真库 Unknown column 会连锁打红全部查询（P2-7.4 前车之鉴）。
-- 语法：MySQL 8。全部 ALTER 加列均带默认值或 NULL，向后兼容，可安全回滚
--       （回滚=DROP COLUMN，见配套文档 §7 风险与回滚）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. knowledge_info：知识库归属四维 + 敏感级
--    缺口取证：现表仅有 user_id + tenant_id + share（扁平归属），
--    无 scope_type/group_id/project_id/owner_agent_id（见
--    docs/script/sql/ruoyi-ai.sql 中 knowledge_info DDL 键名清单）。
-- ---------------------------------------------------------------------
ALTER TABLE `knowledge_info`
    ADD COLUMN `scope_type`    varchar(16) NOT NULL DEFAULT 'PERSON'
        COMMENT '作用域：GLOBAL全局|GROUP产品线(产品组)|PROJECT项目|PERSON个人|AGENT数字员工' AFTER `share`,
    ADD COLUMN `group_id`      bigint NULL DEFAULT NULL
        COMMENT '归属产品组（scope_type=GROUP/PROJECT 时使用；对齐 product_groups.id）',
    ADD COLUMN `project_id`    bigint NULL DEFAULT NULL
        COMMENT '归属项目（scope_type=PROJECT 时使用；对齐 projects.id，项目=产品动词面）',
    ADD COLUMN `owner_agent_id` bigint NULL DEFAULT NULL
        COMMENT '归属数字员工（scope_type=AGENT 时使用；对齐 agent_info.id）',
    ADD COLUMN `sensitivity`   varchar(16) NOT NULL DEFAULT 'INTERNAL'
        COMMENT '敏感级：PUBLIC公开|INTERNAL内部|SECRET机密；检索时按用户 RBAC 过滤';

ALTER TABLE `knowledge_info`
    ADD INDEX `idx_kb_scope_type`  (`scope_type`),
    ADD INDEX `idx_kb_group`       (`group_id`),
    ADD INDEX `idx_kb_project`     (`project_id`),
    ADD INDEX `idx_kb_owner_agent` (`owner_agent_id`),
    ADD INDEX `idx_kb_sensitivity` (`sensitivity`);
-- ---------------------------------------------------------------------
-- 2. knowledge_fragment：向量版本三元组（重嵌入范围可识别）
--    缺口取证：库级已有 vector_model/embedding_model（knowledge_info），
--    但片段级无 embedding_model+embedding_dim+embedded_at 三元组
--    （knowledge_fragment DDL 现态键名：fid/idx/doc_id/content/knowledge_id
--    +审计列+tenant_id），换 embedding 模型后无法 SQL 圈出需重嵌入片段。
-- ---------------------------------------------------------------------
ALTER TABLE `knowledge_fragment`
    ADD COLUMN `embedding_model` varchar(128) NULL DEFAULT NULL
        COMMENT '本片段实际使用的 embedding 模型名（写入时快照，非库级引用）',
    ADD COLUMN `embedding_dim`   int NULL DEFAULT NULL
        COMMENT '本片段向量维度（与 embedding_model 联合判桶）',
    ADD COLUMN `embedded_at`     datetime NULL DEFAULT NULL
        COMMENT '本片段最近一次成功嵌入时间（增量重嵌入水位线）';

ALTER TABLE `knowledge_fragment`
    ADD INDEX `idx_kf_embedded_scope` (`embedding_model`, `embedded_at`);

-- ---------------------------------------------------------------------
-- 3. agent_info：数字员工补归属（与知识库同构的双维归属边）
--    缺口取证：现表有 knowledge_ids（JSON 数组，显式授权线索）与
--    model_id/mcp_tool_ids/skill_names，但无 owner_person_id/project_id/
--    sensitivity——数字员工是四维隔离的归属病根（见 CONTEXT 篇 §3）。
-- ---------------------------------------------------------------------
ALTER TABLE `agent_info`
    ADD COLUMN `owner_person_id` bigint NULL DEFAULT NULL
        COMMENT '归属自然人（数字员工的主人；对齐 project_members.person_id 同源人员域）',
    ADD COLUMN `project_id`      bigint NULL DEFAULT NULL
        COMMENT '归属项目（对齐 projects.id；与 ai_agent_tasks.project_id 同构挂边）',
    ADD COLUMN `sensitivity`     varchar(16) NOT NULL DEFAULT 'INTERNAL'
        COMMENT '该员工可处理的最大敏感级：PUBLIC|INTERNAL|SECRET（检索过滤用）';

ALTER TABLE `agent_info`
    ADD INDEX `idx_agent_owner_person` (`owner_person_id`),
    ADD INDEX `idx_agent_project`      (`project_id`);

-- =====================================================================
-- 回滚草案（仅当 owner 拍板 apply 后需要回撤时使用；未 apply 则无需回滚）
-- ALTER TABLE `knowledge_info`    DROP COLUMN `scope_type`, DROP COLUMN `group_id`,
--     DROP COLUMN `project_id`, DROP COLUMN `owner_agent_id`, DROP COLUMN `sensitivity`;
-- ALTER TABLE `knowledge_fragment` DROP COLUMN `embedding_model`,
--     DROP COLUMN `embedding_dim`, DROP COLUMN `embedded_at`;
-- ALTER TABLE `agent_info`        DROP COLUMN `owner_person_id`,
--     DROP COLUMN `project_id`, DROP COLUMN `sensitivity`;
-- =====================================================================
