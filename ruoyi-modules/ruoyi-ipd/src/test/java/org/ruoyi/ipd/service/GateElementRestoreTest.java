package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.domain.GateElement;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.GateElementMapper;
import org.ruoyi.ipd.security.IpdActor;
import org.ruoyi.ipd.security.IpdPermissionCode;
import org.ruoyi.ipd.security.IpdRolePermissionCatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2-5.x 评审要素「恢复」按钮后端契约（archived → draft）。 */
@Tag("dev")
class GateElementRestoreTest {

    private static final IpdActor ACTOR = new IpdActor(1L, "admin", "SUPER_ADMIN", null);

    private GateElementMapper mapper;
    private AuditLogMapper auditLogMapper;
    private IAuditLogService auditLogService;
    private GateElementService service;

    @BeforeEach
    void setUp() {
        mapper = mock(GateElementMapper.class);
        auditLogService = mock(IAuditLogService.class);
        when(auditLogService.append(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
        auditLogMapper = mock(AuditLogMapper.class);
        service = new GateElementService(mapper, auditLogService, auditLogMapper);
    }

    private static GateElement element(long id, String status, String enabled, int version) {
        GateElement e = GateElement.builder().gateCode("G1").elementCode("G1-E01")
            .elementName("客户验证完成").isVeto("0").sortOrder(1).build();
        e.setId(id);
        e.setStatus(status);
        e.setEnabled(enabled);
        e.setVersion(version);
        return e;
    }

    private static void assertCode(Runnable call, ApiV1ErrorCode code) {
        assertThatThrownBy(call::run).isInstanceOf(IpdBusinessException.class)
            .extracting("errorCode").isEqualTo(code);
    }


    @Test
    @DisplayName("R1 归档恢复成功：archived → draft、enabled=0、version 保留、落 RESTORE 审计")
    void restoreOk() {
        when(mapper.selectById(1L)).thenReturn(element(1L, "archived", "0", 2));
        when(mapper.updateById(any(GateElement.class))).thenReturn(1);
        GateElement out = service.restore(1L, ACTOR);
        assertThat(out.getStatus()).isEqualTo("draft");
        assertThat(out.getEnabled()).isEqualTo("0");
        assertThat(out.getVersion()).isEqualTo(2);
        verify(auditLogService).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("R2 非归档状态恢复 409（同态重放第二次即 409）且零写库")
    void restoreNonArchived409() {
        when(mapper.selectById(2L)).thenReturn(element(2L, "draft", "0", 2));
        assertCode(() -> service.restore(2L, ACTOR), ApiV1ErrorCode.STATE_CONFLICT);
        when(mapper.selectById(3L)).thenReturn(element(3L, "published", "1", 1));
        assertCode(() -> service.restore(3L, ACTOR), ApiV1ErrorCode.STATE_CONFLICT);
        Mockito.verify(mapper, Mockito.never()).updateById(any(GateElement.class));
    }

    @Test
    @DisplayName("R3 恢复后再发布 version 单调递增（2 → 3）")
    void restoreThenPublishBumpsVersion() {
        when(mapper.selectById(1L)).thenReturn(element(1L, "archived", "0", 2));
        when(mapper.updateById(any(GateElement.class))).thenReturn(1);
        service.restore(1L, ACTOR);
        GateElement out = service.publish(1L, ACTOR);
        assertThat(out.getStatus()).isEqualTo("published");
        assertThat(out.getVersion()).isEqualTo(3);
    }


    @Test
    @DisplayName("R4 权限目录登记：SUPER_ADMIN 持 ipd:gate-element:restore（防 30001 注解拒）")
    void superAdminHoldsRestoreCode() {
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", IpdPermissionCode.OPERATION_GATE_ELEMENT_RESTORE))
            .isTrue();
        assertThat(IpdRolePermissionCatalog.has("SUPER_ADMIN", IpdPermissionCode.OPERATION_GATE_ELEMENT_COPY))
            .isTrue();
    }

}
