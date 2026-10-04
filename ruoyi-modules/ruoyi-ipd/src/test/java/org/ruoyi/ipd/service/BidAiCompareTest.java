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
import org.ruoyi.ipd.controller.BidAiCompareController;
import org.ruoyi.ipd.controller.BidAiCompareController.BidCompareReq;
import org.ruoyi.ipd.controller.BidController;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.mapper.BidResponseMapper;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdPermissionException;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import java.util.List;

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
 * AI-P2-2 #3 遴选对比汇总切片回归（只读端点，四件套契约）：
 * ①权限矩阵——bid-select 同注解码 + requireLeaderOrAdmin，非遴选角色 403；
 * ②应标不足 2 份 → 400（PARAM_INVALID）；③正常 → 四维对照表 + 差异高亮；
 * ④全路径零业务表写入（insert/updateById/update 一律 never——confirmToken 人工遴选流零触碰）。
 * 附：审计三件套 aiRole=summarize 过 AI-P1-3 门禁、跨单探测拒读、模型失败/畸形输出不静默补表。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI-P2-2#3 遴选对比汇总：权限矩阵/参数闸/对照结构/只读断言")
class BidAiCompareTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL_JSON = "{\"dimensions\":["
        + "{\"dimension\":\"工期\",\"cells\":{\"11\":\"45日历天\",\"12\":\"60日历天\"},\"difference\":\"相差15天\"},"
        + "{\"dimension\":\"资源\",\"cells\":{\"11\":\"2人月\",\"12\":\"4人月\"},\"difference\":\"投入相差一倍\"},"
        + "{\"dimension\":\"风险承诺\",\"cells\":{\"11\":\"7x24响应\",\"12\":\"5x8响应\"},\"difference\":\"支持等级不同\"},"
        + "{\"dimension\":\"方案匹配度\",\"cells\":{\"11\":\"全覆盖\",\"12\":\"部分覆盖\"},\"difference\":\"覆盖度不同\"}],"
        + "\"differences\":[\"工期相差15天\",\"资源投入相差一倍\"]}";

    @Mock private BidInvitationMapper invitationMapper;
    @Mock private BidResponseMapper responseMapper;
    @Mock private IAiModelConfigService modelConfigService;
    @Mock private AiGateway aiGateway;
    @Mock private IAuditLogService auditLogService;
    @Mock private IpdAuthSession session;
    @Mock private IpdAuthService authService;

    private BidAiCompareService service;
    private BidAiCompareController controller;

    @BeforeEach
    void setUp() {
        service = new BidAiCompareService(invitationMapper, responseMapper,
            modelConfigService, aiGateway, auditLogService);
        controller = new BidAiCompareController(service, new IpdPermission(session, authService));
    }

    /** 会话身份构造（同 BidAdminAssignSecurityScenarioTest 惯例：角色只来自 currentPerson）。 */
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

    private BidResponse response(long id, Long invitationId, String note) {
        BidResponse r = new BidResponse();
        r.setId(id);
        r.setInvitationId(invitationId);
        r.setRdPmId(100L + id);
        r.setStatus("PENDING");
        r.setResponseNote(note);
        return r;
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
        when(responseMapper.selectByIds(any()))
            .thenReturn(List.of(response(11L, 1L, "方案A"), response(12L, 1L, "方案B")));
        when(modelConfigService.currentEnabled()).thenReturn(modelConfig());
        when(modelConfigService.decryptApiKey(any())).thenReturn("sk-test");
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.ok(MODEL_JSON, 120, 260, 900));
    }

    /** 硬红线断言：应标表/招标表（含 confirmToken 载体行）任何写方法零调用。 */
    private void assertNoWrites() {
        verify(responseMapper, never()).insert(any(BidResponse.class));
        verify(responseMapper, never()).updateById(any(BidResponse.class));
        verify(responseMapper, never()).update(any(BidResponse.class), any());
        verify(invitationMapper, never()).insert(any(BidInvitation.class));
        verify(invitationMapper, never()).updateById(any(BidInvitation.class));
        verify(invitationMapper, never()).update(any(BidInvitation.class), any());
    }

    @Test
    @DisplayName("权限矩阵：MARKET_PM/RD_PM 调 ai-compare → 403 且 mapper/AI/审计零触达")
    void nonSelectorRolesForbidden() {
        for (String role : List.of("MARKET_PM", "RD_PM")) {
            loginAs(role);
            assertThatThrownBy(() -> controller.aiCompare(1L, new BidCompareReq(List.of(11L, 12L))))
                .isInstanceOfSatisfying(IpdPermissionException.class, ex -> {
                    assertThat(ex.getHttpStatus()).isEqualTo(403);
                    assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.FORBIDDEN);
                });
        }
        verifyNoInteractions(invitationMapper, responseMapper, aiGateway,
            auditLogService, modelConfigService);
    }

    @Test
    @DisplayName("权限矩阵：GROUP_LEADER/SUPER_ADMIN（遴选资格角色）放行并真正产出对照表")
    void selectorRolesAllowed() {
        for (String role : List.of("GROUP_LEADER", "SUPER_ADMIN")) {
            loginAs(role);
            stubHappyPath();
            var view = controller.aiCompare(1L, new BidCompareReq(List.of(11L, 12L))).getData();
            assertThat(view.dimensions()).as("角色 " + role + " 应拿到四维表").hasSize(4);
        }
    }

    @Test
    @DisplayName("参数闸：应标不足 2 份（含 null/重复）或超 5 份 → 400，AI 与查库零触发")
    void fewerThanTwoResponsesRejected() {
        loginAs("GROUP_LEADER");
        List<BidCompareReq> bad = List.of(
            new BidCompareReq(null),
            new BidCompareReq(List.of(11L)),
            new BidCompareReq(List.of(11L, 11L)),
            new BidCompareReq(java.util.Arrays.asList(11L, null, 12L, 13L, 14L, 15L, 16L)));
        for (BidCompareReq req : bad) {
            assertThatThrownBy(() -> controller.aiCompare(1L, req))
                .isInstanceOfSatisfying(IpdBusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        }
        verifyNoInteractions(aiGateway, auditLogService, modelConfigService);
        assertNoWrites();
    }

    @Test
    @DisplayName("正常：四维对照表 + 差异高亮 + 审计三件套 aiRole=summarize（过 AI-P1-3 门禁）")
    void happyPathReturnsComparisonView() throws Exception {
        loginAs("GROUP_LEADER");
        stubHappyPath();
        var view = controller.aiCompare(1L, new BidCompareReq(List.of(11L, 12L))).getData();
        assertThat(view.invitationId()).isEqualTo(1L);
        assertThat(view.responseIds()).containsExactly("11", "12");
        assertThat(view.dimensions())
            .extracting(BidAiCompareService.DimensionRow::dimension)
            .containsExactly("工期", "资源", "风险承诺", "方案匹配度");
        assertThat(view.dimensions().get(0).cells())
            .containsEntry("11", "45日历天").containsEntry("12", "60日历天");
        assertThat(view.dimensions().get(0).difference()).isEqualTo("相差15天");
        assertThat(view.differences()).containsExactly("工期相差15天", "资源投入相差一倍");
        assertThat(view.model()).isEqualTo("gpt-4o-mini");
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        AuditLog row = captor.getValue();
        assertThat(row.getAction()).isEqualTo("AI_BID_COMPARE");
        assertThat(row.getEntityType()).isEqualTo("bid_invitation");
        assertThat(row.getEntityId()).isEqualTo(1L);
        JsonNode payload = JSON.readTree(row.getAfterData());
        assertThat(payload.path("aiAssisted").asBoolean()).isTrue();
        assertThat(payload.path("aiRole").asText()).isEqualTo("summarize");
        assertThat(payload.path("aiModel").asText()).isEqualTo("gpt-4o-mini");
        assertThat(payload.path("status").asText()).isEqualTo("ok");
        assertThat(payload.path("responseCount").asInt()).isEqualTo(2);
        assertThat(row.getAfterData()).doesNotContain("方案A").doesNotContain("核心网");
        AuditEventData.requireJson(row.getAfterData(), "after_data");
        AuditEventData.requireAiTrail(row.getAfterData(), "after_data");
        assertNoWrites();
    }

    @Test
    @DisplayName("跨单探测：应标不属于该招标单 → 50001/404，AI 零调用且零写入")
    void foreignResponseRejected() {
        loginAs("GROUP_LEADER");
        when(invitationMapper.selectById(1L)).thenReturn(invitation());
        when(responseMapper.selectByIds(any()))
            .thenReturn(List.of(response(11L, 1L, "方案A"), response(12L, 999L, "别单应标")));
        assertThatThrownBy(() -> controller.aiCompare(1L, new BidCompareReq(List.of(11L, 12L))))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verifyNoInteractions(aiGateway, modelConfigService);
        assertNoWrites();
    }

    @Test
    @DisplayName("AI 失败：TIMEOUT → 90001/500 并落 FAIL:TIMEOUT 审计，零写入")
    void aiFailureAuditsAndThrows() {
        loginAs("GROUP_LEADER");
        stubHappyPath();
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.fail("TIMEOUT", "gateway: timeout", 30_000));
        assertThatThrownBy(() -> controller.aiCompare(1L, new BidCompareReq(List.of(11L, 12L))))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR));
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(captor.capture());
        assertThat(captor.getValue().getAfterData()).contains("FAIL:TIMEOUT");
        assertNoWrites();
    }

    @Test
    @DisplayName("畸形输出：非 JSON / 缺维度 → INTERNAL_ERROR + FAIL:PARSE 审计，不静默补半表")
    void malformedModelOutputRejected() {
        loginAs("GROUP_LEADER");
        stubHappyPath();
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.ok("依我看选方案A更稳妥", 50, 20, 300));
        assertThatThrownBy(() -> controller.aiCompare(1L, new BidCompareReq(List.of(11L, 12L))))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR));
        stubHappyPath();
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.ok("{\"dimensions\":[{\"dimension\":\"工期\","
                + "\"cells\":{\"11\":\"45天\",\"12\":\"60天\"},\"difference\":\"差15天\"}]}", 50, 20, 300));
        assertThatThrownBy(() -> controller.aiCompare(1L, new BidCompareReq(List.of(11L, 12L))))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getMessage()).contains("缺少维度"));
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, org.mockito.Mockito.times(2)).append(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(row ->
            assertThat(row.getAfterData()).contains("FAIL:PARSE"));
        assertNoWrites();
    }

    @Test
    @DisplayName("反漂移：ai-compare 注解码与 bid-select /select 同源且目录已登记（组长/超管持有）")
    void permissionCodeMirrorsBidSelect() throws Exception {
        SaCheckPermission mine = BidAiCompareController.class
            .getMethod("aiCompare", Long.class, BidCompareReq.class)
            .getAnnotation(SaCheckPermission.class);
        SaCheckPermission select = BidController.class
            .getMethod("selectResponse", Long.class, Long.class, String.class)
            .getAnnotation(SaCheckPermission.class);
        assertThat(mine).isNotNull();
        assertThat(mine.value()[0])
            .isEqualTo(select.value()[0])
            .isEqualTo(IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE);
        assertThat(IpdRolePermissionCatalog.has("GROUP_LEADER", mine.value()[0])).isTrue();
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", mine.value()[0])).isTrue();
    }
}
