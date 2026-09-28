package org.ruoyi.ipd.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.controller.BidController;
import org.ruoyi.ipd.controller.BidResponseCheckController;
import org.ruoyi.ipd.controller.BidResponseCheckController.BidCheckReq;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI-P2-2 #2 应标完整性检查切片回归（提交前自检，纯只读）：
 * ①参数闸（responseNote 必填/上限）与招标单存在性 → 拒绝且 AI 零触发；
 * ②正常 → 逐条检查表（MET/PARTIAL/MISSING + 证据）+ 总评 + 审计三件套
 * aiRole=precheck（过 AI-P1-3 留痕门禁），BR-AI-04 原文不落审计；
 * ③全路径零业务表写入（检查≠提交，提交仍走 POST /bid-responses 人工流）；
 * ④模型失败/畸形输出（非 JSON/空 checks/非法状态/缺 summary）→ FAIL:PARSE 拒绝，不静默补表；
 * ⑤反漂移：注解码与 submitResponse 同源且目录已登记。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI-P2-2#2 应标完整性检查：参数闸/严格解析/只读断言/审计三件套")
class BidResponseCheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CHECK_JSON = "{\"checks\":["
        + "{\"requirement\":\"7x24 支持\",\"status\":\"MET\",\"evidence\":\"方案提及 7x24 响应\"},"
        + "{\"requirement\":\"Q4 交付\",\"status\":\"PARTIAL\",\"evidence\":\"仅提及四季度，无节点\"},"
        + "{\"requirement\":\"温测报告\",\"status\":\"MISSING\",\"evidence\":\"未提及\"}],"
        + "\"summary\":\"主要缺口在温测报告\"}";

    @Mock private BidInvitationMapper invitationMapper;
    @Mock private IAiModelConfigService modelConfigService;
    @Mock private AiGateway aiGateway;
    @Mock private IAuditLogService auditLogService;
    @Mock private IpdAuthSession session;
    @Mock private IpdAuthService authService;

    private BidResponseCheckService service;
    private BidResponseCheckController controller;

    @BeforeEach
    void setUp() {
        service = new BidResponseCheckService(invitationMapper, modelConfigService, aiGateway, auditLogService)
            .withClock(java.time.Clock.fixed(java.time.Instant.parse("2026-09-27T08:00:00Z"),
                java.time.ZoneId.of("UTC")));
        controller = new BidResponseCheckController(service, new IpdPermission(session, authService));
    }

    /** 会话身份构造（同 BidAiCompareTest 惯例：角色只来自 currentPerson）。 */
    private void loginAs(String personType) {
        Person person = Person.builder()
            .id(7L).name("U7").personType(personType).groupId(3L)
            .accountStatus("ACTIVE").employmentStatus("ACTIVE").delFlag("0")
            .build();
        lenient().when(session.currentPerson()).thenReturn(person);
        lenient().when(authService.scopeOf(person)).thenReturn(IpdAuthService.Scope.FULL);
    }

    private BidInvitation invitation() {
        BidInvitation inv = new BidInvitation();
        inv.setId(1L);
        inv.setStatus("OPEN");
        inv.setTitle("核心网升级招标");
        inv.setContent("Q4 交付，要求 7x24 支持");
        inv.setCreateBy(7L);
        return inv;
    }

    private AiModelConfig modelConfig() {
        AiModelConfig cfg = new AiModelConfig();
        cfg.setProvider("openai");
        cfg.setEndpointUrl("https://api.example.com/v1");
        cfg.setModelName("gpt-4o-mini");
        return cfg;
    }

    private void stubHappyPath() {
        when(invitationMapper.selectById(1L)).thenReturn(invitation());
        when(modelConfigService.currentEnabled()).thenReturn(modelConfig());
        when(modelConfigService.decryptApiKey(any())).thenReturn("sk-test");
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.ok(CHECK_JSON, 120, 260, 900));
    }

    /** 硬红线断言：招标/应标表任何写方法零调用（应标表根本不在依赖面，结构性零写入）。 */
    private void assertNoWrites() {
        verify(invitationMapper, never()).insert(any(BidInvitation.class));
        verify(invitationMapper, never()).updateById(any(BidInvitation.class));
        verify(invitationMapper, never()).update(any(BidInvitation.class), any());
    }

    @Test
    @DisplayName("正常：逐条检查表 + 总评 + 审计三件套 aiRole=precheck（BR-AI-04 原文不落审计）")
    void happyPathReturnsCheckView() throws Exception {
        loginAs("RD_PM");
        stubHappyPath();

        var view = controller.aiCheck(1L, new BidCheckReq("SECRET_NOTE_MARKER 我方方案：7x24 响应")).getData();

        assertThat(view.invitationId()).isEqualTo(1L);
        assertThat(view.invitationTitle()).isEqualTo("核心网升级招标");
        assertThat(view.checks()).extracting(BidResponseCheckService.CheckRow::status)
            .containsExactly("MET", "PARTIAL", "MISSING");
        assertThat(view.checks()).extracting(BidResponseCheckService.CheckRow::requirement)
            .containsExactly("7x24 支持", "Q4 交付", "温测报告");
        assertThat(view.checks().get(0).evidence()).isEqualTo("方案提及 7x24 响应");
        assertThat(view.summary()).isEqualTo("主要缺口在温测报告");
        assertThat(view.model()).isEqualTo("gpt-4o-mini");
        assertThat(view.tokenPrompt()).isEqualTo(120);
        assertThat(view.completionTokens()).isEqualTo(260);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getAction()).isEqualTo("AI_BID_CHECK");
        assertThat(row.getEntityType()).isEqualTo("bid_invitation");
        assertThat(row.getEntityId()).isEqualTo(1L);
        JsonNode payload = JSON.readTree(row.getAfterData());
        assertThat(payload.path("aiAssisted").asBoolean()).isTrue();
        assertThat(payload.path("aiRole").asText()).isEqualTo("precheck");
        assertThat(payload.path("aiModel").asText()).isEqualTo("gpt-4o-mini");
        assertThat(payload.path("status").asText()).isEqualTo("ok");
        assertThat(row.getAfterData())
            .doesNotContain("SECRET_NOTE_MARKER")
            .doesNotContain("7x24"); // prompt/模型输出原文均不落审计（BR-AI-04）
        AuditEventData.requireJson(row.getAfterData(), "after_data");
        AuditEventData.requireAiTrail(row.getAfterData(), "after_data");
        assertNoWrites();
    }

    @Test
    @DisplayName("参数闸：responseNote 空/超限或招标单不存在 → 拒绝，AI 与审计零触达")
    void paramGateRejects() {
        loginAs("RD_PM");
        when(invitationMapper.selectById(1L)).thenReturn(invitation());
        for (String note : java.util.Arrays.asList(null, "   ", "x".repeat(30001))) {
            assertThatThrownBy(() -> controller.aiCheck(1L, new BidCheckReq(note)))
                .isInstanceOfSatisfying(IpdBusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        }
        when(invitationMapper.selectById(2L)).thenReturn(null);
        assertThatThrownBy(() -> controller.aiCheck(2L, new BidCheckReq("方案")))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verifyNoInteractions(aiGateway, modelConfigService, auditLogService);
        assertNoWrites();
    }

    @Test
    @DisplayName("AI 失败：TIMEOUT → 90001/500 并落 FAIL:TIMEOUT 审计，零写入")
    void aiFailureAuditsAndThrows() {
        loginAs("RD_PM");
        stubHappyPath();
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.fail("TIMEOUT", "gateway: timeout", 30_000));

        assertThatThrownBy(() -> controller.aiCheck(1L, new BidCheckReq("方案")))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR));
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        assertThat(captor.getValue().getAfterData()).contains("FAIL:TIMEOUT");
        assertNoWrites();
    }

    @Test
    @DisplayName("畸形输出：非 JSON → FAIL:PARSE；空 checks/非法状态/缺 summary 一律拒，不静默补表")
    void malformedModelOutputRejected() {
        loginAs("RD_PM");
        stubHappyPath();
        String[] badOutputs = {
            "依我看都覆盖了",
            "{\"checks\":[],\"summary\":\"s\"}",
            "{\"checks\":[{\"requirement\":\"x\",\"status\":\"GREEN\",\"evidence\":\"\"}],\"summary\":\"s\"}",
            "{\"checks\":[{\"requirement\":\"\",\"status\":\"MET\",\"evidence\":\"\"}],\"summary\":\"s\"}",
            "{\"checks\":[{\"requirement\":\"x\",\"status\":\"MET\",\"evidence\":\"\"}]}"
        };
        for (String bad : badOutputs) {
            stubHappyPath();
            when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
                .thenReturn(AiChatResult.ok(bad, 50, 20, 300));
            assertThatThrownBy(() -> controller.aiCheck(1L, new BidCheckReq("方案")))
                .isInstanceOfSatisfying(IpdBusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR));
        }
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, org.mockito.Mockito.times(badOutputs.length)).append(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row ->
            assertThat(row.getAfterData()).contains("FAIL:PARSE"));
        assertNoWrites();
    }

    @Test
    @DisplayName("反漂移：ai-completeness-check 注解码与 submitResponse 同源且目录已登记")
    void permissionCodeMirrorsSubmitResponse() throws Exception {
        SaCheckPermission mine = BidResponseCheckController.class
            .getMethod("aiCheck", Long.class, BidCheckReq.class)
            .getAnnotation(SaCheckPermission.class);
        SaCheckPermission submit = BidController.class
            .getMethod("submitResponse", BidResponse.class)
            .getAnnotation(SaCheckPermission.class);
        assertThat(mine).isNotNull();
        assertThat(mine.value()[0])
            .isEqualTo(submit.value()[0])
            .isEqualTo(IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE);
        assertThat(IpdRolePermissionCatalog.has("RD_PM", mine.value()[0])).isTrue();
        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", mine.value()[0])).isTrue();
    }
}
