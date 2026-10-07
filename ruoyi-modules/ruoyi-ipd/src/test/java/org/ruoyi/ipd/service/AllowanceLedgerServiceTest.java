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
import org.mockito.ArgumentCaptor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AllowanceLedger;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.AllowanceLedgerMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ④刀「津贴按原需求」第 2 批契约测试（2026-10-07）。
 *
 * <p>覆盖两项改造：① A4-63 台账查询数据范围过滤；② A4-60 确认停发仅超管。
 *
 * <p>断言方式沿用本仓既有范式（IpdActionWriterMemberTest / DemandCatalogBinderTest）：
 * 捕获 {@code LambdaQueryWrapper}，经 {@code getSqlSegment()} 还原 WHERE 片段，
 * 经 {@code getParamNameValuePairs()} 还原绑定值——不 mock 掉被测的过滤逻辑本身。
 *
 * <p>不覆盖 A4-72（{@code calcFinalAmount} / {@code draftBinding} 接线）：核实结论是
 * <b>不该接</b>——两者与真写路径 {@code AllowanceService.generateMonthlyLedgers}
 * 的封顶基准不同（前者取列表首元素、后者取最高锁定额），接上会改钱；
 * {@code draftBinding} 还会把第 1 批的真削减/负反馈/低分停发结果覆盖回未调整的锁定额。
 * 详见交付报告。
 */
@Tag("dev")
class AllowanceLedgerServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "allowance-ledger-svc");
        TableInfoHelper.initTableInfo(assistant, AllowanceLedger.class);
        TableInfoHelper.initTableInfo(assistant, Person.class);
    }

    private AllowanceLedgerMapper ledgerMapper;
    private PersonMapper personMapper;
    private AllowanceLedgerService svc;

    @BeforeEach
    void setUp() {
        ledgerMapper = mock(AllowanceLedgerMapper.class);
        personMapper = mock(PersonMapper.class);
        svc = new AllowanceLedgerService(ledgerMapper);
        svc.setPersonMapper(personMapper);
        when(ledgerMapper.selectList(any())).thenReturn(List.of());
        when(personMapper.selectList(any())).thenReturn(List.of());
    }

    // ==================== ① A4-63 数据范围过滤 ====================

    @Test
    @DisplayName("A4-63 超管：list 不加 person_id 范围约束（全量可见口径不变）")
    void superAdminSeesEverything() {
        IpdActor admin = new IpdActor(1L, "超管", "SUPER_ADMIN", null);

        svc.list(admin, "2026-10", null);

        LambdaQueryWrapper<AllowanceLedger> w = capturedLedgerQuery();
        // 只断言 IN 形态缺席：ORDER BY 子句本身就含 person_id，不能拿整段 doesNotContain
        assertThat(w.getSqlSegment()).contains("month =").doesNotContain("person_id IN");
        verify(personMapper, never()).selectList(any());
    }

    @Test
    @DisplayName("A4-63 非超管：list 收敛到「自己 + 自己组」，不再返回全公司明细")
    void nonAdminScopedToSelfPlusGroup() {
        IpdActor leader = new IpdActor(10L, "组长", "GROUP_LEADER", 100L);
        when(personMapper.selectList(any())).thenReturn(List.of(
            person(10L, 100L), person(11L, 100L), person(12L, 100L)));

        svc.list(leader, "2026-10", null);

        LambdaQueryWrapper<AllowanceLedger> w = capturedLedgerQuery();
        assertThat(w.getSqlSegment()).contains("person_id IN");
        // 绑定值里 month 是 String、personId 是 Long ⇒ 按类型挑出可见集合精确断言，
        // 既证「含同组」也证「不含他人组」（前者用 contains 会漏掉后者）
        assertThat(personIdParams(w))
            .as("可见集合 = 自己 10 + 同组 11/12，不含他人组")
            .containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(w.getParamNameValuePairs().values()).contains("2026-10");
    }

    @Test
    @DisplayName("A4-63 关键回归：personId 传 null 时旧口径返回全公司——现在必须带 person_id IN")
    void nullPersonIdNoLongerLeaksWholeCompany() {
        IpdActor pm = new IpdActor(20L, "市场PM", "MARKET_PM", 200L);
        when(personMapper.selectList(any())).thenReturn(List.of(person(20L, 200L)));

        svc.list(pm, "2026-10", null);

        // 这条正是缺口本体：personId=null + 无范围过滤 = 全公司津贴金额可拉取
        assertThat(capturedLedgerQuery().getSqlSegment()).contains("person_id IN");
    }

    @Test
    @DisplayName("A4-63 pendingStop 同样收敛（停发原因属薪酬敏感信息）")
    void pendingStopScoped() {
        IpdActor pm = new IpdActor(20L, "研发PM", "RD_PM", 200L);
        when(personMapper.selectList(any())).thenReturn(List.of(person(20L, 200L), person(21L, 200L)));

        svc.pendingStop(pm, "2026-10");

        LambdaQueryWrapper<AllowanceLedger> w = capturedLedgerQuery();
        assertThat(w.getSqlSegment()).contains("person_id IN", "stop_reason IS NOT NULL");
        assertThat(personIdParams(w)).containsExactlyInAnyOrder(20L, 21L);
    }

    @Test
    @DisplayName("A4-63 调用方 personId 与范围过滤是 AND：查他人 ⇒ 落空集（返回空，不泄漏存在性）")
    void callerPersonIdIsAndedNotOverridden() {
        IpdActor leader = new IpdActor(10L, "组长", "GROUP_LEADER", 100L);
        when(personMapper.selectList(any())).thenReturn(List.of(person(10L, 100L)));

        svc.list(leader, "2026-10", 999L);

        LambdaQueryWrapper<AllowanceLedger> w = capturedLedgerQuery();
        // 两个条件都在（999 由调用方给出，可见集合由范围给出），交由 SQL 求交
        assertThat(w.getSqlSegment()).contains("person_id =", "person_id IN");
        assertThat(personIdParams(w)).contains(999L, 10L);
    }

    @Test
    @DisplayName("A4-63 退化方向：PersonMapper 未装配 ⇒ 收敛到仅本人，绝不退化为全量")
    void degradesToSelfOnlyWithoutPersonMapper() {
        svc.setPersonMapper(null);
        IpdActor leader = new IpdActor(10L, "组长", "GROUP_LEADER", 100L);

        svc.list(leader, "2026-10", null);

        LambdaQueryWrapper<AllowanceLedger> w = capturedLedgerQuery();
        assertThat(w.getSqlSegment()).contains("person_id IN");
        assertThat(personIdParams(w)).containsExactly(10L);
    }

    @Test
    @DisplayName("A4-63 无 groupId 的 actor ⇒ 仅本人可见（无归属不放开）")
    void actorWithoutGroupSeesOnlySelf() {
        IpdActor pm = new IpdActor(30L, "无组PM", "MARKET_PM", null);

        svc.list(pm, "2026-10", null);

        assertThat(personIdParams(capturedLedgerQuery())).containsExactly(30L);
    }

    @Test
    @DisplayName("A4-63 可见集合永不为空：同组查询返回空时仍保底含自己（不会生成 IN () 语法错）")
    void visibleSetNeverEmpty() {
        IpdActor leader = new IpdActor(10L, "组长", "GROUP_LEADER", 100L);
        when(personMapper.selectList(any())).thenReturn(List.of());

        svc.list(leader, "2026-10", null);

        assertThat(personIdParams(capturedLedgerQuery())).containsExactly(10L);
    }

    // ==================== ② A4-60 确认停发仅超管 ====================

    @Test
    @DisplayName("A4-60 行为变更：产品组长确认停发由放行变 403（原口径含 GROUP_LEADER）")
    void groupLeaderConfirmStopRejected() {
        IpdActor leader = new IpdActor(10L, "组长", "GROUP_LEADER", 100L);

        assertThatThrownBy(() -> svc.confirmStop(leader, 1L))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.FORBIDDEN));

        // 权限失败不得触达 Mapper
        verify(ledgerMapper, never()).selectById(any());
        verify(ledgerMapper, never()).updateById(any(AllowanceLedger.class));
    }

    @Test
    @DisplayName("A4-60 双 PM 同样被拒（确认停发是超管专属资金动作）")
    void pmConfirmStopRejected() {
        for (String role : List.of("MARKET_PM", "RD_PM")) {
            IpdActor pm = new IpdActor(20L, "PM", role, 200L);
            assertThatThrownBy(() -> svc.confirmStop(pm, 1L))
                .isInstanceOf(IpdBusinessException.class);
        }
        verify(ledgerMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("A4-60 超管放行：终额置 0 并落库")
    void superAdminConfirmStopZeroesAmount() {
        IpdActor admin = new IpdActor(1L, "超管", "SUPER_ADMIN", null);
        AllowanceLedger row = AllowanceLedger.builder()
            .id(1L).personId(20L).projectId(10L).month("2026-10")
            .stopReason("STOP_SCORE_BELOW_60")
            .finalAmount(new BigDecimal("2000.00")).build();
        when(ledgerMapper.selectById(1L)).thenReturn(row);

        AllowanceLedger out = svc.confirmStop(admin, 1L);

        assertThat(out.getFinalAmount()).isEqualByComparingTo("0");
        verify(ledgerMapper).updateById(row);
    }

    @Test
    @DisplayName("A4-60 已是 0 时原样返回且不重复写库（幂等）")
    void confirmStopIdempotentWhenAlreadyZero() {
        IpdActor admin = new IpdActor(1L, "超管", "SUPER_ADMIN", null);
        AllowanceLedger row = AllowanceLedger.builder()
            .id(1L).personId(20L).stopReason("STOP_SCORE_BELOW_60")
            .finalAmount(BigDecimal.ZERO).build();
        when(ledgerMapper.selectById(1L)).thenReturn(row);

        assertThat(svc.confirmStop(admin, 1L).getFinalAmount()).isEqualByComparingTo("0");
        verify(ledgerMapper, never()).updateById(any(AllowanceLedger.class));
    }

    @Test
    @DisplayName("A4-60 无停发原因的行按 404 处理（不区分不存在/无标记，防存在性 oracle）")
    void confirmStopWithoutStopReasonIsNotFound() {
        IpdActor admin = new IpdActor(1L, "超管", "SUPER_ADMIN", null);
        when(ledgerMapper.selectById(5L)).thenReturn(AllowanceLedger.builder()
            .id(5L).personId(20L).stopReason(null).finalAmount(new BigDecimal("100")).build());

        assertThatThrownBy(() -> svc.confirmStop(admin, 5L))
            .isInstanceOf(IpdBusinessException.class)
            .satisfies(e -> assertThat(((IpdBusinessException) e).getErrorCode())
                .isEqualTo(ApiV1ErrorCode.NOT_FOUND));
        verify(ledgerMapper, never()).updateById(any(AllowanceLedger.class));
    }

    // ==================== helpers ====================

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<AllowanceLedger> capturedLedgerQuery() {
        ArgumentCaptor<LambdaQueryWrapper<AllowanceLedger>> c =
            ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(ledgerMapper).selectList(c.capture());
        return c.getValue();
    }

    /**
     * 从绑定值里挑出 personId 相关的 Long 参数。
     *
     * <p><b>必须先触发 {@code getSqlSegment()}</b>：MyBatis-Plus 的 {@code paramNameValuePairs}
     * 是在渲染 SQL 片段时才填充的，单独读它是空 map。只在这里补渲染调用，
     * 免得每个用例都得记住「先断言片段、再断言绑定值」的顺序（本轮已实测踩过：
     * 两个只读绑定值的用例因未渲染而拿到空列表报红）。
     *
     * <p>同一个 map 里还有 month（String）等参数，直接 containsExactly 会把它们算进去；
     * 而 personId 恒为 Long、month 恒为 String，按类型挑出即可对可见集合做精确断言。
     */
    private static List<Object> personIdParams(LambdaQueryWrapper<AllowanceLedger> w) {
        w.getSqlSegment();
        return w.getParamNameValuePairs().values().stream()
            .filter(v -> v instanceof Long)
            .collect(Collectors.toList());
    }

    private static Person person(Long id, Long groupId) {
        return Person.builder().id(id).groupId(groupId).build();
    }
}
