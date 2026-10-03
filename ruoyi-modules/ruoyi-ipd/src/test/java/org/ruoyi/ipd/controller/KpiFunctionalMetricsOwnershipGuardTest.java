/**
 * KpiFunctionalMetricsController 写口归属校验（横向越权防护）负例测试。
 *
 * <p>背景：upsert 的 {@code projectId} 与 delete 的 {@code id} 此前都直接落库，服务链
 * （KpiFunctionalMetricsService）签名里根本没有 actor 参数——控制器类注释承诺的
 * 「对象级校验由 service 二次兜底」并不存在。KpiFunctionalMetric 唯一归属字段是 projectId，
 * 组归属需经 {@code projectId → Project.mainGroupId} 二级解析。
 */
package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiFunctionalMetric;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.dto.UpsertKpiFunctionalMetricReq;
import org.ruoyi.ipd.mapper.KpiFunctionalMetricMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.KpiFunctionalMetricsService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiFunctionalMetricsOwnershipGuardTest {

    private static final Long ACTOR_GROUP = 777001L;
    private static final Long OWNER_GROUP = 999999L;
    private static final Long PROJECT_ID = 100L;
    private static final Long METRIC_ID = 5501L;

    @Mock private IpdPermission ipdPermission;
    @Mock private KpiFunctionalMetricsService kpiFunctionalMetricsService;
    @Mock private KpiFunctionalMetricMapper kpiFunctionalMetricMapper;
    @Mock private ProjectMapper projectMapper;

    @InjectMocks private KpiFunctionalMetricsController controller;

    private IpdActor attacker() {
        return new IpdActor(1L, "研发PM", "RD_PM", ACTOR_GROUP);
    }

    private Project foreignProject() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(OWNER_GROUP);
        return p;
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN))
            .hasMessageContaining("无权操作");
    }

    private static UpsertKpiFunctionalMetricReq req() {
        return new UpsertKpiFunctionalMetricReq(PROJECT_ID, "F-01", "2026-10",
            java.math.BigDecimal.ONE, java.math.BigDecimal.TEN, "v1", "备注");
    }

    @Test
    @DisplayName("upsert 指向他人项目 → FORBIDDEN，且不落库")
    void upsert_crossGroup() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(foreignProject());

        assertForbidden(() -> controller.upsert(req()));
        verify(kpiFunctionalMetricsService, never()).upsert(any());
    }

    @Test
    @DisplayName("upsert 项目不存在 → 统一 FORBIDDEN，不泄漏存在性")
    void upsert_projectNotFound() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertForbidden(() -> controller.upsert(req()));
        verify(kpiFunctionalMetricsService, never()).upsert(any());
    }

    @Test
    @DisplayName("delete 他人项目的量表记录 → FORBIDDEN，且不软删")
    void delete_crossGroup() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        KpiFunctionalMetric existing = new KpiFunctionalMetric();
        existing.setId(METRIC_ID);
        existing.setProjectId(PROJECT_ID);
        when(kpiFunctionalMetricMapper.selectById(METRIC_ID)).thenReturn(existing);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(foreignProject());

        assertForbidden(() -> controller.delete(METRIC_ID));
        verify(kpiFunctionalMetricsService, never()).delete(any());
    }

    @Test
    @DisplayName("delete 记录不存在 → 统一 FORBIDDEN，不调 service")
    void delete_notFound_notLeaked() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(kpiFunctionalMetricMapper.selectById(METRIC_ID)).thenReturn(null);

        assertForbidden(() -> controller.delete(METRIC_ID));
        verify(kpiFunctionalMetricsService, never()).delete(any());
    }

    @Test
    @DisplayName("upsert 同组 → 正常落库（回归）")
    void upsert_sameGroup_ok() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(ACTOR_GROUP);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        KpiFunctionalMetric saved = new KpiFunctionalMetric();
        saved.setId(METRIC_ID);
        when(kpiFunctionalMetricsService.upsert(any())).thenReturn(saved);

        assertThat(controller.upsert(req()).getData()).isNotNull();
        verify(kpiFunctionalMetricsService).upsert(any());
    }

    @Test
    @DisplayName("delete 同组 → 正常软删（回归）")
    void delete_sameGroup_ok() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        KpiFunctionalMetric existing = new KpiFunctionalMetric();
        existing.setId(METRIC_ID);
        existing.setProjectId(PROJECT_ID);
        when(kpiFunctionalMetricMapper.selectById(METRIC_ID)).thenReturn(existing);
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(ACTOR_GROUP);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);

        assertThat(controller.delete(METRIC_ID).getData()).isNull();
        verify(kpiFunctionalMetricsService).delete(METRIC_ID);
    }

    // ==================== list 读口归属校验（横向越权读取） ====================
    // 读口不能照抄写口：写口防的是「不能写进别人项目」，读口防的是「看不到别人项目的量表」。
    // 断言口径是「跨组一条都拿不到」，不是「不能调」——同组必须仍能正常查。

    @Test
    @DisplayName("list 拉他人项目的量表 → FORBIDDEN，返回空列表，绝不漏一行")
    void list_crossGroup_noRowsLeaked() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(foreignProject());

        assertForbidden(() -> controller.list(PROJECT_ID, null));
        // 关键：service 一次都不许被调到——半截结果比报错更容易被下游当数据用
        verify(kpiFunctionalMetricsService, never()).listByProject(any(), any());
    }

    @Test
    @DisplayName("list 跨组即使带 metricCode 过滤也拿不到（防「窄化查询」绕过）")
    void list_crossGroup_withMetricCode_stillForbidden() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(foreignProject());

        assertForbidden(() -> controller.list(PROJECT_ID, "F-01"));
        verify(kpiFunctionalMetricsService, never()).listByProject(any(), any());
    }

    @Test
    @DisplayName("list 项目不存在 → 统一 FORBIDDEN，不泄漏存在性（读口 fail-closed）")
    void list_projectNotFound_noExistenceLeak() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertForbidden(() -> controller.list(PROJECT_ID, null));
        verify(kpiFunctionalMetricsService, never()).listByProject(any(), any());
    }

    @Test
    @DisplayName("list 同组 → 正常返回（回归：读口不能把本组数据也一起拒掉）")
    void list_sameGroup_ok() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(ACTOR_GROUP);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        KpiFunctionalMetric row = new KpiFunctionalMetric();
        row.setId(METRIC_ID);
        when(kpiFunctionalMetricsService.listByProject(PROJECT_ID, null)).thenReturn(List.of(row));

        assertThat(controller.list(PROJECT_ID, null).getData()).hasSize(1);
        verify(kpiFunctionalMetricsService).listByProject(PROJECT_ID, null);
    }
}
