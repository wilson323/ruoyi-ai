package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.LaunchDateChangeRequest;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.workbench.WorkbenchAggregator;
import org.ruoyi.ipd.workbench.WorkbenchPolicy;
import org.ruoyi.ipd.workbench.domain.MyInitiatedTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 我的工作台聚合（ZK-D2+ 后端真实现，对齐原型 GET /api/workflow/tasks 语义）。
 *
 * 真实数据源（无任何 mock）：
 * - 待我处理 / 临期超期 / 已完成：WorkbenchAggregator 聚合器列表统一投递
 *   （P1 方向 B：taskType 按域拆分到 org.ruoyi.ipd.workbench 各实现，summary() 只做调度；
 *   WB-17-1 已实现类与未实现类各有哪些，见 ALL_TASK_TYPES 的 javadoc（本行不复述条数，
 *   条数一律以该常量为准）；
 *   （bonus_lock 已于 2026-10-03 随奖金池退役摘除，17→16）；
 * - 未读通知：notification_events（receiver = 当前人，unread）；
 * - 我的当前推进：当前项目 currentStage 的第一个未完成动作（stageActionMapper 仅剩此职责 + completed 已移入 StageSignAggregator）。
 *
 * 项目范围（原型 access.projectScope 同构）：SUPER_ADMIN=全部；其余=project_members 本人数组。
 * 责任匹配：动作 ownerRole ∈ {MARKET_PM, RD_PM} 时仅该角色可见；其余角色/空值全员可见。
 */
@Service
@RequiredArgsConstructor
public class WorkbenchService implements IWorkbenchService {

    private static final String ST_ACTIVE_PROJECT = "ACTIVE";

    /**
     * spec 页03:165 定义的权威 taskType 词汇表（消费侧过滤见 {@link #tasks}）。
     *
     * <p><b>取值与条数的唯一事实源是本类 {@link #ALL_TASK_TYPES} 常量，本段不复述总条数。</b>
     * 复述数字正是它上一次漂移的原因：此处曾写「权威 17 类全集」，而 2026-10-03 bonus_lock
     * 随奖金池退役后，本常量与前端 WORKBENCH_TASK_TYPE_TEXT 都已不含该类型。
     * 当时说明书页03:165 也仍写「17 类」并列有 bonus_lock，两条线一度不一致；按 G-04
     * （业务规则高于文档惯例，文档惯例高于系统实现）该分歧只能由改说明书收口，不能由代码迁就，
     * 已于 2026-10-03 以 owner 授权的勘误级更新对齐——授权原文、前后对照与回滚方式见
     * docs/ipd-系统说明/log.md「说明书勘误：工作台 taskType 词表 17→16」条目。
     * 今后本段一律只指向常量：要知道几类就数 {@link #ALL_TASK_TYPES}。
     * 契约登记 yaml（docs/ipd-系统说明/workbench-tasktype-契约登记.yaml）的现役键集与本常量
     * 已由 {@code WorkbenchTaskContractDriftTest} 双向断言——只改一边会红。
     *
     * <p><b>已实现</b>（各自 Aggregator 投递）。权威清单是
     * {@code org.ruoyi.ipd.workbench} 下各 {@link WorkbenchAggregator} 实现的 {@code taskType()}
     * 返回值；下列仅为本次修订时的快照，增删聚合器后以此处之外的清单为准：
     * stage_sign / key_gate / key_gate_arbitration / deletion_review / handover /
     * contribution_confirm / strategic_change / closeout / kpi_fill。
     *
     * <p><b>未实现</b>（= {@link #ALL_TASK_TYPES} 去掉上方已实现清单。R218 现查修正 2026-09-25，见
     * docs/ipd-系统说明/验收/R218-WB171-20260925/ 两份调研；纪律不变=不发明新表新机制、
     * owner 拍板前禁止写聚合器——见
     * WB-17-1-tasktype字段级spec填写模板-20260923.md 风险红线）：
     * <ul>
     *   <li>表在码缺（五表 2026-09-25 现查已 apply 存在于 ipd_dev，0 行；
     *     旧登记「DDL 仅草案未 apply」作废）：waiver_review / rd_replacement /
     *     retirement_review / capacity_approval——Java 实体/Mapper/写入 API 全零，
     *     投递 pending_expr 与链路语义待 owner 拍板（缺表4类-14问澄清）</li>
     *   <li>锚/态缺：receipt_review——receipt_ledgers 实为销售回款台账，
     *     无 status/reviewer 锚列，「收据待审」单据态不存在（扩列 vs 新表待拍板）；
     *     bonus_lock——已于 2026-10-03 随奖金池退役从词汇表移除（原「锚/态缺」条目作废）</li>
     *   <li>真缺表：change_implementation / change_verify——change_implementations /
     *     change_implementation_evidence 真库无表（DDL 草稿
     *     20260925-wb171-draft-missing-tables.sql 未 apply，待 owner 拍板 + DBA 窗口）</li>
     * </ul>
     *
     * <p>消费侧过滤（本卡后端真空缺口，S0 切片已交付）：
     * {@code GET /api/v1/workbench/tasks?bucket=&type=&limit=&projectId=}（见 {@link #tasks}），
     * 各 taskType 的 tab 渲染不再依赖后端补数据；旧 /summary 契约保留兼容。
     *
     * <p>已知死路（非本卡引入，契约登记 yaml A1-A4 在案）：上列已实现类中的
     * key_gate / contribution_confirm / strategic_change 三类，其投递锚字段在写入路径下恒 NULL，
     * 真活卡恒空；修复属写入侧车道（GateCreationService / incentives / propose 路径）。
     */
    /** tasks() 支持的 bucket 值域（completed/initiated 有诚实理由 fail-closed，见 tasks() javadoc）。 */
    private static final List<String> OPEN_BUCKETS = List.of("pending", "overdue");
    /** tasks() limit 契约：默认 50，上限 200（防拉全表拖垮工作台）。 */
    private static final int TASKS_DEFAULT_LIMIT = 50;
    private static final int TASKS_MAX_LIMIT = 200;
    // 2026-10-03：bonus_lock 已随「奖金池」域退役移除（17→16，与前端 WORKBENCH_TASK_TYPE_TEXT 对齐；
    // 该类型本就无生产锚态，见上方 javadoc 历史说明）。receipt_review 虽属已退役的回款台账域，
    // 但它是「spec 页03 权威 17 类」词汇表成员，说明书口径修订属 owner 决策，暂保留。
    private static final List<String> ALL_TASK_TYPES = List.of(
        "capacity_approval", "change_implementation", "change_verify",
        "closeout", "contribution_confirm", "deletion_review", "handover",
        "key_gate", "key_gate_arbitration", "kpi_fill", "rd_replacement",
        "receipt_review", "retirement_review", "stage_sign", "strategic_change", "waiver_review");

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final StageActionMapper stageActionMapper;
    private final NotificationService notificationService;
    /** 按域聚合器列表（Spring 注入全部 WorkbenchAggregator 实现，@Order 决定 stage 先 deletion 后）。 */
    private final List<WorkbenchAggregator> aggregators;
    /**
     * 「我发起的」计数的数据源 mapper（P1-4）：
     * <ul>
     *   <li>{@link DeletionRequestMapper}：deletion_requests.create_by = 当前人</li>
     *   <li>{@link LaunchDateChangeRequestMapper}：launch_date_change_requests.create_by = 当前人</li>
     * </ul>
     * 三类业务单据由当前人发起的总数（del_flag='0' 软过滤）= stats.myInitiated。
     * 注意：{@code requester_id / proposer_id} 是领域字段；{@code create_by} 来自 BaseEntity 自动填充，
     * 才是"由我发起的"权威口径（领域字段可能与创建人分离）。
     */
    private final DeletionRequestMapper deletionRequestMapper;
    private final LaunchDateChangeRequestMapper launchDateChangeRequestMapper;

    /**
     * 「待我审批」所需的两项可选协作者（D2 修复）——一律 setter 注入，不加入 {@link #WorkbenchService} 的
     * 构造器：本类被 4 个存量测试类以 7 参构造直接 new，改构造签名会连带打断它们。
     * <p>两种协作者都允许缺席（{@code required = false}，与 DeletionReviewAggregator /
     * DeletionRequestServiceImpl 的可选协作者同型）；缺席时相关分支<b>不投递</b>而非放宽——
     * 宁可不显示，也不产出「审批人是你」而实际无权办理的假卡。
     */
    private PersonMapper personMapper;

    /** 删除审批的目标归属判定，与写路径 {@code DeletionRequestServiceImpl#leaderDecision} 共用同一实现。 */
    private DeletionRequestServiceImpl deletionRequestService;

    @Autowired(required = false)
    public void setPersonMapper(PersonMapper personMapper) {
        this.personMapper = personMapper;
    }

    @Autowired(required = false)
    public void setDeletionRequestService(DeletionRequestServiceImpl deletionRequestService) {
        this.deletionRequestService = deletionRequestService;
    }

    /**
     * 工作台总览。
     *
     * @param actor     当前登录人（SEC-API-01：仅从会话推导）
     * @param projectId 指定当前项目（顶栏切换）；空 = 第一个进行中项目
     * @return stats + 任务平铺列表（前端按项目分组）+ 当前推进 + 删除待办数
     */
    public Map<String, Object> summary(IpdActor actor, Long projectId) {
        return summaryInScope(actor, projectId, visibleProjects(actor), null);
    }

    /** AI 副驾使用已从 Person 重新确认的租户；异步 SSE 不依赖请求线程上下文。 */
    public Map<String, Object> summary(IpdActor actor, Long projectId, String trustedTenantId) {
        if (trustedTenantId == null || trustedTenantId.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN);
        }
        return summaryInScope(actor, projectId, visibleProjects(actor, trustedTenantId), trustedTenantId);
    }

    private Map<String, Object> summaryInScope(IpdActor actor, Long projectId, List<Project> scope,
                                               String trustedTenantId) {
        Map<Long, Project> byId = new LinkedHashMap<>();
        scope.forEach(p -> byId.put(p.getId(), p));

        Date now = new Date();
        // 统一调度：每类 taskType 由各自 aggregator 投递卡（P1 方向 B）
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (WorkbenchAggregator aggregator : aggregators) {
            tasks.addAll(trustedTenantId == null
                ? aggregator.collect(actor, byId, now)
                : aggregator.collect(actor, byId, now, trustedTenantId));
        }
        int completed = aggregators.stream()
            .mapToInt(a -> a.completedCount(actor, byId))
            .sum();

        // overdue 统一按任务卡 dueDate 口径（所有 taskType 同规则）
        long overdue = tasks.stream()
            .filter(t -> t.get("dueDate") instanceof Date d && d.before(now))
            .count();

        Map<String, Object> stats = new LinkedHashMap<>();
        // pending 按主任务总数（B4 拍板③）：全部 taskType 计入
        stats.put("pending", tasks.size());
        // 计数一律 int 装箱：全局 Long→String 序列化会把 Long 计数变字符串，破坏前端 number 契约
        stats.put("overdue", (int) overdue);
        stats.put("unread", (int) notificationService.unreadCount(actor.id()));
        stats.put("completed", (int) completed);
        // 我发起的（P1-4）：deletion_requests + launch_date_change_requests
        // 两张业务单据表 create_by = 当前人 的总数（跨聚合「我发起的」徽标）
        // （coefficient_change_requests 已于 2026-10-03 随「业绩窗口/系数变更」功能块退役，见 countMyInitiated）
        stats.put("myInitiated", countMyInitiated(actor.id()));
        // 按类型计数（设计 §5）：ALL_TASK_TYPES 的每个 key 都预置 0（无数据类也返回），供前端按类型过滤/展示
        Map<String, Integer> pendingType = new LinkedHashMap<>();
        for (String taskType : ALL_TASK_TYPES) {
            pendingType.put(taskType, 0);
        }
        for (Map<String, Object> task : tasks) {
            pendingType.merge(String.valueOf(task.get("taskType")), 1, Integer::sum);
        }
        stats.put("pendingType", pendingType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stats", stats);
        result.put("tasks", tasks);
        // deletionPending 保留独立字段（前端 WorkbenchSummary 兼容）：按 taskType 从调度结果统计
        result.put("deletionPending", (int) tasks.stream()
            .filter(t -> "deletion_review".equals(t.get("taskType")))
            .count());
        // 副驾指定项目若不在进行中可见范围，不能悄悄回退到另一个项目的推进信息。
        result.put("currentAdvance", trustedTenantId != null && projectId != null && !byId.containsKey(projectId)
            ? null : currentAdvance(actor, projectId, byId));
        return result;
    }

    /**
     * 任务队列过滤视图（WB-17-1 S0 切片；spec 页03 §4「GET /api/workflow/tasks?bucket=&type=&limit=」）。
     *
     * <p>与 {@link #summary} 共用同一聚合器调度链（真数据源零 mock），在其上过滤：
     * <ul>
     *   <li>type：ALL_TASK_TYPES 权威枚举之一（非法值 400 fail-closed）；空 = 不过滤</li>
     *   <li>projectId：卡面 projectId 精确匹配；空 = 全部可见项目</li>
     *   <li>bucket：pending=全部在途卡（缺省）；overdue=dueDate 早于当前时刻（与 summary stats.overdue 同规则）</li>
     *   <li>limit：1~200，缺省 50；total 返回截断前命中数</li>
     * </ul>
     *
     * <p>fail-closed 边界（不发明数据源）：bucket=completed 聚合器契约仅有
     * {@link WorkbenchAggregator#completedCount} 计数、无卡级完成历史；bucket=initiated
     * 数据源契约属 {@code /workbench/my-initiated} 既有端点——两态均 400 并指向正确契约，
     * 禁止在此返回空列表伪装「无数据即正常」。
     *
     * <p>排序：priority urgent&gt;high&gt;normal，同优先级 dueDate 升序（null 恒最后），
     * 稳定排序保持聚合器 @Order 调度序为最终 tiebreaker（对齐原型 ORDER BY priority,due_at）。
     */
    @Override
    public Map<String, Object> tasks(IpdActor actor, Long projectId, String bucket, String type, Integer limit) {
        String b = (bucket == null || bucket.isBlank()) ? "pending" : bucket.trim();
        if ("completed".equals(b) || "initiated".equals(b)) {
            throw new IpdBusinessException("bucket=" + b + " 无卡级数据源契约：completed 仅有计数（/summary stats.completed），"
                + "initiated 请走 /workbench/my-initiated；本端点支持 pending|overdue");
        }
        if (!OPEN_BUCKETS.contains(b)) {
            throw new IpdBusinessException("bucket 非法: " + b + "（值域 pending|overdue）");
        }
        String t = (type == null || type.isBlank()) ? null : type.trim();
        if (t != null && !ALL_TASK_TYPES.contains(t)) {
            throw new IpdBusinessException("type 非法: " + t + "，权威取值见 spec 页03:165（与 stats.pendingType 键集同序同值）");
        }
        int lim = limit == null ? TASKS_DEFAULT_LIMIT : limit;
        if (lim < 1 || lim > TASKS_MAX_LIMIT) {
            throw new IpdBusinessException("limit 须在 1~" + TASKS_MAX_LIMIT + "，当前: " + limit);
        }

        List<Project> scope = visibleProjects(actor);
        Map<Long, Project> byId = new LinkedHashMap<>();
        scope.forEach(p -> byId.put(p.getId(), p));
        Date now = new Date();
        List<Map<String, Object>> collected = new ArrayList<>();
        for (WorkbenchAggregator aggregator : aggregators) {
            collected.addAll(aggregator.collect(actor, byId, now));
        }

        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> task : collected) {
            if (t != null && !t.equals(task.get("taskType"))) {
                continue;
            }
            if (projectId != null && !projectId.equals(task.get("projectId"))) {
                continue;
            }
            if ("overdue".equals(b)
                && !(task.get("dueDate") instanceof Date due && due.before(now))) {
                continue;
            }
            filtered.add(task);
        }
        filtered.sort(Comparator
            .comparingInt((Map<String, Object> m) -> priorityRank(m.get("priority")))
            .thenComparing(m -> m.get("dueDate") instanceof Date d ? d : null,
                Comparator.nullsLast(Comparator.naturalOrder())));

        List<Map<String, Object>> page = filtered.size() > lim ? new ArrayList<>(filtered.subList(0, lim)) : filtered;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("bucket", b);
        result.put("type", t);
        result.put("projectId", projectId);
        result.put("limit", lim);
        result.put("total", filtered.size());
        result.put("returned", page.size());
        result.put("tasks", page);
        return result;
    }

    /** 优先级排序锚：与聚合器卡 priority 值域对齐（urgent/high/normal，未知值垫底 3）。 */
    private static int priorityRank(Object priority) {
        if ("urgent".equals(priority)) { return 0; }
        if ("high".equals(priority)) { return 1; }
        if ("normal".equals(priority)) { return 2; }
        return 3;
    }
    /**
     * 「我发起的」聚合（P1-4）：业务单据表（deletion_requests / launch_date_change_requests）
     * 按 create_by 计数。<b>本注释不写表的张数</b>——读法以方法体里实际用到的 mapper 为准。
     * <p>这些表均继承 BaseEntity，{@code create_by} 由 MyBatis-Plus MetaObjectHandler 在插入时填充当前人 ID，
     * 与 requester_id / proposer_id 等领域字段可能分离；以 create_by 为权威「由我发起」口径。
     *
     * @param actorId 当前登录人 ID（来自 IpdActor.id()）
     * @return 上述各表 count 之和；actorId 为空时返回 0（防御性，避免 SQL 拼接 NULL）
     */
    private int countMyInitiated(Long actorId) {
        if (actorId == null) {
            return 0;
        }
        long deletion = deletionRequestMapper.selectCount(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getCreateBy, actorId));
        long launchDate = launchDateChangeRequestMapper.selectCount(new LambdaQueryWrapper<LaunchDateChangeRequest>()
            .eq(LaunchDateChangeRequest::getCreateBy, actorId));
        // 防御性截断到 int 范围（实际业务不可能超 int 上限）
        long total = deletion + launchDate;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /** 项目可见范围：超管全部，其余按成员关系。 */
    private List<Project> visibleProjects(IpdActor actor) {
        return visibleProjects(actor, null);
    }

    private List<Project> visibleProjects(IpdActor actor, String trustedTenantId) {
        if ("SUPER_ADMIN".equals(actor.role())) {
            LambdaQueryWrapper<Project> query = new LambdaQueryWrapper<Project>()
                .eq(Project::getStatus, ST_ACTIVE_PROJECT)
                .orderByAsc(Project::getId);
            if (trustedTenantId != null) {
                query.eq(Project::getTenantId, trustedTenantId);
            }
            return projectMapper.selectList(query);
        }
        LambdaQueryWrapper<ProjectMember> membershipQuery = new LambdaQueryWrapper<ProjectMember>()
            .eq(ProjectMember::getPersonId, actor.id());
        if (trustedTenantId != null) {
            membershipQuery.isNull(ProjectMember::getExitDate);
        }
        List<ProjectMember> memberships = projectMemberMapper.selectList(membershipQuery);
        if (memberships.isEmpty()) {
            return List.of();
        }
        List<Long> ids = memberships.stream().map(ProjectMember::getProjectId).distinct().toList();
        LambdaQueryWrapper<Project> query = new LambdaQueryWrapper<Project>()
            .in(Project::getId, ids)
            .eq(Project::getStatus, ST_ACTIVE_PROJECT)
            .orderByAsc(Project::getId);
        if (trustedTenantId != null) {
            query.eq(Project::getTenantId, trustedTenantId);
        }
        return projectMapper.selectList(query);
    }

    /** 我的当前推进：指定项目（或第一个可见项目）当前阶段的第一个未完成动作。 */
    private Map<String, Object> currentAdvance(IpdActor actor, Long projectId, Map<Long, Project> byId) {
        Project target = null;
        if (projectId != null && byId.containsKey(projectId)) {
            target = byId.get(projectId);
        } else {
            target = byId.values().stream().findFirst().orElse(null);
        }
        if (target == null) {
            return null;
        }
        List<StageAction> actions = stageActionMapper.selectList(
            new LambdaQueryWrapper<StageAction>()
                .eq(StageAction::getProjectId, target.getId())
                .orderByAsc(StageAction::getId));
        StageAction next = actions.stream()
            .filter(a -> WorkbenchPolicy.OPEN_STATUSES.contains(a.getStatus())
                && WorkbenchPolicy.isMine(a.getOwnerRole(), actor.role()))
            .min(Comparator.comparing(StageAction::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);
        Map<String, Object> advance = new LinkedHashMap<>();
        advance.put("projectId", target.getId());
        advance.put("projectCode", target.getCode());
        advance.put("projectName", target.getName());
        advance.put("currentStage", target.getCurrentStage());
        advance.put("actionId", next != null ? next.getId() : null);
        advance.put("actionName", next != null ? next.getActionName() : null);
        advance.put("actionStatus", next != null ? next.getStatus() : null);
        advance.put("deepLink", next != null
            ? "/ipd/projects/" + target.getId() + "/actions/" + next.getId()
            : "/ipd/projects/" + target.getId() + "/flow");
        return advance;
    }

    /** 通知收件箱透传（工作台右侧与顶栏红点共用）。 */
    public List<NotificationEvent> inbox(IpdActor actor, boolean unreadOnly) {
        return notificationService.inbox(actor.id(), unreadOnly);
    }

    /* ========================================================================
     *  R27 P0-6：Workbench 路径 2 函数补全
     *  - myInitiated / myPendingApprovals：聚合业务单据表 deletion_requests + launch_date_change_requests
     * ======================================================================== */

    /** 任务类型常量（聚合视图卡 taskType 字段）。 */
    public static final String TASK_TYPE_DELETION_REQUEST = "DELETION";
    public static final String TASK_TYPE_LAUNCH_DATE_CHANGE = "LAUNCH_DATE";
    /**
     * 阶段动作视图卡的任务类型（短形式值）。
     *
     * <p><b>后端目前无任何生产者发出此 taskType</b>：全后端的 taskType 赋值点只有本类的
     * {@code TASK_TYPE_DELETION_REQUEST} / {@code TASK_TYPE_LAUNCH_DATE_CHANGE}，
     * 以及 {@code org.ruoyi.ipd.workbench} 下各 {@code WorkbenchAggregator} 的自身 taskType
     * （stage_sign / key_gate / … 无一为 STAGE_ACTION）；该值全仓仅此处一处声明，无使用点
     * （2026-10-03 实测，已排除 .codex/ 与 .harness/ 归档）。
     * 本常量系 2026-09-22 随「阶段动作」预留、至今未接线（{@code git log -S} 仅命中引入它的那一笔提交）。
     * 是否接线属产品决策。前端 {@code MY_INITIATED_SOURCE_TEXT} 保留了同名标签位，收不到值时不影响渲染。
     */
    public static final String TASK_TYPE_STAGE_ACTION = "STAGE_ACTION";
    /** 来源表名常量。 */
    public static final String TABLE_DELETION_REQUESTS = "deletion_requests";
    public static final String TABLE_LAUNCH_DATE_CHANGE_REQUESTS = "launch_date_change_requests";
    /** 审批角色常量（取值对齐 Person.person_type 词表，与 DeletionReviewAggregator 同源）。 */
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";
    private static final String ROLE_GROUP_LEADER = "GROUP_LEADER";

    /**
     * 我发起的（R27 P0-6）：业务单据表（删除 / 上市日期）按 create_by=personId 聚合，<b>一单卡</b>。
     * <p>原实现按 {@code selectCount} 展开成等量「占位卡」（假 id / sourceId=null / status=COUNT /
     * 标题里带内部编号），只要该人名下有单据就会看到占位卡、内部编号随之泄漏到界面
     * （本注释不引用真库行数——那需要查库才能断言，此处只陈述 count>0 必出卡这一逻辑事实）。
     * 现改为 {@code selectList}
     * 逐行投影真单据，卡面 id/sourceId/status/title 全部取自业务行。
     * <p>原为「删除 / 系数变更 / 上市日期」三表；系数变更单（coefficient_change_requests）
     *     已随算钱层下线，本方法现只聚合<b>删除 + 上市日期</b>两表。
     * <p>personId=null 返空列表（防御性，避免 SQL 拼接 NULL）。
     */
    @Override
    public List<MyInitiatedTask> myInitiated(Long personId) {
        if (personId == null) {
            return java.util.Collections.emptyList();
        }
        List<MyInitiatedTask> result = new ArrayList<>();

        List<DeletionRequest> deletionRows = deletionRequestMapper.selectList(
            new LambdaQueryWrapper<DeletionRequest>().eq(DeletionRequest::getCreateBy, personId));
        for (DeletionRequest row : nullSafe(deletionRows)) {
            result.add(MyInitiatedTask.builder()
                .id(row.getId())
                .taskType(TASK_TYPE_DELETION_REQUEST)
                .sourceId(row.getId())
                .sourceTable(TABLE_DELETION_REQUESTS)
                .title(deletionTitle(row))
                .status(row.getStatus())
                .initiatorId(row.getCreateBy())
                .approverId(null)
                .createdAt(row.getCreateTime())
                .build());
        }

        List<LaunchDateChangeRequest> launchDateRows = launchDateChangeRequestMapper.selectList(
            new LambdaQueryWrapper<LaunchDateChangeRequest>().eq(LaunchDateChangeRequest::getCreateBy, personId));
        for (LaunchDateChangeRequest row : nullSafe(launchDateRows)) {
            result.add(MyInitiatedTask.builder()
                .id(row.getId())
                .taskType(TASK_TYPE_LAUNCH_DATE_CHANGE)
                .sourceId(row.getId())
                .sourceTable(TABLE_LAUNCH_DATE_CHANGE_REQUESTS)
                .title(launchDateTitle(row))
                .status(row.getStatus())
                .initiatorId(row.getCreateBy())
                .approverId(null)
                .createdAt(row.getCreateTime())
                .build());
        }
        return result;
    }

    /**
     * 待我审批的（R27 P0-6）：仅返回<b>本人确实有权办理</b>的在途单据。
     *
     * <p><b>归属口径（与写路径逐条对齐，不另立一套）</b>：
     * <ul>
     *   <li>deletion_requests：先按 {@code personType} 分角色——SUPER_ADMIN → {@code ADMIN_REVIEW}；
     *       GROUP_LEADER → {@code LEADER_REVIEW}；其余内部角色 → 不投递。组长还须通过
     *       {@link DeletionRequestServiceImpl#isTargetInLeaderGroup}（与
     *       {@code DeletionReviewAggregator}、{@code leaderDecision} 同一判定）确认删除目标属于本人所辖组。
     *       注意 {@code leader_id} 列<b>不是</b>初审阶段的归属依据——它只在组长作出决定后才落值，
     *       状态为 LEADER_REVIEW 时恒为 NULL，故不能拿它当查询条件。</li>
     *   <li>launch_date_change_requests：{@code status=PENDING_SECOND AND confirmer_id=personId}。
     *       第二签人在 propose 时刻即预落（必传、fail-closed，见
     *       {@code LaunchDateChangeService#propose}），{@code secondDecision} 亦以该列为准。</li>
     * </ul>
     *
     * <p>修复前两处分支都只过滤状态、不看归属（删除侧 {@code status IN (LEADER_REVIEW, ADMIN_REVIEW)}、
     * 上市日期侧 {@code status = PENDING_SECOND}），随后把 {@code approverId} 硬写成查询人：
     * 任何能调本端点的内部用户都会看到其他组的在途单据，且系统声称「审批人是你」。
     * <b>此处不要读成「同租户内」</b>——这两张表登记在 {@code tenant.excludes}（IPD 单企业私有部署，
     * 表结构带 {@code tenant_id} 默认 000000、无多租户语义），查询侧本就没有租户级约束，
     * 故修复前实为「不限本组、全量可见」；唯一的收窄就是本次新增的主组判定，
     * 不存在第二层隔离可依赖（写这句话时若写「同租户内」，会让人以为有一道实际不存在的约束）。
     *
     * <p>personId=null 返空列表（防御性）。查不到该 Person 或归属判定能力未装配时同样不投递（fail-closed）。
     */
    @Override
    public List<MyInitiatedTask> myPendingApprovals(Long personId) {
        if (personId == null) {
            return java.util.Collections.emptyList();
        }
        List<MyInitiatedTask> result = new ArrayList<>();

        // 1) deletion_requests：按角色分流后再按目标组归属过滤
        IpdActor actor = resolveActor(personId);
        String reviewStatus = null;
        boolean adminSide = false;
        if (actor != null) {
            if (ROLE_SUPER_ADMIN.equals(actor.role())) {
                reviewStatus = DeletionRequestServiceImpl.ST_ADMIN_REVIEW;
                adminSide = true;
            } else if (ROLE_GROUP_LEADER.equals(actor.role())) {
                reviewStatus = DeletionRequestServiceImpl.ST_LEADER_REVIEW;
            }
        }
        if (reviewStatus != null) {
            List<DeletionRequest> deletionRows = deletionRequestMapper.selectList(
                new LambdaQueryWrapper<DeletionRequest>()
                    .eq(DeletionRequest::getStatus, reviewStatus));
            for (DeletionRequest row : nullSafe(deletionRows)) {
                // 组长还须是目标所属组组长（与 leaderDecision 同一判定，小组长不可越组审批）；
                // 判定能力未装配时同样不投递——宁可不显示，也不产出「审批人是你」而实际办不了的假卡。
                if (!adminSide && (deletionRequestService == null
                    || !deletionRequestService.isTargetInLeaderGroup(actor, row))) {
                    continue;
                }
                result.add(MyInitiatedTask.builder()
                    .id(row.getId())
                    .taskType(TASK_TYPE_DELETION_REQUEST)
                    .sourceId(row.getId())
                    .sourceTable(TABLE_DELETION_REQUESTS)
                    .title(deletionTitle(row))
                    .status(row.getStatus())
                    .initiatorId(row.getCreateBy())
                    // 上面的角色分流 + 归属判定已证明本条待审批人就是 actor
                    .approverId(actor.id())
                    .createdAt(row.getCreateTime())
                    .build());
            }
        }

        // 2) launch_date_change_requests：第二签人以 propose 时预落的 confirmer_id 为准
        List<LaunchDateChangeRequest> launchDateRows = launchDateChangeRequestMapper.selectList(
            new LambdaQueryWrapper<LaunchDateChangeRequest>()
                .eq(LaunchDateChangeRequest::getStatus, LaunchDateChangeRequest.ST_PENDING_SECOND)
                .eq(LaunchDateChangeRequest::getConfirmerId, personId));
        for (LaunchDateChangeRequest row : nullSafe(launchDateRows)) {
            result.add(MyInitiatedTask.builder()
                .id(row.getId())
                .taskType(TASK_TYPE_LAUNCH_DATE_CHANGE)
                .sourceId(row.getId())
                .sourceTable(TABLE_LAUNCH_DATE_CHANGE_REQUESTS)
                .title(launchDateTitle(row))
                .status(row.getStatus())
                .initiatorId(row.getCreateBy())
                // 查询条件已限定 confirmer_id=personId，此处回填行上真值（非硬写查询人）
                .approverId(row.getConfirmerId())
                .createdAt(row.getCreateTime())
                .build());
        }

        return result;
    }

    /**
     * personId → 最小身份投影。只与 {@code IpdPermission#requireInternal} 同「取哪三个字段」
     * （id / personType / groupId），<b>不复刻它的门禁</b>：登录态、auth scope 与内部角色白名单
     * 由上游 Controller（{@code ipdPermission.requireInternal()}）负责，本方法不重复鉴权。
     */
    private IpdActor resolveActor(Long personId) {
        if (personMapper == null) {
            return null;
        }
        Person person = personMapper.selectById(personId);
        if (person == null || person.getId() == null) {
            return null;
        }
        return new IpdActor(person.getId(), person.getName(), person.getPersonType(), person.getGroupId());
    }

    /** 删除单据展示标题：类型#业务 id 优先，缺失时退化为单据主键。 */
    private static String deletionTitle(DeletionRequest row) {
        return row.getEntityType() != null
            ? row.getEntityType() + "#" + row.getEntityId()
            : "删除申请 #" + row.getId();
    }

    /** 上市日期变更展示标题：以申请人填写的理由为标题，缺失时退化为单据主键。 */
    private static String launchDateTitle(LaunchDateChangeRequest row) {
        return row.getReason() != null
            ? row.getReason()
            : "上市日期变更 #" + row.getId();
    }

    private static <T> List<T> nullSafe(List<T> rows) {
        return rows == null ? java.util.Collections.emptyList() : rows;
    }
}
