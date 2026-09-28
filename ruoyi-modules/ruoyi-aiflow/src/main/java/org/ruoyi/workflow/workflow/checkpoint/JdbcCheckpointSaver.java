package org.ruoyi.workflow.workflow.checkpoint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.serializer.std.CheckpointSerializer;
import org.bsc.langgraph4j.serializer.std.ObjectStreamStateSerializer;
import org.bsc.langgraph4j.state.AgentState;
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

/**
 * langgraph4j checkpoint 的 MySQL 落库实现（补遗 §5-5 第 5 项：checkpoint 落库 + 断点续跑）。
 * <p>
 * 自实现官方扩展点 {@link AbstractCheckpointSaver} 的 4 个 protected 抽象方法，
 * 基类已 final 实现 list/get/put/release 与锁；零新依赖。
 * <p>
 * 落库表 t_workflow_checkpoint，一行一个 {@link Checkpoint}：
 * <ul>
 *   <li>栈序语义：基类 put 以 push 头插、get 以 peek 取最新，本实现按 id 升序（插入序）读全量后逐条 push，
 *       还原「最新在头」的 LinkedList；updatedCheckpoint 按 checkpoint_id 就地替换（保留行 id 即保留栈位）。</li>
 *   <li>state 序列化：复用官方 {@link CheckpointSerializer}（ObjectStream 二进制，NodeIOData 等业务对象
 *       均 Serializable，与引擎既有 cloneState 同机制）→ Base64 存 state_json。</li>
 *   <li>thread_id 缺省落 {@code $default}（{@code BaseCheckpointSaver.THREAD_ID_DEFAULT}）；
 *       引擎侧必须传 runtime 实例 uuid 做 thread_id，否则不同实例 checkpoint 串台。</li>
 *   <li>多租户：t_workflow_checkpoint 已登记 tenant.excludes（租户共享）——断点恢复场景无租户上下文，
 *       且本扩展点 API 只有 RunnableConfig 拿不到租户。</li>
 * </ul>
 */
@Component
public class JdbcCheckpointSaver extends AbstractCheckpointSaver {

    /**
     * 官方 Checkpoint 序列化器：state Map 里的业务对象走 Java 序列化（writeObject），
     * 与 WorkflowGraphBuilder 的 ObjectStreamStateSerializer&lt;WfNodeState&gt; 同机制，往返保真。
     */
    static final CheckpointSerializer SERIALIZER = new CheckpointSerializer(new ObjectStreamStateSerializer<>(AgentState::new));

    private final WorkflowCheckpointMapper checkpointMapper;

    public JdbcCheckpointSaver(WorkflowCheckpointMapper checkpointMapper) {
        this.checkpointMapper = checkpointMapper;
    }

    /**
     * Checkpoint → state_json 载荷（Base64 of CheckpointSerializer 二进制流）
     */
    static String serializeCheckpoint(Checkpoint checkpoint) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        try (ObjectOutputStream out = new ObjectOutputStream(buffer)) {
            SERIALIZER.write(checkpoint, out);
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }

    /**
     * state_json 载荷 → Checkpoint（含 state Map 反序列化往返）
     */
    static Checkpoint deserializeCheckpoint(String stateJson) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(stateJson);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return SERIALIZER.read(in);
        }
    }

    /**
     * 按 thread_id 取该线程全部 checkpoint，栈序（最新在头，peek() 是最新）。
     * 行按 id 升序（插入序）读出后逐条 push 头插，最后插入的（最新）落在栈顶。
     */
    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) throws Exception {
        List<WorkflowCheckpoint> rows = checkpointMapper.selectList(new LambdaQueryWrapper<WorkflowCheckpoint>()
                .eq(WorkflowCheckpoint::getThreadId, threadId(config))
                .eq(WorkflowCheckpoint::getIsDeleted, false)
                .orderByAsc(WorkflowCheckpoint::getId));
        LinkedList<Checkpoint> checkpoints = new LinkedList<>();
        for (WorkflowCheckpoint row : rows) {
            checkpoints.push(deserializeCheckpoint(row.getStateJson()));
        }
        return checkpoints;
    }

    /**
     * 新增一条 checkpoint（基类 put 已 push 到内存栈头，此处同步落库）
     */
    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
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
    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
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
    @Override
    protected Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints) throws Exception {
        String threadId = threadId(config);
        WorkflowCheckpoint row = new WorkflowCheckpoint();
        row.setIsDeleted(true);
        checkpointMapper.update(row, new LambdaUpdateWrapper<WorkflowCheckpoint>()
                .eq(WorkflowCheckpoint::getThreadId, threadId)
                .eq(WorkflowCheckpoint::getIsDeleted, false));
        return new Tag(threadId, checkpoints);
    }
}
