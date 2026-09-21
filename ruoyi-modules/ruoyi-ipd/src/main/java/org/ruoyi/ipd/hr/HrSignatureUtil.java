package org.ruoyi.ipd.hr;

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
            sb.append(e.getKey()).append('=').append(e.getValue() == null ? "" : e.getValue().toString()).append('&');
        }
        sb.append("secretKey=").append(secretKey == null ? "" : secretKey);
        return md5Hex(sb.toString());
    }

    static String md5Hex(String src) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] bytes = md.digest(src.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(32);
            for (byte b : bytes) {
                sb.append(String.format("%02X", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
