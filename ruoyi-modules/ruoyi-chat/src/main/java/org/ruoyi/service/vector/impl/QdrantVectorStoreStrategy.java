package org.ruoyi.service.vector.impl;

import io.agentscope.core.embedding.EmbeddingModel;
import org.ruoyi.service.embed.EmbeddingVectors;
import io.qdrant.client.grpc.Points.*;
import io.qdrant.client.grpc.Common.*;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.FieldCondition;
import io.qdrant.client.grpc.Common.Match;
import io.grpc.Status;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points.Query;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.config.VectorStoreProperties;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.factory.EmbeddingModelFactory;
import org.ruoyi.domain.entity.knowledge.KnowledgeAttach;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import static io.qdrant.client.VectorInputFactory.vectorInput;
import static io.qdrant.client.WithPayloadSelectorFactory.enable;

import java.util.ArrayList;
import java.util.List;

/**
 * Qdrant向量库策略实现
 */
@Slf4j
@Component
public class QdrantVectorStoreStrategy extends AbstractVectorStoreStrategy {

    private static final String VECTOR_STORE_TYPE   = "qdrant";
    private static final String TEXT_SEGMENT_KEY    = "text_segment";
    private static final String METADATA_FID_KEY    = "fid";
    private static final String METADATA_KID_KEY    = "kid";
    private static final String METADATA_DOC_ID_KEY = "doc_id";

    private final KnowledgeAttachMapper knowledgeAttachMapper;

    public QdrantVectorStoreStrategy(VectorStoreProperties vectorStoreProperties,
                                     IChatModelService chatModelService,
                                     EmbeddingModelFactory embeddingModelFactory,
                                     KnowledgeAttachMapper knowledgeAttachMapper) {
        super(vectorStoreProperties, embeddingModelFactory, chatModelService);
        this.knowledgeAttachMapper = knowledgeAttachMapper;
    }

    private QdrantClient buildQdrantClient() {
        VectorStoreProperties.Qdrant cfg = vectorStoreProperties.getQdrant();
        QdrantGrpcClient.Builder grpcBuilder = QdrantGrpcClient.newBuilder(cfg.getHost(), cfg.getPort(), cfg.isUseTls());
        if (cfg.getApiKey() != null && !cfg.getApiKey().isEmpty()) {
            grpcBuilder.withApiKey(cfg.getApiKey());
        }
        return new QdrantClient(grpcBuilder.build());
    }

    private int getModelDimension(String modelName) {
        var modelConfig = chatModelService.selectModelByName(modelName);
        if (modelConfig == null || modelConfig.getModelDimension() == null) {
            log.warn("无法解析模型 {} 的向量维度，使用默认值 1024", modelName);
            return 1024;
        }
        return modelConfig.getModelDimension();
    }

    @Override
    public String getVectorStoreType() {
        return VECTOR_STORE_TYPE;
    }

    @Override
    public void createSchema(String kid, String modelName) {
        String collectionName = vectorStoreProperties.getQdrant().getCollectionname() + kid;
        int dimension = getModelDimension(modelName);
        try (QdrantClient client = buildQdrantClient()) {
            Boolean exists = client.collectionExistsAsync(collectionName).get();
            if (!exists) {
                VectorParams params = VectorParams.newBuilder()
                        .setSize(dimension)
                        .setDistance(Distance.Cosine)
                        .build();
                client.createCollectionAsync(collectionName, params).get();
                log.info("Qdrant集合创建成功: {}, dimension: {}", collectionName, dimension);
            } else {
                log.info("Qdrant集合已存在: {}", collectionName);
            }
        } catch (Exception e) {
            log.error("Qdrant集合创建失败: {}", collectionName, e);
            throw new ServiceException("Qdrant集合创建失败: " + collectionName);
        }
    }

    @Override
    public void storeEmbeddings(StoreEmbeddingBo storeEmbeddingBo) {
        createSchema(storeEmbeddingBo.getKid(), storeEmbeddingBo.getEmbeddingModelName());
        EmbeddingModel embeddingModel = getEmbeddingModel(storeEmbeddingBo.getEmbeddingModelName());
        List<String> chunkList = storeEmbeddingBo.getChunkList();
        List<String> fidList = storeEmbeddingBo.getFids();
        String kid = storeEmbeddingBo.getKid();
        String docId = storeEmbeddingBo.getDocId();
        String collectionName = vectorStoreProperties.getQdrant().getCollectionname() + kid;


        log.info("Qdrant向量存储条数记录: {}", chunkList.size());
        long startTime = System.currentTimeMillis();

        if (chunkList.size() != fidList.size()) {
            throw new ServiceException("分片数量与标识数量不一致");
        }
        List<float[]> embeddings = EmbeddingVectors.embedAll(embeddingModel, chunkList);
        if (!embeddings.isEmpty()) { storeEmbeddingBo.setEmbeddingDim(embeddings.get(0).length); }
        List<PointStruct> points = new ArrayList<>();
        for (int i = 0; i < embeddings.size(); i++) {
            float[] vector = normalize(embeddings.get(i));
            List<Float> values = new ArrayList<>();
            for (float value : vector) { values.add(value); }
            // Qdrant payload 名与随机 UUID 点标识沿用存量数据格式。
            var point = PointStruct.newBuilder().setId(PointId.newBuilder().setUuid(java.util.UUID.randomUUID().toString()))
                .setVectors(io.qdrant.client.VectorsFactory.vectors(values))
                .putPayload(TEXT_SEGMENT_KEY, JsonWithInt.Value.newBuilder().setStringValue(chunkList.get(i)).build())
                .putPayload(METADATA_FID_KEY, JsonWithInt.Value.newBuilder().setStringValue(fidList.get(i)).build())
                .putPayload(METADATA_KID_KEY, JsonWithInt.Value.newBuilder().setStringValue(kid).build())
                .putPayload(METADATA_DOC_ID_KEY, JsonWithInt.Value.newBuilder().setStringValue(docId).build());
            WeaviateVectorStoreStrategy.buildFragmentPayload(storeEmbeddingBo, chunkList.get(i), fidList.get(i), vector.length)
                .forEach((key, value) -> point.putPayload(key, value instanceof Number number
                    ? JsonWithInt.Value.newBuilder().setIntegerValue(number.longValue()).build()
                    : JsonWithInt.Value.newBuilder().setStringValue(String.valueOf(value)).build()));
            points.add(point.build());
        }
        try (QdrantClient client = buildQdrantClient()) {
            if (!points.isEmpty()) { client.upsertAsync(collectionName, points).get(); }
        } catch (Exception ex) {
            throw new ServiceException("Qdrant向量写入失败");
        }

        long endTime = System.currentTimeMillis();
        log.info("Qdrant向量存储完成消耗时间：{}秒", (endTime - startTime) / 1000);
    }

    @Override
    public List<String> getQueryVector(QueryVectorBo queryVectorBo) {
        EmbeddingModel embeddingModel = getEmbeddingModel(queryVectorBo.getEmbeddingModelName());
        float[] queryVector = EmbeddingVectors.embed(embeddingModel, queryVectorBo.getQuery());
        normalize(queryVector);

        String collectionName = vectorStoreProperties.getQdrant().getCollectionname() + queryVectorBo.getKid();

        List<Float> vectorList = new ArrayList<>();
        for (float f : queryVector) {
            vectorList.add(f);
        }

        try (QdrantClient client = buildQdrantClient()) {
            QueryPoints request = QueryPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .setQuery(Query.newBuilder()
                            .setNearest(vectorInput(vectorList))
                            .build())
                    .setLimit(queryVectorBo.getMaxResults())
                    .setWithPayload(enable(true))
                    .setFilter(accessFilter(queryVectorBo))
                    .build();

            List<ScoredPoint> results = client.queryAsync(request).get();
            List<String> resultList = new ArrayList<>();
            for (ScoredPoint point : results) {
                if (!VectorAccessMetadata.permits(permissionMetadata(point), queryVectorBo)) continue;
                JsonWithInt.Value textValue = point.getPayloadMap().get(TEXT_SEGMENT_KEY);
                if (textValue != null && textValue.hasStringValue()) {
                    resultList.add(textValue.getStringValue());
                }
            }
            return resultList;
        } catch (Exception e) {
            log.error("Qdrant查询失败: {}", collectionName, e);
            throw new ServiceException("Qdrant向量查询失败");
        }
    }

    @Override
    public List<KnowledgeRetrievalVo> search(QueryVectorBo queryVectorBo) {
        EmbeddingModel embeddingModel = getEmbeddingModel(queryVectorBo.getEmbeddingModelName());
        float[] queryVector = EmbeddingVectors.embed(embeddingModel, queryVectorBo.getQuery());
        normalize(queryVector);

        String collectionName = vectorStoreProperties.getQdrant().getCollectionname() + queryVectorBo.getKid();

        List<Float> vectorList = new ArrayList<>();
        for (float f : queryVector) {
            vectorList.add(f);
        }

        try (QdrantClient client = buildQdrantClient()) {
            QueryPoints request = QueryPoints.newBuilder()
                    .setCollectionName(collectionName)
                    .setQuery(Query.newBuilder()
                            .setNearest(vectorInput(vectorList))
                            .build())
                    .setLimit(queryVectorBo.getMaxResults())
                    .setWithPayload(enable(true))
                    .setFilter(accessFilter(queryVectorBo))
                    .build();

            List<ScoredPoint> results = client.queryAsync(request).get();
            List<org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo> resultList = new ArrayList<>();
            for (ScoredPoint point : results) {
                if (!VectorAccessMetadata.permits(permissionMetadata(point), queryVectorBo)) continue;
                String content = "";
                JsonWithInt.Value textValue = point.getPayloadMap().get(TEXT_SEGMENT_KEY);
                if (textValue != null && textValue.hasStringValue()) {
                    content = textValue.getStringValue();
                }

                String docId = null;
                JsonWithInt.Value docIdValue = point.getPayloadMap().get(METADATA_DOC_ID_KEY);
                if (docIdValue != null && docIdValue.hasStringValue()) {
                    docId = docIdValue.getStringValue();
                }

                String fid = null;
                JsonWithInt.Value fidValue = point.getPayloadMap().get(METADATA_FID_KEY);
                if (fidValue != null && fidValue.hasStringValue()) {
                    fid = fidValue.getStringValue();
                }

                String sourceName = "未知来源";
                if (docId != null) {
                    KnowledgeAttach attach = knowledgeAttachMapper.selectOne(new LambdaQueryWrapper<KnowledgeAttach>()
                            .eq(KnowledgeAttach::getDocId, docId)
                            .last("limit 1"));
                    if (attach != null) {
                        sourceName = attach.getName();
                    }
                }

                resultList.add(org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo.builder()
                        .id(fid)
                        .docId(docId)
                        .content(content)
                        .score((double) point.getScore())
                        .sourceName(sourceName)
                        .build());
            }
            return resultList;
        } catch (Exception e) {
            log.error("Qdrant检索失败: {}", collectionName, e);
            throw new ServiceException("Qdrant向量检索失败");
        }
    }

    @Override
    public void removeById(String id, String modelName) {
        String collectionName = vectorStoreProperties.getQdrant().getCollectionname() + id;
        try (QdrantClient client = buildQdrantClient()) {
            client.deleteCollectionAsync(collectionName).get();
            log.info("Qdrant成功删除集合: {}", collectionName);
        } catch (Exception e) {
            if (Status.fromThrowable(e).getCode() == Status.Code.NOT_FOUND) {
                log.debug("Qdrant集合不存在，跳过删除: {}", collectionName);
                return;
            }
            log.error("Qdrant删除集合失败: {}", collectionName, e);
            throw new ServiceException("失败删除向量数据!");
        }
    }

    @Override
    public void removeByDocId(String docId, String kid) {
        removeByMetadata(kid, METADATA_DOC_ID_KEY, docId);
    }

    @Override
    public void removeByFid(String fid, String kid) {
        removeByMetadata(kid, METADATA_FID_KEY, fid);
    }

    private void removeByMetadata(String kid, String metadataKey, String value) {
        String collectionName = vectorStoreProperties.getQdrant().getCollectionname() + kid;
        Filter filter = Filter.newBuilder().addMust(Condition.newBuilder().setField(FieldCondition.newBuilder()
            .setKey(metadataKey).setMatch(Match.newBuilder().setKeyword(value)))).build();
        try (QdrantClient client = buildQdrantClient()) {
            client.deleteAsync(DeletePoints.newBuilder().setCollectionName(collectionName)
                .setPoints(PointsSelector.newBuilder().setFilter(filter)).build()).get();
        } catch (Exception e) {
            if (Status.fromThrowable(e).getCode() == Status.Code.NOT_FOUND) { return; }
            throw new IllegalStateException("Qdrant删除向量失败", e);
        }
    }
    @Override
    public void updatePayloadSensitivity(String kid, String sensitivity, String embeddingModelName) {
        if (org.ruoyi.enums.KnowledgeSensitivity.parse(sensitivity) == null) throw new ServiceException("非法敏感级");
        try (QdrantClient client = buildQdrantClient()) {
            client.setPayloadAsync(vectorStoreProperties.getQdrant().getCollectionname() + kid,
                java.util.Map.of("sensitivity", JsonWithInt.Value.newBuilder().setStringValue(sensitivity).build()),
                Boolean.TRUE, null, java.time.Duration.ofSeconds(60)).get();
        } catch (Exception ex) { throw new ServiceException("Qdrant payload 敏感级随动失败"); }
    }

    static Filter accessFilter(QueryVectorBo bo) {
        var filter = Filter.newBuilder().addMust(match("scopeType", VectorAccessMetadata.SCOPES))
            .addMust(match("sensitivity", VectorAccessMetadata.sensitivity(bo)));
        var visibility = VectorAccessMetadata.visibility(bo);
        if (!visibility.isEmpty()) {
            var alternatives = Filter.newBuilder();
            visibility.forEach((key, values) -> alternatives.addShould(match(key, values)));
            filter.addMust(Condition.newBuilder().setFilter(alternatives));
        }
        return filter.build();
    }
    private static Condition match(String key, List<String> values) {
        return Condition.newBuilder().setField(FieldCondition.newBuilder().setKey(key)
            .setMatch(Match.newBuilder().setKeywords(RepeatedStrings.newBuilder().addAllStrings(values)))).build();
    }
    private static java.util.Map<String, String> permissionMetadata(ScoredPoint point) {
        var result = new java.util.HashMap<String, String>();
        point.getPayloadMap().forEach((key, value) -> { if (value.hasStringValue()) result.put(key, value.getStringValue()); });
        return result;
    }
}
