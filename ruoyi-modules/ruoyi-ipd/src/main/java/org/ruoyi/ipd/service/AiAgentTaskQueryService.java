package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.IpdResources;
import org.ruoyi.ipd.domain.AiAgentTask;
import org.ruoyi.ipd.mapper.AiAgentTaskMapper;
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
 */
@Service
@RequiredArgsConstructor
public class AiAgentTaskQueryService {

    /** 按项目列表上限（任务卡时间线展示面；超大项目截断防全表拉取，新任务在前）。 */
    static final int LIST_LIMIT = 200;

    private final AiAgentTaskMapper taskMapper;

    /**
     * 按 taskId 单查（不存在按本仓契约收口 50001 NOT_FOUND，IpdResources 统一抛出）。
     *
     * @param taskId 任务行 ID（ai_agent_tasks.id，通知载荷 sourceId 同源）
     * @return 只读投影（无 prompt/fillPayload 原文）
     */
    public AiAgentTaskView getByTaskId(Long taskId) {
        AiAgentTask task = IpdResources.requireOrNotFound(taskMapper.selectById(taskId), taskId, "AI 执行任务");
        return AiAgentTaskView.from(task);
    }

    /**
     * 按项目列任务（create_time DESC，最新在前；上限 {@link #LIST_LIMIT}）。
     *
     * @param projectId 项目 ID
     * @return 任务卡时间线卡片组（空项目返回空列表，不是 null/404）
     */
    public List<AiAgentTaskView> listByProject(Long projectId) {
        return taskMapper.selectList(new LambdaQueryWrapper<AiAgentTask>()
                .eq(AiAgentTask::getProjectId, projectId)
                .orderByDesc(AiAgentTask::getCreateTime)
                .last("LIMIT " + LIST_LIMIT))
            .stream()
            .map(AiAgentTaskView::from)
            .toList();
    }
}
