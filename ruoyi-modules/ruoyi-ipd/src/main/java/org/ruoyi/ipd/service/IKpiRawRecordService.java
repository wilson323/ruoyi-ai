package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRawRecord;
import org.ruoyi.ipd.mapper.KpiRawRecordMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IKpiRawRecordService 接口（paiban-05 接口化，实现见 {@link KpiRawRecordService}）。
 */
public interface IKpiRawRecordService {

    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    void setClock(java.time.Clock clock);

    /** * 录入 KPI 原始值。 */
    /** * */
    /** * @param draft 录入草稿（kpiType / projectId / recordPeriod / rawValue 必填） */
    /** * @param actor 当前操作人（recordedBy 取 actor.id） */
    /** * @return 已落库 KpiRawRecord（ID 已生成） */
    KpiRawRecord record(KpiRawRecord draft, IpdActor actor);

    /** * 列出某项目某类型的所有原始记录（按 record_period DESC）。 */
    /** * */
    /** * @param projectId 项目ID（必填） */
    /** * @param kpiType   KPI 类型（可空；为空时返回该项目全部类型） */
    /** * @return 记录列表 */
    List<KpiRawRecord> list(Long projectId, String kpiType);

    /** * 列出当前支持的 KPI 类型（前端下拉用）。 */
    List<String> listSupportedTypes();

}
