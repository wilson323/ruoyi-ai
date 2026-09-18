# Surefire Tag 过滤规则

## 是什么
Maven Surefire 配 `<groups>${profiles.active}</groups>`（键名 `surefire.groups`，行号会漂），默认跑 `@Tag("dev")`。

## 为什么踩坑
**假绿陷阱**：默认 dev profile 下没有 `@Tag("dev")` 的测试类被**静默跳过**——新测试不加 tag，"测试全绿"毫无意义。这是项目最严重的假绿根源之一（实测 85 跑 71 Error 中 ≥66 为假红/假绿）。

## 怎么识别
- 跑 `mvn test -pl <module> -X 2>&1 | grep -E "(Tests run|excluded|filter)"`：被跳过的测试会出现在"excluded"行
- 新测试类跑完后没在"Tests run"列表 → 八成漏了 `@Tag("dev")`
- 跑 `bash .claude/skills/gen-test/scripts/mock-drift-check.sh` 自动扫所有 `*.test.java` 缺 `@Tag("dev")` 的

## 怎么修
- 每个测试类加 `@Tag("dev")`（JUnit 5 类级注解）
- 集成测试（启动 Spring 上下文）用 `@Tag("integration")` + `@Tag("dev")` 双注解，避免误跑
- `@Tag("exclude")` 显式排除（性能 / 手动测试）

## 验证
- 故意写一个测试类不标 `@Tag("dev")`，跑 `mvn test`，应在输出里看到"excluded"且 Surefire 报告 0 测试
- 加上 `@Tag("dev")` 后应被运行

## 来源
- AGENTS.md §构建/测试：键名 `surefire.groups`（**引用配置用键名，别用行号**）
- 项目记忆：`IPD 后端全量测试五类假红根源与修复模式`