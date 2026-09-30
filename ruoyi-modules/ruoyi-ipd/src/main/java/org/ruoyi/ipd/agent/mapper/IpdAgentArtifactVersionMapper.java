package org.ruoyi.ipd.agent.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.ruoyi.common.mybatis.core.mapper.BaseMapperPlus;
import org.ruoyi.ipd.agent.domain.IpdAgentArtifactVersion;

/**
 * 产物版本 Mapper。无会话异步写路径须豁免租户拦截；store 显式按 tenant_id 限定。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface IpdAgentArtifactVersionMapper
    extends BaseMapperPlus<IpdAgentArtifactVersion, IpdAgentArtifactVersion> {
}
