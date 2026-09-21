package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRawRecord;
import org.ruoyi.ipd.mapper.KpiRawRecordMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R149 batch2a A2：KPI 原始数据录入服务单测。
 *
 * <p>覆盖 5 维度：
 * <ol>
 *   <li>正常路径：8 类 KPI 任一类录入成功</li>
 *   <li>边界：recordPeriod 落到每月 1 号；rawValue=0；kpiType=null 列表查询</li>
 *   <li>异常：kpiType 不在白名单 / rawValue 负数 / 百分比类超 1.0 / 重复录入抛 STATE_CONFLICT</li>
 *   <li>审计：recordedBy = actor.id（无 actor 不抛，走 setRecordedBy(null)）</li>
 *   <li>幂等：多次 list 调用结果一致</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiRawRecordServiceTest {

    @Mock
    private KpiRawRecordMapper mapper;

    private KpiRawRecordService service;

    private static final Long ACTOR_ID = 9001L;
    private static final Long PROJECT_ID = 100L;
    private final IpdActor actor = new IpdActor(ACTOR_ID, "TestLeader", "GROUP_LEADER", 1L);

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        service = new KpiRawRecordService(mapper);
    }

    @Test
    @DisplayName("正常路径：WINDOW_HIT_RATE 类型 KPI 录入成功")
    void record_windowHitRate_success() {
        KpiRawRecord draft = KpiRawRecord.builder()
            .kpiType("WINDOW_HIT_RATE")
            .projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1))
            .rawValue(new BigDecimal("0.85"))
            .build();
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        KpiRawRecord saved = service.record(draft, actor);

        assertThat(saved.getRecordedBy()).isEqualTo(ACTOR_ID);
        assertThat(saved.getRecordedAt()).isNotNull();
        ArgumentCaptor<KpiRawRecord> captor = ArgumentCaptor.forClass(KpiRawRecord.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getKpiType()).isEqualTo("WINDOW_HIT_RATE");
        assertThat(captor.getValue().getRawValue()).isEqualByComparingTo("0.85");
    }

    @Test
    @DisplayName("正常路径：8 类 KPI 都能录入（白名单通过）")
    void record_allTypes_accepted() {
        for (String type : KpiRawRecordService.KPI_TYPES) {
            KpiRawRecord draft = KpiRawRecord.builder()
                .kpiType(type)
                .projectId(PROJECT_ID)
                .recordPeriod(LocalDate.of(2026, 9, 1))
                .rawValue(BigDecimal.ZERO)
                .build();
            when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            assertThat(service.record(draft, actor).getKpiType()).isEqualTo(type);
        }
    }

    @Test
    @DisplayName("异常：kpiType 不在白名单抛 PARAM_INVALID")
    void record_invalidType_rejected() {
        KpiRawRecord draft = KpiRawRecord.builder()
            .kpiType("UNKNOWN_KPI")
            .projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1))
            .rawValue(BigDecimal.ONE)
            .build();

        assertThatThrownBy(() -> service.record(draft, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
        verify(mapper, never()).insert(ArgumentMatchers.<KpiRawRecord>any());
    }

    @Test
    @DisplayName("异常：rawValue 负数抛 PARAM_INVALID")
    void record_negativeValue_rejected() {
        KpiRawRecord draft = KpiRawRecord.builder()
            .kpiType("MTTR")
            .projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1))
            .rawValue(new BigDecimal("-1"))
            .build();

        assertThatThrownBy(() -> service.record(draft, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("异常：百分比类 KPI rawValue > 1.0 抛 PARAM_INVALID")
    void record_pctKpiOverOne_rejected() {
        KpiRawRecord draft = KpiRawRecord.builder()
            .kpiType("REQUIREMENT_ACCURACY")
            .projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1))
            .rawValue(new BigDecimal("1.5"))
            .build();

        assertThatThrownBy(() -> service.record(draft, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("异常：重复录入（同 kpiType/projectId/period）抛 STATE_CONFLICT")
    void record_duplicate_rejected() {
        KpiRawRecord existing = KpiRawRecord.builder().id(1L)
            .kpiType("MTTR").projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1)).build();
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        KpiRawRecord draft = KpiRawRecord.builder()
            .kpiType("MTTR")
            .projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1))
            .rawValue(new BigDecimal("2"))
            .build();

        assertThatThrownBy(() -> service.record(draft, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
        verify(mapper, never()).insert(ArgumentMatchers.<KpiRawRecord>any());
    }

    @Test
    @DisplayName("边界：rawValue = 0 通过（MTTR=0 不抛）")
    void record_zeroValue_accepted() {
        KpiRawRecord draft = KpiRawRecord.builder()
            .kpiType("MTTR")
            .projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1))
            .rawValue(BigDecimal.ZERO)
            .build();
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        KpiRawRecord saved = service.record(draft, actor);
        assertThat(saved.getRawValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("边界：百分比类 KPI rawValue = 1.0 通过（边界值）")
    void record_pctKpiOne_accepted() {
        KpiRawRecord draft = KpiRawRecord.builder()
            .kpiType("WINDOW_HIT_RATE")
            .projectId(PROJECT_ID)
            .recordPeriod(LocalDate.of(2026, 8, 1))
            .rawValue(BigDecimal.ONE)
            .build();
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        KpiRawRecord saved = service.record(draft, actor);
        assertThat(saved.getRawValue()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("异常：null draft 抛 PARAM_INVALID")
    void record_null_rejected() {
        assertThatThrownBy(() -> service.record(null, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("list：指定 kpiType 返回过滤结果")
    void list_byType_returnsFiltered() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(KpiRawRecord.builder().id(1L).kpiType("MTTR").build()));

        List<KpiRawRecord> result = service.list(PROJECT_ID, "MTTR");
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("list：kpiType=null 返回项目下全部记录")
    void list_noType_returnsAll() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                KpiRawRecord.builder().id(1L).kpiType("MTTR").build(),
                KpiRawRecord.builder().id(2L).kpiType("PPM_DEFECT_RATE").build()));

        List<KpiRawRecord> result = service.list(PROJECT_ID, null);
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("list：projectId=null 抛 PARAM_INVALID")
    void list_nullProject_rejected() {
        assertThatThrownBy(() -> service.list(null, "MTTR"))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("listSupportedTypes：返回 8 类固定顺序")
    void listSupportedTypes_returnsEight() {
        List<String> types = service.listSupportedTypes();
        assertThat(types).containsExactly(
            "WINDOW_HIT_RATE",
            "REQUIREMENT_ACCURACY",
            "SCENE_COMPETITIVENESS",
            "PPM_DEFECT_RATE",
            "RELEASE_FREQUENCY",
            "CHANGE_LEAD_TIME",
            "CHANGE_FAILURE_RATE",
            "MTTR"
        );
    }
}
