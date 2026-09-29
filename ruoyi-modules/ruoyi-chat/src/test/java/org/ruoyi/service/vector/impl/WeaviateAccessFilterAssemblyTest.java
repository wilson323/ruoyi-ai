package org.ruoyi.service.vector.impl;

import io.weaviate.client.v1.filters.Operator;
import io.weaviate.client.v1.filters.WhereFilter;
import io.weaviate.client.v1.graphql.query.argument.WhereArgument;
import io.weaviate.client.v1.schema.model.Property;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.service.vector.WeaviatePayloadKeys;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 四刀之一/之二：Weaviate 检索侧 typed where 过滤构造 + 入库侧 payload 键装配直测。
 * <p>
 * 语义（最佳实践 §4 铁律二 + Validator P0 修复）：作用域并集∪归属可见性为 Or 组，
 * 叠加敏感闸门 And——闸门为 ContainsAny + 允许值集合
 * （{@code KnowledgeSensitivity#allowedValuesUpTo} 单源展开；字典序
 * INTERNAL&lt;PUBLIC&lt;SECRET 与敏感级升序相反，禁任何 LessThanEqual 序比较）。
 * 全参数空 → null（不加 where）——现网 knowledge_fragment 0 行 / share=1 0 行
 * 空态下检索行为与 B1 前逐字节一致。
 * typed API（weaviate-client 5.3.0 graphQL().get().withWhere）：where 子句由
 * WhereFilter 结构化构建、序列化/转义由客户端负责；string dataType 键的值一律
 * valueString（builder 强制配对，杜绝 valueText/valueString 手拼配错）。
 * payload：新 8 键驼峰裁决（WeaviatePayloadKeys 单源），归属 ID 以 string 承载
 * （雪花精度），null 归属不落键（空态降级仅 4 旧键）。
 */
@Tag("dev")
class WeaviateAccessFilterAssemblyTest {

    /** weaviate-client 5.3.0 build() 的 assignSingleOrArray 会把单元素数组拆成标量
     * （valueStringArray=null、valueString="x"；序列化两种 JSON 形态均合法），断言取值需兼容两态。 */
    private static String[] stringValuesOf(WhereFilter f) {
        if (f.getValueStringArray() != null) {
            return f.getValueStringArray();
        }
        return f.getValueString() != null ? new String[]{f.getValueString()} : new String[0];
    }

    private static QueryVectorBo boWith(String maxSensitivity, Long personId, List<String> scopeTypes,
                                        Long groupId, Long projectId, List<Long> ownerAgentIds) {
        QueryVectorBo bo = new QueryVectorBo();
        bo.applyBackendAccessFilters(maxSensitivity, personId, scopeTypes, groupId, projectId, ownerAgentIds);
        return bo;
    }

    /** 同包直测包私有静态装配方法（typed WhereFilter 形态，无字符串拼接可反编译断言）。 */
    private static WhereFilter accessFilter(QueryVectorBo bo) {
        return WeaviateVectorStoreStrategy.buildAccessWhereFilter(bo);
    }

    @Test
    void emptyFiltersProduceNoWhereFilter() {
        // 空态：B1 装配点不装配过滤参数（权威源在 B2 IPD 桥）→ 不加 where 子句
        assertNull(accessFilter(boWith(null, null, null, null, null, null)));
        assertNull(accessFilter(boWith(" ", null, List.of(), null, null, List.of())),
            "空白/空集合同为不过滤态");
    }

    @Test
    void sensitivityGateUsesContainsAnyWithAllowedValues() {
        // 三档上限 × 允许值集合（集合语义端到端，Validator P0 修复的核心断言）：
        // cap=PUBLIC 仅 [PUBLIC]（序比较会放行 INTERNAL——越权放大）；
        // cap=INTERNAL [PUBLIC, INTERNAL]（序比较会丢 PUBLIC——误杀）；
        // cap=SECRET 全三值。
        WhereFilter publicCap = accessFilter(boWith("PUBLIC", null, null, null, null, null));
        assertNotNull(publicCap);
        assertEquals(Operator.ContainsAny, publicCap.getOperator(),
            "敏感级闸门须为 ContainsAny（集合语义），禁 LessThanEqual 序比较");
        assertArrayEquals(new String[]{WeaviatePayloadKeys.SENSITIVITY}, publicCap.getPath());
        assertArrayEquals(new String[]{"PUBLIC"}, stringValuesOf(publicCap));
        assertNull(publicCap.getOperands(), "单闸门不得包 Or 组");

        WhereFilter internalCap = accessFilter(boWith("INTERNAL", null, null, null, null, null));
        assertEquals(Operator.ContainsAny, internalCap.getOperator());
        assertArrayEquals(new String[]{"PUBLIC", "INTERNAL"}, internalCap.getValueStringArray(),
            "cap=INTERNAL 须含 PUBLIC+INTERNAL 两值");

        WhereFilter secretCap = accessFilter(boWith("SECRET", null, null, null, null, null));
        assertArrayEquals(new String[]{"PUBLIC", "INTERNAL", "SECRET"}, secretCap.getValueStringArray(),
            "cap=SECRET 须含全部三值");
    }

    @Test
    void visibilityPredicatesComposeOrGroup() {
        WhereFilter filter = accessFilter(boWith(null, 5L, List.of("PERSON", "GLOBAL"), 1L, 2L, null));
        assertEquals(Operator.Or, filter.getOperator(), "作用域∪归属须为 Or 组");
        assertNull(filter.getPath(), "组合节点无 path");
        WhereFilter[] operands = filter.getOperands();
        assertEquals(4, operands.length);
        assertArrayEquals(new String[]{WeaviatePayloadKeys.SCOPE_TYPE}, operands[0].getPath());
        assertEquals(Operator.ContainsAny, operands[0].getOperator());
        assertArrayEquals(new String[]{"PERSON", "GLOBAL"}, operands[0].getValueStringArray());
        assertArrayEquals(new String[]{WeaviatePayloadKeys.GROUP_ID}, operands[1].getPath());
        assertEquals(Operator.Equal, operands[1].getOperator());
        assertArrayEquals(new String[]{"1"}, stringValuesOf(operands[1]));
        assertArrayEquals(new String[]{WeaviatePayloadKeys.PROJECT_ID}, operands[2].getPath());
        assertArrayEquals(new String[]{WeaviatePayloadKeys.OWNER_PERSON_ID}, operands[3].getPath());
        for (WhereFilter operand : operands) {
            assertFalse(WeaviatePayloadKeys.SENSITIVITY.equals(operand.getPath()[0]),
                "无 maxSensitivity 不得出现敏感闸门");
        }
    }

    @Test
    void fullCompositionWrapsOrUnderAndGate() {
        WhereFilter filter = accessFilter(boWith("PUBLIC", 5L, List.of("PERSON"), 1L, 2L, List.of(9L)));
        assertEquals(Operator.And, filter.getOperator(), "结构须为 And(Or(...), sensitivity 闸门)");
        WhereFilter[] operands = filter.getOperands();
        assertEquals(2, operands.length);
        assertEquals(Operator.Or, operands[0].getOperator(), "第一操作数须为可见性 Or 组");
        assertEquals(Operator.ContainsAny, operands[1].getOperator(), "第二操作数须为敏感闸门");
        assertArrayEquals(new String[]{WeaviatePayloadKeys.SENSITIVITY}, operands[1].getPath());
        assertArrayEquals(new String[]{"PUBLIC"}, stringValuesOf(operands[1]));
        // Or 组内含数字员工谓词（ContainsAny + 归属 ID 以 string 承载）
        boolean hasAgentPredicate = false;
        for (WhereFilter operand : operands[0].getOperands()) {
            if (WeaviatePayloadKeys.OWNER_AGENT_ID.equals(operand.getPath()[0])) {
                hasAgentPredicate = true;
                assertArrayEquals(new String[]{"9"}, stringValuesOf(operand));
            }
        }
        assertTrue(hasAgentPredicate, "Or 组须含 ownerAgentId 谓词");
    }

    @Test
    void typedWhereArgumentSerializesValueStringNotValueText() {
        // typed 序列化防线（Validator 指出点）：string dataType 键经客户端 WhereArgument
        // 序列化必须输出 valueString 形态，不得出现 valueText（后者仅配 text dataType 键）；
        // 引号转义由客户端 Serializer 负责，本工程不再有自制 escapeGraphQLString。
        WhereFilter gate = accessFilter(boWith("INTERNAL", null, null, null, null, null));
        String serialized = WhereArgument.builder().filter(gate).build().build();
        assertTrue(serialized.contains("valueString"), "须序列化为 valueString，实际=" + serialized);
        assertTrue(serialized.contains("ContainsAny"), "算子须为 ContainsAny，实际=" + serialized);
        assertTrue(serialized.contains("PUBLIC") && serialized.contains("INTERNAL"),
            "值须含允许值集合两元素，实际=" + serialized);
        assertFalse(serialized.contains("valueText"), "string dataType 键禁用 valueText，实际=" + serialized);
    }

    @Test
    void fragmentPayloadCarriesAllTwelveKeys() {
        StoreEmbeddingBo bo = new StoreEmbeddingBo();
        bo.setKid("1");
        bo.setDocId("doc-1");
        bo.setScopeType("GROUP");
        bo.setGroupId(1234567890123456789L); // 雪花量级
        bo.setProjectId(2L);
        bo.setOwnerPersonId(5L);
        bo.setOwnerAgentId(9L);
        bo.setSensitivity("INTERNAL");
        bo.setEmbeddingModelName("text-embedding-v3");
        Map<String, Object> payload = WeaviateVectorStoreStrategy.buildFragmentPayload(bo, "文本", "fid-1", 1024);

        assertEquals(12, payload.size(), "payload 应含 4 旧键 + 8 新键，实际=" + payload.keySet());
        assertEquals("文本", payload.get(WeaviatePayloadKeys.TEXT));
        assertEquals("fid-1", payload.get(WeaviatePayloadKeys.FID));
        assertEquals("1", payload.get(WeaviatePayloadKeys.KID));
        assertEquals("doc-1", payload.get(WeaviatePayloadKeys.DOC_ID));
        assertEquals("GROUP", payload.get(WeaviatePayloadKeys.SCOPE_TYPE));
        assertEquals("1234567890123456789", payload.get(WeaviatePayloadKeys.GROUP_ID),
            "雪花归属 ID 须以 string 承载防精度丢失");
        assertTrue(payload.get(WeaviatePayloadKeys.GROUP_ID) instanceof String);
        assertEquals(1024, payload.get(WeaviatePayloadKeys.EMBEDDING_DIM));
        assertEquals("text-embedding-v3", payload.get(WeaviatePayloadKeys.EMBEDDING_MODEL));
        assertEquals("INTERNAL", payload.get(WeaviatePayloadKeys.SENSITIVITY));
    }

    @Test
    void fragmentPayloadDegradesToLegacyFourKeysWhenOwnershipAbsent() {
        // 空态降级：归属/敏感级未装配（Part A 前的库或未回填）→ 仅 4 旧键，不落 null 值键
        StoreEmbeddingBo bo = new StoreEmbeddingBo();
        bo.setKid("1");
        bo.setDocId("doc-1");
        Map<String, Object> payload = WeaviateVectorStoreStrategy.buildFragmentPayload(bo, "文本", "fid-1", 768);
        assertEquals(Map.of("text", "文本", "fid", "fid-1", "kid", "1", "docId", "doc-1",
            "embeddingDim", 768), payload,
            "归属全空时仅 4 旧键 + 维度，不得出现 null 值键，实际=" + payload);
    }

    @Test
    void requiredPropertiesMatchKeyManifest() {
        List<Property> properties = WeaviatePayloadKeys.requiredProperties();
        assertEquals(12, properties.size());
        Map<String, String> dataTypes = new java.util.LinkedHashMap<>();
        for (Property property : properties) {
            assertEquals(1, property.getDataType().size(), "每键单 dataType，实际=" + property.getName());
            dataTypes.put(property.getName(), property.getDataType().get(0));
        }
        // 旧 4 键保持 text；新字符串键 string（非分词精确过滤）；维度 int
        assertEquals("text", dataTypes.get("text"));
        assertEquals("text", dataTypes.get("fid"));
        assertEquals("text", dataTypes.get("kid"));
        assertEquals("text", dataTypes.get("docId"));
        for (String key : List.of("scopeType", "groupId", "projectId", "ownerPersonId",
            "ownerAgentId", "sensitivity", "embeddingModel")) {
            assertEquals("string", dataTypes.get(key), key + " 应为 string dataType");
        }
        assertEquals("int", dataTypes.get("embeddingDim"));
    }

    @Test
    void keyManifestIsCamelCaseAsAdjudicated() {
        // §3.3 驼峰裁决：键名与现态 text/fid/kid/docId 风格一致，MySQL 侧保持 snake_case
        assertEquals("scopeType", WeaviatePayloadKeys.SCOPE_TYPE);
        assertEquals("groupId", WeaviatePayloadKeys.GROUP_ID);
        assertEquals("projectId", WeaviatePayloadKeys.PROJECT_ID);
        assertEquals("ownerPersonId", WeaviatePayloadKeys.OWNER_PERSON_ID);
        assertEquals("ownerAgentId", WeaviatePayloadKeys.OWNER_AGENT_ID);
        assertEquals("sensitivity", WeaviatePayloadKeys.SENSITIVITY);
        assertEquals("embeddingModel", WeaviatePayloadKeys.EMBEDDING_MODEL);
        assertEquals("embeddingDim", WeaviatePayloadKeys.EMBEDDING_DIM);
    }
}
