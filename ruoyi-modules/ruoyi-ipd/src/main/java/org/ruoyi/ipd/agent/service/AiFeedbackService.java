package org.ruoyi.ipd.agent.service;

import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAiFeedback;
import org.ruoyi.ipd.agent.dto.AiFeedbackReq;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.AiFeedbackStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.IpdCopilotAccess;

import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * AI 点赞/点踩（合同 #6）：绑定持久目标，本人可更新，跨项目/非本人目标拒绝且零写入。
 *
 * <p>RUN_MESSAGE：targetId = runId。ARTIFACT_VERSION：仅当产物版本行存在时接受，
 * targetId = 版本行雪花 id；不存在仍 STATE_CONFLICT。不另做反馈表。
 */
public class AiFeedbackService {

    private static final Set<String> RATINGS = Set.of("UP", "DOWN");

    private final boolean enabled;
    private final IpdCopilotAccess access;
    private final AgentRunStore runStore;
    private final ArtifactVersionStore artifactStore;
    private final AiFeedbackStore feedbackStore;
    private final LongSupplier clock;

    /**
     * @param enabled 开关
     * @param access 项目访问守卫
     * @param runStore 运行存储
     * @param artifactStore 产物版本存储（可空：ARTIFACT_VERSION 恒 STATE_CONFLICT）
     * @param feedbackStore 反馈存储
     * @param clock 毫秒时钟
     */
    public AiFeedbackService(boolean enabled, IpdCopilotAccess access, AgentRunStore runStore,
                             ArtifactVersionStore artifactStore, AiFeedbackStore feedbackStore,
                             LongSupplier clock) {
        this.enabled = enabled;
        this.access = Objects.requireNonNull(access, "access");
        this.runStore = Objects.requireNonNull(runStore, "runStore");
        this.artifactStore = artifactStore;
        this.feedbackStore = Objects.requireNonNull(feedbackStore, "feedbackStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 兼容无产物存储的装配（ARTIFACT_VERSION 恒 STATE_CONFLICT）。
     *
     * @param enabled 开关
     * @param access 访问守卫
     * @param runStore 运行存储
     * @param feedbackStore 反馈存储
     * @param clock 时钟
     */
    public AiFeedbackService(boolean enabled, IpdCopilotAccess access, AgentRunStore runStore,
                             AiFeedbackStore feedbackStore, LongSupplier clock) {
        this(enabled, access, runStore, null, feedbackStore, clock);
    }

    /**
     * 写入或更新本人反馈。
     *
     * @param actor 会话身份
     * @param targetType 目标类型
     * @param rawTargetId 目标 ID（字符串）
     * @param req 评分与原因
     * @return 反馈视图
     */
    public ProjectAgentViews.Feedback put(IpdActor actor, String targetType, String rawTargetId, AiFeedbackReq req) {
        if (!enabled) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, ProjectAgentConstants.REASON_DISABLED);
        }
        String rating = req == null || req.rating() == null ? null : req.rating().trim().toUpperCase(Locale.ROOT);
        if (!RATINGS.contains(rating)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "rating 须为 UP 或 DOWN");
        }
        String reason = req.reason() == null || req.reason().isBlank() ? null : req.reason().trim();
        if (reason != null && reason.length() > ProjectAgentConstants.FEEDBACK_REASON_MAX_CHARS) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "reason 超过 500 字");
        }
        Long targetId = parseId(rawTargetId);
        Long projectId;
        String tenantId;
        if (ProjectAgentConstants.TARGET_ARTIFACT_VERSION.equals(targetType)) {
            ResolvedTarget resolved = resolveArtifactVersion(actor, targetId);
            projectId = resolved.projectId();
            tenantId = resolved.tenantId();
        } else if (ProjectAgentConstants.TARGET_RUN_MESSAGE.equals(targetType)) {
            ResolvedTarget resolved = resolveRunMessage(actor, targetId);
            projectId = resolved.projectId();
            tenantId = resolved.tenantId();
        } else {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "targetType 须为 RUN_MESSAGE 或 ARTIFACT_VERSION");
        }
        Date now = new Date(clock.getAsLong());
        boolean saved = feedbackStore.find(targetType, targetId, actor.id()).isPresent()
            ? feedbackStore.updateRating(targetType, targetId, actor.id(), rating, reason)
            : insertOrUpdate(actor, tenantId, projectId, targetType, targetId, rating, reason, now);
        if (!saved) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "反馈保存冲突，请重试");
        }
        return new ProjectAgentViews.Feedback(targetType, String.valueOf(targetId), rating, reason,
            ProjectAgentViews.iso(now));
    }

    /** 内部采纳事件；与可更新的点赞分开，首次事件不可覆盖，不授予知识晋升。 */
    public void recordArtifactAdoption(IpdAgentRun run, IpdAgentArtifactVersion version, Long personId) {
        if (!enabled || run == null || run.getId() == null || run.getProjectId() == null
            || run.getTenantId() == null || personId == null || version == null || version.getId() == null
            || !Objects.equals(run.getId(), version.getRunId())
            || !Objects.equals(run.getTenantId(), version.getTenantId())
            || !Objects.equals(run.getPersonId(), personId)) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "采纳反馈来源不一致");
        }
        String type = "ARTIFACT_ADOPTION";
        IpdAiFeedback existing = feedbackStore.find(type, version.getId(), personId).orElse(null);
        if (existing == null) {
            Date now = new Date(clock.getAsLong());
            IpdAiFeedback row = IpdAiFeedback.builder().tenantId(run.getTenantId())
                .projectId(run.getProjectId()).personId(personId).targetType(type).targetId(version.getId())
                .rating("UP").reason("用户采纳该产物版本；不代表文档审核或知识晋升")
                .version(0).delFlag("0").build();
            row.setCreateTime(now);
            row.setCreateBy(personId);
            row.setUpdateBy(personId);
            if (feedbackStore.insert(row)) return;
            existing = feedbackStore.find(type, version.getId(), personId).orElse(null);
        }
        if (existing == null || !Objects.equals(existing.getTenantId(), run.getTenantId())
            || !Objects.equals(existing.getProjectId(), run.getProjectId())
            || !Objects.equals(existing.getPersonId(), personId)
            || !"0".equals(existing.getDelFlag()) || !"UP".equals(existing.getRating())) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "采纳反馈保存冲突，请重试");
        }
    }

    private ResolvedTarget resolveRunMessage(IpdActor actor, Long runId) {
        IpdAgentRun run = runStore.findRun(runId)
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "反馈目标不存在"));
        String tenantId = access.requireVisible(actor, run.getProjectId());
        if (!Objects.equals(tenantId, run.getTenantId()) || !Objects.equals(actor.id(), run.getPersonId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "反馈目标不存在");
        }
        return new ResolvedTarget(tenantId, run.getProjectId());
    }

    private ResolvedTarget resolveArtifactVersion(IpdActor actor, Long versionId) {
        if (artifactStore == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物版本反馈依赖产物版本表，当前不可用");
        }
        IpdAgentArtifactVersion version = artifactStore.findById(versionId).orElse(null);
        if (version == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "产物版本不存在，无法反馈");
        }
        IpdAgentRun run = runStore.findRun(version.getRunId())
            .orElseThrow(() -> new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "反馈目标不存在"));
        String tenantId = access.requireVisible(actor, run.getProjectId());
        if (!Objects.equals(tenantId, run.getTenantId()) || !Objects.equals(actor.id(), run.getPersonId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "反馈目标不存在");
        }
        return new ResolvedTarget(tenantId, run.getProjectId());
    }

    private boolean insertOrUpdate(IpdActor actor, String tenantId, Long projectId, String targetType,
                                   Long targetId, String rating, String reason, Date now) {
        IpdAiFeedback feedback = IpdAiFeedback.builder()
            .tenantId(tenantId)
            .targetType(targetType)
            .targetId(targetId)
            .projectId(projectId)
            .personId(actor.id())
            .rating(rating)
            .reason(reason)
            .version(0)
            .delFlag("0")
            .build();
        feedback.setCreateTime(now);
        feedback.setCreateBy(actor.id());
        feedback.setUpdateBy(actor.id());
        return feedbackStore.insert(feedback)
            || feedbackStore.updateRating(targetType, targetId, actor.id(), rating, reason);
    }

    private static Long parseId(String raw) {
        try {
            return Long.parseLong(raw == null ? "" : raw.trim());
        } catch (NumberFormatException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "targetId 格式错误");
        }
    }

    private record ResolvedTarget(String tenantId, Long projectId) { }
}
