package org.ruoyi.agent;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.ruoyi.config.agent.SkillsPathResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** 原生只读目录；只注入选定技能，不安装脚本、shell或未选定工具。 */
public final class NativeChatSkills {
    private NativeChatSkills() { }

    public static List<AgentSkill> available() {
        Path root = SkillsPathResolver.resolveSkillsPath();
        if (!Files.isDirectory(root)) { return List.of(); }
        return new FileSystemSkillRepository(root, false, "chat-skills", true).getAllSkills();
    }

    public static String selectedPrompt(List<String> names) {
        if (names == null || names.isEmpty()) { return ""; }
        return selectedPrompt(SkillsPathResolver.resolveSkillsPath(), names);
    }

    static String selectedPrompt(Path root, List<String> names) {
        if (Files.isSymbolicLink(root)) { throw new IllegalArgumentException("技能目录来源无效"); }
        FileSystemSkillRepository repository = new FileSystemSkillRepository(root, false, "chat-skills", true);
        StringBuilder prompt = new StringBuilder();
        for (String name : names.stream().distinct().toList()) {
            if (name == null || !name.matches("[A-Za-z0-9_-]+") || Files.isSymbolicLink(root.resolve(name))
                || Files.isSymbolicLink(root.resolve(name).resolve("SKILL.md"))) {
                throw new IllegalArgumentException("技能名称或来源无效");
            }
            AgentSkill skill = repository.getSkill(name);
            if (skill == null || skill.getSkillContent() == null || skill.getSkillContent().isBlank()) {
                throw new IllegalStateException("选定技能不可用");
            }
            prompt.append("\n【本次选定技能：").append(skill.getName()).append("｜sha256：")
                .append(sha256(skill.getSkillContent())).append("】\n").append(skill.getSkillContent());
        }
        prompt.append("\n技能只提供任务规约；执行只能使用本次已登记工具。没有脚本或所需工具时明确说明不可执行，不得声称已生成文件。\n");
        return prompt.toString();
    }

    private static String sha256(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException("SHA-256不可用", unavailable); }
    }
}
