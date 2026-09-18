---
name: gen-test
description: 为 ruoyi-ai 项目按 `@Tag("dev")` Surefire 过滤规范生成 Service/Controller 单测。Agent 接到"补单测"任务时先扫本入口，再按踩坑形态打开 references/，跑前必须 scripts/verify.sh 自检。
disable-model-invocation: true
---

# gen-test

为 RuoYi-AI 后端生成符合项目约定的单元测试。

## 何时用

- 新写 Service / Controller，要补单测
- 现有 Service 加方法，要补分支覆盖
- CI 报告 Surefire 覆盖度告警

## 必读规约（按踩坑形态打开 references/）

- 红名单基线 / 时钟注入例外 / Mock 合法性三形态 → `references/red-baseline-rules.md` + `references/mock-validity-3-types.md`
- Surefire profiles.active 过滤 / Tag 缺失 → `references/tag-filtering-rules.md`
- 多租户过滤 / 权限注解覆盖维度 → `references/tenant-and-permission-rules.md`
- 项目里已知的假路 / 不可跑设设设 → `references/known-dead-ends.md`

## 跑前自检（必跑）

```bash
bash .claude/skills/gen-test/scripts/verify.sh
```

通过才能写测试代码；失败按三类归因（知识错 / 环境错 / 检查不安全）。

## 输出交付物

1. 新建测试文件清单（相对路径）
2. 跑测命令：`mvn test -pl <module> -Dtest=<ClassName>`
3. 覆盖率缺口（哪些 public 方法未覆盖，需 follow-up）

## 禁止清单

- ❌ 不加 `@Tag("dev")`（静默跳过）
- ❌ Mock 真库不可能的数据（违反 mock-validity 三硬规则）
- ❌ `@SpringBootTest` 测纯 Service
- ❌ 测试里 `Thread.sleep` 等异步
- ❌ 复制 `RuoYiAIApplication` 启动做集成测试

## 可复用示例

- Service 单测：`examples/service-test-template.java`
- Controller 单测：`examples/controller-test-template.java`
- 集成测试：`examples/integration-test-template.java`

> 完整历史 SKILL.md 见 `.archive-pre-disco/gen-test.SKILL.md.2026-09-17`。