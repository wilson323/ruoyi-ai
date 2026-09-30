package org.ruoyi.ipd.agent.store;

import org.ruoyi.ipd.agent.domain.IpdAiFeedback;

import java.util.Optional;

/**
 * AI 反馈持久化端口。唯一键 (target_type, target_id, person_id)：
 * {@link #insert} 冲突返回 false，调用方改走 {@link #updateRating}。
 */
public interface AiFeedbackStore {

    /**
     * 查本人对目标的反馈。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param personId 本人
     * @return 已有反馈
     */
    Optional<IpdAiFeedback> find(String targetType, Long targetId, Long personId);

    /**
     * 新增反馈。
     *
     * @param feedback 反馈行
     * @return true 新写入；false 唯一键已存在
     */
    boolean insert(IpdAiFeedback feedback);

    /**
     * 更新本人反馈的评分与原因（按唯一键定位，只改本人行）。
     *
     * @param targetType 目标类型
     * @param targetId 目标 ID
     * @param personId 本人
     * @param rating UP/DOWN
     * @param reason 原因（可空）
     * @return true 更新到 1 行
     */
    boolean updateRating(String targetType, Long targetId, Long personId, String rating, String reason);
}
