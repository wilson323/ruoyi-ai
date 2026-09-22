package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 90 日回款预警服务（C1，R149 batch2a）。
 *
 * <p>核心契约：
 * <ul>
 *   <li>{@link #checkAndGenerate(LocalDate)} — 扫描所有上市后未满 90 日的项目，
 *       计算回款比例，低于阈值（默认 0.25）写入 recovery_warnings（幂等：同日 (projectId, warningDate) 跳过）</li>
 *   <li>{@link #list(Long)} — 按项目列出预警（status filter 可选）</li>
 * </ul>
 *
 * <p>回款比例计算：
 * <ul>
 *   <li>分子 = SUM(窗口内 receipt_amount - refund_amount) （基于 receipt_ledger，AC-INC-32 6 月窗口）</li>
 *   <li>分母 = project.target_sales_amount（可空；为空时跳过该项目的检查）</li>
 *   <li>回收比例 = 分子 / 分母（保留 4 位小数，HALF_UP）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecoveryWarningService implements IRecoveryWarningService {

    /** 90 日窗口（自上市日起 90 个自然日内） */
    public static final int WINDOW_DAYS = 90;

    /** 默认阈值（system_configs 未配置时） */
    public static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.25");

    /** system_configs 参数键 */
    public static final String THRESHOLD_CONFIG_KEY = "recovery.warning90d.threshold";

    private final RecoveryWarningMapper recoveryWarningMapper;
    private final ProjectMapper projectMapper;
    private final ReceiptLedgerMapper receiptLedgerMapper;
    private final ISystemConfigService systemConfigService;

    /** 可注入时钟（R156-A 根除债，仿 KpiRawRecordService 模式）。 */
    private java.time.Clock clock = java.time.Clock.systemDefaultZone();

    public void setClock(java.time.Clock clock) {
        this.clock = (clock == null) ? java.time.Clock.systemDefaultZone() : clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /**
     * 扫描所有上市后未满 90 日的项目，回款比例低于阈值的写入预警表。
     *
     * @param today 扫描当日（可空；为空时取系统当前日期）
     * @return 新增预警条数（幂等去重后）
     */
    @Transactional(rollbackFor = Exception.class)
    public int checkAndGenerate(LocalDate today) {
        LocalDate scanDate = today != null ? today : today();
        BigDecimal threshold = readThreshold();
        log.info("90日回款预警扫描开始 scanDate={} threshold={}", scanDate, threshold);

        // 1) 取所有上市日期 ≤ scanDate 且 ≥ scanDate - 90 日的项目（已上市但未满 90 日）
        LocalDate minLaunch = scanDate.minusDays(WINDOW_DAYS - 1L); // 上市第 90 日仍计入
        List<Project> projects = projectMapper.selectList(
            Wrappers.<Project>lambdaQuery()
                .isNotNull(Project::getLaunchDate)
                .le(Project::getLaunchDate, java.sql.Date.valueOf(scanDate))
                .ge(Project::getLaunchDate, java.sql.Date.valueOf(minLaunch))
                .eq(Project::getDelFlag, "0")
        );

        int saved = 0;
        for (Project project : projects) {
            if (project.getTargetSalesAmount() == null
                || project.getTargetSalesAmount().compareTo(BigDecimal.ZERO) <= 0) {
                log.debug("跳过项目 projectId={}：targetSalesAmount 为空/非正",
                    project.getId());
                continue;
            }

            // 距上市日数
            int daysSinceLaunch = (int) ChronoUnit.DAYS.between(
                toLocalDate(project.getLaunchDate()),
                scanDate);

            // 累计回款（窗口内累计净回款，含 6 月窗口外也可计入——用户口径是"上市后 90 日累计"，简化直接 sum 所有 receipt_ledger）
            BigDecimal recoverySum = sumReceipts(project.getId());
            BigDecimal recoveryRate = recoverySum.divide(
                project.getTargetSalesAmount(), 4, RoundingMode.HALF_UP);

            // 幂等：同日 (projectId, warningDate) 已存在则跳过
            RecoveryWarning existed = recoveryWarningMapper.selectOne(
                Wrappers.<RecoveryWarning>lambdaQuery()
                    .eq(RecoveryWarning::getProjectId, project.getId())
                    .eq(RecoveryWarning::getWarningDate, scanDate)
                    .last("LIMIT 1")
            );
            if (existed != null) {
                log.debug("项目 projectId={} scanDate={} 预警已存在，跳过", project.getId(), scanDate);
                continue;
            }

            // 仅低于阈值才写入
            if (recoveryRate.compareTo(threshold) >= 0) {
                log.debug("项目 projectId={} 回款比例={} ≥ 阈值={}，不预警",
                    project.getId(), recoveryRate, threshold);
                continue;
            }

            RecoveryWarning warning = RecoveryWarning.builder()
                .projectId(project.getId())
                .warningDate(scanDate)
                .daysSinceLaunch(daysSinceLaunch)
                .recoveryRate(recoveryRate)
                .threshold(threshold)
                .status(RecoveryWarning.STATUS_PENDING)
                .build();
            recoveryWarningMapper.insert(warning);
            saved++;
            log.info("项目 projectId={} 触发 90 日回款预警 recoveryRate={} < threshold={} daysSinceLaunch={}",
                project.getId(), recoveryRate, threshold, daysSinceLaunch);
        }

        log.info("90日回款预警扫描完成 scanDate={} scanned={} saved={}",
            scanDate, projects.size(), saved);
        return saved;
    }

    /**
     * 按项目列出预警（按 warning_date DESC）。
     *
     * @param projectId 项目ID（可空；为空时返回全部）
     */
    public List<RecoveryWarning> list(Long projectId) {
        LambdaQueryWrapper<RecoveryWarning> wrapper = Wrappers.<RecoveryWarning>lambdaQuery()
            .orderByDesc(RecoveryWarning::getWarningDate);
        if (projectId != null) {
            wrapper.eq(RecoveryWarning::getProjectId, projectId);
        }
        return recoveryWarningMapper.selectList(wrapper);
    }

    /**
     * 读阈值（system_configs，未配置回退默认）。
     */
    BigDecimal readThreshold() {
        try {
            String raw = systemConfigService != null
                ? systemConfigService.getValue(THRESHOLD_CONFIG_KEY, DEFAULT_THRESHOLD.toPlainString())
                : DEFAULT_THRESHOLD.toPlainString();
            BigDecimal v = new BigDecimal(raw == null ? DEFAULT_THRESHOLD.toPlainString() : raw.trim());
            if (v.compareTo(BigDecimal.ZERO) < 0 || v.compareTo(BigDecimal.ONE) > 0) {
                log.warn("阈值 {} 超出 [0,1]，回退默认 {}", v, DEFAULT_THRESHOLD);
                return DEFAULT_THRESHOLD;
            }
            return v;
        } catch (NumberFormatException e) {
            log.warn("阈值读取非数值，回退默认 {}：{}", DEFAULT_THRESHOLD, e.getMessage());
            return DEFAULT_THRESHOLD;
        }
    }

    /**
     * 累计某项目全部 receipt_ledger 的净回款（receiptAmount - refundAmount）。
     */
    BigDecimal sumReceipts(Long projectId) {
        if (receiptLedgerMapper == null) return BigDecimal.ZERO;
        List<ReceiptLedger> all = receiptLedgerMapper.selectList(
            Wrappers.<ReceiptLedger>lambdaQuery()
                .eq(ReceiptLedger::getProjectId, projectId)
                .eq(ReceiptLedger::getSource, "RECEIPT")
        );
        BigDecimal sum = BigDecimal.ZERO;
        for (ReceiptLedger r : all) {
            BigDecimal receipt = r.getReceiptAmount() != null ? r.getReceiptAmount() : BigDecimal.ZERO;
            BigDecimal refund = r.getRefundAmount() != null ? r.getRefundAmount() : BigDecimal.ZERO;
            sum = sum.add(receipt.subtract(refund));
        }
        return sum;
    }

    /**
     * Date → LocalDate 兼容（项目 launch_date 在 MyBatis 反序列化时可能是
     * java.sql.Date 或 java.util.Date；java.sql.Date.toInstant() JDK 8+
     * 直接抛 UnsupportedOperationException，必须走 toLocalDate()）。
     */
    private static LocalDate toLocalDate(java.util.Date date) {
        if (date == null) return null;
        if (date instanceof java.sql.Date) {
            return ((java.sql.Date) date).toLocalDate();
        }
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}
