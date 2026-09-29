package org.ruoyi.ipd.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.ipd.domain.AiModelUsageLedger;

/**
 * C2 用量账本存取（ai_model_usage_ledger）。
 * <p>
 * 多租户：IPD 单企业私有部署、无多租户语义（与 ai_model_configs 同策略），
 * 父 application.yml 的 tenant.excludes 已登记；类级 {@code @InterceptorIgnore(tenantLine="true")}
 * 双保险（对齐 AiModelConfigMapper R184-A 修法：防异步线程无租户上下文被误过滤）。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AiModelUsageLedgerMapper extends BaseMapper<AiModelUsageLedger> {
}
