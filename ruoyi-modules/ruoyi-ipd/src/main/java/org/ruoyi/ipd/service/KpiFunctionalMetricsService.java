package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiFunctionalMetric;
import org.ruoyi.ipd.mapper.KpiFunctionalMetricMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * KPI 功能指标量表服务（A2 P1 期，R148.1 §2.2 方案②）。
 *
 * <p>职责（P1 期只做**数据采集 + 配置入口**，不含评分计算——计算属 P2）：
 * <ul>
 *   <li>{@link #listByProject(Long, String)}：列出某项目（可再按指标编码过滤）的量表记录</li>
 *   <li>{@link #upsert(KpiFunctionalMetric)}：以 (projectId, metricCode, period) 幂等键 upsert</li>
 *   <li>{@link #delete(Long)}：软删除（G-02 禁物理 DELETE）</li>
 * </ul>
 *
 * <p>8 项指标编码与 {@code KpiScoreCalculator} 的字段名一一对应
 * （{@code MARKET_PM_FUNCTIONAL_FIELDS} + {@code RD_PM_FUNCTIONAL_FIELDS}），
 * 供 P2 期 compute 方法按码取数。
 *
 * <p>DOC-01 §4 语义约束：{@code metricValue} 为空 = **待补充**，不自动填 0、不据空值出分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiFunctionalMetricsService implements IKpiFunctionalMetricsService {

    /**
     * 8 项功能指标编码（有序；前端下拉 / 校验白名单同源）。
     *
     * <p>市场 4 项 + 研发 4 项；对齐 DOC-01 §4 功能指标表与 KpiScoreCalculator 字段。
     */
    public static final List<String> METRIC_CODES = List.of(
        // —— 市场侧（MARKET_PM_FUNCTIONAL_FIELDS）——
        "MKT_REQUIREMENT_ACCURACY",
        "MKT_WINDOW_HIT_RATE",
        "MKT_SCENARIO_COMPETITIVENESS",
        "MKT_COMPETITOR_INTELLIGENCE",
        // —— 研发侧（RD_PM_FUNCTIONAL_FIELDS）——
        "RD_LAUNCH_ON_TIME_RATE",
        "RD_QUALITY_DEFECT_RATE",
        "RD_TECH_INNOVATION",
        "RD_FIRST_PASS_YIELD"
    );

    /** 指标编码白名单。 */
    public static final Set<String> METRIC_CODE_SET = Set.copyOf(METRIC_CODES);

    private final KpiFunctionalMetricMapper kpiFunctionalMetricMapper;

    /**
     * 列出某项目的功能指标量表记录（按 period DESC + metricCode ASC）。
     *
     * @param projectId  项目 ID（必填）
     * @param metricCode 指标编码（可空；为空时返回该项目全部指标）
     * @return 未被软删的记录列表
     */
    public List<KpiFunctionalMetric> listByProject(Long projectId, String metricCode) {
        if (projectId == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        LambdaQueryWrapper<KpiFunctionalMetric> wrapper = Wrappers.<KpiFunctionalMetric>lambdaQuery()
            .eq(KpiFunctionalMetric::getProjectId, projectId)
            .orderByDesc(KpiFunctionalMetric::getPeriod)
            .orderByAsc(KpiFunctionalMetric::getMetricCode);
        if (metricCode != null && !metricCode.isBlank()) {
            validateMetricCode(metricCode);
            wrapper.eq(KpiFunctionalMetric::getMetricCode, metricCode);
        }
        return kpiFunctionalMetricMapper.selectList(wrapper);
    }

    /**
     * 录入 / 更新功能指标量表（幂等 upsert）。
     *
     * <p>幂等键 = (projectId, metricCode, period)：命中则覆盖 metricValue / targetValue /
     * scaleVersion / remark；未命中则新增。
     *
     * @param draft 录入草稿（projectId / metricCode / period 必填）
     * @return 落库后的实体（新增或更新后的行）
     */
    @Transactional(rollbackFor = Exception.class)
    public KpiFunctionalMetric upsert(KpiFunctionalMetric draft) {
        if (draft == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "功能指标量表草稿不能为空");
        }
        validate(draft);

        KpiFunctionalMetric existed = kpiFunctionalMetricMapper.selectOne(
            Wrappers.<KpiFunctionalMetric>lambdaQuery()
                .eq(KpiFunctionalMetric::getProjectId, draft.getProjectId())
                .eq(KpiFunctionalMetric::getMetricCode, draft.getMetricCode())
                .eq(KpiFunctionalMetric::getPeriod, draft.getPeriod())
                .last("LIMIT 1")
        );
        if (existed != null) {
            existed.setMetricValue(draft.getMetricValue())
                .setTargetValue(draft.getTargetValue())
                .setScaleVersion(draft.getScaleVersion())
                .setRemark(draft.getRemark());
            kpiFunctionalMetricMapper.updateById(existed);
            log.debug("KPI 功能指标量表更新 id={} projectId={} metricCode={} period={}",
                existed.getId(), existed.getProjectId(), existed.getMetricCode(), existed.getPeriod());
            return existed;
        }

        kpiFunctionalMetricMapper.insert(draft);
        log.debug("KPI 功能指标量表新增 id={} projectId={} metricCode={} period={}",
            draft.getId(), draft.getProjectId(), draft.getMetricCode(), draft.getPeriod());
        return draft;
    }

    /**
     * 软删除一条量表记录（G-02）。
     *
     * @param id 主键
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        if (id == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "id 不能为空");
        }
        KpiFunctionalMetric existed = kpiFunctionalMetricMapper.selectById(id);
        if (existed == null || "1".equals(existed.getDelFlag())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "功能指标量表记录不存在：id=" + id);
        }
        existed.setDelFlag("1");
        int rows = kpiFunctionalMetricMapper.updateById(existed);
        if (rows != 1) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "功能指标量表软删除未更新唯一记录：id=" + id);
        }
        log.debug("KPI 功能指标量表软删除 id={}", id);
    }

    /** 返回 8 项指标编码（前端下拉用，保持声明顺序）。 */
    public List<String> listMetricCodes() {
        return METRIC_CODES;
    }

    // ===== 校验 =====

    private void validate(KpiFunctionalMetric draft) {
        if (draft.getProjectId() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "projectId 不能为空");
        }
        validateMetricCode(draft.getMetricCode());
        if (draft.getPeriod() == null || draft.getPeriod().isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "period 不能为空");
        }
        if (draft.getMetricValue() == null && draft.getTargetValue() == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "metricValue 与 targetValue 不可同时为空（空值表示待补充，但录入至少需其一）");
        }
        if (draft.getMetricValue() != null && draft.getMetricValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "metricValue 不能为负数");
        }
        if (draft.getTargetValue() != null && draft.getTargetValue().compareTo(BigDecimal.ZERO) < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "targetValue 不能为负数");
        }
        if (draft.getScaleVersion() != null && draft.getScaleVersion().length() > 50) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "scaleVersion 长度不能超过 50");
        }
        if (draft.getRemark() != null && draft.getRemark().length() > 500) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "remark 长度不能超过 500");
        }
    }

    private void validateMetricCode(String metricCode) {
        if (metricCode == null || metricCode.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "metricCode 不能为空");
        }
        if (!METRIC_CODE_SET.contains(metricCode)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "metricCode 不合法（仅支持 8 项功能指标：" + String.join(",", METRIC_CODES) + "）");
        }
    }
}
