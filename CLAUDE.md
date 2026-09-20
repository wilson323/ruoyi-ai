# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

<!-- OPS-09 主协调会话标识：本机当前主协调会话在此打标，SSOT 写保护（.claude/helpers/ssot-write-guard.cjs）据此放行；非主协调会话不得打标，违规写 SSOT 会被拦 -->
<!-- OPS-09-MAIN-COORDINATOR=true -->

## Project

**本仓库正在从 RuoYi-AI 二开改造为「IPD 产品经理管理系统」**——单企业私有部署的中文 IPD（Integrated Product Development）产品工作平台。基于 `wilson323/ruoyi-ai`（原始基线） fork，保留 RuoYi-AI 的 Spring Boot 3.5.8 + Langchain4j 技术栈，叠加 11 条硬约束（G-01~G-11） + 49 页 IPD 业务页面 + 69 动作 + 5 Gate 双签 + KPI / 奖金池核算。

- **基线**：Spring Boot 3.5.8 + Java 17 + Langchain4j 1.17.2 + Langgraph4j。Parent Maven project (revision `3.1.0`)。多租户、多模型（DeepSeek / Zhipu / OpenAI / etc.）、RAG、MCP tools、Supervisor-mode 多 agent。
- **目标**：IPD 产品经理管理系统。详见 `README-IPD-OVERRIDE.md`（优先级高于本文件）和 `docs/开发说明/`（产品设计）+ `docs/ipd-系统说明/`（改造工程指南）。
- **前端**：拆分独立仓库（`ruoyi-web` / `ruoyi-admin`）；本仓库只含后端。
- **核心信念**：**业务规则高于文档惯例，文档惯例高于系统实现**——开发说明书 G-04 硬约束。

## Build & Run

Build from repo root — this is a parent POM with `<modules>`, never build a submodule in isolation:

```bash
# Full build with tests (tests run with active profile tag, see Testing below)
mvn clean package

# Build skipping tests
mvn clean package -DskipTests

# Run locally (profile defaults to dev via SPRING_PROFILES_ACTIVE env override)
mvn spring-boot:run -pl ruoyi-admin

# Run with a specific profile
mvn spring-boot:run -pl ruoyi-admin -Dspring-boot.run.profiles=prod
# or via Maven profile (also sets profiles.active property)
mvn spring-boot:run -pl ruoyi-admin -Pprod
```

Maven profiles map Spring profiles one-to-one: `-Plocal` → `local`, `-Pdev` → `dev`, `-Pprod` → `prod`. `dev` is `activeByDefault`.

## Testing

**Non-obvious gotcha — read first.** `maven-surefire-plugin` is configured (`pom.xml:464-476`) with `<groups>${profiles.active}</groups>` and `<excludedGroups>exclude</excludedGroups>`. This means:

- Under the default `dev` profile, **only tests annotated `@Tag("dev")` run**. A test without any `@Tag` is silently skipped.
- Tests tagged `@Tag("exclude")` are always skipped.
- To run a single test: `mvn test -Dtest=ClassName#methodName -pl ruoyi-modules/ruoyi-chat`

When adding a new test class, add `@Tag("dev")` (or whichever profile you target) or it won't execute under the standard `mvn test`.

## Module Layout

```
ruoyi-admin/                # Spring Boot main, port 6039, controllers, OpenAPI grouping
ruoyi-common/               # 27 shared library modules (BOM-managed)
  ruoyi-common-core         # utilities, base entities, exceptions
  ruoyi-common-security     # Sa-Token + JWT integration
  ruoyi-common-chat         # Langchain4j adapters, AI helpers
  ruoyi-common-mybatis      # MyBatis-Plus config, paging, tenant filter
  ruoyi-common-redis        # Redisson, distributed locks (lock4j)
  ruoyi-common-web          # web mvc config, XSS filter, rate limiter
  ruoyi-common-websocket    # WS server
  ruoyi-common-sse          # SSE server (default streaming channel)
  ruoyi-common-oss          # AWS S3 / MinIO
  ruoyi-common-encrypt      # mybatis-encryptor, api-decrypt
  ruoyi-common-tenant       # multi-tenant context
  ruoyi-common-trace        # distributed tracing writer (writes to trace_run/trace_node)
  ... (~15 more, see ruoyi-common/pom.xml)
ruoyi-modules/              # Feature modules, each ships its own REST API
  ruoyi-system              # RBAC: user/role/menu/dept/post/dict/config
  ruoyi-chat                # AI chat core: Langchain4j agents, knowledge base, RAG, tools
  ruoyi-workflow            # Warm-Flow BPMN workflow
  ruoyi-aiflow              # Visual AI workflow orchestration (drag-and-drop nodes)
  ruoyi-generator           # Velocity-based code generator
ruoyi-extend/               # Auxiliary Spring Boot apps (run separately, not part of main jar)
  ruoyi-monitor-admin       # Spring Boot Admin server
  ruoyi-snailjob-server     # SnailJob distributed job server
```

## AI Stack

- **Langchain4j**: three BOMs pinned in `pom.xml:55-58` (stable `1.17.2`, community `1.17.0-beta27`, beta `1.17.2-beta27`). Keep them aligned when upgrading.
- **Langgraph4j** `1.8.20` — graph-style agent orchestration.
- **Vector DB**: pluggable, selected by `vector-store.type` in `application.yml`. Default `weaviate` (port `28080` in compose); supports `milvus` (`:19530`) and `qdrant` (`:6334`). **The compose-deployed vector DB must match this setting** or RAG queries silently return empty.
- **Models**: configured at runtime via "Model Management" UI; backend integration covers DeepSeek, Zhipu (with official `zai-sdk`), MIMO, Bailian, OpenAI.
- **RAG**: local knowledge stored as `LocalKnowledge` class/collection; doc parsing handles PDF/Word/Excel/images.
- **MCP tools**: protocol integration; builtin tools exposed to LangChain4j agents via `ToolProvider` beans.
- **Workflow orchestration**: `ruoyi-aiflow` provides a visual designer (frontend) backed by SSE-streamed execution on the backend; nodes include model calls, email send, manual review.

## Key Conventions & Gotchas

- **Port 6039 auto-kill**: `RuoYiAIApplication.main()` (`ruoyi-admin/src/main/java/org/ruoyi/RuoYiAIApplication.java:31-67`) calls `killPortProcess(6039)` before startup. It uses `netstat`/`taskkill` (Windows-style commands) — works on Windows, no-ops cleanly on macOS/Linux. Override with `-Dserver.port=xxxx` if needed.
- **Demo mode** (parent `application.yml`, key `demo.enabled`): **default is now `false`** (R8-P0-2 flipped it). If someone flips it back to `true`, every write operation is blocked with "演示模式，不允许操作" on POST/PATCH/DELETE; the whitelist lives in the same block under `demo.excludes` (login, chat-send, system-session, …). Pass `-Ddemo.enabled=false` when you need deterministic behaviour. Do **not** cite line numbers for these keys — see the "cite keys, not line numbers" rule in `AGENTS.md`.
- **Multi-tenant** is on by default (`tenant.enable=true`). Tenant filter is applied via MyBatis-Plus; tenant-shared tables are listed in the parent `application.yml` under `tenant.excludes`. New shared tables must be added there or they'll be incorrectly filtered. Note `trace_run` and `trace_node` are excluded because the trace writer runs on async threads without tenant context. Registering a table that does not exist yet is possible (and currently done: `person_roles` is listed while no such table exists in any local DB), so the excludes list is not a schema inventory.
- **Sa-Token** JWT secret (parent `application.yml`, key `sa-token.jwt-secret-key`) is `${SA_TOKEN_JWT_SECRET_KEY:}` — an env placeholder **with no inline default**, so a missing env fails fast instead of silently using a shared key. `application-dev.yml` keeps a `devOnly-`prefixed fallback so local bootstrapping stays frictionless; that literal is committed, treat it as public and never reuse it outside dev. Guarded by `CredentialLiteralGuardTest`.
- **Coding harness** (`application.yml:24-32`): an agent runtime with budget limits (`max-iterations: 200`, `max-tool-calls: 600`). The `coding.harness.tools.execute-process.enabled=true` flag enables command execution — be aware this can shell out from inside chat.
- **Annotation processors** (`pom.xml:432-457`): five wired via `maven-compiler-plugin` — `therapi-runtime-javadoc-scribe`, `lombok`, `spring-boot-configuration-processor`, `mapstruct-plus-processor`, `lombok-mapstruct-binding`. Adding a new processor means updating `<annotationProcessorPaths>` or it won't run.
- **Java 17** is required. Virtual threads are gated off (`spring.threads.virtual.enabled: false`); toggle on if running JDK 21+.
- **gRPC version pinning**: `pom.xml:67-70` pins `grpc-bom` to `1.62.2` to resolve Milvus SDK conflicts. Don't upgrade gRPC without re-testing Milvus integration.
- **Lombok + MapStruct-Plus** generate boilerplate; respect `@Data`, `@Builder`, `@RequiredArgsConstructor`, and the `IConvert` source pattern (interface + `Impl` suffix class — generator emits both).
- **Trace package layout**: trace-related code lives under `argtrace.*` (recently moved out of `chat.*` per commit history); new trace code goes there, not in `chat/`.

## Endpoints (dev)

- Backend API: http://localhost:6039
- Springdoc Swagger UI: `/swagger-ui.html` (6 OpenAPI groups defined at `application.yml:227-239`)
- Actuator: `/actuator` — all endpoints exposed; health details `ALWAYS`
- SSE stream (chat): `/resource/sse`
- WebSocket: `/resource/websocket` (off by default — set `websocket.enabled=true`)

## Environment Setup

Quick dev stack (Docker Compose, includes MySQL/Redis/Weaviate/MinIO):

```bash
git clone --depth 1 --branch v3.1.0 https://github.com/ageerle/ruoyi-ai.git
cd ruoyi-ai
cp docs/docker/ruoyi-ai/.env.example docs/docker/ruoyi-ai/.env
docker compose --env-file docs/docker/ruoyi-ai/.env \
  -f docs/docker/ruoyi-ai/docker-compose-all.yaml up -d
```

Compose ports: MySQL `13306`, Redis `26379`, Weaviate `28080`, MinIO `29000`/`29090`, backend `26039`. Override MySQL/MinIO passwords before any non-local deploy.

## Related Repositories (not in this repo)

- `ruoyi-web` — user-facing frontend (Vue 3 + Vben Admin)
- `ruoyi-admin` — admin panel frontend
- `ruoyi-drama` — short-drama composition service (uses `short-drama.composition.*` config in this repo)
- `ruoyi-copilot`, `ruoyi-uniapp` — companion apps

## 自动化栈使用手册

本项目除 Claude Code 内置能力外，挂了 4 层自动化：Skill（用户主动调用）、Subagent（Claude 自动并发调度）、Hook（机器执行拦截 / 提醒）、MCP Server（外部能力）。另由 Ruflo 提供多智能体协同底座。

### Skills（用户主动调用，4 个项目自定义 + 30 个 ruflo 内置）

**项目自定义**（都在 `.claude/skills/<name>/SKILL.md`）：

| Skill | 调用 | 适用场景 |
|---|---|---|
| `/ai-module-add` | user-only | 在 `ruoyi-chat` / `ruoyi-aiflow` 加新 Langchain4j agent、tool、workflow 节点或 MCP 工具。封装了项目约定（包结构、注解模板、MCP 暴露、租户 / 权限约束） |
| `/gen-test` | user-only | 按 `@Tag("dev")` Surefire 过滤规范生成 Service / Controller 单测。封装了 Mockito + AssertJ 模板 + 必须覆盖的 6 个维度 |
| `/api-contract` | user-only | 改了 controller / DTO 后生成 OpenAPI 增量 diff + BREAKING / NEW / CHANGE 分类 + 给 `ruoyi-web` / `ruoyi-admin` 的变更通知草稿 |
| `/db-migration` | user-only | 新增业务表 / 加字段 / 加索引 / 新增 snailjob 任务 / 登记租户共享表。封装 DDL 模板、Entity 必备字段、回滚脚本生成 |

**ruflo 内置 30 个**：默认不主动调，需要时按名字调用。`swarm-orchestration` / `v3-swarm-coordination` / `sparc-methodology` 是重型武器，小改动别上。

### Subagents（Claude 自动调度，4 个并发审查）

按改动范围触发，**不重叠**：

| Agent | 触发时机 | 审查范围 | 交给谁 |
|---|---|---|---|
| `code-reviewer` | 改任何 Java 文件 | 架构、可读性、并发、错误处理、Spring 用法、Lombok / MapStruct-Plus 配合 | AI 安全 / 业务安全 / 性能 |
| `langchain4j-agent-reviewer` | 改 `ruoyi-chat` / `ruoyi-aiflow` | prompt 注入、`@Tool` 暴露、token 成本、MCP 配置、RAG 检索 | 性能 / 业务安全 / 架构 |
| `security-reviewer` | 改 controller / service / config / yml | 多租户过滤、Sa-Token + JWT、API 加解密、XSS、SQL 注入、密钥硬编码 | AI / 性能 / 架构 |
| `performance-analyzer` | 改 mapper / service / AI 模块 | SQL 慢查询与 N+1、Redis、连接池、JVM、Langchain4j token、向量化批处理 | 架构 / 业务安全 / AI 安全 |

**调度规则**：

- 改 1-2 行代码 → 不派 agent（成本不划算）
- 改一个 controller 写操作 → `security-reviewer` + `code-reviewer`（2 个）
- 改 Langchain4j 模块 → `langchain4j-agent-reviewer`（1 个就够）
- 模块级重构（>5 文件）→ 2-3 个 agent 并发，烧 token 但值

### Hooks（机器执行，最硬约束）

写在 `.claude/helpers/*.cjs`（Node.js hook）和 `.claude/hooks/*.sh`（Bash hook），被 `.claude/settings.json` 引用。所有 hook 都通过 `node --check` / `bash -n` + 端到端 12+ 项回归测试。

| Hook | 类型 | 触发 | 行为 |
|---|---|---|---|
| `sensitive-field-guard.cjs` | PreToolUse | Write / Edit / MultiEdit | **阻断** `.env*` / `application-prod.yml` / 含 PEM 私钥内容；**警告** JWT secret / 明文 password 字面量 |
| `pom-edit-hint.cjs` | PostToolUse | Write / Edit / MultiEdit 命中 `**/pom.xml` | **不阻断**，stderr 提示 5 类同步项（langchain4j 多 BOM 对齐、annotation processor、grpc 版本、flatten 插件、surefire groups） |
| `block-dangerous-git.sh` | PreToolUse | Bash | **阻断** `git push` / `git push --force` / `git reset --hard` / `git clean -f[d]` / `git branch -D` / `git checkout .` / `git restore .`（来自 mattpocock-skills `git-guardrails-claude-code`） |

调试命令：

```bash
# 手动测试 hook（模拟 stdin payload）
echo '{"tool_name":"Write","tool_input":{"file_path":"/tmp/.env","content":"x"}}' \
  | node .claude/helpers/sensitive-field-guard.cjs
echo $?  # 期望: 2（阻断）
```

### MCP Servers（项目级 scope，仅本项目生效）

| Server | 命令 | 用途 | 状态 |
|---|---|---|---|
| `context7` | `npx -y @upstash/context7-mcp` | 实时查 Langchain4j / Spring Boot 文档 | ✅ 可用 |
| `github` | `npx -y @modelcontextprotocol/server-github` | 操作 issues / PRs / actions | ⚠️ 需 `GITHUB_PERSONAL_ACCESS_TOKEN` 环境变量才能调用；补 token：`claude mcp add github -e GITHUB_PERSONAL_ACCESS_TOKEN=<PAT> -- npx -y @modelcontextprotocol/server-github` |

注册位置：`~/.claude.json → projects[/Users/mac/Documents/ruoyi-ai].mcpServers`，scope = `local`，不会污染其他项目。

### 漂移自检（每次配置变更后跑）

```bash
# 1. 文件存在 + 语法
for f in .claude/skills/{ai-module-add,api-contract,db-migration,gen-test}/SKILL.md \
         .claude/agents/*.md; do [ -f "$f" ] && echo "✅ $f"; done
node --check .claude/helpers/*.cjs
bash -n .claude/hooks/*.sh

# 2. settings.json 合法性 + hook 引用
node -e "JSON.parse(require('fs').readFileSync('.claude/settings.json','utf8'))"

# 3. MCP 注册
node -e 'const j=require("/Users/mac/.claude.json").projects["/Users/mac/Documents/ruoyi-ai"].mcpServers||{}; console.log(Object.keys(j))'

# 4. 4 个 agent 边界节齐全
for a in code-reviewer langchain4j-agent-reviewer performance-analyzer security-reviewer; do
  grep -q "^## 边界" .claude/agents/$a.md && echo "✅ $a" || echo "❌ $a 缺边界节"
done

# 5. Wiki 知识库完整性（karpathy-llm-wiki 验证）
node docs/wiki/wiki-lint.cjs

# 6. IPD 改造合规（ipd-系统说明 自动生成的检查脚本在 docs/ipd-系统说明/改造检查清单.md）
# 手动检查 10 项 grep + CI 集成见 .github/workflows/ipd-migration-check.yml（待新增）
```

### IPD 改造必读（任何二开前必读）

按优先级读这 4 个文件：

1. **`README-IPD-OVERRIDE.md`**（仓库根）—— 改造方向总览，优先级**高于**根目录 README.md
2. **`docs/开发说明/spec/_公共规范.md`** —— UI / 视觉 / 文案 / 术语「宪法」
3. **`docs/开发说明/spec/_导航地图.md`** —— 49 页清单 + 跳转关系 + 权限矩阵
4. **`docs/ipd-系统说明/改造检查清单.md`** —— 静态检查 + CI 集成方案

完整阅读路径详见 `README-IPD-OVERRIDE.md` §6。

**禁止**：改 `docs/开发说明/` 现有任何文件（产品设计文档是「圣经」，不动）。所有修复方案在 `docs/ipd-系统说明/` 下新增。

### Wiki 知识库（RuoYi-AI 基线）

按 karpathy-llm-wiki 工作流生成：

- **入口**：`docs/wiki/wiki/index.md`（21 篇文章清单）
- **模块详解**：`docs/wiki/wiki/modules/<name>.md`（18 篇：admin / chat / aiflow / system / workflow / generator / common + 扩展）
- **跨模块主题**：`docs/wiki/wiki/cross-cutting/<name>.md`（3 篇：架构 / 多租户 / 部署）
- **自动化栈**：`docs/wiki/wiki/automation/claude-code-setup.md`
- **原始材料**：`docs/wiki/raw/<topic>/*.md`（60 个 verbatim 源文件）
- **lint 验证**：`node docs/wiki/wiki-lint.cjs`（每次改 wiki 跑一次）

改造时**先查 wiki**了解 RuoYi-AI 基线实现，再读 `docs/ipd-系统说明/naming-convention.md` 和 `type-mapping.md` 决定新代码怎么写。

### 外部资源（IPD 改造关键事实源，原文已填充）

7 个核心外部资源原文已入库 `docs/ipd-系统说明/外部资源/`（v2 / 历史件已清理，git 历史可查）：

- `IPD系统_AI开发主Prompt_v3.md` ⭐⭐⭐⭐⭐（1377 行，唯一权威规格）
- `IPD系统_六阶段标准动作清单_v3.md` ⭐⭐⭐⭐⭐（69 动作：深管 42 / 轻管 27）
- `IPD系统_五大Gate评审要素_v1.md` ⭐⭐⭐⭐（33 项要素 + 14 否决项）
- `IPD系统_验收清单.md` ⭐⭐⭐⭐（237 条 AC，v2.1）
- `IPD系统_开发执行规则_AI必读.md` ⭐⭐⭐⭐⭐（11 条硬约束 G-01~G-11）
- `IPD系统_冲突裁决与最终待确认清单.md` ⭐⭐⭐⭐
- `IPD系统_待确认决策表_v2.md` ⭐⭐⭐（33 项决策已全部回填 v3）
- 另有 `assets_公共规范-通用.md` / `design-specs_后台-RuoYi-AI.md`（前端规范）与 `mock-data.js`（演示数据，Q2=回款口径）

详见 `docs/ipd-系统说明/fork-原与外部资源清单.md`。

### Ruflo 多智能体协同底座

本项目使用 [Ruflo](https://github.com/ruvnet/ruflo) V3 做多智能体协调（通过 `/ruflo-core:init-project` 初始化）。协调模式、swarm 拓扑、agent prompt 模板见 `.claude-flow/CAPABILITIES.md`、`.claude-flow/config.yaml`、全局 `~/.claude/CLAUDE.md`。

何时启用 Ruflo：

- ✅ 跨 3+ 模块的功能开发、API 大改、安全审计、性能基准
- ❌ 单文件 / 单行改动、配置修改、问答

启用步骤：

```bash
npx claude-flow swarm init --topology hierarchical-mesh --max-agents 8
npx claude-flow hooks route --task "<任务描述>"
```
## Agent skills

### Issue tracker

GitHub Issues（gh CLI）—— origin 是 `wilson323/ruoyi-ai`，upstream 是 `ageerle/ruoyi-ai`。详见 `docs/agents/issue-tracker-github.md`。

### Triage labels

5 个默认标签（needs-triage / needs-info / ready-for-agent / ready-for-human / wontfix）+ 本仓库补充的阶段 / 模块 / 紧急度标签。详见 `docs/agents/triage-labels.md`。

### Domain docs

Single-context 布局（仓库根）。当前未创建 `CONTEXT.md`（按 mattpocock skill 「proceed silently」原则懒加载）。engineering skills 通过 `docs/agents/domain.md` 的「文档地图」了解仓库。详见 `docs/agents/domain.md`。

## 最佳实践应用 SOP（R141 A 智能体落档 · 2026-09-20）

> **来源**：`/Users/mac/Documents/最佳实践/考拉搞AI/` 下两份公众号 SKILL 介绍文（frontend-code-review 7 维度 + webapp-testing 4 字诀）→ R141 系统性梳理后适配到本项目开发体系。
> **闭环状态**：✅ R141 docs 闭环（BCP-014，第 13 BCP 全部 docs-only 闭环达成 100%）。
> **使用要求**：任何 AI 智能体开工前必读本段；提交前必跑主门禁 + 撞号自检 + 自证能红。

### SOP-1 开工前必读（3 件套）

1. **`docs/ipd-系统说明/最佳实践应用登记位-20260920.md`** —— BP-001~015 条目清单（8 字段：编号 / 来源 / 维度 / 摘要 / 适配层级 / 落地方式 / 拍板位 / 自证能红方式）
2. **`docs/ipd-系统说明/BCP-Registry.md §六` + `§十六`** —— 飞轮闭环度量（13/13 = 100%）+ 5 钻撞根因覆盖率（39/80 = 48.75%）+ R141 SOP 复盘
3. **`docs/ipd-系统说明/R141-最佳实践系统性梳理+完整充分应用到本项目开发体系-20260920.md`** —— 治理报告主体（5 阶段 + 三源对账实证段 + 撞车 0 + 撞号预防）

### SOP-2 提交前必跑（5 门禁脚本）

```bash
cd /Users/mac/Documents/ruoyi-ai

# 1. 主门禁：最佳实践应用覆盖度（≥ 80% PASS）
bash scripts/check-best-practices-coverage.sh

# 2. 命名规范（BP-001）
bash scripts/check-naming-convention.sh

# 3. 注释与代码一致（BP-002）
bash scripts/check-doc-code-sync.sh

# 4. 内存泄漏模式（BP-008）
bash scripts/check-memory-leak-pattern.sh

# 5. 可访问性 a11y（BP-009）
bash scripts/check-a11y-basics.sh
```

任何 1 项非零退出 = FAIL；修复后重试。**跳出门禁 = 撞车 0 让路边界严守破例**。

### SOP-3 自证能红 + FAIL_SEED 双向触发（5 脚本标配）

提交前除正常态 PASS 外，必跑 FAIL_SEED 注入验证（避开单绿恐惧）：

```bash
cd /Users/mac/Documents/ruoyi-ai

BP_FAIL_SEED=1 bash scripts/check-best-practices-coverage.sh     # EXIT=1
NAMING_FAIL_SEED=1 bash scripts/check-naming-convention.sh        # EXIT=1
DOCSYNC_FAIL_SEED=1 bash scripts/check-doc-code-sync.sh          # EXIT=1
LEAK_FAIL_SEED=1 bash scripts/check-memory-leak-pattern.sh       # EXIT=1
A11Y_FAIL_SEED=1 bash scripts/check-a11y-basics.sh               # EXIT=1
```

5/5 EXIT=1 = FAIL_SEED 双向触发 PASS（单绿恐惧 = 误报；双绿才算真绿）。

### SOP-4 撞号预防映射表严守（主协调 push 前必跑）

```bash
cd /Users/mac/Documents/ruoyi-ai

# 检查 §十六 落档（仅 R141 A 智能体独占）
grep "^## §十六" docs/ipd-系统说明/BCP-Registry.md  # 1 行

# 检查 §三.3.20 段号唯一（A 独占）
grep -E "^### 3\.20" docs/ipd-系统说明/BCP-Closure-Log.md | sort | uniq -c  # 1 行

# 检查 13/13 闭环数（避开正则误匹配陷阱：必须用「13 BCP CLOSED」格式）
grep "13 BCP CLOSED" docs/ipd-系统说明/BCP-Registry.md  # ≥ 1 行

# 检查 R-7 新钻命中（系统性梳理认知失真）
grep "R-7 系统性梳理认知失真" docs/ipd-系统说明/BCP-Registry.md  # ≥ 2 行（§六 + §十六）
```

任一项 FAIL = 撞号 / 漂移阻断 → 协调 A 智能体修复后重跑。

### SOP-5 撞车 0 让路边界严守（8 红线 100%）

- ✅ 仅 `docs/ipd-系统说明/` + `scripts/` + `.claude/hooks/`（docs 设计）+ `.harness/memory/` 强推进白名单
- ❌ 不动 Java 源码（`microservices/` / `frontend/` / `ruoyi-ipd/` / `ruoyi-ipd-web/` 零修改）
- ❌ 不动 SQL / Flyway（`db/` / `sql/` 零修改）
- ❌ 不抢端口（16039 / 23306 / 8080 / 15666 全部保持）
- ❌ 不杀 PID（34560 / 70554 / 29607 / 65576 全部不撞 ipd_dev）
- ❌ 不动兄弟会话 modified
- ✅ Bash 命令前缀 `cd /Users/mac/Documents/ruoyi-ai &&`
- ✅ A 智能体独占段号（BCP-Registry §十六 + BCP-Closure-Log §三.3.20）

### SOP-6 拍板位三段式（A/B/C 分类基线）

| 类别 | 拍板权属 | SLA | 撞车 0 让路位 | 拍板决策包 |
|---|---|---|---|---|
| **A 类** | AI 自主 | 0d 立即生效 | docs-only 白名单 | paiban-01~06 + 自证能红 PASS 即生效 |
| **B 类** | AI 自主 + 7d 自动 sign-off | D+7 自动（2026-09-27） | docs-only + scripts/ | paiban-07/08/09/10/12/14 |
| **C 类** | **owner 必拍** | D+14 最大破坏重审（2026-10-04） | docs-only 白名单 | paiban-01/02/03/04/05/06/11/13/15/16/17/18 |

### SOP-7 BP-013/014/015 三件套（撞车 0 边界外，等 owner 必拍）

| BP | 实质实装内容 | 拍板位 | docs-only 设计文档 |
|---|---|---|---|
| **BP-013** | `.claude/hooks/pre-commit-best-practices-check.sh` 实质 | **#1 启 IPD 后端真活 E2E** | `BCP-014-pre-commit-best-practices-hook-设计-20260920.md` |
| **BP-014** | `.github/workflows/best-practices-check.yml` 实质 | **#4 DTO 后缀收口** | `BCP-014-pre-commit-best-practices-hook-设计-20260920.md` |
| **BP-015** | ruoyi-ai + ruoyi-ipd-web + ZK-IPD 三仓 pre-commit 共享 | **#6 跨仓 commit 并行授权** | `BCP-014-browser-business-testing-适配设计-20260920.md` |

**owner 拍板前 = 不实装 hook/CI/跨仓实质**，仅 docs-only 落档。撞车 0 让路边界的工程铁律。

### SOP-8 新增/修改 docs 必跑三源对账

```bash
cd /Users/mac/Documents/ruoyi-ai

# 1. log.md R 段（飞轮自举留痕）—— 必须在 R141 段记录本轮变更
grep -A3 "R141" docs/ipd-系统说明/log.md | tail -20  # 应有 R141 段

# 2. BCP-Registry §六 + §十六（飞轮闭环度量 + R141 反思段）
grep "13 BCP CLOSED" docs/ipd-系统说明/BCP-Registry.md  # ≥ 1 行

# 3. BCP-Closure-Log §一 + §三.3.20 + §四（飞轮闭环记录表 + R141 闭环段 + 度量更新）
grep "BCP-014" docs/ipd-系统说明/BCP-Closure-Log.md | head -3  # ≥ 3 行

# 4. 看镜像（如有）—— 4 源全刷同步（避开三源对账漂移）
```

### 登记位引用（SSOT 单一事实源）

- **BP-001~015 条目清单**：`docs/ipd-系统说明/最佳实践应用登记位-20260920.md`
- **R141 治理报告**：`docs/ipd-系统说明/R141-最佳实践系统性梳理+完整充分应用到本项目开发体系-20260920.md`
- **3 个 BCP-014 docs-only 设计文档**：`BCP-014-{frontend-code-review-适配设计,browser-business-testing-适配设计,pre-commit-best-practices-hook-设计}-20260920.md`
- **5 个门禁脚本**：`scripts/check-{best-practices-coverage,naming-convention,doc-code-sync,memory-leak-pattern,a11y-basics}.sh`
- **BCP-Registry 反思段**：`docs/ipd-系统说明/BCP-Registry.md §十六`
- **BCP-Closure-Log 闭环段**：`docs/ipd-系统说明/BCP-Closure-Log.md §三.3.20`
