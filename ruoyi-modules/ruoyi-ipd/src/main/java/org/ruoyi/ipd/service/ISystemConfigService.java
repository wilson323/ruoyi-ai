package org.ruoyi.ipd.service;

import java.util.Date;
import java.util.List;
import org.ruoyi.ipd.domain.SystemConfig;
import org.ruoyi.ipd.domain.SystemConfigVersion;

/**
 * ISystemConfigService 接口（paiban-05 接口化，实现见 {@link SystemConfigServiceImpl}）。
 */
public interface ISystemConfigService {

    public String getValue(String key, String defaultValue);

    public int getIntValue(String key, int defaultValue);

    public boolean getBoolValue(String key, boolean defaultValue);

    public List<SystemConfig> list();

    public void update(String key, String value);

    public void update(String key, String value, Long operatorId);

    public String getValueAsOf(String key, Date asOf);

    public java.util.LinkedHashMap<String, Object> resolveAsOf(String key, Date asOf);

    public List<SystemConfigVersion> listVersions(String key, int limit);

    public void invalidate(String key);

    public void invalidateAll();

}
