package org.ruoyi.ipd.config;

import org.ruoyi.ipd.security.IpdAuthSession;
import org.ruoyi.ipd.security.IpdKnowledgeAccessGate;
import org.ruoyi.service.knowledge.impl.UserIdShareKnowledgeAccessGate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * B2 检索接线：注册 {@link IpdKnowledgeAccessGate} 为 {@code KnowledgeAccessGate}
 * 的 @Primary 实现，覆盖 chat 侧默认 Bean（{@code UserIdShareKnowledgeAccessGate}，
 * 仍以具体类保留在容器内供本桥组合委托 B0 判据）。
 * <p>
 * 与 {@link IpdSaTokenBridgeConfig} 同构先例：接口定义在消费方（ruoyi-chat）、
 * 权威实现在 ruoyi-ipd，依赖方向单向下注，不构成第二套权限体系
 * （实施方案 §3.2 / B0 审计 Q2 登记的替换路径）。
 *
 * @author ruoyi
 * @date 2026-09-28
 */
@Configuration
public class IpdKnowledgeAccessConfig {

    @Bean
    @Primary
    public IpdKnowledgeAccessGate ipdKnowledgeAccessGate(UserIdShareKnowledgeAccessGate baseGate,
                                                         IpdAuthSession authSession) {
        return new IpdKnowledgeAccessGate(baseGate, authSession);
    }
}
