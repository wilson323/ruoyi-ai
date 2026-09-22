package org.ruoyi.ipd.service;

import com.baomidou.lock.annotation.Lock4j;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.dto.ProjectListItemView;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * IProjectService 接口（paiban-05 接口化，实现见 {@link ProjectService}）。
 */
public interface IProjectService {

    /** R149 B2：PM 维度项目列表角色过滤（在职 MARKET_PM/RD_PM）所需 mapper。 */
    /** * 走 setter 模式（仿 BonusPoolService.setProjectMemberMapper）， */
    /** * nullable 兼容 P122AcceptanceTest / P131DatabaseIntegrationTest 等 */
    /** * 旧 10 参构造器入口（不破坏既有兄弟测试）。 */
    void setProjectMemberMapper(ProjectMemberMapper projectMemberMapper);

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** * 创建项目（P1-2.1：四基准 + 模板/市场必填；系数默认/区间；状态强制 DRAFT）。 */
    /** * <p>2026-09-11 owner 拍板：主组可选——客户端未选时服务端权威自动归属 */
    /** * {@code fallbackMainGroupId}（页08 传操作人所在产品组，即 BR-ORG-01 字面语义）， */
    /** * 避免 null mainGroupId 污染下游 SEC-02 按组归属校验链（changeStatus / */
    /** * updateBaselines / advanceStage / bindProject / CoefficientChange / Handover / */
    /** * LaunchDateChange / RequirementStateMachine 全链 assertSameGroupIpd）。 */
    /** * <p>对 {@code uk_projects_code} 冲突做独立事务重试：READ_COMMITTED 下 */
    /** * synchronized(nextCode) 无法覆盖「取号→提交」窗口，HTTP 并发会撞号。 */
    /** * */
    /** * @param project             客户端白名单字段已映射的实体 */
    /** * @param operatorId          操作人 */
    /** * @param fallbackMainGroupId 客户端未选主组时的缺省归属组 */
    /** * @return 落库后的项目（含编码与 CONCEPT/DRAFT） */
    Project create(Project project, Long operatorId, Long fallbackMainGroupId);

    /** * 状态机迁移（非法迁移拒绝）；归档不可再迁出。 */
    /** * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免）。 */
    /** * ZK-IPD §二.10：归档后只读下沉 service 层——归档状态禁一切编辑类状态变更。 */
    /** * */
    /** * @param projectId    项目 ID */
    /** * @param target       目标状态 */
    /** * @param operatorId   操作人 ID（来自会话） */
    /** * @param actorGroupId 操作人所属产品组（横向越权防护用） */
    /** * @param actorRole    操作人角色（SUPER_ADMIN 豁免判断） */
    Project changeStatus(
        Long projectId,
        String target,
        Long operatorId,
        Long actorGroupId,
        String actorRole
    );

    /** * P1-2.2：DRAFT 期内可改四基准；立项后锁定。 */
    /** * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免） */
    /** *                  + before/after 审计（4 个基准字段值变化可追溯）。 */
    /** * */
    /** * @param projectId    项目 ID */
    /** * @param patch        含四基准字段的补丁 */
    /** * @param operatorId   操作人 ID（来自会话） */
    /** * @param actorGroupId 操作人所属产品组 */
    /** * @param actorRole    操作人角色 */
    /** * @return 更新后项目 */
    Project updateBaselines(
        Long projectId,
        Project patch,
        Long operatorId,
        Long actorGroupId,
        String actorRole
    );

    /** * 阶段推进：门禁校验（BR-IPD-06，P1-5 GateEngine 接管）+ LAUNCH 前置上市日期（BR-IPD-08）。 */
    /** * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免） */
    /** *                  + 审计含 prior + new currentStage。 */
    /** * */
    /** * <p>P2-6.2 强化：跳阶前先查需求变更单（{@link RequirementChangeService#hasOpenChange}）， */
    /** * 存在未闭环变更单（DRAFT 或 PENDING_SIGN）⇒ 拒绝推进，AC-GATE-11 跳阶拒绝语义。 */
    /** * 拒绝路径写 STAGE_GUARD_BLOCKED 审计（before/after 镜像 + operatorId + reason）， */
    /** * 不抛 GATE_NOT_PASSED 而抛 STATE_CONFLICT 区分「未闭环变更」与「Gate 要素不齐」。 */
    Project advanceStage(
        Long projectId,
        Long operatorId,
        Long actorGroupId,
        String actorRole
    );

    /** * 阶段推进：门禁校验（BR-IPD-06，P1-5 GateEngine 接管）+ LAUNCH 前置上市日期（BR-IPD-08）。 */
    /** * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免） */
    /** *                  + 审计含 prior + new currentStage。 */
    /** * */
    /** * <p>P2-6.2 强化：跳阶前先查需求变更单（{@link RequirementChangeService#hasOpenChange}）， */
    /** * 存在未闭环变更单（DRAFT 或 PENDING_SIGN）⇒ 拒绝推进，AC-GATE-11 跳阶拒绝语义。 */
    /** * 拒绝路径写 STAGE_GUARD_BLOCKED 审计（before/after 镜像 + operatorId + reason）， */
    /** * 不抛 GATE_NOT_PASSED 而抛 STATE_CONFLICT 区分「未闭环变更」与「Gate 要素不齐」。 */
    Project getById(Long id);

    /** * 阶段推进：门禁校验（BR-IPD-06，P1-5 GateEngine 接管）+ LAUNCH 前置上市日期（BR-IPD-08）。 */
    /** * R8X-CONT-1 P0-1：加 actor.groupId == project.mainGroupId 横向越权防护（SUPER_ADMIN 豁免） */
    /** *                  + 审计含 prior + new currentStage。 */
    /** * */
    /** * <p>P2-6.2 强化：跳阶前先查需求变更单（{@link RequirementChangeService#hasOpenChange}）， */
    /** * 存在未闭环变更单（DRAFT 或 PENDING_SIGN）⇒ 拒绝推进，AC-GATE-11 跳阶拒绝语义。 */
    /** * 拒绝路径写 STAGE_GUARD_BLOCKED 审计（before/after 镜像 + operatorId + reason）， */
    /** * 不抛 GATE_NOT_PASSED 而抛 STATE_CONFLICT 区分「未闭环变更」与「Gate 要素不齐」。 */
    List<Project> list(String keyword);

    /** * P1-9.2：项目列表（含 scenarioDaysRemaining 派生字段 + 临界告警标记）。 */
    /** * <p>R149 B2 升级：新增 {@link #listWithScenario(String, IpdActor)} 按角色硬过滤版本； */
    /** * 本单参签名 <b>保留向后兼容</b>（P192AcceptanceTest 等历史测试入口）， */
    /** * 内部委派给双参版本并传 {@code actor=null}（等价于全量，不做角色过滤）。 */
    /** * */
    /** * @param keyword 项目名关键字 */
    /** * @return 列表视图（含派生字段；全量） */
    List<ProjectListItemView> listWithScenario(String keyword);

    /** * R149 B2：项目列表（按角色硬过滤 + 派生字段）。 */
    /** * */
    /** * <p>角色过滤矩阵（前后端对齐）： */
    /** * <ul> */
    /** *   <li>SUPER_ADMIN：全量（无过滤）</li> */
    /** *   <li>GROUP_LEADER：本组（{@code projects.main_group_id = actor.groupId()}）</li> */
    /** *   <li>MARKET_PM / RD_PM：本人负责的（{@code project_members.person_id = actor.id() */
    /** *       AND role IN (MARKET_PM, RD_PM) AND exit_date IS NULL AND del_flag='0'}）</li> */
    /** *   <li>其他角色 / null actor：空列表（安全默认，避免泄漏全量）</li> */
    /** * </ul> */
    /** * */
    /** * <p>提示横幅由前端维持（前端不改）；本方法只负责<b>权威服务端硬过滤</b>， */
    /** * 即使前端绕过横幅直接调接口也只能取到授权范围内的项目。 */
    /** * */
    /** * @param keyword 项目名关键字（可空） */
    /** * @param actor   当前操作人；null ⇒ 等价于全量（向后兼容） */
    /** * @return 列表视图（含 scenarioDaysRemaining / critical 派生字段） */
    List<ProjectListItemView> listWithScenario(String keyword, IpdActor actor);

    /** * P1-9.2：扫描临界（remaining ≤ 3）的 LEGACY 项目，通知 MARKET_PM + PRODUCT_LEADER。 */
    /** * 由 CronTaskService / 调度任务调用（每天 02:00）。 */
    int scanLegacyCriticalProjects();

    /** * PRJ-YYYY-NNN：取当年最大序号 +1。 */
    /** * <p>PERF-01 / RISK-01：双层保护—— */
    /** * <ul> */
    /** *   <li>{@code @Lock4j}：Redisson 跨 JVM（生产多实例经 Spring 代理生效）</li> */
    /** *   <li>{@code synchronized}：同 JVM 兜底（单测 {@code new ProjectService()} 无 AOP 时仍原子）</li> */
    /** *   <li>DB：{@code uk_projects_code(code)} UNIQUE KEY 最终兜底</li> */
    /** * </ul> */
    String nextCode();

}
