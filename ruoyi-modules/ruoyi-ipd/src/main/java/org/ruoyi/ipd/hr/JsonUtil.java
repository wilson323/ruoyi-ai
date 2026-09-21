package org.ruoyi.ipd.hr;

/**
 * 轻量 JSON 序列化（仅用于签名时 data 字段的 JSON.stringify）。
 *
 * <p>HR 文档示例使用 fastjson 的 {@code JSONObject.toJSONString}；本项目为减少三方依赖，
 * 复用一个 Hutool 内置的轻量实现（Hutool 已在 ruoyi-common-core 引入）。
 * 若以后需要更复杂序列化可替换为 fastjson2。
 */
final class JsonUtil {

    private JsonUtil() {}

    static String toJsonString(Object value) {
        if (value == null) return "";
        // null 兜底用 Hutool JSONUtil；非 null 直接走 Hutool 链式 API
        return cn.hutool.json.JSONUtil.toJsonStr(value);
    }
}
