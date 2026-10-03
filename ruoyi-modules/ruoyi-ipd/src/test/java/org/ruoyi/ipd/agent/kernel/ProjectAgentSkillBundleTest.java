package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class ProjectAgentSkillBundleTest {
    @TempDir Path workspace;
    private final RuntimeContext ctx = RuntimeContext.builder().userId("user").sessionId("run").build();
    @Test void recursiveTextAndBinaryBytesAreFrozenAndPublishedWithoutCorruption() {
        var fs = new LocalFilesystem(workspace.resolve("source"), true, 16);
        byte[] binary = new byte[]{0, (byte) 255, 42};
        String markdown = "---\nname: approved-skill\ndescription: Format verified observations\n---\nUse verified observations.";
        fs.uploadFiles(ctx, List.of(Map.entry("skills/_drafts/approved-skill/SKILL.md", markdown.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Map.entry("skills/_drafts/approved-skill/references/nested/note.txt", "source note".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Map.entry("skills/_drafts/approved-skill/assets/example.bin", binary)));
        var bundle = ProjectAgentSkillBundle.capture(fs, ctx, "skills/_drafts/approved-skill", "approved-skill");
        assertThat(bundle.files()).hasSize(3);
        assertThat(bundle.files().stream().filter(f -> f.path().endsWith(".bin")).findFirst().orElseThrow().bytes()).isEqualTo(binary);
        var result = new ProjectAgentSkillPublisher().publish(workspace.resolve("publication"), ctx, bundle, "7");
        assertThat(result.published()).as(result.scanSummary()).isTrue();
        var published = new LocalFilesystem(workspace.resolve("publication/.skill-publication/" + bundle.sha256()), true, 16);
        assertThat(published.downloadFiles(ctx, List.of("skills/approved-skill/assets/example.bin")).get(0).content()).isEqualTo(binary);
        assertThat(ProjectAgentSkillBundle.capture(published, ctx, "skills/approved-skill", "approved-skill").sha256()).isEqualTo(bundle.sha256());
    }
    @Test void approvedFrozenSnapshotInstallsBinaryAndTextIntoOfficialFilesystem() {
        String md = "---\nname: reviewed\ndescription: Verified notes\n---\nUse verified notes.";
        byte[] binary = new byte[]{0, (byte) 255, 42};
        var body = new ProjectAgentSkillBundle.File("SKILL.md", md,
            org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex(md), "utf-8");
        var asset = new ProjectAgentSkillBundle.File("assets/example.bin", java.util.Base64.getEncoder().encodeToString(binary),
            org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex(binary), "base64");
        var bundle = new ProjectAgentSkillBundle("reviewed", ProjectAgentSkillBundle.digest(List.of(body, asset)), List.of(body, asset));
        var parsed = io.agentscope.core.skill.util.SkillUtil.createFrom(md, Map.of());
        var loaded = new org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill("reviewed", "review-v1", bundle.sha256(), parsed.getSkillContent(), bundle);
        var frozen = new FrozenProjectAgentSkills(List.of(loaded));
        var fs = new LocalFilesystem(workspace.resolve("runtime"), true, 16);
        frozen.installInto(fs, ctx);
        assertThat(fs.downloadFiles(ctx, List.of("skills/reviewed/assets/example.bin")).get(0).content()).isEqualTo(binary);
        assertThat(frozen.getSkill("reviewed").getSkillContent()).isEqualTo(parsed.getSkillContent());
        assertThat(ProjectAgentSkillBundle.capture(fs, ctx, "skills/reviewed", "reviewed").sha256()).isEqualTo(bundle.sha256());
    }

    @Test void publicationDoesNotFollowPreexistingResourceOrParentSymlink() throws Exception {
        String md = "---\nname: safe\ndescription: Verified notes\n---\nUse verified notes.";
        var body = new ProjectAgentSkillBundle.File("SKILL.md", md,
            org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex(md), "utf-8");
        var bundle = new ProjectAgentSkillBundle("safe", ProjectAgentSkillBundle.digest(List.of(body)), List.of(body));
        Path target = workspace.resolve("publication");
        Path draft = java.nio.file.Files.createDirectories(target.resolve(".skill-publication/" + bundle.sha256() + "/skills/_drafts/safe"));
        Path outside = java.nio.file.Files.writeString(workspace.resolve("outside.txt"), "do not overwrite");
        java.nio.file.Files.createSymbolicLink(draft.resolve("SKILL.md"), outside);
        assertThatThrownBy(() -> new ProjectAgentSkillPublisher().publish(target, ctx, bundle, "7"))
            .isInstanceOf(SecurityException.class).hasMessageContaining("symbolic link");
        assertThat(java.nio.file.Files.readString(outside)).isEqualTo("do not overwrite");
        Path otherTarget = java.nio.file.Files.createDirectories(workspace.resolve("another"));
        java.nio.file.Files.createSymbolicLink(otherTarget.resolve(".skill-publication"), workspace.resolve("publication/.skill-publication"));
        assertThatThrownBy(() -> new ProjectAgentSkillPublisher().publish(otherTarget, ctx, bundle, "7"))
            .isInstanceOf(SecurityException.class).hasMessageContaining("symbolic link");
    }

    @Test void editedResourceCannotKeepApprovedDigest() {
        var file = new ProjectAgentSkillBundle.File("SKILL.md", "safe", org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex("safe"), "utf-8");
        String hash = ProjectAgentSkillBundle.digest(List.of(file));
        var changed = new ProjectAgentSkillBundle.File("SKILL.md", "changed", org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex("changed"), "utf-8");
        assertThatThrownBy(() -> new ProjectAgentSkillBundle("safe", hash, List.of(changed))).hasMessageContaining("package hash");
    }
    @Test void traversalAndDuplicateResourcesAreRejected() {
        String sha = org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.sha256Hex("safe");
        var traversal = new ProjectAgentSkillBundle.File("../SKILL.md", "safe", sha, "utf-8");
        assertThatThrownBy(() -> new ProjectAgentSkillBundle("safe", sha, List.of(traversal))).hasMessageContaining("path");
        var file = new ProjectAgentSkillBundle.File("SKILL.md", "safe", sha, "utf-8");
        assertThatThrownBy(() -> new ProjectAgentSkillBundle("safe", sha, List.of(file, file))).hasMessageContaining("Duplicate");
    }
}
