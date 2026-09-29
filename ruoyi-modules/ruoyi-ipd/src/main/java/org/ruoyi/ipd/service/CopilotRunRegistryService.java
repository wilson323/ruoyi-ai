package org.ruoyi.ipd.service;

import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.dto.AiCopilotResp;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P3 取消切片（B↔C 合同 G1，2026-09-29）：AI 副驾 run 注册表 + 取消语义唯一实现。
 *
 * <p>口径对齐 ADR-0075 §③「取消 = CANCELLED 终态并保留审计事实；取消先赢则晚到回调
 * 无写入/成功帧」：进程内 run 句柄按 runId 归属，{@link RunHandle#isCancelled()} 为
 * 流式回调线程的停止谓词——取消后 guard 吞掉全部后续帧（含 done），终结只发生一次。
 *
 * <p>单进程内存态（SSE run 本是进程内资源，重启即失；多副本路由粘性属 C2 待验面，
 * 不在本片发明分布式机制）。TTL 惰性驱逐兜底防句柄泄漏（SSE_TIMEOUT 60s + 5 倍余量）。
 */
@Slf4j
@Service
public class CopilotRunRegistryService {

    /** run 句柄存活上限：超过即视为已结束/断链，惰性驱逐（仅空位时扫描，O(1) 均摊）。 */
    static final long RUN_TTL_MS = 300_000L;

    private final Map<String, RunHandle> runs = new ConcurrentHashMap<>();
    private final IAuditLogService auditLogService;

    public CopilotRunRegistryService(IAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /** 一次 run 的取消句柄：owner 定型，cancelFlag 由流式回调线程与取消端点共享。 */
    public static final class RunHandle {
        private final String runId;
        private final Long ownerId;
        private final long createdAtMs;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        RunHandle(String runId, Long ownerId, long createdAtMs) {
            this.runId = runId;
            this.ownerId = ownerId;
            this.createdAtMs = createdAtMs;
        }

        public String runId() {
            return runId;
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /** 幂等置位：首次 true，重复取消 false（响应语义不重复落审计）。 */
        boolean markCancelled() {
            return cancelled.compareAndSet(false, true);
        }
    }

    /**
     * 注册 run。runId 空则服务端生成；已被他人占用 → FORBIDDEN（防未授权方劫持取消面，
     * 且不泄露该 runId 的存在性）；同 owner 重复注册 = 替换旧句柄。
     */
    public RunHandle register(IpdActor actor, String runId) {
        String id = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId.trim();
        evictExpiredIfFull();
        RunHandle fresh = new RunHandle(id, actor.id(), System.currentTimeMillis());
        runs.compute(id, (k, old) -> {
            if (old != null && !old.ownerId.equals(fresh.ownerId)) {
                throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "runId 已被占用");
            }
            return fresh;
        });
        return fresh;
    }

    /** 正常终帧（done/error/complete 后）注销。 */
    public void unregister(String runId) {
        if (runId != null) {
            runs.remove(runId);
        }
    }

    /**
     * 取消：仅 run 所有者可取消（外部一律 NOT_FOUND，不泄露存在性）。
     * 首次取消成功落审计（ADR-0075：保留审计事实）；重复取消幂等返回。
     */
    public Map<String, Object> cancel(IpdActor actor, String runId) {
        RunHandle h = runs.get(runId);
        if (h == null || !h.ownerId.equals(actor.id())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND);
        }
        boolean first = h.markCancelled();
        if (first) {
            runs.remove(runId);
            auditLogService.append(actor, "AI_COPILOT_RUN_CANCEL", "AI_COPILOT", null, "runId=" + runId);
            log.info("[AI-COPILOT-CANCEL] run cancelled actor={} runId={}", actor.id(), runId);
        }
        return Map.of("runId", runId, "cancelled", true, "firstTime", first);
    }

    /**
     * 既有四帧链（meta/delta/done/error）的取消守卫：取消先赢后全部晚到回调静默
     * （无成功帧语义），完成回调照常注销。
     */
    public AiCopilotService.CopilotStreamSink guard(RunHandle handle, AiCopilotService.CopilotStreamSink inner) {
        if (handle == null) {
            return inner;
        }
        return new AiCopilotService.CopilotStreamSink() {
            @Override
            public void meta(AiCopilotResp resp) {
                if (handle.isCancelled()) {
                    return;
                }
                inner.meta(resp);
            }

            @Override
            public void delta(String token) {
                if (handle.isCancelled()) {
                    return;
                }
                inner.delta(token);
            }

            @Override
            public void done(AiCopilotResp resp) {
                unregister(handle.runId());
                if (handle.isCancelled()) {
                    return;
                }
                inner.done(resp);
            }

            @Override
            public void error(String code, String message) {
                unregister(handle.runId());
                if (handle.isCancelled()) {
                    return;
                }
                inner.error(code, message);
            }
        };
    }

    /** 空间紧张时清扫过期句柄（正常量级下不触发；防异常断链累积）。 */
    private void evictExpiredIfFull() {
        if (runs.size() < 64) {
            return;
        }
        long now = System.currentTimeMillis();
        runs.entrySet().removeIf(e -> now - e.getValue().createdAtMs > RUN_TTL_MS);
    }
}
