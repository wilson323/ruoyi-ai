package org.ruoyi.service.vector;

import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.domain.bo.vector.QueryVectorBo;
import org.ruoyi.domain.bo.vector.StoreEmbeddingBo;
import org.ruoyi.domain.vo.knowledge.KnowledgeRetrievalVo;

import java.util.List;

/**
 * 向量库管理
 *
 * @author ageer
 */
public interface VectorStoreService {

    void storeEmbeddings(StoreEmbeddingBo storeEmbeddingBo) throws ServiceException;

    List<String> getQueryVector(QueryVectorBo queryVectorBo);

    /**
     * 带分数及元数据的检索（用于测试检索功能）
     */
    List<KnowledgeRetrievalVo> search(QueryVectorBo queryVectorBo);

    void createSchema(String kid, String embeddingModelName);

    void removeById(String id, String modelName) throws ServiceException;

    void removeByDocId(String docId, String kid) throws ServiceException;

    void removeByFid(String fid, String kid) throws ServiceException;

    /**
     * 库级 sensitivity 变更时同步已入库向量 payload 的 sensitivity 键（B2 P1-1：
     * B1 轮 Validator 登记「update 改 sensitivity 向量 payload 不随动」——两侧脱钩
     * 即隔离穿透，最佳实践 §4 铁律一）。
     * <p>
     * 默认实现 no-op（静默空体，接口不便记日志）：Milvus/Qdrant 策略 metadata 仅 3 键
     * （fid/kid/docId，N2 登记缺口），本就不承载 sensitivity 键，无事可随动，
     * 不阻断合法管理流；策略补齐 payload 键时再 override。
     * Weaviate 实现见 {@code WeaviateVectorStoreStrategy}（游标分页 + merge 更新）。
     * <p>
     * 失败语义：fail-noisy 抛 {@link ServiceException}（调用方 KnowledgeInfoServiceImpl
     * 与 MySQL 更新同事务回滚，两侧不脱钩；TOCTOU 窗口与 P2-2 登记同款，可重试）。
     *
     * @param kid               知识库 ID
     * @param sensitivity       新敏感级（PUBLIC/INTERNAL/SECRET，已归一化）
     * @param embeddingModelName 库级向量模型名（供策略保障 class/schema 存在）
     */
    default void updatePayloadSensitivity(String kid, String sensitivity, String embeddingModelName) {
    }
}
