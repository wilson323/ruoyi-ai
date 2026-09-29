package org.ruoyi.service.vector;

import io.weaviate.client.v1.schema.model.Property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Weaviate payload 键名单源（B1 四刀之二）。
 * <p>
 * 设计权威：docs/ipd-系统说明/知识库PartB接线实施方案-20260928.md §2.3/§3.3——
 * 键名统一驼峰（沿现态 {@code text/fid/kid/docId} 风格，PoC 合并时改引本常量类，
 * 禁两处字面量各写各的）；MySQL 列名保持 snake_case，两侧经本类/实体映射对齐。
 * <p>
 * dataType 裁决（新 8 键，本切片落码时定）：
 * <ul>
 *   <li>字符串语义键 {@code scopeType/sensitivity/embeddingModel} 用 {@code string}
 *       （非分词，过滤 Equal/ContainsAny 语义精确——序比较（LessThanEqual 等）
 *       已弃用，sensitivity 过滤一律 ContainsAny + 允许值集合；旧四键维持
 *       {@code text} 不动，避免老 class 兼容面扩大）。</li>
 *   <li>归属 ID 键 {@code groupId/projectId/ownerPersonId/ownerAgentId} 用 {@code string}：
 *       雪花主键超出 Weaviate {@code int}(32位) 与 {@code number}(float64, 2^53) 精确整数域，
 *       与现态 {@code kid}（text 承载 + Equal 过滤先例，见 removeByDocId）同构，过滤用
 *       typed WhereFilter 的 {@code valueString}（string dataType 键的强制配对形态；
 *       {@code valueText} 仅适用于 text dataType 键）。</li>
 *   <li>{@code embeddingDim} 用 {@code int}（维度值域小）。</li>
 * </ul>
 * sensitivity 过滤语义：ContainsAny + {@code KnowledgeSensitivity#allowedValuesUpTo}
 * 展开的允许值集合（见 {@link org.ruoyi.enums.KnowledgeSensitivity} javadoc）——
 * 字典序 INTERNAL &lt; PUBLIC &lt; SECRET 与敏感级升序不一致，禁止任何序比较。
 * <p>
 * 老 class 不重建：Weaviate 支持对既有 class 追加 property（写入前补齐探测见
 * WeaviateVectorStoreStrategy#createSchema；有效客户端 5.3.0 提供
 * {@code schema().propertyCreator()}，方案 §2.3 的 not-run 顾虑已核实解除）。
 *
 * @author ruoyi
 * @date 2026-09-28
 */
public final class WeaviatePayloadKeys {

    // ---------- 现态 4 键（既有，键名不变） ----------
    public static final String TEXT = "text";
    public static final String FID = "fid";
    public static final String KID = "kid";
    public static final String DOC_ID = "docId";

    // ---------- B1 新增 8 键（驼峰裁决） ----------
    /** 作用域：GLOBAL/GROUP/PROJECT/PERSON/AGENT（镜像 knowledge_info.scope_type） */
    public static final String SCOPE_TYPE = "scopeType";
    /** 归属产品组（镜像 knowledge_info.group_id，string 承载防雪花精度丢失） */
    public static final String GROUP_ID = "groupId";
    /** 归属项目（镜像 knowledge_info.project_id） */
    public static final String PROJECT_ID = "projectId";
    /** 归属自然人（镜像 knowledge_info.user_id 的 owner 语义） */
    public static final String OWNER_PERSON_ID = "ownerPersonId";
    /** 归属数字员工（镜像 knowledge_info.owner_agent_id） */
    public static final String OWNER_AGENT_ID = "ownerAgentId";
    /** 敏感级：PUBLIC/INTERNAL/SECRET（镜像 knowledge_info.sensitivity） */
    public static final String SENSITIVITY = "sensitivity";
    /** 本片段 embedding 模型名快照（镜像 knowledge_fragment.embedding_model） */
    public static final String EMBEDDING_MODEL = "embeddingModel";
    /** 本片段向量维度（镜像 knowledge_fragment.embedding_dim） */
    public static final String EMBEDDING_DIM = "embeddingDim";

    private WeaviatePayloadKeys() {
    }

    /**
     * class 应具备的全部 property 定义（新库建 class 用；老 class 按此清单补齐缺失项）。
     */
    public static List<Property> requiredProperties() {
        List<Property> properties = new ArrayList<>();
        properties.add(textProperty(TEXT));
        properties.add(textProperty(FID));
        properties.add(textProperty(KID));
        properties.add(textProperty(DOC_ID));
        properties.add(stringProperty(SCOPE_TYPE));
        properties.add(stringProperty(GROUP_ID));
        properties.add(stringProperty(PROJECT_ID));
        properties.add(stringProperty(OWNER_PERSON_ID));
        properties.add(stringProperty(OWNER_AGENT_ID));
        properties.add(stringProperty(SENSITIVITY));
        properties.add(stringProperty(EMBEDDING_MODEL));
        properties.add(Property.builder().name(EMBEDDING_DIM)
            .dataType(Collections.singletonList("int")).build());
        return properties;
    }

    private static Property textProperty(String name) {
        return Property.builder().name(name)
            .dataType(Collections.singletonList("text")).build();
    }

    private static Property stringProperty(String name) {
        return Property.builder().name(name)
            .dataType(Collections.singletonList("string")).build();
    }
}
