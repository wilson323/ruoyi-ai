package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Date;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.tenant.helper.TenantHelper;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRecord;
import org.ruoyi.ipd.domain.PermanentDeleteAudit;
import org.ruoyi.ipd.domain.Person;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.PermanentDeleteAuditMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * IPermanentDeleteService 接口（paiban-05 接口化，实现见 {@link PermanentDeleteService}）。
 */
public interface IPermanentDeleteService {

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** * 执行永久清除。 */
    /** * */
    /** * @param actor        当前超管 */
    /** * @param entityType   person|project|kpi_record */
    /** * @param entityId     实体主键 */
    /** * @param confirmCode  必须等于 REQUIRED_CONFIRM_CODE */
    /** * @return audit row id */
    Long execute(
        IpdActor actor,
        String entityType,
        Long entityId,
        String confirmCode
    );

    /** 列出审计（仅超管；前端对账视图用） */
    List<PermanentDeleteAudit> listByEntityType(String entityType, int limit);

}
