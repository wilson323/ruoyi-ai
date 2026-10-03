package org.ruoyi.ipd.agent.vo;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * 项目智能体接口响应形状（合同 #1–#6）。全部 ID 为字符串，时间为 UTC ISO-8601 字符串，
 * 与 {@code ApiV1Response} 的 code=0/message 包络组合输出。
 */
public final class ProjectAgentViews {

    private static final DateTimeFormatter ISO_UTC =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private ProjectAgentViews() {
    }

    /** Boxed nullable cursors remain JSON numbers despite the primary mapper's Long ID serializer. */
    public static final class SequenceNumberSerializer extends com.fasterxml.jackson.databind.JsonSerializer<Long> {
        @Override public void serialize(Long value, com.fasterxml.jackson.core.JsonGenerator generator,
                com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
            generator.writeNumber(value);
        }
    }

    /**
     * Date → UTC ISO-8601（null 透传）。
     *
     * @param date 时间
     * @return ISO 字符串或 null
     */
    public static String iso(Date date) {
        return date == null ? null : ISO_UTC.format(date.toInstant());
    }

    /**
     * Long → 字符串 ID（null 透传）。
     *
     * @param id 数值 ID
     * @return 字符串 ID
     */
    public static String id(Long id) {
        return id == null ? null : String.valueOf(id);
    }

    /** #1 能力目录。 */
    public record Capabilities(List<Pack> packs, List<Model> models) { }

    /** 能力包。 */
    public record Pack(String code, String version, String name, String description, List<String> stages,
                       List<String> actionCodes, boolean available, String unavailableReason,
                       List<Skill> skills, List<Tool> tools) { }

    /** Skill 可用性。 */
    public record Skill(String name, String version, String sha256, boolean available, String reason) { }

    /** 工具可用性。 */
    public record Tool(String id, String name, boolean readOnly, boolean available, String reason) { }

    /** 模型可用性。 */
    public record Model(String id, String name, boolean available, String reason) { }

    /** #2/#5 创建与取消结果。 */
    public record RunStatus(String runId, String status) { }

    /** #3 运行详情。 */
    public record Run(String runId, String projectId, String agentId, String status, String actionCode,
                      ConfigSnapshot configSnapshot, String errorCode, String createdAt, String finishedAt,
                      List<ArtifactArchive> artifactArchives,
                      @com.fasterxml.jackson.databind.annotation.JsonSerialize(using = SequenceNumberSerializer.class)
                      Long pauseSeq) {
        public Run(String runId, String projectId, String agentId, String status, String actionCode,
                   ConfigSnapshot configSnapshot, String errorCode, String createdAt, String finishedAt,
                   List<ArtifactArchive> artifactArchives) {
            this(runId, projectId, agentId, status, actionCode, configSnapshot, errorCode, createdAt,
                finishedAt, artifactArchives, null);
        }
        public Run(String runId, String projectId, String agentId, String status, String actionCode,
                   ConfigSnapshot configSnapshot, String errorCode, String createdAt, String finishedAt) {
            this(runId, projectId, agentId, status, actionCode, configSnapshot, errorCode, createdAt, finishedAt, List.of());
        }
    }
    /** 原运行产物的权威定档关联，刷新后仍由同一详情口读取。 */
    public record ArtifactArchive(String artifactId, String documentId) { }

    /**
     * 本人运行列表项。没有用户原文，也没有 inputDigest。
     *
     * @param runId 运行 ID
     * @param status 状态
     * @param actionCode 动作，可空
     * @param capabilityPackCode 能力包
     * @param capabilityPackVersion 能力包版本
     * @param createdAt 创建时间
     * @param finishedAt 结束时间，可空
     * @param inputChars 当时输入长度
     * @param artifactTitles 产物标题
     * @param artifactExcerpt 产物正文前 80 字
     */
    public record RunItem(String runId, String status, String actionCode, String capabilityPackCode,
                          String capabilityPackVersion, String createdAt, String finishedAt,
                          Integer inputChars, List<String> artifactTitles, String artifactExcerpt) { }

    /** 运行冻结的配置快照（同时是 ipd_agent_run.config_snapshot 的 JSON 形状）。 */
    public record ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                                 List<SkillRef> skills, List<String> toolIds,
                                 String previousRunId, String targetDocumentId, String baseVersionId,
                                 String aguiInputDigest, String requirementId, String productLineId,
                                 List<io.agentscope.core.agui.model.AguiTool> serverFrontendTools,
                                 List<String> executionToolIds,
                                 org.ruoyi.ipd.agent.model.ProjectAgentModelFingerprint.Snapshot modelFingerprint,
                                 Integer modelIdentityVersion, String fallbackModelConfigId, Integer outputContractVersion) {
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                List<SkillRef> skills, List<String> toolIds, String previousRunId, String targetDocumentId,
                String baseVersionId, String aguiInputDigest, String requirementId, String productLineId,
                List<io.agentscope.core.agui.model.AguiTool> serverFrontendTools, List<String> executionToolIds,
                org.ruoyi.ipd.agent.model.ProjectAgentModelFingerprint.Snapshot modelFingerprint,
                Integer modelIdentityVersion, String fallbackModelConfigId) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds, previousRunId,
                targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, executionToolIds, modelFingerprint, modelIdentityVersion, fallbackModelConfigId, null);
        }
        public ConfigSnapshot withOutputContractVersion(Integer version) {
            return new ConfigSnapshot(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, executionToolIds, modelFingerprint, modelIdentityVersion, fallbackModelConfigId, version);
        }
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                              List<SkillRef> skills, List<String> toolIds, String previousRunId,
                              String targetDocumentId, String baseVersionId, String aguiInputDigest,
                              String requirementId, String productLineId,
                              List<io.agentscope.core.agui.model.AguiTool> serverFrontendTools,
                              List<String> executionToolIds,
                              org.ruoyi.ipd.agent.model.ProjectAgentModelFingerprint.Snapshot modelFingerprint) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds, previousRunId,
                targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, executionToolIds, modelFingerprint, null, null);
        }
        public ConfigSnapshot withModelIdentityVersion(Integer version, String fallbackId) {
            return new ConfigSnapshot(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, executionToolIds, modelFingerprint, version, fallbackId, outputContractVersion);
        }
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                              List<SkillRef> skills, List<String> toolIds, String previousRunId,
                              String targetDocumentId, String baseVersionId, String aguiInputDigest,
                              String requirementId, String productLineId,
                              List<io.agentscope.core.agui.model.AguiTool> serverFrontendTools,
                              List<String> executionToolIds) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds, previousRunId,
                targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, executionToolIds, null);
        }
        public ConfigSnapshot withModelFingerprint(org.ruoyi.ipd.agent.model.ProjectAgentModelFingerprint.Snapshot fingerprint) {
            return new ConfigSnapshot(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, executionToolIds, fingerprint, modelIdentityVersion, fallbackModelConfigId, outputContractVersion);
        }
        public ConfigSnapshot {
            serverFrontendTools = serverFrontendTools == null ? List.of() : List.copyOf(serverFrontendTools);
            executionToolIds = executionToolIds == null ? null : List.copyOf(executionToolIds);
        }
        /** 历史快照不自动扩大原生工具授权范围。 */
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                              List<SkillRef> skills, List<String> toolIds,
                              String previousRunId, String targetDocumentId, String baseVersionId,
                              String aguiInputDigest, String requirementId, String productLineId,
                              List<io.agentscope.core.agui.model.AguiTool> serverFrontendTools) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, null);
        }
        public ConfigSnapshot withExecutionToolIds(List<String> ids) {
            return new ConfigSnapshot(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId,
                serverFrontendTools, ids, modelFingerprint, modelIdentityVersion, fallbackModelConfigId, outputContractVersion);
        }
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                              List<SkillRef> skills, List<String> toolIds,
                              String previousRunId, String targetDocumentId, String baseVersionId,
                              String aguiInputDigest, String requirementId, String productLineId) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId, List.of());
        }
        public ConfigSnapshot withServerFrontendTools(List<io.agentscope.core.agui.model.AguiTool> tools) {
            return new ConfigSnapshot(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, requirementId, productLineId, tools, executionToolIds, modelFingerprint, modelIdentityVersion, fallbackModelConfigId, outputContractVersion);
        }
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                              List<SkillRef> skills, List<String> toolIds,
                              String previousRunId, String targetDocumentId, String baseVersionId,
                              String aguiInputDigest) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, aguiInputDigest, null, null);
        }
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                              List<SkillRef> skills, List<String> toolIds,
                              String previousRunId, String targetDocumentId, String baseVersionId) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds,
                previousRunId, targetDocumentId, baseVersionId, null);
        }
        public ConfigSnapshot(String capabilityPackCode, String capabilityPackVersion, String modelConfigId,
                              List<SkillRef> skills, List<String> toolIds) {
            this(capabilityPackCode, capabilityPackVersion, modelConfigId, skills, toolIds, null, null, null);
        }
    }

    /** 快照中的 Skill 引用。 */
    public record SkillRef(String name, String sha256) { }

    /** #4 事件分页。 */
    public record Events(List<Event> events, long nextSeq, boolean terminal) { }

    /** 单个事件；payload 为 JSON 对象。 */
    public record Event(long seq, String type, Object payload, String createdAt) { }

    /** #6 反馈结果。 */
    public record Feedback(String targetType, String targetId, String rating, String reason, String updatedAt) { }

    /**
     * 产物 apply 结果。indexStatus 如实返回；文档未审、未向量化时不得伪造 READY。
     *
     * @param runId 运行 ID
     * @param artifactId 逻辑产物 ID
     * @param versionId 版本行 ID（反馈 targetId）
     * @param versionNo 版本号
     * @param documentId 落库项目文档 ID
     * @param documentStatus 库内码（GENERATED=待审核，不是展示名）
     * @param indexStatus 索引真实状态（如 NOT_INDEXED）；未入库不得写成 READY
     * @param documentStatusLabel 用户可见主状态（GENERATED → 待审核）
     */
    public record ArtifactApply(String runId, String artifactId, String versionId, int versionNo,
                                String documentId, String documentStatus, String indexStatus,
                                String documentStatusLabel) { }
}
