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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端：聊天内核放行策略下，官方 write_file 写入沙箱后，read_file 能读回同一内容。
 * 证明写入真实发生，而不是只被判定为 allowed。前置：本机已有镜像 python:3.13-alpine。
 */
@Tag("dev")
class AgentScopeChatKernelFileToolsPassThroughTest {
    private static final String MARK = "CHAT_KERNEL_FILE_PASSTHROUGH_OK";
    @TempDir Path workspace;

    @Test void officialWriteFileThenReadFileRoundTripsThroughSandbox() throws Exception {
        List<String> resultsSeenByModel = new CopyOnWriteArrayList<>();
        Model model = new Model() {
            public String getModelName() { return "stub-file-passthrough"; }
            public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                if (messages.stream().noneMatch(m -> m.getTextContent().contains("FILE_PRIMARY"))) {
                    return Flux.just(new ChatResponse("aux", List.of(TextBlock.builder().text("aux").build()),
                        null, Map.of(), "stop"));
                }
                List<String> results = new ArrayList<>();
                for (Msg m : messages) {
                    for (ContentBlock block : m.getContent()) {
                        if (block instanceof ToolResultBlock result) {
                            StringBuilder text = new StringBuilder();
                            for (ContentBlock out : result.getOutput()) {
                                if (out instanceof TextBlock t) { text.append(t.getText()); }
                            }
                            results.add(text.toString());
                        }
                    }
                }
                resultsSeenByModel.clear();
                resultsSeenByModel.addAll(results);
                // 按已收到的工具结果个数推进脚本：0 → 写文件；1 → 读文件；2 → 结束。
                if (results.isEmpty()) {
                    Map<String, Object> input = Map.of("path", "note.txt", "content", MARK);
                    return Flux.just(new ChatResponse("w", List.of(ToolUseBlock.builder().id("w-1")
                        .name("write_file").input(input)
                        .content("{\"path\":\"note.txt\",\"content\":\"" + MARK + "\"}").build()),
                        null, Map.of(), "tool_calls"));
                }
                if (results.size() == 1) {
                    Map<String, Object> input = Map.of("path", "note.txt");
                    return Flux.just(new ChatResponse("r", List.of(ToolUseBlock.builder().id("r-1")
                        .name("read_file").input(input).content("{\"path\":\"note.txt\"}").build()),
                        null, Map.of(), "tool_calls"));
                }
                return Flux.just(new ChatResponse("done", List.of(TextBlock.builder().text("done").build()),
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
        try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(model, "stub:file",
                () -> new InMemoryAgentStateStore(), workspace)) {
            kernel.stream("P", "U", "filetools", "S", "write then read please", "FILE_PRIMARY", sink);
            assertTrue(done.await(180, TimeUnit.SECONDS), "kernel stream did not finish");
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(toolFrames.stream().anyMatch(f -> f.startsWith("write_file:allowed")), "frames=" + toolFrames);
        assertTrue(toolFrames.stream().anyMatch(f -> f.startsWith("read_file:allowed")), "frames=" + toolFrames);
        assertEquals(2, resultsSeenByModel.size(), "seen=" + resultsSeenByModel);
        assertTrue(resultsSeenByModel.get(1).contains(MARK),
            "read_file must return what write_file wrote, seen=" + resultsSeenByModel);
    }
}
