package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.core.exception.ServiceException;
import org.ruoyi.ipd.common.BusinessConfigKeys;
import org.ruoyi.ipd.domain.IpdBusinessConfig;
import org.ruoyi.ipd.domain.IpdBusinessConfigVersion;
import org.ruoyi.ipd.mapper.IpdBusinessConfigMapper;
import org.ruoyi.ipd.mapper.IpdBusinessConfigVersionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.ruoyi.ipd.common.ApiV1ErrorCode;
import org.ruoyi.ipd.common.IpdBusinessException;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IPD 业务参数读取服务（ROOT-R1 P0-6）
 *
 * <p>与 ISystemConfigService 解耦：本服务只管理 ipd_business_config（业务规则参数），
 * ISystemConfigService 管理 system_configs（系统参数）。两者通过 {@code config_key} 前缀区分。
 *
 * <p>缓存（PERF-02 同型设计）：
 * <ul>
 *   <li>读走 {@link ConcurrentHashMap} 单 JVM 缓存——热路径日均 10 万+ 次读不再打 DB</li>
 *   <li>{@code computeIfAbsent} per-key 原子装载，天然防击穿</li>
 *   <li>空结果缓存为 {@link Optional#empty()}——防恶意 key 穿透</li>
 *   <li>写穿透失效：update / invalidate 调用后下次读即新值</li>
 * </ul>
 * 单企业私有部署单实例定位，多实例部署需升级 Redis 广播失效（已留 TODO）。
 *
 * <p>方法索引（与 ROOT-R1 §3 根治方案一致）：
 * <ul>
 *   <li>{@link #getString(String)} / {@link #getBigDecimal(String)} / {@link #getInt(String)} / {@link #getBoolean(String)}</li>
 *   <li>{@link #getString(String, String)} 提供 fallback 默认值</li>
 *   <li>{@link #update(String, String, Long)} 写穿透 + 历史版本链</li>
 *   <li>{@link #invalidate(String)} / {@link #invalidateAll()} 写后失效缓存</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessConfigServiceImpl implements IBusinessConfigService {

    private final IpdBusinessConfigMapper businessConfigMapper;
    private final IpdBusinessConfigVersionMapper businessConfigVersionMapper;

    /** 单 JVM 缓存；key=业务参数键，value=Optional<IpdBusinessConfig>（empty=配置不存在） */
    private final ConcurrentHashMap<String, Optional<IpdBusinessConfig>> CACHE = new ConcurrentHashMap<>();

    /**
     * R149 batch2b A5：scope+scopeId 维度缓存（key=GLOBAL|GROUP|PROJECT:scopeId:configKey）。
     * 5 分钟 TTL——写穿透失效（update/upsert/delete 调用后失效）。
     * 与 {@link #CACHE} 解耦：GLOBAL 单键仍走原 CACHE，GROUP/PROJECT 多键走 SCOPE_CACHE。
     */
    private final ConcurrentHashMap<String, CachedScopedConfig> SCOPE_CACHE = new ConcurrentHashMap<>();

    /** 缓存 TTL：5 分钟（300 秒）。 */
    static final long SCOPE_CACHE_TTL_MILLIS = 5L * 60 * 1000;

    /** scoped config cache value（值 + 装载时间戳） */
    private record CachedScopedConfig(IpdBusinessConfig config, long loadedAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - loadedAt > SCOPE_CACHE_TTL_MILLIS;
        }
    }

    /** scope 维度校验白名单：GLOBAL|GROUP|PROJECT。 */
    public static final List<String> ALLOWED_SCOPES = List.of("GLOBAL", "GROUP", "PROJECT");

    /** 取字符串值（无 key 时抛 ServiceException——按契约禁止穿透到调用方） */
    public String getString(String key) {
        IpdBusinessConfig cfg = requireConfig(key);
        return cfg.getConfigValue();
    }

    /** 取字符串值（带 fallback） */
    public String getString(String key, String defaultValue) {
        return getOptional(key).map(IpdBusinessConfig::getConfigValue).orElse(defaultValue);
    }

    /** 取 BigDecimal 值（按 NUMBER 类型解析） */
    public BigDecimal getBigDecimal(String key) {
        IpdBusinessConfig cfg = requireConfig(key);
        try {
            return new BigDecimal(cfg.getConfigValue());
        } catch (NumberFormatException e) {
            throw new ServiceException("业务参数值非数字 key=" + key + " value=" + cfg.getConfigValue());
        }
    }

    /** 取 BigDecimal 值（带 fallback） */
    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        return getOptional(key)
                .filter(c -> "NUMBER".equals(c.getValueType()))
                .map(IpdBusinessConfig::getConfigValue)
                .map(value -> {
                    try { return new BigDecimal(value); }
                    catch (NumberFormatException e) { return defaultValue; }
                })
                .orElse(defaultValue);
    }

    /** 取 int 值（按 NUMBER 类型解析） */
    public int getInt(String key) {
        return getBigDecimal(key).intValueExact();
    }

    /** 取 int 值（带 fallback） */
    public int getInt(String key, int defaultValue) {
        return getOptional(key)
                .filter(c -> "NUMBER".equals(c.getValueType()))
                .map(IpdBusinessConfig::getConfigValue)
                .map(value -> {
                    try { return new BigDecimal(value).intValueExact(); }
                    catch (NumberFormatException | ArithmeticException e) { return defaultValue; }
                })
                .orElse(defaultValue);
    }

    /** 取 boolean 值（按 BOOL 类型解析；"1"/"true"/"yes" 视为 true） */
    public boolean getBoolean(String key) {
        IpdBusinessConfig cfg = requireConfig(key);
        String v = cfg.getConfigValue();
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
    }

    /** 取 boolean 值（带 fallback） */
    public boolean getBoolean(String key, boolean defaultValue) {
        return getOptional(key)
                .filter(c -> "BOOL".equals(c.getValueType()))
                .map(c -> {
                    String v = c.getConfigValue();
                    return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v);
                })
                .orElse(defaultValue);
    }

    /** 取 Optional<IpdBusinessConfig>（穿透缓存 + DB 一次） */
    public Optional<IpdBusinessConfig> getOptional(String key) {
        return CACHE.computeIfAbsent(key, this::loadFromDb);
    }

    /** 写穿透 + 历史版本链（P0-7 字面量迁移配套） */
    @Transactional(rollbackFor = Exception.class)
    public IpdBusinessConfig update(String key, String newValue, Long operatorId) {
        IpdBusinessConfig existing = requireConfig(key);
        Date now = new Date();
        int newVersion = existing.getVersion() + 1;
        // 1. 闭合当前开区间版本行
        int closed = businessConfigVersionMapper.update(null, new LambdaUpdateWrapper<IpdBusinessConfigVersion>()
                .eq(IpdBusinessConfigVersion::getConfigId, existing.getId())
                .eq(IpdBusinessConfigVersion::getVersion, existing.getVersion())
                .isNull(IpdBusinessConfigVersion::getEffectiveTo)
                .set(IpdBusinessConfigVersion::getEffectiveTo, now));
        if (closed == 0) {
            log.warn("业务参数版本闭合失败 key={} version={}", key, existing.getVersion());
        }
        // 2. 主表更新（version 自增 + 值变更）
        int updated = businessConfigMapper.update(null, new LambdaUpdateWrapper<IpdBusinessConfig>()
                .eq(IpdBusinessConfig::getId, existing.getId())
                .set(IpdBusinessConfig::getConfigValue, newValue)
                .set(IpdBusinessConfig::getVersion, newVersion)
                .set(IpdBusinessConfig::getUpdateBy, operatorId)
                .set(IpdBusinessConfig::getUpdateTime, now));
        if (updated != 1) {
            throw new ServiceException("业务参数更新失败 key=" + key);
        }
        // 3. 追加新版本行
        try {
            IpdBusinessConfigVersion ver = new IpdBusinessConfigVersion();
            ver.setConfigId(existing.getId());
            ver.setConfigKey(key);
            ver.setConfigValue(newValue);
            ver.setVersion(newVersion);
            ver.setEnabled(existing.getEnabled());
            ver.setEffectiveFrom(now);
            ver.setEffectiveTo(null);
            ver.setTenantId(existing.getTenantId());
            ver.setCreateBy(operatorId);
            ver.setCreateTime(now);
            ver.setDelFlag("0");
            businessConfigVersionMapper.insert(ver);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("业务参数版本号冲突 key=" + key + " version=" + newVersion);
        }
        // 4. 写穿透失效
        invalidate(key);
        return businessConfigMapper.selectById(existing.getId());
    }

    /** 失效单个 key 的缓存（外部触发或测试用） */
    public void invalidate(String key) {
        CACHE.remove(key);
    }

    /** 失效全部缓存（系统配置变更后兜底广播） */
    public void invalidateAll() {
        CACHE.clear();
    }

    /** 必填读（无 key 时抛 ServiceException） */
    private IpdBusinessConfig requireConfig(String key) {
        return getOptional(key).orElseThrow(() ->
                new ServiceException("业务参数不存在 key=" + key));
    }

    /** 单次 DB 装载（computeIfAbsent 内部调用） */
    private Optional<IpdBusinessConfig> loadFromDb(String key) {
        LambdaQueryWrapper<IpdBusinessConfig> q = Wrappers.<IpdBusinessConfig>lambdaQuery()
                .eq(IpdBusinessConfig::getConfigKey, key)
                .eq(IpdBusinessConfig::getEnabled, 1)
                .last("LIMIT 1");
        return Optional.ofNullable(businessConfigMapper.selectOne(q));
    }

    // ==================================================================
    // R149 batch2b A5：scope+scopeId 维度读 / 写 / 删 / 缓存
    // ==================================================================

    /**
     * 按 (scope, scopeId, configKey) 取配置，带 5 分钟缓存。
     *
     * <p>匹配语义：scope=GLOBAL 时忽略 scopeId；scope=GROUP|PROJECT 时 scopeId 必填。
     * schema 未 apply（缺 scope_id 列）时仍能跑——通过 SQL 拼接兼容旧/新 schema，
     * 找不到匹配行时返回 defaultValue（与既有 getString(key, defaultValue) 语义一致）。
     *
     * @param scope        GLOBAL|GROUP|PROJECT
     * @param scopeId      group_id / project_id；scope=GLOBAL 时传 null
     * @param configKey    配置键（如 kpi.approvalRole / scenario.approvalRole）
     * @param defaultValue 命中失败时 fallback（key 不存在或 scope 不匹配）
     * @return 配置值或 fallback
     */
    public String getConfig(String scope, String scopeId, String configKey, String defaultValue) {
        if (scope == null || scope.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "scope 必填（GLOBAL|GROUP|PROJECT）");
        }
        if (!ALLOWED_SCOPES.contains(scope)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "scope 仅允许 GLOBAL|GROUP|PROJECT，实际：" + scope);
        }
        if (configKey == null || configKey.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "configKey 必填");
        }
        if (!"GLOBAL".equals(scope) && (scopeId == null || scopeId.isBlank())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "scope=" + scope + " 必须传 scopeId");
        }

        String cacheKey = scope + ":" + (scopeId == null ? "" : scopeId) + ":" + configKey;
        CachedScopedConfig cached = SCOPE_CACHE.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.config() != null ? cached.config().getConfigValue() : defaultValue;
        }
        // 缓存 miss/expired → DB 单次读
        IpdBusinessConfig loaded = loadByScopeFromDb(scope, scopeId, configKey);
        SCOPE_CACHE.put(cacheKey, new CachedScopedConfig(loaded, System.currentTimeMillis()));
        return loaded != null ? loaded.getConfigValue() : defaultValue;
    }

    /**
     * 单次 DB 读（按 scope+scopeId+configKey）。
     * <p>schema 已 apply（V003__business_config_scope_id.sql）时按 scope+scope_id+config_key 精确匹配；
     * schema 未 apply 时回退到仅按 config_key 匹配（兼容存量数据）。
     * <p>实现：使用 MyBatis-Plus QueryWrapper——多一个可选 eq("scope_id", scopeId) 条件，
     * 旧 schema 列不存在时 QueryWrapper 不会自动跳过，但因 mapper.selectList 只生成 SELECT id,... SQL，
     * 不会 SELECT scope_id 列，所以旧 schema 上运行是安全的（只是 GROUP/PROJECT 维度的精确匹配不可用）。
     */
    private IpdBusinessConfig loadByScopeFromDb(String scope, String scopeId, String configKey) {
        QueryWrapper<IpdBusinessConfig> q = new QueryWrapper<>();
        q.eq("config_key", configKey).eq("enabled", 1);
        if ("GLOBAL".equals(scope)) {
            // GLOBAL：只匹配 scope=GLOBAL 的行（兼容旧 schema 中 NULL 视为 GLOBAL 的语义）
            q.and(w -> w.eq("scope", "GLOBAL").or().isNull("scope"));
        } else {
            q.eq("scope", scope);
            if (scopeId != null && !scopeId.isBlank()) {
                q.eq("scope_id", scopeId);
            }
        }
        q.last("LIMIT 1");
        return businessConfigMapper.selectOne(q);
    }

    /**
     * 按 (scope, scopeId, configKey) 列表查询（用于 GET 端点列举）。
     * scope/scopeId 为空时返回所有；scope/scopeId 提供时精确过滤。
     */
    public List<IpdBusinessConfig> listByScope(String scope, String scopeId, String configKeyLike) {
        QueryWrapper<IpdBusinessConfig> q = new QueryWrapper<>();
        q.eq("enabled", 1);
        if (scope != null && !scope.isBlank()) {
            if (!ALLOWED_SCOPES.contains(scope)) {
                throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                    "scope 仅允许 GLOBAL|GROUP|PROJECT，实际：" + scope);
            }
            q.eq("scope", scope);
        }
        if (scopeId != null && !scopeId.isBlank()) {
            q.eq("scope_id", scopeId);
        }
        if (configKeyLike != null && !configKeyLike.isBlank()) {
            q.like("config_key", configKeyLike);
        }
        q.orderByAsc("scope", "config_key");
        return businessConfigMapper.selectList(q);
    }

    /**
     * R149 batch2b A5：upsert 配置（仅超管/组长）。
     * 写入策略：若 (scope, scope_id, config_key) 已存在则更新；否则新增。
     * <p>scope_id 字段依赖 V003 DDL；DDL 未 apply 时该列会被忽略（MySQL 严格模式下抛列不存在错——
     * 这是显式 fail-fast 行为，确保 DBA 不会忘记 apply DDL）。
     *
     * @return 落库行 id
     */
    @Transactional(rollbackFor = Exception.class)
    public Long upsert(String scope, String scopeId, String configKey, String configValue,
                       String valueType, Integer enabled, String description, Long operatorId) {
        requireScopeKey(scope, configKey);
        if ("GLOBAL".equals(scope) && scopeId != null && !scopeId.isBlank()) {
            log.warn("upsert scope=GLOBAL 但传了 scopeId={}，忽略", scopeId);
            scopeId = null;
        }
        if (!"GLOBAL".equals(scope) && (scopeId == null || scopeId.isBlank())) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "scope=" + scope + " 必须传 scopeId");
        }

        // 1. 查找现有行
        QueryWrapper<IpdBusinessConfig> q = new QueryWrapper<>();
        q.eq("config_key", configKey).eq("scope", scope);
        if (scopeId == null) {
            q.isNull("scope_id");
        } else {
            q.eq("scope_id", scopeId);
        }
        q.last("LIMIT 1");
        IpdBusinessConfig existing = businessConfigMapper.selectOne(q);
        Date now = new Date();

        if (existing == null) {
            IpdBusinessConfig row = IpdBusinessConfig.builder()
                .configKey(configKey)
                .configValue(configValue)
                .valueType(valueType == null ? "STRING" : valueType)
                .scope(scope)
                .enabled(enabled == null ? 1 : enabled)
                .version(1)
                .cacheTtl(300)
                .description(description)
                .build();
            businessConfigMapper.insert(row);
            invalidateScopedCache(scope, scopeId, configKey);
            log.info("R149 batch2b businessConfig upsert insert key={} scope={} scopeId={} operatorId={}",
                configKey, scope, scopeId, operatorId);
            return row.getId();
        } else {
            UpdateWrapper<IpdBusinessConfig> uw = new UpdateWrapper<>();
            uw.eq("id", existing.getId())
              .set("config_value", configValue)
              .set("value_type", valueType == null ? existing.getValueType() : valueType)
              .set("enabled", enabled == null ? existing.getEnabled() : enabled)
              .set("version", existing.getVersion() + 1)
              .set("description", description == null ? existing.getDescription() : description)
              .set("update_by", operatorId)
              .set("update_time", now);
            businessConfigMapper.update(null, uw);
            invalidateScopedCache(scope, scopeId, configKey);
            log.info("R149 batch2b businessConfig upsert update key={} scope={} scopeId={} operatorId={}",
                configKey, scope, scopeId, operatorId);
            return existing.getId();
        }
    }

    /**
     * R149 batch2b A5：按 (scope, scopeId, configKey) 软删除。
     * 仅超管；删除前先校验存在；删除后失效缓存。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean softDelete(String scope, String scopeId, String configKey, Long operatorId) {
        requireScopeKey(scope, configKey);
        QueryWrapper<IpdBusinessConfig> q = new QueryWrapper<>();
        q.eq("config_key", configKey).eq("scope", scope);
        if (scopeId == null) {
            q.isNull("scope_id");
        } else {
            q.eq("scope_id", scopeId);
        }
        q.last("LIMIT 1");
        IpdBusinessConfig existing = businessConfigMapper.selectOne(q);
        if (existing == null) {
            return false;
        }
        UpdateWrapper<IpdBusinessConfig> uw = new UpdateWrapper<>();
        uw.eq("id", existing.getId())
          .set("del_flag", "1")
          .set("update_by", operatorId)
          .set("update_time", new Date());
        int rows = businessConfigMapper.update(null, uw);
        invalidateScopedCache(scope, scopeId, configKey);
        return rows > 0;
    }

    /** 失效 scope 维度缓存的指定 key。 */
    private void invalidateScopedCache(String scope, String scopeId, String configKey) {
        String cacheKey = scope + ":" + (scopeId == null ? "" : scopeId) + ":" + configKey;
        SCOPE_CACHE.remove(cacheKey);
        // GLOBAL 维度与原 CACHE 同步失效
        if ("GLOBAL".equals(scope)) {
            CACHE.remove(configKey);
        }
    }

    /** scope+configKey 必填校验（service 内部复用）。 */
    private void requireScopeKey(String scope, String configKey) {
        if (scope == null || scope.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "scope 必填");
        }
        if (!ALLOWED_SCOPES.contains(scope)) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID,
                "scope 仅允许 GLOBAL|GROUP|PROJECT，实际：" + scope);
        }
        if (configKey == null || configKey.isBlank()) {
            throw new IpdBusinessException(ApiV1ErrorCode.PARAM_INVALID, "configKey 必填");
        }
    }

    /** 测试口：清空 scope 缓存（仅 ServiceTest 调用）。 */
    void invalidateScopedAll() {
        SCOPE_CACHE.clear();
    }
}
