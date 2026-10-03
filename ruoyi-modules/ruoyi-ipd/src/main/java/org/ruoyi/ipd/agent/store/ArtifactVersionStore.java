package org.ruoyi.ipd.agent.store;

import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 产物版本持久化端口。
 */
public interface ArtifactVersionStore {

    /**
     * 插入草稿版本。
     *
     * @param version 待插入行
     * @return true 插入成功；false 唯一键冲突
     */
    boolean insert(IpdAgentArtifactVersion version);

    /**
     * 按主键查版本（反馈 ARTIFACT_VERSION 用）。
     *
     * @param versionId 版本行 ID
     * @return 版本行
     */
    Optional<IpdAgentArtifactVersion> findById(Long versionId);

    /**
     * 查运行下某产物的最新版本。
     *
     * @param runId 运行 ID
     * @param artifactId 逻辑产物 ID
     * @return 最新版本（按 version_no 降序）
     */
    Optional<IpdAgentArtifactVersion> findLatest(Long runId, String artifactId);

    /** 在调用方事务内锁定同租户、运行与产物的最新版本；不支持锁的实现必须拒绝。 */
    default Optional<IpdAgentArtifactVersion> findLatestForUpdate(String tenantId, Long runId, String artifactId) {
        throw new UnsupportedOperationException("artifact row locking is not supported");
    }

    /**
     * 将 DRAFT 标记为 APPLIED 并回写 documentId（CAS：仅 DRAFT 可迁）。
     *
     * @param versionId 版本行 ID
     * @param documentId 项目文档 ID
     * @return true 本次赢得迁移
     */
    boolean markApplied(Long versionId, Long documentId);

    /**
     * 按产物标题或正文找出运行。SQL 实现必须参数绑定，不得拼接搜索词。
     *
     * @param tenantId 租户
     * @param text 搜索词
     * @return 命中的 runId
     */
    Set<Long> findRunIdsByContent(String tenantId, String text);

    /**
     * 列出这些运行的版本行，供列表回填标题和摘录。
     *
     * @param runIds 运行 ID
     * @return 版本行
     */
    List<IpdAgentArtifactVersion> listByRunIds(Collection<Long> runIds);
}
