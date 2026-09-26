package org.ruoyi.ipd.service.aiexec;

/**
 * R221 执行器返回的结构化结果（spec §4.2）。
 * ok=true 走 SUCCEEDED（summary 落 result_summary，aiDocId 可选）；ok=false 走退避/DEAD（errorMsg 落 error_msg）。
 */
public record AiExecResult(boolean ok, String summary, Long aiDocId, String errorMsg) {

    public static AiExecResult ok(String summary) {
        return new AiExecResult(true, summary, null, null);
    }

    public static AiExecResult ok(String summary, Long aiDocId) {
        return new AiExecResult(true, summary, aiDocId, null);
    }

    public static AiExecResult fail(String errorMsg) {
        return new AiExecResult(false, null, null, errorMsg);
    }
}
