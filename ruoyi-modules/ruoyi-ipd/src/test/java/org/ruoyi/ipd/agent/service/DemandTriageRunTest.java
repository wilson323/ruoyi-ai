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
    @Mock org.ruoyi.ipd.mapper.RequirementMapper requirements;
    @Mock org.ruoyi.ipd.agent.store.AgentRunStore store;

    @BeforeAll
    static void tableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProjectMember.class);
        TableInfoHelper.initTableInfo(assistant, Requirement.class);
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

        when(runs.create(any(), any(), any())).thenReturn(new org.ruoyi.ipd.agent.vo.ProjectAgentViews.RunStatus("99", "RUNNING"));
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

    @Test
    void selectsPurposePackAmongManyAndIgnoresDisabledModels() {
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).build());
        when(models.statuses()).thenReturn(List.of(
            new ProjectAgentModelCatalog.ModelStatus("1", "备用", false, "未启用"),
            new ProjectAgentModelCatalog.ModelStatus("2", "主模型", true, null)));
        wireActor();
        when(runs.create(any(), any(), any())).thenReturn(new org.ruoyi.ipd.agent.vo.ProjectAgentViews.RunStatus("99", "RUNNING"));
        var dedicated = new CapabilityManifest.PackEntry("demand-triage", "v2", "需求分拣", "", List.of(), List.of(), List.of(), List.of());
        var other = new CapabilityManifest.PackEntry("development", "v1", "开发", "", List.of(), List.of(), List.of(), List.of());
        var packs = new java.util.ArrayList<>(onePack().packs()); packs.add(dedicated); packs.add(other);
        triage(new CapabilityManifest(1, List.of(), List.of(), packs)).attach(Requirement.builder().id(42L).title("门禁").build());
        var req = ArgumentCaptor.forClass(AgentRunCreateReq.class);
        verify(runs).create(any(), eq(9190003L), req.capture());
        assertThat(req.getValue().capabilityPackCode()).isEqualTo("demand-triage");
        assertThat(req.getValue().modelConfigId()).isEqualTo("2");
    }

    @Test
    void configurationFailureIsPersistedAndCanBeReadWithoutModelCall() {
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).build());
        when(models.statuses()).thenReturn(List.of());
        when(requirements.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(1);
        DemandTriageRun triage = triage(onePack()); triage.withRecovery(requirements, store);
        triage.attach(Requirement.builder().id(42L).title("门禁").build());
        var update = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(requirements, org.mockito.Mockito.times(2)).update(org.mockito.ArgumentMatchers.isNull(), update.capture());
        update.getAllValues().get(1).getSqlSet();
        assertThat(update.getAllValues().get(1).getParamNameValuePairs().values()).contains("FAILED", "请启用唯一的主对话模型后重试分拣");
        var view = triage.status(Requirement.builder().id(42L).triageStatus("FAILED")
            .triageError("请启用唯一的主对话模型后重试分拣").build());
        assertThat(view.retryable()).isTrue(); assertThat(view.message()).contains("唯一的主对话模型");
        verify(runs, never()).create(any(), any(), any());
    }

    @Test
    void failedAttemptGetsNewIdempotencyKeyAndWaitingApprovalCannotBeRetried() {
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).build());
        when(models.statuses()).thenReturn(List.of(new ProjectAgentModelCatalog.ModelStatus("2", "主模型", true, null)));
        wireActor();
        Requirement failed = Requirement.builder().id(42L).title("门禁").triageAttempt(1).triageStatus("FAILED").build();
        when(requirements.selectById(42L)).thenReturn(failed);
        when(requirements.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(1);
        when(runs.create(any(), any(), any())).thenReturn(new org.ruoyi.ipd.agent.vo.ProjectAgentViews.RunStatus("99", "RUNNING"));
        DemandTriageRun triage = triage(onePack()); triage.withRecovery(requirements, store);
        triage.retry(42L);
        var req = ArgumentCaptor.forClass(AgentRunCreateReq.class);
        verify(runs).create(any(), any(), req.capture());
        assertThat(req.getValue().idempotencyKey()).isEqualTo("demand-42-retry-2");
        var run = org.ruoyi.ipd.agent.domain.IpdAgentRun.builder().id(99L).status("WAITING_APPROVAL").build();
        when(store.findRun(99L)).thenReturn(java.util.Optional.of(run));
        Requirement waiting = Requirement.builder().id(43L).triageRunId(99L).build();
        when(requirements.selectById(43L)).thenReturn(waiting);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> triage.retry(43L))
            .isInstanceOfSatisfying(org.ruoyi.ipd.common.IpdBusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT))
            .hasMessageContaining("等待负责人确认");
        assertThat(triage.status(waiting).retryable()).isFalse();
    }

    @Test
    void compareAndSetLoserDoesNotCreateAnotherRun() {
        when(requirements.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class))).thenReturn(0);
        DemandTriageRun triage = triage(onePack()); triage.withRecovery(requirements, store);
        triage.attach(Requirement.builder().id(42L).title("门禁").build());
        verify(runs, never()).create(any(), any(), any());
    }

    @Test
    void lostCreationReceiptReadsOriginalPersonAndAttemptWithoutCreatingSecondRun() {
        Requirement lost = Requirement.builder().id(42L).triageAttempt(2).triageStatus("STARTING").triagePersonId(900101L).build();
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).tenantId("000000").build());
        var run = org.ruoyi.ipd.agent.domain.IpdAgentRun.builder().id(99L).status("RUNNING").build();
        when(store.findByIdempotencyKey("000000", 900101L, "demand-42-retry-2"))
            .thenReturn(java.util.Optional.of(run));
        when(store.findRun(99L)).thenReturn(java.util.Optional.of(run));
        DemandTriageRun triage = triage(onePack()); triage.withRecovery(requirements, store);
        assertThat(triage.status(lost).runId()).isEqualTo("99");
        assertThat(triage.status(lost).retryable()).isFalse();
        verify(runs, never()).create(any(), any(), any());
    }

    @Test
    void dynamicPackCatalogIsReadWithTriageProjectsActualTenant() {
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).tenantId("trusted-tenant").build());
        when(models.statuses()).thenReturn(List.of(new ProjectAgentModelCatalog.ModelStatus("2", "主模型", true, null)));
        wireActor();
        var catalog = org.mockito.Mockito.mock(org.ruoyi.ipd.agent.catalog.ProjectAgentPackCatalog.class);
        when(catalog.packs("trusted-tenant")).thenReturn(onePack().packs());
        when(runs.create(any(), any(), any())).thenReturn(new org.ruoyi.ipd.agent.vo.ProjectAgentViews.RunStatus("99", "RUNNING"));
        DemandTriageRun triage = triage(new CapabilityManifest(1, List.of(), List.of(), List.of()));
        triage.withPackCatalog(catalog);
        triage.attach(Requirement.builder().id(42L).title("门禁").build());
        verify(catalog).packs("trusted-tenant");
        verify(runs).create(any(), any(), any());
    }

    @Test
    void lostSuccessfulReceiptRemainsRecoverableInsteadOfStartingFreshAttempt() {
        when(projects.selectById(9190003L)).thenReturn(Project.builder().id(9190003L).build());
        when(models.statuses()).thenReturn(List.of(new ProjectAgentModelCatalog.ModelStatus("2", "主模型", true, null)));
        wireActor();
        when(runs.create(any(), any(), any())).thenReturn(new org.ruoyi.ipd.agent.vo.ProjectAgentViews.RunStatus("99", "RUNNING"));
        when(requirements.update(org.mockito.ArgumentMatchers.isNull(), any(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class)))
            .thenReturn(1, 0);
        DemandTriageRun triage = triage(onePack()); triage.withRecovery(requirements, store);
        triage.attach(Requirement.builder().id(42L).title("门禁").build());
        var updates = ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(requirements, org.mockito.Mockito.times(2)).update(org.mockito.ArgumentMatchers.isNull(), updates.capture());
        for (var update : updates.getAllValues()) {
            update.getSqlSet();
            assertThat(update.getParamNameValuePairs().values()).doesNotContain("FAILED");
        }
        verify(runs).create(any(), any(), any());
    }

    private void wireActor() {
        when(members.selectList(any())).thenReturn(List.of(ProjectMember.builder().id(1L).personId(900101L).build()));
        Person person = new Person(); person.setId(900101L); person.setPersonType("SUPER_ADMIN");
        when(persons.selectById(900101L)).thenReturn(person);
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
