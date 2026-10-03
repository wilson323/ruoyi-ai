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

    FrozenProjectAgentSkills(List<LoadedSkill> frozen) {
        LinkedHashMap<String, AgentSkill> snapshot = new LinkedHashMap<>();
        try (ClasspathSkillRepository source = new ClasspathSkillRepository(ROOT)) {
            for (LoadedSkill locked : frozen == null ? List.<LoadedSkill>of() : frozen) {
                if (locked == null || locked.name() == null || !locked.name().matches("[A-Za-z0-9_-]+")
                    || locked.sha256() == null || !locked.sha256().matches("[a-fA-F0-9]{64}")
                    || locked.version() == null || locked.version().isBlank() || snapshot.containsKey(locked.name())) {
                    throw new IllegalArgumentException("Invalid frozen skill identity");
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
