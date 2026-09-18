package org.ruoyi.example.gen-test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * 标准集成测试模板
 *
 * 适用场景：需要真实 Spring 上下文 + 真 MySQL / Redis / 向量库
 * 关键 funs: @SpringBootTest + @Tag("integration") + @Tag("dev")
 * 引用参考: references/known-dead-ends.md（死路 3：库模块起不了容器，必须放 ruoyi-admin）
 *
 * 必须放在 ruoyi-admin 模块下（有 @SpringBootApplication），
 * 库模块（ruoyi-ipd / ruoyi-system 等）没有 main class，复制后会报找不到 @SpringBootConfiguration。
 */
@Tag("integration")
@Tag("dev")
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "demo.enabled=false",
    "spring.profiles.active=dev"
})
class ExampleIntegrationTest {
    // 需要真实 MySQL / Redis / 向量库 — 跑前确认 docker compose 已起
    @Test
    void context_loads() {
        // 仅验证 Spring 上下文能起来，业务测试另起
    }
}