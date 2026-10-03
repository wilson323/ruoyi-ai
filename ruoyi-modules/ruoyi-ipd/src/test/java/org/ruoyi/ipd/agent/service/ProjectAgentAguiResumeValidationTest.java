package org.ruoyi.ipd.agent.service;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.model.RunAgentInput;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

@Tag("dev")
class ProjectAgentAguiResumeValidationTest {
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private final ProjectAgentAguiResumeValidation.Binding binding =
        new ProjectAgentAguiResumeValidation.Binding("t", "r", "person", 3, 7,
            Map.of("i", new AguiEvent.Interrupt("i", "tool", null, "call", null,
                "2026-10-02T13:00:00Z", Map.of())));

    private RunAgentInput input(String thread, String run, List<AguiResume> responses) {
        return new RunAgentInput(thread, run, List.of(), List.of(), List.of(), Map.of(), Map.of(), responses);
    }
    private List<AguiResume> validate(RunAgentInput input) {
        return ProjectAgentAguiResumeValidation.validate(input, binding, "person", 3, 7, NOW);
    }
    @Test void denialIsResolvedAndPreserved() {
        var response = new AguiResume("i", "resolved", Map.of("approved", false));
        assertEquals(List.of(response), validate(input("t", "r", List.of(response))));
    }
    @Test void foreignOwnerThreadRunAndStaleCheckpointAreRejected() {
        var in = input("t", "r", List.of(new AguiResume("i", "cancelled", null)));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentAguiResumeValidation.validate(in, binding, "other", 3, 7, NOW));
        assertThrows(IllegalArgumentException.class, () -> validate(input("other", "r", in.getResume())));
        assertThrows(IllegalArgumentException.class, () -> validate(input("t", "other", in.getResume())));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentAguiResumeValidation.validate(in, binding, "person", 4, 7, NOW));
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentAguiResumeValidation.validate(in, binding, "person", 3, 8, NOW));
    }
    @Test void unknownDuplicateMissingAndInvalidStatusAreRejected() {
        var valid = new AguiResume("i", "cancelled", null);
        assertThrows(IllegalArgumentException.class, () -> validate(input("t", "r", List.of(valid, valid))));
        assertThrows(IllegalArgumentException.class, () -> validate(input("t", "r", List.of())));
        assertThrows(IllegalArgumentException.class, () -> validate(input("t", "r", List.of(new AguiResume("unknown", "resolved", "x")))));
        assertThrows(IllegalArgumentException.class, () -> validate(input("t", "r", List.of(new AguiResume("i", "yes", null)))));
        assertThrows(IllegalArgumentException.class, () -> validate(input("t", "r", List.of(new AguiResume("i", "cancelled", "x")))));
    }
    @Test void expirationBoundaryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ProjectAgentAguiResumeValidation.validate(
            input("t", "r", List.of(new AguiResume("i", "cancelled", null))), binding,
            "person", 3, 7, Instant.parse("2026-10-02T13:00:00Z")));
    }
}
