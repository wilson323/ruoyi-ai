package org.ruoyi.chat.poc.kernel;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.DocumentMetadata;
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.store.InMemoryStore;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G5 RAG 关：Knowledge 全链 ingest→retrieve + metadata 过滤正负两例（KB 维四维隔离预演）。
 *
 * <p>API 事实（源码核证 2.0.3）：RetrieveConfig 无 metadata 字段，原生过滤通道仅
 * vectorName（store 级）；业务 metadata 载体是 DocumentMetadata.payload（应用层过滤）。
 * 两条通道都验证，差异登记进验收报告。
 *
 * <p>mock 合法性：仅 stub EmbeddingModel（mock 模型输出=向量），SimpleKnowledge /
 * InMemoryStore / Document 存储检索路径全真（仓内 mock 合法三规约）。
 */
@Tag("dev")
class AgentScopeRagPocIT {

    private static final int DIM = 64;

    /** 确定性词袋哈希向量：同词集合 cosine 高、零词交集 cosine=0，语义可控可复现。 */
    static final class StubEmbeddingModel implements EmbeddingModel {
        @Override
        public Mono<double[]> embed(ContentBlock block) {
            if (!(block instanceof TextBlock textBlock)) {
                return Mono.error(
                        new IllegalArgumentException("stub embedding only supports TextBlock"));
            }
            return Mono.fromCallable(() -> vectorFor(textBlock.getText()));
        }

        @Override
        public String getModelName() {
            return "stub-bag-of-words";
        }

        @Override
        public int getDimensions() {
            return DIM;
        }

        private static double[] vectorFor(String text) {
            double[] v = new double[DIM];
            for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                v[Math.floorMod(token.hashCode(), DIM)] += 1.0d;
            }
            double norm = 0.0d;
            for (double x : v) {
                norm += x * x;
            }
            norm = Math.sqrt(norm);
            if (norm > 0.0d) {
                for (int i = 0; i < DIM; i++) {
                    v[i] /= norm;
                }
            }
            return v;
        }
    }

    private static Document doc(String docId, String text, String projectId, String vectorName) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId);
        DocumentMetadata metadata =
                new DocumentMetadata(TextBlock.builder().text(text).build(), docId, "chunk-0", payload);
        Document document = new Document(metadata);
        if (vectorName != null) {
            document.setVectorName(vectorName);
        }
        return document;
    }

    private static SimpleKnowledge knowledge(InMemoryStore store) {
        return SimpleKnowledge.builder()
                .embeddingModel(new StubEmbeddingModel())
                .embeddingStore(store)
                .build();
    }

    private static List<String> projectIds(List<Document> docs) {
        List<String> ids = new ArrayList<>();
        for (Document d : docs) {
            ids.add(String.valueOf(d.getPayloadValue("projectId")));
        }
        return ids;
    }

    private static void dump(String stage, List<Document> docs) {
        System.out.println("G5 " + stage + " results=" + docs.size());
        for (Document d : docs) {
            System.out.println(
                    "G5   doc="
                            + d.getMetadata().getDocId()
                            + " score="
                            + (d.getScore() == null ? "null" : String.format("%.4f", d.getScore()))
                            + " projectId="
                            + d.getPayloadValue("projectId")
                            + " text="
                            + d.getMetadata().getContentText());
        }
    }

    /** 全链实跑：ingest 两 project 文档 → retrieve，证明 Knowledge 接口链路通。 */
    @Test
    void g5IngestRetrieveFullChain() {
        InMemoryStore store = InMemoryStore.builder().dimensions(DIM).build();
        SimpleKnowledge knowledge = knowledge(store);
        knowledge
                .addDocuments(
                        List.of(
                                doc("p1-spec", "alpha kernel design spec agentscope runtime", "P1", "pP1"),
                                doc("p2-spec", "beta finance report module billing", "P2", "pP2")))
                .block();
        System.out.println("G5 ingest storeSize=" + store.size());

        List<Document> results =
                knowledge
                        .retrieve(
                                "alpha beta kernel finance",
                                RetrieveConfig.builder().limit(10).scoreThreshold(0.01d).build())
                        .block();
        dump("fullchain-retrieve", results);
        assertEquals(2, results.size(), "两 project 文档都应被全量检索命中");
    }

    /** 正例 1：RetrieveConfig.vectorName（store 级过滤通道）只召回本 project。 */
    @Test
    void positiveVectorNameFilterOnlyRecallsOwnProject() {
        InMemoryStore store = InMemoryStore.builder().dimensions(DIM).build();
        SimpleKnowledge knowledge = knowledge(store);
        knowledge
                .addDocuments(
                        List.of(
                                doc("p1-spec", "alpha kernel design spec agentscope runtime", "P1", "pP1"),
                                doc("p1-guide", "alpha kernel deploy guide runtime", "P1", "pP1"),
                                doc("p2-spec", "beta finance report module billing", "P2", "pP2"),
                                doc("p2-guide", "beta billing finance guide module", "P2", "pP2")))
                .block();

        List<Document> results =
                knowledge
                        .retrieve(
                                "alpha beta kernel finance",
                                RetrieveConfig.builder()
                                        .limit(10)
                                        .scoreThreshold(0.01d)
                                        .vectorName("pP1")
                                        .build())
                        .block();
        dump("positive-vectorName-pP1", results);
        assertFalse(results.isEmpty(), "pP1 桶应有命中");
        for (String projectId : projectIds(results)) {
            assertEquals("P1", projectId, "vectorName=pP1 只允许召回 P1 文档");
        }
    }

    /**
     * 正例 2：DocumentMetadata.payload（业务 metadata 载体）应用层过滤只召回本 project。
     * 同 vectorName 混灌两 project（最坏情况），证明 payload 过滤独立有效。
     */
    @Test
    void positivePayloadMetadataFilterOnlyRecallsOwnProject() {
        InMemoryStore store = InMemoryStore.builder().dimensions(DIM).build();
        SimpleKnowledge knowledge = knowledge(store);
        knowledge
                .addDocuments(
                        List.of(
                                doc("p1-spec", "alpha kernel design spec agentscope runtime", "P1", "shared"),
                                doc("p2-spec", "beta finance report module billing", "P2", "shared")))
                .block();

        List<Document> raw =
                knowledge
                        .retrieve(
                                "alpha beta kernel finance",
                                RetrieveConfig.builder()
                                        .limit(10)
                                        .scoreThreshold(0.01d)
                                        .vectorName("shared")
                                        .build())
                        .block();
        dump("payload-filter-raw", raw);
        assertEquals(2, raw.size(), "同桶混灌时不过滤应见两 project（过滤才有意义）");

        List<Document> filtered = new ArrayList<>();
        for (Document d : raw) {
            if ("P1".equals(String.valueOf(d.getPayloadValue("projectId")))) {
                filtered.add(d);
            }
        }
        dump("payload-filter-P1-only", filtered);
        assertFalse(filtered.isEmpty(), "payload 过滤后 P1 文档应保留");
        for (String projectId : projectIds(filtered)) {
            assertEquals("P1", projectId, "payload metadata 过滤后只允许 P1");
        }
    }

    /**
     * 负例：A 桶读不到 B 桶。防假绿设计：P2 桶必须有命中（断言循环真执行）、
     * 全库对照组必须含 P1 文档（证明 P1 确实在库且可被相似度命中）。
     */
    @Test
    void negativeCrossProjectBucketInvisible() {
        InMemoryStore store = InMemoryStore.builder().dimensions(DIM).build();
        SimpleKnowledge knowledge = knowledge(store);
        knowledge
                .addDocuments(
                        List.of(
                                doc("p1-spec", "alpha kernel design spec agentscope runtime", "P1", "pP1"),
                                doc("p2-spec", "beta finance report module billing", "P2", "pP2")))
                .block();

        String mixedQuery = "alpha kernel design spec beta";

        // 全库对照：query 必须同时命中两桶（证明 P1 文档真实可检索）
        List<Document> raw =
                knowledge
                        .retrieve(
                                mixedQuery,
                                RetrieveConfig.builder().limit(10).scoreThreshold(0.01d).build())
                        .block();
        dump("negative-raw-both-buckets", raw);
        assertTrue(projectIds(raw).contains("P1"), "对照组必须含 P1 文档（防假绿）");
        assertTrue(projectIds(raw).contains("P2"), "对照组必须含 P2 文档（防假绿）");

        // 负例 1（store 级）：P2 桶检索不得读到 P1 文档，且 P2 桶自身必须有命中
        List<Document> p2Bucket =
                knowledge
                        .retrieve(
                                mixedQuery,
                                RetrieveConfig.builder()
                                        .limit(10)
                                        .scoreThreshold(0.01d)
                                        .vectorName("pP2")
                                        .build())
                        .block();
        dump("negative-p2-bucket", p2Bucket);
        assertFalse(p2Bucket.isEmpty(), "P2 桶应有命中（防断言空转假绿）");
        for (String projectId : projectIds(p2Bucket)) {
            assertTrue(!"P1".equals(projectId), "P2 桶不得读到 P1 文档");
        }

        // 负例 2（payload 视角）：P2 过滤条件下不得出现 P1 文档，且过滤结果非空
        List<Document> p2ByPayload = new ArrayList<>();
        for (Document d : raw) {
            if ("P2".equals(String.valueOf(d.getPayloadValue("projectId")))) {
                p2ByPayload.add(d);
            }
        }
        dump("negative-p2-by-payload", p2ByPayload);
        assertFalse(p2ByPayload.isEmpty(), "P2 payload 过滤结果应非空（防假绿）");
        for (String projectId : projectIds(p2ByPayload)) {
            assertTrue(!"P1".equals(projectId), "P2 payload 视角不得读到 P1 桶文档");
        }
    }
}
