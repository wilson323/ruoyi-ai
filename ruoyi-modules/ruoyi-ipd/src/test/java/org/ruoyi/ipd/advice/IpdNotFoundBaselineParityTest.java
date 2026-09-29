package org.ruoyi.ipd.advice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.core.domain.R;
import org.ruoyi.common.web.handler.GlobalExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双侧对照哨兵：{@link IpdNotFoundAdvice} 的非 IPD 分支必须与基线
 * {@link GlobalExceptionHandler} 的<b>实际输出</b>逐字一致。
 *
 * <p>为什么需要本类：{@code IpdNotFoundAdvice} 以 {@code @Order(HIGHEST_PRECEDENCE + 2)}
 * 且<b>无 basePackages/assignableTypes 限定</b>注册，因此全仓所有路径的
 * {@link NoHandlerFoundException} / {@link NoResourceFoundException} 都由它先于基线接管——
 * 基线那两个 handler 实际已被遮蔽。它的非 IPD 分支是<b>手写复刻</b>基线响应
 * （{@code R.fail(HttpStatus.NOT_FOUND, "请求地址不存在")} 与 {@code R.fail("系统异常，请联系管理员")}），
 * 属契约双写点。既有 {@code Qa07ErrorContractAcceptanceTest} 只把这两条断言钉在<b>硬编码字面量</b>上，
 * 是单侧哨兵：基线若改文案或 code，那份测试照样全绿而真实契约已漂移。
 *
 * <p>本类改为直接调用真基线实例取期望值，两侧同源对照，任一侧单独改动即红。
 * 两侧 code 常量来源不同但同值（基线用 {@code cn.hutool.http.HttpStatus.HTTP_NOT_FOUND}，
 * advice 用 {@code org.ruoyi.common.core.constant.HttpStatus.NOT_FOUND}，均为 404），
 * 因此按<b>运行时实际值</b>而非按常量名比对。
 *
 * <p>设计要点：纯 POJO 直调 + MockHttpServletRequest，不启动 Spring 上下文；
 * 模式沿用同包 {@code Qa07ErrorContractAcceptanceTest}。前缀分支两个方向的破坏都能被捕获——
 * {@code isIpdApi} 恒 false 时本类两条用例红（IPD 域退化为基线 R 包络由 Qa07 用例红），
 * 恒 true 时本类两条用例红（非 IPD 路径不再返回 R）。
 */
@Tag("dev")
@DisplayName("IpdNotFoundAdvice 非 IPD 分支 vs 基线 GlobalExceptionHandler 双侧对照")
class IpdNotFoundBaselineParityTest {

    private final GlobalExceptionHandler baseline = new GlobalExceptionHandler();
    private final IpdNotFoundAdvice advice = new IpdNotFoundAdvice();

    @Test
    @DisplayName("1) 非 IPD 路径 NoHandlerFound：advice 的 R(code,msg) 必须等于基线实际输出")
    void noHandlerOutsideIpdMatchesBaselineOutput() {
        String uri = "/chat/nonexistent";
        NoHandlerFoundException ex = new NoHandlerFoundException("GET", uri, HttpHeaders.EMPTY);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);

        R<Void> expected = baseline.handleNoHandlerFoundException(ex, request);
        Object actual = advice.handleNoHandler(ex, request);

        assertThat(actual)
            .as("非 IPD 路径必须回落基线 R 包络，不得返回 IPD ApiV1Response（否则 /chat 等旧通道契约被改）")
            .isInstanceOf(R.class);
        R<?> actualR = (R<?>) actual;
        assertThat(actualR.getCode())
            .as("code 漂移：基线 handleNoHandlerFoundException 当前实际值=%s，advice 复刻值=%s",
                expected.getCode(), actualR.getCode())
            .isEqualTo(expected.getCode());
        assertThat(actualR.getMsg())
            .as("文案漂移：基线 handleNoHandlerFoundException 当前实际值=%s，advice 复刻值=%s",
                expected.getMsg(), actualR.getMsg())
            .isEqualTo(expected.getMsg());
    }

    @Test
    @DisplayName("2) 非 IPD 路径 NoResourceFound：advice 的 R(code,msg) 必须等于基线 handleServletException 实际输出")
    void noResourceOutsideIpdMatchesBaselineOutput() {
        String uri = "/static/missing.png";
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "missing.png");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);

        // 基线无 NoResourceFoundException 专属 handler，由 handleServletException 兜（NoResourceFoundException extends ServletException）
        R<Void> expected = baseline.handleServletException(ex, request);
        Object actual = advice.handleNoResource(ex, request);

        assertThat(actual)
            .as("非 IPD 静态资源未命中必须回落基线 R 包络，不得返回 IPD ApiV1Response")
            .isInstanceOf(R.class);
        R<?> actualR = (R<?>) actual;
        assertThat(actualR.getCode())
            .as("code 漂移：基线 handleServletException 当前实际值=%s，advice 复刻值=%s",
                expected.getCode(), actualR.getCode())
            .isEqualTo(expected.getCode());
        assertThat(actualR.getMsg())
            .as("文案漂移：基线 handleServletException(internalError) 当前实际值=%s，advice 复刻值=%s",
                expected.getMsg(), actualR.getMsg())
            .isEqualTo(expected.getMsg());
    }

    @Test
    @DisplayName("3) 非 IPD 路径 HttpRequestMethodNotSupported：advice 的 R(code,msg) 必须等于基线 handleHttpRequestMethodNotSupported 实际输出")
    void methodNotSupportedOutsideIpdMatchesBaselineOutput() {
        String uri = "/chat/some-endpoint";
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("DELETE");
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", uri);

        R<Void> expected = baseline.handleHttpRequestMethodNotSupported(ex, request);
        Object actual = advice.handleMethodNotSupported(ex, request);

        assertThat(actual)
            .as("非 IPD 路径 405 必须回落基线 R 包络，不得返回 IPD ApiV1Response")
            .isInstanceOf(R.class);
        R<?> actualR = (R<?>) actual;
        assertThat(actualR.getCode())
            .as("code 漂移：基线 handleHttpRequestMethodNotSupported 当前实际值=%s，advice 复刻值=%s",
                expected.getCode(), actualR.getCode())
            .isEqualTo(expected.getCode());
        assertThat(actualR.getMsg())
            .as("文案漂移：基线 handleHttpRequestMethodNotSupported 当前实际值=%s，advice 复刻值=%s",
                expected.getMsg(), actualR.getMsg())
            .isEqualTo(expected.getMsg());
    }
}
