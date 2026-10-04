package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.ruoyi.ipd.domain.KpiSharedConfirm;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.KpiSharedConfirmMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.vo.KpiSharedConfirmView;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P-1 性能优化：KpiSharedConfirmService.listConfirms 去 N+1。
 *
 * <p>核心断言：personMapper.selectByIds 只调 1 次（按行集合去重 personId 后批量加载），
 * personMapper.selectById 0 次（旧实现逐行懒加载的 N+1 反向断言）。
 *
 * <p>纯 JVM 单测（MockitoExtension，无 Spring），用 {@code @MockitoSettings(strictness=LENIENT)}
 * 避免 SUPER_ADMIN 短路跳过 projectMemberMapper.selectCount 时被 STRICT_STUBS 拒绝消费。
 * {@code LoginHelper.getTenantId()} 在无 Sa-Token 上下文时返回 null，{@code
 * KpiSharedCollectionService.requireTenantMatch(null, project)} 放行（tenantId 隔离降级路径）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("P-1 KpiSharedConfirmService.listConfirms 批量加载（去 N+1）")
class KpiSharedConfirmServiceTest {

    @Mock private KpiSharedConfirmMapper confirmMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private ISystemConfigService systemConfigService;

    private KpiSharedConfirmService service;

    /**
     * 纯 JVM 单测无 MP 运行时，手动初始化 lambda 列缓存——{@code confirm()} 的 CAS 谓词
     * （LambdaUpdateWrapper 上的 eq/isNull/set）需要列名解析，缺它报
     * 「can not find lambda cache for this entity」。
     */
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
    }

    /** 管理员 actor：role=SUPER_ADMIN 短路 isMember 检查，免去 projectMemberMapper stub。 */
    private IpdActor admin() {
        return new IpdActor(1L, "admin", "SUPER_ADMIN", null);
    }

    /** 测试项目：tenantId=null 让 requireTenantMatch 放行。 */
    private Project sProject() {
        Project p = new Project();
        p.setId(200L);
        p.setName("测试项目");
        p.setTenantId(null);
        return p;
    }

    /** 一条 confirm 行（personId/status/deadline 可定制）。 */
    private KpiSharedConfirm sRow(Long personId, String status, Date deadline) {
        KpiSharedConfirm r = new KpiSharedConfirm();
        r.setProjectId(200L);
        r.setPeriod("2026-09");
        r.setPersonId(personId);
        r.setStatus(status);
        r.setDeadlineAt(deadline);
        r.setMetricCode("K01");
        r.setMetricName("KPI-1");
        return r;
    }

    /** 一条 Person 记录（id/name）。 */
    private Person sPerson(Long id, String name) {
        Person p = new Person();
        p.setId(id);
        p.setName(name);
        return p;
    }

    /** BN-1：4 行同 personId=99 → selectByIds 1 次 + selectById 0 次。 */
    @Test
    @DisplayName("P-1 BN-1 listConfirms 4行同personId=99 → selectByIds 1次 + selectById 0次")
    void listConfirms_4rows_samePersonId_selectByIdsOnce() {
        when(projectMapper.selectById(200L)).thenReturn(sProject());
        Date future = new Date(System.currentTimeMillis() + 86_400_000L);
        when(confirmMapper.selectList(any())).thenReturn(List.of(
            sRow(99L, "PENDING", future),
            sRow(99L, "PENDING", future),
            sRow(99L, "PENDING", future),
            sRow(99L, "PENDING", future)));
        when(personMapper.selectByIds(anyList())).thenReturn(List.of(sPerson(99L, "归集组长")));

        List<KpiSharedConfirmView> views = service.listConfirms(admin(), 200L, "2026-09", null);

        assertThat(views).hasSize(4);
        assertThat(views).allSatisfy(v -> assertThat(v.personName()).isEqualTo("归集组长"));
        verify(personMapper, times(1)).selectByIds(anyList());
        verify(personMapper, never()).selectById(any());
    }

    /** BN-2：4 行不同 personId → selectByIds 1 次 + 参数去重 [99,100,200,300]。 */
    @Test
    @DisplayName("P-1 BN-2 listConfirms 4行不同personId → selectByIds 1次 + 参数去重 4 id")
    void listConfirms_4rows_diffPersonIds_oneSelectBatchIds() {
        when(projectMapper.selectById(200L)).thenReturn(sProject());
        Date future = new Date(System.currentTimeMillis() + 86_400_000L);
        when(confirmMapper.selectList(any())).thenReturn(List.of(
            sRow(99L, "PENDING", future),
            sRow(100L, "PENDING", future),
            sRow(200L, "PENDING", future),
            sRow(300L, "PENDING", future)));
        when(personMapper.selectByIds(anyList())).thenReturn(List.of(
            sPerson(99L, "A"), sPerson(100L, "B"),
            sPerson(200L, "C"), sPerson(300L, "D")));

        List<KpiSharedConfirmView> views = service.listConfirms(admin(), 200L, "2026-09", null);

        assertThat(views).hasSize(4);
        assertThat(views.get(0).personName()).isEqualTo("A");
        assertThat(views.get(1).personName()).isEqualTo("B");
        assertThat(views.get(2).personName()).isEqualTo("C");
        assertThat(views.get(3).personName()).isEqualTo("D");
        ArgumentCaptor<List<Long>> idCaptor = ArgumentCaptor.forClass(List.class);
        verify(personMapper, times(1)).selectByIds(idCaptor.capture());
        assertThat(idCaptor.getValue()).containsExactlyInAnyOrder(99L, 100L, 200L, 300L);
        verify(personMapper, never()).selectById(any());
    }

    /** BN-3：空库 → selectByIds 0 次（不发起 SQL），selectById 0 次。 */
    @Test
    @DisplayName("P-1 BN-3 listConfirms 空库 → selectByIds 0次 + selectById 0次")
    void listConfirms_empty_selectByIdsZero() {
        when(projectMapper.selectById(200L)).thenReturn(sProject());
        when(confirmMapper.selectList(any())).thenReturn(List.of());

        List<KpiSharedConfirmView> views = service.listConfirms(admin(), 200L, "2026-09", null);

        assertThat(views).isEmpty();
        verify(personMapper, never()).selectByIds(anyList());
        verify(personMapper, never()).selectById(any());
    }

    /** BN-4：行 personId=null → view.personName=null，null 被过滤不进 selectByIds 集合。 */
    @Test
    @DisplayName("P-1 BN-4 listConfirms 行personId=null → view.personName=null + 不进 selectByIds 集合")
    void listConfirms_nullPersonId_viewNameNull() {
        when(projectMapper.selectById(200L)).thenReturn(sProject());
        Date future = new Date(System.currentTimeMillis() + 86_400_000L);
        when(confirmMapper.selectList(any())).thenReturn(List.of(sRow(null, "PENDING", future)));

        List<KpiSharedConfirmView> views = service.listConfirms(admin(), 200L, "2026-09", null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).personName()).isNull();
        verify(personMapper, never()).selectByIds(anyList());
        verify(personMapper, never()).selectById(any());
    }

    /** BN-5：personId 在 persons 表不存在 → selectByIds 返 [] → view.personName=null（不抛）。 */
    @Test
    @DisplayName("P-1 BN-5 listConfirms personId不存在 → selectByIds返[] → view.personName=null 不抛")
    void listConfirms_personNotExist_viewNameNull() {
        when(projectMapper.selectById(200L)).thenReturn(sProject());
        Date future = new Date(System.currentTimeMillis() + 86_400_000L);
        when(confirmMapper.selectList(any())).thenReturn(List.of(sRow(999L, "PENDING", future)));
        when(personMapper.selectByIds(anyList())).thenReturn(List.of());

        List<KpiSharedConfirmView> views = service.listConfirms(admin(), 200L, "2026-09", null);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).personName()).isNull();
        verify(personMapper, times(1)).selectByIds(anyList());
        verify(personMapper, never()).selectById(any());
    }

    /** BN-6：selectByIds 返多 Person 同 id → 4 view 共用末条姓名（LinkedHashMap.put 同 key 覆盖语义：末条赢）。 */
    @Test
    @DisplayName("P-1 BN-6 listConfirms selectByIds返多Person同id → 4view共用末条姓名（LinkedHashMap.put 覆盖语义）")
    void listConfirms_duplicatedPerson_returnedLastName() {
        when(projectMapper.selectById(200L)).thenReturn(sProject());
        Date future = new Date(System.currentTimeMillis() + 86_400_000L);
        when(confirmMapper.selectList(any())).thenReturn(List.of(
            sRow(99L, "PENDING", future),
            sRow(99L, "PENDING", future),
            sRow(99L, "PENDING", future),
            sRow(99L, "PENDING", future)));
        when(personMapper.selectByIds(anyList())).thenReturn(List.of(
            sPerson(99L, "归集组长-FIRST"),
            sPerson(99L, "归集组长-LAST")));

        List<KpiSharedConfirmView> views = service.listConfirms(admin(), 200L, "2026-09", null);

        assertThat(views).hasSize(4);
        // Java Map.put 同 key 覆盖：末条赢（非首条）。这是标准语义，MyBatis-Plus
        // selectByIds 也按此行为。如需首条赢，preLoadPersonNames 需改 .putIfAbsent。
        assertThat(views).allSatisfy(v -> assertThat(v.personName()).isEqualTo("归集组长-LAST"));
        verify(personMapper, times(1)).selectByIds(anyList());
    }

    /** BN-7：statusFilter=OVERDUE + 行 deadline 已过 → 仅 overdue 入 view，预加载仍按全部 rows 1 次。 */
    @Test
    @DisplayName("P-1 BN-7 listConfirms statusFilter=OVERDUE → 仅 overdue 入 view + 预加载仍1次")
    void listConfirms_overdueFilter_preLoadStillOnce() {
        when(projectMapper.selectById(200L)).thenReturn(sProject());
        Date past = new Date(System.currentTimeMillis() - 86_400_000L);   // 已过
        Date future = new Date(System.currentTimeMillis() + 86_400_000L); // 未过
        when(confirmMapper.selectList(any())).thenReturn(List.of(
            sRow(99L, "PENDING", past),   // overdue
            sRow(99L, "PENDING", future))); // 非 overdue
        when(personMapper.selectByIds(anyList())).thenReturn(List.of(sPerson(99L, "归集组长")));

        List<KpiSharedConfirmView> views = service.listConfirms(admin(), 200L, "2026-09", "OVERDUE");

        assertThat(views).hasSize(1);
        assertThat(views.get(0).status()).isEqualTo("OVERDUE");
        verify(personMapper, times(1)).selectByIds(anyList());
    }

    // ============ 归属守卫8（2026-10-03 收口）：确认端此前只有角色门，任意组长可签任意项目 ============

    private static final Long MAIN_GROUP = 700L;
    private static final Long COLLAB_GROUP = 800L;
    private static final Long RD_PERSON = 900L;

    /** 项目 200：主组 MAIN_GROUP + 一名在职研发PM（其所属组即协同组 COLLAB_GROUP）。 */
    private void givenProjectWithCollaborativeMember() {
        Project p = new Project();
        p.setId(200L);
        p.setMainGroupId(MAIN_GROUP);
        p.setTenantId(null);
        when(projectMapper.selectById(200L)).thenReturn(p);
        org.ruoyi.ipd.domain.ProjectMember m = new org.ruoyi.ipd.domain.ProjectMember();
        m.setProjectId(200L);
        m.setPersonId(RD_PERSON);
        m.setRole("RD_PM");
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(m));
        Person rd = new Person();
        rd.setId(RD_PERSON);
        rd.setGroupId(COLLAB_GROUP);
        when(personMapper.selectById(RD_PERSON)).thenReturn(rd);
    }

    private static IpdActor leader(long id, long groupId) {
        return new IpdActor(id, "组长" + id, "GROUP_LEADER", groupId);
    }

    @Test
    @DisplayName("归属8：外组组长签署 → FORBIDDEN 且零写（修复前只验角色，任意组长可签任意项目）")
    void confirm_outsiderGroupLeader_forbiddenZeroWrite() {
        givenProjectWithCollaborativeMember();
        when(confirmMapper.selectById(55L)).thenReturn(sRow(99L, "PENDING", new Date()));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.confirm(leader(999L, 999L), 55L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class)
            .hasMessageContaining("产品组")
            .extracting(e -> ((org.ruoyi.ipd.common.IpdBusinessException) e).getErrorCode())
            .isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
        verify(confirmMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("归属8 反向锁：协同组组长（研发PM 所在组）→ 必须放行，否则双签永远签不完")
    void confirm_collaborativeGroupLeader_allowed() {
        givenProjectWithCollaborativeMember();
        when(confirmMapper.selectById(55L)).thenReturn(sRow(99L, "PENDING", new Date()));
        when(confirmMapper.update(any(), any())).thenReturn(1);

        service.confirm(leader(12L, COLLAB_GROUP), 55L);

        verify(confirmMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("归属8：主组组长 → 放行")
    void confirm_mainGroupLeader_allowed() {
        givenProjectWithCollaborativeMember();
        when(confirmMapper.selectById(55L)).thenReturn(sRow(99L, "PENDING", new Date()));
        when(confirmMapper.update(any(), any())).thenReturn(1);

        service.confirm(leader(11L, MAIN_GROUP), 55L);

        verify(confirmMapper, times(1)).update(any(), any());
    }

    @Test
    @DisplayName("归属8：未授权者不触达状态信息——外组组长看不到「已完成」这类状态冲突")
    void confirm_outsider_deniedBeforeStatusCheck() {
        givenProjectWithCollaborativeMember();
        // 行已是已完成态：若无归属门，会先抛 STATE_CONFLICT 泄漏状态；有归属门则应先拒。
        when(confirmMapper.selectById(55L)).thenReturn(sRow(99L, "CONFIRMED", new Date()));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> service.confirm(leader(999L, 999L), 55L))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class)
            .extracting(e -> ((org.ruoyi.ipd.common.IpdBusinessException) e).getErrorCode())
            .isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.FORBIDDEN);
    }
}
