package org.ruoyi.ipd.agent.support;

import org.ruoyi.ipd.agent.domain.IpdAiFeedback;
import org.ruoyi.ipd.agent.store.AiFeedbackStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 反馈存储测试替身：忠实模拟唯一键 (target_type, target_id, person_id)。
 */
public final class InMemoryAiFeedbackStore implements AiFeedbackStore {

    private final Map<String, IpdAiFeedback> rows = new LinkedHashMap<>();
    private final AtomicLong ids = new AtomicLong(5_000L);
    public final AtomicInteger inserts = new AtomicInteger();
    public final AtomicInteger updates = new AtomicInteger();

    /** {@inheritDoc} */
    @Override
    public synchronized Optional<IpdAiFeedback> find(String targetType, Long targetId, Long personId) {
        IpdAiFeedback row = rows.get(key(targetType, targetId, personId));
        return Optional.ofNullable(row == null ? null : copy(row));
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean insert(IpdAiFeedback feedback) {
        String k = key(feedback.getTargetType(), feedback.getTargetId(), feedback.getPersonId());
        if (rows.containsKey(k)) {
            return false;
        }
        if (feedback.getId() == null) {
            feedback.setId(ids.incrementAndGet());
        }
        rows.put(k, copy(feedback));
        inserts.incrementAndGet();
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public synchronized boolean updateRating(String targetType, Long targetId, Long personId,
                                             String rating, String reason) {
        String k = key(targetType, targetId, personId);
        IpdAiFeedback existing = rows.get(k);
        if (existing == null) {
            return false;
        }
        existing.setRating(rating);
        existing.setReason(reason);
        updates.incrementAndGet();
        return true;
    }

    /** @return 当前行数 */
    public synchronized int size() {
        return rows.size();
    }

    private static String key(String targetType, Long targetId, Long personId) {
        return targetType + "|" + targetId + "|" + personId;
    }

    private static IpdAiFeedback copy(IpdAiFeedback src) {
        return IpdAiFeedback.builder()
            .id(src.getId())
            .tenantId(src.getTenantId())
            .targetType(src.getTargetType())
            .targetId(src.getTargetId())
            .projectId(src.getProjectId())
            .personId(src.getPersonId())
            .rating(src.getRating())
            .reason(src.getReason())
            .version(src.getVersion())
            .delFlag(src.getDelFlag())
            .build();
    }
}
