-- =====================================================================
-- R31（2026-09-27）：工作流 checkpoint 落库 + 僵尸 DOING 处置（补遗 §5-5 第 5 项）
-- =====================================================================
-- 【用途】
--   langgraph4j 内存 checkpoint（MemorySaver，每次 run 新建、内存即失）换 MySQL 落库，
--   自实现官方扩展点 org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver（langgraph4j-core 1.8.20），
--   治两个老毛病：①流程重启后实例永久卡"执行中"（僵尸 DOING）②断点无法恢复续跑。
--   零新依赖：CRUD 走 MyBatis-Plus（WorkflowCheckpointMapper），序列化复用官方 CheckpointSerializer。
--
-- 【代码落点】（同批交付）
--   entity   org.ruoyi.workflow.entity.WorkflowCheckpoint
--   mapper   org.ruoyi.workflow.mapper.WorkflowCheckpointMapper
--   saver    org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver
--   接线     WorkflowEngine（MemorySaver→JdbcCheckpointSaver；thread_id = t_workflow_runtime.uuid）
--   处置入口 WorkflowRuntimeService.failZombieDoingRuntimes（status=2→4，status_remark 落
--            「进程中断，可从断点续跑」语义区分，不新增 status 枚举值）
--   续跑入口 WorkflowStarter.resumeRuntime（thread 最新 checkpoint → nextNodeId 续跑）
--
-- 【列设计依据】
--   thread_id     = t_workflow_runtime.uuid（varchar(32) 原表口径，此处放宽 varchar(64) 防 UUID 形态溢出）
--   checkpoint_id = langgraph4j Checkpoint.getId()（UUID 字符串，36 字符 → varchar(64)）
--   node_id/next_node_id = Checkpoint.getNodeId()/getNextNodeId()（节点 uuid，Checkpoint 构造器强制非空 → NOT NULL）
--   state_json    = CheckpointSerializer（ObjectStream 二进制）→ Base64 文本；state Map 含 NodeIOData 等
--                   业务对象（全家族 Serializable，与 WorkflowGraphBuilder 的 ObjectStreamStateSerializer
--                   同机制往返保真）；MEDIUMTEXT（64KB 上限，长文本节点输出堆叠留余量）
--   栈序还原：基类 put 用 push 头插、get 用 peek 取最新；本表按 id 升序（插入序）读全量逐条 push 还原
--             「最新在头」，updatedCheckpoint 按 checkpoint_id 就地替换（行 id 不变即保留栈位）
--   其余列对齐 t_workflow_runtime 惯例（create_time/update_time/is_deleted/tenant_id）
--
-- 【多租户】（必读）
--   本表带 tenant_id 列（与现有表风格一致）但**必须**登记父 application.yml 的 tenant.excludes
--   （租户共享，已同批登记）：断点恢复场景无租户上下文，且 AbstractCheckpointSaver 扩展点 API
--   只有 RunnableConfig 拿不到租户；不登记则租户拦截器追加 WHERE tenant_id=? 表现为「查不到
--   checkpoint」，断点续跑全断。
--
-- ★ 生产环境 apply 需 DBA 窗口（本脚本 2026-09-27 已在 ipd_dev@127.0.0.1:13306 验证执行）。
-- 幂等：DROP + CREATE（表结构 DDL，重复执行结果一致）。
-- 回滚：DROP TABLE t_workflow_checkpoint;（数据为断点缓存，可弃；旧代码回退后走内存 checkpoint 不受影响）

DROP TABLE IF EXISTS `t_workflow_checkpoint`;
CREATE TABLE `t_workflow_checkpoint`  (
    `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `thread_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT 'langgraph4j thread id，= t_workflow_runtime.uuid（缺省桶 $default）',
    `checkpoint_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT 'langgraph4j Checkpoint.id（UUID 字符串）',
    `node_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT 'Checkpoint.nodeId：最近完成的节点 uuid',
    `next_node_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT '' COMMENT 'Checkpoint.nextNodeId：下次执行的节点 uuid（断点续跑从这里继续）',
    `state_json` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL COMMENT 'Checkpoint 序列化载荷：CheckpointSerializer(ObjectStream 二进制) 的 Base64，含 state Map（NodeIOData 等业务对象）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常，1 已删',
    `tenant_id` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT '000000' COMMENT '租户编号',
    PRIMARY KEY (`id`) USING BTREE,
    INDEX `idx_thread_id`(`thread_id` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 1 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = '工作流 checkpoint | Workflow Checkpoint' ROW_FORMAT = DYNAMIC;
