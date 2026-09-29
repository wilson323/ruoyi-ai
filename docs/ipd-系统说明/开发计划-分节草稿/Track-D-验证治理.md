# Track D — 验证治理 + round-trip proof + 看板同步（分节草稿）

> 产出者：QA Gatekeeper（质量守门人视角）｜日期：2026-09-28｜状态：**DRAFT（待 owner/主协调会话评审后接线）**
> 事实源（只读）：主计划 `开发计划-AI工作界面六阶段小阶段化-20260928.md`（Global Constraints + §0 事实基线）、`ruoyi-ai/AGENTS.md`、`ruoyi-ipd-web/AGENTS.md`、两仓既有门禁脚本（实测盘点见 §0.2）。
> 标注约定：「实测」= 本次在本机取到的原始证据；「推断」= 未实跑的推理；「未取到」= 取证失败或未覆盖。引用配置一律用键名不用行号（Global Constraint 14）。

---

## 0. 设计原则与事实源盘点

### 0.1 为什么这份不是走过场清单

1. **会红才算数**：每条门禁必须给出「故意触发失败」的具体做法 + 期望退出码。本草稿新增的 3 个脚本已在 scratchpad 夹具上**实测自证能红**（§2 附原始输出），连同既有棘轮脚本共 **4 条带实测红证据**。
2. **三证律**（记忆沉淀金标准）：生产就绪验收 = **HTTP + DB 回读 + 浏览器截图**，缺一标 `PARTIAL`，不得标 done。
3. **断言不进脆弱坐标**：门禁只认键名 / 文件名 / 计数棘轮（按文件计数只减不增），不认行号（行号分钟级漂移，AGENTS.md 实测教训）。
4. **门禁失效必须拦**：脚本内部错误走 exit 2（环境错不能静默全过，仿 `check-api-contract-fe-be.mjs` 位 2 语义）。
5. **证据采集本身也要防假绿**：本次实测踩到一坑——`node x | tail -3` 后取 `$?` 拿到的是 `tail` 的退出码（回归判定打印了 ❌ 但 `$?`=0）。凡退出码证据一律无管道直取，或 zsh 取 `pipestatus[1]`（已写入 §4 附录行）。

### 0.2 门禁资产实测盘点（与任务简报的差异修正）

| 简报说法 | 实测结果 | 影响 |
|---|---|---|
| `ruoyi-ipd-web/scripts/check-ipd-frontend-drift.sh`（4 项检查） | **未取到**于该路径；实测位于**后端仓** `ruoyi-ai/scripts/check-ipd-frontend-drift.sh`（内部 `FRONTEND_ROOT=/Users/mac/Documents/ruoyi-ipd-web` 跨仓扫） | 调用命令必须 `cd /Users/mac/Documents/ruoyi-ai && bash scripts/check-ipd-frontend-drift.sh` |
| `ruoyi-ipd-web/scripts/check-api-contract-fe-be.mjs`（位掩码 0/1/2/4） | **未取到**于该路径；实测位于 `ruoyi-ai/scripts/check-api-contract-fe-be.mjs`（依赖 `scripts/api-contract/orphan-gate-lib.mjs` + `scripts/baselines/`） | 同上，从后端仓根跑 |
| `p1-ddl-apply-check.py` | 实测位于 `ruoyi-ai/docs/ipd-系统说明/验收/p1-ddl-apply-check.py`（全程只读 information_schema） | DDL 门禁命令见 §2 D-G10 |
| `manage.py` PLAN 哈希 | 实测位于 `ruoyi-ai/docs/ipd-系统说明/vibe-kanban/manage.py`（PLAN=看板镜像 md，BASE=`http://127.0.0.1:62250`，PROJECT=`ruoyi-ai`） | 看板规程见 §5 |
| —（简报未列） | `ruoyi-ipd-web/scripts/` 另有 `check-ipd-contract.mjs`（0/1/2，契约清单+known-gaps）、`typecheck-error-count.mjs`（红基线棘轮）、`ipd-smoke.mjs`、`http-probe.py`、`_screenshot-gate-detail.mjs`（playwright 截图先例） | 全部纳入 §1/§3 复用 |
| —（简报未列） | `ruoyi-ai/scripts/` 113 个脚本内已有假绿防御族：`check-surefire-fake-green.sh`、`check-test-selection-fake-green.sh`、`check-mock-legality.sh`、`check-mock-data-realism.sh`、`check-test-assertion-history.sh`（**内建 `TAH_FAIL_SEED=1` 自证红**） | 直接编入 D-G09，不另造轮子 |

### 0.3 Track D 写权限与工作方式

allowedPaths（主计划 §1 表）：只读探针 + `docs/ipd-系统说明/验收/**` + SSOT 镜像（`开发计划-看板镜像.md`，**OPS-09 单一写入者=主协调会话串行写**，Track D 子智能体只提交证据）。本文档即 Track D 的验收规约底稿；门禁脚本落地位置建议 `ruoyi-ipd-web/scripts/`（前端三条）与 `ruoyi-ai/scripts/`（后端一条），接线属新增文件，须走 Task 卡获批，**本草稿不改任何既有文件**。

---

## 1. 验证层级矩阵（六层 × 四 Track）

> 层级：L1 语法/编译 → L2 静态分析 → L3 单测 → L4 构建 → L5 集成（真活 HTTP） → L6 业务验收（三证律）。
> 命令铁律（Global Constraint 8/9）：后端 `mvn` 只在 `ruoyi-ai` 仓根、先 `export PATH="$HOME/tools/maven/bin:$PATH"` 与 `export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"`；复核错峰 + 单模块 + 不带 `-am` 不带 `clean`；前端三件套在 `ruoyi-ipd-web` 仓根；本地起服务必须 vite 直起（`node node_modules/vite/bin/vite.js`，pnpm 包装的 vite 在 macOS 锁死 event loop）；跨仓命令必 `cd 绝对路径 &&` 开头。

### 1.1 Track A（后端：小阶段模型 + 技能映射 + AG-UI 工具下发，`ruoyi-modules/ruoyi-ipd`）

| 层 | 命令 | 期望退出码 | 期望输出片段 / 判据 |
|---|---|---|---|
| L1 编译 | `cd /Users/mac/Documents/ruoyi-ai && export PATH="$HOME/tools/maven/bin:$PATH" && export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home" && mvn clean compile -pl ruoyi-modules/ruoyi-ipd -am` | 0 | `BUILD SUCCESS`；`-am` 解析公共依赖（改公共库先 `mvn install -pl <common> -am -DskipTests`） |
| L2 静态 | `cd /Users/mac/Documents/ruoyi-ai && bash scripts/check-surefire-fake-green.sh && bash scripts/check-test-selection-fake-green.sh && bash scripts/check-mock-legality.sh && node scripts/check-api-contract-fe-be.mjs` | 逐条 0（末条位掩码=0） | `check-api-contract-fe-be`：0 孤儿路径 / 0 新孤儿（位 1/4 不置位）；baseline 防伪通过（位 2 不置位） |
| L3 单测 | 复核（错峰）：`cd /Users/mac/Documents/ruoyi-ai && mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=XxxYyy test`；收口（错峰窗口）：`mvn -o -pl ruoyi-modules/ruoyi-ipd test` | 0 | `Tests run: N, Failures: 0, Errors: 0, Skipped: 0` 且 **N>0**（`Tests run: 0` + `BUILD SUCCESS` 即假绿，D-G09 拦）；新测试类带 `@Tag("dev")` |
| L4 构建 | `cd /Users/mac/Documents/ruoyi-ai && mvn -o -pl ruoyi-modules/ruoyi-ipd package -DskipTests`（错峰窗口，避免并发 `target/` 交叉污染） | 0 | `BUILD SUCCESS`；无 `NoClassDefFoundError` 洪泛（出现→先疑假红，§4 类型 4） |
| L5 集成 | `cd /Users/mac/Documents/ruoyi-ipd-web && python3 scripts/http-probe.py`（或 `IPD_SMOKE_BACKEND=1 IPD_SMOKE_USER=<env> IPD_SMOKE_PASS=<env> node scripts/ipd-smoke.mjs`） | 0 | 真业务成功判据 = `http=200 AND biz=0`（分桶输出）；`biz=404` 是 FE-only 假活、`http=500` 是后端 bug；新表/新约束另跑 D-G10 |
| L6 业务验收 | §3 三证律（HTTP + SQL 回读 + 浏览器截图） | — | 三证齐 + §2 门禁全绿才可标 done |

### 1.2 Track B（前端：CopilotKit 纠正 + 画布 + 文档 + 小阶段导航，`views/ipd/**` + `api/ipd/**`）

| 层 | 命令 | 期望退出码 | 期望输出片段 / 判据 |
|---|---|---|---|
| L1 语法 | `cd /Users/mac/Documents/ruoyi-ipd-web && pnpm run check:type`（turbo → vue-tsc） | **当前期望 1**（4 处存量 TS2493，caeb167，§0.2 主计划）；B0 修复后期望 0 | 棘轮口径（D-G01）：`node scripts/typecheck-error-count.mjs --input=<vue-tsc 输出> --baseline=4 --max-new-errors=0 --exit-on-regression` → 0，输出 `✅ typecheck at baseline` |
| L2 静态 | `cd /Users/mac/Documents/ruoyi-ipd-web && node scripts/check-ipd-contract.mjs`；`cd /Users/mac/Documents/ruoyi-ai && bash scripts/check-ipd-frontend-drift.sh`（实测在后端仓）+ `node scripts/check-api-contract-fe-be.mjs`；`node scripts/check-ipd-color-gate.mjs --root …/apps/web-antd/src --baseline …`（D-G02） | 逐条 0 | `check-ipd-contract`：`0=全对齐或仅 known-gap`；漂移守卫 4 项全过；颜色门禁 R1/R2 计数 ≤ 基线且 R3 全对账 |
| L3 单测 | `cd /Users/mac/Documents/ruoyi-ipd-web && pnpm exec vitest run --config vitest.ipd.config.mts`（先跑 D-G05 保证测试不漏执行） | 0 | 2026-09-28 实测基线：`Test Files  138 passed | 5 skipped (143)`、`Tests  1565 passed | 37 skipped (1602)`——多会话增量**只增不减**；`views/ipd/**/*.test.ts` 已在 include 白名单 |
| L4 构建 | `cd /Users/mac/Documents/ruoyi-ipd-web && pnpm run build:antd` | 0 | `11/11 tasks`、web-antd cache miss 真实重建；**且**日志 `grep -c "error TS"` = 0（AGENTS.md 教训：TS4058 曾把退出 0 的构建里声明产物降级 any，退出码不等于无诊断） |
| L5 集成 | `cd /Users/mac/Documents/ruoyi-ipd-web && IPD_SMOKE_USER=<env> IPD_SMOKE_PASS=<env> node scripts/ipd-smoke.mjs` | 0 | SPA 非空 HTML（禁 `-o /dev/null` 只看 200）+ login（code=0+token+traceId）+ `/auth/me` + `/workbench/summary` |
| L6 业务验收 | chrome-devtools MCP 截图 + §3 三证律（AI 引导→写入→回读展示全环） | — | 含 C08 零直写证明（卡片 confirm 不触发任何 `/api/v1` 写） |

### 1.3 Track C（pm-skills 接入 + AI 引导编排，`.agents/skills/pm-*` + `docs/ipd-系统说明/pm-skills映射/**`）

| 层 | 命令 / 方法 | 期望退出码 | 判据 |
|---|---|---|---|
| L1 语法 | — | **N/A（无编译产物；显式写 N/A，禁止写"跳过"充数）** | — |
| L2 静态 | 映射完整性只读探针：`cd /Users/mac/Documents/ruoyi-ai && mysql --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf ipd_dev -e "SELECT COUNT(DISTINCT action_code) FROM stage_actions"` 对照映射表/seed（凭证不上命令行） | — | 69 真动作全覆盖零重复（§2.8 五不变量）；4 条脏数据 `A01/A02/A1/A2` 必须缺席；若走 `.agents/skills/` 镜像，`diff -r` 事实源与镜像一致（`agentscope-harness` 先例，不一致=「文档说 A 模型用 B」） |
| L3 单测 | 映射 seed 校验不变量落 A 侧单测（`@Tag("dev")`）；提示词/技能本身无单测面 | 0 | `Tests run: N>0` |
| L4 构建 | — | N/A | — |
| L5 集成 | CopilotKit 对话触发 `/discover` 等命令链（`ai-assistant.vue` 单对话通道） | — | AG-UI 事件流可观测（`RUN_*`/`TEXT_MESSAGE_*`/`TOOL_CALL_*`/`STATE_DELTA`）；命令完成后**自动建议下一命令**（pm-skills 官方机制）即"依次引导"的运行证 |
| L6 业务验收 | §3 三证律（引导段证据 = 会话/卡片截图 + 事件帧记录） | — | 69 动作的引导提示词逐条可触发（抽样 + 全量清单核对） |

### 1.4 Track E（MCP/Skill 配置中心 UIUX，改存量 4 页 + `Task E-Verify`/`E-A1`）

| 层 | 命令 | 期望退出码 | 判据 |
|---|---|---|---|
| L1 语法 | 前端同 Track B L1；后端：`cd /Users/mac/Documents/ruoyi-ai && mvn -o -pl ruoyi-modules/ruoyi-chat -Dtest=McpConfigWriteOnlyTest test`（错峰） | 0 | E-Verify 测试类带 `@Tag("dev")` 且 `Tests run: N>0` |
| L2 静态 | `21st review`（`data.tsx`/css：0 error，`design-hardcoded-color` 归零或进基线）；D-G02 颜色（views/mcp\|views/agent **零容忍**）；D-G04 MCP 双门禁（字段集 + 零明文渲染） | 逐条 0 | `21st review` 对 `.vue` 输出 `0 file(s)` 属**预期缺口**（§2.5 补位），不得拿它宣称页面 UI 已过审 |
| L3 单测 | `cd /Users/mac/Documents/ruoyi-ipd-web && pnpm exec vitest run --config vitest.ipd.config.mts`（先扩 include 白名单 `views/mcp/**`/`views/agent/**`，D-G05 会红提醒）+ E-Verify 三断言（§2.3） | 0 | 序列化/导出不含 `configJson`/`authConfig`；留空提交保留原值 |
| L4 构建 | `pnpm run build:antd` | 0 | 同 Track B L4（含日志 TS 诊断 grep） |
| L5 集成 | 登录态 HTTP（token TTL 短，先登录）：`GET /mcp/tool/{id}` 响应体**不含** `configJson`；导出端点字节流不含 | — | 用 `scripts/ipd-smoke.mjs` 登录段取票；直连 `http://127.0.0.1:16039` 或走前端代理 `15666` |
| L6 业务验收 | 三证律 + 编辑态空态卡截图（方案 a：「连接配置已安全保存，不显示明文」+「替换配置」+「留空提交保留原值」）+ 留空再提交后 DB 回读 `config_json` 未被清 | — | §0.6 结论 2 方案 a 全交互闭环 |

---

## 2. 门禁清单（会红才算数）

### 2.0 总表

| 编号 | 门禁 | 归属 | 命令（跨仓必 `cd 绝对路径 &&`） | 期望退出码 | 自证能红方式 | 自证红状态 |
|---|---|---|---|---|---|---|
| D-G01 | 前端三件套 + typecheck 红基线棘轮 | B/E | `cd /Users/mac/Documents/ruoyi-ipd-web && pnpm run check:type && pnpm exec vitest run --config vitest.ipd.config.mts && pnpm run build:antd`；棘轮 `node scripts/typecheck-error-count.mjs --input=<out> --baseline=4 --max-new-errors=0 --exit-on-regression` | 三件套：当前 check:type=1（存量）、vitest=0、build=0；棘轮=0 | 构造 5 行 vue-tsc 错误输入 vs `--baseline=4` 必红 | **已实测**（本次） |
| D-G02 | 颜色门禁（Constraint #21） | B/E | `cd /Users/mac/Documents/ruoyi-ipd-web && node scripts/check-ipd-color-gate.mjs --root apps/web-antd/src --baseline scripts/baselines/ipd-color-baseline.json`（脚本草案见 §2.2） | 0=过 / 1=违规 / 2=锚点缺失 | 夹具注入 `#1677ff`、`:root{--primary:…}`、两处不同值 → exit 1 | **已实测**（本次） |
| D-G03 | MCP 凭据 write-only 回归（Task E-Verify） | E/后端 | `cd /Users/mac/Documents/ruoyi-ai && mvn -o -pl ruoyi-modules/ruoyi-chat -Dtest=McpConfigWriteOnlyTest test` | 0 且 `Tests run: 3` | mutation：隔离副本摘掉 `McpToolVo` 的 `@JsonIgnore` → 三断言必红 | 待 mvn 错峰窗口实测（测试草案见 §2.3） |
| D-G04 | MCP 表单字段集 + 零明文渲染双门禁 | E | `cd /Users/mac/Documents/ruoyi-ipd-web && node scripts/check-mcp-config-gates.mjs`（脚本草案见 §2.4） | 位掩码 0=过 / 1=字段漂移 / 2=环境错 / 4=明文渲染（可叠加） | 夹具注入 `field:'env'`、`{{ tool.configJson }}`、后端多读 `timeout` → exit 5 | **已实测**（本次） |
| D-G05 | vitest 测试发现完备性（防假绿⑤） | B/E | `cd /Users/mac/Documents/ruoyi-ipd-web && node scripts/check-vitest-include-coverage.mjs`（脚本草案见 §2.5） | 0=全被发现 / 1=有孤儿测试 / 2=解析失败 | 夹具放 `views/agent/orphan-fixture/orphan.test.ts`（白名单外路径）→ exit 1 | **已实测**（本次） |
| D-G06 | API 契约孤儿棘轮 | A/B/E | `cd /Users/mac/Documents/ruoyi-ai && node scripts/check-api-contract-fe-be.mjs` | 位掩码 0/1/2/4（可叠加） | 脚本内建：R37 自证数据 + 白名单防伪/baseline sha256 + `git show HEAD` 硬闸（改 baseline → exit 2） | 内建（脚本自述） |
| D-G07 | IPD 前端漂移守卫（4 项） | B | `cd /Users/mac/Documents/ruoyi-ai && bash scripts/check-ipd-frontend-drift.sh`（hook 版 `.claude/helpers/ipd-frontend-drift-guard.cjs` 在前端仓） | 0/1/2 | 受控窗口临时建 `views/ipd/<domain>/<domain>-error.ts` → exit 1 → 删除 | 待首次接线实测 |
| D-G08 | 前后端契约对照（known-gaps 登记制） | B | `cd /Users/mac/Documents/ruoyi-ipd-web && node scripts/check-ipd-contract.mjs` | 0=对齐/仅 known-gap / 1=未登记断裂 / 2=清单坏 | 在 `api/ipd/` 加一个指向不存在端点的调用 → exit 1 | 待首次接线实测 |
| D-G09 | Surefire 假绿三连 + 断言历史 | A/E/后端 | `cd /Users/mac/Documents/ruoyi-ai && bash scripts/check-surefire-fake-green.sh && bash scripts/check-test-selection-fake-green.sh && bash scripts/check-mock-legality.sh && TAH_FAIL_SEED=1 bash scripts/check-test-assertion-history.sh`（最后一跑是自证红，**期望 exit 2**，验毕正常跑一遍期望 0） | 正常 0；自证红跑非 0 | `TAH_FAIL_SEED=1` 内建注入 `assertTrue("现状保留")` 模式 | **内建自证红**（`TAH_FAIL_SEED`） |
| D-G10 | DDL apply 核验（SQL 已 commit ≠ 约束已生效） | A | `cd /Users/mac/Documents/ruoyi-ai && python3 docs/ipd-系统说明/验收/p1-ddl-apply-check.py` | 0=全部生效 / 非 0=半套状态 | 历史实证：`person_roles` 超前登记（表未建）被此法抓出；新表 seed 后先跑它再宣称"已迁移" | 历史实证 + 待新表实测 |
| D-G11 | 三证律证据完整性 + 看板回读 | 全 Track | 证据核对清单（§3.4）+ `python3 docs/ipd-系统说明/vibe-kanban/manage.py` 对账 + 独立 GET 回读 | 缺任一证 → 卡面强制 `◐ PARTIAL` | 喂一份缺 DB 回读的样例报告 → 核对清单必须判 PARTIAL | 待首次验收实测（样例可即造） |
| D-G12 | 21st `.vue` 缺口补位覆盖 | B/E | `21st review`（tsx/css）+ chrome-devtools MCP（`lighthouse_audit`/`take_snapshot`/a11y）+ D-G02 + `check-ipd-frontend-drift.sh` | 补位证据四选二以上留证 | 验收报告模板缺 a11y/截图证据栏 → 核对清单判不通过 | 待首次验收实测 |

**统计：门禁 12 条；自证能红方案 12/12 全覆盖；其中本次已实测红证据 4 条（D-G01/02/04/05），内建自证红 2 条（D-G06 防伪、D-G09 `TAH_FAIL_SEED`），待接线时按给定步骤实测 6 条（D-G03/07/08/10/11/12）。**

### 2.1 前端三件套 + 存量红基线（4 处 TS2493）处置

**现状（主计划 §0.2 复测，2026-09-28）**：`check:type` FAIL，4 处 TS2493 全在 `views/ipd/_shared/ai-assistant.test.ts`（键：`fetcher.mock.calls.filter(([url]) => …)` 的元组解构），来自已提交 commit `caeb167`（HEAD 自带红）；vitest 138 文件/1565 用例 PASS；build:antd PASS。stash 对照两次 vue-tsc 输出 `diff IDENTICAL` 已证明后续改动零新增（该因果证明保留有效）。

**处置推荐：修掉（B0 任务），不做白名单长期化。** 理由：
1. **量小且同模式**：4 处同文件同写法（mock 调用元组解构），是 mock 弱类型问题（`vi.fn()` 无签名 → `calls: [][]`，解构 `[url]` 触发 TS2493「Tuple of length 0 has no element at index 0」【推断，基于 TS2493 语义与代码形态】）。修法二选一：给 `fetcher` mock 显式函数类型（`vi.fn<(url: string, init?: unknown) => Promise<Response>>()`），或解构处改 `calls.map((c) => String(c[0]))`——均为机械改动，不动业务断言。
2. **验收口径冲突**：Track B/E 验收标准是「三绿」；白名单化（`--baseline=4` 长期化）等于把「check:type 恒红」写进流程，每次都要解释，且与 AGENTS.md「不得放宽 tsconfig、跳过文件或假绿」同向。
3. **测试代码类型不安全是假绿温床**：TS2493 恰恰说明 mock 调用签名失真，正是「mock 造死数据」（§4 类型 3）的近邻风险，修掉比压掉更有防御价值。
4. **禁止用 `@ts-expect-error` / `@ts-ignore` 逐行压红**——把断言改成"现状"的变体（§4 类型 2）。

**过渡期兜底（并发窗口内不宜动共享测试文件时）**：`typecheck-error-count.mjs --baseline=4 --max-new-errors=0 --exit-on-regression` 做只减不增棘轮（已实测会红，见下），挂限期卡，**B0 完成后 baseline 收 0 并把 `check:type` 期望退出码翻 0**；棘轮基线数字只允许该脚本产出，禁止手改（对齐 `ratchet-data-guard.cjs` 精神）。

**棘轮自证能红（本次实测，fixture 输入，零仓库侵入）**：

```text
$ node /Users/mac/Documents/ruoyi-ipd-web/scripts/typecheck-error-count.mjs --input=<5 行错误> --baseline=4 --max-new-errors=0 --exit-on-regression
❌ typecheck regression: 1 new error(s) beyond baseline 4
exit=1                        ← 期望红，命中
$ node … --input=<4 行错误> --baseline=4 … --exit-on-regression
✅ typecheck at baseline: 4 errors (no regression)
exit=0                        ← 期望绿，命中
```

### 2.2 颜色门禁（Constraint #21 机器化锁）

**一句话方案**：以 `styles/ipd-tokens.css` 的 `:root` 为唯一色值真值源，脚本扫 `apps/web-antd/src` 全量 `.vue/.ts/.css`——硬编码 hex/rgb/hsl 只许出现在 `--token:` 声明行（其余违规，按文件计数基线棘轮只减不增）、任何 CSS 覆写 `--primary/--success/--warning/--destructive` 即红、`preferences.ts` 与 `bootstrap.ts` 四主题色必须两处同值且逐值等于 `--ipd-blue/green/amber/red`，任一不符 exit 1。

**规则与实测状态**：

| 规则 | 锁什么 | 真仓实测（2026-09-28） |
|---|---|---|
| R1 硬编码色字面量 | Constraint #21 禁令①（hex/rgb/hsl 一律走 `var(--ipd-*)`） | 存量 **50 文件 / 480 处**（含 `views/ipd/**` 旧页、workflow-designer、`ipd-theme.css` 7 处——即 §0.7.1 登记的存量债），须以 `--update-baseline` 生成基线后**只减不增**；`views/mcp/**`+`views/agent/**` **0 处**（Track E 零容忍区当前干净，实测） |
| R2 禁止 CSS 覆写组件主色 | Constraint #21 新增红线（`:root{--primary:…}` 是被 inline 压掉的无效代码，D1） | 存量 1 处（`views/ipd/_shared/ipd-theme.css`，已登记存量债）→ 进基线；新增即红 |
| R3 两处同值 + token 对账 | preferences.ts ↔ bootstrap.ts 同改同提交 + 与 `ipd-tokens.css` 对账 | **当前 PASS**（实测：`colorPrimary=#245bf4`/`colorSuccess=#2f9e52`/`colorWarning=#c98313`/`colorDestructive=#e45757` 两处同值且 = `--ipd-blue/green/amber/red`） |

**豁免与边界**：行内 `ipd-color-ok` 标记（须附理由）豁免并计数上报；`rgb(var(--x))`/`hsl(var(--x))` 形态按 token 合法用法放行（实测修掉过此误报：`editor.vue` 的 `hsl(var(--primary))`）；`*.test.ts` 不扫 R1（组件测试夹具可含色值）；`preferences.ts`/`bootstrap.ts` 不扫 R1（它们是 R3 锚点，值必须是 hex）；基线按「文件→条数」计数，**不记行号**（防行号漂移）。

**脚本草案（= 本次实测的代码原样，路径键名化）**：

```js
#!/usr/bin/env node
/**
 * check-ipd-color-gate.mjs — IPD 颜色系统门禁（锁 Global Constraint #21）
 * 规则：
 *   R1 硬编码色字面量（#hex / rgb( / hsl( ）只允许出现在 CSS 自定义属性声明行
 *      （`--token: …`）——token 定义文件是唯一合法位置；其余一律违规。
 *      行内豁免标记：行含 `ipd-color-ok`（须带理由注释），计数上报。
 *   R2 禁止 `--primary/--success/--warning/--destructive` 的 CSS 覆写（无效代码红线，
 *      组件层主色唯一入口 = preferences.ts + bootstrap.ts）。
 *   R3 两处同值 + 与 token 真值源对账：
 *      preferences.ts 与 bootstrap.ts 的 colorPrimary/Success/Warning/Destructive
 *      必须同值，且逐值等于 styles/ipd-tokens.css 的
 *      --ipd-blue/--ipd-green/--ipd-amber/--ipd-red（:root 段）。
 * 基线：R1/R2 违规按「文件 → 条数」计数棘轮只减不增（不记行号，防行号漂移）。
 * 退出码：0=通过  1=违规/基线被突破  2=脚本或输入错误
 * 用法：node check-ipd-color-gate.mjs --root <srcDir> [--baseline f.json] [--update-baseline]
 */
import { readFileSync, writeFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

const args = process.argv.slice(2);
const getArg = (k) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : undefined; };
const ROOT = getArg('root');
if (!ROOT || !existsSync(ROOT)) { console.error('[color-gate] --root 缺失或不存在'); process.exit(2); }
const BASELINE = getArg('baseline');
const UPDATE = args.includes('--update-baseline');

const ANCHOR_PREF = join(ROOT, 'preferences.ts');
const ANCHOR_BOOT = join(ROOT, 'bootstrap.ts');
const ANCHOR_TOKENS = join(ROOT, 'styles', 'ipd-tokens.css');
for (const f of [ANCHOR_PREF, ANCHOR_BOOT, ANCHOR_TOKENS]) {
  if (!existsSync(f)) { console.error(`[color-gate] 锚点文件缺失(不得删除以规避门禁): ${f}`); process.exit(2); }
}

const COLOR_KEY_MAP = { colorPrimary: '--ipd-blue', colorSuccess: '--ipd-green', colorWarning: '--ipd-amber', colorDestructive: '--ipd-red' };
const errors = []; const pragmas = []; const counts = {}; // rel -> {r1, r2}
const bump = (rel, rule) => { counts[rel] ??= { r1: 0, r2: 0 }; counts[rel][rule]++; };

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (name === 'node_modules' || name.startsWith('.')) continue;
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (/\.(vue|ts|tsx|css)$/.test(name)) yield p;
  }
}

const HEX = /#[0-9a-fA-F]{3,8}\b/;
const FUNC = /\b(?:rgb|rgba|hsl|hsla)\s*\(/;
const CUSTOM_PROP = /(?:^|[\s;{])--[\w-]+\s*:/;
const FORBID_OVERRIDE = /--(?:primary|success|warning|destructive)\s*:/;

for (const abs of walk(ROOT)) {
  const rel = relative(ROOT, abs).split(sep).join('/');
  const isAnchor = abs === ANCHOR_PREF || abs === ANCHOR_BOOT;
  if (/\.test\.tsx?$/.test(rel)) continue; // 测试夹具不扫 R1（组件测试断言里可含色值）
  const lines = readFileSync(abs, 'utf8').split('\n');
  lines.forEach((line, idx) => {
    const t = line.trim();
    if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*') || t.startsWith('<!--')) return;
    if (t.includes('ipd-color-ok')) { pragmas.push(`${rel}:${idx + 1}`); return; }
    if (!isAnchor && /\.(vue|ts|tsx|css)$/.test(rel)) {
      const tokenUse = /var\s*\(\s*--/.test(line); // rgb(var(--x)) / hsl(var(--x)) 属 token 合法用法
      if ((HEX.test(line) || FUNC.test(line)) && !CUSTOM_PROP.test(line) && !tokenUse) {
        bump(rel, 'r1'); errors.push(`R1 硬编码色 ${rel}:${idx + 1} → ${t.slice(0, 80)}`);
      }
    }
    if (/\.css$/.test(rel) && FORBID_OVERRIDE.test(line)) {
      bump(rel, 'r2'); errors.push(`R2 禁止 CSS 覆写组件主色 --primary/… ${rel}:${idx + 1}`);
    }
  });
}

// ---- R3 两处同值 + token 对账 ----
function extractKeyed(src, keys, re) {
  const out = {};
  for (const k of keys) {
    const m = [...src.matchAll(new RegExp(re.source.replace('KEY', k), 'g'))];
    if (m.length === 0) return { error: `缺少 ${k}` };
    const vals = [...new Set(m.map((x) => x[1].toLowerCase()))];
    if (vals.length > 1) return { error: `${k} 同文件多值: ${vals.join(' / ')}` };
    out[k] = vals[0];
  }
  return out;
}
function rootBlock(css) {
  const i = css.indexOf(':root');
  if (i < 0) return '';
  const start = css.indexOf('{', i);
  let depth = 0;
  for (let j = start; j < css.length; j++) {
    if (css[j] === '{') depth++;
    else if (css[j] === '}') { depth--; if (depth === 0) return css.slice(start, j); }
  }
  return '';
}
const pref = extractKeyed(readFileSync(ANCHOR_PREF, 'utf8'), Object.keys(COLOR_KEY_MAP), /KEY:\s*'([^']+)'/);
const boot = extractKeyed(readFileSync(ANCHOR_BOOT, 'utf8'), Object.keys(COLOR_KEY_MAP), /KEY:\s*'([^']+)'/);
const tokBlock = rootBlock(readFileSync(ANCHOR_TOKENS, 'utf8'));
const tok = {};
for (const tokName of Object.values(COLOR_KEY_MAP)) {
  const m = tokBlock.match(new RegExp(`${tokName}:\\s*(#[0-9a-fA-F]{3,8})`));
  if (!m) { errors.push(`R3 ipd-tokens.css :root 缺 ${tokName}`); continue; }
  tok[tokName] = m[1].toLowerCase();
}
for (const [k, tokName] of Object.entries(COLOR_KEY_MAP)) {
  if (pref.error) { errors.push(`R3 preferences.ts ${pref.error}`); break; }
  if (boot.error) { errors.push(`R3 bootstrap.ts ${boot.error}`); break; }
  if (pref[k] !== boot[k]) errors.push(`R3 两处不同值: ${k} preferences=${pref[k]} bootstrap=${boot[k]}`);
  if (tok[tokName] && pref[k] !== tok[tokName]) errors.push(`R3 与 token 不一致: ${k}=${pref[k]} 应等于 ${tokName}=${tok[tokName]}`);
}

// ---- 基线棘轮（按文件计数，只减不增） ----
let baseline = { files: {} };
if (BASELINE && existsSync(BASELINE)) baseline = JSON.parse(readFileSync(BASELINE, 'utf8'));
const regressions = [];
for (const [rel, c] of Object.entries(counts)) {
  const b = baseline.files[rel] ?? { r1: 0, r2: 0 };
  if (c.r1 > (b.r1 ?? 0)) regressions.push(`${rel} R1 ${b.r1 ?? 0}→${c.r1} 超基线`);
  if (c.r2 > (b.r2 ?? 0)) regressions.push(`${rel} R2 ${b.r2 ?? 0}→${c.r2} 超基线`);
}
if (UPDATE && BASELINE) {
  writeFileSync(BASELINE, JSON.stringify({ files: counts }, null, 2) + '\n');
  console.log(`[color-gate] baseline 已更新: ${BASELINE}`);
}
console.log(`[color-gate] R1/R2 违规计数: ${JSON.stringify(counts)} ipd-color-ok 豁免: ${pragmas.length} 处`);
errors.slice(0, 20).forEach((e) => console.log('  ' + e));
regressions.forEach((e) => console.log('  [REGRESSION] ' + e));
if (errors.some((e) => e.startsWith('R3')) || regressions.length > 0) {
  console.log('[color-gate] FAIL'); process.exit(1);
}
if (errors.length > 0 && Object.keys(baseline.files).length === 0 && !UPDATE) {
  console.log('[color-gate] FAIL（存在违规且无基线）'); process.exit(1);
}
console.log('[color-gate] PASS'); process.exit(0);
```
**自证能红实测（本次，scratchpad 夹具，未碰任何仓库文件）**：

```text
[干净夹具：token 用法 + 两处同值]  exit=0  [color-gate] PASS
[违规夹具：注入 3 类违规]          exit=1  [color-gate] FAIL
  R1 硬编码色 views/mcp/tool/bad-hardcolor.vue:3 → .btn { color: #1677ff; border: 1px solid rgba(36, 91, 244, 0.3); }
  R2 禁止 CSS 覆写组件主色 --primary/… views/mcp/tool/bad-override.css:1
  R3 两处不同值: colorWarning preferences=#ff9900 bootstrap=#c98313
  R3 与 token 不一致: colorWarning=#ff9900 应等于 --ipd-amber=#c98313
  [REGRESSION] views/mcp/tool/bad-hardcolor.vue R1 0→1 超基线
```

（附注：单行多个色字面量按行计 1 条；基线口径与之一致即可。真仓全量跑出 50 文件/480 处 + R2 1 处的存量清单已在脚本输出中逐文件计数，接线时以 `--update-baseline` 生成 `scripts/baselines/ipd-color-baseline.json` 为种子。）

### 2.3 MCP 凭据 write-only 门禁（Task E-Verify + 前端半区）

**后端三断言（测试草案，落 `ruoyi-modules/ruoyi-chat/src/test/java/…/McpConfigWriteOnlyTest.java`，必须 `@Tag("dev")`，错峰跑 `mvn -o -pl ruoyi-modules/ruoyi-chat -Dtest=McpConfigWriteOnlyTest test`）**：

```java
@Tag("dev")
class McpConfigWriteOnlyTest {
    private final ObjectMapper om = new ObjectMapper();

    /** 断言1：Jackson 序列化不含 configJson 字段名与密文值（@JsonIgnore 回归） */
    @Test void serializedResponseOmitsConfigJson() throws Exception {
        McpToolVo vo = new McpToolVo();
        vo.setConfigJson("{\"command\":\"x\",\"args\":[\"--api-key\",\"SECRET-VALUE\"]}");
        String json = om.writeValueAsString(vo);
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("configJson"));
        org.junit.jupiter.api.Assertions.assertFalse(json.contains("SECRET-VALUE"));
        McpMarketVo mv = new McpMarketVo();
        mv.setAuthConfig("{\"token\":\"SECRET-TOKEN\"}");
        String mjson = om.writeValueAsString(mv);
        org.junit.jupiter.api.Assertions.assertFalse(mjson.contains("authConfig"));
        org.junit.jupiter.api.Assertions.assertFalse(mjson.contains("SECRET-TOKEN"));
    }

    /** 断言2：Excel 导出字段清单不含 configJson/authConfig（@ExcelIgnoreUnannotated 回归） */
    @Test void exportColumnsOmitSecrets() {
        var fields = cn.idev.excel.util.FieldUtils.getAllFields(McpToolVo.class); // 以实际导出字段解析口径为准【推断：具体 API 以实现时核对】
        org.junit.jupiter.api.Assertions.assertFalse(
            java.util.Arrays.stream(fields).map(java.lang.reflect.Field::getName)
                .toList().contains("configJson"));
    }

    /** 断言3：留空提交保留原值（applyWriteOnlyConfigPolicy 语义回归） */
    @Test void blankEditPreservesStoredValue() {
        // 直接对 McpToolServiceImpl.applyWriteOnlyConfigPolicy 的 update 语义断言：
        // configJson 传 null/空 → update.setConfigJson(null)（= 不覆盖列），非空 → 覆写。
        // 具体调用形态以实现时读 McpToolServiceImpl（键 applyWriteOnlyConfigPolicy）为准【推断】。
    }
}
```

**自证能红（mutation 法，接线时在隔离 worktree/副本执行）**：摘掉 `McpToolVo` 的 `@JsonIgnore`（键：`configJson` 字段相邻注解）→ 断言1 必红；给 `McpToolVo.configJson` 加 `@ExcelProperty` → 断言2 必红；把 `applyWriteOnlyConfigPolicy` 的留空分支改成 `setConfigJson("")` → 断言3 必红。三红各验一次后还原。**教训来源（§0.6）：断言字段泄漏必须读相邻注解，只 grep 字段行曾导致假红误报。**

**前端半区（无明文渲染）**：
1. **静态**：已并入 D-G04 位 4（`{{ }}` 插值 / `v-html` 出现 `configJson|authConfig` 即 exit 位 4，实测见 §2.4）。
2. **组件测试**（扩 include 后落 `views/mcp/**`）：渲染编辑抽屉快照断言——连接配置区为「空态卡 + 替换配置按钮」，密钥类控件 `type=password`，`getByText(/SECRET|api[_-]?key.*=\s*\S+/i)` 为空集【草案】。
3. **运行态**：三证律 E3 截图必须含编辑态空态卡（§3），与「不得把编辑时字段空白当 bug 修好」（Constraint 19②）互证。

### 2.4 表单字段集 + 零明文渲染双门禁（D-G04）

**机器化方案**：脚本内置契约常量 `CONTRACT_KEYS = [command, args, baseUrl]`（= §0.6 实测后端真实读取键集）；扫后端 `LangChain4jMcpToolProviderService` 的 `configNode.has/get/path` 键集必须**恰等**于契约（多读/少读都红，逼出「新增键先 ADR 再改常量」）；扫前端 `views/mcp|views/agent|api/mcp|api/agent` 的 `field/dataIndex/prop` 落连接键域的必须 ⊆ 契约，`env/headers` 在 Task E-A1 落地前出现即红（防「填了没用」假成功界面，Constraint 20）；同脚本位 4 拦凭据明文渲染。位掩码退出码（0/1/2/4）对齐仓内既有约定。

**脚本草案（= 本次实测代码原样）**：

```js
#!/usr/bin/env node
/**
 * check-mcp-config-gates.mjs — MCP 配置面双门禁（位掩码退出码，仿 check-api-contract-fe-be 约定）
 *   位 1 (1)：表单字段集漂移 / 后端读取键漂移（锁 Global Constraint #20 + §0.6 结论 1）
 *            - 后端 LangChain4jMcpToolProviderService 的 configNode.has/get 键集
 *              必须恰为 {command, args, baseUrl}（新增读取键必须先改本契约常量 + ADR）
 *            - 前端连接表单字段（field: '...'）落在连接键域 {command,args,baseUrl,env,headers,
 *              configJson,authConfig,timeout,sslContext} 的必须 ⊆ {command,args,baseUrl}
 *            - `env` / `headers` 在 Task E-A1 落地前出现即违规（填了没用 = 假成功界面）
 *   位 4 (4)：凭据明文渲染（MCP 凭据 write-only 前端半区，Task E 交互红线 #19）
 *            - {{ }} 插值 / v-html / 文本节点出现 configJson | authConfig 即违规
 *   位 2 (2)：环境或输入错误（锚点文件/目录缺失，门禁自身失效必须拦）
 * 叠加示例：5 = 1|4（字段漂移 + 明文渲染同时发生）
 * 退出码 0 = PASS。
 * 用法：node check-mcp-config-gates.mjs [--be-file <LangChain4jMcpToolProviderService.java>]
 *                                      [--fe-dir <dir> ...]（可重复，默认前端四目录）
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, sep } from 'node:path';

const CONTRACT_KEYS = ['command', 'args', 'baseUrl'];          // §0.6 实测键集（唯一真相源常量）
const CONN_KEY_DOMAIN = ['command', 'args', 'baseUrl', 'env', 'headers', 'configJson', 'authConfig', 'timeout', 'sslContext'];
const FORBIDDEN_UNTIL_EA1 = ['env', 'headers'];

const args = process.argv.slice(2);
const getArg = (k) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : undefined; };
const getArgs = (k) => { const out = []; for (let i = 0; i < args.length; i++) if (args[i] === '--' + k) out.push(args[i + 1]); return out; };
const BE_FILE = getArg('be-file') ?? '/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-chat/src/main/java/org/ruoyi/mcp/service/core/LangChain4jMcpToolProviderService.java';
const FE_DIRS = getArgs('fe-dir').length ? getArgs('fe-dir') : [
  '/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/mcp',
  '/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/agent',
  '/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/api/mcp',
  '/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/api/agent',
];

let bits = 0; const problems = [];
if (!existsSync(BE_FILE)) { console.error(`[mcp-gates] 后端锚点缺失: ${BE_FILE}`); process.exit(2); }
for (const d of FE_DIRS) if (!existsSync(d)) { console.error(`[mcp-gates] 前端目录缺失: ${d}`); process.exit(2); }

// ---- 位1-a：后端真实读取键集 == 契约 ----
const beSrc = readFileSync(BE_FILE, 'utf8');
const beKeys = [...new Set([...beSrc.matchAll(/configNode\.(?:has|get|path)\(\s*"([A-Za-z0-9_]+)"\s*\)/g)].map((m) => m[1]))].sort();
const want = [...CONTRACT_KEYS].sort();
if (JSON.stringify(beKeys) !== JSON.stringify(want)) {
  bits |= 1;
  problems.push(`[字段集] 后端读取键 ${JSON.stringify(beKeys)} ≠ 契约 ${JSON.stringify(want)}（新增键须先 ADR + 改 CONTRACT_KEYS）`);
}

// ---- 前端扫描 ----
function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (name === 'node_modules' || name.startsWith('.')) continue;
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (/\.(vue|ts|tsx)$/.test(name) && !/\.test\.tsx?$/.test(name)) yield p;
  }
}
for (const dir of FE_DIRS) {
  for (const abs of walk(dir)) {
    const rel = abs.split(sep).join('/').split('/src/').pop() ?? abs;
    const lines = readFileSync(abs, 'utf8').split('\n');
    lines.forEach((line, idx) => {
      const t = line.trim();
      if (t.startsWith('//') || t.startsWith('*') || t.startsWith('/*')) return;
      // 位1-b：连接键域字段必须 ⊆ 契约；E-A1 前禁 env/headers
      const fm = line.match(/(?:field|dataIndex|prop):\s*'([A-Za-z0-9_]+)'/g) ?? [];
      for (const hit of fm) {
        const key = hit.match(/'([A-Za-z0-9_]+)'/)[1];
        if (!CONN_KEY_DOMAIN.includes(key)) continue;
        if (FORBIDDEN_UNTIL_EA1.includes(key)) {
          bits |= 1; problems.push(`[字段集] E-A1 未落地前禁止字段 '${key}' ${rel}:${idx + 1}`);
        } else if (!CONTRACT_KEYS.includes(key)) {
          bits |= 1; problems.push(`[字段集] 字段 '${key}' 不在后端真实读取键集 ${rel}:${idx + 1}`);
        }
      }
      // 位4：凭据明文渲染
      if (/\{\{[^}]*(?:configJson|authConfig)[^}]*\}\}/.test(line) || /v-html[^\n]*(?:configJson|authConfig)/.test(line)) {
        bits |= 4; problems.push(`[凭据渲染] 密钥字段进入渲染层 ${rel}:${idx + 1} → ${t.slice(0, 80)}`);
      }
    });
  }
}
problems.slice(0, 20).forEach((p) => console.log('  ' + p));
console.log(`[mcp-gates] backend read keys = ${JSON.stringify(beKeys)}`);
console.log(bits === 0 ? '[mcp-gates] PASS (0)' : `[mcp-gates] FAIL (bits=${bits})`);
process.exit(bits);
```
**自证能红实测（本次）**：

```text
[干净夹具：后端读 command/args/baseUrl + 前端只出契约字段]   exit=0  [mcp-gates] PASS (0)
[违规夹具：后端多读 timeout + 前端 field:'env'/'headers' + {{ tool.configJson }}]
  [字段集] 后端读取键 ["args","baseUrl","command","timeout"] ≠ 契约 […]
  [凭据渲染] 密钥字段进入渲染层 …/tool-drawer.vue:1 → <span>{{ tool.configJson }}</span>
  [字段集] E-A1 未落地前禁止字段 'env' …:3（'headers' 同）
  [mcp-gates] FAIL (bits=5)     exit=5   ← 1|4 精确命中设计
```

**真仓当前状态（实测）**：`backend read keys = ["args","baseUrl","command"]` 与契约一致，前端 4 目录零字段/零明文违规 → **PASS (0)**（E5 未开写，窗口期干净，正是上锁时机）。

### 2.5 vitest 测试发现完备性（D-G05，防假绿第 5 类）

**问题**：`vitest.ipd.config.mts` 的 `include` 白名单（键：`test.include`）现为 6 条 glob，覆盖 `api/ipd`、`router/ipd-guard`、`store`、`views/ipd`、`views/workflow`、workflow-designer 单文件——**不含 `views/mcp/**` 与 `views/agent/**`**（实测）。今天这两目录 0 个测试文件（缺口处于**潜伏态**），但 Track E 一落测试就会出现「文件在、永不执行、vitest 照样全绿」的假绿⑤。真仓实测：143 个测试文件 0 孤儿（当前 PASS）。

**脚本草案（= 本次实测代码原样）**：

```js
#!/usr/bin/env node
/**
 * check-vitest-include-coverage.mjs — 测试发现完备性门禁（防假绿第 5 类：
 * vitest include 白名单不含新目录 → 测试文件存在但从未被执行，"vitest 全绿"是空转）。
 * 判据：src 树下每个 *.test.ts(x) / *.spec.ts(x) 至少命中 vitest 配置 include 之一。
 * 退出码：0=全部被发现  1=存在孤儿测试文件  2=脚本/输入错误（配置解析失败必须拦）
 * 用法：node check-vitest-include-coverage.mjs [--config vitest.ipd.config.mts] [--src apps/web-antd/src]
 */
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, dirname, sep } from 'node:path';

const args = process.argv.slice(2);
const getArg = (k, d) => { const i = args.indexOf('--' + k); return i >= 0 ? args[i + 1] : d; };
const CONFIG = getArg('config', 'vitest.ipd.config.mts');
const SRC = getArg('src', 'apps/web-antd/src');
if (!existsSync(CONFIG) || !existsSync(SRC)) { console.error(`[vitest-include] 配置或源目录缺失: ${CONFIG} / ${SRC}`); process.exit(2); }

const cfgText = readFileSync(CONFIG, 'utf8');
const m = cfgText.match(/include:\s*\[([\s\S]*?)\]/);
if (!m) { console.error('[vitest-include] 解析 include 数组失败（配置格式变更需同步本脚本）'); process.exit(2); }
const globs = [...m[1].matchAll(/'([^']+)'|\"([^\"]+)\"/g)].map((x) => x[1] ?? x[2]);
if (globs.length === 0) { console.error('[vitest-include] include 为空'); process.exit(2); }

function globToRegex(glob) {
  let re = '';
  for (let i = 0; i < glob.length; i++) {
    const c = glob[i];
    if (c === '*') {
      if (glob[i + 1] === '*') {
        if (glob[i + 2] === '/') { re += '(?:[^/]*\\/)*'; i += 2; } else { re += '.*'; i += 1; }
      } else re += '[^/]*';
    } else if (c === '?') re += '[^/]';
    else re += c.replace(/[.+^${}()|[\]\\]/g, '\\$&');
  }
  return new RegExp('^' + re + '$');
}
const res = globs.map(globToRegex);
const cfgDir = dirname(CONFIG); // include glob 以配置文件所在目录为基准

function* walk(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    if (name === 'node_modules' || name.startsWith('.')) continue;
    if (statSync(p).isDirectory()) yield* walk(p);
    else if (/\.((test|spec)\.(ts|tsx))$/.test(name)) yield p;
  }
}
const orphans = []; let total = 0;
for (const abs of walk(SRC)) {
  total++;
  const norm = relative(cfgDir, abs).split(sep).join('/'); // 与 include 同一基准
  if (!res.some((r) => r.test(norm))) orphans.push(norm);
}
console.log(`[vitest-include] 测试文件 ${total} 个, include 模式 ${globs.length} 条, 孤儿 ${orphans.length} 个`);
orphans.forEach((o) => console.log(`  [ORPHAN] ${o} 不被任何 include 命中 → vitest 永远不会执行它`));
if (orphans.length > 0) { console.log('[vitest-include] FAIL'); process.exit(1); }
console.log('[vitest-include] PASS'); process.exit(0);
```
**自证能红实测（本次）**：

```text
[夹具：views/mcp/tool/orphan.test.ts + include 只有 views/ipd]
  [ORPHAN] src/views/mcp/tool/orphan.test.ts 不被任何 include 命中 → vitest 永远不会执行它
  [vitest-include] FAIL   exit=1   ← 期望红，命中
[夹具：include 补上 'src/views/mcp/**/*.test.ts']   exit=0  PASS
[真仓 apps/web-antd/src] 测试文件 143 个, 孤儿 0 个   exit=0  （当前健康，接线后持续守）
```

（过程注：首版脚本 glob 基准目录算错导致夹具误判，**被夹具红测当场抓住**——自证红流程对门禁自身同样有效。）

### 2.6 21st review `.vue` 缺口补位（D-G12）

**缺口（§0.5 实测）**：`21st review` 只解析 `.ts/.tsx/.css/.html`，对 `tool-drawer.vue`/`index.vue` 输出 `0 file(s), 0 finding(s)`；`data.tsx` 才有 1 file。**禁止**声称 21st review 已覆盖页面 UI。

**补位矩阵（Track B/E 的 `.vue` 模板层）**：

| 覆盖面 | 手段 | 证据形态 |
|---|---|---|
| 视觉/颜色/圆角 | D-G02 颜色门禁（扫 `.vue` 全文含 `<style>` 与模板内联样式，正补 21st 缺口）+ `21st review` 对 `data.tsx`/css | 门禁输出 + `21st review` JSON |
| a11y | chrome-devtools MCP `lighthouse_audit`（a11y 类目）+ `take_snapshot`（可访问性树） | 分数 + 违规项列表截图 |
| 交互/渲染正确性 | chrome-devtools MCP `take_screenshot` + 三证律 E3 | 截图（含关键字段值可见） |
| 结构/契约 | `check-ipd-frontend-drift.sh` 4 项 + `check-ipd-contract.mjs` | 退出码 0 |
| 设计上下文一致性 | `.21st/design.json` `--check`（防 `--refresh` 覆盖手动富化，Constraint 17） | `Design context drift detected` 不出现（或刷新走 stash 流程留证） |

**判据**：验收报告（§3.4）必须含上表至少两类 `.vue` 补位证据；`lighthouse` a11y 分数低于基线或出现新 error 级违规 → 不得标 done。降级口径：chrome-devtools MCP 未接通时降级为 playwright 截图脚本（先例 `_screenshot-gate-detail.mjs`），并在报告标注降级。

---

## 3. round-trip proof（三证律）：AI 引导 → 结构化写入既有表 → 回读展示

### 3.1 闭环五段与取证点

```
① AI 引导（CopilotKit 对话/卡片）──② confirm（C08 零直写）──③ 既有 /api/v1 端点写入既有表
        │                                                        │
        │                                              ④ DB 独立回读（SELECT）
        │                                                        │
        └──────────── ⑤ UI 回读展示（重进页面/刷新后截图）←───────┘
```

- ① 证据：对话/卡片截图 + AG-UI 事件帧（四帧通道 `createSseFrameParser`：meta/delta/done/error）或 CopilotKit Inspector tool call status 迁移到 `'complete'`（`never reaches 'complete'` = 服务端 tool 失败，官方判据）。
- ② 证据：vitest C08 断言（`ai-assistant.test.ts` 已有先例：confirm 后 `fetcher.mock.calls` 无 `/api/v1` 写、不多发对话轮）+ Network 面板截图（写请求只来自四帧通道转发的既有端点）。
- ③ 证据：**HTTP 三要素**——请求（脱敏）+ 响应（HTTP status、biz 包络 `code=0`、`message`、`traceId`、业务字段与字符串 ID）+ 时间戳 + 当次 `HEAD`。
- ④ 证据：**SQL 独立回读**（见 3.2）——必须是新开的只读查询，不得复用 ③ 的响应体充当回读。
- ⑤ 证据：**浏览器截图**（见 3.3）——硬刷新后目标页面可见同一批字段值；深管动作含附件/状态/时间的可视呈现。

### 3.2 必采证据清单与命令（每 Task 一包）

落点：`ruoyi-ai/docs/ipd-系统说明/验收/TrackD-<TaskID>-三证律-<yyyymmdd-HHMM>.md` + 同名截图目录（Track D allowedPaths 内）。

| # | 证据 | 命令 / 方法 | 必含字段 |
|---|---|---|---|
| E1 | HTTP 写入响应 | `cd /Users/mac/Documents/ruoyi-ipd-web && python3 scripts/http-probe.py`（全量探针）；单点用 curl 走 `http://127.0.0.1:15666` 代理（凭据从环境变量注入，不入命令行历史、不入库） | 时间戳、HTTP status、`code`/`message` 包络、`traceId`、新记录 ID（字符串）、请求体（密钥位脱敏） |
| E2 | DB 回读 | `cd /Users/mac/Documents/ruoyi-ai && mysql --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf ipd_dev -e "SELECT <关键列> FROM <既有表> WHERE id='<E1 返回 ID>'"` | 查询原文 + 命中行关键列值（与 UI 声称值逐字比对）；新表/新约束另跑 `python3 docs/ipd-系统说明/验收/p1-ddl-apply-check.py` |
| E3 | 浏览器截图 | dev server：`cd /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd && node ../../node_modules/vite/bin/vite.js --port 15666 --host 127.0.0.1`（**cwd 必须 apps/web-antd**，仓根起会 404；禁 pnpm 包装 vite）；chrome-devtools MCP `take_screenshot` / `take_snapshot`；playwright 备用（先例 `scripts/_screenshot-gate-detail.mjs`） | 登录态、目标页全貌 + 关键字段值可读、URL 与时间戳水印（报告内注明） |
| E4 | C08 零直写 | vitest `ai-assistant.test.ts` 对应断言绿 + Network 截图 | confirm 无 `/api/v1` 直写、无第二对话通道 |
| E5 | 负向对照（每闭环至少 1 条） | 反例输入（如轻管备注 >200 字、P10 缺证书编号、阻断动作未完成跳阶）→ 期望 ExceptionBuilder 错误码 + 前端提示 | 请求、错误码、提示截图（防"只测正向"的半套闭环） |

**已知坑（采集时）**：wecom mock login token TTL <60s（http-probe 每 25 请求重登）；真业务成功 = `http=200 AND biz=0`，`biz=404` 是 FE-only 假活、`http=500` 看 `sys-error.log`；IPD `/api/v1` 是 `code=0` 包络 + 字符串 ID + 真实 Person 会话，**不是**框架 `code200/msg`。

### 3.3 三证律判定（D-G11 核对清单）

| 判定 | 条件 | 卡面动作 |
|---|---|---|
| VERIFIED | E1+E2+E3 三证齐（写路径另加 E4；每闭环含 E5），且 §2 相关门禁全绿 | 可标 ✅（走 §5 看板规程） |
| PARTIAL | 任一证缺失 / 只有单测或 mock 绿 / 证据无时间戳或无 HEAD | 强制 `◐`，卡面写明缺哪证 + 补证计划 + 责任泳道 |
| 不计证 | mock 单测绿、编译绿、`check:type` 单独绿、截图但无 DB 回读 | — |

**自证能红**：构造两份样例验收包——A 包三证齐、B 包缺 E2——喂核对清单（人工 checklist 即可，脚本化时同判据）：A 判 VERIFIED、B 必须判 PARTIAL。B 判 done 即门禁失效。

### 3.4 验收报告模板（Track D 落盘格式）

```markdown
## <TaskID> 三证律验收 <yyyymmdd-HHMM>
- HEAD: <sha>（git rev-parse 短哈希，现查现写，禁凭记忆）
- 写路径: <端点/表>（既有表名）
- E1 HTTP: <status>/<code>/<traceId>/<ID> + <时间戳>
- E2 SQL 回读: <SELECT 原文> → <命中行关键列值>
- E3 浏览器: <截图文件>（URL/页面/可见字段值）
- E4 零直写: <vitest 用例名> 绿 / <Network 截图>
- E5 负向对照: <反例> → <错误码> + <截图>
- 门禁: D-G01…D-G12 相关项退出码 <逐条>
- 判定: VERIFIED / PARTIAL（缺：…）
```

---

## 4. 假绿 / 假红对照表（5 类既知形态 + 采集附录）

| # | 形态 | 机制（为什么会"绿得像真的"） | 识别方法 | 防复发门禁 | 自证能红 |
|---|---|---|---|---|---|
| 1 | `@Tag("dev")` 缺失 → Surefire 静默跳过 | 父 pom `<groups>${profiles.active}</groups>`（键：`build.plugins.surefire.groups`）按 profile 过滤；无 tag 的测试类**不进入执行**，构建照报 `BUILD SUCCESS`（实测 2507 用例/238 类全靠 tag） | 看 `Tests run:` 是否 >0；对照 surefire XML 报告份数与测试类数；`Tests run: 0` + SUCCESS = 假绿实锤 | D-G09：`check-surefire-fake-green.sh` + `check-test-selection-fake-green.sh`（判据：每个被 Surefire 识别的测试源至少一个方法真被执行） | 造一个无 `@Tag("dev")` 测试类 → 跑出 0 报告但 SUCCESS，选测门禁必须红 |
| 2 | 断言改成"现状" | 把期望值改成当前实现的输出（如期望异常类型跟着实现改）→ 用例转绿而契约缺口仍在（AGENTS.md 假绿第二形态） | diff 审查：同一 commit 里生产代码与测试断言同向改动；断言期望值必须能溯源到 spec 条目而非实现 | D-G09：`check-test-assertion-history.sh`（内建 `TAH_FAIL_SEED=1` 自证红）+ 收口前确认绿的是**契约**不是既有实现；负向对照 E5 进三证律 | `TAH_FAIL_SEED=1 bash scripts/check-test-assertion-history.sh` → 期望 exit 2（内建注入 `assertTrue("现状保留")`） |
| 3 | mock 造死数据 | mock 造出真库写入路径不可能产生的数据组合（WB-17-1 教训：`PENDING_SECOND` 态配 `confirmerId`、`decision IS NULL` 无生产者）→ 单测全绿但真活卡恒空 | 夹具组合逐字段问"生产者是谁"；mock 断言对照真实写路径；真库冒烟对拍 | D-G09：`check-mock-legality.sh`（stub 替业务/默认值反转等 6 类）+ `check-mock-data-realism.sh` + `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md` 规约 + `.claude/skills/gen-test` 生成规约 | 把死数据组合塞进测试 → mock 合法性门禁必须红【待接线实测】 |
| 4 | 并发构建假红（假绿的镜像） | 多会话共工同一工作树，`-am`/`clean` 交叉重写 `target/` → 大面积 `NoClassDefFoundError`（实测 85 跑 71 Error 中 ≥66 假红）；反向：有人对着假红把真断言删掉 → 变假绿 | Error 面积与改动面不相称即疑；**复跑协议**：错峰 + 单模块 + 不带 `-am` 不带 `clean`（`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test`）复现性比对；证据必带时间戳，下结论前复查命令是否被兄弟会话改写 | 流程门禁：验收报告 L3/L4 证据必须注明"错峰单模块复核"与时间戳（D-G11 核对项）；证据采集退出码直取（附录行） | 不造人工假红（禁止故意污染 target/）；以复跑协议作判据，两次结果不一致 → 不下结论 |
| 5 | vitest include 白名单不含新目录 | `vitest.ipd.config.mts` 的 `test.include` 是显式白名单；`views/mcp/**`/`views/agent/**` 不在列（实测）→ 该处测试**永不执行**，vitest 照样全绿（今天 0 测试文件，缺口潜伏） | 测试文件数 vs vitest 报告文件数对拍；D-G05 直接算孤儿 | D-G05：`check-vitest-include-coverage.mjs`（本次已实测自证能红）；Track E 落测试必须同 commit 扩 include | 夹具放 `views/agent/orphan-fixture/orphan.test.ts` → exit 1（2026-09-28 p13 纠偏：E5-⑥ 后 `views/mcp/**` 已进 include，放该处实测 exit 0 不红，原记载位置过时） |

**附录行（证据采集自身的假绿）**：`node x | tail -3` 后取 `$?` 拿到 `tail` 的退出码——本次实测踩中（回归判定打印 ❌ 而 `$?`=0）。规矩：**退出码证据一律无管道直取，或 zsh 用 `pipestatus[1]`**；验收报告中的退出码必须来自直取形式。

**补充形态（非本表 5 类，但同源）**：`build:antd` 退出 0 但日志含 TS 诊断（TS4058 曾把 `scrollBarRef` 声明降级 any）→ L4 判据强制 `grep -c "error TS" <构建日志>` = 0（已写入 §1.2）。

---

## 5. 看板 / SSOT 同步规程（每 Task 完成后）

### 5.1 单一写入者与防并发覆盖

- **OPS-09**：SSOT 镜像 `ruoyi-ai/docs/ipd-系统说明/开发计划-看板镜像.md` 与本地看板（`http://127.0.0.1:62250`，Vibe Kanban）由**主协调会话串行写**；子智能体/Track D 只提交证据，不直接写卡（Global Constraint 11）。
- **PLAN 哈希防覆盖**：写操作走 `cd /Users/mac/Documents/ruoyi-ai && python3 docs/ipd-系统说明/vibe-kanban/manage.py set …`——写前重算 PLAN 哈希、中途变更即中止；**触发中止 = 重试或让路，禁止绕过**（AGENTS.md 红线）。`manage.py queue` 列剩余叶卡与未完成依赖。
- **多会话认领**：同一指令先登记归属再动手，别各自建卡；兄弟在途接手走 R25 三步（逐一评审处置 + 镜像/log.md 登记 + `ORIGIN-` 前缀保留史实）。

### 5.2 每 Task 完成后的更新动作（固定序列，缺一即卡面滞后于事实）

1. **证据入库**：三证律验收包落 `docs/ipd-系统说明/验收/TrackD-<TaskID>-…`（§3.4 模板），证据带时间戳 + HEAD 现查现写。
2. **SSOT 镜像卡面**：状态符号（`✅` 已验收 / `▶` 进行中 / `◇` 待验证 / `⬜` 待认领 / `◐` 部分/阻塞 / `⊘` 移出）+ 证据摘要（命令、tests 数、HTTP/DB 或浏览器证据、HEAD）+ **前态记录保留**（历史证据不删除）。
3. **看板 PUT**：`manage.py set` 同步源和看板；直接拖卡/MCP 修改后**同轮**回写镜像文件（镜像与看板双向一致）。
4. **独立 GET 回读核验**（看板 PUT 后必须独立 GET 回读，记忆金标准）：重新 `GET` 卡面核对——**验证只认 `desc_len` + marker content**（`updated_at` PUT 后不刷新，实测教训，勿以它当回读证据）；镜像侧对账走 `manage.py` reconcile（含 `check-duplicate-ssot.sh` / `check-ssot-drift.sh` 可用则跑）。
5. **反脆弱指针自查**：证据是否运行态（#110）、SSOT 变更是否看过 diff（#111）、子智能体 brief 含禁 commit 段（#114）、脏树处置（#115）、文档副本同步（#118）。

### 5.3 PARTIAL 标注规则

| 场景 | 标注 |
|---|---|
| 三证缺任一 / 门禁有未过项 | `◐ PARTIAL`，卡面必写：缺哪证、补证计划、责任泳道、预计补证窗口 |
| `check:type` 存量红未清期间的 Track B/E 卡 | 即便功能三证齐，**最多 PARTIAL**（§0.2 主计划先例：不得声称"三绿"）；B0 清红后可翻 ✅ |
| 单测全绿但无 HTTP/DB/浏览器证据 | `◐`（AGENTS.md："Mock/单测绿不等于业务闭环，真库或 HTTP 未过不得伪完成"） |
| 证据过期（HEAD 变更后未复测） | 翻回 `◇ 待验证`，卡面留前态记录 |
| 汇总卡 | 不得凭单测全绿关闭；"所有关联细卡验收且业务链通过"才 ✅（镜像卡面既有铁律） |

---

## 6. 未证明项登记表（静态读码结论 → 可执行回归门禁的升级路径）

> 规则：本表任何一项在升级前，验收报告**只能写「静态确认」，不得写 VERIFIED**。

| # | 结论（当前只能这样说） | 证据等级 | 为何不够 | 升级为可执行门禁 | 负责 |
|---|---|---|---|---|---|
| U1 | `configJson` 响应不回显（`McpToolVo` 的 `@JsonIgnore`，键 `configJson`；`McpMarketVo` 键 `authConfig` 同） | 静态读码（§0.6） | 未跑过 HTTP/单测；曾有只 grep 字段行误判前科 | D-G03 断言1 + 登录态 `GET /mcp/tool/{id}` 响应体断言 | Task E-Verify |
| U2 | 导出不含密钥（类上 `@ExcelIgnoreUnannotated` 且无 `@ExcelProperty`） | 静态读码 | 未见导出字节流 | D-G03 断言2 | Task E-Verify |
| U3 | 留空提交保留原值（`McpToolServiceImpl.applyWriteOnlyConfigPolicy`：留空 → `setConfigJson(null)`） | 静态读码 | 无单测/真库回读 | D-G03 断言3 + 三证律（留空再提交后 SQL 回读 `config_json` 未被清） | Task E-Verify + E5 |
| U4 | LOCAL 只读 `command`+`args`、REMOTE 只读 `baseUrl`（`LangChain4jMcpToolProviderService`） | 静态读码（:444-525 附近，键 `configNode.has("command")` 等） | 无运行态/契约门禁锁 | D-G04（本次已实测锁死键集；待接线常驻） | 已备，待接线 |
| U5 | 颜色两处同值 + token 对账（preferences ↔ bootstrap ↔ ipd-tokens） | **本次实测 PASS**（静态脚本级） | 运行态等效未证（inline 主色 D1/D2 机制只在 §0.7.1 验证过一轮） | D-G02 常驻 + chrome-devtools 读 `getComputedStyle(document.documentElement)` 四色抽查（每涉色 Task 一条） | Track B/E 验收 |
| U6 | 4 处 TS2493 系 caeb167 存量、后续改动零新增 | 主计划 §0.2 stash 对照 `diff IDENTICAL` | "三绿"仍未达成；因果证明≠清红 | D-G01 棘轮（已实测会红）→ B0 修掉后 `check:type` 期望 0 | Task B0 |
| U7 | `21st review` 不覆盖 `.vue` | 实测（`0 file(s)`） | 补位证据尚无采集先例 | D-G12 补位矩阵 + 报告强制栏位 | Track B/E 验收 |
| U8 | vitest 白名单缺口（`views/mcp|views/agent` 潜伏） | 本次实测（0 测试文件） | 缺口未爆发≠不存在 | D-G05 常驻（已实测） | 已备，待接线 |
| U9 | E-Verify/E-A1 改动不破坏 `ChildProcessSecretSanitizer` 保护面（唯一被保护 key `DEEPSEEK_API_KEY`） | 静态读码（§0.6） | E-A1 合并 env 的行为未测 | E-A1 单测：用户 env 含 `DEEPSEEK_API_KEY` → 拒绝（`isProtectedKey` 判定）【E-A1 ADR 获批后】 | Task E-A1 |
| U10 | 22 小阶段/69 动作映射五不变量（§2.8） | 计划文档自洽 | seed/实现未落地，无回归 | A 侧 `@Tag("dev")` 不变量单测 + 真库 `COUNT(DISTINCT action_code)` 对拍 | Task A |
| U11 | 三证律本身在真实 Task 上走通 | 无（流程首次运行即为验证） | — | M1 起每个 Task 照 §3 走，Track D 用 D-G11 核对清单自审 | Track D |

---

## 7. 落地顺序建议（哪个门禁先上，理由是"窗口期"）

1. **即刻（E5 未开写窗口期）**：D-G04 + D-G05 + D-G02 落 `ruoyi-ipd-web/scripts/`（三条均为新增文件）——真仓当前全绿（实测），此刻上锁零存量纠缠；颜色基线 `--update-baseline` 生成后交 `ratchet-data-guard` 精神管辖（只减不增）。
2. **Task B0**：修 4 处 TS2493（§2.1 推荐）→ `check:type` 期望翻 0、棘轮 baseline 收 0；同 commit 顺手把 D-G01 三件套接进 CI/收口清单。
3. **Task E-Verify**：D-G03 三断言 + mutation 自证红三连（隔离副本），把 U1-U3 从静态翻 VERIFIED。
4. **每个 Task 验收时**：D-G11 三证律核对 + D-G12 补位证据 + §5 看板序列（PUT→独立 GET 回读）。
5. **A 落新表时**：D-G10 DDL apply 核验前置于"已迁移"宣称。

### 统计摘要

- **门禁 12 条（D-G01…D-G12），自证能红方案 12/12；本次已实测红证据 4 条（D-G01 棘轮 / D-G02 颜色 / D-G04 MCP 双门禁 / D-G05 vitest 发现），内建自证红 2 条（D-G06 / D-G09），待接线按步骤实测 6 条（D-G03/07/08/10/11/12）。**
- 新增脚本草案 3 份（§2.2/§2.4/2.5，均已在夹具上红绿双向实测；真仓只读试跑取得存量基线数字）。
- 未证明项 11 条（U1-U11），其中 U5/U8 已有本轮实测、U4 已有门禁实测，其余待升级。

> 本文档为分节草稿：与主计划冲突处以主计划 Global Constraints 为准（UNIFIED 口径）；接线任何脚本前须建 Task 卡获批，且新文件不落既有文件之上。
