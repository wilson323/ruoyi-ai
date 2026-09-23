package org.ruoyi.ipd.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.ruoyi.ipd.domain.AiModelConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * P4-2.1：AI 模型配置存取（ai_model_configs）。
 * <p>
 * R184-A 修复（2026-09-23）：ai_model_configs 是 IPD 单企业私有部署的全局生效配置（至多 1 行
 * {@code is_active=true}），无多租户语义；即便 {@code tenant.excludes} 已登记该表，
 * 异步线程/无 LoginHelper 的 IPD StpLogic 上下文仍可能在 MyBatis-Plus
 * TenantLineInnerInterceptor 拼接 SQL 时落入 {@code tenant_id IS NULL} 分支并误过滤
 * {@code tenant_id='000000'} 行（实测：currentEnabled() 抛 STATE_CONFLICT → ai_doc_embeddings
 * 0 行）。类级 {@code @InterceptorIgnore(tenantLine="true")} 把租户拦截整段关掉，
 * 让根因浮出到数据/调用链层；本仓 31 张 excludes 表的同类问题修法对齐本注解。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AiModelConfigMapper extends BaseMapper<AiModelConfig> {
}
