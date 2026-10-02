package org.ruoyi.workflow.workflow;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import org.ruoyi.common.chat.service.chat.ChatResponseHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.service.chat.IChatModelService;
import org.ruoyi.common.chat.service.chat.IChatService;
import org.ruoyi.common.chat.service.image.IImageGenerationService;
import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.entity.image.ImageContext;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.factory.ImageServiceFactory;
import org.ruoyi.workflow.base.NodeInputConfigTypeHandler;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.enums.WfIODataTypeEnum;
import org.ruoyi.workflow.workflow.data.NodeIOData;
import org.ruoyi.workflow.workflow.data.NodeIODataContent;
import org.ruoyi.workflow.workflow.def.WfNodeParamRef;
import org.springframework.stereotype.Service;

import java.util.*;

import static org.ruoyi.workflow.cosntant.AdiConstant.WorkflowConstant.DEFAULT_OUTPUT_PARAM_NAME;

@Slf4j
@Service
public class WorkflowUtil{

    @Resource
    private ImageServiceFactory imageServiceFactory;

    @Resource
    private IChatModelService chatModelService;

    @Resource
    private IChatService chatService;

    public static String renderTemplate(String template, List<NodeIOData> values) {
        // 🔒 关键修复：如果 template 为 null，直接返回 null 或空字符串
        if (template == null) {
            return null; // 或 return ""; 根据业务需求
        }

        String result = template;

        // 防御 values 为 null
        if (values == null) {
            return result;
        }

        for (NodeIOData next : values) {
            if (next == null || next.getName() == null) {
                continue;
            }

            String name = next.getName();
            NodeIODataContent<?> dataContent = next.getContent();
            if (dataContent == null || dataContent.getValue() == null) {
                // 变量值为 null，替换为空字符串
                result = result.replace("{" + name + "}", "");
                continue;
            }

            String replacement;
            if (dataContent.getType().equals(WfIODataTypeEnum.FILES.getValue())) {
                @SuppressWarnings("unchecked")
                List<String> value = (List<String>) dataContent.getValue();
                replacement = String.join(",", value);
            } else if (dataContent.getType().equals(WfIODataTypeEnum.OPTIONS.getValue())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> value = (Map<String, Object>) dataContent.getValue();
                replacement = value.toString();
            } else {
                replacement = dataContent.getValue().toString();
            }

            result = result.replace("{" + name + "}", replacement);
        }

        return result;
    }

    public void streamingInvokeLLM(WfState wfState, WfNodeState state, WorkflowNode node, String modelName,
                                   String prompt, String nodeMessageTemplate) {
        log.info("workflow_llm operation=STREAM_INVOKE status=STARTED");

        // 获取用户信息和Token以及SSe连接对象（对话接口需要使用）
        Long sessionId = wfState.getSessionId();
        // 定义模型调用对象
        ChatRequest chatRequest = new ChatRequest();
        chatRequest.setSessionId(sessionId);
        chatRequest.setEnableThinking(false);
        chatRequest.setModel(modelName);
        chatRequest.setContent(prompt);

        var streamingGenerator = new WorkflowNodeStream();
        // 先登记再调用：同步回调和异步回调采用同一节点状态，不依赖 completedNodes 时序。
        wfState.getNodeToStreamingGenerator().put(node.getUuid(), streamingGenerator);
        ChatResponseHandler workflowHandler = new ChatResponseHandler() {
            @Override public void onPartialResponse(String token) {
                streamingGenerator.chunk(token);
            }
            @Override public void onCompleteResponse(ChatResponse response) {
                try {
                    String responseTxt = response.getContent().stream().filter(TextBlock.class::isInstance)
                        .map(TextBlock.class::cast).map(TextBlock::getText).collect(java.util.stream.Collectors.joining());
                    logCompletionMetadata(responseTxt);
                    streamingGenerator.complete(() -> {
                        List<NodeIOData> outputs = new ArrayList<>(state.getInputs());
                        outputs.add(NodeIOData.createByText(DEFAULT_OUTPUT_PARAM_NAME, "", responseTxt));
                        state.setOutputs(outputs);
                    });
                } catch (Exception error) { onError(error); }
            }
            @Override public void onError(Throwable error) { streamingGenerator.fail(error); }
        };
        try { chatService.chat(chatRequest, workflowHandler); }
        catch (RuntimeException error) { streamingGenerator.fail(error); throw error; }
    }

    /**
     * 添加用户信息
     *
     * @param node        节点
     * @param userMessage 用户信息
     */
    private void addUserMessage(WorkflowNode node, List<NodeIOData> userMessage, List<Msg> messages) {
        if (CollUtil.isEmpty(userMessage)) {
            return;
        }
        WfNodeInputConfig nodeInputConfig = NodeInputConfigTypeHandler.fillNodeInputConfig(node.getInputConfig());
        List<WfNodeParamRef> refInputs = nodeInputConfig.getRefInputs();
        Set<String> nameSet = CollStreamUtil.toSet(refInputs, WfNodeParamRef::getName);
        // 构建消息列表
        List<Msg> messageList = buildMessageList(userMessage, nameSet);
        // 如果没有找到匹配的消息，尝试使用input字段
        if (CollUtil.isEmpty(messageList)) {
            messageList = buildMessageList(userMessage, Set.of("input"));
        }
        messages.addAll(messageList);
    }

    /**
     * 组装message对象
     *
     * @param role
     * @param value
     * @return
     */
    private Msg getMessage(String role, String value) {
        logMessageMetadata(role, value);
        return Msg.builder().role(MsgRole.USER).textContent(value).build();
    }

    /**
     * 构建消息列表
     */
    private List<Msg> buildMessageList(List<NodeIOData> userMessage, Set<String> nameSet) {
        return userMessage.stream()
            .filter(item -> item != null && item.getName() != null)
            // 兼容默认输出参数的人机交互
            .filter(item -> nameSet.contains(item.getName()))
            .map(item -> getMessage("user", item.getContent().getValue().toString())).toList();
    }

    /**
     * 调用LLM 根据文字生成图片
     */
    public String buildTextToImage(String modelName, String prompt, String size, Integer seed){
        log.info("workflow_image operation=GENERATE status=STARTED");
        // 根据模型名称查询模型信息
        ChatModelVo chatModelVo = chatModelService.selectModelByName(modelName);
        if (chatModelVo == null) {
            throw new IllegalArgumentException("模型不存在: " + modelName);
        }
        // 根据模型名称找到模型实体
        String category = chatModelVo.getProviderCode();
        // 根据 category 获取对应的 IImageGenerationService（不使用计费代理，工作流场景单独计费）
        IImageGenerationService imageService = imageServiceFactory.getOriginalService(category);
        // 构建文生图上下文对象
        ImageContext imageContext = ImageContext.builder()
            .chatModelVo(chatModelVo)
            .prompt(prompt)
            .size(size)
            .seed(seed)
            .build();
        // 调用LLM 生成图片
        return imageService.generateImage(imageContext);
    }

    static void logCompletionMetadata(String responseText) {
        log.info("workflow_llm operation=STREAM_INVOKE status=COMPLETED outputChars={}",
            textLength(responseText));
    }

    static void logMessageMetadata(String role, String content) {
        String roleType = "user".equalsIgnoreCase(role) ? "USER" : "OTHER";
        log.info("workflow_message status=CREATED roleType={} contentChars={}",
            roleType, textLength(content));
    }

    private static int textLength(String value) {
        return value == null ? 0 : value.length();
    }
}
