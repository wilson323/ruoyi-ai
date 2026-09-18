# R43-β check-doc-drift.sh 设计 — 文档失真门禁(R25 病根 ③ 根除)(2026-09-18)

**作者**:主协调会话 · **日期**:2026-09-18 · **状态**:DRAFT 设计文档(脚本待 R43-β 二轮)
**基线 HEAD**:`f10bbdc4`(R42-C.3 snail-job token 专属规则 commit 后) · **owner 触发**:「系统性梳理全局项目还有哪些待办事项完整执行」
**承接**:R37 §6.1 P0 必修第 3 件 + R43-evolver agent 报告 §4 病根 ③ 空白判定 + R43+ 治理路线图梳理

> 本文档定位:**R25 病根 ③「多事实源无对账(文档维度)」从未门禁化** —— 现有 9 大门禁只覆盖病根 ① ④ ⑤(测试/契约/DB),病根 ②(submission)与病根 ③(doc-drift)两个空白。R43-β 专注病根 ③ = **49 页 API 文档 ↔ Controller 路径 ↔ 文档间链接有效性** 三类漂移的扫描门禁设计。**本轮仅设计 + 5 处失真 fixture + 自证能红方法,不动 scripts/,留给 R43-β 二轮拍板落地**。

---

## 1. 一句话总结

R25 病根 ③ 文档失真门禁 = `scripts/check-doc-drift.sh` 设计:**5 处失真 fixture + 3 模式(default/strict/self-test)+ 4 层哨兵(docs 存在/链接有效/49页 API 锚点/表名白名单)+ 自证能红方法**。撞车 0(不动兄弟 R39 任何在途文件:check-contract-tri-source.sh / check-doc-db-drift.sh / check-pre-commit.sh / r38-5-gates.yml / R39-*.md)。

---

## 2. R25 病根 ③ vs 现有门禁覆盖矩阵

| 病根子项 | 已门禁 | 缺口 |
|---|---|---|
| ① 测试假绿(238 个 @Tag dev Surefire 跳过) | `check-surefire-fake-green.sh`(254 行,R38 bfceebd1) | ✅ 已根除 |
| ② 提交不完整(untracked 引用) | ❌ 0 个脚本 | R43-α 主目标 |
| ③ 文档失真(本文档) | ❌ 0 个脚本 | **R43-β 本文档主目标** |
| ④ 前后端契约无门禁 | `check-api-contract-fe-be.mjs`(406 行,R38 bfceebd1) + 兄弟 R39 `check-contract-tri-source.sh`(326 行,wt-r39-integration) | ✅ 已根除 |
| ⑤ 多事实源无对账(DB schema 维度) | `check-doc-db-drift.sh`(896 行,R38 bfceebd1) + `check-merge-gate-archived-at.sh`(131 行,R42-D) + `check-mirror-vs-board.py`(233 行) | ✅ 已根除 |

**病根 ②③ 是 R25 五病根框架中最后两个空白**。本 R43-β 负责 ③,撞车 0。

---

## 3. check-doc-drift.sh 设计

### 3.1 三模式(沿用 R42-D 兄弟 R41 模板)

| 模式 | 行为 | 退出码 |
|---|---|---|
| `default`(warning) | 报告失真,不阻断 | 0 |
| `--strict` | 报告失真,阻断 PR | 1 |
| `--self-test` | 自证能红(用 §4 的 5 处 fixture 触发违规) | 0(PASS) / 1(FAIL) |

### 3.2 4 层哨兵

1. **哨兵 1**:`docs/ipd-系统说明/` 与 `docs/开发说明/` 必须存在
2. **哨兵 2**:`docs/开发说明/spec/batch-01~05-pages-1-49.md` 5 个文件必须存在(49 页锚点基础)
3. **哨兵 3**:链接有效性 —— 用 `grep -rE "batch-0[1-5]-pages-" docs/` 提取所有锚点引用,与 5 个文件名对账
4. **哨兵 4**:表名白名单 —— `gate_review_elements` / `audit_logs` / `requirements` / `change_requests`(R37 §5.1 8 张漂移表的 4 张核心),扫描 `docs/**/*.md` 引用,任何匹配白名单之外的名字(如 `gate_elements` / `audit_log` / `demand` / `change_request`)即违规

### 3.3 扫描维度(3 类失真)

| 维度 | 检查内容 | 失真样例 |
|---|---|---|
| **A. 49 页 API 锚点漂移** | `batch-XX-pages-YY.md` 中 `@路径前缀/...` 与 `apps/web-antd/src/api/ipd/*.ts` 实际请求路径一致 | 前端 `api/ipd/ai-document.ts` 用 `{documentId}` 后端实际是 `{id}` |
| **B. 文档间链接失效** | `docs/**/*.md` 中 `[文本](相对路径)` 目标存在 | R37 §6 发现的 5 处文档失真中至少 1 处是引用已删文件 |
| **C. 表名漂移(白名单外)** | 文档引用表名 ⊆ {`gate_review_elements`,`audit_logs`,`requirements`,`change_requests`(三张独立表)} | 文档写 `gate_elements` 但 DB 是 `gate_review_elements` |

---

## 4. 5 处失真 fixture(R37 §5.1 + R42-D 实测)

**用作 `--self-test` 自证能红的 fixture**(脚本运行时把 fixture 临时注入,扫完恢复):

| # | 失真样例 | 来源 | 类别 |
|---|---|---|---|
| F1 | `docs/开发说明/spec/batch-01-pages-1-12.md` 中出现 `gate_elements` 字面量 | R37 §5.1 | C.表名漂移 |
| F2 | `docs/ipd-系统说明/工程合同/*.md` 中出现 `audit_log` 字面量(应为 `audit_logs`) | R37 §5.1 | C.表名漂移 |
| F3 | `docs/开发说明/开发说明书.md` 中出现 `demand` 字面量(实际是 `requirements`) | R37 §5.1 | C.表名漂移 |
| F4 | `docs/ipd-系统说明/验收/P3-3.3-*.md` 引用 `[R17](./R17-已删-20260910.md)`(R17 文档已归档) | R37 §6.1(预计) | B.链接失效 |
| F5 | `docs/开发说明/spec/batch-04-pages-25-37.md` 中 API 路径 `/ai-documents/{documentId}/revise` 与后端 `@PostMapping("/{id}/revise")` 不一致 | R37 §4 字段名错位 | A.API 锚点漂移 |

**fixture 注入方式**:`--self-test` 模式下脚本在临时目录 `tmp/r43-beta-fixture-{uuid}/` 注入 5 个失真文件副本,跑完即删,不污染 docs 树。

---

## 5. 自证能红方法论(沿用 R42-D 范式)

```bash
$ bash scripts/check-doc-drift.sh --self-test
⚠️  发现 5 处文档失真(R37 §5.1 + R37 §6 fixture):
  - F1 表名漂移: gate_elements → 应 gate_review_elements
  - F2 表名漂移: audit_log → 应 audit_logs
  - F3 表名漂移: demand → 应 requirements
  - F4 链接失效: R17-已删-20260910.md 已被 R42-A 归档
  - F5 API 锚点: ai-documents/{documentId}/revise → 应 {id}
✅ self-test PASS: 发现 5 处失真,strict 模式可 fail
退出码: 0

$ bash scripts/check-doc-drift.sh --strict
⚠️  发现 5 处文档失真(同 §4 fixture)
❌ strict mode: 阻断(文档失真禁止合入)
退出码: 1
```

---

## 6. 撞车风险评估(R42-D 范式)

| 项 | 兄弟会话状态 | 本 R43-β 影响 |
|---|---|---|
| `scripts/check-doc-db-drift.sh` | wt-r39-integration 改 +73 行(refined 模式) | ✅ 不动(病根 ⑤ 已独立门禁) |
| `scripts/check-contract-tri-source.sh` | wt-r39-integration 新增(326 行,端点三向) | ✅ 不动(病根 ④ 端点维度,与本病根 ③ 文档维度不重叠) |
| `scripts/check-pre-commit.sh` | wt-r39-integration 新增(122 行) | ✅ 不动 |
| `.github/workflows/r38-5-gates.yml` | wt-r39-integration 新增(152 行) | ✅ 不动(本轮不写 workflow,留二轮) |
| `docs/ipd-系统说明/R39-*.md` | wt-r39-integration 新增 2 文件 | ✅ 不动 |
| `docs/ipd-系统说明/log.md` | 兄弟频繁 append | ✅ 仅 append R43-β 段,不删既有 |
| `docs/ipd-系统说明/开发计划-看板镜像.md` | R42-A 精简后 916 行 | ✅ 仅 append R43-β 卡段 |

**撞车 = 0**(本轮仅写新 markdown 设计文档,不动 scripts/ 与既有 R 文档)。

---

## 7. workflow 设计决定:不写 workflow

**不在本轮写 `.github/workflows/check-doc-drift.yml`** 的原因(R42-D §4 同理):

- 本脚本需扫描 `docs/**/*.md` + `apps/web-antd/src/api/ipd/*.ts`,跨仓读
- GitHub Actions CI 环境无前端仓挂载点,跑不完整
- 兄弟 R38 5 门禁 CI workflow `r38-5-gates.yml` 已固化 L1 静态扫描位置,本脚本可作为 L2 扩展项让兄弟 R39 自行合并

**使用模式**(R43-β 二轮落地后):
- owner 合并 PR 前在 `ruoyi-ai/` 根目录跑 `bash scripts/check-doc-drift.sh --strict`
- exit 0 = 允许合入;exit 1 = 阻断(先修文档失真)
- 不接入 GitHub Actions,避免 CI 环境跨仓不可达制造门禁失效

---

## 8. 与既有产物关系

| 既有产物 | 关系 | 本文档动作 |
|---|---|---|
| R37 §6.1 P0 必修第 3 件 | "check-doc-drift.sh(新,固化表名白名单)" | 实质化设计 |
| R37 §5.1 表名漂移 8 张 | 5 处失真 fixture 来源 | 复用 + 加链接失效 + API 锚点 |
| R42-D 三模式 + 哨兵范式 | warning/strict/self-test + 5 哨兵 | 复用 + 适配文档场景 |
| 兄弟 R39 check-contract-tri-source.sh | 病根 ④ 端点三向对账 | 不重叠,本 R43-β 病根 ③ 文档维度 |
| 兄弟 R39 R39-根因反思-20260918.md | spec=26 contract=0 be=229 fe=120 失衡比 | 引用 §2.1 数字,补 49 页 API 锚点维度 |
| log.md R37 §6.1 登记 | check-doc-drift.sh 待补 | append R43-β 段 |
| 看板镜像 R37 §6.1 卡段 | check-doc-drift.sh 待补 | append R43-β 卡段 |

---

## 9. 留给 R43-β 二轮(脚本落地)

| # | 内容 | 撞车风险 | 备注 |
|---|---|---|---|
| R43-β-1 | `scripts/check-doc-drift.sh` 实际脚本(150-200 行,沿用 R42-D 范式) | 中(兄弟 wt-r39-integration 可能仍在改 scripts/ 区) | 等 R39 合入 main 后再做 |
| R43-β-2 | fixture 5 处实测自证能红 | 低(临时目录隔离) | 同 R42-D §3.3 |
| R43-β-3 | 兄弟 R39 合入后,如已建 `r38-5-gates.yml`,建议追加 L2 静态扫描 trigger | 中(改兄弟 R39 已有 workflow) | 等兄弟拍板 |

---

## 10. 一句话给 owner

R25 病根 ③ 文档失真门禁 = `check-doc-drift.sh` 设计稿已就绪(本 markdown),5 处失真 fixture + 3 模式 + 4 哨兵 + 自证能红方法 = R43-β 二轮脚本落地的完整 spec。**撞车 0**,不动兄弟 R39 任何在途文件。本轮不写 scripts/(留给二轮,等 R39 合入 main + owner 拍板范围),不写 workflow(沿用 R42-D §4 同理,跨仓 CI 不可达)。**R43-β 二轮建议撞车评估再做** —— 现在 wt-r39-integration 还在改 scripts/check-doc-db-drift.sh + 新建 check-contract-tri-source.sh,本会话撞车评估做不了,等兄弟合入后再说。

---

*文档作者:主协调会话,2026-09-18。*
*承接:R37 §6.1 P0 必修第 3 件 + R43-evolver agent 报告 + owner「系统性梳理全局项目还有哪些待办事项完整执行」。*
*执行项:1 张新设计 markdown + log.md append + 看板镜像 append(撞车 0)。*
*撞车 0,脚本留 R43-β 二轮。*
