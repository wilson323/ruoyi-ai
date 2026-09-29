package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.AiModelBudget;
import org.ruoyi.ipd.mapper.AiModelBudgetMapper;
import org.springframework.stereotype.Service;

/**
 * C2 模型月度预算（2026-09-29，owner 拍板口径：预算维度 = 模型配置 × 自然月，单位 token）。
 *
 * <p><b>生命周期</b>：调用前 {@link #preoccupy}（预占估计值）→ 调用后 {@link #settle}
 * （回冲预占、记真实消耗）。超支拒绝在预占口：{@code preoccupied + consumed + estimate > budget}
 * 即拒绝新请求；结算恒执行（记账事实不丢），超预算差额进 {@code overageTokens} 披露不吞账。
 *
 * <p><b>无预算行 = 不限额</b>：该模型该月无 {@code ai_model_budget} 行时预占放行、结算 no-op
 * （usage 事实由 ai_model_usage_ledger 承载）——避免缺预算行把线上对话全部锁死。
 *
 * <p><b>并发</b>：预占走 {@code version} 乐观锁 CAS（UPDATE ... WHERE version=旧值），
 * 冲突时有限重读重试；纯数学判定收敛在 {@link #canPreoccupy} / {@link #settleDelta}（可独立测试）。
 */
@Service
@RequiredArgsConstructor
public class AiModelBudgetService {

    private final AiModelBudgetMapper mapper;
    private final Clock clock = Clock.systemDefaultZone();

    private static final int CAS_RETRIES = 3;

    /** 调用前预占。true=放行（含无预算行不限额）；false=超支拒绝。 */
    public boolean preoccupy(Long modelConfigId, long estimateTokens) {
        Objects.requireNonNull(modelConfigId, "modelConfigId");
        if (estimateTokens < 0) {
            throw new IllegalArgumentException("estimateTokens < 0");
        }
        String month = month();
        for (int i = 0; i < CAS_RETRIES; i++) {
            AiModelBudget row = findRow(modelConfigId, month);
            if (row == null) {
                return true;
            }
            if (!canPreoccupy(row, estimateTokens)) {
                return false;
            }
            int updated = mapper.update(null, Wrappers.<AiModelBudget>lambdaUpdate()
                    .setSql("preoccupied_tokens = preoccupied_tokens + " + estimateTokens)
                    .setSql("version = version + 1")
                    .eq(AiModelBudget::getId, row.getId())
                    .eq(AiModelBudget::getVersion, row.getVersion())
                    .apply("preoccupied_tokens + consumed_tokens + {0} <= budget_tokens", estimateTokens));
            if (updated > 0) {
                return true;
            }
            // CAS 冲突：重读重试（下一循环按新行状态判定）
        }
        // 重试耗尽：并发争用下保守拒绝（fail-closed，好过超卖预算）
        return false;
    }

    /**
     * 调用后结算：回冲预占、记真实消耗、超支差额披露。
     * {@code preoccupiedTokens} 为当初预占值；实际用量 = prompt + completion。
     * 无预算行 no-op。结算恒执行（不吞账）。
     */
    public void settle(Long modelConfigId, long preoccupiedTokens, long actualTokens) {
        Objects.requireNonNull(modelConfigId, "modelConfigId");
        if (preoccupiedTokens < 0 || actualTokens < 0) {
            throw new IllegalArgumentException("negative tokens");
        }
        String month = month();
        AiModelBudget row = findRow(modelConfigId, month);
        if (row == null) {
            return;
        }
        long[] delta = settleDelta(preoccupiedTokens, actualTokens);
        // 赋值顺序敏感（MySQL 单表 UPDATE 左到右、后者读到前者新值）：
        // overage 必须先算（引用 consumed 旧值），consumed/preoccupied 后更新。
        mapper.update(null, Wrappers.<AiModelBudget>lambdaUpdate()
                .setSql("overage_tokens = overage_tokens + GREATEST(consumed_tokens + " + actualTokens
                        + " - budget_tokens, 0) - GREATEST(consumed_tokens - budget_tokens, 0)")
                .setSql("consumed_tokens = consumed_tokens + " + delta[1])
                .setSql("preoccupied_tokens = GREATEST(preoccupied_tokens - " + delta[0] + ", 0)")
                .eq(AiModelBudget::getId, row.getId()));
    }

    /** 预占数学（纯函数）：预占后总占用不超预算。 */
    static boolean canPreoccupy(AiModelBudget row, long estimateTokens) {
        long used = safe(row.getPreoccupiedTokens()) + safe(row.getConsumedTokens());
        return used + estimateTokens <= safe(row.getBudgetTokens());
    }

    /**
     * 结算数学（纯函数）：[回冲预占值, 真实消耗增量]。
     * 消耗增量 = 本次实际 token（恒记）；预占按当初值回冲（不足部分被 GREATEST(...,0) 截断，
     * 超支披露由 SQL 侧 overage 表达式按 consumed 旧值计算，不吞账）。
     */
    static long[] settleDelta(long preoccupiedTokens, long actualTokens) {
        return new long[] {preoccupiedTokens, actualTokens};
    }

    private AiModelBudget findRow(Long modelConfigId, String month) {
        return mapper.selectOne(Wrappers.<AiModelBudget>lambdaQuery()
                .eq(AiModelBudget::getModelConfigId, modelConfigId)
                .eq(AiModelBudget::getBudgetMonth, month));
    }

    private String month() {
        LocalDate today = LocalDate.now(clock);
        return String.format("%04d-%02d", today.getYear(), today.getMonthValue());
    }

    private static long safe(Long v) {
        return v == null ? 0L : v;
    }
}
