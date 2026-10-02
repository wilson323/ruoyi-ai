package org.ruoyi.chat.kernel;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 保持原生存储与CAS，冲突抛错阻止Harness 2.0.3未暴露的默认覆盖恢复。 */
final class FailClosedAgentStateStore implements AgentStateStore {
    private final AgentStateStore delegate;
    FailClosedAgentStateStore(AgentStateStore delegate) { this.delegate = delegate; }
    @Override public boolean supportsVersioning() { return delegate.supportsVersioning(); }
    @Override public void save(String user, String session, String key, State value) {
        delegate.save(user, session, key, value);
    }
    @Override public void save(String user, String session, String key, List<? extends State> values) {
        delegate.save(user, session, key, values);
    }
    @Override public long saveIfVersion(String user, String session, String key, State value, long expected) {
        long version = delegate.saveIfVersion(user, session, key, value, expected);
        if (delegate.supportsVersioning() && version == UNVERSIONED) {
            throw new IllegalStateException("native chat state CAS conflict");
        }
        return version;
    }
    @Override public <T extends State> VersionedState<T> getVersioned(String user, String session, String key, Class<T> type) {
        return delegate.getVersioned(user, session, key, type);
    }
    @Override public <T extends State> Optional<T> get(String user, String session, String key, Class<T> type) {
        return delegate.get(user, session, key, type);
    }
    @Override public <T extends State> List<T> getList(String user, String session, String key, Class<T> type) {
        return delegate.getList(user, session, key, type);
    }
    @Override public boolean exists(String user, String session) { return delegate.exists(user, session); }
    @Override public void delete(String user, String session) { delegate.delete(user, session); }
    @Override public void delete(String user, String session, String key) { delegate.delete(user, session, key); }
    @Override public Set<String> listSessionIds(String user) { return delegate.listSessionIds(user); }
    // 借用的应用Redisson由Spring负责关闭，本包装不拥有客户端。
}
