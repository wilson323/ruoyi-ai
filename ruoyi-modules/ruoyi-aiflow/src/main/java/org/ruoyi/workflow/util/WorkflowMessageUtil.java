package org.ruoyi.workflow.util;

import org.ruoyi.common.chat.domain.dto.request.ChatRequest;
import org.ruoyi.common.chat.domain.vo.chat.ChatModelVo;
import org.ruoyi.common.chat.enums.RoleType;
import lombok.extern.slf4j.Slf4j;
import org.ruoyi.common.chat.entity.User;
import org.ruoyi.common.core.service.ConfigService;
import org.ruoyi.common.core.utils.SpringUtils;
import org.ruoyi.common.core.utils.StringUtils;
import org.ruoyi.common.sse.core.SseEmitterHelper;
import org.ruoyi.workflow.cosntant.AdiConstant;
import org.ruoyi.workflow.cosntant.RedisKeyConstant;
import org.ruoyi.workflow.entity.WorkflowNode;
import org.ruoyi.workflow.workflow.WfState;
import org.ruoyi.workflow.workflow.WorkflowUtil;
import org.ruoyi.workflow.workflow.node.enmus.NodeMessageTemplateEnum;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.text.MessageFormat;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 工作流消息工具类
 *
 * <p>R32：原 {@code org.ruoyi.workflow.helper.SSEEmitterHelper} 手写 SSE 生命周期已并入
 * 公共模块 {@link SseEmitterHelper}（completed 状态机 / per-emitter 发送串行化 D4 /
 * 收尾吞异常 D14）；本类仅保留工作流业务胶水——USER_ASKING 互斥键（Redis）与
 * [START]/[DONE]/[ERROR] 业务事件帧。事件协议与前端契约逐帧原样保留。
 *
 * @author Zengxb
 * @date 2026-02-26
 */
@Slf4j
public class WorkflowMessageUtil {


    /**
     * 通知并存储消息(对话使用)
     * @param wfState 工作流实例状态
     * @param sseEmitter SSE连接对象
     * @param node 工作流节点
     * @param message 消息
     */
    public static void notifyAndStoreMessage(WfState wfState, SseEmitter sseEmitter, WorkflowNode node, String message){
        saveWorkflowMessage(wfState, message);
        sendEmitterMessage(sseEmitter, node, message);
    }


    /**
     * 获取节点的响应模板 <br/>
     * 优先读取 sys_config 配置(可在系统管理-配置管理中自定义),
     * 未配置时回退到枚举内置默认模板, 模板仅为展示文案, 缺失不应中断工作流执行
     * @param configKey 参数Key
     * @return 返回模板样式
     */
    public static String getNodeMessageTemplate(String configKey){
        ConfigService configService = SpringUtil.getBean(ConfigService.class);
        String configValue = configService.getConfigValue(configKey);
        if (StringUtils.isNotEmpty(configValue)) {
            return configValue;
        }
        log.warn("sys_config 未配置节点响应模板 [{}], 已回退使用内置默认模板", configKey);
        return NodeMessageTemplateEnum.getDefaultTemplate(configKey);
    }

    /**
     * 保存工作流消息公共方法（对话使用）
     * @param wfState 工作流实例状态
     * @param message 消息
     */
    public static void saveWorkflowMessage(WfState wfState, String message) {
        // Input validation may fail before WfState is created. Do not mask the
        // original error and prevent WorkflowEngine from completing the SSE.
        if (wfState == null) {
            return;
        }
        Long sessionId = wfState.getSessionId();
        Long userId = wfState.getUserId();

        if (sessionId != null && userId != null) {
            ChatRequest chatRequest = new ChatRequest();
            chatRequest.setSessionId(sessionId);
            WorkflowUtil workflowUtil = SpringUtils.getBean(WorkflowUtil.class);
            // todo 保存消息
            //workflowUtil.saveChatMessage(chatRequest, userId, message, RoleType.WORKFLOW.getName(), new ChatModelVo());
        }
    }

    /**
     * 发送SSE消息
     * @param sseEmitter 连接对象
     * @param node 工作流定义
     * @param message 消息
     */
    public static void sendEmitterMessage(SseEmitter sseEmitter, WorkflowNode node, String message) {
        String nodeUuid = node.getUuid();
        SseEmitterHelper.parseAndSendPartialMsg(sseEmitter,"[NODE_CHUNK_" + nodeUuid + "]", message);
    }

    // ======================== 工作流 SSE 会话收尾（USER_ASKING 互斥键 + 业务事件帧） ========================

    /**
     * 提交前检查：同一用户仍在回复中则推 [ERROR] 帧并收尾
     * @return true=可继续执行；false=已在回复中（已推错误帧并收尾）
     */
    public static boolean checkOrComplete(User user, SseEmitter sseEmitter) {
        //Check: If still waiting response
        String askingKey = MessageFormat.format(RedisKeyConstant.USER_ASKING, user.getId());
        String askingVal = stringRedisTemplate().opsForValue().get(askingKey);
        if (StringUtils.isNotBlank(askingVal)) {
            sendErrorAndComplete(user.getId(), sseEmitter, "正在回复中...");
            return false;
        }
        return true;
    }

    /**
     * 开启 SSE 推送：置 USER_ASKING 互斥键（15s TTL）并发 [START] 帧；
     * 发送失败则错误收尾并清除互斥键（不外抛，单错误路径）
     */
    public static void startSse(User user, SseEmitter sseEmitter, String data) {

        String askingKey = MessageFormat.format(RedisKeyConstant.USER_ASKING, user.getId());
        stringRedisTemplate().opsForValue().set(askingKey, "1", 15, TimeUnit.SECONDS);

        if (!SseEmitterHelper.sendEvent(sseEmitter, AdiConstant.SSEEventName.START, data)) {
            SseEmitterHelper.completeWithError(sseEmitter, new java.io.IOException("startSse send failed"));
            delSseRequesting(user.getId());
        }
    }

    /**
     * 正常收尾：推 [DONE] 帧、清除互斥键并 complete（D14：全程不外抛，complete 异常转日志）
     */
    public static void sendComplete(long userId, SseEmitter sseEmitter, String msg) {
        if (SseEmitterHelper.isCompleted(sseEmitter)) {
            log.warn("sseEmitter already completed,userId:{}", userId);
            delSseRequesting(userId);
            return;
        }
        SseEmitterHelper.sendPartial(sseEmitter, AdiConstant.SSEEventName.DONE, msg);
        delSseRequesting(userId);
        SseEmitterHelper.complete(sseEmitter);
    }

    /**
     * 异常收尾：推 [ERROR] 帧、清除互斥键并 complete（D14 同上，不外抛）
     */
    public static void sendErrorAndComplete(long userId, SseEmitter sseEmitter, String errorMsg) {
        if (SseEmitterHelper.isCompleted(sseEmitter)) {
            log.warn("sseEmitter already completed,ignore error:{}", errorMsg);
            delSseRequesting(userId);
            return;
        }
        SseEmitterHelper.sendPartial(sseEmitter, AdiConstant.SSEEventName.ERROR, Objects.toString(errorMsg, ""));
        delSseRequesting(userId);
        SseEmitterHelper.complete(sseEmitter);
    }

    private static void delSseRequesting(long userId) {
        String askingKey = MessageFormat.format(RedisKeyConstant.USER_ASKING, userId);
        stringRedisTemplate().delete(askingKey);
    }

    private static StringRedisTemplate stringRedisTemplate() {
        return SpringUtil.getBean(StringRedisTemplate.class);
    }

}
