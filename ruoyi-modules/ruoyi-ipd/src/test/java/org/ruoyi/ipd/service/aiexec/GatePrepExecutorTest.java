package org.ruoyi.ipd.service.aiexec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.mapper.GateElementMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.GateElementResultService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.system.service.ISysOssService;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** R221 Task 7：GatePrepExecutor(C11) 单测——备料/judge 非否决/否决绝不代判/submit 失败引导。 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GatePrepExecutorTest {

    private static final AiExecContext CTX = new AiExecContext(
        new IpdActor(0L, "system", "SYSTEM", null),
        Clock.fixed(Instant.parse("2026-09-26T10:00:00Z"), ZoneId.of("UTC")));

    @Mock private GateMapper gateMapper;
    @Mock private GateElementMapper gateElementMapper;
    @Mock private GateElementResultService gateElementResultService;
    @Mock private ISysOssService ossService;
    @Mock private NotificationService notificationService;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @InjectMocks private GatePrepExecutor executor;

    private AiAgentTask task() {
        return AiAgentTask.builder().id(4L).projectId(100L).actionCode("C11")
            .stageActionId(9004L).execMode("HUMAN_GATE").build();
    }

    private Gate pendingGate() {
        Gate g = new Gate();
        g.setId(6001L);
        g.setProjectId(100L);
        g.setGateCode("G1");
        g.setStatus("PENDING");
        g.setStartedAt(null); // mock 合法性：PENDING 且未提交是 submit 前置真态
        return g;
    }

    private GateElement element(long id, String code, String isVeto) {
        GateElement e = new GateElement();
        e.setId(id);
        e.setGateCode("G1");
        e.setElementCode(code);
        e.setElementName(code + "要素");
        e.setIsVeto(isVeto);
        e.setEnabled("1");
        return e;
    }

    @Test
    void alreadySubmittedGateIsNoOp() {
        Gate g = pendingGate();
        g.setStartedAt(new java.util.Date()); // 已提交等待签署
        when(gateMapper.selectOne(any())).thenReturn(g);

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isTrue();
        assertThat(r.summary()).contains("no-op");
        verify(gateElementResultService, never()).judge(anyLong(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void judgesNonVetoElementsOnlyThenSubmitsAndNotifies() {
        when(gateMapper.selectOne(any())).thenReturn(pendingGate());
        when(gateElementMapper.selectList(any())).thenReturn(List.of(
            element(11L, "G1-E1", "0"), element(12L, "G1-E2", "1")));
        var vo = new org.ruoyi.system.domain.vo.SysOssVo();
        vo.setOssId(8802L);
        when(ossService.upload(any(org.springframework.web.multipart.MultipartFile.class))).thenReturn(vo);
        when(projectMemberMapper.selectList(any())).thenReturn(List.of());

        AiExecResult r = executor.execute(task(), CTX);

        // 非否决 E1 判 PASS 草稿；否决 E2 绝不代判（盲签/代签红线）
        verify(gateElementResultService).judge(eq(6001L), eq(11L), eq("PASS"), any(), eq("AI-DRAFT-R221"),
            any(), any(), any(), any(), any(IpdActor.class));
        verify(gateElementResultService, never()).judge(eq(6001L), eq(12L), any(), any(), any(), any(), any(), any(), any(), any());
        verify(gateElementResultService).submit(eq(6001L), eq(8802L), eq(8802L), any(IpdActor.class));
        assertThat(r.ok()).isTrue();
    }

    @Test
    void submitRejectedByVetoGapFailsWithGuidanceButStillNotifies() {
        when(gateMapper.selectOne(any())).thenReturn(pendingGate());
        when(gateElementMapper.selectList(any())).thenReturn(List.of(element(12L, "G1-E2", "1")));
        var vo = new org.ruoyi.system.domain.vo.SysOssVo();
        vo.setOssId(8802L);
        when(ossService.upload(any(org.springframework.web.multipart.MultipartFile.class))).thenReturn(vo);
        when(gateElementResultService.submit(anyLong(), anyLong(), anyLong(), any()))
            .thenThrow(new org.ruoyi.common.core.exception.ServiceException("以下适用要素尚未判定: G1-E2"));
        // StillNotifies 断言需真实接收人：给一个在任 MARKET_PM（真库项目合法态）
        when(projectMemberMapper.selectList(any())).thenReturn(List.of(
            org.ruoyi.ipd.domain.ProjectMember.builder().personId(777L).projectId(100L).role("MARKET_PM").build()));

        AiExecResult r = executor.execute(task(), CTX);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorMsg()).contains("待人判否决要素");
        verify(notificationService, atLeastOnce()).publishDaily(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
