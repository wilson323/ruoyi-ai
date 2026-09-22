package org.ruoyi.ipd.service;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.domain.ActionDef;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ProjectStage;
import org.ruoyi.ipd.domain.StageAction;
import org.ruoyi.ipd.mapper.ProjectStageMapper;
import org.ruoyi.ipd.mapper.StageActionMapper;
import org.ruoyi.ipd.seed.ActionCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * IProjectBootstrapService 接口（paiban-05 接口化，实现见 {@link ProjectBootstrapService}）。
 */
public interface IProjectBootstrapService {

    /** 首次成功返回阶段数6，已有完整结构返回0；调用方必须已开启真实事务。 */
    int bootstrap(Long projectId, Long operatorId);

}
