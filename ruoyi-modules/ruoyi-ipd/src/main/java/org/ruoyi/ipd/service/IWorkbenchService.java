package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.CoefficientChangeRequest;
import org.ruoyi.ipd.domain.DeletionRequest;
import org.ruoyi.ipd.domain.LaunchDateChangeRequest;
import org.ruoyi.ipd.domain.NotificationEvent;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.CoefficientChangeRequestMapper;
import org.ruoyi.ipd.mapper.DeletionRequestMapper;
import org.ruoyi.ipd.mapper.LaunchDateChangeRequestMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.workbench.WorkbenchAggregator;
import org.ruoyi.ipd.workbench.WorkbenchPolicy;
import org.springframework.stereotype.Service;

/**
 * IWorkbenchService 接口（paiban-05 接口化，实现见 {@link WorkbenchService}）。
 */
public interface IWorkbenchService {

    /** * 工作台总览。 */
    /** * */
    /** * @param actor     当前登录人（SEC-API-01：仅从会话推导） */
    /** * @param projectId 指定当前项目（顶栏切换）；空 = 第一个进行中项目 */
    /** * @return stats + 任务平铺列表（前端按项目分组）+ 当前推进 + 删除待办数 */
    Map<String, Object> summary(IpdActor actor, Long projectId);

    /** 通知收件箱透传（工作台右侧与顶栏红点共用）。 */
    List<NotificationEvent> inbox(IpdActor actor, boolean unreadOnly);

}
