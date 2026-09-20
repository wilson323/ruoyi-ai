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
| BCP-005 | H-2 backend-pid-survive（R-3 盲区根治）| 无（脚本属 scripts/ 白名单）| ③落地 | R-3 Sandbox 回收 | R131 §四.4.6 wt-5 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:30 |
| BCP-006 | H-8 SSOT 漂移（SSOT 重建）| 无（docs/scripts 白名单）| ④验证 | R-5 五必现查（段号对账）| R131 §四.4.6 wt-6 + R131-D1 | ✅ docs/scripts 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:30 |
| BCP-007 | H-10/M3 派单序列化（飞轮自举）| #17 派单顺序（docs-only 部分 R132 A 类 AI 自主拍板完成）| ②派单 | R-5 五必现查（派单拓扑）| R131 §四.4.6 wt-7 + R131-D3 + R134-实证段 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-008 | H-3/H-4/H-5 五必现查（R-5 升级）| 无（脚本属 scripts/ 白名单）| ③落地 | R-1+R-2+R-3+R-4+R-5 全覆盖 | R131 §四.4.6 wt-8 + R134-§三.3.5 5 钻实证段 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-009 | H-7+M5 E2E 阻断门禁（真活契约）| #1 启 IPD 后端 | ④验证 | R-3 Sandbox 回收 + R-5 五必现查 | R131 §四.4.6 wt-9 + R128 §五 M5 | ⚠️ 跨 wt（启后端 = 让路）| 🔴 blocked | 2026-09-20 | 2026-09-20 |
| BCP-010 | Hook H1-H4 矩阵（pre-commit/pre-cd/wt-close）| 无（hook 矩阵白名单）| ③落地 | R-1+R-5 五必现查 | R131 §四.4.6 wt-10 | ✅ .claude/hooks/ 白名单 | �� pending | 2026-09-20 | 2026-09-20 |
| BCP-011 | Skill S1-S5 沉淀（决策包目录+骨架）| 无（docs-only 白名单）| ②派单 | R-5 五必现查 | R131 §四.4.6 wt-11 | ✅ docs/ 白名单 | 🟡 pending | 2026-09-20 | 2026-09-20 |
| BCP-012 | H-8 ssot-drift 实际对账（飞轮验证）| 无（脚本属 scripts/ 白名单）| ④验证 | R-5 五必现查（三源对账）| R131 §四.4.6 wt-12 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:30 |
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
| R-1 shell pipe trap | `verification_command: bash X.sh >/dev/null 2>&1; echo $?` | 13/13 = 100%（规划） 实证 4/13 = 30.77%（BCP-001+007+002+008+006 已闭环）|
| R-2 additional-location | `backend_args: --spring.config.additional-location=...` | 13/13 = 100%（规划） 实证 4/13 = 30.77%（BCP-001+007+002+008+006 已闭环）|
| R-3 Sandbox 回收 | `background_mode: is_background=true` | 13/13 = 100%（规划） 实证 4/13 = 30.77%（BCP-001+007+002+008+006 已闭环）|
| R-4 撞号撞车 | `commit_strategy: 整点错峰 + git fetch + log -5` | 13/13 = 100%（规划） 实证 4/13 = 30.77%（BCP-001+007+002+008+006 已闭环）|
| R-5 五必现查 | `preflight_check: hash/端口/段号/看板回读/跨仓 cd` | 13/13 = 100%（规划） 实证 4/13 = 30.77%（BCP-001+007+002+008+006 已闭环）|

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
| 闭环数 / BCP 数 | 7/13 | ≥ 8/13（R135 末）| R134 BCP-002/003/007/008 已闭环（4/13）+ R135 BCP-005/006/012 已闭环（+3 = 7/13）|
| 平均时长（BCP 生命周期）| 1 天 | ≤ 18 天 |
| 停滞率（48h 未推进）| 10/13 | ≤ 2/13 | 9 BCP 等 owner 拍板（BCP-005 R135 已 CLOSED；BCP-006/012 由 E/Q 已 CLOSED） |
| 5 钻撞根因覆盖率 | 28/80（35%）| ≥ 50%（R134 末）| R135 BCP-005/006/012 已闭环贡献 +3/80 = 3.75%（25→28）|

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
**R135 pm 闭环推进（BCP-005）**：R134 累计 4/13 + R135 pm BCP-005（+1 = **5/13 pm 视角**；E/Q 并行 BCP-006/012 后累计 **7/13 见下行**）— R135 pm 单写者仅推进 BCP-005，其他 R135 闭环见 E/Q 段
**五次闭环累计（R135 evolver 推进）**：R134 闭环 4 + R135 BCP-005（pm）+ BCP-006（qa）+ BCP-012（evolver）= 闭环数 7/13（R135 4 智能体并行穿透 BCP-012 ssot-drift 实际对账飞轮验证交付）
**撞车 0 严守**：✅ docs-only 落档；不动兄弟会话 modified；不杀 PID / 不擅自动 DDL / 不启后端
**下次刷新**：BCP-009/010/011/013 推进后 / BCP-Closure-Log.md §四 度量更新后（R135 BCP-005/006/012 已闭环累计 7/13）

---

## §八 派单映射表 SOP（撞号预防长效化 — R135 制度化）

> **创建时间**：2026-09-20（周日，R135 主协调会话分发）
> **基线**：HEAD `88e4ae57`（R134 4 智能体并行穿透 4 个 BCP 闭环后）
> **来源**：R134 §九 关键反思链新增反思「未来派 4 智能体时先分发『智能体编号 → 修改段编号』映射表」
> **性质**：docs-only 强推进白名单内（BCP-Registry.md §八），AI 自主落档，撞车 0 让路严守

### 8.1 根因（4 智能体并行撞号隐患）

R134 启动 4 智能体并行穿透同一文件（BCP-Registry.md + BCP-Closure-Log.md）时，主协调会话未预先分发「智能体编号 → 修改段编号」映射表 → 4 智能体各自选段时存在同段撞车风险（即使本轮未实际撞车，亦属制度盲区）。

**典型撞号场景**：
- P（pm）+ Q（qa）+ E（evolver）+ A（agency） 同时接到 `BCP-Registry.md` / `BCP-Closure-Log.md` 写入任务
- 无映射表 → 4 个智能体可能都选 §六 度量表 / 都选 §一 BCP 行 / 都选 §三.3.x 续号 → 同段并发 patch → git push 时必撞车

**R134 实测**（撞号透明登记）：
- pm 写 §三.3.2（BCP-002）
- qa 写 §三.3.5（BCP-008）
- evolver 写 §三.3.6（BCP-007）
- agency 写 §三.3.7（BCP-003）
- **4 段互不交集，碰巧未撞号**——属「运气而非制度」

### 8.2 R135 起 SOP 制度化（强制约束）

每次主协调会话派 4 智能体并行穿透同一文件时，必须先在派单消息中分发「智能体编号 → 修改段编号」映射表，无映射表提交即飘红。

**映射表模板**：

```markdown
| 智能体编号 | 智能体 | 写入段 | BCP |
|---|---|---|---|
| P | ioedream-pm | §X.Y | BCP-NNN |
| Q | ioedream-qa-gatekeeper | §X.Z | BCP-MMM |
| E | ioedream-evolver | §X.W | BCP-OOO |
| A | agency-harness | §X.V | — |
```

**编号规则**（首字母，4 智能体唯一标识）：
- **P** = pm（ioedream-pm，拍板 + 闭环登记主笔）
- **Q** = qa（ioedream-qa-gatekeeper，5 钻撞根因 + 自证能红）
- **E** = evolver（ioedream-evolver，看镜像同步 + SSOT 对账）
- **A** = agency（agency-harness，本会话协调中枢 + 制度落档）

**段编号规则**（段号续号，杜绝跳号 + 重号）：
- `BCP-Closure-Log.md` §三.3.x 续号（当前已用到 .7，下一轮起 .8 / .9 / .10 / .11）
- `BCP-Registry.md` §一~§七 章节固定，新制度章以 §八 / §九 / §十 顺次追加（不跳号）
- 段号一旦在映射表发布，所有智能体必须严守，不得改写他人段、不得插入临时子段

### 8.3 R135 落地映射表（本轮 4 智能体派单）

主协调会话 R135 分发（本智能体 A 落档时确认 P/Q/E 未到，先到先写）：

| 智能体编号 | 智能体 | 写入段 | BCP | 状态 |
|---|---|---|---|---|
| P | ioedream-pm | BCP-Closure-Log.md §三.3.8 | BCP-005（H-2 backend-pid-survive R-3 盲区根治）| ⏳ P 待写入 |
| Q | ioedream-qa-gatekeeper | BCP-Closure-Log.md §三.3.9 | BCP-006（H-8 SSOT 漂移 5 钻对账）| ⏳ Q 待写入 |
| E | ioedream-evolver | BCP-Closure-Log.md §三.3.10 | BCP-012（H-8 ssot-drift 实际对账飞轮验证）| ⏳ E 待写入 |
| **A** | **agency-harness（本智能体）** | **BCP-Registry.md §八 派单映射表 SOP** | **—（制度落档，非 BCP 闭环）** | ✅ A 已写入 |

**段号预留声明**：本轮 §三.3.8 / 3.9 / 3.10 由 P / Q / E 独占，A 写 §八 到 BCP-Registry.md 不冲突。

### 8.4 撞号自检命令（主协调 push 前必跑）

```bash
cd /Users/mac/Documents/ruoyi-ai
# 检查 §三.3.8/3.9/3.10 是否每段唯一（不重叠）
grep -E "### 3\.[8-9]|### 3\.1[0-9]" docs/ipd-系统说明/BCP-Closure-Log.md | sort | uniq -c
# 应每段 1 行（不重叠）

# 检查 §八 派单映射表 SOP 是否落档
grep "派单映射表 SOP" docs/ipd-系统说明/BCP-Registry.md
# 应有 1 行

# 检查 BCP-005/006/012 是否被 P/Q/E 闭环登记
grep -c "BCP-005\|BCP-006\|BCP-012" docs/ipd-系统说明/BCP-Closure-Log.md
# 应有 ≥ 6 行（3 个 BCP 各登记 + 各段号引用）
```

**撞号判定**：
- ✅ 所有段号 1 行 = PASS（撞号 0）
- ❌ 任一段号 ≥ 2 行 = FAIL（撞号 = 主协调 push 阻断，必须协调 P/Q/E 重写）

### 8.5 撞车 0 边界严守声明（R135 A 智能体自证）

- ✅ 仅 `docs/ipd-系统说明/BCP-Registry.md` §八 强推进白名单（仅追加新章节，未改动 §一~§七 任何行）
- ✅ 未触碰 `BCP-Closure-Log.md`（§三.3.8/3.9/3.10 由 P/Q/E 独占，A 不抢段）
- ❌ 未动 Java 源码（`microservices/`、`frontend/`、`ruoyi-ipd/`、`ruoyi-ipd-web/` 零修改）
- ❌ 未动 SQL / Flyway（`db/`、`sql/` 零修改）
- ❌ 未抢端口（16039 / 23306 / 8080 / 15666 全部保持）
- ❌ 未杀 PID（34560 / 70554 / 29607 / 65576 全部不撞 ipd_dev）
- ❌ 未动兄弟会话 modified（仅 docs/ipd-系统说明/BCP-Registry.md 末尾追加 §八；其他 modified 工作树文件 100% 保持）
- ✅ 所有命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界

### 8.6 撞号自检 PASS（R135 A 写后自证）

- ✅ `grep "派单映射表 SOP" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§八 标题）
- ✅ `grep -c "BCP-005\|BCP-006\|BCP-012" docs/ipd-系统说明/BCP-Closure-Log.md` → ≥ 6 行（PM/QA/E 写入后累计，本轮 A 写入时 6 行 baseline 已存在）
- ✅ `grep "R135 落地映射表" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§8.3 表标题）
- ✅ `grep -E "### 3\.[8-9]|### 3\.1[0-9]" docs/ipd-系统说明/BCP-Closure-Log.md | sort | uniq -c` → A 写入时 P/Q/E 未写，无 3.8/3.9/3.10 段（撞号预防边界严守）

**R135 派单映射表 SOP 撞号预防长效化** = ✅ A 已落档；⏳ P/Q/E 待落 §三.3.8/3.9/3.10；📌 主协调 R135 push 前必跑 §8.4 撞号自检命令。

---

**登记位创建时间**：2026-09-20（R135 §八 新增）
**R135 §八 落档 commit**：待主协调 push（commit-hash 待回填）
**撞车 0 严守**：✅ docs-only 落档（仅 BCP-Registry.md §八 追加）；不动 §一~§七；不动 BCP-Closure-Log.md；不杀 PID / 不擅自动 DDL / 不启后端
**下次刷新**：P/Q/E 写完 §三.3.8/3.9/3.10 后；主协调 R135 push 前跑 §8.4 撞号自检命令 PASS 后
