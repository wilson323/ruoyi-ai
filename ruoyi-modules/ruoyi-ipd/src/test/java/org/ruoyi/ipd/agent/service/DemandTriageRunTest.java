package org.ruoyi.ipd.agent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.agent.catalog.CapabilityManifest;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.dto.AgentRunCreateReq;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("dev")
class DemandTriageRunTest {

    @Mock ProjectMapper projects;
    @Mock ProjectMemberMapper members;
    @Mock PersonMapper persons;
    @Mock ProjectAgentModelCatalog models;
    @Mock ProjectAgentRunService runs;

    @BeforeAll
    static void tableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
    }

    @Test
    void attachesRequirementToExistingCreateOnTriageProject() {
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).build());
        when(models.statuses()).thenReturn(List.of(
            new ProjectAgentModelCatalog.ModelStatus("2104885081318375426", "MiniMax-M3", true, null)));
        when(members.selectList(any())).thenReturn(List.of(ProjectMember.builder()
            .id(9190004L).projectId(9190003L).personId(900101L).build()));
        Person person = new Person();
        person.setId(900101L);
        person.setName("管理员");
        person.setPersonType("SUPER_ADMIN");
        person.setGroupId(1L);
        when(persons.selectById(900101L)).thenReturn(person);

        Requirement requirement = Requirement.builder().id(42L).title("门禁").content("要刷脸通行").build();
        triage(onePack()).attach(requirement);

        ArgumentCaptor<AgentRunCreateReq> req = ArgumentCaptor.forClass(AgentRunCreateReq.class);
        ArgumentCaptor<IpdActor> actor = ArgumentCaptor.forClass(IpdActor.class);
        verify(runs).create(actor.capture(), eq(9190003L), req.capture());
        assertThat(actor.getValue().id()).isEqualTo(900101L);
        assertThat(req.getValue().requirementId()).isEqualTo("42");
        assertThat(req.getValue().actionCode()).isNull();
        assertThat(req.getValue().capabilityPackCode()).isEqualTo("market-research");
        assertThat(req.getValue().modelConfigId()).isEqualTo("2104885081318375426");
        assertThat(req.getValue().toolIds()).containsExactly("project_knowledge_search");
        assertThat(req.getValue().idempotencyKey()).isEqualTo("demand-42");
        assertThat(req.getValue().productLineId()).isNull();
    }

    @Test
    void skipsWhenAvailableModelsAreNotExactlyOne() {
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).build());
        when(models.statuses()).thenReturn(List.of(
            new ProjectAgentModelCatalog.ModelStatus("1", "a", true, null),
            new ProjectAgentModelCatalog.ModelStatus("2", "b", true, null)));
        triage(onePack()).attach(Requirement.builder().id(42L).title("门禁").content("要刷脸通行").build());
        verify(runs, never()).create(any(), any(), any());
    }

    @Test
    void skipsWhenDemandAlreadyHasProductLine() {
        triage(onePack()).attach(Requirement.builder().id(42L).productLineId(19L).title("门禁").build());
        verify(projects, never()).selectById(any());
        verify(runs, never()).create(any(), any(), any());
    }

    private DemandTriageRun triage(CapabilityManifest manifest) {
        return new DemandTriageRun(projects, members, persons, models, manifest, runs);
    }

    private static CapabilityManifest onePack() {
        return new CapabilityManifest(1, List.of(), List.of(), List.of(
            new CapabilityManifest.PackEntry("market-research", "v1", "市场调研", "目录",
                List.of(), List.of("C02"), List.of(), List.of("project_knowledge_search"))));
    }
}
