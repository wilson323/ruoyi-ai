package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.ruoyi.chat.kernel.KernelModelRequest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentSkillCatalog.LoadedSkill;
import org.ruoyi.ipd.agent.support.AgentTestFixtures;
import org.ruoyi.ipd.service.AiDocEmbeddingService.RetrievalContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("dev")
class FrozenProjectAgentSkillsTest {
    @TempDir Path workspace;
    private LoadedSkill selected() {
        return AgentTestFixtures.skillCatalog(AgentTestFixtures.manifest()).load("competitor-analysis-ipd").orElseThrow();
    }
    private ProjectAgentRunSpec spec(LoadedSkill skill) {
        return new ProjectAgentRunSpec(101L, 20260929L, "tenant-a", 11L, "C02", "竞品分析",
            List.of(skill), List.of(), new KernelModelRequest("fixture", "openai", null, null), Duration.ofSeconds(3));
    }
    @Test void selectedRepositoryIsImmutableAndDoesNotExposeUnselectedSkills() {
        FrozenProjectAgentSkills repository = new FrozenProjectAgentSkills(List.of(selected()));
        assertThat(repository.getAllSkills()).extracting(io.agentscope.core.skill.AgentSkill::getName)
            .containsExactly("competitor-analysis-ipd");
        assertThat(repository.getSkill("project-archive-ipd")).isNull();
        assertThat(repository.skillExists("project-archive-ipd")).isFalse();
        assertThat(repository.isWriteable()).isFalse();
        assertThatThrownBy(() -> repository.setWriteable(true)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> repository.delete("competitor-analysis-ipd")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void officialFilterAllowsOnlyTheFrozenOwnerApprovedRunSelection() {
        FrozenProjectAgentSkills repository = new FrozenProjectAgentSkills(List.of(selected()));
        assertThat(repository.filter().isAllowed("competitor-analysis-ipd")).isTrue();
        assertThat(repository.filter().isAllowed("unapproved-draft")).isFalse();
        assertThat(repository.getAllSkillNames()).containsExactly("competitor-analysis-ipd");
    }
    @Test void frozenHashVersionAndBodyMismatchFailBeforeAnyModelCall() {
        LoadedSkill skill = selected();
        Model model = mock(Model.class);
        var kernel = new AgentScopeProjectAgentKernel(new ProjectAgentModelAssembler((key, context) -> model),
            (project, type, query) -> new RetrievalContext(0, 0, ""), workspace, 2);
        for (LoadedSkill invalid : List.of(
            new LoadedSkill(skill.name(), skill.version(), "0".repeat(64), skill.content()),
            new LoadedSkill(skill.name(), "unexpected-version", skill.sha256(), skill.content()),
            new LoadedSkill(skill.name(), skill.version(), skill.sha256(), skill.content() + "changed"))) {
            assertThatThrownBy(() -> kernel.buildAgent(spec(invalid), model, mock(ProjectAgentEventSink.class, org.mockito.Mockito.CALLS_REAL_METHODS)))
                .isInstanceOf(IllegalStateException.class);
        }
        verify(model, never()).stream(any(), any(), any());
    }
}
