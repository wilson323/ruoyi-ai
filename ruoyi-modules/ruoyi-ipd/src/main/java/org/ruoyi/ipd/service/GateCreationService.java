package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * P1 / §5.3 HIGH-1.1：G3 评审自动创建入口（独立端点 POST /api/v1/projects/{id}/gates?gateCode=）。
 *
 * <p>约束（按 governance-1 §5.6）：
  - **在途去重**（F6-①，2026-10-07）：同一项目同一 gateCode 已存在 {@code status='PENDING'} 的行时拒绝再建。
    原实现的「14 天时间冷却」把 G3 的**双周复评周期**误当成了**禁建窗口**，方向与需求相反。
  - gateCode ∈ {G1, G2, G3, G4, G5}（ZK-IPD §三.1）
  - 项目状态非 ARCHIVED 才允许创建
  - 写 GATE_AUTO_CREATE 审计行
 *
 * <p>自动触发点（F6-②，owner 2026-10-07 拍板「本阶段动作做完后建」）：
 * 对应动作（C11→G1 / P13→G2 / D05→G3 / L07→G4 / LC02→G5）状态流转到 DONE 时，
 * 由 {@link StageActionService} 委托本服务建卡；映射取自 {@code ActionDef.gate()}，
 * 本类不重复维护一份动作→Gate 的字面量表。
 *
 * <p>F6-③ 的 G3 双周复评每日扫描见 {@link #scanDevG3Recreate()}，与动作触发互为补充
 * （项目长期停在 DEV 且没人维护 D05 行时，只能靠扫描补建）。
 *
 * <p>使用方式：调用方（前端/超管）传入 gateCode，服务在 gates 主体表创建 Gate 实例
 * （PENDING / round=1 / startedAt 留空待 P2-5.1 要素判定提交置位；
 * 后续 GateReviewService.sign 进入双签流）。
 *
 * <p>R30 生产就绪修复（2026-09-11，E2E 抓获 P0）：原实现把签署记录实体 GateReview
 * 直接 insert 进 gate_reviews 表（gate_id NOT NULL 无默认 → 真库恒 90001），
 * 且 reviewerType/decision 语义错位。改为与 GateReviewService 全链一致的
 * gates 主体表写入（sign/reopen/listByProject 均读 gates）。
 */
@Service
@RequiredArgsConstructor
public class GateCreationService implements IGateCreationService {

    /** §5.3 / ZK-IPD §三.1：合法 gateCode 枚举 */
    public static final Set<String> ALLOWED_GATE_CODES = Set.of("G1", "G2", "G3", "G4", "G5");

    /** Gate 在途态：同一 project+gateCode 已有 PENDING 行时不得再建（2026-10-07 F6-①）。 */
    public static final String STATUS_PENDING = "PENDING";

    /** G3 双周复评的最小间隔（天），供每日扫描 job 判定「距上次 G3 是否已到期」。 */
    public static final int DEFAULT_G3_RECREATE_INTERVAL_DAYS = 14;

    private final GateMapper gateMapper;
    private final ProjectMapper projectMapper;
    private final IAuditLogService auditLogService;

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();
    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }
    private Date now() { return Date.from(clock.instant()); }

    @Transactional(rollbackFor = Exception.class)
    public Gate autoCreateGate(Long projectId, String gateCode, Long operatorId) {
        if (projectId == null) {
            throw new ServiceException("项目 ID 不能为空");
        }
        if (gateCode == null || !ALLOWED_GATE_CODES.contains(gateCode)) {
            throw new ServiceException("gateCode 非法（允许 G1/G2/G3/G4/G5）: " + gateCode);
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new ServiceException("项目不存在: " + projectId);
        }
        if ("ARCHIVED".equals(project.getStatus()) || "SUSPENDED".equals(project.getStatus())) {
            throw new ServiceException("归档/暂停项目不可创建 Gate 评审");
        }
        // 在途去重（F6-①，取代原 14 天时间冷却）：
        // 原判据「同项目同 gateCode 最近 14 天内不可重复创建」把 G3 的**双周复评周期**
        // 误当成了**禁建窗口**——需求要「到点必生」，实现却是「14 天内不许再生」，方向相反，
        // 导致 G3 永远建不出来（验收文档 A3-01~06 记为 P0）。
        // 正确判据是「有没有未决的同 Gate 轮次」：有 PENDING 在途才拒；上一轮无论多久以前结的，
        // 只要当前没有在途，就允许再建——这正是 G3 双周复评要的行为。
        Long inFlight = gateMapper.selectCount(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getProjectId, projectId)
            .eq(Gate::getGateCode, gateCode)
            .eq(Gate::getStatus, STATUS_PENDING));
        if (inFlight != null && inFlight > 0) {
            throw new ServiceException(gateCode + " 已有在途轮次未决，不可重复创建（当前在途 " + inFlight + " 条）；"
                + "请先完成或延期当前轮次");
        }
        // R30：写 gates 主体表（PENDING / round=1 / startedAt 留空=尚未提交要素判定，
        // GateReviewService.requireSubmitted 据此拦 sign）；decision/reviewer 归属签署表
        // gate_reviews，由 sign 流写入（保持 NULL=待决语义，见 R11/A4 死路登记）。
        Gate gate = Gate.builder()
            .projectId(projectId)
            .gateCode(gateCode)
            .status(STATUS_PENDING)
            .currentRound(1)
            // 默认 3 天签署期。
            // TODO(F6-④，待授权改 GateReviewService)：应收敛到 GateReviewService.resolveSignDeadlineDays()
            // （读 gate.signDeadlineDays，支持运行时覆盖），但该方法当前是 private、跨类不可见；
            // 去掉 private 属于 GateReviewService 的改动面，不在本刀授权内，故此处留 TODO 不擅自改。
            .signDueAt(new Date(now().getTime() + 3L * 24 * 3600 * 1000))
            .signExtensionCount(0)
            .delFlag("0")
            .build();
        gate.setCreateTime(now());
        gateMapper.insert(gate);
        auditLogService.append(AuditLog.builder()
            .operatorId(operatorId)
            .action("GATE_AUTO_CREATE")
            .entityType("gates")
            .entityId(gate.getId())
            .reason("project:" + projectId + " gateCode:" + gateCode)
            .afterData(AuditEventData.json(
                "projectId", projectId,
                "gateCode", gateCode,
                "round", 1))
            .createTime(now())
            .build());
        return gate;
    }

    /**
     * G3 复评周期（天）。放 yml 是为了让业务方能调，不必改代码发版。
     * 注意：这是「两次 G3 之间至少间隔多久」的<b>业务复评周期</b>（F6-③），
     * 与已废除的「禁止建卡冷却窗口」完全是两件事。
     */
    @org.springframework.beans.factory.annotation.Value("${gate.g3.recreate_interval_days:14}")
    private int g3RecreateIntervalDays = DEFAULT_G3_RECREATE_INTERVAL_DAYS;
    public void setG3RecreateIntervalDays(int days) {
        // 配错值（0/负数）不能让扫描退化成「每天给每个 DEV 项目建一张 G3」——回落到默认 14。
        this.g3RecreateIntervalDays = (days > 0) ? days : DEFAULT_G3_RECREATE_INTERVAL_DAYS;
    }
    public int getG3RecreateIntervalDays() { return g3RecreateIntervalDays; }

    /** 单次扫描最多处理多少个项目，避免项目量大时一条 SQL 拉全表。 */
    static final int G3_SCAN_LIMIT = 500;

    /** G3 所属阶段（与 ActionCatalog 中 D05 的 stage 字段同源）。 */
    static final String DEV_STAGE = "DEV";

    /**
     * F6-③：G3 双周开发复评扫描——为处于 DEV 阶段、且距上次 G3 建卡已满复评周期的项目补建 G3。
     *
     * <p>由 {@code GateG3RecreateScheduler} 每日 10:10 调用。
     *
     * <p>判定口径两条，缺一不可：
     * <ol>
     *   <li><b>时间到期</b>：从未建过 G3，或最后一行的建卡时间距今 ≥ 复评周期（默认 14 天）。
     *       ——这正是需求「每两周一次」要的口子，旧的时间冷却把它堵死了。</li>
     *   <li><b>无在途</b>：同项目 G3 已有 PENDING 行时跳过。建卡失败不阻断整批（单项目 try 隔离），
     *       否则一个项目的数据问题会让当天所有项目都不建卡。</li>
     * </ol>
     *
     * @return 本次实际建成的 G3 数量
     */
    public int scanDevG3Recreate() {
        Date threshold = new Date(now().getTime() - (long) g3RecreateIntervalDays * 24 * 3600 * 1000);
        List<Project> devProjects = projectMapper.selectList(new LambdaQueryWrapper<Project>()
            .eq(Project::getCurrentStage, DEV_STAGE)
            .notIn(Project::getStatus, "ARCHIVED", "SUSPENDED")
            .last("LIMIT " + G3_SCAN_LIMIT));
        if (devProjects == null || devProjects.isEmpty()) {
            return 0;
        }
        int created = 0;
        for (Project p : devProjects) {
            if (p == null || p.getId() == null || "1".equals(p.getDelFlag())) {
                continue;
            }
            try {
                Gate lastG3 = latestGateOf(p.getId(), "G3");
                if (lastG3 != null && lastG3.getCreateTime() != null
                    && lastG3.getCreateTime().after(threshold)) {
                    continue;   // 还没到期
                }
                Long inFlight = gateMapper.selectCount(new LambdaQueryWrapper<Gate>()
                    .eq(Gate::getProjectId, p.getId())
                    .eq(Gate::getGateCode, "G3")
                    .eq(Gate::getStatus, STATUS_PENDING));
                if (inFlight != null && inFlight > 0) {
                    continue;   // 已有在途轮次
                }
                autoCreateGate(p.getId(), "G3", p.getId());
                created++;
            } catch (Exception ex) {
                // 单项目失败不中断整批：记一条审计让问题可追，但不挡住其他项目本期建卡。
                auditLogService.append(AuditLog.builder()
                    .operatorId(p.getId())
                    .action("G3_RECREATE_SKIPPED")
                    .entityType("gates")
                    .reason("project:" + p.getId() + " gateCode:G3 error:" + ex.getMessage())
                    .createTime(now())
                    .build());
            }
        }
        return created;
    }

    /** 取该项目该 gateCode 最后一轮（按 id 倒序取首行）；无记录返回 null。 */
    private Gate latestGateOf(Long projectId, String gateCode) {
        List<Gate> rows = gateMapper.selectList(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getProjectId, projectId)
            .eq(Gate::getGateCode, gateCode)
            .orderByDesc(Gate::getId)
            .last("LIMIT 1"));
        return (rows == null || rows.isEmpty()) ? null : rows.get(0);
    }
}