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
 *   <li>哨兵：ruleCount()==38（增删规则必须同步改此处+对应表驱动行；R24线settleTimeout-APPROVED与R25线DRAFT直分合入后 37→38）</li>
 *   <li>表驱动 38 条合法迁移全部放行（preCheck 不抛 + isAllowed=true）</li>
 *   <li>表驱动 16 条非法迁移全部拒绝（跳级/倒退/错误trigger/终态复活/跨机污染）</li>
 *   <li>横向契约：fail-closed / from=null→INITIAL（isAllowed 与 postCommit 双路径）/ 未登记 postCommit no-op</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class StateMachineGuardContractTest {

    @Mock
    private AuditLogService auditLogService;
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
    @DisplayName("哨兵：种子规则总数=38（8机：deletion 7+bonus 4+gate 6+launch 3+coef 3+contrib 5+handover 3+kpi 7）")
    void sentinelRuleCount() {
        // 增删规则必须同步修改本断言与下方表驱动行——防止规则表与测试悄然漂移
        // 2026-09-09 双线合并：R24线 settleTimeout-APPROVED（gate 5→6）+ R25线 DRAFT直分已并
        assertThat(guard.ruleCount())
            .as("规则总数变化=契约变更，必须显式过本测试 + code review")
            .isEqualTo(38);
    }

    /* ====================== 1. 表驱动：38 条合法迁移 ====================== */

    @ParameterizedTest(name = "[{index}] 合法 {0}")
    @CsvSource({
        // ---- deletion_request（7）----
        "deletion_request, DRAFT, LEADER_REVIEW, submit",
        "deletion_request, LEADER_REVIEW, ADMIN_REVIEW, leaderApprove",
        "deletion_request, LEADER_REVIEW, REJECTED, leaderReject",
        "deletion_request, ADMIN_REVIEW, DELETED, adminApprove",
        "deletion_request, ADMIN_REVIEW, REJECTED, adminReject",
        "deletion_request, LEADER_REVIEW, WITHDRAWN, withdraw",
        "deletion_request, LEADER_REVIEW, ADMIN_REVIEW, escalateOverdue",
        // ---- bonus_pool（4，含 2026-09-09 补登的 DRAFT 直分）----
        "bonus_pool, INITIAL, DRAFT, compute",
        "bonus_pool, DRAFT, CONFIRMED, freeze",
        "bonus_pool, CONFIRMED, DISTRIBUTED, distribute",
        "bonus_pool, DRAFT, DISTRIBUTED, distribute",
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
        // ---- coefficient_change（3）----
        "coefficient_change, INITIAL, PENDING_LEADER, propose",
        "coefficient_change, PENDING_LEADER, CONFIRMED, leaderApprove",
        "coefficient_change, PENDING_LEADER, REJECTED, leaderReject",
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
    })
    void legalTransitionAllowed(String entityType, String from, String to, String trigger) {
        assertThat(guard.isAllowed(entityType, from, to, trigger))
            .as("%s:%s->%s|%s 应登记在案", entityType, from, to, trigger)
            .isTrue();
        assertThatCode(() -> guard.preCheck(entityType, from, to, trigger))
            .as("%s:%s->%s|%s preCheck 应放行", entityType, from, to, trigger)
            .doesNotThrowAnyException();
    }

    /* ====================== 2. 表驱动：16 条非法迁移拒绝 ====================== */

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
        "bonus_pool, DISTRIBUTED, CONFIRMED, freeze",
        // 未登记倒退：CONFIRMED 解冻回 DRAFT
        "bonus_pool, CONFIRMED, DRAFT, unfreeze",
        // 跳级：INITIAL 直分（必须先 compute 成 DRAFT）
        "bonus_pool, INITIAL, CONFIRMED, distribute",
        // reopen 白名单外：APPROVED 不可 reopen（仅 REJECTED/ABSTAINED_TIMEOUT）
        "gate_review, APPROVED, PENDING, reopen",
        // 自环未登记
        "gate_review, PENDING, PENDING, sign",
        // 终态倒退：CONFIRMED 回签
        "launch_date_change, CONFIRMED, PENDING_SECOND, secondSign",
        // 自环未登记
        "coefficient_change, PENDING_LEADER, PENDING_LEADER, propose",
        // CONFIRMED 无 reject 路径（仅 SUBMITTED 可 reject）
        "contribution, CONFIRMED, DRAFT, reject",
        // 终态复活：ROLLED_BACK 重建
        "handover_record, ROLLED_BACK, DRAFT, create",
        // 终态回编辑：APPROVED 回 EDITING
        "kpi_record, APPROVED, EDITING, record",
        // 跨机污染：deletion 的规则不得套用到 kpi 机
        "kpi_record, DRAFT, LEADER_REVIEW, submit",
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
        // bonus_pool:INITIAL->DRAFT|compute 已登记；from=null 应映射到 INITIAL 后命中
        assertThat(guard.isAllowed("bonus_pool", null, "DRAFT", "compute")).isTrue();
        assertThatCode(() -> guard.preCheck("bonus_pool", null, "DRAFT", "compute"))
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
        guard.postCommit("bonus_pool", "DRAFT", "DISTRIBUTED", "skipSteps", 1L, 100L, new Date());
        verifyNoInteractions(auditLogService);
        verifyNoInteractions(notificationService);
    }
}
