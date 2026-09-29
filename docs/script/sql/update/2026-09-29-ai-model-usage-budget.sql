-- C2 模型与预算（2026-09-29）：AI 用量账本 + 模型预算（预占→消耗→结算）
-- 口径（owner 2026-09-29 拍板）：预算分配维度 = 按模型配置 × 自然月；预算单位 = token。
-- 多租户：两表均登记父 application.yml 的 tenant.excludes（IPD 单企业私有部署、无多租户语义，
--       与 ai_model_configs 同策略），Mapper 侧 @InterceptorIgnore(tenantLine="true") 双保险。
-- apply 状态：owner 已授权 apply ipd_dev（2026-09-29）；apply 后以 p1-ddl-apply-check.py 取证，
--       「SQL 已 commit」不等于「约束已生效」。

-- ① 用量账本：每次 AI 调用落一行（含失败），usage 采集面已有（AiChatResult.promptTokens/completionTokens
--    双路解析），本表补齐「落库记账」缺口；不做更新，只追加。
CREATE TABLE IF NOT EXISTS ai_model_usage_ledger (
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    model_config_id   BIGINT       NOT NULL COMMENT 'ai_model_configs.id（模型权威轴）',
    actor_id          VARCHAR(64)  NULL COMMENT '调用方 Person id（可空：系统触发）',
    scene             VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '业务场景（copilot/gate_precheck/bid_check 等）',
    prompt_tokens     INT          NOT NULL DEFAULT 0 COMMENT 'usage.prompt_tokens（响应缺失记 0）',
    completion_tokens INT          NOT NULL DEFAULT 0 COMMENT 'usage.completion_tokens（响应缺失记 0）',
    latency_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '出站调用耗时',
    status            VARCHAR(32)  NOT NULL DEFAULT 'ok' COMMENT 'ok / 失败错误码',
    trace_id          VARCHAR(64)  NULL COMMENT '链路追踪 id（TraceIdFilter）',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '落账时间',
    PRIMARY KEY (id),
    KEY idx_model_created (model_config_id, created_at),
    KEY idx_actor (actor_id),
    KEY idx_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='AI 用量账本（C2）';

-- ② 模型预算：按模型配置 × 自然月一行；预占（preoccupied）→ 消耗（consumed）→ 月末结算。
--    超支拒绝在预占口：preoccupied + consumed + 本次预估 > budget_tokens 即拒绝新请求；
--    结算恒执行（记账事实不丢），实际用量超预算的差额进 overage_tokens 披露，不吞账。
--    无预算行 = 该模型该月不限额（只记账）——避免缺预算行把线上对话全部锁死。
CREATE TABLE IF NOT EXISTS ai_model_budget (
    id                  BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    model_config_id     BIGINT      NOT NULL COMMENT 'ai_model_configs.id',
    budget_month        CHAR(7)     NOT NULL COMMENT '自然月 ''2026-09''',
    budget_tokens       BIGINT      NOT NULL DEFAULT 0 COMMENT '月度预算（token）',
    preoccupied_tokens  BIGINT      NOT NULL DEFAULT 0 COMMENT '预占中（未结算）',
    consumed_tokens     BIGINT      NOT NULL DEFAULT 0 COMMENT '已结算消耗',
    overage_tokens      BIGINT      NOT NULL DEFAULT 0 COMMENT '超支披露（结算时记，不吞账）',
    version             BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁（CAS 预占）',
    created_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_month (model_config_id, budget_month)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT ='AI 模型月度预算（C2 预占→消耗→结算）';
