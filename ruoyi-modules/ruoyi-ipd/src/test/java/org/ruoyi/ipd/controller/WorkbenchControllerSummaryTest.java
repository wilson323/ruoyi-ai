package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.WorkbenchService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-4: WorkbenchController.summary「我发起的」单测,验证 controller->service 路由 +
 * stats.myInitiated 字段契约。{@code @Tag("dev")} 必须,否则 Surefire 静默跳过(假绿陷阱)。
 *
 * <p>覆盖 3 个维度(用户拍板:仅「我发起的」,「我的关注」无数据模型不做):
 * <ol>
 *   <li>正常路径:mock requireInternal 返回 actor -> service.summary 收到 (actor, projectId)
 *       -> 返回的 stats 中含 myInitiated 数字字段(与既有 pending/overdue/completed 并列)</li>
 *   <li>字段不变:stats 中既有 pending/overdue/completed 三个字段值与 mock 输入完全一致(不漂移)</li>
 *   <li>零值边界:service 返回 myInitiated=0 -> 包络 data.stats.myInitiated = 0(非 null)</li>
 * </ol>
 *
 * <p>本测试仅断言「我发起的」字段在 stats 中存在且数字类型正确,不去验证具体的 SQL 拼接,
 * 那部分由 WorkbenchServiceTest 覆盖(构造函数注入 3 个新 mapper 的契约)。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class WorkbenchControllerSummaryTest {

    @Mock
    private IpdPermission ipdPermission;

    @Mock
    private WorkbenchService workbenchService;

    @InjectMocks
    private WorkbenchController controller;

    /**
     * 构造一份完整的 stats + tasks 容器,便于测试中复用:保证 myInitiated 与既有四个字段并列存在。
     */
    private Map<String, Object> buildSummary(long pending, long overdue, long unread, long completed, long myInitiated) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", (int) pending);
        stats.put("overdue", (int) overdue);
        stats.put("unread", (int) unread);
        stats.put("completed", (int) completed);
        stats.put("myInitiated", (int) myInitiated);
        Map<String, Integer> pendingType = new LinkedHashMap<>();
        pendingType.put("stage_sign", 0);
        pendingType.put("deletion_review", 0);
        stats.put("pendingType", pendingType);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("tasks", List.of());
        result.put("deletionPending", 0);
        result.put("currentAdvance", null);
        return result;
    }

    @Test
    @DisplayName("P1-4 #1:summary 含「我发起的」数字字段(与 pending/overdue/completed 并列)")
    @SuppressWarnings("unchecked")
    void summary_returnsMyInitiatedCount() {
        IpdActor actor = new IpdActor(9001L, "alice", "MARKET_PM", 100L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(workbenchService.summary(any(IpdActor.class), any()))
            .thenReturn(buildSummary(3, 1, 2, 5, 7));

        ApiV1Response<Map<String, Object>> resp = controller.summary(null);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        assertThat(resp.getData()).isNotNull();
        Map<String, Object> stats = (Map<String, Object>) resp.getData().get("stats");
        assertThat(stats)
            .as("stats 必须含 myInitiated 字段(P1-4)")
            .containsKey("myInitiated");
        assertThat(stats.get("myInitiated"))
            .as("myInitiated 必须是 Integer 类型,与既有 pending/overdue/completed 同型")
            .isInstanceOf(Integer.class)
            .isEqualTo(7);

        // 验证 actor + projectId(null) 透传
        ArgumentCaptor<IpdActor> actorCaptor = ArgumentCaptor.forClass(IpdActor.class);
        ArgumentCaptor<Long> projectCaptor = ArgumentCaptor.forClass(Long.class);
        verify(workbenchService).summary(actorCaptor.capture(), projectCaptor.capture());
        assertThat(actorCaptor.getValue().id()).isEqualTo(9001L);
        assertThat(projectCaptor.getValue()).isNull();
    }

    @Test
    @DisplayName("P1-4 #2:summary 其他四个字段(pending/overdue/unread/completed)数字保持不变")
    @SuppressWarnings("unchecked")
    void summary_preservesPendingOverdueCompleted() {
        IpdActor actor = new IpdActor(9002L, "bob", "RD_PM", 200L);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(workbenchService.summary(any(IpdActor.class), any()))
            .thenReturn(buildSummary(11, 4, 9, 17, 3));

        ApiV1Response<Map<String, Object>> resp = controller.summary(200L);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        Map<String, Object> stats = (Map<String, Object>) resp.getData().get("stats");
        // 既有四个字段值与 mock 输入完全一致——不漂移
        assertThat(stats.get("pending")).isEqualTo(11);
        assertThat(stats.get("overdue")).isEqualTo(4);
        assertThat(stats.get("unread")).isEqualTo(9);
        assertThat(stats.get("completed")).isEqualTo(17);
        // 新增字段也在
        assertThat(stats.get("myInitiated")).isEqualTo(3);

        // projectId=200 透传
        verify(workbenchService).summary(any(IpdActor.class), eq(200L));
    }

    @Test
    @DisplayName("P1-4 #3:service 返回 myInitiated=0 -> 包络 data.stats.myInitiated = 0(非 null)")
    @SuppressWarnings("unchecked")
    void summary_withNoInitiated_returnsZero() {
        IpdActor actor = new IpdActor(9003L, "carol", "SUPER_ADMIN", null);
        when(ipdPermission.requireInternal()).thenReturn(actor);
        when(workbenchService.summary(any(IpdActor.class), any()))
            .thenReturn(buildSummary(0, 0, 0, 0, 0));

        ApiV1Response<Map<String, Object>> resp = controller.summary(null);

        assertThat(resp.getCode()).isEqualTo(ApiV1Response.CODE_SUCCESS);
        Map<String, Object> stats = (Map<String, Object>) resp.getData().get("stats");
        // 关键契约:myInitiated 必须存在且为 0(不能是 null,否则前端 ?? 0 兜底无意义)
        assertThat(stats).containsKey("myInitiated");
        assertThat(stats.get("myInitiated"))
            .as("actor 无任何业务单据时,myInitiated=0(Int 0),不是 null 也不是负数")
            .isInstanceOf(Integer.class)
            .isEqualTo(0);
    }
}
