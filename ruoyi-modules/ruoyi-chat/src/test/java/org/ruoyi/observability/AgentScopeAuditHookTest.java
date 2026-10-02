package org.ruoyi.observability;

import io.agentscope.core.hook.*;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("dev")
class AgentScopeAuditHookTest {
    @TempDir Path workspace;

    @Test
    void nativeBuilderReallyInvokesDiagnosticHookWithoutChangingResponse() {
        AtomicInteger calls = new AtomicInteger();
        AgentScopeAuditHook hook = new AgentScopeAuditHook("test") {
            @Override public <T extends HookEvent> Mono<T> onEvent(T event) {
                calls.incrementAndGet();
                return super.onEvent(event).doOnNext(returned -> assertThat(returned).isSameAs(event));
            }
        };
        Model model = new Model() {
            public String getModelName() { return "stub:audit"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.just(new ChatResponse("fixture", List.of(TextBlock.builder().text("fixture answer").build()),
                    null, Map.of(), "stop"));
            }
        };
        try (HarnessAgent agent = HarnessAgent.builder().name("audit-fixture").model(model).hook(hook)
            .toolkit(new Toolkit()).workspace(workspace).stateStore(new InMemoryAgentStateStore())
            .disableFilesystemTools().disableShellTool().disableMemoryTools().disableMemoryHooks()
            .disableTranscript().disableSubagents().disableDynamicSubagents().disableDynamicSkills()
            .disableDefaultWorkspaceSkills().skillsEnabled(false).disableToolsConfig().enableAgentTracingLog(false)
            .build()) {
            assertThat(agent.streamEvents(Msg.builder().role(MsgRole.USER).textContent("private fixture prompt").build(),
                RuntimeContext.builder().userId("fixture-user").sessionId("fixture-session").build())
                .collectList().block(Duration.ofSeconds(5))).isNotEmpty();
            assertThat(calls.get()).isPositive();
        }
    }

    @Test
    void diagnosticErrorsKeepIdentityAndNeverLogErrorMessage() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(AgentScopeAuditHook.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start(); logger.addAppender(appender);
        try {
            AgentScopeAuditHook hook = new AgentScopeAuditHook("test");
            ErrorEvent error = new ErrorEvent(mock(io.agentscope.core.agent.Agent.class),
                new IllegalStateException("private prompt api-key fixture"));
            assertThat(hook.onEvent(error).block()).isSameAs(error);
            // SDK构造禁止null；使用边界替身验证诊断仍不会反伤执行。
            ErrorEvent empty = mock(ErrorEvent.class);
            assertThat(hook.onEvent(empty).block()).isSameAs(empty);
            assertThat(appender.list).hasSize(2);
            assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain("private prompt", "api-key", "fixture"));
        } finally { logger.detachAppender(appender); appender.stop(); }
    }
}
