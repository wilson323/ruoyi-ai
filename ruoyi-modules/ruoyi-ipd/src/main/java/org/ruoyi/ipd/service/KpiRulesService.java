package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.KpiRuleSnapshot;
import org.ruoyi.ipd.domain.SystemConfig;
import org.ruoyi.ipd.mapper.KpiRuleSnapshotMapper;
import org.ruoyi.ipd.vo.KpiRuleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KPI 规则读取服务（W1-KPI / paiban-02 方案 B，零 DB 变更）。
 *
 * <p>不新建 kpi_rules 表；复用现有 KPI 数据源，按优先级回退：
 * <ol>
 *   <li>kpi_rule_snapshots 最新快照（version DESC LIMIT 1）的 rule_json 顶层键值；</li>
 *   <li>system_configs 的 kpi.* 键（如 kpi.reviewWeights / kpi.monthlyDeadlineDay）。</li>
 * </ol>
 * 两源皆空 → 返回空列表（不抛），保证 /kpi/rules 恒可用（R108 E2E 契约）。
 */
@Service
@RequiredArgsConstructor
public class KpiRulesService {

    private static final Logger log = LoggerFactory.getLogger(KpiRulesService.class);

    /** 规则 JSON 解析器（显式 BigDecimal 保精度，不依赖 Spring 上下文，单测可直连）。 */
    private static final ObjectMapper JSON = new ObjectMapper()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

    /** rule_json 顶层结构类型（保插入顺序）。 */
    private static final TypeReference<LinkedHashMap<String, Object>> RULE_MAP_TYPE =
        new TypeReference<>() { };

    /** system_configs 中 KPI 规则键前缀。 */
    private static final String KPI_CONFIG_PREFIX = "kpi.";

    private final KpiRuleSnapshotMapper snapshotMapper;
    private final SystemConfigService systemConfigService;

    /**
     * 读取当前生效的 KPI 规则清单（快照优先，system_configs 回退；纯读，不写审计）。
     *
     * @return {ruleKey, ruleValue} 列表，可能为空但不会为 null
     */
    @Transactional(readOnly = true, rollbackFor = Exception.class)
    public List<KpiRuleView> listActiveRules() {
        List<KpiRuleView> fromSnapshot = rulesFromLatestSnapshot();
        if (!fromSnapshot.isEmpty()) {
            return fromSnapshot;
        }
        return rulesFromSystemConfig();
    }

    /** 数据源 1：kpi_rule_snapshots 最新版本的 rule_json 顶层键值。 */
    private List<KpiRuleView> rulesFromLatestSnapshot() {
        KpiRuleSnapshot latest = snapshotMapper.selectOne(
            new LambdaQueryWrapper<KpiRuleSnapshot>()
                .orderByDesc(KpiRuleSnapshot::getVersion)
                .last("LIMIT 1"));
        if (latest == null || isBlank(latest.getRuleJson())) {
            return List.of();
        }
        return flattenRuleJson(latest.getRuleJson().trim());
    }

    /** 规则 JSON 顶层字段拍平为 {ruleKey, ruleValue}；非法/非对象 JSON 降级为空（触发回退）。 */
    private List<KpiRuleView> flattenRuleJson(String ruleJson) {
        try {
            Map<String, Object> parsed = JSON.readValue(ruleJson, RULE_MAP_TYPE);
            if (parsed == null || parsed.isEmpty()) {
                return List.of();
            }
            List<KpiRuleView> views = new ArrayList<>(parsed.size());
            for (Map.Entry<String, Object> entry : parsed.entrySet()) {
                views.add(new KpiRuleView(entry.getKey(), textOf(entry.getValue())));
            }
            return views;
        } catch (Exception ex) {
            log.warn("KPI 规则快照 ruleJson 非法，降级 system_configs 回退源: {}", ex.getMessage());
            return List.of();
        }
    }

    /** 数据源 2：system_configs 的 kpi.* 键（list() 已按 configKey 升序）。 */
    private List<KpiRuleView> rulesFromSystemConfig() {
        List<SystemConfig> configs = systemConfigService.list();
        if (configs == null || configs.isEmpty()) {
            return List.of();
        }
        List<KpiRuleView> views = new ArrayList<>();
        for (SystemConfig config : configs) {
            if (config == null || config.getConfigKey() == null
                || !config.getConfigKey().startsWith(KPI_CONFIG_PREFIX)) {
                continue;
            }
            views.add(new KpiRuleView(config.getConfigKey(),
                config.getConfigValue() == null ? "" : config.getConfigValue()));
        }
        return views;
    }

    /** 标量取文本；嵌套对象/数组保留紧凑 JSON（不丢结构）。 */
    private static String textOf(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Map || value instanceof Collection) {
            try {
                return JSON.writeValueAsString(value);
            } catch (Exception ignore) {
                return String.valueOf(value);
            }
        }
        return String.valueOf(value);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
