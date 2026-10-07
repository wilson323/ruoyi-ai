-- P1-6.1 Gate要素定义版本生命周期（页47 draft/publish/archive）
-- 库：ipd_dev；迁移人：reviewer-qoder @2026-09-06；owner 授权：用户拍板「清理垃圾数据、补 P1-6.1 缺口」
-- 关联卡：P1-6.1（缺口6 DB element_code 唯一索引 + 生命周期列；G2-6 语义按 DOC-05 翻正）
--
-- =====================================================================
-- 【2026-10-07 修订 · 第 3 段已改为 DO-NOT-APPLY，禁止执行】
--
-- 第 3 段（原 :88）原写着：
--     UPDATE gate_review_elements SET is_veto = '1' WHERE element_code='G2-6' AND is_veto='0';
-- 该语句**方向是错的**。本段已整段注释掉，任何执行方式（含 mysql < 整文件、含粘贴单句）
-- 都不再产生副作用。
--
-- · 为何曾被误写：原注释引 DOC-05 的「来源」列「要素原稿:103被决策1覆盖」当作 DOC-05 的主张。
--   实际 DOC-05 的 G2-6 行 isVeto=**否**，「103」是被覆盖的规格原稿位置，不是主张本身。
-- · 正确值：G2-6 is_veto='0'（非否决），全库否决位 **14**（实测 G1=5/G2=4/G3=0/G4=3/G5=2）。
--   四处同向：DOC-05 决策1、建表 DDL 的 is_veto 列注释「14 项」、动作清单 v3 文首「14 项否决项」、
--   验收清单 AC-GLB-12「14 项否决项全部生效」。
-- · 重复 apply 的后果（原语句的 WHERE 看着幂等，实为**单向翻转**）：对一个已正确的库
--   （G2-6='0'）再跑一次，会把它改回 '1'（15 否决位）。触发点是
--   GateElementResultService.submit 命中否决位即抛「命中否决项无法提交通过」，
--   **G2 计划评审被错误硬卡**，与 DOC-05 决策1（G2-6 认证清单进行中也可过 G2）相反。
-- · 正确方向的修正走另一个脚本：2026-10-04-ipd-g2-6-veto-align.sql（'1'→'0' + 通过标准文本）。
--   本段若恢复执行会与之方向对立，**谁后跑谁生效**——这就是它必须保持注释状态的原因。
--
-- 【本机 ipd_dev 实测（2026-10-07，只读回读，未执行任何写操作）】
--   · 本脚本第 1、2 段（4 个生命周期列 + uk_gate_element_code 唯一索引）**已 apply**：
--     information_schema 实测 status/version/veto_dual_required/threshold_json 四列均存在，
--     索引 uk_gate_element_code 存在，33 行正牌种子 status='published'、version=1。
--   · G2-6 当前 is_veto='0'，pass_standard 已是 DOC-05 口径文本 → 该库本就是正确态。
-- ⇒ 对已 apply 的库，本脚本**只剩第 3 段有副作用，而第 3 段是错的**。
--   建议：整份文件**不再 apply**；确需新建库时，执行第 1、2 段即可，第 3 段保持注释。
--   （按本仓纪律，已应用的迁移不改写结构正文，故本文件仅注释有害语句、不删其余内容。）
-- =====================================================================

-- 1) 生命周期列：存量 33 条正牌种子默认 published/version=1（即当前生效定义）

-- [idem-guard: ALTER gate_review_elements.status]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gate_review_elements' AND COLUMN_NAME='status');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gate_review_elements ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''published''
        COMMENT ''生命周期: draft/published/archived（页47）；draft 与 archived 对业务不可见'' AFTER enabled',
  'SELECT ''gate_review_elements.status exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER gate_review_elements.version]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gate_review_elements' AND COLUMN_NAME='version');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gate_review_elements ADD COLUMN version INT NOT NULL DEFAULT 1
        COMMENT ''发布版本号：新建草稿=0，每次 publish 递增'' AFTER status',
  'SELECT ''gate_review_elements.version exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER gate_review_elements.veto_dual_required]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gate_review_elements' AND COLUMN_NAME='veto_dual_required');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gate_review_elements ADD COLUMN veto_dual_required CHAR(1) NOT NULL DEFAULT ''0''
        COMMENT ''双否决位：1=该否决项命中需双签确认（定义层标记，评审侧 P2-5.2 消费）'' AFTER is_veto',
  'SELECT ''gate_review_elements.veto_dual_required exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- [idem-guard: ALTER gate_review_elements.threshold_json]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gate_review_elements' AND COLUMN_NAME='threshold_json');

SET @ddl := IF(@col_exists=0,
  'ALTER TABLE gate_review_elements ADD COLUMN threshold_json VARCHAR(512) NULL
        COMMENT ''阈值配置 JSON 对象（键非空、值均为整数），如 {"minCustomerVerifications":3}'' AFTER pass_standard',
  'SELECT ''gate_review_elements.threshold_json exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- 2) element_code 全局唯一（并发唯一兜底；服务层已有预检）
--    前置核验：element_code 现存 43 行（33 正牌 + 10 已停用测试残留）零重复后本索引方可建

-- [idem-guard: ADD INDEX uk_gate_element_code ON gate_review_elements]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='gate_review_elements' AND INDEX_NAME='uk_gate_element_code');

SET @ddl := IF(@idx_exists=0,
  'ALTER TABLE gate_review_elements ADD UNIQUE INDEX uk_gate_element_code (element_code)',
  'SELECT ''gate_review_elements.uk_gate_element_code exists, skip'' AS msg');

PREPARE stmt FROM @ddl;

EXECUTE stmt;

DEALLOCATE PREPARE stmt;

-- 3) 【DO-NOT-APPLY · 2026-10-07 整段禁用】原 G2-6 否决位翻转语句
--    原语句（方向错误，理由见文首「2026-10-07 修订」）：
--      UPDATE gate_review_elements SET is_veto = '1' WHERE element_code = 'G2-6' AND is_veto = '0';
--    G2-6 **不是**否决项（DOC-05 决策1：认证清单「进行中」也可过 G2），
--    全库否决位权威值 = 14。正确修正走 2026-10-04-ipd-g2-6-veto-align.sql（'1'→'0'）。
--
--    ⛔ DO NOT APPLY · 禁止执行本段（含解除注释、含单句粘贴、含整文件重跑）。
--    ⛔ 本段与其余各段必须分开执行：第 1、2 段幂等且正确，可用于新库；第 3 段永不使用。

-- 回滚参考（如需）：
-- ALTER TABLE gate_review_elements DROP INDEX uk_gate_element_code;
-- ALTER TABLE gate_review_elements
--     DROP COLUMN status, DROP COLUMN version,
--     DROP COLUMN veto_dual_required, DROP COLUMN threshold_json;
-- UPDATE gate_review_elements SET is_veto = '0' WHERE element_code = 'G2-6';
