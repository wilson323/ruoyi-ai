package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.BidInvitation;
import org.ruoyi.ipd.domain.BidResponse;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.BidInvitationMapper;
import org.ruoyi.ipd.mapper.BidResponseMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IBidScanService 接口（paiban-05 接口化，实现见 {@link BidScanService}）。
 */
public interface IBidScanService {

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** * AC-TEAM-06：到期前 3 天的 OPEN 单，提醒市场 PM（dedup_key 含自然日）。 */
    /** * 扫描范围：expireAt ∈ [now, now+3d] 且 status=OPEN。 */
    int scanExpiringSoon();

    /** * AC-TEAM-07：到期有应标但超 7 天未遴选，升级通知产品组长（MEDIUM-2.3 真发通知）。 */
    /** * 扫描范围：expireAt < (now-7d) 且 status=OPEN（说明有应标但还未遴选，否则 scanExpireNoResponse 已处理）。 */
    /** * 升级目标：项目主组组长（persons.person_type=GROUP_LEADER AND group_id=project.main_group_id）。 */
    /** * 幂等：依赖 publishDaily 的自然日 dedup_key；同日重扫同一 overdue 单只发一次。 */
    int scanSelectOverdue();

    /** * AC-TEAM-08：到期无人应标自动 EXPIRED + 项目置 TEAMING（待组队挂起）。 */
    /** * 扫描范围：expireAt < now 且 status=OPEN 且无 PENDING/ACCEPTED 应标。 */
    /** * */
    /** * @return 关闭单数 */
    int scanExpireNoResponse();

}
