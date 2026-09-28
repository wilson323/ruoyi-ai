package org.ruoyi.enums;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型分类枚举与 chat_model.category / sys_dict_type=chat_model_category 取值对齐哨兵。
 *
 * <p>背景（2026-09-28 模型管理盘点）：chat_model.category、字典 chat_model_category 与前端
 * model-modal 统一使用 'rerank'，而枚举历史值为 "reranker"——按分类过滤（/system/model/list
 * category=rerank）会与枚举 key 错位。此测试把「业务消费的分类 key」钉死在真值上，
 * 防止再次漂移。</p>
 */
@Tag("dev")
class ModelTypeTest {

    /** 业务真实消费的分类 key（MediaGenerationController / ChatModelController / 知识库向量与重排链路）。 */
    @Test
    void businessConsumedKeysAlignWithChatModelCategory() {
        assertEquals("chat", ModelType.CHAT.getKey());
        assertEquals("image", ModelType.IMAGE.getKey());
        assertEquals("vector", ModelType.VECTOR.getKey());
        assertEquals("rerank", ModelType.RERANKER.getKey());
        assertEquals("audio", ModelType.AUDIO.getKey());
        assertEquals("video", ModelType.VIDEO.getKey());
    }

    @Test
    void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (ModelType type : ModelType.values()) {
            assertTrue(keys.add(type.getKey()), "重复的模型分类 key: " + type.getKey());
        }
    }
}
