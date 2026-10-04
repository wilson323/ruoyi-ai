package org.ruoyi.ipd.agent.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 内置能力清单（classpath {@code ipd-skills/capability-packs.json}，随 JAR 版本化交付）。
 *
 * <p>W1 能力目录权威 = 本清单；DB 表 {@code ipd_capability_pack(+item)} 的种子与本清单同源，
 * W2 起由 DB 承载管理员扩展/覆盖（见项目智能体运行合同 §4）。清单缺失或格式错误即 fail-fast，
 * 不以空目录静默降级。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CapabilityManifest(int manifestVersion, List<SkillEntry> skills, List<ToolEntry> tools,
                                 List<PackEntry> packs) {

    /** 默认清单资源路径。 */
    public static final String DEFAULT_RESOURCE = "ipd-skills/capability-packs.json";

    /** 清单中的 Skill 锁定项（sha256 = SKILL.md 原始字节摘要）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillEntry(String name, String version, String sha256) { }

    /** 清单中的工具项。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolEntry(String id, String name, boolean readOnly) { }

    /** 能力包：组合 Skill + 工具，适用阶段与动作。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PackEntry(String code, String version, String name, String description,
                            List<String> stages, List<String> actionCodes,
                            List<String> skills, List<String> tools) {
        public PackEntry {
            stages = stages == null ? List.of() : List.copyOf(stages);
            actionCodes = actionCodes == null ? List.of() : List.copyOf(actionCodes);
            skills = skills == null ? List.of() : List.copyOf(skills);
            tools = tools == null ? List.of() : List.copyOf(tools);
        }
    }

    /** 规范化：空列表替代 null，避免调用方判空。 */
    public CapabilityManifest {
        skills = skills == null ? List.of() : List.copyOf(skills);
        tools = tools == null ? List.of() : List.copyOf(tools);
        packs = packs == null ? List.of() : List.copyOf(packs);
    }

    /**
     * 从 classpath 读取清单。
     *
     * @param resource 资源路径
     * @param mapper Jackson 映射器
     * @return 清单
     * @throws IllegalStateException 资源缺失或解析失败（fail-fast）
     */
    public static CapabilityManifest load(String resource, ObjectMapper mapper) {
        ClassLoader loader = CapabilityManifest.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("capability manifest missing: " + resource);
            }
            return mapper.readValue(in, CapabilityManifest.class);
        } catch (IOException e) {
            throw new IllegalStateException("capability manifest unreadable: " + resource, e);
        }
    }

    /**
     * 按 code+version 查能力包。
     *
     * @param code 包编码
     * @param version 包版本
     * @return 能力包
     */
    public Optional<PackEntry> pack(String code, String version) {
        return packs.stream()
            .filter(p -> Objects.equals(p.code(), code) && Objects.equals(p.version(), version))
            .findFirst();
    }

    /**
     * 按名称查 Skill 锁定项。
     *
     * @param name Skill 名
     * @return 锁定项
     */
    public Optional<SkillEntry> skill(String name) {
        return skills.stream().filter(s -> Objects.equals(s.name(), name)).findFirst();
    }

    /**
     * 按 ID 查工具项。
     *
     * @param id 工具 ID
     * @return 工具项
     */
    public Optional<ToolEntry> tool(String id) {
        return tools.stream().filter(t -> Objects.equals(t.id(), id)).findFirst();
    }
}
