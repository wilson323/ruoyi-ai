package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.SwitchingAcceptance;
import org.ruoyi.ipd.dto.SwitchingAcceptanceUnlockReq;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.ProjectScoreMapper;
import org.ruoyi.ipd.mapper.SwitchingAcceptanceMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionException;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-7.1 月度账务切换验收单测（合测）
 *
 * <p>覆盖 6 维度：
 * <ol>
 *   <li>正常路径：run → 5 类校验全过 + diffRate=0 + passed=true</li>
 *   <li>边界：month=YYYY-MM 格式校验；2026-9 拒</li>
 *   <li>异常：非超管 lock 抛 403；run 未执行直接 lock 抛 SWITCHING_NOT_RUN</li>
 *   <li>权限：run/get/list 内部全员；lock/unlock 仅超管</li>
 *   <li>幂等：同月重复 run → upsert；已 lock 幂等</li>
 *   <li>联动：isMonthLocked() 返回 true / false 正确判定</li>
 * </ol>
 *
 * <p>BR：BR-INC-12；AC：AC-INC-50/51。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class SwitchingAcceptanceServiceTest {

    @Mock private SwitchingAcceptanceMapper switchingAcceptanceMapper;
    @Mock private IpdPermission ipdPermission;

    /** P0-9 真实对账适配：6 个数据源 mock（空表 → 5 类校验全过；全对账走真实计算路径）。 */
    private AllowanceLedgerMapper allowanceLedgerMapper;
    private BonusPoolMapper bonusPoolMapper;
    private ProjectScoreMapper projectScoreMapper;
    private NegativeFeedbackMapper negativeFeedbackMapper;
    private ContributionMapper contributionMapper;
    private HandoverMapper handoverMapper;

    private SwitchingAcceptanceService service;

    private static final Long SUPER_ADMIN_ID = 9999L;
    private static final Long INTERNAL_USER_ID = 1234L;
    private static final String VALID_MONTH = "2026-09";
    private static final String INVALID_MONTH = "2026-9";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SwitchingAcceptanceService(switchingAcceptanceMapper, ipdPermission);
        // R28 P0-9 适配：runChecks 已改为真实对账，6 mapper 未注入时 fail-closed（passed=false）。
        // 测试注入空表 mock（selectList→空列表）走真实计算路径且全部通过。
        allowanceLedgerMapper = org.mockito.Mockito.mock(AllowanceLedgerMapper.class);
        bonusPoolMapper = org.mockito.Mockito.mock(BonusPoolMapper.class);
        projectScoreMapper = org.mockito.Mockito.mock(ProjectScoreMapper.class);
        negativeFeedbackMapper = org.mockito.Mockito.mock(NegativeFeedbackMapper.class);
        contributionMapper = org.mockito.Mockito.mock(ContributionMapper.class);
        handoverMapper = org.mockito.Mockito.mock(HandoverMapper.class);
        service.setAllowanceLedgerMapper(allowanceLedgerMapper);
        service.setBonusPoolMapper(bonusPoolMapper);
        service.setProjectScoreMapper(projectScoreMapper);
        service.setNegativeFeedbackMapper(negativeFeedbackMapper);
        service.setContributionMapper(contributionMapper);
        service.setHandoverMapper(handoverMapper);
        // lenient：仅 run 系用例真正消费，lock/unlock/isMonthLocked 用例不触 runChecks
        org.mockito.Mockito.lenient().when(allowanceLedgerMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(bonusPoolMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(projectScoreMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(negativeFeedbackMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(contributionMapper.selectList(any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(handoverMapper.selectList(any())).thenReturn(List.of());
    }

    private IpdActor adminActor() {
        return new IpdActor(SUPER_ADMIN_ID, "Admin", "SUPER_ADMIN", 0L);
    }

    private IpdActor internalActor() {
        return new IpdActor(INTERNAL_USER_ID, "User", "MARKET_PM", 10L);
    }

    private SwitchingAcceptance passedEntity() {
        return SwitchingAcceptance.builder()
            .id(1L)
            .month(VALID_MONTH)
            .ranAt(new Date())
            .ranBy(INTERNAL_USER_ID)
            .diffRate(BigDecimal.ZERO)
            .passed(true)
            .isLocked(false)
            .delFlag("0")
            .build();
    }

    /* ============================================================
     *  run：对账
     * ============================================================ */

    @Test
    @DisplayName("run：5 类校验全过 + diffRate=0 + passed=true")
    void run_cleanMonth_allChecksPassed() {
        when(ipdPermission.requireInternal()).thenReturn(internalActor());
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(null);
        when(switchingAcceptanceMapper.insert(any(SwitchingAcceptance.class))).thenAnswer(inv -> {
            SwitchingAcceptance e = inv.getArgument(0);
            e.setId(1L);
            return 1;
        });

        var report = service.run(VALID_MONTH);
        assertThat(report.passed()).isTrue();
        assertThat(report.diffRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(report.checks()).hasSize(5);
        assertThat(report.summary().get("totalChecks")).isEqualTo(5);
        assertThat(report.summary().get("passedChecks")).isEqualTo(5);
        assertThat(report.summary().get("failedChecks")).isEqualTo(0);
        assertThat(report.isLocked()).isFalse();
        verify(switchingAcceptanceMapper).insert(any(SwitchingAcceptance.class));
    }

    @Test
    @DisplayName("run：month 格式错（2026-9）→ PARAM_INVALID")
    void run_invalidMonth_throws() {
        when(ipdPermission.requireInternal()).thenReturn(internalActor());

        assertThatThrownBy(() -> service.run(INVALID_MONTH))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("YYYY-MM");
        verify(switchingAcceptanceMapper, never()).insert(any(SwitchingAcceptance.class));
    }

    @Test
    @DisplayName("run：month=null → PARAM_INVALID")
    void run_nullMonth_throws() {
        when(ipdPermission.requireInternal()).thenReturn(internalActor());

        assertThatThrownBy(() -> service.run(null))
            .isInstanceOf(IpdBusinessException.class);
    }

    @Test
    @DisplayName("run：数据源未注入 → 5 类校验全 fail-closed（passed=false，禁止假通过）")
    void run_dataSourceMissing_failClosed() {
        when(ipdPermission.requireInternal()).thenReturn(internalActor());
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(null);
        when(switchingAcceptanceMapper.insert(any(SwitchingAcceptance.class))).thenAnswer(inv -> 1);

        // 无 6 mapper 的裸服务：P0-9 真实对账后数据源缺失必须显式失败而非静默通过
        var bare = new SwitchingAcceptanceService(switchingAcceptanceMapper, ipdPermission);
        var report = bare.run(VALID_MONTH);
        assertThat(report.passed()).isFalse();
        assertThat(report.checks()).hasSize(5);
        assertThat(report.checks()).allSatisfy(c -> {
            assertThat(c.passed()).isFalse();
            assertThat(c.note()).contains("未注入");
        });
    }

    @Test
    @DisplayName("run：已存在的月份 → upsert（不抛）")
    void run_existingMonth_upsert() {
        when(ipdPermission.requireInternal()).thenReturn(internalActor());
        SwitchingAcceptance existing = passedEntity();
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(existing);

        var report = service.run(VALID_MONTH);
        assertThat(report.passed()).isTrue();
        verify(switchingAcceptanceMapper).updateById(any(SwitchingAcceptance.class));
    }

    @Test
    @DisplayName("run：已锁定的月份 → 跳过 update，返回原 report")
    void run_lockedMonth_skipUpdate() {
        when(ipdPermission.requireInternal()).thenReturn(internalActor());
        SwitchingAcceptance locked = passedEntity();
        locked.setIsLocked(true);
        locked.setLockedAt(new Date());
        locked.setLockedBy(SUPER_ADMIN_ID);
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(locked);

        var report = service.run(VALID_MONTH);
        assertThat(report.isLocked()).isTrue();
        verify(switchingAcceptanceMapper, never()).updateById(any(SwitchingAcceptance.class));
    }

    /* ============================================================
     *  lock：月度锁定
     * ============================================================ */

    @Test
    @DisplayName("lock：passed=true 后 lock 成功")
    void lock_passedReport_success() {
        when(ipdPermission.requireAdmin()).thenReturn(adminActor());
        SwitchingAcceptance entity = passedEntity();
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(entity);

        var report = service.lock(VALID_MONTH);
        assertThat(report.isLocked()).isTrue();
        assertThat(report.lockedBy()).isEqualTo(SUPER_ADMIN_ID);
        verify(switchingAcceptanceMapper).updateById(any(SwitchingAcceptance.class));
    }

    @Test
    @DisplayName("lock：passed=false → SWITCHING_DIFF_TOO_LARGE")
    void lock_failedReport_throws() {
        when(ipdPermission.requireAdmin()).thenReturn(adminActor());
        SwitchingAcceptance entity = passedEntity();
        entity.setPassed(false);
        entity.setDiffRate(new BigDecimal("0.0500"));
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(entity);

        assertThatThrownBy(() -> service.lock(VALID_MONTH))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("差异率");
        verify(switchingAcceptanceMapper, never()).updateById(any(SwitchingAcceptance.class));
    }

    @Test
    @DisplayName("lock：未 run 直接 lock → SWITCHING_NOT_RUN")
    void lock_notRun_throws() {
        when(ipdPermission.requireAdmin()).thenReturn(adminActor());
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.lock(VALID_MONTH))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("尚未运行对账");
    }

    @Test
    @DisplayName("lock：非超管 → 403")
    void lock_nonAdmin_rejected() {
        when(ipdPermission.requireAdmin())
            .thenThrow(new IpdPermissionException(403, org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN));

        assertThatThrownBy(() -> service.lock(VALID_MONTH))
            .isInstanceOf(IpdPermissionException.class);
    }

    @Test
    @DisplayName("lock：已锁定的月份 → 幂等返回")
    void lock_alreadyLocked_idempotent() {
        when(ipdPermission.requireAdmin()).thenReturn(adminActor());
        SwitchingAcceptance entity = passedEntity();
        entity.setIsLocked(true);
        entity.setLockedAt(new Date());
        entity.setLockedBy(SUPER_ADMIN_ID);
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(entity);

        var report = service.lock(VALID_MONTH);
        assertThat(report.isLocked()).isTrue();
        verify(switchingAcceptanceMapper, never()).updateById(any(SwitchingAcceptance.class));
    }

    /* ============================================================
     *  unlock：月度解锁
     * ============================================================ */

    @Test
    @DisplayName("unlock：附 reason 成功")
    void unlock_withReason_success() {
        when(ipdPermission.requireAdmin()).thenReturn(adminActor());
        SwitchingAcceptance entity = passedEntity();
        entity.setIsLocked(true);
        entity.setLockedAt(new Date());
        entity.setLockedBy(SUPER_ADMIN_ID);
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(entity);

        var report = service.unlock(VALID_MONTH, new SwitchingAcceptanceUnlockReq("事故恢复需要补录津贴"));
        assertThat(report.isLocked()).isFalse();
        assertThat(report.unlockReason()).isEqualTo("事故恢复需要补录津贴");
        assertThat(report.unlockedBy()).isEqualTo(SUPER_ADMIN_ID);
        verify(switchingAcceptanceMapper).updateById(any(SwitchingAcceptance.class));
    }

    @Test
    @DisplayName("unlock：reason 过短（<5 字符）→ PARAM_INVALID")
    void unlock_shortReason_throws() {
        when(ipdPermission.requireAdmin()).thenReturn(adminActor());

        assertThatThrownBy(() ->
                service.unlock(VALID_MONTH, new SwitchingAcceptanceUnlockReq("太短")))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("解锁理由");
    }

    @Test
    @DisplayName("unlock：未锁定的月份 → 幂等返回")
    void unlock_notLocked_idempotent() {
        when(ipdPermission.requireAdmin()).thenReturn(adminActor());
        SwitchingAcceptance entity = passedEntity();
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(entity);

        var report = service.unlock(VALID_MONTH, new SwitchingAcceptanceUnlockReq("理由够长写五个字"));
        assertThat(report.isLocked()).isFalse();
        verify(switchingAcceptanceMapper, never()).updateById(any(SwitchingAcceptance.class));
    }

    /* ============================================================
     *  isMonthLocked：联动判定
     * ============================================================ */

    @Test
    @DisplayName("isMonthLocked：存在锁定记录 → true")
    void isMonthLocked_locked_returnsTrue() {
        SwitchingAcceptance entity = passedEntity();
        entity.setIsLocked(true);
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(entity);

        assertThat(service.isMonthLocked(VALID_MONTH)).isTrue();
    }

    @Test
    @DisplayName("isMonthLocked：未 run → false")
    void isMonthLocked_notRun_returnsFalse() {
        when(switchingAcceptanceMapper.selectOne(any())).thenReturn(null);

        assertThat(service.isMonthLocked(VALID_MONTH)).isFalse();
    }

    @Test
    @DisplayName("isMonthLocked：month=null → false")
    void isMonthLocked_null_returnsFalse() {
        assertThat(service.isMonthLocked(null)).isFalse();
    }
}