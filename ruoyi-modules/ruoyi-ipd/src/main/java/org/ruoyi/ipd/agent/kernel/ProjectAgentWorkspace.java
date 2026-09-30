package org.ruoyi.ipd.agent.kernel;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 项目智能体工作区（HarnessAgent 必需的 workspace + AGENTS.md）。
 *
 * <p>分桶 project × user × agent 与四维隔离键同源；逐段拒绝符号链接与非法段
 * （空、"."、含 ':' / '/' / '\\' / ".."），防路径穿越。W1 关闭全部文件/Shell 工具，
 * 工作区只承载人格文件，不承载项目资料。
 */
public final class ProjectAgentWorkspace {

    private ProjectAgentWorkspace() {
    }

    /**
     * 创建（或复用）工作区并确保 AGENTS.md 为常规文件。
     *
     * @param root 工作区根
     * @param projectId 项目段
     * @param userId 用户段
     * @param agentId 智能体段
     * @return 工作区目录
     * @throws IOException 文件系统错误
     * @throws IllegalArgumentException 非法段（fail-closed）
     * @throws IllegalStateException 发现符号链接或非目录段
     */
    public static Path prepare(Path root, String projectId, String userId, String agentId) throws IOException {
        Path workspace = root.resolve(segment("projectId", projectId))
            .resolve(segment("userId", userId))
            .resolve(segment("agentId", agentId));
        Files.createDirectories(root);
        Path current = root;
        for (Path part : root.relativize(workspace)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("workspace symbolic link rejected");
            }
            try {
                Files.createDirectory(current);
            } catch (FileAlreadyExistsException existing) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException("workspace segment must be a directory", existing);
                }
            }
        }
        Path agentsMd = workspace.resolve("AGENTS.md");
        if (!Files.exists(agentsMd, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.writeString(agentsMd, "# " + agentId + "\n\nIPD project agent (read-only research).\n",
                    StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException concurrentCreate) {
                // 并发运行已创建同一工作区的人格文件。
            }
        }
        if (!Files.isRegularFile(agentsMd, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("workspace AGENTS.md must be a regular file");
        }
        return workspace;
    }

    private static String segment(String name, String value) {
        if (value == null || value.isBlank() || value.equals(".")
            || value.indexOf(':') >= 0 || value.indexOf('/') >= 0
            || value.indexOf('\\') >= 0 || value.contains("..")) {
            throw new IllegalArgumentException("project agent workspace " + name + " segment rejected");
        }
        return value;
    }
}
