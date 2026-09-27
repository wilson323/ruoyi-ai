package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.KpiSharedConfirm;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProductGroup;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.KpiSharedConfirmMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R232-W14 共担 KPI 对账服务单测。
 * <p>mock 合法性：sharedDetail 载荷形状与真库生产者完全一致
 * （{@code KpiSharedCollectionService.collectSharedKpi} 经 AuditEventData.json("…","metrics",MetricResult 列表) 落库的
 * code/source/actual/target/score/weight/included/message 八字段数组），不复造真库不可能出现的组合。
 * <p>公式复用真实例：KpiSharedCollectionService 用 legacy 6 参构造（systemConfig/双认定 mapper 为 null →
 * readMinSample=30、K04 来源回退 SALES_ACCEPTANCE），weightedScore/NPS 公式全走真码，零 stub。
 */
@Tag("dev")
class KpiSharedReconcileServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private static final long PROJECT_ID = 100L;

    private KpiRecordMapper kpiRecordMapper;
    private KpiSharedConfirmMapper confirmMapper;
    private ProjectMapper projectMapper;
    private ProjectMemberMapper projectMemberMapper;
    private PersonMapper personMapper;
    private ProductGroupMapper productGroupMapper;
    private KpiSharedReconcileService service;

    @BeforeEach
    void setUp() {
        kpiRecordMapper = mock(KpiRecordMapper.class);
        confirmMapper = mock(KpiSharedConfirmMapper.class);
        projectMapper = mock(ProjectMapper.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        personMapper = mock(PersonMapper.class);
        productGroupMapper = mock(ProductGroupMapper.class);
        KpiSharedCollectionService formulas = new KpiSharedCollectionService(
            kpiRecordMapper, projectMapper, projectMemberMapper, personMapper, null, null);
        service = new KpiSharedReconcileService(kpiRecordMapper, confirmMapper, projectMapper,
            projectMemberMapper, personMapper, productGroupMapper, formulas);
    }

    /* ---------- fixtures ---------- */

    /** 与 collectSharedKpi 落库同形的目标值项目：K01=100 / K02=10 / K03=50 / K04=6。 */
    private Project project() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setTargetSalesAmount(new BigDecimal("100"));
        p.setTargetChannelCount(10);
        p.setTargetNps(50);
        p.setTargetSceneCount(6);
        return p;
    }

    /**
     * 真实公式自检过的期望明细（collect 口径）：
     * K01 100/100→100.00；K02 5/10→达成 50→90.00；K03 NPS 50≥目标 50→100.00；K04 3/6→达成 50→90.00；
     * 加权 (15+9+10+4.5)/0.40=96.25。
     */
    private String detail(boolean tamperK01Score) {
        double k01Score = tamperK01Score ? 80.00 : 100.00;
        return "{\"source\":\"GROUP_LEADER_COLLECTION\",\"projectId\":100,\"period\":\"2026-08\","
            + "\"metrics\":["
            + "{\"code\":\"K01\",\"source\":\"FINANCE_OR_ERP\",\"actual\":100,\"target\":100,\"score\":"
            + k01Score + ",\"weight\":0.15,\"included\":true,\"message\":\"销量/出货量达成率\"},"
            + "{\"code\":\"K02\",\"source\":\"CHANNEL_CRM\",\"actual\":5,\"target\":10,\"score\":90.00,"
            + "\"weight\":0.10,\"included\":true,\"message\":\"渠道商覆盖达成率\"},"
            + "{\"code\":\"K03\",\"source\":\"NPS_SURVEY\",\"actual\":50,\"target\":50,\"score\":100.00,"
            + "\"weight\":0.10,\"included\":true,\"message\":\"NPS=50\"},"
            + "{\"code\":\"K04\",\"source\":\"SALES_ACCEPTANCE\",\"actual\":3,\"target\":6,\"score\":90.00,"
            + "\"weight\":0.05,\"included\":true,\"message\":\"场景覆盖率\"}],"
            + "\"participantIds\":[1,2],\"collectedBy\":9,\"revision\":1}";
    }

    private KpiRecord row(long personId, String detailJson, String total) {
        return KpiRecord.builder()
            .projectId(PROJECT_ID).personId(personId).kpiType("SHARED").period("2026-08")
            .sharedDetail(detailJson).comprehensiveScore(new BigDecimal(total))
            .revision(1).status("FINALIZED").build();
    }

    private void stubPeriodRows(KpiRecord... rows) {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(kpiRecordMapper.selectOne(any())).thenReturn(rows[0]);
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of(rows));
        when(confirmMapper.selectList(any())).thenReturn(List.of());
    }

    /* ---------- 用例 ---------- */

    @Test
    void allMatchLedgerWhenStoredEqualsRecompute() {
        stubPeriodRows(row(1, detail(false), "96.25"), row(2, detail(false), "96.25"));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.noData()).isFalse();
        assertThat(report.period()).isEqualTo("2026-08");
        assertThat(report.items()).hasSize(4)
            .allSatisfy(i -> assertThat(i.verdict()).isEqualTo(KpiSharedReconcileService.VERDICT_MATCH));
        assertThat(report.items()).filteredOn(i -> "K01".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.recomputedScore()).isEqualTo("100.00");
                assertThat(i.storedScore()).isEqualTo("100.00");
            });
        assertThat(report.totalVerdict()).isEqualTo(KpiSharedReconcileService.VERDICT_MATCH);
        assertThat(report.recomputedTotal()).isEqualTo("96.25");
        assertThat(report.dualPmVerdict()).isEqualTo(KpiSharedReconcileService.VERDICT_MATCH);
    }

    @Test
    void detectsFormulaDiffWhenStoredScoreTampered() {
        // 存储 K01 得分 80 vs 公式复算 100 → 单项 DIFF；综合分按存储明细复算 88.75 vs 存储 91.00 → 总计 DIFF
        stubPeriodRows(row(1, detail(true), "91.00"), row(2, detail(true), "91.00"));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i -> "K01".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(KpiSharedReconcileService.VERDICT_DIFF);
                assertThat(i.recomputedScore()).isEqualTo("100.00");
            });
        assertThat(report.totalVerdict()).isEqualTo(KpiSharedReconcileService.VERDICT_DIFF);
        assertThat(report.recomputedTotal()).isEqualTo("88.75");
    }

    @Test
    void detectsTargetDriftAgainstSourceTable() {
        Project drifted = project();
        drifted.setTargetSalesAmount(new BigDecimal("200")); // 目标销售额被后续修改
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(drifted);
        when(kpiRecordMapper.selectOne(any())).thenReturn(row(1, detail(false), "96.25"));
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of(
            row(1, detail(false), "96.25"), row(2, detail(false), "96.25")));
        when(confirmMapper.selectList(any())).thenReturn(List.of());
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.items()).filteredOn(i -> "K01".equals(i.code()))
            .singleElement().satisfies(i -> {
                assertThat(i.verdict()).isEqualTo(KpiSharedReconcileService.VERDICT_DRIFT);
                assertThat(i.currentTarget()).isEqualTo("200");
                assertThat(i.note()).contains("目标已变更");
            });
        // 其余项不受源表漂移影响
        assertThat(report.items()).filteredOn(i -> "K03".equals(i.code()))
            .singleElement().satisfies(i ->
                assertThat(i.verdict()).isEqualTo(KpiSharedReconcileService.VERDICT_MATCH));
    }

    @Test
    void awaitingLedgerMarksAllPendingWithoutFabricatingScores() {
        // W14-02 缺值语义：从未归集 → 上月周期全项 PENDING_DATA，不按 0 伪判，仍是有台账的有效产出
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        when(kpiRecordMapper.selectOne(any())).thenReturn(null);
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.noData()).isTrue();
        assertThat(report.period()).isEqualTo("2026-08"); // 固定时钟 2026-09 → 归集窗口上月
        assertThat(report.items()).hasSize(4).allSatisfy(i ->
            assertThat(i.verdict()).isEqualTo(KpiSharedReconcileService.VERDICT_PENDING));
        assertThat(report.verdictCounts()).containsEntry(KpiSharedReconcileService.VERDICT_PENDING, 4L);
    }

    @Test
    void dualPmInvariantFlagsRowCountMismatch() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        KpiRecord single = row(1, detail(false), "96.25");
        when(kpiRecordMapper.selectOne(any())).thenReturn(single);
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of(single)); // 真库双 PM 必 2 行，缺行=AC-KPI-11 违例
        when(confirmMapper.selectList(any())).thenReturn(List.of());
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.dualPmVerdict()).isEqualTo(KpiSharedReconcileService.VERDICT_DIFF);
        assertThat(report.dualPmNote()).contains("应恰 2 行");
    }

    @Test
    void confirmChainRowsIncludedWithOverdueDerivation() {
        stubPeriodRows(row(1, detail(false), "96.25"), row(2, detail(false), "96.25"));
        when(confirmMapper.selectList(any())).thenReturn(List.of(
            KpiSharedConfirm.builder().metricCode("K01").status("PENDING")
                .deadlineAt(Date.from(Instant.parse("2026-09-01T00:00:00Z"))).build(),
            KpiSharedConfirm.builder().metricCode("K02").status("CONFIRMED")
                .firstConfirmedBy(9L).secondConfirmedBy(10L).build()));
        var report = service.reconcile(PROJECT_ID, CLOCK);
        assertThat(report.confirms()).extracting(
                KpiSharedReconcileService.ConfirmRow::metricCode,
                KpiSharedReconcileService.ConfirmRow::status)
            .containsExactly(
                tuple("K01", "OVERDUE"), // 读时派生，与 KpiSharedConfirmService 同口径
                tuple("K02", "CONFIRMED"));
    }

    @Test
    void missingProjectFailsFastWithoutFakeLedger() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);
        assertThatThrownBy(() -> service.reconcile(PROJECT_ID, CLOCK))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("项目不存在");
    }

    @Test
    void corruptedDetailJsonFailsInsteadOfSilentPass() {
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(project());
        KpiRecord broken = row(1, "{not-json", "96.25");
        when(kpiRecordMapper.selectOne(any())).thenReturn(broken);
        when(kpiRecordMapper.selectList(any())).thenReturn(List.of(broken));
        assertThatThrownBy(() -> service.reconcile(PROJECT_ID, CLOCK))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("非法 JSON");
    }

    @Test
    void leadersOfResolvesGroupLeaderViaMemberPersonAndMainGroup() {
        Project p = project();
        p.setMainGroupId(5L);
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(p);
        ProjectMember m = new ProjectMember();
        m.setProjectId(PROJECT_ID);
        m.setPersonId(1L);
        m.setRole("MARKET_PM");
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(m));
        Person person = new Person();
        person.setId(1L);
        person.setGroupId(5L);
        when(personMapper.selectById(1L)).thenReturn(person);
        // ProductGroup 为 @Builder（无公开无参构造），用 builder 与真库生产者同路
        ProductGroup group = ProductGroup.builder().id(5L).leaderPersonId(9L).build();
        when(productGroupMapper.selectById(5L)).thenReturn(group);
        assertThat(service.leadersOf(PROJECT_ID)).isEqualTo(Set.of(9L));
    }
}
