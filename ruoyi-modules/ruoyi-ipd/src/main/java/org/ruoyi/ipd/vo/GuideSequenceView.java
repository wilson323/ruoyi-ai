package org.ruoyi.ipd.vo;

import java.util.List;

/** 小阶段引导序列（GET /guide-events 的 progressPatch.value.guideSteps/advanceGate 载荷）。 */
public record GuideSequenceView(String subStageCode, String subStageName, String stageCode,
                                String introText, List<GuideStepView> steps,
                                AdvanceGateView advanceGate) {
}
