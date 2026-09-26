package org.ruoyi.ipd.service.aiexec;

import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.service.StageActionService;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.Set;

/** R221 轻管直接执行（首切片 P08）：登记完成日 → transit(DONE)，operator="0" 系统身份。 */
@Service
@RequiredArgsConstructor
public class LightDirectExecutor implements AiActionExecutor {

    private final StageActionService stageActionService;

    @Override
    public Set<String> supportedActionCodes() {
        return Set.of("P08");
    }

    @Override
    public AiExecResult execute(AiAgentTask task, AiExecContext ctx) {
        Long id = task.getStageActionId();
        Date now = Date.from(ctx.clock().instant());
        stageActionService.recordFields(id, now, null, null, null, null, null, "0");
        stageActionService.transit(id, "DONE", "R221 AI 直接执行（LIGHT）", "0");
        return AiExecResult.ok(task.getActionCode() + " AI 直接执行完成：登记完成日并流转 DONE");
    }
}
