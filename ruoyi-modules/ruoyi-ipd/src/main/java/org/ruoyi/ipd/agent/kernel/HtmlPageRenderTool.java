package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryResult;
import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 受治理工具：把扩展 Markdown 草稿渲染为单文件 HTML 页面，并经官方产物交付链登记为
 * 本次运行的产物草稿（DRAFT）。结构对齐 {@link ProjectKnowledgeSearchTool}：不做授权
 * 判断，执行前必须经 {@code KernelGovernedTool} 的原生 checkPermissions →
 * {@code ToolPolicyEngine} 裁决；写入范围仅限产物草稿链路，与官方 deliver_artifact 同源。
 *
 * <p>渲染失败时原样透传 CLI 行号、组件与正确示例，供模型按行号自修后重试；失败也用
 * 文本结果（非 ERROR 态）返回——治理包装对 ERROR 态统一替换为安全文案，而本工具的
 * 失败诊断是面向模型的操作指引，必须原样到达模型。
 */
public final class HtmlPageRenderTool implements AgentTool {

    static final String DESCRIPTION = "把扩展 Markdown 草稿渲染为单文件 HTML 页面（布局、主题与图表由渲染引擎生成，"
        + "无外部依赖），并登记为本次运行的产物草稿。草稿格式：frontmatter（title 等）+「## 面板标题」分面板"
        + "+ 组件围栏代码块（flow/sequence/tree/timeline/limits/annot/kv/callout）或普通 Markdown。"
        + "渲染引擎会做 STE 写作检查并回报警告；失败时返回行号、组件与正确示例，按示例修正草稿后重试（最多 2 次）。"
        + "禁止手写 HTML/CSS/SVG；fileName 必须以 .html 结尾。";

    private final AnswerMeHtmlRenderer renderer;
    private final ArtifactDeliveryTarget artifacts;
    private final Consumer<Map<String, Object>> stepListener;

    /**
     * @param renderer 渲染引擎（宿主 node 执行）
     * @param artifacts 官方产物交付链（DRAFT 权限/事务/owner/回执复用，不建第二套存储）
     * @param stepListener 成功渲染上报（写 STEP 事件）
     */
    public HtmlPageRenderTool(AnswerMeHtmlRenderer renderer, ArtifactDeliveryTarget artifacts,
                              Consumer<Map<String, Object>> stepListener) {
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
        this.stepListener = stepListener == null ? s -> { } : stepListener;
    }

    @Override
    public String getName() {
        return ProjectAgentToolCatalog.HTML_PAGE_RENDER;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> draft = Map.of("type", "string",
            "description", "扩展 Markdown 草稿全文：frontmatter + ## 面板 + 组件围栏");
        Map<String, Object> fileName = Map.of("type", "string",
            "description", "产物文件名，必须以 .html 结尾，不含路径");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("draft", draft, "fileName", fileName));
        schema.put("required", List.of("draft", "fileName"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        Map<String, Object> input = param == null ? Map.of() : param.getInput();
        String draft = stringArg(input, "draft");
        String fileName = stringArg(input, "fileName");
        if (draft == null) return Mono.just(ToolResultBlock.text("渲染失败：draft 为必填参数"));
        if (fileName == null) return Mono.just(ToolResultBlock.text("渲染失败：fileName 为必填参数"));
        String nameError = fileNameError(fileName);
        if (nameError != null) return Mono.just(ToolResultBlock.text("渲染失败：" + nameError));
        RuntimeContext runtime = param == null ? null : param.getRuntimeContext();
        return Mono.fromCallable(() -> renderAndDeliver(draft, fileName, runtime))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private ToolResultBlock renderAndDeliver(String draft, String fileName, RuntimeContext runtime) {
        var rendered = renderer.render(draft);
        if (rendered instanceof AnswerMeHtmlRenderer.Failure failure) {
            return ToolResultBlock.text(failure.error()
                + (failure.diagnostics() == null ? "" : "\n" + failure.diagnostics()));
        }
        var success = (AnswerMeHtmlRenderer.Success) rendered;
        ArtifactDeliveryResult receipt;
        try {
            // description/force 必须为空/false：交付链按裁决入参逐字段比对（执行声明门禁）。
            receipt = artifacts.deliver(runtime, new ArtifactDeliveryRequest(
                null, success.html(), fileName, null, false));
        } catch (RuntimeException deliveryFailure) {
            return ToolResultBlock.text("产物登记失败：" + deliveryFailure.getMessage());
        }
        if (receipt == null || !receipt.successful()) {
            String detail = receipt == null ? "无回执"
                : receipt.message() != null ? receipt.message() : receipt.error();
            return ToolResultBlock.text("产物登记失败：" + detail);
        }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("tool", getName());
        step.put("fileName", fileName);
        step.put("bytes", success.html().length);
        step.put("steWarnings", success.steWarnings());
        stepListener.accept(step);
        StringBuilder text = new StringBuilder("已生成并登记产物草稿：").append(receipt.message());
        if (!success.summary().isBlank()) text.append("；").append(success.summary());
        text.append("；STE 警告 ").append(success.steWarnings()).append(" 条");
        if (success.steWarnings() > 0) {
            text.append("。建议按警告修订草稿后重新渲染，正文用 2-3 行说明页面结论即可。");
        }
        return ToolResultBlock.text(text.toString());
    }

    /** 文件名校验与产物交付链同一规则，另要求 .html 后缀。 */
    static String fileNameError(String fileName) {
        if (fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 || fileName.indexOf(0) >= 0
            || fileName.equals(".") || fileName.equals("..") || fileName.length() > 200) {
            return "fileName 不合法：不含路径分隔符、非 ./..、长度不超过 200";
        }
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".html")) {
            return "fileName 必须以 .html 结尾";
        }
        return null;
    }

    private static String stringArg(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
