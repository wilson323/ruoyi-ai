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
    void readableIdentityRequiresExactReviewedEvidenceWithSameTitle() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        gate.noteSource(Map.of("hits", 2, "retrievalStatus", "SUCCESS", "citationText", "受控正文",
            "sourceEvidence", java.util.List.of(
                Map.of("sourceType", "KNOWLEDGE_FRAGMENT", "documentId", "12", "knowledgeId", "100",
                    "sourceName", "同名.md", "reviewStatus", "NOT_PROJECT_DOCUMENT"),
                Map.of("sourceType", "PROJECT_DOCUMENT", "documentId", "123",
                    "sourceName", "同名.md", "reviewStatus", "REVIEWED"))));
        for (String rejected : java.util.List.of(
            "同名.md（资料编号：12）来自项目已审核文档",
            "同名.md（资料编号：unknown）来自项目已审核文档",
            "资料编号：unknown 其他材料未取得，但来自项目已审核文档",
            "资料编号：unknown 若其他资料能取得，但该资料来自项目已审核文档",
            "来自项目已审核文档的资料编号：12，资料编号：123 系统知识片段",
            "资料编号：123 来自项目已审核文档，来自项目已审核文档的资料编号：unknown",
            "同名.md documentId=unknown 来自项目已审核文档",
            "同名.md（资料编号：1234）来自项目已审核文档",
            "| 同名.md（资料编号：12） | 已审核项目文档 |",
            "| 同名.md（资料编号：unknown） | 已审核项目文档 |",
            "资料编号：123 来自项目已审核文档，作为项目已审核文档的资料编号：12",
            "资料编号：123 是系统知识片段，来自项目已审核文档的资料编号：12",
            "资料编号：123 来自项目已审核文档 作为项目已审核文档的资料编号：12",
            "资料编号：123 来自项目已审核文档；资料编号：12 来自项目已审核文档")) {
            assertThat(gate.rejectionReason(rejected)).as(rejected)
                .isEqualTo(ProjectAgentCompletionGate.RejectionReason.SOURCE_IDENTITY_MISMATCH);
        }
        for (String allowed : java.util.List.of(
            "同名.md（资料编号：12）是系统知识片段，不是项目已审核文档",
            "同名.md（资料编号：123）来自项目已审核文档",
            "资料编号：123 来自项目已审核文档；资料编号：12 系统知识片段",
            "资料编号：12 系统知识片段，来自项目已审核文档的资料编号：123",
            "资料编号：123 来自项目已审核文档 资料编号：12 系统知识片段",
            "资料编号：12 系统知识片段 资料编号：123 来自项目已审核文档",
            "资料编号：unknown 不是项目已审核文档",
            "资料编号：unknown 并非属于项目已审核文档",
            "资料编号：unknown 不等于是项目已审核文档",
            "资料编号：unknown 正在查询已审核文档的使用规范",
            "资料编号：unknown 项目已审核文档未取得",
            "| 同名.md（资料编号：12） | 不是已审核项目文档 |",
            "资料编号：12 不能作为项目已审核文档",
            "| 同名.md（资料编号：123） | 已审核项目文档 |",
            "如果资料编号：unknown 来自项目已审核文档，才可引用",
            "反例：资料编号：unknown 来自项目已审核文档")) {
            assertThat(gate.rejectionReason(allowed)).as(allowed).isNull();
        }
    }

    @Test
    @DisplayName("项目上下文不得冒作审核文档；否定、条件和层级定义不拒绝")
    void projectMetadataCannotBecomeReviewedDocument() {
        ProjectAgentCompletionGate gate = new ProjectAgentCompletionGate();
        String realRow = "| 项目事实（项目代号 / 产品代号 / 阶段 / 动作） | **项目已审核文档**（最高） | 项目边界 | 未含产品 D 规格 |";
        assertThat(gate.rejectionReason(realRow))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.SOURCE_IDENTITY_MISMATCH);
        assertThat(gate.reject("项目上下文是项目已审核文档")).isEqualTo(ProjectAgentCompletionGate.REJECTED);
        for (String allowed : java.util.List.of(
            "项目事实不是已审核文档", "项目事实不等于已审核文档",
            "| 项目事实（阶段） | 不是项目已审核文档 |",
            "项目事实尚未取得；若有已审核文档将引用",
            "权威层级定义：项目已审核文档最高，系统知识片段其次",
            "若有已审核文档将引用，其他材料仅备查")) {
            assertThat(gate.reject(allowed)).as(allowed).isNull();
        }
        gate.noteSource(Map.of("hits", 1, "retrievalStatus", "SUCCESS", "citationText", "已审核正文",
            "sourceEvidence", java.util.List.of(Map.of("sourceType", "PROJECT_DOCUMENT",
                "documentId", "123", "sourceName", "审核文件.md", "reviewStatus", "REVIEWED"))));
        assertThat(gate.reject("审核文件.md documentId=123 sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED")).isNull();
        assertThat(gate.reject(realRow)).isEqualTo(ProjectAgentCompletionGate.REJECTED);
    }

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

    @org.junit.jupiter.api.Test
    void c02PurposeDefaultCannotBecomeBlockingRequirement() {
        String realRow = "| G-06 | **目的裁剪**（产品设计侧重功能矩阵 / 战略侧重格局与壁垒 / 融资材料侧重差异一页纸），按 C02 步骤 2「**用户声明时**」裁剪 | 阻塞 C02 步骤 2 | 由项目方/需求方在本次任务中显式声明用途；未声明则按规则四维齐全、篇幅克制 |";
        assertThat(new ProjectAgentCompletionGate("C02").rejectionReason(realRow))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.SKILL_CONTRACT_MISMATCH);
        assertThat(new ProjectAgentCompletionGate().rejectionReason(realRow)).isNull();
        assertThat(new ProjectAgentCompletionGate("C01").rejectionReason(realRow)).isNull();
    }

    @org.junit.jupiter.api.Test
    void c02PurposeDefaultKeepsOtherMissingFactsAndConditionalStatements() {
        for (String text : java.util.List.of(
            "用途未声明，按四维齐全、篇幅克制，不阻塞。",
            "| G-06 | 目的裁剪（用户声明时） | 可选，不阻塞 | 默认四维 |",
            "用途已声明为产品设计；竞品名单未取得，停止比较。",
            "| G-08 | 用途声明（已声明融资用途） | 阻塞融资材料输出：原始报价未取得 | 补报价 |",
            "目的裁剪不是阻塞条件，无需先声明用途。",
            "若用途未声明，默认四维；如果声明用途则裁剪。",
            "> 引用错误示例：用途未声明必须先补齐才能继续。",
            "| G-01 | 候选竞品名单 | 阻塞目的裁剪和比较 | 未取得 |")) {
            assertThat(new ProjectAgentCompletionGate("C02").rejectionReason(text)).as(text).isNull();
        }
        assertThat(new ProjectAgentCompletionGate("C02").rejectionReason("用途未声明，必须先声明才能继续"))
            .isEqualTo(ProjectAgentCompletionGate.RejectionReason.SKILL_CONTRACT_MISMATCH);
    }
    @Test
    void optionalPurposeNegationAndAffirmativeContrastStayDistinct() {
        for (String body : java.util.List.of(
            "用途未声明，不构成阻塞；按默认四维执行。",
            "用途未声明，不构成阻塞，但竞品名单缺失阻塞 C02。",
            "用途未声明，竞品名单缺失阻塞 C02。",
            "用途未声明，必须先补竞品名单才能继续。",
            "用途未声明，竞品名单缺失阻塞目的裁剪与四维比较",
            "用途未声明，如果用户要求窄化，必须先声明用途",
            "用途未声明，无须补充用途才能继续，按默认四维执行。",
            "用途未声明时，若按默认四维执行，不构成阻塞条件。",
            "| G-06 | 目的裁剪（用户声明时） | 不构成阻塞 C02 步骤2 | 未声明默认四维 |",
            "| G-06 | 目的裁剪（用户声明时） | 如果用户要求窄化，必须先声明用途 | 未声明默认四维 |")) {
            assertThat(new ProjectAgentCompletionGate("C02").rejectionReason(body)).as(body).isNull();
        }
        for (String body : java.util.List.of(
            "用途声明是可选的，但必须先声明才能继续。",
            "用途未声明，无须补报价且必须先声明用途才能继续",
            "| G-06 | 目的裁剪（用户声明时） | 如果用户要求窄化才裁剪，但未声明用途仍阻塞 C02 | 未声明默认四维 |",
            "| G-06 | 目的裁剪（用户声明时） | 用途可选，但未声明阻塞 C02 步骤2 | 未声明默认四维 |")) {
            assertThat(new ProjectAgentCompletionGate("C02").rejectionReason(body)).as(body)
                .isEqualTo(ProjectAgentCompletionGate.RejectionReason.SKILL_CONTRACT_MISMATCH);
            assertThat(new ProjectAgentCompletionGate("C01").rejectionReason(body)).as(body).isNull();
        }
    }
}
