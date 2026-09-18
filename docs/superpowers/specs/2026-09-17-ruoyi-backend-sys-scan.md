# ruoyi-ai 后端系统性梳理 + 扫修一体化 —— 设计文档

**作者**：主协调会话 · **日期**：2026-09-17 · **状态**：approved · **目标**：派 4 路蜂群（CodeReview 类型）并行扫描 ruoyi-ai 后端 4 类系统性盲点，发现即修，落盘 `docs/全局梳理-2026-09-17.md` 作为汇总证据。

> 注：本 spec 由 brainstorming skill 流程产出（用户已批准 3 项推荐）。实施走 inline 派 subagent。

---

## 1. 背景与目标

### 1.1 触发
用户指令"系统性梳理全局项目深度思考反思还有哪些待办事项完整执行"。

### 1.2 现状（来自 CLAUDE.md Repowise 索引 + AGENTS.md + 本次 DisCo 探索）
- ruoyi-ai 主仓 2038 文件 / 254k 行 / 38 Java 包，重点 ruoyi-ipd 模块（IPD 业务实现）
- 健康指标：defect risk **8.81/10**（高） · maintainability **8.92/10**（高） · performance risk **232 open N+1**
- bug-magnet 模块（按 PR 历史排序）：HandoverService / BidInvitationService / AuditLogService / GateElementController / ProjectController
- 已加固：28+ 个门禁脚本（含 R30+ 治理门禁）、4 个 IPD skill + 7 个 ipd-guard（已 DisCo 化 1 个）
- 已有 P0-P2 待办（memory ec06d86f）：P0 阻塞 7 项 / P1 质量门 6 项 / P2 加固 5 项

### 1.3 4 路蜂群扫描目标

| 蜂群 | 扫描类型 | 范围 | 修复深度 |
|------|----------|------|----------|
| **A** | bug-magnet 5 模块深审 | HandoverService / BidInvitationService / AuditLogService / GateElementController / ProjectController | 找根因 → 修代码 + 补回归测试 |
| **B** | 性能 N+1 / 静态 I/O 232 处 | 全仓 `find` 静态扫描 + 抽样审查 | 找出 Top 5 真实 case → 修（不一定全部 232） |
| **C** | 文档-代码对齐 | docs/开发说明 vs docs/ipd-系统说明 vs 实际 ruoyi-ipd/src/main | 找出显著不一致 → 修代码或修文档（按业务裁决拍板边界） |
| **D** | 生产就绪资产 | Dockerfile / compose / nginx / application-prod.yml / 部署脚本 | 补缺 → 新建最少必要的部署物 |

### 1.4 非目标
- 不重启后端 16039
- 不改前端仓（ruoyi-ipd-web）
- 不改 ZK-IPD 项目
- 不一次扫完所有 232 个 N+1（按价值排序，仅修 Top 5+ 真实风险）
- 不擅自改动业务决策类产物（业务裁决拍板边界已立）

---

## 2. 蜂群派单规约

每路蜂群 subagent 派单模板（仿 001827cc 模式 + memory a57799e1）：

```yaml
task: "<具体扫描+修复目标>"
scope:
  - <绝对路径1>
  - <绝对路径2>
evidence_required:
  - mtime 检查（防止兄弟会话在途被吃）
  - 提交后核 `git show --name-only --format="" HEAD | grep -c .` 必须 == 声明条数
  - 单模块构建验证 `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test`
boundaries:
  - 不动 OPS-09 单写者约束范围外
  - 不擅自改业务决策类
output:
  - 落盘 `<path>/<topic>-2026-09-17.md`
  - commit 信息格式：`fix/refactor: <scope> <具体>`
```

---

## 3. 蜂群 A: bug-magnet 5 模块深审

**输入**：CLAUDE.md 列出的 5 个 bug-magnet 绝对路径
**输出**：
- 每个模块至少 1 处根因 + 修复 commit
- 5 模块产出 5 commit（一次提交/模块）
- 落盘 `docs/全局梳理/swarm-A-bugmagnet-2026-09-17.md`

**修复纪律**：
- 不破坏现有契约（仅修实现 bug，不改 API 签名）
- 补回归测试（按 gen-test DisCo 形态）
- 失败归因三类明确

---

## 4. 蜂群 B: 性能 N+1 Top 5

**输入**：`repowise get_risk include=performance` 抽样
**输出**：
- Top 5 真实 N+1（不要静态分析误报）
- 每个修复 1 commit
- 落盘 `docs/全局梳理/swarm-B-perf-n+1-2026-09-17.md`

**修复纪律**：
- 优先修 hot path（业务请求链路上）
- 不改架构（不改 SQL 改成批处理等大改），仅加 index / 改 fetch 模式
- 抽样验证：业务接口响应时间（参考 log.md 历史请求时间）

---

## 5. 蜂群 C: 文档-代码对齐

**输入**：`docs/开发说明/` 5 个核心文档 vs `ruoyi-ipd` 模块实际实现
**输出**：
- Top 5-10 显著不一致（按"实际业务有但文档未说"或"文档有但实际未实现"分两列）
- 修复决策：每条标注"改代码 / 改文档 / 待 owner 拍板"
- 落盘 `docs/全局梳理/swarm-C-doc-codemismatch-2026-09-17.md`

**修复纪律**：
- 按业务裁决拍板边界：能修代码的修代码；明显是文档错的改文档；边界类标待 owner 拍板
- 不擅自大规模改文档（按 AGENTS.md 文档是产品决策事实源）

---

## 6. 蜂群 D: 生产就绪资产补缺

**输入**：现有 Dockerfile / compose / nginx / application-prod.yml / 部署脚本存在性扫描
**输出**：
- 缺什么补什么：Dockerfile 后端已有补前端 / compose 编排 / nginx 配置 / 健康检查脚本
- 落盘 `docs/全局梳理/swarm-D-prod-assets-2026-09-17.md`

**修复纪律**：
- 不擅自加 K8s 配置（用户没要求）
- 不动 prod yml 模板内容（业务配置由 owner 拍板）
- 仅补"必要且最小"的资产

---

## 7. 汇总文档

**`docs/全局梳理-2026-09-17.md`**：
- 4 路蜂群各一段（链接到子报告 + 关键 findings 数 + commit 数）
- 总 findings 数 + 已修 / 待修分布
- 总 commit 数
- 风险与限制

---

## 8. 自审结果

1. **占位扫描**：无 TBD/TODO，所有目标已明确。
2. **内部一致性**：架构与设计决策一致。
3. **范围检查**：聚焦 4 路并行 + 扫修一体化，复杂度合适。
4. **歧义检查**：所有"应该"改成"必须"/"按"以减少解读空间。

无问题，本 spec 已自审通过，用户已批准，进入派 subagent 实施。

---

## 9. 风险与缓解

| 风险 | 等级 | 缓解 |
|------|------|------|
| 多会话共工污染 | 高 | 每路蜂群独立 worktree，主协调汇总 |
| 蜂群改动过大 | 中 | 派单模板明列 boundaries |
| 业务决策类误改 | 中 | 按业务裁决拍板边界 + 标"待 owner 拍板" |
| 假绿（绿的不是契约） | 高 | 每路蜂群提交前自证能红 + 真活验证 |

---

## 10. 来源
- DisCo-Local 探索：本会话上下文
- memory ec06d86f：IPD 项目生产就绪全景盘点与 P0-P2 待办清单
- memory a57799e1：三轮立即执行闭环（蜂群派单精度教训）
- memory 018dd6f2：证据核收口轮（fresh 验证优先教训）
- CLAUDE.md Repowise 索引：bug-magnet 模块排序 + 健康指标
- AGENTS.md：构建/测试纪律 + 隔离worktree纪律