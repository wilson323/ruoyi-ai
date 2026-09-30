package org.ruoyi.ipd.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.agent.domain.IpdAgentRunEvent;

/**
 * 项目智能体 IpdAgentRunEvent Mapper。执行器在无会话线程写入，租户过滤由 store 显式按
 * tenant_id 限定（与 AiAgentTaskMapper 同口径），自动租户拦截豁免；
 * 部署前须在 tenant.excludes 双保险登记（见项目智能体运行合同 §5）。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface IpdAgentRunEventMapper extends BaseMapperPlus<IpdAgentRunEvent, IpdAgentRunEvent> {
}
