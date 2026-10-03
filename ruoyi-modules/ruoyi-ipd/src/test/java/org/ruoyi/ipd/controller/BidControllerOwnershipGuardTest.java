/**
 * BidController 写口归属校验（横向越权防护）负例测试。
 *
 * <p>背景：招标单 publish / pre-select-token / withdraw / close 五个写口此前只做角色级校验
 * （{@code requireInternal()}，丢弃返回值），服务链里从未把会话人与资源所属组绑定——
 * 任何持 {@code ipd:module:project:status-change} 权限码的内部用户都能操作别人项目的招标单。
 * create 的 {@code projectId} 同样由客户端指定。
 *
 * <p>范式（照 AiDocumentController.requireVersionOnPathChain）：
 * actor 只来自会话 → 归属在写库前解析（id → 招标单 → 项目 → 项目主组）→ 失败统一「无权操作」。
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
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.BidInvitationService;
import org.ruoyi.ipd.service.BidResponseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
class BidControllerOwnershipGuardTest {

    /** 攻击者所在组。 */
    private static final Long ACTOR_GROUP = 777001L;
    /** 被操作资源真实所属组——与 ACTOR_GROUP 不一致，即越权。 */
    private static final Long OWNER_GROUP = 999999L;
    private static final Long INVITATION_ID = 8801L;
    private static final Long PROJECT_ID = 100L;

    @Mock private BidInvitationService bidInvitationService;
    @Mock private BidResponseService bidResponseService;
    @Mock private IpdPermission ipdPermission;
    @Mock private IpdAuthSession session;
    @Mock private ProjectMapper projectMapper;

    @InjectMocks private BidController controller;

    private IpdActor attacker() {
        return new IpdActor(1L, "研发PM", "RD_PM", ACTOR_GROUP);
    }

    /** 一张挂在 OWNER_GROUP 项目上的招标单。 */
    private BidInvitation foreignInvitation() {
        BidInvitation inv = new BidInvitation();
        inv.setId(INVITATION_ID);
        inv.setProjectId(PROJECT_ID);
        inv.setStatus("OPEN");
        inv.setCreateBy(4242L);
        return inv;
    }

    private Project foreignProject() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(OWNER_GROUP);
        return p;
    }

    private void givenCrossGroupInvitation() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(bidInvitationService.getById(INVITATION_ID)).thenReturn(foreignInvitation());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(foreignProject());
    }

    private void assertForbidden(Runnable call) {
        assertThatThrownBy(call::run)
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN))
            .hasMessageContaining("无权操作");
    }

    // ==================== 五个写口逐一验证「越权即拒且不触达 service」 ====================

    @Test
    @DisplayName("closeInvitation 跨组 → FORBIDDEN，且不调 service.close")
    void closeInvitation_crossGroup() {
        givenCrossGroupInvitation();
        assertForbidden(() -> controller.closeInvitation(INVITATION_ID));
        verify(bidInvitationService, never()).close(anyLong());
    }

    @Test
    @DisplayName("withdrawInvitation 跨组 → FORBIDDEN，且不调 service.withdraw")
    void withdrawInvitation_crossGroup() {
        givenCrossGroupInvitation();
        assertForbidden(() -> controller.withdrawInvitation(INVITATION_ID));
        verify(bidInvitationService, never()).withdraw(anyLong());
    }

    @Test
    @DisplayName("publishInvitation 跨组 → FORBIDDEN，且不调 service.publish")
    void publishInvitation_crossGroup() {
        givenCrossGroupInvitation();
        assertForbidden(() -> controller.publishInvitation(INVITATION_ID));
        verify(bidInvitationService, never()).publish(anyLong());
    }

    @Test
    @DisplayName("preSelectToken 跨组 → FORBIDDEN，且不签发 confirmToken")
    void preSelectToken_crossGroup() {
        givenCrossGroupInvitation();
        assertForbidden(() -> controller.preSelectToken(INVITATION_ID));
        verify(bidInvitationService, never()).issueConfirmToken(anyLong());
    }

    @Test
    @DisplayName("createInvitation 指向他人项目 → FORBIDDEN，且不落库")
    void createInvitation_crossGroup() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(foreignProject());
        Person person = new Person();
        person.setId(1L);
        when(session.currentPerson()).thenReturn(person);

        BidInvitation body = new BidInvitation();
        body.setProjectId(PROJECT_ID);
        body.setTitle("跨组招标单");

        assertForbidden(() -> controller.createInvitation(body));
        verify(bidInvitationService, never()).create(any());
    }

    // ==================== 回归：同组 / 超管必须仍能正常操作 ====================

    @Test
    @DisplayName("closeInvitation 同组 → 正常调 service.close（回归）")
    void closeInvitation_sameGroup() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(bidInvitationService.getById(INVITATION_ID)).thenReturn(foreignInvitation());
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setMainGroupId(ACTOR_GROUP);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        when(bidInvitationService.close(INVITATION_ID)).thenReturn(foreignInvitation());

        assertThat(controller.closeInvitation(INVITATION_ID).getData()).isNotNull();
        verify(bidInvitationService).close(INVITATION_ID);
    }

    @Test
    @DisplayName("closeInvitation SUPER_ADMIN 跨组豁免 → 正常调用（回归）")
    void closeInvitation_superAdminExempt() {
        when(ipdPermission.requireInternal())
            .thenReturn(new IpdActor(1L, "超管", "SUPER_ADMIN", ACTOR_GROUP));
        when(bidInvitationService.getById(INVITATION_ID)).thenReturn(foreignInvitation());
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(foreignProject());
        when(bidInvitationService.close(INVITATION_ID)).thenReturn(foreignInvitation());

        assertThat(controller.closeInvitation(INVITATION_ID).getData()).isNotNull();
        verify(bidInvitationService).close(INVITATION_ID);
    }

    @Test
    @DisplayName("closeInvitation 招标单不存在 → 统一 FORBIDDEN，不泄漏存在性")
    void closeInvitation_notFound_notLeaked() {
        when(ipdPermission.requireInternal()).thenReturn(attacker());
        when(bidInvitationService.getById(INVITATION_ID)).thenReturn(null);

        assertForbidden(() -> controller.closeInvitation(INVITATION_ID));
        verify(bidInvitationService, never()).close(anyLong());
    }
}
