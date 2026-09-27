package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.WorkbenchService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WB-17-1 S0 切片 HTTP 接线测试：GET /api/v1/workbench/tasks 路由 + 参数透传 + ApiV1 包络。
 *
 * <p>standalone MockMvc 走真 Controller HTTP 链（路由绑定/参数解析/JSON 序列化），
 * service 层以 mock 钉透传契约；过滤语义本身由 WorkbenchTasksSliceTest 守，不重复断言。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkbenchTasksControllerWiringTest {

    private static final IpdActor ACTOR = new IpdActor(900101L, "超管", "SUPER_ADMIN", 900001L);

    @Mock private IpdPermission ipdPermission;
    @Mock private WorkbenchService workbenchService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WorkbenchController(ipdPermission, workbenchService)).build();
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);
    }

    @Test
    @DisplayName("GET /api/v1/workbench/tasks?bucket=overdue&type=handover&limit=5&projectId=7 → 200 + 四参透传 service")
    void getTasksRoutesParamsToService() throws Exception {
        when(workbenchService.tasks(eq(ACTOR), eq(7L), eq("overdue"), eq("handover"), eq(5)))
            .thenReturn(Map.of("bucket", "overdue", "type", "handover", "total", 0,
                "returned", 0, "tasks", List.of()));
        mvc.perform(get("/api/v1/workbench/tasks")
                .param("projectId", "7").param("bucket", "overdue")
                .param("type", "handover").param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bucket").value("overdue"))
            .andExpect(jsonPath("$.data.type").value("handover"));
    }

    @Test
    @DisplayName("无参访问：bucket/type/projectId/limit 全 null 透传（缺省语义归 service 层）")
    void getTasksWithoutParamsPassesNulls() throws Exception {
        when(workbenchService.tasks(eq(ACTOR), isNull(), isNull(), isNull(), isNull()))
            .thenReturn(Map.of("bucket", "pending", "total", 0, "returned", 0, "tasks", List.of()));
        mvc.perform(get("/api/v1/workbench/tasks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.bucket").value("pending"));
    }
}
