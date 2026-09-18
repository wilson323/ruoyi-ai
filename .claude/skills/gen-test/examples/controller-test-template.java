package org.ruoyi.example.gen-test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ruoyi.system.controller.ExampleController;
import org.ruoyi.system.service.IExampleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 标准 Controller 单测模板
 *
 * 适用场景：HTTP 层契约测试
 * 关键 funs: WebMvcTest + MockBean + MockMvc
 * 引用参考: references/tag-filtering-rules.md, references/known-dead-ends.md（死路 4：未办态需 service.sign() 真实触发）
 *
 * 复制本文件到目标 Controller 测试目录即可使用。
 */
@Tag("dev")
@WebMvcTest(ExampleController.class)
class ExampleControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private IExampleService service;

    @Test
    @DisplayName("list 接口返回 code=200")
    void list_returnsOk() throws Exception {
        when(service.list(any(), any())).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/example/list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }
}