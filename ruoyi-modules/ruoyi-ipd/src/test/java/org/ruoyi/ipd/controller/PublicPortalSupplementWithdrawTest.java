package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.advice.IpdServiceExceptionAdvice;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.dto.GuestDemandUpdateReq;
import org.ruoyi.ipd.dto.GuestDemandView;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.service.GuestDemandService;
import org.ruoyi.ipd.service.IAuditLogService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

/**
 * R3 游客需求补登/撤回 HTTP 端点验收（页39 用例1/用例2；AC-REQ-04/04b；BR-REQ-03/03a/03b）。
 * <p>端点只做接线：直接调 {@link GuestDemandService#supplement}/{@link GuestDemandService#withdraw}
 * （含业务校验/状态机/审计），本测试以 service 真实现 + mock Mapper 取证业务语义，
 * 以 mock service + {@link IpdServiceExceptionAdvice} 取证 HTTP 包络与 clientIp/User-Agent 接线。
 * <p>业务码语义（与 service 实现一一对应，勿凭想象扩展）：
 * <ul>
 *   <li>50001 NOT_FOUND：查询码格式非法（非 ^[A-Z0-9]{8}$）/ 查无此码（与 trace 同口径防枚举）。</li>
 *   <li>50002 STATE_CONFLICT：非 SUBMITTED（已受理/已撤回等）——受理后原文锁定不可补登（BR-REQ-03a），
 *       仅 SUBMITTED 可撤回（BR-REQ-03b）。</li>
 *   <li>10001 PARAM_INVALID：action 缺失/错配、supplement 双字段全空、functionalRequirement &lt; 6 字、
 *       DTO @Size 上限（4000/128）触发 @Valid、withdraw 缺 body（@RequestBody(required=false) 把 null
 *       交 service 同码收口）。</li>
 *   <li>40011 RATE_LIMITED：现网 supplement/withdraw 服务路径<b>未</b>接 GuestRateLimiter（仅
 *       submit/trace 消费配额），本类以 mock service 抛 40011 取证错误包络契约（HTTP 429 + $.code=40011）；
 *       若日后 service 补挂限流，此包络断言继续成立。</li>
 * </ul>
 * <p>mock 数据组合遵守真库可能态：ACCEPTED 必带 acceptedAt；WITHDRAWN 为终态不再变更。
 * <p>{@code @Tag("dev")} 必须：Surefire 按 {@code <groups>${profiles.active}</groups>} 过滤，缺 tag = 静默跳过假绿。
 */
@Tag("dev")
@DisplayName("R3 游客需求补登/撤回端点（页39 用例1/2 / AC-REQ-04/04b）")
class PublicPortalSupplementWithdrawTest {

    private static final String CODE = "AB12CD34";

    private RequirementMapper requirementMapper;
    private IAuditLogService auditLogService;
    private GuestDemandService.GuestRateLimiter limiter;
    private GuestDemandService service;

    @BeforeEach
    void setUp() {
        requirementMapper = mock(RequirementMapper.class);
        auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        limiter = mock(GuestDemandService.GuestRateLimiter.class);
        when(limiter.tryAcquire(anyString())).thenReturn(true);
        service = new GuestDemandService(requirementMapper, mock(ProductMapper.class),
            mock(ProjectMemberMapper.class), auditLogService, limiter);
        // D-1 接线适配：注入真实守卫（种子规则 + fail-closed 迁移闸）
        DefaultStateMachineGuard d1Guard = new DefaultStateMachineGuard(null, null);
        d1Guard.initRules();
        service.setStateMachineGuard(d1Guard);
    }

    /** 真库可能态组合：SUBMITTED 受理前；其余状态带 acceptedAt（WITHDRAWN 终态除外，撤回发生在受理前）。 */
    private static Requirement demand(String status) {
        Requirement r = new Requirement();
        r.setId(101L);
        r.setQueryCode(CODE);
        r.setStatus(status);
        r.setSource("PORTAL_GUEST");
        r.setCustomerName("深圳智控科技");
        r.setSubmitterName("王工");
        r.setContact("13800000000");
        r.setTitle("未指明型号 - 深圳智控科技");
        r.setContent("希望增加离线导出报表功能，支持按月归档");
        r.setCreateTime(new Date(System.currentTimeMillis() - 2 * 3_600_000L));
        if ("ACCEPTED".equals(status)) {
            r.setAcceptedAt(new Date(System.currentTimeMillis() - 1_800_000L));
        }
        return r;
    }

    private MockMvc mvc(GuestDemandService wired) {
        return MockMvcBuilders.standaloneSetup(new PublicPortalController(wired))
            .setControllerAdvice(new IpdServiceExceptionAdvice())
            .build();
    }

    // ==================== service 业务语义（真实现 + mock Mapper） ====================

    @Test
    @DisplayName("正例1：supplement 成功 → content/contact 补登落库 + view 回显 SUBMITTED + 审计 action=supplement")
    void supplementSuccessUpdatesFieldsAndAudits() {
        when(requirementMapper.selectOne(any())).thenReturn(demand("SUBMITTED"));

        GuestDemandView view = service.supplement(CODE,
            new GuestDemandUpdateReq("SUPPLEMENT", "补登：离线导出需支持 PDF 与 Excel 双格式", "13900001111"),
            "1.2.3.4", "JUnit-UA");

        assertEquals(CODE, view.queryCode());
        assertEquals("SUBMITTED", view.status());
        ArgumentCaptor<Requirement> saved = ArgumentCaptor.forClass(Requirement.class);
        verify(requirementMapper).updateById(saved.capture());
        assertEquals("补登：离线导出需支持 PDF 与 Excel 双格式", saved.getValue().getContent());
        assertEquals("13900001111", saved.getValue().getContact());
        ArgumentCaptor<AuditLog> auditCap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(auditCap.capture());
        assertEquals("supplement", auditCap.getValue().getAction());
        assertEquals("guest_demand", auditCap.getValue().getEntityType());
    }

    @Test
    @DisplayName("正例2：withdraw 成功 → 状态置 WITHDRAWN 终态 + 审计 action=withdraw（查询码保留可查历史）")
    void withdrawSuccessSetsTerminalStateAndAudits() {
        when(requirementMapper.selectOne(any())).thenReturn(demand("SUBMITTED"));

        GuestDemandView view = service.withdraw(CODE,
            new GuestDemandUpdateReq("WITHDRAW", null, null), "1.2.3.4", "JUnit-UA");

        assertEquals("WITHDRAWN", view.status());
        ArgumentCaptor<Requirement> saved = ArgumentCaptor.forClass(Requirement.class);
        verify(requirementMapper).updateById(saved.capture());
        assertEquals("WITHDRAWN", saved.getValue().getStatus());
        ArgumentCaptor<AuditLog> auditCap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(auditCap.capture());
        assertEquals("withdraw", auditCap.getValue().getAction());
    }

    @Test
    @DisplayName("反例1：查询码格式非法（小写/7位/含空格）→ 50001 且不触库（supplement/withdraw 双收口）")
    void malformedCodeNotFoundWithoutDb() {
        for (String bad : new String[] {"ab12cd34", "AB12CD3", "AB1 CD34"}) {
            IpdBusinessException s = assertThrows(IpdBusinessException.class,
                () -> service.supplement(bad, new GuestDemandUpdateReq("SUPPLEMENT", "补登内容六个字以上", null),
                    "1.2.3.4", "JUnit-UA"));
            assertEquals(ApiV1ErrorCode.NOT_FOUND, s.getErrorCode(), "supplement 非法码=" + bad);
            IpdBusinessException w = assertThrows(IpdBusinessException.class,
                () -> service.withdraw(bad, new GuestDemandUpdateReq("WITHDRAW", null, null),
                    "1.2.3.4", "JUnit-UA"));
            assertEquals(ApiV1ErrorCode.NOT_FOUND, w.getErrorCode(), "withdraw 非法码=" + bad);
        }
        verify(requirementMapper, never()).selectOne(any());
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("反例2：查无此码 → 50001 NOT_FOUND（与非法码同码防枚举）")
    void unknownCodeNotFound() {
        when(requirementMapper.selectOne(any())).thenReturn(null);
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> service.withdraw(CODE, new GuestDemandUpdateReq("WITHDRAW", null, null), "1.2.3.4", "JUnit-UA"));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode());
        verify(auditLogService, never()).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("反例3：已受理（ACCEPTED）→ 50002 STATE_CONFLICT（补登锁定 BR-REQ-03a / 不可撤 BR-REQ-03b），双端不落库")
    void acceptedBlocksSupplementAndWithdraw() {
        when(requirementMapper.selectOne(any())).thenReturn(demand("ACCEPTED"));
        IpdBusinessException s = assertThrows(IpdBusinessException.class,
            () -> service.supplement(CODE, new GuestDemandUpdateReq("SUPPLEMENT", "受理后想改原文", null),
                "1.2.3.4", "JUnit-UA"));
        assertEquals(ApiV1ErrorCode.STATE_CONFLICT, s.getErrorCode(), "受理后原文锁定仅可评论");
        IpdBusinessException w = assertThrows(IpdBusinessException.class,
            () -> service.withdraw(CODE, new GuestDemandUpdateReq("WITHDRAW", null, null), "1.2.3.4", "JUnit-UA"));
        assertEquals(ApiV1ErrorCode.STATE_CONFLICT, w.getErrorCode(), "受理后不可撤回");
        verify(requirementMapper, never()).updateById(any(Requirement.class));
    }

    @Test
    @DisplayName("反例4：参数收口 10001——action 错配/全空字段/内容过短/withdraw 传 null（service 契约，触库前拦截）")
    void paramInvalidPreDb() {
        when(requirementMapper.selectOne(any())).thenReturn(demand("SUBMITTED"));
        assertThrowsParamInvalid(() -> service.supplement(CODE,
            new GuestDemandUpdateReq("WITHDRAW", "action 错配也轮不到改字段", null), "1.2.3.4", "JUnit-UA"));
        assertThrowsParamInvalid(() -> service.supplement(CODE,
            new GuestDemandUpdateReq("SUPPLEMENT", null, null), "1.2.3.4", "JUnit-UA"));
        assertThrowsParamInvalid(() -> service.supplement(CODE,
            new GuestDemandUpdateReq("SUPPLEMENT", "五字内", null), "1.2.3.4", "JUnit-UA"));
        assertThrowsParamInvalid(() -> service.withdraw(CODE, null, "1.2.3.4", "JUnit-UA"));
        assertThrowsParamInvalid(() -> service.withdraw(CODE,
            new GuestDemandUpdateReq("SUPPLEMENT", null, null), "1.2.3.4", "JUnit-UA"));
        verify(requirementMapper, never()).updateById(any(Requirement.class));
    }

    private static void assertThrowsParamInvalid(org.junit.jupiter.api.function.Executable call) {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class, call);
        assertEquals(ApiV1ErrorCode.PARAM_INVALID, ex.getErrorCode());
    }

    // ==================== HTTP 包络（真实现 + IpdServiceExceptionAdvice） ====================

    @Test
    @DisplayName("HTTP1：POST supplement 成功 → 200 + ApiV1Response 包络 $.code=0 + data 回显")
    void httpSupplementSuccessEnvelope() throws Exception {
        when(requirementMapper.selectOne(any())).thenReturn(demand("SUBMITTED"));

        mvc(service).perform(post("/api/v1/public/demands/" + CODE + "/supplement")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Test-UA")
                .content("{\"action\":\"SUPPLEMENT\",\"functionalRequirement\":\"补登：离线导出支持按月归档\",\"contact\":\"13900001111\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.queryCode").value(CODE))
            .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
            .andExpect(jsonPath("$.data.titleSummary").value("未指明型号 - 深圳智控科技"));
    }

    @Test
    @DisplayName("HTTP2：POST withdraw 成功（body={\"action\":\"WITHDRAW\"}）→ 200 + $.code=0 + data.status=WITHDRAWN")
    void httpWithdrawSuccessEnvelope() throws Exception {
        when(requirementMapper.selectOne(any())).thenReturn(demand("SUBMITTED"));

        mvc(service).perform(post("/api/v1/public/demands/" + CODE + "/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Test-UA")
                .content("{\"action\":\"WITHDRAW\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.queryCode").value(CODE))
            .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
    }

    @Test
    @DisplayName("HTTP3：非法查询码（小写）→ HTTP 404 + $.code=50001（包络与既有 advice 形态一致）")
    void httpMalformedCodeNotFoundEnvelope() throws Exception {
        mvc(service).perform(post("/api/v1/public/demands/ab12cd34/supplement")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Test-UA")
                .content("{\"action\":\"SUPPLEMENT\",\"functionalRequirement\":\"补登：离线导出支持按月归档\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(50001));
        verify(requirementMapper, never()).selectOne(any());
    }

    @Test
    @DisplayName("HTTP4：已受理撤回 → HTTP 409 + $.code=50002（状态不允许）")
    void httpAcceptedWithdrawConflictEnvelope() throws Exception {
        when(requirementMapper.selectOne(any())).thenReturn(demand("ACCEPTED"));

        mvc(service).perform(post("/api/v1/public/demands/" + CODE + "/withdraw")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Test-UA")
                .content("{\"action\":\"WITHDRAW\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(50002));
    }

    @Test
    @DisplayName("HTTP5：withdraw 缺 body → HTTP 400 + $.code=10001（required=false 把 null 交 service 同码收口）")
    void httpWithdrawMissingBodyParamInvalid() throws Exception {
        mvc(service).perform(post("/api/v1/public/demands/" + CODE + "/withdraw")
                .header("User-Agent", "JUnit-Test-UA"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    @DisplayName("HTTP6：supplement 超 @Size 上限（4001 字）→ @Valid 拦截 HTTP 400 + $.code=10001")
    void httpSupplementSizeViolationParamInvalid() throws Exception {
        mvc(service).perform(post("/api/v1/public/demands/" + CODE + "/supplement")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Test-UA")
                .content("{\"action\":\"SUPPLEMENT\",\"functionalRequirement\":\"补" + "登".repeat(4000) + "\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value(10001));
    }

    // ==================== HTTP 接线与限流包络（mock service） ====================

    @Test
    @DisplayName("HTTP7：接线断言——clientIp 只取 getRemoteAddr（无视 XFF 伪造，SEC-REV-05）+ User-Agent 透传 service")
    void httpWiringPassesRemoteAddrAndUserAgent() throws Exception {
        GuestDemandService mockService = mock(GuestDemandService.class);
        when(mockService.supplement(eq(CODE), any(GuestDemandUpdateReq.class), eq("127.0.0.1"), eq("JUnit-Test-UA")))
            .thenReturn(new GuestDemandView(CODE, "SUBMITTED", null, null, "未指明型号 - 深圳智控科技"));
        when(mockService.withdraw(eq(CODE), isNull(), eq("127.0.0.1"), eq("JUnit-Test-UA")))
            .thenReturn(new GuestDemandView(CODE, "WITHDRAWN", null, null, "未指明型号 - 深圳智控科技"));
        MockMvc mvc = mvc(mockService);

        mvc.perform(post("/api/v1/public/demands/" + CODE + "/supplement")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Test-UA")
                .header("X-Forwarded-For", "9.9.9.9")
                .content("{\"action\":\"SUPPLEMENT\",\"functionalRequirement\":\"补登：离线导出支持按月归档\",\"contact\":\"13900001111\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0));
        ArgumentCaptor<GuestDemandUpdateReq> reqCap = ArgumentCaptor.forClass(GuestDemandUpdateReq.class);
        verify(mockService).supplement(eq(CODE), reqCap.capture(), eq("127.0.0.1"), eq("JUnit-Test-UA"));
        assertEquals("SUPPLEMENT", reqCap.getValue().action());
        assertEquals("补登：离线导出支持按月归档", reqCap.getValue().functionalRequirement());
        assertEquals("13900001111", reqCap.getValue().contact());

        mvc.perform(post("/api/v1/public/demands/" + CODE + "/withdraw")
                .header("User-Agent", "JUnit-Test-UA")
                .header("X-Forwarded-For", "9.9.9.9"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
        verify(mockService).withdraw(eq(CODE), isNull(), eq("127.0.0.1"), eq("JUnit-Test-UA"));
    }

    @Test
    @DisplayName("HTTP8：限流 40011 包络契约 → HTTP 429 + $.code=40011（现网 supplement/withdraw 未接限流，仅包络取证）")
    void httpRateLimitedEnvelope() throws Exception {
        GuestDemandService mockService = mock(GuestDemandService.class);
        when(mockService.supplement(eq(CODE), any(GuestDemandUpdateReq.class), eq("127.0.0.1"), eq("JUnit-Test-UA")))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.RATE_LIMITED));

        mvc(mockService).perform(post("/api/v1/public/demands/" + CODE + "/supplement")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "JUnit-Test-UA")
                .content("{\"action\":\"SUPPLEMENT\",\"functionalRequirement\":\"补登：离线导出支持按月归档\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value(40011));
    }
}
