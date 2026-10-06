package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolCallParam;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

/** Server-only approved execution capability. No attribute/token is put into RuntimeContext or persisted state. */
public final class ProjectAgentExecutionClaims implements AutoCloseable {
    public interface DeliveryPathNormalizer { String normalize(Agent actor, RuntimeContext runtime, String path); }
    /** 除官方 deliver_artifact 外，唯一可交付产物的受治理工具（本地渲染工具，交付内容为引擎产物）。 */
    public static final String RENDER_DELIVERY_TOOL = org.ruoyi.ipd.agent.catalog.ProjectAgentToolCatalog.HTML_PAGE_RENDER;
    public record Binding(String runId, String userId, long epoch) { }
    private enum Phase { ISSUED, RESERVED, COMMITTED, ABORTED, CLOSED }
    private static final Object REACTOR_KEY = new Object();
    private final DeliveryPathNormalizer pathNormalizer;
    private boolean sealed;
    private final Binding binding;
    private final LongSupplier currentEpoch;
    private final BiConsumer<Agent, RuntimeContext> currentAuthority;
    private final IdentityHashMap<RuntimeContext, Claim> issued = new IdentityHashMap<>();
    private static final class Claim {
        final Agent actor;
        final RuntimeContext runtime;
        final String toolName;
        final String toolCallId;
        final com.fasterxml.jackson.databind.JsonNode input;
        final String rawContentHash;
        final String normalizedPath;
        Phase phase = Phase.ISSUED;
        Claim(Agent actor, RuntimeContext runtime, String toolName, String toolCallId, com.fasterxml.jackson.databind.JsonNode input, String rawContentHash, String normalizedPath) {
            this.actor = actor; this.runtime = runtime; this.toolName = toolName; this.toolCallId = toolCallId; this.input = input; this.rawContentHash = rawContentHash; this.normalizedPath = normalizedPath;
        }
        @Override public String toString() { return "[server execution capability]"; }
    }
    public ProjectAgentExecutionClaims(Binding binding, LongSupplier currentEpoch,
            BiConsumer<Agent, RuntimeContext> currentAuthority, DeliveryPathNormalizer pathNormalizer) {
        this.pathNormalizer = Objects.requireNonNull(pathNormalizer);
        this.binding = Objects.requireNonNull(binding);
        this.currentEpoch = Objects.requireNonNull(currentEpoch);
        this.currentAuthority = Objects.requireNonNull(currentAuthority);
    }
    Binding binding() { return binding; }
    // Only the official governance execution boundary calls this AFTER canonical ALLOWED is consumed.
    ExecutionScope openApproved(ToolCallParam param) {
        Objects.requireNonNull(param.getAgent());
        Objects.requireNonNull(param.getRuntimeContext());
        currentAuthority.accept(param.getAgent(), param.getRuntimeContext());
        if (currentEpoch.getAsLong() != binding.epoch()
            || !binding.userId().equals(param.getRuntimeContext().getUserId())) throw denied();
        var copy = RuntimeContext.builder(param.getRuntimeContext()).build();
        var use = Objects.requireNonNull(param.getToolUseBlock());
        var input = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(param.getInput());
        String normalized = "deliver_artifact".equals(use.getName())
            ? pathNormalizer.normalize(param.getAgent(), copy, input.path("filePath").asText()) : null;
        var claim = new Claim(param.getAgent(), copy, use.getName(), use.getId(), input, hash(Objects.toString(use.getContent(), "")), normalized);
        synchronized (issued) { if (sealed) throw denied(); issued.put(copy, claim); }
        return new ExecutionScope(claim);
    }
    public void requireAuthorized(RuntimeContext runtime, Binding expected) {
        Claim claim = checked(runtime, expected);
        if ((!"deliver_artifact".equals(claim.toolName) && !RENDER_DELIVERY_TOOL.equals(claim.toolName))
                || claim.toolCallId == null) throw denied();
    }
    public void requireDelivery(RuntimeContext runtime, Binding expected,
            io.agentscope.harness.agent.artifact.ArtifactDeliveryRequest request) {
        requireAuthorized(runtime, expected);
        Claim claim = checked(runtime, expected);
        if (RENDER_DELIVERY_TOOL.equals(claim.toolName)) {
            // 渲染工具的交付内容由引擎在服务端生成：仅 fileName 与裁决入参一致即可，
            // 不携带沙箱路径/描述/强制标记，字段形态与工具入参合同一一对应。
            String name = claim.input.path("fileName").asText("");
            if (request == null || request.filePath() != null || request.description() != null
                    || request.force() || !Objects.equals(request.fileName(), name)) throw denied();
            return;
        }
        String name = claim.input.path("fileName").asText("");
        if (name.isBlank()) {
            String path = claim.normalizedPath.replace('\\', '/');
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            name = path.substring(path.lastIndexOf('/') + 1);
        }
        String description = claim.input.path("description").isMissingNode() || claim.input.path("description").isNull()
            ? null : claim.input.path("description").asText();
        if (request == null || !Objects.equals(request.filePath(), claim.normalizedPath)
            || !Objects.equals(request.fileName(), name) || !Objects.equals(request.description(), description)
            || request.force() != claim.input.path("force").asBoolean(false)) throw denied();
    }
    public void requireReactive(ContextView context, RuntimeContext runtime, Binding expected) {
        Claim claim = checked(runtime, expected);
        if (context.getOrDefault(REACTOR_KEY, null) != claim) throw denied();
    }
    private Claim checked(RuntimeContext runtime, Binding expected) {
        Claim claim;
        synchronized (issued) {
            claim = issued.get(runtime);
            if (claim == null || !binding.equals(expected) || claim.phase == Phase.COMMITTED
                || claim.phase == Phase.ABORTED || claim.phase == Phase.CLOSED) throw denied();
        }
        if (currentEpoch.getAsLong() != binding.epoch()) throw denied();
        currentAuthority.accept(claim.actor, claim.runtime);
        return claim;
    }
    /** Validate can be repeated; reserve is exactly once and occurs ONLY inside the original owned transaction. */
    public Reservation reserve(RuntimeContext runtime, Binding expected) {
        requireAuthorized(runtime, expected);
        Claim claim = checked(runtime, expected);
        synchronized (issued) {
            if (issued.get(runtime) != claim || claim.phase != Phase.ISSUED) throw denied();
            claim.phase = Phase.RESERVED;
        }
        return new Reservation(claim);
    }
    public final class ExecutionScope implements AutoCloseable {
        private final Claim claim;
        private ExecutionScope(Claim claim) { this.claim = claim; }
        public RuntimeContext runtime() { return claim.runtime; }
        public Context contextWrite(Context context) { return context.put(REACTOR_KEY, claim); }
        @Override public void close() {
            synchronized (issued) { issued.remove(claim.runtime); claim.phase = Phase.CLOSED; }
        }
    }
    public final class Reservation {
        private final Claim claim;
        private Reservation(Claim claim) { this.claim = claim; }
        public void commit() { finish(Phase.COMMITTED); }
        public void rollback() { finish(Phase.ABORTED); }
        private void finish(Phase phase) {
            synchronized (issued) {
                if (claim.phase != Phase.RESERVED) throw denied();
                claim.phase = phase;
            }
        }
    }
    @Override public void close() {
        synchronized (issued) { sealed = true; issued.values().forEach(c -> c.phase = Phase.CLOSED); issued.clear(); }
    }
    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static SecurityException denied() { return new SecurityException("No current server-approved execution capability"); }
}
