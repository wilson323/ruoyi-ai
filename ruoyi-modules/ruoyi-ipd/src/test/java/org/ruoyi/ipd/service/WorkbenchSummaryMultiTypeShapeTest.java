package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.workbench.WorkbenchAggregator;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * WB-17-1 多类型形状锁（R217-B4，2026-09-25）。
 *
 * <p>既有 WorkbenchServiceTest 只 mock 了 2 类聚合器（stage/deletion）验证调度骨架；
 * 本类把「9 类 implemented 聚合器同时投递」的真实形状一次性锁死，防三类回归：
 * <ol>
 *   <li><b>类型回归</b>：卡面 taskType 回退成早期硬编码 "stage_action"（WB-17-1 立卡病根），
 *       或某实现类投出 17 类枚举之外的值</li>
 *   <li><b>包络回归</b>：前端既有消费形状被破坏——顶层 stats/tasks/deletionPending/currentAdvance、
 *       stats 四老字段 Integer 装箱、pendingType 17 键全量预置（implemented&gt;0 / PLANNED=0）</li>
 *   <li><b>卡字段回归</b>：聚合器契约 13 必填键（WorkbenchAggregator javadoc）缺键</li>
 * </ol>
 *
 * <p>用假聚合器（FixedAggregator）投卡而非真库——真库各域行为已被 9 个 *AggregatorTest 分域锁定，
 * 本类只管 summary() 组装面的跨类型不变量；剩余 8 类 PLANNED 阻塞原因登记见
 * {@link WorkbenchService} ALL_TASK_TYPES javadoc。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WorkbenchSummaryMultiTypeShapeTest {

    /** 契约登记 yaml implemented:true 的 9 类（顺序=各自 @Order 预期调度序）。 */
    private static final List<String> IMPLEMENTED_TYPES = List.of(
        "stage_sign", "key_gate", "key_gate_arbitration", "deletion_review", "handover",
        "contribution_confirm", "strategic_change", "closeout", "kpi_fill");

    /** 剩余 8 类 PLANNED（pendingType 必须预置 0 键，缺表 4 + 口径待拍板 4）。 */
    private static final List<String> PLANNED_TYPES = List.of(
        "waiver_review", "rd_replacement", "receipt_review", "retirement_review",
        "capacity_approval", "change_implementation", "change_verify", "bonus_lock");

    /** WorkbenchAggregator.collect 卡契约 13 必填键。 */
    private static final List<String> CARD_CONTRACT_KEYS = List.of(
        "id", "projectId", "projectName", "projectCode", "actionCode", "title",
        "taskType", "status", "priority", "ownerRole", "dueDate", "isBlocking", "deepLink");

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private StageActionMapper stageActionMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private DeletionRequestMapper deletionRequestMapper;
    @Mock
    private CoefficientChangeRequestMapper coefficientChangeRequestMapper;
    @Mock
    private LaunchDateChangeRequestMapper launchDateChangeRequestMapper;

    private final IpdActor actor = new IpdActor(99L, "root", "SUPER_ADMIN", null);

    @BeforeEach
    void stubCommon() {
        // SUPER_ADMIN 可见范围=全部 ACTIVE 项目（单项目即可覆盖形状断言）
        lenient().when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(Project.builder().id(10L).code("P-001").name("项目A")
                .currentStage("CDP").status("ACTIVE").build()));
        // currentAdvance：无阶段动作 → next=null，仍返回 advance 包络
        lenient().when(stageActionMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of());
        lenient().when(notificationService.unreadCount(any(Long.class))).thenReturn(5L);
        // 我发起的三表各 1 → stats.myInitiated=3
        lenient().when(deletionRequestMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        lenient().when(coefficientChangeRequestMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        lenient().when(launchDateChangeRequestMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
    }

    /** 固定投卡假聚合器：taskType 由构造决定，collect 透传预置卡。 */
    private static final class FixedAggregator implements WorkbenchAggregator {
        private final String taskType;
        private final List<Map<String, Object>> cards;
        private final int completed;

        FixedAggregator(String taskType, List<Map<String, Object>> cards, int completed) {
            this.taskType = taskType;
            this.cards = cards;
            this.completed = completed;
        }

        @Override
        public String taskType() {
            return taskType;
        }

        @Override
        public List<Map<String, Object>> collect(IpdActor a, Map<Long, Project> visibleProjects, Date now) {
            return cards;
        }

        @Override
        public int completedCount(IpdActor a, Map<Long, Project> visibleProjects) {
            return completed;
        }
    }

    private static Map<String, Object> card(String taskType, long seq, Date dueDate) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", seq);
        c.put("projectId", 10L);
        c.put("projectName", "项目A");
        c.put("projectCode", "P-001");
        c.put("actionCode", "T-" + seq);
        c.put("title", taskType + " 任务");
        c.put("taskType", taskType);
        c.put("status", "PENDING");
        c.put("priority", dueDate != null ? "high" : "normal");
        c.put("ownerRole", null);
        c.put("dueDate", dueDate);
        c.put("isBlocking", "N");
        c.put("deepLink", "/ipd/" + taskType + "/" + seq);
        return c;
    }

    private WorkbenchService newService(List<Map<String, Object>> perTypeCards) {
        List<WorkbenchAggregator> aggregators = new ArrayList<>();
        for (Map<String, Object> card : perTypeCards) {
            aggregators.add(new FixedAggregator(String.valueOf(card.get("taskType")), List.of(card), 0));
        }
        return new WorkbenchService(projectMapper, projectMemberMapper, stageActionMapper,
            notificationService, aggregators,
            deletionRequestMapper, coefficientChangeRequestMapper, launchDateChangeRequestMapper);
    }

    @Test
    @DisplayName("9 类 implemented 同时投递：tasks 保序 9 类；pendingType 17 键（implemented 各=1 / PLANNED 各=0）")
    @SuppressWarnings("unchecked")
    void nineImplementedTypesCoexist_pendingTypeFull17Keys() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String t : IMPLEMENTED_TYPES) {
            cards.add(card(t, cards.size() + 1, null));
        }
        Map<String, Object> result = newService(cards).summary(actor, null);

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        assertThat(tasks).hasSize(9);
        assertThat(tasks).extracting(t -> t.get("taskType")).containsExactlyElementsOf(IMPLEMENTED_TYPES);

        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertThat(stats.get("pending")).isEqualTo(9);
        assertThat(stats.get("unread")).isEqualTo(5);
        assertThat(stats.get("myInitiated")).isEqualTo(3);

        Map<String, Integer> pendingType = (Map<String, Integer>) stats.get("pendingType");
        assertThat(pendingType).as("17 类键全量").hasSize(17);
        for (String t : IMPLEMENTED_TYPES) {
            assertThat(pendingType.get(t)).as("implemented %s 计数", t).isEqualTo(1);
        }
        for (String t : PLANNED_TYPES) {
            assertThat(pendingType.get(t)).as("PLANNED %s 预置 0（键必须存在）", t).isZero();
        }
    }

    @Test
    @DisplayName("类型值域锁死：taskType 永不回退早期硬编码 stage_action，且全部 ∈ pendingType 17 键（=ALL_TASK_TYPES）")
    @SuppressWarnings("unchecked")
    void taskTypeNeverRegressesToLegacyLiteralAndStaysInEnum() {
        List<Map<String, Object>> cards = List.of(
            card("stage_sign", 1, null), card("key_gate", 2, null), card("closeout", 3, null));
        Map<String, Object> result = newService(cards).summary(actor, null);

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertThat(tasks).extracting(t -> t.get("taskType"))
            .as("WB-17-1 立卡病根=硬编码单类 stage_action，永不得复发")
            .doesNotContain("stage_action");
        assertThat(tasks).allSatisfy(t ->
            assertThat(((Map<String, Integer>) stats.get("pendingType")).keySet())
                .as("taskType=%s 必须在 17 类枚举内", t.get("taskType"))
                .contains((String) t.get("taskType")));
    }

    @Test
    @DisplayName("向后兼容包络：顶层 stats/tasks/deletionPending/currentAdvance 健在；stats 四老字段 Integer 装箱；deletionPending 按类型计数")
    @SuppressWarnings("unchecked")
    void responseEnvelopeStaysBackwardCompatible() {
        List<Map<String, Object>> cards = List.of(
            card("stage_sign", 1, null),
            card("deletion_review", 2, null),
            card("deletion_review", 3, null));
        Map<String, Object> result = newService(cards).summary(actor, null);

        assertThat(result).containsKeys("stats", "tasks", "deletionPending", "currentAdvance");
        assertThat(result.get("tasks")).isInstanceOf(List.class);

        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        // 前端 WorkbenchSummary 消费的四个老字段：键在 + Integer 装箱（Long 会被全局序列化变字符串）
        for (String legacy : List.of("pending", "overdue", "unread", "completed")) {
            assertThat(stats.get(legacy)).as("stats.%s 必须 Integer", legacy).isInstanceOf(Integer.class);
        }
        assertThat(result.get("deletionPending")).isInstanceOf(Integer.class).isEqualTo(2);
        assertThat(stats.get("pending")).isEqualTo(3);
    }

    @Test
    @DisplayName("卡字段契约：任意类型投递卡必须齐 WorkbenchAggregator 13 必填键")
    @SuppressWarnings("unchecked")
    void everyCardCarries13ContractKeys() {
        List<Map<String, Object>> cards = new ArrayList<>();
        for (String t : IMPLEMENTED_TYPES) {
            cards.add(card(t, cards.size() + 1, null));
        }
        Map<String, Object> result = newService(cards).summary(actor, null);
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        for (Map<String, Object> t : tasks) {
            assertThat(t).as("taskType=%s 卡契约键", t.get("taskType")).containsKeys(
                "id", "projectId", "projectName", "projectCode", "actionCode", "title",
                "taskType", "status", "priority", "ownerRole", "dueDate", "isBlocking", "deepLink");
            assertThat(CARD_CONTRACT_KEYS).hasSize(13);
        }
    }

    @Test
    @DisplayName("跨类型聚合口径：overdue 按任意类型卡 dueDate<now 计数；completed 由各域 completedCount 求和")
    @SuppressWarnings("unchecked")
    void overdueAndCompletedAggregateAcrossTypes() {
        Date past = new Date(System.currentTimeMillis() - 86400_000L);
        List<WorkbenchAggregator> aggregators = List.of(
            new FixedAggregator("stage_sign", List.of(card("stage_sign", 1, past)), 2),
            new FixedAggregator("key_gate", List.of(card("key_gate", 2, past)), 0),
            new FixedAggregator("closeout", List.of(card("closeout", 3, past)), 1),
            new FixedAggregator("kpi_fill", List.of(card("kpi_fill", 4, null)), 0));
        WorkbenchService service = new WorkbenchService(projectMapper, projectMemberMapper,
            stageActionMapper, notificationService, aggregators,
            deletionRequestMapper, coefficientChangeRequestMapper, launchDateChangeRequestMapper);

        Map<String, Object> result = service.summary(actor, null);
        Map<String, Object> stats = (Map<String, Object>) result.get("stats");
        assertThat(stats.get("overdue")).as("3 类卡逾期统一按 dueDate 口径").isEqualTo(3);
        assertThat(stats.get("completed")).as("completedCount 跨域求和 2+0+1+0").isEqualTo(3);
        assertThat(stats.get("pending")).isEqualTo(4);
    }
}
