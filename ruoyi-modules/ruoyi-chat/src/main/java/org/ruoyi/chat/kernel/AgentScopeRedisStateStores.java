package org.ruoyi.chat.kernel;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import io.agentscope.extensions.redis.state.redisson.RedissonClientAdapter;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** AgentScope 2.0.3 原生状态库与本项目 Redisson 3.51 的单点兼容装配。 */
public final class AgentScopeRedisStateStores {
    private AgentScopeRedisStateStores() { }
    public static AgentStateStore create(RedissonClient client, String keyPrefix) {
        return RedisAgentStateStore.builder().clientAdapter(new Redisson351Adapter(client)).keyPrefix(keyPrefix).build();
    }

    /** SDK 2.0.3 adapter使用4.2才有的ReturnType.LONG；3.51的INTEGER同样返回Number。
     * 仅替换此枚举，其余命令和状态序列化、版本CAS均由SDK处理；共享client不归state store关闭。 */
    private static final class Redisson351Adapter implements RedisClientAdapter {
        private final RedissonClient client;
        private final RedisClientAdapter delegate;
        Redisson351Adapter(RedissonClient client) {
            this.client = java.util.Objects.requireNonNull(client);
            this.delegate = RedissonClientAdapter.of(client);
        }
        public void set(String key, String value) { delegate.set(key, value); }
        public String get(String key) { return delegate.get(key); }
        public void rightPushList(String key, String value) { delegate.rightPushList(key, value); }
        public List<String> rangeList(String key, long start, long end) { return delegate.rangeList(key, start, end); }
        public long getListLength(String key) { return delegate.getListLength(key); }
        public void deleteKeys(String... keys) { delegate.deleteKeys(keys); }
        public void addToSet(String key, String value) { delegate.addToSet(key, value); }
        public Set<String> getSetMembers(String key) { return delegate.getSetMembers(key); }
        public long getSetSize(String key) { return delegate.getSetSize(key); }
        public boolean keyExists(String key) { return delegate.keyExists(key); }
        public Set<String> findKeysByPattern(String pattern) { return delegate.findKeysByPattern(pattern); }
        public long evalScript(String script, List<String> keys, List<String> args) {
            Object result = client.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, script,
                RScript.ReturnType.INTEGER, new ArrayList<Object>(keys), args.toArray());
            if (result instanceof Number number) return number.longValue();
            throw new IllegalStateException("Redis script did not return a number: " + result);
        }
        public void close() { /* 原应用Redisson bean拥有共享client生命周期。 */ }
    }
}
