package org.ruoyi.ipd.service.aiexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
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

    @Override
    public Set<String> supportedActionCodes() {
        return Set.of("C08");
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        String payload = task.getFillPayload();
        if (payload == null || payload.isBlank()) {
            return AiExecResult.fail("C08 基准值需数据：请在动作详情页用对话填表提供四项基准值后重试");
        }
        Long id = task.getStageActionId();
        Date now = Date.from(ctx.clock().instant());
        // C08 valueFields=""，validateCompletion 只要求 actualDoneAt：先落完成日再挂交付物再 transit
        stageActionService.recordFields(id, now, null, null, null, null, null, "0");

        String fileName = "C08-基准值归集-"
            + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ctx.clock().getZone()).format(ctx.clock().instant())
            + ".md";
        byte[] md = buildMarkdown(task, payload, ctx).getBytes(StandardCharsets.UTF_8);
        SysOssVo uploaded = ossService.upload(
            new ByteArrayMultipartFile("file", fileName, "text/markdown", md));
        if (uploaded == null || uploaded.getOssId() == null) {
            return AiExecResult.fail("C08 归集文件上传失败（OSS 未返回 ossId）");
        }
        stageActionService.addDeliverable(id, fileName, uploaded.getOssId(), "0");
        stageActionService.transit(id, "DONE", "R221 AI 直接执行（DEEP 归集）", "0");
        return AiExecResult.ok("C08 基准值归集完成，交付物 ossId=" + uploaded.getOssId());
    }

    /** 归集 markdown：标题 + 项目/任务定位 + fill_payload.fields 逐项。解析失败按原文兜底引用。 */
    private String buildMarkdown(AiAgentTask task, String payload, AiExecContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# C08 销量预测与商业目标（基准值）归集\n\n");
        sb.append("- 项目 ID: ").append(task.getProjectId()).append('\n');
        sb.append("- 阶段动作 ID: ").append(task.getStageActionId()).append('\n');
        sb.append("- 归集时间: ").append(ctx.clock().instant()).append('\n');
        sb.append("- 来源: R221 对话填表（AI 直接执行）\n\n");
        try {
            JsonNode root = JSON.readTree(payload);
            JsonNode fields = root.path("fields");
            if (fields.isObject() && fields.size() > 0) {
                sb.append("## 基准值\n\n");
                Iterator<Map.Entry<String, JsonNode>> it = fields.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    sb.append("- ").append(e.getKey()).append(": ").append(e.getValue().asText()).append('\n');
                }
            } else {
                sb.append("## 原始载荷\n\n```\n").append(payload).append("\n```\n");
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(new IOException("fill_payload 非法 JSON，应由上游对话填表校验拦截", ex));
        }
        return sb.toString();
    }
}
