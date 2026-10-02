package org.ruoyi.workflow.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.ruoyi.common.chat.entity.BaseEntity;

import java.io.Serial;

/**
 * 工作流应用检查点。现有 thread_id 绑定 runtime UUID，按行插入顺序取最新。
 * state_json 为带版本的业务 ObjectStream 载荷；历史二进制协议仅保留只读兼容解码。
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
