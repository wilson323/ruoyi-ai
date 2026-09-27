package org.ruoyi.ipd.dto;

import java.util.Map;

/**
 * R227-C1（AI-FUSION L2，2026-09-26）：域内 AI 建议响应体（方案 §5.1）。
 * <ul>
 *   <li>{@code markdown} AI 输出正文，仅返回给前端展示，**绝不写业务表**（方案 §5.1 强约束）；
 *       用户是否采纳、采纳到哪个字段由前端页面人工决定；</li>
 *   <li>{@code scene} 回显请求场景（前端多入口共用组件时用于分发"采纳"行为）；</li>
 *   <li>{@code aiModel} 实际调用模型名；未启用/失败降级时填 {@code "intent_match"}
 *       （AI-审计三件套规约 §1：白名单占位，门禁知道非真模型）；</li>
 *   <li>{@code degraded} true = 本轮没有真调 AI（模型未配置等降级路径），前端据此展示引导文案；</li>
 *   <li>{@code card} R232-P1-02（2026-09-27）：4 结构化场景（gate.precheck-checklist /
 *       gate.conclusion-draft / project.create.suggest / demand.create.from-requirement）的
 *       结构化卡片载荷（§2.2 cardPayload 契约：{type, version, data, sourceRefs}）；3 轻场景与
 *       降级/异常路径恒 null（纯文本零变化，文本降级路径永不删）。data 字段名清单唯一事实源 =
 *       system_configs 行 ai.suggest.cardCatalog（Schema Catalog，禁硬编码）；data 值全部经
 *       sourceRefs 指向的业务表回读（R3 铁律，LLM 复述值不进 card）。</li>
 * </ul>
 */
public record AiSuggestResp(String scene,
                            String markdown,
                            String aiModel,
                            int promptTokens,
                            int completionTokens,
                            long latencyMs,
                            boolean degraded,
                            Card card) {

    /** 兼容构造（无 card 语义的旧调用面：card 恒 null = 纯文本响应）。 */
    public AiSuggestResp(String scene, String markdown, String aiModel,
                         int promptTokens, int completionTokens, long latencyMs, boolean degraded) {
        this(scene, markdown, aiModel, promptTokens, completionTokens, latencyMs, degraded, null);
    }

    /** 降级应答构造（未配置模型 / 空上下文等不调 AI 的路径；降级恒无 card）。 */
    public static AiSuggestResp degraded(String scene, String markdown, long latencyMs) {
        return new AiSuggestResp(scene, markdown, "intent_match", 0, 0, latencyMs, true);
    }

    /**
     * cardPayload（母文件 §2.2 契约）：前端注册表按 (type, version) 匹配渲染组件。
     *
     * @param type       卡片类型（Catalog 注册键，如 gate.precheck）
     * @param version    schema 版本（Catalog 中该 type 的 version）
     * @param data       结构化数据，字段名/类型与 Catalog 该 type 的字段清单逐项对齐（schema 外字段不产出）
     * @param sourceRefs R3 事实源引用（逻辑名 → 业务表行 id），data 值可经其回读业务表对账
     */
    public record Card(String type,
                       int version,
                       Map<String, Object> data,
                       Map<String, Object> sourceRefs) {
    }
}
