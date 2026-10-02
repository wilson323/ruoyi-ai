package org.ruoyi.common.chat.domain.dto.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class ChatRequestJsonCompatibilityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void historicalMessageObjectsRemainOpaqueListWithCountAndJsonShape() throws Exception {
        String json = """
            {"content":"问题","contextMessages":[
              {"type":"USER","contents":[{"type":"TEXT","text":"历史问题"}]},
              {"type":"AI","text":"历史回答","toolExecutionRequests":[]}
            ]}
            """;
        ChatRequest request = mapper.readValue(json, ChatRequest.class);
        assertThat(request.getContextMessages()).hasSize(2);
        assertThat(request.getContextMessages().get(0)).isInstanceOf(Map.class);
        assertThat(mapper.readTree(mapper.writeValueAsString(request)).get("contextMessages"))
            .isEqualTo(mapper.readTree(json).get("contextMessages"));
    }

    @Test
    void omittedContextKeepsExistingNullDefault() throws Exception {
        ChatRequest request = mapper.readValue("{\"content\":\"问题\"}", ChatRequest.class);
        assertThat(request.getContextMessages()).isNull();
    }
}
