package org.ruoyi.service.embed.impl;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.ContentBlock;
import reactor.core.publisher.Mono;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.enums.ModalityType;
import org.ruoyi.service.embed.BaseEmbedModelService;
import org.springframework.stereotype.Component;
import java.util.Set;

/** 使用 AgentScope 原生嵌入客户端，保留模型配置权限。 */
@Component("openai")
@org.springframework.context.annotation.Scope("prototype")
public class OpenAiEmbeddingProvider implements BaseEmbedModelService {
    private ChatModelVo config;
    private EmbeddingModel model;

    @Override
    public void configure(ChatModelVo config) {
        this.config = config;
        this.model = org.ruoyi.service.embed.EmbeddingModels.create(credentialProvider(),
            config.getModelName(), config.getApiHost(), config.resolveApiKeyForConfiguredEndpoint(credentialProvider()), config.getModelDimension(),
            java.time.Duration.ofSeconds(60));
    }

    protected String credentialProvider() { return "openai"; }

    @Override
    public Set<ModalityType> getSupportedModalities() { return Set.of(ModalityType.TEXT); }

    @Override
    public String getModelName() { return config.getModelName(); }

    @Override
    public int getDimensions() { return config.getModelDimension() == null ? 1024 : config.getModelDimension(); }

    @Override
    public Mono<double[]> embed(ContentBlock block) { return model.embed(block); }
}
