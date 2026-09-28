package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
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
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.KpiSharedConfirm;
import org.ruoyi.ipd.mapper.KpiSharedConfirmMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R33 一期 · 分片 E：C5 共担 KPI 确认并发红绿对照（规格 §6 验收：并发修复带红绿对照测试）。
 *
 * <p>C5 缺陷（规格 §1 C5 行「最薄弱：裸 updateById，第二签并发双写窗口」）：两组长抢签
 * （首签 / 第二签）均走「selectById 读 → 内存改 → updateById 全量写」，后写覆盖先写。
 * 修复为 CAS 谓词翻转（对齐 C2 CoefficientChangeService 决策 CAS 模式）+
 * {@code guardSupport.requireCasHit(rows, msg)}。
 *
 * <p><b>红绿对照（自证能红）</b>：
 * <ul>
 *   <li>绿：CAS 命中单胜——第一签 update 返回 1 通过；并发第二签 update 返回 0 抛并发冲突
 *       （用例 ①③ 绿 + ②④ 绿）</li>
 *   <li>红：临时撤掉 CAS 谓词/requireCasHit（模拟旧 updateById 双写裸奔）→ ②④ 因
 *       「未抛并发冲突」断言失败而红、⑤⑥ 因 wrapper 谓词 capture 不到 update 调用而红；
 *       恢复 CAS 后全绿</li>
 *   <li>既有 KpiSharedConfirmServiceTest（listConfirms N+1）不受影响保持绿</li>
 * </ul>
 *
 * <p>注：StateMachineGuard 用 mock（规则表暂无 kpi_shared_confirm 迁移规则，规则补齐
 * 待主会话统一处理——见分片 E 报告），本文件聚焦并发单胜语义与接线存在性。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("R33 C5 共担KPI确认并发红绿对照（CAS 单胜）")
class KpiSharedConfirmConcurrencyTest {

    private static final Long CONFIRM_ID = 55L;
    private static final Long FIRST_LEADER = 100L;
    private static final Long SECOND_LEADER = 200L;

    /** 新增并发冲突文案（逐字冻结，报告已列明） */
    private static final String FIRST_CAS_CONFLICT =
        "签署并发冲突：该共担 KPI 确认已被其他组长抢先签署，请刷新后重试";
    private static final String SECOND_CAS_CONFLICT =
        "签署并发冲突：该共担 KPI 确认已被其他组长抢先完成双签，请刷新后重试";

    @Mock private KpiSharedConfirmMapper confirmMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private ISystemConfigService systemConfigService;
    @Mock private StateMachineGuard stateMachineGuard;

    private KpiSharedConfirmService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KpiSharedConfirm.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new KpiSharedConfirmService(
            confirmMapper, projectMapper, projectMemberMapper, personMapper,
            auditLogService, systemConfigService);
        service.setStateMachineGuard(stateMachineGuard);
        lenient().when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private IpdActor leader(long id) {
        return new IpdActor(id, "组长" + id, "GROUP_LEADER", null);
    }

    private KpiSharedConfirm pendingRow(Long firstConfirmedBy) {
        KpiSharedConfirm r = KpiSharedConfirm.builder()
            .id(CONFIRM_ID)
            .projectId(200L)
            .period("2026-09")
            .personId(99L)
            .metricCode("K01")
            .metricName("销量/出货量达成率")
            .status(KpiSharedConfirmService.ST_PENDING)
            .build();
        r.setFirstConfirmedBy(firstConfirmedBy);
        return r;
    }

    /* ---------- ① CAS 命中单胜：第一签 update 返回 1 通过 ---------- */

    @Test
    @DisplayName("① 首签 CAS 命中（update 返 1）→ 通过，first 落库、状态仍 PENDING")
    void firstSign_casHit_singleWinner_passes() {
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(null));
        when(confirmMapper.update(isNull(), any())).thenReturn(1);

        KpiSharedConfirmService.ConfirmResult out = service.confirm(leader(FIRST_LEADER), CONFIRM_ID);

        assertThat(out.confirmed()).isFalse();
        assertThat(out.status()).isEqualTo(KpiSharedConfirmService.ST_PENDING);
        assertThat(out.firstConfirmedBy()).isEqualTo(String.valueOf(FIRST_LEADER));
        verify(confirmMapper, times(1)).update(isNull(), any());
        // 红绿对照：旧实现走 updateById 裸写；CAS 化后 updateById 不再被调用
        verify(confirmMapper, never()).updateById(any(KpiSharedConfirm.class));
    }

    @Test
    @DisplayName("① 第二签 CAS 命中（update 返 1）→ 单胜通过，status=CONFIRMED")
    void secondSign_casHit_singleWinner_passes() {
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(FIRST_LEADER));
        when(confirmMapper.update(isNull(), any())).thenReturn(1);

        KpiSharedConfirmService.ConfirmResult out = service.confirm(leader(SECOND_LEADER), CONFIRM_ID);

        assertThat(out.confirmed()).isTrue();
        assertThat(out.status()).isEqualTo(KpiSharedConfirmService.ST_CONFIRMED);
        assertThat(out.firstConfirmedBy()).isEqualTo(String.valueOf(FIRST_LEADER));
        assertThat(out.secondConfirmedBy()).isEqualTo(String.valueOf(SECOND_LEADER));
        verify(confirmMapper, never()).updateById(any(KpiSharedConfirm.class));
    }

    /* ---------- ② 并发第二签（update 返 0）→ 抛并发冲突（旧裸写会静默双写=红） ---------- */

    @Test
    @DisplayName("② 首签并发败者（update 返 0）→ 抛并发冲突文案逐字（撤 CAS 则本例红）")
    void firstSign_casMiss_concurrentConflict_throws() {
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(null));
        when(confirmMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(leader(FIRST_LEADER), CONFIRM_ID))
            .isInstanceOf(ServiceException.class)
            .hasMessage(FIRST_CAS_CONFLICT);
        verify(confirmMapper, never()).updateById(any(KpiSharedConfirm.class));
    }

    @Test
    @DisplayName("② 两组长抢第二签：败者（update 返 0）抛并发冲突，胜者已落 CONFIRMED（单胜）")
    void secondSign_casMiss_concurrentConflict_throws() {
        // 并发语义：胜者已把 status 翻成 CONFIRMED，败者 CAS 谓词 eq(status,PENDING) miss → 0 行
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(FIRST_LEADER));
        when(confirmMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.confirm(leader(SECOND_LEADER), CONFIRM_ID))
            .isInstanceOf(ServiceException.class)
            .hasMessage(SECOND_CAS_CONFLICT);
        verify(confirmMapper, never()).updateById(any(KpiSharedConfirm.class));
    }

    /* ---------- ③ 红绿自证：CAS 谓词存在性断言（撤谓词回旧裸写 → 本组红） ---------- */

    @Test
    @DisplayName("③ 首签 CAS 谓词含 status + first_confirmed_by IS NULL（谓词撤除 → 红）")
    void firstSign_casPredicate_containsStatusAndNullFirstSigner() {
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(null));
        when(confirmMapper.update(isNull(), any())).thenReturn(1);

        service.confirm(leader(FIRST_LEADER), CONFIRM_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KpiSharedConfirm>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(confirmMapper).update(isNull(), cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertThat(sql).contains("status");
        assertThat(sql).contains("first_confirmed_by");
        assertThat(sql).contains("IS NULL");
    }

    @Test
    @DisplayName("③ 第二签 CAS 谓词含 status + first_confirmed_by 等值（谓词撤除 → 红）")
    void secondSign_casPredicate_containsStatusAndFirstSigner() {
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(FIRST_LEADER));
        when(confirmMapper.update(isNull(), any())).thenReturn(1);

        service.confirm(leader(SECOND_LEADER), CONFIRM_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KpiSharedConfirm>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(confirmMapper).update(isNull(), cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertThat(sql).contains("status");
        assertThat(sql).contains("first_confirmed_by");
    }

    /* ---------- ④ C5 补接线存在性：第二签迁移点接 StateMachineGuard ---------- */

    @Test
    @DisplayName("④ 第二签迁移点接线：preCheck(kpi_shared_confirm,PENDING→CONFIRMED,secondSign) + postCommit")
    void secondSign_wiredToStateMachineGuard() {
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(FIRST_LEADER));
        when(confirmMapper.update(isNull(), any())).thenReturn(1);

        service.confirm(leader(SECOND_LEADER), CONFIRM_ID);

        verify(stateMachineGuard).preCheck("kpi_shared_confirm",
            KpiSharedConfirmService.ST_PENDING, KpiSharedConfirmService.ST_CONFIRMED, "secondSign");
        verify(stateMachineGuard).postCommit(
            org.mockito.ArgumentMatchers.eq("kpi_shared_confirm"),
            org.mockito.ArgumentMatchers.eq(KpiSharedConfirmService.ST_PENDING),
            org.mockito.ArgumentMatchers.eq(KpiSharedConfirmService.ST_CONFIRMED),
            org.mockito.ArgumentMatchers.eq("secondSign"),
            org.mockito.ArgumentMatchers.eq(SECOND_LEADER),
            org.mockito.ArgumentMatchers.eq(CONFIRM_ID),
            any(java.util.Date.class));
    }

    /* ---------- ⑤ 存量语义零变更：同人重复签仍 DUAL_SIGN_INCOMPLETE 40002 ---------- */

    @Test
    @DisplayName("⑤ 同人重复签仍抛 DUAL_SIGN_INCOMPLETE（存量文案逐字，不触写）")
    void samePersonReSign_stillDualSignIncomplete() {
        when(confirmMapper.selectById(CONFIRM_ID)).thenReturn(pendingRow(FIRST_LEADER));

        assertThatThrownBy(() -> service.confirm(leader(FIRST_LEADER), CONFIRM_ID))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class)
            .hasMessage("双组长确认需第二位不同组长签署，同一人不能重复确认");
        verify(confirmMapper, never()).update(isNull(), any());
        verify(confirmMapper, never()).updateById(any(KpiSharedConfirm.class));
    }
}
