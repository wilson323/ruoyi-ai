package org.ruoyi.ipd.hr;

/**
 * HR 接口业务异常（FA-HR-Sync·2026-09-21 R149）。
 *
 * <p>承载 HR 业务异常码 + 原始响应（rawBody 包含 PII，日志必须 mask）。
 *
 * <p><b>异常分类</b>（R149-v1 D1.6）：
 * <ul>
 *   <li>{@link #PERMANENT}：参数校验失败 / 工号不存在 / 数据格式非法 — 不重试</li>
 *   <li>{@link #TRANSIENT}：网络超时 / HR 服务 5xx / token 临时失败 — 可重试</li>
 * </ul>
 *
 * <p>HR 文档 code 1006/1007（token invalid）由 {@code HrApiClient} 自动 refresh + retry 一次，
 * 仍失败时按 TRANSIENT 上抛；其余非 0 业务码按 PERMANENT 上抛。
 */
public class HrApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 永久性失败（不重试）。 */
    public static final int PERMANENT = 1;
    /** 临时性失败（可重试）。 */
    public static final int TRANSIENT = 2;

    private final int kind;
    private final String hrCode;
    private final String rawBody;

    public HrApiException(int kind, String hrCode, String message, String rawBody) {
        super(message);
        this.kind = kind;
        this.hrCode = hrCode;
        this.rawBody = rawBody;
    }

    public int getKind() { return kind; }
    public String getHrCode() { return hrCode; }
    public String getRawBody() { return rawBody; }

    public boolean isPermanent() { return kind == PERMANENT; }
    public boolean isTransient() { return kind == TRANSIENT; }

    /** 日志安全摘要：异常码 + 消息前 80 字符 + rawBody 前 200 字符（避免 PII 全量外泄）。 */
    public String safeSummary() {
        String msg = getMessage() == null ? "" : getMessage();
        String raw = rawBody == null ? "" : rawBody.substring(0, Math.min(200, rawBody.length()));
        return "kind=" + kind + " hrCode=" + hrCode + " msg=" + msg + " raw=" + raw;
    }
}
