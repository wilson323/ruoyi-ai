package org.ruoyi.ipd.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.StateTransitionRule;
import org.ruoyi.ipd.service.IAuditLogService;
import org.ruoyi.ipd.service.NotificationService;
import org.ruoyi.ipd.service.StateMachineGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认跨状态机守卫（ROOT-R3-P0-1；规则元数据走内存 ConcurrentHashMap）
 *
 * <p>设计原则：
 * <ul>
 *   <li>规则元数据全部在内存（ConcurrentHashMap），无新增表（与任务「不动 DDL」一致）</li>
 *   <li>种子规则由 {@link #initRules()} 在 Spring {@code @PostConstruct} 阶段注入，覆盖：
 *       IDeletionRequestService 6 条合法迁移 + BonusPoolService 3 条合法迁移 + 4 条终态收敛通配；
 *       2026-09-09 治理轮（P1-2 集中化第一步：登记不接线）补登 5 台 ad-hoc 状态机 19 条
 *       + KpiRecordService 7 条（含 *→ARCHIVED 通配），共 8 台机器 36 条单一事实源</li>
 *   <li>preCheck 三段判定：① 精确匹配 ② 通配「*」+ 已知终态收敛 ③ 其余非法抛 IpdBusinessException</li>
 *   <li>postCommit 按 crossDomain 分流：true=写 audit + 推 FYI 通知；false=no-op</li>
 *   <li>postCommit 失败只记日志、不抛（已提交的事务不可回滚，避免污染审计链）</li>
 * </ul>
 *
 * <p>关键不变量：
 * <ul>
 *   <li>并发安全：ConcurrentHashMap + 不可变 StateTransitionRule（lombok @Data 但注册前 builder
 *       已固化；不修改既有规则）</li>
 *   <li>零依赖循环：构造函数注入只接 IAuditLogService + NotificationService（两个底层组件），
 *       不反向依赖任何上层业务 Service</li>
 *   <li>测试隔离：单元测试在 {@code @BeforeEach} 调用 {@link #resetRules()} 清空后再种</li>
 * </ul>
 */
@Slf4j
@Component
public class DefaultStateMachineGuard implements StateMachineGuard {

    /** DeletionRequest 已知终态：DELETED / REJECTED / WITHDRAWN */
    private static final Set<String> DELETION_TERMINAL =
        Set.of("DELETED", "REJECTED", "WITHDRAWN");
    /** BonusPool 已知终态：CONFIRMED（freeze 后不可再变） / DISTRIBUTED */
    private static final Set<String> BONUS_POOL_TERMINAL =
        Set.of("CONFIRMED", "DISTRIBUTED");
    /** KpiRecord 已知终态：APPROVED / REJECTED / ARCHIVED（2026-09-09 治理轮补登，配套 *→ARCHIVED 通配） */
    private static final Set<String> KPI_RECORD_TERMINAL =
        Set.of("APPROVED", "REJECTED", "ARCHIVED");
    /** BidInvitation 已知终态：CLOSED（D-1 批次补登，配套 *→CLOSED|close 通配收敛） */
    private static final Set<String> BID_INVITATION_TERMINAL =
        Set.of("CLOSED");

    /** 规则表：key = "{entityType}:{fromState}->{toState}" */
    private final Map<String, StateTransitionRule> rules = new ConcurrentHashMap<>();

    private final IAuditLogService auditLogService;
    private final NotificationService notificationService;

    @Autowired
    public DefaultStateMachineGuard(IAuditLogService auditLogService,
                                    NotificationService notificationService) {
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    /**
     * Spring 启动后注入种子规则（覆盖 DeletionRequest / BonusPool 状态机全量合法迁移 + 终态收敛）
     */
    @PostConstruct
    public void initRules() {
        // ---- DeletionRequest 状态机：DRAFT→LEADER_REVIEW→ADMIN_REVIEW→DELETED/REJECTED；WITHDRAWN ----
        register(StateTransitionRule.builder()
            .key("deletion_request:DRAFT->LEADER_REVIEW|submit")
            .entityType("deletion_request")
            .fromState("DRAFT")
            .toState("LEADER_REVIEW")
            .trigger("submit")
            .crossDomain(false)
            .description("提交删除申请进入组长初审")
            .build());
        register(StateTransitionRule.builder()
            .key("deletion_request:LEADER_REVIEW->ADMIN_REVIEW|leaderApprove")
            .entityType("deletion_request")
            .fromState("LEADER_REVIEW")
            .toState("ADMIN_REVIEW")
            .trigger("leaderApprove")
            .crossDomain(true)        // 跨域：触发超管待办
            .description("组长通过进入超管终审（跨域→推超管 ACTION 通知）")
            .build());
        register(StateTransitionRule.builder()
            .key("deletion_request:LEADER_REVIEW->REJECTED|leaderReject")
            .entityType("deletion_request")
            .fromState("LEADER_REVIEW")
            .toState("REJECTED")
            .trigger("leaderReject")
            .crossDomain(true)        // 跨域：通知申请人
            .description("组长驳回（跨域→通知申请人）")
            .build());
        register(StateTransitionRule.builder()
            .key("deletion_request:ADMIN_REVIEW->DELETED|adminApprove")
            .entityType("deletion_request")
            .fromState("ADMIN_REVIEW")
            .toState("DELETED")
            .trigger("adminApprove")
            .crossDomain(true)        // 跨域：触发目标行软删副作用
            .description("超管通过触发原子软删（跨域→触发 DeleteAuditService）")
            .build());
        register(StateTransitionRule.builder()
            .key("deletion_request:ADMIN_REVIEW->REJECTED|adminReject")
            .entityType("deletion_request")
            .fromState("ADMIN_REVIEW")
            .toState("REJECTED")
            .trigger("adminReject")
            .crossDomain(true)        // 跨域：通知申请人
            .description("超管驳回（跨域→通知申请人）")
            .build());
        register(StateTransitionRule.builder()
            .key("deletion_request:*->WITHDRAWN|withdraw")
            .entityType("deletion_request")
            .fromState(StateTransitionRule.FROM_ANY)
            .toState("WITHDRAWN")
            .trigger("withdraw")
            .crossDomain(false)
            .description("申请人 24h 内撤回（终态收敛）")
            .build());
        register(StateTransitionRule.builder()
            .key("deletion_request:LEADER_REVIEW->ADMIN_REVIEW|escalateOverdue")
            .entityType("deletion_request")
            .fromState("LEADER_REVIEW")
            .toState("ADMIN_REVIEW")
            .trigger("escalateOverdue")
            .crossDomain(false)
            .description("组长逾期自动升级超管（PERF-P0-1 批量 UPDATE）")
            .build());

        // ---- BonusPool 状态机：DRAFT→CONFIRMED→DISTRIBUTED ----
        // P1-4 语义化：注册 INITIAL→DRAFT 创建迁移（业务语义：create new bonus pool）
        // 守卫层 preCheck 接受 fromState=Java null，isAllowed 内部映射到字面量 "INITIAL" 再与规则表 key 拼接
        // 修复前 BonusPoolService.compute 真环境 fail-closed 400「守卫层未登记该迁移」
        register(StateTransitionRule.builder()
            .key("bonus_pool:INITIAL->DRAFT|compute")
            .entityType("bonus_pool")
            .fromState("INITIAL")     // P1-4 语义化:创建迁移 = INITIAL 状态
            .toState("DRAFT")
            .trigger("compute")
            .crossDomain(false)
            .description("奖金池 compute：INITIAL→DRAFT（初始创建迁移，业务新建）")
            .build());
        register(StateTransitionRule.builder()
            .key("bonus_pool:DRAFT->CONFIRMED|freeze")
            .entityType("bonus_pool")
            .fromState("DRAFT")
            .toState("CONFIRMED")
            .trigger("freeze")
            .crossDomain(false)
            .description("奖金池 freeze：DRAFT→CONFIRMED（冻结后禁止回滚）")
            .build());
        register(StateTransitionRule.builder()
            .key("bonus_pool:CONFIRMED->DISTRIBUTED|distribute")
            .entityType("bonus_pool")
            .fromState("CONFIRMED")
            .toState("DISTRIBUTED")
            .trigger("distribute")
            .crossDomain(true)        // 跨域：触发津贴账本写入
            .description("奖金池 distribute：CONFIRMED→DISTRIBUTED（跨域→写入 AllowanceLedger）")
            .build());
        // 2026-09-09 C3 缺陷修复：BonusPoolService.distribute 业务上允许 DRAFT/CONFIRMED 两入口
        // （「仅 DRAFT/CONFIRMED 可分配」），但此前只登记了 CONFIRMED 路径——DRAFT 入口时 service
        // 硬编码传 from=CONFIRMED 绕过守卫。补登记 DRAFT 直分路径（未冻结确认即分配，同样写账本）
        register(StateTransitionRule.builder()
            .key("bonus_pool:DRAFT->DISTRIBUTED|distribute")
            .entityType("bonus_pool")
            .fromState("DRAFT")
            .toState("DISTRIBUTED")
            .trigger("distribute")
            .crossDomain(true)        // 跨域：同 CONFIRMED 路径，分配即写 bonus_allocations 台账
            .description("奖金池 distribute：DRAFT→DISTRIBUTED（未冻结直分，跨域→写台账）")
            .build());

        // ---- 2026-09-09 治理轮（P1-2 集中化第一步：登记不接线）----
        // 其余状态机的 Service 仍用各自 ad-hoc 守卫（本轮不改行为）；本表先作单一事实源。
        // 接线时参照 KpiRecordService 的 setter 注入 + preCheckGuard/registerPostCommit 模式。
        // crossDomain 暂全 false——待接线轮按业务逐条评估后再开启审计/通知副作用。

        // ---- GateReview 状态机：PENDING→APPROVED/REJECTED/ABSTAINED_TIMEOUT；REJECTED/ABSTAINED_TIMEOUT→PENDING（reopen）----
        register(StateTransitionRule.builder()
            .key("gate_review:PENDING->APPROVED|sign")
            .entityType("gate_review")
            .fromState("PENDING").toState("APPROVED").trigger("sign")
            .crossDomain(false)
            .description("双签合议 APPROVE / 领域 Gate 主导方单签终态")
            .build());
        register(StateTransitionRule.builder()
            .key("gate_review:PENDING->REJECTED|sign")
            .entityType("gate_review")
            .fromState("PENDING").toState("REJECTED").trigger("sign")
            .crossDomain(false)
            .description("双签 REJECT 终态")
            .build());
        register(StateTransitionRule.builder()
            .key("gate_review:PENDING->ABSTAINED_TIMEOUT|settleTimeout")
            .entityType("gate_review")
            .fromState("PENDING").toState("ABSTAINED_TIMEOUT").trigger("settleTimeout")
            .crossDomain(false)
            .description("签署超时弃权收敛")
            .build());
        register(StateTransitionRule.builder()
            .key("gate_review:REJECTED->PENDING|reopen")
            .entityType("gate_review")
            .fromState("REJECTED").toState("PENDING").trigger("reopen")
            .crossDomain(false)
            .description("驳回重开（APPROVED 为硬终态不可重开）")
            .build());
        register(StateTransitionRule.builder()
            .key("gate_review:ABSTAINED_TIMEOUT->PENDING|reopen")
            .entityType("gate_review")
            .fromState("ABSTAINED_TIMEOUT").toState("PENDING").trigger("reopen")
            .crossDomain(false)
            .description("弃权超时后重开")
            .build());

        // ---- LaunchDateChange 状态机：INITIAL→PENDING_SECOND→CONFIRMED/REJECTED（双签）----
        register(StateTransitionRule.builder()
            .key("launch_date_change:INITIAL->PENDING_SECOND|propose")
            .entityType("launch_date_change")
            .fromState("INITIAL").toState("PENDING_SECOND").trigger("propose")
            .crossDomain(false)
            .description("提议上市日期变更（创建迁移，from=null 映射 INITIAL）")
            .build());
        register(StateTransitionRule.builder()
            .key("launch_date_change:PENDING_SECOND->CONFIRMED|secondSign")
            .entityType("launch_date_change")
            .fromState("PENDING_SECOND").toState("CONFIRMED").trigger("secondSign")
            .crossDomain(false)
            .description("第二签 APPROVE 终态（互补角色/超管校验在 Service）")
            .build());
        register(StateTransitionRule.builder()
            .key("launch_date_change:PENDING_SECOND->REJECTED|secondSign")
            .entityType("launch_date_change")
            .fromState("PENDING_SECOND").toState("REJECTED").trigger("secondSign")
            .crossDomain(false)
            .description("第二签 REJECT 终态")
            .build());

        // ---- CoefficientChange 状态机：INITIAL→PENDING_LEADER→CONFIRMED/REJECTED（组长单审）----
        register(StateTransitionRule.builder()
            .key("coefficient_change:INITIAL->PENDING_LEADER|propose")
            .entityType("coefficient_change")
            .fromState("INITIAL").toState("PENDING_LEADER").trigger("propose")
            .crossDomain(false)
            .description("提议系数变更（创建迁移）")
            .build());
        register(StateTransitionRule.builder()
            .key("coefficient_change:PENDING_LEADER->CONFIRMED|leaderApprove")
            .entityType("coefficient_change")
            .fromState("PENDING_LEADER").toState("CONFIRMED").trigger("leaderApprove")
            .crossDomain(false)
            .description("组长通过终态")
            .build());
        register(StateTransitionRule.builder()
            .key("coefficient_change:PENDING_LEADER->REJECTED|leaderReject")
            .entityType("coefficient_change")
            .fromState("PENDING_LEADER").toState("REJECTED").trigger("leaderReject")
            .crossDomain(false)
            .description("组长驳回终态")
            .build());

        // ---- Contribution 状态机：INITIAL/DRAFT→SUBMITTED→CONFIRMED（组长可退回 DRAFT）----
        register(StateTransitionRule.builder()
            .key("contribution:INITIAL->DRAFT|fill")
            .entityType("contribution")
            .fromState("INITIAL").toState("DRAFT").trigger("fill")
            .crossDomain(false)
            .description("双 PM 填报（创建迁移，同 (projectId,role) 幂等覆盖）")
            .build());
        register(StateTransitionRule.builder()
            .key("contribution:DRAFT->SUBMITTED|submit")
            .entityType("contribution")
            .fromState("DRAFT").toState("SUBMITTED").trigger("submit")
            .crossDomain(false)
            .description("双方均填报完成自动提交")
            .build());
        register(StateTransitionRule.builder()
            .key("contribution:SUBMITTED->CONFIRMED|confirm")
            .entityType("contribution")
            .fromState("SUBMITTED").toState("CONFIRMED").trigger("confirm")
            .crossDomain(false)
            .description("产品组长确认终态（触发版本归档在 Service）")
            .build());
        register(StateTransitionRule.builder()
            .key("contribution:SUBMITTED->DRAFT|reject")
            .entityType("contribution")
            .fromState("SUBMITTED").toState("DRAFT").trigger("reject")
            .crossDomain(false)
            .description("组长退回重填")
            .build());
        register(StateTransitionRule.builder()
            .key("contribution:DRAFT->CONFIRMED|confirm")
            .entityType("contribution")
            .fromState("DRAFT").toState("CONFIRMED").trigger("confirm")
            .crossDomain(false)
            .description("单侧场景直接确认（Service 允许 DRAFT/SUBMITTED 双入口）")
            .build());

        // ---- Handover 状态机（R28 治理轮已接线）：INITIAL→DRAFT→COMPLETED→ROLLED_BACK ----
        register(StateTransitionRule.builder()
            .key("handover_record:INITIAL->DRAFT|create")
            .entityType("handover_record")
            .fromState("INITIAL").toState("DRAFT").trigger("create")
            .crossDomain(false)
            .description("发起移交（创建迁移）")
            .build());
        register(StateTransitionRule.builder()
            .key("handover_record:DRAFT->COMPLETED|accept")
            .entityType("handover_record")
            .fromState("DRAFT").toState("COMPLETED").trigger("accept")
            .crossDomain(false)
            .description("承接人确认（超时漏斗见 HandoverOverdueScanner）")
            .build());
        register(StateTransitionRule.builder()
            .key("handover_record:COMPLETED->ROLLED_BACK|rollback")
            .entityType("handover_record")
            .fromState("COMPLETED").toState("ROLLED_BACK").trigger("rollback")
            .crossDomain(false)
            .description("完成后 24h 内撤销（HANDOVER_LOCKED 50017）")
            .build());

        // ---- KpiRecord 状态机：守卫调用已在（record/approve/reject/archive 4 处）但规则缺位，
        // 写路径暂无产线 HTTP 入口（KpiRecordController 仅 3 个只读端点）——规则补齐后若接线，
        // preCheck 才能放行而非 fail-closed 抛异常。approve/reject 的 from 含 PENDING_REVIEW（javadoc 契约）。----
        register(StateTransitionRule.builder()
            .key("kpi_record:INITIAL->EDITING|record")
            .entityType("kpi_record")
            .fromState("INITIAL").toState("EDITING").trigger("record")
            .crossDomain(false)
            .description("新建 KPI 填报（from=null 映射 INITIAL）")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_record:EDITING->EDITING|record")
            .entityType("kpi_record")
            .fromState("EDITING").toState("EDITING").trigger("record")
            .crossDomain(false)
            .description("草稿更新自环")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_record:EDITING->APPROVED|approve")
            .entityType("kpi_record")
            .fromState("EDITING").toState("APPROVED").trigger("approve")
            .crossDomain(false)
            .description("审批通过（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_record:PENDING_REVIEW->APPROVED|approve")
            .entityType("kpi_record")
            .fromState("PENDING_REVIEW").toState("APPROVED").trigger("approve")
            .crossDomain(false)
            .description("送审后审批通过（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_record:EDITING->REJECTED|reject")
            .entityType("kpi_record")
            .fromState("EDITING").toState("REJECTED").trigger("reject")
            .crossDomain(false)
            .description("驳回（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_record:PENDING_REVIEW->REJECTED|reject")
            .entityType("kpi_record")
            .fromState("PENDING_REVIEW").toState("REJECTED").trigger("reject")
            .crossDomain(false)
            .description("送审后驳回（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_record:*->ARCHIVED|archive")
            .entityType("kpi_record")
            .fromState("*").toState("ARCHIVED").trigger("archive")
            .crossDomain(false)
            .description("任意态归档（终态收敛通配，isTerminalState 配套 KPI_RECORD_TERMINAL）")
            .build());

        // ---- 2026-09-09 治理轮（P1-2 集中化第一步：登记不接线）----
        // 其余状态机的 Service 仍用各自 ad-hoc 守卫（本轮不改行为）；本表先作单一事实源。
        // 接线时参照 KpiRecordService 的 setter 注入 + preCheckGuard/registerPostCommit 模式。
        // crossDomain 暂全 false——待接线轮按业务逐条评估后再开启审计/通知副作用。
        // R28 治理轮：handover_record / requirement_change 两台已接线（setter 注入 + fail-closed）。

        // R24 治理轮补登：settleTimeout 触发「一方弃权按主导方意见执行」（L439）；
        // 此前 GateReviewService 已有该转移点但规则表未登记，预接线后立即报 fail-closed。
        register(StateTransitionRule.builder()
            .key("gate_review:PENDING->APPROVED|settleTimeout")
            .entityType("gate_review")
            .fromState("PENDING").toState("APPROVED").trigger("settleTimeout")
            .crossDomain(false)
            .description("签署超时一方弃权按主导方意见执行（主导方已签 APPROVE）")
            .build());

        // ---- R28 治理轮：RequirementChange 状态机接线（create/submit/reject/sign 4 迁移点）----
        register(StateTransitionRule.builder()
            .key("requirement_change:INITIAL->DRAFT|create")
            .entityType("requirement_change")
            .fromState("INITIAL").toState("DRAFT").trigger("create")
            .crossDomain(false)
            .description("创建变更单草稿（from=null 映射 INITIAL）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_change:DRAFT->PENDING_SIGN|submit")
            .entityType("requirement_change")
            .fromState("DRAFT").toState("PENDING_SIGN").trigger("submit")
            .crossDomain(false)
            .description("提交双签队列")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_change:PENDING_SIGN->REJECTED|reject")
            .entityType("requirement_change")
            .fromState("PENDING_SIGN").toState("REJECTED").trigger("reject")
            .crossDomain(false)
            .description("任一方 REJECT，整体否决（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_change:PENDING_SIGN->APPROVED|sign")
            .entityType("requirement_change")
            .fromState("PENDING_SIGN").toState("APPROVED").trigger("sign")
            .crossDomain(true)
            .description("双签 APPROVE 生效并回写需求池 ADOPTED（跨域：requirements）")
            .build());

        // ---- R33 一期分片 E 停手项补登：KpiSharedConfirm 状态机接线（create/recapture/secondSign 3 迁移点）----
        // KpiSharedConfirmService 尾部签名确认机：两组长双签（首签自环不迁移状态，不登记）；
        // 此前规则表无 kpi_shared_confirm 机（哨兵「kpi 7」是 kpi_record 填报机），接线 preCheck 会 fail-closed。
        register(StateTransitionRule.builder()
            .key("kpi_shared_confirm:INITIAL->PENDING|create")
            .entityType("kpi_shared_confirm")
            .fromState("INITIAL").toState("PENDING").trigger("create")
            .crossDomain(false)
            .description("创建共担 KPI 确认单（from=null 映射 INITIAL）")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_shared_confirm:CONFIRMED->PENDING|recapture")
            .entityType("kpi_shared_confirm")
            .fromState("CONFIRMED").toState("PENDING").trigger("recapture")
            .crossDomain(false)
            .description("重归集复位（已双签确认单重新收数回待签）")
            .build());
        register(StateTransitionRule.builder()
            .key("kpi_shared_confirm:PENDING->CONFIRMED|secondSign")
            .entityType("kpi_shared_confirm")
            .fromState("PENDING").toState("CONFIRMED").trigger("secondSign")
            .crossDomain(false)
            .description("第二签（不同组长）双签达成终态")
            .build());


        // ---- R28 补遗 §5-2 接线轮（fix/r28-guard-wire）：StageAction 状态机登记（C8 消灭）----
        // from→to 严格图：NOT_STARTED→IN_PROGRESS→DONE/DELAYED；DELAYED→IN_PROGRESS/DONE；
        // NA 可在未开始/进行中登记（必 reason，Service 校验）；DONE/NA 硬终态。
        // 此前 transit 仅目标白名单（NOT_STARTED→DONE 直跳、DONE→IN_PROGRESS 回退皆放行，C8）。
        // 词表来源：StageActionService LIGHT/DEEP 枚举 + 前端 ACTION_STATUS_MACHINE 严格图
        // （ruoyi-ipd-web _shared/ipd-state-machines.ts），差异仅 NA 入边（前端图 NA 无入边属缺口，
        // 后端 BR 允许 NA 标记，见 StageActionServiceTest IN_PROGRESS→NA 与 AC-PROD-13 用例）。
        // 轻管跳阶契约（P143AcceptanceTest.lightCanSkipInProgressToDone，P1-4.3 验收）：
        // 守卫规则表无 depth 维度，登记该边同时放行深管直跳——深管直跳的彻底封禁（C8 全形）
        // 需 depth 维度或 Service 层校验，见接线轮报告「建议方案」，本轮不吞尾扩大改动。
        register(StateTransitionRule.builder()
            .key("stage_action:NOT_STARTED->DONE|complete")
            .entityType("stage_action")
            .fromState("NOT_STARTED").toState("DONE").trigger("complete")
            .crossDomain(false)
            .description("轻管跳阶直完成（P143 验收契约；深管完成条件仍由 validateCompletion 把关）")
            .build());
        register(StateTransitionRule.builder()
            .key("stage_action:NOT_STARTED->IN_PROGRESS|start")
            .entityType("stage_action")
            .fromState("NOT_STARTED").toState("IN_PROGRESS").trigger("start")
            .crossDomain(false)
            .description("动作开始（P1-4.3 深/轻管同图）")
            .build());
        register(StateTransitionRule.builder()
            .key("stage_action:IN_PROGRESS->DONE|complete")
            .entityType("stage_action")
            .fromState("IN_PROGRESS").toState("DONE").trigger("complete")
            .crossDomain(false)
            .description("动作完成（深管交付物/轻管完成日校验在 Service validateCompletion，先于守卫）")
            .build());
        register(StateTransitionRule.builder()
            .key("stage_action:DELAYED->DONE|complete")
            .entityType("stage_action")
            .fromState("DELAYED").toState("DONE").trigger("complete")
            .crossDomain(false)
            .description("逾期动作补完成（深管专属，轻管由目标白名单先拒 DELAYED）")
            .build());
        register(StateTransitionRule.builder()
            .key("stage_action:IN_PROGRESS->DELAYED|delay")
            .entityType("stage_action")
            .fromState("IN_PROGRESS").toState("DELAYED").trigger("delay")
            .crossDomain(false)
            .description("深管标记逾期（BR-IPD-05：轻管枚举无 DELAYED，Service 白名单先拒）")
            .build());
        register(StateTransitionRule.builder()
            .key("stage_action:DELAYED->IN_PROGRESS|resume")
            .entityType("stage_action")
            .fromState("DELAYED").toState("IN_PROGRESS").trigger("resume")
            .crossDomain(false)
            .description("逾期动作恢复进行")
            .build());
        register(StateTransitionRule.builder()
            .key("stage_action:NOT_STARTED->NA|mark_na")
            .entityType("stage_action")
            .fromState("NOT_STARTED").toState("NA").trigger("mark_na")
            .crossDomain(false)
            .description("未开始即标记不适用（必 reason，Service 校验；AC-PROD-13 等域规则在 Service）")
            .build());
        register(StateTransitionRule.builder()
            .key("stage_action:IN_PROGRESS->NA|mark_na")
            .entityType("stage_action")
            .fromState("IN_PROGRESS").toState("NA").trigger("mark_na")
            .crossDomain(false)
            .description("进行中改判不适用（必 reason）")
            .build());

        // ---- D-1 批次（补遗 §5 序 2 剩余 6 台接线轮）：project / requirement_v2 / bid_invitation /
        // bid_response / guest_demand / negative_feedback 六台状态机入表 + Service 接线。
        // 边=从各 Service 现有迁移路径逐条提取（零发明）；trigger 单词化与既有词表同风格；
        // 全部精确边零通配（不动 isTerminalState）。批量 UPDATE 旁路（expireOverdue / 遴选落选）
        // 以迁移为单位 preCheck 一次（与 DeletionRequestServiceImpl:386 批量先例同口径）。

        // ---- Project 状态机：INITIAL→DRAFT；DRAFT→TEAMING/ARCHIVED；TEAMING→ACTIVE/ARCHIVED；
        // ACTIVE→SUSPENDED/ARCHIVED；SUSPENDED→ACTIVE/ARCHIVED（ProjectService.STATUS_TRANSITIONS 同图）----
        register(StateTransitionRule.builder()
            .key("project:INITIAL->DRAFT|create")
            .entityType("project")
            .fromState("INITIAL").toState("DRAFT").trigger("create")
            .crossDomain(false)
            .description("立项创建（状态强制 DRAFT，P1-2.1）")
            .build());
        register(StateTransitionRule.builder()
            .key("project:INITIAL->PENDING_START|create")
            .entityType("project")
            .fromState("INITIAL").toState("PENDING_START").trigger("create")
            .crossDomain(false)
            .description("立项进入待开工，六阶段在批准后才生成")
            .build());
        register(StateTransitionRule.builder()
            .key("project:PENDING_START->TEAMING|approveStart")
            .entityType("project")
            .fromState("PENDING_START").toState("TEAMING").trigger("approveStart")
            .crossDomain(false)
            .description("产品线负责人批准开工")
            .build());
        register(StateTransitionRule.builder()
            .key("project:PENDING_START->START_REJECTED|rejectStart")
            .entityType("project")
            .fromState("PENDING_START").toState("START_REJECTED").trigger("rejectStart")
            .crossDomain(false)
            .description("产品线负责人拒绝开工，项目保留")
            .build());
        register(StateTransitionRule.builder()
            .key("project:START_REJECTED->PENDING_START|resubmitStart")
            .entityType("project")
            .fromState("START_REJECTED").toState("PENDING_START").trigger("resubmitStart")
            .crossDomain(false)
            .description("创建人在同一项目上再次提交开工")
            .build());
        register(StateTransitionRule.builder()
            .key("project:DRAFT->TEAMING|changeStatus")
            .entityType("project")
            .fromState("DRAFT").toState("TEAMING").trigger("changeStatus")
            .crossDomain(false)
            .description("草稿进入组队")
            .build());
        register(StateTransitionRule.builder()
            .key("project:DRAFT->ARCHIVED|changeStatus")
            .entityType("project")
            .fromState("DRAFT").toState("ARCHIVED").trigger("changeStatus")
            .crossDomain(false)
            .description("草稿直接归档")
            .build());
        register(StateTransitionRule.builder()
            .key("project:TEAMING->ACTIVE|changeStatus")
            .entityType("project")
            .fromState("TEAMING").toState("ACTIVE").trigger("changeStatus")
            .crossDomain(false)
            .description("组队完成进入进行中")
            .build());
        register(StateTransitionRule.builder()
            .key("project:TEAMING->ARCHIVED|changeStatus")
            .entityType("project")
            .fromState("TEAMING").toState("ARCHIVED").trigger("changeStatus")
            .crossDomain(false)
            .description("组队期归档")
            .build());
        register(StateTransitionRule.builder()
            .key("project:ACTIVE->SUSPENDED|changeStatus")
            .entityType("project")
            .fromState("ACTIVE").toState("SUSPENDED").trigger("changeStatus")
            .crossDomain(false)
            .description("进行中暂停")
            .build());
        register(StateTransitionRule.builder()
            .key("project:ACTIVE->ARCHIVED|changeStatus")
            .entityType("project")
            .fromState("ACTIVE").toState("ARCHIVED").trigger("changeStatus")
            .crossDomain(false)
            .description("进行中归档")
            .build());
        register(StateTransitionRule.builder()
            .key("project:SUSPENDED->ACTIVE|changeStatus")
            .entityType("project")
            .fromState("SUSPENDED").toState("ACTIVE").trigger("changeStatus")
            .crossDomain(false)
            .description("暂停恢复")
            .build());
        register(StateTransitionRule.builder()
            .key("project:SUSPENDED->ARCHIVED|changeStatus")
            .entityType("project")
            .fromState("SUSPENDED").toState("ARCHIVED").trigger("changeStatus")
            .crossDomain(false)
            .description("暂停归档（归档后只读禁迁出，Service 侧 ZK-IPD §二.10 先拦）")
            .build());

        // ---- requirement_v2 状态机（RequirementStateMachine 七态，单入口 transition()）----
        // DRAFT→SUBMITTED/REJECTED；SUBMITTED→ROUTED/REJECTED；ROUTED→ACCEPTED/REJECTED；
        // ACCEPTED→CHANGED/REJECTED；CHANGED→CLOSED/REJECTED。角色门在 Service（AC-REQ-05/06）。
        register(StateTransitionRule.builder()
            .key("requirement_v2:DRAFT->SUBMITTED|transition")
            .entityType("requirement_v2")
            .fromState("DRAFT").toState("SUBMITTED").trigger("transition")
            .crossDomain(false)
            .description("需求提交")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:DRAFT->REJECTED|transition")
            .entityType("requirement_v2")
            .fromState("DRAFT").toState("REJECTED").trigger("transition")
            .crossDomain(false)
            .description("草稿驳回/撤回（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:SUBMITTED->ROUTED|transition")
            .entityType("requirement_v2")
            .fromState("SUBMITTED").toState("ROUTED").trigger("transition")
            .crossDomain(false)
            .description("组长/超管派单路由")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:SUBMITTED->REJECTED|transition")
            .entityType("requirement_v2")
            .fromState("SUBMITTED").toState("REJECTED").trigger("transition")
            .crossDomain(false)
            .description("受理前驳回（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:ROUTED->ACCEPTED|transition")
            .entityType("requirement_v2")
            .fromState("ROUTED").toState("ACCEPTED").trigger("transition")
            .crossDomain(false)
            .description("双 PM 采纳")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:ROUTED->REJECTED|transition")
            .entityType("requirement_v2")
            .fromState("ROUTED").toState("REJECTED").trigger("transition")
            .crossDomain(false)
            .description("路由后驳回（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:ACCEPTED->CHANGED|transition")
            .entityType("requirement_v2")
            .fromState("ACCEPTED").toState("CHANGED").trigger("transition")
            .crossDomain(false)
            .description("采纳后变更（自动创建 RequirementChange DRAFT）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:ACCEPTED->REJECTED|transition")
            .entityType("requirement_v2")
            .fromState("ACCEPTED").toState("REJECTED").trigger("transition")
            .crossDomain(false)
            .description("采纳后撤回（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:CHANGED->CLOSED|transition")
            .entityType("requirement_v2")
            .fromState("CHANGED").toState("CLOSED").trigger("transition")
            .crossDomain(false)
            .description("项目级关闭（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("requirement_v2:CHANGED->REJECTED|transition")
            .entityType("requirement_v2")
            .fromState("CHANGED").toState("REJECTED").trigger("transition")
            .crossDomain(false)
            .description("超管撤变更（终态）")
            .build());

        // ---- BidInvitation 状态机：OPEN→SELECTED/EXPIRED/CLOSED；EXPIRED→SELECTED/CLOSED；
        // SELECTED→CLOSED（close 宽进现状如实登记）。javadoc 词表：OPEN → SELECTED / EXPIRED → CLOSED ----
        register(StateTransitionRule.builder()
            .key("bid_invitation:INITIAL->OPEN|create")
            .entityType("bid_invitation")
            .fromState("INITIAL").toState("OPEN").trigger("create")
            .crossDomain(false)
            .description("市场 PM 发起招标（AC-TEAM-03）")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_invitation:OPEN->SELECTED|select")
            .entityType("bid_invitation")
            .fromState("OPEN").toState("SELECTED").trigger("select")
            .crossDomain(false)
            .description("遴选定标（confirmToken 校验在 Service）")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_invitation:OPEN->EXPIRED|expire")
            .entityType("bid_invitation")
            .fromState("OPEN").toState("EXPIRED").trigger("expire")
            .crossDomain(false)
            .description("到期无人应标自动过期（expireOverdue 批量 UPDATE，迁移级 preCheck）")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_invitation:OPEN->CLOSED|withdraw")
            .entityType("bid_invitation")
            .fromState("OPEN").toState("CLOSED").trigger("withdraw")
            .crossDomain(false)
            .description("24h 内撤回（AC-TEAM-13）")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_invitation:*->CLOSED|close")
            .entityType("bid_invitation")
            .fromState(StateTransitionRule.FROM_ANY).toState("CLOSED").trigger("close")
            .crossDomain(false)
            .description("关闭招标单（close() 无状态门禁宽进，通配收敛至终态 CLOSED，isTerminalState 配套）")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_invitation:EXPIRED->SELECTED|adminAssign")
            .entityType("bid_invitation")
            .fromState("EXPIRED").toState("SELECTED").trigger("adminAssign")
            .crossDomain(false)
            .description("超管强制指派（EXPIRED 挂起 ≥30 日，Bug#4 门禁在 Service）")
            .build());

        // ---- BidResponse 状态机：PENDING→ACCEPTED/REJECTED/WITHDRAWN（遴选落选批量 REJECTED 同迁移）----
        register(StateTransitionRule.builder()
            .key("bid_response:INITIAL->PENDING|respond")
            .entityType("bid_response")
            .fromState("INITIAL").toState("PENDING").trigger("respond")
            .crossDomain(false)
            .description("研发 PM 应标")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_response:PENDING->ACCEPTED|select")
            .entityType("bid_response")
            .fromState("PENDING").toState("ACCEPTED").trigger("select")
            .crossDomain(false)
            .description("中标（回填 rd_pm_id）")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_response:PENDING->REJECTED|select")
            .entityType("bid_response")
            .fromState("PENDING").toState("REJECTED").trigger("select")
            .crossDomain(false)
            .description("落选（同单其余 PENDING 行批量置 REJECTED，迁移级 preCheck）")
            .build());
        register(StateTransitionRule.builder()
            .key("bid_response:PENDING->WITHDRAWN|withdraw")
            .entityType("bid_response")
            .fromState("PENDING").toState("WITHDRAWN").trigger("withdraw")
            .crossDomain(false)
            .description("应标人撤回（仅本人）")
            .build());

        // ---- GuestDemand 状态机（guest 域需求，requirements 表 source=PORTAL_GUEST）：SUBMITTED→WITHDRAWN ----
        register(StateTransitionRule.builder()
            .key("guest_demand:INITIAL->SUBMITTED|submit")
            .entityType("guest_demand")
            .fromState("INITIAL").toState("SUBMITTED").trigger("submit")
            .crossDomain(false)
            .description("游客提交需求（P4-1.1）")
            .build());
        register(StateTransitionRule.builder()
            .key("guest_demand:SUBMITTED->WITHDRAWN|withdraw")
            .entityType("guest_demand")
            .fromState("SUBMITTED").toState("WITHDRAWN").trigger("withdraw")
            .crossDomain(false)
            .description("受理前撤回（BR-REQ-03b，终态）")
            .build());

        // ---- NegativeFeedback 状态机：DRAFT→PENDING_DECISION→EXECUTED/REJECTED；EXECUTED→LIFTED ----
        register(StateTransitionRule.builder()
            .key("negative_feedback:INITIAL->DRAFT|create")
            .entityType("negative_feedback")
            .fromState("INITIAL").toState("DRAFT").trigger("create")
            .crossDomain(false)
            .description("负反馈创建（唯一索引防重复触发）")
            .build());
        register(StateTransitionRule.builder()
            .key("negative_feedback:DRAFT->PENDING_DECISION|submit")
            .entityType("negative_feedback")
            .fromState("DRAFT").toState("PENDING_DECISION").trigger("submit")
            .crossDomain(false)
            .description("提交认定（P3-8.2）")
            .build());
        register(StateTransitionRule.builder()
            .key("negative_feedback:PENDING_DECISION->EXECUTED|decide")
            .entityType("negative_feedback")
            .fromState("PENDING_DECISION").toState("EXECUTED").trigger("decide")
            .crossDomain(false)
            .description("组长/超管认定执行（AC-INC-36b）")
            .build());
        register(StateTransitionRule.builder()
            .key("negative_feedback:PENDING_DECISION->REJECTED|decide")
            .entityType("negative_feedback")
            .fromState("PENDING_DECISION").toState("REJECTED").trigger("decide")
            .crossDomain(false)
            .description("认定驳回（终态）")
            .build());
        register(StateTransitionRule.builder()
            .key("negative_feedback:EXECUTED->LIFTED|lift")
            .entityType("negative_feedback")
            .fromState("EXECUTED").toState("LIFTED").trigger("lift")
            .crossDomain(false)
            .description("解除（恢复津贴+bonusEligible，AC-INC-40 不可逆）")
            .build());

        log.info("StateMachineGuard 种子规则注入完成：{} 条", rules.size());
    }

    /**
     * 测试隔离用：清空所有规则（仅测试场景调用，生产路径由 initRules 一次性种子）
     */
    public void resetRules() {
        rules.clear();
    }

    /** 注册（供外部热加载与 initRules 复用） */
    private void register(StateTransitionRule rule) {
        registerRule(rule);
    }

    @Override
    public void registerRule(StateTransitionRule rule) {
        if (rule == null || rule.getKey() == null) {
            throw new IpdBusinessException("规则或 key 不可为空");
        }
        rules.put(rule.getKey(), rule);
    }

    @Override
    public boolean removeRule(String key) {
        return rules.remove(key) != null;
    }

    @Override
    public boolean isAllowed(String entityType, String fromState, String toState, String trigger) {
        if (entityType == null || toState == null) {
            return false;
        }
        // P1-4 语义化：Java null 表示"创建迁移"，对应规则表里的字面量 "INITIAL"
        String fromKey = fromState == null ? "INITIAL" : fromState;
        // ① 精确匹配（trigger 拼入 key 消除 (from,to) 同名多 trigger 冲突）
        String trigPart = trigger == null ? "" : "|" + trigger;
        StateTransitionRule exact = rules.get(entityType + ":" + fromKey + "->" + toState + trigPart);
        if (exact != null) {
            return true;
        }
        // ② 通配「*」+ 已知终态收敛
        StateTransitionRule wildcard = rules.get(entityType + ":*->" + toState + trigPart);
        if (wildcard != null && isTerminalState(entityType, toState)) {
            return true;
        }
        return false;
    }

    @Override
    public void preCheck(String entityType, String fromState, String toState, String trigger) {
        if (isAllowed(entityType, fromState, toState, trigger)) {
            return;
        }
        // 跨域守卫：拒绝不在规则表里的跨域联动（防止某服务绕过规则表写跨域副作用）
        throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, String.format(
            "状态机非法迁移：entityType=%s, from=%s, to=%s, trigger=%s（守卫层未登记该迁移）",
            entityType, fromState, toState, trigger));
    }

    @Override
    public void postCommit(String entityType, String fromState, String toState, String trigger,
                           Long operatorId, Long entityId, Date occurredAt) {
        if (!isAllowed(entityType, fromState, toState, trigger)) {
            // postCommit 在事务后，非法迁移不能反向破坏已提交事务；记日志 no-op
            log.warn("postCommit 收到未登记迁移，no-op：entityType={}, from={}, to={}, trigger={}",
                entityType, fromState, toState, trigger);
            return;
        }
        // 找到精确规则或通配规则（trigger 拼入 key）
        // 2026-09-09 C3 缺陷修复：与 isAllowed(L409) 同步做 null→"INITIAL" 归一化——
        // 此前 postCommit 直接拼 fromState，from=null 时 key 变 "...:null->..." 永远 miss，
        // 跨域审计静默丢失（isAllowed 已放行但查不到规则的 ghost 路径）
        String fromKey = fromState == null ? "INITIAL" : fromState;
        String trigPart = trigger == null ? "" : "|" + trigger;
        StateTransitionRule rule = rules.get(entityType + ":" + fromKey + "->" + toState + trigPart);
        if (rule == null) {
            rule = rules.get(entityType + ":*->" + toState + trigPart);
        }
        if (rule == null || !rule.isCrossDomain()) {
            return; // 非跨域：no-op（不发审计、不发通知）
        }
        // 跨域：写 audit_log（独立事务，失败不抛）+ 推 FYI 通知
        try {
            Date ts = occurredAt == null ? new Date() : occurredAt;
            AuditLog audit = AuditLog.builder()
                .operatorId(operatorId)
                .action("CROSS_DOMAIN_TRANSITION")
                .entityType(entityType)
                .entityId(entityId)
                // 审计记录语义态（fromKey）：null→INITIAL 归一化后的值，与规则表 key 一致
                .reason("from=" + fromKey + ",to=" + toState + ",trigger=" + trigger)
                .createTime(ts)
                .build();
            auditLogService.append(audit);
            // FYI 通知：发给操作人自己（避免空指针，跨域触发的接收者由业务方决定；本守卫仅留痕）
            if (operatorId != null) {
                String content = "from=" + fromKey + ",to=" + toState + ",trigger=" + trigger
                    + ",entityId=" + entityId;
                notificationService.publish(operatorId, "CROSS_DOMAIN_TRANSITION",
                    NotificationService.KIND_FYI,
                    entityType, entityId,
                    "跨域状态机迁移：" + entityType + " " + fromKey + "→" + toState,
                    content, null);
            }
        } catch (Exception ex) {
            // postCommit 失败不能反向破坏事务；只记日志
            log.error("postCommit 副作用执行失败：entityType={}, from={}, to={}, trigger={}, err={}",
                entityType, fromState, toState, trigger, ex.getMessage(), ex);
        }
    }

    /**
     * 判定 toState 是否为该 entityType 的已知终态（仅用于「*」通配收敛）
     */
    private boolean isTerminalState(String entityType, String toState) {
        if ("deletion_request".equals(entityType)) {
            return DELETION_TERMINAL.contains(toState);
        }
        if ("bonus_pool".equals(entityType)) {
            return BONUS_POOL_TERMINAL.contains(toState);
        }
        if ("kpi_record".equals(entityType)) {
            return KPI_RECORD_TERMINAL.contains(toState);
        }
        if ("bid_invitation".equals(entityType)) {
            return BID_INVITATION_TERMINAL.contains(toState);
        }
        return false;
    }

    /**
     * 测试辅助：返回当前规则数量（生产代码禁止使用）
     */
    public int ruleCount() {
        return rules.size();
    }

    /**
     * 只读规则表快照（§5-1 Guard 规则表导出 JSON 契约链路；仅返回不可变拷贝，
     * 供导出测试序列化为本仓与前端仓共享的 state-machine-guard-rules.json，不构成写入口）
     */
    public Map<String, StateTransitionRule> rulesSnapshot() {
        return java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(rules));
    }

    /**
     * 兼容 ServiceException（保留旧路径调用方的可能性）
     */
    public void preCheckOrThrowServiceException(String entityType, String fromState, String toState, String trigger) {
        if (!isAllowed(entityType, fromState, toState, trigger)) {
            throw new ServiceException("状态机非法迁移：" + entityType + " " + fromState + "→" + toState
                + " (trigger=" + trigger + ")");
        }
    }
}
