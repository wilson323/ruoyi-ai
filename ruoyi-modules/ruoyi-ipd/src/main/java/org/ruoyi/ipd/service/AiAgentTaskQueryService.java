package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.IpdResources;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdIdorGuard;
import org.ruoyi.ipd.vo.AiAgentTaskView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * R221 ai_agent_tasks 只读查询服务（R232 P2-04 任务卡数据通道）。
 *
 * <p>最小面两查询：按 taskId 单查（待办直达/跨设备恢复）+ 按项目列表（任务卡时间线卡片组）。
 *
 * <p><b>纪律</b>：本类零写入——ai_agent_tasks 写路径唯一入口仍是
 * {@link AiExecutionTrigger}（插入）与引擎收尾/复审钩子（条件 UPDATE 翻态），
 * 本类只 select，杜绝查询端点顺手改状态的越权面。软删行由 @TableLogic 自动过滤。
 *
 * <p><b>项目可见性（IDOR 守卫）</b>：两端点均接受外部传入的 taskId / projectId，
 * 角色级 {@code ipd:ai-document:list}（四角色全员可读）不足以限定数据范围，
 * 故一律经 {@link IpdIdorGuard#requireProjectMemberOrSuperAdmin} 收口——
 * SUPER_ADMIN 豁免、项目存在 + 租户一致 + 在职成员（{@code exit_date IS NULL}）才放行，
 * 其余统一 FORBIDDEN（不区分「项目不存在」与「无权限」，不泄漏存在性）。
 * 守卫置于 service 层（不信任 controller 必传），复用包级静态范式，禁止本类另写一套校验。
 * 身份校验一律**先于任何 DB 读**（守卫 1 前置），避免以错误码差异构成存在性 oracle。
 */
@Service
@RequiredArgsConstructor
public class AiAgentTaskQueryService {

    /** 按项目列表上限（任务卡时间线展示面；超大项目截断防全表拉取，新任务在前）。 */
    static final int LIST_LIMIT = 200;

    private final AiAgentTaskMapper taskMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;

    /**
     * 按 taskId 单查（不存在按本仓契约收口 50001 NOT_FOUND，IpdResources 统一抛出）。
     *
     * @param taskId 任务行 ID（ai_agent_tasks.id，通知载荷 sourceId 同源）
     * @param actor  服务端会话身份（controller 由 requireInternal() 捕获后透传，不取前端值）
     * @return 只读投影（无 prompt/fillPayload 原文）
     */
    public AiAgentTaskView getByTaskId(Long taskId, IpdActor actor) {
        // 守卫 1 前置：未认证调用方不得触发任何 DB 读——否则 NOT_FOUND / UNAUTHORIZED 的差异
        // 本身就构成存在性 oracle（与守卫 4/5「角色校验先于任何 DB 读」同口径）
        IpdIdorGuard.requireAuthenticated(actor);
        AiAgentTask task = IpdResources.requireOrNotFound(taskMapper.selectById(taskId), taskId, "AI 执行任务");
        // 项目范围取自任务行本身（不信任调用方另行传入的 projectId），再按守卫 3 裁定可见性
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, task.getProjectId(), projectMemberMapper, projectMapper);
        return AiAgentTaskView.from(task);
    }

    /**
     * 按项目列任务（create_time DESC，最新在前；上限 {@link #LIST_LIMIT}）。
     *
     * @param projectId 项目 ID
     * @param actor     服务端会话身份（controller 由 requireInternal() 捕获后透传）
     * @return 任务卡时间线卡片组（空项目返回空列表，不是 null/404）
     */
    public List<AiAgentTaskView> listByProject(Long projectId, IpdActor actor) {
        // 先裁定可见性再查库：非在职成员不得拿到该项目任何任务行（含计数）
        IpdIdorGuard.requireProjectMemberOrSuperAdmin(actor, projectId, projectMemberMapper, projectMapper);
        return taskMapper.selectList(new LambdaQueryWrapper<AiAgentTask>()
                .eq(AiAgentTask::getProjectId, projectId)
                .orderByDesc(AiAgentTask::getCreateTime)
                .last("LIMIT " + LIST_LIMIT))
            .stream()
            .map(AiAgentTaskView::from)
            .toList();
    }
}
