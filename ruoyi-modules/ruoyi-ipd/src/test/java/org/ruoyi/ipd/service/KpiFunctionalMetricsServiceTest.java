package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiFunctionalMetric;
import org.ruoyi.ipd.mapper.KpiFunctionalMetricMapper;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W2-KPI2A：KPI 功能指标量表服务单测（A2 P1，R148.1 §2.2）。
 *
 * <p>覆盖 5 维度：
 * <ol>
 *   <li>list：正常有数据 / 空集 / 8 项编码枚举</li>
 *   <li>upsert：未命中新增 / 命中幂等更新（不追加行）</li>
 *   <li>delete：软删除成功 / 不存在抛 NOT_FOUND</li>
 *   <li>异常：projectId 空 / metricCode 不在 8 项白名单 / 两值同空 / 负值</li>
 *   <li>契约：8 项指标编码与 KpiScoreCalculator 字段集合一一对应</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiFunctionalMetricsServiceTest {

    @Mock
    private KpiFunctionalMetricMapper mapper;

    private KpiFunctionalMetricsService service;

    private static final Long PROJECT_ID = 100L;
    private static final String METRIC = "MKT_REQUIREMENT_ACCURACY";
    private static final String PERIOD = "2026-09";

    @BeforeAll
    static void initMeta() {
        // lambdaUpdate 构造时即解析 lambda 列名，需 TableInfo 缓存（R216 软删适配同款）
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "kpi-functional-test");
        TableInfoHelper.initTableInfo(assistant, KpiFunctionalMetric.class);
    }

    @BeforeEach
    void setup() {
        service = new KpiFunctionalMetricsService(mapper);
    }

    // ===== 1. list =====

    @Test
    @DisplayName("list 正常：返回该项目量表记录")
    void listByProject_normal() {
        KpiFunctionalMetric row = new KpiFunctionalMetric()
            .setId(1L).setProjectId(PROJECT_ID).setMetricCode(METRIC)
            .setPeriod(PERIOD).setMetricValue(new BigDecimal("12.0000"));
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<KpiFunctionalMetric> out = service.listByProject(PROJECT_ID, null);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getMetricCode()).isEqualTo(METRIC);
        assertThat(out.get(0).getDelFlag()).isNull();
    }

    @Test
    @DisplayName("list 空：无记录返回空列表（不抛异常、不伪造 0 值）")
    void listByProject_empty() {
        when(mapper.selectList(any())).thenReturn(List.of());

        List<KpiFunctionalMetric> out = service.listByProject(PROJECT_ID, METRIC);

        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("list 异常：projectId 为空 → PARAM_INVALID")
    void listByProject_nullProjectId() {
        assertThatThrownBy(() -> service.listByProject(null, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("projectId");
    }

    @Test
    @DisplayName("list 异常：metricCode 不在 8 项白名单 → PARAM_INVALID")
    void listByProject_invalidMetricCode() {
        assertThatThrownBy(() -> service.listByProject(PROJECT_ID, "K01"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("metricCode 不合法");
    }

    @Test
    @DisplayName("list：8 项功能指标编码枚举保持声明顺序")
    void listMetricCodes_eight() {
        List<String> codes = service.listMetricCodes();
        assertThat(codes).hasSize(8);
        assertThat(codes.get(0)).isEqualTo("MKT_REQUIREMENT_ACCURACY");
        assertThat(codes.get(7)).isEqualTo("RD_FIRST_PASS_YIELD");
    }

    // ===== 2. upsert =====

    @Test
    @DisplayName("upsert 新增：未命中幂等键 → insert")
    void upsert_insert() {
        when(mapper.selectOne(any())).thenReturn(null);
        KpiFunctionalMetric draft = new KpiFunctionalMetric()
            .setProjectId(PROJECT_ID).setMetricCode(METRIC).setPeriod(PERIOD)
            .setMetricValue(new BigDecimal("0.1500"))
            .setTargetValue(new BigDecimal("0.1000"))
            .setScaleVersion("SCALE-V1");

        KpiFunctionalMetric out = service.upsert(draft);

        assertThat(out).isSameAs(draft);
        verify(mapper).insert(draft);
        verify(mapper, never()).updateById(any(KpiFunctionalMetric.class));
    }

    @Test
    @DisplayName("upsert 幂等：命中同 (project+metric+period) → updateById，不追加新行")
    void upsert_update_idempotent() {
        KpiFunctionalMetric existed = new KpiFunctionalMetric()
            .setId(7L).setProjectId(PROJECT_ID).setMetricCode(METRIC).setPeriod(PERIOD)
            .setMetricValue(new BigDecimal("0.2000"));
        existed.setDelFlag("0");
        when(mapper.selectOne(any())).thenReturn(existed);

        KpiFunctionalMetric draft = new KpiFunctionalMetric()
            .setProjectId(PROJECT_ID).setMetricCode(METRIC).setPeriod(PERIOD)
            .setMetricValue(new BigDecimal("0.1800"))
            .setTargetValue(new BigDecimal("0.1200"))
            .setScaleVersion("SCALE-V2")
            .setRemark("复录");

        KpiFunctionalMetric out = service.upsert(draft);

        assertThat(out.getId()).isEqualTo(7L);
        assertThat(out.getMetricValue()).isEqualByComparingTo("0.1800");
        assertThat(out.getScaleVersion()).isEqualTo("SCALE-V2");
        verify(mapper).updateById(existed);
        verify(mapper, never()).insert(any(KpiFunctionalMetric.class));
    }

    @Test
    @DisplayName("upsert 异常：metricCode 不在 8 项白名单 → PARAM_INVALID")
    void upsert_invalidMetricCode() {
        KpiFunctionalMetric draft = new KpiFunctionalMetric()
            .setProjectId(PROJECT_ID).setMetricCode("U03").setPeriod(PERIOD)
            .setMetricValue(BigDecimal.ONE);

        assertThatThrownBy(() -> service.upsert(draft))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("metricCode 不合法");
        verify(mapper, never()).insert(any(KpiFunctionalMetric.class));
    }

    @Test
    @DisplayName("upsert 异常：metricValue 与 targetValue 同时为空 → PARAM_INVALID")
    void upsert_bothValuesNull() {
        KpiFunctionalMetric draft = new KpiFunctionalMetric()
            .setProjectId(PROJECT_ID).setMetricCode(METRIC).setPeriod(PERIOD);

        assertThatThrownBy(() -> service.upsert(draft))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不可同时为空");
    }

    @Test
    @DisplayName("upsert 异常：metricValue 为负 → PARAM_INVALID")
    void upsert_negativeValue() {
        KpiFunctionalMetric draft = new KpiFunctionalMetric()
            .setProjectId(PROJECT_ID).setMetricCode(METRIC).setPeriod(PERIOD)
            .setMetricValue(new BigDecimal("-1"));

        assertThatThrownBy(() -> service.upsert(draft))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不能为负数");
    }

    @Test
    @DisplayName("upsert 异常：period 为空 → PARAM_INVALID")
    void upsert_blankPeriod() {
        KpiFunctionalMetric draft = new KpiFunctionalMetric()
            .setProjectId(PROJECT_ID).setMetricCode(METRIC).setPeriod("  ")
            .setTargetValue(BigDecimal.TEN);

        assertThatThrownBy(() -> service.upsert(draft))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("period");
    }

    // ===== 3. delete =====

    @Test
    @DisplayName("delete 正常：置 del_flag=1 软删（不做物理 DELETE）")
    void delete_soft() {
        KpiFunctionalMetric existed = new KpiFunctionalMetric()
            .setId(9L).setProjectId(PROJECT_ID).setMetricCode(METRIC).setPeriod(PERIOD);
        existed.setDelFlag("0");
        when(mapper.selectById(9L)).thenReturn(existed);
        when(mapper.update(isNull(), any())).thenReturn(1);

        service.delete(9L);

        // R216 软删改显式 UPDATE（@TableLogic 剔除病根），不再碰实体
        verify(mapper).update(isNull(), any());
    }

    @Test
    @DisplayName("delete 异常：记录不存在 → NOT_FOUND")
    void delete_notFound() {
        when(mapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.delete(404L))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不存在");
    }

    // ===== 4. 契约 =====

    @Test
    @DisplayName("契约：8 项编码与 KpiScoreCalculator 的 4+4 功能字段数一致")
    void codes_match_calculator_fields() {
        assertThat(KpiFunctionalMetricsService.METRIC_CODES)
            .hasSize(KpiScoreCalculator.MARKET_PM_FUNCTIONAL_FIELDS.size()
                + KpiScoreCalculator.RD_PM_FUNCTIONAL_FIELDS.size());
        assertThat(KpiFunctionalMetricsService.METRIC_CODES).doesNotHaveDuplicates();
    }
}
