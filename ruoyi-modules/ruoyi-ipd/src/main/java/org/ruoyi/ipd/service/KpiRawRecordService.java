package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiRawRecord;
import org.ruoyi.ipd.mapper.KpiRawRecordMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * KPI 原始数据录入服务（A2 KPI P1 期补口，R149 batch2a）。
 *
 * <p>核心契约：
 * <ul>
 *   <li>{@link #record(KpiRawRecord, IpdActor)}：录入原始值；同（kpi_type, project_id, record_period）重复抛 STATE_CONFLICT</li>
 *   <li>{@link #list(Long, String)}：列出某项目某类型所有记录，按 record_period 倒序</li>
 * </ul>
 *
 * <p>8 项 KPI 类型枚举与业务校验：
 * <ul>
 *   <li>WINDOW_HIT_RATE / REQUIREMENT_ACCURACY / SCENE_COMPETITIVENESS — 0~1 区间（百分比口径）</li>
 *   <li>PPM_DEFECT_RATE — &gt;= 0（缺陷率）</li>
 *   <li>RELEASE_FREQUENCY — &gt;= 0（每月发布次数）</li>
 *   <li>CHANGE_LEAD_TIME — &gt;= 0（小时）</li>
 *   <li>CHANGE_FAILURE_RATE — 0~1 区间</li>
 *   <li>MTTR — &gt;= 0（小时）</li>
 * </ul>
 *
 * <p>注：raw_value 字段统一 decimal(10,4)，已涵盖 ppm / 百分比 / 时长 / 频率。
 * 真实业务侧具体单位由录入人自定（UI 展示口径），本服务只校验非负 + 百分比类上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiRawRecordService {

    /** 8 项 KPI 类型白名单（R149 拍板） */
    public static final Set<String> KPI_TYPES = Set.of(
        "WINDOW_HIT_RATE",
        "REQUIREMENT_ACCURACY",
        "SCENE_COMPETITIVENESS",
        "PPM_DEFECT_RATE",
        "RELEASE_FREQUENCY",
        "CHANGE_LEAD_TIME",
        "CHANGE_FAILURE_RATE",
        "MTTR"
    );

    /** 百分比类 KPI（raw_value 必须 ≤ 1.0） */
    private static final Set<String> PCT_KPI_TYPES = Set.of(
        "WINDOW_HIT_RATE",
        "REQUIREMENT_ACCURACY",
        "SCENE_COMPETITIVENESS",
        "CHANGE_FAILURE_RATE"
    );

    private final KpiRawRecordMapper kpiRawRecordMapper;
    /** 可注入时钟（仿 stateMachineGuard 模式；测试固定时刻消除真实时钟摇摆，生产零影响）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }

    private Date now() {
        return Date.from(clock.instant());
    }

    /**
     * 录入 KPI 原始值。
     *
     * @param draft 录入草稿（kpiType / projectId / recordPeriod / rawValue 必填）
     * @param actor 当前操作人（recordedBy 取 actor.id）
     * @return 已落库 KpiRawRecord（ID 已生成）
     */
    @Transactional(rollbackFor = Exception.class)
    public KpiRawRecord record(KpiRawRecord draft, IpdActor actor) {
        if (draft == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "KPI 原始数据草稿不能为空");
        }
        validateType(draft.getKpiType());
        if (draft.getProjectId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        if (draft.getRecordPeriod() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "recordPeriod 不能为空");
        }
        if (draft.getRawValue() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "rawValue 不能为空");
        }
        validateRawValue(draft.getKpiType(), draft.getRawValue());

        // 唯一性：同 (kpi_type, project_id, record_period) 重复录入拒
        KpiRawRecord existed = kpiRawRecordMapper.selectOne(
            Wrappers.<KpiRawRecord>lambdaQuery()
                .eq(KpiRawRecord::getKpiType, draft.getKpiType())
                .eq(KpiRawRecord::getProjectId, draft.getProjectId())
                .eq(KpiRawRecord::getRecordPeriod, draft.getRecordPeriod())
                .last("LIMIT 1")
        );
        if (existed != null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "KPI 原始数据重复录入：kpiType=" + draft.getKpiType()
                    + " projectId=" + draft.getProjectId()
                    + " period=" + draft.getRecordPeriod()
                    + "（已存在 id=" + existed.getId() + "）");
        }

        draft.setRecordedBy(actor != null ? actor.id() : null);
        draft.setRecordedAt(now());
        kpiRawRecordMapper.insert(draft);
        log.debug("KPI 原始数据录入 id={} kpiType={} projectId={} period={} rawValue={}",
            draft.getId(), draft.getKpiType(), draft.getProjectId(),
            draft.getRecordPeriod(), draft.getRawValue());
        return draft;
    }

    /**
     * 列出某项目某类型的所有原始记录（按 record_period DESC）。
     *
     * @param projectId 项目ID（必填）
     * @param kpiType   KPI 类型（可空；为空时返回该项目全部类型）
     * @return 记录列表
     */
    public List<KpiRawRecord> list(Long projectId, String kpiType) {
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        LambdaQueryWrapper<KpiRawRecord> wrapper = Wrappers.<KpiRawRecord>lambdaQuery()
            .eq(KpiRawRecord::getProjectId, projectId)
            .orderByDesc(KpiRawRecord::getRecordPeriod);
        if (kpiType != null && !kpiType.isBlank()) {
            validateType(kpiType);
            wrapper.eq(KpiRawRecord::getKpiType, kpiType);
        }
        return kpiRawRecordMapper.selectList(wrapper);
    }

    /**
     * 列出当前支持的 KPI 类型（前端下拉用）。
     */
    public List<String> listSupportedTypes() {
        // 返回有序列表（按 KPI_TYPES 声明顺序）
        return Arrays.asList(
            "WINDOW_HIT_RATE",
            "REQUIREMENT_ACCURACY",
            "SCENE_COMPETITIVENESS",
            "PPM_DEFECT_RATE",
            "RELEASE_FREQUENCY",
            "CHANGE_LEAD_TIME",
            "CHANGE_FAILURE_RATE",
            "MTTR"
        );
    }

    private void validateType(String kpiType) {
        if (kpiType == null || kpiType.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "kpiType 不能为空");
        }
        if (!KPI_TYPES.contains(kpiType)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "kpiType 不合法（仅支持 " + KPI_TYPES.stream().sorted().collect(Collectors.joining(",")) + "）");
        }
    }

    private void validateRawValue(String kpiType, BigDecimal rawValue) {
        if (rawValue.compareTo(BigDecimal.ZERO) < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "rawValue 不能为负数");
        }
        if (PCT_KPI_TYPES.contains(kpiType) && rawValue.compareTo(BigDecimal.ONE) > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "rawValue 超出百分比上限 1.0（kpiType=" + kpiType + "）");
        }
    }
}
