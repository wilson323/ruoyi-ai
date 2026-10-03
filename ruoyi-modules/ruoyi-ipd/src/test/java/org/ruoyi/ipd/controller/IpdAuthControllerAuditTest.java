/**
 * IpdAuthController 审计收口（2026-10-03）：refresh / logout 此前全程无审计，本测锁补齐后的正例。
 * - refresh 成功 → 一条 TOKEN_REFRESH 审计，字段口径与 IpdAuthService 的 LOGIN 审计一致
 * - logout 成功 → 一条 LOGOUT 审计（entityType=persons，operatorId=entityId=本人）
 * - logout 幂等守卫保持：token 已撤销（currentPerson 抛 NotLoginException）/ tokenValue=null
 *   → 不落审计、不抛错
 */
package org.ruoyi.ipd.controller;

import cn.dev33.satoken.exception.NotLoginException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.IpdAuthService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@DisplayName("refresh/logout 补审计（2026-10-03 审计链收口）")
@ExtendWith(MockitoExtension.class)
class IpdAuthControllerAuditTest {

    @Mock
    private IpdAuthService authService;
    @Mock
    private IpdAuthSession session;
    @Mock
    private IAuditLogService auditLogService;

    @InjectMocks
    private IpdAuthController controller;

    private static Person person() {
        Person p = new Person();
        p.setId(100L);
        p.setName("张三");
        p.setPersonType("MARKET_PM");
        p.setMustChangePwd("0");
        return p;
    }

    @Test
    @DisplayName("refresh 成功：一条 TOKEN_REFRESH 审计，字段口径与 LOGIN 审计一致")
    void refreshWritesAudit() {
        Person p = person();
        when(session.currentPerson()).thenReturn(p);
        when(session.tokenValue()).thenReturn("t-old");
        when(session.login(p)).thenReturn("t-new");
        when(session.timeout()).thenReturn(900L);
        when(authService.scopeOf(p)).thenReturn(IpdAuthService.Scope.FULL);

        ApiV1Response<IpdAuthController.LoginView> resp = controller.refresh();

        assertThat(resp).isNotNull();
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).append(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getAction()).isEqualTo("TOKEN_REFRESH");
        assertThat(row.getEntityType()).isEqualTo("persons");
        assertThat(row.getOperatorId()).isEqualTo(100L);
        assertThat(row.getEntityId()).isEqualTo(100L);
        assertThat(row.getOperatorName()).isEqualTo("张三");
        assertThat(row.getCreateTime()).isNotNull();
    }

    @Test
    @DisplayName("logout 成功：一条 LOGOUT 审计（entityType=persons，operatorId=entityId=本人）")
    void logoutWritesAudit() {
        Person p = person();
        when(session.tokenValue()).thenReturn("t-1");
        when(session.currentPerson()).thenReturn(p);

        ApiV1Response<Void> resp = controller.logout();

        assertThat(resp).isNotNull();
        verify(session, times(1)).logout();
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).append(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getAction()).isEqualTo("LOGOUT");
        assertThat(row.getEntityType()).isEqualTo("persons");
        assertThat(row.getOperatorId()).isEqualTo(100L);
        assertThat(row.getEntityId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("logout 幂等守卫保持：token 已撤销（currentPerson 抛 NotLoginException）→ 不落审计不抛错")
    void logoutRevokedTokenWritesNoAudit() {
        when(session.tokenValue()).thenReturn("t-revoked");
        when(session.currentPerson()).thenThrow(
            NotLoginException.newInstance(IpdAuthSession.LOGIN_TYPE, NotLoginException.INVALID_TOKEN,
                "token 已失效", "t-revoked"));

        ApiV1Response<Void> resp = controller.logout();

        assertThat(resp).isNotNull();
        verify(session, times(1)).logout();   // 原语义：logout 照常幂等执行
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("logout tokenValue=null：不调 logout 也不落审计（幂等守卫原样保留）")
    void logoutNullTokenWritesNoAudit() {
        when(session.tokenValue()).thenReturn(null);

        ApiV1Response<Void> resp = controller.logout();

        assertThat(resp).isNotNull();
        verify(session, never()).logout();
        verify(auditLogService, never()).append(any(AuditLog.class));
    }
}
