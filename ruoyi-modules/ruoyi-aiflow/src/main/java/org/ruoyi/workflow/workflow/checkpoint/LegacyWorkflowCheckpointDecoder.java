package org.ruoyi.workflow.workflow.checkpoint;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 只读兼容旧 t_workflow_checkpoint 二进制协议；不包含旧图执行器。 */
final class LegacyWorkflowCheckpointDecoder {
    private static final int MAX_ENTRIES = 100000;
    static WorkflowCheckpointState read(ObjectInputStream in) throws IOException, ClassNotFoundException {
        String id = text(in, false), node = text(in, true), next = text(in, true);
        return WorkflowCheckpointState.builder().id(id).nodeId(node).nextNodeId(next).state(map(in, 0)).build();
    }
    private static String text(ObjectInput in, boolean nullable) throws IOException {
        int size = in.readInt();
        if (nullable && size == -1) return null;
        if (size < 0 || size > 16 * 1024 * 1024) throw new IOException("旧检查点文本长度错误");
        byte[] value = new byte[size]; in.readFully(value); return new String(value, StandardCharsets.UTF_8);
    }
    private static int count(ObjectInput in) throws IOException {
        int size = in.readInt(); if (size < 0 || size > MAX_ENTRIES) throw new IOException("旧检查点集合长度错误"); return size;
    }
    private static Map<String,Object> map(ObjectInput in, int depth) throws IOException, ClassNotFoundException {
        if (depth > 64) throw new IOException("旧检查点嵌套过深");
        int size = count(in); Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<size;i++) { String key=text(in,false); if(result.containsKey(key)) throw new IOException("旧检查点重复状态键"); result.put(key, nullable(in,depth+1)); }
        return result;
    }
    private static Object nullable(ObjectInput in, int depth) throws IOException, ClassNotFoundException {
        byte mark=in.readByte(); if(mark==0)return null; if(mark!=1)throw new IOException("旧检查点空值标记错误");
        if(depth>64)throw new IOException("旧检查点嵌套过深");
        Object value=in.readObject();
        if (!(value instanceof Class<?> type)) return value;
        if (Map.class.isAssignableFrom(type)) return map(in,depth+1);
        if (List.class.isAssignableFrom(type) || Set.class.isAssignableFrom(type)) {
            int size=count(in); Collection<Object> result=Set.class.isAssignableFrom(type)?new LinkedHashSet<>():new ArrayList<>();
            for(int i=0;i<size;i++)result.add(nullable(in,depth+1)); return result;
        }
        throw new IOException("旧检查点不支持的集合标记");
    }
}
