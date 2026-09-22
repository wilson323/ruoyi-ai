package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.KpiFunctionalMetric;
import org.ruoyi.ipd.mapper.KpiFunctionalMetricMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IKpiFunctionalMetricsService 接口（paiban-05 接口化，实现见 {@link KpiFunctionalMetricsService}）。
 */
public interface IKpiFunctionalMetricsService {

    /** * 列出某项目的功能指标量表记录（按 period DESC + metricCode ASC）。 */
    /** * */
    /** * @param projectId  项目 ID（必填） */
    /** * @param metricCode 指标编码（可空；为空时返回该项目全部指标） */
    /** * @return 未被软删的记录列表 */
    List<KpiFunctionalMetric> listByProject(Long projectId, String metricCode);

    /** * 录入 / 更新功能指标量表（幂等 upsert）。 */
    /** * */
    /** * <p>幂等键 = (projectId, metricCode, period)：命中则覆盖 metricValue / targetValue / */
    /** * scaleVersion / remark；未命中则新增。 */
    /** * */
    /** * @param draft 录入草稿（projectId / metricCode / period 必填） */
    /** * @return 落库后的实体（新增或更新后的行） */
    KpiFunctionalMetric upsert(KpiFunctionalMetric draft);

    /** * 软删除一条量表记录（G-02）。 */
    /** * */
    /** * @param id 主键 */
    void delete(Long id);

    /** 返回 8 项指标编码（前端下拉用，保持声明顺序）。 */
    List<String> listMetricCodes();

}
