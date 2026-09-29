package org.ruoyi.ipd.advice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CONTRACT-01（D 轮 D2 §4.3 真红 → D4 P1-2，2026-09-29）：405 响应不得泄漏框架格式。
 *
 * <p>修复前：{@code DELETE /api/v1/projects/{id}} → HTTP 200 {@code {code:405,msg:"请求方式不支持"}}
 * （基线 GlobalExceptionHandler 的 R 包络），违反 IPD 契约（应为 HTTP 405 +
 * code/message/data/timestamp/traceId）。修复后由 {@link IpdNotFoundAdvice#handleMethodNotSupported}
 * 承接：IPD 域返回 HTTP 405 + 标准包络，非 IPD 域保持基线 R 包络零变化
 * （双侧逐字对照由 {@link IpdNotFoundBaselineParityTest} 哨兵钉扎）。
 *
 * <p>模式沿用 {@link Qa07ErrorContractAcceptanceTest}：纯 POJO 直调 + MockHttpServletRequest，
 * 不启动 Spring 上下文。
 */
@Tag("dev")
@DisplayName("CONTRACT-01：405 响应包络（IPD 标准包络 / 非 IPD 基线 R 包络）")
class Contract01MethodNotAllowedEnvelopeTest {

    private final IpdNotFoundAdvice advice = new IpdNotFoundAdvice();

    @Test
    @DisplayName("IPD 域：DELETE /api/v1/projects/{id} → HTTP 405 + code/message/data/timestamp/traceId 标准包络")
    void methodNotAllowedInIpdDomainReturnsIpdEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/v1/projects/9140001");
        Object result = advice.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE"), request);

        assertThat(result).isInstanceOf(ResponseEntity.class);
        @SuppressWarnings("unchecked")
        ResponseEntity<ApiV1Response<Void>> r = (ResponseEntity<ApiV1Response<Void>>) result;

        // 修复前为 HTTP 200 + {code:405,msg}——HTTP 状态与包络形状双钉扎
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        ApiV1Response<Void> body = r.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(ApiV1ErrorCode.METHOD_NOT_SUPPORTED.getCode());
        assertThat(body.getMessage()).isEqualTo("请求方式不支持");
        // 标准包络五字段形状（D2 §4.3：code/message/data/timestamp/traceId）
        assertThat(body.getData()).isNull();
        assertThat(body.getTimestamp()).isNotBlank();
        assertThat(body.getTraceId()).isNotBlank();
    }

    @Test
    @DisplayName("不误伤：非 IPD 路径 405 → 基线 R 包络（HTTP 200 code=405，msg 字段）")
    void methodNotAllowedOutsideIpdDomainKeepsBaselineEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/chat/some-endpoint");
        Object result = advice.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("DELETE"), request);

        assertThat(result).isInstanceOf(R.class).isNotInstanceOf(ResponseEntity.class);
        R<?> r = (R<?>) result;
        assertThat(r.getCode()).isEqualTo(405);
        assertThat(r.getMsg()).isEqualTo("请求方式不支持");
    }
}
