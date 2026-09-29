package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * AI 副驾的服务端数据范围守卫。SSE 在独立线程运行，不能依赖请求线程的租户上下文。
 * 每次调用重新读取 Person，并用其租户校验项目；前端 projectId 仅用于选定目标。
 */
@Service
@RequiredArgsConstructor
public class IpdCopilotAccess {

    private final PersonMapper personMapper;
    private final IpdAuthService authService;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    /** 返回重新读取的可信租户，供工作台查询显式限定范围。 */
    public String requireVisible(IpdActor actor, Long projectId) {
        if (actor == null || actor.id() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.UNAUTHORIZED);
        }
        Person person = personMapper.selectById(actor.id());
        if (person == null || !actor.id().equals(person.getId())
            || !"ACTIVE".equals(person.getAccountStatus())
            || !"ACTIVE".equals(person.getEmploymentStatus())
            || authService.scopeOf(person) != IpdAuthService.Scope.FULL
            || !Objects.equals(actor.role(), person.getPersonType())
            || !Objects.equals(actor.groupId(), person.getGroupId())
            || person.getTenantId() == null || person.getTenantId().isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.FORBIDDEN);
        }
        if (projectId == null) {
            return person.getTenantId();
        }
        Project project = projectMapper.selectById(projectId);
        if (project == null || !person.getTenantId().equals(project.getTenantId())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见");
        }
        // 保留超管的项目成员豁免，但项目存在性与租户校验同样适用。
        if ("SUPER_ADMIN".equals(person.getPersonType())) {
            return person.getTenantId();
        }
        Long activeMembers = projectMemberMapper.selectCount(Wrappers.<ProjectMember>lambdaQuery()
            .eq(ProjectMember::getProjectId, projectId)
            .eq(ProjectMember::getPersonId, person.getId())
            .isNull(ProjectMember::getExitDate));
        if (activeMembers == null || activeMembers == 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不可见");
        }
        return person.getTenantId();
    }
}
