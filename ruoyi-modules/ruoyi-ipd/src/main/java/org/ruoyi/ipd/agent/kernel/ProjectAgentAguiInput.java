package org.ruoyi.ipd.agent.kernel;

import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.*;
import io.agentscope.core.message.Msg;
import java.util.*;

/** 官方输入的服务器绑定边界；UI metadata 不导入业务权限、身份或 SDK AgentState。 */
public final class ProjectAgentAguiInput {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.ALL,
                com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
            .setVisibility(com.fasterxml.jackson.annotation.PropertyAccessor.FIELD,
                com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY);
    private static final Set<String> RESERVED = Set.of("person", "personid", "user", "userid",
        "project", "projectid", "tenant", "tenantid", "permission", "permissions", "role", "roles",
        "agentstate", "agentstatestore", "statestore", "sessionid", "threadid", "runid",
        "runtimecontext", "securitycontext", "authorization", "confirmresults", "approved");
    private ProjectAgentAguiInput() { }

    public static RunAgentInput withFrontendTools(RunAgentInput input, List<AguiTool> tools) {
        return new RunAgentInput(input.getThreadId(), input.getRunId(), input.getMessages(), tools,
            input.getContext(), input.getState(), input.getForwardedProps(), input.getResume());
    }

    public static String frontendToolsDigest(List<AguiTool> tools) {
        return digest(RunAgentInput.builder().threadId("schema-snapshot").runId("schema-snapshot")
            .tools(tools.stream().sorted(Comparator.comparing(AguiTool::getName)).toList()).build());
    }

    /** 完整协议输入冻结在内存；创建记录只存摘要；已消费中断另存恢复所需的私有规范化输入。 */
    public static RunAgentInput freeze(RunAgentInput input) {
        if (input == null) return null;
        try {
            return JSON.readValue(JSON.writeValueAsBytes(input), RunAgentInput.class);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("invalid AG-UI input", error);
        }
    }

    /** 只编码恢复所需的协议消息、原工具和响应，禁止持久化客户端运行上下文。 */
    public static String encodeRecoveryInput(RunAgentInput input) {
        requireRecoveryInput(input);
        try { return JSON.writeValueAsString(input); }
        catch(java.io.IOException invalid) { throw new IllegalArgumentException("invalid recovery input",invalid); }
    }
    public static RunAgentInput decodeRecoveryInput(String json) {
        try { RunAgentInput input=JSON.readValue(json,RunAgentInput.class);requireRecoveryInput(input);return input; }
        catch(java.io.IOException invalid) { throw new IllegalArgumentException("invalid recovery input",invalid); }
    }
    private static void requireRecoveryInput(RunAgentInput input) {
        Objects.requireNonNull(input);requireId(input.getThreadId());requireId(input.getRunId());
        if(!input.getContext().isEmpty() || !input.getState().isEmpty() || !input.getForwardedProps().isEmpty())
            throw new IllegalArgumentException("recovery input cannot persist client runtime context");
    }

    /** JSON 对象键排序，数组保持语义顺序；摘要覆盖全部官方输入字段。 */
    public static String digest(RunAgentInput input) {
        if (input == null) return null;
        try {
            byte[] bytes = JSON.writeValueAsBytes(canonical(JSON.valueToTree(input)));
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalArgumentException("invalid AG-UI input", error);
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            var out = JSON.createObjectNode();
            var keys = new java.util.TreeSet<String>();
            node.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) out.set(key, canonical(node.get(key)));
            return out;
        }
        if (node.isArray()) {
            var out = JSON.createArrayNode();
            node.forEach(value -> out.add(canonical(value)));
            return out;
        }
        return node;
    }

    /** 调用方传入已鉴权的 ID 及本次运行准许的前端工具定义，不能传市场编号或客户端 schema。 */
    public static RunAgentInput bind(RunAgentInput client, String serverThreadId, String serverRunId,
            Map<String, AguiTool> authorizedFrontendTools) {
        Objects.requireNonNull(client, "AG-UI input required");
        requireId(serverThreadId);
        requireId(serverRunId);
        Map<String, AguiTool> catalog = authorizedFrontendTools == null ? Map.of() : authorizedFrontendTools;
        List<AguiTool> tools = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (AguiTool requested : list(client.getTools())) {
            if (requested == null || requested.getName() == null || !seen.add(requested.getName()))
                throw new IllegalArgumentException("invalid or duplicate frontend tool");
            AguiTool canonical = catalog.get(requested.getName());
            if (canonical == null || !requested.getName().equals(canonical.getName()))
                throw new IllegalArgumentException("frontend tool is not authorized for this run");
            tools.add(canonical);
        }
        // 不复制客户端 thread/run 身份；只保留官方输入数据结构，不执行 state 导入。
        return RunAgentInput.builder().threadId(serverThreadId).runId(serverRunId)
            .messages(List.copyOf(list(client.getMessages()))).tools(List.copyOf(tools))
            .context(List.copyOf(list(client.getContext())))
            .state(metadata(client.getState())).forwardedProps(metadata(client.getForwardedProps()))
            .resume(List.copyOf(list(client.getResume()))).build();
    }

    /** 原始 interrupt 只能来自经 IPD 审批、归属和完整覆盖校验的服务器持久记录。 */
    public static List<Msg> messages(RunAgentInput bound, Map<String, AguiEvent.Interrupt> serverPendingInterrupts) {
        Objects.requireNonNull(bound, "bound AG-UI input required");
        Map<String, AguiEvent.Interrupt> pending = serverPendingInterrupts == null ? Map.of() : serverPendingInterrupts;
        for (AguiResume resume : list(bound.getResume())) {
            if (resume == null || !pending.containsKey(resume.getInterruptId()))
                throw new IllegalArgumentException("resume requires original server interrupt");
        }
        // 官方 converter 保留消息顺序、角色、多模态块、tool-call 参数与工具结果及 resume 语义。
        return List.copyOf(new AguiMessageConverter().toMsgList(bound, pending));
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("server AG-UI identity required");
    }
    private static <T> List<T> list(List<T> value) { return value == null ? List.of() : value; }
    private static Map<String,Object> metadata(Map<String,Object> value) {
        if (value == null) return Map.of();
        return copyMap(value);
    }
    private static Map<String,Object> copyMap(Map<?,?> value) {
        Map<String,Object> copy = new LinkedHashMap<>();
        for (var entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("invalid UI metadata key");
            String normalized = key.replace("_", "").replace("-", "").replace(".", "").toLowerCase(Locale.ROOT);
            if (RESERVED.contains(normalized) || "ipd.server.child.invocation".equals(key) || "ipd.server.agui.resume.intent".equals(key) || "ipd.server.child.completion".equals(key))
                throw new IllegalArgumentException("UI metadata cannot override server governance");
            copy.put(key, copyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
    private static Object copyValue(Object value) {
        if (value instanceof Map<?,?> map) return copyMap(map);
        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>();
            for (Object item : list) copied.add(copyValue(item));
            return Collections.unmodifiableList(copied);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw new IllegalArgumentException("UI metadata must contain JSON values");
    }
}
