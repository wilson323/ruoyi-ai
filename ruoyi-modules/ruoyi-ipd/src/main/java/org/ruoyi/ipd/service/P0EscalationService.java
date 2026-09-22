package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.domain.P0EscalationChain;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.mapper.NotificationEventMapper;
import org.ruoyi.ipd.mapper.P0EscalationChainMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * R149 batch2b C4：P0 升级链服务（AC-C4 决策：同一个 P0 连续两次都没升级，就要升级到双方组长）。
 *
 * <p>升级流程：
 * <ol>
 *   <li>{@link #recordP0Unresolved}：每次 P0 超期未升级 +1 count，写 (projectId, p0EventId) 行</li>
 *   <li>{@link #checkEscalation}：扫描 count >= 2 的 PENDING 记录，按规则升级到双方组长</li>
 *   <li>升级通知：复用现有 {@link NotificationService#publish}，向 GROUP_LEADER 角色两人各发一条</li>
 *   <li>{@link #listByProject}：端点列表查询（用于前端展示升级链）</li>
 * </ol>
 *
 * <p>阈值与频率：
 * <ul>
 *   <li>触发阈值：count >= 2（连续两次未升级）</li>
 *   <li>扫描窗口：默认 24h（next_threshold_at 字段，由调用方计算后传入）</li>
 *   <li>receiver_role=BOTH_LEADERS：以 content 字段标识（NotificationEvent 表无独立列）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class P0EscalationService implements IP0EscalationService {

    /** 触发升级的连续未升级次数阈值（AC-C4 决策） */
    public static final int ESCALATION_THRESHOLD = 2;

    /** 状态机：PENDING → ESCALATED → RESOLVED */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_ESCALATED = "ESCALATED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    /** 接收人角色标识（写在 notification_events.content 中；NotificationEvent 表无独立列） */
    public static final String RECEIVER_ROLE_BOTH_LEADERS = "BOTH_LEADERS";

    /** 通知类型常量（对齐 NotificationService.Types 命名） */
    public static final String NOTIFY_TYPE = "P0_ESCALATION_TO_LEADERS";

    private final P0EscalationChainMapper chainMapper;
    private final NotificationEventMapper notificationEventMapper;
    private final PersonMapper personMapper;
    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;
    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    /**
     * 记录一次 P0 超期未升级（幂等 upsert）。
     *
     * <p>逻辑：
     * <ul>
     *   <li>若 (projectId, p0EventId, status=PENDING) 已有记录：count +1，last_escalation_at 更新</li>
     *   <li>若不存在：新增一行 count=1, status=PENDING</li>
     *   <li>已 ESCALATED/RESOLVED 的不重复触发（避免重复升级）</li>
     * </ul>
     */
    @Transactional(rollbackFor = Exception.class)
    public P0EscalationChain recordP0Unresolved(Long projectId, Long p0EventId, Date nextThresholdAt) {
        if (projectId == null || projectId <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 必填且 > 0");
        }
        if (p0EventId == null || p0EventId <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "p0EventId 必填且 > 0");
        }
        Date now = now();

        // 查找现有 PENDING 行
        QueryWrapper<P0EscalationChain> q = new QueryWrapper<>();
        q.eq("project_id", projectId).eq("p0_event_id", p0EventId).eq("status", STATUS_PENDING);
        q.last("LIMIT 1");
        P0EscalationChain existing = chainMapper.selectOne(q);

        if (existing == null) {
            P0EscalationChain row = P0EscalationChain.builder()
                .projectId(projectId)
                .p0EventId(p0EventId)
                .escalationCount(1)
                .lastEscalationAt(now)
                .nextThresholdAt(nextThresholdAt)
                .status(STATUS_PENDING)
                .tenantId("000000")
                .delFlag("0")
                .build();
            chainMapper.insert(row);
            log.info("R149 batch2b P0 escalation insert projectId={} p0EventId={} count=1",
                projectId, p0EventId);
            return row;
        } else {
            int newCount = existing.getEscalationCount() + 1;
            UpdateWrapper<P0EscalationChain> uw = new UpdateWrapper<>();
            uw.eq("id", existing.getId())
              .set("escalation_count", newCount)
              .set("last_escalation_at", now)
              .set("next_threshold_at", nextThresholdAt)
              .set("update_time", now);
            chainMapper.update(null, uw);
            log.info("R149 batch2b P0 escalation update projectId={} p0EventId={} count={}",
                projectId, p0EventId, newCount);
            existing.setEscalationCount(newCount);
            existing.setLastEscalationAt(now);
            existing.setNextThresholdAt(nextThresholdAt);
            return existing;
        }
    }

    /**
     * 扫描待升级链（count >= 2 且 PENDING），触发升级通知给双方组长。
     *
     * @return 触发的升级数（影响行数）
     */
    @Transactional(rollbackFor = Exception.class)
    public int checkEscalation() {
        QueryWrapper<P0EscalationChain> q = new QueryWrapper<>();
        q.eq("status", STATUS_PENDING)
         .ge("escalation_count", ESCALATION_THRESHOLD);
        List<P0EscalationChain> due = chainMapper.selectList(q);
        if (due.isEmpty()) {
            return 0;
        }
        int escalated = 0;
        for (P0EscalationChain chain : due) {
            if (escalateOne(chain)) {
                escalated++;
            }
        }
        return escalated;
    }

    /**
     * 单条升级：发通知 + 翻状态为 ESCALATED。
     *
     * <p>通知策略：复用 {@link NotificationService#publish}，按 persons.person_type='GROUP_LEADER' 各自成行；
     * NotificationEvent.content 字段标识 receiver_role='BOTH_LEADERS'（无独立列）。
     *
     * @return 是否成功升级
     */
    private boolean escalateOne(P0EscalationChain chain) {
        // 1. 找所有 GROUP_LEADER（双方组长 = 两个产品组的组长；任务里 BOTH_LEADERS 简化为所有 GROUP_LEADER）
        LambdaQueryWrapper<Person> lq = new LambdaQueryWrapper<>();
        lq.eq(Person::getPersonType, "GROUP_LEADER")
          .eq(Person::getAccountStatus, "ACTIVE")
          .eq(Person::getDelFlag, "0");
        List<Person> leaders = personMapper.selectList(lq);
        if (leaders.isEmpty()) {
            log.warn("R149 batch2b P0 escalation skip projectId={} p0EventId={}：未找到 GROUP_LEADER",
                chain.getProjectId(), chain.getP0EventId());
            return false;
        }

        // 2. 构造 content（标识 receiver_role + 业务字段）
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiver_role", RECEIVER_ROLE_BOTH_LEADERS);
        payload.put("projectId", chain.getProjectId());
        payload.put("p0EventId", chain.getP0EventId());
        payload.put("escalationCount", chain.getEscalationCount());
        payload.put("lastEscalationAt", chain.getLastEscalationAt());
        String contentJson;
        try {
            contentJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            contentJson = "{\"receiver_role\":\"" + RECEIVER_ROLE_BOTH_LEADERS + "\",\"_error\":\"json\"}";
        }

        // 3. 给每个 GROUP_LEADER 发一条通知
        for (Person leader : leaders) {
            notificationService.publish(
                leader.getId(),
                NOTIFY_TYPE,
                NotificationService.KIND_ACTION,
                "p0_escalation_chain",
                chain.getId(),
                "P0 升级：项目 " + chain.getProjectId() + " 连续 " + chain.getEscalationCount() + " 次未处置",
                contentJson,
                "/workbench/p0?projectId=" + chain.getProjectId()
            );
        }

        // 4. 翻状态为 ESCALATED（避免重复触发）
        UpdateWrapper<P0EscalationChain> uw = new UpdateWrapper<>();
        uw.eq("id", chain.getId())
          .set("status", STATUS_ESCALATED)
          .set("remark", "已升级到双方组长，count=" + chain.getEscalationCount())
          .set("update_time", now());
        chainMapper.update(null, uw);
        log.warn("R149 batch2b P0 ESCALATED projectId={} p0EventId={} count={} leaders={}",
            chain.getProjectId(), chain.getP0EventId(), chain.getEscalationCount(), leaders.size());
        return true;
    }

    /**
     * 按项目列出升级链（端点用）。
     */
    public List<P0EscalationChain> listByProject(Long projectId) {
        QueryWrapper<P0EscalationChain> q = new QueryWrapper<>();
        if (projectId != null && projectId > 0) {
            q.eq("project_id", projectId);
        }
        q.orderByDesc("last_escalation_at", "id");
        return chainMapper.selectList(q);
    }

    /**
     * 标记某条升级为 RESOLVED（手动关闭；处置完成后调用）。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean resolve(Long id, String remark) {
        if (id == null || id <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "id 必填且 > 0");
        }
        UpdateWrapper<P0EscalationChain> uw = new UpdateWrapper<>();
        uw.eq("id", id)
          .set("status", STATUS_RESOLVED)
          .set("remark", remark == null ? "" : remark)
          .set("update_time", now());
        int rows = chainMapper.update(null, uw);
        return rows > 0;
    }

    /**
     * 测试口：根据 (projectId, p0EventId) 取最新一条。
     */
    public P0EscalationChain findLatest(Long projectId, Long p0EventId) {
        QueryWrapper<P0EscalationChain> q = new QueryWrapper<>();
        q.eq("project_id", projectId).eq("p0_event_id", p0EventId);
        q.orderByDesc("escalation_count")
         .last("LIMIT 1");
        return chainMapper.selectOne(q);
    }
}
