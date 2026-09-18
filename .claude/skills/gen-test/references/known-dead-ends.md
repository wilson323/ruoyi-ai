# 已知假路 / 不可跑设设设登记

## 是什么
项目里被反复踩过、最终确认"不可做"的死路。遇到时直接绕开，不要重试。

## 死路 1：在测试里 `Thread.sleep` 等异步
**症状**：偶发 flake，CI 概率失败。
**为什么死**：异步调度时机不可控。
**绕开**：用 Awaitility `await().atMost(5, SECONDS).untilAsserted(...)` 或 `Mockito.verify` 回调。

## 死路 2：`@SpringBootTest` 测纯 Service
**症状**：测试启动慢（5-30s），但根本没用到 Spring 上下文。
**为什么死**：纯 Service 单测不需要 Spring 容器，MockitoExtension 即可。
**绕开**：纯 Service 用 `@ExtendWith(MockitoExtension.class)` + `@InjectMocks`。

## 死路 3：复制 `RuoYiAIApplication` 启动做集成测试
**症状**：`@SpringBootTest` 找不到 `@SpringBootConfiguration`。
**为什么死**：库模块（ruoyi-ipd / ruoyi-system 等）没有 main class。
**绕开**：集成测试放 `ruoyi-admin` 模块（已有 `@SpringBootApplication`）。

## 死路 4：mock builder 直造"未办态"
**症状**：单测全绿，但真活卡恒空。
**为什么死**：mock 造了真库写入路径不可能产生的数据组合。
**绕开**：未办态优先走 `service.sign()` 真实触发；纯 mock 用例须在 `@DisplayName` 标注"未覆盖 DDL 合法性"。

## 死路 5：行号型断言
**症状**：引用"yml:472"隔天就找不到。
**为什么死**：多会话共工工作树，兄弟在途未提交编辑就会推号（`application.yml` 的 `demo:` 段从 :396 漂到 :400，20 分钟级；`tenant.excludes` 从 :148 漂到 :198）。
**绕开**：用键名 + 当时的值，不写行号。

## 死路 6：用 `mvn -am clean test` 跨模块构建
**症状**：制造大面积 `NoClassDefFoundError` 假红。
**为什么死**：兄弟会话共工 `target/` 目录。
**绕开**：`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test`，单模块 + 不带 `-am` 不带 `clean`。

## 死路 7：写到 `application-prod.yml` 或 `.env*`
**症状**：被 `.claude/helpers/sensitive-field-guard.cjs` 拦截（exit 2）。
**为什么死**：项目级 hook 强制禁止。
**绕开**：本地配置放 `.codex/ipd-dev/config/application-ipd-local.yml`（已 gitignore）。

## 死路 8：把断言改成"现状"让用例转绿
**症状**：单测绿了，但契约缺口未改。
**为什么死**：把契约改成现实（=改期望类型），而非修代码。
**绕开**：永远让"期望"对应"契约"，让"实际"对应"代码"。绿了 = 契约被满足。

## 验证
- 跑 `bash scripts/verify.sh` 应能识别上述死路（已知 dead-end 列表在脚本里维护）
- 自证能红：故意写一个 Thread.sleep 测试，脚本应能扫到

## 来源
- AGENTS.md §构建/测试（5 类病根框架 + 假红陷阱 + 多会话共工纪律）
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`
- 项目记忆：`spring-boot repackage 复用旧 fat jar 但日志仍打 Replacing（BUILD SUCCESS 假象）`
- 项目记忆：`lint 门禁脚本字面量匹配导致的误报陷阱`