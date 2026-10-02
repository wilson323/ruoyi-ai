package org.ruoyi.service.chat.impl.provider;

import org.ruoyi.enums.ChatModeType;
import org.ruoyi.service.chat.AbstractChatService;
import org.springframework.stereotype.Service;

/** 通过共享 AgentScope 原生模型注册表创建模型。 */
@Service
public class ZhiPuChatServiceImpl implements AbstractChatService {
    @Override
    public String getProviderName() { return ChatModeType.ZHI_PU.getCode(); }
}
