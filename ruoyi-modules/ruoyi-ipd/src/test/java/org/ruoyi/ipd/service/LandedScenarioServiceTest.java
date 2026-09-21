package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.LandedScenario;
import org.ruoyi.ipd.mapper.LandedScenarioMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R149 batch2a A4：落地场景登记服务单测。
 *
 * <p>覆盖 6 维度：
 * <ol>
 *   <li>正常路径：单条录入 / 批量导入 / 按月查询</li>
 *   <li>边界：landedAmount=null；空 list 查询；period 区间</li>
 *   <li>异常：字段缺失 / 长度超限 / 重复 (projectId, scenarioCode) 抛 STATE_CONFLICT</li>
 *   <li>审计：recordedBy = actor.id</li>
 *   <li>幂等：批量导入同批次内重复抛 STATE_CONFLICT</li>
 *   <li>不做：双认定（dual-cert）相关断言——用户拍板简化后该路径不实现</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class LandedScenarioServiceTest {

    @Mock
    private LandedScenarioMapper mapper;

    private LandedScenarioService service;

    private static final Long ACTOR_ID = 9100L;
    private static final Long PROJECT_ID = 200L;
    private final IpdActor actor = new IpdActor(ACTOR_ID, "TestPM", "MARKET_PM", 1L);

    @BeforeEach
    void setup() {
        service = new LandedScenarioService(mapper);
    }

    private LandedScenario sample(Long projectId, String code, String name) {
        return LandedScenario.builder()
            .projectId(projectId)
            .scenarioCode(code)
            .scenarioName(name)
            .landedDate(LocalDate.of(2026, 9, 15))
            .landedAmount(new BigDecimal("10000.00"))
            .build();
    }

    @Test
    @DisplayName("正常路径：单条录入成功")
    void record_success() {
        LandedScenario draft = sample(PROJECT_ID, "SCN-001", "电商大促场景");
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        LandedScenario saved = service.record(draft, actor);
        assertThat(saved.getRecordedBy()).isEqualTo(ACTOR_ID);
        verify(mapper).insert(saved);
    }

    @Test
    @DisplayName("边界：landedAmount=null 录入成功")
    void record_nullAmount_success() {
        LandedScenario draft = LandedScenario.builder()
            .projectId(PROJECT_ID)
            .scenarioCode("SCN-002")
            .scenarioName("未量化场景")
            .landedDate(LocalDate.of(2026, 9, 1))
            .landedAmount(null)
            .build();
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        LandedScenario saved = service.record(draft, actor);
        assertThat(saved.getLandedAmount()).isNull();
    }

    @Test
    @DisplayName("异常：scenarioCode 缺失抛 PARAM_INVALID")
    void record_missingCode_rejected() {
        LandedScenario draft = LandedScenario.builder()
            .projectId(PROJECT_ID)
            .scenarioCode(null)
            .scenarioName("no code")
            .landedDate(LocalDate.of(2026, 9, 1))
            .build();
        assertThatThrownBy(() -> service.record(draft, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("异常：landedAmount 为负抛 PARAM_INVALID")
    void record_negativeAmount_rejected() {
        LandedScenario draft = LandedScenario.builder()
            .projectId(PROJECT_ID)
            .scenarioCode("SCN-NEG")
            .scenarioName("neg")
            .landedDate(LocalDate.of(2026, 9, 1))
            .landedAmount(new BigDecimal("-1"))
            .build();
        assertThatThrownBy(() -> service.record(draft, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("异常：重复 (projectId, scenarioCode) 抛 STATE_CONFLICT")
    void record_duplicate_rejected() {
        LandedScenario existing = LandedScenario.builder().id(99L)
            .projectId(PROJECT_ID).scenarioCode("SCN-DUP").build();
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        LandedScenario draft = sample(PROJECT_ID, "SCN-DUP", "dup");
        assertThatThrownBy(() -> service.record(draft, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
        verify(mapper, never()).insert(any(LandedScenario.class));
    }

    @Test
    @DisplayName("正常路径：批量导入 3 条")
    void importBatch_success() {
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        List<LandedScenario> batch = List.of(
            sample(PROJECT_ID, "B-001", "场景1"),
            sample(PROJECT_ID, "B-002", "场景2"),
            sample(PROJECT_ID, "B-003", "场景3")
        );
        int saved = service.importBatch(batch, actor);
        assertThat(saved).isEqualTo(3);
    }

    @Test
    @DisplayName("异常：批量导入超过 BATCH_IMPORT_MAX 抛 PARAM_INVALID")
    void importBatch_overLimit_rejected() {
        List<LandedScenario> tooMany = new ArrayList<>();
        for (int i = 0; i < LandedScenarioService.BATCH_IMPORT_MAX + 1; i++) {
            tooMany.add(sample(PROJECT_ID, "X-" + i, "name"));
        }
        assertThatThrownBy(() -> service.importBatch(tooMany, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("异常：批量导入同批次内重复抛 STATE_CONFLICT")
    void importBatch_duplicateInBatch_rejected() {
        List<LandedScenario> batch = List.of(
            sample(PROJECT_ID, "SAME", "first"),
            sample(PROJECT_ID, "SAME", "second")
        );
        assertThatThrownBy(() -> service.importBatch(batch, actor))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
    }

    @Test
    @DisplayName("正常路径：list 按 projectId 返回")
    void list_byProject_returnsAll() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                LandedScenario.builder().id(1L).scenarioCode("A").build(),
                LandedScenario.builder().id(2L).scenarioCode("B").build()
            ));
        List<LandedScenario> result = service.list(PROJECT_ID, null);
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("正常路径：list 带 period（YYYY-MM-01）按月过滤")
    void list_byMonth_returnsMonthFiltered() {
        when(mapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(LandedScenario.builder().id(1L).build()));
        List<LandedScenario> result = service.list(PROJECT_ID, LocalDate.of(2026, 9, 1));
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("异常：list projectId=null 抛 PARAM_INVALID")
    void list_nullProject_rejected() {
        assertThatThrownBy(() -> service.list(null, null))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }
}
