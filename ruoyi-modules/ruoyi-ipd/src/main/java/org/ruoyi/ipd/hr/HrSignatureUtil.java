package org.ruoyi.ipd.hr;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/**
 * HR 平台签名工具（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>按 HR 文档 §1.2.3：
 * <ol>
 *   <li>按 key 字典序拼接所有非空参数（sign/serialVersionUID 跳过）</li>
 *   <li>末尾追加 {@code &secretKey=...}</li>
 *   <li>MD5 → 32 位大写 hex（BigInteger(1,).toString(16).toUpperCase()）</li>
 * </ol>
 *
 * <p>{@code data} 字段需先用 {@link JsonUtil} 序列化为 JSON 字符串后再走字典序拼接。
 */
@Component
public class HrSignatureUtil {

    public String sign(Map<String, Object> params, String secretKey) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String k = e.getKey();
                if (k == null || "sign".equals(k) || "serialVersionUID".equals(k)) continue;
                sorted.put(k, e.getValue());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            Object v = e.getValue();
            // HR §1.2.3：空值（null/空串）整条跳过，不参与签名
            if (v == null || v.toString().isEmpty()) continue;
            String vs = (v instanceof Map || v instanceof java.util.Collection)
                ? JsonUtil.toJsonString(v)   // data 等结构值按 JSON 序列化后参与签名
                : v.toString();
            sb.append(e.getKey()).append('=').append(vs).append('&');
        }
        sb.append("secretKey=").append(secretKey == null ? "" : secretKey);
        return md5Hex(sb.toString());
    }

    static String md5Hex(String src) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(src.getBytes(StandardCharsets.UTF_8));
            // 与 HR 文档 getMD5Value 样例同口径：BigInteger(1,).toString(16) 去前导 0（网关端同样去前导 0）
            return new BigInteger(1, bytes).toString(16).toUpperCase();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
