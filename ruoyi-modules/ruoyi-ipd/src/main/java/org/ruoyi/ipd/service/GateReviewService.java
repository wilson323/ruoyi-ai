package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.approval.ApprovalGuardSupport;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateArbitration;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.GateReviewObserver;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.GateArbitrationMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.service.StateMachineGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * P2-5.2 Gate 双签：G1/G5 盲签与 G2/3/4 领域签署（BR-GATE-03/04/08，AC-GATE-03/04/05）。
 *
 * <p>签署矩阵（双签否决范围 BR-GATE-03：G1/需求变更/G5）：
 * <ul>
 *   <li><b>G1/G5 双签盲签</b>：市场PM 与研发PM 并行独立提交；任一 REJECT ⇒ Gate 直接
 *       REJECTED（AC-GATE-05，双方均收 GATE_REJECTED 通知）；双 APPROVE ⇒ APPROVED，
 *       双方结论同时揭示（AC-GATE-04）。双方都提交前互不可见对方结论，仅见
 *       "对方已提交"（AC-GATE-03，BR-GATE-03 并行签署）。</li>
 *   <li><b>G2/3/4 领域签署</b>：流程门禁但非双签否决（页24），由领域主导方单签终态；
 *       非主导方 PM 签署拒绝（非授权角色）。</li>
 *   <li><b>主导方按领域非先提交方</b>（owner 2026-09-05 决策，覆盖附录 D6 "先提交方"旧解）：
 *       BR-GATE-08 RACI——产品定位与场景（市场 A/R）⇒ G1 立项/G2 规划/G5 上市归市场；
 *       差异化创新（研发 A/R）⇒ G3 开发/G4 验证归研发（G4 否决项 G4-1/2/3 全研发交付侧）。</li>
 * </ul>
 *
 * <p>超时弃权/期限提醒/仲裁归 P2-5.4；条件遗留项归 P2-5.3。
 *
 * <p>P2-5.4 补充（AC-GATE-06~10/21；BR-GATE-04/05/06）：
 * <ul>
 *   <li><b>重发起（AC-GATE-06/07/07b）</b>：REJECTED/双弃权 Gate 可手动重新发起，
 *       round+1 且签署期限重新起算；第 3 轮起双方产品组长自动列席，
 *       第 5 轮起超管介入通知（BR-GATE-05 不限次数）。</li>
 *   <li><b>超时弃权（AC-GATE-08，BR-GATE-04 D17）</b>：双签 Gate 一方 3 个自然日未签
 *       ⇒ 自动补 ABSTAIN 行，按主导方意见执行并审计弃权事件；两人均未签
 *       ⇒ 双 ABSTAIN 转 ABSTAINED_TIMEOUT，不得无依据放行。</li>
 *   <li><b>期限前 1 天提醒（AC-GATE-09）</b>：未签方收 GATE_SIGN_SOON（每日去重）。</li>
 *   <li><b>冲突仲裁（AC-GATE-10，BR-GATE-06）</b>：双 PM 分歧（先 APPROVE 后 REJECT）
 *       ⇒ 自动邀请双方组长仲裁；两组仍不一致 ⇒ 升级超管终裁，
 *       终裁结果写入项目审计日志（gate_arbitrations 表按人去重，避开
 *       gate_reviews 的 reviewer_type 唯一约束）。</li>
 *   <li><b>延期（AC-GATE-21）</b>：超管可单独延长在签 Gate 的签署期限，
 *       最多 3 次，第 4 次拒绝。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class GateReviewService implements IGateReviewService {

    private static final Logger log = LoggerFactory.getLogger(GateReviewService.class);

    static final String STATUS_PENDING = "PENDING";
    static final String STATUS_APPROVED = "APPROVED";
    static final String STATUS_REJECTED = "REJECTED";
    /** 双方均超期未签：不得无依据放行（P2-5.4 验收列），需 reopen 重启 */
    static final String STATUS_ABSTAINED_TIMEOUT = "ABSTAINED_TIMEOUT";
    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT");
    private static final Set<String> ARBITRATION_DECISIONS = Set.of("APPROVE", "REJECT");
    /** AC-GATE-21：签署期限最多延长 3 次（默认；运行时由 IBusinessConfigService.GATE_EXTENSION_MAX_COUNT 覆盖） */
    static final int DEFAULT_MAX_SIGN_EXTENSIONS = 3;
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    private static final String ROLE_GROUP_LEADER = "GROUP_LEADER";

    /** 签署期限参数（BR-GATE-04 3 个自然日；运行时由 IBusinessConfigService.GATE_SIGN_DEADLINE_DAYS 覆盖） */
    static final String SIGN_DEADLINE_KEY = BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS;
    /** G1/G5 双签最低人数（运行时由 IBusinessConfigService.GATE_DUAL_SIGN_COUNT 覆盖；当前仅 1-2 个角色值，参与兜底） */
    static final String DUAL_SIGN_COUNT_KEY = BusinessConfigKeys.GATE_DUAL_SIGN_COUNT;
    private static final Set<String> SIGNER_ROLES = Set.of("MARKET_PM", "RD_PM");
    /** MEDIUM-1.3：列席人员角色（销售/供应/售后/品质/合规）。 */
    private static final Set<String> OBSERVER_ROLES = Set.of("SALES", "SUPPLY", "AFTERSALES", "QUALITY", "COMPLIANCE");

    private final GateMapper gateMapper;
    private final GateReviewMapper reviewMapper;
    private final ProjectMemberMapper memberMapper;
    private final PersonMapper personMapper;
    private final GateArbitrationMapper arbitrationMapper;
    private final GateReviewObserverMapper observerMapper;
    private final ISystemConfigService systemConfigService;
    private final IAuditLogService auditLogService;
    private final NotificationService notificationService;

    /* ---------- R24 治理轮：GateReview 状态机守卫（接线） ---------- */
    /** GateReview 实体类型（与 DefaultStateMachineGuard.registerRule 约定一致） */
    static final String GATE_REVIEW_ENTITY_TYPE = "gate_review";
    /**
     * R33 一期分片D：审批链共享守卫骨架（组合收编 preCheckGuard/registerPostCommit 六连拷贝，
     * entityType 沿用原常量 {@link #GATE_REVIEW_ENTITY_TYPE}）。语义与原拷贝逐字等价：
     * {@code preCheck} fail-closed、{@code registerPostCommit} 事务同步 afterCommit 双路径降级。
     */
    private final ApprovalGuardSupport guardSupport = new ApprovalGuardSupport(GATE_REVIEW_ENTITY_TYPE);

    @Autowired(required = false)
    public void setStateMachineGuard(StateMachineGuard stateMachineGuard) {
        this.guardSupport.setStateMachineGuard(stateMachineGuard);
    }
    /**
     * P0-10.23 补齐（R30 生产就绪）：项目维度 Gate 列表（原型 /api/key-gates?projectId= 的正式替代）。
     *
     * <p>按 projectId 查 gates 表未删行，id 降序（新创建在前）；空列表 = 项目尚无 Gate
     * （真实空态，不造假数据）。前端 gates 页由此列表替代手输 Gate 编号。
     */
    public List<Gate> listByProject(Long projectId) {
        throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "读取评审需要当前用户身份");
    }

    public List<Gate> listByProject(Long projectId, IpdActor actor) {
        requireVisibleProject(projectId, actor);
        return gateMapper.selectList(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getProjectId, projectId).apply("tenant_id = {0}", readTenant())
            .orderByDesc(Gate::getId));
    }

    /** ROOT-R1 P0-7 字面量迁移：Gate 配置（双签人数/签署期限/延期上限；B-RULE-05 配套）来源 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private IBusinessConfigService businessConfigService;

    /**
     * R212-④（看板卡 dbe1b6a7）：gate → 项目 → 组 归属链路校验所需 ProjectMapper。
     *
     * <p>刻意沿用本类既有 {@code businessConfigService}/{@code stateMachineGuard} 的
     * {@code @Autowired(required = false)} 字段注入范式而非新增构造器形参——避免改动
     * {@code @RequiredArgsConstructor} 生成的构造签名而波及 4 个既有测试构造点（最小侵入）。
     * 缺失即 fail-closed（见 {@link #assertGateProjectSameGroup}），不放行；生产由 Spring
     * 注入（ProjectMapper 与本模块同扫描域，必存在）。测试通过 {@link #setProjectMapper} 注入。
     */
    private org.ruoyi.ipd.mapper.ProjectMapper projectMapper;

    /** 小阶段已批准才允许 Gate 放行。未注入时既有签署测试保持原行为。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private StageAcceptanceService stageAcceptanceService;

    /** 测试口/可选注入：装配 gate→项目→组 归属链路所需的 ProjectMapper（null 表示未装配 → fail-closed）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setProjectMapper(org.ruoyi.ipd.mapper.ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    private ProjectService projectVisibility;
    @Autowired
    public void setProjectVisibility(ProjectService projectVisibility) {
        this.projectVisibility = projectVisibility;
    }

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();
    /** R33 分片D：注入点签名不变，同步转发 guardSupport.setClock——postCommit occurredAt 与 now() 同源时刻。 */
    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
        this.guardSupport.setClock(this.clock);
    }
    private Date now() { return Date.from(clock.instant()); }

    /** G1/G5 为双签盲签 Gate（BR-GATE-03 双签否决范围：G1/需求变更/G5）。 */
    static boolean isDualSignGate(String gateCode) {
        return "G1".equals(gateCode) || "G5".equals(gateCode);
    }

    /** 领域主导方：G3/G4 研发（差异化创新 A/R），其余市场（产品定位与场景 A/R）。 */
    static String leadSideOf(String gateCode) {
        return "G3".equals(gateCode) || "G4".equals(gateCode) ? "RD_PM" : "MARKET_PM";
    }

    /**
     * 签署：写入本轮 GateReview，按签署矩阵推进 Gate 终态。
     *
     * <p>对象级归属断言：角色复检之后补 gate → 项目 → 组 链路断言（同 inviteObservers
     * R212-④ 口径），否则任一持签署角色者可向他组 gate 签署放行。
     *
     * <p><b>防自签/防串签（2026-10-03 修复）</b>：除角色门（requireAuthorized）与同角色判重
     * （requireNotSigned）外，reviewerId 参与比对——同一 reviewerId 不得在本轮以另一角色行
     * 出现（防同人双角色连签凑满双签）；本人角色行的指定签署人（占位行 reviewerId 优先，
     * 退回在册成员解析）与 actor.id 不符即拒（防 A 角色他人代签）。见
     * {@link #requireSignerIdentity}。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public GateReview sign(Long gateId, String decision, String opinion, IpdActor actor) {
        Gate gate = requireGate(gateId);
        requireSubmitted(gate);
        if (decision == null || !DECISIONS.contains(decision)) {
            throw new IpdBusinessException("decision 仅允许 APPROVE|REJECT");
        }
        requireAuthorized(gate.getGateCode(), actor);
        assertGateProjectSameGroup(actor, gate);
        List<GateReview> currentRoundRows = roundRows(gate.getId(), gate.getCurrentRound());
        requireNotSigned(gate, actor.role(), currentRoundRows);
        requireSignerIdentity(gate, actor, currentRoundRows);

        // R11 / A4 修复（预落待签占位行对偶，逐字对照 arbitrate() 对 openArbitration 预落行的
        // 「提交=原行落决策 UPDATE，无行退回 INSERT」范式）：本轮本角色若已有 decision=NULL
        // 占位行（openSignQueue 预落），签署走 UPDATE 原行；无占位行（存量在途 gate / 旧路径）
        // 退回 INSERT，行为兼容。
        GateReview placeholder = currentRoundRows.stream()
            .filter(r -> actor.role().equals(r.getReviewerType()) && r.getDecision() == null)
            .findFirst().orElse(null);
        GateReview row;
        if (placeholder != null) {
            row = placeholder.setDecision(decision).setOpinion(opinion).setSignedAt(now());
            reviewMapper.updateById(row);
        } else {
            row = GateReview.builder()
                .gateId(gateId)
                .reviewerType(actor.role())
                .reviewerId(actor.id())
                .decision(decision)
                .opinion(opinion)
                .signedAt(now())
                .dueAt(dueAtFrom(gate))
                .round(gate.getCurrentRound())
                .build();
            reviewMapper.insert(row);
        }

        audit(actor, gate, "GATE_SIGN", null,
            "decision", decision, "opinion", opinion, "round", gate.getCurrentRound());

        advance(gate, actor);
        return row;
    }

    /* ---------- R232-P2-05（batch 8 盲签红线修复）：遮蔽同源复用入口（REST 视图 / AI 卡片 data / prompt 上下文共用） ---------- */

    /**
     * R232-P2-05：揭示判定**同源唯一入口**（view() 与本方法同源；AiSuggestionService 的卡片 data 与
     * prompt 上下文遮蔽一律调用本方法，禁止在调用方另造布尔逻辑——母文件红线「卡片层不得另造遮蔽逻辑，
     * 必须复用 rowView 同源」）。
     *
     * <p>规则（BR-GATE-03 / AC-GATE-03/04，零变化）：{@code revealed = 终态(gate 非 PENDING) || SUPER_ADMIN}。
     * gate 行缺失按未终态处理（fail-closed 保守遮蔽：揭示是例外不是默认）。
     */
    public static boolean isRevealed(Gate gate, IpdActor actor) {
        boolean terminal = gate != null && !STATUS_PENDING.equals(gate.getStatus());
        boolean superAdmin = actor != null && ROLE_SUPER_ADMIN.equals(actor.role());
        return terminal || superAdmin;
    }

    /**
     * R232-P2-05：行级揭示判定（view() L263 {@code rowView(mine, true)} / L269 {@code rowView(other, revealed)}
     * 同源规则）：本人签署行（{@code reviewerId == actor.id()}）恒揭示，对方行按 revealed。
     */
    public static boolean isRowRevealed(GateReview row, IpdActor actor, boolean revealed) {
        return revealed || (row != null && actor != null && actor.id() != null
            && Objects.equals(actor.id(), row.getReviewerId()));
    }

    /**
     * R232-P2-05：遮蔽开关（rowView L339-348 {@code if (revealed)} 同源）——未揭示行 decision/opinion
     * <b>键在值空</b>（键保留、值置 null，供 Catalog itemFields 键集恒等与 R3 两跳 value_mismatch 对账一致）。
     * REST 视图 rowView 的「键不出现」是其视图口径，卡片口径为键在值空；揭示开关同一来源。
     */
    public static void maskRulingFields(Map<String, Object> row, boolean rowRevealed) {
        if (!rowRevealed) {
            row.put("decision", null);
            row.put("opinion", null);
        }
    }

    /** 双签视图：终态或超管全揭示；在途仅见己方结论与"对方已提交"标志（AC-GATE-03/04）。 */
    public Map<String, Object> view(Long gateId, IpdActor actor) {
        String tenantId = readTenant();
        Gate gate = requireVisibleGate(gateId, actor);
        // R11 / A4：「己方/对方已提交」只统计已决行——decision=NULL 待签占位行不算已签、
        // 不触发 otherSubmitted、也不回显空结论（口径同 maybeEscalateAfterArbitration 对预落待裁行）。
        List<GateReview> rows = reviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
            .eq(GateReview::getGateId, gateId)
            .eq(GateReview::getRound, gate.getCurrentRound())
            .apply("tenant_id = {0}", tenantId)).stream()
            .filter(r -> r.getDecision() != null)
            .toList();
        // R232-P2-05：揭示开关并入 isRevealed 同源唯一入口（原 terminal/superAdmin/revealed 三布尔语义零变化）
        boolean revealed = isRevealed(gate, actor);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("gateId", String.valueOf(gateId));
        view.put("gateCode", gate.getGateCode());
        view.put("status", gate.getStatus());
        view.put("round", gate.getCurrentRound());
        view.put("dualSign", isDualSignGate(gate.getGateCode()));
        view.put("leadSide", leadSideOf(gate.getGateCode()));
        // P2-5.4：签署期限/延期次数（AC-GATE-08/21 展示锚点）
        view.put("signDueAt", gate.getSignDueAt() == null ? null : gate.getSignDueAt().toString());
        view.put("extensionCount", gate.getSignExtensionCount());
        // AC-GATE-07：第 3 轮起双方产品组长自动列席（前端展示列席人）
        if (gate.getCurrentRound() != null && gate.getCurrentRound() >= 3) {
            view.put("observers", collectLeaders(gate).stream()
                .map(l -> Map.of("id", String.valueOf(l.getId()), "name", l.getName()))
                .toList());
        }

        GateReview mine = rows.stream().filter(r -> Objects.equals(actor.id(), r.getReviewerId()))
            .findFirst().orElse(null);
        // 自己的结论对自己总可见；对方的仅在揭示后可见
        view.put("my", mine == null ? null : rowView(mine, true));

        GateReview other = rows.stream().filter(r -> !Objects.equals(actor.id(), r.getReviewerId()))
            .findFirst().orElse(null);
        // AC-GATE-03：对方已提交但未揭示 ⇒ 只返回 otherSubmitted=true，不泄露结论/意见
        view.put("otherSubmitted", other != null);
        view.put("other", other == null ? null : rowView(other, revealed));
        if (!revealed && mine == null && other != null) {
            view.put("hint", "对方已提交，等待您签署");
        }
        return view;
    }

    /** 终态推进：双签 Gate 双方齐签或任一 REJECT；领域 Gate 主导方单签即终态。 */
    private void advance(Gate gate, IpdActor actor) {
        // R11 / A4：本轮行含 openSignQueue 预落的 decision=NULL 占位行——终态判定只按已决行，
        // 否则双签 Gate 占位 2 行会被 rows.size()>=2 误判「双签齐」在首签即放行（盲签红线塌方）。
        List<GateReview> rows = roundRows(gate.getId(), gate.getCurrentRound());
        List<GateReview> decided = rows.stream().filter(r -> r.getDecision() != null).toList();
        boolean rejected = decided.stream().anyMatch(r -> "REJECT".equals(r.getDecision()));
        boolean dual = isDualSignGate(gate.getGateCode());

        if (rejected) {
            settle(gate, STATUS_REJECTED, actor, decided);
            return;
        }
        if (dual) {
            if (decided.size() >= 2) {   // 双 APPROVE ⇒ 通过（AC-GATE-04）
                settle(gate, STATUS_APPROVED, actor, decided);
            }
            return;                    // 一方已签仍在途：等另一方（盲签保持）
        }
        settle(gate, STATUS_APPROVED, actor, decided);  // 领域 Gate 主导方 APPROVE 单签终态
    }

    /** 落终态：更新 Gate 状态 + REJECTED 时双方 GATE_REJECTED 通知（AC-GATE-05）+ 审计。 */
    private void settle(Gate gate, String status, IpdActor actor, List<GateReview> rows) {
        // R33 分片D：终态守卫前置判定收敛 ApprovalGuardSupport.requireFromState（文案逐字沿用
        // requireSubmitted 的终态文案）。原 if(PENDING) 守卫覆盖的非 PENDING 分支为防御死路径
        // （唯一调用链 sign→advance 前置 requireSubmitted 恒保证 PENDING），收敛为 fail-closed
        // 前置断言后活路径行为零变更。
        guardSupport.requireFromState(gate.getStatus(), STATUS_PENDING,
            "Gate 已终态（" + gate.getStatus() + "），不可签署");
        // R24 接线：状态机守卫 preCheck（fail-closed）。守卫 null / 未登记迁移则抛业务异常。
        guardSupport.preCheck(STATUS_PENDING, status, "sign");
        if (STATUS_APPROVED.equals(status) && stageAcceptanceService != null) {
            stageAcceptanceService.assertGateActionsAccepted(gate);
        }
        gate.setStatus(status);
        gateMapper.updateById(gate);
        audit(actor, gate, "REJECTED".equals(status) ? "GATE_REJECT" : "GATE_APPROVE",
            "终态 " + status, "round", gate.getCurrentRound());
        // R24 接线：postCommit 跨域副作用（事务后）——本规则 crossDomain=false、仅作后续扩展点。
        guardSupport.registerPostCommit(STATUS_PENDING, status, "sign", actor.id(), gate.getId());
        if (STATUS_REJECTED.equals(status)) {
            notifyBothSides(gate, rows);
            // P2-5.4 AC-GATE-10：双 PM 意见分歧（先 APPROVE 后 REJECT）⇒ 自动邀请组长仲裁
            if (hasPmConflict(rows)) {
                openArbitration(gate, actor);
            }
        }
    }

    /** 双方=该 Gate 的市场/研发两位签署人；成员绑定缺失时退化通知已签方（AC-GATE-05）。 */
    /**
     * REJECTED 落终态后知会双方 PM（AC-GATE-05）。
     *
     * <p>调用链 settle → advance ← sign()/arbitrate()（均 {@code @Transactional}），本方法在宿主
     * 事务内执行，故走 {@link NotificationService#publishAfterCommit}：延迟到业务提交后独立事务
     * 发布，失败仅 WARN。否则业务回滚（如 CAS 未命中抛异常）时用户仍会收到「Gate 已驳回」的假通知。</p>
     */
    private void notifyBothSides(Gate gate, List<GateReview> rows) {
        List<Long> receivers = new java.util.ArrayList<>();
        for (String role : List.of("MARKET_PM", "RD_PM")) {
            rows.stream().filter(r -> role.equals(r.getReviewerType())).findFirst()
                .ifPresent(r -> receivers.add(r.getReviewerId()));
        }
        if (receivers.size() < 2) {
            memberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
                    .eq(ProjectMember::getProjectId, gate.getProjectId())
                    .in(ProjectMember::getRole, List.of("MARKET_PM", "RD_PM"))
                    .isNull(ProjectMember::getExitDate))
                .forEach(m -> { if (!receivers.contains(m.getPersonId())) receivers.add(m.getPersonId()); });
        }
        for (Long receiver : receivers) {
            notificationService.publishAfterCommit(receiver, NotificationService.Types.GATE_REJECTED,
                NotificationService.KIND_ACTION, "gate", gate.getId(),
                "Gate " + gate.getGateCode() + " 已驳回",
                "Gate " + gate.getGateCode() + " 被否决驳回（第 " + gate.getCurrentRound() + " 轮），请查看评审详情",
                "/reviews/gate/" + gate.getId());
        }
    }

    private Map<String, Object> rowView(GateReview r, boolean revealed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reviewerType", r.getReviewerType());
        m.put("signedAt", r.getSignedAt() == null ? null : r.getSignedAt().toString());
        if (revealed) {
            m.put("decision", r.getDecision());
            m.put("opinion", r.getOpinion());
        }
        return m;
    }

    private static String readTenant() {
        String tenantId = org.ruoyi.common.satoken.utils.LoginHelper.getTenantId();
        return tenantId == null || tenantId.isBlank() ? "000000" : tenantId;
    }

    private void requireVisibleProject(Long projectId, IpdActor actor) {
        if (actor == null || actor.id() == null || projectVisibility == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该评审");
        }
        Project project = projectVisibility.getVisibleById(projectId, actor);
        if (project == null || !Objects.equals(readTenant(), project.getTenantId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该评审");
        }
    }

    private Gate requireVisibleGate(Long gateId, IpdActor actor) {
        Gate gate = gateMapper.selectOne(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getId, gateId).apply("tenant_id = {0}", readTenant()));
        if (gate == null) throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该评审");
        requireVisibleProject(gate.getProjectId(), actor);
        return gate;
    }

    private Gate requireGate(Long gateId) {
        Gate gate = gateMapper.selectById(gateId);
        if (gate == null) {
            throw new IpdBusinessException("Gate 不存在");
        }
        return gate;
    }

    /** 仅接受 P2-5.1 提交后的待签 Gate（startedAt 置位=要素判定已冻结）。 */
    private void requireSubmitted(Gate gate) {
        if (!STATUS_PENDING.equals(gate.getStatus())) {
            throw new IpdBusinessException("Gate 已终态（" + gate.getStatus() + "），不可签署");
        }
        if (gate.getStartedAt() == null) {
            throw new IpdBusinessException("评审尚未提交，请先完成要素判定并提交（P2-5.1）");
        }
    }

    /** 签署授权：双 PM 才可签；G2/3/4 仅领域主导方（非授权角色拒绝）。 */
    private void requireAuthorized(String gateCode, IpdActor actor) {
        if (!SIGNER_ROLES.contains(actor.role())) {
            throw new IpdBusinessException("仅市场PM/研发PM可签署（组长列席与仲裁归后续流程）");
        }
        if (!isDualSignGate(gateCode) && !actor.role().equals(leadSideOf(gateCode))) {
            String lead = leadSideOf(gateCode);
            throw new IpdBusinessException("Gate " + gateCode + " 由" + sideName(lead) + "主导签署，您无权签署");
        }
    }

    /** 同轮同角色重复签署拒绝（并发窗口由 uk_gr_gate_type_round 唯一约束兜底）。 */
    private void requireNotSigned(Gate gate, String reviewerType, List<GateReview> roundRows) {
        // R11 / A4：只统计已决行——decision=NULL 待签占位行不算已签（否则预落后本人永无法签署）
        boolean already = roundRows.stream()
            .anyMatch(r -> reviewerType.equals(r.getReviewerType()) && r.getDecision() != null);
        if (already) {
            throw new IpdBusinessException("本轮您已签署，不可重复签署");
        }
    }

    /**
     * 防自签/防串签（2026-10-03 修复双签缺口）：reviewerId 落库后首次参与签署比对。
     *
     * <p><b>防自签</b>：同一 reviewerId 在本轮以<b>另一角色</b>的行出现（占位或已决均算——
     * 占位行 reviewerId 即该角色的指定签署人，同人被指定双角色时双签已退化为单人行，
     * 第二签必须拦）⇒ 拒绝。此前同一人以 MARKET_PM+RD_PM 连签两行即可凑满
     * {@code decided.size() >= 2} 直接放行 Gate。</p>
     *
     * <p><b>防串签</b>：本人角色在本轮的指定签署人（占位行 reviewerId 优先；无占位行退回
     * {@link #signerPersonId} 在册成员解析）与 {@code actor.id()} 不符 ⇒ 拒绝——杜绝持角色
     * 的第三人替在册签署人代签。指定人解析不到（数据治理缺失态：无占位行且该角色无在册
     * 成员）⇒ 放行，与 {@link #openSignQueue} 的「解析不到签署人跳过不抛错」同口径；
     * 落库 reviewerId=actor.id 仍如实记录实际签署人，审计可追。</p>
     *
     * <p>多租户：reviewerId 为 personId（单企业部署内全局唯一），比对不涉租户列；
     * 行查询走 {@link #roundRows}，租户过滤由 MyBatis-Plus 拦截器统一施加。</p>
     */
    private void requireSignerIdentity(Gate gate, IpdActor actor, List<GateReview> roundRows) {
        boolean selfDualRole = roundRows.stream()
            .anyMatch(r -> !actor.role().equals(r.getReviewerType())
                && actor.id() != null && actor.id().equals(r.getReviewerId()));
        if (selfDualRole) {
            throw new IpdBusinessException("同一人不得在同一 Gate 评审中以双角色连签（防自签）");
        }
        GateReview ownRoleRow = roundRows.stream()
            .filter(r -> actor.role().equals(r.getReviewerType()))
            .findFirst().orElse(null);
        Long designated = ownRoleRow != null && ownRoleRow.getReviewerId() != null
            ? ownRoleRow.getReviewerId()
            : signerPersonId(gate, actor.role());
        if (designated != null && !designated.equals(actor.id())) {
            throw new IpdBusinessException("本角色签署人非本人，不可代签（防串签）");
        }
    }

    private Date dueAtFrom(Gate gate) {
        // P2-5.4：submit/reopen/extend 维护的显式期限优先（AC-GATE-21 延期锚点）；
        // 历史行 sign_due_at 为空时回退旧算法（startedAt + N 天），P2-5.2 行为兼容。
        if (gate.getSignDueAt() != null) {
            return gate.getSignDueAt();
        }
        int days = resolveSignDeadlineDays();
        Date base = gate.getStartedAt() == null ? now() : gate.getStartedAt();
        return new Date(base.getTime() + TimeUnit.DAYS.toMillis(days));
    }

    /**
     * ROOT-R1 P0-7：读取签署期限天数。先读 IBusinessConfigService.GATE_SIGN_DEADLINE_DAYS，回退 SystemConfig。
     */
    private int resolveSignDeadlineDays() {
        if (businessConfigService != null) {
            try {
                return businessConfigService.getInt(SIGN_DEADLINE_KEY);
            } catch (Exception ex) {
                // fall through
            }
        }
        return systemConfigService.getIntValue(SIGN_DEADLINE_KEY, 3);
    }

    /**
     * ROOT-R1 P0-7：读取签署期限最大延期次数（默认 DEFAULT_MAX_SIGN_EXTENSIONS=3）。
     */
    private int resolveMaxSignExtensions() {
        if (businessConfigService != null) {
            try {
                return businessConfigService.getInt(BusinessConfigKeys.GATE_EXTENSION_MAX_COUNT);
            } catch (Exception ex) {
                // fall through
            }
        }
        return DEFAULT_MAX_SIGN_EXTENSIONS;
    }

    private List<GateReview> roundRows(Long gateId, Integer round) {
        return reviewMapper.selectList(new LambdaQueryWrapper<GateReview>()
            .eq(GateReview::getGateId, gateId)
            .eq(GateReview::getRound, round));
    }

    private static String sideName(String role) {
        return "RD_PM".equals(role) ? "研发PM" : "市场PM";
    }

    // ==================== P2-5.4：重发起 / 超时弃权 / 提醒 / 仲裁 / 延期 ====================

    /** 重新发起评审（AC-GATE-06/07/07b；BR-GATE-05 不限次数）：round+1、期限重算。
     * <p>第 3 轮起通知双方产品组长列席；第 5 轮起通知超管介入。 */
    @Transactional(rollbackFor = Exception.class)
    public Gate reopen(Long gateId, IpdActor actor) {
        Gate gate = requireGate(gateId);
        if (!STATUS_REJECTED.equals(gate.getStatus())
            && !STATUS_ABSTAINED_TIMEOUT.equals(gate.getStatus())) {
            throw new IpdBusinessException("仅被驳回或双弃权超时的 Gate 可重新发起，当前：" + gate.getStatus());
        }
        if (!SIGNER_ROLES.contains(actor.role()) && !ROLE_SUPER_ADMIN.equals(actor.role())) {
            throw new IpdBusinessException("仅签署双方或超管可重新发起评审");
        }
        // R24 接线：状态机守卫 preCheck（fail-closed）。本调用发起两条迁移（REJECTED→PENDING|reopen / ABSTAINED_TIMEOUT→PENDING|reopen），守卫会精准命中。
        guardSupport.preCheck(gate.getStatus(), STATUS_PENDING, "reopen");
        int newRound = gate.getCurrentRound() + 1;
        int days = resolveSignDeadlineDays();
        Date newDue = new Date(now().getTime() + TimeUnit.DAYS.toMillis(days));
        // 显式 set 清列：MP updateById 忽略 null 字段，concludedAt 必须置回 null
        // R33 分片D：CAS 判定收敛 ApprovalGuardSupport.requireCasHit（文案逐字沿用本方法起点守卫文案）。
        // 本批不加状态谓词、不改写写语义——reopen 并发防线属 §1 登记缺陷族，一期范围只修 C5/C6 并发。
        guardSupport.requireCasHit(gateMapper.update(null, new LambdaUpdateWrapper<Gate>()
            .eq(Gate::getId, gateId)
            .set(Gate::getStatus, STATUS_PENDING)
            .set(Gate::getCurrentRound, newRound)
            .set(Gate::getSignDueAt, newDue)
            .set(Gate::getConcludedAt, null)),
            "仅被驳回或双弃权超时的 Gate 可重新发起，当前：" + gate.getStatus());
        audit(actor, gate, "GATE_REOPEN", "否决后重新发起评审（BR-GATE-05 不限次数）",
            "round", newRound, "signDueAt", newDue.toString());
        // R24 接线：postCommit 跨域副作用（事务后）——本规则 crossDomain=false、仅作后续扩展点。
        guardSupport.registerPostCommit(gate.getStatus(), STATUS_PENDING, "reopen", actor.id(), gate.getId());

        Gate updated = requireGate(gateId);
        // reopen 为 @Transactional：通知走 publishAfterCommit 延迟到提交后独立事务发送，
        // 若 CAS 守卫/后续步骤把宿主事务打成回滚，用户不会收到「已重新发起评审」的假通知。
        if (newRound >= 3) {
            for (Person leader : collectLeaders(updated)) {
                notificationService.publishAfterCommit(leader.getId(),
                    NotificationService.Types.GATE_ROUND_OBSERVER, NotificationService.KIND_ACTION,
                    "gate", gate.getId(),
                    "Gate " + gate.getGateCode() + " 第 " + newRound + " 轮评审请您列席",
                    "Gate " + gate.getGateCode() + " 进入第 " + newRound + " 轮评审（AC-GATE-07），双方产品组长自动加入列席",
                    "/reviews/gate/" + gate.getId());
            }
        }
        if (newRound >= 5) {
            for (Person admin : superAdmins()) {
                notificationService.publishAfterCommit(admin.getId(),
                    NotificationService.Types.GATE_ADMIN_INTERVENE, NotificationService.KIND_ACTION,
                    "gate", gate.getId(),
                    "Gate " + gate.getGateCode() + " 第 " + newRound + " 轮评审请超管介入",
                    "Gate " + gate.getGateCode() + " 进入第 " + newRound + " 轮评审（AC-GATE-07b），请超管介入协调",
                    "/reviews/gate/" + gate.getId());
            }
        }
        return updated;
    }

    /** 超时弃权扫描（AC-GATE-08，BR-GATE-04 D17）：双签 Gate 超期未签方自动补 ABSTAIN 行。
     * <p>三天规则按主导方区分：主导方已签 APPROVE ⇒ 按主导方意见执行放行；
     * 主导方弃权 ⇒ 无放行依据；两人均未签 ⇒ 双 ABSTAIN 转 ABSTAINED_TIMEOUT（不得无依据放行）。
     * 单签 Gate（G2/3/4）无对方弃权概念，不折算，超期由 scanRemind 催办。 */
    @Transactional(rollbackFor = Exception.class)
    public int scanTimeout(IpdActor operator) {
        List<Gate> pending = gateMapper.selectList(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getStatus, STATUS_PENDING)
            .isNotNull(Gate::getStartedAt));
        int handled = 0;
        for (Gate gate : pending) {
            if (!isDualSignGate(gate.getGateCode())) {
                continue;
            }
            Date due = dueAtFrom(gate);
            if (!now().after(due)) {
                continue;
            }
            List<GateReview> rows = roundRows(gate.getId(), gate.getCurrentRound());
            if (rows.stream().anyMatch(r -> "REJECT".equals(r.getDecision()))) {
                continue; // 防御：REJECT 应已 settle REJECTED，不在此折算
            }
            // R11 / A4：「已签」只统计已决行——占位行（decision=NULL）是待签不是已签，
            // 否则预落后 scanTimeout 会误判「已签方」并走错弃权折算分支。
            boolean marketSigned = rows.stream().anyMatch(r -> "MARKET_PM".equals(r.getReviewerType())
                && r.getDecision() != null);
            boolean rdSigned = rows.stream().anyMatch(r -> "RD_PM".equals(r.getReviewerType())
                && r.getDecision() != null);
            String lead = leadSideOf(gate.getGateCode());
            if (marketSigned && rdSigned) {
                continue; // 防御：双 APPROVE 应已 settle APPROVED
            }
            if (!marketSigned && !rdSigned) {
                insertAbstain(gate, "MARKET_PM", due);
                insertAbstain(gate, "RD_PM", due);
                settleTimeout(gate, STATUS_ABSTAINED_TIMEOUT, operator,
                    "双方均超期未签，双弃权不放行（需 reopen 重启）");
            } else {
                String absentSide = marketSigned ? "RD_PM" : "MARKET_PM";
                String presentSide = marketSigned ? "MARKET_PM" : "RD_PM";
                insertAbstain(gate, absentSide, due);
                audit(operator, gate, "GATE_ABSTAIN_TIMEOUT",
                    "超期未签自动弃权（AC-GATE-08 审计弃权事件）",
                    "side", absentSide, "round", gate.getCurrentRound());
                if (lead.equals(presentSide)) {
                    settleTimeout(gate, STATUS_APPROVED, operator,
                        "一方弃权按主导方意见执行（" + sideName(presentSide) + " 已签 APPROVE）");
                } else {
                    settleTimeout(gate, STATUS_ABSTAINED_TIMEOUT, operator,
                        "主导方（" + sideName(lead) + "）超期弃权，非主导方意见无放行依据");
                }
            }
            notifyBothPms(gate, NotificationService.Types.GATE_ABSTAINED,
                "Gate " + gate.getGateCode() + " 签署超期弃权流转",
                "第 " + gate.getCurrentRound() + " 轮签署超期，未签方已标记弃权（BR-GATE-04），请查看流转结果");
            handled++;
        }
        return handled;
    }

    /** 期限前 1 天提醒扫描（AC-GATE-09）：双签 Gate 提醒未签方；单签 Gate 提醒主导方。 */
    @Transactional(rollbackFor = Exception.class)
    public int scanRemind(IpdActor operator) {
        List<Gate> pending = gateMapper.selectList(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getStatus, STATUS_PENDING)
            .isNotNull(Gate::getStartedAt));
        int reminded = 0;
        for (Gate gate : pending) {
            long untilDue = dueAtFrom(gate).getTime() - now().getTime();
            if (untilDue <= 0 || untilDue > TimeUnit.DAYS.toMillis(1)) {
                continue; // 未到提醒窗口或已超期（归 scanTimeout）
            }
            List<GateReview> rows = roundRows(gate.getId(), gate.getCurrentRound());
            boolean dual = isDualSignGate(gate.getGateCode());
            for (String side : List.of("MARKET_PM", "RD_PM")) {
                // R11 / A4：已签方判定只按已决行——占位行未签，仍需提醒
                if (dual && rows.stream().anyMatch(r -> side.equals(r.getReviewerType())
                    && r.getDecision() != null)) {
                    continue; // 双签 Gate：已签方不提醒
                }
                if (!dual && !side.equals(leadSideOf(gate.getGateCode()))) {
                    continue; // 单签 Gate：仅主导方有签署义务
                }
                Long personId = signerPersonId(gate, side);
                if (personId == null) {
                    continue;
                }
                notificationService.publishDaily(personId, NotificationService.Types.GATE_SIGN_SOON,
                    NotificationService.KIND_ACTION, "gate", gate.getId(),
                    "Gate " + gate.getGateCode() + " 签署期限将于 24 小时内到期",
                    "第 " + gate.getCurrentRound() + " 轮签署期限即将到期（AC-GATE-09），请及时签署",
                    "/reviews/gate/" + gate.getId(), now());
                reminded++;
            }
        }
        return reminded;
    }

    /** 组长仲裁（AC-GATE-10 中段）：仅冲突双方所在组的产品组长，意见 APPROVE|REJECT。
     * <p>组长意见齐备后：一致 ⇒ 仲裁结果知会双方；不一致 ⇒ 自动升级超管终裁。 */
    @Transactional(rollbackFor = Exception.class)
    public GateArbitration arbitrate(Long gateId, String decision, String opinion, IpdActor actor) {
        Gate gate = requireGate(gateId);
        requireArbitratable(gate, actor, ROLE_GROUP_LEADER, "仅产品组长可提交仲裁意见");
        if (decision == null || !ARBITRATION_DECISIONS.contains(decision)) {
            throw new IpdBusinessException("仲裁意见仅允许 APPROVE|REJECT");
        }
        if (collectLeaders(gate).stream().noneMatch(l -> actor.id().equals(l.getId()))) {
            throw new IpdBusinessException("仅冲突双方所在组的产品组长可提交仲裁意见");
        }
        // 待裁行预落语义（WB-17-1 工作台对偶）：开仲裁时已为每位组长 INSERT decision=NULL 行，
        // 提交 = 原行落决策（UPDATE）；无行时（存量数据/旧路径）退回 INSERT。
        GateArbitration existing = arbitrationRows(gate, ROLE_GROUP_LEADER).stream()
            .filter(o -> actor.id().equals(o.getArbitratorId()))
            .findFirst().orElse(null);
        if (existing != null && existing.getDecision() != null) {
            throw new IpdBusinessException("您已提交本轮仲裁意见，不可重复提交");
        }
        GateArbitration row;
        if (existing != null) {
            row = existing.setDecision(decision).setOpinion(opinion);
            arbitrationMapper.updateById(row);
        } else {
            row = GateArbitration.builder()
                .gateId(gateId)
                .round(gate.getCurrentRound())
                .arbitratorType(ROLE_GROUP_LEADER)
                .arbitratorId(actor.id())
                .decision(decision)
                .opinion(opinion)
                .build();
            arbitrationMapper.insert(row);
        }
        audit(actor, gate, "GATE_ARBITRATION", "组长仲裁意见",
            "decision", decision, "round", gate.getCurrentRound());
        maybeEscalateAfterArbitration(gate, actor);
        return row;
    }

    /** 超管终裁（AC-GATE-10 尾段）：仅两组长意见不一致（已升级）后可提交，
     * 终裁结果写入项目审计日志永久归档。Gate 终态不因终裁翻转（变更须 reopen 新轮）。 */
    @Transactional(rollbackFor = Exception.class)
    public GateArbitration finalRuling(Long gateId, String decision, String opinion, IpdActor actor) {
        Gate gate = requireGate(gateId);
        requireArbitratable(gate, actor, ROLE_SUPER_ADMIN, "仅超级管理员可终裁");
        if (decision == null || !ARBITRATION_DECISIONS.contains(decision)) {
            throw new IpdBusinessException("终裁意见仅允许 APPROVE|REJECT");
        }
        // 预落的待裁行（decision=NULL）不计入「两组对立意见」判断
        List<GateArbitration> leaderOpinions = arbitrationRows(gate, ROLE_GROUP_LEADER).stream()
            .filter(o -> o.getDecision() != null).toList();
        if (leaderOpinions.size() < 2) {
            throw new IpdBusinessException("组长仲裁尚未形成两组对立意见，暂无需超管终裁");
        }
        String first = leaderOpinions.get(0).getDecision();
        if (leaderOpinions.stream().allMatch(o -> first.equals(o.getDecision()))) {
            throw new IpdBusinessException("组长仲裁已一致，无需超管终裁");
        }
        boolean already = arbitrationRows(gate, ROLE_SUPER_ADMIN).stream()
            .anyMatch(o -> actor.id().equals(o.getArbitratorId()));
        if (already) {
            throw new IpdBusinessException("您已提交本轮终裁意见，不可重复提交");
        }
        GateArbitration row = GateArbitration.builder()
            .gateId(gateId)
            .round(gate.getCurrentRound())
            .arbitratorType(ROLE_SUPER_ADMIN)
            .arbitratorId(actor.id())
            .decision(decision)
            .opinion(opinion)
            .build();
        arbitrationMapper.insert(row);
        audit(actor, gate, "GATE_FINAL_RULING",
            "超管终裁（终裁结果写入项目审计日志永久归档，AC-GATE-10）",
            "decision", decision, "round", gate.getCurrentRound());
        notifyBothPms(gate, NotificationService.Types.GATE_FINAL_RULING_RESULT,
            "Gate " + gate.getGateCode() + " 超管终裁：" + ("APPROVE".equals(decision) ? "支持通过" : "支持驳回"),
            "第 " + gate.getCurrentRound() + " 轮双PM分歧经组长仲裁未决，超管终裁意见已归档审计日志");
        return row;
    }

    // ==================== MEDIUM-1.3：Gate 列席人员邀请 ====================

    /** 列席人角色白名单。 */
    private static final Set<String> OBSERVER_ROLE_WHITELIST = OBSERVER_ROLES;

    /**
     * 邀请列席人员（MEDIUM-1.3）：仅超管/组长可邀请；
     * 同 gate+observer 唯一约束兜底幂等（重复邀请不报错，返回既有行）。
     *
     * @param gateId      Gate 实例
     * @param observerIds 列席人 personId 列表
     * @param role        列席角色（5 类之一）
     * @param actor       邀请人（必须 SUPER_ADMIN/GROUP_LEADER）
     * @return 邀请行数（去重后）
     */
    @Transactional(rollbackFor = Exception.class)
    public int inviteObservers(Long gateId, List<Long> observerIds, String role, IpdActor actor) {
        Gate gate = requireGate(gateId);
        if (!"SUPER_ADMIN".equals(actor.role()) && !"GROUP_LEADER".equals(actor.role())) {
            throw new IpdBusinessException("仅超管/产品组长可邀请列席人员");
        }
        // R212-④（看板卡 dbe1b6a7）：角色复检之后补对象级归属断言——此前任一组长可向他组
        // gate 邀请列席人并触发通知（跨组写 + 骚扰）。
        assertGateProjectSameGroup(actor, gate);
        if (role == null || !OBSERVER_ROLE_WHITELIST.contains(role)) {
            throw new IpdBusinessException("列席角色仅允许 SALES|SUPPLY|AFTERSALES|QUALITY|COMPLIANCE");
        }
        if (observerIds == null || observerIds.isEmpty()) {
            throw new IpdBusinessException("请选择至少 1 位列席人员");
        }
        int invited = 0;
        for (Long observerId : observerIds) {
            if (observerId == null) continue;
            // person 存在性校验
            Person observer = personMapper.selectById(observerId);
            if (observer == null) {
                throw new IpdBusinessException("列席人不存在：personId=" + observerId);
            }
            // 幂等：先查再插；uk(gate_id, observer_id) 兜底
            Long exist = observerMapper.selectCount(new LambdaQueryWrapper<GateReviewObserver>()
                .eq(GateReviewObserver::getGateId, gateId)
                .eq(GateReviewObserver::getObserverId, observerId));
            if (exist != null && exist > 0) {
                continue;
            }
            GateReviewObserver row = GateReviewObserver.builder()
                .gateId(gateId)
                .observerId(observerId)
                .role(role)
                .invitedBy(actor.id())
                .invitedAt(now())
                .attended(0)
                .build();
            observerMapper.insert(row);
            invited++;
            // 知会被邀请人。inviteObservers 为 @Transactional，走 publishAfterCommit 延迟到提交后发送——
            // 同批后续 observerId 校验失败会整体回滚，已入名单的人不应收到列席邀请。
            notificationService.publishAfterCommit(observerId,
                NotificationService.Types.GATE_OBSERVER_INVITED, NotificationService.KIND_ACTION,
                "gate", gateId,
                "Gate " + gate.getGateCode() + " 邀请您列席",
                "您被邀请作为 " + role + " 角色列席 Gate " + gate.getGateCode()
                    + " 评审（MEDIUM-1.3），请提交列席意见",
                "/reviews/gate/" + gateId);
        }
        audit(actor, gate, "GATE_OBSERVER_INVITED",
            "邀请列席人员（" + role + "，" + invited + " 人）",
            "role", role, "count", invited);
        return invited;
    }

    /**
     * R212-④（看板卡 dbe1b6a7）：gate → 项目 → 组 链路归属断言。
     *
     * <p>SUPER_ADMIN 豁免且<b>不触达任何 DB 读</b>（{@link IpdIdorGuard} 守卫 3/6 同口径，
     * 冒充者在触碰数据前即被拒）；非超管必须满足「操作人组 == gate 所属项目主组」。
     * 任何一环不满足即 fail-closed 抛 {@link IpdBusinessException}
     * （{@link ApiV1ErrorCode#FORBIDDEN} → HTTP 403），且项目不存在与无权限统一文案，
     * 不向他组泄漏「该 gate 指向的项目是否存在」。
     *
     * @param actor 邀请人会话身份（调用方已保证非空角色门通过）
     * @param gate  已加载的 Gate 实例（提供 projectId 链路起点）
     */
    private void assertGateProjectSameGroup(IpdActor actor, Gate gate) {
        if (ROLE_SUPER_ADMIN.equals(actor.role())) {
            return;
        }
        org.ruoyi.ipd.mapper.ProjectMapper mapper = this.projectMapper;
        if (mapper == null || gate == null || gate.getProjectId() == null) {
            // 归属链路不可解析 ⇒ 拒绝（fail-closed），不放行
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
        Project project = mapper.selectById(gate.getProjectId());
        if (project == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
        IpdIdorGuard.assertSameGroupIpd(actor, project.getMainGroupId());
    }

    /**
     * 列席人提交意见（MEDIUM-1.3）：仅 observer 本人可写自己的一行；
     * 不入主审投票（不动 gate_reviews）。
     */
    @Transactional(rollbackFor = Exception.class)
    public GateReviewObserver recordOpinion(Long gateId, Long observerId, String opinion, IpdActor actor) {
        Gate gate = requireGate(gateId);
        if (!actor.id().equals(observerId)) {
            throw new IpdBusinessException("仅本人可提交自己的列席意见（MEDIUM-1.3）");
        }
        if (opinion == null || opinion.isBlank()) {
            throw new IpdBusinessException("列席意见不能为空");
        }
        if (opinion.length() > 2000) {
            throw new IpdBusinessException("列席意见最长 2000 字符");
        }
        GateReviewObserver row = observerMapper.selectOne(new LambdaQueryWrapper<GateReviewObserver>()
            .eq(GateReviewObserver::getGateId, gateId)
            .eq(GateReviewObserver::getObserverId, observerId));
        if (row == null) {
            throw new IpdBusinessException("您未在 Gate " + gateId + " 的列席名单中");
        }
        row.setOpinion(opinion);
        row.setAttended(1);
        observerMapper.updateById(row);
        audit(actor, gate, "GATE_OBSERVER_OPINION",
            "列席人提交意见",
            "observerId", observerId, "opinionLength", opinion.length());
        return row;
    }

    /**
     * 查 gate 全部列席人员 + 意见（MEDIUM-1.3）：仅 PRODUCT_LEADER/GROUP_LEADER/SUPER_ADMIN 可见。
     */
    public List<GateReviewObserver> listObservers(Long gateId, IpdActor actor) {
        String role = actor == null ? null : actor.role();
        if (!"GROUP_LEADER".equals(role) && !"SUPER_ADMIN".equals(role)) {
            throw new IpdBusinessException("仅组长/超管可查询列席人员名单");
        }
        requireVisibleGate(gateId, actor);
        return observerMapper.selectList(new LambdaQueryWrapper<GateReviewObserver>()
            .eq(GateReviewObserver::getGateId, gateId)
            .orderByDesc(GateReviewObserver::getInvitedAt));
    }

    /** 延长签署期限（AC-GATE-21）：仅超管、仅在签 Gate；最多 3 次，第 4 次拒绝。 */
    @Transactional(rollbackFor = Exception.class)
    public Gate extendDeadline(Long gateId, int days, IpdActor actor) {
        Gate gate = requireGate(gateId);
        if (!ROLE_SUPER_ADMIN.equals(actor.role())) {
            throw new IpdBusinessException("仅超级管理员可延长签署期限（AC-GATE-21）");
        }
        if (!STATUS_PENDING.equals(gate.getStatus()) || gate.getStartedAt() == null) {
            throw new IpdBusinessException("仅签署中的 Gate 可延长签署期限");
        }
        if (days <= 0 || days > 30) {
            throw new IpdBusinessException("延长天数须为 1-30");
        }
        int count = gate.getSignExtensionCount() == null ? 0 : gate.getSignExtensionCount();
        if (count >= resolveMaxSignExtensions()) {
            throw new IpdBusinessException("签署期限最多延长 " + resolveMaxSignExtensions() + " 次，已达上限（AC-GATE-21）");
        }
        Date newDue = new Date(dueAtFrom(gate).getTime() + TimeUnit.DAYS.toMillis(days));
        gateMapper.update(null, new LambdaUpdateWrapper<Gate>()
            .eq(Gate::getId, gateId)
            .set(Gate::getSignDueAt, newDue)
            .set(Gate::getSignExtensionCount, count + 1));
        audit(actor, gate, "GATE_SIGN_EXTEND", "超管延长签署期限",
            "days", days, "count", count + 1, "newDueAt", newDue.toString());
        return requireGate(gateId);
    }

    /** 仲裁前置：调用者角色匹配 + Gate 已驳回且当轮双 PM 意见分歧（先 APPROVE 后 REJECT）。 */
    private void requireArbitratable(Gate gate, IpdActor actor, String role, String deniedMessage) {
        if (!role.equals(actor.role())) {
            throw new IpdBusinessException(deniedMessage);
        }
        if (!STATUS_REJECTED.equals(gate.getStatus())) {
            throw new IpdBusinessException("仅被驳回的 Gate 存在仲裁/终裁流程，当前：" + gate.getStatus());
        }
        if (!hasPmConflict(roundRows(gate.getId(), gate.getCurrentRound()))) {
            throw new IpdBusinessException("本轮无双 PM 意见分歧，无需仲裁");
        }
    }

    /** 双 PM 意见分歧 = 当轮同时存在 APPROVE 与 REJECT（先 A 后 R 序列）。 */
    private boolean hasPmConflict(List<GateReview> rows) {
        // R11 / A4：统一「只统计已决行」口径（decision=NULL 占位行值域不含 APPROVE/REJECT，
        // 显式过滤防后续值域扩展时误判；写法对照 maybeEscalateAfterArbitration L910-912）。
        boolean approve = rows.stream().filter(r -> r.getDecision() != null)
            .anyMatch(r -> SIGNER_ROLES.contains(r.getReviewerType())
            && "APPROVE".equals(r.getDecision()));
        boolean reject = rows.stream().filter(r -> r.getDecision() != null)
            .anyMatch(r -> SIGNER_ROLES.contains(r.getReviewerType())
            && "REJECT".equals(r.getDecision()));
        return approve && reject;
    }

    /** 分歧自动开仲裁：审计开启 + 预落组长待裁行 + 邀请通知（AC-GATE-10 链起点）。
     * <p>工作台对偶（WB-17-1）：开仲裁即预落每位组长一条 decision=NULL 待裁行
     * （分配即落行，与 gate_reviews 预建占位行同构）；幂等由先查 + uk(gate_id, round, arbitrator_id) 兜底。 */
    /**
     * 双 PM 分歧 → 自动开组长仲裁（BR-GATE-06）；通知在册组长提交仲裁意见（AC-GATE-10）。
     *
     * <p>调用链 settle → advance ← sign()/arbitrate()（均 {@code @Transactional}），本方法在宿主
     * 事务内执行，故走 {@link NotificationService#publishAfterCommit}：延迟提交后独立事务发送，
     * 宿主回滚时仲裁邀请不发出（避免用户收到一条指向不存在仲裁单的待办）。</p>
     */
    private void openArbitration(Gate gate, IpdActor actor) {
        audit(actor, gate, "GATE_ARBITRATION_OPEN",
            "双PM意见分歧，自动发起组长仲裁（BR-GATE-06）", "round", gate.getCurrentRound());
        List<GateArbitration> existingRows = arbitrationRows(gate, ROLE_GROUP_LEADER);
        for (Person leader : collectLeaders(gate)) {
            boolean preallocated = existingRows.stream()
                .anyMatch(o -> leader.getId().equals(o.getArbitratorId()));
            if (!preallocated) {
                arbitrationMapper.insert(GateArbitration.builder()
                    .gateId(gate.getId())
                    .round(gate.getCurrentRound())
                    .arbitratorType(ROLE_GROUP_LEADER)
                    .arbitratorId(leader.getId())
                    .build()); // decision/opinion 留空 = 待裁（2026-09-08 ALTER 后可 NULL）
            }
            notificationService.publishAfterCommit(leader.getId(),
                NotificationService.Types.GATE_ARBITRATION_REQUEST, NotificationService.KIND_ACTION,
                "gate", gate.getId(),
                "Gate " + gate.getGateCode() + " 双PM意见分歧，请仲裁",
                "第 " + gate.getCurrentRound() + " 轮双方意见冲突，请提交仲裁意见（AC-GATE-10）",
                "/reviews/gate/" + gate.getId());
        }
    }

    /**
     * R11 / A4 修复（预落待签占位行 · openArbitration 的 WB-17-1 对偶范式）：
     * gate 提交（GateElementResultService.submit 置 startedAt）时刻为本轮应签方预落
     * gate_reviews decision=NULL 占位行——KeyGateAggregator 锚点
     * （reviewer_id=actor AND decision IS NULL）自此有了生产者，「GR- 卡真活永不投递」
     * 死路（A4）闭环。
     *
     * <p>真库探针实证（登记原文 2026-09-08；本次修复前复核仍成立）：10 个已提交待签
     * PENDING gate 中 8 个 gate_reviews 零行——R30 改道后 GateCreationService 不再建
     * 占位行，sign 落带值行、insertAbstain 落 ABSTAIN，decision NULL 行无任何生产者。
     *
     * <ul>
     *   <li>应签方：单签 Gate 取 leadSideOf(gateCode) 主导方一行；G1/G5 双签
     *       （isDualSignGate）落 MARKET_PM + RD_PM 两行（与签署矩阵 BR-GATE-03 对齐）</li>
     *   <li>签署人：signerPersonId(gate, side) 解析 project_members 在册首个人；
     *       dueAt/round 照 sign 流口径（dueAtFrom + gate.currentRound）</li>
     *   <li>幂等：同轮同角色已有行即跳过（防重复 submit / 与既有行撞
     *       uk_gr_gate_type_round；仿 openArbitration 预落查重）</li>
     *   <li>解析不到签署人（该角色无在册成员）⇒ log.warn 跳过<b>不抛错</b>：
     *       保护存量 5 个无 PM 成员的在途 gate 提交链路不塌方（数据治理项另卡处理，
     *       不混入本修复）——与 A2 fail-closed 口径不同的原因：CC- 提议入口有双 PM
     *       强参可校验，而 submit 是要素判定收尾，不应因成员配置缺失回滚已冻结快照。</li>
     * </ul>
     */
    public void openSignQueue(Gate gate) {
        if (gate == null || gate.getId() == null) {
            return;
        }
        List<String> sides = isDualSignGate(gate.getGateCode())
            ? List.of("MARKET_PM", "RD_PM")
            : List.of(leadSideOf(gate.getGateCode()));
        List<GateReview> existing = roundRows(gate.getId(), gate.getCurrentRound());
        Date due = dueAtFrom(gate);
        for (String side : sides) {
            boolean exists = existing.stream().anyMatch(r -> side.equals(r.getReviewerType()));
            if (exists) {
                continue; // 幂等：同轮同角色已有行（占位或已签）不重复预落
            }
            Long personId = signerPersonId(gate, side);
            if (personId == null) {
                log.warn("[R11 A4] gate {}（projectId={} 第 {} 轮）角色 {} 无在册签署人，"
                        + "跳过预落待签占位行（工作台 GR- 卡该角色不可达；数据治理项另卡处理）",
                    gate.getId(), gate.getProjectId(), gate.getCurrentRound(), side);
                continue;
            }
            reviewMapper.insert(GateReview.builder()
                .gateId(gate.getId())
                .reviewerType(side)
                .reviewerId(personId)
                .dueAt(due)
                .round(gate.getCurrentRound())
                .build()); // decision/opinion/signedAt 留 NULL = 待签（工作台「分配即落行」锚点）
        }
    }

    /** 组长意见齐备后：一致 ⇒ SETTLED 审计 + 结果知会双方；
     * 不一致 ⇒ ESCALATED 审计 + 通知全部超管终裁（AC-GATE-10 "自动升级"）。 */
    private void maybeEscalateAfterArbitration(Gate gate, IpdActor actor) {
        List<Person> leaders = collectLeaders(gate);
        if (leaders.isEmpty()) {
            return;
        }
        // 只统计已裁行：预落的 decision=NULL 待裁行不算「已提交」
        List<GateArbitration> opinions = arbitrationRows(gate, ROLE_GROUP_LEADER).stream()
            .filter(o -> o.getDecision() != null).toList();
        boolean allOpined = leaders.stream().noneMatch(l -> opinions.stream()
            .noneMatch(o -> l.getId().equals(o.getArbitratorId())));
        if (!allOpined) {
            return; // 还有组长未提交
        }
        String first = opinions.get(0).getDecision();
        if (opinions.stream().allMatch(o -> first.equals(o.getDecision()))) {
            audit(actor, gate, "GATE_ARBITRATION_SETTLED",
                "组长仲裁一致：" + first, "round", gate.getCurrentRound());
            notifyBothPms(gate, NotificationService.Types.GATE_ARBITRATION_RESULT,
                "Gate " + gate.getGateCode() + " 仲裁结果：" + ("APPROVE".equals(first) ? "支持通过" : "支持驳回"),
                "第 " + gate.getCurrentRound() + " 轮双PM分歧经组长仲裁达成一致意见");
        } else {
            audit(actor, gate, "GATE_ARBITRATION_ESCALATED",
                "两组长意见不一致，自动升级超管终裁（BR-GATE-06）", "round", gate.getCurrentRound());
            for (Person admin : superAdmins()) {
                // 本方法经 arbitrate()（@Transactional）链路执行，走 publishAfterCommit 防回滚后误发终裁待办。
                notificationService.publishAfterCommit(admin.getId(),
                    NotificationService.Types.GATE_FINAL_RULING_REQUEST, NotificationService.KIND_ACTION,
                    "gate", gate.getId(),
                    "Gate " + gate.getGateCode() + " 两组长仲裁不一致，请终裁",
                    "第 " + gate.getCurrentRound() + " 轮双PM分歧升级至超管终裁（AC-GATE-10），请提交终裁意见",
                    "/reviews/gate/" + gate.getId());
            }
        }
    }

    /** 弃权行（decision=ABSTAIN，signedAt=null 表示未实际签署；AC-GATE-08）。 */
    private void insertAbstain(Gate gate, String side, Date due) {
        Long personId = signerPersonId(gate, side);
        if (personId == null) {
            throw new IpdBusinessException("签署方在册成员缺失，无法标记弃权：" + side
                + "（projectId=" + gate.getProjectId() + "）");
        }
        // R11 / A4：本轮同角色已有 decision=NULL 占位行（openSignQueue 预落）⇒ 弃权落原行
        // （UPDATE），直插会撞 uk_gr_gate_type_round 唯一约束；无占位行（存量/旧路径）退回 INSERT。
        GateReview placeholder = roundRows(gate.getId(), gate.getCurrentRound()).stream()
            .filter(r -> side.equals(r.getReviewerType()) && r.getDecision() == null)
            .findFirst().orElse(null);
        if (placeholder != null) {
            placeholder.setDecision("ABSTAIN")
                .setOpinion("超期未签署，自动弃权（BR-GATE-04 / AC-GATE-08）");
            reviewMapper.updateById(placeholder);
            return;
        }
        reviewMapper.insert(GateReview.builder()
            .gateId(gate.getId())
            .reviewerType(side)
            .reviewerId(personId)
            .decision("ABSTAIN")
            .opinion("超期未签署，自动弃权（BR-GATE-04 / AC-GATE-08）")
            .dueAt(due)
            .round(gate.getCurrentRound())
            .build());
    }

    /** 超时流转落终态 + 审计（弃权事件/按主导方执行各自留痕）。 */
    private void settleTimeout(Gate gate, String status, IpdActor operator, String reason) {
        // R24 接线：状态机守卫 preCheck（fail-closed）。仅当 Gate 当前仍处 PENDING 才验证迁移合法性；
        // （护责双重设防御：双调扫描（scanTimeout）可能在主流程之后到达导致双 settle，这里仅在 PENDING 时落 UPDATE）
        if (STATUS_PENDING.equals(gate.getStatus())) {
            guardSupport.preCheck(STATUS_PENDING, status, "settleTimeout");
            gateMapper.update(null, new LambdaUpdateWrapper<Gate>()
                .eq(Gate::getId, gate.getId())
                .set(Gate::getStatus, status));
            // R24 接线：postCommit 跨域副作用（事务后）——本规则 crossDomain=false、仅作后续扩展点。
            guardSupport.registerPostCommit(STATUS_PENDING, status, "settleTimeout", operator.id(), gate.getId());
        }
        audit(operator, gate, STATUS_APPROVED.equals(status) ? "GATE_APPROVE" : "GATE_ABSTAIN_TIMEOUT",
            reason, "round", gate.getCurrentRound());
    }

    /** 双 PM 各自的产品组长（在册双 PM → 所在组 → 组长），按人去重；
     * 成员/人员绑定缺失时退化为空列表（仲裁链静默不触发，不阻塞主流程）。 */
    private List<Person> collectLeaders(Gate gate) {
        List<ProjectMember> members = memberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, gate.getProjectId())
            .in(ProjectMember::getRole, List.of("MARKET_PM", "RD_PM"))
            .isNull(ProjectMember::getExitDate));
        if (members == null || members.isEmpty()) {
            return List.of();
        }
        List<Long> pmIds = members.stream().map(ProjectMember::getPersonId).distinct().toList();
        List<Person> pms = personMapper.selectBatchIds(pmIds);
        if (pms == null || pms.isEmpty()) {
            return List.of();
        }
        List<Long> groupIds = pms.stream().map(Person::getGroupId)
            .filter(Objects::nonNull).distinct().toList();
        if (groupIds.isEmpty()) {
            return List.of();
        }
        return personMapper.selectList(new LambdaQueryWrapper<Person>()
            .eq(Person::getPersonType, ROLE_GROUP_LEADER)
            .in(Person::getGroupId, groupIds));
    }

    /** 某签署角色在本项目的在册人（project_members 首个命中；缺失返回 null）。 */
    private Long signerPersonId(Gate gate, String role) {
        List<ProjectMember> members = memberMapper.selectList(new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getProjectId, gate.getProjectId())
            .eq(ProjectMember::getRole, role)
            .isNull(ProjectMember::getExitDate));
        return members == null || members.isEmpty() ? null : members.get(0).getPersonId();
    }

    /** 全部超管（persons.personType=SUPER_ADMIN）。 */
    private List<Person> superAdmins() {
        return personMapper.selectList(new LambdaQueryWrapper<Person>()
            .eq(Person::getPersonType, ROLE_SUPER_ADMIN));
    }

    /** 知会双方 PM（弃权流转/仲裁/终裁结果；在册成员缺失时静默跳过）。
     *  <p>三个调用点（scanTimeout / finalRuling / maybeEscalateAfterArbitration）都在
     *  {@code @Transactional} 方法内（后者经 arbitrate() 进入），故走
     *  {@link NotificationService#publishAfterCommit}：宿主回滚时结果知会不发出，避免「通知说已出结果、
     *  库里却什么都没变」。personId 为 null 时 publishAfterCommit 内部 WARN 跳过（原实现靠外层 if 守）。</p> */
    private void notifyBothPms(Gate gate, String eventType, String title, String content) {
        for (String role : List.of("MARKET_PM", "RD_PM")) {
            Long personId = signerPersonId(gate, role);
            if (personId != null) {
                notificationService.publishAfterCommit(personId, eventType, NotificationService.KIND_ACTION,
                    "gate", gate.getId(), title, content, "/reviews/gate/" + gate.getId());
            }
        }
    }

    /** 本轮某类型的仲裁意见行。 */
    private List<GateArbitration> arbitrationRows(Gate gate, String arbitratorType) {
        return arbitrationMapper.selectList(new LambdaQueryWrapper<GateArbitration>()
            .eq(GateArbitration::getGateId, gate.getId())
            .eq(GateArbitration::getRound, gate.getCurrentRound())
            .eq(GateArbitration::getArbitratorType, arbitratorType));
    }

    private void audit(IpdActor actor, Gate gate, String action, String reason, Object... pairs) {
        auditLogService.append(AuditLog.builder()
            .action(action)
            .entityType("gates")
            .entityId(gate.getId())
            .operatorId(actor.id())
            .operatorName(actor.name())
            .operatorRole(actor.role())
            .afterData(pairs.length == 0 ? null : AuditEventData.json(pairs))
            .reason(reason)
            .createTime(now())
            .build());
    }
}
