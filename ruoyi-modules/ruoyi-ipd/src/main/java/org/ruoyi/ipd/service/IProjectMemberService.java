package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.ProjectMember;
import org.ruoyi.ipd.security.IpdActor;

/**
 * IProjectMemberService 接口（paiban-05 接口化，实现见 {@link ProjectMemberServiceImpl}）。
 */
public interface IProjectMemberService {

    /**
     * 将人员以指定角色绑定到项目（无审批引用）。
     *
     * @param projectId 项目 ID
     * @param personId  人员 ID
     * @param role      项目角色
     * @param operator  操作人
     * @return 新建或更新后的成员记录
     */
    public ProjectMember bindMember(Long projectId, Long personId, String role, IpdActor operator);

    /**
     * 将人员以指定角色绑定到项目，并登记审批引用。
     *
     * @param projectId   项目 ID
     * @param personId    人员 ID
     * @param role        项目角色
     * @param approvalRef 审批单号 / 引用
     * @param operator    操作人
     * @return 新建或更新后的成员记录
     */
    public ProjectMember bindMember(Long projectId, Long personId, String role, String approvalRef, IpdActor operator);

    /**
     * 交接场景下退出成员：将该人在项目中的指定角色置为已退出，并返回影响行数。
     *
     * @param projectId 项目 ID
     * @param personId  人员 ID
     * @param role      要退出的角色
     * @return 更新行数（0 表示无匹配活跃成员）
     */
    public int exitForHandover(Long projectId, Long personId, String role);

    /**
     * 列出项目下当前仍有效的成员（未退出）。
     *
     * @param projectId 项目 ID
     * @return 活跃成员列表；无则空列表
     */
    public List<ProjectMember> listActiveMembers(Long projectId);

}
