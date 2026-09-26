package org.ruoyi.ipd.service.aiexec;

import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.StageAction;

import java.util.Set;

/**
 * R221 AI 动作执行器 SPI（spec §4.2）。
 * 一个执行器声明它支持的 actionCode 集合，引擎按 code 路由；接 69 动作 = 写适配器 + 填矩阵，引擎零改动。
 */
public interface AiActionExecutor {

    /** 本执行器支持的动作码集合（引擎据此建 code→executor 路由表）。 */
    Set<String> supportedActionCodes();

    /** 执行单个任务，返回结构化结果（不抛业务异常；异常由引擎捕获进退避/DEAD）。 */
    AiExecResult execute(AiAgentTask task, AiExecContext ctx);

    /** 终态守卫（CodeReview M3）：DONE/NA 动作重复触发必须 no-op，
     * 不得改写历史完成日/重复生成文档/重复挂交付物（transit 只对同 target 幂等，侧效应不幂等）。 */
    static AiExecResult terminalNoOp(StageAction action) {
        if (action != null && ("DONE".equals(action.getStatus()) || "NA".equals(action.getStatus()))) {
            return AiExecResult.ok("no-op: 动作已终态 " + action.getStatus() + "，AI 不重复执行");
        }
        return null;
    }
}
