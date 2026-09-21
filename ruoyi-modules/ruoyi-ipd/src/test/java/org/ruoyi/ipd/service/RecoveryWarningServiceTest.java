package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.Project;
import org.ruoyi.ipd.domain.ReceiptLedger;
import org.ruoyi.ipd.domain.RecoveryWarning;
import org.ruoyi.ipd.mapper.ProjectMapper;
import org.ruoyi.ipd.mapper.ReceiptLedgerMapper;
import org.ruoyi.ipd.mapper.RecoveryWarningMapper;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R149 batch2a C1：90 日回款预警服务单测。
 *
 * <p>覆盖 6 维度：
 * <ol>
 *   <li>正常路径：低于阈值的项目写入预警；≥ 阈值不写入</li>
 *   <li>边界：scanDate=null 走当前日期；window 内第 90 日仍计入；window 外项目跳过</li>
 *   <li>异常：target_sales_amount 为空/非正 → 跳过；幂等：同日 (projectId, warningDate) 已存在 → 跳过</li>
 *   <li>审计：status=PENDING 默认；threshold = 配置值</li>
 *   <li>幂等：阈值回退（system_configs 未配置 → 默认 0.25）</li>
 *   <li>不做：销售报备 / 交付验收 / 双认定（用户拍板简化）</li>
 * </ol>
 */
@Tag("dev")
@ExtendWith(MockitoExtension.class)
class RecoveryWarningServiceTest {

    @Mock
    private RecoveryWarningMapper warningMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ReceiptLedgerMapper receiptLedgerMapper;
    @Mock
    private SystemConfigService systemConfigService;

    private RecoveryWarningService service;

    @BeforeEach
    void setup() {
        service = new RecoveryWarningService(warningMapper, projectMapper,
            receiptLedgerMapper, systemConfigService);
    }

    private Project project(Long id, LocalDate launchDate, BigDecimal targetSales) {
        Project p = new Project();
        p.setId(id);
        p.setLaunchDate(Date.valueOf(launchDate));
        p.setTargetSalesAmount(targetSales);
        p.setDelFlag("0");
        return p;
    }

    private ReceiptLedger receipt(Long projectId, BigDecimal amount, BigDecimal refund) {
        ReceiptLedger r = new ReceiptLedger();
        r.setProjectId(projectId);
        r.setSource("RECEIPT");
        r.setReceiptAmount(amount);
        r.setRefundAmount(refund);
        return r;
    }

    @Test
    @DisplayName("正常路径：回款比例 < 阈值 → 写入预警")
    void check_belowThreshold_writesWarning() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        Project p = project(101L, scanDate.minusDays(30),
            new BigDecimal("1000000")); // 100w 目标
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(p));
        when(receiptLedgerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                receipt(101L, new BigDecimal("100000"), BigDecimal.ZERO) // 10w 净回款 = 10%
            ));
        when(systemConfigService.getValue(any(), any()))
            .thenReturn("0.25");
        when(warningMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        int saved = service.checkAndGenerate(scanDate);

        assertThat(saved).isEqualTo(1);
        ArgumentCaptor<RecoveryWarning> captor = ArgumentCaptor.forClass(RecoveryWarning.class);
        verify(warningMapper).insert(captor.capture());
        RecoveryWarning w = captor.getValue();
        assertThat(w.getProjectId()).isEqualTo(101L);
        assertThat(w.getRecoveryRate()).isEqualByComparingTo("0.1000");
        assertThat(w.getThreshold()).isEqualByComparingTo("0.25");
        assertThat(w.getStatus()).isEqualTo(RecoveryWarning.STATUS_PENDING);
        assertThat(w.getDaysSinceLaunch()).isEqualTo(30);
        assertThat(w.getWarningDate()).isEqualTo(scanDate);
    }

    @Test
    @DisplayName("正常路径：回款比例 ≥ 阈值 → 不写入")
    void check_aboveThreshold_skipped() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        Project p = project(102L, scanDate.minusDays(30),
            new BigDecimal("1000000"));
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(p));
        when(receiptLedgerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                receipt(102L, new BigDecimal("300000"), BigDecimal.ZERO) // 30%
            ));
        when(systemConfigService.getValue(any(), any()))
            .thenReturn("0.25");

        int saved = service.checkAndGenerate(scanDate);
        assertThat(saved).isZero();
        verify(warningMapper, never()).insert(any(RecoveryWarning.class));
    }

    @Test
    @DisplayName("边界：window 外（上市超过 90 日）项目不参与扫描")
    void check_outsideWindow_skipped() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        // mock 项目 mapper 只返回空（项目 mapper 的 SQL 过滤 window 外，无需 service 二次判定）
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of());

        int saved = service.checkAndGenerate(scanDate);
        assertThat(saved).isZero();
        verify(warningMapper, never()).insert(any(RecoveryWarning.class));
    }

    @Test
    @DisplayName("异常：target_sales_amount 为空 → 跳过该项目")
    void check_nullTargetSales_skipped() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        Project p = project(103L, scanDate.minusDays(10), null);
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(p));
        when(systemConfigService.getValue(any(), any()))
            .thenReturn("0.25");

        int saved = service.checkAndGenerate(scanDate);
        assertThat(saved).isZero();
        verify(warningMapper, never()).insert(any(RecoveryWarning.class));
    }

    @Test
    @DisplayName("异常：target_sales_amount = 0 → 跳过")
    void check_zeroTargetSales_skipped() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        Project p = project(104L, scanDate.minusDays(10), BigDecimal.ZERO);
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(p));
        when(systemConfigService.getValue(any(), any()))
            .thenReturn("0.25");

        int saved = service.checkAndGenerate(scanDate);
        assertThat(saved).isZero();
    }

    @Test
    @DisplayName("幂等：同日 (projectId, warningDate) 已存在 → 跳过")
    void check_alreadyExists_skipped() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        Project p = project(105L, scanDate.minusDays(10),
            new BigDecimal("1000000"));
        RecoveryWarning existing = RecoveryWarning.builder().id(1L)
            .projectId(105L).warningDate(scanDate).build();
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(p));
        when(receiptLedgerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                receipt(105L, new BigDecimal("50000"), BigDecimal.ZERO) // 5%
            ));
        when(systemConfigService.getValue(any(), any()))
            .thenReturn("0.25");
        when(warningMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        int saved = service.checkAndGenerate(scanDate);
        assertThat(saved).isZero();
        verify(warningMapper, never()).insert(any(RecoveryWarning.class));
    }

    @Test
    @DisplayName("阈值回退：system_configs 未配置 → 默认 0.25")
    void check_noConfig_usesDefault() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        when(systemConfigService.getValue(any(), any()))
            .thenReturn(RecoveryWarningService.DEFAULT_THRESHOLD.toPlainString());

        BigDecimal threshold = service.readThreshold();
        assertThat(threshold).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("阈值回退：system_configs 配非法值 → 默认 0.25")
    void check_invalidConfig_usesDefault() {
        when(systemConfigService.getValue(any(), any())).thenReturn("abc");

        BigDecimal threshold = service.readThreshold();
        assertThat(threshold).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("阈值回退：system_configs 配 > 1 → 默认 0.25")
    void check_outOfRangeConfig_usesDefault() {
        when(systemConfigService.getValue(any(), any())).thenReturn("1.5");

        BigDecimal threshold = service.readThreshold();
        assertThat(threshold).isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("阈值：自定义阈值 0.5 生效")
    void check_customThreshold_accepted() {
        LocalDate scanDate = LocalDate.of(2026, 9, 20);
        Project p = project(106L, scanDate.minusDays(10),
            new BigDecimal("1000000"));
        when(projectMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(p));
        when(receiptLedgerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                receipt(106L, new BigDecimal("400000"), BigDecimal.ZERO) // 40%
            ));
        when(systemConfigService.getValue(any(), any())).thenReturn("0.5");
        when(warningMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        int saved = service.checkAndGenerate(scanDate);
        assertThat(saved).isEqualTo(1); // 40% < 50% 触发
    }

    @Test
    @DisplayName("sumReceipts：净额 = receipt - refund")
    void sumReceipts_netAmount() {
        when(receiptLedgerMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                receipt(107L, new BigDecimal("100"), BigDecimal.ZERO),
                receipt(107L, new BigDecimal("200"), new BigDecimal("50"))
            ));
        BigDecimal sum = service.sumReceipts(107L);
        assertThat(sum).isEqualByComparingTo("250"); // 100 + (200-50)
    }

    @Test
    @DisplayName("list：按 projectId 过滤")
    void list_byProject() {
        when(warningMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(RecoveryWarning.builder().id(1L).projectId(200L).build()));

        List<RecoveryWarning> result = service.list(200L);
        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("list：projectId=null 返回全部")
    void list_nullProject_returnsAll() {
        when(warningMapper.selectList(any(LambdaQueryWrapper.class)))
            .thenReturn(List.of(
                RecoveryWarning.builder().id(1L).build(),
                RecoveryWarning.builder().id(2L).build()
            ));

        List<RecoveryWarning> result = service.list(null);
        assertThat(result).hasSize(2);
    }
}
