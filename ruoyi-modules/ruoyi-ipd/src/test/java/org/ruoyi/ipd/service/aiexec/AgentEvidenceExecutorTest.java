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
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.AiGenerationService;
import org.ruoyi.ipd.service.StageActionService;
import org.ruoyi.ipd.service.ai.NodeAgentResolver;
import org.ruoyi.system.domain.vo.SysOssVo;
import org.ruoyi.system.service.ISysOssService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R236 AgentEvidenceExecutor 单测：AI_DIRECT ∧ DEEP 证据档位。
 *
 * <p>锁三条红线：①已绑定智能体却生成失败**绝不静默降级**为伪造完成（走 fail → 引擎退避 → DEAD 转人工）；
 * ②未绑定时降级证据须**显式声明未使用 AI**，不编造结论；③先上传 OSS 再落任何库状态，
 * 上传失败不得留下「有完成日却无交付物」的半成品（DEEP 完成判据 = ≥1 交付物，BR-IPD-03）。
 * 另锁 {@code supportsSchedule()=false}（契约 §7 B4）：本档位 LLM 产物即 DONE 门禁证据、
 * 无独立人审环节，放开调度等于 AI 代签完成。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class AgentEvidenceExecutorTest {

    private static final AiExecContext CTX = new AiExecContext(
        new IpdActor(0L, "system", "SYSTEM", null),
        Clock.fixed(Instant.parse("2026-09-27T10:00:00Z"), ZoneId.of("UTC")));

    /** C07 成本/定价/毛利初步测算：AI_DIRECT ∧ DEEP ∧ MARKET_PM ∧ 无 valueFields。 */
    private static AiAgentTask task() {
        return AiAgentTask.builder().id(11L).projectId(100L).actionCode("C07")
            .stageActionId(9101L).execMode("AI_DIRECT").triggerType("PASSIVE").triggeredBy(77L).build();
    }

    @Mock private StageActionService stageActionService;
    @Mock private AiGenerationService aiGenerationService;
    @Mock private ISysOssService ossService;
    @Mock private NodeAgentResolver nodeAgentResolver;
    @InjectMocks private AgentEvidenceExecutor executor;

    /** 红线（契约 §7 B4）：无人审环节的 LLM 证据不得被调度器自动派发。 */
    @Test
    void scheduleNotSupportedBecauseLlmOutputIsTheGateEvidence() {
        assertThat(executor.supportsSchedule()).isFalse();
    }

    @Test
    void boundAgentEvidenceIsGeneratedUploadedThenTransitsDone() {
        when(nodeAgentResolver.systemPromptOf("C07")).thenReturn("【角色】你是 IPD-C07 节点智能体");
        AiDocument doc = new AiDocument();
        doc.setId(4501L);
        doc.setContent("# 成本测算证据\n\n结论与依据……");
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);
        SysOssVo vo = new SysOssVo();
        vo.setOssId(8901L);
        when(ossService.upload(any(MultipartFile.class))).thenReturn(vo);

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.aiDocId()).isEqualTo(4501L);
        // 智能体指令作 prompt 主体，且带执行上下文
        ArgumentCaptor<AiGenerateReq> req = ArgumentCaptor.forClass(AiGenerateReq.class);
        verify(aiGenerationService).generate(any(IpdActor.class), req.capture());
        assertThat(req.getValue().prompt()).startsWith("【角色】你是 IPD-C07 节点智能体");
        assertThat(req.getValue().prompt()).contains("【执行上下文】");
        // DEEP 完成判据：登记完成日 + 挂交付物，再 transit(DONE)
        verify(stageActionService).recordFields(eq(9101L), any(Date.class),
            isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq("0"));
        verify(stageActionService).addDeliverable(eq(9101L), anyString(), eq(8901L), eq("0"));
        verify(stageActionService).transit(eq(9101L), eq("DONE"), any(String.class), eq("0"));
    }

    /** 红线 5：已绑定智能体却生成失败 → fail 走退避重试，不得静默降级成「已完成」。 */
    @Test
    void boundAgentGenerationFailureFailsInsteadOfSilentDegrade() {
        when(nodeAgentResolver.systemPromptOf("C07")).thenReturn("【角色】你是 IPD-C07 节点智能体");
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class)))
            .thenThrow(new IllegalStateException("月度预算已用尽"));

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("C07").contains("月度预算已用尽");
        verify(ossService, never()).upload(any(MultipartFile.class));
        verify(stageActionService, never()).addDeliverable(anyLong(), anyString(), anyLong(), anyString());
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }

    /** 空正文不得挂空交付物（空文件能过 BR-IPD-03 计数却毫无证据价值 = 假绿）。 */
    @Test
    void blankAgentContentFailsWithoutDeliverable() {
        when(nodeAgentResolver.systemPromptOf("C07")).thenReturn("【角色】你是 IPD-C07 节点智能体");
        AiDocument doc = new AiDocument();
        doc.setId(4502L);
        doc.setContent("   ");
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("空证据");
        verify(ossService, never()).upload(any(MultipartFile.class));
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }

    /** 红线 5 的降级面：未绑定智能体 → 确定性归集，且文件内**显式声明不含 AI 结论**。 */
    @Test
    void unboundAgentDegradesToDeterministicEvidenceDeclaringNoAi() throws IOException {
        when(nodeAgentResolver.systemPromptOf("C07")).thenReturn(null);
        SysOssVo vo = new SysOssVo();
        vo.setOssId(8902L);
        ArgumentCaptor<MultipartFile> file = ArgumentCaptor.forClass(MultipartFile.class);
        when(ossService.upload(file.capture())).thenReturn(vo);

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.aiDocId()).as("降级路径不产 ai_documents").isNull();
        String content = new String(file.getValue().getBytes(), StandardCharsets.UTF_8);
        assertThat(content).contains("不含任何 AI 生成结论").contains("未绑定节点智能体");
        assertThat(r.summary()).contains("降级");
        verify(aiGenerationService, never()).generate(any(), any());
        verify(stageActionService).transit(eq(9101L), eq("DONE"), any(String.class), eq("0"));
    }

    /** 顺序锁：OSS 失败时不得先写完成日/流转，否则留下「DONE 但零交付物」的非法态。 */
    @Test
    void ossFailureLeavesNoCompletionState() {
        when(nodeAgentResolver.systemPromptOf("C07")).thenReturn("【角色】你是 IPD-C07 节点智能体");
        AiDocument doc = new AiDocument();
        doc.setId(4503L);
        doc.setContent("# 证据");
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);
        when(ossService.upload(any(MultipartFile.class))).thenReturn(null);

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("上传失败");
        verify(stageActionService, never()).recordFields(anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(stageActionService, never()).addDeliverable(anyLong(), anyString(), anyLong(), anyString());
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }

    /** M3 红线：人判/已完成动作重复触发不得再产证据、再挂交付物、再流转。 */
    @Test
    void terminalDoneActionIsNoOp() {
        StageAction done = new StageAction();
        done.setStatus("DONE");
        when(stageActionService.getById(9101L)).thenReturn(done);

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).contains("no-op");
        verify(aiGenerationService, never()).generate(any(), any());
        verify(ossService, never()).upload(any(MultipartFile.class));
        verify(stageActionService, never()).transit(anyLong(), any(), any(), any());
    }
}
