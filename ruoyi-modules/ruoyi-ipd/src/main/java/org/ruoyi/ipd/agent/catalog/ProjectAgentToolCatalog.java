package org.ruoyi.ipd.agent.catalog;

import org.ruoyi.service.coding.harness.tool.ToolCapability;
import org.ruoyi.service.coding.harness.tool.ToolDescriptor;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 项目智能体业务及官方基础工具目录。
 *
 * <p>工具安全元数据保留既有 {@link ToolDescriptor} 投影；实际授权由官方权限扩展
 * 结合当前 IPD 身份与项目守卫裁决。目录登记不授予调用权限。清单未实现项明确不可用。
 */
public final class ProjectAgentToolCatalog {

    /** 项目资料检索（只读）工具 ID。 */
    public static final String PROJECT_KNOWLEDGE_SEARCH = "project_knowledge_search";

    /** 工具可用性投影。 */
    public record ToolStatus(String id, String name, boolean readOnly, boolean available, String reason) { }

    private static final Map<String, ToolDescriptor> DESCRIPTORS = descriptors();

    /**
     * 本地资料检索，加上清单里的服务标识。名称不参与能否调用。
     *
     * @return 工具安全描述
     */
    private static Map<String, ToolDescriptor> descriptors() {
        Map<String, ToolDescriptor> map = new HashMap<>();
        map.put(PROJECT_KNOWLEDGE_SEARCH, descriptor(PROJECT_KNOWLEDGE_SEARCH,
            "只读检索本项目已审核文档片段；项目范围由运行绑定，不接受模型传入"));
        for (ProductLineMcpCatalog.Endpoint endpoint : ProductLineMcpCatalog.all()) {
            map.put(endpoint.serviceId(), descriptor(endpoint.serviceId(),
                "只读查询产线「" + endpoint.lineName() + "」；服务标识 " + endpoint.serviceId()));
        }
        return Map.copyOf(map);
    }

    private static ToolDescriptor descriptor(String toolId, String summary) {
        return new ToolDescriptor(toolId, EnumSet.of(ToolCapability.READ, ToolCapability.SEARCH),
            true, 30_000L, 4_096L, 16_384L, false, summary);
    }

    private final CapabilityManifest manifest;
    private final ProjectAgentNativeToolCatalog.Provider nativeReadiness;

    public static final String NATIVE_TOOLS_VERSION = ProjectAgentNativeToolCatalog.VERSION;

    public static List<String> nativeToolIds() { return ProjectAgentNativeToolCatalog.IDS; }
    public static List<String> executionToolIds(List<String> businessIds) {
        return ProjectAgentNativeToolCatalog.executionIds(businessIds);
    }

    /**
     * @param manifest 内置清单
     */
    public ProjectAgentToolCatalog(CapabilityManifest manifest) {
        this(manifest, ProjectAgentNativeToolCatalog.unverified());
    }

    public ProjectAgentToolCatalog(CapabilityManifest manifest, ProjectAgentNativeToolCatalog.Provider nativeReadiness) {
        this.manifest = java.util.Objects.requireNonNull(manifest);
        this.nativeReadiness = java.util.Objects.requireNonNull(nativeReadiness);
    }

    /**
     * 清单工具的可用性。
     *
     * @return 状态列表
     */
    public List<ToolStatus> statuses() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>(nativeToolIds());
        manifest.tools().forEach(tool -> ids.add(tool.id()));
        return ids.stream().map(this::status).toList();
    }

    /**
     * 单个工具可用性。
     *
     * @param toolId 工具 ID
     * @return 状态
     */
    public ToolStatus status(String toolId) {
        if (nativeToolIds().contains(toolId)) {
            var readiness = nativeReadiness.status(toolId);
            if (readiness == null) readiness = new ProjectAgentNativeToolCatalog.Readiness(false, "工具运行环境尚未核验");
            return new ToolStatus(toolId, toolId, ProjectAgentNativeToolCatalog.readOnly(toolId),
                readiness.available(), readiness.reason());
        }
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
        ToolDescriptor nativeDescriptor = ProjectAgentNativeToolCatalog.descriptor(toolId);
        return nativeDescriptor == null ? DESCRIPTORS.get(toolId) : nativeDescriptor;
    }
}
