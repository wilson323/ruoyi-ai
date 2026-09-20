# BCP-014-browser-business-testing-适配设计-20260920

> **BCP 编号**：BCP-014（飞轮首批 BCP 第 14 项 — 最佳实践系统性梳理）
> **R 段**：R141
> **A 智能体独占**（agency-harness）
> **撞车 0 让路**：✅ docs/ 白名单（**不实装 webapp-testing 流程实质**）
> **拍板位**：**B 类 7d 自动 sign-off**（BP-008~012）+ **C 类 14d owner 必拍**（BP-015）

---

## §一 源材料

`/Users/mac/Documents/最佳实践/考拉搞AI/2026-05-03_一天一个SKILL——前端最佳自动化测试 webapp-testing.md`

**源材料性质**：微信公众号文章（非 SKILL.md 实质定义文件），核心是 4 字诀侦察先行 + 黄金组合 + 实战 demo。本设计文档将该原则**适配**到本项目 IPD 前后端测试场景。

---

## §二 4 字诀侦察先行（本项目适配版）

### 2.1 ① lsof + curl 健康检查（Reconnaissance First）

**原文**：检查服务器：lsof -i :端口 + curl 健康检查，确保服务活着。

**本项目落地**：
- 已覆盖：`scripts/check-pre-commit.sh`（含 `drift` / `contract` / `untracked` / `fast` 4 模式 + R-5 五必现查）
- 扩展点：BP-005 health 模式（lsof + curl 真活探针）— 落 `scripts/check-pre-commit.sh health` 子命令
- 撞车 0 让路：✅ scripts-only 白名单

### 2.2 ② wait_for_load_state('networkidle')（SPA Ready）

**原文**：等待页面就绪 — 对于 React/Vue 这种 SPA 特别重要。

**本项目落地**：
- 主仓：不适用（后端无 SPA）
- 前端仓：SOP 段落写入 `apps/web-antd/docs/`（BP-010 落档，前端仓 A 智能体独占）
- 撞车 0 让路：⚠️ docs-only，前端仓不动实质代码

### 2.3 ③ 前后截图取证（Before/After Evidence）

**原文**：拍照取证：截图当前页面状态，留下「案发现场」+ 截图「作案后」状态。

**本项目落地**：
- 已覆盖：`docs/superpowers/plans/2026-09-17-discolocal-gen-test.md`（49KB，**discolocal 截图测试已落地**）
- 扩展点：BP-011 在文档中追加「侦察先行 4 字诀 + 黄金组合」章节引用
- 撞车 0 让路：✅ docs-only

### 2.4 ④ 捕获网络请求报错（Network Error Capture）

**原文**：检查网络请求有没有报错。

**本项目落地**：
- 已覆盖：`scripts/check-e2e-fe-be.sh`（前后端 e2e 阻塞门禁）
- 扩展点：复用现有机制，**不新建**
- 撞车 0 让路：✅ scripts-only

---

## §三 黄金组合（Gold Combination）

**原文**：frontend-design（写好 UI）+ webapp-testing（测好 UI）+ systematic-debugging（修好 Bug）+ **verification-before-completion（验证通过才说「搞定」）**

### 3.1 frontend-design → 本项目无对应 skill

- 撞车 0 让路：✅ docs-only 标注缺口，不新建
- 建议 owner：未来如引入 frontend-design skill，可落档 `.claude/skills/frontend-design/`

### 3.2 webapp-testing → 本项目

- 已覆盖：discolocal 截图测试 + 4 字诀适配版
- 新建：`scripts/check-browser-business-test-coverage.sh`（暂缓，本轮 R141 5 脚本已够）

### 3.3 systematic-debugging → R25 五病根框架

- **完全沉淀**：R25 全局系统性梳理 + 5 大病根框架（病根 ①-⑤）
- 撞车 0 让路：✅ docs-only + scripts-only（已有 `check-ssot-drift.sh` / `check-mock-legality.sh` 等）

### 3.4 verification-before-completion → **本项目核心机制**

- **核心沉淀**：每轮 R 段必跑自证能红 + 三源对账 + 撞号自检
- 新增机制：BP-007 自证能红 + FAIL_SEED 双向触发（5 个新脚本标配）
- 撞车 0 让路：✅ scripts-only

---

## §四 Vue 特定检查（BP-012 前端仓专属）

### 4.1 v-html XSS 风险

**原文**：v-html XSS 风险

**本项目落地**：
- 主仓：`scripts/check-a11y-basics.sh`（已含 ARIA 检测，可扩展加 v-html 检测）
- 前端仓：`apps/web-antd/scripts/check-vue-specific.sh`（新建，前端仓 A 智能体落档）
- 撞车 0 让路：✅ scripts-only，前端仓

### 4.2 v-for 配合 key 使用

**原文**：v-for 配合 key 使用

**本项目落地**：
- 前端仓：check-vue-specific.sh 启发式检测（v-for 块是否含 :key）
- 撞车 0 让路：⚠️ docs-only 设计，前端仓落档

### 4.3 响应式解构检查

**原文**：响应式解构检查

**本项目落地**：
- 前端仓：check-vue-specific.sh 检测 `const { x, y } = reactive(...)` 模式（解构会失去响应性）
- 撞车 0 让路：⚠️ docs-only

### 4.4 computed 副作用检查

**原文**：computed 副作用检查

**本项目落地**：
- 前端仓：check-vue-specific.sh 检测 computed 内含异步 / 状态变更
- 撞车 0 让路：⚠️ docs-only

---

## §五 跨仓 pre-commit 三仓共享（BP-015 docs-only）

### 5.1 设计草案

**意图**：将 `check-best-practices-coverage.sh` + 4 专项脚本接入 ruoyi-ai + ruoyi-ipd-web + ZK-IPD 三仓的 pre-commit hook，实现「一次开发三仓一致」。

**撞车 0 让路**：⚠️ docs-only，**不实装**——等 owner 拍板 #6（跨仓 commit 并行授权）

### 5.2 跨仓脚本同步方案（草案）

```bash
# 三仓共享的脚本存储位置（草案）
# /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
# /Users/mac/Documents/ruoyi-ai/scripts/check-naming-convention.sh
# ... (4 专项)

# 三仓 pre-commit hook 引用（草案）
# ruoyi-ai/.claude/hooks/pre-commit → bash /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
# ruoyi-ipd-web/.claude/hooks/pre-commit → bash /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
# ZK-IPD/.claude/hooks/pre-commit → bash /Users/mac/Documents/ruoyi-ai/scripts/check-best-practices-coverage.sh
```

### 5.3 owner 拍板请求

详见 `BCP-014-cross-repo-pre-commit-设计-20260920.md` §八 Owner 拍板请求。

---

## §六 落地路线图

| 阶段 | 产出 | 撞车 0 | 拍板位 |
|---|---|---|---|
| R141（当前） | 4 字诀适配版表 + 黄金组合映射 + Vue 特定检查设计 | ✅ docs-only | B 7d |
| R142+ | 扩展 check-pre-commit.sh health 模式 + discolocal 文档同步引用 | ✅ docs-only + scripts-only | B 7d |
| 等 owner 拍板 #6 | 跨仓 pre-commit 三仓共享实装 | ⚠️ 三仓白名单（**仅 docs 设计**） | **C 14d owner 必拍** |
| 等 owner 拍板 #6 后 | apps/web-antd/scripts/check-vue-specific.sh 实装 | ⚠️ 前端仓白名单 | **C 14d owner 必拍** |

---

## §七 撞车 0 + 撞号预防自检

### 7.1 撞车 0 让路

- ✅ 仅 `docs/ipd-系统说明/` + `scripts/` + `.claude/hooks/`（docs 设计）+ `.harness/memory/` 强推进白名单
- ✅ 不动 Java 源码 / SQL / 端口 / PID / 兄弟会话 modified
- ✅ Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&`

### 7.2 撞号预防映射表

- ✅ A 智能体独占段号：BCP-Registry §六/§十六/§一 BCP-014 + BCP-Closure-Log §三.3.20/§四
- ✅ 本设计文档不写 §三.3.x / §十一 / §十二 / §十三

---

## §八 关联文档

- `BCP-014-frontend-code-review-适配设计-20260920.md`（BP-001~007 + BP-013 设计）
- `BCP-014-pre-commit-best-practices-hook-设计-20260920.md`（BP-013 docs-only hook 设计）
- `BCP-014-best-practices-ci-workflow-设计-20260920.md`（BP-014 docs-only CI 设计）
- `BCP-014-cross-repo-pre-commit-设计-20260920.md`（BP-015 docs-only 跨仓设计）
- `最佳实践应用登记位-20260920.md`（BP-001~015 条目清单）
- `R141-最佳实践系统性梳理+完整充分应用到本项目开发体系-20260920.md`（治理报告）
- `scripts/check-best-practices-coverage.sh`（主门禁）
- `scripts/check-{naming-convention,doc-code-sync,memory-leak-pattern,a11y-basics}.sh`（4 专项）

---

## §九 黄金组合落地映射

| webapp-testing 黄金组合 | 本项目对应 | 撞车 0 |
|---|---|---|
| frontend-design | **缺口** — 未来如引入 skill 需落 `.claude/skills/` | ✅ docs-only |
| webapp-testing | discolocal 截图测试 + 4 字诀适配版 | ✅ docs-only + scripts-only |
| systematic-debugging | R25 五病根框架 | ✅ 已沉淀 |
| verification-before-completion | R{round} 段必跑自证能红 + 三源对账 + 撞号自检 | ✅ 已沉淀 + 5 新脚本标配 |