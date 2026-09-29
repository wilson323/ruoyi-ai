package org.ruoyi.service.knowledge;

import java.util.List;

/**
 * 检索访问过滤 profile（B2 检索接线）：当前身份的敏感级上限 + 归属可见键取值。
 * <p>
 * 设计权威：docs/ipd-系统说明/知识库PartB接线实施方案-20260928.md §3.2、
 * 知识库结构与属性最佳实践-20260928.md §8.2——「角色→敏感级上限」的权威源在
 * IPD 侧（{@code IpdKnowledgeAccessGate}），ruoyi-chat 不内嵌该表，只经
 * {@link KnowledgeAccessGate#retrievalAccessProfile()} 消费结果；
 * 字段与 {@code QueryVectorBo#applyBackendAccessFilters} 的六个仅后端装配参数一一对应。
 * <p>
 * 默认取值纪律（fail-closed）：身份解析失败/未知角色/匿名一律降到
 * {@link #FAIL_CLOSED_PUBLIC}——PUBLIC 是唯一可安全降级的档位
 * （与 {@code IpdRolePermissionCatalog} 未知 personType 返空=拒绝一切同构）。
 * 检索链默认不装配页面级作用域（scopeTypes/groupId/projectId/ownerAgentIds 为 null
 * =不启用对应谓词）；§5 的页面上下文作用域收窄属页面级装配，不在本 profile 内拍脑袋造值。
 *
 * @param maxSensitivity 敏感级上限（PUBLIC/INTERNAL/SECRET）；null=不启用敏感闸门
 * @param personId       当前自然人 ID（owner_person_id 语义）；null=不启用归属谓词
 * @param scopeTypes     可见作用域集合；null/空=不启用作用域谓词
 * @param groupId        归属产品组；null=不启用
 * @param projectId      归属项目；null=不启用
 * @param ownerAgentIds  可管理的数字员工 ID 集合；null/空=不启用
 * @author ruoyi
 * @date 2026-09-28
 */
public record RetrievalAccessProfile(String maxSensitivity, Long personId, List<String> scopeTypes,
                                     Long groupId, Long projectId, List<Long> ownerAgentIds) {

    /** fail-closed 最严档：PUBLIC 上限、无任何归属放行键（anon/未知身份/解析失败的统一落点）。 */
    public static final RetrievalAccessProfile FAIL_CLOSED_PUBLIC =
        new RetrievalAccessProfile("PUBLIC", null, null, null, null, null);
}
