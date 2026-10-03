package org.ruoyi.ipd.agent.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.chat.kernel.KernelModelRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentModelFingerprintTest {
    private final KernelModelRequest original = new KernelModelRequest("MiniMax-M3", "minimax",
        "fixture-secret", "https://api.minimax.cn/v1", 0.2, 4000, 60000);

    @Test void everyEffectiveFieldAndFallbackAreFrozen() {
        var frozen = ProjectAgentModelFingerprint.capture(original, null);
        assertDoesNotThrow(() -> ProjectAgentModelFingerprint.requireUnchanged(frozen, original, null));
        for (KernelModelRequest changed : List.of(
            new KernelModelRequest("MiniMax-M2.7", "minimax", "fixture-secret", original.apiHost(), 0.2, 4000, 60000),
            new KernelModelRequest(original.modelName(), "custom_api", "fixture-secret", original.apiHost(), 0.2, 4000, 60000),
            new KernelModelRequest(original.modelName(), "minimax", "rotated-fixture", original.apiHost(), 0.2, 4000, 60000),
            new KernelModelRequest(original.modelName(), "minimax", "fixture-secret", "https://api.minimax.io/v1", 0.2, 4000, 60000),
            new KernelModelRequest(original.modelName(), "minimax", "fixture-secret", original.apiHost(), 0.3, 4000, 60000),
            new KernelModelRequest(original.modelName(), "minimax", "fixture-secret", original.apiHost(), 0.2, 5000, 60000),
            new KernelModelRequest(original.modelName(), "minimax", "fixture-secret", original.apiHost(), 0.2, 4000, 70000))) {
            assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(frozen, changed, null));
        }
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(frozen, original, original));
        var withFallback = ProjectAgentModelFingerprint.capture(original, original);
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(withFallback, original, null));
    }

    @Test void historicalMissingOrTamperedEvidenceFailsExplicitly() {
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(null, original, null));
        var frozen = ProjectAgentModelFingerprint.capture(original, null);
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(
            new ProjectAgentModelFingerprint.Snapshot("unknown", frozen.salt(), frozen.digest()), original, null));
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(frozen, null, null));
    }

    @Test void storedEvidenceContainsNoPlaintextAndUsesIndependentSalt() {
        var first = ProjectAgentModelFingerprint.capture(original, null);
        var second = ProjectAgentModelFingerprint.capture(original, null);
        assertNotEquals(first.salt(), second.salt());
        assertNotEquals(first.digest(), second.digest());
        assertFalse(first.toString().contains(original.apiKey()));
        assertFalse(first.toString().contains(original.apiHost()));
        var nullKey = new KernelModelRequest("M", "P", null, "H");
        var emptyKey = new KernelModelRequest("M", "P", "", "H");
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(
            ProjectAgentModelFingerprint.capture(nullKey, null), emptyKey, null));
    }
    @Test void allRunInputCopiesPreserveFrozenModelSelection() {
        var spec = new org.ruoyi.ipd.agent.kernel.ProjectAgentRunSpec(1L, 2L, "t", 3L,
            null, "question", List.of(), List.of(), original, java.time.Duration.ofSeconds(30))
            .withFrozenModels(original, original);
        var copied = spec.withCatalog("catalog").withProjectFacts("facts").withAguiInput(null)
            .withExecutionToolIds(List.of()).withServerResumeMessages(List.of()).withServerChildResumes(List.of());
        assertEquals(spec.frozenModels(), copied.frozenModels());
    }

    @Test void persistedSnapshotCopiesAndJsonRoundTripPreserveFingerprint() throws Exception {
        var fingerprint = ProjectAgentModelFingerprint.capture(original, null);
        var snapshot = new org.ruoyi.ipd.agent.vo.ProjectAgentViews.ConfigSnapshot("pack", "v1", "1", List.of(), List.of())
            .withModelFingerprint(fingerprint).withModelIdentityVersion(1, "202").withExecutionToolIds(List.of()).withServerFrontendTools(List.of());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var recovered = json.readValue(json.writeValueAsString(snapshot), org.ruoyi.ipd.agent.vo.ProjectAgentViews.ConfigSnapshot.class);
        assertEquals(fingerprint, recovered.modelFingerprint());
        assertEquals(1, recovered.modelIdentityVersion());
        assertEquals("202", recovered.fallbackModelConfigId());
        assertNull(json.readValue("{\"modelConfigId\":\"1\"}", org.ruoyi.ipd.agent.vo.ProjectAgentViews.ConfigSnapshot.class).modelFingerprint());
    }

    @Test void configurationIdsAndParametersAreFrozenTogether() {
        var frozen = ProjectAgentModelFingerprint.capture(101L, original, 202L, original);
        assertDoesNotThrow(() -> ProjectAgentModelFingerprint.requireUnchanged(frozen, 101L, original, 202L, original));
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(frozen, 101L, original, 203L, original));
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(frozen, 102L, original, 202L, original));
        assertThrows(IllegalStateException.class, () -> ProjectAgentModelFingerprint.requireUnchanged(
            ProjectAgentModelFingerprint.capture(original, original), 101L, original, 202L, original));
        assertFalse(frozen.toString().contains(original.apiKey()));
    }
}
