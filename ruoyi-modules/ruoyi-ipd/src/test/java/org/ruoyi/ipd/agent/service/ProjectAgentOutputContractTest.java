package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.domain.*;
import org.ruoyi.ipd.agent.model.AgentRunStatus;
import org.ruoyi.ipd.agent.support.*;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import org.ruoyi.ipd.agent.kernel.ProjectAgentOutputContract;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentOutputContractTest {
    final InMemoryAgentRunStore runs = new InMemoryAgentRunStore();
    final InMemoryArtifactVersionStore artifacts = new InMemoryArtifactVersionStore();
    IpdAgentRun run(String action) {
        var r = IpdAgentRun.builder().tenantId("t").personId(1L).projectId(2L).status("RUNNING")
            .actionCode(action).configSnapshot("{\"outputContractVersion\":1}").idempotencyKey(UUID.randomUUID().toString()).build();
        runs.insertRun(r); return r;
    }
    ProjectAgentRunHandle handle(IpdAgentRun r) {
        var h = new ProjectAgentRunHandle(r, runs, artifacts, AgentTestFixtures.MAPPER, () -> 1000L, () -> {});
        h.setDocumentVerifier(row -> {}); return h;
    }
    IpdAgentArtifactVersion document(IpdAgentRun r, String body) {
        var row = IpdAgentArtifactVersion.builder().tenantId("t").runId(r.getId()).artifactId(UUID.randomUUID().toString())
            .versionNo(1).title("report.md").content(body).contentSha256(ProjectAgentSkillCatalog.sha256Hex(body)).status("DRAFT").delFlag("0").build();
        artifacts.insert(row); return row;
    }
    @Test void ordinaryAnswerIncludingDocumentWordsDoesNotBecomeArtifact() {
        var r = run(null); var h = handle(r); h.onText("请澄清报告的含义。普通答案有文档两个字。"); h.onComplete();
        assertEquals("SUCCEEDED", runs.findRun(r.getId()).orElseThrow().getStatus()); assertEquals(0, artifacts.size());
    }
    @Test void actionClosingSummaryCannotCompleteWithoutDocumentReceipt() {
        var r = run("C01"); var h = handle(r); h.onText("# 完成报告\n已经输出全部交付物。"); h.onComplete();
        assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus()); assertEquals(0, artifacts.size());
    }
    @Test void resumedDocumentUsesOriginalBodyNotClosingSummaryAndDoesNotDuplicate() {
        var r = run("C01"); var row = document(r, "# 文档\n正文含问题？与待澄清范围。");
        var first = handle(r); first.onDocument(row.getId());
        var resumed = handle(r); resumed.onText("本轮完成。"); resumed.onComplete();
        assertEquals("SUCCEEDED", runs.findRun(r.getId()).orElseThrow().getStatus()); assertEquals(1, artifacts.size());
        assertEquals(row.getContent(), artifacts.findById(row.getId()).orElseThrow().getContent());
    }
    @Test void allDeliveredDocumentsRemainSubjectToQuality() {
        var r = run("C01"); var h = handle(r); h.onDocument(document(r, "# 好文档\n正文。").getId());
        h.onDocument(document(r, "只有澄清提问，没有文档标题。").getId()); h.onText("# 收尾标题"); h.onComplete();
        assertEquals("VERIFYING", runs.findRun(r.getId()).orElseThrow().getStatus()); assertEquals(2, artifacts.size());
    }
    @Test void documentAuthorityClaimsCannotHideBehindSafeClosingSummary() {
        var r = run("C01"); var h = handle(r); h.onDocument(document(r, "# 文档\nGate 已通过，可以签署。").getId());
        h.onText("安全收尾。"); h.onComplete(); assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus());
    }
    @Test void genericSdkStepCannotClaimDocumentRole() {
        var r = run("C01"); var h = handle(r);
        assertThrows(SecurityException.class, () -> h.onStep(ProjectAgentOutputContract.STEP, Map.of("outputKind", "DOCUMENT")));
        assertEquals(0, artifacts.size());
    }
    @Test void resumedHashOrBytesFailureCannotCommitSuccess() {
        var r = run("C01"); var h = handle(r); h.onDocument(document(r, "# 文档\n正文。").getId());
        var resumed = handle(r); resumed.setDocumentVerifier(row -> { throw new SecurityException("Actual bytes changed"); });
        resumed.onText("完成。"); assertTrue(resumed.finish(AgentRunStatus.SUCCEEDED, null));
        assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus());
    }
    @Test void trustedNumericQuoteSurvivesResumeButUntrustedDeclarationCannot() {
        for (boolean trusted : List.of(true, false)) {
            var r = run("C01"); var first = handle(r);
            first.onToolCall("search", "project_knowledge_search");
            var source = Map.<String,Object>of("projectId", "2", "hits", 1, "retrievalStatus", "SUCCESS", "citationStatus", "SUCCESS", "citationText", "实际报价增长12.5%", "sourceEvidence", List.of());
            if (trusted) first.onTrustedSource(source); else first.onSource(source);
            first.onDocument(document(r, "# 文档\n实际报价增长12.5%。未取得其他材料。").getId());
            var resumed = handle(r); resumed.onText("收尾。"); resumed.onComplete();
            assertEquals(trusted ? "SUCCEEDED" : "FAILED", runs.findRun(r.getId()).orElseThrow().getStatus());
        }
    }
    @Test void zeroHitAndPartialQuoteKeepTheirOriginalDisclosureAndEvidence() {
        var r = run("C01"); var first = handle(r); first.onToolCall("search", "project_knowledge_search");
        first.onTrustedSource(Map.of("projectId", "2", "hits", 0, "retrievalStatus", "NO_RESULTS", "citationText", ""));
        first.onDocument(document(r, "# 文档\n没有说明检索失败。").getId());
        handle(r).onComplete(); assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus());
        var second = run("C01"); var h = handle(second); h.onToolCall("search2", "project_knowledge_search");
        h.onTrustedSource(Map.of("projectId", "2", "hits", 1, "retrievalStatus", "PARTIAL", "citationStatus", "SUCCESS", "citationText", "有效引用12.5%"));
        h.onDocument(document(second, "# 文档\n12.5%。另一来源未取得。").getId()); handle(second).onComplete();
        assertEquals("SUCCEEDED", runs.findRun(second.getId()).orElseThrow().getStatus());
    }
    @Test void wrongProjectTrustedSourceIsRejected() {
        var h = handle(run("C01"));
        assertThrows(SecurityException.class, () -> h.onTrustedSource(Map.of("projectId", "999", "citationText", "12.5%")));
    }
    @Test void unknownOrHistoricalSnapshotNeverFallsBackToAutomaticArtifact() {
        for (String snapshot : List.of("{\"outputContractVersion\":99}", "{\"modelConfigId\":\"1\"}")) {
            var r = run(null); r.setConfigSnapshot(snapshot); var h = handle(r); h.onText("# 不应落草稿\n正文。"); h.onComplete();
            assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus()); assertEquals(0, artifacts.size());
        }
    }
    @Test void truncatedQuoteDoesNotAuthorizeNumbersOutsideRetainedEvidence() throws Exception {
        var r = run("C01"); var h = handle(r); h.onToolCall("search", "project_knowledge_search");
        h.onTrustedSource(Map.of("projectId", "2", "hits", 1, "retrievalStatus", "SUCCESS", "citationStatus", "SUCCESS", "citationText", "有效引用".repeat(2000) + "12.5%"));
        var event = runs.events(r.getId()).stream().filter(e -> "SOURCE".equals(e.getEventType())).findFirst().orElseThrow();
        var payload = AgentTestFixtures.MAPPER.readTree(event.getPayload());
        assertEquals(8000, payload.path("citationQuote").asText().length());
        assertTrue(payload.path("citationQuoteTruncated").asBoolean());
        assertEquals(ProjectAgentSkillCatalog.sha256Hex(payload.path("citationQuote").asText()), payload.path("citationQuoteSha256").asText());
        h.onDocument(document(r, "# 文档\n12.5%。未取得更多资料。").getId()); handle(r).onComplete();
        assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus());
    }
    @Test void alteredPersistedQuoteIsRejectedOnColdCompletion() throws Exception {
        var r = run("C01"); var h = handle(r); h.onToolCall("search", "project_knowledge_search");
        h.onTrustedSource(Map.of("projectId", "2", "hits", 1, "retrievalStatus", "SUCCESS", "citationStatus", "SUCCESS", "citationText", "有效引用12.5%"));
        h.onDocument(document(r, "# 文档\n12.5%。未取得更多资料。").getId());
        var source = runs.events(r.getId()).stream().filter(e -> "SOURCE".equals(e.getEventType())).findFirst().orElseThrow();
        var payload = (com.fasterxml.jackson.databind.node.ObjectNode) AgentTestFixtures.MAPPER.readTree(source.getPayload());
        payload.put("citationQuote", "伪造12.5%"); source.setPayload(AgentTestFixtures.MAPPER.writeValueAsString(payload));
        handle(r).onComplete(); assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus());
    }
    @Test void ForbiddenSourceNeverAuthorizesNumbersAfterResume() {
        var r = run("C01"); var h = handle(r); h.onToolCall("search", "project_knowledge_search");
        h.onTrustedSource(Map.of("projectId", "2", "hits", 0, "retrievalStatus", "FORBIDDEN", "citationText", ""));
        h.onDocument(document(r, "# 文档\n12.5%。无权取得原资料。").getId()); handle(r).onComplete();
        assertEquals("FAILED", runs.findRun(r.getId()).orElseThrow().getStatus());
    }
}
