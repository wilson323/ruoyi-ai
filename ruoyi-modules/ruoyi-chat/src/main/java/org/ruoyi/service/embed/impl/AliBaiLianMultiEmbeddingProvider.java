package org.ruoyi.service.embed.impl;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeMultiModalEmbedding;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.domain.dto.MultiModalInput;
import org.ruoyi.enums.ModalityType;
import org.ruoyi.service.embed.MultiModalEmbedModelService;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Base64;
import java.util.Set;

/** 百炼嵌入统一使用锁定 SDK 的原生客户端；2.0.3 只支持单文本或单图。 */
@Component("bailianMultiModel")
@org.springframework.context.annotation.Scope("prototype")
public class AliBaiLianMultiEmbeddingProvider implements MultiModalEmbedModelService {
    private ChatModelVo chatModelVo;
    private EmbeddingModel nativeModel;

    @Override
    public Mono<double[]> embedImage(String imageDataUrl) {
        return embed(ImageBlock.builder().source(new URLSource(imageDataUrl)).build());
    }

    @Override
    public Mono<double[]> embedVideo(String videoDataUrl) {
        return unsupported();
    }

    @Override
    public Mono<double[]> embedMultiModal(MultiModalInput input) {
        return Mono.defer(() -> {
            if (input == null || !input.hasAnyContent()) {
                return Mono.error(new IllegalArgumentException("请求内容不能为空"));
            }
            // 不将联合嵌入改成首个输入或平均向量，避免伪造同一向量空间的语义。
            if (input.hasVideo() || input.hasMultiImages() || input.getContentCount() != 1) {
                return unsupported();
            }
            if (input.hasText()) {
                return embed(TextBlock.builder().text(input.getText()).build());
            }
            if (input.getImageData() != null && input.getImageData().length > 0) {
                String mime = input.getImageMimeType();
                if (mime == null || !mime.startsWith("image/")) {
                    return Mono.error(new IllegalArgumentException("图像字节必须指定图像 MIME 类型"));
                }
                return embed(ImageBlock.builder().source(Base64Source.builder().mediaType(mime)
                    .data(Base64.getEncoder().encodeToString(input.getImageData())).build()).build());
            }
            return embedImage(input.getImageUrl());
        });
    }

    private static Mono<double[]> unsupported() {
        return Mono.error(new UnsupportedOperationException("AgentScope 2.0.3 百炼嵌入不支持视频、多图或联合输入"));
    }

    @Override
    public String getModelName() { return chatModelVo.getModelName(); }

    @Override
    public int getDimensions() { return chatModelVo.getModelDimension() == null ? 1024 : chatModelVo.getModelDimension(); }

    @Override
    public Mono<double[]> embed(ContentBlock block) {
        return nativeModel == null ? Mono.error(new IllegalStateException("嵌入模型尚未配置")) : nativeModel.embed(block);
    }

    @Override
    public void configure(ChatModelVo config) {
        this.chatModelVo = config;
        var builder = DashScopeMultiModalEmbedding.builder().modelName(config.getModelName())
            .dimensions(getDimensions()).apiKey(config.resolveApiKeyForConfiguredEndpoint("qianwen"));
        if (config.getApiHost() != null && !config.getApiHost().isBlank()) {
            builder.baseUrl(config.getApiHost().replaceAll("/services/embeddings/.*$", ""));
        }
        this.nativeModel = builder.build();
    }

    @Override
    public Set<ModalityType> getSupportedModalities() {
        return Set.of(ModalityType.TEXT, ModalityType.IMAGE);
    }
}
