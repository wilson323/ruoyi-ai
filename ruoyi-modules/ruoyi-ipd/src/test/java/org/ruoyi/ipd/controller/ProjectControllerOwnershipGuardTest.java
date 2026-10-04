/**
 * ProjectController 写口归属校验（横向越权防护）负例 + 反向锁测试。
 *
 * <p>背景：{@code addCertItem} / {@code changeCertStatus} / {@code autoCreateGate} /
 * {@code recordLaunchDate} 四个写口原先只调 {@code requireInternal()}（仅证明已登录），
 * 项目 id 由客户端路径变量指定——任何持 {@code ipd:module:project:status-change}
 * 权限码的内部用户都能操作别人项目的认证项 / Gate / 上市日期。
 * 四条豁免原登记在 {@code scripts/ownership-gate-exempt.txt}，理由「能操作本组外数据」。
 *
 * <p><b>为什么断言的是「在职项目成员」（守卫3）而不是「同组」（守卫6）</b>：
 * BR-ORG-06 三层权限矩阵「编辑项目」一行是普通PM「仅本人负责」/ 组长「仅本人名下」，
 * 不含组维；而 BR-ORG-01 定义主组＝市场PM 所在组、协同组＝研发PM 所在组。
 * 认证清单的法定责任人恰是研发PM（六阶段动作清单 P10/V02）。若用「同组」，
 * <b>会把法定责任人 403 挡在门外</b>。故本类第 2 条是<b>反向锁</b>：
 * 「跨组但在职成员 → 必须放行」，防止后来人把守卫改成「同组」。
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
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.dto.ProjectCertManualReq;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.GateCreationService;
import org.ruoyi.ipd.service.IProjectCertService;
import org.ruoyi.ipd.service.LaunchDateChangeService;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ProjectControllerOwnershipGuardTest {

    /** 协同组研发PM 所在组。 */
    private static final Long RD_GROUP = 777001L;
    /** 项目主组（市场PM 所在组）——与 RD_GROUP 不一致，这正是跨组组队的常态。 */
    private static final Long MAIN_GROUP = 999999L;
    private static final Long PROJECT_ID = 100L;

    @Mock private IProjectCertService projectCertService;
    @Mock private GateCreationService gateCreationService;
    @Mock private LaunchDateChangeService launchDateChangeService;
    @Mock private IpdPermission ipdPermission;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;

    @InjectMocks private ProjectController controller;

    private IpdActor rdPm() {
        return new IpdActor(1L, "研发PM", "RD_PM", RD_GROUP);
    }

    private Project project() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(MAIN_GROUP);
        return p;
    }

    private ProjectCertManualReq certReq() {
        return new ProjectCertManualReq("CN", "中国", "CCC", "CNCA", "1");
    }

    private void givenActor(IpdActor actor) {
        when(ipdPermission.requireInternal()).thenReturn(actor);
    }

    /** 非在职成员：项目存在，但成员计数为 0。 */
    private void givenNonMember() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(projectMemberMapper.selectCount(any())).thenReturn(0L);
    }

    /** 在职成员（可跨组）：成员计数为 1。 */
    private void givenActiveMember() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN));
    }

    // ==================== 负例：非成员一律拒，且不触达 service ====================

    @Test
    @DisplayName("addCertItem 非项目成员 → FORBIDDEN，且不调 service.addManual")
    void addCertItem_nonMember() {
        givenActor(rdPm());
        givenNonMember();
        assertForbidden(() -> controller.addCertItem(PROJECT_ID, certReq()));
        verify(projectCertService, never()).addManual(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("changeCertStatus 非项目成员 → FORBIDDEN，且不调 service.changeStatus")
    void changeCertStatus_nonMember() {
        givenActor(rdPm());
        givenNonMember();
        assertForbidden(() -> controller.changeCertStatus(PROJECT_ID, 55L, "DONE"));
        verify(projectCertService, never()).changeStatus(anyLong(), anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("autoCreateGate 非项目成员 → FORBIDDEN，且不调 service.autoCreateGate")
    void autoCreateGate_nonMember() {
        givenActor(rdPm());
        givenNonMember();
        assertForbidden(() -> controller.autoCreateGate(PROJECT_ID, "G3"));
        verify(gateCreationService, never()).autoCreateGate(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("recordLaunchDate 非项目成员 → FORBIDDEN，且不调 service.initialRecord")
    void recordLaunchDate_nonMember() {
        givenActor(rdPm());
        givenNonMember();
        assertForbidden(() -> controller.recordLaunchDate(
            PROJECT_ID, new ProjectController.LaunchDateRecordReq(LocalDate.now(), "首次录入")));
        verify(launchDateChangeService, never()).initialRecord(anyLong(), any(), anyString(), anyLong());
    }

    // ==================== 反向锁：跨组但在职成员必须放行 ====================
    // 这两条是本类的核心。若有人把守卫从「在职成员」改成「同组」，
    // 它们会立刻变红——那正是我们要防的：认证清单的法定责任人是研发PM（在协同组）。

    @Test
    @DisplayName("反向锁：跨组但在职成员 addCertItem → 放行（协同组研发PM 是法定责任人）")
    void addCertItem_crossGroupActiveMember_allowed() {
        givenActor(rdPm());
        givenActiveMember();
        controller.addCertItem(PROJECT_ID, certReq());
        verify(projectCertService).addManual(eq(PROJECT_ID), any(), eq(1L));
    }

    @Test
    @DisplayName("反向锁：跨组但在职成员 recordLaunchDate → 放行")
    void recordLaunchDate_crossGroupActiveMember_allowed() {
        givenActor(rdPm());
        givenActiveMember();
        controller.recordLaunchDate(
            PROJECT_ID, new ProjectController.LaunchDateRecordReq(LocalDate.now(), "首次录入"));
        verify(launchDateChangeService).initialRecord(eq(PROJECT_ID), any(), eq("首次录入"), eq(1L));
    }

    // ==================== 超管豁免：不触达任何 Mapper ====================

    @Test
    @DisplayName("超管 → 放行，且不查项目 / 不查成员（豁免先于任何 DB 读）")
    void addCertItem_superAdmin_allowed() {
        givenActor(new IpdActor(9L, "超管", "SUPER_ADMIN", null));
        controller.addCertItem(PROJECT_ID, certReq());
        verify(projectCertService).addManual(eq(PROJECT_ID), any(), eq(9L));
        verify(projectMapper, never()).selectById(any());
        verify(projectMemberMapper, never()).selectCount(any());
    }

    // ==================== 项目不存在：统一 FORBIDDEN，不泄漏存在性 ====================

    @Test
    @DisplayName("项目不存在 → FORBIDDEN（与无权限同文案，不区分，避免存在性 oracle）")
    void addCertItem_projectMissing() {
        givenActor(rdPm());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
        assertForbidden(() -> controller.addCertItem(PROJECT_ID, certReq()));
        verify(projectCertService, never()).addManual(anyLong(), any(), anyLong());
    }
}
