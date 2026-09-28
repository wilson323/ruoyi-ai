package org.ruoyi.workflow.workflow.checkpoint;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.ruoyi.workflow.entity.WorkflowCheckpoint;
import org.ruoyi.workflow.mapper.WorkflowCheckpointMapper;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 内存行存储 fake mapper（mock mapper 范式）：只模拟 t_workflow_checkpoint 真库可产生的行形态与
 * save 点实际调用的 4 个 mapper 方法（insert / selectList / update(entity,wrapper)）。
 * <p>
 * 行形态合法性（对照 docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md 三条硬规约）：
 * NOT NULL 列（id/thread_id/checkpoint_id/node_id/next_node_id/create_time/update_time/is_deleted/tenant_id）
 * 全部显式赋值，id 自增、时间戳落值、tenant_id 默认 000000——与 ipd_dev 真库 INSERT 可产生的行一致；
 * is_deleted 恒非 null（真库 NOT NULL DEFAULT 0）。state_json 由生产序列化路径写入，非手造。
 * <p>
 * 未覆盖：MyBatis-Plus wrapper 生成的 SQL 过滤与 MySQL 执行语义（等值/排序由本 fake 模拟，列名解析
 * 依赖 TableInfoHelper.initTableInfo 预热，见各测试 @BeforeAll）。
 */
final class FakeCheckpointDb {

    private long seq = 0;
    private final List<WorkflowCheckpoint> rows = new ArrayList<>();

    WorkflowCheckpointMapper mapper() {
        return (WorkflowCheckpointMapper) Proxy.newProxyInstance(
                WorkflowCheckpointMapper.class.getClassLoader(),
                new Class<?>[]{WorkflowCheckpointMapper.class},
                this::dispatch);
    }

    List<WorkflowCheckpoint> rows() {
        return rows;
    }

    private Object dispatch(Object proxy, java.lang.reflect.Method method, Object[] args) {
        switch (method.getName()) {
            case "insert":
                return insert((WorkflowCheckpoint) args[0]);
            case "selectList":
                return selectList((Wrapper<WorkflowCheckpoint>) args[0]);
            case "update":
                return update((WorkflowCheckpoint) args[0], (Wrapper<WorkflowCheckpoint>) args[1]);
            case "toString":
                return "FakeCheckpointMapper";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                throw new UnsupportedOperationException("FakeCheckpointMapper 未实现: " + method.getName());
        }
    }

    private int insert(WorkflowCheckpoint entity) {
        WorkflowCheckpoint row = copyOf(entity);
        row.setId(++seq);
        row.setCreateTime(LocalDateTime.now());
        row.setUpdateTime(LocalDateTime.now());
        if (null == row.getIsDeleted()) {
            row.setIsDeleted(false);
        }
        rows.add(row);
        return 1;
    }

    private List<WorkflowCheckpoint> selectList(Wrapper<WorkflowCheckpoint> wrapper) {
        List<String> strParams = stringParams(wrapper);
        return rows.stream()
                .filter(row -> !Boolean.TRUE.equals(row.getIsDeleted()))
                .filter(row -> strParams.contains(row.getThreadId()))
                .sorted(Comparator.comparing(WorkflowCheckpoint::getId))
                .map(FakeCheckpointDb::copyOf)
                .collect(Collectors.toList());
    }

    /**
     * 模拟 updateStrategy=NOT_NULL：仅实体非 null 字段进 SET；WHERE 等值条件取 wrapper 字符串参数。
     * updatedCheckpoint（实体带 stateJson）按 (threadId, checkpointId) 双等值匹配，两参数对称匹配消歧；
     * releaseCheckpoints（实体仅 isDeleted=true）按 threadId 等值匹配。
     */
    private int update(WorkflowCheckpoint entity, Wrapper<WorkflowCheckpoint> wrapper) {
        List<String> strParams = stringParams(wrapper);
        int affected = 0;
        for (WorkflowCheckpoint row : rows) {
            if (Boolean.TRUE.equals(row.getIsDeleted())) {
                continue;
            }
            boolean replaceCase = null != entity.getStateJson();
            boolean match = replaceCase
                    ? strParams.contains(row.getThreadId()) && strParams.contains(row.getCheckpointId())
                    : strParams.contains(row.getThreadId());
            if (!match) {
                continue;
            }
            if (replaceCase) {
                row.setStateJson(entity.getStateJson());
                row.setNodeId(entity.getNodeId());
                row.setNextNodeId(entity.getNextNodeId());
            }
            if (null != entity.getIsDeleted()) {
                row.setIsDeleted(entity.getIsDeleted());
            }
            row.setUpdateTime(LocalDateTime.now());
            affected++;
        }
        return affected;
    }

    /**
     * 注意：MyBatis-Plus 3.5.x 的占位符参数是惰性物化的——paramNameValuePairs 的值要等
     * getSqlSegment() 渲染 SQL 片段后才写入，取参数前必须先触发一次 getSqlSegment()。
     */
    private static List<String> stringParams(Wrapper<WorkflowCheckpoint> wrapper) {
        wrapper.getSqlSegment();
        return ((AbstractWrapper<?, ?, ?>) wrapper).getParamNameValuePairs().values().stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toList());
    }

    private static WorkflowCheckpoint copyOf(WorkflowCheckpoint src) {
        WorkflowCheckpoint row = new WorkflowCheckpoint();
        row.setId(src.getId());
        row.setThreadId(src.getThreadId());
        row.setCheckpointId(src.getCheckpointId());
        row.setNodeId(src.getNodeId());
        row.setNextNodeId(src.getNextNodeId());
        row.setStateJson(src.getStateJson());
        row.setCreateTime(src.getCreateTime());
        row.setUpdateTime(src.getUpdateTime());
        row.setIsDeleted(src.getIsDeleted());
        return row;
    }
}
