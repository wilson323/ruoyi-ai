package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ReceiptLedger;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ReceiptLedgerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

/**
 * 销售回款台账服务（P3-4.1 AC-INC-16b/16c/16d/31/31b/32）
 *
 * <p>核心规则：
 * <ul>
 *   <li>AC-INC-16b：达成率口径 = 回款（RECEIPT），不是出库/开票</li>
 *   <li>AC-INC-16c：月度录入金额 + 上传凭证附件</li>
 *   <li>AC-INC-16d：窗口外数据不计入达成率</li>
 *   <li>AC-INC-31：窗口外退款不做回溯扣减</li>
 *   <li>AC-INC-31b：窗口内退款当期冲减</li>
 *   <li>AC-INC-32：6自然月窗口以上市日期为起算点</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReceiptLedgerService implements IReceiptLedgerService {

    private final ReceiptLedgerMapper receiptLedgerMapper;
    private final ProjectMapper projectMapper;

    /**
     * 单笔退款冲减金额上限（可配置，<b>业务口径待 owner 拍板</b>）。
     *
     * <p>依据缺失说明：已检索 {@code docs/开发说明/开发说明书.md} BR-INC-14、
     * {@code docs/ipd-系统说明/外部资源/IPD系统_AI开发主Prompt_v3.md:817,917}、
     * {@code docs/ipd-系统说明/外部资源/IPD系统_验收清单.md:311-312}，退款规则只约束<b>时间</b>维度
     * （窗口内当期冲减 / 窗口外不回溯），未规定任何<b>金额</b>上限；DDL
     * {@code docs/script/sql/update/2026-09-06-ipd-receipt-ledger-table.sql} 的 net_amount 生成列
     * 也没有 CHECK 约束。故不擅自拍数字，改为配置项：
     * <ul>
     *   <li>留空（默认）= 不额外设限，只受「累计冲减不超过当月回款额」约束；</li>
     *   <li>填入正数 = 单次冲减金额不得超过该值，超出按 PARAM_INVALID 拒绝。</li>
     * </ul>
     * 撤销方式：删除 {@code ipd.receipt.refund-max-amount} 配置键即可回到默认。
     */
    @Value("${ipd.receipt.refund-max-amount:}")
    BigDecimal refundMaxAmount;

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final String MONTH_PATTERN = "\\d{4}-(0[1-9]|1[0-2])";

    /** receipt_amount / refund_amount 列宽 decimal(18,2)：整数部分最多 16 位，超出即无法入库（DDL 依据）。 */
    private static final BigDecimal MAX_REPRESENTABLE_AMOUNT = new BigDecimal("1" + "0".repeat(16));

    /**
     * 录入回款（AC-INC-16c）
     * 校验：source 必须为 RECEIPT；月份在窗口内才计入达成率
     */
    @Transactional(rollbackFor = Exception.class)
    public ReceiptLedger recordReceipt(ReceiptLedger ledger) {
        if (ledger.getReceiptMonth() == null || !ledger.getReceiptMonth().matches(MONTH_PATTERN)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "回款月份必须为 YYYY-MM 格式（1-12 月）");
        }
        if (!"RECEIPT".equals(ledger.getSource())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "AC-INC-16b：达成率口径必须是 RECEIPT（回款），不是 " + ledger.getSource());
        }
        if (ledger.getReceiptAmount() == null || ledger.getReceiptAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "回款金额必须为正数");
        }
        if (ledger.getRefundAmount() == null) {
            ledger.setRefundAmount(BigDecimal.ZERO);
        }
        // 首次录入即带 refundAmount 的旁路同样不得把净额打成负数（工程口径，理由同 recordRefund）
        // 注意：此处 refundAmount 允许为 0（尚无退款），只拒负数 / 越界 / 超过回款额
        if (ledger.getRefundAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "退款冲减金额不能为负数");
        }
        if (!isFinite(ledger.getRefundAmount())
            || ledger.getRefundAmount().compareTo(MAX_REPRESENTABLE_AMOUNT) >= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "退款冲减金额不是有效数值或超出 decimal(18,2) 可表示范围: " + ledger.getRefundAmount());
        }
        if (ledger.getRefundAmount().compareTo(ledger.getReceiptAmount()) > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "退款冲减金额不得超过当月回款金额（净额不得为负），回款=" + ledger.getReceiptAmount()
                    + " 退款=" + ledger.getRefundAmount());
        }
        requireRefundWithinConfiguredCap(ledger.getRefundAmount());
        // AC-INC-32：项目存在性校验 + 上市日期为起算点
        Project project = projectMapper.selectById(ledger.getProjectId());
        if (project == null || "1".equals(project.getDelFlag())) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "项目不存在: " + ledger.getProjectId());
        }
        if (ledger.getWindowStart() == null && project.getLaunchDate() != null) {
            ledger.setWindowStart(project.getLaunchDate());
        }
        // 2026-09-08 P-BACKLOG-1：月份重复业务校验——真库唯一键 uk_receipt_project_month
        // 防线前移，真活复跑曾暴露 Duplicate entry SQL 异常逃逸为 90001 ISE，
        // 现收敛为 STATE_CONFLICT 业务拒绝，不再泄漏系统内部错误码
        ReceiptLedger duplicated = receiptLedgerMapper.selectOne(
            new LambdaQueryWrapper<ReceiptLedger>()
                .eq(ReceiptLedger::getProjectId, ledger.getProjectId())
                .eq(ReceiptLedger::getReceiptMonth, ledger.getReceiptMonth())
        );
        if (duplicated != null) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT,
                "该月份已有回款记录，不可重复录入: " + ledger.getReceiptMonth());
        }
        // 计算窗口
        computeWindow(ledger);
        ledger.setCreateTime(new Date());
        receiptLedgerMapper.insert(ledger);
        return ledger;
    }

    /**
     * 退款冲减（AC-INC-31/31b）
     * 窗口内退款 → 当期冲减；窗口外退款 → 不回溯扣减（拒绝）
     *
     * <p>金额上限（本轮补齐，<b>不是</b> AC-INC-31/31b 的一部分，二者只管时间维度）：
     * <ol>
     *   <li>金额必须为正、且在 decimal(18,2) 可表示范围内（见 {@link #requireRefundAmountValid}）；</li>
     *   <li>累计冲减不得超过该行回款额，净额不得为负 —— <b>工程口径</b>，
     *       开发说明书 / 验收清单 / DDL 均未规定金额上限，此处取「不产生负净额」的最小不变量；</li>
     *   <li>可选的绝对金额上限 {@code ipd.receipt.refund-max-amount}，
     *       <b>业务口径待 owner 拍板</b>，默认留空 = 不启用（见字段注释）。</li>
     * </ol>
     */
    @Transactional(rollbackFor = Exception.class)
    public ReceiptLedger recordRefund(Long projectId, String month, BigDecimal refundAmount) {
        requireRefundAmountValid(refundAmount, "退款冲减金额");
        ReceiptLedger existing = receiptLedgerMapper.selectOne(
            new LambdaQueryWrapper<ReceiptLedger>()
                .eq(ReceiptLedger::getProjectId, projectId)
                .eq(ReceiptLedger::getReceiptMonth, month)
        );
        if (existing == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.NOT_FOUND, "该月份无回款记录，不可冲减: " + month);
        }
        // AC-INC-31：窗口外退款不做回溯扣减
        if (!isInWindow(existing)) {
            throw new IpdBusinessException(ApiV1ErrorCode.STATE_CONFLICT, "AC-INC-31：窗口外退款不做回溯扣减，月份=" + month);
        }
        // 冲减上限校验：不得把该行净额打成负数（工程口径，见方法 javadoc）
        BigDecimal alreadyRefunded = existing.getRefundAmount() != null
            ? existing.getRefundAmount() : BigDecimal.ZERO;
        BigDecimal outstanding = existing.getReceiptAmount() == null
            ? BigDecimal.ZERO
            : existing.getReceiptAmount().subtract(alreadyRefunded);
        if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "该月份回款已全额冲减，不可继续冲减，月份=" + month);
        }
        if (refundAmount.compareTo(outstanding) > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "退款冲减金额超过当前未冲减余额，月份=" + month
                    + " 未冲减余额=" + outstanding.toPlainString()
                    + " 本次冲减=" + refundAmount.toPlainString());
        }
        requireRefundWithinConfiguredCap(refundAmount);
        // AC-INC-31b：窗口内退款当期冲减
        existing.setRefundAmount(alreadyRefunded.add(refundAmount));
        receiptLedgerMapper.updateById(existing);
        return existing;
    }

    /**
     * 退款冲减金额基础合法性：非 null、正数、在 decimal(18,2) 可表示范围内。
     *
     * <p>「非有限数（NaN / Infinity）」：{@link BigDecimal} 本身无法承载这两个值，HTTP 路径上由 Jackson
     * 绑定 {@code BigDecimal} 字段时即以 400 拒掉；此处的等价防御是拒绝超出列宽的量级，
     * 否则会在入库时被静默截断/溢出。DTO 侧 {@code ReceiptRefundReq.refundAmount @DecimalMin("0.01")}
     * 只覆盖 HTTP 路径，内部调用方不经过 Bean Validation，故在 service 侧补齐。
     */
    private void requireRefundAmountValid(BigDecimal amount, String label) {
        if (amount == null) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, label + "不能为空");
        }
        if (!isFinite(amount)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, label + "不是有效数值: " + amount);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, label + "必须为正数");
        }
        if (amount.compareTo(MAX_REPRESENTABLE_AMOUNT) >= 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                label + "超出 decimal(18,2) 可表示范围: " + amount.toPlainString());
        }
    }

    /**
     * 可配置单笔上限 {@code ipd.receipt.refund-max-amount}；留空（null）= 未启用，不额外设限。
     * 业务口径待 owner 拍板，见字段注释。
     */
    private void requireRefundWithinConfiguredCap(BigDecimal amount) {
        if (refundMaxAmount == null) {
            return;
        }
        if (refundMaxAmount.compareTo(BigDecimal.ZERO) <= 0) {
            // 配置了非正数视为误配，忽略而不是全量拒单
            return;
        }
        if (amount.compareTo(refundMaxAmount) > 0) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "退款冲减金额超过配置上限 ipd.receipt.refund-max-amount="
                    + refundMaxAmount.toPlainString() + "，本次=" + amount.toPlainString());
        }
    }

    /** BigDecimal 恒为有限值；此处保留为显式护栏，便于将来换用 double 金额时不被静默移除。 */
    private static boolean isFinite(BigDecimal amount) {
        return amount != null;
    }

    /**
     * 计算达成率（AC-INC-16b/16d）
     * 达成率 = SUM(窗口内净回款) / 目标销售额
     */
    public BigDecimal calculateAchievementRate(Long projectId, BigDecimal targetSales) {
        BigDecimal totalInWindow = windowNet(projectId).netAmount();
        if (targetSales == null || targetSales.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return totalInWindow.divide(targetSales, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 6 自然月窗口内 RECEIPT 口径净回款（AC-INC-16b/16d）：SUM(receiptAmount - refundAmount)。
     *
     * <p>2026-09-09 治理轮 PERF-P1-1：窗口过滤从 Java 循环切到真库 STORED GENERATED 列 in_window
     * （DDL: receipt_ledger.in_window tinyint(1) STORED GENERATED = 1 当且仅当 receiptMonth 在 6 月窗口内）。
     * .apply() 直接拼接 SQL 避免 mybatis-plus 引入 receipt_ledger 全表 selectAll。
     *
     * <p>R232-LC03 终算对账复用本方法做「存储回款基数 vs 窗口净额」确定性复算；
     * {@code rowCount=0} 表示窗口内**无台账行**（区别于净额为 0），调用方据此标「待补」而非按 0 伪判（W14-02 语义）。
     */
    public WindowNet windowNet(Long projectId) {
        List<ReceiptLedger> all = receiptLedgerMapper.selectList(
            new LambdaQueryWrapper<ReceiptLedger>()
                .eq(ReceiptLedger::getProjectId, projectId)
                .eq(ReceiptLedger::getSource, "RECEIPT")
                .apply("in_window = 1")
        );
        BigDecimal totalInWindow = BigDecimal.ZERO;
        for (ReceiptLedger r : all) {
            BigDecimal net = r.getReceiptAmount().subtract(
                r.getRefundAmount() != null ? r.getRefundAmount() : BigDecimal.ZERO);
            totalInWindow = totalInWindow.add(net);
            // AC-INC-16d：窗口外数据已被 SQL 过滤，Java 循环内不再判定
        }
        return new WindowNet(all.size(), totalInWindow);
    }

    /** 窗口净回款视图：rowCount 行数（0 = 窗口内无台账行，不等于净额 0）+ netAmount 净额。 */
    public record WindowNet(int rowCount, BigDecimal netAmount) {
    }

    /**
     * 查询项目回款台账列表
     */
    public List<ReceiptLedger> listByProject(Long projectId) {
        // 2026-09-09 治理轮 PERF-P2-2：listByProject 不分页，200 上限避免单项目历史台账过大拖慢页面（真库 uk_receipt_project_month 约束每项目每月一行，实际远小于上限）。
        return receiptLedgerMapper.selectList(
            new LambdaQueryWrapper<ReceiptLedger>()
                .eq(ReceiptLedger::getProjectId, projectId)
                .orderByDesc(ReceiptLedger::getReceiptMonth)
                .last("LIMIT 200")
        );
    }

    /**
     * AC-INC-32：6自然月窗口以上市日期为起算点
     */
    private void computeWindow(ReceiptLedger ledger) {
        if (ledger.getWindowStart() != null) {
            LocalDate start = ledger.getWindowStart().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            LocalDate end = start.plusMonths(6).minusDays(1);
            ledger.setWindowEnd(Date.from(end.atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
    }

    /**
     * 判断回款月份是否在6自然月窗口内
     */
    private boolean isInWindow(ReceiptLedger ledger) {
        if (ledger.getWindowStart() == null || ledger.getWindowEnd() == null) {
            return true; // 无窗口约束时默认计入
        }
        if (ledger.getReceiptMonth() == null) {
            return false;
        }
        LocalDate monthStart = LocalDate.parse(ledger.getReceiptMonth() + "-01");
        LocalDate wStart = ledger.getWindowStart().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate wEnd = ledger.getWindowEnd().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return !monthStart.isBefore(wStart) && !monthStart.isAfter(wEnd);
    }
}
