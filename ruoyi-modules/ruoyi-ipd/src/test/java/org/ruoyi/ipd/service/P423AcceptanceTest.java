/**
 * P4-2.3（卡 10907a06）AI 助手业务串联与风险提示——拍板口径「生成→审核→归档 主链全覆盖」验收。
 *
 * <p>与相邻卡的分工：P4-2.2（{@link P422AcceptanceTest}）钉生成侧护栏（超时/限流/预算/审计），
 * P1-10.1（{@link P1101AcceptanceTest}）钉版本链存储引擎内部契约；本类不重复实现版本引擎，
 * 只钉「业务串联」——生成产物进入人工审核队列、未审核阻断归档、审核通过后归档闭环、
 * 风险提示语义（AC-AI-07 不过滤直接透传 + UI 风险提示数据源）与失败零脏数据。
 *
 * <p>形态：真 AiGenerationService + 真 AiDocumentService 全链串联，仅将 mapper 降级为
 * 内存版链存储（条件 UPDATE 语义：WHERE 支持 eq 与 IN、SET 按 getSqlSet() 解析列名取值
 * ——生产三套 wrapper 列序不同，按位置取会写坏 by/at 列），AI 通道复用 P422 的 mock
 * provider 模式（AiGateway mock）。断言写契约（卡面 AC/BR 原文语义），不放宽断言迁就现状。
 * 已知边界：selectById 与 store 同实例耦合，主代码若删 .set(archived_by) 本套件仍绿，
 * SQL 列级写入的真鉴别力由 P1-10.1 真库探针负责（本卡不重复）。
 *
 * <p>覆盖映射（卡面 AC/BR ↔ 用例见各 @DisplayName）。
 */
package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.ipd.advice.IpdServiceExceptionAdvice;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.controller.AiDocumentController;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.mapper.AiDocumentMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 兼容 @BeforeEach 集中裸 mock() 预置（部分用例不全消费）
@DisplayName("P4-2.3 AI助手业务串联：生成→审核→归档主链/风险提示/零脏数据")
class P423AcceptanceTest {

    private static final IpdActor ACTOR = new IpdActor(9L, "pm-甲", "MARKET_PM", 900001L);
    private static final IpdActor REVIEWER = new IpdActor(12L, "审核人乙", "GROUP_LEADER", 900001L);
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneId.of("UTC"));
    private static final String PLAIN_KEY = "sk-plain-test-1234567890";

    /** 内存版本链存储（id → 行）。id 自 501 起，避免与 actor id 数值碰撞。 */
    private final Map<Long, AiDocument> store = new LinkedHashMap<>();
    private long seq = 500L;

    private AiDocumentMapper mapper;
    private AiDocumentService documentService;
    private AiModelConfigService modelConfigService;
    private IAuditLogService auditLogService;
    private AiGateway aiGateway;
    private AiDocEmbeddingService docEmbeddingService;
    private AiGenerationService generationService;

    @BeforeAll
    static void initTableInfo() {
        // 纯 Mockito JVM 无 mapper 注册环节，lambda 列解析需显式初始化（对齐 P1101/P033）
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiDocument.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(AiDocumentMapper.class);
        when(mapper.insert(any(AiDocument.class))).thenAnswer(inv -> {
            AiDocument r = inv.getArgument(0);
            r.setId(++seq);
            store.put(r.getId(), r);
            return 1;
        });
        when(mapper.selectById(any())).thenAnswer(inv -> store.get((Long) inv.getArgument(0)));
        when(mapper.selectChain(any())).thenAnswer(inv -> chainOf((Long) inv.getArgument(0)));
        // 条件 UPDATE 语义模拟：仅当内存行当前状态 == 流转来源态才生效（与生产 SQL
        // review: eq status=GENERATED set REVIEWED / archive: eq status=REVIEWED set ARCHIVED 同构）
        when(mapper.update(isNull(), any())).thenAnswer(inv -> applyTransition(inv.getArgument(1)));

        auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        modelConfigService = mock(AiModelConfigService.class);
        aiGateway = mock(AiGateway.class);
        docEmbeddingService = mock(AiDocEmbeddingService.class);
        when(docEmbeddingService.retrieveContext(any(), any(), any()))
            .thenReturn(AiDocEmbeddingService.RetrievalContext.EMPTY);

        documentService = new AiDocumentService(mapper).withClock(FIXED);
        documentService.setAuditLogService(auditLogService);
        documentService.setDocEmbeddingService(docEmbeddingService);
        generationService = new AiGenerationService(mapper, documentService,
            modelConfigService, auditLogService, aiGateway, docEmbeddingService);
    }

    // ==================== 内存链工具 ====================

    private List<AiDocument> chainOf(Long id) {
        AiDocument r = store.get(id);
        if (r == null) {
            return List.of();
        }
        AiDocument cur = r;
        while (cur.getParentVersionId() != null) {
            cur = store.get(cur.getParentVersionId());
            if (cur == null) {
                // 父缺失（软删）：生产 CTE anchor 段取不到根 → 空链（history 报 NOT_FOUND），同构对齐
                return List.of();
            }
        }
        List<AiDocument> chain = new ArrayList<>();
        chain.add(cur);
        boolean added = true;
        while (added) {
            added = false;
            Long last = chain.get(chain.size() - 1).getId();
            for (AiDocument d : store.values()) {
                if (last.equals(d.getParentVersionId())) {
                    chain.add(d);
                    added = true;
                    break;
                }
            }
        }
        return chain;
    }

    /**
     * 条件 UPDATE 语义模拟（生产 SQL 同构）。SET 值不猜位置——按 getSqlSet() 解析
     * 「列名→MPGENVALn」映射取值：生产 archive 列序是 status,archived_at,archived_by、
     * review 是 status,reviewed_by,reviewed_at、reject 是 status,review_comment,reviewed_at，
     * 三者不同（CodeReview W-1 实测：按位置取会把 archive 的 by/at 写成 NULL）。
     * WHERE 支持 eq 与 IN（D1 修复后 review 用 status IN (GENERATED,REJECTED)）。
     * 条件不命中（当前态∉来源态或行不存在）返回 0 行，驱动 service 的重读/冲突分支——
     * 「存储层第二道防线」由 archiveConcurrent 与 reviewConcurrent 系列用例覆盖。
     */
    private int applyTransition(Wrapper<AiDocument> wrapper) {
        @SuppressWarnings("unchecked")
        LambdaUpdateWrapper<AiDocument> w = (LambdaUpdateWrapper<AiDocument>) wrapper;
        // 实测（本机 MP 版本 2026-09-25 诊断钉死）：eq/IN 条件参数惰性生成——必须先物化
        // getSqlSegment()，paramNameValuePairs 才含条件占位符。
        String seg = w.getSqlSegment();
        Map<String, Object> pairs = w.getParamNameValuePairs();
        java.util.regex.Matcher idM = java.util.regex.Pattern
            .compile("id\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(seg);
        if (!idM.find()) {
            return 0;
        }
        Object idVal = pairs.get(idM.group(1));
        if (!(idVal instanceof Number n)) {
            return 0;
        }
        AiDocument row = store.get(n.longValue());
        if (row == null) {
            return 0;
        }
        // WHERE status 来源态条件校验（IN 集合或 eq 单值；不命中 ⇒ 状态机拒绝跨态流转）
        boolean statusMatched;
        java.util.regex.Matcher inM = java.util.regex.Pattern
            .compile("status\\s+IN\\s*\\(([^)]*)\\)").matcher(seg);
        if (inM.find()) {
            statusMatched = false;
            java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("MPGENVAL(\\d+)").matcher(inM.group(1));
            while (pm.find()) {
                if (String.valueOf(pairs.get("MPGENVAL" + pm.group(1))).equals(row.getStatus())) {
                    statusMatched = true;
                    break;
                }
            }
        } else {
            java.util.regex.Matcher stM = java.util.regex.Pattern
                .compile("status\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}").matcher(seg);
            statusMatched = !stM.find() || pairs.get(stM.group(1)) == null
                || String.valueOf(pairs.get(stM.group(1))).equals(row.getStatus());
        }
        if (!statusMatched) {
            return 0;
        }
        Map<String, String> col2p = new LinkedHashMap<>();
        java.util.regex.Matcher sm = java.util.regex.Pattern
            .compile("(\\w+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}")
            .matcher(w.getSqlSet());
        while (sm.find()) {
            // 捕获组 (MPGENVAL\d+) 已含字面量前缀，直接作为 pairs 的 key（勿再拼接）
            col2p.put(sm.group(1), sm.group(2));
        }
        if (!(pairs.get(col2p.get("status")) instanceof String target)) {
            return 0;
        }
        switch (target) {
            case AiDocumentService.STATUS_REVIEWED -> {
                row.setStatus(target);
                row.setReviewedBy(asLong(pairs.get(col2p.get("reviewed_by"))));
                row.setReviewedAt(asDate(pairs.get(col2p.get("reviewed_at"))));
            }
            case AiDocumentService.STATUS_ARCHIVED -> {
                row.setStatus(target);
                row.setArchivedBy(asLong(pairs.get(col2p.get("archived_by"))));
                row.setArchivedAt(asDate(pairs.get(col2p.get("archived_at"))));
            }
            case AiDocumentService.STATUS_REJECTED -> {
                row.setStatus(target);
                Object c = pairs.get(col2p.get("review_comment"));
                row.setReviewComment(c instanceof String s ? s : null);
                row.setReviewedAt(asDate(pairs.get(col2p.get("reviewed_at"))));
            }
            default -> {
                return 0;
            }
        }
        return 1;
    }

    private static Long asLong(Object v) {
        return v instanceof Number num ? num.longValue() : null;
    }

    private static java.util.Date asDate(Object v) {
        return v instanceof java.util.Date d ? d : null;
    }

    /** 与 store 真实行脱钩的历史快照（模拟并发读旧值）。 */
    private AiDocument snapshot(long id, String status) {
        return AiDocument.builder().id(id).projectId(77L).docType("PRD").title("快照")
            .content("content-v1").status(status).versionNo(1)
            .contentSha256(AiDocumentService.sha256Hex("content-v1")).build();
    }

    // ==================== 生成侧 stub（复用 P422 mock provider 模式） ====================

    private void stubEnabled(String configJson) {
        when(modelConfigService.currentEnabled()).thenReturn(AiModelConfig.builder()
            .id(1L).provider("openai").endpointUrl("https://api.openai.com/v1")
            .apiKeyEncrypted("ciphertext-not-plain").modelName("gpt-4o-mini")
            .configJson(configJson).isActive(true).build());
        when(modelConfigService.decryptApiKey(any(AiModelConfig.class))).thenReturn(PLAIN_KEY);
    }

    private void stubChatOk(String content) {
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.ok(content, 120, 480, 1500));
    }

    private static AiGenerateReq req() {
        return new AiGenerateReq(77L, "PRD", "需求文档", "原始资料：用户反馈整理与竞品速览");
    }

    private AiDocument generateOk(String content) {
        stubEnabled("{}");
        stubChatOk(content);
        return generationService.generate(ACTOR, req());
    }

    private AiDocument fixtureRow(long id, int versionNo, Long parentId, String status) {
        AiDocument r = AiDocument.builder()
            .id(id).projectId(77L).docType("PRD").title("v" + versionNo)
            .content("content-v" + versionNo).model(versionNo == 1 ? "gpt-4o-mini" : null)
            .status(status).parentVersionId(parentId).versionNo(versionNo)
            .contentSha256(AiDocumentService.sha256Hex("content-v" + versionNo))
            .build();
        store.put(id, r);
        if (id > seq) {
            seq = id;
        }
        return r;
    }

    // ==================== 主链：生成→审核→归档 ====================

    @Test
    @DisplayName("BR-AI-02/AC-AI-02：AI 生成文档即标待审核（GENERATED），且带 AI 出处标记（model 列）供 UI 风险提示")
    void generatedDocMarksPendingReviewWithAiProvenance() {
        AiDocument doc = generateOk("生成的 PRD 正文");

        assertEquals(AiDocumentService.STATUS_GENERATED, doc.getStatus(), "生成即待审核，不得直接生效");
        assertEquals(1, doc.getVersionNo());
        assertNotNull(doc.getModel(), "AI 出处标记（UI 风险提示数据源之一）");
        AiDocument stored = store.get(doc.getId());
        assertSame(doc, stored, "生成产物已入版本链存储");
        assertEquals(AiDocumentService.STATUS_GENERATED, stored.getStatus());
    }

    @Test
    @DisplayName("AC-AI-03：未审核不可归档——GENERATED 行 archive 拒绝且文案=卡面固定字面量")
    void unreviewedArchiveBlockedWithCardWording() {
        AiDocument doc = generateOk("生成的 PRD 正文");

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> documentService.archive(doc.getId(), ACTOR.id()));
        assertEquals(ApiV1ErrorCode.STATE_CONFLICT, ex.getErrorCode(), "未审核归档须 50002 状态冲突");
        assertEquals(AiDocumentService.MSG_REVIEW_REQUIRED, ex.getMessage());
        assertTrue(ex.getMessage().contains("人工审核"),
            "锁卡面文案字面（S-1：防改常量即跟着绿）: " + ex.getMessage());
        assertEquals(AiDocumentService.STATUS_GENERATED, store.get(doc.getId()).getStatus(),
            "被拒后行状态零变化");
    }

    @Test
    @DisplayName("主链闭环：GENERATED→review（审核落名+触发 RAG 入库）→archive（归档落名+双审计）→ARCHIVED 终态")
    void reviewThenArchiveFullChain() {
        AiDocument doc = generateOk("生成的 PRD 正文");

        AiDocument reviewed = documentService.review(doc.getId(), REVIEWER.id());
        assertEquals(AiDocumentService.STATUS_REVIEWED, reviewed.getStatus());
        assertEquals(12L, reviewed.getReviewedBy(), "审核落名（BR-AI-03）");
        assertEquals(java.util.Date.from(FIXED.instant()), reviewed.getReviewedAt());
        verify(docEmbeddingService).embedAsync(any(AiDocument.class));

        AiDocument archived = documentService.archive(doc.getId(), ACTOR.id());
        assertEquals(AiDocumentService.STATUS_ARCHIVED, archived.getStatus());
        assertEquals(9L, archived.getArchivedBy(), "归档落名");
        assertEquals(java.util.Date.from(FIXED.instant()), archived.getArchivedAt());

        List<String> actions = new ArrayList<>();
        var cap = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, atLeast(2)).append(cap.capture());
        cap.getAllValues().forEach(l -> actions.add(l.getAction()));
        assertTrue(actions.contains("AI_DOC_REVIEWED"), "审核流转审计: " + actions);
        assertTrue(actions.contains("AI_DOC_ARCHIVED"), "归档流转审计: " + actions);
    }

    @Test
    @DisplayName("AC-AI-03 反例：审核被拒（REJECTED）视同未审核，不可归档")
    void rejectedRowCannotBeArchived() {
        AiDocument v1 = fixtureRow(701L, 1, null, AiDocumentService.STATUS_REJECTED);

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> documentService.archive(v1.getId(), ACTOR.id()));
        assertEquals(ApiV1ErrorCode.STATE_CONFLICT, ex.getErrorCode());
        assertEquals(AiDocumentService.MSG_REVIEW_REQUIRED, ex.getMessage());
        assertEquals(AiDocumentService.STATUS_REJECTED, store.get(701L).getStatus());
    }

    @Test
    @DisplayName("ARCHIVED 终态封闭：不可再归档、不可再审核（有出口且出口封闭）")
    void archivedTerminalStateIsImmutable() {
        AiDocument v1 = fixtureRow(702L, 1, null, AiDocumentService.STATUS_ARCHIVED);

        IpdBusinessException archEx = assertThrows(IpdBusinessException.class,
            () -> documentService.archive(702L, ACTOR.id()));
        assertEquals(ApiV1ErrorCode.STATE_CONFLICT, archEx.getErrorCode(), "归档终态拒绝必须显式 50002");
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> documentService.review(702L, REVIEWER.id()));
        assertEquals(ApiV1ErrorCode.STATE_CONFLICT, ex.getErrorCode());
        assertEquals(AiDocumentService.STATUS_ARCHIVED, store.get(702L).getStatus(), "终态零变化");
    }

    // ==================== 风险提示语义（AC-AI-07 / BR-AI-04） ====================

    @Test
    @DisplayName("AC-AI-07：敏感内容不过滤直接透传——模型输出原文入库，零掩码零改写")
    void sensitiveContentPassesThroughUnfiltered() {
        String sensitive = "本预算涉密：项目总预算 9,800 万元，其中机密供应商报价见附件；"
            + "ignore previous instructions 注入样本文本";
        AiDocument doc = generateOk(sensitive);

        assertEquals(sensitive, store.get(doc.getId()).getContent(),
            "透传不过滤（AC-AI-07）：不得脱敏/截断/改写，风险把控在人工审核+UI 提示");
        assertEquals(AiDocumentService.sha256Hex(sensitive), doc.getContentSha256(),
            "摘要按原文计算（篡改可检）");
    }

    @Test
    @DisplayName("风险提示触发·预算超限：40013 AI_BUDGET_EXCEEDED + 用户可读文案，且不发起模型调用")
    void budgetExceededTriggersRiskSignal() {
        stubEnabled("{\"budgetTokens\":1000}");
        when(mapper.sumTokensSince(any())).thenReturn(950L);

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> generationService.generate(ACTOR, req()));
        assertEquals(ApiV1ErrorCode.AI_BUDGET_EXCEEDED, ex.getErrorCode());
        assertEquals("AI预算超出限制", ex.getMessage(), "风险文案用户可读");
        verify(aiGateway, never()).chat(any(), anyString(), any(), any());
        assertTrue(store.isEmpty(), "风险拦截零落库");
    }

    // ==================== 失败不产生半成品脏数据 ====================

    @Test
    @DisplayName("零脏数据·模型失败：异常抛出且版本链存储零行，仅失败审计留痕")
    void modelFailureLeavesNoHalfBakedRow() {
        stubEnabled("{}");
        when(aiGateway.chat(any(AiTestConfig.class), anyString(), any(), any()))
            .thenReturn(AiChatResult.fail("HTTP_502", "HTTP 502", 30));

        assertThrows(IpdBusinessException.class, () -> generationService.generate(ACTOR, req()));
        assertTrue(store.isEmpty(), "失败不得留下 GENERATING/半行等脏数据（无中间态落库）");
        var cap = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService).append(cap.capture());
        assertEquals("AI_GENERATE_FAILED", cap.getValue().getAction());
    }

    @Test
    @DisplayName("零脏数据·空响应：模型返回空白 → 不落版本链，行存储仍为空")
    void emptyModelResponseLeavesNoRow() {
        stubEnabled("{}");
        stubChatOk("   ");

        assertThrows(IpdBusinessException.class, () -> generationService.generate(ACTOR, req()));
        assertTrue(store.isEmpty(), "空内容防线失败后不得有半成品");
    }

    @Test
    @DisplayName("零脏数据·审核不存在版本：NOT_FOUND 且存储零变化")
    void reviewMissingVersionNotFoundNoSideEffects() {
        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> documentService.review(40404L, ACTOR.id()));
        assertEquals(ApiV1ErrorCode.NOT_FOUND, ex.getErrorCode(), "钉错误码非任意业务异常（S-1）");
        verify(mapper, never()).update(any(), any());
        assertTrue(store.isEmpty());
    }

    // ==================== D1 修复 + 存储层第二道防线（并发条件不命中） ====================

    @Test
    @DisplayName("D1 修复：REJECTED 行可重新审核（原静默 0 行返回）→ REVIEWED 落名后可归档")
    void rejectedRowCanReReviewThenArchive() {
        fixtureRow(705L, 1, null, AiDocumentService.STATUS_REJECTED);

        AiDocument reviewed = documentService.review(705L, REVIEWER.id());
        assertEquals(AiDocumentService.STATUS_REVIEWED, reviewed.getStatus(),
            "拒绝后必须重新走审核流（reject() javadoc 契约字面）");
        assertEquals(12L, store.get(705L).getReviewedBy(), "重审落名（SQL 层 SET 生效，非仅内存赋值）");
        AiDocument archived = documentService.archive(705L, ACTOR.id());
        assertEquals(AiDocumentService.STATUS_ARCHIVED, archived.getStatus());
    }

    @Test
    @DisplayName("并发·他人先归档：条件 0 行 → 重读终态 ARCHIVED 自洽返回，不报错、不重复审计")
    void archiveConcurrentPreemptedByOtherReturnsFresh() {
        AiDocument real = fixtureRow(706L, 1, null, AiDocumentService.STATUS_ARCHIVED);
        AiDocument stale = snapshot(706L, AiDocumentService.STATUS_REVIEWED);
        AtomicInteger reads = new AtomicInteger();
        when(mapper.selectById(706L)).thenAnswer(inv -> reads.getAndIncrement() == 0 ? stale : store.get(706L));

        AiDocument out = documentService.archive(706L, ACTOR.id());
        assertSame(real, out, "0 行命中走重读分支，返回真实终态行");
        verify(auditLogService, never()).append(any(AuditLog.class)); // 被抢分支不重复落审计
    }

    @Test
    @DisplayName("并发·他人先拒绝：条件 0 行且重读非 ARCHIVED → STATE_CONFLICT 显式拒绝")
    void archiveConcurrentPreemptedByRejectThrows() {
        fixtureRow(707L, 1, null, AiDocumentService.STATUS_REJECTED);
        AiDocument stale = snapshot(707L, AiDocumentService.STATUS_REVIEWED);
        AtomicInteger reads = new AtomicInteger();
        when(mapper.selectById(707L)).thenAnswer(inv -> reads.getAndIncrement() == 0 ? stale : store.get(707L));

        IpdBusinessException ex = assertThrows(IpdBusinessException.class,
            () -> documentService.archive(707L, ACTOR.id()));
        assertEquals(ApiV1ErrorCode.STATE_CONFLICT, ex.getErrorCode());
    }

    @Test
    @DisplayName("并发·他人先审核：review 条件 0 行 → 重读 REVIEWED 幂等返回不报错不覆盖")
    void reviewConcurrentPreemptedReturnsFresh() {
        AiDocument real = fixtureRow(708L, 1, null, AiDocumentService.STATUS_REVIEWED);
        real.setReviewedBy(77L); // 他人已是首位审核人
        AiDocument stale = snapshot(708L, AiDocumentService.STATUS_GENERATED);
        AtomicInteger reads = new AtomicInteger();
        when(mapper.selectById(708L)).thenAnswer(inv -> reads.getAndIncrement() == 0 ? stale : store.get(708L));

        AiDocument out = documentService.review(708L, REVIEWER.id());
        assertEquals(AiDocumentService.STATUS_REVIEWED, out.getStatus());
        assertEquals(77L, store.get(708L).getReviewedBy(), "被抢后不覆盖首位审核人");
        verify(auditLogService, never()).append(any(AuditLog.class)); // 0 行不重复落审计
    }

    // ==================== 编辑（改版）回环与版本比较 ====================

    @Test
    @DisplayName("串联含编辑：审核 v1→改版 v2 重新待审→v2 未审不可归档→审 v2→归档 v2 成功；v1 历史不可变")
    void reviseReopensReviewGateThenArchive() {
        AiDocument doc = generateOk("v1 正文");
        documentService.review(doc.getId(), REVIEWER.id());

        AiDocument v2 = documentService.revise(doc.getId(), doc.getId(), "v2 人工修订正文", null, ACTOR.id());
        assertEquals(AiDocumentService.STATUS_GENERATED, v2.getStatus(), "改版必须重新待审（BR-AI-03）");
        assertEquals(2, v2.getVersionNo());

        assertThrows(IpdBusinessException.class,
            () -> documentService.archive(v2.getId(), ACTOR.id()), "v2 未审不可归档");
        documentService.review(v2.getId(), REVIEWER.id());
        AiDocument archived = documentService.archive(v2.getId(), ACTOR.id());
        assertEquals(AiDocumentService.STATUS_ARCHIVED, archived.getStatus());
        assertEquals(AiDocumentService.STATUS_REVIEWED, store.get(doc.getId()).getStatus(),
            "v1 不被归档动作触碰（append-only，本卡不重复版本引擎职责）");
    }

    @Test
    @DisplayName("串联含版本比较：归档链上 diff(v1,v2) 报 content+sha256 差异，history 全链可回溯")
    void diffAndHistoryCloseTheLoop() {
        AiDocument doc = generateOk("v1 正文");
        documentService.review(doc.getId(), REVIEWER.id());
        AiDocument v2 = documentService.revise(doc.getId(), doc.getId(), "v2 修订", null, ACTOR.id());
        documentService.review(v2.getId(), REVIEWER.id());
        documentService.archive(v2.getId(), ACTOR.id());

        AiDocumentService.DiffReport report = documentService.diff(doc.getId(), v2.getId());
        assertEquals(1, report.fromVersionNo());
        assertEquals(2, report.toVersionNo());
        assertTrue(report.differences().stream().anyMatch(d -> d.field().equals("content")),
            "内容差异必须报出（AC-AI-05）");
        assertTrue(report.differences().stream().anyMatch(d -> d.field().equals("contentSha256")));
        assertEquals(2, documentService.history(v2.getId()).size(), "全链 v1..v2 无一缺失");
    }

    @Test
    @DisplayName("审核幂等不双写：重复 review 短路返回，首位审核人不被覆盖（串联防脏写）")
    void reviewIdempotentNoDoubleWrite() {
        AiDocument doc = generateOk("v1 正文");
        documentService.review(doc.getId(), REVIEWER.id());

        AiDocument again = documentService.review(doc.getId(), 99L);
        assertEquals(12L, again.getReviewedBy(), "幂等：不覆盖首位审核人");
        verify(mapper, times(1)).update(isNull(), any());
    }

    // ==================== HTTP 主链（controller 真实装配，权限 mock） ====================

    private MockMvc mvc() {
        IpdPermission permission = mock(IpdPermission.class);
        when(permission.requireInternal()).thenReturn(ACTOR);
        return MockMvcBuilders.standaloneSetup(
                new AiDocumentController(documentService, generationService, permission))
            .setControllerAdvice(new IpdServiceExceptionAdvice())
            .build();
    }

    @Test
    @DisplayName("HTTP 主链：generate→review→archive 三跳全 code=0，终态 ARCHIVED（页42 前端串联契约）")
    void httpFullChainGenerateReviewArchive() throws Exception {
        stubEnabled("{}");
        stubChatOk("HTTP 链路正文");
        MockMvc mvc = mvc();

        MvcResult gen = mvc.perform(post("/api/v1/ai-documents/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":77,\"docType\":\"PRD\",\"title\":\"需求文档\",\"prompt\":\"原始资料\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("GENERATED"))
            .andReturn();
        long id = com.jayway.jsonpath.JsonPath.parse(gen.getResponse().getContentAsString())
            .read("$.data.id", Long.class);

        mvc.perform(post("/api/v1/ai-documents/" + id + "/versions/" + id + "/review"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("REVIEWED"));

        mvc.perform(post("/api/v1/ai-documents/" + id + "/versions/" + id + "/archive"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(0))
            .andExpect(jsonPath("$.data.status").value("ARCHIVED"))
            .andExpect(jsonPath("$.data.archivedBy").value(9));
    }

    @Test
    @DisplayName("HTTP 未审核归档：generate 后立即 archive → HTTP 409 + code=50002 + 卡面文案")
    void httpUnreviewedArchiveReturnsConflict() throws Exception {
        stubEnabled("{}");
        stubChatOk("待审正文");
        MockMvc mvc = mvc();

        MvcResult gen = mvc.perform(post("/api/v1/ai-documents/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectId\":77,\"docType\":\"PRD\",\"title\":\"需求文档\",\"prompt\":\"原始资料\"}"))
            .andReturn();
        long id = com.jayway.jsonpath.JsonPath.parse(gen.getResponse().getContentAsString())
            .read("$.data.id", Long.class);

        mvc.perform(post("/api/v1/ai-documents/" + id + "/versions/" + id + "/archive"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(50002))
            .andExpect(jsonPath("$.message").value(AiDocumentService.MSG_REVIEW_REQUIRED));
    }
}
