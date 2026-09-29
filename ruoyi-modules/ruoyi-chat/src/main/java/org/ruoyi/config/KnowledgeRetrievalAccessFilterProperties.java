package org.ruoyi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库检索访问过滤开关（B2 检索接线，实施方案 §5 回滚预案 B2 行）。
 * <p>
 * 默认 {@code enabled=false}：检索装配点不注入桥 profile，行为与 B1 前完全一致
 * （where 子句/SQL 谓词均不启用）——即 B2 的回滚态=默认态；@ConfigurationProperties
 * 启动期绑定，改配置需重启生效（无 @RefreshScope，回滚=改回 false 后重启）。
 * 桥（KnowledgeAccessGate 判据）独立于本开关，无论开关与否均 fail-closed 运行，
 * B0 语义不因回滚而退化。
 * <p>
 * 启用后：KnowledgeRetrievalServiceImpl 检索入口按当前身份（HTTP 线程会话 /
 * aiflow WfState.userId 显式透传 / 匿名）经 KnowledgeAccessGate#retrievalAccessProfile
 * 装配 maxSensitivity 等六个仅后端参数（QueryVectorBo#applyBackendAccessFilters 唯一写入口）。
 * 注意启用即收紧：sys_user/匿名身份 fail-closed 到 PUBLIC 档（§3.2），
 * 非 PUBLIC 库（含 owner 自建 INTERNAL 库）对这些身份的检索召回将被闸门收窄——
 * 属设计内行为，观察期后再全量放开。
 *
 * @author ruoyi
 * @date 2026-09-28
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.retrieval.access-filter")
public class KnowledgeRetrievalAccessFilterProperties {

    /**
     * 是否启用检索装配点的桥 profile 注入（默认 false=回旧行为）。
     */
    private boolean enabled = false;
}
