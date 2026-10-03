package org.ruoyi.ipd.agent.catalog;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 服务端唯一 Skill 目录（C4/B5）：经 AgentScope 原生 {@link ClasspathSkillRepository}
 * 读取 JAR 内 {@code ipd-skills/}，并对每个 Skill 做 SHA-256 完整性校验。
 *
 * <p>可用判定（全部满足才 available）：清单登记 → 仓库存在同名 Skill → 原始字节摘要
 * 与清单一致 → 正文非空。任一不满足给出明确 reason，运行时拒绝选用，不以空正文降级。
 * 不依赖开发机路径（{@code /Users/...}），新环境随 JAR 即可读取相同正文。
 */
public class ProjectAgentSkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(ProjectAgentSkillCatalog.class);

    /** Skill 可用性投影。 */
    public record SkillStatus(String name, String version, String sha256, boolean available, String reason) { }

    /** 已校验通过的 Skill 正文。 */
    public record LoadedSkill(String name, String version, String sha256, String content,
                              org.ruoyi.ipd.agent.kernel.ProjectAgentSkillBundle bundle) {
        public LoadedSkill(String name, String version, String sha256, String content) {
            this(name, version, sha256, content, null);
        }
    }
    public interface PublishedResolver {
        Optional<LoadedSkill> load(String tenantId, Long projectId, Long personId, String name, String digest);
        List<SkillStatus> statuses(String tenantId, Long projectId, Long personId);
    }
    private volatile PublishedResolver published;
    public void setPublishedResolver(PublishedResolver resolver) { published = java.util.Objects.requireNonNull(resolver); }
    public Optional<LoadedSkill> load(String name, String tenantId, Long projectId, Long personId, String digest) {
        var reviewed = published == null ? Optional.<LoadedSkill>empty()
            : published.load(tenantId, projectId, personId, name, digest);
        if (reviewed.isPresent()) return reviewed;
        var builtIn = load(name);
        return builtIn.isPresent() && (digest == null || digest.equals(builtIn.get().sha256())) ? builtIn : Optional.empty();
    }
    public List<SkillStatus> statuses(String tenantId, Long projectId, Long personId) {
        var result = new java.util.LinkedHashMap<String, SkillStatus>();
        for (var status : statuses()) result.put(status.name(), status);
        if (published != null) for (var status : published.statuses(tenantId, projectId, personId))
            result.put(status.name(), status);
        return List.copyOf(result.values());
    }

    private final String resourceRoot;
    private final CapabilityManifest manifest;

    /**
     * @param resourceRoot classpath 根目录（生产 {@code ipd-skills}）
     * @param manifest 内置清单
     */
    public ProjectAgentSkillCatalog(String resourceRoot, CapabilityManifest manifest) {
        this.resourceRoot = resourceRoot;
        this.manifest = manifest;
    }

    /**
     * 清单内全部 Skill 的可用性。
     *
     * @return 状态列表（顺序同清单）
     */
    public List<SkillStatus> statuses() {
        return manifest.skills().stream().map(this::status).toList();
    }

    /**
     * 单个 Skill 的可用性（未登记也返回不可用状态）。
     *
     * @param name Skill 名
     * @return 状态
     */
    public SkillStatus status(String name) {
        return manifest.skill(name).map(this::status)
            .orElseGet(() -> new SkillStatus(name, null, null, false, "Skill 未在内置清单登记"));
    }

    /**
     * 加载通过完整性校验的 Skill 正文（运行时唯一取正文入口）。
     *
     * @param name Skill 名
     * @return 正文；不可用为空
     */
    public Optional<LoadedSkill> load(String name) {
        Optional<CapabilityManifest.SkillEntry> entry = manifest.skill(name);
        if (entry.isEmpty()) {
            return Optional.empty();
        }
        Inspection inspection = inspect(entry.get());
        if (inspection.reason() != null) {
            return Optional.empty();
        }
        return Optional.of(new LoadedSkill(name, entry.get().version(), inspection.actualSha(), inspection.content()));
    }

    private SkillStatus status(CapabilityManifest.SkillEntry entry) {
        Inspection inspection = inspect(entry);
        return new SkillStatus(entry.name(), entry.version(), entry.sha256(),
            inspection.reason() == null, inspection.reason());
    }

    /** 读取原生仓库正文 + 原始字节摘要，返回首个不满足项的原因。 */
    private Inspection inspect(CapabilityManifest.SkillEntry entry) {
        AgentSkill skill;
        try (ClasspathSkillRepository repository = new ClasspathSkillRepository(resourceRoot)) {
            if (!repository.skillExists(entry.name())) {
                return Inspection.rejected("Skill 资源缺失");
            }
            skill = repository.getSkill(entry.name());
        } catch (IOException | RuntimeException e) {
            // 2026-10-03：末参带异常对象补齐 message/栈（15:06 skillNames 502 排查时观测缺口同款），
            // 写法与 IpdServiceExceptionAdvice#handleUnexpected 的 type={}+末参 e 一致。
            log.warn("project_agent_skill operation=LOAD status=FAILED skill={} errorType={}",
                entry.name(), e.getClass().getName(), e);
            return Inspection.rejected("Skill 仓库不可读");
        }
        String actualSha = sha256OfRaw(entry.name());
        if (actualSha == null) {
            return Inspection.rejected("Skill 资源缺失");
        }
        if (!actualSha.equalsIgnoreCase(entry.sha256())) {
            log.warn("project_agent_skill operation=VERIFY status=MISMATCH skill={}", entry.name());
            return Inspection.rejected("Skill 完整性校验失败（sha256 与清单不一致）");
        }
        String content = skill == null ? null : skill.getSkillContent();
        if (content == null || content.isBlank()) {
            return Inspection.rejected("Skill 正文为空");
        }
        return new Inspection(null, actualSha, content);
    }

    /** SKILL.md 原始字节 SHA-256（与清单锁定口径一致）。 */
    private String sha256OfRaw(String skillName) {
        String path = resourceRoot + "/" + skillName + "/SKILL.md";
        try (InputStream in = ProjectAgentSkillCatalog.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return sha256Hex(in.readAllBytes());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 字节 SHA-256 十六进制。
     *
     * @param bytes 原始字节
     * @return 小写十六进制摘要
     */
    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * 文本（UTF-8）SHA-256 十六进制。
     *
     * @param text 文本
     * @return 小写十六进制摘要
     */
    public static String sha256Hex(String text) {
        return sha256Hex((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    private record Inspection(String reason, String actualSha, String content) {
        static Inspection rejected(String reason) {
            return new Inspection(reason, null, null);
        }
    }
}
