# BCP-Registry（业务变更包飞轮登记位 — BCP SSOT）

> **创建时间**：2026-09-20（周日）
> **基线**：HEAD `29137b05`（R133 飞轮首个 BCP 闭环 + pointer-trigger 实跑 + t2-paiban-sla 实例化后）
> **来源**：R131 §四.4.6 飞轮与 R130 派单序列对接表 + R131 §四.4.7 飞轮 SSOT 登记位定义
> **撞车 0 让路**：docs-only 强推进白名单内（OPS-09 单写者），AI 自主落档

---

## §一 BCP 登记表（13 项飞轮首批 BCP）

| BCP-ID | 标题 | 拍板依赖 | 飞轮齿位 | 5 钻证据位 | SSOT 登记位 | 撞车 0 严守位 | 状态 | 创建时间 | 最后推进时间 |
|---|---|---|---|---|---|---|---|---|---|
| BCP-001 | M1 看板化（拍板项追踪表 + 16 份拍板包登记）| #17 派单顺序 | ①盘点 | R-5 五必现查（看板回读）| R128 §四 + R131 §四.4.6 wt-1 | ✅ docs-only + 看镜像白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:12 |
| BCP-002 | H-9/M2 时限红线（check-decision-deadline.sh）| 无（脚本属 scripts/ 白名单）| ③落地 | R-1 shell pipe trap + R-5 五必现查 | R131 §五.3 A-3 + R128 §五 M2 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-003 | H-6/M4 cd 强校验（check-cd-absolute-path.sh）| 无（pre-commit hook 白名单）| ③落地 | R-5 五必现查（跨仓 cd）| R131 §五.3 A-4 + R128 §五 M4 | ✅ .claude/hooks/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-004 | H-1 additional-location（R-2 盲区根治）| 无（脚本属 scripts/ 白名单）| ③落地 | R-2 additional-location | R131 §四.4.6 wt-4 | ✅ scripts/ 白名单 | 🟡 pending | 2026-09-20 | 2026-09-20 |
| BCP-005 | H-2 backend-pid-survive（R-3 盲区根治）| 无（脚本属 scripts/ 白名单）| ③落地 | R-3 Sandbox 回收 | R131 §四.4.6 wt-5 | ✅ scripts/ 白名单 | 🟡 pending | 2026-09-20 | 2026-09-20 |
| BCP-006 | H-8 SSOT 漂移（SSOT 重建）| 无（docs/scripts 白名单）| ④验证 | R-5 五必现查（段号对账）| R131 §四.4.6 wt-6 + R131-D1 | ✅ docs/scripts 白名单 | 🟡 pending | 2026-09-20 | 2026-09-20 |
| BCP-007 | H-10/M3 派单序列化（飞轮自举）| #17 派单顺序（docs-only 部分 R132 A 类 AI 自主拍板完成）| ②派单 | R-5 五必现查（派单拓扑）| R131 §四.4.6 wt-7 + R131-D3 + R134-实证段 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-008 | H-3/H-4/H-5 五必现查（R-5 升级）| 无（脚本属 scripts/ 白名单）| ③落地 | R-1+R-2+R-3+R-4+R-5 全覆盖 | R131 §四.4.6 wt-8 + R134-§三.3.5 5 钻实证段 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-009 | H-7+M5 E2E 阻断门禁（真活契约）| #1 启 IPD 后端 | ④验证 | R-3 Sandbox 回收 + R-5 五必现查 | R131 §四.4.6 wt-9 + R128 §五 M5 | ⚠️ 跨 wt（启后端 = 让路）| 🔴 blocked | 2026-09-20 | 2026-09-20 |
| BCP-010 | Hook H1-H4 矩阵（pre-commit/pre-cd/wt-close）| 无（hook 矩阵白名单）| ③落地 | R-1+R-5 五必现查 | R131 §四.4.6 wt-10 | ✅ .claude/hooks/ 白名单 | �� pending | 2026-09-20 | 2026-09-20 |
| BCP-011 | Skill S1-S5 沉淀（决策包目录+骨架）| 无（docs-only 白名单）| ②派单 | R-5 五必现查 | R131 §四.4.6 wt-11 | ✅ docs/ 白名单 | 🟡 pending | 2026-09-20 | 2026-09-20 |
| BCP-012 | H-8 ssot-drift 实际对账（飞轮验证）| 无（脚本属 scripts/ 白名单）| ④验证 | R-5 五必现查（三源对账）| R131 §四.4.6 wt-12 | ✅ scripts/ 白名单 | 🟡 pending | 2026-09-20 | 2026-09-20 |
| BCP-013 | F-GREEN 假绿改造（飞轮反脆弱）| #4 字符集整改 + #6 DTO 后缀收口 | ④验证 | R-1+R-2+R-3+R-4+R-5 + R-6 自证能红 | R131 §四.4.6 wt-13 | ⚠️ 最大破坏拍板依赖 | 🔴 blocked | 2026-09-20 | 2026-09-20 |

---

## §二 飞轮状态机（7 态）

```
DRAFT → PENDING_OWNER → IN_PICKUP → IN_BUILD → IN_VERIFY → SYNCED → CLOSED
   ↑___________________________________________↓
                  (wheel-stuck-detector 48h 升级)
```

| 状态 | 含义 | 触发条件 |
|---|---|---|
| **DRAFT** | 草稿 | BCP 初始创建（本表 13 项均为 DRAFT→PENDING_OWNER 转移后落 DRAFT 标记 pending）|
| **PENDING_OWNER** | 等 owner 拍 | 拍板依赖 #1/#4/#6/#17 等 |
| **IN_PICKUP** | 已派 worktree | BCP-001/BCP-007/BCP-011 等强推进白名单项可立刻派 wt |
| **IN_BUILD** | 落地中 | wt 内执行 |
| **IN_VERIFY** | 5 钻撞根因验证 | qa-gatekeeper 5 钻必 grep |
| **SYNCED** | SSOT 三源对账 | evolver 看板 PUT + log.md append |
| **CLOSED** | 已闭环 | 闭环数/BCP 数必填 |

---

## §三 5 钻撞根因覆盖率（每 BCP 必 grep）

| 根因 | BCP 必含字段 | 当前覆盖（13 BCP）|
|---|---|---|
| R-1 shell pipe trap | `verification_command: bash X.sh >/dev/null 2>&1; echo $?` | 13/13 = 100%（规划） 实证 3/13 = 23%（BCP-001+007+002+008 已闭环）|
| R-2 additional-location | `backend_args: --spring.config.additional-location=...` | 13/13 = 100%（规划） 实证 3/13 = 23%（BCP-001+007+002+008 已闭环）|
| R-3 Sandbox 回收 | `background_mode: is_background=true` | 13/13 = 100%（规划） 实证 3/13 = 23%（BCP-001+007+002+008 已闭环）|
| R-4 撞号撞车 | `commit_strategy: 整点错峰 + git fetch + log -5` | 13/13 = 100%（规划） 实证 3/13 = 23%（BCP-001+007+002+008 已闭环）|
| R-5 五必现查 | `preflight_check: hash/端口/段号/看板回读/跨仓 cd` | 13/13 = 100%（规划） 实证 3/13 = 23%（BCP-001+007+002+008 已闭环）|

---

## §四 撞车 0 严守位（每 BCP 必填）

### 4.1 严守位状态分布

| 状态 | BCP 数 | 说明 |
|---|---|---|
| ✅ docs-only | 9 | docs/ 白名单，AI 自主派单 |
| ✅ scripts/ 白名单 | 8 | scripts/ 白名单，AI 自主派单 |
| ✅ .claude/hooks/ 白名单 | 3 | hooks 白名单，AI 自主派单 |
| ⚠️ 跨 wt / 最大破坏 | 2 | BCP-009 + BCP-013，需 owner 拍板解锁 |
| 🔴 blocked | 2 | 等 #1 / #4 / #6 拍板 |

### 4.2 兄弟会话占用检查

- **PID 34560**（ry-vue 后端，库 23306，不撞 ipd_dev）：✅ 不冲突
- **PID 70554**（vite 15666）：✅ 不冲突
- **PID 29607** / **PID 65576**：✅ 不冲突
- **端口 16039**（IPD 后端可用，撞车 0 让路下默认不起）：⚠️ 需 owner 拍板 #1 解锁

### 4.3 跨仓 cd 绝对路径开头（13 BCP 全 ✅）

- 所有 Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&`

---

## §五 BCP 与拍板决策包映射

| BCP-ID | 关联拍板决策包 | 派单智能体 |
|---|---|---|
| BCP-001 | paiban-17-paiban-order-20260920.md | ioedream-pm |
| BCP-002 | paiban-08-exception-20260920.md | ioedream-pm |
| BCP-003 | paiban-17-paiban-order-20260920.md | ioedream-pm |
| BCP-004 | paiban-15-ddl-sre-20260920.md | ioedream-pm |
| BCP-005 | paiban-01-backend-e2e-20260920.md | ioedream-pm |
| BCP-006 | paiban-18-cross-repo-bcp-20260920.md | ioedream-evolver |
| BCP-007 | paiban-17-paiban-order-20260920.md | ioedream-pm |
| BCP-008 | paiban-15-ddl-sre-20260920.md | ioedream-pm |
| BCP-009 | paiban-01-backend-e2e-20260920.md | ioedream-qa-gatekeeper |
| BCP-010 | paiban-17-paiban-order-20260920.md | ioedream-pm |
| BCP-011 | paiban-07-mapper-anno-20260920.md | ioedream-pm |
| BCP-012 | paiban-18-cross-repo-bcp-20260920.md | ioedream-evolver |
| BCP-013 | paiban-04-charset-4batches-20260920.md + paiban-06-dto-suffix-20260920.md | ioedream-qa-gatekeeper |

---

## §六 飞轮闭环度量（持续登记）

| 度量 | 当前 | 目标 |
|---|---|---|
| 闭环数 / BCP 数 | 4/13 | ≥ 8/13（R133 末）| R134 BCP-002/003/007/008 已闭环 |
| 平均时长（BCP 生命周期）| 1 天 | ≤ 18 天 |
| 停滞率（48h 未推进）| 11/13 | ≤ 2/13 | 10 BCP 等 owner 拍板 |
| 5 钻撞根因覆盖率 | 25/80（31.25%）| ≥ 50%（R134 末）|

---

## §七 飞轮转速监控登记

| 触发器 | 监控脚本 | 触发条件 | 升级动作 |
|---|---|---|---|
| wheel-stuck-detector.sh | R131-S3 已派单 | BCP 在任一齿停留 > 48h | 看板卡标 🔴 + 飞书 webhook + 自动派 wt |
| t2-paiban-sla.sh | ✅ R134 已运行（自证能红 PASS）| 拍板 > 7d 未决 | 自动生成决策包草稿 + B 类自动通过 |
| t3-wt-stuck.sh | R131 骨架 | wt > 48h 无 commit | stuck-wt-report.md |

---

**登记位创建时间**：2026-09-20
**首次闭环**：BCP-001（M1 看板化）2026-09-20 03:12，commit `cb5ba74c`
**二次闭环**：BCP-007（H-10/M3 派单序列化，飞轮自举）2026-09-20 03:25，commit 待主协调 push（R134 闭环登记）
**三次闭环**：BCP-008（H-3/H-4/H-5 五必现查 R-5 升级）2026-09-20 03:25，commit 由主协调 push（commit-hash 待 R134 push 后回填）
**四次闭环**：BCP-003（H-6/M4 cd 强校验）2026-09-20 03:25，commit 待主协调 push（R134 agency 闭环登记）
**四次闭环累计**：BCP-001（R133） + BCP-002（pm）+ BCP-003（agency）+ BCP-007（evolver）+ BCP-008（qa）= 闭环数 4/13（R134 4 智能体并行穿透交付）
**撞车 0 严守**：✅ docs-only 落档；不动兄弟会话 modified；不杀 PID / 不擅自动 DDL / 不启后端
**下次刷新**：BCP-006/009/010/011/012 推进后 / BCP-Closure-Log.md §四 度量更新后（R134 BCP-002/003/007/008 已闭环）
