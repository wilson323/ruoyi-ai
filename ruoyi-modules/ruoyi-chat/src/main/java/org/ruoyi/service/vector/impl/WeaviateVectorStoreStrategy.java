package org.ruoyi.service.vector.impl;

import cn.hutool.json.JSONObject;
import io.agentscope.core.embedding.EmbeddingModel;
import org.ruoyi.service.embed.EmbeddingVectors;

import io.weaviate.client.WeaviateClient;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.config.VectorStoreProperties;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;
import org.ruoyi.enums.KnowledgeSensitivity;
import org.ruoyi.factory.EmbeddingModelFactory;
import org.ruoyi.service.vector.WeaviatePayloadKeys;
import org.springframework.stereotype.Component;
import io.weaviate.client.Config;
import io.weaviate.client.base.Result;
import io.weaviate.client.v1.batch.api.ObjectsBatchDeleter;
import io.weaviate.client.v1.batch.api.ObjectsBatcher;
import io.weaviate.client.v1.data.model.WeaviateObject;
import io.weaviate.client.v1.batch.model.BatchDeleteResponse;
import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.model.GraphQLResponse;
import io.weaviate.client.v1.graphql.query.Get;
import io.weaviate.client.v1.graphql.query.argument.NearVectorArgument;
import io.weaviate.client.v1.graphql.query.argument.SortArgument;
import io.weaviate.client.v1.graphql.query.argument.SortOrder;
import io.weaviate.client.v1.graphql.query.fields.Field;
import io.weaviate.client.v1.schema.model.Property;
import io.weaviate.client.v1.schema.model.Schema;
import io.weaviate.client.v1.schema.model.WeaviateClass;
import org.ruoyi.domain.vo.knowledge.KnowledgeAttachSource;
import org.ruoyi.mapper.knowledge.KnowledgeAttachMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Weaviate向量库策略实现
 * <p>
 * B1 四刀之二/之一落点：
 * <ul>
 *   <li>入库 payload：在现态 {@code text/fid/kid/docId} 四键之上补 8 个驼峰归属键
 *       （{@link WeaviatePayloadKeys} 单源；存量片段 0 行，无 Reindex 负担）。</li>
 *   <li>检索消费：{@link #search} 按 QueryVectorBo 仅后端装配参数构造 typed
 *       {@code WhereFilter}（graphQL().get() typed 链，客户端序列化/转义）——
 *       可见集 OR 组 ∩ 敏感闸门（ContainsAny + 允许值集合，非序比较）；
 *       参数全空=不加 where=B1 前行为。</li>
 * </ul>
 *
 * @author Yzm
 */
@Slf4j
@Component
public class WeaviateVectorStoreStrategy extends AbstractVectorStoreStrategy {

    private volatile WeaviateClient client;
    private final KnowledgeAttachMapper knowledgeAttachMapper;
    /**
     * 已确认存在的 class 缓存，避免每次检索都全量拉取 schema
     */
    private final Set<String> knownClasses = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** payload 敏感级随动的游标分页批量（低频管理动作，量级对齐 schema 补齐探测）。 */
    private static final int PAYLOAD_SYNC_BATCH_SIZE = 100;

    public WeaviateVectorStoreStrategy(VectorStoreProperties vectorStoreProperties,
                                       IChatModelService chatModelService,
                                       EmbeddingModelFactory embeddingModelFactory,
                                       KnowledgeAttachMapper knowledgeAttachMapper) {
        super(vectorStoreProperties, embeddingModelFactory,chatModelService);
        this.knowledgeAttachMapper = knowledgeAttachMapper;
    }

    /**
     * 懒加载单例客户端，避免 remove 等方法在未调用 createSchema 时 NPE
     */
    private WeaviateClient getClient() {
        if (client == null) {
            synchronized (this) {
                if (client == null) {
                    String protocol = vectorStoreProperties.getWeaviate().getProtocol();
                    String host = vectorStoreProperties.getWeaviate().getHost();
                    client = new WeaviateClient(new Config(protocol, host));
                }
            }
        }
        return client;
    }

    @Override
    public String getVectorStoreType() {
        return "weaviate";
    }

    @Override
    public void createSchema(String kid, String embeddingModelName) {
        String className = vectorStoreProperties.getWeaviate().getClassname() + kid;
        if (knownClasses.contains(className)) {
            return;
        }
        // 检查类是否存在，如果不存在就创建 schema
        Result<Schema> schemaResult = getClient().schema().getter().run();
        Schema schema = schemaResult.getResult();
        WeaviateClass existing = null;
        if (schema.getClasses() != null) {
            for (WeaviateClass weaviateClass : schema.getClasses()) {
                if (weaviateClass.getClassName().equals(className)) {
                    existing = weaviateClass;
                    break;
                }
            }
        }
        if (existing == null) {
            // 类不存在，创建 schema（B1：property 清单单源 WeaviatePayloadKeys，含 8 个新驼峰键）
            WeaviateClass build = WeaviateClass.builder()
                    .className(className)
                    .vectorizer("none")
                    .properties(WeaviatePayloadKeys.requiredProperties())
                    .build();
            Result<Boolean> createResult = getClient().schema().classCreator().withClass(build).run();
            if (createResult.hasErrors()) {
                log.error("Schema 创建失败: {}", createResult.getError());
                throw new ServiceException("Weaviate Schema 创建失败: " + createResult.getError());
            } else {
                log.info("Schema 创建成功: {}", className);
            }
        } else {
            // 老 class 不重建：写入前按清单补齐缺失 property（Weaviate 支持追加；
            // 客户端 5.3.0 的 schema().propertyCreator()，方案 §2.3 not-run 顾虑已核实解除）。
            // 失败语义=抛错中断写入（attach 置 FAILED），不静默降级——否则出现
            // 「MySQL 有标、向量侧无键」的隔离穿透（最佳实践 §4 铁律一）。
            ensurePayloadPropertiesPresent(className, existing);
        }
        knownClasses.add(className);
    }

    /**
     * 对已存在的 class 补齐缺失的 payload property（幂等：仅添加清单中缺失的项）。
     */
    private void ensurePayloadPropertiesPresent(String className, WeaviateClass existing) {
        Set<String> presentNames = existing.getProperties() == null
            ? Set.of()
            : existing.getProperties().stream().map(Property::getName).collect(Collectors.toSet());
        for (Property required : WeaviatePayloadKeys.requiredProperties()) {
            if (presentNames.contains(required.getName())) {
                continue;
            }
            Result<Boolean> addResult = getClient().schema().propertyCreator()
                .withClassName(className)
                .withProperty(required)
                .run();
            if (addResult == null || addResult.hasErrors()) {
                log.error("property 补齐失败: class={}, property={}, error={}",
                    className, required.getName(), addResult == null ? "null result" : addResult.getError());
                throw new ServiceException("Weaviate property 补齐失败: " + required.getName());
            }
            log.info("property 补齐成功: class={}, property={}", className, required.getName());
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
        log.info("向量存储条数记录: {}", chunkList.size());
        long startTime = System.currentTimeMillis();
        List<float[]> embeddings = EmbeddingVectors.embedAll(embeddingModel, chunkList);
        if (embeddings.isEmpty()) { return; }
        if (embeddings.size() != chunkList.size()) {
            throw new ServiceException("Embedding 返回数量与分片数量不一致");
        }
        // B1 三元组权威值：以嵌入实测维度回写 Bo（「实际用了什么」优先于配置值），
        // 供 KnowledgeAttachServiceImpl#parse 为本批片段补 knowledge_fragment.embedding_dim。
        int actualDim = embeddings.get(0).length;
        storeEmbeddingBo.setEmbeddingDim(actualDim);
        ObjectsBatcher batcher = getClient().batch().objectsBatcher();
        for (int i = 0; i < chunkList.size(); i++) {
            String text = chunkList.get(i);
            String fid = fidList.get(i);
            float[] embedding = embeddings.get(i);
            Map<String, Object> properties = buildFragmentPayload(storeEmbeddingBo, text, fid, actualDim);
            float[] vectorArray = embedding;
            normalize(vectorArray);
            Float[] vector = toObjectArray(vectorArray);

            batcher.withObject(WeaviateObject.builder()
                    .className(vectorStoreProperties.getWeaviate().getClassname() + kid)
                    .properties(properties).vector(vector).build());
        }
        Result<?> batchResult = batcher.run();
        if (batchResult.hasErrors()) {
            throw new ServiceException("Weaviate 批量写入失败: " + batchResult.getError());
        }
        long endTime = System.currentTimeMillis();
        log.info("向量存储完成消耗时间：" + (endTime - startTime) / 1000 + "秒");
    }

    /**
     * B1 四刀之二：组装单个片段的 payload（现态 4 键 + 8 个驼峰新键）。
     * <p>
     * 归属/敏感级取自 {@link StoreEmbeddingBo}（parse 时从 knowledge_info 镜像），
     * null 值键不写入（空态降级：无归属元数据的老写入路径不携带新键）；
     * {@code embeddingDim} 恒写（嵌入实测值必得）。
     * 键名单源 {@link WeaviatePayloadKeys}，禁字面量。
     */
    static Map<String, Object> buildFragmentPayload(StoreEmbeddingBo bo, String text, String fid, int dimension) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(WeaviatePayloadKeys.TEXT, text);
        properties.put(WeaviatePayloadKeys.FID, fid);
        properties.put(WeaviatePayloadKeys.KID, bo.getKid());
        properties.put(WeaviatePayloadKeys.DOC_ID, bo.getDocId());
        putIfNotNull(properties, WeaviatePayloadKeys.SCOPE_TYPE, bo.getScopeType());
        putIdIfNotNull(properties, WeaviatePayloadKeys.GROUP_ID, bo.getGroupId());
        putIdIfNotNull(properties, WeaviatePayloadKeys.PROJECT_ID, bo.getProjectId());
        putIdIfNotNull(properties, WeaviatePayloadKeys.OWNER_PERSON_ID, bo.getOwnerPersonId());
        putIdIfNotNull(properties, WeaviatePayloadKeys.OWNER_AGENT_ID, bo.getOwnerAgentId());
        putIfNotNull(properties, WeaviatePayloadKeys.SENSITIVITY, bo.getSensitivity());
        putIfNotNull(properties, WeaviatePayloadKeys.EMBEDDING_MODEL, bo.getEmbeddingModelName());
        properties.put(WeaviatePayloadKeys.EMBEDDING_DIM, dimension);
        return properties;
    }

    private static void putIfNotNull(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private static void putIdIfNotNull(Map<String, Object> target, String key, Long value) {
        if (value != null) {
            // 归属 ID 以 string 承载（雪花精度，见 WeaviatePayloadKeys dataType 裁决）
            target.put(key, String.valueOf(value));
        }
    }

    @Override
    public List<String> getQueryVector(QueryVectorBo queryVectorBo) {
        createSchema(queryVectorBo.getKid(), queryVectorBo.getEmbeddingModelName());
        EmbeddingModel embeddingModel = getEmbeddingModel(queryVectorBo.getEmbeddingModelName());
        float[] vector = EmbeddingVectors.embed(embeddingModel, queryVectorBo.getQuery());
        // 查询向量单位化处理
        normalize(vector);

        List<String> vectorStrings = new ArrayList<>();
        for (float v : vector) {
            vectorStrings.add(String.valueOf(v));
        }
        String vectorStr = String.join(",", vectorStrings);
        String className = vectorStoreProperties.getWeaviate().getClassname();

        // 构建 GraphQL 查询（本方法无 where 过滤消费，维持 raw 形态；
        // 访问过滤的 typed 链见 #search）
        String graphQLQuery = String.format(
                "{\n" +
                        "  Get {\n" +
                        "    %s(nearVector: {vector: [%s]} limit: %d) {\n" +
                        "      text\n" +
                        "      fid\n" +
                        "      kid\n" +
                        "      docId\n" +
                        "      _additional {\n" +
                        "        distance\n" +
                        "        id\n" +
                        "      }\n" +
                        "    }\n" +
                        "  }\n" +
                        "}",
                className + queryVectorBo.getKid(),
                vectorStr,
                queryVectorBo.getMaxResults()
        );

        Result<GraphQLResponse> result = getClient().graphQL().raw().withQuery(graphQLQuery).run();
        List<String> resultList = new ArrayList<>();
        if (result != null && !result.hasErrors()) {
            Object data = result.getResult().getData();
            JSONObject entries = new JSONObject(data);
            Map<String, cn.hutool.json.JSONArray> entriesMap = entries.get("Get", Map.class);
            cn.hutool.json.JSONArray objects = entriesMap.get(className + queryVectorBo.getKid());
            if (objects.isEmpty()) {
                return resultList;
            }
            for (Object object : objects) {
                Map<String, String> map = (Map<String, String>) object;
                String content = map.get("text");
                resultList.add(content);
            }
            return resultList;
        } else {
            log.error("GraphQL 查询失败: {}", result.getError());
            return resultList;
        }
    }

    /**
     * 按文档 ID 取附件名称。创建人用 {@link KnowledgeAttachSource} 按字符串读，
     * 不是数字时仍返回出处，不把整段向量检索打成失败。
     *
     * @param mapper 附件查询
     * @param docId 文档 ID，可空
     * @return 附件名称；没有名称时返回「未知来源」
     */
    static String sourceName(KnowledgeAttachMapper mapper, String knowledgeId, String docId) {
        if (knowledgeId == null || knowledgeId.isBlank() || docId == null || docId.isBlank()) {
            return "未知来源";
        }
        KnowledgeAttachSource row = mapper.selectSourceByKnowledgeAndDocId(knowledgeId, docId);
        if (row == null || row.getName() == null || row.getName().isBlank()) {
            return "未知来源";
        }
        return row.getName();
    }

    @Override
    public List<KnowledgeRetrievalVo> search(QueryVectorBo queryVectorBo) {
        // 检索只读。Schema 创建/补齐只由 storeEmbeddings 和显式管理写入口负责。
        EmbeddingModel embeddingModel = getEmbeddingModel(queryVectorBo.getEmbeddingModelName());
        float[] vector = EmbeddingVectors.embed(embeddingModel, queryVectorBo.getQuery());
        // 查询向量单位化处理
        normalize(vector);
        String className = vectorStoreProperties.getWeaviate().getClassname();
        // B1 四刀之一：payload where 过滤消费（仅后端装配参数非空时加 where，见 buildAccessWhereFilter）
        WhereFilter accessFilter = buildAccessWhereFilter(queryVectorBo);

        // typed GraphQL 链（客户端 5.3.0 graphQL().get()）：where 子句由 WhereFilter
        // 结构化构建、值引号转义由客户端 Serializer 统一处理——整体消灭手写 GraphQL
        // 字符串拼接与自制 escapeGraphQLString；string dataType 键的过滤值一律
        // valueString（typed builder 强制键/值形态配对，杜绝 valueText/valueString 配错）
        Get getQuery = getClient().graphQL().get()
                .withClassName(className + queryVectorBo.getKid())
                .withNearVector(NearVectorArgument.builder().vector(toObjectArray(vector)).build())
                .withLimit(queryVectorBo.getMaxResults())
                .withFields(
                        Field.builder().name(WeaviatePayloadKeys.TEXT).build(),
                        Field.builder().name(WeaviatePayloadKeys.FID).build(),
                        Field.builder().name(WeaviatePayloadKeys.DOC_ID).build(),
                        Field.builder().name("_additional")
                                .fields(Field.builder().name("distance").build()).build());
        if (accessFilter != null) {
            getQuery = getQuery.withWhere(accessFilter);
        }

        Result<GraphQLResponse> result;
        try {
            result = getQuery.run();
        } catch (RuntimeException failure) {
            // SDK 错误可能带地址或鉴权信息，不把底层异常原文传入 SOURCE。
            throw new ServiceException("知识库向量查询不可用");
        }
        requireSearchResponse(result);
        List<org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo> resultList = new ArrayList<>();

        {
            Object data = result.getResult().getData();
            JSONObject entries = new JSONObject(data);
            Map<String, cn.hutool.json.JSONArray> entriesMap = entries.get("Get", Map.class);
            cn.hutool.json.JSONArray objects = entriesMap.get(className + queryVectorBo.getKid());
            if (objects == null) {
                return resultList;
            }

            for (Object obj : objects) {
                Map<String, Object> map = (Map<String, Object>) obj;
                String content = (String) map.get("text");
                String docId = (String) map.get("docId");
                String fid = (String) map.get("fid");

                Map<String, Object> additional = (Map<String, Object>) map.get("_additional");
                Double distance = Double.valueOf(String.valueOf(additional.get("distance")));
                // 转换距离为得分 (Weaviate 0 是最相近，1 是最远；余弦距离下 1-dist 即为相似度)
                double score = 1.0 - distance;

                String sourceName = sourceName(knowledgeAttachMapper, queryVectorBo.getKid(), docId);

                resultList.add(org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo.builder()
                        .id(fid)
                        .docId(docId)
                        .content(content)
                        .score(score)
                        .sourceName(sourceName)
                        .build());
            }
        }
        return resultList;
    }

    /** 查询失败和损坏响应不可伪装成零命中；错误信息不包含远端响应正文。 */
    static void requireSearchResponse(Result<GraphQLResponse> result) {
        if (result == null || result.hasErrors() || result.getResult() == null
            || result.getResult().getData() == null
            || (result.getResult().getErrors() != null && result.getResult().getErrors().length > 0)) {
            throw new ServiceException("知识库向量查询不可用");
        }
    }

    /**
     * B1 四刀之一：按 QueryVectorBo 仅后端装配参数构造 typed {@link WhereFilter}。
     * <p>
     * 语义（最佳实践 §4 铁律二）：「作用域并集 ∪ 归属可见性」为 OR 组，
     * 叠加敏感闸门 AND：「sensitivity ∈ allowedValuesUpTo(maxSensitivity)」
     * 以 ContainsAny + 允许值集合表达（集合语义，禁序比较——字典序
     * INTERNAL&lt;PUBLIC&lt;SECRET 与敏感级升序 PUBLIC&lt;INTERNAL&lt;SECRET 不一致，
     * 序比较曾致 cap=PUBLIC 放行 INTERNAL 库的越权放大，Validator P0）。
     * 全部参数为空 → 返回 {@code null}（不加 where，与 B1 前行为一致）——B1 检索
     * 装配点均不装配过滤参数（上限权威源在 IPD 桥，B2 接入），现网运行态零行为变化。
     * <p>
     * 值域已由 {@code QueryVectorBo#applyBackendAccessFilters} 枚举/字符集校验收口，
     * 值序列化（引号转义）由 weaviate-client Serializer 兜底（纵深防御）。
     *
     * @return null 或 And(Or(可见性谓词...), 敏感闸门) 形态的 typed 过滤器
     */
    static WhereFilter buildAccessWhereFilter(QueryVectorBo bo) {
        List<WhereFilter> visibilityFilters = new ArrayList<>();
        if (bo.getScopeTypes() != null && !bo.getScopeTypes().isEmpty()) {
            visibilityFilters.add(stringValuesFilter(WeaviatePayloadKeys.SCOPE_TYPE, Operator.ContainsAny,
                bo.getScopeTypes()));
        }
        if (bo.getGroupId() != null) {
            visibilityFilters.add(stringValuesFilter(WeaviatePayloadKeys.GROUP_ID, Operator.Equal,
                List.of(String.valueOf(bo.getGroupId()))));
        }
        if (bo.getProjectId() != null) {
            visibilityFilters.add(stringValuesFilter(WeaviatePayloadKeys.PROJECT_ID, Operator.Equal,
                List.of(String.valueOf(bo.getProjectId()))));
        }
        if (bo.getPersonId() != null) {
            visibilityFilters.add(stringValuesFilter(WeaviatePayloadKeys.OWNER_PERSON_ID, Operator.Equal,
                List.of(String.valueOf(bo.getPersonId()))));
        }
        if (bo.getOwnerAgentIds() != null && !bo.getOwnerAgentIds().isEmpty()) {
            List<String> agentIds = bo.getOwnerAgentIds().stream().map(String::valueOf).toList();
            visibilityFilters.add(stringValuesFilter(WeaviatePayloadKeys.OWNER_AGENT_ID, Operator.ContainsAny,
                agentIds));
        }
        WhereFilter sensitivityGate = bo.getMaxSensitivity() == null ? null
            : stringValuesFilter(WeaviatePayloadKeys.SENSITIVITY, Operator.ContainsAny,
                KnowledgeSensitivity.allowedNamesUpTo(bo.getMaxSensitivity()));

        if (visibilityFilters.isEmpty() && sensitivityGate == null) {
            return null;
        }
        if (visibilityFilters.isEmpty()) {
            return sensitivityGate;
        }
        WhereFilter visibilityGroup = group(Operator.Or, visibilityFilters);
        if (sensitivityGate == null) {
            return visibilityGroup;
        }
        return WhereFilter.builder().operator(Operator.And).operands(visibilityGroup, sensitivityGate).build();
    }

    /**
     * string dataType payload 键的谓词：值一律走 builder 的 {@code valueString}
     * （weaviate-client 5.3.0 typed API，单值/多值均以 varargs 落 valueStringArray，
     * 序列化时由客户端统一输出 valueString 形态并做引号转义）。
     * valueText 仅适用于 text dataType 键（见 removeByDocId/removeByFid 的 docId/fid）。
     */
    private static WhereFilter stringValuesFilter(String key, String operator, List<String> values) {
        return WhereFilter.builder()
            .path(key)
            .operator(operator)
            .valueString(values.toArray(new String[0]))
            .build();
    }

    private static WhereFilter group(String operator, List<WhereFilter> operands) {
        if (operands.size() == 1) {
            return operands.get(0);
        }
        return WhereFilter.builder()
            .operator(operator)
            .operands(operands.toArray(new WhereFilter[0]))
            .build();
    }

    /**
     * B2 P1-1：库级 sensitivity 变更随动已入库 payload 的 sensitivity 键
     * （B1 轮 Validator 登记「update 不随动=隔离穿透」，最佳实践 §4 铁律一）。
     * <p>
     * 实现路径（weaviate-client 5.3.0）：游标分页（withAfter）拉取该 class 全部对象
     * {@code _additional { id }}，逐对象 {@code data().updater().withMerge()} PATCH
     * 单键——merge 语义只改 sensitivity 不触碰 text/fid/kid/docId 等其余 payload
     * （batch PUT 会整体替换属性，禁用）。
     * 不带 where 全量扫：老 4 键对象（sensitivity 键缺失）不命中 NotEqual 过滤，
     * 全量 merge 顺带补齐缺键（B1 前写入对象的补标），正确性优先于扫描成本——
     * 库级改敏感级是低频人审动作（§8.1 规则 1），可接受。
     * 失败语义 fail-noisy：任一批次失败抛 {@link ServiceException}，由调用方
     * KnowledgeInfoServiceImpl 与 MySQL 更新同事务回滚（两侧不脱钩）。
     */
    @Override
    public void updatePayloadSensitivity(String kid, String sensitivity, String embeddingModelName) {
        createSchema(kid, embeddingModelName);
        String className = vectorStoreProperties.getWeaviate().getClassname() + kid;
        Map<String, Object> patch = java.util.Map.of(WeaviatePayloadKeys.SENSITIVITY, sensitivity);
        String cursor = null;
        int total = 0;
        while (true) {
            List<String> batchIds = fetchObjectIds(className, cursor);
            if (batchIds.isEmpty()) {
                break;
            }
            for (String id : batchIds) {
                Result<Boolean> update = getClient().data().updater()
                    .withID(id)
                    .withClassName(className)
                    .withMerge()
                    .withProperties(patch)
                    .run();
                if (update == null || update.hasErrors()) {
                    throw new ServiceException("Weaviate payload 敏感级随动失败: kid=" + kid
                        + ", objectId=" + id + ", error=" + (update == null ? "null result" : update.getError()));
                }
                total++;
            }
            String next = batchIds.get(batchIds.size() - 1);
            if (next.equals(cursor)) {
                // 防御：游标未推进（异常服务端行为），终止防死循环并留痕
                log.error("Weaviate 游标未推进，终止敏感级随动: class={}, cursor={}", className, cursor);
                break;
            }
            cursor = next;
        }
        log.info("Weaviate payload 敏感级随动完成: kid={}, sensitivity={}, 更新对象数={}", kid, sensitivity, total);
    }

    /**
     * 游标分页拉取 class 下对象 ID 列表（withAfter 语义：传入上一批末位 id，
     * 返回其后对象；空列表=遍历完成）。批量上限与 createSchema 补齐探测同一量级。
     */
    private List<String> fetchObjectIds(String className, String cursor) {
        Get query = getClient().graphQL().get()
            .withClassName(className)
            .withLimit(PAYLOAD_SYNC_BATCH_SIZE)
            // P2-1：游标分页必须固定排序——无 sort 时 Weaviate 跨请求顺序不保证
            //（并发写入下尤甚），withAfter 遍历会漏批=部分对象敏感级未随动。
            .withSort(SortArgument.builder().path(new String[]{"id"}).order(SortOrder.asc).build())
            .withFields(Field.builder().name("_additional")
                .fields(Field.builder().name("id").build()).build());
        if (cursor != null) {
            query = query.withAfter(cursor);
        }
        Result<GraphQLResponse> result = query.run();
        if (result == null || result.hasErrors()) {
            throw new ServiceException("Weaviate 对象 ID 分页拉取失败: class=" + className
                + ", error=" + (result == null ? "null result" : result.getError()));
        }
        return parseBatchObjectIds(result.getResult() == null ? null : result.getResult().getData(), className);
    }

    /**
     * 解析 GraphQL Get 响应中的对象 ID 列表（包私有静态便于直测：与 #search 的
     * JSONObject 解析同构，data.Get.&lt;className&gt; 数组取 _additional.id）。
     */
    static List<String> parseBatchObjectIds(Object data, String className) {
        List<String> ids = new ArrayList<>();
        if (data == null) {
            return ids;
        }
        JSONObject entries = new JSONObject(data);
        Map<String, cn.hutool.json.JSONArray> entriesMap = entries.get("Get", Map.class);
        if (entriesMap == null) {
            return ids;
        }
        cn.hutool.json.JSONArray objects = entriesMap.get(className);
        if (objects == null) {
            return ids;
        }
        for (Object obj : objects) {
            Map<String, Object> map = (Map<String, Object>) obj;
            Map<String, Object> additional = (Map<String, Object>) map.get("_additional");
            if (additional != null && additional.get("id") != null) {
                ids.add(String.valueOf(additional.get("id")));
            }
        }
        return ids;
    }

    @Override
    @SneakyThrows
    public void removeById(String id, String modelName) {
        String className = vectorStoreProperties.getWeaviate().getClassname();
        String finalClassName = className + id;
        Result<Boolean> result = getClient().schema().classDeleter().withClassName(finalClassName).run();
        knownClasses.remove(finalClassName);
        if (result.hasErrors()) {
            log.error("失败删除向量: " + result.getError());
            throw new ServiceException("失败删除向量数据!");
        } else {
            log.info("成功删除向量数据: " + result.getResult());
        }
    }

    @Override
    public void removeByDocId(String docId, String kid) {
        String className = vectorStoreProperties.getWeaviate().getClassname() + kid;
        // 构建 Where 条件（docId 为 text dataType 键，valueText 是其正确配对形态）
        WhereFilter whereFilter = WhereFilter.builder()
                .path("docId")
                .operator(Operator.Equal)
                .valueText(docId)
                .build();
        ObjectsBatchDeleter deleter = getClient().batch().objectsBatchDeleter();
        Result<BatchDeleteResponse> result = deleter.withClassName(className)
                .withWhere(whereFilter)
                .run();
        if (result != null && !result.hasErrors()) {
            log.info("成功删除 docId={} 的所有向量数据", docId);
        } else {
            log.error("删除失败: {}", result.getError());
        }
    }

    @Override
    public void removeByFid(String fid, String kid) {
        String className = vectorStoreProperties.getWeaviate().getClassname() + kid;
        // 构建 Where 条件（fid 为 text dataType 键，valueText 是其正确配对形态）
        WhereFilter whereFilter = WhereFilter.builder()
                .path("fid")
                .operator(Operator.Equal)
                .valueText(fid)
                .build();
        ObjectsBatchDeleter deleter = getClient().batch().objectsBatchDeleter();
        Result<BatchDeleteResponse> result = deleter.withClassName(className)
                .withWhere(whereFilter)
                .run();
        if (result != null && !result.hasErrors()) {
            log.info("成功删除 fid={} 的所有向量数据", fid);
        } else {
            log.error("删除失败: {}", result.getError());
        }
    }

}
