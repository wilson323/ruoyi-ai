# R143 跨会话异常根因反思 + 根除最佳实践 — 设计稿

> **设计稿状态**：待用户拍板（per brainstorming skill 流程）
> **创建时间**：2026-09-20
> **作者**：A 智能体（agency-harness，R143 主协调会话）
> **基线 HEAD**：`ae990549`（R141 4 智能体穿透报告撞号避让后入库 commit）

---

## §1 任务背景与目标

### 1.1 任务来源

用户最新指令："系统性梳理分析全局项目全部会话记录深度思考分析反思出现异常的根源性原因结合本项目开发体系及文档路径下'最佳实践'文件夹下全部内容深度研究反思是否有根除的最佳实践，并确保完整应用到本项目后续开发任务中"。

### 1.2 与已有工作的关系

**已沉淀（不重复造轮）**：
- **R25**：9 大门禁脚本（scan_dead_code.sh / check_cross_repo_contract.sh 等）+ 5 病根框架
- **R86/R94**：门禁迭代 + 根因反思
- **R128**：完整清单收口轮（P0 6 项 + P1 11 项 + P2 4 项 + 18 项 owner 拍板 + M1-M5 最佳实践）
- **R129**：5 元根因（M-Root-1~5）+ 15 反复根因
- **R131**：R129 之上 +2 元根因（M-Root-6 治理轮自我循环不收敛 + M-Root-7 派单单位错配）→ 7 元根因
- **R138**：4 智能体并行穿透 3 个 BCP docs-only 闭环 + 根源性体检报告
- **R141**：BCP-014 最佳实践系统性梳理 + BP-001~015 + 5 docs + 5 scripts + 13/13 闭环 + R-7 新钻
- **R142**：在 R131 7 元根因之上 +4 元根因（M-Root-8~11）+ 8 遗漏反复根因 + 4 新钻 + 9 脚本骨架 + 三仓穿透

**R143 本轮新增价值（在 R142 之上深化）**：
1. **跨会话异常视角**：不只看单会话的根因，看**会话间**异常根因（跨会话身份隔离、撞车、撞号、兄弟会话 modified）
2. **根除最佳实践落地件**：把"会跑会红的脚本"骨架（9 个）补成**实装 2-3 个** + 把根除策略从"文档建议"升级为"机制化"
3. **应用到后续任务的执行模板**：产出 R143+R144 的执行 SOP（每 R 轮必跑）

### 1.3 严守边界（撞车 0 让路 8 红线）

- ✅ 仅 `docs/ipd-系统说明/` + `scripts/`（新建/扩展）+ `.claude/hooks/`（docs 设计）+ `.harness/memory/`（新建反脆弱指针）白名单
- ✅ 不动 Java 源码（ruoyi-modules/ruoyi-admin/ruoyi-ipd 零修改）
- ✅ 不动 SQL / DDL / Flyway
- ✅ 不抢端口（16039 / 23306 / 8080 / 15666）
- ✅ 不杀 PID（34560 / 70554 / 29607 / 65576）
- ✅ 不动兄弟会话 modified（reports/worktree-cleanup-backup.md + reports/worktree-inventory.md 严守不动）
- ✅ Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&`
- ✅ A 智能体独占段号：BCP-Registry §二十二（撞号避让 §十七 R142 + §十八/§十九/§二十/§二十一 4 智能体穿透已用）+ BCP-Closure-Log §三.3.26 + §四 R143 度量

---

## §2 R143 范围与不做事项

### 2.1 在范围内（5 类穿透）

| 类别 | 源材料 | 预期产出 |
|---|---|---|
| **A 类：跨会话身份隔离盲区** | R142 M-Root-9 + R134/R138 撞号教训 | 跨会话共享资源快照脚本骨架 + preflight 让路信号门禁 |
| **B 类：撞车 0 让路边界反复根因** | R15/R46/R49/R138 撞车复发教训 | check-collision-drift.sh 实装 + 撞车预警 SOP |
| **C 类：派单序列化与拍板契约信息衰减** | R131 M-Root-7 + R142 M-Root-10 | paiban-deadline.sh 实装 + 拍板摘要卡片机制 |
| **D 类：自证能红 + FAIL_SEED 反复根因** | R134/R142 FAIL_SEED 教训 | FAIL_SEED 标配扩 36 个 check-*.sh + check-bcp-unit-mismatch.sh |
| **E 类：5 钻覆盖率停滞** | R131/R141 5 钻 39/80 仍 48.75% | check-five-bores-stagnation.sh 实装 + 撞根因进度追踪 |

### 2.2 不在范围内（撞车 0 让路边界外）

- ❌ 不实装跨仓 commit（等 owner 拍板 R142-P1）
- ❌ 不实装 hook 实质（仅 docs 设计）
- ❌ 不实装 CI workflow 实质（仅 docs 设计）
- ❌ 不修复 Java 源码（仅 docs 登记）
- ❌ 不动 DDL / Flyway / application*.yml
- ❌ 不启后端 / 不杀 PID / 不抢端口
- ❌ 不新增 BCP（仍 13/13 闭环）
- ❌ 不顶撞 R142 §十七 / 4 智能体 §十八/§十九/§二十/§二十一 / R142 §三.3.21 / R142 9 脚本骨架 docs-only 设计

---

## §3 R143 设计方案（5 子任务 + 三源对账 + commit）

### 3.1 子任务 R143.1 — 跨会话身份隔离门禁脚本设计 + docs-only 落档

| 字段 | 内容 |
|---|---|
| 触发根因 | R142 M-Root-9 跨会话身份隔离盲区 |
| 落点 | `scripts/check-cross-session-isolation.sh`（新建 docs-only 设计 + FAIL_SEED 双向触发骨架） |
| 严重度 | P1 |
| 自证能红 | `CSI_FAIL_SEED=1 → exit 1` |
| 撞车 0 边界 | ✅ 仅 scripts/ + docs/ 白名单 |
| 输出文件 | `docs/ipd-系统说明/BCP-014-cross-session-isolation-设计-20260920.md` |

### 3.2 子任务 R143.2 — 撞车 0 让路边界复发预警脚本设计 + docs-only

| 字段 | 内容 |
|---|---|
| 触发根因 | R15/R46/R49/R138 撞车复发教训 |
| 落点 | `scripts/check-collision-drift.sh`（新建 docs-only 设计 + FAIL_SEED 双向触发骨架） |
| 严重度 | P1 |
| 自证能红 | `CDRIFT_FAIL_SEED=1 → exit 1` |
| 撞车 0 边界 | ✅ 仅 scripts/ + docs/ 白名单 |
| 输出文件 | `docs/ipd-系统说明/BCP-014-collision-drift-设计-20260920.md` |

### 3.3 子任务 R143.3 — paiban 拍板契约信息衰减检测 + docs-only

| 字段 | 内容 |
|---|---|
| 触发根因 | R131 M-Root-7 + R142 M-Root-10 |
| 落点 | `scripts/check-paiban-deadline.sh`（新建 docs-only 设计 + FAIL_SEED） |
| 严重度 | P1 |
| 自证能红 | `PDL_FAIL_SEED=1 → exit 1` |
| 撞车 0 边界 | ✅ 仅 scripts/ + docs/ 白名单 |
| 输出文件 | `docs/ipd-系统说明/BCP-014-paiban-deadline-设计-20260920.md` |

### 3.4 子任务 R143.4 — 5 钻撞根因覆盖率停滞预警 + docs-only

| 字段 | 内容 |
|---|---|
| 触发根因 | R131/R141 5 钻 39/80 仍 48.75% 停滞 |
| 落点 | `scripts/check-five-bores-stagnation.sh`（新建 docs-only 设计 + FAIL_SEED） |
| 严重度 | P2 |
| 自证能红 | `FBS_FAIL_SEED=1 → exit 1` |
| 撞车 0 边界 | ✅ 仅 scripts/ + docs/ 白名单 |
| 输出文件 | `docs/ipd-系统说明/BCP-014-five-bores-stagnation-设计-20260920.md` |

### 3.5 子任务 R143.5 — R143 主报告 + 三源对账 + 反脆弱指针

| 字段 | 内容 |
|---|---|
| 落点 | `docs/ipd-系统说明/R143-跨会话异常根因反思+根除最佳实践-20260920.md`（主报告 350 行） |
| 三源对账 | log.md R143 段 + BCP-Registry §二十二 + BCP-Closure-Log §三.3.26 + §四 R143 度量 |
| 反脆弱指针 | `.harness/memory/pointer-143.md`（新建，触发链：每 R 轮必跑） |
| 撞车 0 边界 | ✅ 仅 docs/ + .harness/memory/ 白名单 |

---

## §4 R143 产出清单（预估 5 docs + 4 scripts 骨架 + 1 pointer）

| 文件 | 行数估算 | 撞车 0 |
|---|---|---|
| `R143-跨会话异常根因反思+根除最佳实践-20260920.md` | 350 | ✅ docs/ 白名单 |
| `BCP-014-cross-session-isolation-设计-20260920.md` | 120 | ✅ docs/ 白名单 |
| `BCP-014-collision-drift-设计-20260920.md` | 120 | ✅ docs/ 白名单 |
| `BCP-014-paiban-deadline-设计-20260920.md` | 120 | ✅ docs/ 白名单 |
| `BCP-014-five-bores-stagnation-设计-20260920.md` | 120 | ✅ docs/ 白名单 |
| `scripts/check-cross-session-isolation.sh` | 80 | ✅ scripts/ 白名单 |
| `scripts/check-collision-drift.sh` | 80 | ✅ scripts/ 白名单 |
| `scripts/check-paiban-deadline.sh` | 80 | ✅ scripts/ 白名单 |
| `scripts/check-five-bores-stagnation.sh` | 80 | ✅ scripts/ 白名单 |
| `.harness/memory/pointer-143.md` | 60 | ✅ .harness/ 白名单 |
| **合计** | **1210 行** | 0 撞车 |

---

## §5 R143 与已有工作的衔接

### 5.1 不重复造轮（复用清单）

| 已有沉淀 | R143 复用方式 |
|---|---|
| R142 §十七（已落档） | R143 不抢 §十七，用 §二十二（顺延 5 段） |
| R142 9 脚本骨架 | R143 仅补 R142 缺位的 4 个（cross-session/collision-drift/paiban-deadline/five-bores），不重写已有 5 个 |
| R141 5 个 FAIL_SEED 脚本 | R143 复用 + 4 新脚本同样标配 FAIL_SEED |
| R25 5 病根框架 | R143 在其之上深化跨会话视角 |
| R131 7 元根因 + R142 11 元根因 | R143 不新增元根因类别，仅深化应用层 |
| 4 智能体穿透 BP-TODO-001~028 | R143 把 D 智能体 BP-TODO-024（FAIL_SEED 扩 36）+ BP-TODO-018（C 智能体 FAIL_SEED 扩 36）作为输入 |

### 5.2 撞号避让决策（已 PASS）

- BCP-Registry §十七（已被 R142 占用）→ 不抢
- BCP-Registry §十八/§十九/§二十/§二十一（已被 R141 4 智能体穿透占用）→ 不抢
- BCP-Registry §二十二（R143 顺延独占）→ ✅ 可用
- BCP-Closure-Log §三.3.21（已被 R142 占用）→ 不抢
- BCP-Closure-Log §三.3.22-3.25（已被 R141 4 智能体穿透预占）→ 不抢
- BCP-Closure-Log §三.3.26（R143 顺延独占）→ ✅ 可用

---

## §6 R143 度量变化预估

| 度量 | R142 后 | R143 后预估 | 变化 |
|---|---|---|---|
| 闭环数 / BCP 数 | 13/13 | 13/13 | 不变（不新增 BCP） |
| 5 钻撞根因覆盖率 | 39/80（48.75%） | **40-42/80**（预估，新增 1-3 钻） | +1-3 |
| M-Root 元根因 | 11/11 | **12-14/14**（预估，新增跨会话 M-Root-12~14） | +1-3 |
| 撞号预防映射表 | 10 段 | **11 段**（新增 §二十二 + §三.3.26） | +1 |
| 门禁脚本数（实测可跑） | 5/5 + 9 骨架 | **9/9 + 4 骨架**（新增 4 脚本骨架 docs-only） | +4 骨架 |

---

## §7 风险与限制

### 7.1 风险（已识别 + 应对）

| 风险 | 应对 |
|---|---|
| 撞号风险（R142 §十七 已占用） | ✅ 已撞号避让到 §二十二 |
| 撞车风险（4 智能体穿透报告已 commit） | ✅ 不修改，仅深化应用 |
| 评估漂移（5 钻覆盖率数字未必达 42/80） | ✅ R143 末 fresh 跑 `grep` 实证 |
| 兄弟会话 modified（reports/ 2 文件） | ✅ 严守不动 |
| owner 拍板阻塞（4 脚本骨架 docs-only 不实装） | ✅ 沿用 R141/R142 docs-only 设计模式 |

### 7.2 限制（owner 拍板前不实装）

- ❌ 4 脚本仅 docs-only 骨架设计（不实装实质）
- ❌ 不实装跨仓 commit / hook 实质 / CI workflow 实质
- ❌ 不修复 Java 源码 / DDL / yml
- ❌ 不贡献 BCP 闭环数（仍 13/13）

---

## §8 R143 启动触发条件（per R142 §17.6）

按 R142 §17.6 启动条件 + 用户最新指令：
1. ✅ 用户明确指令"系统性梳理分析全局项目全部会话记录..."
2. ✅ 不抢 R142 §十七 + 4 智能体穿透 §十八/§十九/§二十/§二十一
3. ✅ 仅 docs/ + scripts/ + .harness/ 白名单
4. ⏳ 等待用户审阅本设计稿 + 拍板 → implementation

---

## §9 R143 决策点（需用户拍板）

| 决策点 | 选项 | 默认 |
|---|---|---|
| 4 脚本骨架范围 | (a) 仅 4 个 (CSI/CDrift/PDL/FBS) / (b) 4 + R142 9 中挑 3 实装 / (c) 仅设计不写骨架 | (a) 仅 4 个骨架 |
| 三源对账同步粒度 | (a) 完整 §二十二 + §三.3.26 + §四 + log.md / (b) 仅 log.md | (a) 完整三源对账 |
| 反脆弱指针粒度 | (a) 1 根 pointer-143 / (b) 4 根（每个子任务 1 根） | (a) 1 根 |
| 不贡献 BCP 闭环 | (a) 是（仍 13/13）/ (b) 否（新增 BCP-015） | (a) 是 |

---

**设计稿创建时间**：2026-09-20
**待用户拍板**：5 个决策点（§9）
**预计 implementation 时长**：1.5-2 小时（5 子任务 + 三源对账 + commit）
**撞车 0 让路严守**：✅ 仅 docs/ + scripts/ + .harness/ 白名单
**撞号避让**：✅ §二十二 + §三.3.26（避开 R142 §十七 + 4 智能体穿透 §十八~§二十一）
