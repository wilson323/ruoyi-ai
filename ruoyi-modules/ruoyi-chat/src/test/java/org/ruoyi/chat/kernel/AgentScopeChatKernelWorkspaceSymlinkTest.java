package org.ruoyi.chat.kernel;

import io.agentscope.core.model.Model;
import io.agentscope.extensions.mysql.state.MysqlAgentStateStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@Tag("dev")
@DisplayName("C1 工作区预置符号链接不得把 Agent 写入或读取引出受信根")
class AgentScopeChatKernelWorkspaceSymlinkTest {

    @TempDir
    Path temp;

    @Test
    void rejectsSymlinkAtEveryWorkspaceLevelBeforeStateOrModelUse() throws IOException {
        for (int level = 0; level < 4; level++) {
            Path root = temp.resolve("root-" + level);
            Path outside = temp.resolve("outside-" + level);
            Files.createDirectories(root);
            Files.createDirectories(outside);
            Path link = switch (level) {
                case 0 -> root.resolve("project");
                case 1 -> Files.createDirectories(root.resolve("project")).resolve("person");
                case 2 -> Files.createDirectories(root.resolve("project/person")).resolve("agent");
                default -> Files.createDirectories(root.resolve("project/person/agent")).resolve("AGENTS.md");
            };
            Files.createSymbolicLink(link, outside);
            AtomicBoolean stateOpened = new AtomicBoolean();
            List<String> frames = new ArrayList<>();
            try (AgentScopeChatKernel kernel = new AgentScopeChatKernel(
                    mock(Model.class), "stub:symlink", () -> {
                        stateOpened.set(true);
                        return mock(MysqlAgentStateStore.class);
                    }, root)) {
                kernel.stream("project", "person", "agent", "session", "hi", null,
                        new KernelChatSink() {
                            @Override public void onContent(String delta) { frames.add("content"); }
                            @Override public void onReasoning(String delta) { frames.add("reasoning"); }
                            @Override public void onMcpTool(String name, String status, String result) {
                                frames.add("tool");
                            }
                            @Override public void onError(String code, String message) { frames.add("error"); }
                            @Override public void onComplete() { frames.add("complete"); }
                        });
            }
            assertEquals(List.of("error"), frames, "link level=" + level);
            assertFalse(stateOpened.get(), "拒绝符号链接应早于状态存储装配，level=" + level);
            try (var entries = Files.list(outside)) {
                assertTrue(entries.findAny().isEmpty(), "受信根外不得生成工作区文件，level=" + level);
            }
        }
    }
}
