package org.ruoyi.chat.kernel;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.middleware.AgentTraceMiddleware;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("dev")
class OfficialAgentTraceLoggingTest {
    @Test void actualOfficialTraceRetainsPhasesAndLevelsWithoutRawContentOrSecrets() {
        LoggerContext logging = (LoggerContext) LoggerFactory.getILoggerFactory();
        var nativeLogger = logging.getLogger(OfficialAgentTraceLogging.NATIVE_LOGGER);
        Level oldLevel = nativeLogger.getLevel();
        var capture = new ListAppender<ILoggingEvent>();
        capture.setContext(logging); capture.start();
        var safeLogger = logging.getLogger(OfficialAgentTraceLogging.SAFE_LOGGER);
        safeLogger.addAppender(capture);
        try {
            OfficialAgentTraceLogging.install();
            OfficialAgentTraceLogging.install();
            nativeLogger.setLevel(Level.DEBUG);
            Agent agent = mock(Agent.class);
            when(agent.getName()).thenReturn("ACTOR_SECRET_CANARY");
            RuntimeContext context = RuntimeContext.builder().userId("user").sessionId("session").build();
            context.setAgentState(AgentState.builder().context(List.of(Msg.builder().role(MsgRole.ASSISTANT)
                .textContent("ANSWER_SECRET_CANARY").build())).build());
            var trace = new AgentTraceMiddleware();
            var user = Msg.builder().role(MsgRole.USER).textContent("INPUT_SECRET_CANARY").build();
            trace.onAgent(agent, context, new AgentInput(List.of(user)), input -> Flux.empty()).blockLast();
            trace.onReasoning(agent, context, new ReasoningInput(List.of(user), List.of(), null), input ->
                Flux.just(new TextBlockDeltaEvent("reply", "text", "STREAM_SECRET_CANARY"))).blockLast();
            String emitted = capture.list.stream().map(ILoggingEvent::getFormattedMessage)
                .reduce("", (first, second) -> first + "\n" + second);
            assertThat(emitted).contains("PRE_CALL", "POST_CALL", "PRE_REASONING", "POST_REASONING", "elapsedMillis=");
            assertThat(emitted).doesNotContain("ACTOR_SECRET_CANARY", "INPUT_SECRET_CANARY",
                "ANSWER_SECRET_CANARY", "STREAM_SECRET_CANARY");
            assertThat(capture.list).extracting(ILoggingEvent::getLevel).contains(Level.INFO, Level.DEBUG);
            assertThat(capture.list).allSatisfy(event -> {
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getMDCPropertyMap()).isEmpty();
                assertThat(event.getLoggerName()).isEqualTo(OfficialAgentTraceLogging.SAFE_LOGGER);
            });
        } finally {
            nativeLogger.setLevel(oldLevel);
            safeLogger.detachAppender(capture); capture.stop();
        }
    }
}
