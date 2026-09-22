package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ReceiptLedger;
import org.ruoyi.ipd.domain.RecoveryWarning;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ReceiptLedgerMapper;
import org.ruoyi.ipd.mapper.RecoveryWarningMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IRecoveryWarningService 接口（paiban-05 接口化，实现见 {@link RecoveryWarningService}）。
 */
public interface IRecoveryWarningService {

    /** 可注入时钟（R156-A 根除债，仿 KpiRawRecordService 模式）。 */
    void setClock(java.time.Clock clock);

    /** * 扫描所有上市后未满 90 日的项目，回款比例低于阈值的写入预警表。 */
    /** * */
    /** * @param today 扫描当日（可空；为空时取系统当前日期） */
    /** * @return 新增预警条数（幂等去重后） */
    int checkAndGenerate(LocalDate today);

    /** * 按项目列出预警（按 warning_date DESC）。 */
    /** * */
    /** * @param projectId 项目ID（可空；为空时返回全部） */
    List<RecoveryWarning> list(Long projectId);

}
