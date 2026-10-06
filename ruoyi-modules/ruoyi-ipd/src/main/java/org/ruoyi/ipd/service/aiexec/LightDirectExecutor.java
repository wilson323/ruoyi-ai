package org.ruoyi.ipd.service.aiexec;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.StageActionService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Set;

/**
 * R221 轻管直接执行（R236 扩至全部 14 个 AI_DIRECT ∧ LIGHT ∧ 无 valueFields 动作）：
 * 登记完成日 → transit(DONE)，operator="0" 系统身份。
 *
 * <p><b>零 LLM</b>：本档位动作的完成判据只有 {@code actualDoneAt}（BR-IPD-05），无任何产物要求，
 * 故不绑定节点智能体、不调 {@code generate()}——给确定性动作接 LLM 只会增加成本与失败面。
 *
 * <p><b>动态深度码不得归本类</b>（契约 §7 B3）：{@code ActionCatalog.expectedDepth} 下 V11 在
 * SOLUTION 模板下为 DEEP，而 {@code StageActionService} L117 按**实例** depth 判定、DEEP 强制 ≥1
 * 交付物（BR-IPD-03）；本类不产交付物 → 该类实例必死于校验 → 退避 → DEAD。故 V11 归
 * {@link AgentEvidenceExecutor}，并由哨兵测试锁定本约束。
 */
@Service
@RequiredArgsConstructor
public class LightDirectExecutor implements AiActionExecutor {

    /** 14 码（契约 §7.3）：不含 V11（动态深度，见类注释）。 */
    static final Set<String> CODES = Set.of(
        "P08", "P09", "P10", "D02", "D03", "D07", "D08", "D09",
        "D10", "V01", "V04", "V05", "L05", "LC06");

    private final StageActionService stageActionService;

    @Override
    public Set<String> supportedActionCodes() {
        return CODES;
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        Long id = task.getStageActionId();
        AiExecResult terminal = AiActionExecutor.terminalNoOp(stageActionService.getById(id));
        if (terminal != null) {
            return terminal;
        }
        Date now = Date.from(ctx.clock().instant());
        stageActionService.recordFields(id, now, null, null, null, null, null, null, "0");
        stageActionService.transit(id, "DONE", "R221 AI 直接执行（LIGHT）", "0");
        return AiExecResult.ok(task.getActionCode() + " AI 直接执行完成：登记完成日并流转 DONE");
    }
}
