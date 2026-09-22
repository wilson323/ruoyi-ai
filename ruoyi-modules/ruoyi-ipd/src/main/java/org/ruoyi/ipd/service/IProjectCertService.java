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

    public ProjectCertListView listView(Long projectId);

    public List<ProjectCertItem> listByProject(Long projectId);

    public ProjectCertItem addManual(Long projectId, ProjectCertManualReq req, Long operatorId);

    public ProjectCertItem changeStatus(Long projectId, Long itemId, String target, Long operatorId);

}
