package org.ruoyi.chat.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 装配边界回归；不调用模型、不验证持久化，不替代真实库/HTTP 验收。 */
@Tag("dev")
class AgentScopeKernelBoundaryTest {
    @TempDir Path workspace;

    @Test
    void enabledKernelRequiresExplicitStateCatalog() {
        assertThrows(IllegalArgumentException.class, () -> new AgentScopeChatKernel(
                mock(DataSource.class), "stub:boundary", "", "agentscope_sessions", workspace));
        try (AgentScopeChatKernel ignored = new AgentScopeChatKernel(
                mock(DataSource.class), "stub:boundary", "ipd_dev", "agentscope_sessions", workspace)) {
            // 显式 catalog 可装配；建表与真实读写另由集成验收负责。
        }
    }

    @Test
    void invalidScopeClosesTurnResourcesBeforeError() throws Exception {
        AutoCloseable resources = mock(AutoCloseable.class);
        KernelChatSink sink = mock(KernelChatSink.class);
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(mock(Model.class),
                "stub:boundary", () -> mock(MysqlAgentStateStore.class), workspace)) {
            kernel.stream("chat", null, "employee", "session", "hello", null, null,
                new io.agentscope.core.tool.Toolkit(), resources, sink);
            verify(resources).close();
            verify(sink).onError(AgentScopeChatKernel.ERR_SCOPE_REJECTED,
                AgentScopeChatKernel.SAFE_ERROR_MESSAGE);
            verify(sink, never()).onComplete();
        }
    }

    @Test
    void collidingPromptHashesMustNotReuseAgent() throws Exception {
        assertEquals("Aa".hashCode(), "BB".hashCode());
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(mock(Model.class),
                "stub:boundary", () -> mock(MysqlAgentStateStore.class), workspace)) {
            Method agent = AgentScopeChatKernel.class.getDeclaredMethod("agent", String.class, String.class);
            agent.setAccessible(true);
            Object first = agent.invoke(kernel, "employee", "Aa");
            Object second = agent.invoke(kernel, "employee", "BB");
            assertNotSame(first, second, "不同系统提示词必须使用不同配置实例");
            assertSame(first, agent.invoke(kernel, "employee", "Aa"));
        }
    }

    @Test
    void assemblyFailureMustNotExposeExceptionToSink() {
        KernelChatSink sink = mock(KernelChatSink.class);
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(mock(Model.class),
                "stub:boundary", () -> { throw new IllegalStateException("SECRET_CANARY"); }, workspace)) {
            kernel.stream("project", "user", "employee", "session", "hi", null, sink);
            verify(sink).onError(eq("KERNEL_ERROR"), argThat(message ->
                    message != null && !message.contains("SECRET_CANARY")
                            && !message.contains("IllegalStateException")));
            verifyNoMoreInteractions(sink);
        }
    }

    @Test
    void asynchronousFailureMustNotExposeExceptionToSink() throws Exception {
        KernelChatSink sink = mock(KernelChatSink.class);
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(mock(Model.class),
                "stub:boundary", () -> mock(MysqlAgentStateStore.class), workspace)) {
            Class<?> bridgeType = Class.forName(AgentScopeChatKernel.class.getName() + "$AgentEventSinkBridge");
            Constructor<?> constructor = bridgeType.getDeclaredConstructor(AgentScopeChatKernel.class, KernelChatSink.class);
            constructor.setAccessible(true);
            Object bridge = constructor.newInstance(kernel, sink);
            Method error = bridgeType.getDeclaredMethod("error", Throwable.class);
            error.setAccessible(true);
            error.invoke(bridge, new IllegalStateException("SECRET_CANARY"));
            verify(sink).onError("KERNEL_STREAM_ERROR", "对话处理失败，请稍后重试");
            verifyNoMoreInteractions(sink);
        }
    }

    @Test
    void officialDefaultToolsStayRegisteredUnderGovernance() throws Exception {
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(mock(Model.class),
                "stub:boundary", () -> mock(MysqlAgentStateStore.class), workspace)) {
            Method agent = AgentScopeChatKernel.class.getDeclaredMethod("agent", String.class, String.class);
            agent.setAccessible(true);
            HarnessAgent built = (HarnessAgent) agent.invoke(kernel, "employee", "hello");
            var names = built.getToolkit().getToolNames();
            // 官方能力全量启用（禁止禁用）：文件、沙箱执行、记忆、检索、计划、技能管理都在注册面。
            assertTrue(names.containsAll(List.of("read_file", "write_file", "execute",
                    "memory_search", "memory_get", "memory_save", "session_search",
                    "web_fetch", "web_search", "plan_enter", "plan_write", "plan_exit",
                    "skill_manage")), "官方默认工具必须全量注册: " + names);
            // 注册面保持完整的同时，全部工具（含官方默认工具）统一经治理包装；
            // 只读裁决与出站闸门收敛在治理层，不再靠删工具/禁开关实现。
            for (String name : names) {
                assertTrue(built.getToolkit().getTool(name)
                        instanceof org.ruoyi.chat.kernel.tool.KernelGovernedTool,
                        "官方默认工具必须经治理包装: " + name);
            }
        }
    }
}
