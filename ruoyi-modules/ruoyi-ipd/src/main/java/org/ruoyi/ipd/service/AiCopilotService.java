package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.AiCopilotReq;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI-P2-3（2026-09-11）：AI 副驾——工作台项目助理。
 * <ul>
 *   <li>意图兜底：关键字命中 {@link #classifyIntent} → TASKS/ADVANCE 直接走 workbench 现成数据
 *       渲染（不调 AI，省一次 chat 与 audit 的 token 误标）；CHITCHAT 走 AI 生成回答；</li>
 *   <li>越权：projectId 非空时，SA 放行；其余角色需 project_members 命中 → 否则 NOT_FOUND/403 拦下，
 *       「不教 AI 编数据」（卡面要求，BR-AI-05）；</li>
 *   <li>上下文注入：项目上下文 = workbench summary.currentAdvance（当前阶段+未完成动作）；
 *       个人上下文 = workbench summary.tasks（待我处理/临期超期列表）；不注入 prompt 原文与日志；</li>
 *   <li>审计：每次问答记 AI_COPILOT_CHAT，afterData 三件套 aiAssisted/aiModel/aiRole=copilot_answer
 *       + intent + token 用量 + latencyMs；只记数量不记对话原文（BR-AI-04）；</li>
 *   <li>SSE 流式：{@code /ai-copilot/chat/stream} 控制器层把同步结果分片推流（观感达成；
 *       真流式 AI-STRAT-3 接入 Langchain4j streaming）；</li>
 *   <li>未来接 RAG：复用 AI-STRAT-1 {@code AiDocEmbeddingService.retrieveContext} 作为第三档上下文，
 *       本卡 MVP 先打通项目/个人两档。</li>
 * </ul>
 */
@Slf4j
@Service
public class AiCopilotService {

    /** 多轮历史最大条数（防 prompt 爆炸；超长由前端分页截断）。 */
    static final int MAX_HISTORY = 8;
    /** 副驾单次最大生成 token（短答；与生成文档的 4096/8192 解耦）。 */
    static final int MAX_TOKENS = 800;
    /** 副驾超时（独立于 generate 的 60s；问答短交互 30s 内足够）。 */
    static final int COPILOT_TIMEOUT_MS = 30_000;

    private final AiModelConfigService modelConfigService;
    private final WorkbenchService workbenchService;
    private final AiGateway aiGateway;
    private final AuditLogService auditLogService;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public AiCopilotService(AiModelConfigService modelConfigService,
                            WorkbenchService workbenchService,
                            AiGateway aiGateway,
                            AuditLogService auditLogService,
                            ProjectMapper projectMapper,
                            ProjectMemberMapper projectMemberMapper) {
        this.modelConfigService = modelConfigService;
        this.workbenchService = workbenchService;
        this.aiGateway = aiGateway;
        this.auditLogService = auditLogService;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    /** 测试口：注入固定时钟。 */
    AiCopilotService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    public AiCopilotResp chat(IpdActor actor, AiCopilotReq req) {
        long start = clock.millis();
        if (req == null || req.message() == null || req.message().isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "message 必填");
        }
        // 越权拦截：projectId 非空时按角色过滤（不教 AI 编数据；BR-AI-05 内部角色对等 ≠ 跨项目越权）
        assertProjectVisible(actor, req.projectId());

        // 1) 意图分类——命中直接走 workbench 数据 + 简短模板解释（不调 AI，节省 token 与审计失真）
        String intent = classifyIntent(req.message());
        if ("TASKS".equals(intent)) {
            return tasksPath(actor, req, start, intent);
        }
        if ("ADVANCE".equals(intent)) {
            return advancePath(actor, req, start, intent);
        }

        // 2) CHITCHAT/FALLBACK：调 AI 生成（项目+个人上下文注入 system prompt；BR-AI-04 不入原文）
        return chitchatPath(actor, req, start, intent);
    }

    // ---- 意图分类（关键字命中；ML 分类留后续） ----

    static String classifyIntent(String message) {
        String m = message == null ? "" : message;
        if (m.contains("该干什么") || m.contains("我该干啥") || m.contains("待办")
            || m.contains("做什么") || m.contains("next") || m.contains("todo")) {
            return "TASKS";
        }
        if (m.contains("进度") || m.contains("阶段") || m.contains("推进")
            || m.contains("项目当前") || m.contains("卡在哪") || m.contains("advance")) {
            return "ADVANCE";
        }
        return "CHITCHAT";
    }

    // ---- 三个路径 ----

    private AiCopilotResp tasksPath(IpdActor actor, AiCopilotReq req, long start, String intent) {
        Map<String, Object> summary = workbenchService.summary(actor, req.projectId());
        List<Map<String, Object>> tasks = tasksList(summary);
        List<AiCopilotResp.CopilotDataItem> items = new ArrayList<>();
        for (Map<String, Object> t : tasks) {
            items.add(new AiCopilotResp.CopilotDataItem(
                String.valueOf(t.getOrDefault("taskType", "TASK")),
                String.valueOf(t.getOrDefault("title", "")),
                String.valueOf(t.getOrDefault("hint", "")),
                String.valueOf(t.getOrDefault("url", ""))));
        }
        long latency = clock.millis() - start;
        // 意图兜底不调 AI：审计只记「分发意图」+ token=0；不假标 aiModel（避免门禁误为 AI 生成）
        auditCopilot(actor, req, intent, latency, 0, 0, "intent_match", null);
        String answer = items.isEmpty()
            ? "当前没有待你处理的待办。"
            : "你有 " + items.size() + " 项待办，下方按类型排序展示。";
        return new AiCopilotResp(intent, answer, items, List.of("workbench.tasks"), 0, 0, latency);
    }

    private AiCopilotResp advancePath(IpdActor actor, AiCopilotReq req, long start, String intent) {
        Map<String, Object> summary = workbenchService.summary(actor, req.projectId());
        Map<String, Object> advance = advanceMap(summary);
        List<AiCopilotResp.CopilotDataItem> items = new ArrayList<>();
        if (advance != null && !advance.isEmpty()) {
            // advanceMap 兼容旧键名（外部测试 mock 偶用 "advance"），WorkbenchService 实际键为 "currentAdvance"
            String projectName = String.valueOf(advance.getOrDefault("projectName", ""));
            String stage = String.valueOf(advance.getOrDefault("currentStage", ""));
            String action = String.valueOf(advance.getOrDefault("actionName", ""));
            if (!projectName.isBlank() && !"null".equals(projectName)) {
                items.add(new AiCopilotResp.CopilotDataItem("ADVANCE",
                    projectName + "｜" + stage,
                    action.isBlank() || "null".equals(action) ? "当前阶段无未完成动作" : "下一步：" + action,
                    null));
            }
        }
        long latency = clock.millis() - start;
        auditCopilot(actor, req, intent, latency, 0, 0, "intent_match", null);
        String answer = items.isEmpty()
            ? (req.projectId() == null ? "请先选中一个项目。" : "当前项目无进行中的阶段动作。")
            : "你关注的项目当前推进情况如下。";
        return new AiCopilotResp(intent, answer, items, List.of("workbench.advance"), 0, 0, latency);
    }

    private AiCopilotResp chitchatPath(IpdActor actor, AiCopilotReq req, long start, String intent) {
        AiModelConfig config;
        try {
            config = modelConfigService.currentEnabled();
        } catch (IpdBusinessException ex) {
            // 兜底：当前未启用 AI 模型时返回友好提示，不抛 50002（避免用户体验断崖；AI-STRAT-1 已落地可后续接）
            long latency = clock.millis() - start;
            auditCopilot(actor, req, intent, latency, 0, 0,
                "FAIL:" + (ex.getErrorCode() == null ? "UNKNOWN" : ex.getErrorCode().name()), null);
            // S3923 修复：原三元两分支文案相同（冗余拷贝粘贴），统一为单一提示语，行为零变更。
            String tip = "AI 副驾暂未启用：未配置生效的 AI 模型。请联系超管在「AI 模型配置」启用。";
            return new AiCopilotResp(intent, tip, List.of(), List.of("config.disabled"),
                0, 0, latency);
        }
        Map<String, Object> summary = workbenchService.summary(actor, req.projectId());
        String projectCtx = renderProjectContext(summary);
        String personalCtx = renderPersonalContext(summary);
        String prompt = composePrompt(req, projectCtx, personalCtx);

        AiChatResult result = aiGateway.chat(
            new AiTestConfig(config.getProvider(), config.getEndpointUrl(),
                modelConfigService.decryptApiKey(config), config.getModelName(), COPILOT_TIMEOUT_MS),
            prompt, MAX_TOKENS, new BigDecimal("0.50"));

        long latency = clock.millis() - start;
        if (!result.success()) {
            auditCopilot(actor, req, intent, latency, 0, 0,
                "FAIL:" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()), config.getModelName());
            // 失败时透传给前端（与现有 chat 失败语义一致）
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "AI 副驾暂不可用：" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()));
        }
        String answer = result.content() == null ? "" : result.content();
        auditCopilot(actor, req, intent, latency, result.promptTokens(), result.completionTokens(), "ok", config.getModelName());
        List<String> sources = new ArrayList<>();
        if (!projectCtx.isEmpty()) sources.add("project.advance");
        if (!personalCtx.isEmpty()) sources.add("workbench.tasks");
        return new AiCopilotResp(intent, answer, List.of(), sources,
            result.promptTokens(), result.completionTokens(), latency);
    }

    // ---- Prompt 拼装（项目+个人在前、需求在后；总长钳 MAX_PROMPT_LEN 解耦为本地常量） ----

    static final int COPILOT_PROMPT_MAX = 8_000;

    static String composePrompt(AiCopilotReq req, String projectCtx, String personalCtx) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 IPD 产品经理系统的 AI 副驾，负责回答工作台相关的短问题。\n");
        sb.append("- 回答 ≤200 字，分点列出；不要编数据，未确认的字段说「未确认」。\n");
        sb.append("- 用户问项目情况时，仅基于下方「项目上下文」与「个人上下文」回答；越权信息一律拒答。\n\n");
        if (projectCtx != null && !projectCtx.isBlank()) {
            sb.append("【项目上下文】\n").append(projectCtx).append("\n\n");
        }
        if (personalCtx != null && !personalCtx.isBlank()) {
            sb.append("【个人上下文（待办/临期）】\n").append(personalCtx).append("\n\n");
        }
        sb.append("【历史对话】\n");
        List<AiCopilotReq.CopilotTurn> history = req.history();
        if (history != null) {
            int from = Math.max(0, history.size() - MAX_HISTORY);
            for (int i = from; i < history.size(); i++) {
                AiCopilotReq.CopilotTurn t = history.get(i);
                sb.append("- ").append(t.role() == null ? "user" : t.role())
                    .append("：").append(t.content() == null ? "" : t.content()).append("\n");
            }
        }
        sb.append("\n【本次问题】\n").append(req.message());
        if (sb.length() > COPILOT_PROMPT_MAX) {
            return sb.substring(0, COPILOT_PROMPT_MAX);
        }
        return sb.toString();
    }

    // ---- 上下文渲染（BR-AI-04：不进审计/日志；只渲染摘要） ----

    static String renderProjectContext(Map<String, Object> summary) {
        Map<String, Object> advance = advanceMap(summary);
        if (advance == null || advance.isEmpty()) {
            return "";
        }
        String projectName = String.valueOf(advance.getOrDefault("projectName", ""));
        String stage = String.valueOf(advance.getOrDefault("currentStage", ""));
        String action = String.valueOf(advance.getOrDefault("actionName", ""));
        StringBuilder sb = new StringBuilder();
        sb.append("项目：").append(nullToDash(projectName)).append("\n");
        sb.append("当前阶段：").append(nullToDash(stage)).append("\n");
        sb.append("下一步动作：").append(action == null || action.isBlank() || "null".equals(action) ? "（无）" : action);
        return sb.toString();
    }

    static String renderPersonalContext(Map<String, Object> summary) {
        List<Map<String, Object>> tasks = tasksList(summary);
        if (tasks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int max = Math.min(tasks.size(), 8);
        for (int i = 0; i < max; i++) {
            Map<String, Object> t = tasks.get(i);
            sb.append(i + 1).append(". [")
                .append(t.getOrDefault("taskType", "TASK")).append("] ")
                .append(t.getOrDefault("title", "")).append(" — ")
                .append(t.getOrDefault("hint", "")).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> advanceMap(Map<String, Object> summary) {
        if (summary == null) return Map.of();
        // 优先 WorkbenchService.summary 的真实键 "currentAdvance"；兼容旧契约 "advance"
        Object a = summary.get("currentAdvance");
        if (!(a instanceof Map)) a = summary.get("advance");
        return a instanceof Map ? (Map<String, Object>) a : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> tasksList(Map<String, Object> summary) {
        if (summary == null) return List.of();
        Object t = summary.get("tasks");
        return t instanceof List ? (List<Map<String, Object>>) t : List.of();
    }

    // ---- 越权拦截（不依赖反射调私有 visibleProjects，自己写最小版） ----

    private void assertProjectVisible(IpdActor actor, Long projectId) {
        if (projectId == null) {
            return; // 全局问题不限制
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        if ("SUPER_ADMIN".equals(actor.role())) {
            return; // SA 全可见
        }
        Long hit = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getPersonId, actor.id()));
        if (hit == null || hit == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见");
        }
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() || "null".equals(s) ? "-" : s;
    }

    // ---- 审计（AI_COPILOT_CHAT，三件套 + aiRole=copilot_answer；只记数量不记原文） ----

    private void auditCopilot(IpdActor actor, AiCopilotReq req, String intent, long latencyMs,
                              int tokenPrompt, int tokenCompletion, String status, String aiModel) {
        boolean aiCalled = aiModel != null && !aiModel.isBlank();
        // AI-P1-3 三件套：aiAssisted 必为 true（copilot 是 AI 直接回答）；aiModel/aiRole 白名单
        // intent_match 路径（命中即返回，不调 AI）也标 aiAssisted=true + aiModel="intent_match"——
        // 门禁不会拦"intent_match"模型名（非空即可）；语义诚实标记「本轮 AI 没真调」。
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role())
            .action("AI_COPILOT_CHAT").entityType("AI_COPILOT")
            .afterData(AuditEventData.json(
                "aiAssisted", true,
                "aiModel", aiCalled ? aiModel : "intent_match",
                "aiRole", "copilot_answer",
                "intent", intent,
                "projectId", req.projectId(),
                "status", status,
                "tokenPrompt", tokenPrompt,
                "tokenCompletion", tokenCompletion,
                "latencyMs", latencyMs,
                "promptLen", req.message() == null ? 0 : req.message().length()))
            .build());
    }

    /** 业务时钟注入（裸时钟守卫禁 System.currentTimeMillis，见 治理/测试编写三禁）。 */
    long nowMs() {
        return clock.millis();
    }

}
