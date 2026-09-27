package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.AiSuggestReq;
import org.ruoyi.ipd.dto.AiSuggestResp;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.ai.AiChatResult;
import org.ruoyi.ipd.service.ai.AiGateway;
import org.ruoyi.ipd.service.ai.AiTestConfig;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R227-C1（AI-FUSION Layer 2，2026-09-26，owner 拍板核心 5 页）：域内 AI 建议统一服务。
 *
 * <p>方案 §5.1：单一入口、多场景分发——按 scene 拉业务实体上下文拼 prompt 调 LLM，
 * 返回 markdown 供用户在页面上自行采纳。
 *
 * <p>强约束（方案 §5.1 + 产品圣经）：
 * <ul>
 *   <li>AI 输出**只返回 markdown，绝不写业务表**——采纳动作在前端由用户手动完成；</li>
 *   <li>BR-AI-05 越权：项目相关场景按 copilot 同款 project_members 可见性校验
 *       （不重新实现语义，复制 {@code AiCopilotService.assertProjectVisible} 的同构私有实现——
 *       copilot 正被兄弟会话在途修改，不动其文件避免撞车）；</li>
 *   <li>AI-审计三件套规约：每次调用（含降级/失败）落 {@code AI_SUGGEST} 审计行，
 *       aiAssisted=true / aiRole=suggestion / aiModel=真模型名或 "intent_match"（降级），
 *       只记 promptLen 不记原文（BR-AI-04）。</li>
 * </ul>
 *
 * <p>场景白名单（7 个，对应拍板的 5 个页面）：
 * workbench.next-step / workbench.risk-warning / project.summary.refresh /
 * project.create.suggest / demand.create.from-requirement /
 * gate.precheck-checklist / gate.conclusion-draft。
 */
@Slf4j
@Service
public class AiSuggestionService {

    /** 场景白名单（未命中直接 PARAM_INVALID，防任意 scene 拼 prompt 注入）。 */
    static final Set<String> SCENES = Set.of(
        "workbench.next-step", "workbench.risk-warning",
        "project.summary.refresh",
        "project.create.suggest", "demand.create.from-requirement",
        "gate.precheck-checklist", "gate.conclusion-draft");

    /** 建议输出 maxTokens（markdown 草稿比 copilot 短答长，比文档生成短）。 */
    static final int MAX_TOKENS = 1200;
    /** 建议调用超时（与 copilot 同档：同步交互 30s 上限）。 */
    static final int SUGGEST_TIMEOUT_MS = 30_000;
    /** prompt 总长上限（上下文渲染后截断，防极端数据撑爆）。 */
    static final int PROMPT_MAX = 30_000;

    private final AiModelConfigService modelConfigService;
    private final WorkbenchService workbenchService;
    private final AiGateway aiGateway;
    private final IAuditLogService auditLogService;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final GateReviewMapper gateReviewMapper;
    private final GateElementResultMapper gateElementResultMapper;

    /** 测试口注入固定时钟（同 copilot 模式）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public AiSuggestionService(AiModelConfigService modelConfigService,
                               WorkbenchService workbenchService,
                               AiGateway aiGateway,
                               IAuditLogService auditLogService,
                               ProjectMapper projectMapper,
                               ProjectMemberMapper projectMemberMapper,
                               GateReviewMapper gateReviewMapper,
                               GateElementResultMapper gateElementResultMapper) {
        this.modelConfigService = modelConfigService;
        this.workbenchService = workbenchService;
        this.aiGateway = aiGateway;
        this.auditLogService = auditLogService;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.gateReviewMapper = gateReviewMapper;
        this.gateElementResultMapper = gateElementResultMapper;
    }

    AiSuggestionService withClock(java.time.Clock fixed) {
        this.clock = fixed;
        return this;
    }

    /**
     * 统一入口：校验 → 越权 → 场景上下文 → prompt → LLM → 审计 → 返回 markdown。
     * 模型未启用走降级应答（同 copilot 语义：友好提示不抛 500，前端按 degraded 展示引导）。
     */
    public AiSuggestResp suggest(IpdActor actor, AiSuggestReq req) {
        long start = clock.millis();
        if (req == null || req.scene() == null || !SCENES.contains(req.scene())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "scene 必填且在白名单内：" + String.join(", ", SCENES.stream().sorted().toList()));
        }
        String scene = req.scene();
        // 创建类场景必须有用户素材；项目类场景必须有 projectId
        if ((scene.equals("project.create.suggest") || scene.equals("demand.create.from-requirement"))
            && (req.userPrompt() == null || req.userPrompt().isBlank())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "该场景 userPrompt 必填（原始素材）");
        }
        if (scene.startsWith("gate.")) {
            if (req.entityId() == null) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "gate 场景 entityId（gateId）必填");
            }
            List<GateReview> reviews = gateReviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
                .eq(GateReview::getGateId, req.entityId()));
            if (reviews.isEmpty()) {
                throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "该 Gate 下无评审记录");
            }
            assertProjectVisible(actor, reviews.get(0).getProjectId());
        } else if (scene.startsWith("workbench.")) {
            assertProjectVisible(actor, req.projectId()); // 可空=全局，同 copilot 语义
        } else {
            if (req.projectId() == null) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "该场景 projectId 必填");
            }
            assertProjectVisible(actor, req.projectId());
        }

        String context = renderContext(actor, req);
        String prompt = composePrompt(scene, context, req.userPrompt());

        AiModelConfig config;
        try {
            config = modelConfigService.currentEnabled();
        } catch (IpdBusinessException ex) {
            long latency = clock.millis() - start;
            audit(actor, req, latency, 0, 0,
                "FAIL:" + (ex.getErrorCode() == null ? "UNKNOWN" : ex.getErrorCode().name()), null);
            return AiSuggestResp.degraded(scene,
                "AI 建议暂未启用：未配置生效的 AI 模型。请联系超管在「AI 模型配置」启用。", latency);
        }

        AiChatResult result = aiGateway.chat(
            new AiTestConfig(config.getProvider(), config.getEndpointUrl(),
                modelConfigService.decryptApiKey(config), config.getModelName(), SUGGEST_TIMEOUT_MS),
            prompt, MAX_TOKENS, new BigDecimal("0.50"));

        long latency = clock.millis() - start;
        if (!result.success()) {
            audit(actor, req, latency, 0, 0,
                "FAIL:" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()), config.getModelName());
            throw new IpdBusinessException(ApiV1ErrorCode.INTERNAL_ERROR,
                "AI 建议暂不可用：" + (result.errorCode() == null ? "UNKNOWN" : result.errorCode()));
        }
        audit(actor, req, latency, result.promptTokens(), result.completionTokens(), "ok", config.getModelName());
        return new AiSuggestResp(scene, result.content() == null ? "" : result.content(),
            config.getModelName(), result.promptTokens(), result.completionTokens(), latency, false);
    }

    // ---- 场景上下文渲染（包私有静态便于行为测试直渲断言） ----

    /** 按场景拉业务上下文（渲染为纯文本块，进 prompt 不进审计）。 */
    private String renderContext(IpdActor actor, AiSuggestReq req) {
        String scene = req.scene();
        if (scene.startsWith("workbench.")) {
            return renderWorkbenchContext(workbenchService.summary(actor, req.projectId()));
        }
        if (scene.equals("project.summary.refresh")) {
            return renderProjectContext(requireProject(req.projectId()));
        }
        if (scene.startsWith("gate.")) {
            List<GateReview> reviews = gateReviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
                .eq(GateReview::getGateId, req.entityId()));
            GateReview latest = reviews.get(reviews.size() - 1);
            StringBuilder sb = new StringBuilder(renderProjectContext(requireProject(latest.getProjectId())));
            for (GateReview r : reviews) {
                sb.append("- 评审行：gate=").append(nullToDash(r.getGateCode()))
                    .append("，轮次=").append(r.getRound())
                    .append("，签署人类型=").append(nullToDash(r.getReviewerType()))
                    .append("，决定=").append(nullToDash(r.getDecision()))
                    .append("，意见=").append(nullToDash(r.getOpinion())).append('\n');
            }
            List<GateElementResult> results = gateElementResultMapper.selectList(
                new LambdaQueryWrapper<GateElementResult>()
                    .eq(GateElementResult::getGateId, req.entityId()));
            int shown = 0;
            for (GateElementResult r : results) {
                if (shown++ >= 20) {
                    sb.append("- （要素结果共 ").append(results.size()).append(" 条，仅渲染前 20 条）\n");
                    break;
                }
                sb.append("- 要素#").append(r.getElementId())
                    .append("：结果=").append(nullToDash(r.getResult()))
                    .append("，备注=").append(nullToDash(r.getConditionNote())).append('\n');
            }
            return sb.toString();
        }
        // 创建类场景无实体上下文，素材在 userPrompt
        return "";
    }

    /** workbench summary → 文本块（当前推进 + 待办前 10 条 + 统计）；渲染口径同 copilot 意图兜底路径。 */
    @SuppressWarnings("unchecked")
    static String renderWorkbenchContext(Map<String, Object> summary) {
        StringBuilder sb = new StringBuilder();
        Object adv = summary.get("currentAdvance");
        if (adv instanceof Map<?, ?> m && !m.isEmpty()) {
            Map<String, Object> a = (Map<String, Object>) m;
            sb.append("- 当前推进：项目 ").append(nullToDash(str(a.get("projectCode"))))
                .append(' ').append(nullToDash(str(a.get("projectName"))))
                .append("，阶段 ").append(nullToDash(str(a.get("currentStage"))))
                .append("，下一动作 ").append(nullToDash(str(a.get("actionName")))).append('\n');
        }
        if (summary.get("stats") instanceof Map<?, ?> s) {
            sb.append("- 统计：待处理 ").append(s.get("pending"))
                .append("，超期 ").append(s.get("overdue"))
                .append("，未完成 ").append(s.get("completed")).append('\n');
        }
        if (summary.get("tasks") instanceof List<?> tasks) {
            int i = 0;
            for (Object t : tasks) {
                if (i++ >= 10) {
                    sb.append("- （待办共 ").append(tasks.size()).append(" 条，仅渲染前 10 条）\n");
                    break;
                }
                if (t instanceof Map<?, ?> tm) {
                    Map<String, Object> m = (Map<String, Object>) tm;
                    sb.append("- 待办：").append(nullToDash(str(m.get("title"))))
                        .append("（状态 ").append(nullToDash(str(m.get("status"))))
                        .append("，截止 ").append(nullToDash(str(m.get("dueAt"))))
                        .append(m.get("overdue") == Boolean.TRUE ? "，已超期" : "").append("）\n");
                }
            }
        }
        return sb.toString();
    }

    static String renderProjectContext(Project p) {
        return "- 项目：" + nullToDash(p.getCode()) + ' ' + nullToDash(p.getName())
            + "，当前阶段 " + nullToDash(p.getCurrentStage()) + '\n';
    }

    private Project requireProject(Long projectId) {
        Project p = projectMapper.selectById(projectId);
        if (p == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不存在");
        }
        return p;
    }

    /** scene → 任务指令模板 + 上下文拼接（超出 PROMPT_MAX 截断；userPrompt 原文只进 prompt）。 */
    static String composePrompt(String scene, String context, String userPrompt) {
        String instruction = switch (scene) {
            case "workbench.next-step" ->
                "根据以下工作台上下文，给出该产品经理接下来最应推进的 3 个具体行动（按优先级排序，每条一句话+理由）。";
            case "workbench.risk-warning" ->
                "根据以下工作台上下文，识别 3 条最紧迫的风险预警（超期、停滞、临期），每条给出风险描述与建议动作。";
            case "project.summary.refresh" ->
                "根据以下项目上下文，写一段 150 字以内的项目状态总结（进展、阻塞、下一步），markdown 格式。";
            case "project.create.suggest" ->
                "你是 IPD 立项助手。根据用户的原始想法，起草项目章程要点：项目名建议、目标（可衡量的 3 条）、范围（含/不含）、关键干系人建议。markdown 分节输出。";
            case "demand.create.from-requirement" ->
                "你是需求管理助手。把用户的原始需求整理成规范需求单草稿：标题（≤30字）、需求描述、分类建议、优先级建议（P0-P3+理由）、验收标准草案。markdown 分节输出。";
            case "gate.precheck-checklist" ->
                "你是 Gate 评审助手。根据以下评审与要素结果上下文，生成评审前检查清单：哪些要素证据已齐、哪些缺失或存疑、评审需要追问的问题。markdown 清单格式。";
            case "gate.conclusion-draft" ->
                "你是 Gate 评审助手。根据以下评审上下文与要素结果，起草评审结论（含通过/不通过/有条件通过的建议倾向与理由、遗留条件建议）。markdown，200 字以内。";
            default -> throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "未知场景：" + scene);
        };
        StringBuilder sb = new StringBuilder(instruction).append('\n');
        if (!context.isEmpty()) {
            sb.append("\n【业务上下文】\n").append(context);
        }
        if (userPrompt != null && !userPrompt.isBlank()) {
            sb.append("\n【用户原始素材】\n").append(userPrompt).append('\n');
        }
        sb.append("\n要求：只输出 markdown 正文，不要解释你没有的数据；不确定的信息标注「待确认」。");
        String s = sb.toString();
        return s.length() > PROMPT_MAX ? s.substring(0, PROMPT_MAX) : s;
    }

    // ---- 越权与审计 ----

    /** 项目可见性校验（copilot 同构：SA 全可见，其余需 project_members 命中；不改动 copilot 文件避免在途撞车）。 */
    private void assertProjectVisible(IpdActor actor, Long projectId) {
        if (projectId == null) {
            return; // 全局维度不限制（workbench 全局待办）
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        if ("SUPER_ADMIN".equals(actor.role())) {
            return;
        }
        Long hit = projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getPersonId, actor.id()));
        if (hit == null || hit == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见");
        }
    }

    /** AI_SUGGEST 审计行（三件套规约 §2：aiRole=suggestion；原文不落库，只记 promptLen）。 */
    private void audit(IpdActor actor, AiSuggestReq req, long latencyMs,
                       int tokenPrompt, int tokenCompletion, String status, String aiModel) {
        auditLogService.append(AuditLog.builder()
            .operatorId(actor.id()).operatorName(actor.name()).operatorRole(actor.role())
            .action("AI_SUGGEST").entityType("AI_SUGGESTION")
            .afterData(AuditEventData.json(
                "aiAssisted", true,
                "aiModel", aiModel == null || aiModel.isBlank() ? "intent_match" : aiModel,
                "aiRole", "suggestion",
                "scene", req.scene(),
                "projectId", req.projectId(),
                "entityId", req.entityId(),
                "status", status,
                "tokenPrompt", tokenPrompt,
                "tokenCompletion", tokenCompletion,
                "latencyMs", latencyMs,
                "promptLen", req.userPrompt() == null ? 0 : req.userPrompt().length()))
            .build());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String nullToDash(String s) {
        return s == null || s.isBlank() || "null".equals(s) ? "-" : s;
    }
}
