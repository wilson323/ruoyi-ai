package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.ruoyi.ipd.support.NoopTransactionManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F3：{@code ProjectService.advanceStage} 接 Gate 评审判决。
 *
 * <p>本类只验接线层的三件事：① 阻断时抛 GATE_NOT_PASSED 且中文原因可读；
 * ② 拒绝路径落 {@code STAGE_GATE_BLOCKED} 审计且阶段不被推进；
 * ③ 不阻断时行为与改动前完全一致（CONCEPT→PLAN 正常推进）。
 *
 * <p>刻意用<b>真实 GateEngine</b>（只 mock mapper）而非 mock GateEngine——mock 掉整个引擎
 * 就等于把被测逻辑一起 mock 掉，绿灯什么也证明不了。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateStageAdvanceBlockTest {

    @Mock private ProjectMapper projectMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private StageActionMapper stageActionMapper;
    @Mock private GateMapper gateMapper;
    @Mock private GateElementResultMapper gateElementResultMapper;
    @Mock private org.ruoyi.ipd.mapper.ProjectMemberMapper projectMemberMapper;

    private GateEngine engine;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Gate.class);
        TableInfoHelper.initTableInfo(assistant, GateElementResult.class);
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @BeforeEach
    void setUp() {
        engine = new GateEngine(stageActionMapper, mock(ISystemConfigService.class));
        engine.setGateMapper(gateMapper);
        engine.setGateElementResultMapper(gateElementResultMapper);
        lenient().when(auditLogService.append(any(AuditLog.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(projectMemberMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        // CONCEPT 阶段 S 级阻断动作全部 DONE 且已确认 → 让 gateEngine.check 先过，
        // 后面被拦的只能是本刀新增的评审判决。
        lenient().when(stageActionMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(ActionCatalog.byStage("CONCEPT").stream()
                .filter(org.ruoyi.ipd.domain.ActionDef::blocking)
                .map(d -> {
                    StageAction a = new StageAction();
                    a.setProjectId(100L);
                    a.setActionCode(d.code());
                    a.setActionName(d.name());
                    a.setStatus("DONE");
                    a.setConfirmedBy(1L);
                    return a;
                }).toList());
    }

    
    private ProjectService newService() {
        ProjectService svc = new ProjectService(projectMapper,
            mock(org.ruoyi.ipd.mapper.ProductMapper.class), stageActionMapper,
            mock(org.ruoyi.ipd.mapper.KpiRecordMapper.class),
            auditLogService, engine, NoopTransactionManager.INSTANCE,
            null /* 不挂需求变更单门禁，专注本刀 */);
        svc.setProjectMemberMapper(projectMemberMapper);
        return svc;
    }

    private Project activeConcept() {
        Project p = new Project();
        p.setId(100L);
        p.setName("f3-fixture");
        p.setStatus("ACTIVE");
        p.setCurrentStage("CONCEPT");
        p.setLevel("S");
        p.setMainGroupId(11L);
        p.setDelFlag("0");
        return p;
    }

    private Gate gate(String gateCode, String status) {
        Gate g = new Gate();
        g.setId(9L);
        g.setProjectId(100L);
        g.setGateCode(gateCode);
        g.setStatus(status);
        return g;
    }

    @SuppressWarnings("unchecked")
    private void mockGates(Gate... gates) {
        lenient().when(gateMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(gates));
    }

    @SuppressWarnings("unchecked")
    private void mockOpenLeftover(long count) {
        lenient().when(gateElementResultMapper.selectCount(any(LambdaQueryWrapper.class)))
            .thenReturn(count);
    }

    @Test
    @DisplayName("F3：G1 评审 REJECTED → advanceStage 抛 GATE_NOT_PASSED，阶段不推进，落 STAGE_GATE_BLOCKED 审计")
    void rejectedGateBlocksAdvanceStage() {
        Project project = activeConcept();
        when(projectMapper.selectById(100L)).thenReturn(project);
        mockGates(gate("G1", "REJECTED"));

        assertThatThrownBy(() -> newService().advanceStage(100L, 5L, 11L, "MARKET_PM"))
            .isInstanceOf(IpdBusinessException.class)
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
        // 中文原因可读：含 Gate 码与状态
        assertThatThrownBy(() -> newService().advanceStage(100L, 5L, 11L, "MARKET_PM"))
            .hasMessageContaining("G1").hasMessageContaining("REJECTED");

        assertThat(project.getCurrentStage()).isEqualTo("CONCEPT");
        verify(projectMapper, never()).updateById(any(Project.class));

        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, atLeastOnce()).append(cap.capture());
        boolean blocked = cap.getAllValues().stream()
            .anyMatch(l -> "STAGE_GATE_BLOCKED".equals(l.getAction())
                && Long.valueOf(100L).equals(l.getEntityId())
                && l.getBeforeData() != null
                && l.getBeforeData().contains("gateCode")
                && l.getBeforeData().contains("gateStatus"));
        assertThat(blocked)
            .as("评审判决拒绝必须落 STAGE_GATE_BLOCKED 审计（区别于 P2-6.2 的 STAGE_GUARD_BLOCKED）")
            .isTrue();
    }

    @Test
    @DisplayName("F3：G1 评审通过但有 3 项未关闭遗留项 → 同样拒绝推进")
    void openLeftoverBlocksAdvanceStage() {
        Project project = activeConcept();
        when(projectMapper.selectById(100L)).thenReturn(project);
        mockGates(gate("G1", "APPROVED"));
        mockOpenLeftover(3L);

        assertThatThrownBy(() -> newService().advanceStage(100L, 5L, 11L, "MARKET_PM"))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("遗留项")
            .extracting("errorCode").isEqualTo(ApiV1ErrorCode.GATE_NOT_PASSED);
        assertThat(project.getCurrentStage()).isEqualTo("CONCEPT");
    }

    @Test
    @DisplayName("F3：G1 评审 PENDING → 不拦，正常推进 CONCEPT→PLAN（行为与本刀前一致）")
    void pendingGateAllowsAdvanceStage() {
        Project project = activeConcept();
        when(projectMapper.selectById(100L)).thenReturn(project);
        mockGates(gate("G1", "PENDING"));
        mockOpenLeftover(0L);

        Project out = newService().advanceStage(100L, 5L, 11L, "MARKET_PM");
        assertThat(out.getCurrentStage()).isEqualTo("PLAN");
    }

    @Test
    @DisplayName("F3：gates 表无行（存量 300 项目形态）→ 不拦，正常推进")
    void noGateRowAllowsAdvanceStage() {
        Project project = activeConcept();
        when(projectMapper.selectById(100L)).thenReturn(project);
        mockGates();

        Project out = newService().advanceStage(100L, 5L, 11L, "MARKET_PM");
        assertThat(out.getCurrentStage()).isEqualTo("PLAN");
        ArgumentCaptor<AuditLog> cap = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, atLeastOnce()).append(cap.capture());
        assertThat(cap.getAllValues().stream()
            .noneMatch(l -> "STAGE_GATE_BLOCKED".equals(l.getAction())))
            .as("放行路径不得写 STAGE_GATE_BLOCKED 审计")
            .isTrue();
    }

    @Test
    @DisplayName("F3：VALID 阶段无 Gate 绑定 → 不拦（本刀对 VALID 零影响）")
    void validStageWithoutGateBindingAllows() {
        assertThat(ActionCatalog.gateOfStage("VALID")).isNull();
        assertThatCode(() -> engine.evaluateStageExitGate(100L, "VALID"))
            .doesNotThrowAnyException();
        assertThat(engine.evaluateStageExitGate(100L, "VALID").blocking()).isFalse();
    }
}