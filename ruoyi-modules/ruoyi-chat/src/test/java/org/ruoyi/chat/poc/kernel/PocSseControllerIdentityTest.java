package org.ruoyi.chat.poc.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.exception.NotLoginException;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * PocSseController 身份收口行为自证（ADR-0075 D9 / SEC-API-01，2026-09-29）。
 *
 * <p>契约：userId 恒从登录会话推导（{@code LoginHelper}），请求参数自报已移除；
 * 未登录 fail-closed——不给「默认 U1」式降级，且内核（{@code PocKernelSupport}）不得被触达。
 *
 * <p>自证口径：反例（未登录）必须证明「内核零调用」，正例（会话身份）必须证明
 * 「内核收到的 userId = 会话值」——两条都断言的是契约，不是既有实现的快照。
 */
@Tag("dev")
class PocSseControllerIdentityTest {

    private final PocSseController controller = new PocSseController();

    @Test
    @DisplayName("反例：未登录 → fail-closed，内核零触达（无默认 U1 降级）")
    void noSession_failClosed_kernelNotReached() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
                MockedStatic<PocKernelSupport> support = mockStatic(PocKernelSupport.class)) {
            login.when(LoginHelper::getUserId)
                    .thenThrow(new NotLoginException((String) null, "test", "test: no login"));

            // fail-closed：不抛到容器（错误帧收束），且内核不得被触达
            SseEmitter emitter = controller.stream("hi", "P1", "emp-a1", "");
            org.assertj.core.api.Assertions.assertThat(emitter).as("错误帧收束后仍返回 emitter").isNotNull();

            // 身份校验未过 → 内核工厂不得被调用（校验先于任何内核/DB 触达，与守卫 4/5 同口径）
            support.verify(() -> PocKernelSupport.agent(anyString()), never());
            // 身份源唯一：只从会话取，且恰取一次
            login.verify(LoginHelper::getUserId, times(1));
        }
    }

    @Test
    @DisplayName("正例：会话 userId=900103 → 原样透传内核（非请求参数、非默认值）")
    void sessionUserId_delegatedToKernel() {
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class);
                MockedStatic<PocKernelSupport> support = mockStatic(PocKernelSupport.class)) {
            login.when(LoginHelper::getUserId).thenReturn(900103L);

            HarnessAgent agent = mock(HarnessAgent.class);
            when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class)))
                    .thenReturn(reactor.core.publisher.Flux.empty());
            support.when(() -> PocKernelSupport.agent("emp-a1")).thenReturn(agent);

            controller.stream("hi", "P1", "emp-a1", "S9");

            ArgumentCaptor<RuntimeContext> ctxCap = ArgumentCaptor.forClass(RuntimeContext.class);
            verify(agent).streamEvents(any(Msg.class), ctxCap.capture());
            assertThat(ctxCap.getValue().getUserId())
                    .as("内核身份段必须含会话值 900103 并经四维收口折叠 p{project}:u{user}（D9：禁参数自报/默认值）")
                    .contains("900103")
                    .startsWith("pP1:");
            assertThat(ctxCap.getValue().getSessionId())
                    .as("会话段经收口折叠 a{agent}:s{session}，含透传值 S9")
                    .contains("S9");
        }
    }
}
