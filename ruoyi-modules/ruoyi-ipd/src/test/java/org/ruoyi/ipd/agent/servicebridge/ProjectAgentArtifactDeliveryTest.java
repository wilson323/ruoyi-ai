package org.ruoyi.ipd.agent.servicebridge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.artifact.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.ipd.agent.domain.*;
import org.ruoyi.ipd.agent.store.*;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.service.IpdCopilotAccess;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Tag("dev")
class ProjectAgentArtifactDeliveryTest {
    @Test
    void nativeOriginMustMatchLogicalVersionAndTitle() {
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "identity.md",
                                                "text".getBytes(StandardCharsets.UTF_8),
                                                "identity.md",
                                                null,
                                                false))
                                .successful())
                .isTrue();
        String original = events.get(0).getPayload();
        events.get(0)
                .setPayload(
                        original.replace("\"title\":\"identity.md\"", "\"title\":\"forged.md\""));
        assertThatThrownBy(() -> target.download(AgentTestFixtures.ACTOR, 101L))
                .hasMessageContaining("version identity mismatch");
        events.get(0).setPayload(original);
        rows.get(101L).setVersionNo(2);
        assertThatThrownBy(() -> target.requireDocumentContent(rows.get(101L)))
                .hasMessageContaining("version identity mismatch");
    }

    @TempDir Path root;
    Map<Long, IpdAgentArtifactVersion> rows = new HashMap<>();
    Map<Long, IpdAgentArtifactVersion> before;
    boolean commitFail;
    boolean leaseHeld = true;
    boolean eventFail;
    List<IpdAgentRunEvent> events = new ArrayList<>();
    List<IpdAgentRunEvent> beforeEvents;
    AtomicLong ids = new AtomicLong(100);
    ArtifactVersionStore versions = mock(ArtifactVersionStore.class);
    IpdCopilotAccess access = mock(IpdCopilotAccess.class);
    AgentRunStore runs = mock(AgentRunStore.class);
    RuntimeContext ctx = RuntimeContext.builder().userId("trusted-user").sessionId("901").build();
    ProjectAgentArtifactDelivery target;

    @BeforeEach
    void setup() throws Exception {
        root = root.toRealPath();
        when(access.requireVisible(any(), any())).thenReturn(AgentTestFixtures.TENANT);
        var r = new IpdAgentRun();
        r.setId(901L);
        r.setPersonId(AgentTestFixtures.ACTOR.id());
        r.setProjectId(AgentTestFixtures.PROJECT_ID);
        r.setTenantId(AgentTestFixtures.TENANT);
        r.setStatus("RUNNING");
        when(runs.findRun(901L)).thenReturn(Optional.of(r));
        when(versions.findLatestForUpdate(any(), any(), any()))
                .thenAnswer(
                        i ->
                                rows.values().stream()
                                        .filter(v -> v.getArtifactId().equals(i.getArgument(2)))
                                        .max(
                                                Comparator.comparing(
                                                        IpdAgentArtifactVersion::getVersionNo)));
        when(versions.insert(any()))
                .thenAnswer(
                        i -> {
                            IpdAgentArtifactVersion v = i.getArgument(0);
                            rows.put(v.getId(), v);
                            return true;
                        });
        when(versions.findById(any()))
                .thenAnswer(i -> Optional.ofNullable(rows.get(i.getArgument(0))));
        var manager =
                new AbstractPlatformTransactionManager() {
                    protected Object doGetTransaction() {
                        return new Object();
                    }

                    protected void doBegin(Object t, TransactionDefinition d) {
                        before = new HashMap<>(rows);
                        beforeEvents = new ArrayList<>(events);
                    }

                    protected void doCommit(DefaultTransactionStatus s) {
                        if (commitFail) throw new IllegalStateException("forced commit rollback");
                    }

                    protected void doRollback(DefaultTransactionStatus s) {
                        rows.clear();
                        rows.putAll(before);
                        events.clear();
                        events.addAll(beforeEvents);
                    }
                };
        manager.setRollbackOnCommitFailure(true);
        when(runs.listEvents(any(), anyLong(), anyInt()))
                .thenAnswer(
                        inv ->
                                events.stream()
                                        .filter(e -> e.getSeq() > (Long) inv.getArgument(1))
                                        .toList());
        var origin =
                new ProjectAgentArtifactOrigin(
                        runs,
                        payload -> {
                            if (eventFail) throw new IllegalStateException("forced event failure");
                            var event = new IpdAgentRunEvent();
                            event.setRunId(901L);
                            event.setTenantId(AgentTestFixtures.TENANT);
                            event.setSeq((long) events.size() + 1);
                            event.setEventType("ARTIFACT");
                            try {
                                event.setPayload(
                                        new com.fasterxml.jackson.databind.ObjectMapper()
                                                .writeValueAsString(payload));
                            } catch (Exception x) {
                                throw new RuntimeException(x);
                            }
                            events.add(event);
                        });
        target =
                new ProjectAgentArtifactDelivery(
                        new ProjectAgentArtifactDelivery.Binding(
                                AgentTestFixtures.ACTOR,
                                AgentTestFixtures.PROJECT_ID,
                                AgentTestFixtures.TENANT,
                                901L,
                                "trusted-user",
                                "901"),
                        access,
                        runs,
                        versions,
                        new TransactionTemplate(manager),
                        new ProjectAgentArtifactDelivery.RunOwnerTransaction() {
                            public <T> T owned(Supplier<T> b) {
                                if (!leaseHeld) throw new SecurityException("run lease lost");
                                return b.get();
                            }
                        },
                        root,
                        ids::incrementAndGet,
                        (binding, runtime) -> {
                            if (runtime != ctx)
                                throw new SecurityException("No server-issued runtime claim");
                        },
                        origin);
    }

    @Test
    void utf8WholeContentBinaryBytesAndAppendForceKeepOldVersion() {
        byte[] text = ("完整UTF8正文😀\n" + "全文".repeat(2000)).getBytes(StandardCharsets.UTF_8);
        var result =
                target.deliver(
                        ctx, new ArtifactDeliveryRequest("doc.md", text, "doc.md", null, false));
        assertThat(result.successful()).isTrue();
        assertThat(rows.get(101L).getContent()).isEqualTo(new String(text, StandardCharsets.UTF_8));
        assertThat(rows.get(101L).getStatus()).isEqualTo("DRAFT");
        assertThat(target.download(AgentTestFixtures.ACTOR, 101L)).containsExactly(text);
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "doc.md", text, "doc.md", null, false))
                                .conflict())
                .isTrue();
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "doc.md",
                                                new byte[] {0, 1, (byte) 255, 42},
                                                "doc.md",
                                                null,
                                                true))
                                .successful())
                .isTrue();
        assertThat(rows.get(102L).getVersionNo()).isEqualTo(2);
        assertThat(target.download(AgentTestFixtures.ACTOR, 101L)).containsExactly(text);
        assertThat(target.download(AgentTestFixtures.ACTOR, 102L))
                .containsExactly(0, 1, (byte) 255, 42);
        assertThatThrownBy(() -> target.requireDocumentContent(rows.get(102L)))
                .hasMessageContaining("not document apply");
        verify(versions, never()).markApplied(any(), any());
    }

    @Test
    void commitRollbackRemovesOnlyNewBytesAndRows() {
        commitFail = true;
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "a.bin", new byte[] {1, 2}, "a.bin", null, false))
                                .successful())
                .isFalse();
        assertThat(rows).isEmpty();
        assertThat(root.resolve("101.bin")).doesNotExist();
    }

    @Test
    void wrongRunChildRevokedAndTamperedBytesCannotReturnSuccess() throws Exception {
        leaseHeld = false;
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "lease.bin",
                                                new byte[] {1},
                                                "lease.bin",
                                                null,
                                                false))
                                .successful())
                .isFalse();
        assertThat(rows).isEmpty();
        leaseHeld = true;
        var otherRun = runs.findRun(901L).orElseThrow();
        otherRun.setPersonId(999L);
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "owner.bin",
                                                new byte[] {1},
                                                "owner.bin",
                                                null,
                                                false))
                                .successful())
                .isFalse();
        assertThat(rows).isEmpty();
        otherRun.setPersonId(AgentTestFixtures.ACTOR.id());
        var child = RuntimeContext.builder().userId("trusted-user").sessionId("child-901").build();
        assertThat(
                        target.deliver(
                                        child,
                                        new ArtifactDeliveryRequest(
                                                "a.bin", new byte[] {1, 2}, "a.bin", null, false))
                                .successful())
                .isFalse();
        assertThat(rows).isEmpty();
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "a.bin", new byte[] {1, 2}, "a.bin", null, false))
                                .successful())
                .isTrue();
        Files.write(root.resolve("101.bin"), new byte[] {9});
        assertThatThrownBy(() -> target.download(AgentTestFixtures.ACTOR, 101L))
                .hasMessageContaining("receipt mismatch");
        when(access.requireVisible(any(), any())).thenThrow(new SecurityException("revoked"));
        assertThatThrownBy(() -> target.download(AgentTestFixtures.ACTOR, 101L))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void officialDeliveryToolReadsRealFileBytesAndReturnsVerifiedDraftReceipt() throws Exception {
        Path workspace = Files.createDirectory(root.resolve("workspace"));
        byte[] original = {0, 1, 2, (byte) 255};
        Files.write(workspace.resolve("output.bin"), original);
        var filesystem =
                new io.agentscope.harness.agent.filesystem.local.LocalFilesystem(workspace);
        var tool = new io.agentscope.harness.agent.tool.ArtifactDeliveryTool(filesystem, target);
        String result = tool.deliverArtifact(ctx, "output.bin", "output.bin", null, false);
        assertThat(result).doesNotStartWith("Error:");
        assertThat(result).contains("101");
        assertThat(target.download(AgentTestFixtures.ACTOR, 101L)).containsExactly(original);
        assertThat(rows.get(101L).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void textReceiptPrefixAndForgedRowsDoNotGrantBinaryIdentity() throws Exception {
        byte[] text =
                (ProjectAgentArtifactDelivery.FILE_RECEIPT + "{\"versionId\":102,\"binary\":true}")
                        .getBytes(StandardCharsets.UTF_8);
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "text.md", text, "text.md", null, false))
                                .successful())
                .isTrue();
        var artifactAccess =
                new ProjectAgentArtifactAccess(
                        (actor, runId) -> {
                            if (!actor.id().equals(AgentTestFixtures.ACTOR.id())
                                    || !runId.equals(901L))
                                throw new SecurityException("Unbound artifact actor/run");
                            return target;
                        });
        assertThatCode(
                        () ->
                                artifactAccess.requireDocumentContent(
                                        AgentTestFixtures.ACTOR, 901L, rows.get(101L)))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                artifactAccess.requireDocumentContent(
                                        AgentTestFixtures.ACTOR, 902L, rows.get(101L)))
                .hasMessageContaining("run mismatch");
        var spoof = rows.get(101L).toBuilder().content("forged").build();
        assertThatThrownBy(() -> target.requireDocumentContent(spoof))
                .hasMessageContaining("version mismatch");
        assertThatThrownBy(
                        () ->
                                target.download(
                                        new org.ruoyi.ipd.security.IpdActor(
                                                999L, "other", "MEMBER", 1L),
                                        101L))
                .isInstanceOf(SecurityException.class);
        var metadata = root.resolve("101.receipt.json");
        String saved = Files.readString(metadata);
        Files.writeString(metadata, saved.replace("101", "102"));
        assertThatThrownBy(() -> target.download(AgentTestFixtures.ACTOR, 101L))
                .hasMessageContaining("receipt mismatch");
        var spoofContext = RuntimeContext.builder().userId("trusted-user").sessionId("901").build();
        assertThat(
                        target.deliver(
                                        spoofContext,
                                        new ArtifactDeliveryRequest(
                                                "spoof.bin",
                                                new byte[] {1},
                                                "spoof.bin",
                                                null,
                                                false))
                                .successful())
                .isFalse();
    }

    @Test
    void binaryOriginSurvivesLostBytesAndSidecarAndEventFailureRollsBack() throws Exception {
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "lost.bin",
                                                new byte[] {0, 2, (byte) 255},
                                                "lost.bin",
                                                null,
                                                false))
                                .successful())
                .isTrue();
        assertThat(events).hasSize(1);
        Files.delete(root.resolve("101.bin"));
        Files.delete(root.resolve("101.receipt.json"));
        assertThatThrownBy(() -> target.requireDocumentContent(rows.get(101L)))
                .hasMessageContaining("not document apply");
        assertThatThrownBy(() -> target.download(AgentTestFixtures.ACTOR, 101L))
                .hasMessageContaining("metadata unavailable");
        eventFail = true;
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "failed.bin",
                                                new byte[] {1},
                                                "failed.bin",
                                                null,
                                                false))
                                .successful())
                .isFalse();
        assertThat(rows).hasSize(1);
        assertThat(events).hasSize(1);
        assertThat(root.resolve("102.bin")).doesNotExist();
        assertThat(root.resolve("102.receipt.json")).doesNotExist();
    }

    @Test
    void tripleLossWrongVersionOriginAndUnattestedLegacyFailClosed() throws Exception {
        assertThat(
                        target.deliver(
                                        ctx,
                                        new ArtifactDeliveryRequest(
                                                "lost.bin",
                                                new byte[] {0, 1, (byte) 255},
                                                "lost.bin",
                                                null,
                                                false))
                                .successful())
                .isTrue();
        Files.delete(root.resolve("101.bin"));
        Files.delete(root.resolve("101.receipt.json"));
        String original = events.get(0).getPayload();
        events.get(0)
                .setPayload(original.replace("\"versionId\":\"101\"", "\"versionId\":\"102\""));
        assertThatThrownBy(() -> target.requireDocumentContent(rows.get(101L)))
                .hasMessageContaining("trusted origin unavailable");
        events.clear();
        assertThatThrownBy(() -> target.requireDocumentContent(rows.get(101L)))
                .hasMessageContaining("trusted origin unavailable");
        var legacy =
                rows.get(101L).toBuilder()
                        .id(202L)
                        .artifactId("legacy-text")
                        .content(ProjectAgentArtifactDelivery.FILE_RECEIPT + "normal text")
                        .build();
        legacy.setContentSha256(
                java.util.HexFormat.of()
                        .formatHex(
                                java.security.MessageDigest.getInstance("SHA-256")
                                        .digest(
                                                legacy.getContent()
                                                        .getBytes(StandardCharsets.UTF_8))));
        rows.put(202L, legacy);
        assertThatThrownBy(() -> target.requireDocumentContent(legacy))
                .hasMessageContaining("trusted origin unavailable");
        var event = new IpdAgentRunEvent();
        event.setSeq(1L);
        event.setRunId(901L);
        event.setTenantId(AgentTestFixtures.TENANT);
        event.setEventType("ARTIFACT");
        var payload = new java.util.LinkedHashMap<String, Object>();
        payload.put("artifactId", "legacy-text");
        payload.put("versionId", "202");
        payload.put("version", 1);
        payload.put("contentHash", legacy.getContentSha256());
        event.setPayload(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(payload));
        events.add(event);
        assertThatCode(() -> target.requireDocumentContent(legacy)).doesNotThrowAnyException();
        event.setPayload(event.getPayload().replace("legacy-text", "forged-other"));
        assertThatThrownBy(() -> target.requireDocumentContent(legacy))
                .hasMessageContaining("legacy text origin mismatch");
    }
}
