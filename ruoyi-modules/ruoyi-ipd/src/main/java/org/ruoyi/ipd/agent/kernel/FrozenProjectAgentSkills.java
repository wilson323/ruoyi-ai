package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.SkillFilter;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.core.skill.repository.ClasspathSkillRepository;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.*;

/** Selected-only SDK repository snapshot; the frozen run remains the authorization authority. */
final class FrozenProjectAgentSkills implements AgentSkillRepository {
    private static final String ROOT = "ipd-skills";
    private final Map<String, AgentSkill> selected;
    private final List<ProjectAgentSkillBundle> reviewedBundles;

    FrozenProjectAgentSkills(List<LoadedSkill> frozen) {
        LinkedHashMap<String, AgentSkill> snapshot = new LinkedHashMap<>();
        List<ProjectAgentSkillBundle> bundles = new ArrayList<>();
        try (ClasspathSkillRepository source = new ClasspathSkillRepository(ROOT)) {
            for (LoadedSkill locked : frozen == null ? List.<LoadedSkill>of() : frozen) {
                if (locked == null || locked.name() == null || !locked.name().matches("[A-Za-z0-9_-]+")
                    || locked.sha256() == null || !locked.sha256().matches("[a-fA-F0-9]{64}")
                    || locked.version() == null || locked.version().isBlank() || snapshot.containsKey(locked.name())) {
                    throw new IllegalArgumentException("Invalid frozen skill identity");
                }
                if (locked.bundle() != null) {
                    var bundle = locked.bundle();
                    if (!locked.name().equals(bundle.skillName()) || !locked.sha256().equals(bundle.sha256()))
                        throw new IllegalStateException("Reviewed skill snapshot mismatch");
                    AgentSkill approved = io.agentscope.core.skill.util.SkillUtil.createFrom(bundle.markdown(), bundle.textResources())
                        .toBuilder().putMetadata("version", locked.version()).build();
                    if (!locked.name().equals(approved.getName()) || !locked.content().equals(approved.getSkillContent()))
                        throw new IllegalStateException("Reviewed skill body changed");
                    bundles.add(bundle);
                    snapshot.put(locked.name(), approved);
                    continue;
                }
                byte[] raw;
                try (InputStream input = getClass().getClassLoader().getResourceAsStream(ROOT + "/" + locked.name() + "/SKILL.md")) {
                    if (input == null) { throw new IllegalStateException("Selected skill resource is missing"); }
                    raw = input.readAllBytes();
                }
                if (!sha(raw).equalsIgnoreCase(locked.sha256())) { throw new IllegalStateException("Selected skill hash changed"); }
                AgentSkill skill = source.getSkill(locked.name());
                if (skill == null || !locked.name().equals(skill.getName())
                    || !locked.version().equals(Objects.toString(skill.getMetadataValue("version"), ""))
                    || locked.content() == null || locked.content().isBlank()
                    || !locked.content().equals(skill.getSkillContent())) {
                    throw new IllegalStateException("Selected skill version or body changed");
                }
                // Official repositories retain resource metadata for progressive discovery/loading.
                // Publication remains a business decision; this snapshot is never a promotion sink.
                snapshot.put(locked.name(), skill);
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Selected skill repository is unavailable", failure);
        }
        selected = Collections.unmodifiableMap(snapshot);
        reviewedBundles = List.copyOf(bundles);
    }
    /** Install only already approved immutable bundles after official sandbox lifecycle activation. */
    void installInto(io.agentscope.harness.agent.filesystem.AbstractFilesystem filesystem,
                     io.agentscope.core.agent.RuntimeContext context) {
        Objects.requireNonNull(filesystem); Objects.requireNonNull(context);
        for (var bundle : reviewedBundles) {
            String root = "skills/" + bundle.skillName();
            ProjectAgentSkillBundle.rejectSymbolicLinks(filesystem, context, root);
            for (var file : bundle.files()) {
                String path = root + "/" + file.path();
                var current = filesystem.downloadFiles(context, List.of(path));
                if (current.size() == 1 && current.get(0).isSuccess()
                    && Arrays.equals(current.get(0).content(), file.bytes())) continue;
                var uploaded = filesystem.uploadFiles(context, List.of(Map.entry(path, file.bytes())));
                if (uploaded.size() != 1 || !uploaded.get(0).isSuccess())
                    throw new IllegalStateException("Approved skill byte installation failed");
            }
            var actual = ProjectAgentSkillBundle.capture(filesystem, context, root, bundle.skillName());
            if (!actual.sha256().equals(bundle.sha256())) throw new IllegalStateException("Installed skill package differs from approval");
        }
    }
    /** Official skill filter prevents unapproved workspace drafts from becoming executable. */
    SkillFilter filter() { return SkillFilter.only(selected.keySet().toArray(String[]::new)); }
    @Override public AgentSkill getSkill(String name) { return selected.get(name); }
    @Override public List<String> getAllSkillNames() { return List.copyOf(selected.keySet()); }
    @Override public List<AgentSkill> getAllSkills() { return List.copyOf(selected.values()); }
    @Override public boolean skillExists(String name) { return selected.containsKey(name); }
    @Override public boolean save(List<AgentSkill> skills, boolean force) { throw new UnsupportedOperationException("Frozen skills are read only"); }
    @Override public boolean delete(String name) { throw new UnsupportedOperationException("Frozen skills are read only"); }
    @Override public AgentSkillRepositoryInfo getRepositoryInfo() { return new AgentSkillRepositoryInfo("classpath-selected", ROOT, false); }
    @Override public String getSource() { return "classpath-selected_" + ROOT; }
    @Override public void setWriteable(boolean writable) { if (writable) { throw new UnsupportedOperationException("Frozen skills are read only"); } }
    @Override public boolean isWriteable() { return false; }
    private static String sha(byte[] raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
