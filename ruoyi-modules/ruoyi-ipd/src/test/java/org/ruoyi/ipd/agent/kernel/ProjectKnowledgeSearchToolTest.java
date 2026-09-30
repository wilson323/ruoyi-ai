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

    private static KernelToolGovernance governance(ToolDescriptor... descriptors) {
        return new KernelToolGovernance(new ToolPolicyEngine(List.of(descriptors)), HarnessPermissionMode.READ_ONLY,
            new InMemoryKernelToolEffectLedger(), new KernelToolCallTrace());
    }
}
