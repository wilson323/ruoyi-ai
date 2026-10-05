package org.ruoyi.ipd.service;


/**
 * IProjectBootstrapService 接口（paiban-05 接口化，实现见 {@link ProjectBootstrapService}）。
 */
public interface IProjectBootstrapService {

    /** 首次成功返回阶段数6，已有完整结构返回0；调用方必须已开启真实事务。 */
    int bootstrap(Long projectId, Long operatorId);

}
