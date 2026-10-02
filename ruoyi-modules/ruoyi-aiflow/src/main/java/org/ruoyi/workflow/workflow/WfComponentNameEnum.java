package org.ruoyi.workflow.workflow;

import lombok.Getter;

import java.util.Arrays;

@Getter
public enum WfComponentNameEnum {
    START("Start"),

    END("End"),

    LLM_ANSWER("Answer"),

    DALLE3("Dalle3"),

    TONGYI_WANX("Tongyiwanx"),

    FAQ_EXTRACTOR("FaqExtractor"),

    KNOWLEDGE_RETRIEVER("KnowledgeRetrieval"),

    SWITCHER("Switcher"),

    GOOGLE_SEARCH("Google"),

    MAIL_SEND("MailSend"),

    HTTP_REQUEST("HttpRequest");

    private final String name;

    WfComponentNameEnum(String name) {
        this.name = name;
    }

    /** 与恢复闸门共用的副作用分类，未知效果不得自动重放。 */
    public boolean hasSideEffect() {
        return this == HTTP_REQUEST || this == MAIL_SEND || this == DALLE3 || this == TONGYI_WANX;
    }

    public static WfComponentNameEnum getByName(String name) {
        return Arrays.stream(WfComponentNameEnum.values()).filter(item -> item.name.equals(name)).findFirst().orElse(null);
    }
}
