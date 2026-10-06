package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.IpdActionSkillMap;
import org.ruoyi.ipd.mapper.IpdActionSkillMapMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class IpdActionSkillMapServiceTest {

    private IpdActionSkillMapMapper mapper;
    private IpdActionSkillMapService service;

    @BeforeEach
    void setUp() {
        // getSqlSegment 断言依赖 lambda cache（同 IpdActionWriterMemberTest 模式）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "skill-map"), IpdActionSkillMap.class);
        mapper = mock(IpdActionSkillMapMapper.class);
        service = new IpdActionSkillMapService(mapper);
    }

    private static IpdActionSkillMap map(long id, String code, String subStage, int sort, String skills) {
        return IpdActionSkillMap.builder().id(id).actionCode(code).subStageCode(subStage)
            .sortOrder(sort).skillNames(skills).remark("待 §3 定稿后补齐 skill_names").build();
    }

    @Test
    @DisplayName("listAll 按 subStageCode + sort_order 升序（乱序输入）")
    void listAllSortsBySubStageThenSort() {
        when(mapper.selectList(any())).thenReturn(List.of(
            map(2L, "C02", "CONCEPT-S2", 1, null),
            map(3L, "C04", "CONCEPT-S1", 2, null),
            map(1L, "C01", "CONCEPT-S1", 1, "[\"interview-script\"]")));

        List<IpdActionSkillMap> out = service.listAll();

        assertThat(out).extracting(IpdActionSkillMap::getActionCode)
            .containsExactly("C01", "C04", "C02");
    }

    @Test
    @DisplayName("parseSkillNames：NULL 与空串 → 空列表（§3 未定稿口径，不回 null）")
    void parseSkillNamesNullAndBlank() {
        assertThat(IpdActionSkillMapService.parseSkillNames(null)).isEmpty();
        assertThat(IpdActionSkillMapService.parseSkillNames("")).isEmpty();
        assertThat(IpdActionSkillMapService.parseSkillNames("  ")).isEmpty();
    }

    @Test
    @DisplayName("parseSkillNames：JSON 数组文本 → 按序列表")
    void parseSkillNamesValidArray() {
        assertThat(IpdActionSkillMapService.parseSkillNames("[\"interview-script\",\"market-sizing\"]"))
            .containsExactly("interview-script", "market-sizing");
        assertThat(IpdActionSkillMapService.parseSkillNames("[]")).isEmpty();
    }

    @Test
    @DisplayName("目录读面只收全局默认行：listAll/listBySubStage 查询条件携带 project_id=0")
    void catalogReadsOnlyGlobalRows() {
        when(mapper.selectList(any())).thenReturn(List.of());
        service.listAll();
        service.listBySubStage("CONCEPT-S1");
        var captor = org.mockito.ArgumentCaptor.forClass(
            (Class<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IpdActionSkillMap>>) (Class<?>) com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).selectList(captor.capture());
        for (var wrapper : captor.getAllValues()) {
            assertThat(wrapper.getSqlSegment()).contains("project_id");
            assertThat(wrapper.getParamNameValuePairs().values()).contains(0L);
        }
    }

    @Test
    @DisplayName("resolve：项目级绑定优先于全局默认")
    void resolvePrefersProjectLevelBinding() {
        IpdActionSkillMap projectRow = map(11L, "C02", "CONCEPT-S2", 1, "[\"project-skill\"]");
        projectRow.setProjectId(777L);
        IpdActionSkillMap globalRow = map(12L, "C02", "CONCEPT-S2", 1, "[\"global-skill\"]");
        globalRow.setProjectId(0L);
        when(mapper.selectList(any())).thenReturn(List.of(globalRow, projectRow));
        assertThat(service.resolve("C02", 777L)).isSameAs(projectRow);
    }

    @Test
    @DisplayName("resolve：无项目级绑定回退全局；未定稿行（skill_names NULL）不算绑定不拦截回退")
    void resolveFallsBackToGlobalAndSkipsUndefinedRows() {
        IpdActionSkillMap undefinedProjectRow = map(11L, "C02", "CONCEPT-S2", 1, null);
        undefinedProjectRow.setProjectId(777L);
        IpdActionSkillMap globalRow = map(12L, "C02", "CONCEPT-S2", 1, "[\"global-skill\"]");
        globalRow.setProjectId(0L);
        when(mapper.selectList(any())).thenReturn(List.of(undefinedProjectRow, globalRow));
        assertThat(service.resolve("C02", 777L)).isSameAs(globalRow);
        when(mapper.selectList(any())).thenReturn(List.of(undefinedProjectRow));
        assertThat(service.resolve("C02", 777L)).isNull();
    }

    @Test
    @DisplayName("resolve：projectId null/≤0 只查全局；空码返回 null")
    void resolveNullProjectAndBlankCode() {
        IpdActionSkillMap globalRow = map(12L, "C02", "CONCEPT-S2", 1, "[\"global-skill\"]");
        globalRow.setProjectId(0L);
        when(mapper.selectList(any())).thenReturn(List.of(globalRow));
        assertThat(service.resolve("C02", null)).isSameAs(globalRow);
        assertThat(service.resolve("C02", 0L)).isSameAs(globalRow);
        assertThat(service.resolve(null, 777L)).isNull();
        assertThat(service.resolve("  ", 777L)).isNull();
    }

    @Test
    @DisplayName("parseSkillNames：JSON 非法抛 90001（fail-loud，不静默吞坏数据）")
    void parseSkillNamesInvalidJsonFailsLoud() {
        assertThatThrownBy(() -> IpdActionSkillMapService.parseSkillNames("{\"bad\""))
            .isInstanceOfSatisfying(IpdBusinessException.class, ex -> {
                assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR);
                assertThat(ex.getMessage()).contains("skill_names JSON 非法");
            });
    }
}
