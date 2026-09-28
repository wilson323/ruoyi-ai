package org.ruoyi.ipd.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.ipd.domain.AuditLog;
import org.ruoyi.ipd.security.IpdActor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审计 append 事务边界契约（2026-09-27 工作树收口轮，防静默漂移）：
 * 三个 append 形态都必须是 REQUIRES_NEW——「失败审计独立落库（业务失败不回滚审计）」是仓级红线
 * （RequirementChangeSecurityRound2Test / IpdAuthLoginAuditTest / AuditChainHeadAppendContractTest 载明）。
 * <p>为何连重载一起锁：重载自调用 {@code this.append(AuditLog)} 不走 Spring 代理，
 * 仅底层带注解时独立事务会被静默降级为「随业务回滚」；mock 测试体系结构性看不见这层漂移
 * （Mockito 直接 new 实例，注解不生效），故以反射断言把契约从注释升格为可执行门禁
 * （仿 ProjectBootstrapServiceTest 同型先例）。
 */
@Tag("dev")
class AuditLogAppendTransactionContractTest {

    @Test
    void allAppendOverloadsRequireNewTransaction() throws NoSuchMethodException {
        assertRequiresNew(AuditLogServiceImpl.class
            .getDeclaredMethod("append", AuditLog.class));
        assertRequiresNew(AuditLogServiceImpl.class
            .getDeclaredMethod("append", IpdActor.class, String.class, String.class, Long.class, String.class));
        assertRequiresNew(AuditLogServiceImpl.class
            .getDeclaredMethod("append", Long.class, String.class, String.class, Long.class, String.class));
    }

    private void assertRequiresNew(Method method) {
        Transactional transaction = method.getAnnotation(Transactional.class);
        assertThat(transaction)
            .as("%s 必须带 @Transactional（失败审计独立落库红线）", method.getName())
            .isNotNull();
        assertThat(transaction.rollbackFor()).contains(Exception.class);
        assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
