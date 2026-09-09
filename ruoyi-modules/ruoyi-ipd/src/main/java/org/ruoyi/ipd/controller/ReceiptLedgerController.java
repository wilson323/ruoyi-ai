package org.ruoyi.ipd.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.audit.IpdAudit;
import org.ruoyi.ipd.audit.IpdEntityType;
import org.ruoyi.ipd.common.ApiV1Response;
import org.ruoyi.ipd.domain.ReceiptLedger;
import org.ruoyi.ipd.dto.ReceiptLedgerCreateReq;
import org.ruoyi.ipd.dto.ReceiptRefundReq;
import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdPermission;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.service.ReceiptLedgerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 销售回款台账 HTTP 端点（P3-4.1 AC-INC-16b/16c/16d/31/31b/32）。
 * 2026-09-08 缺口补齐：recordReceipt / recordRefund / listByProject 此前无 controller
 * 挂载，receipt_ledger 表 0 行（服务存在但写入路径未接线）。
 * 权限同 BonusPool 域：录入/冲减同 COMPUTE（管理员动作），查询同 QUERY。
 */
@RestController
@RequestMapping("/api/v1/receipt-ledgers")
@RequiredArgsConstructor
public class ReceiptLedgerController {

    private final IpdPermission permission;
    private final ReceiptLedgerService service;

    /** 月度回款录入（AC-INC-16c）：凭证可空，source/窗口由服务端定死。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_BONUS_POOL_COMPUTE, type = IpdAuthSession.LOGIN_TYPE)
    @IpdAudit(action = "RECEIPT_CREATE",
              entityType = IpdEntityType.RECEIPT_LEDGER,
              entityId = "#result.data.id",
              reason = "'回款录入 ' + #req.receiptMonth + ' 金额 ' + #req.receiptAmount",
              adminOnly = true)
    @PostMapping
    public ApiV1Response<ReceiptLedger> create(@Valid @RequestBody ReceiptLedgerCreateReq req) {
        ReceiptLedger ledger = new ReceiptLedger();
        ledger.setProjectId(req.projectId());
        ledger.setReceiptMonth(req.receiptMonth());
        ledger.setReceiptAmount(req.receiptAmount());
        ledger.setRefundAmount(req.refundAmount());
        ledger.setVoucherUrl(req.voucherUrl());
        ledger.setVoucherHash(req.voucherHash());
        ledger.setSource("RECEIPT");
        // AOP P1 批注解化（R22 2026-09-09）：审计/超管门禁移交 IpdAuditAspect（adminOnly 同步落库，
        // 行为等价：requireAdmin 先于业务、成功返回后 append），SpEL 在注解字面量里维护。
        return ApiV1Response.ok(service.recordReceipt(ledger));
    }

    /** 退款冲减（AC-INC-31/31b）：窗口内当期冲减，窗口外拒绝回溯。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_BONUS_POOL_COMPUTE, type = IpdAuthSession.LOGIN_TYPE)
    @IpdAudit(action = "RECEIPT_REFUND",
              entityType = IpdEntityType.RECEIPT_LEDGER,
              entityId = "#result.data.id",
              reason = "'退款冲减 ' + #req.month + ' 金额 ' + #req.refundAmount",
              adminOnly = true)
    @PostMapping("/{projectId}/refunds")
    public ApiV1Response<ReceiptLedger> refund(@PathVariable Long projectId,
                                               @Valid @RequestBody ReceiptRefundReq req) {
        return ApiV1Response.ok(service.recordRefund(projectId, req.month(), req.refundAmount()));
    }

    /** 按项目查询回款台账（达成率口径明细，AC-INC-16b）。 */
    @SaCheckPermission(value = IpdPermissionCode.OPERATION_BONUS_POOL_QUERY, type = IpdAuthSession.LOGIN_TYPE)
    @GetMapping("/by-project/{projectId}")
    public ApiV1Response<List<ReceiptLedger>> listByProject(@PathVariable Long projectId) {
        permission.requireInternal();
        return ApiV1Response.ok(service.listByProject(projectId));
    }
}
