package org.ruoyi.ipd.hr;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HR 平台签名工具（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>严格按接口文档 §1.2 实现：
 * <ol>
 *   <li>所有 API 入参（除 sign）按 ASCII 升序排序</li>
 *   <li>{@code key=value&...&} 形式拼接，空值跳过；data 字段 JSON.stringify 后参与拼接</li>
 *   <li>末尾追加 {@code secretKey=xxx}</li>
 *   <li>UTF-8 → MD5 → 十六进制大写 → 去前导 0 → 转大写无符号正整数（BigInteger(1,).toString(16)）</li>
 * </ol>
 *
 * <p>实现严格匹配接口文档 §1.2.3 案例代码（{@code getSign + getMD5Value}）：
 * 排序方式、data JSON 化、拼装规则、空值跳过、secretKey 尾部追加、十六进制大写去前导 0。
 *
 * <p>注意：HR 文档使用 BigInteger(1, md5Bytes).toString(16).toUpperCase()，天然丢前导 0。
 * 本实现严格对齐。
 */
@Component
public class HrSignatureUtil {

    private static final char EQ = '=';
    private static final char AMP = '&';
    private static final String SIGN_KEY = "sign";
    private static final String SERIAL_KEY = "serialVersionUID";

    /**
     * 拼接待签字符串（不含最终 MD5）。
     * <p>辅助函数：单测可见，便于对照文档示例。
     */
    public String buildSignString(Map<String, Object> params, String secretKey) {
        if (params == null || params.isEmpty()) {
            return "secretKey=" + nvl(secretKey);
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(params.entrySet());
        // 按 key 的 ASCII 升序（与文档一致；与 JDK 默认 String.compareTo 等价）
        entries.sort(Comparator.comparing(e -> e.getKey() == null ? "" : e.getKey()));
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> item : entries) {
            String key = item.getKey();
            if (key == null || StrUtil.isEmpty(key)) continue;
            if (SIGN_KEY.equals(key) || SERIAL_KEY.equals(key)) continue;
            String val;
            if ("data".equals(key)) {
                // data 字段需 JSON.stringify（fastjson2/Hutool 都可，文档示例是 JSONObject.toJSONString）
                val = item.getValue() == null ? "" : JsonUtil.toJsonString(item.getValue());
            } else {
                val = item.getValue() == null ? "" : item.getValue().toString();
            }
            if (StrUtil.isEmpty(val)) continue;
            sb.append(key).append(EQ).append(val).append(AMP);
        }
        sb.append("secretKey=").append(nvl(secretKey));
        return sb.toString();
    }

    /**
     * 计算签名（调用方传入拼装好的参数 + secretKey，返回大写十六进制；前置 0 自动丢失）。
     */
    public String sign(Map<String, Object> params, String secretKey) {
        String src = buildSignString(params, secretKey);
        return md5Hex(src);
    }

    /**
     * MD5 字节流 → BigInteger(1,) → 十六进制大写（去前导 0 与文档保持一致）。
     */
    public String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return new java.math.BigInteger(1, bytes).toString(16).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not supported", e);
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}
