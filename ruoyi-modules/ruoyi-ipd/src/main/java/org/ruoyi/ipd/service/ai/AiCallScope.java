package org.ruoyi.ipd.service.ai;

import java.util.Objects;

import org.ruoyi.ipd.security.IpdActor;

/**
 * C2-3 用量记账身份（2026-09-29）：随 {@link AiTestConfig#scope()} 携带，{@link AiGateway}
 * 据此做预算预占→结算（{@code ai_model_budget}）与用量落账（{@code ai_model_usage_ledger}）。
 *
 * <p>scope 为 null 的调用 =「无记账面」（连通性测试、embed 增强链路等），既不预占也不落账——
 * 语义是不纳入预算轴，而非免费。预算维度 = 模型配置 × 自然月（owner 2026-09-29 拍板，token 单位）。
 *
 * @param modelConfigId {@code ai_model_configs.id}（模型权威轴，必填）
 * @param actorId       调用方 Person id 字符串（可空：系统触发，账本 actor_id 允许 NULL）
 * @param scene         业务场景（suggest / gate_precheck / bid_check / bid_compare / generate 等）
 */
public record AiCallScope(Long modelConfigId, String actorId, String scene) {

    public AiCallScope {
        Objects.requireNonNull(modelConfigId, "modelConfigId");
        scene = scene == null ? "" : scene;
    }

    /**
     * 从会话 actor 构造（actor 或 id 为空 → actorId 记 null，即系统触发口径）。
     * modelConfigId 为空 → 返回 null（无记账面，与旧口径一致）：生产配置恒有 DB 主键，
     * 空 ID 仅出现在测试桩未 stub 的场景，此时不记账而非报错（载波可选语义）。
     */
    public static AiCallScope of(Long modelConfigId, IpdActor actor, String scene) {
        if (modelConfigId == null) {
            return null;
        }
        String actorId = actor == null || actor.id() == null ? null : String.valueOf(actor.id());
        return new AiCallScope(modelConfigId, actorId, scene);
    }
}
