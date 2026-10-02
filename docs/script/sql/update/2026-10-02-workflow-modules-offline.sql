-- 工作流双模块下线（ruoyi-aiflow + ruoyi-workflow/warm-flow）
-- 拍板：owner 2026-10-02「aiflow 整模块下线 + warm-flow 一并下线」；与 langchain4j 清理合并一刀。
-- 前置证据：跨模块 import 0 引用（ipd/chat/system/common）；flow_* 10 表全 0 行、test_leave 0 行（从未投产）；
--          t_workflow* 7 表仅测试数据；菜单 33 行为上游模板遗留 + E1 权限码（20260927-e1 已 apply，随模块一并退役）。
-- 关联：三套工作流引擎定位裁定与Guard链路归一-20260927.md（IPD 领域状态机为唯一编排事实源）。
-- 幂等：DROP IF EXISTS / DELETE 按固定 ID 集合，重复执行安全。
-- 回滚：不可回滚（表与菜单随模块代码一并删除，恢复需 revert 代码 commit + 重放上游种子）。

-- ----------------------------
-- 1. 菜单与角色关联（33 行菜单：11616 工作流树 19 + E1 码 8 + 11618 我的任务树 5 + aiflow 编排管理 1）
-- ----------------------------
DELETE FROM sys_role_menu WHERE menu_id IN (
  11616,11620,11621,11622,11623,11624,11625,11626,11627,11630,11631,11700,11701,
  11801,11802,11803,11804,11805,11806,
  11900,11901,11902,11903,11904,11905,11906,11907,
  11618,11619,11629,11632,11633,
  2031361596464902145
);
DELETE FROM sys_menu WHERE menu_id IN (
  11616,11620,11621,11622,11623,11624,11625,11626,11627,11630,11631,11700,11701,
  11801,11802,11803,11804,11805,11806,
  11900,11901,11902,11903,11904,11905,11906,11907,
  11618,11619,11629,11632,11633,
  2031361596464902145
);
-- 兜底：权限码前缀抓漏网行
DELETE FROM sys_role_menu WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE perms LIKE 'workflow:%');
DELETE FROM sys_menu WHERE perms LIKE 'workflow:%';

-- ----------------------------
-- 2. warm-flow 框架表（10 张，全 0 行）+ 演示业务表
-- ----------------------------
DROP TABLE IF EXISTS `flow_category`;
DROP TABLE IF EXISTS `flow_definition`;
DROP TABLE IF EXISTS `flow_his_task`;
DROP TABLE IF EXISTS `flow_instance`;
DROP TABLE IF EXISTS `flow_instance_biz_ext`;
DROP TABLE IF EXISTS `flow_node`;
DROP TABLE IF EXISTS `flow_skip`;
DROP TABLE IF EXISTS `flow_spel`;
DROP TABLE IF EXISTS `flow_task`;
DROP TABLE IF EXISTS `flow_user`;
DROP TABLE IF EXISTS `test_leave`;

-- ----------------------------
-- 3. aiflow 画布编排表（7 张，仅测试数据）
-- ----------------------------
DROP TABLE IF EXISTS `t_workflow`;
DROP TABLE IF EXISTS `t_workflow_checkpoint`;
DROP TABLE IF EXISTS `t_workflow_component`;
DROP TABLE IF EXISTS `t_workflow_edge`;
DROP TABLE IF EXISTS `t_workflow_node`;
DROP TABLE IF EXISTS `t_workflow_runtime`;
DROP TABLE IF EXISTS `t_workflow_runtime_node`;

-- ----------------------------
-- 4. sys_config 工作流节点消息模板（aiflow 消费方已删，死数据）
-- ----------------------------
DELETE FROM sys_config WHERE config_key IN (
  'node.httpRequest.template',
  'node.image.template',
  'node.mailsend.template',
  'node.end.template',
  'node.switch.template',
  'node.llmAnswer.template',
  'node.exception.template',
  'node.googleSearch.template'
);

-- 自检（应全部为 0）：
-- SELECT COUNT(*) FROM sys_menu WHERE menu_id IN (11616,11618,11619,11620,11621,11622,11623,11624,11625,11626,11627,11629,11630,11631,11632,11633,11700,11701,11801,11802,11803,11804,11805,11806,11900,11901,11902,11903,11904,11905,11906,11907,2031361596464902145) OR perms LIKE 'workflow:%';
-- SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ipd_dev' AND (table_name LIKE 'flow\_%' OR table_name LIKE 't_workflow%' OR table_name='test_leave');
-- SELECT COUNT(*) FROM sys_config WHERE config_key LIKE 'node.%.template';
