package org.ruoyi.chat.kernel;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.InMemoryAgentStateStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端（真实内核 + 真实官方 execute 工具 + 真实 Docker 沙箱 + 假模型）：
 * 聊天内核现行放行策略下，官方执行类工具必须真的执行，并把结果交还给模型。
 * 前置：本机已有沙箱镜像 python:3.13-alpine（仓库测试不联网拉取）。
 */
@Tag("dev")
class AgentScopeChatKernelToolPassThroughTest {
    private static final String MARK = "CHAT_KERNEL_PASSTHROUGH_OK";
    @TempDir Path workspace;

    @Test void officialExecuteToolRunsInSandboxAndResultReachesModel() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> toolResultsSeenByModel = new CopyOnWriteArrayList<>();
        Model model = new Model() {
            public String getModelName() { return "stub-passthrough"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                // 官方记忆抽取等会独立调用同一模型；只处理带本测试标记的主对话调用。
                boolean primary = messages.stream().anyMatch(m -> m.getTextContent().contains("PASSTHROUGH_PRIMARY"));
                if (!primary) {
                    return Flux.just(new ChatResponse("aux", List.of(TextBlock.builder().text("aux").build()),
                        null, Map.of(), "stop"));
                }
                boolean sawResult = false;
                for (Msg m : messages) {
                    for (ContentBlock block : m.getContent()) {
                        if (block instanceof ToolResultBlock result) {
                            sawResult = true;
                            for (ContentBlock out : result.getOutput()) {
                                if (out instanceof TextBlock text) { toolResultsSeenByModel.add(text.getText()); }
                            }
                        }
                    }
                }
                if (!sawResult && calls.getAndIncrement() == 0) {
                    ToolUseBlock call = ToolUseBlock.builder().id("exec-1").name("execute")
                        .input(Map.of("command", "echo " + MARK)).content("{\"command\":\"echo " + MARK + "\"}").build();
                    return Flux.just(new ChatResponse("r1", List.of(call), null, Map.of(), "tool_calls"));
                }
                return Flux.just(new ChatResponse("r2", List.of(TextBlock.builder().text("done").build()),
                    null, Map.of(), "stop"));
            }
        };
        List<String> toolFrames = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        KernelChatSink sink = new KernelChatSink() {
            public void onContent(String value) { }
            public void onReasoning(String value) { }
            public void onMcpTool(String tool, String status, String result) { toolFrames.add(tool + ":" + status + ":" + result); }
            public void onError(String code, String message) { errors.add(code); done.countDown(); }
            public void onComplete() { done.countDown(); }
        };
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(model, "stub:passthrough",
                () -> new InMemoryAgentStateStore(), workspace)) {
            kernel.stream("P", "U", "passthrough", "S", "run echo please", "PASSTHROUGH_PRIMARY", sink);
            assertTrue(done.await(120, TimeUnit.SECONDS), "kernel stream did not finish");
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(toolFrames.stream().anyMatch(f -> f.startsWith("execute:allowed")),
            "execute must be adjudicated allowed, frames=" + toolFrames);
        assertTrue(toolResultsSeenByModel.stream().anyMatch(t -> t.contains(MARK)),
            "model must receive the real sandbox output, seen=" + toolResultsSeenByModel);
    }
}
