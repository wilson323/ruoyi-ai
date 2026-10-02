package org.ruoyi.workflow.workflow.checkpoint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.ruoyi.workflow.entity.WorkflowCheckpoint;
import org.ruoyi.workflow.mapper.WorkflowCheckpointMapper;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;

/** 复用 t_workflow_checkpoint 的栈序与逻辑删除合同，不建第二张状态表。 */
@Component
public class JdbcCheckpointSaver  {

    private final WorkflowCheckpointMapper checkpointMapper;
    private final javax.sql.DataSource dataSource;
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.Semaphore> TEST_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();
    public JdbcCheckpointSaver(WorkflowCheckpointMapper checkpointMapper) { this(checkpointMapper, null); }
    @org.springframework.beans.factory.annotation.Autowired
    public JdbcCheckpointSaver(WorkflowCheckpointMapper checkpointMapper, javax.sql.DataSource dataSource) {
        this.checkpointMapper = checkpointMapper; this.dataSource = dataSource;
    }
    public interface RunLease extends AutoCloseable { void requireHeld() throws Exception; @Override void close() throws Exception; }
    public RunLease acquireRun(String runtimeUuid) throws Exception {
        if (runtimeUuid == null || runtimeUuid.isBlank()) throw new IllegalArgumentException("运行标识不能为空");
        if (dataSource == null) {
            // 仅一参数 mock mapper 单元夹具使用；Spring 生产装配必须注入真实 DataSource。
            var lock = TEST_LOCKS.computeIfAbsent(runtimeUuid, key -> new java.util.concurrent.Semaphore(1));
            if (!lock.tryAcquire()) throw new IllegalStateException("工作流正在执行，不能同时恢复");
            return new RunLease() {
                private final java.util.concurrent.atomic.AtomicBoolean held = new java.util.concurrent.atomic.AtomicBoolean(true);
                public void requireHeld() { if (!held.get()) throw new IllegalStateException("工作流执行锁已释放"); }
                public void close() { if (held.compareAndSet(true,false)) lock.release(); }
            };
        }
        java.sql.Connection connection = dataSource.getConnection();
        try {
            String database;
            try (var query = connection.prepareStatement("SELECT DATABASE()"); var rows = query.executeQuery()) {
                if (!rows.next() || (database = rows.getString(1)) == null) throw new IllegalStateException("检查点数据库未选择");
            }
            String key = "ipd-wf:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((database + "\0" + runtimeUuid).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 48);
            try (var query = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
                query.setString(1, key);
                try (var rows = query.executeQuery()) { if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull()) throw new IllegalStateException("工作流正在执行，不能同时恢复"); }
            }
            return new RunLease() {
                public void requireHeld() throws Exception {
                    synchronized (connection) {
                        try (var query = connection.prepareStatement("SELECT IS_USED_LOCK(?), CONNECTION_ID()")) {
                            query.setString(1,key);
                            try (var rows = query.executeQuery()) {
                                if (!rows.next()) throw new IllegalStateException("工作流执行锁已丢失");
                                long owner = rows.getLong(1); boolean missing = rows.wasNull();
                                if (missing || owner != rows.getLong(2)) throw new IllegalStateException("工作流执行锁已丢失");
                            }
                        }
                    }
                }
                public void close() throws Exception {
                    synchronized (connection) {
                        try (var query = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                            query.setString(1,key);
                            try (var rows = query.executeQuery()) { if (!rows.next() || rows.getInt(1) != 1 || rows.wasNull()) throw new IllegalStateException("工作流执行锁已丢失"); }
                        } finally { connection.close(); }
                    }
                }
            };
        } catch (Exception error) { connection.close(); throw error; }
    }

    /**
     * Checkpoint → state_json 载荷（Base64 of CheckpointSerializer 二进制流）
     */
    static String serializeCheckpoint(WorkflowCheckpointState checkpoint) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            out.writeUTF("IPD-WORKFLOW-CHECKPOINT-2");
            out.writeObject(checkpoint.getId()); out.writeObject(checkpoint.getNodeId());
            out.writeObject(checkpoint.getNextNodeId()); out.writeObject(checkpoint.getState());
        }
        return "v2:" + Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    /**
     * state_json 载荷 → Checkpoint（含 state Map 反序列化往返）
     */
    static WorkflowCheckpointState deserializeCheckpoint(String stateJson) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(stateJson.startsWith("v2:") ? stateJson.substring(3) : stateJson);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            in.setObjectInputFilter(info -> {
                if (info.depth() > 64 || info.references() > 100000 || info.streamBytes() > 32L * 1024 * 1024 || info.arrayLength() > 100000)
                    return java.io.ObjectInputFilter.Status.REJECTED;
                Class<?> type = info.serialClass();
                if (type == null) return java.io.ObjectInputFilter.Status.UNDECIDED;
                while (type.isArray()) type = type.getComponentType();
                String name = type.getName();
                return type.isPrimitive() || name.startsWith("java.lang.") || name.startsWith("java.util.") || name.startsWith("java.math.")
                    || name.startsWith("org.ruoyi.workflow.workflow.data.")
                    ? java.io.ObjectInputFilter.Status.ALLOWED : java.io.ObjectInputFilter.Status.REJECTED;
            });
            // 新载荷有明确版本，历史二进制载荷由只读兼容解码器识别。
            if (stateJson.startsWith("v2:")) {
                if (!"IPD-WORKFLOW-CHECKPOINT-2".equals(in.readUTF())) throw new java.io.IOException("检查点协议版本错误");
                String id = (String) in.readObject(), node = (String) in.readObject(), next = (String) in.readObject();
                Object raw = in.readObject();
                if (!(raw instanceof java.util.Map<?, ?> map)) throw new java.io.IOException("检查点状态不是Map");
                java.util.Map<String, Object> state = new java.util.LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) throw new java.io.IOException("检查点状态键错误");
                    state.put(key, entry.getValue());
                }
                return WorkflowCheckpointState.builder().id(id).nodeId(node).nextNodeId(next).state(state).build();
            }
            return LegacyWorkflowCheckpointDecoder.read(in);
        }
    }

    public static final String THREAD_ID_DEFAULT = "$default";
    public record Tag(String threadId, java.util.List<WorkflowCheckpointState> checkpoints) { }
    private String threadId(WorkflowCheckpointConfig config) {
        return config.getThreadId() == null ? THREAD_ID_DEFAULT : config.getThreadId();
    }
    public synchronized java.util.List<WorkflowCheckpointState> list(WorkflowCheckpointConfig config) throws Exception {
        return java.util.List.copyOf(loadCheckpoints(config));
    }
    public synchronized java.util.Optional<WorkflowCheckpointState> get(WorkflowCheckpointConfig config) throws Exception {
        var values = loadCheckpoints(config);
        if (config.getCheckPointId() != null) return values.stream().filter(c -> config.getCheckPointId().equals(c.getId())).findFirst();
        return values.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(values.getFirst());
    }
    public synchronized void put(WorkflowCheckpointConfig config, WorkflowCheckpointState checkpoint) throws Exception {
        if (config.getCheckPointId() == null) insertedCheckpoint(config, new LinkedList<>(), checkpoint);
        else {
            var values = loadCheckpoints(config);
            if (values.stream().noneMatch(c -> config.getCheckPointId().equals(c.getId()))) throw new IllegalStateException("检查点不存在");
            if (!config.getCheckPointId().equals(checkpoint.getId())) throw new IllegalArgumentException("替换检查点ID不一致");
            updatedCheckpoint(config, values, checkpoint);
        }
    }
    public synchronized Tag release(WorkflowCheckpointConfig config) throws Exception {
        return releaseCheckpoints(config, loadCheckpoints(config));
    }

    /**
     * 按 thread_id 取该线程全部 checkpoint，栈序（最新在头，peek() 是最新）。
     * 行按 id 升序（插入序）读出后逐条 push 头插，最后插入的（最新）落在栈顶。
     */
    protected LinkedList<WorkflowCheckpointState> loadCheckpoints(WorkflowCheckpointConfig config) throws Exception {
        List<WorkflowCheckpoint> rows = checkpointMapper.selectList(new LambdaQueryWrapper<WorkflowCheckpoint>()
                .eq(WorkflowCheckpoint::getThreadId, threadId(config))
                .eq(WorkflowCheckpoint::getIsDeleted, false)
                .orderByAsc(WorkflowCheckpoint::getId));
        LinkedList<WorkflowCheckpointState> checkpoints = new LinkedList<>();
        for (WorkflowCheckpoint row : rows) {
            checkpoints.push(deserializeCheckpoint(row.getStateJson()));
        }
        return checkpoints;
    }

    /**
     * 新增一条 checkpoint（基类 put 已 push 到内存栈头，此处同步落库）
     */
    protected void insertedCheckpoint(WorkflowCheckpointConfig config, LinkedList<WorkflowCheckpointState> checkpoints, WorkflowCheckpointState checkpoint) throws Exception {
        WorkflowCheckpoint row = new WorkflowCheckpoint();
        row.setThreadId(threadId(config));
        row.setCheckpointId(checkpoint.getId());
        row.setNodeId(checkpoint.getNodeId());
        row.setNextNodeId(checkpoint.getNextNodeId());
        row.setStateJson(serializeCheckpoint(checkpoint));
        row.setIsDeleted(false);
        checkpointMapper.insert(row);
    }

    /**
     * 按 checkpoint_id 就地替换一条（基类 put 已对内存栈 set(index, checkpoint)，
     * 行 id 不变即保留栈位，此处同步落库）
     */
    protected void updatedCheckpoint(WorkflowCheckpointConfig config, LinkedList<WorkflowCheckpointState> checkpoints, WorkflowCheckpointState checkpoint) throws Exception {
        WorkflowCheckpoint row = new WorkflowCheckpoint();
        row.setNodeId(checkpoint.getNodeId());
        row.setNextNodeId(checkpoint.getNextNodeId());
        row.setStateJson(serializeCheckpoint(checkpoint));
        row.setUpdateTime(LocalDateTime.now());
        checkpointMapper.update(row, new LambdaUpdateWrapper<WorkflowCheckpoint>()
                .eq(WorkflowCheckpoint::getThreadId, threadId(config))
                .eq(WorkflowCheckpoint::getCheckpointId, checkpoint.getId())
                .eq(WorkflowCheckpoint::getIsDeleted, false));
    }

    /**
     * 释放/清理该 thread 的 checkpoint（逻辑删除，与仓库 softDelete 惯例一致）
     */
    protected Tag releaseCheckpoints(WorkflowCheckpointConfig config, LinkedList<WorkflowCheckpointState> checkpoints) throws Exception {
        String threadId = threadId(config);
        WorkflowCheckpoint row = new WorkflowCheckpoint();
        row.setIsDeleted(true);
        checkpointMapper.update(row, new LambdaUpdateWrapper<WorkflowCheckpoint>()
                .eq(WorkflowCheckpoint::getThreadId, threadId)
                .eq(WorkflowCheckpoint::getIsDeleted, false));
        return new Tag(threadId, checkpoints);
    }
}
