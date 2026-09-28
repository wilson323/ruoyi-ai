package org.ruoyi.service.knowledge.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.service.knowledge.IKnowledgeInfoService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B0 检索访问门最小判据单测。
 * <p>
 * mock 合法性（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 精神）：
 * 真实写入路径（KnowledgeInfoController#add）从会话派生 userId，库存在则 userId 非空；
 * share 取值域 {0,1}。本类不造「库存在但 userId=null」等真库不可能组合。
 * 纯 mock 用例，未覆盖 DDL 合法性。
 * <p>
 * 双参重载用例（B0 修复：ws 消息线程显式身份）与单参用例分开成组：
 * 单参=HTTP 线程（mock LoginHelper 模拟会话），双参=非 HTTP 线程（显式 userId，断言不读会话）。
 */
@Tag("dev")
class UserIdShareKnowledgeAccessGateTest {

    private static KnowledgeInfoVo kb(Long id, Long userId, Long share) {
        KnowledgeInfoVo vo = new KnowledgeInfoVo();
        vo.setId(id);
        vo.setUserId(userId);
        vo.setShare(share);
        return vo;
    }

    @Test
    void ownPrivateLibraryPasses() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        when(infoService.queryById(9L)).thenReturn(kb(9L, 100L, 0L));
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            assertDoesNotThrow(() -> gate.checkRetrievalAccess(9L));
        }
    }

    @Test
    void otherSharedLibraryPasses() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        when(infoService.queryById(9L)).thenReturn(kb(9L, 200L, 1L));
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            assertDoesNotThrow(() -> gate.checkRetrievalAccess(9L));
        }
    }

    @Test
    void otherPrivateLibraryRejected() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        when(infoService.queryById(9L)).thenReturn(kb(9L, 200L, 0L));
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            ServiceException ex = assertThrows(ServiceException.class, () -> gate.checkRetrievalAccess(9L));
            assertTrue(ex.getMessage().contains("kid=9"), "异常消息应含 kid 便于排查");
        }
    }

    @Test
    void nonexistentKidRejected() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        when(infoService.queryById(404L)).thenReturn(null);
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(100L);
            ServiceException ex = assertThrows(ServiceException.class, () -> gate.checkRetrievalAccess(404L));
            assertTrue(ex.getMessage().contains("kid=404"));
        }
    }

    @Test
    void nullKidRejected() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        assertThrows(ServiceException.class, () -> gate.checkRetrievalAccess(null));
        verify(infoService, never()).queryById(any());
    }

    @Test
    void notLoggedInRejected() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            // LoginHelper.getUserId() 未登录/无会话上下文时返回 null（getExtra 捕获异常后归 null）
            login.when(LoginHelper::getUserId).thenReturn(null);
            assertThrows(ServiceException.class, () -> gate.checkRetrievalAccess(9L));
        }
        // 未登录在触库之前即拒绝
        verify(infoService, never()).queryById(any());
    }

    // ---------- 双参重载（显式身份变体，ws 消息线程场景） ----------

    @Test
    void explicitUserIdOwnedLibraryPasses() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        when(infoService.queryById(9L)).thenReturn(kb(9L, 100L, 0L));
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            assertDoesNotThrow(() -> gate.checkRetrievalAccess(9L, 100L));
            // 双参变体不得读会话 ThreadLocal：ws 消息线程无 Sa-Token 上下文，读也恒 null（B0 缺陷根因）
            login.verifyNoInteractions();
        }
    }

    @Test
    void explicitUserIdOtherPrivateLibraryRejected() {
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        when(infoService.queryById(9L)).thenReturn(kb(9L, 200L, 0L));
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        ServiceException ex = assertThrows(ServiceException.class, () -> gate.checkRetrievalAccess(9L, 100L));
        assertTrue(ex.getMessage().contains("kid=9"), "异常消息应含 kid 便于排查");
    }

    @Test
    void explicitNullUserIdRejected() {
        // 对应 ws 场景：session attributes 中 userId 丢失/未写入（正常握手不会发生，防御性拒绝且不触库）
        IKnowledgeInfoService infoService = mock(IKnowledgeInfoService.class);
        UserIdShareKnowledgeAccessGate gate = new UserIdShareKnowledgeAccessGate(infoService);
        ServiceException ex = assertThrows(ServiceException.class, () -> gate.checkRetrievalAccess(9L, null));
        assertTrue(ex.getMessage().contains("kid=9"));
        verify(infoService, never()).queryById(any());
    }
}
