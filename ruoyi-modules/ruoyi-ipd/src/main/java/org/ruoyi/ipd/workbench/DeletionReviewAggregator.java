package org.ruoyi.ipd.workbench;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.service.DeletionRequestServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除审批任务投递（taskType=deletion_review，WB-17-1 P0 逻辑原地迁入）：
 * 组长 = 待初审每条 1 卡；超管 = 待终审每条 1 卡；其余角色空。
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class DeletionReviewAggregator implements WorkbenchAggregator {

    private final DeletionRequestMapper deletionRequestMapper;

    /** 与审批写路径共用目标组解析；仅副驾受信租户路径使用。 */
    private DeletionRequestServiceImpl deletionRequestService;

    @Autowired
    public void setDeletionRequestService(DeletionRequestServiceImpl deletionRequestService) {
        this.deletionRequestService = deletionRequestService;
    }

    @Override
    public String taskType() {
        return "deletion_review";
    }

    @Override
    public List<Map<String, Object>> collect(IpdActor actor, Map<Long, Project> visibleProjects, Date now) {
        return collect(actor, visibleProjects, now, null);
    }

    @Override
    public List<Map<String, Object>> collect(IpdActor actor, Map<Long, Project> visibleProjects,
                                             Date now, String trustedTenantId) {
        String reviewStatus;
        boolean adminSide;
        if ("SUPER_ADMIN".equals(actor.role())) {
            reviewStatus = DeletionRequestServiceImpl.ST_ADMIN_REVIEW;
            adminSide = true;
        } else if ("GROUP_LEADER".equals(actor.role())) {
            reviewStatus = DeletionRequestServiceImpl.ST_LEADER_REVIEW;
            adminSide = false;
        } else {
            return List.of();
        }
        LambdaQueryWrapper<DeletionRequest> query = new LambdaQueryWrapper<DeletionRequest>()
            .eq(DeletionRequest::getStatus, reviewStatus);
        if (trustedTenantId != null) {
            query.eq(DeletionRequest::getTenantId, trustedTenantId);
        }
        List<DeletionRequest> pending = deletionRequestMapper.selectList(query);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (DeletionRequest request : pending) {
            if (trustedTenantId != null && !trustedTenantId.equals(request.getTenantId())) {
                continue;
            }
            if (trustedTenantId != null && !adminSide
                && (deletionRequestService == null
                    || !deletionRequestService.isTargetInLeaderGroup(actor, request))) {
                continue;
            }
            tasks.add(toTask(request, adminSide, now));
        }
        return tasks;
    }

    private Map<String, Object> toTask(DeletionRequest request, boolean adminSide, Date now) {
        Date due = adminSide ? request.getAdminDueAt() : request.getLeaderDueAt();
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", "DEL-" + request.getId());
        task.put("projectId", null);
        task.put("projectName", "删除审批");
        task.put("projectCode", request.getEntityType());
        task.put("actionCode", "DEL-REVIEW-" + request.getId());
        task.put("title", (adminSide ? "删除终审：" : "删除初审：") + request.getEntityType() + " #" + request.getEntityId());
        task.put("taskType", taskType());
        task.put("status", request.getStatus());
        task.put("priority", due != null && due.before(now) ? "high" : "normal");
        task.put("ownerRole", null);
        task.put("dueDate", due);
        task.put("isBlocking", "1"); // 值域对齐 stage_actions 的 '1'/'N'（前端 isBlocking === '1' 判定阻断）
        task.put("deepLink", "/ipd/deletion/review");
        return task;
    }
}
