package org.ruoyi.ipd.service.aiexec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.utils.DateUtils;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * R221 数值归集直接执行（R236 由 C08 单码**数据化泛化**至全部 4 个 AI_DIRECT ∧ 有 valueFields 动作）：
 * 人确认的对话填表载荷 → markdown 归集 → OSS 上传 → addDeliverable（BR-IPD-03 深管证据 ≥1 交付物）
 * → 按 valueFields 落 recordFields → transit(DONE)。无载荷不伪造完成，fail 引导用户先走对话填表
 * （spec §3.5/§4.2）。
 *
 * <p><b>为何泛化而非新建 {@code ValueFieldDirectExecutor}</b>（契约 §1 裁决 C）：C08 与 D11/V02/L08
 * 是**同一行为族**（人确认载荷 → 归集 → 落库），差异只在 {@code valueFields} 是否为空。用
 * {@code ActionCatalog.byCode(code).valueFields()} 数据驱动即可消去 C08 硬编码，少一个类、少一处重复逻辑。
 *
 * <p><b>valueFields token → 载荷键 → recordFields 形参 映射（契约 §7.1，不得按 token 字面反射）</b>：
 * <ul>
 *   <li>{@code FAR,FRR}（D11）→ 载荷 {@code farValue}+{@code frrValue} → 形参 farValue/frrValue；
 *       {@code StageActionService} L193-195 与 L294-296 **双处强制成对**，缺一即抛，故本类前置校验；</li>
 *   <li>{@code CERT_NO,CERT_DATE}（V02）→ 载荷 {@code certNo}+{@code certPassedAt}；
 *       <b>token 名 CERT_DATE 与字段名 certPassedAt 不同源</b>，且 {@code CERT_DATE} 在全代码零消费点，
 *       实际由 {@code CERT_NO} token 触发校验（L297）；</li>
 *   <li>{@code LAUNCH_DATE}（L08）→ <b>StageAction 无 launchDate 列</b>（L43-48）、validateCompletion
 *       无专属守卫；上市日期即完成日，唯一落点是 {@code actualDoneAt}；</li>
 *   <li>{@code BASELINE}（C08）→ 无数值落点，载荷字段仅进归集 md，只落 {@code actualDoneAt}
 *       （修正原 L71 过期注释「C08 valueFields=""」——实际为 BASELINE）。</li>
 * </ul>
 *
 * <p><b>红线 1/2</b>：写库数值一律来自人确认载荷，**绝不解析 LLM 自由文本回填数值字段**；
 * 载荷键即 {@code AiCopilotService.FILL_FIELD_WHITELIST}（scene {@code stage-action-fields}）的
 * Java 字段名，后端已强校验、敏感字段永不下发。
 */
@Service
@RequiredArgsConstructor
public class DeepDirectExecutor implements AiActionExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 4 码（契约 §7.3）：C08/D11/V02/L08。 */
    static final Set<String> CODES = Set.of("C08", "D11", "V02", "L08");

    private final StageActionService stageActionService;
    private final ISysOssService ossService;
    private final AiAgentTaskMapper taskMapper;

    @Override
    public Set<String> supportedActionCodes() {
        return CODES;
    }

    /** 遗留 MINOR#2：本档位需对话填表载荷，SCHEDULE 自动派发无载荷必 fail → 不接受主动扫描，只走人确认后的 PASSIVE。 */
    @Override
    public boolean supportsSchedule() {
        return false;
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        Long id = task.getStageActionId();
        AiExecResult terminal = AiActionExecutor.terminalNoOp(stageActionService.getById(id));
        if (terminal != null) {
            return terminal; // M3：终态动作不重写完成日/不重复传交付物
        }
        ActionDef def = ActionCatalog.byCode(task.getActionCode());
        String payload = resolvePayload(task);
        if (payload == null || payload.isBlank()) {
            return AiExecResult.fail(def.code() + " " + def.name() + "需数据：请在动作详情页用对话填表提供 "
                + def.valueFields() + " 后手动触发执行（主动扫描不自动采用未确认建议）");
        }
        // N2：载荷先解析后副作用——非法 JSON 不得先把完成日落库
        JsonNode fields;
        try {
            fields = JSON.readTree(payload).path("fields");
        } catch (IOException ex) {
            return AiExecResult.fail(def.code() + " 对话填表载荷非法（非 JSON）：请重新对话填表后再执行");
        }
        // 复审问题6：fields 缺失/空对象同样不得走完——空载荷不能把动作刷成 DONE
        if (!fields.isObject() || fields.size() == 0) {
            return AiExecResult.fail(def.code() + " 对话填表载荷无有效字段（fields 缺失或为空）：请重新对话填表后再执行");
        }

        Set<String> vf = tokens(def.valueFields());
        BigDecimal farValue = null;
        BigDecimal frrValue = null;
        String certNo = null;
        Date certPassedAt = null;
        if (vf.contains("FAR") || vf.contains("FRR")) {
            farValue = decimal(fields, "farValue");
            frrValue = decimal(fields, "frrValue");
            // 前置校验：StageActionService 双处强制成对，缺一即抛——先 fail 引导比抛异常更可读
            if (farValue == null || frrValue == null) {
                return AiExecResult.fail(def.code() + " 要求 FAR/FRR 成对登记，载荷缺 farValue/frrValue：请重新对话填表后再执行");
            }
        }
        if (vf.contains("CERT_NO")) {
            certNo = text(fields, "certNo");
            certPassedAt = date(fields, "certPassedAt");
            if (certNo == null || certNo.isBlank() || certPassedAt == null) {
                return AiExecResult.fail(def.code() + " 要求 certNo 与 certPassedAt（目录 token CERT_DATE）同时登记：请重新对话填表后再执行");
            }
        }
        // LAUNCH_DATE/BASELINE 无独立数值列 → 上市日期/完成日统一落 actualDoneAt；
        // 人确认值优先于系统当前时间，未给则用当前时间（与 C08 原行为一致）
        Date actualDoneAt = date(fields, "actualDoneAt");
        Date now = Date.from(ctx.clock().instant());
        stageActionService.recordFields(id, actualDoneAt == null ? now : actualDoneAt,
            farValue, frrValue, certNo, certPassedAt, null, null, "0");

        String fileName = def.code() + "-" + def.name() + "-归集-"
            + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ctx.clock().getZone()).format(ctx.clock().instant())
            + ".md";
        byte[] md = buildMarkdown(task, def, payload, fields, ctx).getBytes(StandardCharsets.UTF_8);
        SysOssVo uploaded = ossService.upload(
            new ByteArrayMultipartFile("file", fileName, "text/markdown", md));
        if (uploaded == null || uploaded.getOssId() == null) {
            return AiExecResult.fail(def.code() + " 归集文件上传失败（OSS 未返回 ossId）");
        }
        stageActionService.addDeliverable(id, fileName, uploaded.getOssId(), "0");
        stageActionService.transit(id, "DONE", "R221 AI 直接执行（DEEP 归集）", "0");
        return AiExecResult.ok(def.code() + " " + def.name() + "归集完成，交付物 ossId=" + uploaded.getOssId());
    }

    /** valueFields 逗号切分（空/空白 → 空集，如 C08 的 BASELINE 也走同一入口，无数值落点即无分支）。 */
    private static Set<String> tokens(String valueFields) {
        Set<String> out = new LinkedHashSet<>();
        if (valueFields == null || valueFields.isBlank()) {
            return out;
        }
        for (String t : valueFields.split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static String text(JsonNode fields, String key) {
        JsonNode n = fields.get(key);
        return n == null || n.isNull() ? null : n.asText().trim();
    }

    private static BigDecimal decimal(JsonNode fields, String key) {
        JsonNode n = fields.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String s = n.asText().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return null; // 非数值不猜、不默认 0——由上层成对/非空校验拦下并引导重填
        }
    }

    /** 日期解析复用 {@link DateUtils#parseDate(Object)}（仓内成熟件，多模式 + 失败返回 null），不自建解析。 */
    private static Date date(JsonNode fields, String key) {
        JsonNode n = fields.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isNumber()) {
            return new Date(n.asLong());
        }
        String s = n.asText().trim();
        return s.isEmpty() ? null : DateUtils.parseDate(s);
    }

    /**
     * M1（CodeReview）：PASSIVE 行自身 fill_payload 恒为 null（唯一写载荷的 triggerChat 落 CHAT
     * 审计行不参与派发），故回捞同动作最近一条带载荷的 CHAT 行闭环对话填表链路。
     *
     * <p>复审问题1/2 收紧信任面：CHAT 行的 actionCode/stageActionId 均来自客户端 pageContext，
     * 回捞仅限「自然人点过执行」的 PASSIVE 行（triggerType=PASSIVE 且 triggeredBy 非空），
     * 且必须同项目 + 同动作码（server 端 task.projectId/actionCode 做硬约束，防跨项目/跨动作串载荷）；
     * SCHEDULE/EVENT 无人确认环节，不得自动采用 suggest 载荷，连查都不查。
     */
    private String resolvePayload(AiAgentTask task) {
        String payload = task.getFillPayload();
        if (payload != null && !payload.isBlank()) {
            return payload;
        }
        if (!AiAgentTask.TRIGGER_PASSIVE.equals(task.getTriggerType()) || task.getTriggeredBy() == null) {
            return null;
        }
        AiAgentTask chat = taskMapper.selectOne(new LambdaQueryWrapper<AiAgentTask>()
            .eq(AiAgentTask::getStageActionId, task.getStageActionId())
            .eq(AiAgentTask::getProjectId, task.getProjectId())
            .eq(AiAgentTask::getActionCode, task.getActionCode())
            .eq(AiAgentTask::getTriggerType, AiAgentTask.TRIGGER_CHAT)
            .isNotNull(AiAgentTask::getFillPayload)
            .orderByDesc(AiAgentTask::getId)
            .last("LIMIT 1"));
        return chat == null ? null : chat.getFillPayload();
    }

    /** 归集 markdown：标题 + 项目/任务定位 + 载荷 fields 逐项；无可解析字段时按原文兜底引用。 */
    private String buildMarkdown(AiAgentTask task, ActionDef def, String payload, JsonNode fields, AiExecContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(def.code()).append(' ').append(def.name()).append("（数值归集）\n\n");
        sb.append("- 项目 ID: ").append(task.getProjectId()).append('\n');
        sb.append("- 阶段动作 ID: ").append(task.getStageActionId()).append('\n');
        sb.append("- 归集时间: ").append(ctx.clock().instant()).append('\n');
        sb.append("- 目录 valueFields: ").append(def.valueFields() == null || def.valueFields().isBlank()
            ? "（无数值要求）" : def.valueFields()).append('\n');
        sb.append("- 来源: R221 对话填表（AI 直接执行，人确认后归集）\n\n");
        if (fields.isObject() && fields.size() > 0) {
            sb.append("## 登记值\n\n");
            fields.properties().forEach(e ->
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append('\n')
            );
        } else {
            // 复审问题6 后不可达（execute 已前置 fail），保留原文兜底仅为防御，不再参与完成判定
            sb.append("## 原始载荷\n\n```\n").append(payload).append("\n```\n");
        }
        return sb.toString();
    }
}
