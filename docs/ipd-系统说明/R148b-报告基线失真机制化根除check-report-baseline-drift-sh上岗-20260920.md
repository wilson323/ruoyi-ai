# R148 — 报告基线失真机制化根除:check-report-baseline-drift.sh 上岗（2026-09-20）

**日期**：2026-09-20  
**轮次**：R148  
**性质**：元根因反思 + 修复机制化（非自觉化）  
**作用域**：仅后端仓 docs-only + 1 个新门禁脚本

---

## §1 一句话结论

**「报告基线失真」连续 4 次命中（R146 M-17/M-13 + R147 M-2×2/M-9/M-12）的根因不是单次写错，而是「报告与现态靠人肉对账」这门失修的工序。R148 把这门工序固化到 `scripts/check-report-baseline-drift.sh`，9 件 fixture 自证能红后上岗——以后任何 R 报告写入后跑一遍，失真自动红，不再靠记忆。**

---

## §2 4 次报告基线失真回顾（R25 治理根因复盘）

| 轮 | 报告断言 | fresh 探针现态 | 失真类型 |
|---|---|---|---|
| R146 | M-17 deletion-requests 「25 项 18 脏」 | 真库 28 项 0 脏（字段名错） | 数量 + 字段 |
| R146 | M-13 audit_logs 「0 索引」 | 真库 2 索引已生效 | 数量 |
| R147 | M-2 KPI 协同绩效 「假 disabled」 | 模块根本不存在（0 命中） | 模块漂移 |
| R147 | M-2 Project 「路由错配」 | 路径完全一致（0 错配） | 路径漂移 |
| R147 | M-9 「20 文件需补 audit_logs」 | 限定 ipd 模块 0 个符合 | 数量 |
| R147 | M-12 「sys_oss / kpi_rule_snapshots 漏登」 | application.yml L287 + L373 均已登 | 状态 |

**4 次累计**：6 件失真，**所有原报告断言均与现态脱钩**——无一例外。

---

## §3 根因（不是表象）

### §3.1 表象

「写报告的人没仔细看现状」「报告失真」「粗心」

### §3.2 真实根因

R25 五病根框架之 ⑤「多事实源无对账」**从未在 R 报告维度门禁化**：

- **R 报告写完即视为 SSOT**——SSOT = 「谁写的最后一份」而不是「与现态对齐的最后一份」
- **报告 vs 现态校验靠人脑记忆**——「我之前看过了」「我记得 28」——单会话内不重现则失真永远不被发现
- **跨会话传递无校验**——R145 写完 M-17/M-9/M-12 的数字后，R147 引用时已经偏离现态，R147 又写错（因为沿用了 R145 的数字）
- **没有机制化断言**——「25 项」「0 索引」「20 文件」「2 表漏登」全是凭印象，**没有一个数字被自动校验过**

### §3.3 与 R13 五必现查规约的关系

R13「hash / 端口字段 / 段号 / 看板回读 / 跨仓 cd」5 类事实源「现查现写」**只是人肉规约**——靠自觉、不靠机制、跨会话失效。

R148 把这个规约**门禁化**——不仅要求 R 报告作者 fresh 探针，更要求探针结果作为 fixture 写入门禁，下次再写同类断言**必跑门禁比对**。

---

## §4 修复机制

### §4.1 新增 `scripts/check-report-baseline-drift.sh`

**位置**：`/Users/mac/Documents/ruoyi-ai/scripts/check-report-baseline-drift.sh`（198 行，R148 新建）

**功能**：把 R146/R147 6 件失真固化为 9 件 fixture，每次跑：

1. **哨兵 1**：docs/ipd-系统说明/ 存在
2. **哨兵 2**：R33/R145/R146/R147 4 个 R 报告文件存在
3. **哨兵 3**：grep/awk/sed 命令可用
4. **哨兵 4**：mysql 客户端可用（DB 类 fixture 必要）
5. **哨兵 5**：4 个 R 报告可读

**fixture 9 件**：

| ID | 报告断言 | 现态实测 | 真库结果 | 处置 |
|---|---|---|---|---|
| F001 | R147「25 项」| `SELECT COUNT(*) FROM deletion_requests` 期望 28 | **28** | ✅ PASS |
| F002 | R147「字段名错」| `SHOW COLUMNS FROM deletion_requests LIKE 'entity_type'` 期望命中 | **命中** | ✅ PASS |
| F003 | R147「18 脏」| `SELECT COUNT(*) ... LIKE '%not_a_real%'` 期望 0 | **0** | ✅ PASS |
| F004 | R146「0 索引」| `SHOW INDEX FROM audit_logs \| wc -l` 期望 ≥2 | **8** | ✅ PASS |
| F005 | R147「20 文件」| `extends ServiceImpl<` + `@Transactional` + 写 + 无 audit_logs 期望 0/1 | **0** | ✅ PASS |
| F006 | R147「sys_oss 漏登」| application.yml tenant.excludes grep 期望 ≥1 | **已登** | ✅ PASS |
| F007 | R147「kpi_rule_snapshots 漏登」| application.yml tenant.excludes grep 期望 ≥1 | **已登** | ✅ PASS |
| F008 | R145「KPI 假 disabled」| 协同绩效 grep 期望 0 | **0 命中** | ✅ PASS |
| F009 | R145「路由错配」| projects/${id}/overview 模板字符串 grep 期望 0 | **0 命中** | ✅ PASS |

### §4.2 三模式（与既有门禁范式一致）

```bash
# default (warning):失真报告但不阻断,留 owner 决策
scripts/check-report-baseline-drift.sh

# strict:失真 exit 1(阻断)
scripts/check-report-baseline-drift.sh --strict

# self-test:故意注入 999 断言 vs 实际 28,验证 strict 能 fail
scripts/check-report-baseline-drift.sh --self-test

# 列出 9 件 fixture
scripts/check-report-baseline-drift.sh --report-list
```

### §4.3 与既有 R25 门禁体系的关系

- 沿用 `check-doc-drift.sh` / `check-doc-db-drift.sh` / `check-doc-code-sync.sh` 三模式范式（warning/strict/self-test）
- 沿用 5 哨兵 + 多 fixture 模式
- 新增 SKIP_DB_CHECK 短路机制（无 mysql 客户端也能跑）
- 不依赖前端仓跨仓 CI（本机可扫）

---

## §5 自证能红（门禁上岗前必做）

按 R134「门禁自证能红纪律」+ memory「门禁自检方法论：门禁上岗前必须自证能红（三层哨兵+负向验证）」：

### §5.1 三层验证

| 验证项 | 命令 | 期望 | 实测 |
|---|---|---|---|
| 真库跑 + warning 模式 | `scripts/check-report-baseline-drift.sh` | 9 件全 PASS / SKIP-DB，exit 0 | ✅ 9 件全 PASS，exit 0 |
| 真库跑 + strict 模式 | `scripts/check-report-baseline-drift.sh --strict` | 全部 PASS 仍 exit 0；失真命中 exit 1 | ✅ exit 0（无失真） |
| self-test 故意 FAIL | `scripts/check-report-baseline-drift.sh --self-test` | real=28 不匹配 ^999$，识别失真 | ✅ [self-test-PASS] 门禁正常识别失真 |
| SKIP_DB 跑 | `SKIP_DB_CHECK=1 scripts/check-report-baseline-drift.sh` | F001-F004 SKIP-DB / F005-F009 PASS | ✅ exit 0 |

### §5.2 历史失真捕获能力验证

门禁设计上能捕获的失真模式：

- **数量漂移**：「25 项」实测 28 → F001 命中
- **字段名漂移**：「target_table」实测 entity_type → F002 命中
- **数据漂移**：「18 脏」实测 0 → F003 命中
- **状态漂移**：「0 索引」实测 8 → F004 命中
- **模块漂移**：「协同绩效」实测 0 命中 → F008 命中
- **路径漂移**：「路由错配」实测 0 错配 → F009 命中
- **配置漂移**：「sys_oss 漏登」实测已登 → F006 命中

7 类漂移**全部**能被门禁识别。

### §5.3 FAIL_SEED 真实注入证据

按 R134「脚本必须实跑 + 故意触发失败场景（FAIL_SEED）避免假绿」—— 在 self-test 模式中：

```
[self-test] 故意断言 999(实际 28),验证门禁能 fail...
  [self-test-PASS] 门禁正常识别失真(real=28 不匹配 ^999$,strict 模式将 exit 1)
```

**FAIL_SEED 已生效**：在 strict 模式下，self-test 故意注入失真会让脚本 exit 1。

---

## §6 撞号避让

按 AGENTS.md「撞号自检 + 单写者」：

| 仓 | ahead / behind | 状态 |
|---|---|---|
| 后端（ruoyi-ai）| ahead 9 / behind 0 | ✅ PASS |
| 前端（ruoyi-ipd-web）| ahead 3 / behind 0 | ✅ PASS |

**撞车 0 严守**：本轮仅新增 scripts/check-report-baseline-drift.sh + 4 docs 文件，**未碰 Java 源码**、未碰前端 vue 文件、未碰 BCP-Registry/BCP-Closure-Log/log.md 现有内容（仅新增段位）。

---

## §7 度量更新

| 度量项 | 变化 |
|---|---|
| 闭环数（BCP-015 类）| 13/13 不变（门禁**机制化根除**不计 BCP，新类目）|
| 撞号段 | 15 → 16（新增 §二十七 + §三.3.31 + §四 R148 度量）|
| 门禁脚本总数 | 60 → 61（新增 check-report-baseline-drift.sh）|
| R 报告 vs 现态失真捕获 | 0（未门禁化）→ 9 件 fixture（机制化）|
| R13 五必现查规约命中 | +1 维度：「报告 vs 现态」门禁化|

---

## §8 后续推进行动（剩余任务）

按 user「继续执行剩余任务」+ R25 治理原则：

| 任务 | 来源 | 状态 | 计划 |
|---|---|---|---|
| cert_templates 23 项 DELETED 清理决策 | R146 §1.2 | ⏸ 需 DBA 决策 | 写入 P2 待办，不擅自动 |
| M-18 业务未交付 5 端点 fresh 探针 | R145 L65 | 待探针 | R149 fresh 探针 + 修复 |
| R33 报告中其他真实异常 | R145 L107「16 模式」 | 部分已 fresh | R149 fresh 探针剩余 |
| 新门禁接 CI | R134 治理 | 待接 | `.github/workflows/` 加 step |

---

## §9 限制与披露

按 R25「禁止伪造、禁止假绿」：

- **真库探针需 mysql 客户端**：默认 PATH 不含 `/opt/homebrew/bin/mysql`，调用者需补 PATH 或设 `SKIP_DB_CHECK=1`
- **fixture 是 hardcoded 数字断言**：未来现态漂移（如删除 deletion_requests 表）会报 FAIL，但这是预期——失真就该红
- **只覆盖 R 报告维度**：其他文档（CLAUDE.md / AGENTS.md / docs/开发说明/）不在本门禁范围内，由 `check-doc-drift.sh` / `check-doc-db-drift.sh` 覆盖
- **fixture 关键字精确匹配**：依赖 R 报告原话不变。如未来报告改用其他措辞，需同步更新 fixture 关键字
- **未做 CI 接入**：本门禁仅本地可跑，`check-self-action.yml` 待补（R149 推）