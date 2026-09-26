package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.domain.SystemConfig;
import org.ruoyi.ipd.domain.SystemConfigVersion;
import org.ruoyi.ipd.mapper.SystemConfigMapper;
import org.ruoyi.ipd.mapper.SystemConfigVersionMapper;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R219 台账②（AC-CFG-02）：双配置源漂移——system-configs 写面对 business 权威源的收敛代理。
 *
 * <p>背景：{@code gate.signDeadlineDays} / {@code bonus.poolRate} 两键在 ipd_business_config
 * 与 system_configs 双表都有行，运行态消费方（resolveSignDeadlineDays / readActivePoolRate）
 * 优先读 business、异常才回退 system。旧行为下 PUT /system-configs 只写 system 行 →
 * API 200 + invalidated=true 但实际生效值不变（黑洞）。
 *
 * <p>本类断言写代理契约：双源键写入时把 business 源一并收敛，非双源键不触碰 business，
 * 两源已一致时幂等跳过（不膨胀版本链），并暴露实际生效源供回显。
 * <p>不得据此单测绿标 done（BR-真库：HTTP/真库写入验证未过只可 inreview）。
 */
@Tag("dev")
@DisplayName("R219② 双配置源漂移：写代理收敛 + 生效源回显")
@ExtendWith(MockitoExtension.class)
class R219DualConfigSourceSyncTest {

    @Mock
    private SystemConfigMapper systemConfigMapper;

    @Mock
    private SystemConfigVersionMapper systemConfigVersionMapper;

    @Mock
    private IBusinessConfigService businessConfigService;

    private SystemConfigServiceImpl service;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), ""), SystemConfigVersion.class);
    }

    @BeforeEach
    void setUp() {
        service = new SystemConfigServiceImpl(systemConfigMapper, systemConfigVersionMapper);
        service.setBusinessConfigService(businessConfigService);
    }

    private SystemConfig row(String key, String value) {
        SystemConfig c = SystemConfig.builder()
            .id(1L).configKey(key).configValue(value).valueType("NUMBER").defaultValue(value).build();
        c.setCreateTime(new Date(System.currentTimeMillis() - 90L * 24 * 3600 * 1000));
        return c;
    }

    @Test
    @DisplayName("双源键真值变更：system 行落库后把 business 源一并收敛到新值（AC-CFG-02 复测契约）")
    void dualKeyRealChange_syncsBusinessSource() {
        when(systemConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(row(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "3"));
        when(systemConfigVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(businessConfigService.getString(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS)).thenReturn("3");

        service.update(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "5", 9L);

        verify(businessConfigService).update(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "5", 9L);
    }

    @Test
    @DisplayName("两源已一致：幂等跳过，不写 business 版本链")
    void bothSourcesAlreadyEqual_skipsBusinessWrite() {
        when(systemConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(row(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "3"));
        when(businessConfigService.getString(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS)).thenReturn("3");

        // PUT 值 == system 旧值 且 == business 现值 → 短路 + 代理幂等跳过
        service.update(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "3", 9L);

        verify(businessConfigService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("scale 漂移不算变更：0.05 对 0.0500 数值相等 → 双源均不重复写")
    void scaleDrift_treatedAsNumericEqual() {
        when(systemConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(row(BusinessConfigKeys.BONUS_POOL_RATE, "0.05"));
        when(businessConfigService.getString(BusinessConfigKeys.BONUS_POOL_RATE)).thenReturn("0.0500");

        service.update(BusinessConfigKeys.BONUS_POOL_RATE, "0.0500", 9L);

        // system 侧 0.05 == 0.0500 短路；business 侧 0.0500 == 0.0500 幂等跳过
        verify(businessConfigService, never()).update(any(), any(), any());
    }

    @Test
    @DisplayName("自愈：system 行已等于新值但 business 漂移 → 短路分支内仍收敛 business")
    void businessDrift_selfHealsOnSameSystemValue() {
        when(systemConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(row(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "3"));
        // business 被旁路改成了 7，与本次要写的 3 不一致
        when(businessConfigService.getString(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS)).thenReturn("7");

        service.update(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "3", 9L);

        verify(businessConfigService).update(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "3", 9L);
    }

    @Test
    @DisplayName("非双源键不触碰 business 服务")
    void nonDualKey_neverTouchesBusinessService() {
        when(systemConfigMapper.selectOne(any(LambdaQueryWrapper.class)))
            .thenReturn(row("bonus.salesSource", "BOOKING"));
        when(systemConfigVersionMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        service.update("bonus.salesSource", "SHIPMENT", 9L);

        verify(businessConfigService, never()).update(any(), any(), any());
        verify(businessConfigService, never()).getString(any());
    }

    @Test
    @DisplayName("生效源回显：双源键且 business 行存在 → BUSINESS_CONFIG；非双源键 → SYSTEM_CONFIGS")
    void resolvingSourceObservability() {
        when(businessConfigService.getString(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS)).thenReturn("3");
        assertThat(service.resolvingSourceFor(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS))
            .isEqualTo("BUSINESS_CONFIG");
        assertThat(service.resolvingSourceFor("bonus.salesSource")).isEqualTo("SYSTEM_CONFIGS");
    }

    @Test
    @DisplayName("一致性复核：business 漂移时 isConsistentWithResolvingSource=false（供 controller 破短路）")
    void consistencyCheck_detectsDrift() {
        // business 漂移 7（与传入 3 不等）→ && 短路，无需读 system 行
        when(businessConfigService.getString(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS)).thenReturn("7");

        assertThat(service.isConsistentWithResolvingSource(BusinessConfigKeys.GATE_SIGN_DEADLINE_DAYS, "3"))
            .isFalse();
    }
}
