package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.NotificationChannelType;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.mapper.NotificationEventMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INotificationService 接口（paiban-05 接口化，实现见 {@link NotificationService}）。
 */
public interface INotificationService {

    /** * 发布事件（幂等）：dedup_key = source_type:event_type:source_id:receiver_id 撞库时 */
    /** * 返回既有行不重发（MySQL 下捕获 DuplicateKey 不污染事务）。同一事件发给多个接收者 */
    /** * 各自成行（receiver 维度独立去重，如 AC-TEAM-05 中标 1 人 + 落选 2 人）。 */
    /** * */
    /** * @return 落库行（重复发布时为既有行） */
    NotificationEvent publish(
        Long receiverId,
        String eventType,
        String kind,
        String sourceType,
        Long sourceId,
        String title,
        String content,
        String actionUrl
    );

    /** * P1-4.4 每日提醒专用：dedupKey 追加自然日（yyyyMMdd）。 */
    /** * 同日重扫不重发（重复扫描不多通知）；次日可再提醒（AC-IPD-12 每日提醒）。 */
    NotificationEvent publishDaily(
        Long receiverId,
        String eventType,
        String kind,
        String sourceType,
        Long sourceId,
        String title,
        String content,
        String actionUrl,
        java.util.Date day
    );

    /** * 本人收件箱（receiver 强隔离：查询恒带 receiver_id 条件）。 */
    /** * */
    /** * @param receiverId  接收者 */
    /** * @param unreadOnly true=仅未读 */
    /** * @return 事件列表（新→旧） */
    List<NotificationEvent> inbox(Long receiverId, boolean unreadOnly);

    /** 未读计数（页03 站内信红点）。 */
    long unreadCount(Long receiverId);

    /** * 标记已读（仅本人；他人 ID 视为不存在，杜绝探测）。 */
    /** * */
    /** * @param id         事件 ID */
    /** * @param receiverId 当前会话人 */
    /** * @return 更新后行（已读幂等） */
    NotificationEvent markRead(Long id, Long receiverId);

    /** 全部已读（仅本人未读行）。 */
    int markAllRead(Long receiverId);

    /** * outbox 消费端：扫描到期行（PENDING，或 FAILED 且退避期满）逐条投递。 */
    /** * 单条失败不影响后续行；结果计数供运维观察（调用方可为管理端点或后续调度器）。 */
    /** * */
    /** * @param limit 单轮上限（1-200） */
    /** * @return sent/failed/dead/skipped 计数 */
    Map<String, Integer> dispatchPending(int limit);

}
