package org.ruoyi.ipd.agent.kernel;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class ProjectAgentWorkspaceTest {
    @TempDir Path root;

    @Test void separatesUsersAndKeepsBusinessAuthorityOutsideWorkingMemory() throws Exception {
        Path first = ProjectAgentWorkspace.prepare(root, "101", "11", "project-agent");
        Path second = ProjectAgentWorkspace.prepare(root, "101", "12", "project-agent");
        assertThat(first).isNotEqualTo(second);
        assertThat(Files.readString(first.resolve("AGENTS.md")))
            .contains("never business facts or authorization", "owner approves publication");
        Files.writeString(first.resolve("MEMORY.md"), "private preference");
        assertThat(second.resolve("MEMORY.md")).doesNotExist();
    }

    @Test void rejectsRootAndAncestorSymlinkBeforeWorkspaceCreation() throws Exception {
        Path target = Files.createDirectory(root.resolve("target"));
        Path link = Files.createSymbolicLink(root.resolve("link"), target);
        assertThatThrownBy(() -> ProjectAgentWorkspace.prepare(link, "101", "11", "agent"))
            .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ProjectAgentWorkspace.prepare(link.resolve("nested"), "101", "11", "agent"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(target.resolve("101")).doesNotExist();
    }

    @Test void rejectsUnsafeIdentityAndExistingPersonaSymlink() throws Exception {
        assertThatThrownBy(() -> ProjectAgentWorkspace.prepare(root, "../escape", "11", "agent"))
            .isInstanceOf(IllegalArgumentException.class);
        Path workspace = ProjectAgentWorkspace.prepare(root, "101", "11", "agent");
        Path other = Files.writeString(root.resolve("other.md"), "do not load");
        // Use a second, otherwise empty workspace to avoid deleting any fixture file.
        Path unsafe = Files.createDirectories(root.resolve("101/12/agent"));
        Files.createSymbolicLink(unsafe.resolve("AGENTS.md"), other);
        assertThatThrownBy(() -> ProjectAgentWorkspace.prepare(root, "101", "12", "agent"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(workspace.resolve("AGENTS.md")).isRegularFile();
    }
}
