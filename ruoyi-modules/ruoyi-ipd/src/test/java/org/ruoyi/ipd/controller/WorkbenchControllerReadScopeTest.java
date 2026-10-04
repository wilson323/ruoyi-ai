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
import org.ruoyi.ipd.workbench.domain.MyInitiatedTask;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工作台两个「我的」读端点的<b>读取范围</b>守卫：
 * {@code GET /api/v1/workbench/my-initiated} 与 {@code /my-pending-approvals}
 * 只能查会话本人，不接受调用方指定 personId。
 *
 * <p><b>缺陷形态（修复前）</b>：两处均为
 * {@code long pid = personId != null ? personId : actor.id();}，而正上方 javadoc 写着
 * 「personId 缺省 = 当前登录人（从会话推导，SEC-API-01 强制）」——注释声称强制、代码却放行任意值。
 * 后果：任何内部用户传别人的 id 即可读到别人的待审/已发起单据清单（含单据标题）。
 * 对 {@code my-pending-approvals} 更重：service 按 personId 反查 Person 再推导角色
 * （SUPER_ADMIN→ADMIN_REVIEW / GROUP_LEADER→LEADER_REVIEW），传他人 id 等于拿到<b>他人的角色视角</b>。
 *
 * <p><b>本测试为什么能证明「A 传 B 的 id 读不到 B 的数据」</b>：service 以 personId 为键钉了两份
 * <b>互不相同</b>的载荷（本人 =「我的单据」/ 他人 =「别人的单据」）。于是断言有两层——
 * <ol>
 *   <li>响应体必须落在「我的单据」（若参数被重新接受，响应会变成「别人的单据」→ 变红）；</li>
 *   <li>{@code verify(never())} 断言 service 从未以他人 id 被调用（若参数被透传，调用即发生 → 变红）。</li>
 * </ol>
 * 两层都红才叫真的修好；只断言 200 无法区分「忽略了参数」与「参数生效了但恰好返回空」。
 *
 * <p>standalone MockMvc 走真 Controller HTTP 链（路由绑定 / 参数解析 / JSON 序列化），
 * 不注册 Sa-Token 拦截器，故 {@code ipdPermission.requireInternal()} 由 mock 供给会话身份。
 * {@code @Tag("dev")} 必须，否则 Surefire 静默跳过（假绿陷阱）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkbenchControllerReadScopeTest {

    /** 会话身份（本人）。 */
    private static final long SELF_ID = 900101L;
    /** 别人（攻击者想读的目标）。 */
    private static final long OTHER_ID = 900999L;

    private static final IpdActor ACTOR = new IpdActor(SELF_ID, "本人", "MARKET_PM", 900001L);

    @Mock private IpdPermission ipdPermission;
    @Mock private WorkbenchService workbenchService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new WorkbenchController(ipdPermission, workbenchService)).build();
        when(ipdPermission.requireInternal()).thenReturn(ACTOR);

        // 两份载荷按 personId 分键，内容可区分——这是本测试能「证伪」的关键仪器。
        when(workbenchService.myInitiated(SELF_ID)).thenReturn(List.of(card("我的单据")));
        when(workbenchService.myInitiated(OTHER_ID)).thenReturn(List.of(card("别人的单据")));
        when(workbenchService.myPendingApprovals(SELF_ID)).thenReturn(List.of(card("我的待审")));
        when(workbenchService.myPendingApprovals(OTHER_ID)).thenReturn(List.of(card("别人的待审")));
    }

    private static MyInitiatedTask card(String title) {
        return MyInitiatedTask.builder().title(title).build();
    }

    /* ---------- my-initiated ---------- */

    @Test
    @DisplayName("my-initiated 无参：以会话身份查询，返回本人单据")
    void myInitiated_usesSessionIdentity() throws Exception {
        mvc.perform(get("/api/v1/workbench/my-initiated"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].title").value("我的单据"));
        verify(workbenchService).myInitiated(SELF_ID);
    }

    @Test
    @DisplayName("my-initiated 传他人 personId：忽略该参数，仍返回本人单据，service 从未收到他人 id")
    void myInitiated_ignoresForeignPersonId() throws Exception {
        mvc.perform(get("/api/v1/workbench/my-initiated").param("personId", String.valueOf(OTHER_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].title").value("我的单据"));

        verify(workbenchService).myInitiated(SELF_ID);
        verify(workbenchService, never()).myInitiated(OTHER_ID);
    }

    /* ---------- my-pending-approvals ---------- */

    @Test
    @DisplayName("my-pending-approvals 无参：以会话身份查询，返回本人待审")
    void myPendingApprovals_usesSessionIdentity() throws Exception {
        mvc.perform(get("/api/v1/workbench/my-pending-approvals"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].title").value("我的待审"));
        verify(workbenchService).myPendingApprovals(SELF_ID);
    }

    @Test
    @DisplayName("my-pending-approvals 传他人 personId：忽略该参数，仍返回本人待审，service 从未收到他人 id")
    void myPendingApprovals_ignoresForeignPersonId() throws Exception {
        mvc.perform(get("/api/v1/workbench/my-pending-approvals").param("personId", String.valueOf(OTHER_ID)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].title").value("我的待审"));

        verify(workbenchService).myPendingApprovals(SELF_ID);
        verify(workbenchService, never()).myPendingApprovals(OTHER_ID);
    }
}
