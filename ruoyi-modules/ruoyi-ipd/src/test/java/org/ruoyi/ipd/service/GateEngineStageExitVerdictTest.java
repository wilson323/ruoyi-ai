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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F3：阶段出口 Gate 评审判决（{@code GateEngine.evaluateStageExitGate}）。
 *
 * <p>覆盖 5 条：① 阶段→Gate 映射正确性；② REJECTED 拦；③ OPEN 遗留项拦；
 * ④ 存量放行（mapper 未装配 / gates 表无行）；⑤ <b>PENDING 不拦</b>（最易写错的一条）。
 *
 * <p>断言口径提醒：本测试类只验「是否拦截」这一个维度；{@code check} 的动作门禁由
 * {@code GateEngineTest} 独立覆盖，两者互不替代。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateEngineStageExitVerdictTest {

    @Mock private StageActionMapper stageActionMapper;
    @Mock private ISystemConfigService systemConfigService;
    @Mock private GateMapper gateMapper;
    @Mock private GateElementResultMapper gateElementResultMapper;

    private GateEngine engine;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Gate.class);
        TableInfoHelper.initTableInfo(assistant, GateElementResult.class);
        TableInfoHelper.initTableInfo(assistant, StageAction.class);
    }

    @BeforeEach
    void setUp() {
        engine = new GateEngine(stageActionMapper, systemConfigService);
    }

    private Gate gate(Long id, String gateCode, String status) {
        Gate g = new Gate();
        g.setId(id);
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

    // ==================== ① 映射正确性 ====================

    @Test
    @DisplayName("映射：CONCEPT→G1、PLAN→G2、DEV→G3、LAUNCH→G4、LIFECYCLE→G5")
    void stageToGateMapping() {
        assertThat(ActionCatalog.gateOfStage("CONCEPT")).isEqualTo("G1");
        assertThat(ActionCatalog.gateOfStage("PLAN")).isEqualTo("G2");
        assertThat(ActionCatalog.gateOfStage("DEV")).isEqualTo("G3");
        assertThat(ActionCatalog.gateOfStage("LAUNCH")).isEqualTo("G4");
        assertThat(ActionCatalog.gateOfStage("LIFECYCLE")).isEqualTo("G5");
    }

    @Test
    @DisplayName("映射：VALID 阶段目录无挂 Gate 动作 → null（不拦）；空/null 阶段 → null")
    void unmappedStagesReturnNull() {
        assertThat(ActionCatalog.gateOfStage("VALID")).isNull();
        assertThat(ActionCatalog.gateOfStage(null)).isNull();
        assertThat(ActionCatalog.gateOfStage("  ")).isNull();
        assertThat(ActionCatalog.gateOfStage("NOT_A_STAGE")).isNull();
        // 逆向映射与正向映射必须自洽
        assertThat(ActionCatalog.stageOfGate(ActionCatalog.gateOfStage("CONCEPT"))).isEqualTo("CONCEPT");
    }

    // ==================== ② REJECTED 拦 ====================

    @Test
    @DisplayName("REJECTED：CONCEPT 阶段 G1 评审被否决 → blocking=true")
    void rejectedBlocks() {
        engine.setGateMapper(gateMapper);
        mockGates(gate(9L, "G1", "REJECTED"));
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "CONCEPT");
        assertThat(v.blocking()).isTrue();
        assertThat(v.gateCode()).isEqualTo("G1");
        assertThat(v.gateStatus()).isEqualTo("REJECTED");
        assertThat(v.message()).contains("REJECTED");
    }

    @Test
    @DisplayName("ABSTAINED_TIMEOUT（双签超时弃权）同样拦截")
    void abstainTimeoutBlocks() {
        engine.setGateMapper(gateMapper);
        mockGates(gate(9L, "G1", "ABSTAINED_TIMEOUT"));
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "CONCEPT");
        assertThat(v.blocking()).isTrue();
        assertThat(v.message()).contains("ABSTAINED_TIMEOUT");
    }

    @Test
    @DisplayName("多轮评审：按 id 倒序取最新一轮，旧轮 REJECTED 不误伤新轮 APPROVED")
    void latestRoundWins() {
        engine.setGateMapper(gateMapper);
        // 列表首行=最新（id 最大）
        mockGates(gate(20L, "G1", "APPROVED"), gate(9L, "G1", "REJECTED"));
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "CONCEPT");
        assertThat(v.blocking()).isFalse();
        assertThat(v.gateStatus()).isEqualTo("APPROVED");
    }

    // ==================== ③ OPEN 遗留项拦 ====================

    @Test
    @DisplayName("评审已通过但 leftover_status=OPEN 遗留项 > 0 → blocking=true")
    void openLeftoverBlocks() {
        engine.setGateMapper(gateMapper);
        engine.setGateElementResultMapper(gateElementResultMapper);
        mockGates(gate(9L, "G1", "APPROVED"));
        mockOpenLeftover(2L);
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "CONCEPT");
        assertThat(v.blocking()).isTrue();
        assertThat(v.gateStatus()).isEqualTo("APPROVED");
        assertThat(v.openLeftoverCount()).isEqualTo(2);
        assertThat(v.message()).contains("2").contains("遗留项");
    }

    @Test
    @DisplayName("遗留项全部关闭（OPEN=0）→ 放行")
    void closedLeftoverAllows() {
        engine.setGateMapper(gateMapper);
        engine.setGateElementResultMapper(gateElementResultMapper);
        mockGates(gate(9L, "G1", "APPROVED"));
        mockOpenLeftover(0L);
        assertThat(engine.evaluateStageExitGate(100L, "CONCEPT").blocking()).isFalse();
    }

    // ==================== ④ 存量放行 ====================

    @Test
    @DisplayName("存量放行：mapper 未装配（单测/裁剪部署）→ 不拦")
    void noMapperAssembledAllows() {
        // 故意不 setGateMapper / setGateElementResultMapper
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "CONCEPT");
        assertThat(v.blocking()).isFalse();
        assertThat(v.gateCode()).isEqualTo("G1");
    }

    @Test
    @DisplayName("存量放行：mapper 已装配但 gates 表无行 → 不拦（gates 表现 0 行）")
    void noGateRowAllows() {
        engine.setGateMapper(gateMapper);
        mockGates();
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "CONCEPT");
        assertThat(v.blocking()).isFalse();
        assertThat(v.gateStatus()).isNull();
        assertThat(v.message()).contains("无评审记录");
    }

    @Test
    @DisplayName("存量放行：VALID 阶段无 Gate 绑定 → 不拦")
    void stageWithoutGateAllows() {
        engine.setGateMapper(gateMapper);
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "VALID");
        assertThat(v.blocking()).isFalse();
        assertThat(v.gateCode()).isNull();
    }

    // ==================== ⑤ PENDING 不拦 ====================

    @Test
    @DisplayName("PENDING：评审尚未开始不是「被判失败」→ 不拦（owner 拍板口径）")
    void pendingDoesNotBlock() {
        engine.setGateMapper(gateMapper);
        engine.setGateElementResultMapper(gateElementResultMapper);
        mockGates(gate(9L, "G1", "PENDING"));
        mockOpenLeftover(0L);
        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(100L, "CONCEPT");
        assertThat(v.blocking())
            .as("PENDING 必须放行——拦截口径只含 REJECTED/ABSTAINED_TIMEOUT/OPEN 遗留项")
            .isFalse();
        assertThat(v.gateStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("APPROVED 且无遗留项 → 放行（正常推进路径）")
    void approvedAllows() {
        engine.setGateMapper(gateMapper);
        engine.setGateElementResultMapper(gateElementResultMapper);
        mockGates(gate(9L, "G1", "APPROVED"));
        mockOpenLeftover(0L);
        assertThat(engine.evaluateStageExitGate(100L, "CONCEPT").blocking()).isFalse();
    }

    /**
     * fail-closed 承重用例：查询故障时必须**拦停**，不得放行。
     *
     * <p>原实现把查询异常吞成「无记录 / 0 条」⇒ 被驳回的 Gate 在一次数据库抖动里静默失效，
     * 阶段照推。本用例把 mapper 打成抛异常来锁住新行为——去掉 try/catch 就会红。
     */
    @Test
    @DisplayName("查询故障时 fail-closed 拦停（不得把「查失败」当成「没问题」）")
    void queryFailureBlocksInsteadOfPassing() {
        engine.setGateMapper(gateMapper);
        engine.setGateElementResultMapper(gateElementResultMapper);
        when(gateMapper.selectList(any())).thenThrow(new RuntimeException("模拟数据库不可用"));

        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(11L, "CONCEPT");

        assertThat(v.blocking()).isTrue();
        assertThat(v.message()).contains("查询失败");
        // openLeftoverCount=-1 是「判不出来」的哨兵值，区别于真实的 0 条
        assertThat(v.openLeftoverCount()).isEqualTo(-1);
    }

    @Test
    @DisplayName("遗留项查询故障同样 fail-closed（Gate 通过但遗留项查不动 → 拦停）")
    void leftoverQueryFailureBlocksEvenWhenGateApproved() {
        engine.setGateMapper(gateMapper);
        engine.setGateElementResultMapper(gateElementResultMapper);
        Gate approved = new Gate();
        approved.setId(801L);
        approved.setStatus("APPROVED");
        when(gateMapper.selectList(any())).thenReturn(List.of(approved));
        when(gateElementResultMapper.selectCount(any())).thenThrow(new RuntimeException("模拟遗留项查询不可用"));

        GateEngine.StageGateVerdict v = engine.evaluateStageExitGate(11L, "CONCEPT");

        assertThat(v.blocking()).isTrue();
        assertThat(v.message()).contains("遗留项查询失败");
    }

    @Test
    @DisplayName("mapper 未装配仍放行（确定性「没有闸」≠ 故障，两条路径不可混淆）")
    void mapperNotWiredStillPasses() {
        GateEngine bare = new GateEngine(stageActionMapper, systemConfigService);   // 不注入 gateMapper
        GateEngine.StageGateVerdict v = bare.evaluateStageExitGate(11L, "CONCEPT");
        assertThat(v.blocking()).isFalse();
    }
}