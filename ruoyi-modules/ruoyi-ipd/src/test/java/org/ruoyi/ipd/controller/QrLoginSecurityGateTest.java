package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.ratelimiter.annotation.RateLimiter;
import org.ruoyi.common.ratelimiter.enums.LimitType;
import org.ruoyi.ipd.advice.IpdServiceExceptionAdvice;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.service.IpdAuthService;
import org.ruoyi.ipd.service.IAuditLogService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R214/U0 看板卡 9d50c5fd：qr-login 端点安全门验收测试。
 *
 * <p>覆盖三项最小改造：① 配置开关 {@code ipd.auth.qr-login.enabled}（默认 false → 关闭态统一 4xx 拒绝）；
 * ② 匿名端点接入既有严格限流（复用 {@code @RateLimiter} 注解/Redisson 切面，非内存版）；
 * ③ Mock 能力跟随开关（默认关闭）。
 *
 * <p>红脸自证：{@link #gateDisabled_rejects4xx_andNeverSignsJwt()} 证明「关闭态确实 409 且不查库、不签 JWT」，
 * 而非仅测开启态（避免假绿）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("R214/U0 qr-login 安全门（开关 + 限流 + Mock 默认关）")
class QrLoginSecurityGateTest {

    @Mock PersonMapper personMapper;
    @Mock IAuditLogService auditLogService;
    @Mock IpdAuthSession session;

    private IpdAuthController controller;

    private Person activePerson() {
        return Person.builder()
            .id(100L).name("Sec-Test").username("sec100")
            .personType("MARKET_PM").groupId(1L).level("L3")
            .passwordHash("$2a$04$dummyhashfordeterministicmapping")
            .accountStatus("ACTIVE").employmentStatus("ACTIVE")
            .mustChangePwd("0").wecomUserId("wc_sec_001").delFlag("0")
            .build();
    }

    @BeforeEach
    void setUp() {
        IpdAuthService authService = new IpdAuthService(personMapper, auditLogService);
        controller = new IpdAuthController(authService, session, null, null);
    }

    // ───────── ① 开关：关闭态（红脸自证） ─────────

    @Test
    @DisplayName("红脸自证：开关关闭 → 抛 QR_LOGIN_NOT_ENABLED，且不查库、不签发 JWT")
    void gateDisabled_rejects4xx_andNeverSignsJwt() {
        ReflectionTestUtils.setField(controller, "qrLoginEnabled", false);

        assertThatThrownBy(() ->
            controller.wecomQrLogin(new IpdAuthController.WecomLoginRequest("wc_sec_001")))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.QR_LOGIN_NOT_ENABLED);

        // 关闭态绝不触达 Mock 登录链路：不查库、不签 JWT
        verify(personMapper, never()).selectOne(any());
        verify(session, never()).login(any());
    }

    @Test
    @DisplayName("开关缺省语义：@Value 默认表达式为 :false（生产安全默认关闭）")
    void switchDefaultExpressionIsFalse() throws Exception {
        Method m = IpdAuthController.class.getDeclaredMethod(
            "wecomQrLogin", IpdAuthController.WecomLoginRequest.class);
        assertThat(m).isNotNull();
        String value = IpdAuthController.class
            .getDeclaredField("qrLoginEnabled")
            .getAnnotation(org.springframework.beans.factory.annotation.Value.class).value();
        assertThat(value).isEqualTo("${ipd.auth.qr-login.enabled:false}");
    }

    // ───────── ① 开关：开启态（正例，证明拒绝非无条件） ─────────

    @Test
    @DisplayName("开关开启 + 已绑定 → 正常放行签发 JWT（证明关闭态拒绝非无条件误伤）")
    void gateEnabled_signsToken() {
        ReflectionTestUtils.setField(controller, "qrLoginEnabled", true);
        Person p = activePerson();
        when(personMapper.selectOne(any())).thenReturn(p);
        when(session.login(p)).thenReturn("jwt-token-abc");
        when(session.timeout()).thenReturn(1800L);

        ApiV1Response<IpdAuthController.LoginView> resp =
            controller.wecomQrLogin(new IpdAuthController.WecomLoginRequest("wc_sec_001"));

        assertThat(resp.getCode()).isEqualTo(0);
        assertThat(resp.getData().token()).isEqualTo("jwt-token-abc");
        assertThat(resp.getData().scope()).isEqualTo("FULL");
    }

    // ───────── 既有错误包络：409 映射 ─────────

    @Test
    @DisplayName("QR_LOGIN_NOT_ENABLED → HTTP 409（复用既有包络，非自造格式）")
    void errorCodeMapsToConflict() {
        assertThat(ApiV1ErrorCode.QR_LOGIN_NOT_ENABLED.getHttpStatus()).isEqualTo(409);
        assertThat(ApiV1ErrorCode.QR_LOGIN_NOT_ENABLED.getCode()).isEqualTo(50019);
    }

    @Test
    @DisplayName("advice 端到端：IpdBusinessException(QR_LOGIN_NOT_ENABLED) → 409 + code=50019")
    void adviceWrapsGateRejectionAs409() {
        ResponseEntity<ApiV1Response<Void>> r = new IpdServiceExceptionAdvice()
            .handleIpdBusiness(new IpdBusinessException(ApiV1ErrorCode.QR_LOGIN_NOT_ENABLED));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).isNotNull();
        assertThat(r.getBody().getCode()).isEqualTo(ApiV1ErrorCode.QR_LOGIN_NOT_ENABLED.getCode());
    }

    // ───────── ② 严格限流：结构化断言（复用 @RateLimiter 注解/切面，非内存版） ─────────

    @Test
    @DisplayName("qr-login 已接入既有严格限流：@RateLimiter(limitType=IP, count=5, time=60)")
    void rateLimiterAnnotationWired() throws Exception {
        Method m = IpdAuthController.class.getDeclaredMethod(
            "wecomQrLogin", IpdAuthController.WecomLoginRequest.class);
        RateLimiter rl = m.getAnnotation(RateLimiter.class);
        assertThat(rl).as("qr-login 必须带 @RateLimiter（复用 Redisson 分布式切面，非 InMemoryHourRateLimiter）").isNotNull();
        assertThat(rl.limitType()).isEqualTo(LimitType.IP);
        assertThat(rl.count()).isEqualTo(5);
        assertThat(rl.time()).isEqualTo(60);
    }
}
