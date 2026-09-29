package org.ruoyi.ipd.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.AiModelBudget;
import org.ruoyi.ipd.mapper.AiModelBudgetMapper;

/**
 * C2 预算预占→结算测试：超支拒绝、CAS 重试耗尽保守拒绝、无预算行不限额、结算恒执行。
 *
 * <p><b>注意</b>：必须 {@code @Tag("dev")}（Surefire groups=${profiles.active} 过滤，缺 tag 假绿）。
 *
 * <p>Mock 合法性：mock 的 {@code AiModelBudget} 行数据组合与真库生产路径一致——
 * {@code preoccupiedTokens>0} 仅出现在预占成功后的行、{@code consumedTokens} 只由结算写入，
 * 不造 PENDING 配错值类的死路组合。
 */
@Tag("dev")
@DisplayName("C2 预算：预占→消耗→结算")
class AiModelBudgetServiceTest {

    private AiModelBudgetMapper mapper;
    private AiModelBudgetService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AiModelBudgetMapper.class);
        service = new AiModelBudgetService(mapper);
    }

    private static AiModelBudget row(long budget, long preoccupied, long consumed) {
        return AiModelBudget.builder()
                .id(1L)
                .modelConfigId(7L)
                .budgetMonth("2026-09")
                .budgetTokens(budget)
                .preoccupiedTokens(preoccupied)
                .consumedTokens(consumed)
                .overageTokens(0L)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("无预算行 = 不限额：预占放行、结算 no-op")
    void noBudgetRowMeansUnlimited() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(service.preoccupy(7L, 1000)).isTrue();
        service.settle(7L, 1000, 800);

        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("超支拒绝：预占后总占用超预算 → false，不落 UPDATE")
    void overBudgetRejected() {
        when(mapper.selectOne(any())).thenReturn(row(100, 0, 90));

        assertThat(service.preoccupy(7L, 20)).isFalse();

        verify(mapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("边界放行：恰好用满预算 = 放行")
    void exactBudgetAllowed() {
        assertThat(AiModelBudgetService.canPreoccupy(row(100, 30, 50), 20)).isTrue();
        assertThat(AiModelBudgetService.canPreoccupy(row(100, 30, 50), 21)).isFalse();
    }

    @Test
    @DisplayName("CAS 成功：预占落库（UPDATE 命中 version 条件）")
    void preoccupyCasSuccess() {
        when(mapper.selectOne(any())).thenReturn(row(100, 0, 0));
        when(mapper.update(any(), any())).thenReturn(1);

        assertThat(service.preoccupy(7L, 60)).isTrue();

        verify(mapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("CAS 重试耗尽：并发争用下保守拒绝（fail-closed，好过超卖）")
    void preoccupyCasExhaustedRejects() {
        when(mapper.selectOne(any())).thenReturn(row(100, 0, 0));
        when(mapper.update(any(), any())).thenReturn(0);

        assertThat(service.preoccupy(7L, 60)).isFalse();

        verify(mapper, times(3)).update(any(), any());
    }

    @Test
    @DisplayName("结算恒执行：回冲预占、记真实消耗（含超支也记账不吞）")
    void settleAlwaysApplies() {
        when(mapper.selectOne(any())).thenReturn(row(100, 60, 0));

        service.settle(7L, 60, 150);

        verify(mapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("结算数学：[回冲预占值, 消耗增量]")
    void settleDeltaSemantics() {
        long[] delta = AiModelBudgetService.settleDelta(60, 150);

        assertThat(delta[0]).isEqualTo(60L);
        assertThat(delta[1]).isEqualTo(150L);
    }

    @Test
    @DisplayName("负值入参拒绝")
    void negativeTokensRejected() {
        assertThatThrownBy(() -> service.preoccupy(7L, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.settle(7L, -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
