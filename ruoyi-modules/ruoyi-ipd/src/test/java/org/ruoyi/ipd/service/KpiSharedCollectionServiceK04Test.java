package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.LandedScenario;
import org.ruoyi.ipd.domain.SwitchingAcceptance;
import org.ruoyi.ipd.mapper.LandedScenarioMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.ruoyi.ipd.mapper.ProductGroupMapper;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ProjectMemberMapper;
import org.ruoyi.ipd.mapper.SwitchingAcceptanceMapper;
import org.ruoyi.ipd.security.IpdPermission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P2-1 K04 双认定单测（用户拍板：销售报备 + 交付验收都认定，K04 source 改双来源判定）。
 *
 * <p>覆盖用户拍板的三种 K04 结果 + 边界：
 * <ol>
 *   <li>仅销售报备 → K04 source = {@code SALES_ACCEPTANCE}</li>
 *   <li>仅交付验收 → K04 source = {@code DELIVERY_ACCEPTANCE}</li>
 *   <li>两者都有 → K04 source = {@code DUAL_ACCEPTANCE}</li>
 *   <li>两者都没有 → K04 source 回退 {@code SALES_ACCEPTANCE}（保持历史语义，不抛错）</li>
 * </ol>
 *
 * <p>策略：直接调 {@link KpiSharedCollectionService#resolveK04Source(Long)}（package-private，
 * 同包测试可见），mock 两个 Mapper 的 {@code selectCount} 返回值，断言 source 标签。
 *
 * <p>{@code @Tag("dev")}：本地测试（mvn -Dtest=...），不入 CI 套件（防假绿陷阱）。
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class KpiSharedCollectionServiceK04Test {

    @Mock private org.ruoyi.ipd.mapper.KpiRecordMapper kpiRecordMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private ProjectMemberMapper projectMemberMapper;
    @Mock private PersonMapper personMapper;
    @Mock private IpdPermission permission;
    @Mock private IAuditLogService auditLogService;
    @Mock private ProductGroupMapper productGroupMapper;
    @Mock private ISystemConfigService systemConfigService;
    @Mock private NotificationService notificationService;
    @Mock private LandedScenarioMapper landedScenarioMapper;
    @Mock private SwitchingAcceptanceMapper switchingAcceptanceMapper;

    private KpiSharedCollectionService service;

    private static final Long PROJECT_ID = 1001L;

    private KpiSharedCollectionService newServiceWithDualMappers() {
        return new KpiSharedCollectionService(
            kpiRecordMapper, projectMapper, projectMemberMapper, personMapper,
            permission, auditLogService, productGroupMapper, systemConfigService,
            notificationService, landedScenarioMapper, switchingAcceptanceMapper);
    }

    @Test
    @DisplayName("[P2-1] K04 仅销售报备 → source = SALES_ACCEPTANCE")
    void testK04_only_sales() {
        // 销售报备：landed_scenarios 有项目记录（count>0）
        when(landedScenarioMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(3L);
        // 交付验收：switching_acceptance 无项目记录
        when(switchingAcceptanceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service = newServiceWithDualMappers();
        String source = service.resolveK04Source(PROJECT_ID);

        assertThat(source).isEqualTo(KpiSharedCollectionService.K04_SOURCE_SALES_ONLY);
    }

    @Test
    @DisplayName("[P2-1] K04 仅交付验收 → source = DELIVERY_ACCEPTANCE")
    void testK04_only_delivery() {
        // 销售报备：无
        when(landedScenarioMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        // 交付验收：有
        when(switchingAcceptanceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        service = newServiceWithDualMappers();
        String source = service.resolveK04Source(PROJECT_ID);

        assertThat(source).isEqualTo(KpiSharedCollectionService.K04_SOURCE_DELIVERY_ONLY);
    }

    @Test
    @DisplayName("[P2-1] K04 双来源（销售+交付都认定）→ source = DUAL_ACCEPTANCE")
    void testK04_both() {
        // 销售报备：有（landed_scenarios 多条落地场景）
        when(landedScenarioMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);
        // 交付验收：有（switching_acceptance 多月验收）
        when(switchingAcceptanceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        service = newServiceWithDualMappers();
        String source = service.resolveK04Source(PROJECT_ID);

        assertThat(source).isEqualTo(KpiSharedCollectionService.K04_SOURCE_DUAL);
    }

    @Test
    @DisplayName("[P2-1] K04 边界：两者都没有 → source 回退 SALES_ACCEPTANCE（不抛错）")
    void testK04_neither_returns_sales_only_fallback() {
        // 销售报备：无
        when(landedScenarioMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        // 交付验收：无
        when(switchingAcceptanceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        service = newServiceWithDualMappers();
        String source = service.resolveK04Source(PROJECT_ID);

        // 不抛错；K04 仍按 landed/planned 计算得分；source 保持历史语义
        assertThat(source).isEqualTo(KpiSharedCollectionService.K04_SOURCE_SALES_ONLY);
    }

    @Test
    @DisplayName("[P2-1] K04 边界：两个 Mapper 都未注入（legacy 路径）→ 回退 SALES_ACCEPTANCE")
    void testK04_both_mappers_null_legacy_fallback() {
        // 模拟旧 9 参数构造器（landedScenarioMapper/switchingAcceptanceMapper 传 null）
        service = new KpiSharedCollectionService(
            kpiRecordMapper, projectMapper, projectMemberMapper, personMapper,
            permission, auditLogService, productGroupMapper, systemConfigService,
            notificationService);

        String source = service.resolveK04Source(PROJECT_ID);

        assertThat(source).isEqualTo(KpiSharedCollectionService.K04_SOURCE_SALES_ONLY);
    }

    @Test
    @DisplayName("[P2-1] K04 边界：projectId 为 null → 回退 SALES_ACCEPTANCE（防御性兜底）")
    void testK04_null_projectId_returns_sales_only() {
        service = newServiceWithDualMappers();

        String source = service.resolveK04Source(null);

        // 不抛错；按既有的 source 语义兜底
        assertThat(source).isEqualTo(KpiSharedCollectionService.K04_SOURCE_SALES_ONLY);
    }

    @Test
    @DisplayName("[P2-1] K04 边界：LambdaQueryWrapper 传入两个 Mapper 的 selectCount")
    void testK04_query_wrapper_passed_to_mappers() {
        when(landedScenarioMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
        when(switchingAcceptanceMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        service = newServiceWithDualMappers();
        service.resolveK04Source(PROJECT_ID);

        // 两个 mapper 各被调一次（说明存在性判定走 selectCount 而非 selectList）
        org.mockito.Mockito.verify(landedScenarioMapper).selectCount(
            org.mockito.ArgumentMatchers.argThat(w -> w instanceof LambdaQueryWrapper));
        org.mockito.Mockito.verify(switchingAcceptanceMapper).selectCount(
            org.mockito.ArgumentMatchers.argThat(w -> w instanceof LambdaQueryWrapper));
    }

    @Test
    @DisplayName("[P2-1] K04 三种 source 常量值与拍板一致")
    void testK04_source_constants_match_pinned_values() {
        // 用户拍板字面值（双认定 source 三种枚举）：与 KpiSharedCollectionService 常量一致
        assertThat(KpiSharedCollectionService.K04_SOURCE_SALES_ONLY).isEqualTo("SALES_ACCEPTANCE");
        assertThat(KpiSharedCollectionService.K04_SOURCE_DELIVERY_ONLY).isEqualTo("DELIVERY_ACCEPTANCE");
        assertThat(KpiSharedCollectionService.K04_SOURCE_DUAL).isEqualTo("DUAL_ACCEPTANCE");
    }

    // 显式类型引用，避免 unused import 警告
    @SuppressWarnings("unused")
    private static final Class<?> LANDED_TYPE = LandedScenario.class;
    @SuppressWarnings("unused")
    private static final Class<?> SW_TYPE = SwitchingAcceptance.class;
}
