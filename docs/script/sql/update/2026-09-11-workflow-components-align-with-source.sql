-- 工作流组件库与源码执行器对齐（2026-09-11）
--
-- 背景：组件库由本表驱动（前端画布左侧组件列表），而「哪些组件可执行」的
-- 事实源在后端源码（ruoyi-aiflow 模块）：
--   workflow/workflow/WfComponentNameEnum（组件名枚举）
--   workflow/workflow/WfNodeFactory（执行器分支工厂）
--   workflow/workflow/node/（执行器实现，9 个）
-- 上游发布档 ruoyi-ai.sql 该表仅 5 行（Start/End/Answer/Switcher/Google），
-- 导致「源码有执行器、库里未注册」的 4 个组件在画布上不可选。
--
-- 本脚本按源码执行器清单补齐 4 行（幂等：按 name + tenant_id 判重，已存在则跳过）：
--   Tongyiwanx         → image/ImageNode
--   MailSend           → mailSend/MailSendNode
--   KnowledgeRetrieval → knowledgeRetrieval/KnowledgeRetrievalNode
--   HttpRequest        → httpRequest/HttpRequestNode
-- 注：uuid / display_order 为本地生成值（官方发布档无对应行可考）；title / remark 与现库一致。
--
-- 明确不补（后端源码层断链，非本脚本遗漏）：
--   Dalle3 / FaqExtractor —— WfNodeFactory 无对应分支（default 返回 null），
--   node/ 下无执行器实现；前端仅有 NodeShell 转发壳（12 行，无专属实现），
--   属官方历史预留位。组件库由本表驱动——不注册则画布不可拖出，保持现状即正确。
--
-- 执行方式：人工/DBA apply（仓库无 Flyway/Liquibase），可重复执行。

-- 通义万相-画图（执行器：image/ImageNode）
INSERT IGNORE INTO `t_workflow_component`
    (`uuid`, `name`, `title`, `remark`, `display_order`, `is_enable`,
     `create_time`, `update_time`, `is_deleted`, `tenant_id`)
SELECT
    '3549bf7d55be48a1bcf9a854a6ce1233',
    'Tongyiwanx',
    '通义万相-画图',
    '调用文生图模型生成图片',
    20,
    1,
    NOW(),
    NOW(),
    0,
    '000000'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM `t_workflow_component`
    WHERE `name` = 'Tongyiwanx'
      AND `tenant_id` = '000000'
);

-- 邮件发送（执行器：mailSend/MailSendNode）
INSERT IGNORE INTO `t_workflow_component`
    (`uuid`, `name`, `title`, `remark`, `display_order`, `is_enable`,
     `create_time`, `update_time`, `is_deleted`, `tenant_id`)
SELECT
    'a728ec7370bc44db853250a61c05544c',
    'MailSend',
    '邮件发送',
    '发送邮件到指定邮箱',
    25,
    1,
    NOW(),
    NOW(),
    0,
    '000000'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM `t_workflow_component`
    WHERE `name` = 'MailSend'
      AND `tenant_id` = '000000'
);

-- 知识检索（执行器：knowledgeRetrieval/KnowledgeRetrievalNode）
INSERT IGNORE INTO `t_workflow_component`
    (`uuid`, `name`, `title`, `remark`, `display_order`, `is_enable`,
     `create_time`, `update_time`, `is_deleted`, `tenant_id`)
SELECT
    'b241e7fb9a354276bc946bc8b268772e',
    'KnowledgeRetrieval',
    '知识检索',
    '从知识库中检索信息，需选中知识库',
    30,
    1,
    NOW(),
    NOW(),
    0,
    '000000'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM `t_workflow_component`
    WHERE `name` = 'KnowledgeRetrieval'
      AND `tenant_id` = '000000'
);

-- Http请求（执行器：httpRequest/HttpRequestNode）
INSERT IGNORE INTO `t_workflow_component`
    (`uuid`, `name`, `title`, `remark`, `display_order`, `is_enable`,
     `create_time`, `update_time`, `is_deleted`, `tenant_id`)
SELECT
    '4e87666eaa7e4637b56fc9536c40e74e',
    'HttpRequest',
    'Http请求',
    '通过Http协议发送请求，可将其他组件的输出作为参数，也可设置常量作为参数。',
    35,
    1,
    NOW(),
    NOW(),
    0,
    '000000'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1
    FROM `t_workflow_component`
    WHERE `name` = 'HttpRequest'
      AND `tenant_id` = '000000'
);
