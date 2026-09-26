package org.ruoyi.ipd.service.aiexec;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * R221 深管直接执行（首切片 C08 基准值归集）：对话填表载荷 → markdown 归集
 * → OSS 上传 → addDeliverable（BR-IPD-03 深管证据 ≥1 交付物）→ transit(DONE)。
 * 无载荷不伪造完成，fail 引导用户先走对话填表（spec §3.5/§4.2）。
 */
@Service
@RequiredArgsConstructor
public class DeepDirectExecutor implements AiActionExecutor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StageActionService stageActionService;
    private final ISysOssService ossService;
    private final AiAgentTaskMapper taskMapper;

    @Override
    public Set<String> supportedActionCodes() {
        return Set.of("C08");
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        Long id = task.getStageActionId();
        AiExecResult terminal = AiActionExecutor.terminalNoOp(stageActionService.getById(id));
        if (terminal != null) {
            return terminal; // M3：终态动作不重写完成日/不重复传交付物
        }
        String payload = resolvePayload(task);
        if (payload == null || payload.isBlank()) {
            return AiExecResult.fail("C08 基准值需数据：请在动作详情页用对话填表提供四项基准值后手动触发执行（主动扫描不自动采用未确认建议）");
        }
        // N2：载荷先解析后副作用——非法 JSON 不得先把完成日落库
        JsonNode fields;
        try {
            fields = JSON.readTree(payload).path("fields");
        } catch (IOException ex) {
            return AiExecResult.fail("C08 对话填表载荷非法（非 JSON）：请重新对话填表后再执行");
        }
        // 复审问题6：fields 缺失/空对象同样不得走完——空载荷不能把动作刷成 DONE
        if (!fields.isObject() || fields.size() == 0) {
            return AiExecResult.fail("C08 对话填表载荷无有效字段（fields 缺失或为空）：请重新对话填表后再执行");
        }
        Date now = Date.from(ctx.clock().instant());
        // C08 valueFields=""，validateCompletion 只要求 actualDoneAt：先落完成日再挂交付物再 transit
        stageActionService.recordFields(id, now, null, null, null, null, null, "0");

        String fileName = "C08-基准值归集-"
            + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ctx.clock().getZone()).format(ctx.clock().instant())
            + ".md";
        byte[] md = buildMarkdown(task, payload, fields, ctx).getBytes(StandardCharsets.UTF_8);
        SysOssVo uploaded = ossService.upload(
            new ByteArrayMultipartFile("file", fileName, "text/markdown", md));
        if (uploaded == null || uploaded.getOssId() == null) {
            return AiExecResult.fail("C08 归集文件上传失败（OSS 未返回 ossId）");
        }
        stageActionService.addDeliverable(id, fileName, uploaded.getOssId(), "0");
        stageActionService.transit(id, "DONE", "R221 AI 直接执行（DEEP 归集）", "0");
        return AiExecResult.ok("C08 基准值归集完成，交付物 ossId=" + uploaded.getOssId());
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
    private String buildMarkdown(AiAgentTask task, String payload, JsonNode fields, AiExecContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# C08 销量预测与商业目标（基准值）归集\n\n");
        sb.append("- 项目 ID: ").append(task.getProjectId()).append('\n');
        sb.append("- 阶段动作 ID: ").append(task.getStageActionId()).append('\n');
        sb.append("- 归集时间: ").append(ctx.clock().instant()).append('\n');
        sb.append("- 来源: R221 对话填表（AI 直接执行）\n\n");
        if (fields.isObject() && fields.size() > 0) {
            sb.append("## 基准值\n\n");
            Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append('\n');
            }
        } else {
            // 复审问题6 后不可达（execute 已前置 fail），保留原文兜底仅为防御，不再参与完成判定
            sb.append("## 原始载荷\n\n```\n").append(payload).append("\n```\n");
        }
        return sb.toString();
    }
}
