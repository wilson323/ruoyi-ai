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

/** P2-5.x 评审要素「发布」按钮后端契约（draft → published）。 */
@Tag("dev")
class GateElementPublishTest {

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
    @DisplayName("P1 草稿发布成功：published / enabled=1 / version 0→1 / 落 PUBLISH 审计")
    void publishOk() {
        when(mapper.selectById(1L)).thenReturn(element(1L, "draft", "0", 0));
        when(mapper.updateById(any(GateElement.class))).thenReturn(1);
        GateElement out = service.publish(1L, ACTOR);
        assertThat(out.getStatus()).isEqualTo("published");
        assertThat(out.getEnabled()).isEqualTo("1");
        assertThat(out.getVersion()).isEqualTo(1);
        verify(auditLogService).append(any(AuditLog.class));
    }

    @Test
    @DisplayName("P2 非草稿发布 409（STATE_CONFLICT）且零写库")
    void publishNonDraft409() {
        when(mapper.selectById(2L)).thenReturn(element(2L, "published", "1", 1));
        assertCode(() -> service.publish(2L, ACTOR), ApiV1ErrorCode.STATE_CONFLICT);
        Mockito.verify(mapper, Mockito.never()).updateById(any(GateElement.class));
    }

    @Test
    @DisplayName("P3 要素不存在 404；非法 id 400")
    void publishMissingAndInvalidId() {
        assertCode(() -> service.publish(3L, ACTOR), ApiV1ErrorCode.NOT_FOUND);
        assertCode(() -> service.publish(null, ACTOR), ApiV1ErrorCode.PARAM_INVALID);
    }

    // ZK-DIFF-P1-02（页47 规格③）：发布前校验 isVeto=true ⇒ vetoDualRequired=true
    @Test
    @DisplayName("P4 否决项草稿 vetoDualRequired='0' 发布被拒且零写库")
    void publishVetoWithoutDualRejected() {
        GateElement e = GateElement.builder().gateCode("G1").elementCode("G1-V01")
            .elementName("毛利率门槛").isVeto("1").vetoDualRequired("0").sortOrder(1).build();
        e.setId(4L);
        e.setStatus("draft");
        e.setEnabled("0");
        e.setVersion(0);
        when(mapper.selectById(4L)).thenReturn(e);
        assertThatThrownBy(() -> service.publish(4L, ACTOR))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("双签");
        Mockito.verify(mapper, Mockito.never()).updateById(any(GateElement.class));
    }

    @Test
    @DisplayName("P5 否决项草稿 vetoDualRequired='1' 发布通过")
    void publishVetoWithDualOk() {
        GateElement e = GateElement.builder().gateCode("G1").elementCode("G1-V02")
            .elementName("客户需求验证").isVeto("1").vetoDualRequired("1").sortOrder(2).build();
        e.setId(5L);
        e.setStatus("draft");
        e.setEnabled("0");
        e.setVersion(0);
        when(mapper.selectById(5L)).thenReturn(e);
        when(mapper.updateById(any(GateElement.class))).thenReturn(1);
        GateElement out = service.publish(5L, ACTOR);
        assertThat(out.getStatus()).isEqualTo("published");
    }

    @Test
    @DisplayName("P6 legacy 'Y' 否决项归一后仍要求双签：vetoDualRequired='N' 发布被拒")
    void publishLegacyVetoNormalized() {
        GateElement e = GateElement.builder().gateCode("G1").elementCode("G1-V03")
            .elementName("legacy 否决项").isVeto("Y").vetoDualRequired("N").sortOrder(3).build();
        e.setId(6L);
        e.setStatus("draft");
        e.setEnabled("0");
        e.setVersion(0);
        when(mapper.selectById(6L)).thenReturn(e);
        assertThatThrownBy(() -> service.publish(6L, ACTOR))
            .isInstanceOf(IpdBusinessException.class)
            .hasMessageContaining("双签");
    }

    @Test
    @DisplayName("P7 thresholdJson 非法 JSON 发布被拒（规格③：thresholdJson 合法）")
    void publishInvalidThresholdJsonRejected() {
        GateElement e = GateElement.builder().gateCode("G1").elementCode("G1-V04")
            .elementName("门槛项").isVeto("0").thresholdJson("{not-json").sortOrder(4).build();
        e.setId(7L);
        e.setStatus("draft");
        e.setEnabled("0");
        e.setVersion(0);
        when(mapper.selectById(7L)).thenReturn(e);
        assertCode(() -> service.publish(7L, ACTOR), ApiV1ErrorCode.PARAM_INVALID);
    }

}
