package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class SubStageGateServiceTest {

    private IpdSubStageService subStageService;
    private IpdActionSkillMapService skillMapService;
    private StageActionMapper stageActionMapper;
    private SubStageGateService service;

    @BeforeEach
    void setUp() {
        subStageService = mock(IpdSubStageService.class);
        skillMapService = mock(IpdActionSkillMapService.class);
        stageActionMapper = mock(StageActionMapper.class);
        service = new SubStageGateService(subStageService, skillMapService, stageActionMapper);
    }

    /** 目录夹具：CONCEPT-S1..S4 + PLAN-S1 + 常驻 KPI-S1（字段值与 §6.4.2 seed 一致）。 */
    private static List<IpdSubStage> catalog() {
        return List.of(
            sub("CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0", null, "0"),
            sub("CONCEPT-S2", "竞争与客群", "CONCEPT", 2, "0", null, "0"),
            sub("CONCEPT-S3", "商业论证", "CONCEPT", 3, "0", null, "0"),
            sub("CONCEPT-S4", "合规与立项", "CONCEPT", 4, "1", "G1", "0"),
            sub("PLAN-S1", "需求定义", "PLAN", 1, "0", null, "0"),
            sub("KPI-S1", "共担KPI归集（常驻）", "KPI", 99, "0", null, "1"));
    }

    private static IpdSubStage sub(String code, String name, String stage, int sort,
                                   String isGate, String gateCode, String isResident) {
        return IpdSubStage.builder().code(code).name(name).stageCode(stage).sortOrder(sort)
            .isGate(isGate).gateCode(gateCode).isResident(isResident).ownerRole("MARKET_PM").build();
    }

    private static IpdActionSkillMap map(String actionCode, String subStageCode, int sort) {
        return IpdActionSkillMap.builder().actionCode(actionCode).subStageCode(subStageCode)
            .sortOrder(sort).skillNames(null).build();
    }

    /** 实例夹具：名称取 ActionCatalog 真名，深度/阻断/BioCV 与目录一致（真库可产生组合）。 */
    private static StageAction action(String code, String depth, String isBlocking,
                                      String status, String historyMark, String isBio) {
        return StageAction.builder().projectId(1001L).actionCode(code)
            .actionName(ActionCatalog.byCode(code).name())
            .ownerRole(ActionCatalog.byCode(code).ownerRole())
            .depth(depth).status(status).confirmedBy("DONE".equals(status) ? 9002L : null)
            .historyMark(historyMark)
            .isBlocking(isBlocking).isBioFeature(isBio).build();
    }

    @Test
    @DisplayName("不变量④放行：前序阻断动作全清（已批准DONE/NA）→ 不抛")
    void advanceAllowedWhenAllBlockingSettled() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2),
            map("C02", "CONCEPT-S2", 1), map("C03", "CONCEPT-S2", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "DONE", null, "0"),
            action("C04", "DEEP", "1", "NA", null, "0")));

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("完成只是提交：未经负责人批准，新建与存量项目均不能推进")
    void submittedActionBlocksUntilApprovedAndResubmissionClearsAcceptance() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(map("C01", "CONCEPT-S1", 1)));
        StageAction submitted = action("C01", "DEEP", "1", "DONE", null, "0");
        submitted.setConfirmedBy(null);
        when(stageActionMapper.selectList(any())).thenReturn(List.of(submitted));

        for (boolean requireRows : List.of(false, true)) {
            assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2", requireRows))
                .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                    assertThat(ex.getMessage()).contains("C01(待产线负责人批准)");
                });
            submitted.setConfirmedBy(9002L);
            assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2", requireRows))
                .doesNotThrowAnyException();
            // 重新提交会清掉批准人；门禁必须重新等待批准，不能沿用此前 DONE。
            submitted.setConfirmedBy(null);
            assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2", requireRows))
                .isInstanceOf(IpdBusinessException.class)
                .hasMessageContaining("待产线负责人批准");
        }
    }

    @Test
    @DisplayName("不变量④拦截：前序阻断动作 NOT_STARTED → 40001 且 message 点名动作码")
    void advanceBlockedByNotStartedBlockingAction() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "NOT_STARTED", null, "0"),
            action("C04", "DEEP", "1", "DONE", null, "0")));

        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                assertThat(ex.getMessage()).contains("C01");
            });
    }

    @Test
    @DisplayName("不变量④拦截：跨多小阶段点名全部待完成码（C12），非阻断码不入 message")
    void pendingListsAllBlockingCodesAcrossSubStages() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2),
            map("C02", "CONCEPT-S2", 1), map("C03", "CONCEPT-S2", 2),
            map("C06", "CONCEPT-S3", 1), map("C07", "CONCEPT-S3", 2),
            map("C08", "CONCEPT-S3", 3), map("C09", "CONCEPT-S3", 4),
            map("C05", "CONCEPT-S4", 1), map("C10", "CONCEPT-S4", 2),
            map("C12", "CONCEPT-S4", 3), map("C11", "CONCEPT-S4", 4)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "DONE", null, "0"),
            action("C04", "DEEP", "1", "DONE", null, "0"),
            action("C02", "DEEP", "1", "DONE", null, "0"),
            action("C03", "DEEP", "1", "DONE", null, "0"),
            action("C06", "DEEP", "1", "DONE", null, "0"),
            action("C07", "DEEP", "1", "DONE", null, "0"),
            action("C08", "DEEP", "1", "DONE", null, "0"),
            action("C09", "DEEP", "1", "DONE", null, "0"),
            action("C05", "LIGHT", "0", "NOT_STARTED", null, "0"),
            action("C10", "DEEP", "0", "NOT_STARTED", null, "0"),
            action("C12", "DEEP", "1", "NOT_STARTED", null, "1"),
            action("C11", "DEEP", "1", "NOT_STARTED", "HISTORICAL_MISSING", "0")));

        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "PLAN-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
                assertThat(ex.getMessage()).contains("C12");
                assertThat(ex.getMessage()).doesNotContain("C05").doesNotContain("C10").doesNotContain("C11");
            });
    }

    @Test
    @DisplayName("NA 与 HISTORICAL_MISSING 均视为已满足（历史缺失不伪造 DONE）")
    void naAndHistoricalMissingSettleGate() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "NA", null, "0"),
            action("C04", "DEEP", "1", "NOT_STARTED", "HISTORICAL_MISSING", "0")));

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("非阻断动作未完成不拦截（C05/C10 均 LIGHT/DEEP 非阻断）")
    void nonBlockingActionsDoNotBlock() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2),
            map("C02", "CONCEPT-S2", 1), map("C03", "CONCEPT-S2", 2),
            map("C06", "CONCEPT-S3", 1), map("C07", "CONCEPT-S3", 2),
            map("C08", "CONCEPT-S3", 3), map("C09", "CONCEPT-S3", 4),
            map("C05", "CONCEPT-S4", 1), map("C10", "CONCEPT-S4", 2),
            map("C12", "CONCEPT-S4", 3), map("C11", "CONCEPT-S4", 4)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of(
            action("C01", "DEEP", "1", "DONE", null, "0"),
            action("C04", "DEEP", "1", "DONE", null, "0"),
            action("C02", "DEEP", "1", "DONE", null, "0"),
            action("C03", "DEEP", "1", "DONE", null, "0"),
            action("C06", "DEEP", "1", "DONE", null, "0"),
            action("C07", "DEEP", "1", "DONE", null, "0"),
            action("C08", "DEEP", "1", "DONE", null, "0"),
            action("C09", "DEEP", "1", "DONE", null, "0"),
            action("C05", "LIGHT", "0", "NOT_STARTED", null, "0"),
            action("C10", "DEEP", "0", "NOT_STARTED", null, "0"),
            action("C12", "DEEP", "1", "DONE", null, "1"),
            action("C11", "DEEP", "1", "DONE", null, "0")));

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "PLAN-S1"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("实例缺行跳过（历史项目未全量实例化不拦，不造伪数据）")
    void missingInstanceRowsAreSkipped() {
        when(subStageService.listAll()).thenReturn(catalog());
        when(skillMapService.listAll()).thenReturn(List.of(
            map("C01", "CONCEPT-S1", 1), map("C04", "CONCEPT-S1", 2)));
        when(stageActionMapper.selectList(any())).thenReturn(List.of());

        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S2"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("目标为首个小阶段无前序，直接放行（不查实例）")
    void firstSubStageHasNoPredecessors() {
        when(subStageService.listAll()).thenReturn(catalog());
        assertThatCode(() -> service.assertAdvanceAllowed(1001L, "CONCEPT-S1"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("常驻小阶段与未知码不可作为推进目标（10001 PARAM_INVALID）")
    void residentAndUnknownTargetRejected() {
        when(subStageService.listAll()).thenReturn(catalog());
        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "KPI-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        assertThatThrownBy(() -> service.assertAdvanceAllowed(1001L, "NOPE-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
    }
}
