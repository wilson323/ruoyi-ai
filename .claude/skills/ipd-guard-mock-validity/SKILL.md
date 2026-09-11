---
name: ipd-guard-mock-validity
description: ruoyi-ai 仓单测编写与判定的合法性硬卡口。封装 Mockito 替业务假绿、Calendar 月份双重减一、Caffeine 异步驱逐、静态扫描误伤注释、测试 helper 语义反转、stub 滞后等 5 类反复踩坑的根因与修法。判定 Mock 单测绿不等于业务闭环。
---

# ipd-guard-mock-validity

测试合法性的硬卡口。**Mock 单测全绿不等于业务闭环**。

## 何时使用

- 写新单测（含 `@ExtendWith(MockitoExtension.class)` 或 `@SpringBootTest`）
- 跑测试套件准备翻卡 done
- 验收测试套件（无论是自己的还是兄弟会话的）
- 排查"测试全绿但 HTTP 500"或"测试全绿但真库无数据"
- 评估 `ipd-guard-ddl-apply` 链路中的测试覆盖度

## 三条硬规约（不可违背）

### 规约 1：Mockito 测试 ≠ 业务闭环

`@ExtendWith(MockitoExtension.class)` + `when().thenReturn()` + `verify()` 的测试**未连真库**。已实测卡号：P313 / P361 / P381 / P1102 全员此类型。

**判定**：

```bash
# 检查测试类的"模式"——是 Mockito 还是 SpringBootTest
grep -l "@ExtendWith(MockitoExtension.class)" \
  ruoyi-modules/ruoyi-ipd/src/test/java/.../*Test.java
grep -l "@SpringBootTest" \
  ruoyi-modules/ruoyi-ipd/src/test/java/.../*Test.java
```

**硬要求**：业务关键路径必须有 `@SpringBootTest` + 真活集成测试（连 `ipd_dev`），不能只靠 Mockito stub。

### 规约 2：Mock 不可替业务实现

Mock 可以替"外部依赖"（HTTP client、消息队列、缓存），**不可以**替本服务的核心业务逻辑。

已实测反模式：
- Mock 掉 `Service.save()` 的返回值——验不出 NOT NULL 字段未设置致 HTTP 500
- Mock 掉 `Mapper.selectOne()` 返回固定 ID——验不出 selectChain 递归 CTE 改写后的真实路径
- Mock 掉 `Mapper.selectList()` 返回空集——验不出 selectOne(LIMIT 1) 改写后的首条语义

**修法**：以 main 现态数据访问路径为准重写 stub，不保旧 stub。Stub 必须对应 main 实际执行的方法调用。

### 规约 3：测试 helper 语义必须显式

helper 默认值翻转是隐蔽的真凶——`getOrDefault(code, "PASS")` 把"缺判"场景静默反转成全过。

**硬要求**：
- 双 helper 并存——缺省 PASS 版（多数用例）+ `containsKey` 显式缺判版（负例用例）
- 按语义命名（不要图省事复用）
- helper 单元测试覆盖每个分支

## 五类假红 / 假绿根源（实测 1851 用例）

| 类别 | 现象 | 真因 | 修法 |
|---|---|---|---|
| **Calendar 双重减一** | 日期断言随真实时钟摇摆（周一跑 expected 9 / 固定时钟跑 expected 6） | helper `date(y,m,d)` 内部做 m-1，调用方传 `Calendar.SEPTEMBER`（0-based=8）变 8 月 | helper 一律传 1-based 字面量，或统一 `java.time.LocalDate` |
| **Caffeine 异步驱逐** | maximumSize 测试断言 estimatedSize 时 200 写入后 size=196 | ForkJoinPool 异步维护滞后超限 | `builder.executor(Runnable::run)` 强制同步维护 |
| **静态扫描误伤注释** | `doesNotContain("===")` 命中源码注释分隔线 `/* ==== */` | 扫描未剔除注释行 | 扫描前按行剔除注释（`//`、`/*`、`*` 开头 + 行内 `//` 后缀） |
| **helper 语义反转** | 负例用例全过 | `getOrDefault(code, "PASS")` 把缺判静默反转 | 双 helper 并存，按语义命名 |
| **stub 滞后** | `UnnecessaryStubbing` 或路径性 NOT_FOUND | selectChain 递归 CTE 替代 selectById 后旧 stub 未消费 | 以 main 现态数据访问路径为准重写 stub |

## Surefire @Tag 假绿陷阱

Surefire 按 `<groups>${profiles.active}</groups>` 过滤（pom.xml:472），默认 dev profile 下没有 `@Tag("dev")` 的测试类被**静默跳过**。

```bash
# 提交前自检：所有测试类都有 @Tag
grep -rL "@Tag" ruoyi-modules/ruoyi-ipd/src/test/java/.../*Test.java
# 输出应为空——任何缺 @Tag 的新测试默认会被跳过
```

新测试不加 tag，**"测试全绿"毫无意义**——surefire 直接跳过。

## 多会话共工的测试收敛

- 判责先看文件 git status：干净 = 已提交真红；M = 兄弟 WIP 摇摆不介入。
- 多会话工作树测试收敛需 fresh 复跑两次确认（实测兄弟会话期间可能正在 apply DDL）。

## 必做检查清单

测试编写 / 验收前自检：

- [ ] 业务关键路径测试用 `@SpringBootTest` + 真活集成测试（连 `ipd_dev`）
- [ ] 测试类有 `@Tag("dev")`（否则 dev profile 下静默跳过）
- [ ] Mockito 测试只覆盖"外部依赖替身"，不替本服务核心逻辑
- [ ] helper 默认值语义显式（不能反转缺判）
- [ ] stub 对应 main 现态数据访问路径（不保旧 stub）
- [ ] 日期 helper 传 1-based 字面量（不用 Calendar 0-based 常量）
- [ ] 缓存相关测试强制同步维护（`builder.executor(Runnable::run)`）
- [ ] 静态扫描类测试先剔注释再扫
- [ ] 跑 `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test` 不带 `-am` 不带 `clean`（避免假红）

## 失败归因

| 现象 | 真因 | 归因分类 | 修法 |
|---|---|---|---|
| Mockito 单测全绿但真库缺数据 | 单测未连真库 | 知识错（违反规约 1） | 补 @SpringBootTest 真活集成测试 |
| 单测绿但 HTTP 500 | Mock 替业务，NOT NULL 字段未设 | 知识错（违反规约 2） | 取消对应 stub，让真实 Service 跑 |
| "测试全绿"但 mvn -Dtest 跑出 0 个用例 | 测试类缺 @Tag("dev") | 知识错 | 补 @Tag |
| 负例用例全过 | helper 缺省值反转 | 知识错（违反规约 3） | 拆 helper，按语义命名 |
| 多会话共工时测试一会绿一会红 | 兄弟会话在 DDL/数据 | 环境错 | fresh 复跑 + git status 判责 |
| UnnecessaryStubbing 警告 | stub 对应已废弃的方法 | 检查错 | 以 main 现态重写 stub |

## 禁止清单

- ❌ 接收 Mockito 单测绿作为业务闭环证据
- ❌ Mock 替本服务的核心业务方法
- ❌ 写测试不加 `@Tag("dev")`（dev profile 下静默跳过 = 假绿）
- ❌ helper 默认值反转缺判语义（"宽容"等于"看不见 bug"）
- ❌ 跨模块跑测试用 `mvn -am` 或 `mvn clean`（已知制造假红）
- ❌ 把"测试全绿"等同于"契约守住"（参考 `mock合法性与已知死路登记-20260908.md` 三条硬规约）

## 版本指纹

- 验证时 surefire 配置：`<groups>${profiles.active}</groups>`（pom 现查）
- 验证时测试数：约 1851 用例（验证时现查）
- 验证时真库：ipd_dev @ 127.0.0.1:13306
- 验证时根因文档：`docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md`
- 最近验证：2026-09-11（首版蒸馏）
- 过期触发：surefire 配置变更 / 测试规模大幅变化 / 新引入测试框架