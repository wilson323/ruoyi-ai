package org.ruoyi.ipd.hr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 内部 JSON 序列化/反序列化辅助。
 *
 * <p>2026-10-07 修复：序列化由 Hutool 切换为 Jackson——Hutool 不识别 Jackson 的
 * {@code @JsonProperty}，导致 {@code SyncBody}（HEAD/BODY/INPUT_TYP 契约键名）序列化
 * 输出小驼峰（head/body/inputTyp），与 EHR 网关/官方 demo/probe 口径不符。
 * 签名（{@link HrSignatureUtil}）与请求体（{@link HrApiClient} / {@link HrTokenClient}）
 * 必须共用本方法序列化，保证两侧字符串逐字节一致。
 */
final class JsonUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private JsonUtil() { }
    static String toJsonString(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JsonUtil.toJsonString 序列化失败: " + e.getMessage(), e);
        }
    }
    /** 解析 HR 响应根节点（Jackson JsonNode）。 */
    static JsonNode parseTree(String raw) {
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new RuntimeException("JsonUtil.parseTree 解析失败: " + e.getMessage(), e);
        }
    }
}
