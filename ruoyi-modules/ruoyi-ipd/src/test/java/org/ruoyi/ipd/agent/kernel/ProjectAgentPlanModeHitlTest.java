package org.ruoyi.ipd.agent.kernel;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * plan_exit HITL 专项契约测试（官方 Plan Mode 根路径）。
 *
 * <p>{@link ProjectAgentOfficialHitlTest} 用通用 ASK 工具证明了「ASK→真实 ConfirmResult」机制；本测试用
 * <b>真实 PlanExitTool</b>（经 {@code .enablePlanMode()} 自动装配、再经 {@link ProjectAgentOfficialToolGovernance}
 * 包装）驱动 plan_enter→plan_write→plan_exit 全序列，断言：
 * <ol>
 *   <li>plan_exit 的 ASK 触发 {@link RequireUserConfirmEvent}，待批期间仍处 plan 模式，plan_write 已落 PLAN.md；</li>
 *   <li>批准（{@code ConfirmResult(true)}）→ plan_exit 执行 → 退出 plan 模式进入 BUILD；</li>
 *   <li>拒绝（{@code ConfirmResult(false)}）→ plan_exit 不执行 → 仍停留 plan 模式（只读终态）；</li>
 *   <li>plan_exit 的 ASK 经官方 {@link AgentEventConverterRegistry} 产出 kind=permission_confirm、
 *       reason=tool_call、toolName=plan_exit 的 AG-UI 中断（前端批准面据此渲染）。</li>
 * </ol>
 * 全部复用现有 WAITING_APPROVAL 单轨的官方机制，不新建第二套批准面。
 */
@Tag("dev")
class ProjectAgentPlanModeHitlTest {

    @TempDir
    Path root;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER = "person-plan";
    private static final String SESSION = "run-plan";
    private static final String EXIT_ID = "plan-exit-call";

    @Test
    @DisplayName("plan_exit 批准后退出 plan 模式进入 BUILD")
    void planExitApprovalExitsPlanModeToBuild() throws Exception {
        drivePlanExitThenResume(true);
    }

    @Test
    @DisplayName("plan_exit 拒绝后仍停留 plan 模式（只读终态，不卡死）")
    void planExitRejectionKeepsPlanModeReadOnly() throws Exception {
        drivePlanExitThenResume(false);
    }

    @Test
    @DisplayName("plan_exit 的 ASK 经官方转换器产出 permission_confirm 中断（toolName=plan_exit）")
    void planExitAskSurfacesPermissionConfirmInterrupt() throws Exception {
        ProjectAgentOfficialToolGovernance guard =
                new ProjectAgentOfficialToolGovernance(mock(ProjectAgentEventSink.class));
        try (HarnessAgent agent = buildAgent(planThenExitModel(), guard)) {
            RuntimeContext context =
                    RuntimeContext.builder().userId(USER).sessionId(SESSION).build();
            List<AgentEvent> events =
                    agent.streamEvents(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("先规划再执行")
                                                    .build()),
                                    context)
                            .collectList()
                            .block(Duration.ofSeconds(20));
            RequireUserConfirmEvent confirm = requireConfirmEvent(events);

            AguiStreamContext agui =
                    new AguiStreamContext("plan", "plan", AguiAdapterConfig.builder().build());
            new AgentEventConverterRegistry().convert(confirm, agui);
            AguiEvent.Interrupt found = null;
            for (AguiEvent.Interrupt interrupt : agui.getPendingInterrupts()) {
                if ("plan_exit".equals(interrupt.metadata().get("toolName"))) {
                    found = interrupt;
                    break;
                }
            }
            assertNotNull(found, "plan_exit ASK 应产出 toolName=plan_exit 的 AG-UI 中断");
            assertEquals("tool_call", found.reason(), "permission_confirm 中断 reason 应为 tool_call");
            assertEquals(
                    "permission_confirm",
                    found.metadata().get("agentscope.interruptKind"),
                    "中断 kind 应为 permission_confirm，供前端批准面渲染");
        }
    }

    /** 驱动 plan_enter→plan_write→plan_exit(ASK)，再按 approved 恢复，断言 plan 模式终态。 */
    private void drivePlanExitThenResume(boolean approved) throws Exception {
        ProjectAgentOfficialToolGovernance guard =
                new ProjectAgentOfficialToolGovernance(mock(ProjectAgentEventSink.class));
        try (HarnessAgent agent = buildAgent(planThenExitModel(), guard)) {
            assertNotNull(agent.getToolkit().getTool("plan_exit"), "enablePlanMode 应装配 plan_exit");
            assertFalse(agent.isPlanModeActive(USER, SESSION), "起始不应处于 plan 模式");

            RuntimeContext context =
                    RuntimeContext.builder().userId(USER).sessionId(SESSION).build();
            List<AgentEvent> events =
                    agent.streamEvents(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("先规划再执行")
                                                    .build()),
                                    context)
                            .collectList()
                            .block(Duration.ofSeconds(20));

            RequireUserConfirmEvent confirm = requireConfirmEvent(events);
            ToolUseBlock exitCall =
                    confirm.getToolCalls().stream()
                            .filter(call -> "plan_exit".equals(call.getName()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "确认事件应携带 plan_exit 调用, 实际="
                                                            + confirm.getToolCalls().stream()
                                                                    .map(ToolUseBlock::getName)
                                                                    .toList()));
            assertEquals(EXIT_ID, exitCall.getId(), "确认的应是本轮 plan_exit 调用");
            assertTrue(
                    agent.isPlanModeActive(USER, SESSION),
                    "plan_exit 待批期间必须仍处 plan 模式（尚未退出）");
            assertPlanFileWritten();

            Msg resume =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(approved ? "批准执行" : "继续规划")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.of(new ConfirmResult(approved, exitCall))))
                            .build();
            agent.streamEvents(List.of(resume), context)
                    .collectList()
                    .block(Duration.ofSeconds(20));

            assertEquals(
                    !approved,
                    agent.isPlanModeActive(USER, SESSION),
                    approved
                            ? "批准后 plan_exit 执行，应退出 plan 模式进入 BUILD"
                            : "拒绝后 plan_exit 不执行，应仍停留 plan 模式");
        }
    }

    private void assertPlanFileWritten() {
        try (var walk = Files.walk(root)) {
            assertTrue(
                    walk.anyMatch(path -> "PLAN.md".equals(path.getFileName().toString())),
                    "plan_write 应在 plan 模式落下 PLAN.md");
        } catch (Exception e) {
            fail("校验 PLAN.md 失败: " + e);
        }
    }

    private static RequireUserConfirmEvent requireConfirmEvent(List<AgentEvent> events) {
        assertNotNull(events, "事件流不应为空");
        return events.stream()
                .filter(RequireUserConfirmEvent.class::isInstance)
                .map(RequireUserConfirmEvent.class::cast)
                .findFirst()
                .orElseThrow(
                        () ->
                                new AssertionError(
                                        "plan_exit 的 ASK 应触发 RequireUserConfirmEvent"));
    }

    private HarnessAgent buildAgent(Model model, ProjectAgentOfficialToolGovernance guard) {
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("plan-hitl")
                        .model(model)
                        .toolkit(new Toolkit())
                        .enablePlanMode()
                        .planFileDirectory("plans")
                        .middleware(guard)
                        // 生产同款（AgentScopeProjectAgentKernel#officialFactoryBuilder 用
                        // ProjectAgentOfficialPermissions.workspace()）：预置 plan_enter/plan_write/plan_exit
                        // 的 ALLOW 规则（三者均在 HarnessPlatformTools.NAMES 内）。enter/write passthrough+ALLOW
                        // 直接放行；plan_exit 自身 checkPermissions 返回 ask，引擎序 ask>allow 使其恒触发 HITL。
                        .permissionContext(ProjectAgentOfficialPermissions.workspace())
                        .filesystem(new LocalFilesystemSpec().project(root))
                        .workspace(root)
                        .stateStore(new InMemoryAgentStateStore())
                        .maxIters(8)
                        .build();
        // 生产路径同款：build 后把官方工具（含 plan_exit）包成 OwnedTool，接管运行所有权与批准校验。
        guard.bind(agent.getToolkit());
        return agent;
    }

    /** mock 模型依次产出 plan_enter→plan_write→plan_exit，其后收尾。 */
    private static Model planThenExitModel() {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("plan-hitl-contract");
        AtomicInteger responses = new AtomicInteger();
        when(model.stream(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            int n = responses.getAndIncrement();
                            if (n == 0) {
                                return Flux.just(
                                        ChatResponse.builder()
                                                .finishReason("tool_calls")
                                                .content(
                                                        List.of(
                                                                toolUse(
                                                                        "enter-call",
                                                                        "plan_enter",
                                                                        Map.of())))
                                                .build());
                            }
                            if (n == 1) {
                                return Flux.just(
                                        ChatResponse.builder()
                                                .finishReason("tool_calls")
                                                .content(
                                                        List.of(
                                                                toolUse(
                                                                        "write-call",
                                                                        "plan_write",
                                                                        Map.of(
                                                                                "content",
                                                                                "# 计划\n- 步骤一"))))
                                                .build());
                            }
                            if (n == 2) {
                                return Flux.just(
                                        ChatResponse.builder()
                                                .finishReason("tool_calls")
                                                .content(
                                                        List.of(
                                                                toolUse(
                                                                        EXIT_ID,
                                                                        "plan_exit",
                                                                        Map.of(
                                                                                "summary",
                                                                                "就绪"))))
                                                .build());
                            }
                            return Flux.just(
                                    ChatResponse.builder()
                                            .finishReason("stop")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("完成")
                                                                    .build()))
                                            .build());
                        });
        return model;
    }

    /**
     * 补 {@link #planExitRejectionKeepsPlanModeReadOnly} 的覆盖缺口。
     *
     * <p>该用例名与 DisplayName 都声称「只读终态」，但原实现只断言 {@code isPlanModeActive}，
     * **「只读」本身无任何断言**——名称承诺 > 断言覆盖面。
     *
     * <p>本用例把「只读」钉在<strong>权限契约</strong>这个真正的地基上：拒绝后 plan 模式仍在，
     * 模型要能继续修订计划，靠的是 {@code plan_write} 始终 ALLOW 而非 ASK/DENY。
     * 该结论与「是否被拒」无关——只要配置不变，批准与拒绝两条路径的 plan_write 放行行为就相同。
     *
     * <p>不驱动第二轮模型：实测 plan_exit 的 ASK 中断<strong>不终止同一轮内的 ReAct 循环</strong>，
     * 首段 streamEvents 会连续消耗 6 次模型调用（#0–#5），按序号分支的脚本模型不可靠。
     * 行为面已由 {@link #drivePlanExitThenResume} 覆盖（plan_write 落盘见 assertPlanFileWritten）。
     */
    @Test
    @DisplayName("只读契约：plan_exit 恒 ASK 且未被 DENY，plan_enter/plan_write 恒 ALLOW")
    void planModePermissionContract() {
        var ctx = ProjectAgentOfficialPermissions.workspace();

        // plan_exit：唯一批准面。必须在 askRules，且绝不能同时出现在 denyRules
        assertTrue(
                ctx.getAskRules().containsKey("plan_exit"),
                "plan_exit 必须在 askRules——否则官方 allowRule 会压制工具自检 ASK，HITL 静默失效");
        assertFalse(
                ctx.getDenyRules().containsKey("plan_exit"),
                "plan_exit 不得被 deny，否则计划确认无从发起");

        // plan_enter / plan_write：只读不等于不可写计划，被拒后仍须能修订
        assertTrue(
                ctx.getAllowRules().containsKey("plan_enter"),
                "plan_enter 应直接放行，模型可自由进入规划");
        assertTrue(
                ctx.getAllowRules().containsKey("plan_write"),
                "plan_write 应直接放行——被拒后仍须能修订计划，只读≠冻结计划");
        assertFalse(
                ctx.getAskRules().containsKey("plan_write"),
                "plan_write 不得弹批准，否则每次修订都要打断用户");
    }

    /**
     * 补 {@link #planExitRejectionKeepsPlanModeReadOnly} 的覆盖缺口：
     * 该用例名与 DisplayName 都声称「只读终态」，但原实现只断言 {@code isPlanModeActive}，
     * **未断言拒绝后 plan_write 仍可用**——只读性本身无任何断言（名称承诺 > 断言覆盖面）。
     *
     * <p>本用例驱动完整四轮：plan_enter → plan_write → plan_exit(ASK，被拒) → plan_write(再写)。
     * 断言拒绝后第二轮 plan_write **真的落盘**（按内容判定，而非仅看无异常）。
     */
    @Test
    @DisplayName("plan_exit 拒绝后 plan_write 仍可用（PLAN.md 可继续修订，不被误锁）")
    void planExitRejectionKeepsPlanWriteUsable() throws Exception {
        String firstContent = "# 计划\n- 步骤一";
        String revisedContent = "# 计划\n- 步骤一\n- 步骤二（拒绝后修订）";

        ProjectAgentOfficialToolGovernance guard =
                new ProjectAgentOfficialToolGovernance(mock(ProjectAgentEventSink.class));
        try (HarnessAgent agent =
                buildAgent(planExitRejectedThenRewriteModel(firstContent, revisedContent), guard)) {
            RuntimeContext context =
                    RuntimeContext.builder().userId(USER).sessionId(SESSION).build();

            // 第一轮：进 plan 模式 → 写计划 → 请求退出（触发 ASK 中断）
            List<AgentEvent> events =
                    agent.streamEvents(
                                    List.of(
                                            Msg.builder()
                                                    .role(MsgRole.USER)
                                                    .textContent("先规划再执行")
                                                    .build()),
                                    context)
                            .collectList()
                            .block(Duration.ofSeconds(20));
            RequireUserConfirmEvent confirm = requireConfirmEvent(events);
            ToolUseBlock exitCall =
                    confirm.getToolCalls().stream()
                            .filter(call -> "plan_exit".equals(call.getName()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "确认事件应携带 plan_exit 调用, 实际="
                                                            + confirm.getToolCalls().stream()
                                                                    .map(ToolUseBlock::getName)
                                                                    .toList()));
            assertTrue(agent.isPlanModeActive(USER, SESSION), "待批期间应仍处 plan 模式");

            // 拒绝 plan_exit
            Msg resume =
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent("继续规划")
                            .metadata(
                                    Map.of(
                                            Msg.METADATA_CONFIRM_RESULTS,
                                            List.of(new ConfirmResult(false, exitCall))))
                            .build();
            List<AgentEvent> afterReject =
                    agent.streamEvents(List.of(resume), context)
                            .collectList()
                            .block(Duration.ofSeconds(20));

            // 拒绝后不得对 plan_write 再弹批准——它走 ALLOW 直通
            assertTrue(
                    afterReject.stream().noneMatch(RequireUserConfirmEvent.class::isInstance),
                    "拒绝 plan_exit 后 plan_write 应直接放行，不应再次触发批准中断");
            assertTrue(agent.isPlanModeActive(USER, SESSION), "拒绝后应仍停留 plan 模式");

            // 按内容判定：第二轮 plan_write 真的落盘了（而不是仅"没报错"）
            assertPlanFileContains(revisedContent);
        }
    }

    /** 校验 PLAN.md 已落盘且包含给定的修订内容。 */
    private void assertPlanFileContains(String needle) {
        try (var walk = Files.walk(root)) {
            var plan =
                    walk.filter(path -> "PLAN.md".equals(path.getFileName().toString()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    "拒绝后 plan_write 应仍能落盘 PLAN.md"));
            // 诊断增强：枚举全部 PLAN.md（planFileDirectory 决定可能不止一个），逐个列出
            StringBuilder dump = new StringBuilder();
            boolean hit = false;
            try (var all = Files.walk(root)) {
                for (Path candidate :
                        all.filter(x -> "PLAN.md".equals(x.getFileName().toString()))
                                .toList()) {
                    String body = Files.readString(candidate);
                    if (body.contains(needle)) { hit = true; }
                    dump.append("\n    [").append(root.relativize(candidate)).append("] = ").append(body);
                }
            }
            assertTrue(hit, "PLAN.md 应包含拒绝后写入的修订内容, 期望片段=" + needle + ", 实际文件:" + dump);
        } catch (AssertionError e) {
            throw e;
        } catch (Exception e) {
            fail("校验 PLAN.md 失败: " + e);
        }
    }

    /** 脚本模型：plan_enter → plan_write → plan_exit → plan_write(拒绝后修订) → 收尾。 */
    private static Model planExitRejectedThenRewriteModel(String first, String revised) {
        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("plan-hitl-rejection");
        AtomicInteger responses = new AtomicInteger();
        when(model.stream(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            // Harness maintenance also uses this model with no tool schemas.
                            // Only primary reasoning may advance the scripted tool sequence.
                            List<io.agentscope.core.model.ToolSchema> schemas = invocation.getArgument(1);
                            boolean primary = schemas != null && schemas.stream()
                                    .anyMatch(schema -> "plan_write".equals(schema.getName()));
                            if (!primary) {
                                return Flux.just(ChatResponse.builder().finishReason("stop")
                                        .content(List.of(TextBlock.builder().text("fixture maintenance").build())).build());
                            }
                            int n = responses.getAndIncrement();
                            if (n == 0) {
                                return Flux.just(
                                        ChatResponse.builder()
                                                .finishReason("tool_calls")
                                                .content(
                                                        List.of(
                                                                toolUse(
                                                                        "enter-call",
                                                                        "plan_enter",
                                                                        Map.of())))
                                                .build());
                            }
                            if (n == 1) {
                                return Flux.just(
                                        ChatResponse.builder()
                                                .finishReason("tool_calls")
                                                .content(
                                                        List.of(
                                                                toolUse(
                                                                        "write-call",
                                                                        "plan_write",
                                                                        Map.of("content", first))))
                                                .build());
                            }
                            if (n == 2) {
                                return Flux.just(
                                        ChatResponse.builder()
                                                .finishReason("tool_calls")
                                                .content(
                                                        List.of(
                                                                toolUse(
                                                                        EXIT_ID,
                                                                        "plan_exit",
                                                                        Map.of(
                                                                                "summary",
                                                                                "就绪"))))
                                                .build());
                            }
                            if (n == 3) {
                                return Flux.just(
                                        ChatResponse.builder()
                                                .finishReason("tool_calls")
                                                .content(
                                                        List.of(
                                                                toolUse(
                                                                        "rewrite-call",
                                                                        "plan_write",
                                                                        Map.of(
                                                                                "content",
                                                                                revised))))
                                                .build());
                            }
                            return Flux.just(
                                    ChatResponse.builder()
                                            .finishReason("stop")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("完成")
                                                                    .build()))
                                            .build());
                        });
        return model;
    }

    private static ToolUseBlock toolUse(String id, String name, Map<String, Object> input) {
        String json;
        try {
            json = MAPPER.writeValueAsString(input);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return ToolUseBlock.builder().id(id).name(name).content(json).input(input).build();
    }
}
