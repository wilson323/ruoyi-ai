-- =====================================================================
-- 五张核心表补业务唯一键（DRAFT · 草案 · 未执行）
-- 生成：2026-10-07 数据测绘（team-lead 派单「修 ipd_sub_stage 空表与唯一键」任务 2）
-- 状态：DO NOT APPLY。AI 产出 SQL，owner 人工执行。执行前必须先跑本文件 §3 预检。
-- 依据：docs/ipd-系统说明/系统画像-数据层.md 结论 3 + 代码 selectOne/check-then-insert 实证
-- 环境：MySQL 8.0.46（真库 ipd_dev）；引擎全部 InnoDB
-- =====================================================================
-- 结论一句话：
--   5 张表当前只有主键；其中 3 张（stage_actions / project_stages / gate_element_results）
--   存在「先查后插」竞态写入，且下游有代码假设查出来只有一行 —— 必须补唯一键。
--   notification_events 已有 uk_notify_dedup(dedup_key)，本轮不动。
--   project_members 存在「同一人可否在同一项目兼两角色」的未决业务问题，不擅自定，列 §4 待拍板。
-- =====================================================================


-- ---------------------------------------------------------------------
-- §1 表 A：stage_actions（阶段推进的事实来源 · 最高优先）
-- ---------------------------------------------------------------------
-- 现有唯一键：仅 PRIMARY(id)。
-- 现有索引  ：idx_sa_project_code(project_id, action_code) —— NON_UNIQUE，
--             列组合与目标唯一键【完全相同】，故用 DROP+ADD 原地升级，不新增第二个索引。
--
-- 必要性依据（哪个代码点假设了唯一性）：
--   ① IpdZkScenarioInitializer.java:311-313  注释原文「幂等：project+actionCode 已存在即跳过」
--      —— selectCount 后 insert，无数据库约束，并发下可插入重复行。
--   ② StageActionService.java:481-500         C12 动作 selectCount 后 insert，同上竞态。
--   ③ StageActionService.java:622-643         批量补齐：先 selectList 建「已存在集合」再
--      insertBatch(200)，两个请求交错即产生重复。
--   ④ SubStageGateService.java:93-99          把结果收进 Map<actionCode, StageAction>，
--      重复行会被静默覆盖 —— 门禁读到的「唯一那条」是不确定的。
--   ⑤ GateMaterialChecker.java:53 / GateEngine.java:438 同按 (project, action) 定位单条。
--
-- ⚠ 唯一键【不覆盖】的一类脏数据（写在这里以免误以为加了就干净）：
--   Z01-Z05 历史别名与权威码若同时存在，是两个不同字符串，唯一键【拦不住】。
--   ActionCatalog.resolveCode 会把两者归一到同一权威码，SubStageGateService 的 Map 仍会
--   静默二选一。清理别名重复行是独立的数据治理项，不在本 DDL 范围。
--
-- 重复预检（执行前必跑，期望 0 行为干净）：
--   SELECT project_id, action_code, COUNT(*) c FROM stage_actions
--   WHERE del_flag='0' GROUP BY project_id, action_code HAVING COUNT(*) > 1;
--   2026-10-07 实测：0 个重复组 / 0 多余行（17 行全表）。
--
-- 锁影响：表 17 行、DATA_LENGTH 16KB。ALTER 走 INPLACE + LOCK=NONE（加二级唯一索引），
--   实际持有 MDL 写锁的时间是毫秒级，无需 online DDL 窗口。
-- ---------------------------------------------------------------------
ALTER TABLE stage_actions
  DROP INDEX idx_sa_project_code,
  ADD UNIQUE INDEX uk_sa_project_code (project_id, action_code);

-- ⚠ 若 prefer ADD 而非 DROP+ADD（例如担心期间有查询依赖旧索引名），
--   改用等价的 ADD-only 写法（会多留一个冗余索引，二者功能重复）：
--   ALTER TABLE stage_actions ADD UNIQUE INDEX uk_sa_project_code (project_id, action_code);


-- ---------------------------------------------------------------------
-- §2 表 B：project_stages
-- ---------------------------------------------------------------------
-- 现有唯一键：仅 PRIMARY(id)。现有索引 idx_stages_project(project_id) —— NON_UNIQUE。
--
-- 必要性依据：
--   ① StageAcceptanceService.java:211-216  requireStage() 用 selectOne
--      过滤 (project_id, stage_code, del_flag='0')，**没有 LIMIT**。
--      MyBatis-Plus 的 selectOne 命中多行会抛 TooManyResultsException ——
--      这是本轮唯一一个「重复行直接炸接口、且无任何降级」的点。
--   ② IpdZkScenarioInitializer.java:317-320  selectOne(... .last("limit 1")) 靠 LIMIT 兜底，
--      说明开发者已遇到过或预期到多行，只是没有在库层收口。
--
-- 关于 del_flag 是否进键：del_flag ∈ {'0','1'}。若把 del_flag 放进唯一键，
--   同一 (project, stage) 只能存在 1 条已删行 —— 第二次软删即撞键。
--   故【不进键】，语义为「一个项目的一个大阶段恒只有一行，历史与否用 del_flag 表达」。
--   预检按 del_flag='0' 口径；若全表口径也干净（2026-10-07 实测两种口径均 0 重复组），
--   加键后软删+重建流程不受影响。
--
-- 重复预检：
--   -- 全表口径（更严，建议以此为准）
--   SELECT project_id, stage_code, COUNT(*) c FROM project_stages
--   GROUP BY project_id, stage_code HAVING COUNT(*) > 1;
--   2026-10-07 实测：0 个重复组（30 行全表）。
--
-- 锁影响：30 行 / 16KB，INPLACE + LOCK=NONE，毫秒级。
-- ---------------------------------------------------------------------
ALTER TABLE project_stages
  ADD UNIQUE INDEX uk_ps_project_stage (project_id, stage_code);


-- ---------------------------------------------------------------------
-- §3 表 C：gate_element_results
-- ---------------------------------------------------------------------
-- 现有唯一键：仅 PRIMARY(id)。现有索引 idx_ger_gate(gate_id) —— NON_UNIQUE。
--
-- 必要性依据（judge() 是典型的先查后插）：
--   ① GateElementResultService.java:164-176  judge() 先 selectOne(... limit 1)，
--      命中就 update，miss 就 insert —— 两个操作员同时判同一要素即插两行。
--      同文件 125-131 行有「Gate 已提交并冻结快照，不可改判」的前置校验，
--      但那是业务态校验，不是并发保护。
--   ② GateElementResultService.java:91-93    selectList 后收成 Map 并 (a,b)->b 取后者，
--      重复行导致判定结果被覆盖，且【静默无日志】。
--   ③ GatePrecheckService.java:128-130 / GateEngine.java:229-231  按 (gate_id, …) 聚合
--      遍历全部结果行，重复行会让要素计数翻倍，直接影响 Gate 放行判断。
--
-- 重复预检：
--   SELECT gate_id, element_id, COUNT(*) c FROM gate_element_results
--   WHERE del_flag='0' GROUP BY gate_id, element_id HAVING COUNT(*) > 1;
--   2026-10-07 实测：0 个重复组（表当前 0 行）。
--
-- 锁影响：0 行 / 16KB，INPLACE + LOCK=NONE，毫秒级。
-- ---------------------------------------------------------------------
ALTER TABLE gate_element_results
  ADD UNIQUE INDEX uk_ger_gate_element (gate_id, element_id);


-- ---------------------------------------------------------------------
-- §4 表 D：project_members —— 【不写 DDL，待 owner 拍板】
-- ---------------------------------------------------------------------
-- 现有唯一键：仅 PRIMARY(id)。现有索引 idx_pm_project(project_id) / idx_pm_person(person_id)
--            —— 均 NON_UNIQUE。
--
-- 代码实测（逐条查过，无一处硬假设唯一）：
--   ① PostLaunchReviewService.java:210-215  selectOne(...).orderByAsc(id).last("LIMIT 1")
--   ② HandoverService.java:166-170          同上，带 LIMIT 1
--   ③ HandoverService.java:139-143          selectCount（计数，天然容忍重复）
--   ⇒ 三个读取点全部是 LIMIT 1 或 count，重复行不会炸接口。故本表风险等级低于 §1-§3。
--
-- 唯一的真实缺口：ProjectMemberServiceImpl.java:141 memberMapper.insert(member) 前无
--   「同项目同角色是否已有在任成员」的前置校验（前置的 selectCount 锁的是 person_id
--   用于项目数上限判定，eq(personId).isNull(exitDate)，与本缺口不是同一件事）。
--   ⇒ 并发或重复提交可绑出两行同 (project_id, person_id, role)。
--
-- 卡住不动的业务问题：**同一个人能否在同一项目里兼任两个角色？**
--   支持「能」的例子：bindMember 入参含 role，代码从未断言「一人一项目一角色」。
--   支持「不能」的例子：2026-10-07 实测 10 行数据，同一 project_id 下 person_id
--                       从未重复，呈现「一人一项目一角色」形态。
--   支持「要能重绑」的第三种情形：成员用 exit_date 退出后可能再被绑回同一项目，
--   此时 (project_id, person_id, role) 三列完全相同 —— 唯一键会直接拦死正常业务。
--   MySQL 8.0 无部分唯一索引（无法只对「在任」行加约束），所以这个键要么误伤重绑、
--   要么形同虚设。
--
-- 候选（owner 二选一，AI 不代拍）：
--   方案 ①  uk_pm_project_person_role (project_id, person_id, role)
--          —— 堵死重复绑定，但【会拦死「退出后再绑回」】。需先确认该场景是否存在。
--   方案 ②  维持现状，仅在 bindMember 补一条前置校验 + FOR UPDATE 串行化
--          —— 零 DDL 风险，但并发窗口靠应用层保证。
--
-- 重复预检（现状，两种口径都干净）：
--   SELECT project_id, person_id, COUNT(*) c FROM project_members
--   WHERE del_flag='0' GROUP BY project_id, person_id HAVING COUNT(*) > 1;   -- 0 组
--   SELECT project_id, person_id, role, COUNT(*) c FROM project_members
--   WHERE del_flag='0' GROUP BY project_id, person_id, role HAVING COUNT(*) > 1;  -- 0 组
-- 锁影响：10 行 / 16KB，若最终决定加键同样是毫秒级。


-- ---------------------------------------------------------------------
-- §5 表 E：notification_events —— 【不需要动】
-- ---------------------------------------------------------------------
-- 现有唯一键：uk_notify_dedup (dedup_key) UNIQUE —— 业务去重键已在库层收口。
-- 2026-10-07 实测：其余索引 idx_notify_inbox / idx_notify_dispatch / idx_notify_error
--   均为查询用非唯一索引，不是业务键，无需升级。
-- 重复预检：SELECT dedup_key, COUNT(*) c FROM notification_events GROUP BY dedup_key
--   HAVING COUNT(*)>1;  → 0 组（表当前 0 行）。
-- 结论：本轮不动。列在此处是为了让「5 张表都要处理」的账面收敛为「实际 3 张要动」。


-- ---------------------------------------------------------------------
-- §6 执行顺序与回滚
-- ---------------------------------------------------------------------
-- 建议顺序（每条独立事务，失败互不影响）：
--   1. ALTER TABLE stage_actions         DROP+ADD uk_sa_project_code
--   2. ALTER TABLE project_stages        ADD     uk_ps_project_stage
--   3. ALTER TABLE gate_element_results  ADD     uk_ger_gate_element
--   （project_members 与 notification_events 本轮不执行）
--
-- 回滚（保留 DROP INDEX 语句，以便在确认前先用只读方式核对依赖）：
--   ALTER TABLE stage_actions        DROP INDEX uk_sa_project_code,
--                                   ADD INDEX idx_sa_project_code (project_id, action_code);
--   ALTER TABLE project_stages       DROP INDEX uk_ps_project_stage;
--   ALTER TABLE gate_element_results DROP INDEX uk_ger_gate_element;
--
-- 执行后建议复验（期望各 1 条 UNIQUE / 0 条重复组）：
--   SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE FROM information_schema.STATISTICS
--   WHERE TABLE_SCHEMA='ipd_dev' AND INDEX_NAME LIKE 'uk_%'
--     AND TABLE_NAME IN ('stage_actions','project_stages','gate_element_results');
-- =====================================================================
