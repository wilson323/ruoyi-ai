package org.ruoyi.domain.dto.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 市场工具元数据脱敏投影器（Track E2-BE-1）。
 *
 * <p>原始 {@code mcp_market_tool.tool_metadata} 是市场源透传的 JSON，可能携带
 * provider 内部信息甚至凭据（对照 Global Constraints #19 write-only 精神）。投影
 * 规则保守三层：
 * <ol>
 *   <li>命名否决：键名命中 {@code (key|token|secret|password|credential|auth)}
 *       （忽略大小写）一律丢弃，即使出现在白名单里也丢弃；</li>
 *   <li>白名单：只保留 {@link #DISPLAY_KEYS}；</li>
 *   <li>形态收窄：仅保留标量与「标量数组」；对象/嵌套结构整体丢弃（不递归展开，
 *       防止攻击者把密值藏进第二层）。</li>
 * </ol>
 *
 * <p>解析失败/空输入返回空 Map（不断列表接口，前端对应空态「元数据未透出」）。
 */
public final class McpMarketMetadataRedactor {

    /** 展示安全键白名单（探针实证后可增删；新增键必须过第 1 层命名否决）。 */
    private static final Set<String> DISPLAY_KEYS = Set.of(
        "description", "homepage", "repository", "license",
        "tags", "categories", "author", "version", "icon", "readme"
    );

    private static final Pattern SENSITIVE_KEY =
        Pattern.compile("(?i).*(key|token|secret|password|credential|auth).*");
    /** 白名单字段仍可能在自由文本里夹带凭据；命中时丢弃整个字段。 */
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
        "(?i)(?:\\b(?:api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|authorization)\\b\\s*[:=]\\s*\\S+"
            + "|\\bBearer\\s+\\S+|\\bsk-[A-Za-z0-9_-]{12,}\\b|https?://[^\\s/@]+:[^\\s/@]+@)"
    );

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpMarketMetadataRedactor() {
    }

    public static Map<String, Object> redact(String toolMetadataJson) {
        if (toolMetadataJson == null || toolMetadataJson.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw;
        try {
            raw = MAPPER.readValue(toolMetadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
        Map<String, Object> view = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (SENSITIVE_KEY.matcher(key).matches()) {
                continue;
            }
            if (!DISPLAY_KEYS.contains(key)) {
                continue;
            }
            if (value == null) {
                continue;
            }
            if (value instanceof List<?> list && isScalarList(list)
                && list.stream().noneMatch(McpMarketMetadataRedactor::containsSensitiveValue)) {
                view.put(key, list);
            } else if (value instanceof Map<?, ?>) {
                continue;
            } else if (!(value instanceof List<?>) && !containsSensitiveValue(value)) {
                view.put(key, value);
            }
        }
        return view;
    }

    private static boolean isScalarList(List<?> list) {
        for (Object item : list) {
            if (item instanceof Map<?, ?> || item instanceof List<?>) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsSensitiveValue(Object value) {
        return value instanceof String text && SENSITIVE_VALUE.matcher(text).find();
    }
}
