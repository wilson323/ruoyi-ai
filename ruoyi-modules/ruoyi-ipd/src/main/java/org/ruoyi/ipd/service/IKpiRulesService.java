package org.ruoyi.ipd.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.ruoyi.ipd.domain.KpiRuleSnapshot;
import org.ruoyi.ipd.domain.SystemConfig;
import org.ruoyi.ipd.mapper.KpiRuleSnapshotMapper;
import org.ruoyi.ipd.vo.KpiRuleView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IKpiRulesService 接口（paiban-05 接口化，实现见 {@link KpiRulesService}）。
 */
public interface IKpiRulesService {

    /** * 读取当前生效的 KPI 规则清单（快照优先，system_configs 回退；纯读，不写审计）。 */
    /** * */
    /** * @return {ruleKey, ruleValue} 列表，可能为空但不会为 null */
    List<KpiRuleView> listActiveRules();

}
