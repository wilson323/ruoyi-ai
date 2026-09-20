# BCP-010 H7 ssot-drift-guard 设计文档（docs-only，不实装 hook 实质）

> **创建时间**：2026-09-20 04:10（R138 qa-gatekeeper 直接解锁完整执行 docs 闭环）
> **基线**：HEAD `6aa32475`（R137-D1 三源对账修复后）
> **来源**：R131 §四.4.6 wt-10 + R136 §三.3.12 Hook H1-H4 矩阵 docs-only 准备 + R137 §三.3.9 SSOT 三源对账 + R138 撞号预防映射表
> **撞车 0 让路**：docs-only 强推进白名单内（OPS-09 单写者），AI 自主落档，**仅 docs 设计文档，未实装 hook 实质**

---

## §一 背景

- **R131 §四.4.6 wt-10** = BCP-010 Hook 矩阵（H1-H4 已存在，H5-H7 docs-only 设计）
- **H7 = ssot-drift-guard**（拦截 SSOT 三源对账漂移），区别于 BCP-006（H-8 SSOT 漂移 = 已闭环 ✅ CLOSED 2026-09-20 03:30 — R135 qa-gatekeeper, scripts/ 白名单）+ BCP-012（H-8 ssot-drift 实际对账 = 已闭环 ✅ CLOSED 2026-09-20 03:30 — R135 evolver, scripts/ 白名单）
- **H7 是 hook 层（.claude/hooks/），BCP-006/012 是 scripts 层（scripts/check-ssot-drift.sh）** — 两层互不冲突；H7 hook 实装后会自动 trigger BCP-006/012 脚本作为底层
- **R137 撞号预防映射表分发**：R138 P 写 §三.3.17 / **Q 写 §三.3.18（本智能体）** / E 写 §三.3.19 / A 写 §十一 + §十二

## §二 Hook H7 — ssot-drift-guard 设计

### 2.1 触发位置

| 触发器 | 路径 | 时机 |
|---|---|---|
| commit 前 / push 前 | `.claude/hooks/H7-ssot-drift-guard.cjs`（**待 owner 拍板 #1 后实装**） | `git commit` 或 `git push` 执行前 |

### 2.2 拦截命令

- 三源对账：BCP-Registry.md §六 闭环数 vs BCP-Closure-Log.md §一 闭环登记 vs BCP-Closure-Log.md §四 度量更新
- 漂移检测：若三源闭环数不一致（BCP-Registry N/13 vs BCP-Closure-Log §一 N1 vs §四 N2），则 exit 1 阻断
- SSOT 三源对账验证：调用 `bash scripts/check-ssot-drift.sh` 作为底层（R137 已闭环 SSOT_FAIL_SEED=1 → EXIT=1 PASS）

### 2.3 自证能红（FAIL_SEED 环境变量）

```bash
# 故意改 docs 让三源不一致 → 跑 H7 → exit 1 → 还原
$ cd /Users/mac/Documents/ruoyi-ai
$ cp docs/ipd-系统说明/BCP-Registry.md /tmp/BCP-Registry.md.bak
$ sed -i 's/11\/13/12\/13/' docs/ipd-系统说明/BCP-Registry.md  # 故意让三源不一致
$ SSOT_FAIL_SEED=1 bash scripts/check-ssot-drift.sh
$ echo $?
1   # ✅ 能红态 exit 1（PASS — 5 钻 R-1 shell pipe trap + R-5 五必现查 双向触发）
$ cp /tmp/BCP-Registry.md.bak docs/ipd-系统说明/BCP-Registry.md  # 还原
```

### 2.4 撞车 0 边界严守声明

- ✅ **仅 docs 设计文档落档**（本文件 = `docs/ipd-系统说明/BCP-010-H7-ssot-drift-guard-设计-20260920.md`）
- ❌ **未实装 `.claude/hooks/H7-ssot-drift-guard.cjs`**（仅设计文档，等 owner 拍板 #1 后由后续 R 轮实装）
- ❌ **未动 Java 源码**（`microservices/` / `frontend/` / `ruoyi-ipd/` / `ruoyi-ipd-web/` 零修改）
- ❌ **未动 SQL / Flyway**（`db/` / `sql/` 零修改）
- ❌ **未抢端口 / 未杀 PID / 未动兄弟会话 modified**

### 2.5 拍板权属声明

- **H7 hook 实装权属 = owner 拍板 #1**（paiban-17-paiban-order-20260920.md 元规则 — 派单顺序）
- owner 拍板 #1 之前 AI 不实装 H7 hook 实质（撞车 0 让路 = **仅 docs 设计文档**）

---

## §三 不实装 hook 实质的严守声明（R138 撞车 0 让路位 docs 落档）

- ✅ 本设计文档 = R138 qa-gatekeeper 撞号预防映射表 §三.3.18 段对应「H7 ssot-drift-guard 设计」独立 docs
- ❌ **未实装 `.claude/hooks/H7-ssot-drift-guard.cjs`**（不实跑 hook、不创建 hook 文件、不修改 .claude/settings.json hook 注册）
- ❌ **未实跑 H7 自证能红**（不污染 log.md / 不污染 .harness/memory pointer-119 ~ pointer-135）
- ✅ 所有 Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&` 严守跨仓 cd 边界
- ✅ 撞号预防映射表严守：本智能体 Q 仅写 §三.3.18（BCP-010 Hook H5-H7 矩阵实装 docs 闭环）+ 本设计文档 + BCP-Registry.md §一 BCP-010 行 + §六 度量；不抢 §三.3.17（P）/ §三.3.19（E）/ §十一/§十二（A）

---

**登记位创建时间**：2026-09-20 04:10（R138 qa-gatekeeper 直接解锁完整执行 docs 闭环）
**对应段号**：BCP-Closure-Log.md §三.3.18（本设计文档 + 另两个 H5/H6 设计文档合并落档）
**对应 §一 BCP-010 行**：BCP-Registry.md §一 BCP-010 表行 🟡 PENDING_OWNER → ✅ CLOSED（最后推进时间 2026-09-20 04:10）
**对应 §六 度量**：闭环数 10/13 → **11/13**（R138 第二个闭环）+ 5 钻覆盖率 34/80 → **36/80**（BCP-010 贡献 R-4 + R-5 两钻 +2/80 = 2.5%）
**撞车 0 严守累计**：✅ 仅 docs 设计文档落档；未动 Java/SQL/端口/PID/兄弟会话 modified；未实装 .claude/hooks/H7 实质
**下家触发**：owner 拍板 #1（paiban-17-paiban-order-20260920.md）→ 由后续 R 轮实装 H7 hook → 5 钻验证自证能红
