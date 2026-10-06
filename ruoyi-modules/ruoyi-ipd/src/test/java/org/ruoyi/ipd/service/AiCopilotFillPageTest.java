package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R221 对话即填表（spec §3.5）：意图分类 + schema 白名单强校验（后端强校验非仅前端）+ JSON 容错解析。
 */
@Tag("dev")
class AiCopilotFillPageTest {

    @Test
    void classifyIntentRecognizesFillPage() {
        assertThat(AiCopilotService.classifyIntent("帮我把这个动作的基准值填了", true)).isEqualTo("FILL_PAGE");
        // 无页面上下文时「填」不劫持闲聊
        assertThat(AiCopilotService.classifyIntent("帮我把这个动作的基准值填了", false)).isEqualTo("CHITCHAT");
        // 既有关键字优先级不被破坏（TASKS 优先于 FILL_PAGE；输入以磁盘现态实关键字「待办」为准）
        assertThat(AiCopilotService.classifyIntent("我的待办有哪些", true)).isEqualTo("TASKS");
        // 1 参重载委托 hasPageContext=false，「填」不命中 FILL_PAGE
        assertThat(AiCopilotService.classifyIntent("帮我填一下")).isEqualTo("CHITCHAT");
    }

    @Test
    void schemaWhitelistDropsUnknownFields() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("actualDoneAt", "2026-09-26");
        raw.put("farValue", "0.001");
        raw.put("salary", "99999");
        raw.put("operatorRole", "SUPER_ADMIN");
        // R230：remark 曾误列白名单造成「建议得出但存不下」。D4（2026-10-06）后端端点已支持
        // remark（人工链路可存），但 AI 链路维持 R230 口径暂不放开——AI 产出 remark 仍在
        // filterFillFields 层丢弃（本断言即该口径的守门）。
        raw.put("remark", "AI 建议的备注");

        Map<String, Object> kept = AiCopilotService.filterFillFields("stage-action-fields", raw);

        assertThat(kept).containsOnlyKeys("actualDoneAt", "farValue");
        assertThat(kept).doesNotContainKey("remark");
    }

    @Test
    void stageActionWhitelistExcludesRemarkAndMatchesFieldsEndpoint() {
        // R230 能力对称性守门：白名单必须 ⊆ StageActionFieldsReq 可落库字段。
        // D4（2026-10-06）后 Req 已含第 7 字段 remark（人工链路）；AI 链路维持不放开 remark
        //（R230 口径），故白名单保持 6 字段、remark 仍为负向锚点。
        Set<String> allowed = AiCopilotService.FILL_FIELD_WHITELIST.get("stage-action-fields");

        assertThat(allowed).doesNotContain("remark");
        assertThat(allowed).containsExactlyInAnyOrder(
            "actualDoneAt", "farValue", "frrValue", "certNo", "certPassedAt", "algoType");
        assertThat(allowed).hasSize(6);
    }

    @Test
    void unknownSceneRejectsAll() {
        Map<String, Object> kept = AiCopilotService.filterFillFields("bonus-pool", Map.of("amount", "1"));
        assertThat(kept).isEmpty(); // 未登记 scene 一律拒绝（金额类敏感面永不开放）
    }

    @Test
    void parseJsonMapToleratesMarkdownFenceAndProse() {
        // AI 常见把 JSON 包在 ```json 围栏 + 前后解释文字里，解析须容错截取首个 { 到末个 }。
        // 注意职责分工：parseJsonMap 是**纯解析器**，此处 remark 透传是解析层正确行为（它不做过滤）；
        // remark 的真正拦截点在下游 filterFillFields（见 schemaWhitelistDropsUnknownFields / R230）。
        Map<String, Object> m = AiCopilotService.parseJsonMap(
            "好的，结果如下：\n```json\n{\"farValue\":\"0.002\",\"remark\":\"ok\"}\n```\n以上。");
        assertThat(m).containsEntry("farValue", "0.002").containsEntry("remark", "ok");
    }

    @Test
    void parseJsonMapReturnsEmptyOnGarbage() {
        assertThat(AiCopilotService.parseJsonMap("不是 JSON")).isEmpty();
        assertThat(AiCopilotService.parseJsonMap(null)).isEmpty();
        assertThat(AiCopilotService.parseJsonMap("")).isEmpty();
    }
}
