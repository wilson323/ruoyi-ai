package org.ruoyi.ipd.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.ProjectScore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IProjectScoreService 接口（paiban-05 接口化，实现见 {@link ProjectScoreService}）。
 */
public interface IProjectScoreService {

    /** * AC-KPI-16c 两 PM 独立评分占位：返回草稿记录 */
    ProjectScore draft(ProjectScore draft);

    /** * P3-4.5 AC-INC-22/23/24：按综合得分查项目绩效系数。 */
    /** * <pre> */
    /** *   score ≥ 95  ⇒ 1.0（AC-INC-22：96 分 ⇒ 1.0） */
    /** *   score ≥ 85  ⇒ 0.8（AC-INC-23：88 分 ⇒ 0.8） */
    /** *   score ≥ 70  ⇒ 0.6 */
    /** *   score ≥ 60  ⇒ 0.3 */
    /** *   score &lt; 60  ⇒ 0（AC-INC-24：55 分 ⇒ 0，取消奖金资格） */
    /** * </pre> */
    BigDecimal projectPerformanceCoefficient(BigDecimal comprehensiveScore);

}
