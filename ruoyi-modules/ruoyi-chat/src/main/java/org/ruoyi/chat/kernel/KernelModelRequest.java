package org.ruoyi.chat.kernel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;

/**
 * W2 模型路由入参（2026-09-28，ADR-0075 矩阵 #9「包装」薄适配面）。
 *
 * <p>当前 ruoyi-chat 来源：请求 {@code model} 字段（{@code modelName}）+
 * {@code chat_model} 的 {@link ChatModelVo}；IPD {@code ai_model_configs} 尚未接入。
 * （{@code providerCode}/{@code apiKey}/{@code apiHost}，凭据落位纪律见 W1 入条件）。
 * 身份维度（userId/sessionId 等）不入本对象——矩阵 #1/#4 收口在 {@link KernelScopeKey}，
 * 模型层与隔离键不混线。
 *
 * <p>本对象只携带装配数据，不承载任何选型/解析逻辑：解析唯一源 = AgentScope
 * {@code ModelRegistry}（{@link KernelModelSelector}），禁止第二套路由。
 */
public record KernelModelRequest(String modelName, String providerCode, String apiKey, String apiHost) {

    /** 缓存身份仅存摘要；同名模型的端点或凭据变化必须得到不同身份。 */
    String configurationIdentity() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, modelName);
            update(digest, providerCode);
            update(digest, apiHost);
            update(digest, apiKey);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (value == null ? 0 : 1));
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    @Override
    public String toString() {
        return "KernelModelRequest[modelName=" + modelName + ", providerCode=" + providerCode
                + ", apiKey=<redacted>, apiHost=<redacted>]";
    }

    /** 从现有 ruoyi-chat 的 chat_model 查询结果映射；不是 IPD 模型配置。 */
    public static KernelModelRequest from(ChatModelVo vo) {
        return vo == null
                ? null
                : new KernelModelRequest(vo.getModelName(), vo.getProviderCode(), vo.getApiKey(), vo.getApiHost());
    }

    /** 请求未带完整配置时只携 modelName；解析失败时不隐式切换模型。 */
    public static KernelModelRequest from(ChatModelVo vo, String modelName) {
        return vo != null ? from(vo) : new KernelModelRequest(modelName, null, null, null);
    }
}
