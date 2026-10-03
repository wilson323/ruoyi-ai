package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.StateTransitionRule;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 状态机守卫全量契约测试（C3 根除机制，2026-09-09）
 *
 * <p>病根背景：守卫表 37 条规则与 Service 接线长期靠"人肉对账"，出现过 4 类真缺陷——
 * R5 adminReject 标 crossDomain=true 但驳回分支漏调 postCommit、escalateOverdue 的
 * preCheck 挂在 UPDATE 之后、BonusPoolService.distribute 硬编码假 from 绕过守卫、
 * postCommit 缺 null→INITIAL 归一化导致跨域审计静默丢失。本测试把规则表钉成
 * 「单一事实源 + 哨兵」：任何规则增删必须显式改本文件，杜绝顺手漂移。
 *
 * <p>覆盖维度：
 * <ol>
 *   <li>哨兵：ruleCount()==89（增删规则必须同步改此处+对应表驱动行；R24线settleTimeout-APPROVED与R25线DRAFT直分合入后 37→38；R28线requirement_change接线4条 38→42；后补登至 50；R33 一期 kpi_shared_confirm 3条 50→53；D-1 批次 6 机 36 条 53→89）</li>
 *   <li>表驱动 89 条合法迁移全部放行（preCheck 不抛 + isAllowed=true；通配 *->CLOSED|close 展开为逐 from 语义行）</li>
 *   <li>表驱动 22 条非法迁移全部拒绝（跳级/倒退/错误trigger/终态复活/跨机污染）</li>
 *   <li>横向契约：fail-closed / from=null→INITIAL（isAllowed 与 postCommit 双路径）/ 未登记 postCommit no-op</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class StateMachineGuardContractTest {

    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private NotificationService notificationService;

    private DefaultStateMachineGuard guard;

    @BeforeEach
    void setUp() {
        guard = new DefaultStateMachineGuard(auditLogService, notificationService);
        guard.resetRules();
        guard.initRules();
    }

    /* ====================== 0. 哨兵：规则数 ====================== */

    @Test
    @DisplayName("哨兵：种子规则总数=86（93 − 奖金池4 + 系数变更3，随「算钱」层下线）")
    void sentinelRuleCount() {
        // 增删规则必须同步修改本断言与下方表驱动行——防止规则表与测试悄然漂移
        // 2026-09-09 双线合并：R24线 settleTimeout-APPROVED（gate 5→6）+ R25线 DRAFT直分已并
        // 2026-09-09 R28线：requirement_change 4 迁移点接线（INITIAL->DRAFT/PENDING_SIGN/REJECTED/APPROVED）
        // 接线轮（fix/r28-guard-wire）：stage_action 7 边登记，transit 由目标白名单升级为 from→to 严格图（C8）
        // R33 一期（2026-09-27）：kpi_shared_confirm 接线 3 条（create/recapture/secondSign）50→53
        // D-1 批次（蜂群 SWARM-A）：6 台 36 条接线规则 53→89
        // 产品线开工合同：创建待开工/批准/拒绝/重新提交四边，89→93。
        // 「算钱」层下线：移除 bonus_pool 4 条 + coefficient_change 3 条，93→86。
        assertThat(guard.ruleCount())
            .as("规则总数变化=契约变更，必须显式过本测试 + code review")
            .isEqualTo(86);
    }

    /* ====================== 1. 表驱动：86 条规则合法迁移 ====================== */

    @ParameterizedTest(name = "[{index}] 合法 {0}")
    @CsvSource({
        // 产品线负责人开工审批（业务合同：批准后才生成阶段；拒绝保留项目再次提交）
        "project, INITIAL, PENDING_START, create",
        "project, PENDING_START, TEAMING, approveStart",
        "project, PENDING_START, START_REJECTED, rejectStart",
        "project, START_REJECTED, PENDING_START, resubmitStart",
        // ---- deletion_request（7）----
        "deletion_request, DRAFT, LEADER_REVIEW, submit",
        "deletion_request, LEADER_REVIEW, ADMIN_REVIEW, leaderApprove",
        "deletion_request, LEADER_REVIEW, REJECTED, leaderReject",
        "deletion_request, ADMIN_REVIEW, DELETED, adminApprove",
        "deletion_request, ADMIN_REVIEW, REJECTED, adminReject",
        "deletion_request, LEADER_REVIEW, WITHDRAWN, withdraw",
        "deletion_request, LEADER_REVIEW, ADMIN_REVIEW, escalateOverdue",
        // ---- gate_review（6，含 R24线 settleTimeout-APPROVED 补登）----
        "gate_review, PENDING, APPROVED, sign",
        "gate_review, PENDING, REJECTED, sign",
        "gate_review, PENDING, ABSTAINED_TIMEOUT, settleTimeout",
        "gate_review, PENDING, APPROVED, settleTimeout",   // R24线补登：签署超时一方弃权按主导方意见执行
        "gate_review, REJECTED, PENDING, reopen",
        "gate_review, ABSTAINED_TIMEOUT, PENDING, reopen",
        // ---- launch_date_change（3）----
        "launch_date_change, INITIAL, PENDING_SECOND, propose",
        "launch_date_change, PENDING_SECOND, CONFIRMED, secondSign",
        "launch_date_change, PENDING_SECOND, REJECTED, secondSign",
        // ---- contribution（5）----
        "contribution, INITIAL, DRAFT, fill",
        "contribution, DRAFT, SUBMITTED, submit",
        "contribution, SUBMITTED, CONFIRMED, confirm",
        "contribution, SUBMITTED, DRAFT, reject",
        "contribution, DRAFT, CONFIRMED, confirm",
        // ---- handover_record（3）----
        "handover_record, INITIAL, DRAFT, create",
        "handover_record, DRAFT, COMPLETED, accept",
        "handover_record, COMPLETED, ROLLED_BACK, rollback",
        // ---- kpi_record（7）----
        "kpi_record, INITIAL, EDITING, record",
        "kpi_record, EDITING, EDITING, record",
        "kpi_record, EDITING, APPROVED, approve",
        "kpi_record, PENDING_REVIEW, APPROVED, approve",
        "kpi_record, EDITING, REJECTED, reject",
        "kpi_record, PENDING_REVIEW, REJECTED, reject",
        "kpi_record, EDITING, ARCHIVED, archive",
        // ---- kpi_shared_confirm（3，R33 一期 C5 补接线：首签自环不迁移状态不登记）----
        "kpi_shared_confirm, INITIAL, PENDING, create",
        "kpi_shared_confirm, CONFIRMED, PENDING, recapture",
        "kpi_shared_confirm, PENDING, CONFIRMED, secondSign",
        // ---- requirement_change（4，R28线接线：create/submit/reject/sign）----
        "requirement_change, INITIAL, DRAFT, create",
        "requirement_change, DRAFT, PENDING_SIGN, submit",
        "requirement_change, PENDING_SIGN, REJECTED, reject",
        "requirement_change, PENDING_SIGN, APPROVED, sign",
        // ---- stage_action（8，接线轮 C8：from→to 严格图，DONE/NA 硬终态）----
        "stage_action, NOT_STARTED, IN_PROGRESS, start",
        "stage_action, NOT_STARTED, DONE, complete",   // P143 轻管跳阶验收契约
        "stage_action, IN_PROGRESS, DONE, complete",
        "stage_action, DELAYED, DONE, complete",
        "stage_action, IN_PROGRESS, DELAYED, delay",
        "stage_action, DELAYED, IN_PROGRESS, resume",
        "stage_action, NOT_STARTED, NA, mark_na",
        "stage_action, IN_PROGRESS, NA, mark_na",
        // ---- D-1 批次：project（9）----
        "project, INITIAL, DRAFT, create",
        "project, DRAFT, TEAMING, changeStatus",
        "project, DRAFT, ARCHIVED, changeStatus",
        "project, TEAMING, ACTIVE, changeStatus",
        "project, TEAMING, ARCHIVED, changeStatus",
        "project, ACTIVE, SUSPENDED, changeStatus",
        "project, ACTIVE, ARCHIVED, changeStatus",
        "project, SUSPENDED, ACTIVE, changeStatus",
        "project, SUSPENDED, ARCHIVED, changeStatus",
        // ---- D-1 批次：requirement_v2（10，trigger 统一 transition）----
        "requirement_v2, DRAFT, SUBMITTED, transition",
        "requirement_v2, DRAFT, REJECTED, transition",
        "requirement_v2, SUBMITTED, ROUTED, transition",
        "requirement_v2, SUBMITTED, REJECTED, transition",
        "requirement_v2, ROUTED, ACCEPTED, transition",
        "requirement_v2, ROUTED, REJECTED, transition",
        "requirement_v2, ACCEPTED, CHANGED, transition",
        "requirement_v2, ACCEPTED, REJECTED, transition",
        "requirement_v2, CHANGED, CLOSED, transition",
        "requirement_v2, CHANGED, REJECTED, transition",
        // ---- D-1 批次：bid_invitation（6，含 *->CLOSED 通配终态收敛）----
        "bid_invitation, INITIAL, OPEN, create",
        "bid_invitation, OPEN, SELECTED, select",
        "bid_invitation, OPEN, EXPIRED, expire",
        "bid_invitation, OPEN, CLOSED, withdraw",
        "bid_invitation, *, CLOSED, close",
        "bid_invitation, EXPIRED, SELECTED, adminAssign",
        // 通配展开语义行（isTerminalState(BID_INVITATION_TERMINAL=CLOSED) 配套）：close() 宽进
        "bid_invitation, SELECTED, CLOSED, close",
        "bid_invitation, EXPIRED, CLOSED, close",
        "bid_invitation, CLOSED, CLOSED, close",
        // ---- D-1 批次：bid_response（4）----
        "bid_response, INITIAL, PENDING, respond",
        "bid_response, PENDING, ACCEPTED, select",
        "bid_response, PENDING, REJECTED, select",
        "bid_response, PENDING, WITHDRAWN, withdraw",
        // ---- D-1 批次：guest_demand（2）----
        "guest_demand, INITIAL, SUBMITTED, submit",
        "guest_demand, SUBMITTED, WITHDRAWN, withdraw",
        // ---- D-1 批次：negative_feedback（5）----
        "negative_feedback, INITIAL, DRAFT, create",
        "negative_feedback, DRAFT, PENDING_DECISION, submit",
        "negative_feedback, PENDING_DECISION, EXECUTED, decide",
        "negative_feedback, PENDING_DECISION, REJECTED, decide",
        "negative_feedback, EXECUTED, LIFTED, lift",
    })
    void legalTransitionAllowed(String entityType, String from, String to, String trigger) {
        assertThat(guard.isAllowed(entityType, from, to, trigger))
            .as("%s:%s->%s|%s 应登记在案", entityType, from, to, trigger)
            .isTrue();
        assertThatCode(() -> guard.preCheck(entityType, from, to, trigger))
            .as("%s:%s->%s|%s preCheck 应放行", entityType, from, to, trigger)
            .doesNotThrowAnyException();
    }

    /* ====================== 2. 表驱动：22 条非法迁移拒绝 ====================== */

    @ParameterizedTest(name = "[{index}] 非法 {0}:{1}->{2}|{3}")
    @CsvSource({
        // 跳级：DRAFT 直跳 ADMIN_REVIEW（绕过组长初审）
        "deletion_request, DRAFT, ADMIN_REVIEW, leaderApprove",
        // 倒退：ADMIN_REVIEW 退回 LEADER_REVIEW
        "deletion_request, ADMIN_REVIEW, LEADER_REVIEW, leaderApprove",
        // 错误 trigger：同 (from,to) 但 trigger 未登记
        "deletion_request, DRAFT, LEADER_REVIEW, wrongTrigger",
        // 终态复活：DELETED 复活
        "deletion_request, DELETED, DRAFT, reopen",
        // 终态复活：WITHDRAWN 再提交
        "deletion_request, WITHDRAWN, LEADER_REVIEW, submit",
        // 终态倒退：DISTRIBUTED 退 CONFIRMED
        // 未登记倒退：CONFIRMED 解冻回 DRAFT
        // 跳级：INITIAL 直分（必须先 compute 成 DRAFT）
        // reopen 白名单外：APPROVED 不可 reopen（仅 REJECTED/ABSTAINED_TIMEOUT）
        "gate_review, APPROVED, PENDING, reopen",
        // 自环未登记
        "gate_review, PENDING, PENDING, sign",
        // 终态倒退：CONFIRMED 回签
        "launch_date_change, CONFIRMED, PENDING_SECOND, secondSign",
        // 自环未登记
        // CONFIRMED 无 reject 路径（仅 SUBMITTED 可 reject）
        "contribution, CONFIRMED, DRAFT, reject",
        // 终态复活：ROLLED_BACK 重建
        "handover_record, ROLLED_BACK, DRAFT, create",
        // 终态回编辑：APPROVED 回 EDITING
        "kpi_record, APPROVED, EDITING, record",
        // 跨机污染：deletion 的规则不得套用到 kpi 机
        "kpi_record, DRAFT, LEADER_REVIEW, submit",
        // 跳级：DRAFT 直跳 APPROVED（必须双签，绕过 PENDING_SIGN）
        "requirement_change, DRAFT, APPROVED, sign",
        // 终态复活：APPROVED 回 PENDING_SIGN（单方 APPROVE 不回退，变更单生效后不可逆）
        "requirement_change, APPROVED, PENDING_SIGN, submit",
        // 未登记：DELAYED 直标 NA（须先 resume 回 IN_PROGRESS 再判 NA）
        "stage_action, DELAYED, NA, mark_na",
        // C8 消灭②：终态回退——DONE 回 IN_PROGRESS（旧目标白名单放行，严格图拒绝）
        "stage_action, DONE, IN_PROGRESS, resume",
        // 终态复活：NA 再开工
        "stage_action, NA, IN_PROGRESS, start",
        // 倒退：IN_PROGRESS 退回 NOT_STARTED
        "stage_action, IN_PROGRESS, NOT_STARTED, start",
    })
    void illegalTransitionRejected(String entityType, String from, String to, String trigger) {
        assertThat(guard.isAllowed(entityType, from, to, trigger))
            .as("%s:%s->%s|%s 不应登记", entityType, from, to, trigger)
            .isFalse();
        assertThatThrownBy(() -> guard.preCheck(entityType, from, to, trigger))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("状态机非法迁移");
    }

    /* ====================== 3. 横向契约 ====================== */

    @Test
    @DisplayName("fail-closed：未知 entityType 一律拒绝（未登记即拒绝，无白名单兜底）")
    void unknownEntityTypeFailClosed() {
        assertThat(guard.isAllowed("nonexistent_machine", "A", "B", "fire")).isFalse();
        assertThatThrownBy(() -> guard.preCheck("nonexistent_machine", "A", "B", "fire"))
            .isInstanceOf(IpdBusinessException.class);
    }

    @Test
    @DisplayName("from=null→INITIAL：isAllowed 路径（Java null 表示创建迁移）")
    void nullFromMappedToInitialIsAllowed() {
        // contribution:INITIAL->DRAFT|fill 已登记；from=null 应映射到 INITIAL 后命中
        // （样本原为 bonus_pool:INITIAL->DRAFT|compute，该规则随「算钱」层下线移除；
        //   本用例验的是「from=null→INITIAL」这一通用归一化行为，与具体状态机无关，故换同型规则。）
        assertThat(guard.isAllowed("contribution", null, "DRAFT", "fill")).isTrue();
        assertThatCode(() -> guard.preCheck("contribution", null, "DRAFT", "fill"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("from=null→INITIAL：postCommit 路径回归（2026-09-09 缺陷修复前 key 拼 null 永远 miss，跨域审计静默丢失）")
    void nullFromPostCommitNormalizationRegression() {
        // 热加载一条 crossDomain=true 的创建迁移规则（模拟未来 service 以 from=null 调 postCommit）
        guard.registerRule(StateTransitionRule.builder()
            .key("test_entity:INITIAL->LIVE|create")
            .entityType("test_entity")
            .fromState("INITIAL")
            .toState("LIVE")
            .trigger("create")
            .crossDomain(true)
            .description("回归测试：null from 归一化")
            .build());

        // 修复前：postCommit 拼 "test_entity:null->LIVE|create" miss → no-op，审计丢失
        // 修复后：null→INITIAL 归一化 → 命中规则 → 写跨域审计
        guard.postCommit("test_entity", null, "LIVE", "create", 1L, 100L, new Date());

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        AuditLog written = captor.getValue();
        assertThat(written.getAction()).isEqualTo("CROSS_DOMAIN_TRANSITION");
        assertThat(written.getReason()).contains("from=INITIAL");
    }

    @Test
    @DisplayName("postCommit 收到未登记迁移：no-op 不抛不写审计（已提交事务不可反向破坏）")
    void postCommitUnregisteredNoOp() {
        guard.postCommit("contribution", "DRAFT", "DISTRIBUTED", "skipSteps", 1L, 100L, new Date());
        verifyNoInteractions(auditLogService);
        verifyNoInteractions(notificationService);
    }
}
