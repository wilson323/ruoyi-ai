package org.ruoyi.ipd.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.ruoyi.common.sse.core.SseEmitterManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IpdSseController 认证失败响应形态回归（2026-09-11）。
 *
 * <p>背景：认证失败曾 return null → Spring 写 200 + 空体（无 Content-Type），
 * 浏览器 EventSource 按默认 text/plain 解析，报
 * 「MIME type ("text/plain") is not "text/event-stream"」并盲目重连；
 * 修复后认证失败必须返回语义化 401。
 *
 * <p>只测 NO_TOKEN 分支（不触达 sa-token dao，standalone MockMvc 即可）；
 * TOKEN_INVALID_OR_EXPIRED 分支与 NO_TOKEN 共用同一映射逻辑。
 */
@Tag("dev")
class IpdSseControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // SseEmitterManager 无参构造依赖 Spring 上下文（SpringUtil），standalone 下必须 mock；
        // 本测试只走 NO_TOKEN 分支，manager 永不被调用。
        mockMvc = MockMvcBuilders
            .standaloneSetup(new IpdSseController(mock(SseEmitterManager.class)))
            .build();
    }

    @Test
    void connectWithoutTokenRejectsWith401NotSilent200() throws Exception {
        mockMvc.perform(get("/api/v1/resource/sse").param("clientid", "ut-no-token"))
            .andExpect(status().isUnauthorized());
    }
}
