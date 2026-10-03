package org.ruoyi.ipd.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ruoyi.ipd.domain.AuditChainHead;
import org.ruoyi.ipd.mapper.AuditChainHeadMapper;
import org.ruoyi.ipd.mapper.AuditLogMapper;
import org.ruoyi.ipd.mapper.PersonMapper;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttributeSourceAdvisor;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 验链读快照一致性（2026-10-03 补，SEC-AUD-01 遗留项 2）。
 *
 * <p><b>要锁的契约</b>：{@code verifyChainAnchored} 读审计表与读锚表是两条语句。若它们落在
 * 两个自动提交事务里，就可能夹在一次并发 {@code append} 的提交中间（先读到 append 前的表、
 * 再读到 append 后的锚），从而误报 {@code ANCHOR_TRUNCATED}——把「表和锚一致」误判成「链尾被删」。
 * 唯一的修法是让两次读共享一个可重复读快照，即
 * {@code @Transactional(readOnly = true, isolation = REPEATABLE_READ)}。
 *
 * <p><b>本测试为什么不满足于「断言注解存在」</b>：三个历史出口之间是 self-invocation
 * （verifyChain → verifyChainDetailed → verifyChainAnchored），不走 Spring 代理，注解写在
 * 内部方法上对这条调用链完全无效。所以这里真实搭一套 Spring AOP 代理（ProxyFactory +
 * TransactionInterceptor + 记录型 PlatformTransactionManager），断言：经代理调用每个出口时，
 * 事务管理器被要求开启的事务确实 readOnly + REPEATABLE_READ，且两次 mapper 读都发生在该事务之内。
 * 另有一条反向测试锁住 self-invocation 陷阱本身（裸调用不开事务）。
 *
 * <p><b>本测试不做什么</b>：不连真实 MySQL，不验 InnoDB MVCC 的实际行为。它锁的是「应用层确实
 * 申请到了一个可重复读的只读快照，且两次读落在其中」这一半——另一半（InnoDB 的实现）由
 * {@code append} 侧推锚与插行同事务提交所保证，见 AuditLogServiceImpl 的 javadoc。
 */
@Tag("dev")
@DisplayName("验链读快照一致性（三出口共享可重复读只读快照，堵住并发 append 误报）")
@ExtendWith(MockitoExtension.class)
class AuditChainVerifySnapshotTest {

    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private AuditChainHeadMapper chainHeadMapper;
    @Mock
    private PersonMapper personMapper;

    @InjectMocks
    private AuditLogServiceImpl service;

    /** 记录每次被要求开启的事务属性，并能回答「此刻是否在事务内」。 */
    private static final class RecordingTxManager implements PlatformTransactionManager {
        private final List<TransactionDefinition> opened = new ArrayList<>();
        private int depth;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            opened.add(definition);
            depth++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            depth--;
        }

        @Override
        public void rollback(TransactionStatus status) {
            depth--;
        }
    }

    /** 搭出与 Spring 容器同构的代理：注解属性由 AnnotationTransactionAttributeSource 解析。 */
    private static AuditLogServiceImpl proxied(AuditLogServiceImpl target, RecordingTxManager tm) {
        TransactionInterceptor interceptor =
            new TransactionInterceptor(tm, new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setTarget(target);
        // 与 Spring Boot 默认一致（proxyTargetClass=true）；JDK 接口代理那条路 self-invocation 行为相同。
        factory.setProxyTargetClass(true);
        factory.addAdvisor(new TransactionAttributeSourceAdvisor(interceptor));
        return (AuditLogServiceImpl) factory.getProxy();
    }

    /**
     * 空表 + 锚 seq 0：两个 mapper 读都会发生，结论为 ANCHOR_OK。
     * 两次读各自记录当时的事务深度，供断言。
     */
    private List<Integer> depthsSeenByReads(RecordingTxManager tm) {
        List<Integer> depths = new ArrayList<>();
        when(auditLogMapper.selectList(any())).thenAnswer(inv -> {
            depths.add(tm.depth);
            return List.of();
        });
        AuditChainHead head = new AuditChainHead();
        head.setChainKey("GLOBAL");
        head.setLastSeq(0L);
        head.setLastHash("genesis");
        head.setNextSeq(1L);
        when(chainHeadMapper.selectById(anyString())).thenAnswer(inv -> {
            depths.add(tm.depth);
            return head;
        });
        return depths;
    }

    @Test
    @DisplayName("三出口逐个经代理：事务属性为只读 + 可重复读，两次读深度均为 1")
    void eachExitOpensReadOnlyRepeatableRead() {
        for (String methodName : List.of("verifyChain", "verifyChainStrict", "verifyChainDetailed")) {
            RecordingTxManager tm = new RecordingTxManager();
            List<Integer> readDepths = depthsSeenByReads(tm);
            AuditLogServiceImpl proxy = proxied(service, tm);

            invoke(proxy, methodName);

            assertThat(tm.opened).as("%s 应开启一次事务", methodName).hasSize(1);
            TransactionDefinition tx = tm.opened.get(0);
            assertThat(tx.isReadOnly()).as("%s 必须是只读事务", methodName).isTrue();
            assertThat(tx.getIsolationLevel())
                .as("%s 必须是可重复读，否则表读与锚读可能落在不同快照", methodName)
                .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThat(readDepths).as("%s 的两次读都应发生在事务内", methodName)
                .containsExactly(1, 1);
            assertThat(tm.depth).as("%s 返回时事务应已提交", methodName).isZero();
        }
    }

    @Test
    @DisplayName("直接被调用的锚校验出口同样受保护（不依赖上层出口代开事务）")
    void anchoredExitAloneIsCovered() {
        RecordingTxManager tm = new RecordingTxManager();
        List<Integer> readDepths = depthsSeenByReads(tm);
        AuditLogServiceImpl proxy = proxied(service, tm);

        proxy.verifyChainAnchored();

        assertThat(tm.opened).hasSize(1);
        assertThat(tm.opened.get(0).isReadOnly()).isTrue();
        assertThat(tm.opened.get(0).getIsolationLevel())
            .isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(readDepths).containsExactly(1, 1);
    }

    @Test
    @DisplayName("self-invocation 陷阱：不经代理的裸调用不开事务——这正是注解只能加在最外层出口的原因")
    void bareSelfInvocationOpensNoTransaction() {
        RecordingTxManager tm = new RecordingTxManager();
        List<Integer> readDepths = depthsSeenByReads(tm);

        // 模拟「注解误加在内部方法」的情形：service.verifyChain() 内部再自调 verifyChainDetailed()
        service.verifyChain();

        assertThat(tm.opened).isEmpty();
        assertThat(readDepths).as("裸调用下两次读都发生在事务外——两次读各自自动提交")
            .containsExactly(0, 0);
    }

    @Test
    @DisplayName("写入侧事务语义零变更：append 仍 REQUIRES_NEW、rebuildChain 仍 REQUIRED 且 rollbackFor=Exception")
    void writePathTransactionSemanticsUnchanged() throws Exception {
        assertPropagation("append", org.springframework.transaction.annotation.Propagation.REQUIRES_NEW);
        assertPropagation("rebuildChain", org.springframework.transaction.annotation.Propagation.REQUIRED);
    }

    private static void assertPropagation(String methodName,
                                          org.springframework.transaction.annotation.Propagation expected)
        throws Exception {
        for (Method m : AuditLogServiceImpl.class.getDeclaredMethods()) {
            if (!m.getName().equals(methodName)) {
                continue;
            }
            var ann = m.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertThat(ann).as("%s 仍应带 @Transactional", methodName).isNotNull();
            assertThat(ann.propagation()).as("%s 传播行为不得被本次改动影响", methodName).isEqualTo(expected);
            assertThat(ann.rollbackFor()).as("%s 仍需 rollbackFor=Exception", methodName)
                .contains(Exception.class);
            assertThat(ann.readOnly()).as("%s 是写入路径，不得为只读", methodName).isFalse();
            return;
        }
        throw new AssertionError("未找到方法 " + methodName);
    }

    private static void invoke(AuditLogServiceImpl proxy, String methodName) {
        switch (methodName) {
            case "verifyChain" -> proxy.verifyChain();
            case "verifyChainStrict" -> proxy.verifyChainStrict();
            case "verifyChainDetailed" -> proxy.verifyChainDetailed();
            default -> throw new AssertionError("未知方法 " + methodName);
        }
    }
}
