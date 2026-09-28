package org.ruoyi.workflow.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.extension.toolkit.ChainWrappers;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.ruoyi.common.chat.base.ThreadContext;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.chat.enums.ErrorEnum;
import org.ruoyi.workflow.dto.workflow.WfRuntimeNodeDto;
import org.ruoyi.workflow.dto.workflow.WfRuntimeResp;
import org.ruoyi.workflow.entity.Workflow;
import org.ruoyi.workflow.entity.WorkflowRuntime;
import org.ruoyi.workflow.mapper.WorkflowRunMapper;
import org.ruoyi.workflow.util.JsonUtil;
import org.ruoyi.workflow.util.MPPageUtil;
import org.ruoyi.workflow.util.PrivilegeUtil;
import org.ruoyi.workflow.util.UuidUtil;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_DOING;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_FAIL;
import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED;

@Slf4j
@Service
public class WorkflowRuntimeService extends ServiceImpl<WorkflowRunMapper, WorkflowRuntime> {

    @Resource
    private WorkflowService workflowService;

    @Resource
    private WorkflowRuntimeNodeService workflowRuntimeNodeService;

    public WfRuntimeResp create(User user, Long workflowId) {
        WorkflowRuntime one = new WorkflowRuntime();
        one.setUuid(UuidUtil.createShort());
        one.setUserId(user.getId());
        one.setWorkflowId(workflowId);
        baseMapper.insert(one);

        one = baseMapper.selectById(one.getId());
        return changeToDTO(one);
    }

    public void updateInput(long id, WfState wfState) {
        if (CollectionUtils.isEmpty(wfState.getInput())) {
            log.warn("没有输入数据,id:{}", id);
            return;
        }
        WorkflowRuntime node = baseMapper.selectById(id);
        if (null == node) {
            log.error("工作流实例不存在,id:{}", id);
            return;
        }
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(id);
        ObjectNode ob = JsonUtil.createObjectNode();
        for (NodeIOData data : wfState.getInput()) {
            ob.set(data.getName(), JsonUtil.classToJsonNode(data.getContent()));
        }
        updateOne.setInput(JsonUtil.toJson(ob));
        updateOne.setStatus(WORKFLOW_PROCESS_STATUS_DOING);
        baseMapper.updateById(updateOne);
    }

    public WorkflowRuntime updateOutput(long id, WfState wfState) {
        WorkflowRuntime node = baseMapper.selectById(id);
        if (null == node) {
            log.error("工作流实例不存在,id:{}", id);
            return null;
        }
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(id);
        ObjectNode ob = JsonUtil.createObjectNode();
        for (NodeIOData data : wfState.getOutput()) {
            ob.set(data.getName(), JsonUtil.classToJsonNode(data.getContent()));
        }
        updateOne.setOutput(JsonUtil.toJson(ob));
        updateOne.setStatus(wfState.getProcessStatus());
        baseMapper.updateById(updateOne);
        return updateOne;
    }

    public void updateStatus(long id, int processStatus, String statusRemark) {
        WorkflowRuntime node = baseMapper.selectById(id);
        if (null == node) {
            log.error("工作流实例不存在,id:{}", id);
            return;
        }
        WorkflowRuntime updateOne = new WorkflowRuntime();
        updateOne.setId(id);
        updateOne.setStatus(processStatus);
        updateOne.setStatusRemark(StringUtils.substring(statusRemark, 0, 250));
        baseMapper.updateById(updateOne);
    }

    /**
     * 按 uuid 取实例（断点续跑等运维入口用）：不做当前用户过滤——ThreadContext 无登录态时
     * {@link #getByUuid} 会 NPE，权限控制交给调用方入口。
     */
    public WorkflowRuntime getByUuidForResume(String uuid) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(WorkflowRuntime::getUuid, uuid)
                .eq(WorkflowRuntime::getIsDeleted, false)
                .last("limit 1")
                .one();
    }

    /**
     * 僵尸 DOING 处置（补遗 §5-5 D1）：把 status=DOING 且 update_time 早于阈值（超时/进程重启遗留）
     * 的实例标记为 FAIL，status_remark 落「进程中断，可从断点续跑」语义区分（不新增 status 枚举值）。
     * <p>
     * 阈值语义：正常运行中的实例 update_time 停留在进入 DOING 的时刻（节点进度记在 t_workflow_runtime_node），
     * 故 staleTimeout 应大于单实例最长预期执行时长；进程重启后立即处置可传 Duration.ZERO。
     *
     * @return 处置的实例数
     */
    public int failZombieDoingRuntimes(Duration staleTimeout) {
        LocalDateTime deadline = LocalDateTime.now().minus(staleTimeout);
        List<WorkflowRuntime> zombies = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(WorkflowRuntime::getStatus, WORKFLOW_PROCESS_STATUS_DOING)
                .eq(WorkflowRuntime::getIsDeleted, false)
                .le(WorkflowRuntime::getUpdateTime, deadline)
                .list();
        for (WorkflowRuntime zombie : zombies) {
            log.warn("僵尸 DOING 处置为 FAIL 并标记可续跑,id:{},uuid:{}", zombie.getId(), zombie.getUuid());
            updateStatus(zombie.getId(), WORKFLOW_PROCESS_STATUS_FAIL, WORKFLOW_PROCESS_STATUS_REMARK_INTERRUPTED);
        }
        return zombies.size();
    }

    public WorkflowRuntime getByUuid(String uuid) {
        return ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(!ThreadContext.getCurrentUser().getIsAdmin(), WorkflowRuntime::getUserId, ThreadContext.getCurrentUserId())
                .eq(WorkflowRuntime::getUuid, uuid)
                .eq(WorkflowRuntime::getIsDeleted, false)
                .last("limit 1")
                .one();
    }

    public Page<WfRuntimeResp> page(String wfUuid, Integer currentPage, Integer pageSize) {
        Workflow workflow = workflowService.getOrThrow(wfUuid);
        User user = ThreadContext.getCurrentUser();
        Page<WorkflowRuntime> page = ChainWrappers.lambdaQueryChain(baseMapper)
                .eq(WorkflowRuntime::getWorkflowId, workflow.getId())
                .eq(WorkflowRuntime::getIsDeleted, false)
                .eq(!user.getIsAdmin(), WorkflowRuntime::getUserId, user.getId())
                .orderByDesc(WorkflowRuntime::getUpdateTime)
                .page(new Page<>(currentPage, pageSize));
        Page<WfRuntimeResp> result = new Page<>();
        MPPageUtil.convertToPage(page, result, WfRuntimeResp.class, (source, target) -> {
            fillInputOutput(target);
            return target;
        });
        return result;
    }

    public List<WfRuntimeNodeDto> listByRuntimeUuid(String runtimeUuid) {
        WorkflowRuntime runtime = PrivilegeUtil.checkAndGetByUuid(runtimeUuid, this.query(), ErrorEnum.A_WF_RUNTIME_NOT_FOUND);
        return workflowRuntimeNodeService.listByWfRuntimeId(runtime.getId());
    }

    public boolean deleteAll(String wfUuid) {
        Workflow workflow = workflowService.getOrThrow(wfUuid);
        User user = ThreadContext.getCurrentUser();
        return ChainWrappers.lambdaUpdateChain(baseMapper)
                .eq(WorkflowRuntime::getWorkflowId, workflow.getId())
                .eq(!user.getIsAdmin(), WorkflowRuntime::getUserId, user.getId())
                .set(WorkflowRuntime::getIsDeleted, true)
                .update();
    }

    private WfRuntimeResp changeToDTO(WorkflowRuntime runtime) {
        WfRuntimeResp result = new WfRuntimeResp();
        BeanUtils.copyProperties(runtime, result);
        fillInputOutput(result);
        return result;
    }

//    private void fillNodes(WfRuntimeResp runtimeResp) {
//        List<WfRuntimeNodeDto> nodes = workflowRuntimeNodeService.listByWfRuntimeId(runtimeResp.getId());
//        runtimeResp.setNodes(nodes);
//    }

    private void fillInputOutput(WfRuntimeResp target) {
        if (null == target.getInput()) {
            target.setInput(JsonUtil.createObjectNode());
        }
        if (null == target.getOutput()) {
            target.setOutput(JsonUtil.createObjectNode());
        }
    }

    public boolean softDelete(String uuid) {
        WorkflowRuntime workflowRuntime = PrivilegeUtil.checkAndGetByUuid(uuid, this.query(), ErrorEnum.A_WF_NOT_FOUND);
        return ChainWrappers.lambdaUpdateChain(baseMapper)
                .eq(WorkflowRuntime::getId, workflowRuntime.getId())
                .set(WorkflowRuntime::getIsDeleted, true)
                .update();
    }
}
