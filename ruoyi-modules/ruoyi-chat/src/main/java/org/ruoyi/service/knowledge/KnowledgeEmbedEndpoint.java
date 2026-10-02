package org.ruoyi.service.knowledge;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.ollama.OllamaTextEmbedding;
import org.ruoyi.common.chat.embedding.BuiltinEmbeddingDefaults;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.entity.knowledge.KnowledgeFragment;
import org.ruoyi.domain.vo.knowledge.KnowledgeInfoVo;
import org.ruoyi.mapper.knowledge.KnowledgeFragmentMapper;
import org.ruoyi.service.vector.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库解析选用的嵌入端点。
 * 知识库上的模型名和向量库可以继续为空；为空时与文档嵌入一样走内置模型，不另建模型行。
 */
public final class KnowledgeEmbedEndpoint {

    /** 与文档嵌入相同的内置模型名。 */
    public static final String MODEL_NAME = BuiltinEmbeddingDefaults.MODEL_NAME;

    /** 与文档嵌入相同的内置地址。 */
    public static final String BASE_URL = BuiltinEmbeddingDefaults.BASE_URL;

    private static final int EMBED_TIMEOUT_MS = 30_000;

    /**
     * 解析结果。
     *
     * @param modelName 选用的模型名
     * @param baseUrl 内置模型的地址；已配置其他模型时为 null，仍走原模型表
     * @param builtin 是否走内置模型
     */
    public record Choice(String modelName, String baseUrl, boolean builtin) {
    }

    private KnowledgeEmbedEndpoint() {
    }

    /**
     * 知识库嵌入模型名为空，或已经是内置模型名时，选用内置 Qwen3。
     * 其他非空名字保持不变，避免改掉已经配好的库。
     *
     * @param embeddingModel 知识库 embedding_model，允许为空
     * @return 选用结果
     */
    public static Choice resolve(String embeddingModel) {
        String name = embeddingModel == null ? "" : embeddingModel.trim();
        if (name.isEmpty() || MODEL_NAME.equals(name)) {
            return new Choice(MODEL_NAME, BASE_URL, true);
        }
        return new Choice(name, null, false);
    }

    /**
     * 判断向量库策略是否应直接使用内置客户端，而不去模型表查找。
     *
     * @param modelName 即将用于嵌入的模型名
     * @return 是内置模型时为 true
     */
    public static boolean isBuiltinModel(String modelName) {
        return MODEL_NAME.equals(modelName);
    }

    /**
     * 构造与文档嵌入相同的客户端：空密钥，不把对话模型的密钥发到这个地址。
     *
     * @return 内置嵌入模型
     */
    public static EmbeddingModel embeddingModel() {
        return org.ruoyi.service.embed.EmbeddingModels.create("ollama", MODEL_NAME, BASE_URL, null,
            BuiltinEmbeddingDefaults.DIMENSION, Duration.ofMillis(EMBED_TIMEOUT_MS));
    }

    /**
     * 解析失败时保留已有来源备注，不用错误信息覆盖。
     *
     * @param existingRemark 解析前的来源备注
     * @param error 失败原因，只用于调用方打日志，不写回备注
     * @return 原备注
     */
    public static String remarkAfterFailure(String existingRemark, String error) {
        return existingRemark;
    }

    /**
     * 给嵌入模型名为空的已有片段补向量。不改正文，不清备注，不改附件状态。
     *
     * @param fragmentMapper 片段表
     * @param infoService 知识库配置
     * @param vectorStore 现有向量库
     * @return 成功回写的片段数
     */
    public static int fillStoredVectors(KnowledgeFragmentMapper fragmentMapper,
                                        IKnowledgeInfoService infoService,
                                        VectorStoreService vectorStore) {
        return KnowledgeStoredVectorFill.fill(fragmentMapper, infoService, vectorStore);
    }
}

/**
 * 给已经入库、嵌入模型仍为空的片段补向量。
 * 不改正文，不清来源备注，不改附件状态，也不把索引进度写成完成。
 */
final class KnowledgeStoredVectorFill {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeStoredVectorFill.class);

    private KnowledgeStoredVectorFill() {
    }

    /**
     * 按文档把空向量片段写入现有向量库，并把实测维度回写到片段行。
     *
     * @param fragmentMapper 片段表
     * @param infoService 知识库配置
     * @param vectorStore 现有向量库
     * @return 成功回写的片段数
     */
    static int fill(KnowledgeFragmentMapper fragmentMapper,
                    IKnowledgeInfoService infoService,
                    VectorStoreService vectorStore) {
        List<KnowledgeFragment> rows = fragmentMapper.selectList(Wrappers.<KnowledgeFragment>lambdaQuery()
            .select(KnowledgeFragment::getId, KnowledgeFragment::getFid, KnowledgeFragment::getIdx,
                KnowledgeFragment::getDocId, KnowledgeFragment::getContent, KnowledgeFragment::getKnowledgeId,
                KnowledgeFragment::getEmbeddingModel, KnowledgeFragment::getEmbeddingDim,
                KnowledgeFragment::getEmbeddedAt)
            .and(w -> w.isNull(KnowledgeFragment::getEmbeddingModel)
                .or().eq(KnowledgeFragment::getEmbeddingModel, ""))
            .orderByAsc(KnowledgeFragment::getKnowledgeId)
            .orderByAsc(KnowledgeFragment::getDocId)
            .orderByAsc(KnowledgeFragment::getIdx));
        Map<String, List<KnowledgeFragment>> byDoc = new LinkedHashMap<>();
        for (KnowledgeFragment row : rows) {
            if (row.getDocId() == null || row.getContent() == null || row.getContent().isBlank()) {
                continue;
            }
            byDoc.computeIfAbsent(row.getKnowledgeId() + "\0" + row.getDocId(), key -> new ArrayList<>()).add(row);
        }
        Map<Long, KnowledgeInfoVo> infos = new LinkedHashMap<>();
        int filled = 0;
        for (List<KnowledgeFragment> group : byDoc.values()) {
            KnowledgeFragment first = group.get(0);
            KnowledgeInfoVo info = infos.computeIfAbsent(first.getKnowledgeId(), infoService::queryById);
            if (info == null) {
                continue;
            }
            KnowledgeEmbedEndpoint.Choice choice = KnowledgeEmbedEndpoint.resolve(info.getEmbeddingModel());
            String kid = String.valueOf(first.getKnowledgeId());
            try {
                vectorStore.removeByDocId(first.getDocId(), kid);
            } catch (RuntimeException ex) {
                LOG.warn("[knowledge-embed] 清理旧向量失败 docId={} error={}", first.getDocId(), ex.getMessage());
            }
            for (KnowledgeFragment row : group) {
                if (storeOne(vectorStore, info, choice, kid, row)) {
                    fragmentMapper.update(null, Wrappers.<KnowledgeFragment>lambdaUpdate()
                        .set(KnowledgeFragment::getEmbeddingModel, choice.modelName())
                        .set(KnowledgeFragment::getEmbeddingDim, row.getEmbeddingDim())
                        .set(KnowledgeFragment::getEmbeddedAt, row.getEmbeddedAt())
                        .eq(KnowledgeFragment::getId, row.getId()));
                    filled++;
                }
            }
        }
        LOG.info("[knowledge-embed] 空向量片段已回写 {} 条", filled);
        return filled;
    }

    private static boolean storeOne(VectorStoreService vectorStore, KnowledgeInfoVo info,
                                    KnowledgeEmbedEndpoint.Choice choice, String kid,
                                    KnowledgeFragment row) {
        StoreEmbeddingBo bo = new StoreEmbeddingBo();
        bo.setKid(kid);
        bo.setDocId(row.getDocId());
        bo.setFids(List.of(row.getFid()));
        bo.setChunkList(List.of(row.getContent()));
        bo.setVectorStoreName(info.getVectorModel());
        bo.setEmbeddingModelName(choice.modelName());
        bo.setBaseUrl(choice.builtin() ? choice.baseUrl() : null);
        bo.setScopeType(info.getScopeType());
        bo.setGroupId(info.getGroupId());
        bo.setProjectId(info.getProjectId());
        bo.setOwnerPersonId(info.getUserId());
        bo.setOwnerAgentId(info.getOwnerAgentId());
        bo.setSensitivity(info.getSensitivity());
        try {
            vectorStore.storeEmbeddings(bo);
        } catch (RuntimeException ex) {
            LOG.warn("[knowledge-embed] 片段向量失败 id={} error={}", row.getId(), ex.getMessage());
            return false;
        }
        if (bo.getEmbeddingDim() == null) {
            return false;
        }
        row.setEmbeddingDim(bo.getEmbeddingDim());
        row.setEmbeddedAt(new Date());
        return true;
    }
}
