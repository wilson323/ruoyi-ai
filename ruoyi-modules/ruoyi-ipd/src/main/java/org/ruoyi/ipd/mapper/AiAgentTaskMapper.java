package org.ruoyi.ipd.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.domain.AiAgentTask;

/**
 * R221：AI 代理执行任务行 Mapper。
 * 调度线程无会话上下文，租户过滤双保险豁免（tenant.excludes 已登记 ai_agent_tasks）。
 * 抢占/收尾一律经 service 内受控条件 UPDATE（@Version 乐观守卫），不裸调 updateById。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface AiAgentTaskMapper extends BaseMapperPlus<AiAgentTask, AiAgentTask> {
}
