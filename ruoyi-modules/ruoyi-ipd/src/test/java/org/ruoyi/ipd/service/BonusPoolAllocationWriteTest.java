package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.BonusAllocation;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Contribution;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.BonusAllocationMapper;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ContributionMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-DATA-gap-1 接线验收（业务裁决提案-20260908 A3）：distribute 翻状态后批量写
 * bonus_allocations 台账——分配对象 = 双 PM（MARKET_PM + RD_PM，exit_date IS NULL），
 * contribution_rate = 五维加权分（tierCoefficient）× 本方占比（marketShare/rdShare），
 * allocated_amount = finalPool × 本方占比，status = DRAFT。
 *
 * <p>幂等：DISTRIBUTED 终态直接返回不重复写台账。
 * R213-M1.1 owner 拍板（2026-09-24，卡 15d5e689）：贡献度未评定/未确认 →
 * distribute 业务拒绝（STATE_CONFLICT），不再存在“照写 null”路径，
 * 对齐 bonus_allocations.contribution_rate DDL NOT NULL 与 BR-INC-08。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class BonusPoolAllocationWriteTest {

    @Mock
    private BonusPoolMapper bonusPoolMapper;
    @Mock
    private BonusAllocationMapper bonusAllocationMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private ContributionMapper contributionMapper;
    @Mock
    private StateMachineGuard stateMachineGuard;

    private BonusPoolService service;

    @BeforeEach
    void setUp() {
        service = new BonusPoolService(bonusPoolMapper);
        service.setStateMachineGuard(stateMachineGuard);
        service.setBonusAllocationMapper(bonusAllocationMapper);
        service.setProjectMemberMapper(projectMemberMapper);
        service.setContributionMapper(contributionMapper);
    }

    private IpdActor actor() {
        return new IpdActor(900103L, "ipd-admin", "SUPER_ADMIN", 1L);
    }

    private ProjectMember pm(Long personId, String role) {
        ProjectMember m = new ProjectMember();
        m.setPersonId(personId);
        m.setRole(role);
        m.setExitDate(null);
        return m;
    }

    private Contribution confirmedContribution() {
        Contribution c = new Contribution();
        c.setTierCoefficient(new BigDecimal("0.80"));
        c.setMarketShare(new BigDecimal("0.55"));
        c.setRdShare(new BigDecimal("0.45"));
        c.setStatus(Contribution.ST_CONFIRMED);
        return c;
    }

    @Test
    @DisplayName("distribute：写 2 条 bonus_allocations，contributionRate=tier×本方占比，amount=pool×本方占比")
    void distribute_writes_dual_pm_allocations() {
        BonusPool pool = new BonusPool();
        pool.setId(600L);
        pool.setProjectId(200L);
        pool.setStatus(BonusPoolService.STATUS_DRAFT);
        pool.setFinalPool(new BigDecimal("1000000"));
        when(bonusPoolMapper.selectById(600L)).thenReturn(pool);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(
            pm(900103L, "MARKET_PM"), pm(900104L, "RD_PM")));
        when(contributionMapper.selectOne(any())).thenReturn(confirmedContribution());

        service.distribute(600L, new BigDecimal("0.55"), new BigDecimal("0.45"), actor());

        ArgumentCaptor<BonusAllocation> captor = ArgumentCaptor.forClass(BonusAllocation.class);
        verify(bonusAllocationMapper, times(2)).insert(captor.capture());
        List<BonusAllocation> rows = captor.getAllValues();

        BonusAllocation market = rows.get(0);
        assertThat(market.getBonusPoolId()).isEqualTo(600L);
        assertThat(market.getPersonId()).isEqualTo(900103L);
        assertThat(market.getRoleInProject()).isEqualTo("MARKET_PM");
        assertThat(market.getContributionRate()).isEqualByComparingTo("0.44");
        assertThat(market.getAllocatedAmount()).isEqualByComparingTo("550000");
        assertThat(market.getStatus()).isEqualTo("DRAFT");

        BonusAllocation rd = rows.get(1);
        assertThat(rd.getPersonId()).isEqualTo(900104L);
        assertThat(rd.getRoleInProject()).isEqualTo("RD_PM");
        assertThat(rd.getContributionRate()).isEqualByComparingTo("0.36");
        assertThat(rd.getAllocatedAmount()).isEqualByComparingTo("450000");
        assertThat(rd.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("distribute 门禁：无贡献度评定记录 → STATE_CONFLICT 拒绝，不翻状态不写台账")
    void distribute_contribution_missing_rejected() {
        BonusPool pool = new BonusPool();
        pool.setId(601L);
        pool.setProjectId(201L);
        pool.setStatus(BonusPoolService.STATUS_CONFIRMED);
        pool.setFinalPool(new BigDecimal("50000"));
        when(bonusPoolMapper.selectById(601L)).thenReturn(pool);
        when(contributionMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.distribute(601L, new BigDecimal("0.55"), new BigDecimal("0.45"), actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不允许分配奖金");

        assertThat(pool.getStatus()).isEqualTo(BonusPoolService.STATUS_CONFIRMED);
        verify(bonusAllocationMapper, never()).insert(any(BonusAllocation.class));
        verify(bonusPoolMapper, never()).updateById(any(BonusPool.class));
    }

    @Test
    @DisplayName("distribute 门禁：最新评定 SUBMITTED 未确认 → STATE_CONFLICT 拒绝")
    void distribute_contribution_not_confirmed_rejected() {
        BonusPool pool = new BonusPool();
        pool.setId(603L);
        pool.setProjectId(203L);
        pool.setStatus(BonusPoolService.STATUS_DRAFT);
        pool.setFinalPool(new BigDecimal("50000"));
        when(bonusPoolMapper.selectById(603L)).thenReturn(pool);
        Contribution submitted = confirmedContribution();
        submitted.setStatus(Contribution.ST_SUBMITTED);
        when(contributionMapper.selectOne(any())).thenReturn(submitted);

        assertThatThrownBy(() -> service.distribute(603L, new BigDecimal("0.55"), new BigDecimal("0.45"), actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("未 CONFIRMED");

        verify(bonusAllocationMapper, never()).insert(any(BonusAllocation.class));
    }

    @Test
    @DisplayName("distribute 门禁：CONFIRMED 但 tierCoefficient 缺失 → STATE_CONFLICT 拒绝")
    void distribute_contribution_missing_tierCoefficient_rejected() {
        BonusPool pool = new BonusPool();
        pool.setId(604L);
        pool.setProjectId(204L);
        pool.setStatus(BonusPoolService.STATUS_DRAFT);
        pool.setFinalPool(new BigDecimal("50000"));
        when(bonusPoolMapper.selectById(604L)).thenReturn(pool);
        Contribution noTier = confirmedContribution();
        noTier.setTierCoefficient(null);
        when(contributionMapper.selectOne(any())).thenReturn(noTier);

        assertThatThrownBy(() -> service.distribute(604L, new BigDecimal("0.55"), new BigDecimal("0.45"), actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("tierCoefficient");

        verify(bonusAllocationMapper, never()).insert(any(BonusAllocation.class));
    }

    @Test
    @DisplayName("distribute：DISTRIBUTED 终态幂等返回，不重复写台账")
    void distribute_idempotent_no_rewrite() {
        BonusPool pool = new BonusPool();
        pool.setId(602L);
        pool.setProjectId(202L);
        pool.setStatus(BonusPoolService.STATUS_DISTRIBUTED);
        pool.setDistributedAt(new Date());
        when(bonusPoolMapper.selectById(602L)).thenReturn(pool);

        service.distribute(602L, new BigDecimal("0.55"), new BigDecimal("0.45"), actor());

        verify(bonusAllocationMapper, times(0)).insert(any(BonusAllocation.class));
        verify(bonusPoolMapper, times(0)).updateById(any(BonusPool.class));
    }
}
