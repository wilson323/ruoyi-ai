package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.AiDocument;
import org.ruoyi.ipd.dto.AiGenerateReq;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.service.AiGenerationService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.StageActionService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R221 Task 7：GenerateExecutor(C01) 单测——只到 IN_PROGRESS，绝不代签 DONE。 */
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
    @InjectMocks private GenerateExecutor executor;

    @Test
    void generatesDraftTransitsInProgressAndCarriesDocId() {
        AiDocument doc = new AiDocument();
        doc.setId(4401L);
        when(aiGenerationService.generate(any(IpdActor.class), any(AiGenerateReq.class))).thenReturn(doc);
        when(projectMemberMapper.selectList(any())).thenReturn(java.util.List.of()); // 无成员则跳过通知（合法 fixture：真库项目可以没有 MARKET_PM）
        AiAgentTask t = AiAgentTask.builder().id(3L).projectId(100L).actionCode("C01")
            .stageActionId(9001L).execMode("AI_GENERATE").build();

        AiExecResult r = executor.execute(t, CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.aiDocId()).isEqualTo(4401L);
        // 关键契约：只到 IN_PROGRESS，绝不代签 DONE（人审通过由 review hook 收尾）
        verify(stageActionService).transit(eq(9001L), eq("IN_PROGRESS"), any(String.class), eq("0"));
        verify(stageActionService, never()).transit(anyLong(), eq("DONE"), any(), any());
    }
}
