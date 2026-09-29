package org.ruoyi.ipd.workbench;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.DeleteAuditService;
import org.ruoyi.ipd.service.DeletionRequestServiceImpl;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.ISystemConfigService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeletionReviewAggregator 单测（WB-17-1 P0 行为下沉 + P1.1 迁入，@Tag("dev")）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class DeletionReviewAggregatorTest {

    @BeforeAll
    static void initLambdaColumns() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            DeletionRequest.class);
    }

    @Mock
    private DeletionRequestMapper deletionRequestMapper;
    @Mock
    private ProjectMapper projectMapper;

    private DeletionReviewAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new DeletionReviewAggregator(deletionRequestMapper);
    }

    @Test
    @DisplayName("taskType 契约：固定 deletion_review（spec 页03:165 权威枚举）")
    void taskType_isDeletionReview() {
        assertThat(aggregator.taskType()).isEqualTo("deletion_review");
    }

    @Test
    @DisplayName("GROUP_LEADER：待初审每条 1 卡（字段契约 + 逾期 priority + isBlocking='1' 值域回归）")
    void collect_leaderGetsLeaderReviewCards() {
        IpdActor leader = new IpdActor(3L, "leader", "GROUP_LEADER", 10L);
        Date past = new Date(System.currentTimeMillis() - 86400000L);
        DeletionRequest dr1 = DeletionRequest.builder()
            .id(501L).entityType("project").entityId(10L).status("LEADER_REVIEW")
            .leaderDueAt(past).build();
        DeletionRequest dr2 = DeletionRequest.builder()
            .id(502L).entityType("requirement").entityId(20L).status("LEADER_REVIEW")
            .leaderDueAt(null).build();
        when(deletionRequestMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(dr1, dr2));

        List<Map<String, Object>> tasks = aggregator.collect(leader, new LinkedHashMap<>(), new Date());

        assertThat(tasks).hasSize(2);
        assertThat(tasks).allSatisfy(t -> {
            assertThat(t.get("taskType")).isEqualTo("deletion_review");
            assertThat(t.get("deepLink")).isEqualTo("/ipd/deletion/review");
            assertThat(t.get("projectName")).isEqualTo("删除审批");
            assertThat(t.get("isBlocking")).isEqualTo("1"); // P0 修过的 'Y' 值域 bug 回归断言
        });
        assertThat(tasks.get(0).get("title")).asString().startsWith("删除初审：");
        assertThat(tasks.get(0).get("priority")).isEqualTo("high");     // dr1 leaderDueAt 已过
        assertThat(tasks.get(1).get("priority")).isEqualTo("normal");   // dr2 无期限
        assertThat(tasks.get(0).get("id")).isEqualTo("DEL-501");
    }

    @Test
    @DisplayName("SUPER_ADMIN：待终审每条 1 卡，title 前缀「删除终审：」")
    void collect_adminGetsAdminReviewCards() {
        IpdActor admin = new IpdActor(99L, "root", "SUPER_ADMIN", null);
        DeletionRequest dr = DeletionRequest.builder()
            .id(503L).entityType("project").entityId(30L).status("ADMIN_REVIEW")
            .adminDueAt(null).build();
        when(deletionRequestMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(dr));

        List<Map<String, Object>> tasks = aggregator.collect(admin, new LinkedHashMap<>(), new Date());

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).get("title")).asString().startsWith("删除终审：");
        assertThat(tasks.get(0).get("status")).isEqualTo("ADMIN_REVIEW");
    }

    @Test
    @DisplayName("其他角色（RD_PM）→ 空，不触发查询")
    void collect_skipsIrrelevantRoles() {
        IpdActor rd = new IpdActor(4L, "rd", "RD_PM", 10L);

        List<Map<String, Object>> tasks = aggregator.collect(rd, new LinkedHashMap<>(), new Date());

        assertThat(tasks).isEmpty();
        verify(deletionRequestMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("空结果短路：pending 空列表 → 空")
    void collect_shortCircuitsOnEmptyPending() {
        IpdActor leader = new IpdActor(3L, "leader", "GROUP_LEADER", 10L);
        when(deletionRequestMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        assertThat(aggregator.collect(leader, new LinkedHashMap<>(), new Date())).isEmpty();
    }

    @Test
    @DisplayName("AI 副驾审批卡按可信租户查询并防御过滤跨租户返回行")
    void copilotCollectExcludesCrossTenantDeletionRequests() {
        IpdActor admin = new IpdActor(99L, "root", "SUPER_ADMIN", null);
        DeletionRequest sameTenant = DeletionRequest.builder()
            .id(503L).entityType("project").entityId(30L).status("ADMIN_REVIEW")
            .tenantId("tenant-a").build();
        DeletionRequest foreign = DeletionRequest.builder()
            .id(504L).entityType("project").entityId(40L).status("ADMIN_REVIEW")
            .tenantId("tenant-b").build();
        when(deletionRequestMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(sameTenant, foreign));

        List<Map<String, Object>> tasks = aggregator.collect(admin, Map.of(), new Date(), "tenant-a");

        assertThat(tasks).extracting(t -> t.get("id")).containsExactly("DEL-503");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<DeletionRequest>> query = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(deletionRequestMapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id");
        assertThat(query.getValue().getParamNameValuePairs()).containsValue("tenant-a");
    }

    @Test
    @DisplayName("AI 副驾组长只能看到目标属于本组的删除初审卡")
    void copilotLeaderDoesNotSeeAnotherGroupsDeletionReview() {
        IpdActor leader = new IpdActor(3L, "leader", "GROUP_LEADER", 10L);
        Project own = Project.builder().id(10L).mainGroupId(10L).tenantId("tenant-a").build();
        Project foreign = Project.builder().id(11L).mainGroupId(20L).tenantId("tenant-a").build();
        DeletionRequest ownRequest = DeletionRequest.builder()
            .id(501L).entityType("projects").entityId(10L).status("LEADER_REVIEW")
            .tenantId("tenant-a").build();
        DeletionRequest foreignRequest = DeletionRequest.builder()
            .id(502L).entityType("projects").entityId(11L).status("LEADER_REVIEW")
            .tenantId("tenant-a").build();
        when(deletionRequestMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(ownRequest, foreignRequest));
        when(projectMapper.selectById(10L)).thenReturn(own);
        when(projectMapper.selectById(11L)).thenReturn(foreign);
        DeletionRequestServiceImpl scopeService = new DeletionRequestServiceImpl(
            deletionRequestMapper, mock(ISystemConfigService.class), mock(IAuditLogService.class),
            mock(DeleteAuditService.class), mock(ProjectMemberMapper.class), projectMapper,
            mock(GateMapper.class), mock(ProductMapper.class), mock(PersonMapper.class));
        aggregator.setDeletionRequestService(scopeService);

        List<Map<String, Object>> tasks = aggregator.collect(leader,
            Map.of(own.getId(), own, foreign.getId(), foreign), new Date(), "tenant-a");

        assertThat(tasks).extracting(t -> t.get("id")).containsExactly("DEL-501");
        verify(projectMapper).selectById(10L);
        verify(projectMapper).selectById(11L);
    }
}
