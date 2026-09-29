package org.ruoyi.ipd.service;

import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.AiModelUsageLedger;
import org.ruoyi.ipd.mapper.AiModelUsageLedgerMapper;
import org.springframework.stereotype.Service;

/**
 * C2 用量账本（2026-09-29）：每次 AI 调用追加一行（含失败），补齐 usage「落库记账」缺口。
 * usage 采集面已有（{@code AiChatResult.promptTokens/completionTokens} 双路解析）；
 * 本服务只追加不更新，账本事实不回写、不修正（修正走新行 + 业务标注）。
 */
@Service
@RequiredArgsConstructor
public class AiModelUsageLedgerService {

    private final AiModelUsageLedgerMapper mapper;

    /** 落账一行。失败调用也要落（status=错误码，tokens 可为 0）。 */
    public void recordUsage(Long modelConfigId, String actorId, String scene,
                            int promptTokens, int completionTokens, long latencyMs,
                            String status, String traceId) {
        Objects.requireNonNull(modelConfigId, "modelConfigId");
        mapper.insert(AiModelUsageLedger.builder()
                .modelConfigId(modelConfigId)
                .actorId(actorId)
                .scene(scene == null ? "" : scene)
                .promptTokens(Math.max(0, promptTokens))
                .completionTokens(Math.max(0, completionTokens))
                .latencyMs(Math.max(0L, latencyMs))
                .status(status == null ? "ok" : status)
                .traceId(traceId)
                .build());
    }
}
