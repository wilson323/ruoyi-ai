package org.ruoyi.ipd.agent.kernel;

import io.agentscope.harness.agent.artifact.ArtifactDeliveryTarget;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.bus.WorkspaceAsyncToolRegistry;
import io.agentscope.harness.agent.bus.WorkspaceMessageBus;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.team.LocalTeamClient;
import io.agentscope.harness.agent.team.TeamClient;
import io.agentscope.harness.agent.team.TeamContext;
import io.agentscope.harness.agent.team.TeamCreateSpec;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import org.ruoyi.chat.kernel.KernelScopeKey;

/** 官方 run 内协作装配；TeamClient 不是 IPD 产品线团队及业务审批权。 */
public final class ProjectAgentOfficialCollaboration {
    private ProjectAgentOfficialCollaboration() { }

    public record Assembly(TeamClient teamClient, TeamContext teamContext,
                           MessageBus messageBus, AsyncToolRegistry asyncToolRegistry) { }

    /** 底层 store / filesystem 必须由调用方提供真实持久化实现，不用空注册表模拟异步协作。 */
    public static Assembly create(KernelScopeKey.Scope scope, BaseStore store,
            AbstractFilesystem runFilesystem, ProjectAgentEventSink sink) {
        Objects.requireNonNull(scope);
        Objects.requireNonNull(runFilesystem);
        Objects.requireNonNull(sink);
        BaseStore owned = new OwnedRunStore(store, scope, sink);
        LocalTeamClient client = new LocalTeamClient(owned);
        String namespace = scope.slotId();
        String team = "project-agent-run";
        // 官方写入真实 meta 与 lead 成员；重复createTeam按SDK契约回读已有meta。
        client.createTeam(new TeamCreateSpec(team, namespace,
            "本次运行的技能专业任务协作", "project-agent", "", List.of())).block();
        TeamContext context = new TeamContext(team, namespace, "本次运行的技能专业任务协作",
            "lead", true, List.of(new TeamContext.MemberSnapshot("lead", "project-agent", "active")),
            List.of("listTasks", "listClaimableTasks", "createTask", "assignTask", "claimTask",
                "unclaimTask", "completeTask", "failTask", "sendMessage", "broadcastMessage",
                "listMessages", "listMembers", "completeTeam"));
        AbstractFilesystem ownedFs = ownedFilesystem(runFilesystem, sink);
        String runtimeRoot = "/.collaboration/" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(scope.slotId().getBytes(StandardCharsets.UTF_8));
        return new Assembly(client, context,
            new WorkspaceMessageBus(ownedFs, runtimeRoot + "/bus"),
            new WorkspaceAsyncToolRegistry(ownedFs, runtimeRoot + "/async-tools"));
    }

    /** 官方 delivery SPI只包装真实业务目标；onArtifact事件本身不能充当落库成功。 */
    public static ArtifactDeliveryTarget guardArtifactTarget(ArtifactDeliveryTarget target,
            ProjectAgentEventSink sink) {
        Objects.requireNonNull(target, "A real artifact delivery target is required");
        return (context, request) -> sink.withActiveOwnership(() ->
            Objects.requireNonNull(target.deliver(context, request), "Artifact delivery returned no receipt"));
    }

    private static AbstractFilesystem ownedFilesystem(AbstractFilesystem filesystem, ProjectAgentEventSink sink) {
        return (AbstractFilesystem) Proxy.newProxyInstance(AbstractFilesystem.class.getClassLoader(),
            new Class<?>[]{AbstractFilesystem.class}, (proxy, method, args) -> sink.withActiveOwnership(() -> {
                try { return method.invoke(filesystem, args); }
                catch (InvocationTargetException failed) {
                    if (failed.getCause() instanceof RuntimeException runtime) throw runtime;
                    throw new IllegalStateException("Official collaboration filesystem failed", failed.getCause());
                } catch (IllegalAccessException inaccessible) {
                    throw new IllegalStateException("Official collaboration filesystem is inaccessible", inaccessible);
                }
            }));
    }

    static final class OwnedRunStore implements BaseStore {
        private final BaseStore delegate;
        private final List<String> prefix;
        private final ProjectAgentEventSink sink;
        OwnedRunStore(BaseStore delegate, KernelScopeKey.Scope scope, ProjectAgentEventSink sink) {
            this.delegate = Objects.requireNonNull(delegate);
            this.prefix = List.of("ipd-agent-run", scope.userId(), scope.sessionId());
            this.sink = sink;
        }
        private List<String> namespace(List<String> namespace) {
            List<String> scoped = new ArrayList<>(prefix);
            for (String segment : namespace) {
                if (segment == null || segment.isBlank() || segment.contains("..")
                    || segment.contains("/") || segment.contains("\\"))
                    throw new IllegalArgumentException("Invalid collaboration namespace");
                scoped.add(segment);
            }
            return List.copyOf(scoped);
        }
        public StoreItem get(List<String> ns, String key) {
            return sink.withActiveOwnership(() -> delegate.get(namespace(ns), key));
        }
        public void put(List<String> ns, String key, Map<String, Object> value) {
            sink.withActiveOwnership(() -> { delegate.put(namespace(ns), key, value); return null; });
        }
        public boolean putIfVersion(List<String> ns, String key, Map<String, Object> value, long version) {
            return sink.withActiveOwnership(() -> delegate.putIfVersion(namespace(ns), key, value, version));
        }
        public List<StoreItem> search(List<String> ns, int limit, int offset) {
            return sink.withActiveOwnership(() -> delegate.search(namespace(ns), limit, offset));
        }
        public void delete(List<String> ns, String key) {
            sink.withActiveOwnership(() -> { delegate.delete(namespace(ns), key); return null; });
        }
    }
}
