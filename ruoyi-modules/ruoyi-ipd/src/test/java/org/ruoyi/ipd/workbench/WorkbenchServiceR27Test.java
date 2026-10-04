package org.ruoyi.ipd.workbench;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.LaunchDateChangeRequest;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.DeletionRequestServiceImpl;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.WorkbenchService;
import org.ruoyi.ipd.workbench.domain.MyInitiatedTask;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R27 P0-6：WorkbenchService 路径 2 函数补全 单测（TDD 红→绿）。
 *
 * <p>本类守两条契约：
 * <ul>
 *   <li>{@link WorkbenchService#myInitiated(Long)}：按 create_by=personId 逐行投影<b>真单据</b>
 *       （D3：修复前按 selectCount 展开成假 id / sourceId=null / status=COUNT 的「占位卡」，
 *       标题里还带内部编号 [R27-P0-6]，真库有数据时必然泄漏到界面）</li>
 *   <li>{@link WorkbenchService#myPendingApprovals(Long)}：只返回<b>本人确实有权办理</b>的在途单据
 *       （D2：修复前只过滤状态、不看归属，随后把 approverId 硬写成查询人——
 *       任何内部用户都能看到全公司的待审单据，且系统声称「审批人是你」）</li>
 * </ul>
 *
 * <p>纯 mock 注入 mapper，Lambda 列缓存通过 {@link TableInfoHelper#initTableInfo} 初始化。
 *
 * <p>@Tag("dev") 是项目级 surefire 守门——非 dev 标签不进入执行。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WorkbenchServiceR27Test {

    private static final long LEADER_ID = 9101L;
    private static final long ADMIN_ID = 9102L;
    private static final long MARKET_PM_ID = 9103L;
    private static final long CONFIRMER_ID = 9201L;

    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private NotificationService notificationService;
    @Mock private DeletionRequestMapper deletionRequestMapper;
    @Mock private LaunchDateChangeRequestMapper launchDateChangeRequestMapper;
    @Mock private PersonMapper personMapper;
    @Mock private DeletionRequestServiceImpl deletionRequestService;

    private WorkbenchService service;

    /** 纯 JVM 单测无 MP 运行时：手动初始化 lambda 列缓存（视图卡 + 两张业务单据域） */
    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> entity : List.of(MyInitiatedTask.class, DeletionRequest.class, LaunchDateChangeRequest.class)) {
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }

    @BeforeEach
    void setUp() {
        service = new WorkbenchService(
            projectMapper, projectMemberMapper, stageActionMapper, notificationService,
            java.util.List.of(),  // aggregators 为空（myInitiated/myPendingApprovals 不走 aggregator）
            deletionRequestMapper, launchDateChangeRequestMapper);
        // 「待我审批」的两项可选协作者（setter 注入，与生产装配同型）
        service.setPersonMapper(personMapper);
        service.setDeletionRequestService(deletionRequestService);
    }

    /* ====================== 1. myInitiated（D3：真单据，不是占位卡） ====================== */

    @Test
    @DisplayName("[R27-P0-6#1][D3] myInitiated：逐行投影真单据的 id/title/status，不再展开占位卡")
    @SuppressWarnings("unchecked")
    void myInitiated_projectsRealRowsInsteadOfPlaceholders() {
        long personId = 900101L;
        Date created = new Date(1_700_000_000_000L);
        DeletionRequest deletion = deletionRow(7001L, 88L, "LEADER_REVIEW", personId, created);
        LaunchDateChangeRequest launchDate = launchDateRow(8001L, 99L, "客户要求延期", personId, created, 9201L);

        when(deletionRequestMapper.selectList(any())).thenReturn(List.of(deletion));
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(List.of(launchDate));

        List<MyInitiatedTask> result = service.myInitiated(personId);

        assertThat(result).hasSize(2);

        MyInitiatedTask deletionCard = result.stream()
            .filter(t -> WorkbenchService.TASK_TYPE_DELETION_REQUEST.equals(t.getTaskType()))
            .findFirst().orElseThrow();
        // 真实主键 + 真实状态 + 真实标题（修复前：负 id / sourceId=null / status=COUNT / "[R27-P0-6] …占位卡"）
        assertThat(deletionCard.getId()).isEqualTo(7001L);
        assertThat(deletionCard.getSourceId()).isEqualTo(7001L);
        assertThat(deletionCard.getStatus()).isEqualTo("LEADER_REVIEW");
        assertThat(deletionCard.getTitle()).isEqualTo("projects#88");
        assertThat(deletionCard.getInitiatorId()).isEqualTo(personId);
        assertThat(deletionCard.getCreatedAt()).isEqualTo(created);

        MyInitiatedTask launchCard = result.stream()
            .filter(t -> WorkbenchService.TASK_TYPE_LAUNCH_DATE_CHANGE.equals(t.getTaskType()))
            .findFirst().orElseThrow();
        assertThat(launchCard.getId()).isEqualTo(8001L);
        assertThat(launchCard.getSourceId()).isEqualTo(8001L);
        assertThat(launchCard.getStatus()).isEqualTo("PENDING_SECOND");
        assertThat(launchCard.getTitle()).isEqualTo("客户要求延期");

        // 界面文案不得出现任何内部编号/占位标记，也不得有「未指明单据」的卡
        assertThat(result).allSatisfy(card -> {
            assertThat(card.getTitle()).doesNotContain("[R27", "占位卡", "COUNT");
            assertThat(card.getStatus()).isNotEqualTo("COUNT");
            assertThat(card.getSourceId()).isNotNull();
            assertThat(card.getId()).isPositive();
        });

        // 「我发起的」口径 = create_by=personId（查询链里确实带了这个条件）
        ArgumentCaptor<LambdaQueryWrapper<DeletionRequest>> deletionQuery =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(deletionRequestMapper).selectList(deletionQuery.capture());
        assertThat(deletionQuery.getValue().getSqlSegment()).contains("create_by");
        assertThat(deletionQuery.getValue().getParamNameValuePairs().values()).contains(personId);

        ArgumentCaptor<LambdaQueryWrapper<LaunchDateChangeRequest>> launchQuery =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(launchDateChangeRequestMapper).selectList(launchQuery.capture());
        assertThat(launchQuery.getValue().getSqlSegment()).contains("create_by");
        assertThat(launchQuery.getValue().getParamNameValuePairs().values()).contains(personId);
    }

    @Test
    @DisplayName("[R27-P0-6#1] myInitiated：本人无单据 ⇒ 空列表（不再造 count 张占位卡）")
    void myInitiated_noRows_returnsEmpty() {
        when(deletionRequestMapper.selectList(any())).thenReturn(List.of());
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.myInitiated(900101L)).isEmpty();
    }

    @Test
    @DisplayName("[R27-P0-6#1] myInitiated：personId 为空 ⇒ 返回空列表（防御性）")
    void myInitiated_null_returnsEmpty() {
        List<MyInitiatedTask> result = service.myInitiated(null);

        assertThat(result).isNotNull().isEmpty();
    }

    /* ====================== 2. myPendingApprovals（D2：只投递本人有权办理的） ====================== */

    @Test
    @DisplayName("[R27-P0-6#2][D2] myPendingApprovals：组长只看到本组待初审，别人组的单据不出现")
    void myPendingApprovals_leaderSeesOnlyOwnGroupRequests() {
        when(personMapper.selectById(LEADER_ID)).thenReturn(leader(LEADER_ID, 500L));
        // 真库同状态行有多条（分属不同组/不同组长）；mapper 一律返回，归属过滤是服务端职责
        DeletionRequest ownGroup = deletionRow(6001L, 100L, "LEADER_REVIEW", 9001L, new Date(), LEADER_ID);
        DeletionRequest otherGroup = deletionRow(6002L, 200L, "LEADER_REVIEW", 9002L, new Date(), 9001L);
        when(deletionRequestMapper.selectList(any())).thenReturn(List.of(ownGroup, otherGroup));
        when(deletionRequestService.isTargetInLeaderGroup(any(), eq(ownGroup))).thenReturn(true);
        when(deletionRequestService.isTargetInLeaderGroup(any(), eq(otherGroup))).thenReturn(false);
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(List.of());

        List<MyInitiatedTask> result = service.myPendingApprovals(LEADER_ID);

        // B 用户的待审单据（otherGroup）不进结果——修复前两条都会返回且都标「审批人=LEADER_ID」
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(6001L);
        assertThat(result).noneMatch(t -> Long.valueOf(6002L).equals(t.getId()));
        assertThat(result.get(0).getApproverId()).isEqualTo(LEADER_ID);
        assertThat(result.get(0).getInitiatorId()).isEqualTo(9001L);

        // approverId 与「做过归属判定的那个身份」是同一个 id，不是凭空硬写
        ArgumentCaptor<IpdActor> actorCaptor = ArgumentCaptor.forClass(IpdActor.class);
        verify(deletionRequestService).isTargetInLeaderGroup(actorCaptor.capture(), eq(ownGroup));
        assertThat(actorCaptor.getValue().id()).isEqualTo(result.get(0).getApproverId());
        assertThat(actorCaptor.getValue().groupId()).isEqualTo(500L);
        assertThat(actorCaptor.getValue().role()).isEqualTo("GROUP_LEADER");

        // 查询链只取本角色对应的那一档状态（不是 LEADER_REVIEW ∪ ADMIN_REVIEW 全捞）
        ArgumentCaptor<LambdaQueryWrapper<DeletionRequest>> query =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(deletionRequestMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("status");
        assertThat(query.getValue().getParamNameValuePairs().values())
            .contains("LEADER_REVIEW")
            .doesNotContain("ADMIN_REVIEW");
    }

    @Test
    @DisplayName("[R27-P0-6#2][D2] myPendingApprovals：超管看到终审档，且不受组长归属判定拦截")
    void myPendingApprovals_adminSeesAdminReviewTier() {
        when(personMapper.selectById(ADMIN_ID)).thenReturn(superAdmin(ADMIN_ID));
        DeletionRequest pending = deletionRow(6101L, 300L, "ADMIN_REVIEW", 9001L, new Date(), LEADER_ID);
        when(deletionRequestMapper.selectList(any())).thenReturn(List.of(pending));
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(List.of());

        List<MyInitiatedTask> result = service.myPendingApprovals(ADMIN_ID);

        // 超管分支不查 isTargetInLeaderGroup（默认 mock 返回 false）；能拿到卡即证明没被组长判定拦掉
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(6101L);
        assertThat(result.get(0).getApproverId()).isEqualTo(ADMIN_ID);
        assertThat(result.get(0).getStatus()).isEqualTo("ADMIN_REVIEW");

        ArgumentCaptor<LambdaQueryWrapper<DeletionRequest>> query =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(deletionRequestMapper).selectList(query.capture());
        // 注意：MP 的 paramNameValuePairs 由 getSqlSegment() 触发物化，必须先读 SQL 片段再读参数值，
        // 否则拿到的是空 map（读仪器之前先确认仪器本身是活的）。
        assertThat(query.getValue().getSqlSegment()).contains("status");
        assertThat(query.getValue().getParamNameValuePairs().values())
            .contains("ADMIN_REVIEW")
            .doesNotContain("LEADER_REVIEW");
    }

    @Test
    @DisplayName("[R27-P0-6#2][D2] myPendingApprovals：非审批角色 ⇒ 一条删除待审都看不到（修复前全部可见）")
    void myPendingApprovals_nonApproverRoleSeesNoDeletionCards() {
        when(personMapper.selectById(MARKET_PM_ID)).thenReturn(marketPm(MARKET_PM_ID, 500L));
        // 表里确实有待审行；若照旧实现（只过滤状态）会全部返回并标「审批人=MARKET_PM_ID」
        lenient().when(deletionRequestMapper.selectList(any())).thenReturn(
            List.of(deletionRow(6201L, 400L, "LEADER_REVIEW", 9001L, new Date(), LEADER_ID)));
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(List.of());

        List<MyInitiatedTask> result = service.myPendingApprovals(MARKET_PM_ID);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("[R27-P0-6#2][D2] myPendingApprovals：归属判定能力未装配 ⇒ 组长侧不投递（fail-closed）")
    void myPendingApprovals_leaderWithoutOwnershipResolverSeesNothing() {
        when(personMapper.selectById(LEADER_ID)).thenReturn(leader(LEADER_ID, 500L));
        lenient().when(deletionRequestMapper.selectList(any())).thenReturn(
            List.of(deletionRow(6501L, 500L, "LEADER_REVIEW", 9001L, new Date(), LEADER_ID)));
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(List.of());
        // 判定能力缺席：不能因为"查不出归属"就当成"没有归属限制"把单据全放出去
        service.setDeletionRequestService(null);

        assertThat(service.myPendingApprovals(LEADER_ID)).isEmpty();
    }

    @Test
    @DisplayName("[R27-P0-6#2][D2] myPendingApprovals：上市日期变更只投给 propose 时预落的第二签人")
    @SuppressWarnings("unchecked")
    void myPendingApprovals_launchDateOnlyForPreassignedConfirmer() {
        when(personMapper.selectById(CONFIRMER_ID)).thenReturn(marketPm(CONFIRMER_ID, 500L));
        lenient().when(deletionRequestMapper.selectList(any())).thenReturn(List.of());
        Date created = new Date(1_700_000_000_000L);
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(
            List.of(launchDateRow(6301L, 77L, "客户要求延期", 9001L, created, CONFIRMER_ID)));

        List<MyInitiatedTask> result = service.myPendingApprovals(CONFIRMER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(6301L);
        assertThat(result.get(0).getStatus()).isEqualTo("PENDING_SECOND");
        assertThat(result.get(0).getApproverId()).isEqualTo(CONFIRMER_ID);

        // 查询链自带归属条件：status=PENDING_SECOND AND confirmer_id=personId
        ArgumentCaptor<LambdaQueryWrapper<LaunchDateChangeRequest>> query =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(launchDateChangeRequestMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("status", "confirmer_id");
        assertThat(query.getValue().getParamNameValuePairs().values())
            .contains("PENDING_SECOND", CONFIRMER_ID);
    }

    @Test
    @DisplayName("[R27-P0-6#2][D2] myPendingApprovals：approverId 取行上真值，不是把查询人硬写回去")
    void myPendingApprovals_approverIdComesFromRowNotFromQueryArgument() {
        when(personMapper.selectById(CONFIRMER_ID)).thenReturn(marketPm(CONFIRMER_ID, 500L));
        lenient().when(deletionRequestMapper.selectList(any())).thenReturn(List.of());
        // 模拟「返回了不属于查询人的行」：approverId 必须如实回填行上的 9305，而不是查询人 9201
        when(launchDateChangeRequestMapper.selectList(any())).thenReturn(
            List.of(launchDateRow(6401L, 78L, "理由", 9001L, new Date(), 9305L)));

        List<MyInitiatedTask> result = service.myPendingApprovals(CONFIRMER_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getApproverId()).isEqualTo(9305L);
        assertThat(result.get(0).getApproverId()).isNotEqualTo(CONFIRMER_ID);
    }

    @Test
    @DisplayName("[R27-P0-6#2][D2] myPendingApprovals：查不到该 Person ⇒ 不投递（fail-closed）")
    void myPendingApprovals_unknownPersonSeesNothing() {
        when(personMapper.selectById(7777L)).thenReturn(null);

        assertThat(service.myPendingApprovals(7777L)).isEmpty();
    }

    @Test
    @DisplayName("[R27-P0-6#2] myPendingApprovals：personId 为空 ⇒ 返回空列表（防御性）")
    void myPendingApprovals_null_returnsEmpty() {
        List<MyInitiatedTask> result = service.myPendingApprovals(null);

        assertThat(result).isNotNull().isEmpty();
    }

    /* ====================== 构造辅助 ====================== */

    /** 删除申请真行（createBy/createTime 属 BaseEntity，须走 setter）。 */
    private static DeletionRequest deletionRow(Long id, Long entityId, String status, Long createBy,
                                               Date createTime) {
        return deletionRow(id, entityId, status, createBy, createTime, null);
    }

    private static DeletionRequest deletionRow(Long id, Long entityId, String status, Long createBy,
                                               Date createTime, Long leaderId) {
        DeletionRequest row = DeletionRequest.builder()
            .id(id).entityType("projects").entityId(entityId)
            .requesterId(createBy).status(status).leaderId(leaderId)
            .build();
        row.setCreateBy(createBy);
        row.setCreateTime(createTime);
        return row;
    }

    /** 上市日期变更真行。 */
    private static LaunchDateChangeRequest launchDateRow(Long id, Long projectId, String reason, Long createBy,
                                                        Date createTime, Long confirmerId) {
        LaunchDateChangeRequest row = LaunchDateChangeRequest.builder()
            .id(id).projectId(projectId).reason(reason)
            .proposerId(createBy).confirmerId(confirmerId)
            .status(LaunchDateChangeRequest.ST_PENDING_SECOND)
            .build();
        row.setCreateBy(createBy);
        row.setCreateTime(createTime);
        return row;
    }

    private static Person leader(Long id, Long groupId) {
        return Person.builder().id(id).name("组长").personType("GROUP_LEADER").groupId(groupId).build();
    }

    private static Person superAdmin(Long id) {
        return Person.builder().id(id).name("超管").personType("SUPER_ADMIN").build();
    }

    private static Person marketPm(Long id, Long groupId) {
        return Person.builder().id(id).name("市场PM").personType("MARKET_PM").groupId(groupId).build();
    }
}
