package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BonusPool;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.BonusPoolMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.math.BigDecimal;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-4.4 奖金池核算 HTTP 端点单测（service 层）
 *
 * <p>覆盖 6 维度：
 * <ol>
 *   <li>正常路径：compute 完整公式 4 因子 / freeze DRAFT→CONFIRMED / distribute 比例拆分</li>
 *   <li>边界：DISTRIBUTED 终态冻结幂等返回 / 0 回款 finalPool=0</li>
 *   <li>异常：projectId 不存在 / actualReceipts<0 / marketShare<0.40 / rdShare>0.60 / 项目无 levelCoefficient</li>
 *   <li>权限：本卡 service 层不测（IpdPermission 在 controller 层测）</li>
 *   <li>审计：freeze + distribute 各落 1 条 audit_log（用 mock verify）</li>
 *   <li>幂等：freeze 重复调用只写 1 次 / distribute 重复 DISTRIBUTED 不重写审计</li>
 * </ol>
 *
 * <p>AC：AC-INC-16/17/18/19/20/21；BR：BR-INC-04/05/06。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class BonusPoolServiceTest {

    @Mock
    private BonusPoolMapper bonusPoolMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private IAuditLogService auditLogService;
    /** ROOT-R3-P0-1 修复：跨状态机守卫 mock（fail-closed 改造后必显式注入，否则 preCheckGuard 抛 IpdBusinessException） */
    @Mock
    private StateMachineGuard stateMachineGuard;
    /** R149 A1：注入 mock 读取 {@code bonus.windowMonths}（默认 6） */
    @Mock
    private ISystemConfigService systemConfigService;

    private BonusPoolService service;

    @BeforeEach
    void setUp() {
        service = new BonusPoolService(bonusPoolMapper, projectMapper);
        service.setAuditLogService(auditLogService);
        // ROOT-R3-P0-1 修复：注入 mock 守卫（fail-closed 改造后，preCheckGuard 必显式 fail-fast）
        service.setStateMachineGuard(stateMachineGuard);
        // R149 A1：注入 mock ISystemConfigService（默认未 stub 时回退 DEFAULT_WINDOW_MONTHS=6）
        service.setSystemConfigService(systemConfigService);
    }

    /** 测试用 actor（SUPER_ADMIN，涉钱审批权） */
    private IpdActor actor() {
        return new IpdActor(1001L, "测试超管", "SUPER_ADMIN", 1L);
    }

    /** S 级 1.8 系数的项目（公式常用 fixture） */
    private Project sLevelProject() {
        Project p = new Project();
        p.setId(200L);
        p.setLevel("S");
        p.setLevelCoefficient(new BigDecimal("1.8"));
        return p;
    }

    /* ====================== 正常路径 ====================== */

    @Test
    @DisplayName("compute：S 级 1.8 + 达成率 100% + 个人绩效 1.2 → 完整公式 4 因子叠加落 DRAFT")
    void compute_fullFormula_sLevel_100Percent_personalCoefficient() {
        when(projectMapper.selectById(200L)).thenReturn(sLevelProject());

        BonusPool pool = service.compute(
            200L,
            new BigDecimal("10000000"),  // actualReceipts
            new BigDecimal("100"),       // achievementRate → tier=1.0
            new BigDecimal("1.2"),       // personalCoefficient
            new BigDecimal("0.05"),
            actor()                      // W4-B：compute 现在收 actor 落审计
        );

        // 10000000 × 0.05 × 1.8 × 1.0 × 1.2 = 1080000
        assertThat(pool.getFinalPool()).isEqualByComparingTo(new BigDecimal("1080000"));
        assertThat(pool.getStatus()).isEqualTo(BonusPoolService.STATUS_DRAFT);
        assertThat(pool.getTierCoefficient()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(pool.getCoefficient()).isEqualByComparingTo(new BigDecimal("1.8"));
        // 验证 insert 被调用
        verify(bonusPoolMapper, times(1)).insert(pool);
    }

    @Test
    @DisplayName("freeze：DRAFT → CONFIRMED 状态流转，落 1 条 audit")
    void freeze_draftToConfirmed_appendsAudit() {
        BonusPool existing = new BonusPool();
        existing.setId(500L);
        existing.setProjectId(200L);
        existing.setStatus(BonusPoolService.STATUS_DRAFT);
        existing.setFinalPool(new BigDecimal("500000"));
        when(bonusPoolMapper.selectById(500L)).thenReturn(existing);
        // R217 拍板C 配套守卫：freeze 前置校验池关联项目（projectId=200）存在
        when(projectMapper.selectById(200L)).thenReturn(sLevelProject());

        BonusPool result = service.freeze(500L, "审批通过", actor());

        assertThat(result.getStatus()).isEqualTo(BonusPoolService.STATUS_CONFIRMED);
        verify(bonusPoolMapper, times(1)).updateById(any(BonusPool.class));

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).append(auditCaptor.capture());
        AuditLog audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo(BonusPoolService.ACTION_FREEZE);
        assertThat(audit.getEntityType()).isEqualTo("bonus_pools");
        assertThat(audit.getEntityId()).isEqualTo(500L);
        assertThat(audit.getOperatorRole()).isEqualTo("SUPER_ADMIN");
        assertThat(audit.getReason()).contains("DRAFT→CONFIRMED");
    }

    @Test
    @DisplayName("distribute：§三.2.4 比例 0.55/0.45 → marketAmount=pool×0.55，rdAmount=pool×0.45，status=DISTRIBUTED")
    void distribute_zkFormula_section324_split() {
        BonusPool existing = new BonusPool();
        existing.setId(600L);
        existing.setProjectId(200L);
        existing.setStatus(BonusPoolService.STATUS_DRAFT);
        existing.setFinalPool(new BigDecimal("1000000"));
        when(bonusPoolMapper.selectById(600L)).thenReturn(existing);
        // R217 拍板C 配套守卫：distribute 前置校验池关联项目（projectId=200）存在
        when(projectMapper.selectById(200L)).thenReturn(sLevelProject());

        BonusPool result = service.distribute(
            600L,
            new BigDecimal("0.55"),  // marketShare
            new BigDecimal("0.45"),  // rdShare
            actor()
        );

        assertThat(result.getStatus()).isEqualTo(BonusPoolService.STATUS_DISTRIBUTED);
        assertThat(result.getDistributions()).contains("\"marketAmount\":550000");
        assertThat(result.getDistributions()).contains("\"rdAmount\":450000");
        assertThat(result.getDistributedAt()).isNotNull();
        verify(bonusPoolMapper, times(1)).updateById(any(BonusPool.class));

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).append(auditCaptor.capture());
        AuditLog audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo(BonusPoolService.ACTION_DISTRIBUTE);
        assertThat(audit.getReason()).contains("DRAFT→DISTRIBUTED market=0.55 rd=0.45");
    }

    /* ====================== 边界 ====================== */

    @Test
    @DisplayName("distribute 幂等：已是 DISTRIBUTED 再调 → 不重写审计，返回当前实体")
    void distribute_alreadyDistributed_isIdempotent() {
        BonusPool existing = new BonusPool();
        existing.setId(700L);
        existing.setStatus(BonusPoolService.STATUS_DISTRIBUTED);
        existing.setFinalPool(new BigDecimal("1000000"));
        existing.setDistributedAt(new Date());
        when(bonusPoolMapper.selectById(700L)).thenReturn(existing);

        BonusPool result = service.distribute(700L,
            new BigDecimal("0.55"), new BigDecimal("0.45"), actor());

        assertThat(result.getStatus()).isEqualTo(BonusPoolService.STATUS_DISTRIBUTED);
        // 幂等：不写第二条审计 + 不 updateById
        verify(auditLogService, never()).append(any(AuditLog.class));
        verify(bonusPoolMapper, never()).updateById(any(BonusPool.class));
    }

    @Test
    @DisplayName("compute 边界：actualReceipts=0 → finalPool=0，不抛错")
    void compute_zeroReceipts_returnsZeroPool() {
        when(projectMapper.selectById(200L)).thenReturn(sLevelProject());

        BonusPool pool = service.compute(200L,
            BigDecimal.ZERO,
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor());

        assertThat(pool.getFinalPool()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(pool.getStatus()).isEqualTo(BonusPoolService.STATUS_DRAFT);
    }

    /* ====================== 异常 ====================== */

    @Test
    @DisplayName("compute 异常：projectId 不存在 → ServiceException")
    void compute_projectNotFound_throwsServiceException() {
        when(projectMapper.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> service.compute(999L,
            new BigDecimal("1000000"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor()))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("项目不存在");
    }

    @Test
    @DisplayName("compute 异常：项目无 levelCoefficient（G1 双签未完成） → ServiceException")
    void compute_projectMissingLevelCoefficient_throwsServiceException() {
        Project p = new Project();
        p.setId(300L);
        p.setLevel("S");
        p.setLevelCoefficient(null);  // G1 双签未完成
        when(projectMapper.selectById(300L)).thenReturn(p);

        assertThatThrownBy(() -> service.compute(300L,
            new BigDecimal("1000000"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor()))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("S/A/B 差异化系数未配置");
    }

    @Test
    @DisplayName("compute 异常：actualReceipts<0 → IpdBusinessException(PARAM_INVALID)")
    void compute_negativeReceipts_throwsIpdBusinessException() {
        assertThatThrownBy(() -> service.compute(200L,
            new BigDecimal("-1"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不能为负");
    }

    @Test
    @DisplayName("distribute 异常：marketShare<0.40 → ServiceException（§三.2.4 比例越界）")
    void distribute_marketShareBelowRange_throwsServiceException() {
        BonusPool existing = new BonusPool();
        existing.setId(800L);
        existing.setStatus(BonusPoolService.STATUS_DRAFT);
        existing.setFinalPool(new BigDecimal("1000000"));
        when(bonusPoolMapper.selectById(800L)).thenReturn(existing);

        assertThatThrownBy(() -> service.distribute(800L,
            new BigDecimal("0.30"),  // < 0.40
            new BigDecimal("0.70"),
            actor()))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("40%-65%");
    }

    @Test
    @DisplayName("getById 异常：奖金池不存在 → IpdBusinessException(NOT_FOUND)")
    void getById_notFound_throwsIpdBusinessException() {
        when(bonusPoolMapper.selectById(404L)).thenReturn(null);

        assertThatThrownBy(() -> service.getById(404L))
            .isInstanceOf(IpdBusinessException.class);
    }

    /* ====================== R217 拍板C 配套：freeze/distribute 项目存在性守卫 ====================== */

    @Test
    @DisplayName("[R217-GATE] freeze：池关联项目物理不存在（幽灵 9140004）→ STATE_CONFLICT 拒绝，零状态写入")
    void freeze_projectMissing_rejected() {
        BonusPool existing = new BonusPool();
        existing.setId(910L);
        existing.setProjectId(9140004L);
        existing.setStatus(BonusPoolService.STATUS_DRAFT);
        existing.setFinalPool(new BigDecimal("42000"));
        when(bonusPoolMapper.selectById(910L)).thenReturn(existing);
        when(projectMapper.selectById(9140004L)).thenReturn(null);

        assertThatThrownBy(() -> service.freeze(910L, "幽灵项目池", actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("奖金池关联项目不存在或已归档")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
        verify(bonusPoolMapper, never()).updateById(any(BonusPool.class));
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("[R217-GATE] distribute：池关联项目不存在 → STATE_CONFLICT 拒绝，不写台账/审计")
    void distribute_projectMissing_rejected() {
        BonusPool existing = new BonusPool();
        existing.setId(911L);
        existing.setProjectId(9140004L);
        existing.setStatus(BonusPoolService.STATUS_CONFIRMED);
        existing.setFinalPool(new BigDecimal("1000000"));
        when(bonusPoolMapper.selectById(911L)).thenReturn(existing);
        when(projectMapper.selectById(9140004L)).thenReturn(null);

        assertThatThrownBy(() -> service.distribute(911L,
            new BigDecimal("0.55"), new BigDecimal("0.45"), actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("奖金池关联项目不存在或已归档")
            .extracting(e -> ((IpdBusinessException) e).getErrorCode())
            .isEqualTo(ApiV1ErrorCode.STATE_CONFLICT);
        verify(bonusPoolMapper, never()).updateById(any(BonusPool.class));
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("[R217-GATE] freeze：项目已软删（del_flag='1'，逻辑孤儿 §2.5 型）→ 同样拒绝")
    void freeze_projectSoftDeleted_rejected() {
        BonusPool existing = new BonusPool();
        existing.setId(912L);
        existing.setProjectId(2098389746999926785L);
        existing.setStatus(BonusPoolService.STATUS_DRAFT);
        existing.setFinalPool(new BigDecimal("500000"));
        when(bonusPoolMapper.selectById(912L)).thenReturn(existing);
        Project archived = sLevelProject();
        archived.setDelFlag("1");
        when(projectMapper.selectById(2098389746999926785L)).thenReturn(archived);

        assertThatThrownBy(() -> service.freeze(912L, "软删项目池", actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("奖金池关联项目不存在或已归档");
        verify(bonusPoolMapper, never()).updateById(any(BonusPool.class));
    }

    /* ====================== 幂等 / 审计 ====================== */

    @Test
    @DisplayName("freeze 幂等：CONFIRMED 再调 freeze → 不写第二条 audit，不 updateById")
    void freeze_alreadyConfirmed_isIdempotent() {
        BonusPool existing = new BonusPool();
        existing.setId(900L);
        existing.setStatus(BonusPoolService.STATUS_CONFIRMED);
        existing.setFinalPool(new BigDecimal("500000"));
        when(bonusPoolMapper.selectById(900L)).thenReturn(existing);

        BonusPool result = service.freeze(900L, "再次冻结", actor());

        assertThat(result.getStatus()).isEqualTo(BonusPoolService.STATUS_CONFIRMED);
        // 幂等：no audit / no update
        verify(auditLogService, never()).append(any(AuditLog.class));
        verify(bonusPoolMapper, never()).updateById(any(BonusPool.class));
    }

    /* ====================== W4-B：compute 审计 + DuplicateKey 改 409 ====================== */

    @Test
    @DisplayName("W4-B 件 1：compute 落 1 条 BONUS_POOL_COMPUTE 审计（与 freeze/distribute 同严）")
    void compute_appendsAudit_bonusPoolComputeAction() {
        when(projectMapper.selectById(200L)).thenReturn(sLevelProject());
        when(bonusPoolMapper.selectByProjectIdAnyStatus(200L))
            .thenReturn(null);  // 无现有池（任意态）

        service.compute(200L,
            new BigDecimal("10000000"),
            new BigDecimal("100"),
            new BigDecimal("1.2"),
            new BigDecimal("0.05"),
            actor());

        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, times(1)).append(auditCaptor.capture());
        AuditLog audit = auditCaptor.getValue();
        assertThat(audit.getAction()).isEqualTo(BonusPoolService.ACTION_COMPUTE);
        assertThat(audit.getEntityType()).isEqualTo("bonus_pools");
        assertThat(audit.getOperatorRole()).isEqualTo("SUPER_ADMIN");
        // [SEC-FIX-HIGH-5.2-FOLLOWUP] 结构化字段 afterData JSON 替代字符串拼接
        assertThat(audit.getReason()).contains("projectId=200");
        assertThat(audit.getReason()).contains("finalPool=");
        assertThat(audit.getAfterData()).contains("\"projectId\":200");
        assertThat(audit.getAfterData()).contains("\"actualReceipts\":10000000");
        assertThat(audit.getAfterData()).contains("\"achievementRate\":100");
        assertThat(audit.getAfterData()).contains("\"personalCoefficient\":1.2");
        assertThat(audit.getAfterData()).contains("\"poolRate\":0.05");
        assertThat(audit.getAfterData()).contains("\"status\":\"DRAFT\"");
    }

    @Test
    @DisplayName("W4-B 件 2：同 projectId 已存在 DRAFT 二次 compute → IpdBusinessException(STATE_CONFLICT, 409)")
    void compute_existingDraft_throwsStateConflict() {
        // 不需要 stub projectMapper——查 DRAFT existing 命中即抛异常（在 buildPoolFromProjectWithAchievement 之前）
        BonusPool existingDraft = new BonusPool();
        existingDraft.setId(555L);
        existingDraft.setProjectId(200L);
        existingDraft.setStatus(BonusPoolService.STATUS_DRAFT);
        when(bonusPoolMapper.selectByProjectIdAnyStatus(200L))
            .thenReturn(existingDraft);

        assertThatThrownBy(() -> service.compute(200L,
            new BigDecimal("10000000"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("已存在 DRAFT 奖金池")
            .hasMessageContaining("id=555");
        // 不写审计、不 insert
        verify(auditLogService, never()).append(any(AuditLog.class));
        verify(bonusPoolMapper, never()).insert(any(BonusPool.class));
    }

    @Test
    @DisplayName("W4-B 件 1+：审计失败不阻断主流程（mock append 抛异常，compute 仍成功）")
    void compute_auditFailure_doesNotBlockBusiness() {
        when(projectMapper.selectById(200L)).thenReturn(sLevelProject());
        when(bonusPoolMapper.selectByProjectIdAnyStatus(200L))
            .thenReturn(null);
        // mock append 抛 RuntimeException，appendAudit 内 try/catch 应吞掉
        org.mockito.Mockito.doThrow(new RuntimeException("audit append boom"))
            .when(auditLogService).append(any(AuditLog.class));

        BonusPool pool = service.compute(200L,
            new BigDecimal("10000000"),
            new BigDecimal("100"),
            new BigDecimal("1.2"),
            new BigDecimal("0.05"),
            actor());

        // 主流程成功：DRAFT 入库 + finalPool 正确计算
        assertThat(pool.getStatus()).isEqualTo(BonusPoolService.STATUS_DRAFT);
        assertThat(pool.getFinalPool()).isEqualByComparingTo(new BigDecimal("1080000"));
        verify(bonusPoolMapper, times(1)).insert(any(BonusPool.class));
    }

    /* ====== R219（看板卡 2bef6e0e）：已 freeze 池二次 compute → 干净 409，不再裸 500 ====== */

    @Test
    @DisplayName("R219：已存在 CONFIRMED 池二次 compute → STATE_CONFLICT(409)，不 insert")
    void compute_existingConfirmed_throwsStateConflictNotRaw500() {
        BonusPool confirmed = new BonusPool();
        confirmed.setId(666L);
        confirmed.setProjectId(200L);
        confirmed.setStatus(BonusPoolService.STATUS_CONFIRMED);
        when(bonusPoolMapper.selectByProjectIdAnyStatus(200L)).thenReturn(confirmed);

        assertThatThrownBy(() -> service.compute(200L,
            new BigDecimal("10000000"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("CONFIRMED")
            .hasMessageContaining("id=666");
        // 前置拦截：不走到 build/insert，Uk 碰撞在 Service 层就变成干净 409
        verify(bonusPoolMapper, never()).insert(any(BonusPool.class));
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("R219：并发窗口撞 uk → DuplicateKeyException 被转 STATE_CONFLICT(409)，不外抛 500")
    void compute_duplicateKeyRace_convertedToStateConflict() {
        when(projectMapper.selectById(200L)).thenReturn(sLevelProject());
        when(bonusPoolMapper.selectByProjectIdAnyStatus(200L)).thenReturn(null); // 前置查无洞可钻
        when(bonusPoolMapper.insert(any(BonusPool.class)))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_bp_project"));

        assertThatThrownBy(() -> service.compute(200L,
            new BigDecimal("10000000"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("并发冲突");
    }

    /* ====================== [SEC-FIX-HIGH-5.2-FOLLOWUP] 三件套校验 ====================== */

    @Test
    @DisplayName("[SEC-FIX-HIGH-5.2-FOLLOWUP] compute 异常：actualReceipts 超上限 → IpdBusinessException(PARAM_INVALID)")
    void compute_exceedsActualReceiptsMax_throwsIpdBusinessException() {
        // actualReceipts = 1e13 超过 ACTUAL_RECEIPTS_MAX (1e12)
        assertThatThrownBy(() -> service.compute(200L,
            new BigDecimal("10000000000000"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("0.05"),
            actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("超过合理上限");
        // 不写审计、不 insert
        verify(auditLogService, never()).append(any(AuditLog.class));
        verify(bonusPoolMapper, never()).insert(any(BonusPool.class));
    }

    @Test
    @DisplayName("[SEC-FIX-HIGH-5.2-FOLLOWUP] compute 异常：poolRate > 1 → 走 validatePoolRate 拒绝")
    void compute_invalidPoolRate_throwsIpdBusinessException() {
        assertThatThrownBy(() -> service.compute(200L,
            new BigDecimal("1000000"),
            new BigDecimal("100"),
            new BigDecimal("1.0"),
            new BigDecimal("1.5"),  // 超过 POOL_RATE_MAX=1
            actor()))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("poolRate");
        verify(auditLogService, never()).append(any(AuditLog.class));
        verify(bonusPoolMapper, never()).insert(any(BonusPool.class));
    }

    /* ====================== R149 A1：奖金池窗口月数配置 ====================== */

    @Test
    @DisplayName("[R149-A1] readWindowMonths() 默认值（systemConfigService 未注入）= 6")
    void readWindowMonths_defaultReturnsSix() {
        // 解除 ISystemConfigService 注入（模拟 legacy 路径）
        service.setSystemConfigService(null);
        assertThat(service.readWindowMonths()).isEqualTo(6);
        assertThat(service.readWindowMonths()).isEqualTo(BonusPoolService.DEFAULT_WINDOW_MONTHS);
        // 恢复 mock，避免污染后续测试
        service.setSystemConfigService(systemConfigService);
    }

    @Test
    @DisplayName("[R149-A1] readWindowMonths() 配置键 bonus.windowMonths=12 ⇒ 读 12")
    void readWindowMonths_configuredReturnsConfigured() {
        when(systemConfigService.getIntValue("bonus.windowMonths", 6)).thenReturn(12);
        assertThat(service.readWindowMonths()).isEqualTo(12);
    }

    @Test
    @DisplayName("[R149-A1] readWindowMonths() 配置 0/负值 ⇒ 回退默认 6（非法值防护）")
    void readWindowMonths_invalidConfigFallsBackToDefault() {
        when(systemConfigService.getIntValue("bonus.windowMonths", 6)).thenReturn(0);
        assertThat(service.readWindowMonths()).isEqualTo(6);
        when(systemConfigService.getIntValue("bonus.windowMonths", 6)).thenReturn(-1);
        assertThat(service.readWindowMonths()).isEqualTo(6);
    }

    @Test
    @DisplayName("[R149-A1] readWindowMonths() 配置服务抛异常 ⇒ 回退默认 6（不阻塞业务）")
    void readWindowMonths_serviceExceptionFallsBackToDefault() {
        when(systemConfigService.getIntValue("bonus.windowMonths", 6))
            .thenThrow(new RuntimeException("mock db error"));
        assertThat(service.readWindowMonths()).isEqualTo(6);
    }
}
