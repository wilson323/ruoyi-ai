package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.NegativeFeedback;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.dto.NegativeFeedbackCreateReq;
import org.ruoyi.ipd.dto.NegativeFeedbackDecisionReq;
import org.ruoyi.ipd.dto.NegativeFeedbackView;
import org.ruoyi.ipd.mapper.NegativeFeedbackMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * INegativeFeedbackService 接口（paiban-05 接口化，实现见 {@link NegativeFeedbackService}）。
 */
public interface INegativeFeedbackService {

    /** * 录入负反馈（DRAFT 创建）。 */
    /** * */
    /** * <p>AC-INC-40 重复检查：同项目同 triggerType 已 EXECUTED → NF_REENTRY_NOT_ALLOWED。 */
    /** * <p>SEC-REV-round3 Bug#6（中危 TOCTOU-dedup-bypass）：service selectCount 与 DB 唯一索引 */
    /** * 维度不同会导致并发插入绕过 service 检查但被 DB 拒抛 raw DuplicateKeyException； */
    /** * 修复：service 镜像索引维度（count 任意 del_flag=0）+ catch DuplicateKeyException 翻 NF_REENTRY_NOT_ALLOWED。 */
    /** * <p>SEC-REV-round3 Bug#4（高危 horizontal-privilege）：录入前按项目 group 校验 actor 归属。 */
    NegativeFeedback create(NegativeFeedbackCreateReq req, IpdActor actor);

    /** * 提交认定（DRAFT → PENDING_DECISION）。 */
    NegativeFeedback submit(Long id, IpdActor actor);

    /** * 组长 / 超管认定（PENDING_DECISION → EXECUTED；REJECT → REJECTED）。 */
    /** * */
    /** * <p>decide APPROVE 后通过 NotificationService 发 NEGATIVE_FEEDBACK_EXECUTED 通知双 PM。 */
    NegativeFeedback decide(Long id, NegativeFeedbackDecisionReq req, IpdActor actor);

    /** * 解除（EXECUTED → LIFTED；恢复 bonusEligible）。 */
    /** * */
    /** * <p>AC-INC-40：解除动作不可逆（lift 后再触发新事件需要重新走 create 流程并通过唯一索引）。 */
    NegativeFeedback lift(Long id, NegativeFeedbackDecisionReq req, IpdActor actor);

    /** * 解除（EXECUTED → LIFTED；恢复 bonusEligible）。 */
    /** * */
    /** * <p>AC-INC-40：解除动作不可逆（lift 后再触发新事件需要重新走 create 流程并通过唯一索引）。 */
    NegativeFeedback getById(Long id, IpdActor actor);

    /** 兼容旧测试口（无 actor）；详情默认放行——controller 路径已走 requireInternal。 */
    NegativeFeedback getById(Long id);

    /** * 项目下当前生效中的负反馈记录（EXECUTED 且未解除）。 */
    /** * <p>SEC-REV-round3 Bug#4：actor 必传，非超管需校验项目归属。 */
    List<NegativeFeedback> effectiveByProject(Long projectId, IpdActor actor);

    /** 兼容旧测试口 */
    List<NegativeFeedback> effectiveByProject(Long projectId);

    /** 兼容旧测试口 */
    List<NegativeFeedback> listByProject(Long projectId, IpdActor actor, String status);

    /** 兼容旧测试口 */
    List<NegativeFeedback> listByProject(Long projectId, String status);

}
