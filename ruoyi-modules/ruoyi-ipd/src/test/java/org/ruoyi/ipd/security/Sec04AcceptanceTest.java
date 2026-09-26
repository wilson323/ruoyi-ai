package org.ruoyi.ipd.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * SEC-04（卡 a4657cec）行为层验收：附件下载/对象访问的 IDOR 静态守卫三段式
 * （认证 → 归属/租户 → 角色豁免）fail-closed 正反例。
 *
 * <p>与真库 HTTP 三证矩阵（docs/ipd-系统说明/验收/SEC-04-三证矩阵-20260926.py，
 * 2026-09-26 实测 16/16 PASS）互补：本测试锁守护逻辑分支与"不泄露存在性"文案契约，
 * 真 HTTP 矩阵锁会话/权限码/DB 零写端到端事实。Mock 不代替业务结论，两侧证据合并判读。
 *
 * <p>mock 合法性（WB-17-1 三规约）：所有 stub 均为真库可达查询分支
 * （selectById 命中/未命中、selectCount 0/1、租户一致/不一致），不构造生产写入路径
 * 不可能产生的数据组合。
 */
@Tag("dev")
class Sec04AcceptanceTest {

    private static final Long PROJECT_ID = 9140005L;
    private static final IpdActor MARKET_PM = new IpdActor(900103L, "ipd-market", "MARKET_PM", 1L);
    private static final IpdActor RD_CROSS_GROUP = new IpdActor(900104L, "ipd-rd", "RD_PM", 2L);
    private static final IpdActor SUPER_ADMIN = new IpdActor(900101L, "ipd-admin", "SUPER_ADMIN", null);

    private Project project(String tenantId) {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setTenantId(tenantId);
        return p;
    }

    // ---------- 守卫 1：未认证 fail-closed ----------

    @Test
    @DisplayName("S4-1 游客（actor=null）下载被拒 UNAUTHORIZED，零 DB 触达")
    void nullActorRejectedBeforeAnyDbRead() {
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        assertThatThrownBy(() -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(null, PROJECT_ID, memberMapper, projectMapper))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.UNAUTHORIZED);
        verifyNoInteractions(memberMapper, projectMapper);
    }

    // ---------- 守卫 3：self/主组/跨组/超管 ----------

    @Test
    @DisplayName("S4-2 self/主组在职成员放行（项目存在+租户一致+exit_date IS NULL 计数=1）")
    void activeProjectMemberAllowed() {
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class, Mockito.CALLS_REAL_METHODS)) {
            lh.when(LoginHelper::getTenantId).thenReturn("000000");
            when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("000000"));
            when(memberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
            assertThatCode(() -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(
                MARKET_PM, PROJECT_ID, memberMapper, projectMapper)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("S4-3 跨组非成员拒绝 FORBIDDEN，文案不泄露对象名（真 HTTP DL-4 同口径）")
    void crossGroupMemberRejectedWithoutLeak() {
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class, Mockito.CALLS_REAL_METHODS)) {
            lh.when(LoginHelper::getTenantId).thenReturn("000000");
            when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("000000"));
            when(memberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
            assertThatThrownBy(() -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(
                RD_CROSS_GROUP, PROJECT_ID, memberMapper, projectMapper))
                .isInstanceOf(IpdBusinessException.class)
                .hasMessage("非项目成员，无权访问")
                // 反泄露契约：错误消息不得携带文件名/对象标识
                .hasMessageNotContaining(".pdf")
                .hasMessageNotContaining(String.valueOf(PROJECT_ID));
        }
    }

    @Test
    @DisplayName("S4-4 项目不存在与无权限统一文案（不制造存在性 oracle）")
    void missingProjectUsesUniformForbiddenMessage() {
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class, Mockito.CALLS_REAL_METHODS)) {
            lh.when(LoginHelper::getTenantId).thenReturn("000000");
            when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
            assertThatThrownBy(() -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(
                RD_CROSS_GROUP, PROJECT_ID, memberMapper, projectMapper))
                .isInstanceOf(IpdBusinessException.class)
                .hasMessage("无权访问该项目");
        }
    }

    @Test
    @DisplayName("S4-5 SUPER_ADMIN 豁免短路：不触达任何 DB 读（真 HTTP DL-2 放行同语义）")
    void superAdminSkipsAllDbReads() {
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        assertThatCode(() -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(
            SUPER_ADMIN, PROJECT_ID, memberMapper, projectMapper)).doesNotThrowAnyException();
        verifyNoInteractions(memberMapper, projectMapper);
    }

    @Test
    @DisplayName("S4-6 跨租户项目拒绝且与无权限同文案（租户一致性口径）")
    void crossTenantProjectRejectedUniformly() {
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        try (MockedStatic<LoginHelper> lh = mockStatic(LoginHelper.class, Mockito.CALLS_REAL_METHODS)) {
            lh.when(LoginHelper::getTenantId).thenReturn("000000");
            when(projectMapper.selectById(PROJECT_ID)).thenReturn(project("999999"));
            assertThatThrownBy(() -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(
                MARKET_PM, PROJECT_ID, memberMapper, projectMapper))
                .isInstanceOf(IpdBusinessException.class)
                .hasMessage("无权访问该项目");
        }
    }

    // ---------- 守卫 5：审计导出仅超管（AE-1/AE-3 行为层对应） ----------

    @Test
    @DisplayName("S4-7 非超管触发 requireSuperAdmin 拒绝；超管放行（零 DB 读）")
    void requireSuperAdminGateForAuditExport() {
        assertThatThrownBy(() -> IpdIdorGuard.requireSuperAdmin(RD_CROSS_GROUP))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessage("仅超管可执行");
        assertThatCode(() -> IpdIdorGuard.requireSuperAdmin(SUPER_ADMIN)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("S4-8 projectId=null fail-closed PARAM_INVALID（防枚举探测）")
    void nullProjectIdRejected() {
        ProjectMemberMapper memberMapper = mock(ProjectMemberMapper.class);
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        assertThatThrownBy(() -> IpdIdorGuard.requireProjectMemberOrSuperAdmin(
            MARKET_PM, null, memberMapper, projectMapper))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
        verifyNoInteractions(memberMapper, projectMapper);
    }
}
