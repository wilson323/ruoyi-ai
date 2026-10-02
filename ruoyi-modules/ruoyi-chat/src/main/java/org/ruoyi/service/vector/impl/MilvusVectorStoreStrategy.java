package org.ruoyi.service.vector.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.vector.request.*;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.utility.request.FlushReq;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.config.VectorStoreProperties;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.factory.EmbeddingModelFactory;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import org.ruoyi.mapper.knowledge.KnowledgeInfoMapper;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.service.embed.EmbeddingVectors;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** AgentScope 负责嵌入；官方 Milvus 客户端保持原 id/text/metadata/vector 存量协议。 */
@Component
public class MilvusVectorStoreStrategy extends AbstractVectorStoreStrategy {
    private final KnowledgeAttachMapper knowledgeAttachMapper;

    public MilvusVectorStoreStrategy(VectorStoreProperties properties, IChatModelService models,
                                    EmbeddingModelFactory embeddings, KnowledgeAttachMapper attaches,
                                    KnowledgeInfoMapper knowledgeInfoMapper) {
        super(properties, embeddings, models);
        this.knowledgeAttachMapper = attaches;
    }

    private MilvusClientV2 client() {
        return new MilvusClientV2(ConnectConfig.builder().uri(vectorStoreProperties.getMilvus().getUrl()).build());
    }

    private String collection(String kid) { return vectorStoreProperties.getMilvus().getCollectionname() + kid; }

    @Override
    public void createSchema(String kid, String modelName) {
        MilvusClientV2 client = client();
        try {
            String collection = collection(kid);
            if (!client.hasCollection(HasCollectionReq.builder().collectionName(collection).build())) {
                var schema = client.createSchema();
                schema.addField(AddFieldReq.builder().fieldName("id").dataType(DataType.VarChar)
                    .maxLength(65535).isPrimaryKey(true).autoID(false).build());
                schema.addField(AddFieldReq.builder().fieldName("text").dataType(DataType.VarChar).maxLength(65535).build());
                schema.addField(AddFieldReq.builder().fieldName("metadata").dataType(DataType.JSON).build());
                schema.addField(AddFieldReq.builder().fieldName("vector").dataType(DataType.FloatVector)
                    .dimension(getEmbeddingModel(modelName).getDimensions()).build());
                client.createCollection(CreateCollectionReq.builder().collectionName(collection).collectionSchema(schema)
                    .indexParams(List.of(IndexParam.builder().fieldName("vector").indexType(IndexParam.IndexType.IVF_FLAT)
                        .metricType(IndexParam.MetricType.COSINE).extraParams(java.util.Map.of("nlist", 1024)).build())).build());
            }
            client.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
        } finally { client.close(); }
    }

    @Override
    public void storeEmbeddings(StoreEmbeddingBo bo) {
        if (bo.getChunkList().size() != bo.getFids().size()) {
            throw new ServiceException("分片数量与标识数量不一致");
        }
        createSchema(bo.getKid(), bo.getEmbeddingModelName());
        var vectors = EmbeddingVectors.embedAll(getEmbeddingModel(bo.getEmbeddingModelName()), bo.getChunkList());
        List<JsonObject> rows = new ArrayList<>();
        for (int i = 0; i < vectors.size(); i++) {
            JsonObject row = new JsonObject();
            row.addProperty("id", java.util.UUID.randomUUID().toString());
            row.addProperty("text", bo.getChunkList().get(i));
            JsonObject metadata = new JsonObject();
            metadata.addProperty("fid", bo.getFids().get(i));
            metadata.addProperty("kid", bo.getKid());
            metadata.addProperty("docId", bo.getDocId());
            WeaviateVectorStoreStrategy.buildFragmentPayload(bo, bo.getChunkList().get(i), bo.getFids().get(i), vectors.get(i).length)
                .forEach((key, value) -> {
                    if (value instanceof Number number) metadata.addProperty(key, number);
                    else metadata.addProperty(key, String.valueOf(value));
                });
            row.add("metadata", metadata);
            JsonArray vector = new JsonArray();
            for (float value : normalize(vectors.get(i))) { vector.add(value); }
            row.add("vector", vector);
            rows.add(row);
        }
        MilvusClientV2 client = client();
        try {
            if (!rows.isEmpty()) {
                client.insert(InsertReq.builder().collectionName(collection(bo.getKid())).data(rows).build());
                client.flush(FlushReq.builder().collectionNames(List.of(collection(bo.getKid()))).build());
                bo.setEmbeddingDim(vectors.get(0).length);
            }
        } finally { client.close(); }
    }

    @Override
    public List<String> getQueryVector(QueryVectorBo bo) {
        return search(bo).stream().map(KnowledgeRetrievalVo::getContent).toList();
    }

    @Override
    public List<KnowledgeRetrievalVo> search(QueryVectorBo bo) {
        float[] vector = normalize(EmbeddingVectors.embed(getEmbeddingModel(bo.getEmbeddingModelName()), bo.getQuery()));
        MilvusClientV2 client = client();
        try {
            var response = client.search(SearchReq.builder().collectionName(collection(bo.getKid())).annsField("vector")
                .data(List.of(new FloatVec(vector))).topK(bo.getMaxResults()).outputFields(List.of("text", "metadata"))
                .metricType(IndexParam.MetricType.COSINE).filter(VectorAccessMetadata.milvusFilter(bo)).build());
            List<KnowledgeRetrievalVo> results = new ArrayList<>();
            for (var batch : response.getSearchResults()) {
                for (var hit : batch) {
                    Object raw = hit.getEntity().get("metadata");
                    JsonObject metadata = raw instanceof JsonObject json ? json
                        : JsonParser.parseString(raw instanceof String text ? text : new com.google.gson.Gson().toJson(raw)).getAsJsonObject();
                    var permission = new java.util.HashMap<String, String>();
                    metadata.entrySet().forEach(e -> { if (e.getValue().isJsonPrimitive()) permission.put(e.getKey(), e.getValue().getAsString()); });
                    if (!VectorAccessMetadata.permits(permission, bo)) continue;
                    String docId = metadata.has("docId") ? metadata.get("docId").getAsString() : null;
                    String fid = metadata.has("fid") ? metadata.get("fid").getAsString() : null;
                    String source = "未知来源";
                    if (docId != null) {
                        KnowledgeAttach attach = knowledgeAttachMapper.selectOne(new LambdaQueryWrapper<KnowledgeAttach>()
                            .eq(KnowledgeAttach::getDocId, docId).last("limit 1"));
                        if (attach != null) { source = attach.getName(); }
                    }
                    results.add(KnowledgeRetrievalVo.builder().id(fid).docId(docId).sourceName(source)
                        .content(String.valueOf(hit.getEntity().get("text"))).score(hit.getScore().doubleValue()).build());
                }
            }
            return results;
        } finally { client.close(); }
    }

    @Override
    public void removeById(String id, String modelName) {
        MilvusClientV2 client = client();
        try { client.dropCollection(DropCollectionReq.builder().collectionName(collection(id)).build()); }
        finally { client.close(); }
    }

    private void removeByMetadata(String kid, String key, String value) {
        MilvusClientV2 client = client();
        try {
            if (!client.hasCollection(HasCollectionReq.builder().collectionName(collection(kid)).build())) { return; }
            String encoded = new com.google.gson.Gson().toJson(value);
            client.delete(DeleteReq.builder().collectionName(collection(kid))
                .filter("metadata[\"" + key + "\"] == " + encoded).build());
        } finally { client.close(); }
    }

    @Override
    public void removeByDocId(String docId, String kid) { removeByMetadata(kid, "docId", docId); }
    @Override
    public void removeByFid(String fid, String kid) { removeByMetadata(kid, "fid", fid); }
    @Override
    public String getVectorStoreType() { return "milvus"; }
    @Override
    public void updatePayloadSensitivity(String kid, String sensitivity, String embeddingModelName) {
        var level = org.ruoyi.enums.KnowledgeSensitivity.parse(sensitivity);
        if (level == null) throw new ServiceException("非法敏感级");
        MilvusClientV2 client = client();
        io.milvus.orm.iterator.QueryIterator iterator = null;
        try {
            if (!client.hasCollection(HasCollectionReq.builder().collectionName(collection(kid)).build())) return;
            iterator = client.queryIterator(QueryIteratorReq.builder().collectionName(collection(kid))
                .outputFields(List.of("*", "vector")).expr("id != \"\"").batchSize(256)
                .consistencyLevel(io.milvus.v2.common.ConsistencyLevel.STRONG).build());
            while (true) {
                var batch = iterator.next();
                if (batch == null) throw new ServiceException("Milvus 敏感级随动返回空响应");
                if (batch.isEmpty()) break;
                List<JsonObject> rows = batch.stream().map(row -> patchSensitivity(row.getFieldValues(), level.name())).toList();
                client.upsert(UpsertReq.builder().collectionName(collection(kid)).data(rows).build());
            }
            client.flush(FlushReq.builder().collectionNames(List.of(collection(kid))).build());
        } catch (Exception ex) {
            throw new ServiceException("Milvus payload 敏感级随动失败");
        } finally {
            try { if (iterator != null) iterator.close(); } finally { client.close(); }
        }
    }

    /** 完整行 upsert：仅替换 JSON metadata 的敏感标，保留原向量、主键和额外字段。 */
    static JsonObject patchSensitivity(java.util.Map<String, Object> fields, String sensitivity) {
        var gson = new com.google.gson.Gson();
        JsonObject row = gson.toJsonTree(fields).getAsJsonObject();
        if (!row.has("id") || !row.has("vector") || !row.has("text"))
            throw new ServiceException("Milvus 敏感级随动缺失原始字段");
        Object raw = fields.get("metadata");
        JsonObject metadata;
        if (raw == null) metadata = new JsonObject();
        else if (raw instanceof JsonObject json) metadata = json.deepCopy();
        else metadata = JsonParser.parseString(raw instanceof String text ? text : gson.toJson(raw)).getAsJsonObject();
        metadata.addProperty("sensitivity", sensitivity);
        row.add("metadata", metadata);
        return row;
    }

}
