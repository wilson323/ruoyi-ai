package org.ruoyi.ipd.service;

import java.util.List;
import org.ruoyi.ipd.vo.KpiRuleView;

/**
 * IKpiRulesService 接口（paiban-05 接口化，实现见 {@link KpiRulesService}）。
 */
public interface IKpiRulesService {

    /** * 读取当前生效的 KPI 规则清单（快照优先，system_configs 回退；纯读，不写审计）。 */
    /** * */
    /** * @return {ruleKey, ruleValue} 列表，可能为空但不会为 null */
    List<KpiRuleView> listActiveRules();

}
