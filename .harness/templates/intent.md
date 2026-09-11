# intent.md — Plan 阶段产物
<!--
  Anthropic AI Native SDLC Playbook · Plan 阶段标准产物
  由提出者与 AI 共同完成：AI 像分析师一样追问，人校对确认后提交版本控制。
  来源字段服务于 Maintain 阶段的闭环 —— 线上事故会生成新的 intent.md 回到这里。
-->

## 元信息

```yaml
intent_id: INTENT-<YYYYMMDD>-<NNN>
author: <提出者>
created: <YYYY-MM-DD>
status: draft | reviewing | approved | superseded
supersedes: <上一个 intent_id，或 null>
source: user-request | incident | <issue-link>   # Maintain 阶段回填事故来源
```

## 1. 问题（要解决什么）

<用提出者自己的话讲清楚：现在哪里痛、痛的频率、痛的代价。不要写解决方案。>

## 2. 目标（做完之后什么样）

<可验证的目标描述。避免"提升体验"这类不可判定的表述。>

## 3. 范围

**在范围内**：
- <明确列出>

**不在范围内**（比"在范围内"更值钱）：
- <明确列出 —— 告诉 AI 别多管闲事>

## 4. 约束

- 时间：<deadline 或"无硬性期限">
- 预算：<人力 / token 预算 / 成本上限 max_cost_usd>
- 合规：<涉及的行业规范、数据分级>
- 技术：<必须使用的栈、禁止引入的依赖>

## 5. 已知未知（还没想清楚的地方）

<显式列出未决问题。Anthropic: 不提供的假设不得悄悄猜测，必须显式写出供审视。>

- [?] <未决问题 1>
- [?] <未决问题 2>

## 6. 安全评审（Anthropic 要求前置到此阶段）

| 项 | 结论 |
|---|---|
| 涉及的数据分级 | <public / internal / confidential / restricted> |
| 潜在攻击面 | <对照 MITRE ATT&CK 的初步分析> |
| 需要哪些团队会签 | <安全 / 合规 / 法务 / 无> |
| 已知的组织内先例 | <过往类似决策的链接> |

## 7. 验收信号（什么情况下认为这个 intent 达成了）

- [ ] <可观测信号 1>
- [ ] <可观测信号 2>

---

**下一步**：进入 Design 阶段，产出 `spec.md`。规格未评审通过前，不得进入 Build。