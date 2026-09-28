package org.ruoyi.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.chat.entity.BaseEntity;

import java.io.Serial;

/**
 * 工作流 checkpoint（langgraph4j 断点落库） | Workflow checkpoint
 * <p>
 * 一行 = 一个 {@code org.bsc.langgraph4j.checkpoint.Checkpoint}，按 thread_id（= t_workflow_runtime.uuid）成栈：
 * 基类 AbstractCheckpointSaver 以 push 头插维护「最新在头」，本表以 id 升序（插入序）还原该栈序。
 * state_json 存 {@code CheckpointSerializer}（ObjectStream 二进制）的 Base64 载荷，含 state Map（NodeIOData 等业务对象）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "t_workflow_checkpoint", autoResultMap = true)
@Schema(title = "工作流 checkpoint | Workflow checkpoint")
public class WorkflowCheckpoint extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableField("thread_id")
    private String threadId;

    @TableField("checkpoint_id")
    private String checkpointId;

    @TableField("node_id")
    private String nodeId;

    @TableField("next_node_id")
    private String nextNodeId;

    @TableField("state_json")
    private String stateJson;
}
