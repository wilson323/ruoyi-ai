package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ReceiptLedger;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ReceiptLedgerMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IReceiptLedgerService 接口（paiban-05 接口化，实现见 {@link ReceiptLedgerService}）。
 */
public interface IReceiptLedgerService {

    /** * 录入回款（AC-INC-16c） */
    /** * 校验：source 必须为 RECEIPT；月份在窗口内才计入达成率 */
    ReceiptLedger recordReceipt(ReceiptLedger ledger);

    /** * 退款冲减（AC-INC-31/31b） */
    /** * 窗口内退款 → 当期冲减；窗口外退款 → 不回溯扣减（拒绝） */
    ReceiptLedger recordRefund(Long projectId, String month, BigDecimal refundAmount);

    /** * 计算达成率（AC-INC-16b/16d） */
    /** * 达成率 = SUM(窗口内净回款) / 目标销售额 */
    BigDecimal calculateAchievementRate(Long projectId, BigDecimal targetSales);

    /** * 查询项目回款台账列表 */
    List<ReceiptLedger> listByProject(Long projectId);

}
