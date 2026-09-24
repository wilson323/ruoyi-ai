package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectCertItem;
import org.ruoyi.ipd.dto.ProjectCertListView;
import org.ruoyi.ipd.dto.ProjectCertManualReq;

/**
 * IProjectCertService 接口（paiban-05 接口化，实现见 {@link ProjectCertServiceImpl}）。
 */
public interface IProjectCertService {

    public int syncFromProject(Project project, Long operatorId);

    /**
     * R212-⑤（看板卡 dbe1b6a7）：带会话身份的 re-sync 入口（HTTP 面必须走本方法）。
     * 服务内做「操作人组 == 项目主组」断言（SUPER_ADMIN 豁免）后再委托两参版。
     */
    public int syncFromProjectAuthorized(Project project, org.ruoyi.ipd.security.IpdActor actor);

    public ProjectCertListView listView(Long projectId);

    public List<ProjectCertItem> listByProject(Long projectId);

    public ProjectCertItem addManual(Long projectId, ProjectCertManualReq req, Long operatorId);

    public ProjectCertItem changeStatus(Long projectId, Long itemId, String target, Long operatorId);

}
