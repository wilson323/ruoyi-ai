# 孤儿端点「只减不增」防复发门禁 — 设计草案 V0

> **状态：草案（DESIGN ONLY，未落盘仓库、未改任何脚本）。需 owner 拍板后另立看板卡执行。**
> 生成：2026-09-24T14:13Z 复跑轮 · 全程只读 · 靶脚本 `scripts/check-api-contract-fe-be.mjs`(431 行)

## §0 设计前提（来自部分一实测，非假设）
- P1 门禁当前**完全未接线**：15 个 `.github/workflows/*.yml`、`.claude/hooks/*`、根 package.json（不存在）均不调用；唯一引用是 `scripts/check-contract-tri-source.sh:62,125-128` 的存在性哨兵（不 exec、不继承 exit code）⇒ **改 exit code 语义对既有 CI/调用方零回归**。
- P2 孤儿口径 = **canonical path 去重**（BE 实测 285 个方法级 @*Mapping → 报告仅 96 条）。∴ baseline / 白名单 **主键必须是 path**，method 仅登记为提示，否则豁免不干净。
- P3 归一：`normalizePath()` 把 `{id}`/`${id}`/`{id:.+}` 统一压成 `{VAR}`；登记串入库前必须过同一函数。
- P4 溯源缺口：报告 JSON 无 git HEAD / 无生成命令字段（R212 §六已点名 0921 基线「生成命令未登记」）。baseline 必须自证来源。
- P5 现查存量：**96** 条孤儿 = 拟豁免基线；`orphan_paths=0`、`field_mismatches=15`（15 条为另一维度，本轮不改其语义）。

## §1 靶脚本现状结构（函数级事实，行号取自 a4290782）
| 位置 | 职责 | 与本次改造关系 |
|---|---|---|
| L55-67 `resolveFeRoot()` | IPD_FE_API_DIR(剥 3 层) > 同级推断 > 默认 | 不动 |
| L80-98 `parseArgs()` | 仅 `--strict/--json/--fe-root/--be-root/-h`；未知参数 exit 2 | **改动点 A** |
| L120-152 normalize/keyOf | 路径归一 | 复用做白名单归一 |
| L155-200 `scanFrontend()` | ipdGet/Post/... + requestIpd 双正则 | 不动（=「同轮有前端调用」自动识别源） |
| L203-239 `scanBackend()` | 类级+方法级拼接 | 不动 |
| L242-285 `detectFieldMismatches()` | 变量名错位 | 不动 |
| L288-326 main 前段 + 输入哨兵 | `fe<56 / be<52` → exit 2 | 不动（但阈值需参数化，见 §5） |
| L328-352 (a) orphan_paths | 前端调/后端无 | 不动 |
| L354-370 (b) orphan_endpoints | **后端有/前端未调**，path 去重 | **改动点 C 输入** |
| L375-379 判定 | `pass = !hasP0 && !(strict&&hasP1)` | **改动点 B** |
| L381-402 report | scan/orphan/summary/pass/mode | **改动点 D** |
| L425 `if(!pass) exit(1)` | 唯一 exit(1) 出口 | **改动点 E** |

## §2 最小改动点（5 处 + 2 个新函数，纯增量，扫描逻辑零改动 ⇒ 96/15/0 三个数字不变）
- **A** `parseArgs` L80-98：新增 `--whitelist <p>` `--baseline <p>` `--update-baseline` `--no-ratchet`（逃生阀）；默认 ratchet ON。
- **A'** 新函数 `loadJson(p, {required})`：读+schema 校验，失败 exit 2（复用现有错误风格）。
- **B** 新函数 `classifyOrphans(orphanEndpoints, baselinePaths, whitelist, now)` → `{covered_new:[], exempt_baseline:[], exempt_whitelist:[], violations:[], stale_whitelist:[], expired_whitelist:[]}`；插入点在 L373（字段错位检测之后、判定之前）。
- **C** 判定 L375-379：`hasP0` 保持不变；新增 `hasNewOrphan = violations.length>0`；`pass = !hasP0 && !hasNewOrphan && !failByStrict`。
- **D** report L381-402：加 `scan.git_head`（`git rev-parse HEAD`）、`scan.cmd`、`orphan_gate:{...}` 段；`summary.new_orphan_violations_count`。
- **E** 出口 L425：改为分档 `process.exit(exitCode)`（见 §4）。
- 兼容性结论：无第三方 exec 本脚本（P1）；tri-source 哨兵只判 `-f` 存在性，不受影响；`--json` 只增字段不删字段 ⇒ 已存解析器（R212 证据脚本 / .repowise 读取 `summary.*`）向后兼容。风险点仅一个：**默认 ratchet ON 会让「今天 exit 0」的场景变成可能 exit 2**，故首版建议 `--ratchet` 需显式开启，CI 接线时再翻默认。

## §3 白名单文件 schema
路径（建议）：`docs/ipd-系统说明/api-internal-whitelist.json`（mjs 原生 JSON.parse 可解析，与现有中文文档同目录便于人审；不放 scripts/ 以区分「数据」与「逻辑」）
```
{ "$schema_version": 1,
  "entries": [ { "path": "/api/v1/hr-sync/sync-now",
    "methods": ["POST"], "reason": "运维手动触发 HR 全量同步，仅内网/无 UI 入口",
    "owner_card": "OPS-06", "review_date": "2026-09-24", "expire": "2026-12-31",
    "added_by": "R213-orphan-gate", "evidence": "HrSyncController.java:151" } ] }
```
主键 = `path`（canonical，§0-P2/P3）；`methods` 仅提示；缺 `expire` 视为无限期 ⇒ schema 校验直接 FAIL。

### 3.1 示例条目（4 条，path/行号取自现查 JSON 实测，非杜撰）
| path（canonical） | methods | reason | owner_card | review_date | expire | evidence |
|---|---|---|---|---|---|---|
| /api/v1/hr-sync/sync-now | POST | 运维手动触发全量同步，无 UI | OPS-06 | 2026-09-24 | 2026-12-31 | HrSyncController.java:151 |
| /api/v1/hr-sync/pending-handovers | GET | 值班看板内部对账读口 | OPS-06 | 2026-09-24 | 2026-12-31 | HrSyncController.java:131 |
| /api/v1/person-sync/jobs/{VAR}/retry | POST | 单任务重试，仅告警自动化调用 | SEC-04 | 2026-09-24 | 2026-12-31 | PersonSyncController.java:60 |
| /api/v1/person-sync/jobs/abnormal | GET | 异常巡检内部探针 | OPS-06 | 2026-09-24 | 2026-12-31 | PersonSyncController.java:88 |

### 3.2 白名单入库校验（反「把真缺口偷偷加进白名单」）
1. **卡号必须可验**：`owner_card` 需在看板镜像 `docs/ipd-系统说明/开发计划-看板镜像.md` 中以 `| <KEY> |` 形式存在（现查实测：`OPS-06` 命中 2 次、`SEC-04` 命中 1 次、`15d5e689` 命中 1 次 ⇒ 三条都是真卡）。命中失败 → 门禁 FAIL，不给「随手编个 R999」留缝。
2. **path 必须真实存在于后端扫描集**：登记一条后端没有的路径 → FAIL（防污染 + 防死条目）。
3. **`evidence` 可机械校验**：`File.java:NN` 的 NN 需 ≤ 该文件行数且该行含 `Mapping`；不满足 → FAIL。
4. **过期即失效**：`expire < today` → 该条从豁免集剔除，对应孤儿回落为 violation（默认 WARN 一版、次版 FAIL，给缓冲）。杜绝永久豁免沉积。
5. **反向清账**：白名单里的 path 已不再是孤儿（前端补了调用）→ 报 `stale_whitelist`，按 `--strict` 决定是否 FAIL；防「白名单只增不减」这一新漂移源。
6. **文案防伪**：`reason` 长度 ≥ 12 且不得为泛词（`内部接口`/`待办`/`N/A`）；`review_date` 必须是近 90 天内。

## §4 exit code 语义（含与既有语义的**冲突处置**，必须拍板）
需求书要 `0=通过 / 1=环境错误 / 2=新增孤儿未白名单`。但脚本**现状**是 `0=PASS / 1=P0孤儿路径(或strict字段错位) / 2=脚本输入错误`（L29-32 声明，L94/295/300/311/315/425 实现）。直接照改会把「P0 孤儿路径」从 1 挪到 2 并占用 2 的旧含义 ⇒ 任何按码分支的人都被骗。
| 方案 | 码位分配 | 优 | 劣 |
|---|---|---|---|
| **A（推荐）** 位掩码 | 0 通过 / 1 保留给 P0 孤儿路径 / 2 保留给环境错误 / **4 新增孤儿未白名单** / 可叠加(1\|4=5) | 零回归；`rc&2` vs `rc&4` 能区分「环境坏了」和「真缺口」；CI 可精确提示 | 需求书原文要改 |
| B 原样照搬 | 0 通过 / 1 环境 / 2 新增孤儿 | 与需求书一致 | **静默反转**既有 1/2 含义；本仓有 R212 报告 §六等按 `exit code` 取证的惯例，反转=制造新漂移 |
判定优先级（方案 A）：环境错误(2) > P0 孤儿路径(1) > 新增孤儿(4)，取或。首版落地时以 `--ratchet=off|warn|fail` 三态灰度。

### 4.1 baseline 快照（存量 96 条豁免）
- 文件：`scripts/baselines/api-contract-orphan-baseline.json`（**脚本独占写**，人改即违规 —— 对齐本仓「PENDING 文件只允许脚本生成」红线）
- 生成：`node scripts/check-api-contract-fe-be.mjs --json --update-baseline` ；内容 = `{generated_at, git_head, gen_cmd, fe_root, count, paths:[...]}`（gen_cmd/git_head 直接堵掉 R212 §六点名那条「基线无生成命令」旧账）
- **刷新纪律（棘轮）**：① `--update-baseline` 只允许 **count 单调不增**；② 若磁盘 baseline.count > `git show HEAD:<baseline>` 的 count ⇒ 直接 FAIL（**这是防「删基线重生」绕过的硬闸**，因为绕过者必须改历史才能提交）；③ 收缩时自动裁剪并在 `growth_log` 记 `-N`。
- 输出必带 `vs baseline: -N / +M` 行（与 R212「105→98 净消亡 7」同口径），让复核者不看 diff 也能判方向。
- 白名单与 baseline 的分工：**baseline = 存量旧账（只减不增，人工不可新增）**；**白名单 = 有意的内部/运维接口（人工新增，须卡号+到期）**。二者不可互换，否则新端点能藏进 baseline。

## §5 接线点（三选 + 本仓适配度，实测数据支撑）
现查事实：`git config core.hooksPath` = **`.claude/hooks`** ⇒ `.claude/hooks/pre-commit` 已生效，它 `exec check-pre-commit.sh "$@"`（该脚本有 `all/drift/contract/untracked/fast` 五模式，exit 0/1/2）。本脚本单跑 **0.067s**（120 文件全扫）。
| 接线方式 | 适配度 | 优 | 劣 / 本仓约束 |
|---|---|---|---|
| **A. check-pre-commit.sh 增「门禁 N」**（推荐） | ★★★★★ | 复用既有五模式与 0/1/2 约定；0.067s 可忽略；照 R43-α 先例设「fast 模式也跑」（untracked 门禁就是这么实质化的） | 只拦本机 agent 提交，可 `--no-verify`；需改 check-pre-commit.sh（哨兵段要加 `node` 可用性检查，否则没 node 时误报 env error） |
| **B. GitHub workflow**（仿 `r38-5-gates.yml` 门禁 2 写法） | ★★★☆ | 真拦截（PR 不可绕）；能出 artifact JSON 供报告引用 | **必须双仓 checkout**：脚本 exit 2 的第一因就是 `前端 API 目录不存在`（L293-296）。CI 里要么 checkout ruoyi-ipd-web 并设 `IPD_FE_API_DIR`，要么把前端 api 快照作 artifact 传入；且 `--be-root` 默认写死本机绝对路径（L48），CI 化前必须参数化 —— 这是一条独立的前置子任务 |
| **C. 定期跑（cron / 每轮 R-报告 §基线现查）** | ★★★★ | 零侵入、已验证可行（本次复跑就是证据）；能抓「别的仓改前端导致孤儿复活」 | 事后发现，非阻断；必须落 HEAD+命令+exit code 三件套，否则重演 R212 §六「来源待补」旧账 |
| **D. 复用 `.claude/settings.json` PreToolUse 阻断体系**（本仓特色） | ★★★★ | 现有 `hook-handler.cjs pre-edit` + `sensitive-field-guard.cjs` 已在写文件前拦截；加一条「`scripts/baselines/*.json` 与 `api-internal-whitelist.json` 非脚本会话禁改」正好治「手工改基线」这一最现实绕过路径 | 只拦 agent，不拦人手敲 `vim`/`git commit --no-verify`；须与 A 或 B 组合才有牙齿 |
**推荐组合**：A（本机即时反馈）+ D（防 agent 改基线）先行；B 作为二期硬闸；C 全程保留作回归证据链。

## §6 实施任务拆解（草案，**本卡不执行**；每步含可机械复核的验收条件）
- **S1 去硬编码 + 自证字段**（前置，独立可交付）：`beRoot` 改由 REPO_ROOT 推断；阈值 56/52 参数化；report 增 `scan.git_head/cmd/fe_root`。
  验收：① 双仓环境下 orphan 数仍 = **96**（与本轮 `/tmp/r212-refresh-contract.json` 集合对称差 = 0，防改造引入噪声）；② `--json` 的 git_head == `git rev-parse HEAD`；③ 故意给错 `IPD_FE_API_DIR` → exit 2 且 stderr 指名缺失目录。
- **S2 baseline 落盘 + `--ratchet=warn`**：生成 `scripts/baselines/api-contract-orphan-baseline.json`（含 96 path + git_head + gen_cmd）。
  验收：baseline.paths 与现查集合对称差 = **0**；磁盘 count > `git show HEAD:baseline` count 时门禁非 0；`--update-baseline` 试图上调 count 被拒并有红字原因。
- **S3 白名单校验器**：`api-internal-whitelist.json` + §3.2 六条规则。
  验收（自证能红四例）：伪造卡号 `R9999`→FAIL；真卡 `OPS-06`+真 path→PASS；`expire` 过期→该孤儿回落 violation；后端不存在的 path→FAIL。
- **S4 判定接入 + `--ratchet=fail` + 自证能红**：改动点 B/C/D/E 全接，exit 走方案 A 位掩码。
  验收：worktree 内临时加 `@GetMapping("/zz-probe")` → exit 码含 4 且输出指名该 path；删掉后回 0；脚本头注释退出码表与 R212/AGENTS 文档同步（#118 文档全局一致）。
- **S5 接线 + 证据链**：§5 方案 A（check-pre-commit.sh 门禁 N，fast 模式仍跑）+ D（helper 保护两个数据文件）。
  验收：`check-pre-commit.sh` 五模式全跑通、总增耗时 <1s；`grep -rn check-api-contract-fe-be .claude .github` 有接线命中；执行卡回填真实卡号与 HEAD 证据。

## §7 未决 / 风险（需 owner 拍板，勿由实现者自行决定）
1. **exit code 方案 A vs B**（§4）—— 直接影响既有取证脚本，我推荐 A（位掩码，零反转）。
2. **96 条是否全部进 baseline 豁免**，还是先做一次「真缺口 vs 有意内部口」人工分诊（预估 gates/hr-sync/person-sync/audit-logs 等 ~25 条属后者，宜走白名单而非 baseline，让存量旧账也能被 reason 审一次）。
3. **method 维度要不要收紧**：现状 path 去重会掩盖「同路径另一方法未调」。收紧会一次性放大孤儿数（285-96 的差面），属口径变更，需单独裁决。
4. **跨仓时序**：孤儿可在「前端仓回退」时复活，本机 pre-commit 看不到对仓状态 ⇒ 是否要求 FE 侧也接一道（ruoyi-ipd-web 无 .claude/hooks 体系）。

## §5.1 补充现查（接线影响面的边界，避免误判）
- BE 仓 15 个 workflow 中**无一个**调用靶脚本；`r38-5-gates.yml` 调 tri-source、`r25-root-cause-lint.yml` 调 `check_cross_repo_contract.sh` —— 是**另外两套**契约门禁，改靶脚本 exit code 不影响它们。
- FE 仓自带 `.github/workflows/ipd-contract-check.yml:45 → node scripts/check-ipd-contract.mjs`（**FE 侧独立脚本，已接线**）。∴ 本仓不是"无 CI 契约门禁"，而是"BE 孤儿端点这一维无拦截"。接线模板可直接抄这条 yml（含 checkout 双仓方案），降低 S5 设计不确定性。

## §8 声明
本文为**设计草案**，未落盘仓库、未改 `check-api-contract-fe-be.mjs`/任何 hook/workflow，未跑写命令，未发真库或后端 HTTP 写请求。§6 五步须 owner 拍板 §7 四项后另立看板卡执行；执行卡需回填真实卡号，不得沿用文中示例卡号。
