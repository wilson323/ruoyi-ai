package org.ruoyi.ipd.hr;

/**
 * HR 接口业务异常（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>承载 HR 业务异常码 + 原始响应（rawBody 包含 PII，日志必须 mask）。
 */
public class HrApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String rawBody;

    public HrApiException(int code, String message, String rawBody) {
        super(message);
        this.code = code;
        this.rawBody = rawBody;
    }

    public int getCode() { return code; }
    public String getRawBody() { return rawBody; }

    /** 日志安全摘要：异常码 + 消息前 80 字符 + rawBody 前 200 字符（避免 PII 全量外泄）。 */
    public String safeSummary() {
        String msg = getMessage() == null ? "" : getMessage();
        String raw = rawBody == null ? "" : rawBody.substring(0, Math.min(200, rawBody.length()));
        return "code=" + code + " msg=" + msg + " raw=" + raw;
    }
}
