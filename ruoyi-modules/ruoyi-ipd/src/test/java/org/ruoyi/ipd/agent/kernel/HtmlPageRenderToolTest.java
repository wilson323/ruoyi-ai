package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.tool.InMemoryKernelToolEffectLedger;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
import org.ruoyi.chat.kernel.tool.KernelToolCallTrace;
import org.ruoyi.chat.kernel.tool.KernelToolGovernance;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML 页面渲染工具：成功经官方产物链登记 DRAFT 并回报回执；CLI 失败行号诊断以文本
 * 结果（非 ERROR 态）原样透传供模型自修——治理包装对 ERROR 态统一替换安全文案；fileName
 * 校验先于渲染；治理裁决 WORKSPACE_WRITE 放行、READ_ONLY 拒绝零副作用。
 */
@Tag("dev")
class HtmlPageRenderToolTest {

    @TempDir Path cache;

    private static final AnswerMeHtmlRenderer.Settings SETTINGS =
        new AnswerMeHtmlRenderer.Settings(true, "node", Duration.ofSeconds(30), 65_536, 8_000);

    /** 渲染进程 fake：成功落 page.html；失败回 ✗ 行号诊断（契约见 AnswerMeHtmlRendererTest）。 */
    static final class RenderProcess implements AnswerMeHtmlRenderer.ProcessExecutor {
        final AtomicInteger renders = new AtomicInteger();
        volatile boolean failRender = false;

        @Override public AnswerMeHtmlRenderer.ProcessOutput run(List<String> command,
                Map<String, String> environment, Path workingDirectory, Duration timeout) {
            if (command.contains("--version")) {
                return new AnswerMeHtmlRenderer.ProcessOutput(0, "v22.22.3\n", "", false);
            }
            renders.incrementAndGet();
            if (failRender) {
                return new AnswerMeHtmlRenderer.ProcessOutput(1,
                    "✗ L8 [sequence] 语法错误\n  Correct example:\n    A -> B: 请求\n  Full syntax: am help sequence",
                    "", false);
            }
            Path html = Path.of(command.get(command.indexOf("-o") + 1));
            try {
                Files.writeString(html, "<html>页面</html>", StandardCharsets.UTF_8);
            } catch (IOException io) {
                throw new java.io.UncheckedIOException(io);
            }
            return new AnswerMeHtmlRenderer.ProcessOutput(0,
                "✓ " + html + "\n  sheet · blueprint · 2 panels\n  STE ✓ 2 warnings", "", false);
        }
    }

    /** 官方产物交付链 fake：记录请求，回执可覆写。 */
    static final class RecordingTarget implements ArtifactDeliveryTarget {
        final List<ArtifactDeliveryRequest> delivered = new CopyOnWriteArrayList<>();
        volatile ArtifactDeliveryResult result =
            ArtifactDeliveryResult.success("Draft stored; version=7; sha256=deadbeef");
        volatile RuntimeException throwInstead;

        @Override public ArtifactDeliveryResult deliver(RuntimeContext runtime, ArtifactDeliveryRequest request) {
            delivered.add(request);
            if (throwInstead != null) throw throwInstead;
            return result;
        }
    }

    private final List<Map<String, Object>> steps = new CopyOnWriteArrayList<>();

    private HtmlPageRenderTool tool(RenderProcess process, RecordingTarget target) {
        return new HtmlPageRenderTool(new AnswerMeHtmlRenderer(SETTINGS, process, cache), target, steps::add);
    }

    private static ToolCallParam param(Map<String, Object> input) {
        return ToolCallParam.builder()
            .toolUseBlock(new ToolUseBlock("call-1", ProjectAgentToolCatalog.HTML_PAGE_RENDER, input))
            .input(input)
            .build();
    }

    @Test
    @DisplayName("渲染成功：产物经官方链登记 DRAFT，回执带 version/摘要/STE 警告并上报 step")
    void successDeliversDraftAndReportsReceipt() {
        RenderProcess process = new RenderProcess();
        RecordingTarget target = new RecordingTarget();

        ToolResultBlock result = tool(process, target)
            .callAsync(param(Map.of("draft", "# 标题\n\n## 面板", "fileName", "竞品分析页面.html")))
            .block();

        assertThat(result).isNotNull();
        assertThat(result.getState().name()).isNotEqualTo("ERROR");
        String text = result.getOutput().toString();
        assertThat(text).contains("已生成并登记产物草稿", "Draft stored; version=7",
            "sheet · blueprint · 2 panels", "STE 警告 2 条", "建议按警告修订");
        assertThat(process.renders).hasValue(1);
        assertThat(target.delivered).singleElement().satisfies(request -> {
            assertThat(request.filePath()).isNull();
            assertThat(request.fileName()).isEqualTo("竞品分析页面.html");
            assertThat(new String(request.content(), StandardCharsets.UTF_8)).contains("<html>");
            assertThat(request.force()).isFalse();
        });
        assertThat(steps).singleElement().satisfies(step -> {
            assertThat(step.get("tool")).isEqualTo(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
            assertThat(step.get("fileName")).isEqualTo("竞品分析页面.html");
            assertThat(step.get("steWarnings")).isEqualTo(2);
            assertThat(step.get("bytes")).isEqualTo("<html>页面</html>".getBytes(StandardCharsets.UTF_8).length);
        });
    }

    @Test
    @DisplayName("渲染失败：✗ 行号/组件/正确示例原样透传，产物链零调用")
    void renderFailurePassesCliDiagnosticsWithoutDelivery() {
        RenderProcess process = new RenderProcess();
        process.failRender = true;
        RecordingTarget target = new RecordingTarget();

        ToolResultBlock result = tool(process, target)
            .callAsync(param(Map.of("draft", "# 标题", "fileName", "页面.html")))
            .block();

        assertThat(result.getState().name()).isNotEqualTo("ERROR");
        assertThat(result.getOutput().toString())
            .contains("✗ L8 [sequence]", "Correct example", "Full syntax");
        assertThat(target.delivered).isEmpty();
        assertThat(steps).isEmpty();
    }

    @Test
    @DisplayName("产物链失败与异常都以文本结果报「产物登记失败」，不伪装成功也不被治理层吞诊断")
    void deliveryFailureIsAnExplicitError() {
        RenderProcess process = new RenderProcess();
        RecordingTarget rejected = new RecordingTarget();
        rejected.result = ArtifactDeliveryResult.fail("写入被拒绝");

        ToolResultBlock failed = tool(process, rejected)
            .callAsync(param(Map.of("draft", "# 标题", "fileName", "页面.html"))).block();
        assertThat(failed.getState().name()).as("文本结果避免治理层安全文案替换").isNotEqualTo("ERROR");
        assertThat(failed.getOutput().toString()).contains("产物登记失败", "写入被拒绝");

        RecordingTarget throwing = new RecordingTarget();
        throwing.throwInstead = new IllegalStateException("模拟交付链异常");
        ToolResultBlock crashed = tool(process, throwing)
            .callAsync(param(Map.of("draft", "# 标题", "fileName", "页面.html"))).block();
        assertThat(crashed.getState().name()).isNotEqualTo("ERROR");
        assertThat(crashed.getOutput().toString()).contains("产物登记失败", "模拟交付链异常");
    }

    @Test
    @DisplayName("fileName 不合法在渲染前进制拒绝：无 .html、带路径、./..、超 200")
    void invalidFileNamesAreRejectedBeforeRender() {
        RenderProcess process = new RenderProcess();
        RecordingTarget target = new RecordingTarget();
        var underTest = tool(process, target);
        for (String bad : List.of("报告.md", "a/b.html", "..", "x".repeat(201) + ".html", "./a.html")) {
            ToolResultBlock result = underTest
                .callAsync(param(Map.of("draft", "# 标题", "fileName", bad))).block();
            assertThat(result.getState().name()).as("fileName=%s 应报错", bad).isNotEqualTo("ERROR");
            assertThat(result.getOutput().toString()).as("fileName=%s 应说明原因", bad).contains("渲染失败");
        }
        assertThat(process.renders).hasValue(0);
        assertThat(target.delivered).isEmpty();
        assertThat(HtmlPageRenderTool.fileNameError("报告.HTML")).as("大写后缀按不区分大小写放行").isNull();
    }

    @Test
    @DisplayName("缺 draft/fileName 参数直接报错，零渲染零交付")
    void missingArgumentsAreErrors() {
        RenderProcess process = new RenderProcess();
        var underTest = tool(process, new RecordingTarget());
        assertThat(underTest.callAsync(param(Map.of("fileName", "页面.html"))).block()
            .getOutput().toString()).contains("draft 为必填参数");
        assertThat(underTest.callAsync(param(Map.of("draft", "# 标题"))).block()
            .getOutput().toString()).contains("fileName 为必填参数");
        assertThat(underTest.callAsync(param(Map.of("draft", " ", "fileName", " "))).block()
            .getOutput().toString()).contains("draft 为必填参数");
        assertThat(process.renders).hasValue(0);
    }

    @Test
    @DisplayName("治理：真实 WRITE 描述符 WORKSPACE_WRITE 放行执行一次；READ_ONLY 拒绝且零副作用")
    void governanceAllowsWorkspaceWriteAndDeniesReadOnly() {
        ToolDescriptor descriptor = ProjectAgentToolCatalog.descriptor(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
        assertThat(descriptor).isNotNull();

        RenderProcess process = new RenderProcess();
        RecordingTarget target = new RecordingTarget();
        KernelToolGovernance workspaceWrite = new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptor)),
            HarnessPermissionMode.WORKSPACE_WRITE, new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
        KernelGovernedTool governed = KernelGovernedTool.wrap(tool(process, target), workspaceWrite);
        Map<String, Object> input = Map.of("draft", "# 标题", "fileName", "页面.html");

        assertThat(governed.checkPermissions(input, null).block().getBehavior())
            .isEqualTo(PermissionBehavior.ALLOW);
        governed.callAsync(param(input)).block();
        assertThat(process.renders).hasValue(1);
        assertThat(target.delivered).hasSize(1);

        RenderProcess deniedProcess = new RenderProcess();
        RecordingTarget deniedTarget = new RecordingTarget();
        KernelToolGovernance readOnly = new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptor)),
            HarnessPermissionMode.READ_ONLY, new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
        KernelGovernedTool refused = KernelGovernedTool.wrap(tool(deniedProcess, deniedTarget), readOnly);

        assertThat(refused.checkPermissions(input, null).block().getBehavior())
            .isEqualTo(PermissionBehavior.DENY);
        refused.callAsync(param(input)).block();
        assertThat(deniedProcess.renders).hasValue(0);
        assertThat(deniedTarget.delivered).isEmpty();
    }

    @Test
    @DisplayName("工具元数据：ID/参数 schema 与目录登记一致，非只读如实声明")
    void toolMetadataMatchesCatalogRegistration() {
        RenderProcess process = new RenderProcess();
        var underTest = tool(process, new RecordingTarget());
        assertThat(underTest.getName()).isEqualTo(ProjectAgentToolCatalog.HTML_PAGE_RENDER);
        assertThat(underTest.isReadOnly()).isFalse();
        Map<String, Object> schema = underTest.getParameters();
        assertThat(schema.toString())
            .contains("draft", "fileName", "required", "additionalProperties");
    }
}
