-- R217-P1-4.2（看板卡 fde68b8c）：deliverables 新增 content_hash 列
-- 卡面 AC「上传者/大小/hash入库」：uploaded_by / file_size 列已存在（P0 schema），
--   仅缺 hash 落库列。服务端上传时对文件字节计算 SHA-256 hex（64 字符）写入本列。
-- 变更性质：纯 additive 可空列，不触碰既有行与约束，向后兼容（历史行 content_hash=NULL，
--   与 oss_id 可空同口径——历史无 OSS 文件占位已由 CONSISTENCY-13 认可）。
-- apply 状态：已 apply 本机 ipd_dev（2026-09-25，apply 前后 information_schema.COLUMNS 回读留证）；其他环境待 owner apply。

ALTER TABLE deliverables ADD COLUMN content_hash VARCHAR(64) NULL COMMENT '附件内容SHA-256十六进制（P1-4.2 服务端上传时计算落库；历史行为NULL）';

-- 自检：apply 后必须存在该列
SHOW COLUMNS FROM deliverables LIKE 'content_hash';

-- 回滚
-- ALTER TABLE deliverables DROP COLUMN content_hash;
