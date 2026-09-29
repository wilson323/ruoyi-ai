package org.ruoyi.domain.bo.vector;

import lombok.Data;

import java.util.List;

/**
 * 保存向量所需参数
 *
 * @author ageer
 */
@Data
public class StoreEmbeddingBo {

    /**
     * 切分文本块列表
     */
    private List<String> chunkList;

    /**
     * 知识库kid
     */
    private String kid;

    /**
     * 文档id
     */
    private String docId;

    /**
     * 知识块id列表
     */
    private List<String> fids;

    /**
     * 向量库名称
     */
    private String vectorStoreName;

    /**
     * 向量化模型id
     */
    private Long embeddingModelId;

    /**
     * 向量化模型名称
     */
    private String embeddingModelName;

    /**
     * 请求地址
     */
    private String baseUrl;

    // ========== B1 四刀之二：payload 归属冗余值（入库时随片段写入向量侧） ==========
    // 取值来源：KnowledgeAttachServiceImpl#parse 从 knowledge_info 镜像；
    // 消费端：WeaviateVectorStoreStrategy#storeEmbeddings 按 WeaviatePayloadKeys 驼峰键写入。
    // 设计纪律（最佳实践 §4 铁律一）：MySQL 与向量侧归属值同一事务语义写入，
    // 禁止「库里 INTERNAL、向量侧无标」的隔离穿透。null 值键不写入 payload（空态降级）。

    /**
     * 作用域（GLOBAL/GROUP/PROJECT/PERSON/AGENT），镜像 knowledge_info.scope_type
     */
    private String scopeType;

    /**
     * 归属产品组，镜像 knowledge_info.group_id
     */
    private Long groupId;

    /**
     * 归属项目，镜像 knowledge_info.project_id
     */
    private Long projectId;

    /**
     * 归属自然人，镜像 knowledge_info.user_id（owner_person_id 语义）
     */
    private Long ownerPersonId;

    /**
     * 归属数字员工，镜像 knowledge_info.owner_agent_id
     */
    private Long ownerAgentId;

    /**
     * 敏感级（PUBLIC/INTERNAL/SECRET），镜像 knowledge_info.sensitivity
     */
    private String sensitivity;

    /**
     * 实际向量维度（三元组 embedding_dim 的权威值）。
     * <p>
     * 由策略侧在嵌入完成后以 {@code Embedding#dimension()} 实测值回写
     * （「实际用了什么」优先于「配置写了什么」，最佳实践 §3 组三），
     * KnowledgeAttachServiceImpl#parse 在 storeEmbeddings 返回后读取，
     * 为本批 knowledge_fragment 行补齐 embedding_dim 列。
     */
    private Integer embeddingDim;
}
