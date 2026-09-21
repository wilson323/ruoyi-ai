package org.ruoyi.ipd.hr;

/**
 * HR 平台 API 异常（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>{@code code} 语义：HTTP 状态码（4xx/5xx）；HR 业务码（0 成功 / 其他异常，参见 HR 文档第二章）。
 * {@code rawBody} 用于排障，**严禁日志打印全 body**——可能含敏感 PII。
 */
public class HrApiException extends RuntimeException {

    private final int code;
    private final String rawBody;

    public HrApiException(int code, String message, String rawBody) {
        super(message);
        this.code = code;
        this.rawBody = rawBody;
    }

    public int getCode() { return code; }
    public String getRawBody() { return rawBody; }
}
