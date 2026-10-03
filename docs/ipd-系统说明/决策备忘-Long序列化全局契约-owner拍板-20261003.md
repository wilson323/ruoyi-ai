# 决策备忘：全局 Long→String 序列化契约（待 owner 拍板）

- 登记时间：2026-10-03
- 来源：事件载荷 Long→STRING 契约钉住（commit `0901355e`，2026-10-02）遗留的 C 类拍板项
- 状态：**待 owner 拍板**（本文只列事实与选项，不改代码）

## 一、背景

IPD `/api/v1` 规格要求「字符串 ID」。当前实现是 `IpdPrimaryBeansConfig#objectMapper()`（`@Primary` ObjectMapper）**无条件**注册 `Long.class → ToStringSerializer`，即所有经该 mapper 序列化的 Long 一律输出 JSON 字符串。

2026-10-02 排查事件载荷缺陷时发现配套陷阱：`JsonNode.longValue()` 对 TextNode **恒返回 0**，`asLong()` 才兼容 STRING/INTEGER 两态。事件载荷已按 STRING 钉住契约并消除测试假绿（commit `0901355e`，4 文件 +153/-5，门禁 7/7）。

## 二、现状事实（均为 A 级证据，来源见括号）

1. `@Primary ObjectMapper` 无条件注册 `Long→ToStringSerializer`（`org.ruoyi.ipd.config.IpdPrimaryBeansConfig#objectMapper()` 源码）。
2. 事件载荷契约已按 STRING 钉住，测试断言真实类型（commit `0901355e` 内 `ProjectAgentAguiPauseResumeServiceTest` 假绿消除）。
3. 前端（ruoyi-ipd-web）按字符串 ID 消费（AGENTS.md「IPD `/api/v1` 使用 code0/message 包络、字符串ID」）。

## 三、选项

| 选项 | 内容 | 代价/风险 |
| --- | --- | --- |
| **A（建议）** | 维持现状：全局 Long→STRING 为定案 | 零改动；一致性最强；消费方读回用 `asLong()` 模式（已有先例） |
| B | 收窄为字段级 `@JsonSerialize(using = ToStringSerializer.class)`，只对 ID 类字段 | 改动面大、逐字段易漏、新旧双轨期契约漂移 |
| C | 移除全局转换，改前端 BigInt 处理 | 违背「字符串 ID」规格；前端改动面大；回归风险最高 |

## 四、建议与拍板后动作

建议选 **A**：现状即目标态，`0901355e` 已把契约钉在测试上。

owner 拍板后：
- 若 A：本备忘标记「已拍板-维持」，无需代码改动；后续新端点不再逐一拍板。
- 若 B/C：另立看板卡排期，先出字段清单与前端影响面评估再动代码。

## 五、拍板记录

| 日期 | 拍板人 | 结论 | 备注 |
| --- | --- | --- | --- |
| （待填） | （owner） | （A/B/C） | （） |
