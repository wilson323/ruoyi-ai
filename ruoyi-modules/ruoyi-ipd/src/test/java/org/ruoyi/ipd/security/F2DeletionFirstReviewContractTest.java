package org.ruoyi.ipd.security;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.service.DeleteAuditService;
import org.ruoyi.ipd.service.DeletionRequestServiceImpl;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.ISystemConfigService;
import org.ruoyi.ipd.service.StateMachineGuard;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F-2（2026-10-06）：删除「初审权限穿透超管」契约。
 *
 * <p><b>缺陷</b>：{@code ipd:deletion-request:leader} 原先与 4 个组长/超管共有码打包在同一个
 * {@code DELETION_LEADER} 集合里，而 {@code SUPER_ADMIN} merge 了这个集合 ⇒ 超管能对删除申请做初审。
 * {@code DeletionRequestServiceImpl#leaderDecision} 又对超管豁免组匹配 ⇒ 同一人可自提、自审（初审）、
 * 再自批（终审），「组长初审 + 超管终审」双层审核（AC-DEL-02 / AC-REQ-09）被同人闭环击穿。
 *
 * <p><b>本测试锁四件事</b>：
 * <ol>
 *   <li>初审码只授予 GROUP_LEADER，SUPER_ADMIN 集合里查不到（注解层收口）；</li>
 *   <li>同批拆出的其余 4 码<b>没被连坐收紧</b>——超管仍持有（防过度收紧引发全员 403）；</li>
 *   <li>超管调 {@code leaderDecision} → FORBIDDEN 且零读写（方法体层收口）；</li>
 *   <li>本组组长仍放行（收口不是把功能打死）。</li>
 * </ol>
 */
@Tag("dev")
@DisplayName("F-2：删除初审仅产品组长，超管不得穿透（初审/终审角色分离）")
@ExtendWith(MockitoExtension.class)
class F2DeletionFirstReviewContractTest {

    private static final String FIRST_REVIEW_CODE = IpdPermissionCode.OPERATION_DELETION_REQUEST_LEADER;
    private static final String ADMIN_CODE = IpdPermissionCode.OPERATION_DELETION_REQUEST_ADMIN;

    @Mock private DeletionRequestMapper deletionRequestMapper;
    @Mock private ISystemConfigService systemConfigService;
    @Mock private IAuditLogService auditLogService;
    @Mock private DeleteAuditService deleteAuditService;
    @Mock private StateMachineGuard stateMachineGuard;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private GateMapper gateMapper;
    @Mock private ProductMapper productMapper;
    @Mock private PersonMapper personMapper;

    private DeletionRequestServiceImpl service;

    private static final IpdActor ACTOR_ADMIN = new IpdActor(2L, "超管", "SUPER_ADMIN", null);
    private static final IpdActor ACTOR_LEADER_SAME_GROUP = new IpdActor(5L, "本组组长", "GROUP_LEADER", 20L);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "f2-deletion");
        TableInfoHelper.initTableInfo(assistant, DeletionRequest.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        service = new DeletionRequestServiceImpl(
            deletionRequestMapper, systemConfigService, auditLogService, deleteAuditService,
            projectMemberMapper, projectMapper, gateMapper, productMapper, personMapper);
        service.setStateMachineGuard(stateMachineGuard);
    }

    private DeletionRequest savedLeaderReview(Long id) {
        DeletionRequest request = DeletionRequest.builder()
            .id(id).entityType("projects").entityId(100L).reason("测试删除")
            .requesterId(1L).status(DeletionRequestServiceImpl.ST_LEADER_REVIEW).build();
        request.setCreateTime(new Date());
        return request;
    }

    private Project projectOfGroup(Long groupId) {
        Project project = new Project();
        project.setMainGroupId(groupId);
        return project;
    }

    @Test
    @DisplayName("1) 目录面：初审码只授予 GROUP_LEADER，SUPER_ADMIN 集合里查不到")
    void firstReviewCodeBelongsToGroupLeaderOnly() {
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", FIRST_REVIEW_CODE)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", FIRST_REVIEW_CODE))
            .as("超管不得持有删除初审码（否则初审/终审同人闭环）")
            .isFalse();
        assertThat(IpdRolePermissionCatalog.defaultPermissionsOf("SUPER_ADMIN"))
            .as("SUPER_ADMIN 默认权限清单中不得出现初审码")
            .doesNotContain(FIRST_REVIEW_CODE);
        assertThat(IpdRolePermissionCatalog.defaultPermissionsOf("GROUP_LEADER"))
            .contains(FIRST_REVIEW_CODE);
    }

    @Test
    @DisplayName("2) 连带面：拆集不得连坐收紧其余 4 码（超管仍持有）+ 终审码仍只归超管")
    void sharedLeaderCodesAreNotOverTightened() {
        String[] shared = {
            IpdPermissionCode.OPERATION_CONTRIBUTION_CONFIRM,
            IpdPermissionCode.OPERATION_NEGATIVE_FEEDBACK_DECIDE,
            IpdPermissionCode.OPERATION_COMPLIANCE_WRITE,
            IpdPermissionCode.OPERATION_P0_ESCALATION_READ
        };
        for (String code : shared) {
            assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", code))
                .as("超管仍须持有 %s（本轮只收回初审码）", code)
                .isTrue();
            assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", code))
                .as("组长仍须持有 %s", code)
                .isTrue();
        }
        // 终审码本就只归超管：收口不是把终审也打死（初审分离 ≠ 终审取消）
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", ADMIN_CODE)).isTrue();
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", ADMIN_CODE)).isFalse();
    }

    @Test
    @DisplayName("3) 方法体面：超管调 leaderDecision → FORBIDDEN，零 DB 读写")
    void superAdminLeaderDecisionForbiddenWithZeroWrites() {
        assertThatThrownBy(() -> service.leaderDecision(ACTOR_ADMIN, 9L, true, "超管直批"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("仅产品组长可初审删除申请")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
        verify(deletionRequestMapper, never()).selectById(any());
        verify(deletionRequestMapper, never()).updateById(any(DeletionRequest.class));
        verify(deleteAuditService, never()).approveAndExecute(any(), any());
        verify(auditLogService, never()).append(any(AuditLog.class));
        verify(auditLogService, never()).append(nullable(Long.class), any(), any(), any(), any());
    }

    @Test
    @DisplayName("4) 未误伤：本组组长（组 20 = 目标主组 20）仍放行 → ADMIN_REVIEW")
    void sameGroupLeaderStillAllowed() {
        DeletionRequest request = savedLeaderReview(9L);
        when(deletionRequestMapper.selectById(9L)).thenReturn(request);
        when(projectMapper.selectById(100L)).thenReturn(projectOfGroup(20L));

        DeletionRequest after = service.leaderDecision(ACTOR_LEADER_SAME_GROUP, 9L, true, "同意");

        assertThat(after.getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_ADMIN_REVIEW);
        assertThat(after.getLeaderId()).isEqualTo(5L);
    }
}
