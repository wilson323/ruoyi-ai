# SKILLS.md — 全部 Skill 速查（Layer 2 参考）

> 共 24 个。本文件由 `skills.manifest` 自动生成，勿手工编辑。
> 生成命令：`python3 ~/.claude/ai-native-sdlc/.gen-skills-md.py`

变更清单后重跑生成命令即可，无需改动其他文件。

---

## Plan & Design

- **`four-line-spec-authoring`** — 模糊需求→4行规格(目标/不做/验收/边界)，未评审不许实现

## Plan

- **`prior-art-before-build`** — 动手前先调研：场景判断→澄清→同搜开源+付费→两轴评分→三阶段成本
- **`security-review-at-intent`** — 安全评审前置到 intent 阶段，对照 MITRE ATT&CK + 组织上下文

## Plan & Maintain

- **`tagged-expert-memory`** — 专家结论按 域/日期/置信度/类型 打标签入库，90天未用清理

## Build

- **`worktree-isolation-manager`** — 每任务独立 worktree + 确定性端口分配 + 子模块初始化
- **`egress-allowlist`** — 出站流量白名单，防提示词注入外泄；读权限≠发权限

## Plan & Build

- **`team-harness-bootstrap`** — team-harness 仓库脚手架 + sync 脚本 + 规则走 PR review

## Build & Deploy

- **`cross-vendor-review`** — diff 路由给不同供应商模型审查，人类 merge

## Build & Test

- **`multi-reviewer-partition`** — 多审查者按关注点分区(权限/数据流/依赖/历史事故)，不共享盲区

## Test

- **`verification-loop-with-escalation`** — 每单元 build→vet→verify，同错3次升级人审
- **`risk-derived-test-scope`** — 把影响分析的风险等级转成回归范围与测试点清单
- **`continuous-eval-regression`** — 换模型/改规则前用固定任务集重跑同套标准，防静默回归

## Test & Deploy

- **`pii-desensitization-gate`** — 20类正则脱敏 + 21步输出前扫描 + 零容忍禁止项

## Deploy & Maintain

- **`tool-permission-gate`** — 每次工具调用 allow/deny/ask，危险路径 bypass 不可关闭
- **`ai-approval-audit-trail`** — AI 自主审批须记录信号与理由 + 风险加权抽样 + SIEM 归因

## Deploy

- **`side-effect-log-and-idempotency`** — 确定性幂等键 + 5字段副作用日志，具名授权闸门

## Maintain

- **`harness-health-scan`** — 五层34项巡检，自含 HTML 报告 + 按严重度排序的修复提示词
- **`session-finalization-gate`** — 五轴收口对账(代码/验证/文档/遗留/风险)，未过不得声明完成
- **`evidence-bound-handover`** — 暂停/换会话前写4字段交接证据，缺证据拒绝开新任务
- **`context-deletion-experiment`** — 删一类上下文重跑，成功率不降即为死重
- **`incremental-skill-merger`** — 只追加不覆盖，Layer 0/5 不可改，CoW 快照可回滚
- **`markdown-to-multistyle-pdf`** — Markdown→sun/journal/atlas 三风格 PDF
- **`agent-identity-least-privilege`** — 智能体独立身份+最小权限；**智能体间联系渠道本身也是权限边界**
- **`observation-mode-promotion`** — 新 AI 审查者先观察模式只评论，信任建立后逐级放开
