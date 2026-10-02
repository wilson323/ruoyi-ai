package org.ruoyi.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import static org.assertj.core.api.Assertions.*;

@Tag("dev")
class NativeChatSkillsTest {
    @TempDir Path root;

    @Test
    void onlySelectedSkillIsReadThroughNativeRepository() throws Exception {
        Files.createDirectories(root.resolve("docx"));
        Files.writeString(root.resolve("docx/SKILL.md"),
            "---\nname: docx\ndescription: document skill\n---\n# Steps\nUse supplied facts.\n");
        String prompt = NativeChatSkills.selectedPrompt(root, List.of("docx"));
        assertThat(prompt).contains("本次选定技能：docx", "sha256：", "Use supplied facts.", "本次已登记工具");
        assertThatThrownBy(() -> NativeChatSkills.selectedPrompt(root, List.of("../docx")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sqlRulesOnlyAppearWithSelectedSqlTool() {
        String normal = AgentChatPrompt.build("客服", List.of(), Set.of());
        String sql = AgentChatPrompt.build("客服", List.of(), Set.of("executeSql"));
        assertThat(normal).startsWith("客服").doesNotContain("需要查询数据库时");
        assertThat(sql).contains("需要查询数据库时");
        assertThat(normal).contains("仅在用户请求绘制图表时");
    }
}
