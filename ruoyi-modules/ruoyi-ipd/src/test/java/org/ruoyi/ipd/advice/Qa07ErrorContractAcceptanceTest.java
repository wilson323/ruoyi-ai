package org.ruoyi.ipd.advice;

import cn.dev33.satoken.exception.FirewallCheckException;
import cn.dev33.satoken.servlet.model.SaRequestForServlet;
import cn.dev33.satoken.servlet.model.SaResponseForServlet;
import cn.dev33.satoken.strategy.SaFirewallStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.config.IpdFirewallResponseConfig;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-07 三个错误码缺口的契约验收（真逻辑断言，直接调用 handler / 防火墙回调）：
 * <ol>
 *   <li>缺口①：/api/v1/** 下无 handler / 无静态资源 → 404 + IPD 包络（code=50001/message）；
 *       非 IPD 路径复刻基线 R 包络（不误伤旧通道）</li>
 *   <li>缺口②：路径参数类型错 → 400/10001（修复前被兜底成 500/90001）</li>
 *   <li>缺口③：sa-token 防火墙拦截（URL 含 //）→ 400/10001 JSON（修复前 text/plain 纯文本）</li>
 * </ol>
 */
@Tag("dev")
class Qa07ErrorContractAcceptanceTest {

    private final IpdServiceExceptionAdvice advice = new IpdServiceExceptionAdvice();
    private final IpdNotFoundAdvice notFoundAdvice = new IpdNotFoundAdvice();

    @AfterEach
    void restoreFirewallHandle() {
        SaFirewallStrategy.instance.checkFailHandle = null;
    }

    @SuppressWarnings("unused")
    private static void qa07SampleHandler(@org.springframework.web.bind.annotation.PathVariable String id) {
    }

    // ---------- 缺口②：路径参数类型错 ----------

    @Test
    @DisplayName("缺口②：路径参数类型错 → 400/10001（修复前 500/90001）")
    void typeMismatchMapsToParamInvalid() {
        MethodArgumentTypeMismatchException e = new MethodArgumentTypeMismatchException(
            "abc", Long.class, "id", null, new RuntimeException("For input string: \"abc\""));
        ResponseEntity<ApiV1Response<Void>> r = advice.handleTypeMismatch(e);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().getCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID.getCode());
        // 不把底层异常细节（转换堆栈文案）透传给客户端
        assertThat(r.getBody().getMessage()).doesNotContain("For input string");
    }

    // ---------- 缺口③（MVC 层）：路径变量缺失 ----------

    @Test
    @DisplayName("缺口③ MVC 层：MissingPathVariableException → 400/10001 JSON")
    void missingPathVariableMapsToParamInvalid() throws Exception {
        var e = new org.springframework.web.bind.MissingPathVariableException("id", new MethodParameter(
            Qa07ErrorContractAcceptanceTest.class.getDeclaredMethod("qa07SampleHandler", String.class), 0));
        ResponseEntity<ApiV1Response<Void>> r = advice.handleMissingPathVariable(e);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().getCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID.getCode());
        assertThat(r.getBody().getMessage()).isEqualTo("路径参数缺失");
    }

    // ---------- 缺口①：不存在端点（无 handler / 无静态资源） ----------

    @Test
    @DisplayName("缺口①：/api/v1/ 不存在端点(NoHandlerFound) → 404 + IPD 包络 50001")
    void noHandlerInIpdDomainReturnsIpdEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/nonexistent-qa07-endpoint");
        Object result = notFoundAdvice.handleNoHandler(
            new NoHandlerFoundException("GET", "/api/v1/nonexistent-qa07-endpoint", HttpHeaders.EMPTY), request);
        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<ApiV1Response<Void>> r = (ResponseEntity<ApiV1Response<Void>>) result;
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().getCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND.getCode());
        assertThat(r.getBody().getMessage()).isEqualTo(ApiV1ErrorCode.NOT_FOUND.getMessage());
    }

    @Test
    @DisplayName("缺口①：/api/v1/ 静态资源未命中(NoResourceFound) → 404 + IPD 包络 50001")
    void noResourceInIpdDomainReturnsIpdEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/missing-asset.js");
        Object result = notFoundAdvice.handleNoResource(
            new NoResourceFoundException(HttpMethod.GET, "missing-asset.js"), request);
        assertThat(result).isInstanceOf(ResponseEntity.class);
        ResponseEntity<ApiV1Response<Void>> r = (ResponseEntity<ApiV1Response<Void>>) result;
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().getCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    @DisplayName("不误伤：非 IPD 路径 NoHandlerFound → 基线 R 包络（msg 字段，非 IPD 包络）")
    void noHandlerOutsideIpdDomainKeepsBaselineEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/chat/nonexistent");
        Object result = notFoundAdvice.handleNoHandler(
            new NoHandlerFoundException("GET", "/chat/nonexistent", HttpHeaders.EMPTY), request);
        assertThat(result).isInstanceOf(R.class);
        R<?> r = (R<?>) result;
        assertThat(r.getCode()).isEqualTo(404);
        assertThat(r.getMsg()).isEqualTo("请求地址不存在");
    }

    @Test
    @DisplayName("不误伤：非 IPD 路径 NoResourceFound → 基线 R 包络 code=500（原 handleServletException 契约）")
    void noResourceOutsideIpdDomainKeepsBaselineEnvelope() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/static/missing.png");
        Object result = notFoundAdvice.handleNoResource(
            new NoResourceFoundException(HttpMethod.GET, "missing.png"), request);
        assertThat(result).isInstanceOf(R.class);
        R<?> r = (R<?>) result;
        assertThat(r.getMsg()).isEqualTo("系统异常，请联系管理员");
    }

    // ---------- 缺口③（filter 层）：sa-token 防火墙拦截 ----------

    @Test
    @DisplayName("缺口③ filter 层：/api/v1/products//1 防火墙拦截 → 400/10001 JSON 包络")
    void firewallRejectInIpdDomainWritesJsonEnvelope() throws Exception {
        new IpdFirewallResponseConfig().routeFirewallFailToIpdEnvelope();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("GET", "/api/v1/products//1");
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        FirewallCheckException e = new FirewallCheckException("非法请求：/api/v1/products//1");
        SaFirewallStrategy.instance.checkFailHandle.run(e,
            new SaRequestForServlet(mockRequest), new SaResponseForServlet(mockResponse), null);
        assertThat(mockResponse.getStatus()).isEqualTo(400);
        assertThat(mockResponse.getContentType()).contains("application/json");
        String body = mockResponse.getContentAsString();
        assertThat(body).contains("\"code\":10001");
        assertThat(body).contains("\"message\":\"非法请求路径\"");
        // 不回显请求路径，防反射
        assertThat(body).doesNotContain("/api/v1/products//1");
    }

    @Test
    @DisplayName("不误伤：非 IPD 路径防火墙拦截 → 保持 sa-token 默认 text/plain 行为")
    void firewallRejectOutsideIpdDomainKeepsDefaultBehavior() throws Exception {
        new IpdFirewallResponseConfig().routeFirewallFailToIpdEnvelope();
        MockHttpServletRequest mockRequest = new MockHttpServletRequest("GET", "/chat//double");
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        FirewallCheckException e = new FirewallCheckException("非法请求：/chat//double");
        SaFirewallStrategy.instance.checkFailHandle.run(e,
            new SaRequestForServlet(mockRequest), new SaResponseForServlet(mockResponse), null);
        assertThat(mockResponse.getStatus()).isEqualTo(200);
        assertThat(mockResponse.getContentType()).contains("text/plain");
        assertThat(mockResponse.getContentAsString()).isEqualTo("非法请求：/chat//double");
    }
}
