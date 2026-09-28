package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.HandoverRecord;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.support.NoopTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R33 ChainSpec 一期（分片 C7）契约测试：HandoverService → ApprovalGuardSupport 骨架替换。
 *
 * <p>钉死四类契约（拆委托即红，见迁移报告「自证能红方案」）：
 * <ol>
 *   <li>六连拷贝收编后接线不漂移：create/accept/rollback 的 preCheck/registerPostCommit
 *       六元组与迁移前逐字一致（accept 的 from=DRAFT 在 setStatus 之前捕获）。</li>
 *   <li>requireCasHit 收敛后 CAS miss 仍抛 ServiceException 原文案且中止绑定（行为零变更）。</li>
 *   <li>requireFromState 收敛后异常类型（ServiceException）与文案逐字不变（accept/archive 两处）。</li>
 *   <li>setClock 双投 guardSupport：迁移前 occurredAt 取 now()，必须与本地时钟同源（逐字保真）。</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class HandoverGuardSupportContractTest {

    @Mock
    private ProjectMemberMapper memberMapper;
    @Mock
    private PersonMapper personMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private HandoverMapper handoverMapper;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private IProjectMemberService projectMemberService;
    @Mock
    private IpdAuthSession ipdAuthSession;
    @Mock
    private NotificationService notificationService;
    @Mock
    private StateMachineGuard stateMachineGuard;

    private static final IpdActor OPERATOR_FROM = new IpdActor(1L, "old-pm", "MARKET_PM", null);
    private static final IpdActor RECIPIENT = new IpdActor(2L, "new-pm", "MARKET_PM", null);

    /** 纯 JVM 单测无 MP 运行时：手动初始化 lambda 列缓存（create/accept/rollback 的 wrapper 列解析）。 */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Person.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, HandoverRecord.class);
    }

    private HandoverService newServiceWithGuard() {
        HandoverService service = new HandoverService(memberMapper, personMapper, projectMapper,
            handoverMapper, auditLogService, projectMemberService, NoopTransactionManager.INSTANCE,
            ipdAuthSession, notificationService);
        service.setStateMachineGuard(stateMachineGuard);
        return service;
    }

    private HandoverService newServiceWithoutGuard() {
        // 故意不调用 setStateMachineGuard——fail-closed 语义由 ApprovalGuardSupport 冻结保证
        return new HandoverService(memberMapper, personMapper, projectMapper,
            handoverMapper, auditLogService, projectMemberService, NoopTransactionManager.INSTANCE,
            ipdAuthSession, notificationService);
    }

    private HandoverRecord rec(String status) {
        return HandoverRecord.builder()
            .id(9L).handoverType("PROJECT").fromPersonId(1L).toPersonId(2L)
            .projectId(100L).handoverRole("MARKET_PM").status(status).build();
    }

    private Person eligibleRecipient() {
        Person p = new Person();
        p.setId(2L);
        p.setName("new-pm");
        p.setPersonType("MARKET_PM");
        p.setAccountStatus("ACTIVE");
        p.setEmploymentStatus("ACTIVE");
        return p;
    }

    private void stubCreateDraftHappyPath() {
        when(projectMapper.selectById(100L)).thenReturn(new Project());
        when(personMapper.selectById(2L)).thenReturn(eligibleRecipient());
        when(handoverMapper.selectCount(any())).thenReturn(0L);
    }

    // ===== ① 六连收编后的接线契约（六元组逐字） =====

    @Test
    @DisplayName("C7-契约1：createDraft 接线 preCheck(null→DRAFT)/registerPostCommit 六元组与迁移前逐字一致")
    void createWiresPreCheckAndPostCommit() {
        stubCreateDraftHappyPath();
        HandoverService service = newServiceWithGuard();

        service.initiate(100L, "MARKET_PM", 2L, "交接", OPERATOR_FROM);

        verify(stateMachineGuard).preCheck(eq("handover_record"), isNull(), eq("DRAFT"), eq("create"));
        verify(stateMachineGuard).postCommit(eq("handover_record"), isNull(), eq("DRAFT"),
            eq("create"), eq(1L), nullable(Long.class), any(Date.class));
    }

    @Test
    @DisplayName("C7-契约2：accept 接线 from=DRAFT（setStatus 前捕获）→COMPLETED 六元组")
    void acceptWiresGuardWithFromBeforeMutation() {
        when(handoverMapper.selectById(9L)).thenReturn(rec("DRAFT"));
        when(personMapper.selectById(2L)).thenReturn(eligibleRecipient());
        when(projectMemberService.exitForHandover(100L, 1L, "MARKET_PM")).thenReturn(1);
        when(projectMemberService.bindMember(eq(100L), eq(2L), eq("MARKET_PM"), eq("AP-1"), eq(RECIPIENT)))
            .thenReturn(ProjectMember.builder().id(77L).build());
        when(memberMapper.selectList(any())).thenReturn(List.of(ProjectMember.builder().build()));
        HandoverService service = newServiceWithGuard();

        HandoverRecord after = service.accept(9L, "AP-1", RECIPIENT);

        assertThat(after.getStatus()).isEqualTo("COMPLETED");
        verify(stateMachineGuard).preCheck("handover_record", "DRAFT", "COMPLETED", "accept");
        verify(stateMachineGuard).postCommit(eq("handover_record"), eq("DRAFT"), eq("COMPLETED"),
            eq("accept"), eq(2L), eq(9L), any(Date.class));
    }

    @Test
    @DisplayName("C7-契约3：rollback 接线 COMPLETED→ROLLED_BACK 六元组（24h 撤销窗业务逻辑零触碰）")
    void rollbackWiresGuardSixTuple() {
        Instant fixed = Instant.parse("2030-01-01T00:00:00Z");
        HandoverRecord completed = rec("COMPLETED");
        completed.setCompletedAt(Date.from(fixed));
        when(handoverMapper.selectById(9L)).thenReturn(completed);
        when(memberMapper.update(any(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        HandoverService service = newServiceWithGuard();
        // 固定时钟使 hoursSince=0（24h 撤销窗内）；发起人本人撤销（isInitiator 免组长/超管判定）
        service.setClock(Clock.fixed(fixed, ZoneId.systemDefault()));

        service.rollback(9L, "误操作", OPERATOR_FROM);

        verify(stateMachineGuard).preCheck("handover_record", "COMPLETED", "ROLLED_BACK", "rollback");
        verify(stateMachineGuard).postCommit(eq("handover_record"), eq("COMPLETED"), eq("ROLLED_BACK"),
            eq("rollback"), eq(1L), eq(9L), any(Date.class));
    }

    // ===== ② requireCasHit 收敛：CAS miss 行为逐字保真 =====

    @Test
    @DisplayName("C7-契约4：accept CAS miss→ServiceException 原文案且中止绑定（requireCasHit 收敛零变更）")
    void acceptCasMissThrowsOriginalMessageAndStopsBind() {
        when(handoverMapper.selectById(9L)).thenReturn(rec("DRAFT"));
        when(personMapper.selectById(2L)).thenReturn(eligibleRecipient());
        when(projectMemberService.exitForHandover(100L, 1L, "MARKET_PM")).thenReturn(0);
        HandoverService service = newServiceWithGuard();

        assertThatThrownBy(() -> service.accept(9L, "AP-1", RECIPIENT))
            .isInstanceOf(ServiceException.class)
            .hasMessage("原负责人在该项目已无此角色在任绑定，移交中止");
        verify(projectMemberService, never()).bindMember(any(), any(), any(), any(), any());
    }

    // ===== ③ requireFromState 收敛：异常类型与文案逐字 =====

    @Test
    @DisplayName("C7-契约5：accept requireFromState 文案逐字（不可重复处理）")
    void acceptRequireFromStateViolationMessageVerbatim() {
        when(handoverMapper.selectById(9L)).thenReturn(rec("COMPLETED"));
        HandoverService service = newServiceWithGuard();

        assertThatThrownBy(() -> service.accept(9L, "AP-1", RECIPIENT))
            .isInstanceOf(ServiceException.class)
            .hasMessage("移交已处理（状态 COMPLETED），不可重复处理");
    }

    @Test
    @DisplayName("C7-契约6：archive requireFromState 文案逐字（仅 COMPLETED 可归档）")
    void archiveRequireFromStateViolationMessageVerbatim() {
        HandoverRecord draft = rec("DRAFT");
        when(handoverMapper.selectById(9L)).thenReturn(draft);
        HandoverService service = newServiceWithGuard();

        // 项目组长 actor（GROUP_LEADER）非移交双方 → assertArchivePermission 命中组长分支
        when(projectMapper.selectById(100L)).thenReturn(projectOfGroup(20L));
        IpdActor leader = new IpdActor(5L, "组长", "GROUP_LEADER", 20L);

        assertThatThrownBy(() -> service.archiveCompletedHandover(9L, leader))
            .isInstanceOf(ServiceException.class)
            .hasMessage("仅 COMPLETED 移交可归档（当前 DRAFT）");
    }

    // ===== ④ fail-closed 与 Clock 投递口径 =====

    @Test
    @DisplayName("C7-契约7：守卫未装配 fail-closed（ServiceException 逐字文案，from=null 归一化）")
    void preCheckFailClosedWithoutGuard() {
        stubCreateDraftHappyPath();
        HandoverService bare = newServiceWithoutGuard();

        assertThatThrownBy(() -> bare.initiate(100L, "MARKET_PM", 2L, "交接", OPERATOR_FROM))
            .isInstanceOf(ServiceException.class)
            .hasMessage("状态机守卫未装配 entityType=handover_record from=null to=DRAFT");
    }

    @Test
    @DisplayName("C7-契约8：setClock 双投 guardSupport——postCommit occurredAt 与注入时钟同源（迁移前 now() 语义）")
    void setClockForwardedOccurredAtUsesInjectedClock() {
        when(handoverMapper.selectById(9L)).thenReturn(rec("DRAFT"));
        when(personMapper.selectById(2L)).thenReturn(eligibleRecipient());
        when(projectMemberService.exitForHandover(100L, 1L, "MARKET_PM")).thenReturn(1);
        when(projectMemberService.bindMember(eq(100L), eq(2L), eq("MARKET_PM"), eq("AP-1"), eq(RECIPIENT)))
            .thenReturn(ProjectMember.builder().id(77L).build());
        when(memberMapper.selectList(any())).thenReturn(List.of(ProjectMember.builder().build()));
        HandoverService service = newServiceWithGuard();
        // 固定在 2030 年：若有人拆掉 setClock 的双投委托，occurredAt 退回墙钟（2026）→ 本条红
        Clock fixed = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneId.systemDefault());
        service.setClock(fixed);

        service.accept(9L, "AP-1", RECIPIENT);

        ArgumentCaptor<Date> occurredAt = ArgumentCaptor.forClass(Date.class);
        verify(stateMachineGuard).postCommit(eq("handover_record"), eq("DRAFT"), eq("COMPLETED"),
            eq("accept"), eq(2L), eq(9L), occurredAt.capture());
        // 与注入时钟同源（迁移前 registerPostCommit 的 occurredAt=now()，逐字等价）
        assertThat(occurredAt.getValue()).isEqualTo(Date.from(fixed.instant()));
    }

    private static Project projectOfGroup(Long groupId) {
        Project project = new Project();
        project.setMainGroupId(groupId);
        return project;
    }
}
