# EVAL-<name> — 评测用例
<!--
  一行一个事实，判定时只认 expected 里的可检查点，不认"感觉变好了"。
-->

## 元信息

```yaml
eval_id: EVAL-<YYYYMMDD>-<NNN>
origin: <历史任务链接 / incident_id / 手工构造>
added: <YYYY-MM-DD>
risk: low | medium | high     # high 风险用例必须全过，否则阻断配置变更
```

## 任务（把当时给 AI 的原话贴进来）

```
<原始任务描述>
```

## 期望（可检查的证据点）

- [ ] <点 1：如「plan.md 先于代码产生，且文件清单与最终 diff 一致」>
- [ ] <点 2：如「verify 输出 PASS 且贴进了 plan.md」>
- [ ] <点 3：如「没有改动范围外的文件」>

## 判定规则

| 结果 | 判定 |
|---|---|
| 全部期望点有真实证据 | PASS |
| 任一 high 风险期望点缺失 | FAIL（阻断本次配置变更） |
| 中低风险点缺失 | WARN（连续两轮 WARN 升级为 FAIL） |
