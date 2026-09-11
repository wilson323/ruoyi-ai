package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Set;

/**
 * P1 / §5.3 HIGH-1.1：G3 评审自动创建入口（独立端点 POST /api/v1/projects/{id}/gates?gateCode=）。
 *
 * <p>约束（按 governance-1 §5.6）：
  - 同一项目同一 gateCode 每 14 天最多创建 1 次（防刷创建 / 审计噪声）
  - gateCode ∈ {G1, G2, G3, G4, G5}（ZK-IPD §三.1）
  - 项目状态非 ARCHIVED 才允许创建
  - 写 GATE_AUTO_CREATE 审计行
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
public class GateCreationService {

    /** §5.3 / ZK-IPD §三.1：合法 gateCode 枚举 */
    public static final Set<String> ALLOWED_GATE_CODES = Set.of("G1", "G2", "G3", "G4", "G5");

    /** §5.3：每 14 天最多创建 1 次（毫秒） */
    public static final long CREATE_COOLDOWN_MS = 14L * 24 * 3600 * 1000;

    private final GateMapper gateMapper;
    private final ProjectMapper projectMapper;
    private final AuditLogService auditLogService;

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
        // 冷却窗口：同项目同 gateCode 最近 14 天内不可重复创建（gates 主体表）
        Date cutoff = new Date(now().getTime() - CREATE_COOLDOWN_MS);
        Long recent = gateMapper.selectCount(new LambdaQueryWrapper<Gate>()
            .eq(Gate::getProjectId, projectId)
            .eq(Gate::getGateCode, gateCode)
            .ge(Gate::getCreateTime, cutoff));
        if (recent != null && recent > 0) {
            throw new ServiceException(gateCode + " 评审每 14 天最多自动创建 1 次（最近 14 天已有 " + recent + " 条）");
        }
        // R30：写 gates 主体表（PENDING / round=1 / startedAt 留空=尚未提交要素判定，
        // GateReviewService.requireSubmitted 据此拦 sign）；decision/reviewer 归属签署表
        // gate_reviews，由 sign 流写入（保持 NULL=待决语义，见 R11/A4 死路登记）。
        Gate gate = Gate.builder()
            .projectId(projectId)
            .gateCode(gateCode)
            .status("PENDING")
            .currentRound(1)
            .signDueAt(new Date(now().getTime() + 3L * 24 * 3600 * 1000)) // 默认 3 天签署期
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
}