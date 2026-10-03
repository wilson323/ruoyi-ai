package org.ruoyi.ipd.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.agent.domain.IpdAgentRun;
import org.ruoyi.ipd.agent.store.AgentRunStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

/** 启动及周期恢复仅处理已有owner协议标记的运行；时间只用于旧记录诊断，不判死亡。 */
@Slf4j
@Component
public class ProjectAgentInterruptedRunCloser {
    private static final int PAGE = 50;
    private final AgentRunStore store;
    private final long bootedAtMillis;
    private ProjectAgentRunRecovery recovery;
    private Long recoveryCursor;

    @Autowired
    public ProjectAgentInterruptedRunCloser(AgentRunStore store) {
        this(store, System.currentTimeMillis());
    }

    ProjectAgentInterruptedRunCloser(AgentRunStore store, long bootedAtMillis) {
        this.store = store;
        this.bootedAtMillis = bootedAtMillis;
    }

    @Autowired(required = false)
    public void setRecovery(ProjectAgentRunRecovery recovery) { this.recovery = recovery; }

    /** 是否能收口由成熟所有权锁+DB epoch共同决定。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${ipd.project-agent.recovery-scan-ms:30000}")
    public synchronized void recoverOwnedCandidates() {
        if (recovery == null) return;
        // 按runId分页扫描，WAITING_APPROVAL与旧无owner运行会明确跳过。
        List<IpdAgentRun> rows = store.listRecoveryCandidates(recoveryCursor, PAGE);
        for (IpdAgentRun row : rows) {
            try { recovery.recover(row); } catch (RuntimeException failure) {
                log.warn("project_agent operation=INTERRUPT_RECOVERY status=PENDING_RECOVERY runId={} errorType={}",
                    row.getId(), failure.getClass().getName());
            }
        }
        recoveryCursor = rows.size() < PAGE ? null : rows.get(rows.size() - 1).getId();
    }

    /** 仅提示待核查，不能把其他实例仍在执行的运行记为中断或已恢复。 */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            recoverOwnedCandidates();
            int sampled = diagnoseCandidates();
            if (sampled > 0) {
                log.warn("project_agent operation=INTERRUPT_DIAGNOSE status=PENDING_RECOVERY sampledCount={} sampleLimit={}",
                    sampled, PAGE);
            }
        } catch (RuntimeException failure) {
            log.warn("project_agent operation=INTERRUPT_DIAGNOSE status=PENDING_RECOVERY errorType={}",
                failure.getClass().getName());
        }
    }

    /** 返回一页候选的样本数，不是中断总数；没有任何业务写操作。 */
    public int diagnoseCandidates() {
        List<IpdAgentRun> rows = store.listInterruptedCandidates(new Date(bootedAtMillis), PAGE);
        return rows.size();
    }
}
