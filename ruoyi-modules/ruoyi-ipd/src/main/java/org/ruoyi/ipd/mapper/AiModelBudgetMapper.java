package org.ruoyi.ipd.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.ipd.domain.AiModelBudget;

/**
 * C2 模型月度预算存取（ai_model_budget）。
 * <p>
 * 多租户：IPD 单企业私有部署、无多租户语义（与 ai_model_configs 同策略），
 * 父 application.yml 的 tenant.excludes 已登记；类级 {@code @InterceptorIgnore(tenantLine="true")}
 * 双保险（对齐 AiModelConfigMapper R184-A 修法）。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AiModelBudgetMapper extends BaseMapper<AiModelBudget> {
}
