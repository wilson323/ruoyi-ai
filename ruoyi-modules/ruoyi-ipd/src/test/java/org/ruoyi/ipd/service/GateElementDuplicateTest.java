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

/** P2-5.x 评审要素「复制」按钮后端契约（复制为新草稿）。 */
@Tag("dev")
class GateElementDuplicateTest {

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
    @DisplayName("D1 复制成功：编码 -DUP、名称（副本）、新行 draft/version=0，源行零改动")
    void duplicateOk() {
        GateElement source = element(1L, "published", "1", 3);
        when(mapper.selectById(1L)).thenReturn(source);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(GateElement.class))).thenAnswer(inv -> {
            GateElement c = inv.getArgument(0);
            c.setId(88L);
            return 1;
        });
        GateElement out = service.duplicate(1L, ACTOR);
        assertThat(out.getElementCode()).isEqualTo("G1-E01-DUP");
        assertThat(out.getElementName()).isEqualTo("客户验证完成（副本）");
        assertThat(out.getStatus()).isEqualTo("draft");
        assertThat(out.getVersion()).isZero();
        assertThat(out.getEnabled()).isEqualTo("0");
        assertThat(source.getElementCode()).isEqualTo("G1-E01");
        assertThat(source.getStatus()).isEqualTo("published");
        verify(auditLogService).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("D2 编码/名称被占用时序号递增（-DUP2 与（副本）2）")
    void duplicateBumpsSuffix() {
        when(mapper.selectById(2L)).thenReturn(element(2L, "archived", "0", 1));
        when(mapper.selectCount(any())).thenReturn(1L, 0L, 1L, 0L);
        when(mapper.insert(any(GateElement.class))).thenReturn(1);
        GateElement out = service.duplicate(2L, ACTOR);
        assertThat(out.getElementCode()).isEqualTo("G1-E01-DUP2");
        assertThat(out.getElementName()).isEqualTo("客户验证完成（副本）2");
    }

    @Test
    @DisplayName("D3 源要素不存在 404 且零写库")
    void duplicateMissingSource404() {
        assertCode(() -> service.duplicate(9L, ACTOR), ApiV1ErrorCode.NOT_FOUND);
        Mockito.verify(mapper, Mockito.never()).insert(any(GateElement.class));
    }

}
