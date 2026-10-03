package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 父运行持有的权威 actor/session 注册；不接受 sub-UUID 格式作为授权证据。 */
public final class ProjectAgentChildLineageRegistry {
    public static final String INTERNAL_ORIGIN = "ipd.server.child.invocation";
    record Invocation(Agent actor, RuntimeContext context) { }
    public record CallSnapshot(String id, String name, String canonicalInput, String rawContent) {
        public String getId() { return id; }
        static CallSnapshot of(io.agentscope.core.message.ToolUseBlock call) {
            return new CallSnapshot(call.getId(), call.getName(), canonical(call.getInput()), call.getContent());
        }
    }
    public record SuspensionSnapshot(String generateReason, CallSnapshot call, String resultState,
                                     String canonicalResultMetadata, String canonicalResultOutput) { }
    public record ParentCall(String nonce, String userId, String sessionId, CallSnapshot call,
                             SuspensionSnapshot suspension, long checkpointVersion) {
        public ParentCall(String nonce,String userId,String sessionId,CallSnapshot call) { this(nonce,userId,sessionId,call,null,-1); }
    }
    private final Map<String,SuspensionSnapshot> nativeSuspensions=new ConcurrentHashMap<>();
    void captureSuspension(Agent actor,RuntimeContext context,io.agentscope.core.event.AgentEvent event) {
        if(!(event instanceof io.agentscope.core.event.AgentResultEvent result)
            || result.getResult().getGenerateReason()!=io.agentscope.core.message.GenerateReason.TOOL_SUSPENDED) return;
        requireKnown(actor,context);
        for(var call:result.getResult().getContentBlocks(io.agentscope.core.message.ToolUseBlock.class)) {
            var snapshot=CallSnapshot.of(call);
            var suspended=result.getResult().getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream()
                .filter(t->call.getId().equals(t.getId())&&call.getName().equals(t.getName())&&t.isSuspended()).findFirst().orElseThrow(()->new SecurityException("Native parent suspension result missing"));
            synchronized(issuedParents) {
                issuedParents.stream().filter(b->b.parent().userId().equals(context.getUserId())&&b.parent().sessionId().equals(context.getSessionId())&&b.parent().call().equals(snapshot))
                    .forEach(binding->nativeSuspensions.put(binding.parent().nonce(),new SuspensionSnapshot(result.getResult().getGenerateReason().name(),snapshot,suspended.getState().name(),canonical(suspended.getMetadata()),canonical(suspended.getOutput()))));
            }
        }
    }
    private ParentCall checkpointParent(ParentCall parent,io.agentscope.core.state.AgentStateStore store) {
        var nativeReceipt=nativeSuspensions.get(parent.nonce());
        if(nativeReceipt==null)throw new SecurityException("Actual native parent suspension receipt missing");
        var saved=store.getVersioned(parent.userId(),parent.sessionId(),"agent_state",io.agentscope.core.state.AgentState.class);
        if(!saved.isPresent()||saved.version()<0)throw new SecurityException("Parent suspension checkpoint missing");
        return new ParentCall(parent.nonce(),parent.userId(),parent.sessionId(),parent.call(),nativeReceipt,saved.version());
    }
    public static void requireNativeSuspension(ParentCall parent) {
        if(parent==null||parent.suspension()==null||parent.checkpointVersion()<0
            || !parent.call().equals(parent.suspension().call())
            || !"TOOL_SUSPENDED".equals(parent.suspension().generateReason())||!"RUNNING".equals(parent.suspension().resultState()))throw new SecurityException("Original native parent suspension proof missing");
        try {var metadata=new com.fasterxml.jackson.databind.ObjectMapper().readTree(parent.suspension().canonicalResultMetadata());
            if(!metadata.path(io.agentscope.core.message.ToolResultBlock.METADATA_SUSPENDED).asBoolean(false))throw new SecurityException("Original native parent suspended marker missing");
        }catch(java.io.IOException invalid){throw new SecurityException("Invalid native suspension marker",invalid);}
    }
    public record FactoryDescriptor(String name, String sdkVersion, String sourceJarHash,
                                    String moduleJarHash, String declarationHash, String policyHash) { }
    public record ChildApproval(String locator, String userId, String sessionId, long checkpointVersion,
                                String replyId, java.util.List<CallSnapshot> calls,
                                FactoryDescriptor factory, ParentCall parentCall) {
        public ChildApproval { calls = java.util.List.copyOf(calls); }
        public ChildApproval(String locator, String userId, String sessionId, long checkpointVersion,
                             String replyId, java.util.List<CallSnapshot> calls) {
            this(locator,userId,sessionId,checkpointVersion,replyId,calls,null,null);
        }
        public ChildApproval withCalls(java.util.List<CallSnapshot> selected) {
            return new ChildApproval(locator,userId,sessionId,checkpointVersion,replyId,selected,factory,parentCall);
        }
    }
    /** 原 SDK 完成结果及已保存检查点；仅服务器原事件链存取，不是客户端权限。 */
    public record ChildCompletion(ChildApproval approval,long completedCheckpointVersion,String generateReason,String finalText) {
        public ChildCompletion { java.util.Objects.requireNonNull(approval);approval=approval.withCalls(approval.calls()); }
    }
    private record FactoryBinding(FactoryDescriptor factory, ParentCall parent) { }
    private record ParentBinding(ParentCall parent) { }
    private final Object parentContextKey = new Object();
    private final Set<ParentBinding> issuedParents = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Map<Agent,Agent> factoryHandles = Collections.synchronizedMap(new IdentityHashMap<>());
    Agent factoryHandle(Agent actual) { return factoryHandles.get(actualActor(actual)); }
    private final Map<Agent,FactoryBinding> factoryBindings = Collections.synchronizedMap(new IdentityHashMap<>());
    private final Set<String> dispatched = ConcurrentHashMap.newKeySet();
    private volatile String policyHash;
    static final String SOURCE_HASH = "6e30e9429eedc1551e858674b57541d94fd9b7b753550c844f3fe8bdbbf12034";
    void bindPolicy(String frozenPolicyHash) {
        if (policyHash != null && !policyHash.equals(frozenPolicyHash)) throw new SecurityException("Frozen child policy changed");
        policyHash = java.util.Objects.requireNonNull(frozenPolicyHash);
    }
    static String hash(Object value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonical(value).getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException missing) { throw new IllegalStateException(missing); }
    }
    FactoryDescriptor descriptor(String name, io.agentscope.harness.agent.subagent.SubagentDeclaration declaration) {
        if (policyHash == null) throw new SecurityException("Frozen child policy is unavailable");
        return frozenDescriptor(name,declaration,policyHash);
    }
    public static FactoryDescriptor frozenDescriptor(String name,io.agentscope.harness.agent.subagent.SubagentDeclaration declaration,String policyHash) {
        verifyLoadedSdk(HarnessAgent.class,"6a6e3ff2db5401fc7bdb795a903c1ae6a65c3ee17c79e9884419dfc8c2b720ff");
        verifyLoadedSdk(io.agentscope.harness.agent.tool.AgentSpawnTool.class,"1a9b46693dea60d8841161540463bd10fd9674165abf84a5482e0864836ce757");
        return new FactoryDescriptor(name,"2.0.3",SOURCE_HASH,"e6f6f5c39d8e16ca5c2ea3727b56dbcfa8fd408ef54a0018c0c9416251beb1a0",declaration == null ? null : hash(declaration),policyHash);
    }
    private static void verifyLoadedSdk(Class<?> type,String expected) {
        try(var bytes=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
            if(bytes==null || !java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())).equals(expected))
                throw new SecurityException("Loaded official SDK does not match the frozen 2.0.3 artifact");
        } catch(java.io.IOException|java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("Official SDK artifact cannot be attested",unavailable);}
    }
    RuntimeContext issueParentCall(Agent actor, RuntimeContext runtime, io.agentscope.core.message.ToolUseBlock call) {
        requireKnown(actor,runtime);
        ParentBinding binding = new ParentBinding(new ParentCall(java.util.UUID.randomUUID().toString(),runtime.getUserId(),runtime.getSessionId(),CallSnapshot.of(call)));
        issuedParents.add(binding);
        // Only this registry can issue the identity-bearing object. No metadata/browser serialization.
        return RuntimeContext.builder().from(runtime).put(parentKey(),binding).build();
    }
    private String parentKey() { return getClass().getName()+":"+System.identityHashCode(parentContextKey); }
    private ParentBinding parentBinding(RuntimeContext runtime) {
        Object raw=runtime.get(parentKey());
        if (!(raw instanceof ParentBinding binding) || !issuedParents.contains(binding)) throw new SecurityException("Unknown server parent call issuer");
        return binding;
    }
    void registerFactoryChild(Agent child, RuntimeContext parent, FactoryDescriptor descriptor) {
        var binding=parentBinding(parent);
        registerFactoryChild(child);
        factoryBindings.put(actualActor(child),new FactoryBinding(descriptor,binding.parent()));
    }
    boolean parentNeedsPause(RuntimeContext runtime) {
        ParentCall parent=parentBinding(runtime).parent();
        return awaiting.keySet().stream().anyMatch(key->{var invocation=origins.get(key.locator());
            var binding=invocation==null?null:factoryBindings.get(invocation.actor());
            return binding!=null && binding.parent().equals(parent);});
    }
    RuntimeContext restoreParentContext(ParentCall parent, io.agentscope.core.state.AgentStateStore store) {
        requireCurrentAccess.run();
        requireNativeSuspension(parent);
        if (parent==null || !trustedRoot.getUserId().equals(parent.userId()) || !ownsSession(parent.userId(),parent.sessionId())) throw new SecurityException("Original parent receipt missing");
        var saved=store.getVersioned(parent.userId(),parent.sessionId(),"agent_state",io.agentscope.core.state.AgentState.class);
        if (!saved.isPresent() || saved.version()!=parent.checkpointVersion()) throw new SecurityException("Original parent checkpoint missing or changed");
        var messages=saved.value().getContext();
        var latest=messages.stream().filter(m->m.getRole()==io.agentscope.core.message.MsgRole.ASSISTANT).reduce((a,b)->b).orElseThrow();
        if (latest.getContentBlocks(io.agentscope.core.message.ToolUseBlock.class).stream().filter(t->CallSnapshot.of(t).equals(parent.call())).count()!=1
            || messages.stream().flatMap(m->m.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream()).anyMatch(t->parent.call().id().equals(t.getId())))
            throw new SecurityException("Original parent call is no longer unfinished");
        ParentBinding binding=new ParentBinding(parent);issuedParents.add(binding);
        return RuntimeContext.builder().from(trustedRoot).sessionId(parent.sessionId()).put(parentKey(),binding).build();
    }
    Invocation liveInvocation(ChildApproval approval) { return origins.get(approval.locator()); }
    void admitRestored(Agent actor, RuntimeContext runtime, ChildApproval approval) {
        registerInvocation(actor,runtime);
        origins.put(approval.locator(),new Invocation(actualActor(actor),runtime));
        sessionOrigins.put(runtime.getSessionId(),approval.locator());
    }
    /** This is a claim, not permission. A failed dispatch retains it and requires explicit recovery. */
    void claimDispatch(ChildApproval approval) {
        requireCurrentAccess.run();
        if (!dispatched.add(approval.locator()+":"+approval.checkpointVersion()+":"+hash(approval.calls()))) throw new SecurityException("Child resume was already dispatched");
    }
    void acknowledgeSaved(ChildApproval approval, io.agentscope.core.state.AgentStateStore store) {
        requireCurrentAccess.run();
        var saved=store.getVersioned(approval.userId(),approval.sessionId(),"agent_state",io.agentscope.core.state.AgentState.class);
        if (!saved.isPresent() || saved.version()<=approval.checkpointVersion()) throw new SecurityException("Child completion checkpoint missing");
        for (var call:approval.calls()) {
            boolean exact=saved.value().getContext().stream().flatMap(m->m.getContentBlocks(io.agentscope.core.message.ToolUseBlock.class).stream()).anyMatch(t->CallSnapshot.of(t).equals(call));
            boolean result=saved.value().getContext().stream().flatMap(m->m.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream()).anyMatch(t->call.id().equals(t.getId())&&call.name().equals(t.getName())&&!t.isSuspended()&&(t.getState()==io.agentscope.core.message.ToolResultState.SUCCESS||t.getState()==io.agentscope.core.message.ToolResultState.DENIED));
            if (!exact || !result) throw new SecurityException("Original child completion receipt missing");
        }
        approval.calls().forEach(call->awaiting.remove(new ApprovalKey(approval.locator(),call)));
    }
    private record AskSnapshot(String replyId, java.util.List<CallSnapshot> calls) { }
    static String canonical(Object value) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        return sort(mapper.valueToTree(value)).toString();
    }
    private static com.fasterxml.jackson.databind.JsonNode sort(com.fasterxml.jackson.databind.JsonNode node) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (node.isObject()) {
            var result = mapper.createObjectNode();
            var keys = new java.util.TreeSet<String>(); node.fieldNames().forEachRemaining(keys::add);
            keys.forEach(key -> result.set(key, sort(node.get(key)))); return result;
        }
        if (node.isArray()) { var result = mapper.createArrayNode(); node.forEach(item -> result.add(sort(item))); return result; }
        return node.deepCopy();
    }
    private final Map<String, Invocation> origins = new ConcurrentHashMap<>();
    private final Map<String, String> sessionOrigins = new ConcurrentHashMap<>();
    private record ApprovalKey(String locator, CallSnapshot call) { }
    private final Map<ApprovalKey, AskSnapshot> awaiting = new ConcurrentHashMap<>();

    /** Locator is internal correlation only; authority remains the registered actor and owning run. */
    String invocationLocator(Agent actor, RuntimeContext context) {
        requireKnown(actor, context);
        if (trustedRoot.getSessionId().equals(context.getSessionId())) return null;
        String locator = sessionOrigins.computeIfAbsent(context.getSessionId(), key -> java.util.UUID.randomUUID().toString());
        Invocation existing = origins.putIfAbsent(locator, new Invocation(actualActor(actor), context));
        if (existing != null && existing.actor() != actualActor(actor)) throw new SecurityException("Invocation actor changed");
        return locator;
    }

    /** Consume before AG-UI conversion; no token, runtime state or actor escapes to the browser. */
    public void captureAndStrip(io.agentscope.core.event.AgentEvent event) {
        var metadata = event.getMetadata();
        if (metadata == null || !metadata.containsKey(INTERNAL_ORIGIN)) return;
        Object locator = metadata.get(INTERNAL_ORIGIN);
        var clean = new java.util.LinkedHashMap<String,Object>(metadata);
        clean.remove(INTERNAL_ORIGIN);
        event.withMetadata(clean);
        Invocation invocation = origins.get(locator);
        if (invocation == null) throw new SecurityException("Unknown child event origin");
        requireKnown(invocation.actor(), invocation.context());
        if (event instanceof io.agentscope.core.event.RequireUserConfirmEvent ask) {
            if (ask.getToolCalls() == null || ask.getToolCalls().isEmpty()) throw new SecurityException("Empty child approval");
            for (var call : ask.getToolCalls()) {
                if (call.getId() == null || call.getName() == null) throw new SecurityException("Child call identity is unavailable");
                var snapshot = CallSnapshot.of(call);
                awaiting.putIfAbsent(new ApprovalKey((String)locator, snapshot), new AskSnapshot(ask.getReplyId(), java.util.List.of(snapshot)));
            }
        }
    }

    public boolean hasPendingChildApprovals() { return !awaiting.isEmpty(); }

    /** Only persisted original ASK calls form a pause request; root checkpoint must already exist. */
    public java.util.List<ChildApproval> checkpointApprovals(io.agentscope.core.state.AgentStateStore store, long rootVersion) {
        requireCurrentAccess.run();
        if (rootVersion < 0) throw new SecurityException("Parent checkpoint is not persisted");
        var parentSaved = store.getVersioned(trustedRoot.getUserId(), trustedRoot.getSessionId(),
            "agent_state", io.agentscope.core.state.AgentState.class);
        if (!parentSaved.isPresent() || parentSaved.version() != rootVersion) throw new SecurityException("Parent checkpoint changed");
        var result = new java.util.ArrayList<ChildApproval>();
        awaiting.forEach((key, ask) -> {
            String locator = key.locator();
            Invocation invocation = origins.get(locator);
            requireKnown(invocation.actor(), invocation.context());
            var saved = store.getVersioned(trustedRoot.getUserId(), invocation.context().getSessionId(),
                "agent_state", io.agentscope.core.state.AgentState.class);
            if (!saved.isPresent() || saved.version() < 0) throw new SecurityException("Child checkpoint is not persisted");
            var messages = saved.value().getContext();
            var latest = messages.stream().filter(msg -> msg.getRole() == io.agentscope.core.message.MsgRole.ASSISTANT)
                .reduce((first, second) -> second).orElseThrow(() -> new SecurityException("No active child assistant"));
            var calls = ask.calls();
            for (var call : calls) {
                boolean completed = messages.stream().flatMap(msg -> msg.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class).stream())
                    .anyMatch(item -> java.util.Objects.equals(item.getId(), call.id()));
                long matches = latest.getContentBlocks(io.agentscope.core.message.ToolUseBlock.class).stream()
                    .filter(item -> item.getState() == io.agentscope.core.message.ToolCallState.ASKING && CallSnapshot.of(item).equals(call)).count();
                if (completed || matches != 1) throw new SecurityException("Child approval is not the latest unfinished native ASK");
            }
            result.add(new ChildApproval(locator, trustedRoot.getUserId(), invocation.context().getSessionId(), saved.version(), ask.replyId(), java.util.List.copyOf(calls), requireFactory(invocation.actor()).factory(), checkpointParent(requireFactory(invocation.actor()).parent(),store)));
        });
        return java.util.List.copyOf(result);
    }

    private FactoryBinding requireFactory(Agent actor) {
        var binding=factoryBindings.get(actualActor(actor));
        if(binding==null) throw new SecurityException("Original factory descriptor unavailable");
        return binding;
    }
    private final RuntimeContext trustedRoot;
    private final Runnable requireCurrentAccess;
    private final Set<Agent> factoryActors = Collections.synchronizedSet(
        Collections.newSetFromMap(new IdentityHashMap<>()));
    private final Map<String, Set<Agent>> childInvocations = new ConcurrentHashMap<>();
    private volatile Agent rootActor;

    public ProjectAgentChildLineageRegistry(RuntimeContext trustedRoot, Runnable requireCurrentAccess) {
        this.trustedRoot = java.util.Objects.requireNonNull(trustedRoot);
        this.requireCurrentAccess = java.util.Objects.requireNonNull(requireCurrentAccess);
    }

    public synchronized void bindRoot(HarnessAgent parent) {
        Agent actual = parent.getDelegate();
        if (rootActor != null && rootActor != actual) {
            throw new SecurityException("Parent actor registration cannot be replaced");
        }
        rootActor = actual;
    }

    /** 只能由已授权的官方 factory consumer 调用，注册真实对象而非名称相等。 */
    void registerFactoryChild(Agent child) {
        requireCurrentAccess.run();
        factoryActors.add(actualActor(child));
        factoryHandles.put(actualActor(child),child);
    }

    /** Scope middleware 已核 opaque 父授权 token 后，登记官方实际 child invocation。 */
    void registerInvocation(Agent actor, RuntimeContext context) {
        requireCurrentAccess.run();
        requireUser(context);
        Agent actual = actualActor(actor);
        if (trustedRoot.getSessionId().equals(context.getSessionId())) {
            if (actual != rootActor) { throw new SecurityException("Unknown parent actor"); }
            return;
        }
        if (!factoryActors.contains(actual)) { throw new SecurityException("Child factory actor is not registered"); }
        childInvocations.computeIfAbsent(context.getSessionId(), key -> Collections.synchronizedSet(
            Collections.newSetFromMap(new IdentityHashMap<>()))).add(actual);
    }

    /** 供正式业务治理 hook 在 acting 前共享调用；新 session 字符串不产生授权。 */
    public void requireKnown(Agent actor, RuntimeContext context) {
        requireCurrentAccess.run();
        requireUser(context);
        Agent actual = actualActor(actor);
        if (trustedRoot.getSessionId().equals(context.getSessionId()) && actual == rootActor) { return; }
        Set<Agent> known = childInvocations.get(context.getSessionId());
        if (known == null || !known.contains(actual)) {
            throw new SecurityException("Child invocation is not registered by the parent run");
        }
    }

    /** State-store slot admission uses this same actual invocation registry, never a session-name pattern. */
    public boolean ownsSession(String user, String session) {
        if (!trustedRoot.getUserId().equals(user) || session == null) return false;
        if (trustedRoot.getSessionId().equals(session)) return rootActor != null;
        Set<Agent> known = childInvocations.get(session);
        return known != null && !known.isEmpty();
    }

    private void requireUser(RuntimeContext context) {
        if (context == null || !trustedRoot.getUserId().equals(context.getUserId())
            || context.getSessionId() == null || context.getSessionId().isBlank()) {
            throw new SecurityException("Trusted project person is unavailable");
        }
    }

    private static Agent actualActor(Agent agent) {
        return agent instanceof HarnessAgent harness ? harness.getDelegate() : agent;
    }
}
