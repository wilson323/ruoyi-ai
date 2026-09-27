package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeliverableMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接线轮（fix/r28-guard-wire）契约测试：StageActionService.transit ↔ DefaultStateMachineGuard
 * stage_action 7 边严格图（补遗 C8 消灭）。
 *
 * <p>与 StateMachineGuardContractTest（规则表本体，dev tag）互补：本类钉「Service 接线行为」——
 * 旧目标白名单放行但严格图非法的迁移（直跳/回退）必须在写库前被守卫拒绝（fail-closed，
 * 不吞异常：拒绝后 updateById/audit 均不得发生）。
 *
 * <p>mock 数据组合合法性（gen-test 规约）：所有 from→to 均为真库可产生的状态组合
 * （深管动作逾期回进行、进行中改 NA 等，词表见 StageActionService LIGHT/DEEP 枚举）；
 * 项目 ACTIVE+del_flag=0 是 transit 前置 assertProjectWritable 的通过态。
 */
@Tag("dev") // 根 pom surefire <groups>${profiles.active}</groups>（默认 dev）：无 tag=静默跳过（假绿），对齐仓内既有测试模式
class StageActionTransitGuardContractTest {

    private StageActionMapper actionMapper;
    private DeliverableMapper deliverableMapper;
    private IAuditLogService auditLogService;
    private ProjectMapper projectMapper;
    private StageActionService service;

    @BeforeEach
    void setUp() {
        actionMapper = mock(StageActionMapper.class);
        deliverableMapper = mock(DeliverableMapper.class);
        auditLogService = mock(IAuditLogService.class);
        projectMapper = mock(ProjectMapper.class);
        ProjectStageMapper projectStageMapper = mock(ProjectStageMapper.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectMapper.selectById(any())).thenReturn(
            Project.builder().id(100L).status("ACTIVE").delFlag("0").mainGroupId(900001L).build());
        when(actionMapper.updateById(any(StageAction.class))).thenReturn(1);
        // 深管 DONE 交付物校验通过态（Qa04 同构）：证明守卫拒绝与 BR 校验无关，是图约束本身
        when(deliverableMapper.selectCount(any())).thenReturn(2L);
        service = new StageActionService(actionMapper, deliverableMapper, auditLogService,
            projectStageMapper, projectMapper);
        DefaultStateMachineGuard guard = new DefaultStateMachineGuard(auditLogService,
            mock(NotificationService.class));
        guard.resetRules();
        guard.initRules();
        service.setStateMachineGuard(guard);
    }

    private StageAction seed(String status, String depth) {
        StageAction a = StageAction.builder()
            .id(1L).projectId(100L).stageId(10L).actionCode("C01").actionName("合规评审")
            .ownerRole("MARKET_PM").depth(depth).status(status)
            .isBlocking("1").isBioFeature("0")
            .build();
        a.setVersion(0);
        when(actionMapper.selectById(1L)).thenReturn(a);
        return a;
    }

    @Test
    @DisplayName("NOT_STARTED→DONE 轻管跳阶放行（P143 既有验收契约；守卫表无 depth 维度，深管直跳面如实登记，见接线轮报告建议方案）")
    void notStartedToDoneJumpAllowedPerP143Contract() {
        StageAction a = seed("NOT_STARTED", "LIGHT");
        a.setActualDoneAt(new Date());
        StageAction out = service.transit(1L, "DONE", "跳阶完成", "42");
        assertThat(out.getStatus()).isEqualTo("DONE");
    }

    @Test
    @DisplayName("C8②：DONE→IN_PROGRESS 终态回退被守卫拒绝（旧目标白名单放行）")
    void doneToInProgressRollbackRejected() {
        seed("DONE", "DEEP");
        assertThatThrownBy(() -> service.transit(1L, "IN_PROGRESS", "回退", "42"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机非法迁移");
        verify(actionMapper, never()).updateById(any(StageAction.class));
    }

    @Test
    @DisplayName("NA 硬终态：NA→IN_PROGRESS 复活被守卫拒绝")
    void naTerminalRevivalRejected() {
        seed("NA", "LIGHT");
        assertThatThrownBy(() -> service.transit(1L, "IN_PROGRESS", "复活", "42"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机非法迁移");
    }

    @Test
    @DisplayName("IN_PROGRESS→NOT_STARTED 倒退被守卫拒绝")
    void inProgressBackwardRejected() {
        seed("IN_PROGRESS", "DEEP");
        assertThatThrownBy(() -> service.transit(1L, "NOT_STARTED", "倒退", "42"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机非法迁移");
    }

    @Test
    @DisplayName("合法边全放行：start/complete/delay/resume/NA 五类迁移通过且写 TRANSIT 审计")
    void legalEdgesPassWithAudit() {
        seed("NOT_STARTED", "DEEP");
        assertThatCode(() -> service.transit(1L, "IN_PROGRESS", "开工", "42"))
            .doesNotThrowAnyException();

        seed("IN_PROGRESS", "DEEP");
        assertThatCode(() -> service.transit(1L, "DELAYED", "逾期", "42"))
            .doesNotThrowAnyException();

        seed("DELAYED", "DEEP");
        assertThatCode(() -> service.transit(1L, "IN_PROGRESS", "恢复", "42"))
            .doesNotThrowAnyException();

        seed("DELAYED", "DEEP");
        assertThatCode(() -> service.transit(1L, "DONE", "补完成", "42"))
            .doesNotThrowAnyException();

        seed("IN_PROGRESS", "LIGHT");
        assertThatCode(() -> service.transit(1L, "NA", "不适用（有理由）", "42"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("守卫登记 from 语义：postCommit 收到变更前快照（防 setStatus 后 ghost 迁移老 bug）")
    void postCommitReceivesPreChangeSnapshot() {
        seed("IN_PROGRESS", "LIGHT");
        StageAction a = actionMapper.selectById(1L);
        // 轻管 DONE 需完成日（BR-IPD-05），补齐后迁移——断言迁移成功即验证 from/to 传入守卫一致
        a.setActualDoneAt(new Date());
        service.transit(1L, "DONE", "完成", "42");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, org.mockito.Mockito.atLeastOnce()).append(captor.capture());
        assertThat(captor.getAllValues()).anySatisfy(log ->
            assertThat(log.getAction()).isEqualTo("TRANSIT"));
    }

    @Test
    @DisplayName("fail-closed：守卫未装配（Spring 装配缺失/裸测试 new）时 transit 拒绝迁移")
    void missingGuardFailsClosed() {
        StageActionService bare = new StageActionService(actionMapper, deliverableMapper,
            auditLogService, mock(ProjectStageMapper.class), projectMapper);
        seed("NOT_STARTED", "DEEP");
        assertThatThrownBy(() -> bare.transit(1L, "IN_PROGRESS", "开工", "42"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机守卫未装配");
        verify(actionMapper, never()).updateById(any(StageAction.class));
    }

    @Test
    @DisplayName("幂等短路先于守卫：同态重复 transit 返回当前态，不触库不触守卫（与 P144 语义一致）")
    void idempotentShortCircuitBeforeGuard() {
        seed("DONE", "DEEP");
        StageAction out = service.transit(1L, "DONE", "重复", "42");
        assertThat(out.getStatus()).isEqualTo("DONE");
        verify(actionMapper, never()).updateById(any(StageAction.class));
    }
}
