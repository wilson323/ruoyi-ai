/**
 * GateMaterialController 安全守卫测试（[SEC-FIX] 2026-09-06）。
 * - projectId 与 gateId 不匹配 → 抛 50001 RESOURCE_NOT_FOUND
 * - gateId 不存在 → 同样抛
 * - 关联通过 → 调 service
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
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.GateMaterialChecker;
import org.ruoyi.ipd.service.GateMaterialUploadService;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateMaterialControllerSecurityTest {

    @Mock private GateMaterialChecker checker;
    @Mock private GateMapper gateMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private IpdPermission ipdPermission;
    @Mock private GateMaterialUploadService gateMaterialUploadService;
    @Mock private MultipartFile file;
    @InjectMocks private GateMaterialController controller;

    @Test
    @DisplayName("[SEC-FIX] gateId 不存在 → 抛 RESOURCE_NOT_FOUND，不调 service")
    void gateNotFound() {
        when(gateMapper.selectById(1L)).thenReturn(null);
        assertThatThrownBy(() -> controller.materials(1L, 100L))
            .isInstanceOf(IpdBusinessException.class);
        verify(checker, never()).listMaterialStatus(any(), any());
    }

    @Test
    @DisplayName("[SEC-FIX] projectId 与 Gate.projectId 不匹配 → 抛 RESOURCE_NOT_FOUND")
    void projectIdMismatch() {
        Gate gate = new Gate();
        gate.setId(1L);
        gate.setProjectId(999L);
        when(gateMapper.selectById(1L)).thenReturn(gate);
        assertThatThrownBy(() -> controller.materials(1L, 100L))
            .isInstanceOf(IpdBusinessException.class);
        verify(checker, never()).listMaterialStatus(any(), any());
    }

    @Test
    @DisplayName("[SEC-FIX] gateId 与 projectId 匹配 → 正常调 service")
    void happyPath() {
        Gate gate = new Gate();
        gate.setId(1L);
        gate.setProjectId(100L);
        when(gateMapper.selectById(1L)).thenReturn(gate);
        when(checker.listMaterialStatus(1L, 100L)).thenReturn(Map.of("isReady", true));
        assertThat(controller.materials(1L, 100L).getData().get("isReady")).isEqualTo(true);
        verify(checker, times(1)).listMaterialStatus(1L, 100L);
    }

    // ==================== upload 写口归属校验（横向越权防护） ====================

    @Test
    @DisplayName("[SEC-FIX] upload：操作人组 != Gate 所属项目主组 → FORBIDDEN，不调 upload service")
    void upload_crossGroup_forbidden() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(1L, "研发PM", "RD_PM", 777001L));
        Gate gate = new Gate();
        gate.setId(1L);
        gate.setProjectId(100L);
        when(gateMapper.selectById(1L)).thenReturn(gate);
        Project project = new Project();
        project.setId(100L);
        project.setMainGroupId(999999L);   // 与 actor.groupId 不一致
        when(projectMapper.selectById(100L)).thenReturn(project);

        assertThatThrownBy(() -> controller.upload(1L, file))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN))
            .hasMessageContaining("无权操作");
        verify(gateMaterialUploadService, never()).upload(any(), any());
    }

    @Test
    @DisplayName("[SEC-FIX] upload：Gate 不存在 → 统一 FORBIDDEN，不泄漏存在性")
    void upload_gateNotFound_forbidden() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(1L, "研发PM", "RD_PM", 777001L));
        when(gateMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> controller.upload(1L, file))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN))
            .hasMessageContaining("无权操作");
        verify(gateMaterialUploadService, never()).upload(any(), any());
    }

    @Test
    @DisplayName("[SEC-FIX] upload：同组 → 正常调 upload service（回归）")
    void upload_sameGroup_ok() {
        when(ipdPermission.requireInternal()).thenReturn(new IpdActor(1L, "研发PM", "RD_PM", 777001L));
        Gate gate = new Gate();
        gate.setId(1L);
        gate.setProjectId(100L);
        when(gateMapper.selectById(1L)).thenReturn(gate);
        Project project = new Project();
        project.setId(100L);
        project.setMainGroupId(777001L);   // 与 actor.groupId 一致
        when(projectMapper.selectById(100L)).thenReturn(project);
        when(gateMaterialUploadService.upload(eq(1L), any())).thenReturn(Map.of("ossId", "oss-1"));

        assertThat(controller.upload(1L, file).getData()).containsEntry("ossId", "oss-1");
        verify(gateMaterialUploadService, times(1)).upload(eq(1L), any());
    }
}
