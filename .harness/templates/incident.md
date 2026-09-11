# incident.md — Maintain 阶段产物（诊断记录）
<!--
  Anthropic AI Native SDLC Playbook · Maintain 阶段标准产物
  确定性监控发现信号越界 → AI 诊断 → 写成本文件 → 生成新的 intent.md 回到 Plan。
  「上线不是终点，维护阶段把整个循环重新接回 Plan。」
-->

## 元信息

```yaml
incident_id: INC-<YYYYMMDD>-<NNN>
detected: <YYYY-MM-DD HH:MM>
severity: S1(生产不可用) | S2(功能受损) | S3(体验受损) | S4(隐患)
status: detecting | diagnosing | intent-created | resolved
intent_ref: <诊断完成后续建的 INTENT-id，未建则留空>
```

## 1. 检测信号（确定性的，不是感觉）

| 项 | 值 |
|---|---|
| 监控来源 | <错误率 / 延迟 / CI 失败率 / 用户报告 → 转为告警通道> |
| 正常区间 | <阈值范围> |
| 实际读数 | <越界值 + 时间点> |

**纪律**：检测必须由确定性规则触发（阈值/断言），不能是「我觉得不对劲」。

## 2. 影响面（谁被伤了）

- 影响功能：<列出>
- 影响用户/数据：<范围，是否涉及数据损坏或泄露>
- 是否需要立即回滚：<是/否 + 依据 spec.md 的回滚方案>

## 3. 诊断（AI 做，人复核）

**根因**：
<一句话讲清。写不清根因就继续查，不许猜。>

**证据链**：
```
$ <诊断命令 1>
<真实输出>

$ <诊断命令 2>
<真实输出>
```

**为什么之前的防线没拦住**：
<逐层过：Layer 1 规则没写？Layer 2 Skill 缺流程？Layer 3 hook 缺拦截？测试缺用例？——这决定了回灌到哪一层。>

## 4. 权限边界（处置时智能体只做最小动作）

- 本次处置中 AI 只做：<读日志 / 写文档 / 发消息> 等只读与记录动作
- AI 不做：<任何部署 / 数据修改 / 联系其他智能体代执行>
- 依据 Anthropic 教训：事故响应智能体曾试图通过另一个实例推送修复——智能体协作必须走与人相同的可审计渠道。

## 5. 闭环动作（Maintain → Plan）

- [ ] 已生成新 `intent.md`（source: incident，incident_ref 回填本文件 id）
- [ ] 回灌规则已落层（按"为什么没拦住"的结论）：
  - Layer 1 CLAUDE.md：什么都没写 / 新增了 <内容>
  - Layer 2 Skill：新增/修订了 <skill 名>
  - Layer 3 hooks：新增/收紧了 <拦截规则>
  - Evals：本事故已加入回归用例集（`.harness/evals/`）
- [ ] 阈值/监控是否需要调整：<是/否 + 内容>

---

**下一步**：新 intent.md 进入 Plan 阶段，走完整的 spec → plan → build → test → deploy 链，不许直接改代码了事。
