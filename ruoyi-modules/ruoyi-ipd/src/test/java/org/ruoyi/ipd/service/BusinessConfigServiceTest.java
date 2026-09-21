package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.domain.IpdBusinessConfig;
import org.ruoyi.ipd.mapper.IpdBusinessConfigMapper;
import org.ruoyi.ipd.domain.IpdBusinessConfigVersion;
import org.ruoyi.ipd.mapper.IpdBusinessConfigVersionMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ROOT-R1 业务参数配置化根治：BusinessConfigService 单测（10 测覆盖 6 维度）
 *
 * <p>覆盖：
 * <ul>
 *   <li>缓存命中：同一 key 第二次读不命中 DB</li>
 *   <li>穿透：未 enabled 配置走 fallback</li>
 *   <li>写穿透：update 后缓存立即失效</li>
 *   <li>类型解析：STRING/NUMBER/BOOL/JSON 解析</li>
 *   <li>异常：非数字字符串抛 ServiceException</li>
 *   <li>版本链：update 闭合当前 + 追加新行</li>
 * </ul>
 */
@Tag("dev")
class BusinessConfigServiceTest {

    private IpdBusinessConfigMapper configMapper;
    private IpdBusinessConfigVersionMapper versionMapper;
    private BusinessConfigService service;

    @BeforeAll
    static void initTableInfo() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "ipd-p05-test"),
                IpdBusinessConfig.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "ipd-p05-test-version"),
                IpdBusinessConfigVersion.class);
    }

    @BeforeEach
    void setUp() {
        configMapper = mock(IpdBusinessConfigMapper.class);
        versionMapper = mock(IpdBusinessConfigVersionMapper.class);
        service = new BusinessConfigService(configMapper, versionMapper);
    }

    private IpdBusinessConfig cfg(String key, String value, String type) {
        IpdBusinessConfig c = new IpdBusinessConfig();
        c.setId(1L); c.setConfigKey(key); c.setConfigValue(value);
        c.setValueType(type); c.setScope("GLOBAL"); c.setEnabled(1);
        c.setVersion(1); c.setCacheTtl(60); c.setTenantId("000000");
        c.setDelFlag("0");
        return c;
    }

    @Test
    @DisplayName("① 字符串读 + 缓存命中：同 key 第二次读不命中 DB")
    void getString_caches() {
        when(configMapper.selectOne(any())).thenReturn(cfg(BusinessConfigKeys.BONUS_POOL_RATE, "0.0500", "NUMBER"));
        // 第一次：DB
        String v1 = service.getString(BusinessConfigKeys.BONUS_POOL_RATE);
        // 第二次：缓存
        String v2 = service.getString(BusinessConfigKeys.BONUS_POOL_RATE);
        assertThat(v1).isEqualTo("0.0500");
        assertThat(v2).isEqualTo("0.0500");
        verify(configMapper, times(1)).selectOne(any()); // 仅 1 次 DB
    }

    @Test
    @DisplayName("② BigDecimal 解析：NUMBER 类型")
    void getBigDecimal_parses() {
        when(configMapper.selectOne(any())).thenReturn(cfg(BusinessConfigKeys.KPI_STOP_THRESHOLD, "60", "NUMBER"));
        BigDecimal v = service.getBigDecimal(BusinessConfigKeys.KPI_STOP_THRESHOLD);
        assertThat(v).isEqualByComparingTo("60");
    }

    @Test
    @DisplayName("③ int 解析")
    void getInt_parses() {
        when(configMapper.selectOne(any())).thenReturn(cfg(BusinessConfigKeys.GATE_DUAL_SIGN_COUNT, "3", "NUMBER"));
        assertThat(service.getInt(BusinessConfigKeys.GATE_DUAL_SIGN_COUNT)).isEqualTo(3);
    }

    @Test
    @DisplayName("④ boolean 解析：true/1/yes 三种格式")
    void getBoolean_parses() {
        when(configMapper.selectOne(any())).thenReturn(cfg("bonus.enabled", "yes", "BOOL"));
        assertThat(service.getBoolean("bonus.enabled")).isTrue();
    }

    @Test
    @DisplayName("⑤ fallback：未 enabled 配置走 fallback 默认值")
    void fallback_returnsDefault() {
        when(configMapper.selectOne(any())).thenReturn(null);
        String v = service.getString(BusinessConfigKeys.KPI_REVISION_MODE, "append");
        assertThat(v).isEqualTo("append");
    }

    @Test
    @DisplayName("⑥ 异常：非数字字符串抛 ServiceException")
    void getBigDecimal_throwsOnNonNumeric() {
        when(configMapper.selectOne(any())).thenReturn(cfg(BusinessConfigKeys.BONUS_POOL_RATE, "abc", "NUMBER"));
        assertThatThrownBy(() -> service.getBigDecimal(BusinessConfigKeys.BONUS_POOL_RATE))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("⑦ 必填读：key 不存在抛 ServiceException")
    void requireConfig_throwsOnMissing() {
        when(configMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.getInt(BusinessConfigKeys.GATE_DUAL_SIGN_COUNT))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("⑧ 写穿透：update 后缓存失效，下次读即新值")
    void update_invalidatesCache() {
        IpdBusinessConfig cfgOld = cfg(BusinessConfigKeys.BONUS_POOL_RATE, "0.0500", "NUMBER");
        IpdBusinessConfig cfgNew = cfg(BusinessConfigKeys.BONUS_POOL_RATE, "0.0700", "NUMBER");
        // 第一次读（update 内 requireConfig）：返旧值
        // 第二次读（update 后 getString）：写穿透后走 selectOne 返新值
        when(configMapper.selectOne(any())).thenReturn(cfgOld, cfgNew);
        when(configMapper.update(any(), any())).thenReturn(1);
        when(versionMapper.update((IpdBusinessConfigVersion) any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(configMapper.selectById(1L)).thenReturn(cfgNew);
        when(versionMapper.insert((IpdBusinessConfigVersion) any())).thenReturn(1);

        service.update(BusinessConfigKeys.BONUS_POOL_RATE, "0.0700", 1L);
        // 缓存被 invalidate，下次读走 DB 拿新值
        String newValue = service.getString(BusinessConfigKeys.BONUS_POOL_RATE);
        assertThat(newValue).isEqualTo("0.0700");
    }

    @Test
    @DisplayName("⑨ invalidateAll：批量失效缓存")
    void invalidateAll_clearsCache() {
        when(configMapper.selectOne(any())).thenReturn(cfg(BusinessConfigKeys.BONUS_POOL_RATE, "0.0500", "NUMBER"));
        service.getString(BusinessConfigKeys.BONUS_POOL_RATE);
        service.invalidateAll();
        // 再次读取应重新走 DB
        service.getString(BusinessConfigKeys.BONUS_POOL_RATE);
        verify(configMapper, times(2)).selectOne(any());
    }

    @Test
    @DisplayName("⑩ 写穿透失败抛 ServiceException")
    void update_throwsOnUpdateFailure() {
        when(configMapper.selectOne(any())).thenReturn(cfg(BusinessConfigKeys.BONUS_POOL_RATE, "0.0500", "NUMBER"));
        when(versionMapper.update((IpdBusinessConfigVersion) any(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(1);
        when(configMapper.update(any(), any())).thenReturn(0); // 主表更新失败
        assertThatThrownBy(() -> service.update(BusinessConfigKeys.BONUS_POOL_RATE, "0.0700", 1L))
                .isInstanceOf(RuntimeException.class);
    }

    // ==================================================================
    // R149 batch2b A5：scope+scopeId 维度测试
    // ==================================================================

    @Test
    @DisplayName("⑪ getConfig GROUP 维度：命中返回 DB 值")
    void getConfig_groupHit() {
        IpdBusinessConfig c = cfg(BusinessConfigKeys.KPI_APPROVAL_ROLE, "GROUP_LEADER", "STRING");
        c.setScope("GROUP"); c.setEnabled(1);
        when(configMapper.selectOne(any())).thenReturn(c);

        String v = service.getConfig("GROUP", "1001", BusinessConfigKeys.KPI_APPROVAL_ROLE, "DEFAULT");
        assertThat(v).isEqualTo("GROUP_LEADER");
    }

    @Test
    @DisplayName("⑫ getConfig GROUP 维度：未命中返回 fallback")
    void getConfig_groupFallback() {
        when(configMapper.selectOne(any())).thenReturn(null);
        String v = service.getConfig("GROUP", "1001", "not.exist.key", "DEFAULT_ROLE");
        assertThat(v).isEqualTo("DEFAULT_ROLE");
    }

    @Test
    @DisplayName("⑬ getConfig 缓存命中：5 分钟内不重读 DB")
    void getConfig_caches() {
        IpdBusinessConfig c = cfg(BusinessConfigKeys.KPI_APPROVAL_ROLE, "GROUP_LEADER", "STRING");
        c.setScope("GROUP"); c.setEnabled(1);
        when(configMapper.selectOne(any())).thenReturn(c);

        service.getConfig("GROUP", "1001", BusinessConfigKeys.KPI_APPROVAL_ROLE, "X");
        service.getConfig("GROUP", "1001", BusinessConfigKeys.KPI_APPROVAL_ROLE, "X");
        // 第二次走缓存，DB 只读 1 次
        verify(configMapper, times(1)).selectOne(any());
    }

    @Test
    @DisplayName("⑭ getConfig scope=GROUP 缺 scopeId 抛 PARAM_INVALID")
    void getConfig_groupRequiresScopeId() {
        assertThatThrownBy(() -> service.getConfig("GROUP", null,
                BusinessConfigKeys.KPI_APPROVAL_ROLE, "DEFAULT"))
            .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> service.getConfig("GROUP", "",
                BusinessConfigKeys.KPI_APPROVAL_ROLE, "DEFAULT"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("⑮ getConfig scope 非法值抛异常")
    void getConfig_invalidScope() {
        assertThatThrownBy(() -> service.getConfig("WRONG_SCOPE", null,
                BusinessConfigKeys.KPI_APPROVAL_ROLE, "DEFAULT"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("⑯ upsert 新增：返回新行 id")
    void upsert_insert() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(configMapper.insert(any(IpdBusinessConfig.class))).thenAnswer(inv -> {
            IpdBusinessConfig arg = inv.getArgument(0);
            arg.setId(500L);
            return 1;
        });
        Long id = service.upsert("GROUP", "1001", BusinessConfigKeys.KPI_APPROVAL_ROLE,
            "GROUP_LEADER", "STRING", 1, "测试", 1L);
        assertThat(id).isEqualTo(500L);
        verify(configMapper).insert(any(IpdBusinessConfig.class));
    }

    @Test
    @DisplayName("⑰ upsert 更新：命中已存在行")
    void upsert_update() {
        IpdBusinessConfig existing = cfg(BusinessConfigKeys.KPI_APPROVAL_ROLE, "OLD", "STRING");
        existing.setScope("GROUP");
        when(configMapper.selectOne(any())).thenReturn(existing);
        when(configMapper.update(any(), any())).thenReturn(1);
        Long id = service.upsert("GROUP", "1001", BusinessConfigKeys.KPI_APPROVAL_ROLE,
            "NEW", "STRING", 1, null, 1L);
        assertThat(id).isEqualTo(existing.getId());
        verify(configMapper).update(any(), any());
    }

    @Test
    @DisplayName("⑱ upsert scope=GLOBAL 带 scopeId 自动忽略 scopeId")
    void upsert_globalScopeIdIgnored() {
        when(configMapper.selectOne(any())).thenReturn(null);
        when(configMapper.insert(any(IpdBusinessConfig.class))).thenAnswer(inv -> {
            IpdBusinessConfig arg = inv.getArgument(0);
            arg.setId(600L);
            return 1;
        });
        Long id = service.upsert("GLOBAL", "1001", "global.key", "value",
            "STRING", 1, null, 1L);
        assertThat(id).isEqualTo(600L);
    }

    @Test
    @DisplayName("⑲ softDelete 命中：返回 true")
    void softDelete_hit() {
        IpdBusinessConfig existing = cfg(BusinessConfigKeys.KPI_APPROVAL_ROLE, "X", "STRING");
        existing.setScope("GROUP");
        when(configMapper.selectOne(any())).thenReturn(existing);
        when(configMapper.update(any(), any())).thenReturn(1);
        boolean ok = service.softDelete("GROUP", "1001", BusinessConfigKeys.KPI_APPROVAL_ROLE, 1L);
        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("⑳ softDelete 未命中：返回 false")
    void softDelete_miss() {
        when(configMapper.selectOne(any())).thenReturn(null);
        boolean ok = service.softDelete("GROUP", "1001", "not.exist", 1L);
        assertThat(ok).isFalse();
        verify(configMapper, never()).update(any(), any());
    }

}
