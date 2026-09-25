-- =====================================================================
-- WB-17-1 工作台 taskType 扩展 —— 缺表核对后 DDL 草稿
-- 状态：DRAFT 草稿（待 owner 拍板 + DBA 窗口 apply，本会话未对真库执行任何 DDL/DML）
-- 车道：R218-WB171（2026-09-25）；卡 id e30c86a2-2263-49ca-ad4d-218c644ccb08
-- =====================================================================
-- 【现查核对结论（证据：2026-09-25 mysql --defaults-extra-file=...cnf ipd_dev -e "SHOW TABLES"）】
-- 卡面「8 类缺表」前提已部分过时：
--   A. 缺表 4 类（waiver/rd_replacement/retirement/capacity）对应 5 张表
--      （gate_waivers / rd_replacements / rd_replacement_approvals /
--        product_retirements / multi_project_capacity_approvals）
--      均已存在于真库 —— 来源为 2026-09-08-ipd-c-batch-4-tables-draft.sql
--      （14 问拍板后 R12.1 apply，WorkbenchService.java L64「未 apply」javadoc 已过时）。
--      → 本文件不再为其重复写 CREATE TABLE；其真实缺口是 Java 实体/Mapper/写入链路（见调研文档）。
--   B. 口径待拍板 4 类（receipt_review / change_implementation / change_verify / bonus_lock）
--      表均已存在（receipt_ledgers / requirement_changes / bonus_pools），
--      缺的是「列与状态机锚」，非表 → 见下方 ALTER 草稿（段 C）。
--   C. 现查新增表级空白仅 1 张：saved_items（spec 页03 §3 saved 字段 + §5⑥
--      「saved bucket 收藏对象进入 saved_items 表」；真库 SHOW TABLES 无此表）。
--      注：saved_items 不属 17 类 taskType 数据源，属页03 收藏桶配套表。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 段 B：saved_items 幂等建表草稿（现查唯一表级空白）
-- 规格依据：docs/开发说明/spec/batch-01-pages-01-12.md 页03 §3「saved | array | /api/saved-items」
--          + §5 闭环剧本⑥「saved bucket 收藏对象进入 saved_items 表」
-- 列风格对齐：真库同域表 receipt_ledgers 的 BaseEntity 尾列（SHOW COLUMNS 现查 2026-09-25）
-- 待 owner 拍板：target_type 值域（spec 未穷举）；去重约束是否按 (person_id,target_type,target_id) 唯一
-- ---------------------------------------------------------------------
create table if not exists saved_items (
  id            bigint        not null                comment '主键（雪花，与仓内惯例一致）',
  person_id     bigint        not null                comment '收藏人（persons.id，会话推导防伪造）',
  target_type   varchar(32)   not null                comment '收藏对象类型（project/task/deliverable…值域规格待补，owner 拍板）',
  target_id     bigint        not null                comment '收藏对象 id（按 target_type 关联各业务表主键）',
  project_id    bigint        null                    comment '冗余项目维度（可选；工作台按项目分组展示用）',
  create_dept   bigint        null                    comment '创建部门',
  create_by     bigint        null                    comment '创建人',
  create_time   datetime      null default current_timestamp comment '创建时间',
  update_by     bigint        null                    comment '更新人',
  update_time   datetime      null default current_timestamp on update current_timestamp comment '更新时间',
  tenant_id     varchar(20)   null default '000000'   comment '租户号',
  del_flag      char(1)       null default '0'        comment '删除标志（0存在 1删除）',
  remark        varchar(500)  null                    comment '备注',
  primary key (id),
  key idx_saved_items_person (person_id, target_type)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_general_ci comment='工作台收藏项（spec 页03 saved bucket；WB-17-1 R218 草稿，未执行）';

-- ---------------------------------------------------------------------
-- 段 C：change_implementation / change_verify 两类任务的数据源表草稿
-- 规格依据：docs/开发说明/spec/batch-03-pages-25-37.md 页26
--   L88（权限锚 change_implementations.owner_id）、L90（POST /api/changes/:id/implementation）、
--   L50（attachments 数据源 = change_implementation_evidence）、L136-138（实施不通过→in_progress）、
--   L137（两名责任PM分别收到 change_verify 任务）
-- 真库证据：SHOW TABLES LIKE 'change_implementations' / 'change_implementation_evidence' 均空（2026-09-25 现查）
-- 待 owner 拍板：① verify 双PM列是否并入本表（本草稿并入，对齐原型 closure.mjs 单表语义）
--   ② requirement_changes 既有链路（DRAFT/PENDING_SIGN/APPROVED/REJECTED）与本表关系 =
--      「批准后才生成 implementation 行」，实施任务锚字段不再 ALTER 到 requirement_changes（替代方案二选一，owner 定）
-- ---------------------------------------------------------------------
create table if not exists change_implementations (
  id                bigint        not null              comment '主键（雪花）',
  change_id         bigint        not null              comment '变更单 id（requirement_changes.id，spec 语义 change_requests）',
  project_id        bigint        not null              comment '项目 id（冗余，visibleProjects 过滤锚）',
  owner_id          bigint        not null              comment '实施责任人 person id（spec 页26 L88 权限锚 change_implementations.owner_id）',
  status            varchar(24)   not null default 'NOT_STARTED' comment '实施态：NOT_STARTED/IN_PROGRESS/IMPLEMENTED/VERIFIED_MARKET/VERIFIED_RD/CLOSED（值域规格待补，草稿按 spec 页26 闭环剧本推导，owner 拍板）',
  baseline_note     varchar(2000) null                  comment '基线修订说明（POST /api/changes/:id/baseline {note}）',
  baseline_revision int           null                  comment '基线修订版本号（响应 {ok,revision}）',
  implementation_note varchar(2000) null                comment '实施结果说明（POST /api/changes/:id/implementation {note}）',
  market_pm_id      bigint        null                  comment '市场PM person id（change_verify 投递锚1）',
  market_decision   varchar(16)   null                  comment '市场PM验证结论 approved/rejected（NULL=待验证）',
  market_verified_at datetime     null                  comment '市场PM验证时间',
  rd_pm_id          bigint        null                  comment '研发PM person id（change_verify 投递锚2）',
  rd_decision       varchar(16)   null                  comment '研发PM验证结论 approved/rejected（NULL=待验证）',
  rd_verified_at    datetime      null                  comment '研发PM验证时间',
  closed_by         bigint        null                  comment '关闭人（spec 页26 L107 v3 缺失字段 closed_by）',
  closed_at         datetime      null                  comment '关闭时间（同上 closed_at）',
  due_at            datetime      null                  comment '实施期限（规格待补：spec 未给出期限来源，owner 拍板）',
  create_dept       bigint        null                  comment '创建部门',
  create_by         bigint        null                  comment '创建人',
  create_time       datetime      null default current_timestamp comment '创建时间',
  update_by         bigint        null                  comment '更新人',
  update_time       datetime      null default current_timestamp on update current_timestamp comment '更新时间',
  tenant_id         varchar(20)   null default '000000' comment '租户号',
  del_flag          char(1)       null default '0'      comment '删除标志（0存在 1删除）',
  remark            varchar(500)  null                  comment '备注',
  version           int           not null default 0    comment '乐观锁版本',
  primary key (id),
  unique key uk_change_impl_change (change_id) comment '一变更单一实施行（幂等防重）',
  key idx_change_impl_owner (owner_id, status),
  key idx_change_impl_project (project_id)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_general_ci comment='变更实施闭环单（spec 页26；change_implementation/change_verify 任务数据源；WB-17-1 R218 草稿，未执行）';

create table if not exists change_implementation_evidence (
  id            bigint        not null              comment '主键（雪花）',
  change_id     bigint        not null              comment '变更单 id（POST /api/changes/:id/evidence 关联对象）',
  description   varchar(500)  not null              comment '证据说明（请求体 {description}）',
  attachment_id bigint        null                  comment '附件 id（请求体 {attachmentId?}，关联 attachments 体系；落表规格待补：spec 未指明附件物理归属）',
  project_id    bigint        null                  comment '项目 id（冗余可见范围过滤）',
  create_dept   bigint        null                  comment '创建部门',
  create_by     bigint        null                  comment '创建人（提交证据人）',
  create_time   datetime      null default current_timestamp comment '创建时间',
  update_by     bigint        null                  comment '更新人',
  update_time   datetime      null default current_timestamp on update current_timestamp comment '更新时间',
  tenant_id     varchar(20)   null default '000000' comment '租户号',
  del_flag      char(1)       null default '0'      comment '删除标志（0存在 1删除）',
  remark        varchar(500)  null                  comment '备注',
  primary key (id),
  key idx_cie_change (change_id)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_general_ci comment='变更实施证据/附件（spec 页26 L50 attachments 数据源；WB-17-1 R218 草稿，未执行）';

-- ---------------------------------------------------------------------
-- 段 D：列级缺口草稿（表在、投递锚列不在 —— 仅当 owner 选「扩列方案」时启用；
--        备选方案=新建独立评审单表，不在本文件展开，规格待补）
-- 真库证据（SHOW COLUMNS 现查 2026-09-25）：
--   receipt_ledgers   现有列无任何 status/reviewer/review_* 字段（2 行 source=RECEIPT）
--   requirement_changes 现有 status 值域 DRAFT/PENDING_SIGN/APPROVED/REJECTED，
--     无 leader_approver_id/admin_approver_id（spec 页26 L107 v3 缺失字段；
--     implementer/verifier 锚改由段 C change_implementations 承载，不再扩列）
-- ---------------------------------------------------------------------
-- D-1 receipt_review（方案①扩列；owner 拍板前先不执行）
-- alter table receipt_ledgers add column review_status varchar(24) null comment '评审态：PENDING_REVIEW/APPROVED/REJECTED（值域规格待补）';
-- alter table receipt_ledgers add column reviewer_id   bigint      null comment '评审人 person id（投递锚）';
-- alter table receipt_ledgers add column reviewed_at   datetime    null comment '评审时间';
-- D-2 requirement_changes v3 审批人单独字段（spec 页26 L107）
-- alter table requirement_changes add column leader_approver_id bigint null comment '产品组长审批人（v3 要求单独存储）';
-- alter table requirement_changes add column admin_approver_id  bigint null comment '超级管理员审批人（v3 要求单独存储）';

-- ---------------------------------------------------------------------
-- 段 E：bonus_lock —— 无 DDL 缺口登记
-- bonus_pools 表在（20 行），状态机 DRAFT→CONFIRMED→DISTRIBUTED（BonusPoolService.java:57 javadoc +
-- DefaultStateMachineGuard.java:156 bonus_pool:DRAFT->CONFIRMED|freeze 现查）。
-- 「待锁定(PENDING_LOCK)」前置态无生产者 → 属状态机/口径拍板项（owner 定：新增态 or
-- 以「DRAFT 且到达锁定窗口」派生投递），不涉及建表。规格待补。
-- =====================================================================
-- 再声明：本文件全部语句均未执行。apply 前需 owner 逐段拍板 + DBA 窗口 +
-- 按仓内规约补登 tenant.excludes / GRANT（参照 2026-09-09-ipd-grant-c-batch-4-dml.sql 先例）。
-- =====================================================================
