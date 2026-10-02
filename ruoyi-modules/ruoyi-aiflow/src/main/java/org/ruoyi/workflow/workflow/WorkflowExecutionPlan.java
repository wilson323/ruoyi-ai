package org.ruoyi.workflow.workflow;

import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.entity.WorkflowEdge;
import org.ruoyi.workflow.workflow.checkpoint.JdbcCheckpointSaver;
import org.ruoyi.workflow.workflow.checkpoint.WorkflowCheckpointState;
import org.ruoyi.workflow.workflow.checkpoint.WorkflowCheckpointConfig;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 确定性业务图调度。AgentScope 承担模型与工具调用；本类只保留业务边、join 和检查点。 */
public final class WorkflowExecutionPlan {
    public static final String END = "__END__";
    public static final String START = "__START__";
    private final Map<String, WorkflowNode> nodes;
    private final Map<String, List<WorkflowEdge>> incoming = new LinkedHashMap<>();
    private final Map<String, List<WorkflowEdge>> outgoing = new LinkedHashMap<>();
    private final String start;
    private final WorkflowNodeRunner runner;
    private final WfState wfState;
    private final String digest;
    private final Set<String> sideEffects;

    WorkflowExecutionPlan(List<WorkflowNode> definitions, List<WorkflowEdge> edges,
                          String start, WorkflowNodeRunner runner, WfState wfState, Set<String> sideEffects) {
        this.start = start; this.runner = runner; this.wfState = wfState; this.sideEffects = Set.copyOf(sideEffects);
        nodes = new LinkedHashMap<>();
        for (WorkflowNode node : definitions) {
            if (node.getUuid() == null || nodes.putIfAbsent(node.getUuid(), node) != null)
                throw new IllegalArgumentException("工作流节点标识缺失或重复");
        }
        if (!nodes.containsKey(start)) throw new IllegalArgumentException("工作流开始节点不存在");
        Set<String> unique = new HashSet<>();
        for (WorkflowEdge edge : edges) {
            String source = edge.getSourceNodeUuid(), target = edge.getTargetNodeUuid();
            if (!nodes.containsKey(source) || !nodes.containsKey(target))
                throw new IllegalArgumentException("工作流边指向不存在的节点");
            if (!unique.add(source + "\0" + target)) throw new IllegalArgumentException("工作流存在重复边");
            outgoing.computeIfAbsent(source, k -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(target, k -> new ArrayList<>()).add(edge);
        }
        if (!incoming.getOrDefault(start, List.of()).isEmpty()) throw new IllegalArgumentException("开始节点不能有入边");
        Set<String> visited = new HashSet<>(), visiting = new HashSet<>();
        visit(start, visiting, visited);
        if (visited.size() != nodes.size()) throw new IllegalArgumentException("工作流存在孤立或不可达节点");
        for (var entry : outgoing.entrySet()) {
            List<WorkflowEdge> next = entry.getValue();
            if (next.size() > 1) {
                long blank = next.stream().filter(e -> e.getSourceHandle() == null || e.getSourceHandle().isBlank()).count();
                if (blank != 0 && blank != next.size()) throw new IllegalArgumentException("工作流并行边与条件边不能混用");
                if (blank == next.size()) {
                    // 保留原合同：并行支路内部不接受条件分支。
                    Set<String> branch = new HashSet<>();
                    for (WorkflowEdge edge : next) rejectConditionalInBranch(edge.getTargetNodeUuid(), branch);
                }
            }
        }
        try {
            List<String> canonical = new ArrayList<>();
            nodes.forEach((id, n) -> canonical.add("N:" + id + ":" + n.getWorkflowComponentId() + ":" + Objects.toString(n.getInputConfig(), "") + ":" + Objects.toString(n.getNodeConfig(), "")));
            edges.forEach(e -> canonical.add("E:" + e.getSourceNodeUuid() + ":" + e.getTargetNodeUuid() + ":" + Objects.toString(e.getSourceHandle(), "")));
            Collections.sort(canonical);
            digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.join("\n", canonical).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private void visit(String node, Set<String> visiting, Set<String> visited) {
        if (visiting.contains(node)) throw new IllegalArgumentException("工作流存在循环");
        if (!visited.add(node)) return;
        visiting.add(node);
        for (WorkflowEdge edge : outgoing.getOrDefault(node, List.of())) visit(edge.getTargetNodeUuid(), visiting, visited);
        visiting.remove(node);
    }
    private void rejectConditionalInBranch(String node, Set<String> seen) {
        if (!seen.add(node) || incoming.getOrDefault(node, List.of()).size() > 1) return;
        var next = outgoing.getOrDefault(node, List.of());
        if (next.size() > 1 && next.stream().anyMatch(e -> e.getSourceHandle() != null && !e.getSourceHandle().isBlank()))
            throw new IllegalArgumentException("并行节点中不能包含条件分支");
        next.forEach(e -> rejectConditionalInBranch(e.getTargetNodeUuid(), seen));
    }

    public String execute(JdbcCheckpointSaver saver, String thread, boolean resume, Map<String, Object> initial,
                          Consumer<String> afterNode) throws Exception {
        try (JdbcCheckpointSaver.RunLease lease = saver.acquireRun(thread)) {
            return executeLocked(saver, thread, resume, initial, afterNode, lease);
        }
    }
    private String executeLocked(JdbcCheckpointSaver saver, String thread, boolean resume, Map<String, Object> initial,
                          Consumer<String> afterNode, JdbcCheckpointSaver.RunLease lease) throws Exception {
        lease.requireHeld();
        WorkflowCheckpointConfig config = WorkflowCheckpointConfig.builder().threadId(thread).build();
        LinkedHashSet<String> completed = new LinkedHashSet<>(), skipped = new LinkedHashSet<>();
        LinkedHashMap<String, List<String>> routes = new LinkedHashMap<>();
        Map<String, Object> metadata = new LinkedHashMap<>(initial);
        LinkedHashSet<String> inFlight = new LinkedHashSet<>();
        if (resume) {
            WorkflowCheckpointState checkpoint = saver.get(config).orElseThrow(() -> new IllegalStateException("没有有效检查点，不能重新执行工作流"));
            Map<String, Object> state = checkpoint.getState();
            if (!Objects.equals(state.get("workflowFormat"), 2)) {
                restoreLegacy(checkpoint, completed, routes, metadata);
            } else {
                if (!Objects.equals(digest, state.get("graphDigest"))) throw new IllegalStateException("工作流定义已改变，不能恢复旧检查点");
                completed.addAll(strings(state.get("completed"))); skipped.addAll(strings(state.get("skipped")));
                inFlight.addAll(strings(state.get("inFlight")));
                if (inFlight.stream().anyMatch(sideEffects::contains)) throw new IllegalStateException("副作用节点结果尚未确认，禁止自动重放，请先核对业务结果");
                Object storedRoutes = state.get("routes");
                if (!(storedRoutes instanceof Map<?, ?> routeMap)) throw new IllegalStateException("检查点缺少分支选择");
                routeMap.forEach((k, v) -> routes.put(k.toString(), strings(v)));
                Object values = state.get("metadata");
                if (values instanceof Map<?, ?> map) map.forEach((k, v) -> metadata.put(k.toString(), v));
            }
            if (!nodes.keySet().containsAll(inFlight) || !Collections.disjoint(inFlight, completed) || !Collections.disjoint(inFlight, skipped))
                throw new IllegalStateException("检查点执行中节点状态错误");
            if (!nodes.keySet().containsAll(completed) || !nodes.keySet().containsAll(skipped)) throw new IllegalStateException("检查点包含未知节点");
            if (!Collections.disjoint(completed, skipped) || skipped.contains(start) || !completed.containsAll(routes.keySet()) || !routes.keySet().containsAll(completed))
                throw new IllegalStateException("检查点完成状态或路由缺失");
            for (var entry : routes.entrySet()) {
                Set<String> legal = new HashSet<>(); outgoing.getOrDefault(entry.getKey(), List.of()).forEach(e -> legal.add(e.getTargetNodeUuid()));
                if (!legal.containsAll(entry.getValue())) throw new IllegalStateException("检查点包含未配置路由");
            }
        } else {
            if (saver.get(config).isPresent()) throw new IllegalStateException("运行已有检查点，禁止从开始重复执行");
            save(saver, config, START, start, completed, skipped, routes, metadata, inFlight, lease);
        }
        if (resume && wfState != null) wfState.retainCheckpointCompleted(completed);
        String tenant = wfState == null ? null : org.ruoyi.common.tenant.helper.TenantHelper.getTenantId();
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, Math.min(8, nodes.size())));
        Object checkpointLock = new Object();
        java.util.concurrent.atomic.AtomicBoolean checkpointFailed = new java.util.concurrent.atomic.AtomicBoolean();
        try {
            while (completed.size() + skipped.size() < nodes.size()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("工作流执行被中断");
                int skippedBefore = skipped.size();
                List<String> ready = new ArrayList<>();
                for (String node : nodes.keySet()) {
                    if (completed.contains(node) || skipped.contains(node)) continue;
                    List<WorkflowEdge> predecessors = incoming.getOrDefault(node, List.of());
                    boolean resolved = predecessors.stream().allMatch(e -> completed.contains(e.getSourceNodeUuid()) || skipped.contains(e.getSourceNodeUuid()));
                    if (!resolved) continue;
                    boolean active = node.equals(start) || predecessors.stream().anyMatch(e -> routes.getOrDefault(e.getSourceNodeUuid(), List.of()).contains(node));
                    if (active) ready.add(node); else skipped.add(node);
                }
                if (ready.isEmpty()) {
                    if (completed.size() + skipped.size() == nodes.size()) break;
                    if (skipped.size() > skippedBefore) continue;
                    throw new IllegalStateException("工作流没有可执行节点，检查点或路由不一致");
                }
                inFlight.addAll(ready);
                save(saver, config, completed.stream().reduce((a,b) -> b).orElse(START), ready.get(0), completed, skipped, routes, metadata, inFlight, lease);
                List<Future<?>> futures = new ArrayList<>();
                for (String node : ready) futures.add(executor.submit(() -> {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("节点执行被中断");
                    Map<String, Object> result;
                    if (tenant != null) org.ruoyi.common.tenant.helper.TenantHelper.setDynamic(tenant);
                    try {
                    if (wfState != null) wfState.beginNode(node);
                    Map<String, Object> input;
                    synchronized (checkpointLock) { input = new LinkedHashMap<>(metadata); }
                    try {
                        synchronized (checkpointLock) {
                            if (checkpointFailed.get()) throw new IllegalStateException("检查点写入已失败，禁止启动后续节点");
                            lease.requireHeld();
                        }
                        result = runner.run(nodes.get(node), new WfNodeState(input));
                    } catch (Exception error) { throw new CompletionException(error); }
                    finally {
                        if (wfState != null) wfState.endNode();
                    }
                    if (result == null) throw new IllegalStateException("节点没有返回执行结果");
                    var next = outgoing.getOrDefault(node, List.of());
                    List<String> chosen;
                    if (next.size() > 1 && next.stream().noneMatch(e -> e.getSourceHandle() == null || e.getSourceHandle().isBlank())) {
                        String target = WorkflowGraphBuilder.resolveNextRoute(result, node);
                        if (next.stream().noneMatch(e -> target.equals(e.getTargetNodeUuid()))) throw new IllegalStateException("条件路由指向未配置节点");
                        chosen = List.of(target);
                    } else chosen = next.stream().map(WorkflowEdge::getTargetNodeUuid).toList();
                    synchronized (checkpointLock) {
                        try {
                            if (checkpointFailed.get()) throw new IllegalStateException("检查点写入已失败，禁止提交后续节点");
                            lease.requireHeld();
                            afterNode.accept(node);
                            metadata.putAll(result); routes.put(node, chosen); completed.add(node); inFlight.remove(node);
                            String pending = nodes.keySet().stream().filter(n -> !completed.contains(n) && !skipped.contains(n)).findFirst().orElse(END);
                            save(saver, config, node, pending, completed, skipped, routes, metadata, inFlight, lease);
                        } catch (Exception error) {
                            checkpointFailed.set(true);
                            throw new CompletionException(error);
                        }
                    }
                    } finally {
                        if (tenant != null) org.ruoyi.common.tenant.helper.TenantHelper.clearDynamic();
                    }
                }));
                Exception failure = null;
                for (Future<?> future : futures) {
                    try { future.get(); }
                    catch (ExecutionException error) {
                        if (failure == null) failure = new IllegalStateException("工作流节点执行失败", error.getCause());
                        else failure.addSuppressed(error.getCause());
                    }
                }
                if (failure != null) throw failure;
            }
            save(saver, config, completed.stream().reduce((a, b) -> b).orElse(START), END, completed, skipped, routes, metadata, inFlight, lease);
            return completed.stream().reduce((a, b) -> b).orElse(START);
        } finally {
            executor.shutdownNow();
            boolean interrupted = Thread.interrupted();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("工作流节点未停止，禁止重放运行");
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
    private void restoreLegacy(WorkflowCheckpointState checkpoint, Set<String> completed,
                               Map<String, List<String>> routes, Map<String, Object> metadata) {
        // 旧复杂子图没有完整活跃前驱/分支快照。拒绝猜测，保留存量记录供显式恢复。
        if (outgoing.values().stream().anyMatch(e -> e.size() > 1) || incoming.values().stream().anyMatch(e -> e.size() > 1))
            throw new IllegalStateException("旧并行或条件检查点需要明确恢复，禁止重放副作用");
        String next = checkpoint.getNextNodeId();
        if (!END.equals(next) && !nodes.containsKey(next)) throw new IllegalStateException("旧检查点下一节点不可识别");
        if (sideEffects.contains(next)) throw new IllegalStateException("旧检查点副作用结果不明，禁止自动重放");
        String current = start;
        while (!current.equals(next) && !END.equals(current)) {
            completed.add(current);
            List<String> successors = outgoing.getOrDefault(current, List.of()).stream().map(WorkflowEdge::getTargetNodeUuid).toList();
            routes.put(current, successors); current = successors.isEmpty() ? END : successors.get(0);
        }
        if (!current.equals(next)) throw new IllegalStateException("旧检查点不在当前图中");
        metadata.putAll(checkpoint.getState());
    }
    private static List<String> strings(Object raw) {
        if (!(raw instanceof Collection<?> values) || values.stream().anyMatch(v -> !(v instanceof String)))
            throw new IllegalStateException("检查点节点集合格式错误");
        return values.stream().map(String.class::cast).toList();
    }
    private void save(JdbcCheckpointSaver saver, WorkflowCheckpointConfig config, String node, String next,
                      Set<String> completed, Set<String> skipped, Map<String, List<String>> routes, Map<String, Object> metadata, Set<String> inFlight, JdbcCheckpointSaver.RunLease lease) throws Exception {
        lease.requireHeld();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("workflowFormat", 2); state.put("graphDigest", digest);
        state.put("completed", new ArrayList<>(completed)); state.put("skipped", new ArrayList<>(skipped));
        state.put("inFlight", new ArrayList<>(inFlight));
        state.put("routes", new LinkedHashMap<>(routes)); state.put("metadata", new LinkedHashMap<>(metadata));
        saver.put(config, WorkflowCheckpointState.builder().nodeId(node).nextNodeId(next).state(state).build());
    }
}
