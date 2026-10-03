package org.ruoyi.ipd.agent.support;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
/** 仅事务边界测试，不替代数据库行锁或Redis故障实测。 */
public final class AgentOwnershipTestTransactions {
    private AgentOwnershipTestTransactions() { }
    public static TransactionTemplate create() {
        return new TransactionTemplate(new AbstractPlatformTransactionManager() {
            protected Object doGetTransaction() { return new Object(); }
            protected void doBegin(Object tx, TransactionDefinition definition) { }
            protected void doCommit(DefaultTransactionStatus status) { }
            protected void doRollback(DefaultTransactionStatus status) { }
        });
    }
}
