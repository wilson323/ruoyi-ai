package org.ruoyi.chat.kernel;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1 身份三态接线负例（矩阵 #4）：非法段 fail-closed 拒绝且不触引擎/存储；
 * PoC 的匿名降级不适用于正式桥：空 userId 必须拒绝且不得触达存储。
 * 模型用 stub（只 mock 输出），状态存储用「毒化供应器」证明拒绝路径零触达。
 */
@Tag("dev")
@DisplayName("W1 内核桥身份三态：fail-closed 拒绝不触引擎")
class AgentScopeChatKernelIdentityTest {

    @Test
    @DisplayName("userId 含 ':'（复合键注入）→ SCOPE_REJECTED，状态存储零触达")
    void colonInUserIdIsRejectedBeforeEngine() {
        Probe probe = new Probe();

        probe.kernel.stream("P1", "U1:evil", "emp-a1", "S1", "hi", null, probe.sink);

        assertEquals(List.of("error:SCOPE_REJECTED"), probe.sink.log);
        assertFalse(probe.touched.get(), "fail-closed 拒绝路径不得触达状态存储");
    }

    @Test
    @DisplayName("sessionId 含 '..'（路径穿越）→ SCOPE_REJECTED，状态存储零触达")
    void traversalInSessionIsRejectedBeforeEngine() {
        Probe probe = new Probe();

        probe.kernel.stream("P1", "U1", "emp-a1", "../S1", "hi", null, probe.sink);

        assertEquals(List.of("error:SCOPE_REJECTED"), probe.sink.log);
        assertFalse(probe.touched.get(), "fail-closed 拒绝路径不得触达状态存储");
    }

    @Test
    @DisplayName("projectId 空白（必填段缺失）→ SCOPE_REJECTED，状态存储零触达")
    void blankProjectIsRejectedBeforeEngine() {
        Probe probe = new Probe();

        probe.kernel.stream(" ", "U1", "emp-a1", "S1", "hi", null, probe.sink);

        assertEquals(List.of("error:SCOPE_REJECTED"), probe.sink.log);
        assertFalse(probe.touched.get(), "fail-closed 拒绝路径不得触达状态存储");
    }

    @Test
    @DisplayName("空 userId 在正式桥被拒绝且不触达状态存储")
    void blankUserIdIsRejected() {
        Probe probe = new Probe();

        probe.kernel.stream("P1", "", "emp-a1", "S1", "hi", null, probe.sink);

        assertEquals(1, probe.sink.log.size());
        assertEquals(List.of("error:SCOPE_REJECTED"), probe.sink.log);
        assertFalse(probe.touched.get());
    }

    @Test
    @DisplayName("null userId 在正式桥被拒绝且不触达状态存储")
    void nullUserIdIsRejected() {
        Probe probe = new Probe();

        probe.kernel.stream("P1", null, "emp-a1", "S1", "hi", null, probe.sink);

        assertEquals(1, probe.sink.log.size());
        assertEquals(List.of("error:SCOPE_REJECTED"), probe.sink.log);
        assertFalse(probe.touched.get());
    }

    @Test
    @DisplayName("绝对路径 agentId 在正式桥被拒绝且不触达状态存储")
    void absoluteAgentIdIsRejected() {
        Probe probe = new Probe();
        probe.kernel.stream("P1", "U1", "/tmp/escape", "S1", "hi", null, probe.sink);
        assertEquals(List.of("error:SCOPE_REJECTED"), probe.sink.log);
        assertFalse(probe.touched.get());
    }

    @Test
    @DisplayName("多段 agentId 在正式桥被拒绝且不触达状态存储")
    void nestedAgentIdIsRejected() {
        Probe probe = new Probe();
        probe.kernel.stream("P1", "U1", "folder/employee", "S1", "hi", null, probe.sink);
        assertEquals(List.of("error:SCOPE_REJECTED"), probe.sink.log);
        assertFalse(probe.touched.get());
    }

    /** 测试装配：stub 模型 + 毒化存储供应器（触达即翻标记并失败）+ 记录型 sink。 */
    private static final class Probe {

        private final AtomicBoolean touched = new AtomicBoolean(false);
        private final RecordingSink sink = new RecordingSink();
        private final AgentScopeChatKernel kernel;

        private Probe() {
            Supplier<MysqlAgentStateStore> poisoned = () -> {
                touched.set(true);
                throw new IllegalStateException("poisoned state store");
            };
            kernel = new AgentScopeChatKernel(
                new SilentModel(), "stub:noop-model", poisoned, Path.of(System.getProperty("java.io.tmpdir")));
        }
    }

    /** stub 模型：只 mock 输出（合法三规约），此处不应被真正调用。 */
    private static final class SilentModel implements Model {

        @Override
        public String getModelName() {
            return "stub-noop-model";
        }

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
    }

    private static final class RecordingSink implements KernelChatSink {

        private final List<String> log = new ArrayList<>();

        @Override
        public void onContent(String delta) {
            log.add("content:" + delta);
        }

        @Override
        public void onReasoning(String delta) {
            log.add("reasoning:" + delta);
        }

        @Override
        public void onMcpTool(String toolName, String status, String result) {
            log.add("mcp_tool:" + toolName);
        }

        @Override
        public void onError(String code, String message) {
            log.add("error:" + code);
        }

        @Override
        public void onComplete() {
            log.add("complete");
        }
    }
}
