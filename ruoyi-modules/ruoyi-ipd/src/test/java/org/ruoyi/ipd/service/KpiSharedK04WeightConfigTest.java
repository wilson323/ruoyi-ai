package org.ruoyi.ipd.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.mapper.KpiRecordMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.security.IpdPermission;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * R219 台账①（AC-CFG-01）：K04 权重配置化验收——{@code system_configs.kpi.k04Weight}
 * 实时读、非法值回退 0.05（对齐 HIGH-4.1 readMinSample / kpi.monthlyDeadlineDay 模式）。
 *
 * <p>Mockito 单元验收；真库 HTTP 复测在波次收尾统一执行，未过前本卡只到 inreview。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
@DisplayName("R219① K04 权重配置化（kpi.k04Weight）")
class KpiSharedK04WeightConfigTest {

    @Mock private KpiRecordMapper kpiRecordMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private ProductGroupMapper productGroupMapper;
    @Mock private IpdPermission permission;
    @Mock private IAuditLogService auditLogService;
    @Mock private ISystemConfigService systemConfigService;
    @Mock private NotificationService notificationService;

    private KpiSharedCollectionService service;

    @BeforeEach
    void setUp() {
        service = new KpiSharedCollectionService(
            kpiRecordMapper, projectMapper, projectMemberMapper, personMapper,
            permission, auditLogService, productGroupMapper, systemConfigService,
            notificationService);
    }

    private void stubConfig(String raw) {
        when(systemConfigService.getValue(eq("kpi.k04Weight"), anyString())).thenReturn(raw);
    }

    @Test
    @DisplayName("配置 0.08 → 实时生效（不再钉死常量面值）")
    void configuredWeightIsUsed() {
        stubConfig("0.08");
        assertThat(service.readK04Weight()).isEqualByComparingTo(new BigDecimal("0.08"));
    }

    @Test
    @DisplayName("缺省/非法值（空串、0、0.50 越上限、非数）→ 回退 0.05")
    void invalidValuesFallBackToDefault() {
        stubConfig("0.05");
        assertThat(service.readK04Weight()).isEqualByComparingTo(new BigDecimal("0.05"));
        stubConfig("0");
        assertThat(service.readK04Weight()).isEqualByComparingTo(new BigDecimal("0.05"));
        stubConfig("0.50");
        assertThat(service.readK04Weight()).isEqualByComparingTo(new BigDecimal("0.05"));
        stubConfig("abc");
        assertThat(service.readK04Weight()).isEqualByComparingTo(new BigDecimal("0.05"));
    }
}
