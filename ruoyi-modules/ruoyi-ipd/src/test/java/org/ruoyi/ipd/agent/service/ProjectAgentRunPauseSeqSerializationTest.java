package org.ruoyi.ipd.agent.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.config.IpdPrimaryBeansConfig;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** Use the actual primary HTTP mapper: cursor sequence numbers are not string IDs. */
@Tag("dev")
class ProjectAgentRunPauseSeqSerializationTest {
    @Test void actualMapperKeepsPauseSequenceNumericAndIdsAsStrings() throws Exception {
        var mapper = new IpdPrimaryBeansConfig().objectMapper();
        var run = new ProjectAgentViews.Run("9007199254740993", "9007199254740995", "agent", "WAITING_APPROVAL", null,
            null, null, "now", null, List.of(), 5_000_000_052L);
        var json = mapper.readTree(mapper.writeValueAsString(run));
        assertTrue(json.path("pauseSeq").isIntegralNumber(), "pauseSeq must match numeric event.seq in the browser");
        assertEquals(5_000_000_052L, json.path("pauseSeq").asLong());
        assertTrue(json.path("runId").isTextual()); assertEquals(run.runId(), json.path("runId").asText());
        assertTrue(json.path("projectId").isTextual()); assertEquals(run.projectId(), json.path("projectId").asText());
        var event = mapper.readTree(mapper.writeValueAsString(new ProjectAgentViews.Event(5_000_000_052L, "STEP", null, "now")));
        assertEquals(event.path("seq"), json.path("pauseSeq"));
    }
    @Test void noPauseRemainsAbsentRatherThanFakeZero() throws Exception {
        var mapper = new IpdPrimaryBeansConfig().objectMapper();
        var run = new ProjectAgentViews.Run("run", "project", "agent", "RUNNING", null, null, null, "now", null);
        assertFalse(mapper.readTree(mapper.writeValueAsString(run)).has("pauseSeq"));
    }
}
