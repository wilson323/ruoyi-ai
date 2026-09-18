-- =====================================================================
-- P1-10.2 / AI-STRAT-1 收尾：ai_doc_embeddings 表 DDL
-- 适用库：ipd_dev（本地隔离环境；禁止迁移生产，由 DBA 按发布窗口执行）
-- 前置：ai_documents 表已存在（P0-2 基线）；当前 0 行，CREATE IF NOT EXISTS 无损。
--
-- 业务语义（BR-AI-04 / 2026-09-17 抽样实证）：
--   - ai_documents 审核通过（REVIEWED）即异步向量化
--   - content 按固定窗口切片，每片存 embedding 向量（JSON float 数组）
--   - 生成时同项目（含同 embed_model）余弦 top-K 检索注入上下文
--   - 向量空间一致性锚：embed_model 参与检索过滤——换模型旧向量不可比
--
-- 红线（BR-AI-04）：
--   - 切片原文只进 ai_doc_embeddings（业务库）与生成 prompt
--   - 不进审计（审计只记 contextHits/contextChars）
--   - 不进日志（明文 chunk_text 不落盘日志）
--
-- 租户：单企业私有部署，tenant_id 默认 '000000'，tenant.excludes 已登记（application.yml L342）
-- 日期：2026-09-17
-- =====================================================================

-- [idem-guard: CREATE ai_doc_embeddings]
CREATE TABLE IF NOT EXISTS ai_doc_embeddings
(
    id          bigint        not null comment '主键（雪花）',
    doc_id      bigint        not null comment 'ai_documents.id（版本链节点）',
    project_id  bigint        not null comment '项目ID（检索范围锚）',
    doc_type    varchar(32)   null     comment '文档类型快照（PRD/MRD…，注入上下文标注来源用）',
    title       varchar(200)  null     comment '文档标题快照',
    chunk_seq   int           not null comment '切片序号（0 起）',
    chunk_text  mediumtext    not null comment '切片原文（检索命中后注入上下文的片段）',
    embed_model varchar(64)   not null comment 'embedding 模型名（向量空间一致性锚：换模型旧向量不可比）',
    vector_json mediumtext    not null comment '向量（JSON float 数组；Java 余弦计算）',
    tenant_id   varchar(20)   null     default '000000' comment '租户ID',
    create_time datetime      null     default CURRENT_TIMESTAMP comment '创建时间',
    primary key (id),
    -- 检索热路径：同项目 + 同 embed_model 是必备过滤条件
    key idx_ai_emb_project_model (project_id, embed_model),
    key idx_ai_emb_doc (doc_id)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_general_ci
  comment='AI 文档向量化切片（BR-AI-04：切片原文只进本表与生成 prompt，不进审计/日志）';
