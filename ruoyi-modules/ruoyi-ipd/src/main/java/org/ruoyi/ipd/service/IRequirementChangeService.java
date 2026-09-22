package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.domain.RequirementChange;
import org.ruoyi.ipd.mapper.RequirementChangeMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * IRequirementChangeService 接口（paiban-05 接口化，实现见 {@link RequirementChangeService}）。
 */
public interface IRequirementChangeService {

    /** * ROOT-R3-P0-2：Spring 注入 StateMachineGuard（nullable 兼容旧测试）。 */
    /** * 测试场景可通过此 setter 注入 mock；运行时由 Spring 装配。 */
    void setStateMachineGuard(StateMachineGuard stateMachineGuard);

    /** * ROOT-R3-P0-2：注册 postCommit 副作用（事务提交后触发，避免回滚后污染）。 */
    /** * 无守卫注入时降级 no-op；无事务上下文时直接执行（向后兼容测试场景）。 */
    /** * 创建变更单（草稿状态）。AC-REQ-08：需求转需求变更单 ⇒ 可生成。 */
    /** * 引用原需求 ID，影响评估快照由调用方提供（beforeSnapshot / afterSnapshot JSON）， */
    /** * 包含范围/成本/时限/质量四维度。 */
    /** * */
    /** * <p>P2-6.2 强化：影响评估四维度在 create 阶段即强制校验 —— beforeSnapshot / afterSnapshot */
    /** * 必须为合法 JSON 且同时包含「范围/成本/时限/质量」四个键，任一缺失 ⇒ PARAM_INVALID。 */
    /** * 提交阶段（{@link #submit}）的同口径校验作为兜底双保险（防御 create 之后回填快照路径）。 */
    RequirementChange create(RequirementChange change, IpdActor actor);

    /** * 提交双签：DRAFT ⇒ PENDING_SIGN。 */
    /** * 提交校验影响评估四维度（范围/成本/时限/质量）全部非空——任一缺失拒绝。 */
    /** * */
    /** * <p>P2-6.2 强化：复用 {@link #validateFourDimensionalSnapshot} 作为兜底双保险。 */
    /** * 正常路径下 create 已校验；本方法在 create→submit 期间快照被外部覆盖/篡改时兜底拒绝， */
    /** * 防止「四维度在 create 后被偷换为留白快照」绕过校验。 */
    RequirementChange submit(Long id, IpdActor actor);

    /** * 双签：市场PM + 研发PM 双方均 APPROVE ⇒ APPROVED； */
    /** * 任一 REJECT ⇒ REJECTED。签名记录聚合在 signatures 字段（MARKET_PM=APPROVE;RD_PM=APPROVE）。 */
    /** * */
    /** * <p>BR-GATE-07 关联：APPROVED 时回写需求池状态为"已采纳"（AC-GATE-12）， */
    /** * 显式 REJECT 不得超时绕过（AC-GATE-11：未闭环拒绝跳阶由 GateEngine 通过 hasOpenChange 拦截）。 */
    RequirementChange sign(Long id, String decision, String opinion, IpdActor actor);

    /** * KPI：需求变更率 = 变更单数 ÷ 总需求数（AC-KPI-14，无需手工填）。 */
    /** * */
    /** * <p>SEC-REV-REQ-CHANGE-03：中危 missing-tenant-scope 修复 —— 强制要求传入 actor，按 actor 角色+group 限定 KPI 范围： */
    /** * <ul> */
    /** *   <li>SUPER_ADMIN：可看全局（不过滤）</li> */
    /** *   <li>其他内部角色：按 actor.groupId() 限定本人所在组的 KPI 视角</li> */
    /** *   <li>传入 null actor → 抛 UNAUTHORIZED（拒绝 null 旁路）</li> */
    /** * </ul> */
    /** * 旧实现 {@code selectCount(null)} 完全绕过租户/分组过滤，被列为中危漏洞。 */
    double kpiChangeRate(IpdActor actor);

    /** 兼容旧测试：默认走 SUPER_ADMIN 视角（无 group 限定）。生产代码应使用 {@link #kpiChangeRate(IpdActor)}。 */
    double kpiChangeRate();

    /** * 阶段门禁集成（AC-GATE-11 跳阶拒绝）：GateEngine 跳阶前调本方法， */
    /** * 存在未闭环变更单 ⇒ 抛 GATE_NOT_PASSED，提示"存在未完成变更单"。 */
    /** * 返回当前未闭环变更单数量（>=1 时阻断）。 */
    int countOpenByProject(Long projectId);

    /** 详情：返回含影响快照的双签进度视图。 */
    Map<String, Object> detail(Long id);

    /** 项目内变更单列表（按状态过滤供 P2-6.2 阶段门禁未闭环检测）。 */
    IPage<RequirementChange> listByProject(
        int pageNo,
        int pageSize,
        Long projectId,
        String status
    );

    /** * 列出项目内未闭环的变更单（status ∈ {DRAFT, PENDING_SIGN}）。 */
    /** * 供 P2-6.2 阶段门禁在跳阶前调用，AC-GATE-11 拒绝语义。 */
    List<RequirementChange> listOpenByProject(Long projectId);

    /** 是否存在项目内未闭环变更单（供阶段门禁快速拦截，AC-GATE-11）。 */
    boolean hasOpenChange(Long projectId);

}
