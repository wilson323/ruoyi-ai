package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.ReceiptLedger;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ReceiptLedgerMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回款冲减金额上限边界值测试。
 *
 * <p>覆盖三层校验：
 * <ol>
 *   <li>基础合法性：null / 负数 / 零 / 超出 decimal(18,2) 量级；</li>
 *   <li>未冲减余额：累计冲减不得超过该行回款额（净额不得为负），等于上限通过、超 1 分拒绝；</li>
 *   <li>可配置单笔上限 {@code ipd.receipt.refund-max-amount}：默认留空不启用，
 *       配置后等于上限通过、超上限拒绝（<b>业务口径待 owner 拍板</b>）。</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class ReceiptLedgerRefundAmountCapTest {

    @Mock
    private ReceiptLedgerMapper receiptLedgerMapper;
    @Mock
    private ProjectMapper projectMapper;

    private ReceiptLedgerService service;

    @BeforeEach
    void setUp() {
        service = new ReceiptLedgerService(receiptLedgerMapper, projectMapper);
        service.refundMaxAmount = null; // 默认：未配置绝对上限
    }

    /** 无窗口约束的台账行（isInWindow 默认计入）；refunded = 已累计冲减额 */
    private ReceiptLedger ledger(String receipt, String refunded) {
        return ReceiptLedger.builder()
            .id(1L)
            .projectId(100L)
            .receiptMonth("2026-03")
            .receiptAmount(new BigDecimal(receipt))
            .refundAmount(new BigDecimal(refunded))
            .source("RECEIPT")
            .build();
    }

    private void givenLedger(ReceiptLedger l) {
        when(receiptLedgerMapper.selectOne(any())).thenReturn(l);
    }

    // ---------- ① 基础合法性 ----------

    @Test
    @DisplayName("边界：冲减金额为 null → 拒绝且不落库")
    void recordRefund_nullAmount_rejected() {
        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", null))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不能为空");
        verify(receiptLedgerMapper, never()).updateById(any(ReceiptLedger.class));
    }

    @Test
    @DisplayName("边界：冲减金额为负数 → 拒绝")
    void recordRefund_negativeAmount_rejected() {
        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", new BigDecimal("-100.00")))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("必须为正数");
        verify(receiptLedgerMapper, never()).updateById(any(ReceiptLedger.class));
    }

    @Test
    @DisplayName("边界：冲减金额为零 → 拒绝（DTO @DecimalMin(0.01) 语义下沉到 service）")
    void recordRefund_zeroAmount_rejected() {
        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", BigDecimal.ZERO))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("必须为正数");
        verify(receiptLedgerMapper, never()).updateById(any(ReceiptLedger.class));
    }

    @Test
    @DisplayName("边界：金额超出 decimal(18,2) 量级（等价于 Infinity 的兜底）→ 拒绝")
    void recordRefund_amountBeyondColumnRange_rejected() {
        BigDecimal huge = new BigDecimal("1" + "0".repeat(16)); // 10^16，decimal(18,2) 整数部分上限
        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", huge))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("decimal(18,2)");
        verify(receiptLedgerMapper, never()).updateById(any(ReceiptLedger.class));
    }

    // ---------- ② 未冲减余额（净额不得为负） ----------

    @Test
    @DisplayName("边界：冲减额恰好等于未冲减余额 → 通过（等于上限）")
    void recordRefund_equalsOutstandingBalance_passes() {
        givenLedger(ledger("1000.00", "300.00")); // 未冲减余额 700.00

        ReceiptLedger result = service.recordRefund(100L, "2026-03", new BigDecimal("700.00"));

        assertThat(result.getRefundAmount()).isEqualByComparingTo("1000.00");
        verify(receiptLedgerMapper).updateById(result);
    }

    @Test
    @DisplayName("边界：冲减额超未冲减余额 0.01 → 拒绝，净额不得为负")
    void recordRefund_exceedsOutstandingBalance_rejected() {
        givenLedger(ledger("1000.00", "300.00")); // 未冲减余额 700.00

        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", new BigDecimal("700.01")))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("超过当前未冲减余额");
        verify(receiptLedgerMapper, never()).updateById(any(ReceiptLedger.class));
    }

    @Test
    @DisplayName("边界：当月已全额冲减（未冲减余额为 0）→ 拒绝")
    void recordRefund_fullyRefunded_rejected() {
        givenLedger(ledger("1000.00", "1000.00"));

        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", new BigDecimal("0.01")))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("已全额冲减");
        verify(receiptLedgerMapper, never()).updateById(any(ReceiptLedger.class));
    }

    @Test
    @DisplayName("边界：refundAmount 在库中为 null（老数据）→ 按 0 处理，仍受余额约束")
    void recordRefund_nullStoredRefund_treatedAsZero() {
        ReceiptLedger l = ledger("500.00", "0.00");
        l.setRefundAmount(null);
        givenLedger(l);

        ReceiptLedger result = service.recordRefund(100L, "2026-03", new BigDecimal("500.00"));

        assertThat(result.getRefundAmount()).isEqualByComparingTo("500.00");

        assertThatThrownBy(() -> {
            ReceiptLedger l2 = ledger("500.00", "0.00");
            l2.setRefundAmount(null);
            givenLedger(l2);
            service.recordRefund(100L, "2026-03", new BigDecimal("500.01"));
        }).isInstanceOf(IpdBusinessException.class);
    }

    // ---------- ③ 可配置单笔上限（业务口径待 owner 拍板） ----------

    @Test
    @DisplayName("未配置 refund-max-amount → 不额外设限，仅受未冲减余额约束")
    void recordRefund_capNotConfigured_noAbsoluteLimit() {
        givenLedger(ledger("1000000.00", "0.00"));

        ReceiptLedger result = service.recordRefund(100L, "2026-03", new BigDecimal("999999.00"));

        assertThat(result.getRefundAmount()).isEqualByComparingTo("999999.00");
    }

    @Test
    @DisplayName("已配置 refund-max-amount：冲减额等于上限 → 通过（等于上限）")
    void recordRefund_equalsConfiguredCap_passes() {
        service.refundMaxAmount = new BigDecimal("50000.00");
        givenLedger(ledger("1000000.00", "0.00"));

        ReceiptLedger result = service.recordRefund(100L, "2026-03", new BigDecimal("50000.00"));

        assertThat(result.getRefundAmount()).isEqualByComparingTo("50000.00");
    }

    @Test
    @DisplayName("已配置 refund-max-amount：冲减额超上限 0.01 → 拒绝")
    void recordRefund_exceedsConfiguredCap_rejected() {
        service.refundMaxAmount = new BigDecimal("50000.00");
        givenLedger(ledger("1000000.00", "0.00"));

        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", new BigDecimal("50000.01")))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("ipd.receipt.refund-max-amount");
        verify(receiptLedgerMapper, never()).updateById(any(ReceiptLedger.class));
    }

    @Test
    @DisplayName("退款请求在台账查询前即被拒（负数），不产生额外查询")
    void recordRefund_invalidAmount_rejectedBeforeLookup() {
        assertThatThrownBy(() -> service.recordRefund(100L, "2026-03", new BigDecimal("-1.00")))
            .isInstanceOf(IpdBusinessException.class);
        verify(receiptLedgerMapper, never()).selectOne(any());
    }

    // ---------- ④ recordReceipt 旁路：首次录入即带 refundAmount ----------

    @Test
    @DisplayName("旁路收口：首次录入 refundAmount > receiptAmount → 拒绝（净额不得为负）")
    void recordReceipt_refundExceedsReceipt_rejected() {
        ReceiptLedger l = new ReceiptLedger();
        l.setProjectId(100L);
        l.setSource("RECEIPT");
        l.setReceiptMonth("2026-03");
        l.setReceiptAmount(new BigDecimal("100.00"));
        l.setRefundAmount(new BigDecimal("100.01"));

        assertThatThrownBy(() -> service.recordReceipt(l))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("净额不得为负");
        verify(receiptLedgerMapper, never()).insert(any(ReceiptLedger.class));
    }

    @Test
    @DisplayName("旁路收口：首次录入 refundAmount 为负 → 拒绝；为 0 → 放行（尚无退款）")
    void recordReceipt_refundSignBoundary() {
        ReceiptLedger neg = new ReceiptLedger();
        neg.setProjectId(100L);
        neg.setSource("RECEIPT");
        neg.setReceiptMonth("2026-03");
        neg.setReceiptAmount(new BigDecimal("100.00"));
        neg.setRefundAmount(new BigDecimal("-0.01"));
        assertThatThrownBy(() -> service.recordReceipt(neg))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("不能为负数");

        when(projectMapper.selectById(100L)).thenReturn(null); // 金额侧已通过，走项目校验分支
        ReceiptLedger zero = new ReceiptLedger();
        zero.setProjectId(100L);
        zero.setSource("RECEIPT");
        zero.setReceiptMonth("2026-03");
        zero.setReceiptAmount(new BigDecimal("100.00"));
        zero.setRefundAmount(BigDecimal.ZERO);
        assertThatThrownBy(() -> service.recordReceipt(zero))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("项目不存在");
        verify(receiptLedgerMapper, never()).insert(any(ReceiptLedger.class));
    }
}
