package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.domain.RequirementChange;
import org.ruoyi.ipd.mapper.RequirementChangeMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R33 一期 · 分片 E：C6 需求变更并发红绿对照（规格 §1 C6 行「四道防线全缺」/ §6 验收）。
 *
 * <p>C6 缺陷：并行双签（市场PM ∥ 研发PM）第二签决策走「读 signatures → 内存聚合 →
 * updateById 全量写」，无乐观锁/CAS/唯一键/串行化——后写覆盖先写，「;」聚合签名串丢失
 * 一方签名，双签永不合拢。修复为 CAS 谓词翻转（对齐 C2 模式）：
 * 谓词 = id + status=PENDING_SIGN + signatures 读改写窗口旧值，未命中 0 行 =
 * {@code guardSupport.requireCasHit} 抛并发冲突。
 *
 * <p><b>红绿对照（自证能红）</b>：
 * <ul>
 *   <li>绿：CAS 命中单胜——第一签 update 返回 1 通过、并发第二签 update 返回 0 抛并发冲突</li>
 *   <li>红：临时撤掉 CAS 谓词/requireCasHit（模拟旧 updateById 双写）→ ②③④⑤ 因
 *       「未抛并发冲突」断言失败而红、⑥ 因 wrapper 谓词断言而红；恢复 CAS 后全绿</li>
 *   <li>既有测试（RequirementChangeSecurityRound2Test / P262AcceptanceTest /
 *       P2_6_2_DualSignStageGuardTest）补 CAS 命中 stub 后保持绿</li>
 * </ul>
 *
 * <p>R-8 红线：「;」字符串聚合签名串为存量存储行为，本文件不引入新存储形态（零变更）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("R33 C6 需求变更并发红绿对照（CAS 单胜）")
class RequirementChangeConcurrencyTest {

    private static final Long CHANGE_ID = 99L;
    private static final Long REQUIREMENT_ID = 7L;

    private static final IpdActor MARKET_PM = new IpdActor(101L, "mkt", "MARKET_PM", 11L);
    private static final IpdActor RD_PM = new IpdActor(202L, "rd", "RD_PM", 11L);

    /** 新增并发冲突文案（逐字冻结，报告已列明；三写点共用） */
    private static final String CAS_CONFLICT =
        "签署并发冲突：该变更单已被其他签署方同时处理，请刷新后重试";

    @Mock private RequirementChangeMapper changeMapper;
    @Mock private RequirementMapper reqMapper;
    @Mock private IAuditLogService auditLogService;
    @Mock private StateMachineGuard stateMachineGuard;

    private RequirementChangeService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, RequirementChange.class);
        TableInfoHelper.initTableInfo(assistant, Requirement.class);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new RequirementChangeService(changeMapper, reqMapper, auditLogService);
        service.setStateMachineGuard(stateMachineGuard);
        lenient().when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** PENDING_SIGN 变更单（signatures 可为 null=无签名 / 已有聚合串） */
    private RequirementChange pendingChange(String signatures) {
        RequirementChange c = new RequirementChange();
        c.setId(CHANGE_ID);
        c.setRequirementId(REQUIREMENT_ID);
        c.setProjectId(33L);
        c.setStatus(RequirementChangeService.STATUS_PENDING_SIGN);
        c.setSignatures(signatures);
        return c;
    }

    /* ---------- ① CAS 命中单胜：第一签 update 返回 1 通过 ---------- */

    @Test
    @DisplayName("① 第一签 CAS 命中（update 返 1）→ 通过，PENDING_SIGN 累计签名")
    void firstSign_casHit_singleWinner_passes() {
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange(null));
        when(changeMapper.update(isNull(), any())).thenReturn(1);

        RequirementChange out = service.sign(CHANGE_ID, "APPROVE", "ok", MARKET_PM);

        assertThat(out.getStatus()).isEqualTo(RequirementChangeService.STATUS_PENDING_SIGN);
        assertThat(out.getSignatures()).contains("MARKET_PM:101=APPROVE");
        verify(changeMapper, times(1)).update(isNull(), any());
        // 红绿对照：旧实现走 updateById 裸写；CAS 化后 updateById 不再被调用
        verify(changeMapper, never()).updateById(any(RequirementChange.class));
    }

    @Test
    @DisplayName("① 第二签 CAS 命中（update 返 1）→ 齐签单胜，APPROVED + 回写需求池")
    void secondSign_casHit_singleWinner_approves() {
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange("MARKET_PM:101=APPROVE"));
        when(reqMapper.selectById(REQUIREMENT_ID))
            .thenReturn(Requirement.builder().id(REQUIREMENT_ID).status("SUBMITTED").build());
        when(changeMapper.update(isNull(), any())).thenReturn(1);

        RequirementChange out = service.sign(CHANGE_ID, "APPROVE", "ok", RD_PM);

        assertThat(out.getStatus()).isEqualTo(RequirementChangeService.STATUS_APPROVED);
        assertThat(out.getSignatures()).contains("MARKET_PM:101=APPROVE").contains("RD_PM:202=APPROVE");
        verify(changeMapper, never()).updateById(any(RequirementChange.class));
    }

    /* ---------- ② 并行双签败者（update 返 0）→ 抛并发冲突（旧裸写静默双写=红） ---------- */

    @Test
    @DisplayName("② 并行双签第一签败者（update 返 0）→ 抛并发冲突文案逐字（撤 CAS 则本例红）")
    void firstSign_casMiss_concurrentConflict_throws() {
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange(null));
        when(changeMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.sign(CHANGE_ID, "APPROVE", "ok", MARKET_PM))
            .isInstanceOf(ServiceException.class)
            .hasMessage(CAS_CONFLICT);
        verify(changeMapper, never()).updateById(any(RequirementChange.class));
    }

    @Test
    @DisplayName("② 并行双签第二签败者（update 返 0）→ 抛并发冲突，不再双写覆盖（单胜）")
    void secondSign_casMiss_concurrentConflict_throws() {
        // 并发语义：另一方已把 signatures 翻新（读改写窗口旧值失效）→ CAS 谓词 miss → 0 行
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange("MARKET_PM:101=APPROVE"));
        when(reqMapper.selectById(REQUIREMENT_ID))
            .thenReturn(Requirement.builder().id(REQUIREMENT_ID).status("SUBMITTED").build());
        when(changeMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.sign(CHANGE_ID, "APPROVE", "ok", RD_PM))
            .isInstanceOf(ServiceException.class)
            .hasMessage(CAS_CONFLICT);
        verify(changeMapper, never()).updateById(any(RequirementChange.class));
    }

    @Test
    @DisplayName("② REJECT 决策 CAS miss（update 返 0）→ 抛并发冲突（撤 CAS 则本例红）")
    void reject_casMiss_concurrentConflict_throws() {
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange("MARKET_PM:101=APPROVE"));
        when(changeMapper.update(isNull(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.sign(CHANGE_ID, "REJECT", "成本过高", RD_PM))
            .isInstanceOf(ServiceException.class)
            .hasMessage(CAS_CONFLICT);
        verify(changeMapper, never()).updateById(any(RequirementChange.class));
    }

    /* ---------- ③ 红绿自证：CAS 谓词存在性断言（撤谓词回旧裸写 → 本组红） ---------- */

    @Test
    @DisplayName("③ 单方签 CAS 谓词含 status + signatures（含旧值；谓词撤除 → 红）")
    void partialSign_casPredicate_containsStatusAndSignatures() {
        // 真单方 partial 场景：已有另一市场PM签名，尚无 RD_PM=APPROVE（未合拢）→ 走单方累计路径
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange("MARKET_PM:303=APPROVE"));
        when(changeMapper.update(isNull(), any())).thenReturn(1);

        service.sign(CHANGE_ID, "APPROVE", "ok", MARKET_PM);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<RequirementChange>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(changeMapper).update(isNull(), cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertThat(sql).contains("status");
        assertThat(sql).contains("signatures");
    }

    @Test
    @DisplayName("③ 首签（signatures=null）CAS 谓词 signatures IS NULL（null 语义保持；谓词撤除 → 红）")
    void firstSign_casPredicate_nullSignatures() {
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange(null));
        when(changeMapper.update(isNull(), any())).thenReturn(1);

        service.sign(CHANGE_ID, "APPROVE", "ok", MARKET_PM);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<RequirementChange>> cap = ArgumentCaptor.forClass(Wrapper.class);
        verify(changeMapper).update(isNull(), cap.capture());
        String sql = cap.getValue().getSqlSegment();
        assertThat(sql).contains("signatures");
        assertThat(sql).contains("IS NULL");
    }

    /* ---------- ④ C6 接线保持：齐签迁移点仍走 StateMachineGuard ---------- */

    @Test
    @DisplayName("④ 齐签迁移点接线保持：preCheck(requirement_change,PENDING_SIGN→APPROVED,sign) + postCommit")
    void secondSign_wiredToStateMachineGuard() {
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange("MARKET_PM:101=APPROVE"));
        when(reqMapper.selectById(REQUIREMENT_ID))
            .thenReturn(Requirement.builder().id(REQUIREMENT_ID).status("SUBMITTED").build());
        when(changeMapper.update(isNull(), any())).thenReturn(1);

        service.sign(CHANGE_ID, "APPROVE", "ok", RD_PM);

        verify(stateMachineGuard).preCheck("requirement_change",
            RequirementChangeService.STATUS_PENDING_SIGN, RequirementChangeService.STATUS_APPROVED, "sign");
        verify(stateMachineGuard).postCommit(
            org.mockito.ArgumentMatchers.eq("requirement_change"),
            org.mockito.ArgumentMatchers.eq(RequirementChangeService.STATUS_PENDING_SIGN),
            org.mockito.ArgumentMatchers.eq(RequirementChangeService.STATUS_APPROVED),
            org.mockito.ArgumentMatchers.eq("sign"),
            org.mockito.ArgumentMatchers.eq(RD_PM.id()),
            org.mockito.ArgumentMatchers.eq(CHANGE_ID),
            any(java.util.Date.class));
    }

    /* ---------- ⑤ R-8 存量行为零变更：「;」聚合签名串格式不动 ---------- */

    @Test
    @DisplayName("⑤ 「;」聚合签名串格式逐字保持（R-8 红线：存量存储行为零变更）")
    void signatureAggregationFormat_unchanged() {
        when(changeMapper.selectById(CHANGE_ID)).thenReturn(pendingChange("MARKET_PM:101=APPROVE"));
        when(reqMapper.selectById(REQUIREMENT_ID))
            .thenReturn(Requirement.builder().id(REQUIREMENT_ID).status("SUBMITTED").build());
        when(changeMapper.update(isNull(), any())).thenReturn(1);

        RequirementChange out = service.sign(CHANGE_ID, "APPROVE", "ok", RD_PM);

        assertThat(out.getSignatures()).isEqualTo("MARKET_PM:101=APPROVE;RD_PM:202=APPROVE");
    }
}
