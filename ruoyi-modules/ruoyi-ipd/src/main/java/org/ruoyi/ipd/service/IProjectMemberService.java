package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.security.IpdActor;

/**
 * IProjectMemberService 接口（paiban-05 接口化，实现见 {@link ProjectMemberServiceImpl}）。
 */
public interface IProjectMemberService {

    public ProjectMember bindMember(Long projectId, Long personId, String role, IpdActor operator);

    public ProjectMember bindMember(Long projectId, Long personId, String role, String approvalRef, IpdActor operator);

    public int exitForHandover(Long projectId, Long personId, String role);

    public List<ProjectMember> listActiveMembers(Long projectId);

}
