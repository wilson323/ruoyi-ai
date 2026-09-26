package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.service.executor.RequirementSoftDeleteExecutor;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.Calendar;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.List;

/**
 * R218 卡1（U1）AC-REQ-09 契约测试：需求池记录删除接入双层审核链。
 *
 * <p>缺陷（看板 e256007b / R218 归因条目·DEF-A）：POST 删除申请对 entityType=requirements
 * 返回 400「不支持的 entity_type: requirements」——DeletionRequestServiceImpl 白名单缺
 * requirements、无 RequirementSoftDeleteExecutor、entityExists 缺分量。
 *
 * <p>本测试契约 = AC-REQ-09 原文「需求池记录删除强制双层审核（组长初审+超管终审）」，
 * 且必须复用既有链（submit→LEADER_REVIEW→ADMIN_REVIEW→approveAndExecute 原子软删），不另起炉灶。
 *
 * <p>TDD 证据：本类红跑原始输出与时间戳留档
 * docs/ipd-系统说明/验收/R218-AC续跑-20260925/defect-fix/evidence/。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class R218AcReq09DeletionChainTest {

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
    @Mock private RequirementMapper requirementMapper;

    private DeletionRequestServiceImpl service;

    private static final IpdActor ACTOR_ADMIN = new IpdActor(2L, "超管", "SUPER_ADMIN", null);
    private static final IpdActor ACTOR_LEADER_SAME_GROUP = new IpdActor(5L, "本组组长", "GROUP_LEADER", 20L);
    private static final IpdActor ACTOR_LEADER_FOREIGN_GROUP = new IpdActor(6L, "外组组长", "GROUP_LEADER", 99L);
    /** 真库取证值（归因报告 DEF-A：requirements/2103338131276259329 存在且 SUBMITTED）。 */
    private static final Long REAL_REQ_ID = 2103338131276259329L;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "r218-req09");
        TableInfoHelper.initTableInfo(assistant, DeletionRequest.class);
        TableInfoHelper.initTableInfo(assistant, Requirement.class);
    }

    @BeforeEach
    void setUp() {
        // 与 DeletionRequestServiceTest 同款 9 参构造（不破坏存量构造签名——本波修复走可选注入缝）
        service = new DeletionRequestServiceImpl(
            deletionRequestMapper, systemConfigService, auditLogService, deleteAuditService,
            projectMemberMapper, projectMapper, gateMapper, productMapper, personMapper);
        service.setStateMachineGuard(stateMachineGuard);
        when(systemConfigService.getIntValue("deletion.leaderDeadlineDays", 2)).thenReturn(2);
        when(systemConfigService.getIntValue("deletion.adminDeadlineDays", 2)).thenReturn(2);
    }

    private DeletionRequest savedReq(Long id, String status, Date createTime) {
        DeletionRequest request = DeletionRequest.builder()
            .id(id).entityType("requirements").entityId(REAL_REQ_ID).reason("需求池清理")
            .requesterId(1L).status(status).build();
        request.setCreateTime(createTime);
        return request;
    }

    @Test
    @DisplayName("AC-REQ-09-① 契约：POST 删除申请 entityType=requirements 不再 400，进入 LEADER_REVIEW（双层链起点）")
    void submitRequirementsEntersDualReviewChain() {
        DeletionRequest request = service.submit(ACTOR_ADMIN, "requirements", REAL_REQ_ID, "{}", "需求池清理");

        assertThat(request.getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_LEADER_REVIEW);
        assertThat(request.getEntityType()).isEqualTo("requirements");
        assertThat(request.getEntityId()).isEqualTo(REAL_REQ_ID);
        verify(deletionRequestMapper).insert(any(DeletionRequest.class));
        verify(auditLogService).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("AC-REQ-09-② 回归守卫：白名单扩容不得放行未知类型（fail-closed 保持）")
    void unknownEntityTypeStillRejected() {
        assertThatThrownBy(() -> service.submit(ACTOR_ADMIN, "unknown_type", 1L, "{}", "x"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不支持的 entity_type")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
        verify(deletionRequestMapper, never()).insert(any(DeletionRequest.class));
    }

    @Test
    @DisplayName("AC-REQ-09-③ 双层链复用：requirements 申请走既有 leaderDecision→adminDecision（超管终审委托原子软删执行器）")
    void dualChainReusedForRequirements() {
        DeletionRequest leaderReview = savedReq(9L, DeletionRequestServiceImpl.ST_LEADER_REVIEW, new Date());
        when(deletionRequestMapper.selectById(9L)).thenReturn(leaderReview);
        // 超管可兼任初审（既有链语义，勿另起炉灶）
        DeletionRequest afterLeader = service.leaderDecision(ACTOR_ADMIN, 9L, true, "组长初审通过");
        assertThat(afterLeader.getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_ADMIN_REVIEW);

        DeletionRequest executed = savedReq(9L, DeletionRequestServiceImpl.ST_DELETED, new Date());
        executed.setExecutedAt(new Date());
        when(deleteAuditService.approveAndExecute(9L, 2L)).thenReturn(executed);
        DeletionRequest afterAdmin = service.adminDecision(ACTOR_ADMIN, 9L, true, "终审通过");

        assertThat(afterAdmin.getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_DELETED);
        assertThat(afterAdmin.getExecutedAt()).isNotNull();
        // 终审必须经 DeleteAuditService.approveAndExecute 原子软删（P0-6.2 / AC-DEL-02 同口径）
        verify(deleteAuditService).approveAndExecute(9L, 2L);
    }

    // ===== entityExists / resolveScope 分量（卡面三分量补齐） =====

    @Test
    @DisplayName("AC-REQ-09-④ entityExists 分量：超管对不存在的需求实体 → NOT_FOUND，零写入（防僵尸申请）")
    void superAdminSubmitMissingRequirementRejected() {
        service.setRequirementMapper(requirementMapper);
        when(requirementMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.submit(ACTOR_ADMIN, "requirements", 999L, "{}", "幽灵需求"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("目标实体不存在: requirements/999")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.NOT_FOUND);
        verify(deletionRequestMapper, never()).insert(any(DeletionRequest.class));
    }

    @Test
    @DisplayName("AC-REQ-09-⑤ resolveScope 分量：需求经产品上溯所属组——本组组长可发起，外组组长 FORBIDDEN（fail-closed）")
    void groupLeaderScopeResolvedViaProductOfRequirement() {
        service.setRequirementMapper(requirementMapper);
        Requirement requirement = Requirement.builder().id(REAL_REQ_ID).productId(1001L).status("SUBMITTED").build();
        when(requirementMapper.selectById(REAL_REQ_ID)).thenReturn(requirement);
        Product product = Product.builder().id(1001L).productName("ZK 控制器").productCode("ZK-01").groupId(20L).build();
        when(productMapper.selectById(1001L)).thenReturn(product);

        DeletionRequest request = service.submit(ACTOR_LEADER_SAME_GROUP, "requirements", REAL_REQ_ID, "{}", "组内清理");
        assertThat(request.getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_LEADER_REVIEW);
        assertThat(request.getRequesterId()).isEqualTo(5L);

        assertThatThrownBy(() -> service.submit(ACTOR_LEADER_FOREIGN_GROUP, "requirements", REAL_REQ_ID, "{}", "跨组越权"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("仅资源 owner / 在职项目成员 / 所属组组长可发起删除申请")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);
    }

    // ===== RequirementSoftDeleteExecutor 接入 DeleteAuditService 注册表（终审执行腿） =====

    @Test
    @DisplayName("AC-REQ-09-⑥ 终审执行：approveAndExecute(requirements) → 显式 UPDATE del_flag='1' + DELETE_EXECUTE 审计同事务")
    void approveAndExecuteSoftDeletesRequirement() {
        DeleteAuditService auditService = new DeleteAuditService(deletionRequestMapper, auditLogService,
            List.of(new RequirementSoftDeleteExecutor(requirementMapper)));
        assertThat(auditService.supportedEntityTypes()).contains("requirements");

        DeletionRequest req = savedReq(31L, DeletionRequestServiceImpl.ST_ADMIN_REVIEW, new Date());
        when(deletionRequestMapper.selectById(31L)).thenReturn(req);
        when(deletionRequestMapper.updateById(any(DeletionRequest.class))).thenReturn(1);
        when(requirementMapper.selectById(REAL_REQ_ID))
            .thenReturn(Requirement.builder().id(REAL_REQ_ID).status("SUBMITTED").delFlag("0").build());
        when(requirementMapper.update(isNull(), any())).thenReturn(1);

        DeletionRequest after = auditService.approveAndExecute(31L, 2L);

        assertThat(after.getStatus()).isEqualTo(DeletionRequestServiceImpl.ST_DELETED);
        assertThat(after.getExecutedAt()).isNotNull();
        // @TableLogic 实体必须走显式 LambdaUpdateWrapper SET del_flag（R216 实测回归同款）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Requirement>> cap = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(requirementMapper).update(isNull(), cap.capture());
        assertThat(cap.getValue().getSqlSet()).contains("del_flag");
        ArgumentCaptor<AuditLog> auditCap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(auditCap.capture());
        assertThat(auditCap.getValue().getAction()).isEqualTo(DeleteAuditService.ACTION_DELETE_EXECUTE);
        assertThat(auditCap.getValue().getEntityType()).isEqualTo("requirements");
    }

    @Test
    @DisplayName("AC-REQ-09-⑦ 幂等：目标已软删/不存在 → DELETE_NOOP 不重复 UPDATE（executor.isDeleted 先行）")
    void approveAndExecuteNoopWhenAlreadyDeleted() {
        DeleteAuditService auditService = new DeleteAuditService(deletionRequestMapper, auditLogService,
            List.of(new RequirementSoftDeleteExecutor(requirementMapper)));
        DeletionRequest req = savedReq(32L, DeletionRequestServiceImpl.ST_ADMIN_REVIEW, new Date());
        when(deletionRequestMapper.selectById(32L)).thenReturn(req);
        when(deletionRequestMapper.updateById(any(DeletionRequest.class))).thenReturn(1);
        when(requirementMapper.selectById(REAL_REQ_ID)).thenReturn(null);

        auditService.approveAndExecute(32L, 2L);

        verify(requirementMapper, never()).update(isNull(), any());
        ArgumentCaptor<AuditLog> auditCap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(auditCap.capture());
        assertThat(auditCap.getValue().getAction()).isEqualTo(DeleteAuditService.ACTION_DELETE_NOOP);
    }

    private static Date date(int y, int m, int d) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(y, m - 1, d, 10, 0, 0);
        return c.getTime();
    }
}
