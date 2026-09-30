package org.ruoyi.ipd.agent.catalog;

import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * 项目智能体工具目录（W1 仅一个只读工具）。
 *
 * <p>工具安全元数据以既有自研 {@link ToolDescriptor} 声明，由 {@code ToolPolicyEngine}
 * 作为唯一裁决源（红线 D1：不另造白名单语义）。清单登记而本目录无实现的工具一律不可用。
 */
public final class ProjectAgentToolCatalog {

    /** 项目资料检索（只读）工具 ID。 */
    public static final String PROJECT_KNOWLEDGE_SEARCH = "project_knowledge_search";

    /** 工具可用性投影。 */
    public record ToolStatus(String id, String name, boolean readOnly, boolean available, String reason) { }

    private static final Map<String, ToolDescriptor> DESCRIPTORS = Map.of(
        PROJECT_KNOWLEDGE_SEARCH,
        new ToolDescriptor(PROJECT_KNOWLEDGE_SEARCH, EnumSet.of(ToolCapability.READ, ToolCapability.SEARCH),
            true, 30_000L, 4_096L, 16_384L, false,
            "只读检索本项目已审核文档片段；项目范围由运行绑定，不接受模型传入"));

    private final CapabilityManifest manifest;

    /**
     * @param manifest 内置清单
     */
    public ProjectAgentToolCatalog(CapabilityManifest manifest) {
        this.manifest = manifest;
    }

    /**
     * 清单工具的可用性。
     *
     * @return 状态列表
     */
    public List<ToolStatus> statuses() {
        return manifest.tools().stream().map(t -> status(t.id())).toList();
    }

    /**
     * 单个工具可用性。
     *
     * @param toolId 工具 ID
     * @return 状态
     */
    public ToolStatus status(String toolId) {
        CapabilityManifest.ToolEntry entry = manifest.tool(toolId).orElse(null);
        if (entry == null) {
            return new ToolStatus(toolId, null, false, false, "工具未在内置清单登记");
        }
        ToolDescriptor descriptor = DESCRIPTORS.get(toolId);
        if (descriptor == null) {
            return new ToolStatus(toolId, entry.name(), entry.readOnly(), false, "工具实现未交付");
        }
        boolean readOnly = !descriptor.hasCapability(ToolCapability.WRITE)
            && !descriptor.hasCapability(ToolCapability.DESTRUCTIVE)
            && !descriptor.hasCapability(ToolCapability.EXECUTE)
            && !descriptor.hasCapability(ToolCapability.NETWORK);
        if (!readOnly) {
            return new ToolStatus(toolId, entry.name(), false, false, "W1 仅开放只读工具");
        }
        return new ToolStatus(toolId, entry.name(), true, true, null);
    }

    /**
     * 工具安全描述（裁决源输入）。
     *
     * @param toolId 工具 ID
     * @return 描述；未实现为 null
     */
    public static ToolDescriptor descriptor(String toolId) {
        return DESCRIPTORS.get(toolId);
    }
}
