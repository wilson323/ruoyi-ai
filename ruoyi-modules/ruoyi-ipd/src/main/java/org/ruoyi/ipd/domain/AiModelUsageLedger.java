package org.ruoyi.ipd.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * C2 用量账本（ai_model_usage_ledger，2026-09-29）：每次 AI 调用追加一行（含失败）。
 * usage 采集面已有（{@code AiChatResult.promptTokens/completionTokens} 双路解析），
 * 本实体补齐「落库记账」缺口；只追加不更新。
 * created_at 走 DB 默认值，不映射（不继承 BaseEntity，避免 create_time 列名冲突）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("ai_model_usage_ledger")
public class AiModelUsageLedger {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** ai_model_configs.id（模型权威轴）。 */
    private Long modelConfigId;

    /** 调用方 Person id；可空 = 系统触发。 */
    private String actorId;

    /** 业务场景（copilot/gate_precheck/bid_check 等）。 */
    private String scene;

    private Integer promptTokens;

    private Integer completionTokens;

    private Long latencyMs;

    /** ok / 失败错误码。 */
    private String status;

    /** 链路追踪 id（TraceIdFilter）。 */
    private String traceId;
}
