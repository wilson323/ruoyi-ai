package org.ruoyi.ipd.vo;

import org.ruoyi.ipd.copilotkit.CommandDegrader;

import java.util.List;

/** 单动作引导步骤（步骤序 = ipd_action_skill_map.sortOrder 升序）。 */
public record GuideStepView(String actionCode, String actionName, Integer sortOrder,
                            String bindLevel, String aiMode, List<String> skillNames,
                            List<String> commandChain,
                            List<CommandDegrader.DegradedStep> degradedSteps,
                            String guidePrompt, String stepState, Boolean blocking) {
}
