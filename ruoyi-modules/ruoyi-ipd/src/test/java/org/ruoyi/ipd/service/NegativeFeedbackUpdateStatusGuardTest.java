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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R215-GAP-B3：negative-feedbacks/{id}/status 状态机守卫单测。
 *
 * <p>验证 {@link NegativeFeedbackService#updateStatusGuarded(Long, String, IpdActor)}：
 * <ul>
 *   <li>合法转移（DRAFT→PENDING_DECISION, PENDING_DECISION→EXECUTED/REJECTED, EXECUTED→LIFTED）→ 成功</li>
 *   <li>非法转移（LIFTED→DRAFT, EXECUTED→DRAFT, DRAFT→EXECUTED 等）→ NF_STATE_INVALID</li>
 *   <li>参数为空 → PARAM_INVALID</li>
 *   <li>记录不存在 → NOT_FOUND</li>
 *   <li>跨组越权 → FORBIDDEN（由 assertProjectReadable 守卫）</li>
 * </ul>
 *
 * <p>Hermetic 单元测试（无 Spring 启动、无 DB 连接）：纯 Mockito mock。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("R215-GAP-B3 negative-feedbacks/{id}/status 状态机守卫")
class NegativeFeedbackUpdateStatusGuardTest {

    @Mock private NegativeFeedbackMapper mapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper memberMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private NotificationService notificationService;
    @Mock private org.ruoyi.ipd.security.IpdPermission ipdPermission;

    private NegativeFeedbackService service;

    /** 同组组长（合法操作人） */
    private static final IpdActor LEADER = new IpdActor(99L, "组长", "GROUP_LEADER", 100L);
    /** 超管（归属校验豁免） */
    private static final IpdActor ADMIN = new IpdActor(1L, "超管", "SUPER_ADMIN", 100L);
    /** 跨组操作人（应被拒绝） */
    private static final IpdActor OTHER_GROUP_LEADER = new IpdActor(88L, "他组组长", "GROUP_LEADER", 999L);

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NegativeFeedback.class);
    }

    @BeforeEach
    void setUp() {
        service = new NegativeFeedbackService(mapper, memberMapper, auditLogService,
            notificationService, projectMapper, ipdPermission);
        // 默认项目归属：mainGroupId=100（与 LEADER 同组）
        lenient().when(projectMapper.selectById(any())).thenReturn(
            Project.builder().id(7L).mainGroupId(100L).status("ACTIVE").delFlag("0").build());
        lenient().when(ipdPermission.canReadProject(any(), any(), any())).thenAnswer(inv -> {
            String role = inv.getArgument(0);
            Long actorGroup = inv.getArgument(1);
            Long projectGroup = inv.getArgument(2);
            if ("SUPER_ADMIN".equals(role)) return true;
            return actorGroup != null && actorGroup.equals(projectGroup);
        });
    }

    /* ====================== 正例：合法状态转移 ====================== */

    @ParameterizedTest(name = "[{index}] {0} → {1} 合法")
    @CsvSource({
        "DRAFT, PENDING_DECISION",
        "PENDING_DECISION, EXECUTED",
        "PENDING_DECISION, REJECTED",
        "EXECUTED, LIFTED"
    })
    @DisplayName("[B3-正例] 合法状态转移 → 更新成功返回 true")
    void updateStatusGuarded_legalTransition_success(String currentStatus, String targetStatus) {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status(currentStatus).build();
        when(mapper.selectById(33L)).thenReturn(row);
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(1);

        boolean result = service.updateStatusGuarded(33L, targetStatus, LEADER);

        assertThat(result).isTrue();
        verify(mapper).update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("[B3-正例] SUPER_ADMIN 跨组也能操作（归属校验豁免）")
    void updateStatusGuarded_superAdmin_crossGroup_allowed() {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status("DRAFT").build();
        when(mapper.selectById(33L)).thenReturn(row);
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(1);

        boolean result = service.updateStatusGuarded(33L, "PENDING_DECISION", ADMIN);

        assertThat(result).isTrue();
    }

    /* ====================== 副作用：补齐与 submit/decide/lift 一致 ====================== */

    @Test
    @DisplayName("[B3-副作用] PENDING_DECISION→EXECUTED 写 decidedBy/decidedAt + DECIDE_EXECUTE 审计 + 通知双PM")
    void updateStatusGuarded_toExecuted_completesDecisionSideEffects() {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status("PENDING_DECISION")
            .mainPersonId(50L).relatedPersonId(60L).build();
        when(mapper.selectById(33L)).thenReturn(row);
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThat(service.updateStatusGuarded(33L, "EXECUTED", LEADER)).isTrue();
        // 内存态回写：不再产生 EXECUTED 但 decidedAt=NULL 的半行
        assertThat(row.getStatus()).isEqualTo("EXECUTED");
        assertThat(row.getDecidedBy()).isEqualTo(99L);
        assertThat(row.getDecidedAt()).isNotNull();
        // 审计动作与正式审批路径一致
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo("DECIDE_EXECUTE");
        // 主责+连带双PM各一条执行通知
        verify(notificationService, times(2)).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[B3-副作用] PENDING_DECISION→REJECTED 写 decidedBy/decidedAt + DECIDE_REJECT 审计，不发执行通知")
    void updateStatusGuarded_toRejected_writesDecisionAndAudits() {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status("PENDING_DECISION")
            .mainPersonId(50L).relatedPersonId(60L).build();
        when(mapper.selectById(33L)).thenReturn(row);
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThat(service.updateStatusGuarded(33L, "REJECTED", LEADER)).isTrue();
        assertThat(row.getDecidedBy()).isEqualTo(99L);
        assertThat(row.getDecidedAt()).isNotNull();
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo("DECIDE_REJECT");
        verify(notificationService, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[B3-副作用] EXECUTED→LIFTED 写 liftedBy/liftedAt + LIFT 审计 + 解除通知")
    void updateStatusGuarded_toLifted_completesLiftSideEffects() {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status("EXECUTED")
            .mainPersonId(50L).relatedPersonId(60L).build();
        when(mapper.selectById(33L)).thenReturn(row);
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThat(service.updateStatusGuarded(33L, "LIFTED", LEADER)).isTrue();
        assertThat(row.getLiftedBy()).isEqualTo(99L);
        assertThat(row.getLiftedAt()).isNotNull();
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo("LIFT");
        verify(notificationService, times(2)).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[B3-副作用] DRAFT→PENDING_DECISION 仅 SUBMIT 审计，无决策人、无通知")
    void updateStatusGuarded_toPendingDecision_auditOnly() {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status("DRAFT").build();
        when(mapper.selectById(33L)).thenReturn(row);
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(1);

        assertThat(service.updateStatusGuarded(33L, "PENDING_DECISION", LEADER)).isTrue();
        assertThat(row.getDecidedBy()).isNull();
        assertThat(row.getDecidedAt()).isNull();
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertThat(cap.getValue().getAction()).isEqualTo("SUBMIT");
        verify(notificationService, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("[B3-TOCTOU] 并发转移命中失败(affected=0) → 不审计不通知、不改内存态、返回 false")
    void updateStatusGuarded_concurrentMiss_noSideEffects() {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status("PENDING_DECISION")
            .mainPersonId(50L).relatedPersonId(60L).build();
        when(mapper.selectById(33L)).thenReturn(row);
        when(mapper.update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThat(service.updateStatusGuarded(33L, "EXECUTED", LEADER)).isFalse();
        assertThat(row.getStatus()).isEqualTo("PENDING_DECISION");
        assertThat(row.getDecidedAt()).isNull();
        verify(auditLogService, never()).append(ArgumentMatchers.any(AuditLog.class));
        verify(notificationService, never()).publish(any(), any(), any(), any(), any(), any(), any(), any());
    }

    /* ====================== 负例：非法状态转移 ====================== */

    @ParameterizedTest(name = "[{index}] {0} → {1} 非法")
    @CsvSource({
        "DRAFT, EXECUTED",
        "DRAFT, LIFTED",
        "DRAFT, REJECTED",
        "PENDING_DECISION, DRAFT",
        "PENDING_DECISION, LIFTED",
        "EXECUTED, DRAFT",
        "EXECUTED, PENDING_DECISION",
        "LIFTED, DRAFT",
        "LIFTED, EXECUTED",
        "REJECTED, DRAFT",
        "REJECTED, EXECUTED"
    })
    @DisplayName("[B3-负例] 非法状态转移 → NF_STATE_INVALID 拒绝")
    void updateStatusGuarded_illegalTransition_rejected(String currentStatus, String targetStatus) {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status(currentStatus).build();
        when(mapper.selectById(33L)).thenReturn(row);

        assertThatThrownBy(() -> service.updateStatusGuarded(33L, targetStatus, LEADER))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.NF_STATE_INVALID);

        verify(mapper, never()).update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class));
    }

    /* ====================== 负例：参数校验 ====================== */

    @Test
    @DisplayName("[B3-负例] id=null → PARAM_INVALID")
    void updateStatusGuarded_nullId_paramInvalid() {
        assertThatThrownBy(() -> service.updateStatusGuarded(null, "EXECUTED", LEADER))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("[B3-负例] status=null → PARAM_INVALID")
    void updateStatusGuarded_nullStatus_paramInvalid() {
        assertThatThrownBy(() -> service.updateStatusGuarded(33L, null, LEADER))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    @Test
    @DisplayName("[B3-负例] status=空白 → PARAM_INVALID")
    void updateStatusGuarded_blankStatus_paramInvalid() {
        assertThatThrownBy(() -> service.updateStatusGuarded(33L, "  ", LEADER))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.PARAM_INVALID);
    }

    /* ====================== 负例：记录不存在 ====================== */

    @Test
    @DisplayName("[B3-负例] 记录不存在 → NOT_FOUND")
    void updateStatusGuarded_rowNotFound_notFound() {
        when(mapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.updateStatusGuarded(999L, "EXECUTED", LEADER))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.NOT_FOUND);
    }

    /* ====================== 负例：跨组越权 ====================== */

    @Test
    @DisplayName("[B3-负例] 跨组操作人 → FORBIDDEN（assertProjectReadable 守卫）")
    void updateStatusGuarded_crossGroup_forbidden() {
        NegativeFeedback row = NegativeFeedback.builder()
            .id(33L).projectId(7L).status("DRAFT").build();
        when(mapper.selectById(33L)).thenReturn(row);

        assertThatThrownBy(() -> service.updateStatusGuarded(33L, "PENDING_DECISION", OTHER_GROUP_LEADER))
            .isInstanceOf(IpdBusinessException.class)
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.FORBIDDEN);

        verify(mapper, never()).update(any(), ArgumentMatchers.any(LambdaUpdateWrapper.class));
    }
}
