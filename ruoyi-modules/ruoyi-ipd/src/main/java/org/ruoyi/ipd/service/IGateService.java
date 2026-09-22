package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Gate;
import org.ruoyi.ipd.mapper.GateMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IGateService 接口（paiban-05 接口化，实现见 {@link GateService}）。
 */
public interface IGateService {

    /** * AC-GATE-26：上市日期变更 → 下游 G3/G4/G5 截止日重排。 */
    /** * */
    /** * @param projectId 项目 */
    /** * @param deltaDays 偏移天数（正=推迟，负=提前） */
    /** * @param requestId 关联的上市日期变更申请 ID（审计用；可空） */
    /** * @param actor     操作人 */
    /** * @return 被更新的 gate 数量 */
    int shiftDownstreamGates(
        Long projectId,
        long deltaDays,
        Long requestId,
        IpdActor actor
    );

}
