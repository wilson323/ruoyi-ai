package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.domain.GateArbitration;
import org.ruoyi.ipd.domain.GateReview;
import org.ruoyi.ipd.domain.GateReviewObserver;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.GateArbitrationMapper;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.mapper.GateReviewMapper;
import org.ruoyi.ipd.mapper.GateReviewObserverMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * IGateReviewService 接口（paiban-05 接口化，实现见 {@link GateReviewService}）。
 */
public interface IGateReviewService {

    /** GateReview 实体类型（与 DefaultStateMachineGuard.registerRule 约定一致） */
    void setStateMachineGuard(StateMachineGuard stateMachineGuard);

    /** R24 接线：注册 postCommit 副作用（事务提交后触发）。 */
    /** * P0-10.23 补齐（R30 生产就绪）：项目维度 Gate 列表（原型 /api/key-gates?projectId= 的正式替代）。 */
    /** * */
    /** * <p>按 projectId 查 gates 表未删行，id 降序（新创建在前）；空列表 = 项目尚无 Gate */
    /** * （真实空态，不造假数据）。前端 gates 页由此列表替代手输 Gate 编号。 */
    List<Gate> listByProject(Long projectId);

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** 签署：写入本轮 GateReview，按签署矩阵推进 Gate 终态。 */
    GateReview sign(Long gateId, String decision, String opinion, IpdActor actor);

    /** 双签视图：终态或超管全揭示；在途仅见己方结论与"对方已提交"标志（AC-GATE-03/04）。 */
    Map<String, Object> view(Long gateId, IpdActor actor);

    /** 重新发起评审（AC-GATE-06/07/07b；BR-GATE-05 不限次数）：round+1、期限重算。 */
    /** * <p>第 3 轮起通知双方产品组长列席；第 5 轮起通知超管介入。 */
    Gate reopen(Long gateId, IpdActor actor);

    /** 超时弃权扫描（AC-GATE-08，BR-GATE-04 D17）：双签 Gate 超期未签方自动补 ABSTAIN 行。 */
    /** * <p>三天规则按主导方区分：主导方已签 APPROVE ⇒ 按主导方意见执行放行； */
    /** * 主导方弃权 ⇒ 无放行依据；两人均未签 ⇒ 双 ABSTAIN 转 ABSTAINED_TIMEOUT（不得无依据放行）。 */
    /** * 单签 Gate（G2/3/4）无对方弃权概念，不折算，超期由 scanRemind 催办。 */
    int scanTimeout(IpdActor operator);

    /** 期限前 1 天提醒扫描（AC-GATE-09）：双签 Gate 提醒未签方；单签 Gate 提醒主导方。 */
    int scanRemind(IpdActor operator);

    /** 组长仲裁（AC-GATE-10 中段）：仅冲突双方所在组的产品组长，意见 APPROVE|REJECT。 */
    /** * <p>组长意见齐备后：一致 ⇒ 仲裁结果知会双方；不一致 ⇒ 自动升级超管终裁。 */
    GateArbitration arbitrate(Long gateId, String decision, String opinion, IpdActor actor);

    /** 超管终裁（AC-GATE-10 尾段）：仅两组长意见不一致（已升级）后可提交， */
    /** * 终裁结果写入项目审计日志永久归档。Gate 终态不因终裁翻转（变更须 reopen 新轮）。 */
    GateArbitration finalRuling(Long gateId, String decision, String opinion, IpdActor actor);

    /** * 邀请列席人员（MEDIUM-1.3）：仅超管/组长可邀请； */
    /** * 同 gate+observer 唯一约束兜底幂等（重复邀请不报错，返回既有行）。 */
    /** * */
    /** * @param gateId      Gate 实例 */
    /** * @param observerIds 列席人 personId 列表 */
    /** * @param role        列席角色（5 类之一） */
    /** * @param actor       邀请人（必须 SUPER_ADMIN/GROUP_LEADER） */
    /** * @return 邀请行数（去重后） */
    int inviteObservers(
        Long gateId,
        List<Long> observerIds,
        String role,
        IpdActor actor
    );

    /** * 列席人提交意见（MEDIUM-1.3）：仅 observer 本人可写自己的一行； */
    /** * 不入主审投票（不动 gate_reviews）。 */
    GateReviewObserver recordOpinion(Long gateId, Long observerId, String opinion, IpdActor actor);

    /** * 查 gate 全部列席人员 + 意见（MEDIUM-1.3）：仅 PRODUCT_LEADER/GROUP_LEADER/SUPER_ADMIN 可见。 */
    List<GateReviewObserver> listObservers(Long gateId, IpdActor actor);

    /** 延长签署期限（AC-GATE-21）：仅超管、仅在签 Gate；最多 3 次，第 4 次拒绝。 */
    Gate extendDeadline(Long gateId, int days, IpdActor actor);

}
