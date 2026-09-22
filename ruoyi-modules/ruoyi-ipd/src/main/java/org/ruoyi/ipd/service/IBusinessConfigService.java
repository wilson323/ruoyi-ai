package org.ruoyi.ipd.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.ruoyi.ipd.domain.IpdBusinessConfig;

/**
 * IBusinessConfigService 接口（paiban-05 接口化，实现见 {@link BusinessConfigServiceImpl}）。
 */
public interface IBusinessConfigService {

    public String getString(String key);

    public String getString(String key, String defaultValue);

    public BigDecimal getBigDecimal(String key);

    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue);

    public int getInt(String key);

    public int getInt(String key, int defaultValue);

    public boolean getBoolean(String key);

    public boolean getBoolean(String key, boolean defaultValue);

    public Optional<IpdBusinessConfig> getOptional(String key);

    public IpdBusinessConfig update(String key, String newValue, Long operatorId);

    public void invalidate(String key);

    public void invalidateAll();

    public String getConfig(String scope, String scopeId, String configKey, String defaultValue);

    public List<IpdBusinessConfig> listByScope(String scope, String scopeId, String configKeyLike);

    public Long upsert(String scope, String scopeId, String configKey, String configValue, String valueType, Integer enabled, String description, Long operatorId);

    public boolean softDelete(String scope, String scopeId, String configKey, Long operatorId);

}
