package org.ruoyi.chat.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * C1 切片（2026-09-29）：工作区分桶补 project/user 维，与 {@link KernelScopeKey} 同源。
 *
 * <p>正例：不同 project×user×agent 落不同桶且各自生成 AGENTS.md；同键同配置复用同一
 * Agent 实例。反例：{@code ':'}/{@code '..'}/{@code '/'}、反斜杠、{@code '.'}、空段注入
 * fail-closed 拒绝且零落盘；旧平铺桶（workspaceRoot/agentId）不得再现。
 *
 * <p>模型用 stub（只 mock 输出，合法三规约），状态存储用 mock/毒化供应器；
 * 不调用真实模型、不验证持久化，不替代真实库/HTTP 验收。
 */
@Tag("dev")
@DisplayName("C1 内核工作区隔离：project×user×agent 分桶 + 注入拒绝")
class AgentScopeChatKernelWorkspaceIsolationTest {

    @TempDir
    Path workspace;

    private final KernelModelSelector selector = new KernelModelSelector(
            "stub:workspace", (registryKey, context) -> mock(Model.class));

    @Test
    @DisplayName("stream() 四维分桶：跨项目/跨用户互不同桶，各生成 AGENTS.md，不触兼容降级桶")
    void distinctScopesGetDistinctWorkspaceBuckets() {
        AtomicBoolean touched = new AtomicBoolean(false);
        Supplier<MysqlAgentStateStore> poisoned = () -> {
            touched.set(true);
            throw new IllegalStateException("poisoned state store");
        };
        RecordingSink sink = new RecordingSink();
        try (AgentScopeChatKernel kernel = kernel(poisoned)) {
            kernel.stream("P1", "U1", "employee", "S1", "hi", null, sink);
            kernel.stream("P2", "U1", "employee", "S1", "hi", null, sink);
            kernel.stream("P1", "U2", "employee", "S1", "hi", null, sink);
        }

        assertTrue(touched.get(), "工作区分桶必须先于状态存储装配完成");
        for (Path bucket : List.of(
                workspace.resolve("P1").resolve("U1").resolve("employee"),
                workspace.resolve("P2").resolve("U1").resolve("employee"),
                workspace.resolve("P1").resolve("U2").resolve("employee"))) {
            assertTrue(Files.isRegularFile(bucket.resolve("AGENTS.md")),
                    "每个 project×user×agent 分桶都要有自己的 AGENTS.md：" + bucket);
        }
        assertEquals(List.of("error:KERNEL_ERROR", "error:KERNEL_ERROR", "error:KERNEL_ERROR"), sink.log,
                "毒化供应器下每轮恰一同步错误帧，无部分成功帧");
        assertFalse(Files.exists(workspace.resolve("employee")), "旧平铺桶不得再现");
        assertFalse(Files.exists(workspace.resolve(AgentScopeChatKernel.UNSCOPED_SEGMENT)),
                "正式四维路径不得触达兼容降级桶");
    }

    @Test
    @DisplayName("同键同配置复用同一 Agent 实例；跨项目/跨用户不得复用")
    void sameScopeReusesWorkspaceBucket() throws Exception {
        try (AgentScopeChatKernel kernel = kernel(() -> mock(MysqlAgentStateStore.class))) {
            HarnessAgent first = buildScoped(kernel, "P1", "U1", "employee");
            HarnessAgent second = buildScoped(kernel, "P1", "U1", "employee");
            assertSame(first, second, "同 project×user×agent×提示词×模型配置必须复用同一 Agent");

            HarnessAgent otherProject = buildScoped(kernel, "P2", "U1", "employee");
            HarnessAgent otherUser = buildScoped(kernel, "P1", "U2", "employee");
            assertNotSame(first, otherProject, "跨项目不得复用（Agent 绑定各自工作区桶）");
            assertNotSame(first, otherUser, "跨用户不得复用（Agent 绑定各自工作区桶）");

            assertTrue(Files.isRegularFile(workspace.resolve("P1").resolve("U1")
                    .resolve("employee").resolve("AGENTS.md")));
        }
    }

    @Test
    @DisplayName("路径注入段（':'/'..'/'/'/'\\'/'.'/空/null）→ fail-closed 拒绝且零落盘")
    void workspaceSegmentInjectionIsRejected() throws IOException {
        String[][] attacks = {
                {"P1:evil", "U1", "employee"},
                {"P1", "U1:evil", "employee"},
                {"P1", "U1", "employee:evil"},
                {"../etc", "U1", "employee"},
                {"P1", "..", "employee"},
                {"P1", "U1", ".."},
                {"P1/x", "U1", "employee"},
                {"P1", "U1/y", "employee"},
                {"P1", "U1", "a/b"},
                {"P1", "U1", "a\\b"},
                {"P1", "U1", "."},
                {" ", "U1", "employee"},
                {"P1", "U1", ""},
                {null, "U1", "employee"},
                {"P1", null, "employee"},
                {"P1", "U1", null},
        };
        for (String[] attack : attacks) {
            assertThrows(IllegalArgumentException.class,
                    () -> AgentScopeChatKernel.workspaceFor(workspace, attack[0], attack[1], attack[2]),
                    "注入段必须 fail-closed 拒绝：" + attack[0] + "|" + attack[1] + "|" + attack[2]);
        }
        assertEquals(workspace.resolve("P1").resolve("U1").resolve("employee"),
                AgentScopeChatKernel.workspaceFor(workspace, "P1", "U1", "employee"),
                "合法三段必须落成 root/project/user/agent 三级分桶");
        try (var entries = Files.list(workspace)) {
            assertTrue(entries.findAny().isEmpty(), "纯路径计算/拒绝都不得落盘");
        }
    }

    @Test
    @DisplayName("W1 兼容面 agent(String,String) 降级到 __unscoped__ 分桶，不得回退旧平铺桶")
    void legacyCompatRouteBucketsUnderUnscopedSegment() throws Exception {
        try (AgentScopeChatKernel kernel = kernel(() -> mock(MysqlAgentStateStore.class))) {
            Method compat = AgentScopeChatKernel.class.getDeclaredMethod("agent", String.class, String.class);
            compat.setAccessible(true);
            compat.invoke(kernel, "employee", "hello");

            Path unscoped = workspace.resolve(AgentScopeChatKernel.UNSCOPED_SEGMENT)
                    .resolve(AgentScopeChatKernel.UNSCOPED_SEGMENT);
            assertTrue(Files.isRegularFile(unscoped.resolve("employee").resolve("AGENTS.md")),
                    "兼容面也要分桶（防未来开放文件工具后跨身份混写）");
            assertFalse(Files.exists(workspace.resolve("employee")), "旧平铺桶不得再现");
        }
    }

    private AgentScopeChatKernel kernel(Supplier<MysqlAgentStateStore> stores) {
        return new AgentScopeChatKernel(mock(Model.class), "stub:workspace", stores, workspace);
    }

    /** 反射走 W2 五参装配面（同 AgentScopeKernelBoundaryTest 模式），同步完成工作区分桶。 */
    private HarnessAgent buildScoped(AgentScopeChatKernel kernel, String projectId, String userId, String agentId)
            throws Exception {
        Method agent = AgentScopeChatKernel.class.getDeclaredMethod("agent",
                String.class, String.class, String.class, String.class, KernelModelSelector.ModelPlan.class);
        agent.setAccessible(true);
        return (HarnessAgent) agent.invoke(kernel, projectId, userId, agentId, null, selector.plan(null));
    }

    /** 记录型 sink：只记录同步终态帧（本测试不依赖异步流，样式同 AgentScopeChatKernelIdentityTest）。 */
    private static final class RecordingSink implements KernelChatSink {

        private final List<String> log = new ArrayList<>();

        @Override
        public void onContent(String delta) {
            log.add("content:" + delta);
        }

        @Override
        public void onReasoning(String delta) {
            log.add("reasoning:" + delta);
        }

        @Override
        public void onMcpTool(String toolName, String status, String result) {
            log.add("mcp_tool:" + toolName);
        }

        @Override
        public void onError(String code, String message) {
            log.add("error:" + code);
        }

        @Override
        public void onComplete() {
            log.add("complete");
        }
    }
}
