package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdSubStage;
import org.ruoyi.ipd.mapper.IpdSubStageMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class IpdSubStageServiceTest {

    private IpdSubStageMapper mapper;
    private IpdSubStageService service;

    @BeforeEach
    void setUp() {
        mapper = mock(IpdSubStageMapper.class);
        service = new IpdSubStageService(mapper);
    }

    private static IpdSubStage sub(long id, String code, String name, String stage, int sort,
                                   String isGate, String gateCode, String isResident) {
        return IpdSubStage.builder().id(id).code(code).name(name).stageCode(stage)
            .sortOrder(sort).isGate(isGate).gateCode(gateCode).isResident(isResident)
            .ownerRole("MARKET_PM").skillHint("pm-toolkit").build();
    }

    @Test
    @DisplayName("listAll 按大阶段顺序 + 阶段内 sort 升序（乱序输入），KPI 常驻恒最后")
    void listAllSortsByStageRankThenSortOrder() {
        when(mapper.selectList(any())).thenReturn(List.of(
            sub(22L, "KPI-S1", "共担KPI归集（常驻）", "KPI", 99, "0", null, "1"),
            sub(2L, "CONCEPT-S2", "竞争与客群", "CONCEPT", 2, "0", null, "0"),
            sub(5L, "PLAN-S1", "需求定义", "PLAN", 1, "0", null, "0"),
            sub(1L, "CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0", null, "0")));

        List<IpdSubStage> out = service.listAll();

        assertThat(out).extracting(IpdSubStage::getCode)
            .containsExactly("CONCEPT-S1", "CONCEPT-S2", "PLAN-S1", "KPI-S1");
    }

    @Test
    @DisplayName("stageRank：未知大阶段排最后（权重最大），不抛错")
    void stageRankUnknownStageLast() {
        assertThat(IpdSubStageService.stageRank("CONCEPT")).isEqualTo(0);
        assertThat(IpdSubStageService.stageRank("KPI")).isEqualTo(6);
        assertThat(IpdSubStageService.stageRank("UNKNOWN"))
            .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("getByCode 命中返回目录行")
    void getByCodeHit() {
        when(mapper.selectOne(any())).thenReturn(
            sub(1L, "CONCEPT-S1", "市场洞察", "CONCEPT", 1, "0", null, "0"));
        IpdSubStage row = service.getByCode("CONCEPT-S1");
        assertThat(row.getName()).isEqualTo("市场洞察");
    }

    @Test
    @DisplayName("getByCode 未命中抛 50001 NOT_FOUND（fail-loud，不返回 null）")
    void getByCodeMissThrowsNotFound() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.getByCode("NOPE-S1"))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND);
                assertThat(ex.getMessage()).contains("NOPE-S1");
            });
    }
}
