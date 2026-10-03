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
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.GateElementResultService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GateElementResultController#submit 归属守卫测试。
 *
 * <p><b>为什么这处单独补测</b>：上一轮只给 {@code GateMaterialController.upload} 加了归属校验，
 * 但那只是把材料落到 OSS 侧；<b>真正把材料 URL 挂到评审上、冻结要素快照、置 startedAt/signDueAt、
 * 开启双签队列的是 {@code submit}</b>。只堵 upload 等于只堵了半条路——任意内部角色仍可对
 * 别人项目的 gateId 调 submit 完成整条提交动作。
 *
 * <p>断言口径：
 * <ul>
 *   <li>跨组 → FORBIDDEN，且 service.submit 一次都不许被调（写库前拦住）</li>
 *   <li>Gate/项目/主组任一环缺失 → 统一 FORBIDDEN，不区分「不存在」与「无权」</li>
 *   <li>同组 → 正常提交（守卫不能把本组正常流程一起拒掉）</li>
 *   <li>超管 → 跨组豁免（与 IpdIdorGuard.assertSameGroupIpd 口径一致）</li>
 * </ul>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("Gate submit 归属守卫：提交评审必须归属本组项目")
class GateElementResultSubmitOwnershipTest {

    private static final Long ACTOR_GROUP = 777001L;
    private static final Long OWNER_GROUP = 999999L;
    private static final Long GATE_ID = 11L;
    private static final Long PROJECT_ID = 100L;
    private static final Long OSS_MATERIALS = 501L;
    private static final Long OSS_MINUTES = 502L;

    @Mock private GateElementResultService service;
    @Mock private IpdPermission permission;
    @Mock private GateMapper gateMapper;
    @Mock private ProjectMapper projectMapper;
    @InjectMocks private GateElementResultController controller;

    private IpdActor actor(String role, Long groupId) {
        return new IpdActor(1L, "研发PM", role, groupId);
    }

    private Gate gateOf(Long projectId) {
        Gate g = new Gate();
        g.setId(GATE_ID);
        g.setProjectId(projectId);
        g.setGateCode("G3");
        return g;
    }

    private Project projectOf(Long mainGroupId) {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(mainGroupId);
        return p;
    }

    private GateElementResultController.MandatoryOutputsReq outputs() {
        return new GateElementResultController.MandatoryOutputsReq(OSS_MATERIALS, OSS_MINUTES);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN))
            .hasMessageContaining("无权操作");
    }

    @Test
    @DisplayName("submit 提交他人项目的 Gate → FORBIDDEN，且 service.submit 从未被调用")
    void submit_crossGroup_notReached() {
        when(permission.requireInternal()).thenReturn(actor("RD_PM", ACTOR_GROUP));
        when(gateMapper.selectById(GATE_ID)).thenReturn(gateOf(PROJECT_ID));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectOf(OWNER_GROUP));

        assertForbidden(() -> controller.submit(GATE_ID, outputs()));
        // 承重断言：守卫必须在写库前生效，否则 Gate 已被改写（startedAt/材料 URL/双签队列）
        verify(service, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("submit Gate 不存在 → 统一 FORBIDDEN，不泄漏存在性")
    void submit_gateNotFound_noExistenceLeak() {
        when(permission.requireInternal()).thenReturn(actor("RD_PM", ACTOR_GROUP));
        when(gateMapper.selectById(GATE_ID)).thenReturn(null);

        assertForbidden(() -> controller.submit(GATE_ID, outputs()));
        verify(service, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("submit Gate 未挂项目 → fail-closed 抛 FORBIDDEN（归属链断即拒）")
    void submit_gateWithoutProject_failClosed() {
        when(permission.requireInternal()).thenReturn(actor("RD_PM", ACTOR_GROUP));
        when(gateMapper.selectById(GATE_ID)).thenReturn(gateOf(null));

        assertForbidden(() -> controller.submit(GATE_ID, outputs()));
        verify(service, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("submit 项目不存在 → 统一 FORBIDDEN（不返回 404，不暴露该项目）")
    void submit_projectNotFound_failClosed() {
        when(permission.requireInternal()).thenReturn(actor("RD_PM", ACTOR_GROUP));
        when(gateMapper.selectById(GATE_ID)).thenReturn(gateOf(PROJECT_ID));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertForbidden(() -> controller.submit(GATE_ID, outputs()));
        verify(service, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("submit 项目无主组 → fail-closed 抛 FORBIDDEN（防 null 与 null 误判为同组）")
    void submit_projectWithoutGroup_failClosed() {
        when(permission.requireInternal()).thenReturn(actor("RD_PM", ACTOR_GROUP));
        when(gateMapper.selectById(GATE_ID)).thenReturn(gateOf(PROJECT_ID));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectOf(null));

        assertForbidden(() -> controller.submit(GATE_ID, outputs()));
        verify(service, never()).submit(any(), any(), any(), any());
    }

    @Test
    @DisplayName("submit 同组 → 正常提交（回归：守卫不能把本组正常流程拒掉）")
    void submit_sameGroup_ok() {
        IpdActor sameGroup = actor("RD_PM", ACTOR_GROUP);
        when(permission.requireInternal()).thenReturn(sameGroup);
        when(gateMapper.selectById(GATE_ID)).thenReturn(gateOf(PROJECT_ID));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectOf(ACTOR_GROUP));
        when(service.submit(eq(GATE_ID), eq(OSS_MATERIALS), eq(OSS_MINUTES), eq(sameGroup)))
            .thenReturn(gateOf(PROJECT_ID));

        assertThat(controller.submit(GATE_ID, outputs()).getData()).isNotNull();
        verify(service).submit(GATE_ID, OSS_MATERIALS, OSS_MINUTES, sameGroup);
    }

    @Test
    @DisplayName("submit 超管跨组 → 豁免放行（与 IpdIdorGuard 既有口径一致，不自创第三种）")
    void submit_superAdmin_crossGroup_allowed() {
        IpdActor admin = actor("SUPER_ADMIN", OWNER_GROUP);
        when(permission.requireInternal()).thenReturn(admin);
        when(gateMapper.selectById(GATE_ID)).thenReturn(gateOf(PROJECT_ID));
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(projectOf(OWNER_GROUP));
        when(service.submit(eq(GATE_ID), eq(OSS_MATERIALS), eq(OSS_MINUTES), eq(admin)))
            .thenReturn(gateOf(PROJECT_ID));

        assertThat(controller.submit(GATE_ID, outputs()).getData()).isNotNull();
        verify(service).submit(GATE_ID, OSS_MATERIALS, OSS_MINUTES, admin);
    }
}
