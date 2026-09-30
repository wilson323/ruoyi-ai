package org.ruoyi.ipd.agent.service;

import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本人项目运行列表：搜索只碰动作、状态和产物标题正文。
 */
final class ProjectAgentRunListing {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int EXCERPT_CHARS = 80;

    private ProjectAgentRunListing() {
    }

    /**
     * 组装一页运行摘要。
     *
     * @param store 运行存储
     * @param artifacts 产物存储，可空
     * @param tenantId 可见租户
     * @param projectId 项目
     * @param personId 当前人
     * @param status 精确状态，可空
     * @param actionCode 精确动作，可空
     * @param q 搜索词，可空
     * @param cursor 上一页最后的 runId，可空
     * @param limit 条数，空则 20，超过 50 截断
     * @return 摘要；不含 inputDigest 和用户原文
     */
    static List<ProjectAgentViews.RunItem> page(AgentRunStore store, ArtifactVersionStore artifacts,
                                                 String tenantId, Long projectId, Long personId,
                                                 String status, String actionCode, String q,
                                                 Long cursor, Integer limit) {
        String text = q == null ? "" : q.trim();
        Set<Long> hits = text.isEmpty() || artifacts == null
            ? Set.of() : artifacts.findRunIdsByContent(tenantId, text);
        List<IpdAgentRun> runs = store.listOwnRuns(new AgentRunStore.OwnRunQuery(
            tenantId, projectId, personId, normalizeStatus(status),
            actionCode == null ? null : actionCode.trim(), text, hits, cursor, normalizeLimit(limit)));
        Map<Long, List<IpdAgentArtifactVersion>> byRun = versionsByRun(artifacts, runs);
        List<ProjectAgentViews.RunItem> items = new ArrayList<>();
        for (IpdAgentRun run : runs) {
            items.add(toItem(run, byRun.getOrDefault(run.getId(), List.of())));
        }
        return List.copyOf(items);
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return AgentRunStatus.valueOf(status.trim()).name();
        } catch (IllegalArgumentException e) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "status 不是运行状态");
        }
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "limit 必须为正");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static Map<Long, List<IpdAgentArtifactVersion>> versionsByRun(
        ArtifactVersionStore artifacts, List<IpdAgentRun> runs) {
        Map<Long, List<IpdAgentArtifactVersion>> grouped = new LinkedHashMap<>();
        if (artifacts == null || runs.isEmpty()) {
            return grouped;
        }
        List<Long> ids = runs.stream().map(IpdAgentRun::getId).toList();
        for (IpdAgentArtifactVersion version : artifacts.listByRunIds(ids)) {
            grouped.computeIfAbsent(version.getRunId(), ignored -> new ArrayList<>()).add(version);
        }
        return grouped;
    }

    private static ProjectAgentViews.RunItem toItem(IpdAgentRun run, List<IpdAgentArtifactVersion> versions) {
        List<String> titles = versions.stream().map(IpdAgentArtifactVersion::getTitle).toList();
        String excerpt = versions.isEmpty() ? null : excerpt(versions.get(0).getContent());
        return new ProjectAgentViews.RunItem(
            ProjectAgentViews.id(run.getId()), run.getStatus(), run.getActionCode(),
            run.getCapabilityPackCode(), run.getCapabilityPackVersion(),
            ProjectAgentViews.iso(run.getCreateTime()), ProjectAgentViews.iso(run.getFinishedAt()),
            run.getInputChars(), titles, excerpt);
    }

    private static String excerpt(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        return content.length() <= EXCERPT_CHARS ? content : content.substring(0, EXCERPT_CHARS);
    }
}
