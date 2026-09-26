package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * R221 唯一写 ai_agent_tasks 的触发入口（spec §3）。
 * dedup：同 dedup_key 且 status ∈ PENDING/RUNNING 存在即跳过返回既有任务（spec §2.2 幂等定案）。
 * 派发：有事务则 afterCommit 提交引擎异步跑一轮；无事务（调度线程）直接异步派发。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiExecutionTrigger {

    private final AiAgentTaskMapper taskMapper;
    private final AiExecutionEngine engine;

    public AiAgentTask triggerPassive(Long projectId, String actionCode, Long stageActionId, Long triggeredBy) {
        return trigger(projectId, actionCode, stageActionId, AiAgentTask.TRIGGER_PASSIVE, triggeredBy, null, null);
    }

    public AiAgentTask triggerEvent(Long projectId, String actionCode, Long stageActionId) {
        return trigger(projectId, actionCode, stageActionId, AiAgentTask.TRIGGER_EVENT, null, null, null);
    }

    public AiAgentTask triggerSchedule(Long projectId, String actionCode, Long stageActionId) {
        return trigger(projectId, actionCode, stageActionId, AiAgentTask.TRIGGER_SCHEDULE, null, null, null);
    }

    public AiAgentTask triggerChat(Long projectId, String actionCode, Long stageActionId, Long triggeredBy,
                                   String fillPayloadJson, String inputDigest) {
        return trigger(projectId, actionCode, stageActionId, AiAgentTask.TRIGGER_CHAT, triggeredBy,
            fillPayloadJson, inputDigest);
    }

    private AiAgentTask trigger(Long projectId, String actionCode, Long stageActionId, String triggerType,
                                Long triggeredBy, String fillPayloadJson, String inputDigest) {
        String dedupKey = actionCode + ":" + stageActionId + ":" + triggerType;
        List<AiAgentTask> inflight = taskMapper.selectList(new LambdaQueryWrapper<AiAgentTask>()
            .eq(AiAgentTask::getDedupKey, dedupKey)
            .in(AiAgentTask::getStatus, AiAgentTask.STATUS_PENDING, AiAgentTask.STATUS_RUNNING));
        if (!inflight.isEmpty()) {
            log.info("[R221] dedup 命中，跳过建任务: {}", dedupKey);
            return inflight.get(0);
        }
        ActionDef def = ActionCatalog.byCode(actionCode);
        AiAgentTask t = AiAgentTask.builder()
            .projectId(projectId).actionCode(def.code()).stageActionId(stageActionId)
            .triggerType(triggerType).execMode(def.execMode())
            .status(AiAgentTask.STATUS_PENDING).dedupKey(dedupKey)
            .attempt(0).fillPayload(fillPayloadJson).inputDigest(inputDigest)
            .triggeredBy(triggeredBy)
            .build();
        t.setCreateBy(0L); // Global Constraint 4：调度/系统线程 createBy 手工置 0
        try {
            taskMapper.insert(t);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // DB 唯一兜底 uk_active_dedup 挡下并发双插（SELECT-then-INSERT 的 TOCTOU 竞态，#4）：
            // 重查在途行返回，保证触发幂等（不重复执行动作、不重复写业务表）。
            List<AiAgentTask> raced = taskMapper.selectList(new LambdaQueryWrapper<AiAgentTask>()
                .eq(AiAgentTask::getDedupKey, dedupKey)
                .in(AiAgentTask::getStatus, AiAgentTask.STATUS_PENDING, AiAgentTask.STATUS_RUNNING));
            if (!raced.isEmpty()) {
                log.info("[R221] 唯一键兜底并发双插，返回既有在途任务: {}", dedupKey);
                return raced.get(0);
            }
            throw dup; // 极端：唯一键冲突但查不到在途行（对手行已翻终态），交上层处理
        }
        dispatchAfterCommit();
        return t;
    }

    private void dispatchAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    engine.dispatchAsync();
                }
            });
        } else {
            engine.dispatchAsync();
        }
    }
}
