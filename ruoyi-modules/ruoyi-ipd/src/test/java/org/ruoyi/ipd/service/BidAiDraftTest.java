package org.ruoyi.ipd.service;

import cn.dev33.satoken.annotation.SaCheckPermission;
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
import org.ruoyi.ipd.controller.BidAiDraftController;
import org.ruoyi.ipd.controller.BidAiDraftController.BidDraftReq;
import org.ruoyi.ipd.controller.BidController;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AI-P2-2 #1 招标书起草切片回归（文档生成类——委托 IAiGenerationService.generate
 * 既有治理链，不新建 AI 通道）：
 * ①参数闸（projectId/title/brief 必填与上限）→ PARAM_INVALID 且 generate 零触发；
 * ②正常 → 草稿 v1 登记视图 + 委托载荷锁（docType=BID_INVITATION_DRAFT、promptType=null
 * 裸 prompt、prompt 带章节/占位/起草说明红线）；
 * ③generate 失败异常直通不吞不降级（不出半成品草稿）；
 * ④反漂移：注解码与 createInvitation 同源且目录已登记。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AI-P2-2#1 招标书起草：参数闸/委托载荷/失败直通/权限同源")
class BidAiDraftTest {

    @Mock private IAiGenerationService aiGenerationService;
    @Mock private IpdAuthSession session;
    @Mock private IpdAuthService authService;

    private BidAiDraftService service;
    private BidAiDraftController controller;

    @BeforeEach
    void setUp() {
        service = new BidAiDraftService(aiGenerationService)
            .withClock(Clock.fixed(Instant.parse("2026-09-27T08:00:00Z"), ZoneId.of("UTC")));
        controller = new BidAiDraftController(service, new IpdPermission(session, authService));
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

    private void stubGenerate() {
        when(aiGenerationService.generate(any(), any())).thenReturn(AiDocument.builder()
            .id(900L).projectId(1L).docType(BidAiDraftService.DOC_TYPE)
            .title("Q4 核心网升级招标").content("# 招标书正文…").model("gpt-4o-mini")
            .tokenPrompt(120).tokenCompletion(340).status("GENERATED")
            .build());
    }

    @Test
    @DisplayName("正常：委托 generate 出草稿 v1，载荷锁 docType/裸 prompt/章节与占位红线")
    void happyPathDelegatesToGenerate() {
        loginAs("MARKET_PM");
        stubGenerate();

        var view = controller.aiDraft(new BidDraftReq(1L, "Q4 核心网升级招标",
            "SECRET_BRIEF_MARKER 需要升级核心网，Q4 上线")).getData();

        assertThat(view.docId()).isEqualTo(900L);
        assertThat(view.title()).isEqualTo("Q4 核心网升级招标");
        assertThat(view.model()).isEqualTo("gpt-4o-mini");
        assertThat(view.tokenPrompt()).isEqualTo(120);
        assertThat(view.tokenCompletion()).isEqualTo(340);
        assertThat(view.status()).isEqualTo("GENERATED");
        assertThat(view.latencyMs()).isZero(); // 固定时钟
        ArgumentCaptor<AiGenerateReq> cap = ArgumentCaptor.forClass(AiGenerateReq.class);
        verify(aiGenerationService, times(1)).generate(any(), cap.capture());
        AiGenerateReq req = cap.getValue();
        assertThat(req.projectId()).isEqualTo(1L);
        assertThat(req.docType()).isEqualTo("BID_INVITATION_DRAFT");
        assertThat(req.title()).isEqualTo("Q4 核心网升级招标");
        assertThat(req.promptType()).as("裸 prompt 兼容口径：不走 PromptTemplates").isNull();
        assertThat(req.prompt())
            .contains("SECRET_BRIEF_MARKER")
            .contains("项目背景").contains("招标目标").contains("交付要求")
            .contains("工期安排").contains("验收标准").contains("应标须知")
            .contains("起草说明")
            .contains("禁止编造具体数字承诺")
            .contains("待 PM 确认");
    }

    @Test
    @DisplayName("参数闸：projectId/title/brief 缺失或超限 → PARAM_INVALID，generate 零触发")
    void paramGateRejects() {
        loginAs("MARKET_PM");
        String overTitle = "t".repeat(201);
        String overBrief = "b".repeat(25001);
        for (BidDraftReq req : java.util.List.of(
            new BidDraftReq(null, "t", "b"),
            new BidDraftReq(1L, null, "b"),
            new BidDraftReq(1L, "  ", "b"),
            new BidDraftReq(1L, overTitle, "b"),
            new BidDraftReq(1L, "t", null),
            new BidDraftReq(1L, "t", "   "),
            new BidDraftReq(1L, "t", overBrief))) {
            assertThatThrownBy(() -> controller.aiDraft(req))
                .isInstanceOfSatisfying(IpdBusinessException.class,
                    ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.PARAM_INVALID));
        }
        verifyNoInteractions(aiGenerationService);
    }

    @Test
    @DisplayName("generate 失败：异常直通不吞不降级（不出半成品草稿）")
    void generateFailurePropagates() {
        loginAs("MARKET_PM");
        when(aiGenerationService.generate(any(), any()))
            .thenThrow(new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR, "AI 生成失败：TIMEOUT"));

        assertThatThrownBy(() -> controller.aiDraft(new BidDraftReq(1L, "t", "b")))
            .isInstanceOfSatisfying(IpdBusinessException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ApiV1ErrorCode.INTERNAL_ERROR));
    }

    @Test
    @DisplayName("反漂移：ai-draft 注解码与 createInvitation 同源且目录已登记（MARKET_PM/RD_PM 持有）")
    void permissionCodeMirrorsCreateInvitation() throws Exception {
        SaCheckPermission mine = BidAiDraftController.class
            .getMethod("aiDraft", BidDraftReq.class)
            .getAnnotation(SaCheckPermission.class);
        SaCheckPermission create = BidController.class
            .getMethod("createInvitation", BidInvitation.class)
            .getAnnotation(SaCheckPermission.class);
        assertThat(mine).isNotNull();
        assertThat(mine.value()[0])
            .isEqualTo(create.value()[0])
            .isEqualTo(IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE);
        assertThat(IpdRolePermissionCatalog.has("MARKET_PM", mine.value()[0])).isTrue();
        assertThat(IpdRolePermissionCatalog.has("RD_PM", mine.value()[0])).isTrue();
    }
}
