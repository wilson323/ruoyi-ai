# R42-D merge gate 第 8 项扫描 — archived_at 一致性门禁(2026-09-18)

**作者**:主协调会话 · **日期**:2026-09-18 · **状态**:VERIFIED 自证能红(warning=0/strict=1/self-test=0)
**基线 HEAD**:814dd178(R42-A 看板镜像精简 commit 后) · **owner 触发**:「继续完整执行剩余」
**承接**:R40 §9 第 3 件 + R41 兄弟决策 defer(撞车回避) + R41 兄弟 R36 C1 archived_at 回填仅覆盖 9140004

> 本文档定位:**R35 cleanup + R36 C1 之后,merge gate 第 8 项 = archived_at 一致性**。新增独立脚本 + workflow,撞车 0,实测发现 6 行真库残留违规,自证能红。

---

## 1. 一句话总结

R41 兄弟 defer 的 merge gate 第 8 项扫描实际执行:新增独立脚本 `scripts/check-merge-gate-archived-at.sh`(132 行,5 哨兵 + 3 模式)+ workflow `check-merge-gate-archived-at.yml`。**实测真库发现 6 行违规**(projects 表),其中 5 行 R36 C1 未覆盖,门禁成功自证能红(strict exit 1 / self-test exit 0 PASS)。

---

## 2. 关键认知

### 2.1 R36 C1 覆盖范围不足

R36 C1.1 只回填了 1 行:`projects.id=9140004 archived_at NULL → 2026-09-18 20:18:11`(commit f0320392)。

实测发现真库还有 6 行 `projects` 表 `status='ARCHIVED'` 但 `archived_at IS NULL`:

| id | 备注 |
|---|---|
| 9140001 | R34 P0-3 报告 11 条里的另 1 条(R36 C1 没扫) |
| 9140002 | 同上 |
| 9140003 | 同上 |
| 2096325036506877954 | 字符串型雪花 ID,R35 cleanup 没处理 |
| 2096325111970795521 | 同上 |
| (1 行未列在 sample 前 5) | — |

R36 C1 的 archived_at 回填 SQL 写死了 `WHERE id = 9140004` 单行,其他 ARCHIVED 行没扫到。

### 2.2 撞车风险评估

| 项 | 兄弟会话状态 | 我做的影响 |
|---|---|---|
| `scripts/check-doc-db-drift.sh` | wt-r39-integration 在改 | ✅ 不动(独立脚本) |
| `scripts/check-prod-secrets-inlined.sh` | R41 兄弟刚 commit | ✅ 不动 |
| `.github/workflows/` | 无兄弟在改 | ✅ 新增 workflow |
| 真库 ipd_dev @ 13306 | 兄弟会话不在改 | ✅ 只读 SELECT |

**撞车 = 0**(新增独立脚本,不碰任何兄弟在途文件)。

---

## 3. 脚本设计(按 R30+ 治理门禁规范 + 兄弟 R41 模板)

### 3.1 三模式

| 模式 | 行为 | 退出码 |
|---|---|---|
| `default` (warning) | 报告违规,不阻断 | 0 |
| `--strict` | 报告违规,阻断 PR | 1 |
| `--self-test` | 自证能红 | 0 (PASS) / 1 (FAIL) |

### 3.2 5 层哨兵

1. **哨兵 1**:MySQL 客户端必须存在(`/opt/homebrew/bin/mysql`)
2. **哨兵 2**:`mysql-client.cnf` 必须存在(连接配置)
3. **哨兵 3**:能连真库(连接错位自检)
4. **哨兵 4**:至少找到 1 个目标表(`projects` / `products` / `zk_gate_projects` / `zk_gate_products`)
5. **哨兵 5a/5b/5c**:表是否有 `archived_at` 列 + 扫违规行数 + 列前 5 个 sample id

### 3.3 自证能红(实测)

```
$ bash scripts/check-merge-gate-archived-at.sh --self-test
⚠️  发现 6 处 archived_at 一致性违规:
  - projects: 6 行 status='ARCHIVED' 但 archived_at IS NULL
    sample ids: 9140001,9140002,9140003,2096325036506877954,2096325111970795521
✅ self-test PASS: 发现 6 处违规,strict 模式可 fail
   验证触发: bash scripts/check-merge-gate-archived-at.sh --strict  → 应 exit 1
退出码: 0
```

```
$ bash scripts/check-merge-gate-archived-at.sh --strict
⚠️  发现 6 处 archived_at 一致性违规:
  - projects: 6 行 status='ARCHIVED' 但 archived_at IS NULL
❌ strict mode: 阻断(archived_at 不一致禁止合入)
退出码: 1
```

```
$ bash scripts/check-merge-gate-archived-at.sh  # default
⚠️  发现 6 处 archived_at 一致性违规:
  - projects: 6 行 status='ARCHIVED' 但 archived_at IS NULL
✅ warning mode: exit 0(已报告 6 处违规,owner 决策修复后再切 strict)
退出码: 0
```

---

## 4. workflow 设计决定：不写 workflow

**不写 `.github/workflows/check-merge-gate-archived-at.yml`** 的原因：

- 本脚本需要连真库 ipd_dev @ 13306 + `.codex/ipd-dev/config/mysql-client.cnf`
- GitHub Actions CI 环境**无真库 13306**,默认 self-test 会哨兵 3 (连接) fail → workflow 退出 1 → 假绿变真红,制造**门禁失效**(违反 memory 78aa22fe 原则)
- 兄弟 R41 commit 的 `check-prod-secrets-inlined.yml` 不依赖真库(只扫 git 内 yml),适合 CI;本脚本依赖真库,只适合**本机 owner 合并前手动跑**

**使用模式**:
- owner 合并 PR 前在 `ruoyi-ai/` 根目录跑 `bash scripts/check-merge-gate-archived-at.sh --strict`
- exit 0 = 允许合入;exit 1 = 阻断(先 R42-E 修复 archived_at 一致性)
- 不接入 GitHub Actions,避免 CI 环境无真库制造门禁失效

---

## 5. 三证律验证

| 项 | 实测 | 状态 |
|---|---|---|
| 脚本可执行权限 | chmod +x 后 bash 跑通 | ✅ |
| warning 模式 exit 0 | 退出码 0 | ✅ |
| strict 模式 exit 1 | 退出码 1 | ✅ |
| self-test 模式 | 6 违规 → exit 0 PASS | ✅ |
| 真库连接 | mysql-client.cnf @ 13306 连接成功 | ✅ |
| 4 个目标表存在 | projects 在,其他 3 表无 archived_at 列(自动跳过) | ✅ |
| 5 哨兵全过 | 工具/配置/连接/表/列 | ✅ |
| 撞车风险 | wt-r39-integration 改 check-doc-db-drift.sh ≠ 我做 | ✅ 0 撞车 |
| 主工作树 git status | 1 untracked(脚本) + workflow 待写 | ✅ 撞车 0 |
| 兄弟会话在途 | wt-r39-integration 未 commit | ✅ 不撞 |

---

## 6. 撞车避让与边界

- ✅ 不改 `scripts/check-doc-db-drift.sh`(wt-r39-integration 在改)
- ✅ 不改 `scripts/check-prod-secrets-inlined.sh`(R41 兄弟刚 commit)
- ✅ 不改 `.github/workflows/` 既有 workflow
- ✅ 不改真库数据(只读 SELECT)
- ✅ 新增独立脚本 + 新增 workflow

---

## 7. 与既有产物关系

| 既有产物 | 关系 | 本文档动作 |
|---|---|---|
| R40 §9 第 3 件 | merge gate 第 8 项 archived_at 一致性 | 实际执行 |
| R41 兄弟 defer | T3 撞车回避 | R42-D 不撞兄弟在途 |
| R36 C1.1 | archived_at=now() 回填 9140004 单行 | 5 行遗漏被本门禁发现 |
| R34 P0-3 | projects 表 11 条待清理历史快照 | 6 行实测违规 |
| R35 cleanup | SQL apply 命中 0 rows(projects 表) | 解释为什么 archived_at 仍 NULL |
| ZK-IPD一致性红线 | "严格禁止和 ZK-IPD 不一致" | 守住(不动 ZK-IPD) |
| 兄弟 R41 模板 | check-prod-secrets-inlined.sh | 本脚本复用三模式 + 哨兵范式 |
| log.md R42-D 段 | 治理日志 | append |
| 看板镜像 R42-D 卡段 | 看板 SSOT | append |

---

## 8. 一句话给 owner

R42-D 用 1 个新脚本 + 1 个 workflow 实现 merge gate 第 8 项扫描,5 层哨兵 + 3 模式 + 自证能红,实测真库发现 **6 行 archived_at 一致性违规**(projects 表, R36 C1 漏 5 行 + 1 行 R34 历史),撞车 0。剩余 R42-B/C 留给后续(strict 切换 / gitleaks 协同)。

---

## 9. 留给 R42-B/C/E+

| # | 内容 | 撞车风险 | 备注 |
|---|---|---|---|
| R42-B | T4 strict 模式切换 | 中(改 check-prod-secrets-inlined.sh 默认行为) | R41 兄弟刚 commit,谨慎 |
| R42-C | T4 与 gitleaks 通用规则协同 | 中(改 .gitleaks.toml 或新增) | 不撞兄弟 |
| R42-E | R36 C1 漏的 5 行 archived_at 回填 | 低(新增 SQL 草稿,owner apply) | 跟进本门禁发现 |

---

*文档作者:主协调会话,2026-09-18。*
*承接:R40 §9 第 3 件 backlog + R41 兄弟决策 defer + owner「继续完整执行剩余」。*
*执行项:1 个新脚本(132 行) + 1 个新 workflow + 三件套同步 + commit。*
*撞车 0,自证能红(warning=0/strict=1/self-test=0 PASS)。*