package org.ruoyi.ipd.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R215-P2（看板卡 be31bc06）守卫契约：未分诊（双 PM 未分派）的需求不得关联项目。
 * 缺陷时序：先 link-project 成功（SCHEDULED）后 triage 被拒 → 未分派双 PM 即已挂项目。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DemandLinkProjectTriageGuardTest {

    @Mock private RequirementMapper requirementMapper;
    @Mock private ProductMapper productMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private PersonMapper personMapper;
    @Mock private IpdPermission ipdPermission;

    /** 需求所属产品组（本测试的操作人组与之相同，是所有正向用例的前提）。 */
    private static final Long OWN_GROUP = 77001L;
    /** 需求 fixture 挂的产品 ID。 */
    private static final Long PRODUCT_ID = 33001L;

    private DemandController controller;

    @BeforeAll
    static void initMeta() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "R215P2-test");
        TableInfoHelper.initTableInfo(assistant, Requirement.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        controller = new DemandController(requirementMapper, productMapper, projectMapper, personMapper,
            ipdPermission);
        // 默认桩：会话 actor 属于 OWN_GROUP，且需求所属产品也属于 OWN_GROUP（正向路径）
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(1L, "市场PM", "MARKET_PM", OWN_GROUP));
        Product product = new Product();
        product.setId(PRODUCT_ID);
        product.setGroupId(OWN_GROUP);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(product);
    }

    private static Requirement demand(Long marketPmId, Long rdPmId) {
        Requirement r = new Requirement();
        r.setId(2103330885699985410L);
        r.setProductId(PRODUCT_ID);
        r.setStatus("SUBMITTED");
        r.setMarketPmId(marketPmId);
        r.setRdPmId(rdPmId);
        return r;
    }

    @Test
    @DisplayName("SEC-1 操作人组 != 需求所属产品组 → 403 拒绝且不落库（横向越权防护）")
    void crossGroupLinkProject_rejected() {
        Requirement r = demand(900103L, 900104L);
        when(requirementMapper.selectById(2103330885699985410L)).thenReturn(r);
        // 需求所属产品属于别的组
        Product foreign = new Product();
        foreign.setId(PRODUCT_ID);
        foreign.setGroupId(99999L);
        when(productMapper.selectById(PRODUCT_ID)).thenReturn(foreign);
        // 即使待关联项目存在，越权者也应止步于归属校验
        Project project = new Project();
        project.setId(9140001L);
        lenient().when(projectMapper.selectById(9140001L)).thenReturn(project);

        assertThatThrownBy(() -> controller.linkProject(2103330885699985410L,
                new DemandController.LinkProjectRequest(9140001L)))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN))
            .hasMessageContaining("无权操作");
        verify(requirementMapper, never()).updateById(any(Requirement.class));
    }

    @Test
    @DisplayName("R215-P2.1 双PM均未分派 → 400 拒绝且不落库")
    void bothPmMissing_rejected() {
        when(requirementMapper.selectById(2103330885699985410L)).thenReturn(demand(null, null));

        assertThatThrownBy(() -> controller.linkProject(2103330885699985410L,
                new DemandController.LinkProjectRequest(9140001L)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST))
            .hasMessageContaining("未完成分诊");
        verify(requirementMapper, never()).updateById(any(Requirement.class));
    }

    @Test
    @DisplayName("R215-P2.2 仅市场PM分派（研发PM缺失）→ 400 拒绝")
    void rdPmMissing_rejected() {
        when(requirementMapper.selectById(2103330885699985410L)).thenReturn(demand(900103L, null));

        assertThatThrownBy(() -> controller.linkProject(2103330885699985410L,
                new DemandController.LinkProjectRequest(9140001L)))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST));
        verify(requirementMapper, never()).updateById(any(Requirement.class));
    }

    @Test
    @DisplayName("R215-P2.3 双PM已分派 + ACCEPTED → 关联成功并置 SCHEDULED（回归）")
    void bothPmAssigned_acceptedToScheduled() {
        Requirement r = demand(900103L, 900104L);
        r.setStatus("ACCEPTED");
        when(requirementMapper.selectById(2103330885699985410L)).thenReturn(r);
        Project project = new Project();
        project.setId(9140001L);
        when(projectMapper.selectById(9140001L)).thenReturn(project);

        var resp = controller.linkProject(2103330885699985410L,
            new DemandController.LinkProjectRequest(9140001L));

        assertThat(resp.getData()).containsEntry("projectId", 9140001L).containsEntry("status", "SCHEDULED");
        ArgumentCaptor<Requirement> captor = ArgumentCaptor.forClass(Requirement.class);
        verify(requirementMapper).updateById(captor.capture());
        assertThat(captor.getValue().getProjectId()).isEqualTo(9140001L);
        assertThat(captor.getValue().getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("R215-P2.4 双PM已分派 + SUBMITTED → 仍按既有契约置 SCHEDULED（既有行为回归）")
    void bothPmAssigned_submittedToScheduled() {
        when(requirementMapper.selectById(2103330885699985410L)).thenReturn(demand(900103L, 900104L));
        Project project = new Project();
        project.setId(9140001L);
        when(projectMapper.selectById(9140001L)).thenReturn(project);

        var resp = controller.linkProject(2103330885699985410L,
            new DemandController.LinkProjectRequest(9140001L));

        assertThat(resp.getData()).containsEntry("status", "SCHEDULED");
    }
}
