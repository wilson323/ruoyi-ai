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
 * C2 模型月度预算（ai_model_budget，2026-09-29）：预算维度 = 模型配置 × 自然月（owner 拍板），
 * 预算单位 = token。生命周期：预占（preoccupied）→ 消耗（consumed）→ 结算差额回冲，
 * 超支差额进 {@code overageTokens} 披露（不吞账）。无预算行 = 该模型该月不限额（只记账）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@TableName("ai_model_budget")
public class AiModelBudget {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** ai_model_configs.id。 */
    private Long modelConfigId;

    /** 自然月 '2026-09'。 */
    private String budgetMonth;

    /** 月度预算（token）。 */
    private Long budgetTokens;

    /** 预占中（未结算）。 */
    private Long preoccupiedTokens;

    /** 已结算消耗。 */
    private Long consumedTokens;

    /** 超支披露（结算时记，不吞账）。 */
    private Long overageTokens;

    /** 乐观锁（CAS 预占）。 */
    private Long version;
}
