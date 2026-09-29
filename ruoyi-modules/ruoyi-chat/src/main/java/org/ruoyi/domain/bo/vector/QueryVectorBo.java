package org.ruoyi.domain.bo.vector;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.enums.KnowledgeSensitivity;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 查询向量所需参数
 *
 * @author ageer
 */
@Data
public class QueryVectorBo {

    /**
     * 查询内容
     */
    private String query;

    /**
     * 知识库kid
     */
    private String kid;

    /**
     * 查询向量返回条数
     */
    private Integer maxResults;

    /**
     * 向量库模型名称
     */
    private String vectorModelName;

    /**
     * 向量化模型ID
     */
    private Long embeddingModelId;

    /**
     * 向量化模型ID
     */
    private String embeddingModelName;

    /**
     * 请求地址
     */
    private String baseUrl;


    // ========== 重排序相关参数 ==========

    /**
     * 是否启用重排序
     * 默认为 false
     */
    private Boolean enableRerank = false;

    /**
     * 重排序模型名称
     */
    private String rerankModelName;

    /**
     * 重排序后返回的文档数量（topN）
     * 如果不指定，默认与 maxResults 相同
     */
    private Integer rerankTopN;

    /**
     * 重排序相关性分数阈值
     * 低于此阈值的文档将被过滤
     */
    private Double rerankScoreThreshold;

    // ========== 混合检索与阈值相关参数 ==========

    /**
     * 相似度阈值 (0.0-1.0)
     * 应用于向量搜索阶段
     */
    private Double similarityThreshold;

    /**
     * 是否启用混合检索
     */
    private Boolean enableHybrid = false;

    /**
     * 混合检索权重 (0.0-1.0)
     */
    private Double hybridAlpha;

    // ========== 仅后端装配的访问过滤参数（B1 四刀之一/之三，C4 防透传） ==========
    //
    // 设计权威：知识库PartB接线实施方案-20260928.md §2.2 + 最佳实践 §8.2：
    // 这 6 个参数只允许后端在检索入口（S1 接入点/IPD 桥）装配，
    // 不开客户端注入口——客户端可控即回到 S1 病根（越权放大可见面）。
    //
    // 防透传实现（C4，实测修正）：
    // 1) 六字段一律 @Setter(AccessLevel.NONE) + @JsonProperty(access = READ_ONLY)：
    //    仅无 setter 不够——Jackson 对「有 getter + 有字段」的属性仍走字段反射写入
    //    （QueryVectorBoAccessFilterTest#frontendJsonCannotInjectAccessFilters 实测复现），
    //    READ_ONLY 在反序列化侧强制忽略、序列化侧保留（日志/trace 可见过滤参数便于排障）；
    //    Spring @ModelAttribute/表单绑定则由 setter 缺失天然免疫；
    // 2) 唯一写入口 {@link #applyBackendAccessFilters(...)}（含 Bo 拷贝便捷重载），
    //    装配时做枚举/字符集校验，兼防 GraphQL where 子句与 SQL 注入；
    // 3) 现态检索装配点（CustomVectorRetriever/ChatServiceFacade.buildQueryVectorBo/
    //    KnowledgeFragmentServiceImpl.retrieval）在 B1 均不装配本组参数——
    //    maxSensitivity 的权威源是 IPD 侧桥（§8.2，chat 不得内嵌角色→上限表），
    //    部分装配（如只给 personId 不给完整可见集）会造成共享/员工库误杀，
    //    故 B1 通道就位、取值留空（null=不加谓词=B1 前行为），B2 桥接入后生效。

    /**
     * 敏感级上限（PUBLIC/INTERNAL/SECRET）；null=不启用敏感闸门。
     * 消费端以「允许值集合」语义比较（Weaviate ContainsAny + MySQL IN，
     * 集合由 KnowledgeSensitivity#allowedValuesUpTo 单源展开）——禁止字符串序比较：
     * 字典序 INTERNAL&lt;PUBLIC&lt;SECRET 与敏感级升序 PUBLIC&lt;INTERNAL&lt;SECRET 不一致
     * （序比较曾致 cap=PUBLIC 越权放行 INTERNAL 库，Validator P0）。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Setter(AccessLevel.NONE)
    private String maxSensitivity;

    /**
     * 当前自然人 ID（owner_person_id 语义）；null=不启用归属谓词。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Setter(AccessLevel.NONE)
    private Long personId;

    /**
     * 可见作用域集合（GLOBAL/GROUP/PROJECT/PERSON/AGENT 子集）；null/空=不启用作用域谓词。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Setter(AccessLevel.NONE)
    private List<String> scopeTypes;

    /**
     * 归属产品组过滤；null=不启用。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Setter(AccessLevel.NONE)
    private Long groupId;

    /**
     * 归属项目过滤；null=不启用。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Setter(AccessLevel.NONE)
    private Long projectId;

    /**
     * 可管理的数字员工 ID 集合；null/空=不启用。
     */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    @Setter(AccessLevel.NONE)
    private List<Long> ownerAgentIds;

    /**
     * C4 唯一写入口：后端装配访问过滤参数（校验后落值）。
     *
     * @throws ServiceException maxSensitivity 非法枚举或 scopeTypes 含非法字符（仅 [A-Z_]，防注入）
     */
    public void applyBackendAccessFilters(String maxSensitivity, Long personId, List<String> scopeTypes,
                                          Long groupId, Long projectId, List<Long> ownerAgentIds) {
        this.maxSensitivity = normalizeSensitivity(maxSensitivity);
        this.personId = personId;
        this.scopeTypes = normalizeScopeTypes(scopeTypes);
        this.groupId = groupId;
        this.projectId = projectId;
        this.ownerAgentIds = (ownerAgentIds == null || ownerAgentIds.isEmpty())
            ? null : List.copyOf(ownerAgentIds);
    }

    /**
     * 拷贝便捷重载：从既有 Bo 同步访问过滤参数（KnowledgeRetrievalServiceImpl#copyOf 等内部透传用）。
     */
    public void applyBackendAccessFilters(QueryVectorBo source) {
        applyBackendAccessFilters(source.maxSensitivity, source.personId, source.scopeTypes,
            source.groupId, source.projectId, source.ownerAgentIds);
    }

    private static String normalizeSensitivity(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        KnowledgeSensitivity parsed = KnowledgeSensitivity.parse(raw);
        if (parsed == null) {
            throw new ServiceException("非法敏感级上限（仅 PUBLIC/INTERNAL/SECRET）: " + raw);
        }
        return parsed.name();
    }

    private static List<String> normalizeScopeTypes(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        Pattern allowed = Pattern.compile("^[A-Z][A-Z_]{0,15}$");
        List<String> normalized = raw.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.trim().toUpperCase())
            .distinct()
            .toList();
        if (normalized.isEmpty()) {
            return null;
        }
        for (String scope : normalized) {
            if (!allowed.matcher(scope).matches()) {
                throw new ServiceException("非法作用域取值（仅大写字母/下划线）: " + scope);
            }
        }
        return normalized;
    }
}
