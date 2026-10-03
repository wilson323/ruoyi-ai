package org.ruoyi.ipd.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.ProjectAgentConstants;
import org.ruoyi.ipd.agent.catalog.ProjectAgentModelCatalog;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;
import org.ruoyi.ipd.agent.model.AgentEventType;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.ruoyi.ipd.agent.store.ArtifactVersionStore;
import org.ruoyi.ipd.agent.vo.ProjectAgentViews;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.ruoyi.ipd.mapper.AiModelConfigMapper;
import org.ruoyi.ipd.service.AiDocumentService;
import org.ruoyi.ipd.service.AiModelConfigService;
import org.ruoyi.ipd.service.IpdCopilotAccess;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.ACTOR;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.PROJECT_ID;
import static org.ruoyi.ipd.agent.support.AgentTestFixtures.TENANT;

/**
 * apply 回执：库内码仍是 GENERATED，用户可见主状态为待审核；索引未就绪不得标 READY。
 * docType 取动作目录归类，token 只累加本次运行的模型调用。
 */
@Tag("dev")
class ProjectAgentApplyStatusTest {

    @Test
    @DisplayName("apply 成功：主状态待审核，索引保持 NOT_INDEXED，文档类型为 MARKET_RESEARCH")
    void applyKeepsPendingReviewAndUnindexed() {
        Harness harness = new Harness();
        IpdAgentArtifactVersion draft = version(IpdAgentArtifactVersion.STATUS_DRAFT, null);
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1")).thenReturn(Optional.of(draft));
        when(harness.runs.listEvents(9L, 0L, ProjectAgentConstants.EVENTS_PAGE_LIMIT)).thenReturn(List.of(
            step(1L, "{\"kind\":\"MODEL_CALL\"}")));
        AiDocument doc = AiDocument.builder().id(8001L).status(AiDocumentService.STATUS_GENERATED).build();
        when(harness.documents.createGeneratedAuthorized(eq(ACTOR), eq(PROJECT_ID), eq("MARKET_RESEARCH"),
            eq("产物"), eq("正文"), isNull(), isNull(), isNull())).thenReturn(doc);
        when(harness.artifacts.markApplied(71L, 8001L)).thenReturn(true);

        ProjectAgentViews.ArtifactApply view = harness.service.applyArtifact(ACTOR, 9L, "art-1");

        assertThat(doc.getStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(view.documentStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(view.documentStatusLabel()).isEqualTo(AiDocumentService.LABEL_PENDING_REVIEW);
        assertThat(view.documentStatusLabel()).isNotEqualTo("已审核");
        assertThat(view.indexStatus()).isEqualTo(ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
        assertThat(view.indexStatus()).isNotEqualTo("READY");
        verify(harness.documents).createGeneratedAuthorized(eq(ACTOR), eq(PROJECT_ID), eq("MARKET_RESEARCH"),
            eq("产物"), eq("正文"), isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("已应用幂等：仍是待审核，索引不得改成 READY")
    void appliedReplayStaysUnindexed() {
        Harness harness = new Harness();
        IpdAgentArtifactVersion applied = version(IpdAgentArtifactVersion.STATUS_APPLIED, 8001L);
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1")).thenReturn(Optional.of(applied));
        when(harness.documents.statusOf(8001L)).thenReturn(Optional.of(AiDocumentService.STATUS_GENERATED));

        ProjectAgentViews.ArtifactApply view = harness.service.applyArtifact(ACTOR, 9L, "art-1");

        assertThat(view.documentStatusLabel()).isEqualTo("待审核");
        assertThat(view.indexStatus()).isEqualTo("NOT_INDEXED");
        assertThat(view.indexStatus()).isNotEqualTo("READY");
    }

    @Test
    @DisplayName("重复定档读回已拒绝，不把审核结果写成待审核")
    void appliedReplayReadsRejectedStatus() {
        Harness harness = new Harness();
        IpdAgentArtifactVersion applied = version(IpdAgentArtifactVersion.STATUS_APPLIED, 8001L);
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1")).thenReturn(Optional.of(applied));
        when(harness.documents.statusOf(8001L)).thenReturn(Optional.of(AiDocumentService.STATUS_REJECTED));

        ProjectAgentViews.ArtifactApply view = harness.service.applyArtifact(ACTOR, 9L, "art-1");

        assertThat(view.documentStatus()).isEqualTo(AiDocumentService.STATUS_REJECTED);
        assertThat(view.documentStatusLabel()).isEqualTo("已拒绝");
        assertThat(view.indexStatus()).isEqualTo(ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
        verify(harness.documents, never()).createGeneratedAuthorized(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重复定档找不到文档时拒绝，不写成待审核")
    void appliedReplayMissingDocumentIsConflict() {
        Harness harness = new Harness();
        IpdAgentArtifactVersion applied = version(IpdAgentArtifactVersion.STATUS_APPLIED, 8001L);
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1")).thenReturn(Optional.of(applied));
        when(harness.documents.statusOf(8001L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> harness.service.applyArtifact(ACTOR, 9L, "art-1"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("定档文档不存在");
    }

    @Test
    @DisplayName("未绑定动作时拒绝定档，不写文档")
    void blankActionRejected() {
        Harness harness = new Harness();
        harness.run.setActionCode(null);
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1"))
            .thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT, null)));

        assertThatThrownBy(() -> harness.service.applyArtifact(ACTOR, 9L, "art-1"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("未绑定动作");
        verify(harness.documents, never()).createGeneratedAuthorized(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("动作没有文档类型时拒绝定档，不把动作码写成 docType")
    void unmappedActionRejected() {
        Harness harness = new Harness();
        harness.run.setActionCode("C05");
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1"))
            .thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT, null)));

        assertThatThrownBy(() -> harness.service.applyArtifact(ACTOR, 9L, "art-1"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("没有可定档的文档类型");
        verify(harness.documents, never()).createGeneratedAuthorized(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("事件里的 inputTokens/outputTokens 翻页累加后传入定档")
    void applySumsModelCallTokens() {
        Harness harness = new Harness();
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1"))
            .thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT, null)));
        List<IpdAgentRunEvent> first = new ArrayList<>();
        for (long seq = 1; seq <= ProjectAgentConstants.EVENTS_PAGE_LIMIT; seq++) {
            String payload = seq == ProjectAgentConstants.EVENTS_PAGE_LIMIT
                ? "{\"kind\":\"MODEL_CALL\",\"inputTokens\":11,\"outputTokens\":7}"
                : "{\"kind\":\"MODEL_CALL\"}";
            first.add(step(seq, payload));
        }
        when(harness.runs.listEvents(9L, 0L, ProjectAgentConstants.EVENTS_PAGE_LIMIT)).thenReturn(first);
        when(harness.runs.listEvents(9L, (long) ProjectAgentConstants.EVENTS_PAGE_LIMIT,
            ProjectAgentConstants.EVENTS_PAGE_LIMIT)).thenReturn(List.of(
            step(201L, "{\"kind\":\"MODEL_CALL\",\"inputTokens\":4,\"outputTokens\":1}"),
            IpdAgentRunEvent.builder().runId(9L).tenantId(TENANT).seq(202L).eventType(AgentEventType.TOOL_CALL.name())
                .payload("{\"inputTokens\":100,\"outputTokens\":100}").build()));
        AiDocument doc = AiDocument.builder().id(8001L).status(AiDocumentService.STATUS_GENERATED).build();
        when(harness.documents.createGeneratedAuthorized(eq(ACTOR), eq(PROJECT_ID), eq("MARKET_RESEARCH"),
            eq("产物"), eq("正文"), isNull(), eq(15), eq(8))).thenReturn(doc);
        when(harness.artifacts.markApplied(71L, 8001L)).thenReturn(true);

        ProjectAgentViews.ArtifactApply view = harness.service.applyArtifact(ACTOR, 9L, "art-1");

        assertThat(view.documentStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(view.indexStatus()).isEqualTo(ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
        verify(harness.documents).createGeneratedAuthorized(eq(ACTOR), eq(PROJECT_ID), eq("MARKET_RESEARCH"),
            eq("产物"), eq("正文"), isNull(), eq(15), eq(8));
    }

    @Test
    @DisplayName("已停用配置仍用原 modelName，不换模型")
    void applyUsesConfiguredModelNameWhenInactive() {
        Harness harness = new Harness();
        harness.run.setModelConfigId(7L);
        AiModelConfigMapper mapper = mock(AiModelConfigMapper.class);
        when(mapper.selectById(7L)).thenReturn(AiModelConfig.builder()
            .id(7L).modelName("MiniMax-M3").isActive(false).build());
        ProjectAgentModelCatalog catalog = new ProjectAgentModelCatalog(mapper, mock(AiModelConfigService.class));
        assertThat(catalog.resolve(7L)).isEmpty();
        harness.service.setModelCatalog(catalog);
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1"))
            .thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT, null)));
        AiDocument doc = AiDocument.builder().id(8001L).status(AiDocumentService.STATUS_GENERATED).build();
        when(harness.documents.createGeneratedAuthorized(eq(ACTOR), eq(PROJECT_ID), eq("MARKET_RESEARCH"),
            eq("产物"), eq("正文"), eq("MiniMax-M3"), isNull(), isNull())).thenReturn(doc);
        when(harness.artifacts.markApplied(71L, 8001L)).thenReturn(true);

        harness.service.applyArtifact(ACTOR, 9L, "art-1");

        verify(harness.documents).createGeneratedAuthorized(eq(ACTOR), eq(PROJECT_ID), eq("MARKET_RESEARCH"),
            eq("产物"), eq("正文"), eq("MiniMax-M3"), isNull(), isNull());
    }

    @Test
    @DisplayName("占位已被占用时不建文档，回读已有 documentId，状态仍是待审核")
    void claimedSlotDoesNotCreateDocument() {
        Harness harness = new Harness();
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1")).thenReturn(
            Optional.of(version(IpdAgentArtifactVersion.STATUS_APPLIED, 9002L)));
        when(harness.documents.statusOf(9002L)).thenReturn(Optional.of(AiDocumentService.STATUS_GENERATED));
        AiDocument orphan = AiDocument.builder().id(8001L).status(AiDocumentService.STATUS_GENERATED).build();
        when(harness.documents.createGeneratedAuthorized(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(orphan);
        when(harness.artifacts.markApplied(71L, 8001L)).thenReturn(true);

        ProjectAgentViews.ArtifactApply view = harness.service.applyArtifact(ACTOR, 9L, "art-1");

        assertThat(view.documentId()).isEqualTo("9002");
        assertThat(view.documentStatus()).isEqualTo(AiDocumentService.STATUS_GENERATED);
        assertThat(view.documentStatusLabel()).isEqualTo(AiDocumentService.LABEL_PENDING_REVIEW);
        assertThat(view.indexStatus()).isEqualTo(ProjectAgentConstants.INDEX_STATUS_NOT_INDEXED);
        assertThat(view.indexStatus()).isNotEqualTo("READY");
        verify(harness.documents, never()).createGeneratedAuthorized(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("CAS 冲突通过事务代理回滚，而不是提交新文档后回放")
    void applyConflictRollsBackThroughSpringProxy() {
        Harness harness = new Harness();
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1"))
            .thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT, null)));
        when(harness.documents.createGeneratedAuthorized(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(AiDocument.builder().id(8001L).status(AiDocumentService.STATUS_GENERATED).build());
        when(harness.artifacts.markApplied(71L, 8001L)).thenReturn(false);
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        var source = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor(manager, source);
        var factory = new org.springframework.aop.framework.ProxyFactory(harness.service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(interceptor);
        ProjectAgentRunService proxied = (ProjectAgentRunService) factory.getProxy();

        assertThatThrownBy(() -> proxied.applyArtifact(ACTOR, 9L, "art-1"))
            .isInstanceOf(IpdBusinessException.class).hasMessageContaining("产物应用冲突");

        verify(manager).rollback(status);
        verify(manager, never()).commit(any());
        // 仅来源守卫读取一次原版本；CAS失败后不能再读版本回放或掩盖回滚。
        verify(harness.artifacts, org.mockito.Mockito.times(1)).findById(71L);
        var sequence = org.mockito.Mockito.inOrder(harness.artifacts, harness.documents);
        sequence.verify(harness.artifacts).findLatestForUpdate(TENANT, 9L, "art-1");
        sequence.verify(harness.artifacts).findById(71L);
        sequence.verify(harness.documents).createGeneratedAuthorized(any(), any(), any(), any(), any(), any(), any(), any());
        sequence.verify(harness.artifacts).markApplied(71L, 8001L);

    }

    @Test
    @DisplayName("文档创建失败不能进入产物关联")
    void documentFailureDoesNotMarkApplied() {
        Harness harness = new Harness();
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1"))
            .thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT, null)));
        when(harness.documents.createGeneratedAuthorized(any(), any(), any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalStateException("document insert failed"));

        assertThatThrownBy(() -> harness.service.applyArtifact(ACTOR, 9L, "art-1"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("document insert failed");
        verify(harness.artifacts, never()).markApplied(any(), any());
    }

    @Test
    @DisplayName("同一动作的链头已退回时，下一次运行带上这一版意见")
    void rejectedHeadCommentJoinsNextRunFacts() {
        AiDocumentService documents = mock(AiDocumentService.class);
        AiDocument root = document(1L, AiDocumentService.STATUS_REJECTED, "要改范围", 1000L);
        when(documents.listByProject(PROJECT_ID, null)).thenReturn(List.of(root));
        when(documents.history(1L)).thenReturn(List.of(root));

        String comment = ProjectAgentRunService.commentForAction(documents, PROJECT_ID, "MARKET_RESEARCH");
        String facts = ProjectAgentRunService.appendRejectedComment("项目：门禁\n", comment);

        assertThat(comment).isEqualTo("要改范围");
        assertThat(facts).contains("退回意见：要改范围");
        assertThat(facts).doesNotContain("REJECTED");
    }

    @Test
    @DisplayName("旧版已退回但链头已是新稿时，不把旧意见带进下一次运行")
    void olderRejectionDoesNotOverrideNewerHead() {
        AiDocumentService documents = mock(AiDocumentService.class);
        AiDocument root = document(1L, AiDocumentService.STATUS_REJECTED, "要改范围", 1000L);
        AiDocument head = document(2L, AiDocumentService.STATUS_GENERATED, null, 2000L);
        when(documents.listByProject(PROJECT_ID, null)).thenReturn(List.of(root));
        when(documents.history(1L)).thenReturn(List.of(root, head));

        assertThat(ProjectAgentRunService.commentForAction(documents, PROJECT_ID, "MARKET_RESEARCH")).isNull();
    }

    @Test
    @DisplayName("同一文档类型有多条链时，只认最新链头的退回意见")
    void latestRejectedHeadWins() {
        AiDocumentService documents = mock(AiDocumentService.class);
        AiDocument older = document(1L, AiDocumentService.STATUS_REJECTED, "旧意见", 1000L);
        AiDocument newerRoot = document(3L, AiDocumentService.STATUS_REJECTED, "新意见", 3000L);
        when(documents.listByProject(PROJECT_ID, null)).thenReturn(List.of(older, newerRoot));
        when(documents.history(1L)).thenReturn(List.of(older));
        when(documents.history(3L)).thenReturn(List.of(newerRoot));

        assertThat(ProjectAgentRunService.commentForAction(documents, PROJECT_ID, "MARKET_RESEARCH"))
            .isEqualTo("新意见");
    }

    @Test
    void explicitReworkAppliesToOriginalChainAndNeverCreatesIndependentRoot() {
        Harness h = new Harness();
        h.run.setConfigSnapshot("{\"previousRunId\":\"8\",\"targetDocumentId\":\"8000\",\"baseVersionId\":\"8000\"}");
        IpdAgentRun previous = IpdAgentRun.builder().id(8L).tenantId(TENANT).projectId(PROJECT_ID)
            .personId(ACTOR.id()).actionCode("C02").status("SUCCEEDED").build();
        when(h.runs.findRun(8L)).thenReturn(Optional.of(previous));
        when(h.artifacts.listByRunIds(List.of(8L))).thenReturn(List.of(version(IpdAgentArtifactVersion.STATUS_APPLIED,8000L)));
        when(h.artifacts.findLatestForUpdate(TENANT,9L,"art-1")).thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT,null)));
        AiDocument revised = AiDocument.builder().id(8001L).parentVersionId(8000L).versionNo(2).status("GENERATED").build();
        when(h.documents.reviseGeneratedAuthorized(eq(ACTOR),eq(PROJECT_ID),eq("MARKET_RESEARCH"),eq(8000L),eq(8000L),
            eq("产物"),eq("正文"),isNull(),isNull(),isNull())).thenReturn(revised);
        when(h.artifacts.markApplied(71L,8001L)).thenReturn(true);
        assertThat(h.service.applyArtifact(ACTOR,9L,"art-1").documentId()).isEqualTo("8001");
        verify(h.documents,never()).createGeneratedAuthorized(any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void foreignPreviousRunCannotBindReworkDocument() {
        Harness h = new Harness();
        h.run.setConfigSnapshot("{\"previousRunId\":\"8\",\"targetDocumentId\":\"8000\",\"baseVersionId\":\"8000\"}");
        when(h.runs.findRun(8L)).thenReturn(Optional.of(IpdAgentRun.builder().id(8L).tenantId(TENANT)
            .projectId(PROJECT_ID).personId(ACTOR.id()+1).actionCode("C02").status("SUCCEEDED").build()));
        when(h.artifacts.findLatestForUpdate(TENANT,9L,"art-1")).thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT,null)));
        assertThatThrownBy(() -> h.service.applyArtifact(ACTOR,9L,"art-1")).isInstanceOf(IpdBusinessException.class);
        verify(h.documents,never()).reviseGeneratedAuthorized(any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
    }

    @Test
    void reworkRejectsWrongProjectActionOpenRunAndUnrelatedDocument() {
        for (String mismatch : List.of("project", "action", "open", "document")) {
            Harness h = new Harness();
            h.run.setConfigSnapshot("{\"previousRunId\":\"8\",\"targetDocumentId\":\"8000\",\"baseVersionId\":\"8000\"}");
            IpdAgentRun previous = IpdAgentRun.builder().id(8L).tenantId(TENANT)
                .projectId(PROJECT_ID).personId(ACTOR.id()).actionCode("C02").status("SUCCEEDED").build();
            if (mismatch.equals("project")) previous.setProjectId(PROJECT_ID+1);
            if (mismatch.equals("action")) previous.setActionCode("C03");
            if (mismatch.equals("open")) previous.setStatus("RUNNING");
            when(h.runs.findRun(8L)).thenReturn(Optional.of(previous));
            when(h.artifacts.listByRunIds(List.of(8L))).thenReturn(List.of(version(IpdAgentArtifactVersion.STATUS_APPLIED,
                mismatch.equals("document") ? 9000L : 8000L)));
            when(h.artifacts.findLatestForUpdate(TENANT,9L,"art-1")).thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT,null)));
            assertThatThrownBy(() -> h.service.applyArtifact(ACTOR,9L,"art-1")).isInstanceOf(IpdBusinessException.class);
            verify(h.documents,never()).reviseGeneratedAuthorized(any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        }
    }

    @Test
    void explicitReworkReadsOnlyChosenChainEvenWhenSameTypeHasNewerRejectedDocument() {
        AiDocumentService documents = mock(AiDocumentService.class);
        AiDocument a = document(8000L, "REJECTED", "A意见", 1000L);
        AiDocument b = document(9000L, "REJECTED", "B意见", 2000L);
        when(documents.history(8000L)).thenReturn(List.of(a));
        when(documents.listByProject(PROJECT_ID, null)).thenReturn(List.of(a, b));

        assertThat(ProjectAgentRunService.commentForRework(documents, PROJECT_ID,
            "MARKET_RESEARCH", 8000L, 8000L)).isEqualTo("A意见");
        verify(documents, never()).listByProject(any(), any());
        verify(documents, never()).history(9000L);
    }

    @Test
    void staleBaseAndUnreadableChainRejectBeforeRunReservationOrInsertion() {
        for (String fault : List.of("stale", "unreadable")) {
            Harness h = new Harness();
            when(h.runs.findRun(8L)).thenReturn(Optional.of(IpdAgentRun.builder().id(8L)
                .tenantId(TENANT).projectId(PROJECT_ID).personId(ACTOR.id())
                .actionCode("C02").status("SUCCEEDED").build()));
            when(h.artifacts.listByRunIds(List.of(8L)))
                .thenReturn(List.of(version(IpdAgentArtifactVersion.STATUS_APPLIED, 8000L)));
            if (fault.equals("stale")) {
                when(h.documents.history(8000L)).thenReturn(List.of(
                    document(8000L, "REJECTED", "旧意见", 1000L),
                    document(8001L, "GENERATED", null, 2000L)));
            } else {
                when(h.documents.history(8000L)).thenThrow(new IpdBusinessException(
                    org.ruoyi.ipd.common.ApiV1ErrorCode.STATE_CONFLICT));
            }
            var template = org.ruoyi.ipd.agent.support.AgentTestFixtures.c02("rework-base-0001", "按意见修订");
            var req = new org.ruoyi.ipd.agent.dto.AgentRunCreateReq(template.capabilityPackCode(),
                template.capabilityPackVersion(), template.modelConfigId(), template.skillNames(),
                template.toolIds(), template.actionCode(), template.message(), template.idempotencyKey(),
                null, null, "8", "8000", "8000");
            assertThatThrownBy(() -> h.service.create(ACTOR, PROJECT_ID, req))
                .isInstanceOf(IpdBusinessException.class);
            verify(h.executor, never()).tryReserve();
            verify(h.executor, never()).insertReservedRun(any());
        }
    }

    private static AiDocument document(long id, String status, String comment, long createdAt) {
        AiDocument row = AiDocument.builder()
            .id(id)
            .projectId(PROJECT_ID)
            .docType("MARKET_RESEARCH")
            .status(status)
            .reviewComment(comment)
            .build();
        row.setCreateTime(new Date(createdAt));
        return row;
    }

    private static IpdAgentRunEvent step(long seq, String payload) {
        return IpdAgentRunEvent.builder()
            .runId(9L).tenantId(TENANT)
            .seq(seq)
            .eventType(AgentEventType.STEP.name())
            .payload(payload)
            .build();
    }

    private static IpdAgentArtifactVersion version(String status, Long documentId) {
        return IpdAgentArtifactVersion.builder()
            .id(71L)
            .runId(9L).tenantId(TENANT)
            .contentSha256(contentHash())
            .artifactId("art-1")
            .versionNo(1)
            .title("产物")
            .content("正文")
            .status(status)
            .documentId(documentId)
            .build();
    }

    /** 与原 Handle ARTIFACT 事件相同的可信正文 SHA，不按字符串前缀猜附件类型。 */
    private static String contentHash() {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("正文".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** 只装配 apply 需要的替身；内核与执行器不参与本次断言。 */
    private static final class Harness {
        final AgentRunStore runs = mock(AgentRunStore.class);
        final ArtifactVersionStore artifacts = mock(ArtifactVersionStore.class);
        final AiDocumentService documents = mock(AiDocumentService.class);
        final ProjectAgentRunExecutor executor = mock(ProjectAgentRunExecutor.class);
        final IpdAgentRun run;
        final ProjectAgentRunService service;

        Harness() {
            IpdCopilotAccess access = mock(IpdCopilotAccess.class);
            when(access.requireVisible(ACTOR, PROJECT_ID)).thenReturn(TENANT);
            run = IpdAgentRun.builder()
                .id(9L)
                .tenantId(TENANT)
                .projectId(PROJECT_ID)
                .personId(ACTOR.id())
                .actionCode("C02")
                .build();
            when(runs.findRun(9L)).thenReturn(Optional.of(run));
            var originEvent = IpdAgentRunEvent.builder().runId(9L).tenantId(TENANT).seq(10000L)
                .eventType(AgentEventType.ARTIFACT.name())
                .payload("{\"artifactId\":\"art-1\",\"title\":\"产物\",\"versionId\":\"71\",\"version\":1,\"contentHash\":\"" + contentHash() + "\"}").build();
            // 特定用量测试覆盖 MODEL_CALL 页；原可信产物仍在同一 run 的后续事件页。
            when(runs.listEvents(eq(9L), anyLong(), eq(ProjectAgentConstants.EVENTS_PAGE_LIMIT)))
                .thenAnswer(call -> (long) call.getArgument(1) < 10000L ? List.of(originEvent) : List.of());
            when(artifacts.findById(71L)).thenAnswer(call -> Optional.of(version(IpdAgentArtifactVersion.STATUS_DRAFT, null)));

            service = new ProjectAgentRunService(true, access, org.ruoyi.ipd.agent.support.AgentTestFixtures.planner(), runs, artifacts,
                documents, null, null, executor, new ObjectMapper(), () -> 0L,
                Duration.ofSeconds(60));
            try {
                var root = java.nio.file.Files.createTempDirectory("ipd-apply-origin-").toRealPath();
                var origin = new org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactOrigin(runs,
                    payload -> { throw new SecurityException("Read fixture cannot issue origin"); });
                var target = new org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactDelivery(
                    new org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactDelivery.Binding(ACTOR, PROJECT_ID, TENANT, 9L, "readonly", "readonly"),
                    access, runs, artifacts, new org.springframework.transaction.support.TransactionTemplate(
                        mock(org.springframework.transaction.PlatformTransactionManager.class)),
                    new org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactDelivery.RunOwnerTransaction() {
                        public <T> T owned(java.util.function.Supplier<T> body) { throw new SecurityException("Read fixture cannot deliver"); }
                    }, root, () -> { throw new SecurityException("Read fixture cannot allocate"); },
                    (binding, runtime) -> { throw new SecurityException("Read fixture cannot deliver"); }, origin);
                service.setArtifactAccess(new org.ruoyi.ipd.agent.servicebridge.ProjectAgentArtifactAccess((actor, runId) -> {
                    if (!ACTOR.id().equals(actor.id()) || !Long.valueOf(9L).equals(runId)) throw new SecurityException("Original run identity mismatch");
                    return target;
                }));
            } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        }
    }
    @Test
    void applyStartsReadCommittedTransactionBeforeLockAndReplayReads() {
        Harness harness = new Harness();
        when(harness.artifacts.findLatestForUpdate(TENANT, 9L, "art-1"))
            .thenReturn(Optional.of(version(IpdAgentArtifactVersion.STATUS_APPLIED, 8001L)));
        when(harness.documents.statusOf(8001L)).thenReturn(Optional.of(AiDocumentService.STATUS_REJECTED));
        var manager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        var status = new org.springframework.transaction.support.SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenAnswer(call -> {
            org.springframework.transaction.TransactionDefinition definition = call.getArgument(0);
            assertThat(definition.getIsolationLevel())
                .isEqualTo(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
            return status;
        });
        var source = new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource();
        var factory = new org.springframework.aop.framework.ProxyFactory(harness.service);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new org.springframework.transaction.interceptor.TransactionInterceptor(manager, source));
        ProjectAgentRunService proxied = (ProjectAgentRunService) factory.getProxy();
        var applied = proxied.applyArtifact(ACTOR, 9L, "art-1");
        assertThat(applied.documentStatus()).isEqualTo(AiDocumentService.STATUS_REJECTED);
        verify(manager).commit(status);
        verify(harness.documents).statusOf(8001L);
        verify(harness.documents, never()).createGeneratedAuthorized(any(), any(), any(), any(), any(), any(), any(), any());
    }

}
