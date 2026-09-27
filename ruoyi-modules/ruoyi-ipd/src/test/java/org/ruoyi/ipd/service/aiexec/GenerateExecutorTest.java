package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.service.AiGenerationService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.ipd.service.ai.NodeAgentResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R221 Task 7：GenerateExecutor 单测——只到 IN_PROGRESS，绝不代签 DONE。
 *
 * <p>R236 扩充：锁定三处硬编码消除后的行为（契约 §7 B6）——docType 取
 * {@code ActionCatalog.docTypeOf}、prompt 优先取节点智能体 system_prompt、通知角色取
 * {@code def.ownerRole()}；以及未绑定智能体时的降级链（命中 PromptType 则复用既有
 * {@code PromptTemplates} 成熟模板，不硬编码文案）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GenerateExecutorTest {

    private static final AiExecContext CTX = new AiExecContext(
        new IpdActor(0L, "system", "SYSTEM", null),
        Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("UTC")));

    @Mock private AiGenerationService aiGenerationService;
    @Mock private StageActionService stageActionService;
    @Mock private NotificationService notificationService;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private NodeAgentResolver nodeAgentResolver;
    @InjectMocks private GenerateExecutor executor;

    @Test
    void generatesDraftTransitsInProgressAndCarriesDocId() {
        AiDocument doc = new AiDocument();
        doc.setId(4401L);
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of()); // 无成员则跳过通知（合法 fixture：真库项目可以没有 MARKET_PM）
        AiAgentTask t = AiAgentTask.builder().id(3L).projectId(100L).actionCode("C01")
            .stageActionId(9001L).execMode("AI_GENERATE").build();

        AiExecResult r = executor.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.aiDocId()).isEqualTo(4401L);
        // 关键契约：只到 IN_PROGRESS，绝不代签 DONE（人审通过由 review hook 收尾）
        verify(stageActionService).transit(eq(9001L), eq("IN_PROGRESS"), any(String.class), eq("0"));
        verify(stageActionService, never()).transit(anyLong(), eq("DONE"), any(), any());
        // B6：docType 不再写死 MARKET_RESEARCH 字面量，而是由目录派生（C01 仍得 MARKET_RESEARCH，保 RAG 过滤连续性）
        AiGenerateReq req = capturedReq();
        assertThat(req.docType()).isEqualTo("MARKET_RESEARCH");
        assertThat(req.title()).contains("C01");
        // 未绑定智能体且 MARKET_RESEARCH 不在 PromptType 值域 → promptType 留 null（裸 prompt 直传）
        assertThat(req.promptType()).isNull();
        assertThat(req.prompt()).contains("市场机会与痛点调研");
    }

    /** M3 红线：人审已完结（DONE）后重复触发不得把状态打回 IN_PROGRESS/重复生成文档 */
    @Test
    void generateSkipsTerminalDoneAction() {
        org.ruoyi.ipd.domain.StageAction done = new org.ruoyi.ipd.domain.StageAction();
        done.setStatus("DONE");
        when(stageActionService.getById(9001L)).thenReturn(done);
        AiAgentTask t = AiAgentTask.builder().id(3L).projectId(100L).actionCode("C01")
            .stageActionId(9001L).execMode("AI_GENERATE").build();

        AiExecResult r = executor.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).contains("no-op");
        verify(aiGenerationService, never()).generate(any(), any());
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }

    /**
     * R236 主路径：绑定节点智能体时用其 system_prompt 作指令，且 promptType 必须留 null——
     * 否则智能体指令会与 {@code PromptTemplates} 模板**双重叠加**，两套指令相互干扰。
     */
    @Test
    void boundNodeAgentPromptIsUsedWithoutTemplateOverlay() {
        AiDocument doc = new AiDocument();
        doc.setId(4402L);
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());
        when(nodeAgentResolver.systemPromptOf("C01")).thenReturn("【角色】你是 IPD-C01 节点智能体");
        AiAgentTask t = AiAgentTask.builder().id(4L).projectId(100L).actionCode("C01")
            .stageActionId(9002L).execMode("AI_GENERATE").build();

        executor.execute(t, CTX);

        AiGenerateReq req = capturedReq();
        assertThat(req.prompt()).startsWith("【角色】你是 IPD-C01 节点智能体");
        assertThat(req.prompt()).contains("【执行上下文】").contains("C01");
        assertThat(req.promptType()).as("绑定智能体时不得叠加模板").isNull();
    }

    /**
     * R236 降级链复用成熟件：未绑定智能体且 docType 命中 {@code PromptType} 值域（P01→PRD）
     * 时，把它作为 promptType 下发，直接走既有 8 套 {@code PromptTemplates}（而非硬编码文案）。
     */
    @Test
    void unboundAgentReusesMaturePromptTemplateWhenDocTypeMaps() {
        AiDocument doc = new AiDocument();
        doc.setId(4403L);
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());
        AiAgentTask t = AiAgentTask.builder().id(5L).projectId(100L).actionCode("P01")
            .stageActionId(9003L).execMode("AI_GENERATE").build();

        executor.execute(t, CTX);

        AiGenerateReq req = capturedReq();
        assertThat(req.docType()).isEqualTo("PRD");
        assertThat(req.promptType()).as("降级应复用 PromptTemplates 而非自造文案").isEqualTo("PRD");
        assertThat(req.prompt()).contains("产品需求规格定义PRD");
    }

    /** B6：通知角色取目录 {@code ownerRole}（P03=RD_PM），不再写死 MARKET_PM 通知错人。 */
    @Test
    void notifiesCatalogOwnerRoleInsteadOfHardcodedMarketPm() {
        AiDocument doc = new AiDocument();
        doc.setId(4404L);
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);
        ProjectMember rdPm = new ProjectMember();
        rdPm.setPersonId(77L);
        rdPm.setRole("RD_PM");
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(rdPm));
        AiAgentTask t = AiAgentTask.builder().id(6L).projectId(100L).actionCode("P03")
            .stageActionId(9004L).execMode("AI_GENERATE").build();

        executor.execute(t, CTX);

        // 查询按 ownerRole 过滤（而非写死 MARKET_PM）；命中则发一条日级去重待审通知
        verify(notificationService).publishDaily(eq(77L), eq(NotificationService.Types.AI_PREPARED_GENERATE),
            eq(NotificationService.KIND_ACTION), eq("ai_agent_task"), eq(6L),
            anyString(), anyString(), eq("/projects/100"), any());
    }

    private AiGenerateReq capturedReq() {
        ArgumentCaptor<AiGenerateReq> captor = ArgumentCaptor.forClass(AiGenerateReq.class);
        verify(aiGenerationService).generate(any(IpdActor.class), captor.capture());
        return captor.getValue();
    }
}
