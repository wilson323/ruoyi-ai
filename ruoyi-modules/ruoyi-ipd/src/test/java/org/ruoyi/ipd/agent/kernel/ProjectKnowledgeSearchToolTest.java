package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.tool.InMemoryKernelToolEffectLedger;
import org.ruoyi.chat.kernel.tool.KernelGovernedTool;
import org.ruoyi.chat.kernel.tool.KernelToolCallTrace;
import org.ruoyi.chat.kernel.tool.KernelToolGovernance;
import org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import org.ruoyi.service.coding.harness.model.HarnessPermissionMode;
import org.ruoyi.service.coding.harness.tool.PolicyDecision;
import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;
import org.ruoyi.service.coding.harness.tool.ToolPolicyEngine;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读项目资料检索工具：项目硬隔离（忽略模型传入的 projectId）、真实检索才上报 SOURCE、
 * 经 KernelGovernedTool 治理——ALLOW 执行恰一次，DENY/未知工具零副作用（检索端口不被触达）。
 */
@Tag("dev")
class ProjectKnowledgeSearchToolTest {

    @Test
    void sourceEventCarriesActualTypedIdsInsteadOfInferringFromSourceName() {
        List<Map<String, Object>> recorded = new java.util.ArrayList<>();
        var identity = new org.ruoyi.ipd.service.AiDocEmbeddingService.CitationSource(
            "KNOWLEDGE_FRAGMENT", "doc-1", "100", "101", "资料.md", "NOT_PROJECT_DOCUMENT");
        var typed = new ProjectKnowledgeSearchTool(1L, (p, t, q) ->
            new RetrievalContext(1, 2, "原句", "原句", List.of(identity)), recorded::add);
        typed.callAsync(param(Map.of("query", "资料"))).block();
        assertThat(recorded).singleElement().satisfies(event -> {
            assertThat(event.get("sourceEvidence").toString()).contains("KNOWLEDGE_FRAGMENT", "doc-1",
                "knowledgeId=100", "fragmentId=101", "NOT_PROJECT_DOCUMENT");
        });
    }

    private static final Long BOUND = 20260929L;

    private final List<Long> retrievedProjects = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> sources = new CopyOnWriteArrayList<>();
    private final ProjectKnowledgeSearchTool tool = new ProjectKnowledgeSearchTool(BOUND, (projectId, docType, q) -> {
        retrievedProjects.add(projectId);
        return new RetrievalContext(1, 20, "【相关历史文档片段 1｜竞品调研｜A】价格 999 元");
    }, sources::add);

    private static ToolCallParam param(Map<String, Object> input) {
        return ToolCallParam.builder()
            .toolUseBlock(new ToolUseBlock("call-1", ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH, input))
            .input(input)
            .build();
    }

    @Test
    @DisplayName("项目硬隔离：模型传入他项目 projectId/tenantId 被忽略，只检索运行绑定项目")
    void boundProjectIsTheOnlyScope() {
        ToolResultBlock result = tool.callAsync(param(Map.of("query", "竞品价格", "projectId", "999", "tenantId", "x")))
            .block();

        assertThat(retrievedProjects).containsExactly(BOUND);
        assertThat(result).isNotNull();
        assertThat(sources).singleElement().satisfies(s -> {
            assertThat(s.get("projectId")).isEqualTo(String.valueOf(BOUND));
            assertThat(s.get("hits")).isEqualTo(1);
            assertThat(s.get("toolCallId")).isEqualTo("call-1");
        });
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.getParameters().toString()).doesNotContain("projectId");
    }

    @Test
    @DisplayName("缺 query：返回错误结果，不检索、不上报来源")
    void missingQueryDoesNotRetrieve() {
        tool.callAsync(param(Map.of("docType", "MARKET"))).block();
        assertThat(retrievedProjects).isEmpty();
        assertThat(sources).isEmpty();
    }

    @Test
    @DisplayName("治理 ALLOW：READ_ONLY 下只读工具放行，执行恰一次并结算账本")
    void governedAllowExecutesOnce() {
        KernelToolGovernance governance = governance(ProjectAgentToolCatalog.descriptor(
            ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH));
        KernelGovernedTool governed = KernelGovernedTool.wrap(tool, governance);
        Map<String, Object> input = Map.of("query", "渠道");

        PermissionBehavior behavior = governed.checkPermissions(input, null).block().getBehavior();
        governed.callAsync(param(input)).block();

        assertThat(behavior).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(retrievedProjects).containsExactly(BOUND);
        assertThat(governance.trace().count()).isEqualTo(1);
        assertThat(governance.ledger().writeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("治理 DENY：声明为写能力时 READ_ONLY 拒绝，执行段 fail-closed，检索端口零触达")
    void governedDenyHasZeroSideEffects() {
        ToolDescriptor asWrite = new ToolDescriptor(ProjectAgentToolCatalog.PROJECT_KNOWLEDGE_SEARCH,
            EnumSet.of(ToolCapability.WRITE), true, 1_000L, 1_024L, 1_024L, false, "test write");
        KernelToolGovernance governance = governance(asWrite);
        KernelGovernedTool governed = KernelGovernedTool.wrap(tool, governance);
        Map<String, Object> input = Map.of("query", "技术路线");

        PermissionBehavior behavior = governed.checkPermissions(input, null).block().getBehavior();
        ToolResultBlock refused = governed.callAsync(param(input)).block();

        assertThat(behavior).isEqualTo(PermissionBehavior.DENY);
        assertThat(retrievedProjects).isEmpty();
        assertThat(sources).isEmpty();
        assertThat(refused).isNotNull();
        assertThat(governance.trace().events()).allSatisfy(e -> assertThat(e.decision()).isEqualTo(PolicyDecision.DENY));
    }

    @Test
    @DisplayName("治理未知工具：ToolPolicyEngine fail-closed DENY，零副作用")
    void unknownToolIsDenied() {
        KernelToolGovernance governance = governance();
        KernelGovernedTool governed = KernelGovernedTool.wrap(tool, governance);

        assertThat(governed.checkPermissions(Map.of("query", "a"), null).block().getBehavior())
            .isEqualTo(PermissionBehavior.DENY);
        governed.callAsync(param(Map.of("query", "a"))).block();
        assertThat(retrievedProjects).isEmpty();
    }

    @Test
    @DisplayName("零命中但向量故障：返回错误，保留故障而非伪装为没有资料")
    void zeroHitsWithFailureRemainsError() {
        String failure = ProjectKnowledgeVectorSearch.FAILURE_MARK + "服务暂不可用";
        ProjectKnowledgeSearchTool failed = new ProjectKnowledgeSearchTool(BOUND,
            (p, d, q) -> new RetrievalContext(0, failure.length(), failure), sources::add);
        ToolResultBlock result = failed.callAsync(param(Map.of("query", "海康威视"))).block();
        assertThat(result.getState().name()).isEqualTo("ERROR");
        assertThat(result.getOutput().toString()).contains("服务暂不可用").doesNotContain("未检索到");
        assertThat(sources).singleElement().satisfies(source ->
            assertThat(source.get("retrievalStatus")).isEqualTo("FAILED"));
    }

    @Test
    @DisplayName("局部故障且另有命中：保留片段和故障，标记部分成功")
    void partialHitsKeepFailureAndEvidence() {
        String block = ProjectKnowledgeVectorSearch.FAILURE_MARK + "向量不可用；原文：研发费用率12.83%";
        ProjectKnowledgeSearchTool partial = new ProjectKnowledgeSearchTool(BOUND,
            (p, d, q) -> new RetrievalContext(1, block.length(), block), sources::add);
        ToolResultBlock result = partial.callAsync(param(Map.of("query", "研发"))).block();
        // SDK text结果在Toolkit归一化前是RUNNING；此处验证它不是错误观察。
        assertThat(result.getState().name()).isNotEqualTo("ERROR");
        assertThat(result.getOutput().toString()).contains("向量不可用", "12.83%");
        assertThat(sources).singleElement().satisfies(source ->
            assertThat(source.get("retrievalStatus")).isEqualTo("PARTIAL"));
    }

    @Test
    @DisplayName("真实零命中：仍返回未取得说明，区别于故障")
    void emptyResultRemainsNoHit() {
        ProjectKnowledgeSearchTool empty = new ProjectKnowledgeSearchTool(BOUND,
            (p, d, q) -> new RetrievalContext(0, 0, ""), sources::add);
        ToolResultBlock result = empty.callAsync(param(Map.of("query", "研发"))).block();
        // SDK text结果在Toolkit归一化前是RUNNING；此处验证它不是错误观察。
        assertThat(result.getState().name()).isNotEqualTo("ERROR");
        assertThat(result.getOutput().toString()).contains("未取得：").contains("未检索到");
        assertThat(sources).singleElement().satisfies(source ->
            assertThat(source.get("retrievalStatus")).isEqualTo("NO_HIT"));
    }

    @Test
    @DisplayName("无权和命中原句分开写，不把无权写成没有资料")
    void deniedResultIsSeparateFromNoHit() {
        String block = ProjectKnowledgeVectorSearch.FAILURE_MARK + "没有权限读取该知识库";
        ProjectKnowledgeSearchTool denied = new ProjectKnowledgeSearchTool(BOUND,
            (p, d, q) -> new RetrievalContext(0, block.length(), block), sources::add);
        ToolResultBlock result = denied.callAsync(param(Map.of("query", "研发"))).block();
        assertThat(result.getState().name()).isEqualTo("ERROR");
        assertThat(result.getOutput().toString()).contains("无权：").contains("没有权限").doesNotContain("未取得");
        assertThat(sources).singleElement().satisfies(source ->
            assertThat(source.get("retrievalStatus")).isEqualTo("UNAUTHORIZED"));

        sources.clear();
        String quote = "【相关历史文档片段｜海康威视】研发费用率 12.83%";
        ProjectKnowledgeSearchTool hit = new ProjectKnowledgeSearchTool(BOUND,
            (p, d, q) -> new RetrievalContext(1, quote.length(), quote), sources::add);
        ToolResultBlock quoted = hit.callAsync(param(Map.of("query", "研发费用率"))).block();
        assertThat(quoted.getOutput().toString()).contains("命中原句：").contains("12.83%").doesNotContain("未取得");
        assertThat(sources).singleElement().satisfies(source ->
            assertThat(source.get("retrievalStatus")).isEqualTo("SUCCESS"));
    }

    private static KernelToolGovernance governance(ToolDescriptor... descriptors) {
        return new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptors)), HarnessPermissionMode.READ_ONLY,
            new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
    }
    @Test
    void fullSuccessfulEvidenceAndMixedFailureAreSeparated() {
        String full = "原文".repeat(600) + "12.83%";
        ProjectKnowledgeSearchTool successful = new ProjectKnowledgeSearchTool(BOUND,
            (project, type, query) -> new RetrievalContext(1, full.length(), full), sources::add);
        successful.callAsync(param(Map.of("query", "研发"))).block();
        assertThat(sources.get(0).get("citationText")).isEqualTo(full);
        assertThat((String) sources.get(0).get("preview")).hasSize(1000);
        ProjectKnowledgeSearchTool mixed = new ProjectKnowledgeSearchTool(BOUND,
            (project, type, query) -> new RetrievalContext(1, 100,
                full + ProjectKnowledgeVectorSearch.FAILURE_MARK + "错误118.69"), sources::add);
        mixed.callAsync(param(Map.of("query", "研发"))).block();
        assertThat(sources.get(1).get("citationText")).isEqualTo("");
    }

    @Test
    void partialResultPublishesOnlyExplicitSuccessfulCitation() {
        String quote = "【产品知识片段｜原始资料】研发费用率12.83%";
        String mixed = ProjectKnowledgeVectorSearch.FAILURE_MARK + "故障118.69\n" + quote;
        ProjectKnowledgeSearchTool partial = new ProjectKnowledgeSearchTool(BOUND,
            (p, d, q) -> new RetrievalContext(1, mixed.length(), mixed, quote), sources::add);
        partial.callAsync(param(Map.of("query", "研发"))).block();
        assertThat(sources).singleElement().satisfies(source -> {
            assertThat(source.get("retrievalStatus")).isEqualTo("PARTIAL");
            assertThat(source.get("citationStatus")).isEqualTo("SUCCESS");
            assertThat(source.get("citationText")).isEqualTo(quote);
            assertThat((String) source.get("citationText")).doesNotContain("118.69", "故障");
        });
    }
    @Test
    void partialToolTextSeparatesSourceLimitationsFromQuotedEvidence() {
        String quote = "【产品知识片段 1｜正式资料.md｜出处：原文件】\n研发费用率12.83%\n";
        String failure = ProjectKnowledgeVectorSearch.FAILURE_MARK + "缺失来源，错误数字118.69\n";
        ToolResultBlock result = ProjectKnowledgeSearchTool.result(
            new RetrievalContext(1, (failure + quote).length(), failure + quote, quote));
        String text = result.getOutput().toString();
        assertThat(text).contains("部分成功", "限制仅影响对应来源", "检索限制（不作引用）");
        assertThat(text.substring(text.indexOf("命中原句："))).contains("12.83%")
            .doesNotContain("118.69", "缺失来源", "检索失败");
        assertThat(text.indexOf("12.83%")).isEqualTo(text.lastIndexOf("12.83%"));
    }

    @Test
    void legacyMixedBlockWithoutIndependentCitationIsNeverLabelledAsQuote() {
        String block = ProjectKnowledgeVectorSearch.FAILURE_MARK + "错误118.69；疑似原文12.83%";
        String text = ProjectKnowledgeSearchTool.result(new RetrievalContext(1, block.length(), block))
            .getOutput().toString();
        assertThat(text).contains("部分成功", "未取得可独立引用").doesNotContain("命中原句：");
    }
}
