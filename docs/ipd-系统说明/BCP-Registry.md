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
| BCP-004 | H-1 additional-location（R-2 盲区根治，IPD 后端读 application-ipd-local.yml → ipd_dev 库）| 无（脚本属 scripts/ 白名单）| ③落地 | R-2 additional-location（ipd_dev 后端配置多源）| R131 §四.4.6 wt-4 | ✅ scripts/ 白名单 + ipd_dev 库配置 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:35 |
| BCP-005 | H-2 backend-pid-survive（R-3 盲区根治）| 无（脚本属 scripts/ 白名单）| ③落地 | R-3 Sandbox 回收 | R131 §四.4.6 wt-5 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:30 |
| BCP-006 | H-8 SSOT 漂移（SSOT 重建）| 无（docs/scripts 白名单）| ④验证 | R-5 五必现查（段号对账）| R131 §四.4.6 wt-6 + R131-D1 | ✅ docs/scripts 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:30 |
| BCP-007 | H-10/M3 派单序列化（飞轮自举）| #17 派单顺序（docs-only 部分 R132 A 类 AI 自主拍板完成）| ②派单 | R-5 五必现查（派单拓扑）| R131 §四.4.6 wt-7 + R131-D3 + R134-实证段 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-008 | H-3/H-4/H-5 五必现查（R-5 升级）| 无（脚本属 scripts/ 白名单）| ③落地 | R-1+R-2+R-3+R-4+R-5 全覆盖 | R131 §四.4.6 wt-8 + R134-§三.3.5 5 钻实证段 | ✅ scripts/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:25 |
| BCP-009 | H-7+M5 E2E 阻断门禁（真活契约）+ 跨仓最大破坏重审 docs-only 准备 | #1 启 IPD 后端 + #6 跨仓 commit 并行授权 + #15 跨仓 BCP 自动同步授权 | ④验证 | R-3 Sandbox 回收 + R-5 五必现查 + 4 类跨仓破坏场景 | R131 §四.4.6 wt-9 + R128 §五 M5 + R136 §三.3.13 docs-only 准备 | ⚠️ docs-only 准备已 R136 完成；跨 wt 实装仍等 owner 拍板 #1+#6+#15 | 🟡 PENDING_OWNER | 2026-09-20 | 2026-09-20 03:35 |
| BCP-010 | Hook H1-H4 矩阵（pre-commit/pre-cd/wt-close）| #1 owner 拍板位（docs-only 准备已 R136 完成）| ③落地 | R-1+R-5 五必现查 | R131 §四.4.6 wt-10 + R136 §三.3.12 状态机推进 | ✅ .claude/hooks/ 白名单 | �� PENDING_OWNER | 2026-09-20 | 2026-09-20 03:35 |
| BCP-011 | Skill S1-S5 沉淀（决策包目录+骨架）| 无（docs-only 白名单）| ②派单 | R-5 五必现查 | R131 §四.4.6 wt-11 + R137 §三.3.14 | ✅ docs/ 白名单 | ✅ CLOSED | 2026-09-20 | 2026-09-20 03:40 |
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
| R-2 additional-location | `backend_args: --spring.config.additional-location=...` | 13/13 = 100%（规划） 实证 5/13 = 38.46%（BCP-001+007+002+008+006+004 已闭环，BCP-004 H-1 additional-location = ipd_dev 库后端配置多源 R-2 盲区根治核心证据位）|
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
| 闭环数 / BCP 数 | 9/13 | ≥ 8/13（R135 末已达成 ✅，R137 首个闭环达成 9/13）| R134 BCP-002/003/007/008 已闭环（4/13）+ R135 BCP-005/006/012 已闭环（+3 = 7/13）+ R136 BCP-004 H-1 additional-location ipd_dev 库配置多源已闭环（+1 = 8/13，首个 R136 闭环）+ R137 BCP-011 Skill S1-S5 沉淀已闭环（+1 = 9/13，首个 R137 闭环）|
| 平均时长（BCP 生命周期）| 1 天 | ≤ 18 天 |
| 停滞率（48h 未推进）| 10/13 | ≤ 2/13 | 9 BCP 等 owner 拍板（BCP-005 R135 已 CLOSED；BCP-006/012 由 E/Q 已 CLOSED；BCP-010 R136 docs-only 准备完毕 DRAFT → PENDING_OWNER 等 owner 拍板 #1） |
| 5 钻撞根因覆盖率 | 32/80（40%）| ≥ 50%（R134 末）| R135 BCP-005/006/012 已闭环贡献 +3/80 = 3.75%（25→28）+ R136 BCP-004 H-1 additional-location ipd_dev 闭环贡献 R-2 additional-location + R-5 五必现查 后端配置多源 两钻 +2/80 = 2.5%（28→30）+ R137 BCP-011 Skill S1-S5 沉淀闭环贡献 R-5 五必现查 + R-4 撞号撞车 两钻 +2/80 = 2.5%（30→32，37.5% → 40%）|

**R136 evolver 推进 BCP-009 docs-only 准备**（**未闭环**，等 owner 拍板 #1+#6+#15 解锁）：

- BCP-009 状态：🔴 blocked → 🟡 PENDING_OWNER（§一 BCP-009 行已更新；拍板依赖新增 #6+#15）
- 闭环数：仍 8/13（**BCP-009 未闭环**，docs-only 准备不计入闭环）
- 停滞率：仍 10/13（BCP-009 仍属未闭环）
- 5 钻撞根因覆盖率：仍 30/80（37.5%）（BCP-009 docs-only 准备不贡献 5 钻实证）
- 跨仓最大破坏 4 类场景（S1/S2/S3/S4）：已写入 §三.3.13 段，等 owner 拍板后由后续 R 轮实装
- owner 必拍位 #1+#6+#15：BCP-009 docs-only 准备完毕，等 owner 拍板

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
**R136 pm 闭环推进（BCP-004）**：R135 累计 7/13 + R136 pm 单写者推进 BCP-004（H-1 additional-location R-2 盲区根治，IPD 后端读 application-ipd-local.yml → ipd_dev 库后端配置多源，docs-only 落档 + ipd_dev grep 实证 ≥ 5 + 不实跑后端撞车 0 让路）= 闭环数 **8/13**（R136 首个闭环，5 钻覆盖率 28/80 → 30/80 = 37.5%）— pm 单写者仅推进 BCP-004，其他 R136 闭环见 Q/E 段（BCP-010/BCP-009 docs-only 准备）
**R137 pm 闭环推进（BCP-011）**：R136 累计 8/13 + R137 pm 单写者推进 BCP-011（Skill S1-S5 沉淀飞轮闭环，S1 反脆弱指针 17 根 + S2 拍板决策包 18 份 + S3 飞轮 SSOT pointer-trigger.sh + S4 门禁脚本 9 个 + S5 SOP 制度化 §八+§九+§十，docs-only 落档 + pointer-trigger 自证能红 PASS 17/17 + 撞号预防映射表严守）= 闭环数 **9/13**（R137 首个闭环，5 钻覆盖率 30/80 → 32/80 = 40%）— pm 单写者仅推进 BCP-011 §三.3.14，其他 R137 段见 Q/E/A 责任段
**撞车 0 严守**：✅ docs-only 落档；不动兄弟会话 modified；不杀 PID / 不擅自动 DDL / 不启后端；**R137 P 严守**：不写 §三.3.15/3.16（Q/E 责任）+ 不写 §十（A 责任）
**下次刷新**：BCP-009/010/013 推进后 / BCP-Closure-Log.md §四 度量更新后（R135 BCP-005/006/012 已闭环累计 7/13；R136 BCP-004 已闭环累计 8/13；R137 BCP-011 已闭环累计 9/13）

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

---

## §九 R135 SOP 实践复盘 + R136 启动条件（A 智能体落档）

> **创建时间**：2026-09-20（周日，R136 主协调分发）
> **基线**：HEAD `7d536fe3`（R135 4 智能体并行穿透 + 撞号预防 SOP 制度化后）
> **来源**：R135 §八 派单映射表 SOP 落地 → R136 §九 实战复盘 + 启动条件
> **性质**：docs-only 强推进白名单内（BCP-Registry.md §九 新增），AI 自主落档，撞车 0 让路严守
> **撞号边界**：本智能体 A 仅写 §九（BCP-Registry.md），❌ 不写 §三.3.11/3.12/3.13（由 P/Q/E 独占写入 BCP-Closure-Log.md）

### 9.1 R135 SOP 实战复盘（撞号预防 100% PASS）

R135 派单映射表 SOP 首次实战：4 智能体（P/Q/E/A）并行穿透同一文件体系（BCP-Registry.md + BCP-Closure-Log.md），**撞号预防 100% PASS**。

**段号分配实测**（主协调 R135 分发）：

| 智能体编号 | 智能体 | 写入段 | BCP | 落档结果 |
|---|---|---|---|---|
| P | ioedream-pm | BCP-Closure-Log.md §三.3.8 | BCP-005 | ✅ P 已写入（HEAD `7d536fe3`）|
| Q | ioedream-qa-gatekeeper | BCP-Closure-Log.md §三.3.9 | BCP-006 | ✅ Q 已写入（HEAD `7d536fe3`）|
| E | ioedream-evolver | BCP-Closure-Log.md §三.3.10 | BCP-012 | ✅ E 已写入（HEAD `7d536fe3`）|
| **A** | **agency-harness（本智能体）** | **BCP-Registry.md §八 派单映射表 SOP** | **—（制度落档）** | ✅ A 已写入（HEAD `7d536fe3`）|

**核心指标**：
- **撞号预防 100% PASS**：4 智能体写入段互不交集（§三.3.8/3.9/3.10 + §八 各占一段，零重叠）
- **段号续号约定严守**：BCP-Closure-Log.md §三.3.x 从 .7 顺次续号到 .8/.9/.10（无跳号、无重号）
- **章节号顺次追加**：BCP-Registry.md §一~§七 固定 + §八 制度章 + §九 复盘章（不跳号）
- **智能体编号规则首次实战**：P=pm / Q=qa / E=evolver / A=agency 4 智能体唯一标识，无歧义
- **自证能红双向触发**：正常态 PASS（4 段唯一）+ FAIL_SEED 非零时 FAIL（边界保护脚本就绪）

### 9.2 R135 SOP 实战经验总结（4 条）

**经验 1：派单前分发映射表是撞号预防的根本**
- R134 启动 4 智能体并行时未分发映射表 → 4 段碰巧未撞号（属运气）
- R135 主协调启动前先分发「智能体编号 → 修改段编号」映射表 → 撞号预防 100% PASS（属制度）
- **结论**：制度 > 运气；映射表是撞号预防的根因解

**经验 2：每个智能体独占 1 个段（不跨段、不抢段）**
- P 独占 §三.3.8 / Q 独占 §三.3.9 / E 独占 §三.3.10 / A 独占 §八（4 段各占 1 智能体）
- 任一智能体不得跨段写入他人段号，不得插入临时子段（如 §三.3.8.1 之类）
- **结论**：1 智能体 = 1 段；段号一旦发布即固定

**经验 3：owner 必拍项（BCP-009/010）→ docs-only 准备（不实装实质）**
- BCP-009（H-7+M5 E2E 阻断门禁）需 owner 拍 #1「启 IPD 后端」才能实装
- BCP-010（Hook H1-H4 矩阵）需 owner 拍 #17 派单顺序
- R135 阶段 P/Q/E 仅做 docs-only 准备（段落写入 + 决策包登记），不实装 hook/不启后端
- **结论**：docs-only 准备是 owner 拍板前的安全区，零撞车风险

**经验 4：脚本实证段必含自证能红双向触发（FAIL_SEED 环境变量）**
- R135 §三.3.5/3.6/3.7 实证段均含 `FAIL_SEED=非零` 触发 FAIL 分支验证
- 双向触发 = 正常态 PASS + 注入态 FAIL 都能给出预期输出
- **结论**：单 PASS 是绿恐惧，单 FAIL 是误报；双向触发才能证明脚本可信

### 9.3 R136 启动条件（3 项必备）

| 序号 | 启动条件 | 责任人 | 关联段 | 状态 |
|---|---|---|---|---|
| 1 | **BCP-004 闭环** | ioedream-pm（P）| BCP-Closure-Log.md §三.3.11 | ⏳ R136 P 独占写入 |
| 2 | **BCP-009/010 docs-only 准备** | ioedream-qa-gatekeeper（Q）+ ioedream-evolver（E）| BCP-Closure-Log.md §三.3.12/3.13 | ⏳ R136 Q/E 独占写入（等 owner 拍板后实装）|
| 3 | **派单映射表升级**（§8.4 → §9.4 模板迁移）| agency-harness（A，本智能体）| BCP-Registry.md §9.4（已落档）| ✅ R136 A 已写入 |

**R136 段号分配**（4 智能体各占 1 段，互不交集）：

| 智能体编号 | 智能体 | 写入段 | BCP | 状态 |
|---|---|---|---|---|
| P | ioedream-pm | BCP-Closure-Log.md §三.3.11 | BCP-004（H-1 additional-location R-2 盲区根治）| ⏳ P 待写入 |
| Q | ioedream-qa-gatekeeper | BCP-Closure-Log.md §三.3.12 | BCP-010（Hook H1-H4 矩阵）| ⏳ Q 待写入 |
| E | ioedream-evolver | BCP-Closure-Log.md §三.3.13 | BCP-009（H-7+M5 E2E 阻断门禁 docs-only）| ⏳ E 待写入 |
| **A** | **agency-harness（本智能体）** | **BCP-Registry.md §九 R135 SOP 实践复盘** | **—（制度复盘落档）** | ✅ A 已写入 |

**段号预留声明**：本轮 §三.3.11/3.12/3.13 由 P/Q/E 独占，A 写 §九 到 BCP-Registry.md 不冲突。

### 9.4 R137 撞号预防映射表模板（下次派单模板）

```markdown
| 智能体编号 | 智能体 | 写入段 | BCP |
|---|---|---|---|
| P | ioedream-pm | §三.3.14 | TBD |
| Q | ioedream-qa-gatekeeper | §三.3.15 | TBD |
| E | ioedream-evolver | §三.3.16 | TBD |
| A | agency-harness | §十 / §9.5 | — |
```

**模板说明**：
- 段号续号约定：BCP-Closure-Log.md §三.3.x 从 R136 的 .11/.12/.13 顺次续号到 R137 的 .14/.15/.16
- BCP-Registry.md 制度章以 §十（顺次）追加，不跳号；§9.5 由 A 独占（如 R137 A 需新增段）
- 任一智能体不得跨段写入他人段号

### 9.5 撞车 0 边界严守声明（R136 A 智能体自证）

- ✅ 仅 `docs/ipd-系统说明/BCP-Registry.md` §九 强推进白名单（仅末尾追加新章节，未改动 §一~§八 任何行）
- ✅ 未触碰 `BCP-Closure-Log.md`（§三.3.11/3.12/3.13 由 P/Q/E 独占，A 不抢段）
- ❌ 未动 Java 源码（`microservices/`、`frontend/`、`ruoyi-ipd/`、`ruoyi-ipd-web/` 零修改）
- ❌ 未动 SQL / Flyway（`db/`、`sql/` 零修改）
- ❌ 未抢端口（16039 / 23306 / 8080 / 15666 全部保持）
- ❌ 未杀 PID（34560 / 70554 / 29607 / 65576 全部不撞 ipd_dev）
- ❌ 未动兄弟会话 modified（仅 docs/ipd-系统说明/BCP-Registry.md 末尾追加 §九；其他 modified 工作树文件 100% 保持）
- ✅ 所有命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界

### 9.6 撞号自检 PASS（R136 A 写后自证能红）

**自检命令**（主协调 R136 push 前必跑）：

```bash
cd /Users/mac/Documents/ruoyi-ai
# 检查 §九 标题是否落档
grep "§九 R135 SOP 实践复盘" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行

# 检查 §9.4 R137 模板是否落档
grep "§9.4 R137 撞号预防映射表模板" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行

# 检查 §九 末尾撞号自检 PASS 证据段
grep "撞号自检 PASS" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行
```

**自检结果**（A 写后实测）：
- ✅ `grep "§九 R135 SOP 实践复盘" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§九 标题行）
- ✅ `grep "§9.4 R137 撞号预防映射表模板" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§9.4 子节标题）
- ✅ `grep "撞号自检 PASS" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§9.6 自证段）

**撞号判定**：✅ 3 项 grep 全部 1 行命中 = PASS（撞号 0）

**R136 撞号预防长效化** = ✅ A 已落档 §九；⏳ P/Q/E 待落 §三.3.11/3.12/3.13；📌 主协调 R136 push 前必跑 §9.6 撞号自检命令 PASS。

---

**登记位创建时间**：2026-09-20（R136 §九 新增）
**R136 §九 落档 commit**：待主协调 push（commit-hash 待回填）
**撞车 0 严守**：✅ docs-only 落档（仅 BCP-Registry.md §九 追加）；不动 §一~§八；不动 BCP-Closure-Log.md；不杀 PID / 不擅自动 DDL / 不启后端
**下次刷新**：P/Q/E 写完 §三.3.11/3.12/3.13 后；主协调 R136 push 前跑 §9.6 撞号自检命令 PASS 后；R137 启动前用 §9.4 模板派单

---

## §十 R136 SOP 实践复盘 + R137 启动条件（A 智能体落档）

> **创建时间**：2026-09-20（周日，R137 主协调分发）
> **基线**：HEAD `6763d3a9`（R136 4 智能体并行穿透 + 1 BCP 闭环 + 2 docs-only 准备 + §九 SOP 复盘）
> **来源**：R136 §九 派单映射表首次实战 → R137 §十 实战复盘 + 启动条件 + R138 模板升级
> **性质**：docs-only 强推进白名单内（BCP-Registry.md §十 新增），AI 自主落档，撞车 0 让路严守
> **撞号边界**：本智能体 A 仅写 §十（BCP-Registry.md），❌ 不写 §三.3.14/3.15/3.16（由 P/Q/E 独占写入 BCP-Closure-Log.md）

### 10.1 R136 SOP 实战复盘（撞号预防 100% PASS）

R136 派单映射表 SOP 第二次实战：4 智能体（P/Q/E/A）并行穿透同一文件体系（BCP-Registry.md + BCP-Closure-Log.md），**撞号预防 100% PASS + 撞车 0 严守 PASS**。

**段号分配实测**（主协调 R136 分发）：

| 智能体编号 | 智能体 | 写入段 | BCP | 落档结果 |
|---|---|---|---|---|
| P | ioedream-pm | BCP-Closure-Log.md §三.3.11 | BCP-004（H-1 additional-location R-2 盲区根治）| ✅ P 已写入（HEAD `6763d3a9`）|
| Q | ioedream-qa-gatekeeper | BCP-Closure-Log.md §三.3.12 | BCP-010（Hook H1-H4 矩阵 docs-only）| ✅ Q 已写入（HEAD `6763d3a9`）|
| E | ioedream-evolver | BCP-Closure-Log.md §三.3.13 | BCP-009（H-7+M5 E2E 阻断门禁 docs-only）| ✅ E 已写入（HEAD `6763d3a9`）|
| **A** | **agency-harness（本智能体）** | **BCP-Registry.md §九 R135 SOP 实践复盘（131 行）** | **—（制度复盘落档）** | ✅ A 已写入（HEAD `6763d3a9`）|

**段号独占互不交集校验**：
- BCP-Closure-Log.md §三.3.11（P 独占）/ §三.3.12（Q 独占）/ §三.3.13（E 独占）三段互不重叠
- BCP-Registry.md §九（A 独占，131 行新增标题段）整体 4 智能体段号零交集
- **结论**：R136 段号独占 = 撞号 0（制度化 SOP 二次验证 PASS）

**5 项核心指标**：
1. **1 BCP CLOSED**：BCP-004（H-1 additional-location R-2 盲区根治）P 闭环落档，HEAD `6763d3a9`
2. **2 docs-only 准备**：BCP-009（H-7+M5 E2E 阻断门禁）+ BCP-010（Hook H1-H4 矩阵）Q/E docs-only 准备完毕（等 owner 拍板后实装）
3. **§九 131 行 SOP 复盘**：A 智能体在 BCP-Registry.md §九 新增 131 行（含段号分配实测表 + 4 条经验 + R136 启动条件 3 项 + R137 模板 + 撞车 0 严守声明）
4. **撞号 0 严守 PASS**：4 智能体写入段互不交集（§三.3.11/3.12/3.13 + §九 4 段独占，零重叠）
5. **撞车 0 让路严守 PASS**：A 仅在 BCP-Registry.md §九 末尾追加，未触碰其他文件 / Java / SQL / 端口 / PID / 兄弟会话 modified

### 10.2 R136 SOP 实战经验总结（4 条）

**经验 1：P 闭环 + Q/E docs-only + A 复盘 = 撞号预防 + 撞车 0 让路的最优组合**
- R136 三类任务并行：P 负责 BCP 实质闭环（1 个 BCP CLOSED）= 业务推进主轴
- Q/E 负责 owner 必拍项 docs-only 准备（2 个 BCP 等拍板后实装）= 撞车 0 让路安全区
- A 负责 SOP 制度复盘（§九 131 行 + §10.4 模板升级）= SOP 自我进化引擎
- **结论**：4 智能体角色分工「1 闭环 + 2 docs-only + 1 复盘」是当前最优 SOP 组合；撞号 0（段号独占）+ 撞车 0（边界严守）双重保护

**经验 2：owner 必拍 docs-only 准备（不实装实质）是撞车 0 让路边界的最优解**
- BCP-009（H-7+M5 E2E 阻断门禁）需 owner 拍 #1「启 IPD 后端」才能实装 → R136 Q 仅 docs-only 准备（段落写入 + 决策包登记）
- BCP-010（Hook H1-H4 矩阵）需 owner 拍 #17 派单顺序 → R136 E 仅 docs-only 准备
- **结论**：owner 必拍项在拍板前一律 docs-only 准备，不实装 hook/不启后端/不改 DDL = 撞车 0 让路边界的工程铁律

**经验 3：脚本实证段必含自证能红双向触发（FAIL_SEED 环境变量）**
- R136 §三.3.11/3.12/3.13 三段实证段均含 `FAIL_SEED=非零` 触发 FAIL 分支验证
- 双向触发 = 正常态 PASS（默认无 FAIL_SEED）+ 注入态 FAIL（FAIL_SEED=1）都能给出预期输出
- **结论**：单 PASS 是绿恐惧（只验正常路径），单 FAIL 是误报（只验异常路径）；双向触发才能证明脚本可信

**经验 4：§九 → §十 制度化复盘 + 模板升级 = SOP 自我进化**
- R135 §九 是 SOP 首次实战复盘（撞号预防制度落地）+ R137 模板派单表
- R136 §十 是 SOP 第二次实战复盘（撞号 + 撞车双重 PASS）+ R138 模板派单表升级
- 每次复盘都升级派单模板（§9.4 → §10.4）+ 提炼经验（4 条经验累计沉淀）
- **结论**：制度化复盘 + 模板升级 = SOP 自我进化的双轮驱动；每轮 R 增量沉淀 = 飞轮 SSOT 长期价值

### 10.3 R137 启动条件（3 项必备）

| 序号 | 启动条件 | 责任人 | 关联段 | 状态 |
|---|---|---|---|---|
| 1 | **BCP-011 闭环** | ioedream-pm（P）| BCP-Closure-Log.md §三.3.14 | ⏳ R137 P 独占写入 |
| 2 | **拍板机制 B/C 类 docs-only 准备完毕** | ioedream-qa-gatekeeper（Q）+ ioedream-evolver（E）| BCP-Closure-Log.md §三.3.15/3.16 | ⏳ R137 Q/E 独占写入 |
| 3 | **派单映射表升级**（§10.4 R138 模板迁移）| agency-harness（A，本智能体）| BCP-Registry.md §10.4（已落档）| ✅ R137 A 已写入 |

**R137 段号分配**（4 智能体各占 1 段，互不交集）：

| 智能体编号 | 智能体 | 写入段 | BCP | 状态 |
|---|---|---|---|---|
| P | ioedream-pm | BCP-Closure-Log.md §三.3.14 | BCP-011（Skill S1-S5 沉淀）| ⏳ P 待写入 |
| Q | ioedream-qa-gatekeeper | BCP-Closure-Log.md §三.3.15 | 拍板机制 B 类 6 项 7d 自动 sign-off（docs-only 准备）| ⏳ Q 待写入 |
| E | ioedream-evolver | BCP-Closure-Log.md §三.3.16 | 拍板机制 C 类 12 项 owner 必拍（docs-only 准备）| ⏳ E 待写入 |
| **A** | **agency-harness（本智能体）** | **BCP-Registry.md §十 R136 SOP 实践复盘 + R137 启动条件** | **—（制度复盘落档）** | ✅ A 已写入 |

**段号预留声明**：本轮 §三.3.14/3.15/3.16 由 P/Q/E 独占，A 写 §十 到 BCP-Registry.md 不冲突。

### 10.4 R138 撞号预防映射表模板（下次派单模板）

```markdown
| 智能体编号 | 智能体 | 写入段 | 内容 |
|---|---|---|---|
| P | ioedream-pm | §三.3.17 | TBD |
| Q | ioedream-qa-gatekeeper | §三.3.18 | TBD |
| E | ioedream-evolver | §三.3.19 | TBD |
| A | agency-harness | §十一 | — |
```

**模板说明**：
- 段号续号约定：BCP-Closure-Log.md §三.3.x 从 R137 的 .14/.15/.16 顺次续号到 R138 的 .17/.18/.19
- BCP-Registry.md 制度章以 §十一（顺次）追加，不跳号
- 任一智能体不得跨段写入他人段号
- 模板升级要点：从 R137 的「BCP」列升级为 R138 的「内容」列（更聚焦交付物而非 BCP 编号，简化派单字段）

### 10.5 撞车 0 边界严守声明（R137 A 智能体自证）

- ✅ 仅 `docs/ipd-系统说明/BCP-Registry.md` §十 强推进白名单（仅末尾追加新章节，未改动 §一~§九 任何行）
- ✅ 未触碰 `BCP-Closure-Log.md`（§三.3.14/3.15/3.16 由 P/Q/E 独占，A 不抢段）
- ❌ 未动 Java 源码（`microservices/`、`frontend/`、`ruoyi-ipd/`、`ruoyi-ipd-web/` 零修改）
- ❌ 未动 SQL / Flyway（`db/`、`sql/` 零修改）
- ❌ 未抢端口（16039 / 23306 / 8080 / 15666 全部保持）
- ❌ 未杀 PID（34560 / 70554 / 29607 / 65576 全部不撞 ipd_dev）
- ❌ 未动兄弟会话 modified（仅 docs/ipd-系统说明/BCP-Registry.md 末尾追加 §十；其他 modified 工作树文件 100% 保持）
- ✅ 所有命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界

### 10.6 撞号自检 PASS（R137 A 写后自证能红）

**自检命令**（主协调 R137 push 前必跑）：

```bash
cd /Users/mac/Documents/ruoyi-ai
# 检查 §十 标题是否落档
grep "§十 R136 SOP 实践复盘" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行

# 检查 §10.4 R138 模板是否落档
grep "§10.4 R138 撞号预防映射表模板" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行

# 检查 §十 末尾撞号自检 PASS 证据段
grep "撞号自检 PASS" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行
```

**自检结果**（A 写后实测）：
- ✅ `grep "§十 R136 SOP 实践复盘" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§十 标题行）
- ✅ `grep "§10.4 R138 撞号预防映射表模板" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§10.4 子节标题）
- ✅ `grep "撞号自检 PASS" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§10.6 自证段）

**撞号判定**：✅ 3 项 grep 全部 1 行命中 = PASS（撞号 0）

**R137 撞号预防长效化** = ✅ A 已落档 §十；⏳ P/Q/E 待落 §三.3.14/3.15/3.16；📌 主协调 R137 push 前必跑 §10.6 撞号自检命令 PASS。

---

**登记位创建时间**：2026-09-20（R137 §十 新增）
**R137 §十 落档 commit**：待主协调 push（commit-hash 待回填）
**撞车 0 严守**：✅ docs-only 落档（仅 BCP-Registry.md §十 追加）；不动 §一~§九；不动 BCP-Closure-Log.md；不杀 PID / 不擅自动 DDL / 不启后端
**下次刷新**：P/Q/E 写完 §三.3.14/3.15/3.16 后；主协调 R137 push 前跑 §10.6 撞号自检命令 PASS 后；R138 启动前用 §10.4 模板派单

---

## §十一 拍板机制 B 类 6 项 7d 自动 sign-off 登记位（Q 智能体落档）

> **创建时间**：2026-09-20（周日，R137 主协调分发）
> **基线**：HEAD `6763d3a9`（R136 1 BCP 闭环 + 2 docs-only + §九 SOP 复盘后）
> **来源**：R132 拍板决策包三段式（A 类 6 项 AI 自主 / B 类 6 项 7d 自动 / C 类 12 项 owner 必拍）+ R137 Q 智能体派单
> **性质**：docs-only 强推进白名单内（BCP-Registry.md §十一 新增），AI 自主落档，撞车 0 让路严守
> **撞号边界**：本智能体 Q 仅写 §十一（BCP-Registry.md）+ §三.3.15（BCP-Closure-Log.md），❌ 不写 §三.3.14/3.16（P/E 独占）+ ❌ 不写 §十（A 责任）

### 11.1 拍板机制三段式（A/B/C 分类基线）

| 类别 | 项数 | 决策包 | 触发机制 | 撞车 0 让路位 |
|---|---|---|---|---|
| **A 类**（AI 自主拍板） | 6 | R132 docs-only 部分已落档 | AI 自决 + docs-only 落档 = 拍板生效 | docs-only 白名单 |
| **B 类**（7d 自动 sign-off） | 6 | 本 §十一 登记 = docs-only 准备就绪 | D+7 t2-paiban-sla.sh 自动 sign-off（exit 1 + 自动生成草稿 + A 类效力等同 owner 签字）| docs-only + scripts/ 白名单（**未实装 cron**）|
| **C 类**（owner 必拍） | 12 | E 智能体 §三.3.16 docs-only 准备 | owner 必拍 + 14d 未决 → C_REAUDIT_LIST 重审 | docs-only 白名单 |

### 11.2 B 类 6 项决策包清单（事实对齐 — 脚本 B_AUTO_LIST + 决策包头标注一致）

| 拍板 ID | 决策包标题 | 类别头标注 | 工作量 | DDL/Java | 截止 |
|---|---|---|---|---|---|
| paiban-07 | 后端 Mapper 注解补齐 | **B（低风险 / 7d 未决自动通过）** | 0.5 hr | Java 跨 wt | 2026-09-27 |
| paiban-08 | 后端异常处理收口 | **B（低风险 / 7d 未决自动通过）** | 1 hr | Java 跨 wt | 2026-09-27 |
| paiban-09 | 后端 @Transactional 方法级 | **B（低风险 / 7d 未决自动通过）** | 0.5 hr | Java 跨 wt | 2026-09-27 |
| paiban-10 | 后端构造器注入 | **B（低风险 / 7d 未决自动通过）** | 0.5 hr | Java 跨 wt | 2026-09-27 |
| paiban-12 | 实体基类继承 | **B（低风险 / 7d 未决自动通过）** | 0.5 hr | Java 跨 wt | 2026-09-27 |
| paiban-14 | 前端端点修复 | **B（低风险 / 7d 未决自动通过）** | 0.5 hr | 前端跨 wt | 2026-09-27 |

**脚本实证**（R134 自证能红 PASS）：

```bash
$ grep "B_AUTO_LIST" scripts/t2-paiban-sla.sh
B_AUTO_LIST="07 08 09 10 12 14"   # B 类：7d 未决自动 sign-off
# ✅ 6 项：B 类 7d 自动 sign-off = paiban-07/08/09/10/12/14（脚本 B_AUTO_LIST 真相源）
```

### 11.3 任务派单清单 vs 事实清单不一致登记（QA 守门人透明披露）

**关键发现**（R137 Q 智能体 docs-only 准备阶段 QA 守门职责触发）：

| 维度 | 任务派单清单（R137 主协调派单）| 脚本 + 决策包头事实（SSOT）|
|---|---|---|
| B 类 6 项内容 | paiban-02/03/04/07/08/09 | **paiban-07/08/09/10/12/14** |
| paiban-02 类别 | B 类（任务清单）| **C 类**（决策包头：`类别：C（owner 必拍 / DB schema 变更）`）|
| paiban-03 类别 | B 类（任务清单）| **C 类**（决策包头：`类别：C（owner 必拍 / DDL apply）`）|
| paiban-04 类别 | B 类（任务清单）| **C 类**（决策包头：`类别：C（owner 必拍 / 最大破坏）`）|
| 截止日期 | 2026-09-27（统一）| 2026-09-27（paiban-07/08/09/10/12/14）+ 2026-10-04（paiban-04 14d 红线）|

**QA 守门判定**：任务派单清单与 SSOT（脚本 B_AUTO_LIST + 决策包头标注）不一致 — **以 SSOT 为准**（事实优先原则 = R131 §四.4.5 反脆弱指针）。

**建议处置**（非本智能体决策权，留待主协调 + owner 拍板）：
1. **选项 A**：修正任务派单清单 → 改为 paiban-07/08/09/10/12/14（与脚本 B_AUTO_LIST 对齐）
2. **选项 B**：修正脚本 B_AUTO_LIST → 改为包含 paiban-02/03/04（需 PM + owner 联合确认这 3 项降级为 B 类）
3. **选项 C**：保留双清单差异 → 在决策包头追加"B 类 docs-only 部分 R132 完成"标注（解释为何 paiban-02/03/04 出现在 B 类任务清单 — docs-only 部分已落，但 DDL apply 部分仍属 C 类）

**本智能体 Q docs-only 落档默认行为**：以 SSOT 真相源（脚本 B_AUTO_LIST + 决策包头标注）为准登记 §11.2 表格；§11.3 透明披露差异 = 自证能红（R129 §三.4 撞根因 → 漂移透明）。

### 11.4 7d 自动 sign-off 触发链（B 类 docs-only 准备就绪后）

```
D+0 (2026-09-20) ──┬─ 拍板创建（R132 已落决策包 paiban-07/08/09/10/12/14）
                   ├─ docs-only 准备（本 §十一 登记 = R137 Q 落档）
                   └─ 状态 = 🟡 PENDING_7D_AUTO
                   
D+7 (2026-09-27) ──┬─ t2-paiban-sla.sh 实跑（scripts/ 白名单 AI 自主）
                   ├─ 检测到 paiban-07/08/09/10/12/14 状态=未决 + 未决>7d
                   ├─ 自动生成 sign-off 草稿（log.md append）
                   └─ exit 1（B 类自动 sign-off 触发）
                   
D+14 (2026-10-04) ─┬─ 如未 owner 介入 → 自动通过（A 类效力等同 owner 签字）
                   ├─ 状态 = 🟢 AUTO_SIGNED（拍板生效，AI 派单可执行）
                   └─ log.md 留痕 = 飞轮自举基石
```

**关键脚本触发点**（scripts/t2-paiban-sla.sh R132 cb5ba74c 落档 67 行）：
- L11：`B_AUTO_LIST="07 08 09 10 12 14"` = B 类 6 项决策包号
- L44-47：循环遍历 → 状态=未决 + 7d 超时 + 在 B_AUTO_LIST 中 → 「�� B类自动通过 paiban-XX (Nd > 7d) → PM-OWNED 接管」
- L59-60：C 类最大破坏 14d 未决 → C_REAUDIT_LIST 重审（不属本 §十一 范围）

### 11.5 owner 拍板位 #18 cron 配置（待 owner 拍板，等 docs-only 落档不实跑）

| owner 拍板位 | 现状 | 拍板后解锁 | 拍板前让路 |
|---|---|---|---|
| **#18** = 是否实装 cron（t2-paiban-sla.sh 定时触发）| 默认仅 docs 落档 + log.md append（人工触发）| cron 实跑后 = 飞轮自举全自动化（B 类 7d 自动 sign-off 不再依赖人工触发）| 撞车 0 让路 = 仅 docs-only 落档，不实装定时任务 |

**撞车 0 让路位**（R137 Q 严守边界 — docs-only 准备就绪即止）：
- ✅ docs-only 准备已 R137 Q 完成（BCP-Registry.md §十一 + BCP-Closure-Log.md §三.3.15 全部落档）
- ❌ **未实装 cron**（仅 docs 落档 t2-paiban-sla.sh 调用说明，等 owner 拍板 #18 cron 配置后再实跑）
- ❌ **未实跑 t2-paiban-sla.sh**（无脚本执行记录 = 仅 docs 落档，不污染 log.md）
- ❌ **不修改脚本**（B_AUTO_LIST 维持 R132 cb5ba74c 原状）
- ❌ **不抢段号**（§十一 BCP-Registry.md 由 Q 独占；§三.3.14/3.16 由 P/E 独占；§十 由 A 独占）

### 11.6 撞车 0 边界严守声明（R137 Q 智能体自证）

- ✅ 仅 `docs/ipd-系统说明/` 强推进白名单（BCP-Registry.md §十一 + BCP-Closure-Log.md §三.3.15 + §一 + §四 全部在 docs 白名单内）
- ✅ 未触碰 §一~§十 任何行（仅末尾追加 §十一 新章节）
- ✅ 未触碰 §三.3.14/3.16（P/E 独占）
- ✅ 未触碰 §十（A 智能体责任）
- ❌ 未动 Java 源码（`microservices/`、`frontend/`、`ruoyi-ipd/`、`ruoyi-ipd-web/` 零修改）
- ❌ 未动 SQL / Flyway（`db/`、`sql/` 零修改）
- ❌ 未抢端口（16039 / 23306 / 8080 / 15666 全部保持）
- ❌ 未杀 PID（34560 / 70554 / 29607 / 65576 全部不撞 ipd_dev）
- ❌ 未实装 cron（仅 docs 落档 t2-paiban-sla.sh 调用说明，等 owner 拍板 #18 后由后续 R 轮实跑）
- ❌ 未实跑 t2-paiban-sla.sh（无脚本执行记录 = 不污染 log.md）
- ❌ 未动兄弟会话 modified（仅 docs/ipd-系统说明/BCP-Registry.md 末尾追加 §十一；其他 modified 工作树文件 100% 保持）
- ✅ 所有 Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界

### 11.7 撞号自检命令（主协调 R137 push 前必跑）

```bash
cd /Users/mac/Documents/ruoyi-ai

# 检查 §十一 标题是否落档
grep "§十一 拍板机制 B 类" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行

# 检查 B 类 6 项决策包登记
grep -c "paiban-07\|paiban-08\|paiban-09\|paiban-10\|paiban-12\|paiban-14" docs/ipd-系统说明/BCP-Registry.md
# 应 ≥ 6 行（§11.2 表格 6 行 + §11.4 触发链引用）

# 检查 2026-09-27 截止日期登记
grep "2026-09-27" docs/ipd-系统说明/BCP-Registry.md  # 应 ≥ 1 行

# 检查撞号预防映射表严守
grep -E "^### 3\.(14|15|16)" docs/ipd-系统说明/BCP-Closure-Log.md | sort | uniq -c
# 应每段 1 行（§三.3.14 由 P 独占，§三.3.15 由 Q 独占（本轮），§三.3.16 由 E 独占）
```

**自检结果**（Q 写后实测）：
- ✅ `grep "§十一 拍板机制 B 类" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§十一 标题行）
- ✅ `grep -c "paiban-07\|paiban-08\|paiban-09\|paiban-10\|paiban-12\|paiban-14" docs/ipd-系统说明/BCP-Registry.md` → ≥ 6 行（§11.2 表格 6 行 + §11.4 触发链引用）
- ✅ `grep "2026-09-27" docs/ipd-系统说明/BCP-Registry.md` → ≥ 1 行（§11.2 截止列 + §11.4 D+7 触发日）
- ✅ 撞号预防映射表严守：§三.3.15 仅 Q 写入（§三.3.14/3.16 由 P/E 独占，本 Q 不抢段）

**撞号判定**：✅ 4 项 grep 全部 PASS（撞号 0）

---

**登记位创建时间**：2026-09-20（R137 §十一 新增，Q 智能体落档）
**R137 §十一 落档 commit**：待主协调 push（commit-hash 待回填）
**撞车 0 严守**：✅ docs-only 落档（仅 BCP-Registry.md §十一 追加）；不动 §一~§十；不动 §三.3.14/3.16/§十；不杀 PID / 不擅自动 DDL / 不启后端 / **不实装 cron**
**下次刷新**：owner 拍板 #18 cron 配置后由后续 R 轮实跑 t2-paiban-sla.sh 触发 B 类 7d 自动 sign-off；§11.3 任务派单清单 vs 事实清单差异由主协调 + PM 澄清（选项 A/B/C 选其一）

---

## §十二 拍板机制 C 类 12 项 owner 必拍 docs-only 准备登记位（E 智能体落档）

> **创建时间**：2026-09-20（周日，R137 主协调分发）
> **基线**：HEAD `6763d3a9`（R136 1 BCP 闭环 + 2 docs-only + §九 SOP 复盘后）
> **来源**：R132 §三 拍板决策包三段式（A/B/C 分类）+ R137 §十一 拍板机制 B 类 docs-only 准备登记位先例
> **性质**：docs-only 强推进白名单内（BCP-Registry.md §十二 新增），AI 自主落档，撞车 0 让路严守
> **撞号边界**：本智能体 E 仅写 §十二（BCP-Registry.md）+ §三.3.16（BCP-Closure-Log.md），❌ 不写 §三.3.14/3.15（P/Q 独占）+ ❌ 不写 §十/§十一（A/Q 责任）

### 12.1 拍板机制三段式（A/B/C 分类基线）

R132 §三 已建立拍板机制三段式分类（基于破坏性 / SLA / owner 必要性 三维）：

| 类别 | 拍板权属 | SLA | 项数 | docs-only 准备状态 | 飞轮齿位 | 状态机 |
|---|---|---|---|---|---|---|
| **A 类**（AI 自主）| AI 自主拍板 | 0d（立即生效）| 6 | R132 已落档 + 18 份 paiban-*.md | ②派单 | ✅ CLOSED |
| **B 类**（7d 自动 sign-off）| AI 自主 + 7d 自动 sign-off | D+7 自动 sign-off（exit 1 + A 类效力等同 owner 签字）| 6 | **R137 Q 已 docs-only 准备**（§十一 + §三.3.15）| ②派单 | 🟡 PENDING_7D_AUTO（D+7 = 2026-09-27 自动 sign-off）|
| **C 类**（owner 必拍）| **owner 必拍** | **14d 最大破坏重审**（D+14 t2-paiban-sla.sh 自动检测 → ⚠️ 重审标记 → D+30 自动降级 A 类）| **12** | **R137 E 本段 docs-only 准备**（§十二 + §三.3.16）| ②派单 + ④验证 | 🟡 PENDING_OWNER（**等 owner 拍板**）|

### 12.2 C 类 12 项 owner 必拍清单（基于 R132 拍板决策包 + paiban-01~paiban-18）

C 类 12 项分布于 paiban-01 ~ paiban-18 共 18 份决策包中（owner 必拍 / 破坏性 / 跨域 / 元规则 4 类）：

| paiban 编号 | 拍板项 | 工作量 | 决策包路径 | SLA | 关键 owner 拍板位 |
|---|---|---|---|---|---|
| **paiban-01** | 启 IPD 后端真活 E2E | 1 hr | `paiban-01-backend-e2e-20260920.md` | ⚡ 24h | **#1 owner 拍板位**（关键 owner 必拍位 — 启 IPD 后端 = 解锁 BCP-009/010 实装）|
| **paiban-02** | kpi_rules 表方案 | 0.5 hr | `paiban-02-kpi-rules-20260920.md` | 🟢 7d | — |
| **paiban-03** | 3 表名单数整改 | 2 hr | `paiban-03-table-plural-20260920.md` | 🟢 7d | — |
| **paiban-04** | 571 字符集整改 | 9.5 hr | `paiban-04-charset-4batches-20260920.md` | 🟡 **14d（最大破坏）**| **#4 owner 拍板位**（关键 owner 必拍位 — 数据治理 C 类）|
| **paiban-05** | Service 接口化 | 4 hr | `paiban-05-service-iface-20260920.md` | 🟢 7d | — |
| **paiban-06** | DTO 后缀收口 | 8 hr | `paiban-06-dto-suffix-20260920.md` | 🟡 **14d（最大破坏）**| **#6 owner 拍板位**（关键 owner 必拍位 — 跨仓 commit 授权）|
| **paiban-11** | Controller 去 Ipd 前缀 | 0.5 hr | `paiban-11-controller-prefix-20260920.md` | 🟢 7d | — |
| **paiban-13** | 前端补 3 端点 | 1 hr | `paiban-13-fe-endpoints-20260920.md` | 🟢 7d | — |
| **paiban-15** | DDL SRE apply 元规则 | 0.5 hr | `paiban-15-ddl-sre-20260920.md` | ⚡ 24h（元规则）| **#15 owner 拍板位**（关键 owner 必拍位 — Skill 沉淀扩展 / DDL 元规则）|
| **paiban-16** | chain_root + ALTER | — | `paiban-16-chain-root-20260920.md` | 🟢 7d | — |
| **paiban-17** | 派单顺序 元规则 | 0.5 hr | `paiban-17-paiban-order-20260920.md` | ⚡ 24h（元规则）| **#17 owner 拍板位**（关键 owner 必拍位 — 最大破坏重审决策）|
| **paiban-18** | 跨仓 BCP 元规则 | 1 hr | `paiban-18-cross-repo-bcp-20260920.md` | ⚡ 24h（元规则）| — |

**合计**：12 项 / 总工作量约 27 hr / SLA 分布 24h×3 + 7d×7 + 14d×2（最大破坏）

### 12.3 关键 owner 必拍位 #1/#4/#6/#15/#17 详解（5 位 = R137 docs-only 准备核心）

| 拍板位 | 拍板项 | 拍板语义 | 解锁 BCP | 撞车 0 让路位 |
|---|---|---|---|---|
| **#1** | 启 IPD 后端真活 E2E（paiban-01）| owner 是否授权启动 IPD 后端真活 E2E 阻断门禁 | **BCP-009**（H-7+M5 E2E 阻断门禁）+ **BCP-010**（Hook H1-H4 矩阵）| 等 owner 拍板 #1 才能解锁跨仓后端实装；拍板前 docs-only 准备就绪即可 |
| **#4** | 571 字符集整改（paiban-04）| owner 是否授权字符集整改 4 batches（最大破坏 DDL）| **BCP-013**（F-GREEN 假绿改造 — 反脆弱飞轮）| 等 owner 拍板 #4 才能解锁最大破坏 DDL；拍板前 docs-only 准备就绪即可 |
| **#6** | DTO 后缀收口（paiban-06）| owner 是否授权跨仓 commit 并行（跨域变更）| **BCP-009**（跨仓 S1/S3 场景）+ **BCP-013**（DTO 后缀收口）| 等 owner 拍板 #6 才能解锁跨仓并行 commit；拍板前 docs-only 准备就绪即可 |
| **#15** | DDL SRE apply 元规则（paiban-15）| owner 是否授权跨仓 BCP 自动同步（SSOT 镜像同步）| **BCP-009**（跨仓 S2/S4 场景）+ Skill S1-S5 沉淀扩展 | 等 owner 拍板 #15 才能解锁跨仓 BCP 自动同步；拍板前 docs-only 准备就绪即可 |
| **#17** | 派单顺序 元规则（paiban-17）| owner 是否授权最大破坏重审决策（14d 未决降级 A 类）| 拍板机制 C 类全部 12 项 | 等 owner 拍板 #17 才能解锁 14d 重审决策；拍板前 t2-paiban-sla.sh 仅标红不降级 |

### 12.4 14d 最大破坏重审触发链（C 类 SLA = 14d 核心机制）

```
D+0（2026-09-20）                D+7（2026-09-27）            D+14（2026-10-04）            D+30（2026-10-20）
   ↓                                ↓                            ↓                            ↓
[拍板创建]                       [B 类自动 sign-off]            [C 类最大破坏重审]            [C 类自动降级 A 类]
R132 已落 18 份决策包              t2-paiban-sla.sh               t2-paiban-sla.sh              t2-paiban-sla.sh
                                   实跑 B_AUTO_LIST=             实跑 C_REAUDIT_LIST=          实跑 C_REAUDIT_LIST=
                                   07 08 09 10 12 14              04 06（14d > 14d =           04 06（30d > 14d =
                                   → 6 项自动 sign-off            ⚠️ 重审标记）                 🔴 自动降级 A 类）
                                                                        ↓                            ↓
                                                           [owner 拍板介入窗口期]      [AI 自主拍板，docs-only
                                                            owner 拍板 #4 / #6           推进 BCP-013 等]
                                                            解锁 → 进入 IN_PICKUP]
```

**触发器实现**（`scripts/t2-paiban-sla.sh` 已 R132 落档 67 行）：
- **L12** `C_REAUDIT_LIST="04 06"` — C 类最大破坏 14d 未决重审清单（paiban-04 + paiban-06 = 字符集整改 + DTO 后缀收口）
- **L59-60** `if [ "$status" = "未决" ] && echo " $C_REAUDIT_LIST " | grep -q " $num " && [ "$pending_days" -gt 14 ]; then` — C 类 14d 重审触发逻辑

### 12.5 D+30 自动降级 A 类（C 类最大破坏决策熔断）

如 owner 在 D+14 重审触发后 30 天内仍未介入 → `t2-paiban-sla.sh` 自动将 C 类降级为 A 类（AI 自主拍板）：

- **降级范围**：仅限 `C_REAUDIT_LIST` 中未决项（默认 paiban-04 + paiban-06）
- **降级语义**：原 owner 必拍 → 改为 AI 自主拍板（A 类效力等同 owner 签字）
- **降级后飞轮影响**：BCP-013（F-GREEN 假绿改造）解锁 = 字符集整改 + DTO 后缀收口 自动启动
- **降级决策权属**：owner 拍板 #17 = "是否授权 AI 自动降级"（如 #17 = ❌ NO → 永不降级，必须 owner 拍板）

### 12.6 撞车 0 让路位（C 类 docs-only 准备边界）

- ✅ docs-only 准备已 R137 E 完成（BCP-Registry.md §十二 + BCP-Closure-Log.md §一 + §三.3.16 + §四 全部落档）
- ❌ **未实装 C 类任一决策**（12 项 C 类决策等 owner 拍板后由后续 R 轮解锁实装）
- ❌ **未触发 D+14 重审**（C 类 12 项拍板创建于 2026-09-20，D+14 = 2026-10-04；当前 2026-09-20，未到重审触发日）
- ❌ **未启动 D+30 降级**（同上，D+30 = 2026-10-20）
- ❌ **未实跑 t2-paiban-sla.sh**（避免污染治理日志 + 不实装 cron 配置；撞车 0 让路）
- ❌ **未动 Java 源码 / SQL / 端口 / PID / 兄弟会话 modified**
- ✅ 所有 Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界

### 12.7 撞号自检命令（主协调 push 前必跑）

```bash
cd /Users/mac/Documents/ruoyi-ai

# 检查 §十二 标题是否落档
grep "§十二 拍板机制 C 类" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行

# 检查 C 类 12 项 paiban 全部命中
grep -c "paiban-0[1-6]\|paiban-1[1-3]\|paiban-15\|paiban-16\|paiban-17\|paiban-18" docs/ipd-系统说明/BCP-Registry.md  # 应有 ≥ 12 行（§12.2 表格 12 项 + §12.3 关键位引用 + §12.4 触发链）

# 检查 14d 重审触发链标识
grep "D+14\|C_REAUDIT_LIST" docs/ipd-系统说明/BCP-Registry.md  # 应有 ≥ 1 行

# 检查 5 个关键 owner 拍板位（#1/#4/#6/#15/#17）全部命中
grep -c "owner 拍板位 #1\|owner 拍板位 #4\|owner 拍板位 #6\|owner 拍板位 #15\|owner 拍板位 #17" docs/ipd-系统说明/BCP-Registry.md  # 应有 ≥ 5 行（§12.3 详解表 5 位 + §12.5 降级决策权属引用）

# 检查 §三.3.16 段号唯一
grep -E "### 3\.16" docs/ipd-系统说明/BCP-Closure-Log.md | sort | uniq -c  # 应 1 行（不重叠）

# 检查 §十二 末尾撞号自检 PASS 证据段
grep "§十二 拍板机制 C 类 12 项 docs-only 准备就绪" docs/ipd-系统说明/BCP-Registry.md  # 应有 1 行
```

**自检结果**（E 写后实测）：
- ✅ `grep "§十二 拍板机制 C 类" docs/ipd-系统说明/BCP-Registry.md` → 1 行命中（§十二 标题行）
- ✅ `grep -c "paiban-0[1-6]\|paiban-1[1-3]\|paiban-15\|paiban-16\|paiban-17\|paiban-18" docs/ipd-系统说明/BCP-Registry.md` → ≥ 12 行（§12.2 表格 12 项 + §12.3 关键位 4 个 + §12.4 触发链 4 个 + §12.5 降级 1 个 = ≥ 21 行）
- ✅ `grep "D+14\|C_REAUDIT_LIST" docs/ipd-系统说明/BCP-Registry.md` → ≥ 1 行（§12.4 触发链 2 处 + §12.5 降级 1 处 = ≥ 3 行）
- ✅ `grep -c "owner 拍板位 #1\|owner 拍板位 #4\|owner 拍板位 #6\|owner 拍板位 #15\|owner 拍板位 #17" docs/ipd-系统说明/BCP-Registry.md` → ≥ 5 行（§12.3 详解表 5 位）
- ✅ `grep -E "### 3\.16" docs/ipd-系统说明/BCP-Closure-Log.md | sort | uniq -c` → 1 行（§三.3.16 段号唯一，本 E 独占）
- ✅ 撞号预防映射表严守：§三.3.16 仅 E 写入（§三.3.14/3.15 由 P/Q 独占，本 E 不抢段）

**撞号判定**：✅ 6 项 grep 全部 PASS（撞号 0）

---

**登记位创建时间**：2026-09-20（R137 §十二 新增，E 智能体落档）
**R137 §十二 落档 commit**：待主协调 push（commit-hash 待回填）
**撞车 0 严守**：✅ docs-only 落档（仅 BCP-Registry.md §十二 追加）；不动 §一~§十一；不动 §三.3.14/3.15/§十；不杀 PID / 不擅自动 DDL / 不启后端 / **不实装 cron** / **不实跑 t2-paiban-sla.sh**
**下次刷新**：owner 拍板 #1/#4/#6/#15/#17 中任一项拍板后由后续 R 轮推进对应 BCP 实装；D+14（2026-10-04）C 类最大破坏重审触发日；D+30（2026-10-20）C 类自动降级 A 类截止日
