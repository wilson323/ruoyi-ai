package org.ruoyi.ipd.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.ruoyi.common.satoken.utils.LoginHelper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 包级静态 IDOR 守卫——基于 W5-E-2.1~2.4 四大 P0 修复的成熟模式抽取（W5-E-Guard）。
 *
 * <p>三段式校验：actor 必填 → 目标归属/租户一致 → 角色豁免；任何一环不满足即 fail-closed。
 * 供所有接受 projectId / personId 等敏感参数的 Service 复用，为新增方法提供统一默认范式，
 * 消除逐 Service 手写校验的遗漏面（W5-E IDOR 全仓扫第 9 层根因：新方法无 actor 校验默认范式 +
 * 缺包级静态 helper——19 个 P0 Service / 78 个敏感参数方法中 69% 存在风险）。
 *
 * <p>与四大 P0 修复的对应关系：
 * <ul>
 *   <li>守卫 1 {@link #requireAuthenticated(IpdActor)}——W5-E-2.2/2.3/2.4 的同名私有助手同款
 *       （service 层不信任 controller 必传，防御性兜底）</li>
 *   <li>守卫 2 {@link #requireSelfOrSuperAdmin(IpdActor, Long)}——W5-E-2.1 changePassword
 *       「本人或 SUPER_ADMIN 运维豁免」模式</li>
 *   <li>守卫 3 {@link #requireProjectMemberOrSuperAdmin(IpdActor, Long, ProjectMemberMapper, ProjectMapper)}
 *       ——W5-E-2.4 BidResponse 三分支模式 + W4-Security KpiSharedCollectionService 的
 *       租户一致性（{@link LoginHelper#getTenantId()}，与多租户拦截器同容忍口径）与
 *       在职成员判定（{@code exit_date IS NULL}，KpiSharedCollectionService 同口径）</li>
 *   <li>守卫 4 {@link #requireRoleOrSuperAdmin(IpdActor, String)}——W5-E-2.2 leaderDecision
 *       角色门模式（角色校验先于任何 DB 读）</li>
 *   <li>守卫 5 {@link #requireSuperAdmin(IpdActor)}——W5-E-2.2 adminDecision /
 *       W5-E-2.3 autoScan 的 SUPER_ADMIN 方法内硬校验模式（与 Controller requireAdmin 同严）</li>
 *   <li>守卫 6 {@link #assertSameGroupIpd(IpdActor, Long)}——ProductService.bindProject / unbindProject
 *       「actor 归属产品组 vs. 项目主组」同款横向越权守卫（commit 后台安全审查 W28-2 cross-group-idor 闭环）。
 *       <b>统一口径 LEAD-GROUP-01：全仓跨组写判定唯一真源，GROUP_LEADER 无跨组豁免（同 MARKET_PM / RD_PM），
 *       唯一豁免是 SUPER_ADMIN。写路径禁止本地手写角色分支。</b></li>
 * </ul>
 *
 * <p>设计约定：
 * <ul>
 *   <li>{@link IpdActor} 是 record 且不携带 tenantId，租户上下文取
 *       {@link LoginHelper#getTenantId()}（W4-Security 决策 1：null/空串视为「未启用租户隔离」放行）</li>
 *   <li>SUPER_ADMIN 角色判定沿用 {@code "SUPER_ADMIN".equals(actor.role())} 字面口径
 *       （IpdActor record 无方法扩展，与四大 P0 修复及 KpiSharedCollectionService 同款）</li>
 *   <li>FORBIDDEN 文案统一「无权访问/无权操作」，不区分资源不存在与无权限——不泄漏存在性
 *       （W4-Security 决策 3）</li>
 * </ul>
 */
public final class IpdIdorGuard {

    /** 超管角色字面量（与四大 P0 修复口径一致，IpdActor record 无方法扩展） */
    private static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    private IpdIdorGuard() {
    }

    /**
     * 守卫 1：actor 必填（W5-E-2.3 requireAuthenticated 模式）。
     *
     * <p>service 层不信任 controller 必传，防御性兜底：actor == null 或 id == null 一律按未登录拒绝。
     *
     * @param actor 服务端会话身份（IpdPermission.requireXxx() 捕获值）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#UNAUTHORIZED} 当 actor 为 null 或 actor.id() 为 null
     */
    public static void requireAuthenticated(IpdActor actor) {
        if (actor == null || actor.id() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.UNAUTHORIZED, "未登录");
        }
    }

    /**
     * 守卫 2：本人或超管（W5-E-2.1 changePassword 模式）。
     *
     * <p>仅允许 actor.id() == personId 本人操作自身数据；SUPER_ADMIN 享运维豁免（如超管重置密码场景）。
     *
     * @param actor    服务端会话身份
     * @param personId 目标人员 ID
     * @throws IpdBusinessException {@link ApiV1ErrorCode#PARAM_INVALID} 当 personId 为 null；
     *                              {@link ApiV1ErrorCode#UNAUTHORIZED} 当 actor 缺失；
     *                              {@link ApiV1ErrorCode#FORBIDDEN} 当非本人且非超管
     */
    public static void requireSelfOrSuperAdmin(IpdActor actor, Long personId) {
        requireAuthenticated(actor);
        if (personId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "personId 不能为空");
        }
        if (actor.id().equals(personId)) {
            return;
        }
        if (ROLE_SUPER_ADMIN.equals(actor.role())) {
            return;
        }
        throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作他人数据");
    }

    /**
     * 守卫 3：项目在职成员或超管（W5-E-2.4 BidResponse 三分支模式）。
     *
     * <p>放行分支（按序短路）：SUPER_ADMIN 豁免（不触达任何 DB 读）→ 项目存在 + 租户一致 +
     * 在职 ProjectMember（projectId + personId 匹配且 {@code exit_date IS NULL}，
     * 与 KpiSharedCollectionService:445-452 同口径，排除已退出成员）。
     * 其余一律 FORBIDDEN——项目不存在与无权限统一文案，不泄漏存在性（fail-closed）。
     *
     * @param actor               服务端会话身份
     * @param projectId           目标项目 ID
     * @param projectMemberMapper 成员 Mapper（只读查询，由调用方构造注入）
     * @param projectMapper       项目 Mapper（只读查询，由调用方构造注入）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#PARAM_INVALID} 当 projectId 为 null；
     *                              {@link ApiV1ErrorCode#UNAUTHORIZED} 当 actor 缺失；
     *                              {@link ApiV1ErrorCode#FORBIDDEN} 当项目不存在 / 跨租户 / 非在职成员
     */
    public static void requireProjectMemberOrSuperAdmin(IpdActor actor, Long projectId,
            ProjectMemberMapper projectMemberMapper, ProjectMapper projectMapper) {
        requireAuthenticated(actor);
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        if (ROLE_SUPER_ADMIN.equals(actor.role())) {
            return;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            // 统一 FORBIDDEN 文案——不区分「项目不存在」与「无权限」，避免存在性 oracle
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该项目");
        }
        requireTenantMatch(currentTenantId(), project);
        Long count = projectMemberMapper.selectCount(Wrappers.<ProjectMember>lambdaQuery()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getPersonId, actor.id())
            .isNull(ProjectMember::getExitDate));
        if (count == null || count == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "非项目成员，无权访问");
        }
    }

    /**
     * 守卫 8：组长对本项目所属产品组的访问权（主组组长 ∪ 协同组组长）。
     *
     * <p>2026-10-03 收口。它服务的是这样一类动作：<b>责任人是组长，而组长按定义不是项目成员</b>。
     * 实测两条数据模型事实（schema 快照原文）：
     * <ul>
     *   <li>{@code product_groups.leader_person_id} 是**标量列**（不是关联表）⇒ 一个产品组只有一个组长；</li>
     *   <li>{@code project_members.role} 的注释写死 {@code MARKET_PM|RD_PM} ⇒ <b>组长不在项目成员表里</b>。</li>
     * </ul>
     * 因此守卫 3（在职项目成员）对这类动作会**把两位合法责任人都拒掉**，守卫 6（{@code assertSameGroupIpd}）
     * 只放行主组组长、**挡掉协同组组长**——两者都会把功能弄坏，而不是修好安全问题。
     *
     * <p>放行口径（与 {@code KpiSharedCollectionService.requireProjectAccess} 同款，本方法即其唯一真源）：
     * 超管，或「actor 所属组 = 项目主组」，或「actor 所属组 = 该项目任一**在职**成员所属组」。
     * 第二条覆盖协同组：研发PM 是主组之外的在职成员，其所在组即协同组。
     *
     * <p>本守卫**不含角色门**——各调用方的角色要求不同（如 K01-K04 归集与双签确认都是
     * {@code GROUP_LEADER|SUPER_ADMIN}，但表述不同），角色门由调用方自己先判。
     *
     * @param actor 服务端会话身份
     * @param projectId 项目 ID
     * @throws IpdBusinessException {@code PARAM_INVALID} projectId 为空；{@code UNAUTHORIZED} actor 缺失；
     *                              {@code FORBIDDEN} 项目不存在、租户不一致、或不属于该项目主组/协同组
     */
    public static void requireProjectGroupAccess(IpdActor actor, Long projectId,
            ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper, PersonMapper personMapper) {
        requireAuthenticated(actor);
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        if (ROLE_SUPER_ADMIN.equals(actor.role())) {
            return;
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null) {
            // 统一 FORBIDDEN 文案——不区分「项目不存在」与「无权限」，避免存在性 oracle
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该项目");
        }
        requireTenantMatch(currentTenantId(), project);
        if (project.getMainGroupId() != null && project.getMainGroupId().equals(actor.groupId())) {
            return;
        }
        if (memberGroupIds(project.getId(), projectMemberMapper, personMapper).contains(actor.groupId())) {
            return;
        }
        // 文案保留「产品组」三字：AC-KPI-20 与既有验收测试按此断言
        throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "不能跨产品组操作该项目数据");
    }

    /**
     * 取该项目全部**在职**成员（{@code exit_date IS NULL}）所属的产品组集合。
     *
     * <p>与 {@code KpiSharedCollectionService.activeMembers} 同口径，含 {@code MARKET_PM|RD_PM} 角色过滤
     * ——该过滤是 schema 注释写死的既有约定（{@code project_members.role} 注释「MARKET_PM|RD_PM（固定不可跨 B7）」），
     * 补上它才能保证本守卫接管后归集路径行为逐字不变。角色非空的 member 才参与取组；
     * person 缺失或 groupId 缺失的成员跳过（不把 null 放进集合，避免「无组 actor 因集合含 null 而被放行」）。
     */
    private static Set<Long> memberGroupIds(Long projectId, ProjectMemberMapper projectMemberMapper,
            PersonMapper personMapper) {
        List<ProjectMember> members = projectMemberMapper.selectList(Wrappers.<ProjectMember>lambdaQuery()
            .eq(ProjectMember::getProjectId, projectId)
            .isNull(ProjectMember::getExitDate)
            .in(ProjectMember::getRole, List.of("MARKET_PM", "RD_PM")));
        Set<Long> groupIds = new LinkedHashSet<>();
        if (members == null) {
            return groupIds;
        }
        for (ProjectMember member : members) {
            Person person = member.getPersonId() == null ? null : personMapper.selectById(member.getPersonId());
            if (person != null && person.getGroupId() != null) {
                groupIds.add(person.getGroupId());
            }
        }
        return groupIds;
    }

    /**
     * 守卫 4：指定角色或超管（W5-E-2.2 leaderDecision 角色门模式）。
     *
     * <p>角色匹配或 SUPER_ADMIN 豁免放行；角色校验先于任何 DB 读（冒充者在触碰数据前即被拒）。
     *
     * @param actor        服务端会话身份
     * @param requiredRole 要求的角色字面量（如 {@code "GROUP_LEADER"}）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#PARAM_INVALID} 当 requiredRole 为空白；
     *                              {@link ApiV1ErrorCode#UNAUTHORIZED} 当 actor 缺失；
     *                              {@link ApiV1ErrorCode#FORBIDDEN} 当角色不匹配且非超管
     */
    public static void requireRoleOrSuperAdmin(IpdActor actor, String requiredRole) {
        requireAuthenticated(actor);
        if (requiredRole == null || requiredRole.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "requiredRole 不能为空");
        }
        if (ROLE_SUPER_ADMIN.equals(actor.role()) || requiredRole.equals(actor.role())) {
            return;
        }
        throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权执行该操作");
    }

    /**
     * 守卫 5：仅超管（W5-E-2.2 adminDecision / W5-E-2.3 autoScan 硬校验模式）。
     *
     * <p>service 方法内兜底与 Controller requireAdmin 同严（防注解/Catalog 漂移导致资金、
     * 软删等高危操作失防）；校验先于任何 DB 读。
     *
     * @param actor 服务端会话身份
     * @throws IpdBusinessException {@link ApiV1ErrorCode#UNAUTHORIZED} 当 actor 缺失；
     *                              {@link ApiV1ErrorCode#FORBIDDEN} 当非 SUPER_ADMIN
     */
    public static void requireSuperAdmin(IpdActor actor) {
        requireAuthenticated(actor);
        if (!ROLE_SUPER_ADMIN.equals(actor.role())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "仅超管可执行");
        }
    }

    /**
     * 守卫 6：操作人归属组与目标业务对象归属组一致性。
     * 详见 javadoc 守卫列表。W28-2 commit 后台安全审查 high cross-group-idor 闭环。
     *
     * <p><b>统一口径 LEAD-GROUP-01（组长跨组不放行）</b>：本方法是全仓「跨组写操作判定」的唯一真源。
     * 依据 BR-ORG-06 三层权限矩阵（v3 主 Prompt L408-421）——产品组长在「查看项目 / 编辑项目 /
     * 删除初审 / 导出审计日志」四行一律限定「本组」，无一行授予跨组，「全部」只属超级管理员；
     * BR-ORG-05 授予组长的跨组能力仅限<b>可查看</b>；G-09 / BR-ORG-04 明确「不做代理组长 / 代审人机制」。
     *
     * <p>因此：<b>GROUP_LEADER 在此与 MARKET_PM / RD_PM 同口径——跨组一律拒绝，无豁免</b>。
     * 全仓任何写路径需要跨组判定时必须调用本方法，禁止本地手写角色分支
     * （历史反例：{@code BidP231Validator.assertProjectVisible} 曾对组长无条件放行，已收口至此处）。
     */
    public static void assertSameGroupIpd(IpdActor actor, Long objectGroupId) {
        requireAuthenticated(actor);
        if (ROLE_SUPER_ADMIN.equals(actor.role())) {
            return;
        }
        if (actor.groupId() == null || !actor.groupId().equals(objectGroupId)) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权操作");
        }
    }

    /**
     * 守卫 7：项目与会话租户一致性（供 service 层直用，免跨 Service 静态调用）。
     *
     * <p>沿用 {@link #requireTenantMatch} 的容忍口径：会话租户为 null/空（未启用隔离、
     * 异步线程、纯 JVM 单测）或项目租户缺失 → 放行；两侧均非空且不一致 → FORBIDDEN。
     * 单企业私有部署下本守卫恒真，它的价值在于“一旦真的开多租户，跨租户接管会立即被拒”。
     *
     * @param project 已加载的项目（可为 null，null 时不在此报错，由调用方的存在性校验负责）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#FORBIDDEN} 当租户两侧均非空且不一致
     */
    public static void requireProjectTenantMatch(Project project) {
        requireTenantMatch(currentTenantId(), project);
    }

    /**
     * 跨租户守卫（W4-Security 决策 1，KpiSharedCollectionService.requireTenantMatch 同口径）。
     *
     * <p>租户匹配或租户上下文缺失（null/空串，即租户隔离未启用或未登录场景）→ 放行；
     * 会话租户与项目租户两侧均非空且不一致 → FORBIDDEN（不区分 null/不匹配，统一文案）。
     * 包级可见以便单测直测。
     *
     * @param currentTenant 当前会话租户（可为 null 或空串）
     * @param project       已加载项目（非 null）
     * @throws IpdBusinessException {@link ApiV1ErrorCode#FORBIDDEN} 当两侧租户均非空且不一致
     */
    static void requireTenantMatch(String currentTenant, Project project) {
        if (currentTenant != null && !currentTenant.isEmpty()
            && project != null && project.getTenantId() != null
            && !currentTenant.equals(project.getTenantId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN, "无权访问该项目");
        }
    }

    /**
     * 当前会话租户（W4-Security 决策 1 容忍口径：IpdActor record 不携带 tenantId）。
     *
     * <p>{@code LoginHelper.getTenantId()} 在租户禁用或未登录场景下返回 null 或空串
     * （getExtra 内部吞异常）；此处再包一层 try-catch 防御 LoginHelper 未来演化抛出，
     * 无 Sa-Token 上下文（纯 JVM 单测 / 异步线程）一律按「租户上下文缺失」处理。
     */
    static String currentTenantId() {
        try {
            return LoginHelper.getTenantId();
        } catch (Exception ex) {
            return null;
        }
    }
}
