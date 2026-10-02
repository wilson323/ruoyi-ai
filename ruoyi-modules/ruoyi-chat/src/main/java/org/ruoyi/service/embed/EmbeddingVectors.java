package org.ruoyi.service.embed;

import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.message.TextBlock;
import java.time.Duration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

/** 同步业务边界上的 AgentScope 嵌入结果校验，不改变已有向量库。 */
public final class EmbeddingVectors {
    private EmbeddingVectors() { }

    public static float[] embed(EmbeddingModel model, String text) {
        return embed(model, text, Duration.ofSeconds(60));
    }

    public static float[] embed(EmbeddingModel model, String text, Duration timeout) {
        double[] vector = model.embed(TextBlock.builder().text(text).build()).block(timeout);
        return validate(model, vector);
    }

    private static float[] validate(EmbeddingModel model, double[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("嵌入模型未返回向量");
        }
        if (model.getDimensions() > 0 && vector.length != model.getDimensions()) {
            throw new IllegalStateException("嵌入向量维度与模型配置不一致");
        }
        float[] result = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            result[i] = (float) vector[i];
            if (!Double.isFinite(vector[i]) || !Float.isFinite(result[i])) {
                throw new IllegalStateException("嵌入向量含非法数值");
            }
        }
        return result;
    }

    /** 顺序执行，失败即停止，禁止缺条向量与原分片错位。 */
    public static List<float[]> embedAll(EmbeddingModel model, List<String> texts) {
        return embedAll(model, texts, Duration.ofSeconds(60));
    }

    /** 一个批次共享固定时限；超时取消当前 SDK publisher，后续分片不再启动。 */
    public static List<float[]> embedAll(EmbeddingModel model, List<String> texts, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("嵌入批次超时必须为正数");
        }
        return Flux.fromIterable(texts)
            .concatMap(text -> Mono.defer(() -> model.embed(TextBlock.builder().text(text).build()))
                .switchIfEmpty(Mono.error(new IllegalStateException("嵌入模型未返回向量")))
                .map(vector -> validate(model, vector)))
            .collectList()
            .timeout(timeout)
            .block();
    }
}
