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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P2-5.x 评审要素「归档」按钮 + 管理视图后端契约。 */
@Tag("dev")
class GateElementArchiveTest {

    private static final IpdActor ACTOR = new IpdActor(1L, "admin", "SUPER_ADMIN", null);

    private GateElementMapper mapper;
    private AuditLogMapper auditLogMapper;
    private AuditLogService auditLogService;
    private GateElementService service;

    @BeforeEach
    void setUp() {
        mapper = mock(GateElementMapper.class);
        auditLogService = mock(AuditLogService.class);
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
    @DisplayName("A1 已发布归档成功：archived / enabled=0 / 落 ARCHIVE 审计")
    void archiveOk() {
        when(mapper.selectById(1L)).thenReturn(element(1L, "published", "1", 1));
        when(mapper.updateById(any(GateElement.class))).thenReturn(1);
        GateElement out = service.archive(1L, ACTOR);
        assertThat(out.getStatus()).isEqualTo("archived");
        assertThat(out.getEnabled()).isEqualTo("0");
        verify(auditLogService).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("A2 重复归档 409 且零写库")
    void archiveTwice409() {
        when(mapper.selectById(2L)).thenReturn(element(2L, "archived", "0", 1));
        assertCode(() -> service.archive(2L, ACTOR), ApiV1ErrorCode.STATE_CONFLICT);
        Mockito.verify(mapper, Mockito.never()).updateById(any(GateElement.class));
    }

    @Test
    @DisplayName("A3 管理视图返回全状态（草稿/已发布/归档）；非法 gate 400")
    void manageViewCoversAllStatuses() {
        when(mapper.selectList(any())).thenReturn(java.util.List.of(
            element(1L, "draft", "0", 0), element(2L, "published", "1", 1), element(3L, "archived", "0", 2)));
        assertThat(service.listForManage(null)).hasSize(3);
        assertThat(service.listForManage("G1")).hasSize(3);
        assertCode(() -> service.listForManage("G9"), ApiV1ErrorCode.PARAM_INVALID);
    }

}
