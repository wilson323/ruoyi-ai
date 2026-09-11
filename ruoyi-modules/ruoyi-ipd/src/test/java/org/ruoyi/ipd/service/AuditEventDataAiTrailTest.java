package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI-P1-3 留痕门禁单测（《AI参与留痕规范-20260910》§4）：
 * aiAssisted=true 的审计载荷必须同时携带非空 aiModel + 白名单 aiRole（draft|precheck|summarize），
 * 缺一即拒写（防半吊子留痕）；决策语义 aiRole 一概拦下（责任链红线：AI 只出建议，决策恒为人工）。
 * 门禁接线点：AuditLogService.append 入口（单点，44 文件/81 处调用自动受益）。
 */
@Tag("dev")
@DisplayName("AI-P1-3 留痕门禁：aiAssisted 三件套完备性")
class AuditEventDataAiTrailTest {

    @Test
    @DisplayName("缺 aiModel 拒写：有参与标记却查不到模型，责任链断片")
    void missingAiModelRejected() {
        String payload = AuditEventData.json(
            "aiAssisted", true, "aiRole", "draft", "projectId", 77L);
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
            () -> AuditEventData.requireAiTrail(payload, "after_data"));
        assertTrue(ex.getMessage().contains("aiModel"), ex.getMessage());
        assertTrue(ex.getMessage().contains("after_data"), "报错定位列名: " + ex.getMessage());
    }

    @Test
    @DisplayName("缺 aiRole 拒写：只标 aiAssisted+aiModel 仍属半吊子留痕")
    void missingAiRoleRejected() {
        String payload = AuditEventData.json(
            "aiAssisted", true, "aiModel", "gpt-4o-mini", "projectId", 77L);
        assertThrows(DataIntegrityViolationException.class,
            () -> AuditEventData.requireAiTrail(payload, "after_data"));
    }

    @Test
    @DisplayName("决策语义 aiRole 拒写：approve/reject/decide 一概拦（AI 不得标记为决策角色）")
    void decisionRoleRejected() {
        for (String decisionRole : new String[] {"approve", "reject", "decide"}) {
            String payload = AuditEventData.json(
                "aiAssisted", true, "aiModel", "gpt-4o-mini", "aiRole", decisionRole);
            DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class,
                () -> AuditEventData.requireAiTrail(payload, "before_data"),
                "aiRole=" + decisionRole + " 必须被拦");
            assertTrue(ex.getMessage().contains("白名单"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("合法三件套放行：draft / precheck / summarize 三角色全通过")
    void legalTrailPasses() {
        for (String role : new String[] {"draft", "precheck", "summarize"}) {
            String payload = AuditEventData.json(
                "aiAssisted", true, "aiModel", "gpt-4o-mini", "aiRole", role,
                "tokenPrompt", 120, "latencyMs", 900);
            assertDoesNotThrow(() -> AuditEventData.requireAiTrail(payload, "after_data"),
                "aiRole=" + role + " 是合法建议角色");
        }
    }

    @Test
    @DisplayName("非 AI 载荷零影响：无 aiAssisted 键或显式 false 均放行（存量 81 处调用点不受扰）")
    void nonAiPayloadUntouched() {
        assertDoesNotThrow(() -> AuditEventData.requireAiTrail(
            AuditEventData.json("projectId", 77L, "status", "GENERATED"), "after_data"));
        assertDoesNotThrow(() -> AuditEventData.requireAiTrail(
            AuditEventData.json("aiAssisted", false, "note", "人工创建"), "after_data"));
        // null/空载荷（多数审计行无载荷）同样放行
        assertDoesNotThrow(() -> AuditEventData.requireAiTrail(null, "after_data"));
        assertDoesNotThrow(() -> AuditEventData.requireAiTrail("", "before_data"));
    }

    @Test
    @DisplayName("职责边界：非法 JSON 不在本门禁拦（由 requireJson 负责），本方法静默返回")
    void malformedJsonDelegatesToRequireJson() {
        assertDoesNotThrow(() -> AuditEventData.requireAiTrail("not-json{{", "after_data"));
        assertThrows(DataIntegrityViolationException.class,
            () -> AuditEventData.requireJson("not-json{{", "after_data"), "requireJson 仍拦非法 JSON");
    }
}
