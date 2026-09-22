package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.service.KpiRulesService;
import org.ruoyi.ipd.vo.KpiRuleView;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W1-KPI / paiban-02 方案 B 单测：{@code GET /api/v1/kpi/rules}（R108 契约）。
 *
 * <p>3 维度覆盖：
 * <ol>
 *   <li>正常返回 [{ruleKey, ruleValue}] 列表；</li>
 *   <li>空态（数据源无规则）→ 空列表不抛，code=0；</li>
 *   <li>鉴权前置：requireInternal 抛 IpdPermissionException 透传，service 零调用。</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiRulesControllerTest {

    @Mock
    private IpdPermission ipdPermission;

    @Mock
    private KpiRulesService rulesService;

    @InjectMocks
    private KpiRulesController controller;

    @Test
    @DisplayName("[W1-KPI-1] rules 正常返回 [{ruleKey, ruleValue}] 列表")
    void rules_returns_rule_list() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(1L, "市场PM", "MARKET_PM", 10L));
        when(rulesService.listActiveRules()).thenReturn(List.of(
            new KpiRuleView("self", "0.5"),
            new KpiRuleView("market", "0.5")));

        ApiV1Response<List<KpiRuleView>> resp = controller.rules();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).hasSize(2);
        assertThat(resp.getData()).extracting(KpiRuleView::ruleKey).containsExactly("self", "market");
        assertThat(resp.getData()).extracting(KpiRuleView::ruleValue).containsExactly("0.5", "0.5");
        verify(rulesService, times(1)).listActiveRules();
    }

    @Test
    @DisplayName("[W1-KPI-2] rules 数据源为空 → 返回空列表不抛（code=0）")
    void rules_with_empty_source_returns_empty_list() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(1L, "超管", "SUPER_ADMIN", null));
        when(rulesService.listActiveRules()).thenReturn(Collections.emptyList());

        ApiV1Response<List<KpiRuleView>> resp = controller.rules();

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isEmpty();
        verify(rulesService, times(1)).listActiveRules();
    }

    @Test
    @DisplayName("[W1-KPI-3] rules 未认证 → IpdPermissionException(401) 透传，service 零调用")
    void rules_requires_internal_permission() {
        when(ipdPermission.requireInternal())
            .thenThrow(new IpdPermissionException(401, ApiV1ErrorCode.UNAUTHORIZED));

        assertThatThrownBy(() -> controller.rules())
            .isInstanceOf(IpdPermissionException.class)
            .satisfies(ex -> {
                IpdPermissionException pex = (IpdPermissionException) ex;
                assertThat(pex.getHttpStatus()).isEqualTo(401);
                assertThat(pex.getErrorCode()).isEqualTo(ApiV1ErrorCode.UNAUTHORIZED);
            });
        verify(rulesService, never()).listActiveRules();
    }
}
