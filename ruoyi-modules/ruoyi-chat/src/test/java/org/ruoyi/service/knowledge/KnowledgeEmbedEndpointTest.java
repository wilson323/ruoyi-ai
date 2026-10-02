package org.ruoyi.service.knowledge;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识库嵌入配置为空时，必须选用与文档嵌入相同的内置模型，并保留来源备注。
 */
@Tag("dev")
class KnowledgeEmbedEndpointTest {

    @Test
    void blankConfigUsesBuiltinModelAndKeepsSourceRemark() {
        KnowledgeEmbedEndpoint.Choice choice = KnowledgeEmbedEndpoint.resolve(null);

        assertThat(choice.modelName()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(choice.baseUrl()).isEqualTo("http://127.0.0.1:11434/v1");
        assertThat(KnowledgeEmbedEndpoint.remarkAfterFailure("/kb/海康威视.md", "未找到模型配置"))
            .isEqualTo("/kb/海康威视.md");
        assertThat(KnowledgeEmbedEndpoint.resolve("custom-embed").modelName()).isEqualTo("custom-embed");
    }
}
