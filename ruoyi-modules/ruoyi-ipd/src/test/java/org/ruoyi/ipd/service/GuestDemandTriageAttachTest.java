package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.agent.service.DemandTriageRun;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.Product;
import org.ruoyi.ipd.domain.Requirement;
import org.ruoyi.ipd.dto.GuestDemandSubmitReq;
import org.ruoyi.ipd.mapper.ProductMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.RequirementMapper;
import org.ruoyi.ipd.service.impl.DefaultStateMachineGuard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class GuestDemandTriageAttachTest {

    private RequirementMapper requirements;
    private ProductMapper products;
    private GuestDemandService service;
    private RecordingTriage triage;

    @BeforeEach
    void setUp() {
        requirements = mock(RequirementMapper.class);
        products = mock(ProductMapper.class);
        ProjectMemberMapper members = mock(ProjectMemberMapper.class);
        IAuditLogService audit = mock(IAuditLogService.class);
        when(audit.append(any(AuditLog.class))).thenAnswer(invocation -> invocation.getArgument(0));
        GuestDemandService.GuestRateLimiter limiter = mock(GuestDemandService.GuestRateLimiter.class);
        when(limiter.tryAcquire(anyString())).thenReturn(true);
        when(requirements.selectCount(any())).thenReturn(0L);
        when(requirements.insert(any(Requirement.class))).thenAnswer(invocation -> {
            Requirement row = invocation.getArgument(0);
            row.setId(42L);
            return 1;
        });
        service = new GuestDemandService(requirements, products, members, audit, limiter);
        DefaultStateMachineGuard guard = new DefaultStateMachineGuard(null, null);
        guard.initRules();
        service.setStateMachineGuard(guard);
        triage = new RecordingTriage();
        service.setDemandTriageRun(triage);
    }

    @Test
    void unboundSubmitPassesRequirementToExistingTriageRun() {
        service.submit(new GuestDemandSubmitReq("深圳智控科技", "王工", null, null, "catalog-access",
            "希望增加离线导出报表功能，支持按月归档", null), "127.0.0.1", "ua");
        assertThat(triage.seen).isNotNull();
        assertThat(triage.seen.getId()).isEqualTo(42L);
        assertThat(triage.seen.getProductLineId()).isNull();
    }

    @Test
    void selectedProductLineDoesNotAttach() {
        Product product = new Product();
        product.setId(9L);
        product.setStatus("ACTIVE");
        product.setProductLineId(19L);
        when(products.selectById(9L)).thenReturn(product);
        service.submit(new GuestDemandSubmitReq("深圳智控科技", "王工", null, 9L, null,
            "希望增加离线导出报表功能，支持按月归档", null), "127.0.0.1", "ua");
        assertThat(triage.seen).isNull();
    }

    @Test
    void transactionOnlyCreatesTriageAfterCommitAndRollbackCreatesNone() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.submit(new GuestDemandSubmitReq("深圳智控科技", "王工", null, null, "catalog-access",
                "希望增加离线导出报表功能，支持按月归档", null), "127.0.0.1", "ua");
            assertThat(triage.seen).isNull();
            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) sync.afterCompletion(1);
            assertThat(triage.seen).isNull();
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.submit(new GuestDemandSubmitReq("深圳智控科技", "王工", null, null, "catalog-access",
                "希望增加离线导出报表功能，支持按月归档", null), "127.0.0.1", "ua");
            assertThat(triage.seen).isNull();
            for (var sync : org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()) sync.afterCommit();
            assertThat(triage.seen).isNotNull();
        } finally { org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization(); }
    }

    /** 只记录游客提交是否把需求交给分拣接线，不创建运行。 */
    private static final class RecordingTriage extends DemandTriageRun {
        private Requirement seen;

        private RecordingTriage() {
            super(null, null, null, null, null, null);
        }

        @Override
        public void attach(Requirement requirement) {
            this.seen = requirement;
        }
    }
}
