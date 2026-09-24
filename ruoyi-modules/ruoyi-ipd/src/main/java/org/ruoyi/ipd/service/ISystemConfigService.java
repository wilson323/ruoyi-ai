package org.ruoyi.ipd.service;

import java.util.Date;
import java.util.List;
import org.ruoyi.ipd.domain.SystemConfig;
import org.ruoyi.ipd.domain.SystemConfigVersion;

/**
 * ISystemConfigService 接口（paiban-05 接口化，实现见 {@link SystemConfigServiceImpl}）。
 */
public interface ISystemConfigService {

    /**
     * 按键读取配置字符串；键不存在或空值时返回 defaultValue。
     *
     * @param key          配置键
     * @param defaultValue 缺失时的回落值
     * @return 当前生效字符串
     */
    public String getValue(String key, String defaultValue);

    /**
     * 按键读取整数配置；解析失败或缺失时返回 defaultValue。
     *
     * @param key          配置键
     * @param defaultValue 缺失 / 非法时的回落值
     * @return 当前生效整数
     */
    public int getIntValue(String key, int defaultValue);

    /**
     * 按键读取布尔配置（识别 true/false/1/0 等约定）；缺失时返回 defaultValue。
     *
     * @param key          配置键
     * @param defaultValue 缺失时的回落值
     * @return 当前生效布尔值
     */
    public boolean getBoolValue(String key, boolean defaultValue);

    /**
     * 列出全部系统配置当前行（含键、值、元数据）。
     *
     * @return 配置列表；无则空列表
     */
    public List<SystemConfig> list();

    /**
     * 更新配置值（操作人未显式传入，由实现侧取当前会话或系统身份）。
     *
     * @param key   配置键
     * @param value 新值
     */
    public void update(String key, String value);

    /**
     * 更新配置值并记录操作人，同时写入版本历史。
     *
     * @param key        配置键
     * @param value      新值
     * @param operatorId 操作人 personId
     */
    public void update(String key, String value, Long operatorId);

    /**
     * 取指定时间点生效的配置值（按时点版本回溯，非当前行）。
     *
     * @param key  配置键
     * @param asOf 时点
     * @return 当时生效值；无版本则 null
     */
    public String getValueAsOf(String key, Date asOf);

    /**
     * 解析指定时点的配置视图（值 + 版本元数据），供审计 / 回放。
     *
     * @param key  配置键
     * @param asOf 时点
     * @return 含 value / version 等字段的有序映射
     */
    public java.util.LinkedHashMap<String, Object> resolveAsOf(String key, Date asOf);

    /**
     * 列出某键的历史版本，按时间倒序截断。
     *
     * @param key   配置键
     * @param limit 最大条数
     * @return 版本列表
     */
    public List<SystemConfigVersion> listVersions(String key, int limit);

    /**
     * 使单键本地 / 分布式缓存失效，下次读取回源。
     *
     * @param key 配置键
     */
    public void invalidate(String key);

    /**
     * 清空全部系统配置缓存。
     */
    public void invalidateAll();

}
