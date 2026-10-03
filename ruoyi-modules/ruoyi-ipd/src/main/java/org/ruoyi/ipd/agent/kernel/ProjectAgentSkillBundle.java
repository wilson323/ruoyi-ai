package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable complete byte package; STEP events are its durable authority. */
public record ProjectAgentSkillBundle(String skillName, String sha256, List<File> files) {
    public record File(String path, String content, String sha256, String encoding) {
        public byte[] bytes() {
            byte[] bytes = "base64".equals(encoding) ? Base64.getDecoder().decode(content)
                : "utf-8".equals(encoding) ? content.getBytes(StandardCharsets.UTF_8)
                : invalidEncoding();
            if (!ProjectAgentSkillCatalog.sha256Hex(bytes).equals(sha256))
                throw new IllegalStateException("Skill file hash mismatch");
            return bytes;
        }
        private static byte[] invalidEncoding() { throw new IllegalArgumentException("Unsupported skill encoding"); }
    }
    public ProjectAgentSkillBundle {
        if (skillName == null || !skillName.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid skill name");
        files = files == null ? List.of() : List.copyOf(files);
        if (files.isEmpty() || files.size() > 128) throw new IllegalArgumentException("Invalid skill file count");
        Set<String> paths = new HashSet<>(); long total = 0;
        for (File file : files) {
            String path = file.path();
            if (path == null || path.startsWith("/") || path.contains("\\") || path.contains("\u0000")
                || Arrays.stream(path.split("/", -1)).anyMatch(s -> s.isBlank() || s.equals(".") || s.equals("..")))
                throw new IllegalArgumentException("Invalid skill resource path");
            if (!paths.add(path)) throw new IllegalArgumentException("Duplicate skill resource");
            total += file.bytes().length;
            if (total > 2_000_000) throw new IllegalArgumentException("Skill package exceeds limit");
        }
        if (!paths.contains("SKILL.md")) throw new IllegalArgumentException("Skill body is missing");
        if (!digest(files).equals(sha256)) throw new IllegalArgumentException("Skill package hash mismatch");
    }
    public static ProjectAgentSkillBundle capture(AbstractFilesystem filesystem, RuntimeContext ctx, String root, String name) {
        rejectSymbolicLinks(filesystem, ctx, root);
        List<File> files = new ArrayList<>();
        var listed = filesystem.glob(ctx, "**/*", root);
        if (!listed.isSuccess() || listed.matches() == null) throw new IllegalStateException("Skill resource listing failed");
        long total = 0;
        for (var entry : listed.matches()) {
            String path = entry.path().replace('\\', '/').replaceAll("/+$", "");
            if (path.startsWith("/") && !root.startsWith("/")) path = path.substring(1);
            if (!path.startsWith(root + "/")) throw new IllegalStateException("Skill resource escaped root");
            String relative = path.substring(root.length() + 1);
            if (entry.isDirectory()) continue;
            if (files.size() >= 128 || entry.size() > 2_000_000) throw new IllegalStateException("Skill package exceeds limit");
            var downloaded = filesystem.downloadFiles(ctx, List.of(path));
            if (downloaded.size() != 1 || !downloaded.get(0).isSuccess() || downloaded.get(0).content() == null)
                throw new IllegalStateException("Skill byte download failed");
            byte[] bytes = downloaded.get(0).content(); total += bytes.length;
            if (total > 2_000_000) throw new IllegalStateException("Skill package exceeds limit");
            String content; String encoding;
            try {
                content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (content.indexOf('\0') >= 0) throw new java.nio.charset.CharacterCodingException();
                encoding = "utf-8";
            } catch (java.nio.charset.CharacterCodingException invalid) {
                content = Base64.getEncoder().encodeToString(bytes); encoding = "base64";
            }
            files.add(new File(relative, content, ProjectAgentSkillCatalog.sha256Hex(bytes), encoding));
        }
        return new ProjectAgentSkillBundle(name, digest(files), files);
    }
    public static void rejectSymbolicLinks(AbstractFilesystem filesystem, RuntimeContext context, String root) {
        if (filesystem instanceof io.agentscope.harness.agent.filesystem.local.LocalFilesystem local) {
            var base = local.getCwd().toAbsolutePath().normalize();
            var relative = root.startsWith("/") ? root.substring(1) : root;
            var directory = base.resolve(relative).normalize();
            if (!directory.startsWith(base)) throw new SecurityException("Skill path escaped filesystem");
            for (var current = directory; current != null && current.startsWith(base); current = current.getParent())
                if (java.nio.file.Files.isSymbolicLink(current)) throw new SecurityException("Skill symbolic link rejected");
            if (java.nio.file.Files.exists(directory)) try (var paths = java.nio.file.Files.walk(directory)) {
                if (paths.anyMatch(java.nio.file.Files::isSymbolicLink)) throw new SecurityException("Skill symbolic link rejected");
            } catch (java.io.IOException failure) { throw new IllegalStateException("Skill path inspection failed"); }
        } else if (filesystem instanceof io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem sandbox) {
            String quoted = io.agentscope.harness.agent.filesystem.util.FilesystemUtils.shellQuote(root);
            var result = sandbox.execute(context, "p=" + quoted
                + "; while [ \"$p\" != . ] && [ \"$p\" != / ]; do if [ -L \"$p\" ]; then echo LINK; fi; "
                + "p=$(dirname \"$p\"); done; if [ -e " + quoted + " ]; then find " + quoted + " -type l -print; fi", 10);
            if (!result.isSuccess()) throw new IllegalStateException("Skill path inspection failed");
            if (result.output() != null && !result.output().isBlank()) throw new SecurityException("Skill symbolic link rejected");
        } else throw new IllegalStateException("Skill filesystem cannot verify symbolic links");
    }
    public static String digest(List<File> files) {
        StringBuilder canonical = new StringBuilder();
        files.stream().sorted(Comparator.comparing(File::path)).forEach(file -> canonical
            .append(file.path().length()).append(':').append(file.path()).append(':')
            .append(ProjectAgentSkillCatalog.sha256Hex(file.bytes())).append('\n'));
        return ProjectAgentSkillCatalog.sha256Hex(canonical.toString());
    }
    public String markdown() {
        File md = files.stream().filter(f -> f.path().equals("SKILL.md")).findFirst().orElseThrow();
        if (!md.encoding().equals("utf-8")) throw new IllegalArgumentException("SKILL.md must be UTF-8 text");
        return md.content();
    }
    public Map<String, String> textResources() {
        Map<String, String> resources = new LinkedHashMap<>();
        files.stream().filter(f -> !f.path().equals("SKILL.md") && f.encoding().equals("utf-8"))
            .forEach(f -> resources.put(f.path(), f.content()));
        return Map.copyOf(resources);
    }
}
