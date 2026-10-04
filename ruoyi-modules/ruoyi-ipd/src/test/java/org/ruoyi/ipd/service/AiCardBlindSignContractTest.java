package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.mapper.GateArbitrationMapper;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.HandoverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementChangeMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R232-P2-05（batch 8）：盲签隔离契约测试扩展——卡片层不得绕过 {@code GateReviewService.rowView} 遮蔽。
 *
 * <p>契约源（以计划原文为准）：{@code docs/ipd-系统说明/CopilotKit三项能力落地-全局执行计划-20260927.md}
 * <ul>
 *   <li>L101 P2-05：action=扩展既有契约测试——卡片 data/sourceRefs 查询走 ✓ GateReviewService.rowView
 *       （L263/L269）；arbitrate/finalRuling 卡无 AI 倾向渲染；success=reveal 前对方判定不进任何 card
 *       data/sourceRefs；fail=绕过遮蔽=🔴 盲签红线级停线升级；</li>
 *   <li>L118 BR-AI-05/盲签：卡片 sourceRefs 走 rowView 同源；reveal 前对方判定不进 data/sourceRefs/上下文。</li>
 * </ul>
 *
 * <p>遮蔽通道（行号证据，2026-09-27 工作树）：{@code GateReviewService.view()} L239-241
 * （revealed = terminal || SUPER_ADMIN）、L263 {@code rowView(mine, true)}（己方恒揭示）、
 * L269 {@code rowView(other, revealed)}、L339-348 {@code rowView}（revealed=false 时 decision/opinion
 * 不出现）。卡片层同源口径：己方行恒可见、对方行 reveal 前 decision/opinion **字段为空**（键在值空，
 * Catalog itemFields 键集恒等），reveal（终态/超管）后可见。
 *
 * <p>三向断言面（交付报告口径）：
 * <ul>
 *   <li>后端查询轴：card data/sourceRefs 遮蔽 + sourceRefs 纯行 id 引用（本类 case1/2/3/6）；</li>
 *   <li>AI 上下文轴：prompt 不含对方判定（case4/5，红线 L118「上下文」条款）；</li>
 *   <li>arbitrate/finalRuling 无 AI 倾向轴：仲裁/终审决策通道拒 AI 倾向值（case7）+ 卡数据面
 *       AI 倾向字段丢弃留痕（case8）+ FILL_FIELD_WHITELIST 6 字段对账基准（case9）。</li>
 * </ul>
 *
 * <p>mock 合法性（mock合法性与已知死路登记三硬规则）：mock 行按 ipd_dev DDL NOT NULL 构造
 * （gate_reviews: id/gate_id/reviewer_type/reviewer_id/due_at/round），并遵守业务写入规则组合——
 * 按 {@code GateReviewService.advance()} L279-290 真实推进语义，REJECT 行/双签齐即终态，
 * 故「G1 双签在途」fixture 恒为「仅单方已签（APPROVE）+ 另一方待签行（decision=null，列可空）」；
 * 终态揭示正向对照锚定既有 view() 层用例（P252AcceptanceTest sign_bothApprove_gateApprovedAndRevealed），
 * 卡片层当前无 Gate 状态输入缝，终态揭示待生产引入 reveal 语义后补锚。
 *
 * <p>断言自证能红（计划 L101 validation）：case10 用同一断言助手捕获故意泄漏投影；
 * case1/case4 为对当前实现的活体契约断言（红=违例实证，按计划 fail path 停线升级）。
 */
@Tag("dev")
@DisplayName("R232-P2-05：盲签隔离契约——卡片层 rowView 同源遮蔽 + arbitrate/finalRuling 无 AI 倾向")
class AiCardBlindSignContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 对方判定/意见泄漏样例（唯一源，各 case 引用防漂移）。 */
    private static final String OTHER_OPINION = "对方已签意见-盲签红线验证";
    private static final String MY_OPINION = "己方签署意见-盲签验证";
    private static final String AI_TENDENCY_VALUE = "AI倾向-建议通过-泄露样例";
    private static final String AI_TENDENCY_SCORE = "倾向评分值0.93x";
    private static final String AI_RECOMMEND_REASON = "推荐理由-泄露样例";

    private static final long GATE_ID = 41L;
    private static final long PROJECT_ID = 7L;

    private static final IpdActor MARKET = new IpdActor(301L, "陈市场", "MARKET_PM", PROJECT_ID);
    private static final IpdActor SUPER = new IpdActor(303L, "系统管理员", "SUPER_ADMIN", null);
    private static final IpdActor LEADER = new IpdActor(304L, "王组长", "GROUP_LEADER", PROJECT_ID);

    private AiModelConfigService modelConfigService;
    private WorkbenchService workbenchService;
    private AiGateway aiGateway;
    private IAuditLogService auditLogService;
    private ProjectMapper projectMapper;
    private ProjectMemberMapper projectMemberMapper;
    private GateReviewMapper gateReviewMapper;
    private GateElementResultMapper gateElementResultMapper;
    private ISystemConfigService systemConfigService;
    private RequirementMapper requirementMapper;
    private RequirementChangeMapper requirementChangeMapper;
    private HandoverMapper handoverMapper;
    private AiSuggestionService service;

    @BeforeAll
    static void initMybatisMeta() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "P205-gr"), GateReview.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "P205-gate"), Gate.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "P205-pm"), ProjectMember.class);
    }

    @BeforeEach
    void setUp() {
        modelConfigService = mock(AiModelConfigService.class);
        workbenchService = mock(WorkbenchService.class);
        aiGateway = mock(AiGateway.class);
        auditLogService = mock(IAuditLogService.class);
        projectMapper = mock(ProjectMapper.class);
        projectMemberMapper = mock(ProjectMemberMapper.class);
        gateReviewMapper = mock(GateReviewMapper.class);
        gateElementResultMapper = mock(GateElementResultMapper.class);
        systemConfigService = mock(ISystemConfigService.class);
        requirementMapper = mock(RequirementMapper.class);
        requirementChangeMapper = mock(RequirementChangeMapper.class);
        handoverMapper = mock(HandoverMapper.class);
        service = new AiSuggestionService(modelConfigService, workbenchService, aiGateway,
            auditLogService, projectMapper, projectMemberMapper, gateReviewMapper, gateElementResultMapper,
            systemConfigService, requirementMapper, requirementChangeMapper, handoverMapper)
            .withClock(Clock.fixed(Instant.parse("2026-09-27T08:00:00Z"), ZoneId.of("UTC")));
        when(systemConfigService.getValue(eq(AiSuggestionService.CARD_CATALOG_KEY), any()))
            .thenReturn(AiSuggestionCardTest.CATALOG_JSON);
    }

    // ---- mock 合法性构建器（ipd_dev DDL NOT NULL + advance() L279-290 真实组合） ----

    /** gate_reviews：id/gate_id/reviewer_type/reviewer_id/due_at/round NOT NULL；decision 列可空（待签行）。 */
    private GateReview review(long id, long gateId, String reviewerType, String decision, String opinion) {
        GateReview r = new GateReview();
        r.setId(id);
        r.setGateId(gateId);
        r.setProjectId(PROJECT_ID);
        r.setReviewerType(reviewerType);
        r.setReviewerId("MARKET_PM".equals(reviewerType) ? MARKET.id() : 302L);
        r.setDueAt(new Date());
        r.setRound(1);
        r.setGateCode("G1");
        r.setDecision(decision);
        r.setOpinion(opinion);
        return r;
    }

    private void enableModelAndGateway(String llmContent) {
        AiModelConfig config = new AiModelConfig();
        config.setProvider("openai");
        config.setEndpointUrl("http://mock/v1");
        config.setModelName("mock-mini");
        when(modelConfigService.currentEnabled()).thenReturn(config);
        when(modelConfigService.decryptApiKey(config)).thenReturn("sk-x");
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), anyInt(), any()))
            .thenReturn(AiChatResult.ok(llmContent, 120, 45, 1000L));
    }

    private void mockProject(long id) {
        Project p = new Project();
        p.setId(id);
        p.setCode("PRJ-" + id);
        p.setName("示例项目");
        p.setCurrentStage("PLAN");
        p.setProductId(5L);
        when(projectMapper.selectById(id)).thenReturn(p);
        when(projectMemberMapper.selectCount(any())).thenReturn(1L);
    }

    /**
     * G1 双签在途 fixture（真实组合）：仅对方（RD_PM）已签 APPROVE + 己方（MARKET_PM）待签行。
     * 按 advance() L279-290：REJECT 行/双签齐即终态，故在途态不可能含 REJECT 或双已签。
     */
    private void stubInFlightOnlyOtherSideSigned() {
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            review(91L, GATE_ID, "RD_PM", "APPROVE", OTHER_OPINION),
            review(92L, GATE_ID, "MARKET_PM", null, null)));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of());
    }

    /** 对称 fixture：仅己方（MARKET_PM）已签 + 对方待签行。 */
    private void stubInFlightOnlyMySideSigned() {
        when(gateReviewMapper.selectList(any())).thenReturn(List.of(
            review(91L, GATE_ID, "MARKET_PM", "APPROVE", MY_OPINION),
            review(92L, GATE_ID, "RD_PM", null, null)));
        when(gateElementResultMapper.selectList(any())).thenReturn(List.of());
    }

    // ---- 盲签遮蔽契约断言助手（「卡片层不得绕过遮蔽」行为断言；case10 自证能红） ----

    /**
     * rowView 同源遮蔽口径：viewerRole 己方行恒可见（GateReviewService L263 rowView(mine,true)）；
     * 对方行 reveal 前 decision/opinion 必须为空（L269 rowView(other,revealed=false) + L339-348）。
     * 返回违例清单（空=契约成立）。
     */
    static List<String> blindSignViolations(List<Map<String, Object>> reviews, String viewerRole) {
        List<String> violations = new ArrayList<>();
        for (Map<String, Object> row : reviews) {
            if (viewerRole.equals(row.get("reviewerType"))) {
                continue;
            }
            if (row.get("decision") != null) {
                violations.add("对方 decision 未遮蔽：" + row.get("decision"));
            }
            if (row.get("opinion") != null) {
                violations.add("对方 opinion 未遮蔽：" + row.get("opinion"));
            }
        }
        return violations;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> reviewRows(AiSuggestResp.Card card) {
        return (List<Map<String, Object>>) card.data().get("reviews");
    }

    private static Map<String, Object> rowOf(List<Map<String, Object>> rows, String reviewerType) {
        return rows.stream().filter(r -> reviewerType.equals(r.get("reviewerType"))).findFirst()
            .orElseThrow(() -> new AssertionError("缺 reviewerType=" + reviewerType + " 行"));
    }

    private static Set<String> declaredFieldNames(JsonNode def) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode f : def.get("fields")) {
            names.add(f.path("name").asText());
        }
        return names;
    }

    private static JsonNode cardDefFromCatalog(String scene) {
        try {
            JsonNode cards = JSON.readTree(AiSuggestionCardTest.CATALOG_JSON).get("cards");
            for (JsonNode c : cards) {
                if (scene.equals(c.path("scene").asText())) {
                    return c;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("fixture 解析失败", e);
        }
        throw new IllegalStateException("fixture 缺 scene: " + scene);
    }

    @Test
    void sameRoleDifferentSignerStaysBlindInActualCardProjection() {
        GateReview row = review(91L, GATE_ID, "MARKET_PM", "APPROVE", OTHER_OPINION);
        row.setReviewerId(999L);
        Map<String,Object> facts = new LinkedHashMap<>();
        AiSuggestionService.projectGateFacts(Set.of("reviews"), List.of(row), List.of(), facts, MARKET, false);
        Map<?,?> projected = (Map<?,?>) ((List<?>) facts.get("reviews")).get(0);
        assertThat(projected.get("decision")).isNull();
        assertThat(projected.get("opinion")).isNull();
        assertThat(GateReviewService.isRowRevealed(row, MARKET, true)).isTrue();
    }

    @Test
    void ownSignerIdentityRemainsVisibleAcrossRoleChanges() {
        GateReview row = review(91L, GATE_ID, "RD_PM", "APPROVE", MY_OPINION);
        row.setReviewerId(MARKET.id());
        assertThat(GateReviewService.isRowRevealed(row, MARKET, false)).isTrue();
        assertThat(GateReviewService.isRowRevealed(row, null, false)).isFalse();
    }

    // ---- ① 卡片 data 遮蔽契约（后端查询轴，rowView 同源） ----

    @Test
    @DisplayName("①【契约】G1 在途·非超管：card data 对方判定/意见字段为空（reveal 前不进 data）")
    void otherSideRulingMaskedBeforeRevealInCardData() {
        enableModelAndGateway("## 结论草稿");
        mockProject(PROJECT_ID);
        stubInFlightOnlyOtherSideSigned();

        AiSuggestResp resp = service.suggest(MARKET, new AiSuggestReq("gate.conclusion-draft", null, GATE_ID, null));

        assertThat(resp.card()).as("出卡成功（降级会掩盖遮蔽断言，须显式断言）").isNotNull();
        List<Map<String, Object>> reviews = reviewRows(resp.card());
        assertThat(blindSignViolations(reviews, "MARKET_PM"))
            .as("计划 P2-05（L101）+ BR-AI-05（L118）：reveal 前对方判定不进 card data；"
                + "遮蔽口径=GateReviewService.rowView L269/L339-348（revealed=false 时 decision/opinion 不出现）；"
                + "若红=AiSuggestionService.projectGateFacts L549-559 直查未遮蔽通道原样投影（绕过 rowView）")
            .isEmpty();
        Map<String, Object> otherRow = rowOf(reviews, "RD_PM");
        assertThat(otherRow.get("decision")).as("对方判定字段为空（rowView 遮蔽同源）").isNull();
        assertThat(otherRow.get("opinion")).as("对方意见字段为空（rowView 遮蔽同源）").isNull();
        assertThat(otherRow.get("reviewerType")).as("遮蔽=值空非删行（Catalog itemFields 键集恒等）").isEqualTo("RD_PM");
        assertThat(otherRow.get("round")).isEqualTo(1);
        assertThat(resp.card().data().toString())
            .as("data 全文兜底：对方判定/意见原文不得出现")
            .doesNotContain(OTHER_OPINION, "APPROVE");
    }

    @Test
    @DisplayName("①【豁免 pin】G1 在途·己方已签：己方判定/意见恒可见（rowView(mine,true) L263 同源）")
    void myOwnRulingStaysVisibleInCardData() {
        enableModelAndGateway("## 结论草稿");
        mockProject(PROJECT_ID);
        stubInFlightOnlyMySideSigned();

        AiSuggestResp resp = service.suggest(MARKET, new AiSuggestReq("gate.conclusion-draft", null, GATE_ID, null));

        assertThat(resp.card()).isNotNull();
        Map<String, Object> myRow = rowOf(reviewRows(resp.card()), "MARKET_PM");
        assertThat(myRow.get("decision")).as("己方判定对己恒可见").isEqualTo("APPROVE");
        assertThat(myRow.get("opinion")).as("己方意见对己恒可见").isEqualTo(MY_OPINION);
    }

    @Test
    @DisplayName("①【豁免 pin】G1 在途·超管：全揭示（revealed=terminal||SUPER_ADMIN L240-241，对齐 P252 既有语义）")
    void superAdminInFlightSeesBothSidesInCardData() {
        enableModelAndGateway("## 结论草稿");
        mockProject(PROJECT_ID);
        stubInFlightOnlyOtherSideSigned();

        AiSuggestResp resp = service.suggest(SUPER, new AiSuggestReq("gate.conclusion-draft", null, GATE_ID, null));

        assertThat(resp.card()).isNotNull();
        Map<String, Object> otherRow = rowOf(reviewRows(resp.card()), "RD_PM");
        assertThat(otherRow.get("decision")).as("超管在途全揭示").isEqualTo("APPROVE");
        assertThat(otherRow.get("opinion")).isEqualTo(OTHER_OPINION);
    }

    // ---- ① 上下文轴（红线 L118：reveal 前对方判定不进上下文） ----

    @Test
    @DisplayName("①【契约】G1 在途·非超管：AI prompt 上下文不含对方判定/意见（renderContext 不得拼入）")
    void promptContextMasksOtherSideRulingBeforeReveal() {
        enableModelAndGateway("## 结论草稿");
        mockProject(PROJECT_ID);
        stubInFlightOnlyOtherSideSigned();

        service.suggest(MARKET, new AiSuggestReq("gate.conclusion-draft", null, GATE_ID, null));

        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        verify(aiGateway).chat(any(AiTestConfig.class), promptCap.capture(), anyInt(), any());
        String prompt = promptCap.getValue();
        assertThat(prompt)
            .as("红线 L118：reveal 前对方判定不进上下文；"
                + "若红=AiSuggestionService.renderContext L785-786 把全行「决定/意见」拼进 prompt（绕过 rowView）")
            .doesNotContain(OTHER_OPINION, "决定=APPROVE");
    }

    @Test
    @DisplayName("①【豁免 pin】G1 在途·己方已签：己方判定可进上下文（红线只禁对方）")
    void promptContextKeepsMyOwnRuling() {
        enableModelAndGateway("## 结论草稿");
        mockProject(PROJECT_ID);
        stubInFlightOnlyMySideSigned();

        service.suggest(MARKET, new AiSuggestReq("gate.conclusion-draft", null, GATE_ID, null));

        ArgumentCaptor<String> promptCap = ArgumentCaptor.forClass(String.class);
        verify(aiGateway).chat(any(AiTestConfig.class), promptCap.capture(), anyInt(), any());
        assertThat(promptCap.getValue())
            .as("己方判定/意见进上下文合法（红线 L118 只禁对方判定）")
            .contains(MY_OPINION, "决定=APPROVE");
    }

    // ---- ① sourceRefs 轴（BR-AI-05：sourceRefs 走 rowView 同源，判定内容零入口） ----

    @Test
    @DisplayName("①【契约】sourceRefs 只含行 id 引用（gateId/reviewIds/elementResultIds），判定内容零入口")
    void sourceRefsCarryOnlyRowIdReferencesNeverRulings() {
        enableModelAndGateway("## 结论草稿");
        mockProject(PROJECT_ID);
        stubInFlightOnlyOtherSideSigned();

        AiSuggestResp resp = service.suggest(MARKET, new AiSuggestReq("gate.conclusion-draft", null, GATE_ID, null));

        assertThat(resp.card()).isNotNull();
        Map<String, Object> refs = resp.card().sourceRefs();
        assertThat(refs.keySet()).containsExactlyInAnyOrder("gateId", "reviewIds", "elementResultIds");
        assertThat(refs.get("gateId")).isInstanceOf(Number.class);
        assertThat(refs.get("reviewIds")).isInstanceOf(List.class);
        assertThat(refs.get("elementResultIds")).isInstanceOf(List.class);
        assertThat(refs.toString())
            .as("sourceRefs 不得携带判定/意见内容（BR-AI-05 L118）")
            .doesNotContain("APPROVE", OTHER_OPINION, "decision", "opinion");
    }

    // ---- ② arbitrate/finalRuling 无 AI 倾向（决策通道 + 卡数据面） ----

    @Test
    @DisplayName("②【契约】arbitrate/finalRuling 决策通道拒 AI 倾向值（decision 仅 APPROVE|REJECT 人判白名单）")
    void arbitrateAndFinalRulingRejectAiTendencyDecisionValues() {
        GateMapper gateMapper = mock(GateMapper.class);
        GateReviewMapper reviewMapper = mock(GateReviewMapper.class);
        GateReviewService gateService = new GateReviewService(gateMapper, reviewMapper,
            mock(ProjectMemberMapper.class), mock(PersonMapper.class),
            mock(GateArbitrationMapper.class), mock(GateReviewObserverMapper.class),
            mock(ISystemConfigService.class), mock(IAuditLogService.class), mock(NotificationService.class));
        Gate gate = new Gate();
        gate.setId(502L);
        gate.setProjectId(11L);
        gate.setGateCode("G1");
        gate.setStatus("REJECTED"); // mock 合法性：REJECT 已落即 REJECTED 终态（advance L279-284）
        gate.setCurrentRound(1);
        when(gateMapper.selectById(502L)).thenReturn(gate);
        when(reviewMapper.selectList(any())).thenReturn(List.of( // 双 PM 冲突（hasPmConflict L851-856）
            review(71L, 502L, "MARKET_PM", "APPROVE", "市场侧同意"),
            review(72L, 502L, "RD_PM", "REJECT", "研发侧否决")));

        assertThatThrownBy(() -> gateService.arbitrate(502L, AI_TENDENCY_VALUE, AI_RECOMMEND_REASON, LEADER))
            .as("仲裁意见通道：AI 倾向值必须被拦截（GateReviewService L590-591）")
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("仅允许 APPROVE|REJECT");
        assertThatThrownBy(() -> gateService.finalRuling(502L, AI_TENDENCY_SCORE, AI_RECOMMEND_REASON, SUPER))
            .as("终裁意见通道：AI 倾向值必须被拦截（GateReviewService L631-632）")
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("仅允许 APPROVE|REJECT");
    }

    @Test
    @DisplayName("②【契约】arbitrate/finalRuling 卡数据面：AI 倾向字段（aiSuggestion/倾向评分/推荐理由类）丢弃 + SCHEMA_DROP 留痕")
    void aiTendencyFieldsDroppedFromGateCardsWithAuditTrail() {
        JsonNode def = cardDefFromCatalog("gate.conclusion-draft");
        Set<String> wanted = declaredFieldNames(def);
        List<GateReview> rows = List.of(review(91L, GATE_ID, "MARKET_PM", "APPROVE", "材料齐备"));
        Map<String, Object> facts = new LinkedHashMap<>();
        AiSuggestionService.projectGateFacts(wanted, rows, List.<GateElementResult>of(), facts);
        // 污染注入：AI 倾向类字段（schema 外）——顶层 + reviews 子字段 + sourceRefs 键
        facts.put("aiSuggestion", AI_TENDENCY_VALUE);
        facts.put("tendencyScore", AI_TENDENCY_SCORE);
        facts.put("recommendReason", AI_RECOMMEND_REASON);
        facts.put("倾向评分", AI_TENDENCY_SCORE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> injected = (List<Map<String, Object>>) facts.get("reviews");
        injected.get(0).put("aiConfidence", AI_TENDENCY_SCORE);
        Map<String, Object> refs = new LinkedHashMap<>();
        refs.put("gateId", GATE_ID);
        refs.put("reviewIds", List.of(91L));
        refs.put("elementResultIds", List.of());
        refs.put("suggestion", AI_TENDENCY_VALUE);
        when(gateReviewMapper.selectList(any())).thenReturn(rows); // R3 回读两跳同投影

        AiSuggestResp.Card card = service.buildCardChecked(SUPER,
            new AiSuggestReq("gate.conclusion-draft", PROJECT_ID, GATE_ID, "用户素材-盲签"),
            AiSuggestionCardTest.CATALOG_JSON, facts, refs, "mock-mini");

        assertThat(card).as("倾向字段被丢弃不构成拒卡（R2 丢弃≠R3 对账失败）").isNotNull();
        assertThat(card.data().keySet())
            .as("data 键集与 Catalog 恒等，AI 倾向字段零残留")
            .containsExactlyInAnyOrderElementsOf(wanted);
        assertThat(reviewRows(card).get(0).keySet())
            .as("reviews 子字段与 itemFields 恒等（aiConfidence 被丢弃）")
            .containsExactlyInAnyOrder("reviewerType", "decision", "opinion", "round");
        assertThat(card.data().toString())
            .as("AI 倾向值不出现于返回视图（arbitrate/finalRuling 场景卡无 AI 倾向）")
            .doesNotContain(AI_TENDENCY_VALUE, AI_TENDENCY_SCORE, AI_RECOMMEND_REASON);
        // ③ 审计留痕：SCHEMA_DROP 逐路径留痕 + 三件套口径（aiRole=suggestion、promptLen-only）
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        String after = cap.getValue().getAfterData();
        assertThat(after).contains("SCHEMA_DROP",
            "aiSuggestion", "tendencyScore", "recommendReason", "倾向评分",
            "reviews.aiConfidence", "sourceRefs.suggestion");
        assertThat(after).contains("\"aiRole\":\"suggestion\"");
        assertThat(after)
            .as("留痕只记字段路径与 promptLen，倾向值原文不进审计（BR-AI-04）")
            .doesNotContain(AI_TENDENCY_VALUE, AI_RECOMMEND_REASON);
    }

    // ---- 对账基准：FILL_FIELD_WHITELIST 6 字段口径（AiCopilotService L285-287 唯一源） ----

    @Test
    @DisplayName("③【对账基准】FILL_FIELD_WHITELIST 6 字段口径成立，且填表通道不带判定/倾向字段")
    void fillWhitelistSixFieldBaselineHoldsAndInterceptsRulingFields() {
        assertThat(AiCopilotService.FILL_FIELD_WHITELIST.get("stage-action-fields"))
            .as("对账基准：AiCopilotService.java L285-287 唯一源，6 字段口径")
            .containsExactlyInAnyOrder("actualDoneAt", "farValue", "frrValue", "certNo", "certPassedAt", "algoType");
        Set<String> rulingNames = Set.of("decision", "opinion", "aiSuggestion", "tendencyScore", "recommendReason");
        for (Map.Entry<String, Set<String>> e : AiCopilotService.FILL_FIELD_WHITELIST.entrySet()) {
            assertThat(e.getValue())
                .as("填表白名单不得含判定/倾向字段（盲签红线）：" + e.getKey())
                .doesNotContainAnyElementsOf(rulingNames);
        }
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("certNo", "CN-1");
        raw.put("decision", "APPROVE");
        raw.put("aiSuggestion", AI_TENDENCY_VALUE);
        raw.put("opinion", OTHER_OPINION);
        assertThat(AiCopilotService.filterFillFields("stage-action-fields", raw))
            .as("直查通道拦截：白名单外判定/倾向字段被丢弃，字段为空")
            .containsOnlyKeys("certNo")
            .containsEntry("certNo", "CN-1");
    }

    // ---- 断言自证能红（计划 L101 validation） ----

    @Test
    @DisplayName("断言自证能红：同一遮蔽断言捕获故意泄漏投影（自证能红），遮蔽投影通过")
    void blindSignAssertionSelfProofFlagsLeakyProjection() {
        Map<String, Object> leakyOther = new LinkedHashMap<>();
        leakyOther.put("reviewerType", "RD_PM");
        leakyOther.put("decision", "REJECT");
        leakyOther.put("opinion", OTHER_OPINION);
        leakyOther.put("round", 1);
        Map<String, Object> mine = new LinkedHashMap<>();
        mine.put("reviewerType", "MARKET_PM");
        mine.put("decision", "APPROVE");
        mine.put("opinion", MY_OPINION);
        mine.put("round", 1);

        assertThat(blindSignViolations(List.of(leakyOther, mine), "MARKET_PM"))
            .as("自证能红：泄漏投影必须被同一断言助手捕获（decision+opinion 两条违例）")
            .hasSize(2)
            .anySatisfy(v -> assertThat(v).contains("decision"))
            .anySatisfy(v -> assertThat(v).contains("opinion"));

        Map<String, Object> maskedOther = new LinkedHashMap<>();
        maskedOther.put("reviewerType", "RD_PM");
        maskedOther.put("decision", null);
        maskedOther.put("opinion", null);
        maskedOther.put("round", 1);
        assertThat(blindSignViolations(List.of(maskedOther, mine), "MARKET_PM"))
            .as("遮蔽投影（字段为空）+ 己方可见 = 契约成立")
            .isEmpty();
    }
}
