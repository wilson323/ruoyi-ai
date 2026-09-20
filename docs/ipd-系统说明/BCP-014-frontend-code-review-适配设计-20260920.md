# BCP-014-frontend-code-review-适配设计-20260920

> **BCP 编号**：BCP-014（飞轮首批 BCP 第 14 项 — 最佳实践系统性梳理）
> **R 段**：R141
> **A 智能体独占**（agency-harness）
> **撞车 0 让路**：✅ docs/ 白名单 + .claude/hooks/ 设计文档白名单（**未实装 hook 实质**）
> **拍板位**：**C 类 14d owner 必拍**（BP-013 owner 拍板 #1 后实装 hook 实质）

---

## §一 源材料

`/Users/mac/Documents/最佳实践/考拉搞AI/2026-05-23_一天一个SKILL——前端超级审查员 frontend-code-review.md`

**源材料性质**：微信公众号文章（非 SKILL.md 实质定义文件），核心是 7 维度审查清单 + 报告结构 + 落地建议。本设计文档将该清单**适配**到本项目 IPD 后端 + ruoyi-ipd-web 前端的实际场景。

---

## §二 7 维度审查清单（本项目适配版）

### 2.1 代码质量（Code Quality）

| 维度 | 本项目检查项 | 落点 |
|---|---|---|
| 命名规范 | Java 类 PascalCase / 常量 UPPER_SNAKE_CASE / 前端 kebab-case | `scripts/check-naming-convention.sh`（BP-001，已落档） |
| 函数长度 | 单方法 > 50 行需拆解（启发式） | 待扩展 |
| 重复代码 | DRY 违反：多 Service 重复样板 | 待扩展 |
| 注释与代码一致 | JavaDoc 缺失 + @param 不一致 | `scripts/check-doc-code-sync.sh`（BP-002，已落档） |

**撞车 0 让路**：✅ docs-only + scripts-only 白名单

### 2.2 功能实现（Functional Correctness）

| 维度 | 本项目检查项 | 落点 |
|---|---|---|
| 业务逻辑 | 端点契约对账（前后端 + 合同三向） | `scripts/check-contract-tri-source.sh`（已存在，BCP-006 R-5） |
| 错误处理 | 异常吞噬 + 空 catch + 错误码不一致 | `scripts/check-assertion-line-drift.sh`（已存在，扩展 BP-003） |
| 边界条件 | 入参校验 + 空值处理 + 类型转换 | 已覆盖 `check-module-boundary.sh` 部分 |

### 2.3 性能优化（Performance）

| 维度 | 本项目检查项 | 落点 |
|---|---|---|
| 循环嵌套 | 深嵌套检测（启发式：3 层以上循环） | 待扩展 |
| 内存泄漏 | 事件监听未移除 + 定时器未清理 + WebSocket 未关闭 | `scripts/check-memory-leak-pattern.sh`（BP-008，已落档） |
| 渲染优化 | Vue 响应式依赖 + 重渲染（仅前端） | 待前端仓实现 |

### 2.4 安全性（Security）

| 维度 | 本项目检查项 | 落点 |
|---|---|---|
| XSS（dangerouslySetInnerHTML / v-html） | 前端 XSS 模式检测 | 待前端仓 |
| 敏感信息泄露 | JWT secret / 密码 / API key 硬编码 | `scripts/check-prod-secrets-inlined.sh`（已存在，BP-004 完全覆盖） |
| eval() 使用 | JS eval() 误用 | 待前端仓 |

### 2.5 可访问性 a11y（Accessibility）

| 维度 | 本项目检查项 | 落点 |
|---|---|---|
| 语义化 HTML | 标签正确性（div vs button vs a） | `scripts/check-a11y-basics.sh`（BP-009，已落档，跨仓） |
| ARIA 属性 | aria-* 拼写 + 正确性 | 已覆盖 |
| 键盘导航 | onClick 但无 onKey 事件 | 已覆盖 |
| alt 属性 | <img> alt 缺失 | 已覆盖 |

### 2.6 React 特定（前端）

仅 `apps/web-antd/`（非 React 项目，本项目用 Vue 3）— **不适配**。

### 2.7 Vue 特定（前端）

| 维度 | 本项目检查项 | 落点 |
|---|---|---|
| v-html XSS | 模板内 v-html 使用 | 待前端仓实现 `apps/web-antd/scripts/check-vue-specific.sh`（BP-012，前端仓） |
| v-for key | list key 缺失 | 待前端仓 |
| 响应式解构 | 失去响应性检测 | 待前端仓 |
| computed 副作用 | 计算属性含副作用 | 待前端仓 |

---

## §三 报告结构（审查结果标准输出）

```
[优秀实践]
  - FooService 命名清晰
  - Controller 注解规范

[P0 严重问题 — 必须修复]
  - 安全漏洞 / 逻辑错误 / 契约断裂

[P1 警告 — 建议修复]
  - 性能隐患 / 代码异味 / 错误处理不完善

[P2 建议 — 最佳实践参考]
  - 命名优化 / 注释补全 / 模式升级

[评分]
  0-10 分（S/A/B/C/D/F）

[修复建议]
  每个问题附示例代码

[代码行号引用]
  引用具体行号定位
```

**本项目实现**：每个新门禁脚本输出格式统一遵循上述结构：
- `[PASS]` / `[FAIL]` / `[WARN]` / `[INFO]` 分级
- 引用文件路径 + 行号
- 修复模板（`echo "  修复模板: ..."`）

---

## §四 CI 接入（撞车 0 边界外 / owner 必拍）

### 4.1 GitHub Action 草案（待 owner 拍板 #1）

```yaml
# .github/workflows/best-practices-check.yml（草案）
name: best-practices-check
on:
  pull_request:
    paths:
      - '**/*.java'
      - '**/*.ts'
      - '**/*.tsx'
      - '**/*.vue'
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: naming
        run: bash scripts/check-naming-convention.sh
      - name: doc-sync
        run: bash scripts/check-doc-code-sync.sh
      - name: memory-leak
        run: bash scripts/check-memory-leak-pattern.sh
      - name: a11y
        run: bash scripts/check-a11y-basics.sh
      - name: bp-coverage
        run: bash scripts/check-best-practices-coverage.sh
```

**撞车 0 让路**：本设计文档落档**不实装 CI**，等 owner 拍板 #1 后实装 workflow 文件。

### 4.2 pre-commit hook 接入（同 BCP-010 H5 矩阵）

详见 `BCP-014-pre-commit-best-practices-hook-设计-20260920.md`（同设计文档）。

---

## §五 落地路线图

| 阶段 | 产出 | 撞车 0 | 拍板位 |
|---|---|---|---|
| R141（当前） | 5 个门禁脚本 + 3 个 BCP-014 设计文档 + 登记位 + 治理报告 | ✅ docs-only + scripts-only | A 24h 立即派单 |
| R142+ | 扩展 4 专项脚本覆盖度（启发式阈值调优） | ✅ scripts-only | A 24h |
| 等 owner 拍板 #1 | 实装 pre-commit hook 实质 + GitHub Action workflow | ⚠️ hook/CI 白名单（**仅 docs 设计**） | **C 14d owner 必拍** |
| 等 owner 拍板 #4 | 字符集整改 + DTO 后缀收口 + GitHub Action CI 实质 | ⚠️ docs/scripts 白名单 | **C 14d owner 必拍** |

---

## §六 撞车 0 + 撞号预防自检

### 6.1 撞车 0 让路

- ✅ 仅 `docs/ipd-系统说明/` + `scripts/` + `.claude/hooks/`（docs 设计）+ `.harness/memory/` 强推进白名单
- ✅ 不动 Java 源码 / SQL / 端口 / PID / 兄弟会话 modified
- ✅ Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&`

### 6.2 撞号预防映射表

- ✅ A 智能体独占段号：BCP-Registry §六/§十六/§一 BCP-014 + BCP-Closure-Log §三.3.20/§四
- ✅ 本设计文档不写 §三.3.x / §十一 / §十二 / §十三

---

## §七 关联文档

- `BCP-014-browser-business-testing-适配设计-20260920.md`（BP-008~012 + BP-015 设计）
- `BCP-014-pre-commit-best-practices-hook-设计-20260920.md`（BP-013 docs-only hook 设计）
- `BCP-014-best-practices-ci-workflow-设计-20260920.md`（BP-014 docs-only CI 设计）
- `BCP-014-cross-repo-pre-commit-设计-20260920.md`（BP-015 docs-only 跨仓设计）
- `最佳实践应用登记位-20260920.md`（BP-001~015 条目清单）
- `R141-最佳实践系统性梳理+完整充分应用到本项目开发体系-20260920.md`（治理报告）
- `scripts/check-best-practices-coverage.sh`（主门禁）
- `scripts/check-{naming-convention,doc-code-sync,memory-leak-pattern,a11y-basics}.sh`（4 专项）

---

## §八 Owner 拍板请求（撞车 0 边界外）

### 8.1 BP-013 owner 拍板 #1（启 IPD 后端真活 E2E）

**决策项**：是否授权启用 IPD 后端真活 E2E 阻断门禁？
- 选项 A：授权 → 解锁 pre-commit H5 hook 实质实装（best-practices-check）
- 选项 B：不授权 → 本设计文档保持 docs-only 状态，hook 实质继续 owner-blocked
- 选项 C：部分授权 → 仅启用 docs-only 检查，CI 触发待 owner 二次拍板

### 8.2 BP-014 owner 拍板 #4（DTO 后缀收口 + GitHub Action CI）

**决策项**：是否授权 GitHub Action best-practices-check workflow 自动触发？
- 选项 A：授权 → 启用 `.github/workflows/best-practices-check.yml` 实质
- 选项 B：不授权 → 维持 docs-only，CI 触发依赖 owner 手动

### 8.3 BP-015 owner 拍板 #6（跨仓 commit 并行授权）

**决策项**：是否授权跨仓 pre-commit 三仓共享？
- 选项 A：授权 → ruoyi-ai + ruoyi-ipd-web + ZK-IPD 三仓 pre-commit 同步实装
- 选项 B：不授权 → 维持单仓实装，跨仓需 owner 手动 trigger

**撞车 0 让路**：本设计文档**不实装**任何 hook/CI/跨仓实质，仅 docs-only 落档。owner 拍板前 hook/CI/跨仓代码不入库。