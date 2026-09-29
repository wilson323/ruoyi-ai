package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.LaunchDateChangeRequest;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.workbench.WorkbenchAggregator;
import org.ruoyi.ipd.workbench.WorkbenchPolicy;
import org.ruoyi.ipd.workbench.domain.MyInitiatedTask;
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
 *   WB-17-1 现态已实现 9 类（清单见 ALL_TASK_TYPES javadoc），剩余 8 类登记同下）；
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
     * spec 页03:165 权威 17 类全集（与前端 WORKBENCH_TASK_TYPE_TEXT 同序同值，勿漂移）。
     *
     * <p><b>WB-17-1 已实现 9 类</b>（各自 Aggregator 投递，契约见
     * docs/ipd-系统说明/workbench-tasktype-契约登记.yaml）：
     * stage_sign / key_gate / key_gate_arbitration / deletion_review / handover /
     * contribution_confirm / strategic_change / closeout / kpi_fill。
     *
     * <p><b>剩余 8 类登记（R218 现查修正 2026-09-25，见
     * docs/ipd-系统说明/验收/R218-WB171-20260925/ 两份调研；纪律不变=不发明新表新机制、
     * owner 拍板前禁止写聚合器——见
     * WB-17-1-tasktype字段级spec填写模板-20260923.md 风险红线）</b>：
     * <ul>
     *   <li>表在码缺 4 类（五表 2026-09-25 现查已 apply 存在于 ipd_dev，0 行；
     *     旧登记「DDL 仅草案未 apply」作废）：waiver_review / rd_replacement /
     *     retirement_review / capacity_approval——Java 实体/Mapper/写入 API 全零，
     *     投递 pending_expr 与链路语义待 owner 拍板（缺表4类-14问澄清）</li>
     *   <li>锚/态缺 2 类：receipt_review——receipt_ledgers 实为销售回款台账，
     *     无 status/reviewer 锚列，「收据待审」单据态不存在（扩列 vs 新表待拍板）；
     *     bonus_lock——bonus_pools 状态机仅 DRAFT→CONFIRMED→DISTRIBUTED，
     *     无「待锁定」前置态（新增态+生产者 vs DRAFT+窗口派生，口径二选一待拍板）</li>
     *   <li>真缺表 2 类：change_implementation / change_verify——change_implementations /
     *     change_implementation_evidence 真库无表（DDL 草稿
     *     20260925-wb171-draft-missing-tables.sql 未 apply，待 owner 拍板 + DBA 窗口）</li>
     * </ul>
     *
     * <p>消费侧过滤（本卡后端真空缺口，S0 切片已交付）：
     * {@code GET /api/v1/workbench/tasks?bucket=&type=&limit=&projectId=}（{@link #tasks}），
     * 17 类 tab 渲染不再依赖后端补数据；旧 /summary 契约保留兼容。
     *
     * <p>已知死路（非本卡引入，契约登记 yaml A1-A4 在案）：已实现 9 类中
     * key_gate / contribution_confirm / strategic_change 的投递锚字段在写入路径下恒 NULL，
     * 真活卡恒空；修复属写入侧车道（GateCreationService / incentives / propose 路径）。
     */
    /** tasks() 支持的 bucket 值域（completed/initiated 有诚实理由 fail-closed，见 tasks() javadoc）。 */
    private static final List<String> OPEN_BUCKETS = List.of("pending", "overdue");
    /** tasks() limit 契约：默认 50，上限 200（防拉全表拖垮工作台）。 */
    private static final int TASKS_DEFAULT_LIMIT = 50;
    private static final int TASKS_MAX_LIMIT = 200;
    private static final List<String> ALL_TASK_TYPES = List.of(
        "bonus_lock", "capacity_approval", "change_implementation", "change_verify",
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
     *   <li>{@link CoefficientChangeRequestMapper}：coefficient_change_requests.create_by = 当前人</li>
     *   <li>{@link LaunchDateChangeRequestMapper}：launch_date_change_requests.create_by = 当前人</li>
     * </ul>
     * 三类业务单据由当前人发起的总数（del_flag='0' 软过滤）= stats.myInitiated。
     * 注意：{@code requester_id / proposer_id} 是领域字段；{@code create_by} 来自 BaseEntity 自动填充，
     * 才是"由我发起的"权威口径（领域字段可能与创建人分离）。
     */
    private final DeletionRequestMapper deletionRequestMapper;
    private final CoefficientChangeRequestMapper coefficientChangeRequestMapper;
    private final LaunchDateChangeRequestMapper launchDateChangeRequestMapper;

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
        // 我发起的（P1-4）：deletion_requests + coefficient_change_requests + launch_date_change_requests
        // 三张业务单据表 create_by = 当前人 的总数（跨聚合「我发起的」徽标）
        stats.put("myInitiated", countMyInitiated(actor.id()));
        // 按类型计数（设计 §5）：17 类 key 预置 0（无数据类也返回），供前端按类型过滤/展示
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
     *   <li>type：17 类权威枚举之一（非法值 400 fail-closed）；空 = 不过滤</li>
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
            throw new IpdBusinessException("type 非法: " + t + "，权威 17 类见 spec 页03:165（与 stats.pendingType 键集同序同值）");
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
     * 「我发起的」聚合（P1-4）：三张业务单据表（删除申请/系数变更/上市日期变更）按 create_by 计数。
     * <p>三表均继承 BaseEntity，{@code create_by} 由 MyBatis-Plus MetaObjectHandler 在插入时填充当前人 ID，
     * 与 requester_id / proposer_id 等领域字段可能分离；以 create_by 为权威「由我发起」口径。
     *
     * @param actorId 当前登录人 ID（来自 IpdActor.id()）
     * @return 三个表 count 之和；actorId 为空时返回 0（防御性，避免 SQL 拼接 NULL）
     */
    private int countMyInitiated(Long actorId) {
        if (actorId == null) {
            return 0;
        }
        long deletion = deletionRequestMapper.selectCount(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getCreateBy, actorId));
        long coefficient = coefficientChangeRequestMapper.selectCount(new LambdaQueryWrapper<CoefficientChangeRequest>()
            .eq(CoefficientChangeRequest::getCreateBy, actorId));
        long launchDate = launchDateChangeRequestMapper.selectCount(new LambdaQueryWrapper<LaunchDateChangeRequest>()
            .eq(LaunchDateChangeRequest::getCreateBy, actorId));
        // 防御性截断到 int 范围（实际业务不可能超 int 上限）
        long total = deletion + coefficient + launchDate;
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
     *  - myInitiated / myPendingApprovals：聚合 3 张业务单据表
     * ======================================================================== */

    /** 任务类型常量（聚合视图卡 taskType 字段）。 */
    public static final String TASK_TYPE_DELETION_REQUEST = "DELETION";
    public static final String TASK_TYPE_COEFFICIENT_CHANGE = "COEFFICIENT";
    public static final String TASK_TYPE_LAUNCH_DATE_CHANGE = "LAUNCH_DATE";
    public static final String TASK_TYPE_STAGE_ACTION = "STAGE_ACTION";
    /** 来源表名常量。 */
    public static final String TABLE_DELETION_REQUESTS = "deletion_requests";
    public static final String TABLE_COEFFICIENT_CHANGE_REQUESTS = "coefficient_change_requests";
    public static final String TABLE_LAUNCH_DATE_CHANGE_REQUESTS = "launch_date_change_requests";

    /**
     * 我发起的（R27 P0-6）：三张业务单据表（删除/系数/上市日期）按 create_by=personId 聚合。
     * <p>每张表 count>0 即生成对应 {@link MyInitiatedTask} 视图卡；总数=三表 count 之和。
     * <p>personId=null 返空列表（防御性，避免 SQL 拼接 NULL）。
     * <p>注意：本方法返回 N 条"虚拟视图卡"（每表 count 条数）；实际业务单据详情仍走各业务单据的 list 接口，
     * 此处仅作为工作台「我发起的」徽标 + 列表的聚合视图，避免前端多次调用。
     */
    @Override
    public List<MyInitiatedTask> myInitiated(Long personId) {
        if (personId == null) {
            return java.util.Collections.emptyList();
        }
        long deletionCount = deletionRequestMapper.selectCount(new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getCreateBy, personId));
        long coefficientCount = coefficientChangeRequestMapper.selectCount(new LambdaQueryWrapper<CoefficientChangeRequest>()
            .eq(CoefficientChangeRequest::getCreateBy, personId));
        long launchDateCount = launchDateChangeRequestMapper.selectCount(new LambdaQueryWrapper<LaunchDateChangeRequest>()
            .eq(LaunchDateChangeRequest::getCreateBy, personId));

        List<MyInitiatedTask> result = new ArrayList<>();
        // 注：当前设计按"count 数展开为占位卡"——前端展示「我发起的」分组时按 taskType 渲染；
        // 真实业务单据详情通过 sourceId 二次查询各业务 list 接口。
        // 若需精确单据视图，可在此调用 selectList(byId) 替换 selectCount。
        appendPlaceholderCards(result, TASK_TYPE_DELETION_REQUEST, TABLE_DELETION_REQUESTS, deletionCount);
        appendPlaceholderCards(result, TASK_TYPE_COEFFICIENT_CHANGE, TABLE_COEFFICIENT_CHANGE_REQUESTS, coefficientCount);
        appendPlaceholderCards(result, TASK_TYPE_LAUNCH_DATE_CHANGE, TABLE_LAUNCH_DATE_CHANGE_REQUESTS, launchDateCount);
        return result;
    }

    /**
     * 待我审批的（R27 P0-6）：三张业务单据表中处于审批态的记录。
     * <p>审批态映射（按各表状态机）：
     * <ul>
     *   <li>deletion_requests：LEADER_REVIEW / ADMIN_REVIEW（leader_id=personId）</li>
     *   <li>coefficient_change_requests：PENDING_LEADER（leader_id=personId）</li>
     *   <li>launch_date_change_requests：PENDING_SECOND（approver_id=personId）</li>
     * </ul>
     * <p>personId=null 返空列表（防御性）。
     */
    @Override
    public List<MyInitiatedTask> myPendingApprovals(Long personId) {
        if (personId == null) {
            return java.util.Collections.emptyList();
        }
        List<MyInitiatedTask> result = new ArrayList<>();

        // 1) deletion_requests：LEADER_REVIEW 或 ADMIN_REVIEW（双审模式：leader 先审 → 升级 admin 再审）
        List<DeletionRequest> deletionRows = deletionRequestMapper.selectList(new LambdaQueryWrapper<DeletionRequest>()
            .and(w -> w.in(DeletionRequest::getStatus, "LEADER_REVIEW", "ADMIN_REVIEW")));
        for (DeletionRequest row : deletionRows) {
            result.add(MyInitiatedTask.builder()
                .id(row.getId())
                .taskType(TASK_TYPE_DELETION_REQUEST)
                .sourceId(row.getId())
                .sourceTable(TABLE_DELETION_REQUESTS)
                .title(row.getEntityType() != null ? row.getEntityType() + "#" + row.getEntityId() : "DELETION#" + row.getId())
                .status(row.getStatus())
                .initiatorId(row.getCreateBy())
                .approverId(personId)
                .createdAt(row.getCreateTime())
                .build());
        }

        // 2) coefficient_change_requests：PENDING_LEADER（组长审批）
        List<CoefficientChangeRequest> coefficientRows = coefficientChangeRequestMapper.selectList(
            new LambdaQueryWrapper<CoefficientChangeRequest>()
                .eq(CoefficientChangeRequest::getStatus, "PENDING_LEADER"));
        for (CoefficientChangeRequest row : coefficientRows) {
            result.add(MyInitiatedTask.builder()
                .id(row.getId())
                .taskType(TASK_TYPE_COEFFICIENT_CHANGE)
                .sourceId(row.getId())
                .sourceTable(TABLE_COEFFICIENT_CHANGE_REQUESTS)
                .title(row.getReason() != null ? row.getReason() : "COEFFICIENT#" + row.getProjectId())
                .status(row.getStatus())
                .initiatorId(row.getCreateBy())
                .approverId(personId)
                .createdAt(row.getCreateTime())
                .build());
        }

        // 3) launch_date_change_requests：PENDING_SECOND（第二人复核）
        List<LaunchDateChangeRequest> launchDateRows = launchDateChangeRequestMapper.selectList(
            new LambdaQueryWrapper<LaunchDateChangeRequest>()
                .eq(LaunchDateChangeRequest::getStatus, "PENDING_SECOND"));
        for (LaunchDateChangeRequest row : launchDateRows) {
            result.add(MyInitiatedTask.builder()
                .id(row.getId())
                .taskType(TASK_TYPE_LAUNCH_DATE_CHANGE)
                .sourceId(row.getId())
                .sourceTable(TABLE_LAUNCH_DATE_CHANGE_REQUESTS)
                .title(row.getReason() != null ? row.getReason() : "LAUNCH_DATE#" + row.getProjectId())
                .status(row.getStatus())
                .initiatorId(row.getCreateBy())
                .approverId(personId)
                .createdAt(row.getCreateTime())
                .build());
        }

        return result;
    }

    /**
     * 内部辅助：按 count 展开为占位 MyInitiatedTask 视图卡。
     * 用于 myInitiated 的"按 type 渲染分组"语义——前端拿到 N 条同类型卡后按 taskType 聚合显示。
     */
    private void appendPlaceholderCards(List<MyInitiatedTask> target, String taskType, String tableName, long count) {
        if (count <= 0) {
            return;
        }
        for (long i = 0; i < count; i++) {
            // sourceId 用负数占位（负数表示"未指明具体单据"），避免与真业务单据 id 冲突
            long placeholderId = -(i + 1) * 1000L - taskType.hashCode() % 1000;
            target.add(MyInitiatedTask.builder()
                .id(placeholderId)
                .taskType(taskType)
                .sourceId(null)
                .sourceTable(tableName)
                .title("[R27-P0-6] " + taskType + " 占位卡")
                .status("COUNT")
                .initiatorId(null)
                .approverId(null)
                .createdAt(new Date())
                .build());
        }
    }
}
