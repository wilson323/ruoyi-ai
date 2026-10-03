package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
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
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateArbitration;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.domain.GateElementResult;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.OssFileEntity;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.mapper.GateArbitrationMapper;
import org.ruoyi.ipd.mapper.GateElementMapper;
import org.ruoyi.ipd.mapper.GateElementResultMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.OssFileMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R11 / A4 验收：工作台「GR- 签署卡真活永不投递」死路的占位行生产者（GateReviewService.openSignQueue）。
 *
 * <p>病根（docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md §二 A4 / §三）：
 * KeyGateAggregator 锚 {@code reviewer_id=actor AND decision IS NULL}，但 R30 改道后
 * 全仓无任何生产者落 decision=NULL 行（sign 落带值、insertAbstain 落 ABSTAIN、
 * GateCreationService 不再建占位行）——真库探针实证 10 个已提交待签 PENDING gate 中
 * 8 个 gate_reviews 零行。修复方向甲：gate 提交（startedAt 置位）即为本轮应签方预落待签占位行。
 *
 * <p>本类按登记 §一规约「未办态用例优先走真实写入路径构造数据」（P254AcceptanceTest 范式）：
 * 占位行一律由 openSignQueue / submit 真实产生，不用 builder 直造 decision=NULL 行；
 * 内存签名簿 fake 让 insert / selectList / updateById 共享同一份行集合，
 * 从而能真断言「UPDATE 原行、行数不增」这一 A4 的核心防回归点。
 *
 * <p>覆盖面（任务书 A4-⑤ 六项 + 两项对偶补强）：
 * <ol>
 *   <li>submit 置位后本轮 decision=NULL 行数 == 应签人数（双签 2 行 / 单签 1 行）</li>
 *   <li>sign 命中原占位行走 UPDATE，行数不增（防撞 uk_gr_gate_type_round）</li>
 *   <li>requireNotSigned 不把占位行算已签（否则预落后本人永无法签署）</li>
 *   <li>advance / hasPmConflict 只按已决行（占位行不参与齐签判定与分歧判定）</li>
 *   <li>重复 submit / 重复 openSignQueue 幂等（同轮同角色不重复预落）</li>
 *   <li>解析不到签署人时 warn 跳过不抛错（保护存量无 PM 成员的在途 gate）</li>
 *   <li>view 不把占位行回显为 my / otherSubmitted</li>
 *   <li>scanTimeout 对占位行落 ABSTAIN 走 UPDATE，不新增撞唯一键</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class GateSignQueueAcceptanceTest {

    @Mock
    private GateMapper gateMapper;
    @Mock
    private GateReviewMapper reviewMapper;
    @Mock
    private ProjectMemberMapper memberMapper;
    @Mock
    private PersonMapper personMapper;
    @Mock
    private GateArbitrationMapper arbitrationMapper;
    @Mock
    private GateReviewObserverMapper observerMapper;
    @Mock
    private ISystemConfigService systemConfigService;
    @Mock
    private IAuditLogService auditLogService;
    @Mock
    private NotificationService notificationService;

    /* ---- submit 真链（GateElementResultService）所需，用于 wiring 行为锁 ---- */
    @Mock
    private GateElementMapper elementMapper;
    @Mock
    private GateElementResultMapper resultMapper;
    @Mock
    private OssFileMapper ossFileMapper;
    /** sign() 的 gate→项目→组 归属断言所需（R212-④ 同款注入点）。 */
    @Mock
    private ProjectMapper projectMapper;

    private GateReviewService service;
    private GateElementResultService submitService;

    private static final IpdActor MARKET = new IpdActor(301L, "陈市场", "MARKET_PM", 7L);
    private static final IpdActor RD = new IpdActor(302L, "刘研发", "RD_PM", 7L);
    private static final IpdActor SUPER = new IpdActor(303L, "系统管理员", "SUPER_ADMIN", null);
    /** 他组成员：用于 sign() 跨组越权负例（组 ≠ 项目主组）。 */
    private static final IpdActor OUTSIDER = new IpdActor(304L, "外组市场", "MARKET_PM", 8L);

    private Gate gate;
    /** 内存 gate_reviews 签名簿：insert / updateById / selectList 同源，可真断言「行数不增」。 */
    private final List<GateReview> reviewRows = new ArrayList<>();
    /** 在册项目成员池：可清空以模拟「该角色无签署人」的数据治理缺失态。 */
    private final List<ProjectMember> members = new ArrayList<>();

    private static final long GATE_ID = 601L;
    private static final long PROJECT_ID = 11L;

    @BeforeAll
    static void initMybatisMeta() {
        MapperBuilderAssistant a = new MapperBuilderAssistant(new MybatisConfiguration(), "gsq");
        TableInfoHelper.initTableInfo(a, GateReview.class);
        TableInfoHelper.initTableInfo(a, Gate.class);
        TableInfoHelper.initTableInfo(a, GateArbitration.class);
        TableInfoHelper.initTableInfo(a, ProjectMember.class);
        TableInfoHelper.initTableInfo(a, Person.class);
        TableInfoHelper.initTableInfo(a, GateElementResult.class);
        TableInfoHelper.initTableInfo(a, GateElement.class);
    }

    @BeforeEach
    void setUp() {
        service = new GateReviewService(gateMapper, reviewMapper, memberMapper, personMapper,
            arbitrationMapper, observerMapper, systemConfigService, auditLogService, notificationService);
        // 归属断言 fail-closed：未装配 ProjectMapper 一律「无权操作」，故必须注入。
        service.setProjectMapper(projectMapper);
        lenient().when(projectMapper.selectById(PROJECT_ID))
            .thenReturn(Project.builder().id(PROJECT_ID).mainGroupId(MARKET.groupId())
                .status("ACTIVE").delFlag("0").build());
        org.ruoyi.ipd.service.impl.DefaultStateMachineGuard guard =
            new org.ruoyi.ipd.service.impl.DefaultStateMachineGuard(auditLogService, notificationService);
        guard.initRules();
        service.setStateMachineGuard(guard);

        gate = new Gate();
        gate.setId(GATE_ID);
        gate.setProjectId(PROJECT_ID);
        gate.setGateCode("G1");
        gate.setStatus("PENDING");
        gate.setCurrentRound(1);
        gate.setStartedAt(new Date());
        gate.setSignDueAt(new Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3)));

        reviewRows.clear();
        members.clear();
        members.add(member(301L, "MARKET_PM"));
        members.add(member(302L, "RD_PM"));

        lenient().when(gateMapper.selectById(GATE_ID)).thenReturn(gate);
        lenient().when(gateMapper.updateById(any(Gate.class))).thenReturn(1);
        lenient().when(gateMapper.update(any(), any())).thenReturn(1);
        // 默认为「无其他在途 gate」：scanTimeout 用例按需要覆写
        lenient().when(gateMapper.selectList(any())).thenReturn(List.of());
        lenient().when(reviewMapper.insert(any(GateReview.class))).thenAnswer(inv -> {
            reviewRows.add(inv.getArgument(0));
            return 1;
        });
        lenient().when(reviewMapper.updateById(any(GateReview.class))).thenReturn(1);
        lenient().when(reviewMapper.selectList(any()))
            .thenAnswer(inv -> reviewRowsMatching(reviewRows, inv.getArgument(0)));
        lenient().when(arbitrationMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>());
        lenient().when(arbitrationMapper.insert(any(GateArbitration.class))).thenReturn(1);
        lenient().when(systemConfigService.getIntValue(anyString(), eq(3))).thenReturn(3);
        lenient().when(systemConfigService.getIntValue(anyString(), eq(5))).thenReturn(5);
        // 按 wrapper 参数袋里的角色字面量分流返回——抗调用次序漂移，且与真库 SQL 语义一致
        lenient().when(memberMapper.selectList(any())).thenAnswer(inv -> membersMatching(members, inv.getArgument(0)));
    }

    private static ProjectMember member(long personId, String role) {
        ProjectMember m = new ProjectMember();
        m.setPersonId(personId);
        m.setRole(role);
        m.setProjectId(PROJECT_ID);
        return m;
    }

    /**
     * 内存 gate_reviews 簿按 wrapper 的 gateId(Long) / round(Integer) 过滤——
     * 忠实复刻 roundRows(gateId, round) 的 SQL 语义，幂等与新轮次预落才能被真断言
     * （若 selectList 无脑返回全量，「同轮同角色跳过」会退化成「任意历史行即跳过」，假绿）。
     */
    private static List<GateReview> reviewRowsMatching(List<GateReview> pool, Object wrapper) {
        AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
        w.getSqlSegment();
        Collection<Object> values = w.getParamNameValuePairs().values();
        List<Long> gateIds = values.stream().filter(v -> v instanceof Long).map(v -> (Long) v).toList();
        List<Integer> rounds = values.stream().filter(v -> v instanceof Integer).map(v -> (Integer) v).toList();
        return pool.stream()
            .filter(r -> gateIds.isEmpty() || gateIds.contains(r.getGateId()))
            .filter(r -> rounds.isEmpty() || rounds.contains(r.getRound()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** 从 LambdaQueryWrapper 参数袋提取签署角色过滤值（MARKET_PM / RD_PM），无单角色过滤则视作全集。 */
    private static List<ProjectMember> membersMatching(List<ProjectMember> pool, Object wrapper) {
        AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) wrapper;
        // MyBatis-Plus 的参数袋是惰性填充的（值段是 ISqlSegment lambda），
        // 先触发一次 SQL 段生成，getParamNameValuePairs() 里才有 eq/in 的实值。
        w.getSqlSegment();
        Collection<Object> values = w.getParamNameValuePairs().values();
        boolean market = values.contains("MARKET_PM");
        boolean rd = values.contains("RD_PM");
        if (market && rd) {
            return new ArrayList<>(pool);
        }
        if (market || rd) {
            String role = market ? "MARKET_PM" : "RD_PM";
            return pool.stream().filter(m -> role.equals(m.getRole())).toList();
        }
        return new ArrayList<>(pool);
    }

    private List<GateReview> pendingRows() {
        return reviewRows.stream().filter(r -> r.getDecision() == null).toList();
    }

    private List<String> auditActions() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogService, atLeastOnce()).append(captor.capture());
        return captor.getAllValues().stream().map(AuditLog::getAction).toList();
    }

    /** 走真实写入路径：直接调生产者（openSignQueue），等价于 submit 置位后的效果。 */
    private void openQueue() {
        service.openSignQueue(gate);
    }

    // ==================== ① 生产者：submit 置位即预落本轮待签占位行 ====================

    @Test
    @DisplayName("A4①：G1 提交（startedAt 置位）⇒ 本轮预落 2 条 decision=NULL 占位行（MARKET_PM + RD_PM）")
    void submit_preallocatesPlaceholderRows_forDualSignGate() {
        submitService = new GateElementResultService(gateMapper, elementMapper, resultMapper,
            systemConfigService, auditLogService, notificationService, ossFileMapper);
        submitService.setGateReviewService(service);
        gate.setStartedAt(null);
        gate.setSignDueAt(null);
        GateElement veto = element(601L, "G1-2", "1");
        GateElement g1 = element(602L, "G1-1", "0");
        GateElement normal = element(603L, "G1-3", "0");
        when(elementMapper.selectList(any())).thenReturn(List.of(veto, g1, normal));
        when(resultMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
            judged(601L, "PASS"), judged(602L, "PASS"), judged(603L, "CONDITIONAL")));
        when(gateMapper.selectList(any())).thenReturn(List.of());
        when(ossFileMapper.selectById(9001L)).thenReturn(oss(9001L));
        when(ossFileMapper.selectById(9002L)).thenReturn(oss(9002L));

        Gate submitted = submitService.submit(GATE_ID, 9001L, 9002L, MARKET);

        assertThat(submitted.getStartedAt()).isNotNull();
        // 生产者落地：本轮恰好 2 条待签行（== 双签应签人数），decision/opinion/signedAt 全空
        assertThat(reviewRows).hasSize(2);
        assertThat(pendingRows()).hasSize(2);
        assertThat(reviewRows).extracting(GateReview::getReviewerType)
            .containsExactlyInAnyOrder("MARKET_PM", "RD_PM");
        assertThat(reviewRows).extracting(GateReview::getReviewerId)
            .containsExactlyInAnyOrder(301L, 302L);
        for (GateReview r : reviewRows) {
            assertThat(r.getGateId()).isEqualTo(GATE_ID);
            assertThat(r.getRound()).isEqualTo(submitted.getCurrentRound());
            assertThat(r.getDecision()).isNull();
            assertThat(r.getOpinion()).isNull();
            assertThat(r.getSignedAt()).isNull();
            // dueAt 照 sign 流口径（submit 起算的 signDueAt）
            assertThat(r.getDueAt()).isEqualTo(submitted.getSignDueAt());
        }
    }

    @Test
    @DisplayName("A4①b：单签 Gate（G3）⇒ 只预落主导方 RD_PM 一行（leadSideOf 口径，不越权多投卡）")
    void openSignQueue_singleSignGate_onlyLeadSideRow() {
        gate.setGateCode("G3");

        openQueue();

        assertThat(reviewRows).hasSize(1);
        assertThat(reviewRows.get(0).getReviewerType()).isEqualTo("RD_PM");
        assertThat(reviewRows.get(0).getReviewerId()).isEqualTo(302L);
        assertThat(reviewRows.get(0).getDecision()).isNull();
    }

    // ==================== ⑤ 幂等 ====================

    @Test
    @DisplayName("A4⑤：重复 submit/重复 openSignQueue ⇒ 同轮同角色不重复预落（防撞 uk_gr_gate_type_round）")
    void openSignQueue_repeat_invocation_isIdempotent() {
        openQueue();
        openQueue();
        openQueue();

        assertThat(reviewRows).hasSize(2);
        verify(reviewMapper, times(2)).insert(any(GateReview.class));
    }

    @Test
    @DisplayName("A4⑤b：新一轮（reopen 后 round+1）重新预落——幂等只约束同轮，不吞掉新轮待签行")
    void openSignQueue_nextRound_preallocatesAgain() {
        openQueue();
        gate.setCurrentRound(2);

        openQueue();

        assertThat(reviewRows).hasSize(4);
        assertThat(reviewRows).filteredOn(r -> r.getRound() == 2).hasSize(2);
    }

    // ==================== ⑥ 无签署人：warn 跳过不抛（数据治理项另卡处理） ====================

    @Test
    @DisplayName("A4⑥：应签角色在 project_members 无在册人 ⇒ log.warn 跳过，不抛错、不阻断提交链路")
    void openSignQueue_missingSigner_skipsWithoutThrowing() {
        members.clear();                       // 真库探针：5 个在途 gate 无任何 PM 成员
        assertThatCode(this::openQueue).doesNotThrowAnyException();
        assertThat(reviewRows).isEmpty();

        members.add(member(301L, "MARKET_PM")); // 只缺 RD_PM ⇒ 仍为有人的那侧落行
        openQueue();
        assertThat(reviewRows).extracting(GateReview::getReviewerType).containsExactly("MARKET_PM");
    }

    // ==================== ① 跨组越权负例（sign 归属断言） ====================

    @Test
    @DisplayName("归属断言：他组签署人（同为 MARKET_PM 但组≠项目主组）签署本组 gate ⇒ 无权操作")
    void sign_actorGroupDiffersFromProjectGroup_rejected() {
        openQueue();
        int before = reviewRows.size();

        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", "越权签署", OUTSIDER))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("无权操作");

        // 拒绝发生在写库之前：签名簿不增行，gate 状态不被推进
        assertThat(reviewRows).hasSize(before);
        assertThat(reviewRows).allSatisfy(r -> assertThat(r.getDecision()).isNull());
        verify(gateMapper, never()).updateById(any(Gate.class));
    }

    @Test
    @DisplayName("归属断言：项目行缺失（归属链路不可解析）⇒ fail-closed 无权操作，不放行")
    void sign_projectRowMissing_failsClosed() {
        openQueue();
        when(projectMapper.selectById(PROJECT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", "同组签署", MARKET))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("无权操作");
        assertThat(reviewRows).allSatisfy(r -> assertThat(r.getDecision()).isNull());
    }

    // ==================== ② sign 命中占位行走 UPDATE ====================

    @Test
    @DisplayName("A4②：sign 命中原占位行 UPDATE 落决策——行数不增（否则撞 uk_gr_gate_type_round）")
    void sign_updatesExistingPlaceholder_rowCountUnchanged() {
        openQueue();
        int before = reviewRows.size();

        GateReview row = service.sign(GATE_ID, "APPROVE", "同意", MARKET);

        assertThat(reviewRows).hasSize(before);
        assertThat(row.getReviewerId()).isEqualTo(301L);
        assertThat(row.getDecision()).isEqualTo("APPROVE");
        assertThat(row.getOpinion()).isEqualTo("同意");
        assertThat(row.getSignedAt()).isNotNull();
        assertThat(pendingRows()).hasSize(1);  // 只剩 RD_PM 待签
        verify(reviewMapper, times(2)).insert(any(GateReview.class)); // 仅 openSignQueue 那 2 条
        verify(reviewMapper).updateById(row);
    }

    @Test
    @DisplayName("A4②b：无占位行（存量在途 gate / 旧路径）⇒ sign 退回 INSERT，行为兼容")
    void sign_withoutPlaceholder_fallsBackToInsert() {
        GateReview row = service.sign(GATE_ID, "APPROVE", "同意", MARKET);

        assertThat(reviewRows).hasSize(1);
        assertThat(row.getDecision()).isEqualTo("APPROVE");
        verify(reviewMapper).insert(any(GateReview.class));
    }

    // ==================== ③ requireNotSigned 不把占位行算已签 ====================

    @Test
    @DisplayName("A4③：占位行不计入「已签」——本人仍可签；真签完再签才被拒")
    void requireNotSigned_placeholderIsNotSigned() {
        openQueue();
        // 若把 decision=NULL 当已签，这里会抛「本轮您已签署」——预落即锁死签署入口（A4 反噬）
        assertThatCode(() -> service.sign(GATE_ID, "APPROVE", "同意", MARKET))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> service.sign(GATE_ID, "REJECT", "改主意", MARKET))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class)
            .hasMessageContaining("本轮您已签署，不可重复签署");
    }

    @Test
    @DisplayName("A4③b：签署授权口径零变化——超管即使有占位行也不可签（仅市场PM/研发PM）")
    void sign_superAdminStillRejected() {
        openQueue();

        assertThatThrownBy(() -> service.sign(GATE_ID, "APPROVE", null, SUPER))
            .isInstanceOf(org.ruoyi.ipd.common.IpdBusinessException.class)
            .hasMessageContaining("仅市场PM/研发PM可签署");
    }

    // ==================== ④ advance / hasPmConflict 只按已决行 ====================

    @Test
    @DisplayName("A4④：双签 Gate 首签后仍 PENDING——占位行不得被 rows.size()>=2 误判为齐签放行")
    void advance_placeholdersDoNotFakeDualSignCompletion() {
        openQueue();

        service.sign(GATE_ID, "APPROVE", "同意", MARKET);

        assertThat(gate.getStatus()).isEqualTo("PENDING");

        service.sign(GATE_ID, "APPROVE", "同意", RD);

        assertThat(gate.getStatus()).isEqualTo("APPROVED");
        assertThat(pendingRows()).isEmpty();
    }

    @Test
    @DisplayName("A4④b：hasPmConflict 只按已决行——单方 REJECT + 对方占位行不构成分歧，不开仲裁")
    void hasPmConflict_ignoresPlaceholderRows() {
        openQueue();

        service.sign(GATE_ID, "REJECT", "否决", MARKET);

        assertThat(gate.getStatus()).isEqualTo("REJECTED");
        assertThat(auditActions()).doesNotContain("GATE_ARBITRATION_OPEN");

        // 对照组：双真签且意见相反 ⇒ 才算分歧（P254 AC-GATE-10 语义保持）
        reviewRows.clear();
        gate.setStatus("PENDING");
        openQueue();
        service.sign(GATE_ID, "APPROVE", "同意", MARKET);
        service.sign(GATE_ID, "REJECT", "否决", RD);
        assertThat(auditActions()).contains("GATE_ARBITRATION_OPEN");
    }

    // ==================== ⑦ view 口径 ====================

    @Test
    @DisplayName("A4⑦：view 不把占位行回显成 my 结论 / otherSubmitted（否则盲签泄露「对方已签」假信号）")
    void view_placeholderRowsAreNotReportedAsSubmitted() {
        openQueue();

        Map<String, Object> view = service.view(GATE_ID, MARKET);

        assertThat(view.get("my")).isNull();
        assertThat(view.get("otherSubmitted")).isEqualTo(false);
        assertThat(view.get("dualSign")).isEqualTo(true);

        service.sign(GATE_ID, "APPROVE", "同意", MARKET);
        Map<String, Object> after = service.view(GATE_ID, RD);
        assertThat(after.get("my")).isNull();
        assertThat(after.get("otherSubmitted")).isEqualTo(true);
    }

    // ==================== ⑧ scanTimeout 弃权走 UPDATE ====================

    @Test
    @DisplayName("A4⑧：超期弃权对占位行落 ABSTAIN（UPDATE 原行），不新增撞 uk_gr_gate_type_round")
    void scanTimeout_abstainUpdatesPlaceholder_withoutNewRow() {
        openQueue();
        gate.setSignDueAt(new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)));
        when(gateMapper.selectList(any())).thenReturn(List.of(gate));

        int handled = service.scanTimeout(SUPER);

        assertThat(handled).isEqualTo(1);
        assertThat(reviewRows).hasSize(2);
        verify(reviewMapper, times(2)).insert(any(GateReview.class)); // 仍只有 openSignQueue 的 2 条
        assertThat(reviewRows).allMatch(r -> "ABSTAIN".equals(r.getDecision()));
        assertThat(reviewRows).allMatch(r -> r.getSignedAt() == null); // ABSTAIN=未实际签署（AC-GATE-08）
    }

    // ---------- 真实写入路径所需的最小 fixture（P251 范式） ----------

    private static GateElement element(Long id, String code, String isVeto) {
        GateElement e = new GateElement();
        e.setId(id);
        e.setGateCode("G1");
        e.setElementCode(code);
        e.setElementName("要素" + code);
        e.setPassStandard("标准" + code);
        e.setIsVeto(isVeto);
        e.setSortOrder(1);
        return e;
    }

    private static GateElementResult judged(Long elementId, String result) {
        return GateElementResult.builder().gateId(GATE_ID).elementId(elementId).result(result)
            .conditionNote("CONDITIONAL".equals(result) ? "整改中" : null).build();
    }

    private static OssFileEntity oss(Long id) {
        OssFileEntity oss = new OssFileEntity();
        oss.setOssId(id);
        oss.setUrl("https://oss.local/gate/" + id + ".pdf");
        return oss;
    }
}
