package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完成门：零命中须写明未取得；只有肯定句里的 Gate 宣称才拒绝。
 * 否定句里出现「评审通过」不得当成越权。
 */
@Tag("dev")
class ProjectAgentCompletionGateTest {

    @Test
    @DisplayName("固定原因保持原判据、首拒绝优先与通用业务码")
    void fixedReasonsPreserveDecisionAndPrecedence() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        assertThat(gate.rejectionReason("<think>隐藏内容</think>"))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.EMPTY_BODY);
        assertThat(gate.rejectionReason("Gate已通过，数值0.74"))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.GATE_AUTHORITY_CLAIM);
        gate.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        assertThat(gate.rejectionReason("数值0.74"))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.MISSING_RETRIEVAL_DISCLOSURE);
        assertThat(gate.rejectionReason("数值0.74未取得"))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.UNSUPPORTED_MEASUREMENT);
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "0.7407947778701782",
            "sourceEvidence", java.util.List.of(Map.of("sourceType", "KNOWLEDGE_FRAGMENT",
                "documentId", "12", "knowledgeId", "100", "sourceName", "模板.md",
                "reviewStatus", "NOT_PROJECT_DOCUMENT"))));
        assertThat(gate.rejectionReason("documentId=12 sourceType=PROJECT_DOCUMENT 数值0.74"))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.SOURCE_IDENTITY_MISMATCH);
        assertThat(gate.rejectionReason("最高相似度约0.74"))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.UNSUPPORTED_MEASUREMENT);
        assertThat(gate.reject("最高相似度约0.74"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.rejectionReason("原文数值0.7407947778701782，未宣称评审通过" )).isNull();
        assertThat(gate.reject("原文数值0.7407947778701782，未宣称评审通过" )).isNull();
    }

    @Test
    void knownKnowledgeIdentityCannotBeClassifiedAsReviewedProjectDocument() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "模板正文",
            "sourceEvidence", java.util.List.of(Map.of("sourceType", "KNOWLEDGE_FRAGMENT",
                "documentId", "template-doc", "knowledgeId", "100", "sourceName", "产品主档模板.md",
                "reviewStatus", "NOT_PROJECT_DOCUMENT"))));
        assertThat(gate.reject("## 1.2 项目已审核文档\n| 产品主档模板.md | 模板结构 |"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("产品主档模板.md 属于已审核文档"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("## 系统知识库\n| 产品主档模板.md | 模板结构 |\n项目已审核文档未取得。"))
            .isNull();
        assertThat(gate.reject("## 项目已审核文档\n| 产品主档模板.md | 不是项目已审核文档 |"))
            .isNull();
        // 同名正式文档真实命中时存在歧义，不凭文件名拒绝。
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "审核正文",
            "sourceEvidence", java.util.List.of(Map.of("sourceType", "PROJECT_DOCUMENT",
                "documentId", "123", "sourceName", "产品主档模板.md", "reviewStatus", "REVIEWED"))));
        assertThat(gate.reject("## 项目已审核文档\n| 产品主档模板.md | 审核正文 |"))
            .isNull();
    }

    @Test
    void sameTitleCannotBorrowTheOtherDocumentIdentity() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "模板正文",
            "sourceEvidence", java.util.List.of(Map.of("sourceType", "KNOWLEDGE_FRAGMENT",
                "documentId", "template-doc", "knowledgeId", "100", "sourceName", "模板.md",
                "reviewStatus", "NOT_PROJECT_DOCUMENT"))));
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "审核正文",
            "sourceEvidence", java.util.List.of(Map.of("sourceType", "PROJECT_DOCUMENT",
                "documentId", "123", "sourceName", "模板.md", "reviewStatus", "REVIEWED"))));
        assertThat(gate.reject("模板.md documentId=template-doc sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("模板.md不是完整版本但来自项目已审核文档 documentId=template-doc"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("模板.md documentId=123 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED"))
            .isNull();
        assertThat(new ProjectAgentCompletionGate().reject("操作说明：评审通过后才能进入下一阶段。"))
            .isNull();
    }

    @Test
    void identityTokensAreExactAndEachReferenceIsChecked() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 2, "retrievalStatus", "SUCCESS", "citationText", "正文",
            "sourceEvidence", java.util.List.of(
                Map.of("sourceType", "KNOWLEDGE_FRAGMENT", "documentId", "12", "knowledgeId", "100",
                    "fragmentId", "55", "sourceName", "模板.md", "reviewStatus", "NOT_PROJECT_DOCUMENT"),
                Map.of("sourceType", "PROJECT_DOCUMENT", "documentId", "123",
                    "sourceName", "正式.md", "reviewStatus", "REVIEWED"))));
        assertThat(gate.reject("正式.md documentId=123 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED")).isNull();
        assertThat(gate.reject("正式.md documentId=123 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED；"
            + "模板.md documentId=12 sourceType=KNOWLEDGE_FRAGMENT reviewStatus=NOT_PROJECT_DOCUMENT")).isNull();
        assertThat(gate.reject("模板.md documentId=12 sourceType=KNOWLEDGE_FRAGMENT reviewStatus=NOT_PROJECT_DOCUMENT；"
            + "模板.md documentId=12 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED documentId=123｜正式.md")).isNull();
        assertThat(gate.reject("documentId=123 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED "
            + "documentId=12 sourceType=KNOWLEDGE_FRAGMENT reviewStatus=NOT_PROJECT_DOCUMENT")).isNull();
        assertThat(gate.reject("documentId=12 sourceType=KNOWLEDGE_FRAGMENT reviewStatus=NOT_PROJECT_DOCUMENT "
            + "documentId=12 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    void untypedLegacyCitationDoesNotInventDocumentClassification() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "模板.md"));
        assertThat(gate.reject("项目已审核文档未取得；模板.md只是资料。" )).isNull();
    }

    @Test
    @DisplayName("否定句里的评审通过不拒绝；有命中时可落草稿")
    void denialOfGateClaimIsNotOverreach() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS"));
        String text = """
            不输出 Gate 评审通过或不通过的结论。
            未给出 Gate 评审通过或不通过的结论。
            公开报价未取得。
            """;

        assertThat(gate.reject(text)).isNull();
    }

    @Test
    @DisplayName("分句内的肯定宣称仍拒绝")
    void affirmativeGateClaimStillRejects() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        gate.noteSource(Map.of("hits", 3, "retrievalStatus", "SUCCESS"));

        assertThat(gate.reject("建议 Gate 签署，评审通过")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("资料未齐，但评审通过")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    @DisplayName("检索零命中且未写未取得仍拒绝")
    void zeroHitWithoutDisclosureStillRejects() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        gate.noteSource(Map.of("hits", 0));

        assertThat(gate.reject("竞品价格为 12 元")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    @DisplayName("引用中的小数和百分数必须出现在命中原句里")
    void measurementMustMatchQuotedSource() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS",
            "preview", "海康威视.md 研发费用率 12.83%"));

        assertThat(gate.reject("研发费用率 12.83%。其余未取得。")).isNull();
        assertThat(gate.reject("研发费用率 99.1%。")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("步骤 1，年份 2026，费用率 12.83%。")).isNull();
    }

    @Test
    @DisplayName("没查到或无权时，正文里的百分数不能当出处")
    void missingOrDeniedSourceCannotSupportMeasurement() {
        ProjectAgentCompletionGate empty = new ProjectAgentCompletionGate();
        empty.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        empty.noteSource(Map.of("hits", 0, "retrievalStatus", "NO_HIT", "preview", ""));
        assertThat(empty.reject("费用率未取得，但可能是 12.83%。")).isEqualTo(ProjectAgentCompletionGate.REJECTED);

        ProjectAgentCompletionGate denied = new ProjectAgentCompletionGate();
        denied.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        denied.noteSource(Map.of("hits", 0, "retrievalStatus", "UNAUTHORIZED",
            "preview", "无权：没有权限"));
        assertThat(denied.reject("无权，费用率未取得。")).isNull();
        assertThat(denied.reject("无权，费用率 12.83%。")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }
    @Test
    void completeEvidenceBeyondPreviewAndUnsupportedNumbers() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS",
            "preview", "原句前缀", "citationText", "前缀".repeat(600) + "研发费用率12.83%"));
        assertThat(gate.reject("研发费用率12.83%。")).isNull();
        for (String number : new String[]{"118.69", "13.09", "117.53"}) {
            assertThat(gate.reject("费用" + number)).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        }
        assertThat(gate.reject("<think>试算118.69，评审通过</think>研发费用率12.83%。")).isNull();
    }

    @Test
    void mixedFailureDoesNotContributeNumbers() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteTool(ProjectAgentCompletionGate.SEARCH_TOOL);
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "PARTIAL",
            "preview", "失败118.69", "citationText", "失败118.69"));
        assertThat(gate.reject("118.69")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    void partialUsesOnlyExplicitCleanEvidence() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "PARTIAL", "citationStatus", "SUCCESS",
            "preview", "成功12.83%，失败99.1%", "citationText", "干净原句12.83%"));
        assertThat(gate.reject("费用率12.83%。")).isNull();
        assertThat(gate.reject("费用率99.1%。")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        ProjectAgentCompletionGate missing = new ProjectAgentCompletionGate();
        missing.noteSource(Map.of("hits", 1, "retrievalStatus", "PARTIAL", "citationStatus", "SUCCESS",
            "preview", "失败99.1%", "citationText", ""));
        assertThat(missing.reject("费用率99.1%。")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    void incompleteThinkingNeverBecomesDeliverable() {
        assertThat(ProjectAgentCompletionGate.deliverableBody("正文<think>118.69")).isEqualTo("正文");
        assertThat(ProjectAgentCompletionGate.deliverableBody("<think>118.69</think>正文<think>13.09</think>结尾"))
            .isEqualTo("正文结尾");
        assertThat(new ProjectAgentCompletionGate().reject("<think>未闭合"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    void emptyOutputCannotComplete() {
        for (String text : new String[]{null, "", "  ", "<think>过程</think>"}) {
            assertThat(new ProjectAgentCompletionGate().reject(text)).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        }
    }

    @Test
    void remoteSourceEnforcesCompletionWithoutLocalToolName() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 0, "retrievalStatus", "FAILED", "sourceKind", "REMOTE_APPLICATION"));
        assertThat(gate.reject("价格已确认")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("价格未取得。")).isNull();
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "费用率12.83%"));
        assertThat(gate.reject("费用率12.83%。")).isNull();
        assertThat(gate.reject("费用率99.1%。")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    void headingSectionNumbersAreNotMeasurements() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "项目资料原句"));
        assertThat(gate.reject("### 1.1 项目内资料检索\n### 1.2.3 产品资料\n正文分析")).isNull();
        assertThat(gate.reject("### 1.1 项目资料\n价格1.2元")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("### 1.1 价格1.2元")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("正文中1.1是报价")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("### 1.2% 费用率")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        assertThat(gate.reject("### 1.1 项目资料\n费用率12.83%"))
            .isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

    @Test
    void unknownSourceStatusCannotSupplyEvidence() {
        for (String status : new String[]{"", "UNKNOWN", "FAILED", "UNAUTHORIZED", "NO_HIT"}) {
            ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
            gate.noteSource(Map.of("hits", 1, "retrievalStatus", status, "citationText", "费用12.83%"));
            assertThat(gate.reject("费用12.83%，其余未取得。")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        }
    }

}
