package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.workbench.WorkbenchAggregator;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * WB-17-1 S0 切片契约测试：{@code WorkbenchService.tasks()} 过滤视图
 * （spec 页03 §4「tasks?bucket=&type=&limit=&projectId=」，前端 17 类 tab 数据面）。
 *
 * <p>钉死契约（非实现镜像断言）：
 * ①type 17 类合法值零误杀 + 非法值 fail-closed 400；②bucket 值域 pending|overdue，
 * completed/initiated 指向正确数据源契约的 fail-closed 文案；③limit 缺省 50/上限 200/
 * total=截断前命中数；④overdue 与 summary stats.overdue 同规则（dueDate before now）；
 * ⑤排序 urgent&gt;high&gt;normal + dueDate 升序 null 垫底；⑥与 summary 的 pendingType
 * 键集共用同一 ALL_TASK_TYPES 权威（17 类全通过滤校验）。
 *
 * <p>聚合器以 mock 投递合成卡（仅测调度/过滤层，不伪造业务数据源——聚合器自身真数据
 * 由各 *AggregatorTest 守）。@Tag("dev") 项目级 surefire 守门。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkbenchTasksSliceTest {

    private static final IpdActor ACTOR = new IpdActor(900101L, "超管", "SUPER_ADMIN", 900001L);

    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private NotificationService notificationService;
    @Mock private DeletionRequestMapper deletionRequestMapper;
    @Mock private CoefficientChangeRequestMapper coefficientChangeRequestMapper;
    @Mock private LaunchDateChangeRequestMapper launchDateChangeRequestMapper;
    @Mock private WorkbenchAggregator aggA;
    @Mock private WorkbenchAggregator aggB;

    private WorkbenchService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @BeforeEach
    void setUp() {
        service = new WorkbenchService(projectMapper, projectMemberMapper, stageActionMapper,
            notificationService, List.of(aggA, aggB),
            deletionRequestMapper, coefficientChangeRequestMapper, launchDateChangeRequestMapper);
        Project p1 = new Project();
        p1.setId(7L);
        p1.setName("项目7");
        when(projectMapper.selectList(any())).thenReturn(List.of(p1));
        lenient().when(aggA.collect(any(), any(), any(Date.class))).thenReturn(List.of(
            card("A1", "stage_sign", 7L, "high", hours(-24)),
            card("A2", "key_gate", 8L, "urgent", hours(-48)),
            card("A3", "deletion_review", 7L, "normal", null)));
        lenient().when(aggB.collect(any(), any(), any(Date.class))).thenReturn(List.of(
            card("B1", "deletion_review", 9L, "normal", hours(+72))));
    }

    /* ---------- helpers ---------- */

    private static Date hours(long delta) {
        return new Date(System.currentTimeMillis() + delta * 3600_000L);
    }

    private static Map<String, Object> card(String id, String type, Long projectId, String priority, Date due) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("taskType", type);
        m.put("projectId", projectId);
        m.put("priority", priority);
        m.put("dueDate", due);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> tasksOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("tasks");
    }

    /* ---------- 1. pending 缺省 + 结构契约 ---------- */

    @Test
    @DisplayName("#1 bucket 缺省=pending：全量在途卡 + total/returned/bucket 回声")
    void defaultBucketPendingReturnsAllCards() {
        Map<String, Object> r = service.tasks(ACTOR, null, null, null, null);
        assertThat(r.get("bucket")).isEqualTo("pending");
        assertThat(r.get("limit")).isEqualTo(50);
        assertThat(r.get("type")).isNull();
        assertThat(tasksOf(r)).hasSize(4);
        assertThat(r.get("total")).isEqualTo(4);
        assertThat(r.get("returned")).isEqualTo(4);
    }

    /* ---------- 2. type 过滤 ---------- */

    @Test
    @DisplayName("#2 type=deletion_review 仅投该类卡（17 类 tab 数据面核心断言）")
    void typeFilterKeepsOnlyMatchingCards() {
        Map<String, Object> r = service.tasks(ACTOR, null, null, "deletion_review", null);
        assertThat(tasksOf(r)).hasSize(2)
            .allSatisfy(m -> assertThat(m.get("taskType")).isEqualTo("deletion_review"));
        assertThat(r.get("total")).isEqualTo(2);
    }

    @Test
    @DisplayName("#3 非法 type fail-closed（防前端拼错静默返空伪装正常）")
    void invalidTypeFailsClosed() {
        assertThatThrownBy(() -> service.tasks(ACTOR, null, null, "not_a_real_type", null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("type 非法");
    }

    @Test
    @DisplayName("#4 spec 页03 权威 17 类全部通过 type 校验（与 summary pendingType 键集同源零漂移）")
    void allSeventeenAuthoritativeTypesPassValidation() {
        for (String type : List.of("stage_sign", "key_gate", "key_gate_arbitration", "deletion_review",
            "waiver_review", "handover", "rd_replacement", "contribution_confirm", "receipt_review",
            "retirement_review", "strategic_change", "capacity_approval", "kpi_fill",
            "change_implementation", "change_verify", "bonus_lock", "closeout")) {
            Map<String, Object> r = service.tasks(ACTOR, null, null, type, null);
            assertThat(tasksOf(r)).as("type=%s 合法值不得抛", type)
                .allSatisfy(m -> assertThat(m.get("taskType")).isEqualTo(type));
        }
    }

    /* ---------- 3. bucket 语义 ---------- */

    @Test
    @DisplayName("#5 bucket=overdue 与 summary stats.overdue 同规则（dueDate 早于当前，无期限卡不入）")
    void overdueBucketMatchesSummaryRule() {
        Map<String, Object> r = service.tasks(ACTOR, null, "overdue", null, null);
        assertThat(tasksOf(r)).extracting(m -> m.get("id")).containsExactlyInAnyOrder("A1", "A2");
    }

    @Test
    @DisplayName("#6 bucket=completed/initiated fail-closed 且文案指向正确数据源契约")
    void unsupportedBucketsFailClosedWithGuidance() {
        assertThatThrownBy(() -> service.tasks(ACTOR, null, "completed", null, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("stats.completed");
        assertThatThrownBy(() -> service.tasks(ACTOR, null, "initiated", null, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("my-initiated");
    }

    @Test
    @DisplayName("#7 未知 bucket fail-closed（值域 pending|overdue）")
    void unknownBucketFailsClosed() {
        assertThatThrownBy(() -> service.tasks(ACTOR, null, "whatever", null, null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("bucket 非法");
    }

    /* ---------- 4. projectId / limit ---------- */

    @Test
    @DisplayName("#8 projectId=7 精确过滤（卡面 projectId 锚：A1/A3 属项目7，A2=8/B1=9 不入）")
    void projectIdFilter() {
        Map<String, Object> r = service.tasks(ACTOR, 7L, null, null, null);
        assertThat(tasksOf(r)).hasSize(2)
            .allSatisfy(m -> assertThat(m.get("projectId")).isEqualTo(7L));
        assertThat(r.get("total")).isEqualTo(2);
    }

    @Test
    @DisplayName("#9 limit=1：returned 截断为 1，total 仍为截断前命中数")
    void limitTruncatesButTotalKeepsPreTruncationCount() {
        Map<String, Object> r = service.tasks(ACTOR, null, null, null, 1);
        assertThat(r.get("returned")).isEqualTo(1);
        assertThat(r.get("total")).isEqualTo(4);
    }

    @Test
    @DisplayName("#10 limit 越界（0/201）fail-closed")
    void limitBoundsFailClosed() {
        assertThatThrownBy(() -> service.tasks(ACTOR, null, null, null, 0))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("limit");
        assertThatThrownBy(() -> service.tasks(ACTOR, null, null, null, 201))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("limit");
    }

    /* ---------- 5. 排序契约 ---------- */

    @Test
    @DisplayName("#11 排序：urgent>high>normal；同优先级 dueDate 升序、null 垫底")
    void orderingFollowsPrototypeContract() {
        Map<String, Object> r = service.tasks(ACTOR, null, null, null, null);
        // 投卡：A2 urgent(-48h) / A1 high(-24h) / B1 normal(+72h) / A3 normal(null)
        assertThat(tasksOf(r)).extracting(m -> m.get("id"))
            .containsExactly("A2", "A1", "B1", "A3");
    }
}
