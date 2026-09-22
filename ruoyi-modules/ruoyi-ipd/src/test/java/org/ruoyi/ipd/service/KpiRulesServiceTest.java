package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.KpiRuleSnapshot;
import org.ruoyi.ipd.domain.SystemConfig;
import org.ruoyi.ipd.mapper.KpiRuleSnapshotMapper;
import org.ruoyi.ipd.vo.KpiRuleView;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * W1-KPI / paiban-02 方案 B 单测：{@link KpiRulesService}（复用现有 KPI 表，零 DB 变更）。
 *
 * <p>覆盖：快照优先拍平 / 空快照回退 system_configs kpi.* / 双空态空列表 /
 * 非法 JSON 降级回退 / 嵌套值保留 JSON。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiRulesServiceTest {

    @Mock
    private KpiRuleSnapshotMapper snapshotMapper;

    @Mock
    private ISystemConfigService systemConfigService;

    private KpiRulesService service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "w1-kpi-rules-test"),
            KpiRuleSnapshot.class);
    }

    @BeforeEach
    void setUp() {
        service = new KpiRulesService(snapshotMapper, systemConfigService);
    }

    @Test
    @DisplayName("[W1-KPI-S1] 数据源 1：最新快照 rule_json 拍平为 [{ruleKey, ruleValue}]（version DESC + LIMIT 1）")
    void listActiveRules_usesLatestSnapshotAndFlattensJson() {
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(snapshot("{\"self\":0.5,\"market\":0.3,\"rd\":0.2}"));

        List<KpiRuleView> rules = service.listActiveRules();

        assertThat(rules).hasSize(3);
        assertThat(rules).extracting(KpiRuleView::ruleKey)
            .containsExactly("self", "market", "rd");
        assertThat(rules).extracting(KpiRuleView::ruleValue)
            .containsExactly("0.5", "0.3", "0.2");
    }

    @Test
    @DisplayName("[W1-KPI-S2] 数据源 2：无快照 → 回退 system_configs 的 kpi.* 键（非 kpi 键过滤）")
    void listActiveRules_fallsBackToSystemConfigWhenSnapshotAbsent() {
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(systemConfigService.list()).thenReturn(List.of(
            config("kpi.monthlyDeadlineDay", "5"),
            config("scenario.approvalRole", "GROUP_LEADER"),
            config("kpi.reviewWeights", "{\"self\":0.6}")));

        List<KpiRuleView> rules = service.listActiveRules();

        assertThat(rules).hasSize(2);
        assertThat(rules).extracting(KpiRuleView::ruleKey)
            .containsExactly("kpi.monthlyDeadlineDay", "kpi.reviewWeights");
        assertThat(rules).extracting(KpiRuleView::ruleValue)
            .containsExactly("5", "{\"self\":0.6}");
    }

    @Test
    @DisplayName("[W1-KPI-S3] 边界：快照与 system_configs 皆空 → 返回空列表不抛")
    void listActiveRules_returnsEmptyListWhenNoSource() {
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(systemConfigService.list()).thenReturn(List.of());

        List<KpiRuleView> rules = service.listActiveRules();

        assertThat(rules).isEmpty();
    }

    @Test
    @DisplayName("[W1-KPI-S4] 异常降级：rule_json 非法 → 回退 system_configs（不抛）")
    void listActiveRules_invalidJsonFallsBackToSystemConfig() {
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(snapshot("{not-json"));
        when(systemConfigService.list())
            .thenReturn(List.of(config("kpi.monthlyDeadlineDay", "5")));

        List<KpiRuleView> rules = service.listActiveRules();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).ruleKey()).isEqualTo("kpi.monthlyDeadlineDay");
    }

    @Test
    @DisplayName("[W1-KPI-S5] 值形态：标量取文本、布尔取 true、嵌套对象/数组保留紧凑 JSON")
    void listActiveRules_normalizesScalarAndNestedValues() {
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(snapshot(
            "{\"self\":0.50,\"enabled\":true,\"weights\":{\"self\":0.5},\"tags\":[\"a\",\"b\"]}"));

        List<KpiRuleView> rules = service.listActiveRules();

        assertThat(rules).extracting(KpiRuleView::ruleKey)
            .containsExactly("self", "enabled", "weights", "tags");
        assertThat(rules).extracting(KpiRuleView::ruleValue)
            .containsExactly("0.50", "true", "{\"self\":0.5}", "[\"a\",\"b\"]");
    }

    @Test
    @DisplayName("[W1-KPI-S6] 边界：rule_json 全空白 → 视为无快照，走 system_configs 回退")
    void listActiveRules_blankRuleJsonFallsBack() {
        when(snapshotMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(snapshot("   "));
        when(systemConfigService.list())
            .thenReturn(List.of(config("kpi.reviewWeights", "{\"self\":0.6}")));

        List<KpiRuleView> rules = service.listActiveRules();

        assertThat(rules).extracting(KpiRuleView::ruleKey).containsExactly("kpi.reviewWeights");
    }

    /* ====================== 测试工具 ====================== */

    private KpiRuleSnapshot snapshot(String ruleJson) {
        return KpiRuleSnapshot.builder()
            .version(3L)
            .ruleJson(ruleJson)
            .build();
    }

    private SystemConfig config(String key, String value) {
        SystemConfig c = new SystemConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        return c;
    }
}
