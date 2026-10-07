package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
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
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 / §5.3 HIGH-1.1：G3 评审自动创建入口验收测试。
 *
 * <p>边界：
 * <ol>
 *   <li>合法 gateCode（G1/G2/G3/G4/G5）⇒ 创建成功 + 写 GATE_AUTO_CREATE 审计</li>
 *   <li>非法 gateCode（XYZ/空/null）⇒ 拒</li>
 *   <li>ARCHIVED/SUSPENDED 项目 ⇒ 拒</li>
 *   <li>F6-① 在途去重：同项目同 gateCode 已存在 PENDING 行 ⇒ 拒（不再是 14 天时间冷却）</li>
 *   <li>项目不存在 ⇒ 拒</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateAutoCreateAcceptanceTest {

    @Mock private GateMapper gateMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private IAuditLogService auditLogService;

    private GateCreationService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Gate.class);
        TableInfoHelper.initTableInfo(assistant, Project.class);
    }

    @BeforeEach
    void setUp() {
        service = new GateCreationService(gateMapper, projectMapper, auditLogService);
    }

    private Project activeProject(long id) {
        return Project.builder().id(id).name("GATE 测试 " + id).status("ACTIVE").mainGroupId(70L).delFlag("0").build();
    }

    @Test
    @DisplayName("AC#1 G3 合法创建 ⇒ 成功 + 审计（R30：写 gates 主体表）")
    void createG3_succeeds() {
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(gateMapper.selectCount(any())).thenReturn(0L);
        Gate g = service.autoCreateGate(700L, "G3", 999L);
        assertThat(g.getGateCode()).isEqualTo("G3");
        assertThat(g.getProjectId()).isEqualTo(700L);
        assertThat(g.getCurrentRound()).isEqualTo(1);
        assertThat(g.getStatus()).isEqualTo("PENDING");
        // R30 修复（E2E 抓获 P0）：创建写 gates 主体表，startedAt 必须留 NULL——
        // GateReviewService.requireSubmitted 据此拦 sign（"评审尚未提交，请先完成要素判定并提交（P2-5.1）"），
        // submit 置位 startedAt=要素判定冻结。decision/reviewer 归属签署表 gate_reviews（sign 流写入），
        // KeyGateAggregator 的 .isNull(GateReview::getDecision) 待决锚点保持命中。
        assertThat(g.getStartedAt()).as("R30:创建时 startedAt 必须为 NULL（尚未提交要素判定）").isNull();
        assertThat(g.getSignDueAt()).as("R30:默认 3 天签署期").isNotNull();
        verify(gateMapper, times(1)).insert(any(Gate.class));
        verify(auditLogService, times(1)).append(any());
    }

    @Test
    @DisplayName("AC#2 非法 gateCode ⇒ 拒")
    void invalidGateCode_rejected() {
        assertThatThrownBy(() -> service.autoCreateGate(700L, "XYZ", 999L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("gateCode");
    }

    @Test
    @DisplayName("AC#3 ARCHIVED 项目 ⇒ 拒")
    void archivedProject_rejected() {
        Project p = activeProject(700L);
        p.setStatus("ARCHIVED");
        when(projectMapper.selectById(700L)).thenReturn(p);
        assertThatThrownBy(() -> service.autoCreateGate(700L, "G3", 999L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("归档");
    }

    @Test
    @DisplayName("AC#4 F6-① 在途去重：同项目同 gateCode 已有 PENDING 轮次 ⇒ 拒")
    void inFlightPending_rejected() {
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(gateMapper.selectCount(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.autoCreateGate(700L, "G3", 999L))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("在途");
        verify(gateMapper, never()).insert(any(Gate.class));
    }

    /**
     * F6-① 的**承重测试**。
     *
     * <p>为什么必须单独加这一条：上面两条用例 mock 的是 {@code gateMapper.selectCount()} 的
     * **返回值**，看不见**查询谓词**。实测把生产谓词从 {@code eq(status,'PENDING')} 改成
     * {@code isNull(status)}（等价于「不按在途拦」），这两条用例依然全绿——绿灯证明不了
     * 「在途去重真的按 PENDING 过滤」，只证明「count&gt;0 时会抛异常」。
     *
     * <p>本条改为捕获真实的 {@code LambdaQueryWrapper}，渲染 SQL 片段并检查参数表，
     * 把谓词本身钉住：改谓词就会红。
     */
    @Test
    @DisplayName("AC#4c F6-① 承重：在途去重的查询谓词必须按 status='PENDING' 过滤（承重，非烟雾）")
    void inFlightQueryFiltersByPendingStatus() {
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(gateMapper.selectCount(any())).thenReturn(0L);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Gate>> captor =
            org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        service.autoCreateGate(700L, "G3", 999L);
        verify(gateMapper).selectCount(captor.capture());

        com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?> w = captor.getValue();
        // 必须先渲染 SQL 片段：eq() 把值包在惰性 Supplier 里，不渲染就永远不写进参数表。
        String sqlSegment = w.getSqlSegment();
        java.util.Collection<Object> params = w.getParamNameValuePairs().values();

        assertThat(sqlSegment).contains("project_id").contains("gate_code").contains("status");
        assertThat(params).contains("G3");
        // 这条是 F6-① 的核心：必须带 PENDING 状态过滤。缺了它，「同项目同 Gate 已有在途轮次」
        // 就拦不住，会重复建卡。
        assertThat(params).contains(GateCreationService.STATUS_PENDING);
        // 反向确认：谓词不是 isNull(status)（那是「没有状态」的另一套含义，等于不拦）
        assertThat(sqlSegment).doesNotContain("IS NULL");
    }

    @Test
    @DisplayName("AC#4b F6-① 语义反转：无在途 PENDING 即建成功（不再受 14 天时间冷却约束）")
    void noInFlight_creates_evenIfLastOneWasLongAgo() {
        // F6-① 之前的口径：14 天内建过同 gateCode 就拒。本用例锁死新语义——
        // 上一次建卡无论多久以前，只要当前没有 PENDING 在途，就允许再建（G3 双周复评的正解）。
        when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
        when(gateMapper.selectCount(any())).thenReturn(0L);
        Gate g = service.autoCreateGate(700L, "G3", 999L);
        assertThat(g.getStatus()).isEqualTo("PENDING");
        verify(gateMapper, times(1)).insert(any(Gate.class));
    }

    @Test
    @DisplayName("AC#5 项目不存在 ⇒ 拒")
    void missingProject_rejected() {
        when(projectMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> service.autoCreateGate(999L, "G3", 999L))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("AC#6 全部 5 gateCode 合法（G1/G2/G3/G4/G5）")
    void allGateCodes_accepted() {
        for (String code : GateCreationService.ALLOWED_GATE_CODES) {
            when(projectMapper.selectById(700L)).thenReturn(activeProject(700L));
            when(gateMapper.selectCount(any())).thenReturn(0L);
            Gate g = service.autoCreateGate(700L, code, 999L);
            assertThat(g.getGateCode()).isEqualTo(code);
        }
    }
}