# R215 增量收口 · 契约采集修复 + 三项只读调查 + 清理清单（20260925）

> **边界声明**（对齐 owner 本轮纪律：严格禁止过度设计、确保全局一致性、该清理的清理掉）：
> 本报告产出 = 1 处最小修复 + 事实清单 + 待拍板清理建议。不发明新机制、不建新台账、不动业务代码。
> 全程只读调查（MySQL 仅 SELECT/SHOW、HTTP 仅 GET）；除 `scripts/check-api-contract-fe-be.mjs`（任务1 指定）与本报告文件外未写任何文件；无任何 git commit/push/add（主会话统一提交）；未执行 DELETE/DDL；未动 Java 源码。

---

## 任务1：契约采集盲区②修复（可写，最小修复）✅

### 修改点

`scripts/check-api-contract-fe-be.mjs` 模式 1（ipdGet/ipdPost/ipdPut/ipdDelete/ipdPatch 调用采集），正则一处：

```diff
- const reIpdCall = /ipd(GET|POST|PUT|DELETE|PATCH)\s*(?:<(?:[^<>]|<[^<>]*>)*>)?\s*\(\s*([`'"])(.+?)\2/gi;
+ const reIpdCall = /ipd(GET|POST|PUT|DELETE|PATCH)\s*(?:<[^()]*>)?\s*\(\s*([`'"])(.+?)\2/gi;
```

- **根因**：旧泛型组 `(?:<(?:[^<>]|<[^<>]*>)*>)?` 是枚举式两层尖括号嵌套；前端唯一三层形态
  `ipdGet<Partial<IpdPage<Record<string, unknown>>>>`（`ruoyi-ipd-web/apps/web-antd/src/api/ipd/compliance.ts:121` fetchAuditTrail）
  只吞外两层、残留 `>>(`，`\s*\(` 无法匹配 → 整条调用漏扫 → `GET /api/v1/compliance/audit-trail/{VAR}/{VAR}` 假孤儿。
- **修法**（最小泛化，注释已按既有「R215 修正」同风格写明推导）：改 `<[^()]*>`。结构不变量 = 泛型段右边界必为紧邻调用开括号 `(` 前的最后一个 `>`；贪婪 `[^()]*` 吃到本调用首个 `(` 前、回溯后 `>` 恰匹配最外层闭角括号，**任意层**嵌套通吃。泛型内含圆括号（函数类型参数）的病态写法本仓为零；若匹配失败可选组回退空匹配，与旧行为一致不产生误采。
- **模式 2（requestIpd/authenticatedRequest/requestPortal）未动**：rg 全量确认该系列无 ≥三层泛型调用，最小修复边界只覆盖模式 1。

### 自证（`node scripts/check-api-contract-fe-be.mjs --json`，修复前 vs 修复后）

| 指标 | before | after | 判定 |
|---|---|---|---|
| fe_calls_non_test | 265 | 266 | 恰好 +1（=fetchAuditTrail 被采到） |
| orphan_paths (P0) | 0 | 0 | 持平 ✅ |
| **orphan_endpoints** | **26** | **25** | **恰好 -1** ✅ |
| 消失的孤儿 | — | `GET /api/v1/compliance/audit-trail/{VAR}/{VAR}` | 命中目标 ✅ |
| 孤儿端点新增 | — | 0 条 | 无副作用 ✅ |
| field_mismatches | 0 | 0 | 不劣化 ✅（明细逐条比对一致） |
| path_var_name_diffs | 24 | 24 | 不劣化 ✅（明细逐条比对一致） |
| exit code | 6 | 0 | 见下方归因说明 |

### ⚠️ 归因说明（诚实边界）：exit 6→0 的翻转**非本修复因果**

修复前后两次运行之间，工作区存在**并行 R217 会话对 `StageActionController.java` 的活跃编辑**（git status: M，+9/-2；本报告落盘时该文件仍在变动中）。before 时的 bit2/bit4（白名单 evidence 行号 87/100 漂移 → 防伪失败 → 2 条 stage-actions violations）在 after 时因该文件行号再次漂移而自愈（当前 87 行为 javadoc、100 行为 return 行，校验结果随并行编辑波动）。**本修复的净效应只以上表中不受该文件影响的指标为准**：fe_calls +1、orphan_endpoints -1（恰为 audit-trail）、mismatches/diffs 明细逐条一致。stage-actions 两条端点在 before/after 均为孤儿（未消失未新增），未被本修复触碰。

---

## 任务2a：R215-DATA · deletion_requests 悬空外键僵尸行（只读调查）✅

**调查方式**：`mysql --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf ipd_dev`（socket 连接，凭证不上命令行），仅 SELECT/SHOW/DESCRIBE。库：ipd_dev（MySQL 8.0.46）。

### 全景（32 行，entity_type × status × 目标行状态）

| entity_type | status | 目标行状态 | 条数 | 语义 |
|---|---|---|---|---|
| cert_templates | DELETED | del_flag='1'（软删存活） | 22 | 审批链正常执行（软删） |
| cert_templates | LEADER_REVIEW | del_flag='0'（存活） | 1 | 在途审批，正常 |
| products | DELETED | del_flag='1' | 2 | 审批链正常执行 |
| products | DELETED | del_flag='0' | 1 | 目标行存在（非悬空，行为略怪但不在本题范围） |
| products | ADMIN_REVIEW | del_flag='0' | 1 | 在途审批，正常 |
| products | LEADER_REVIEW/ADMIN_REVIEW/REJECTED/WITHDRAWN | **目标行不存在** | **4** | **悬空** |
| projects | LEADER_REVIEW | **目标行不存在** | **1** | **悬空** |

### 悬空僵尸行明细（5 条）

| id | entity_type | entity_id | status | create_time | executed_at | entity_snapshot |
|---|---|---|---|---|---|---|
| 2096381708617248770 | products | 2 | LEADER_REVIEW | 2026-09-05 06:35:22 | NULL | `{"k": "v"}` |
| 2096373346072539138 | products | 999 | REJECTED | 2026-09-06 07:02:09 | NULL | `{"name": "test"}` |
| 2096381708998930433 | products | 3 | ADMIN_REVIEW | 2026-09-06 07:35:23 | NULL | `{"k": "v"}` |
| 2096381707870662657 | products | 1 | WITHDRAWN | 2026-09-06 07:35:23 | NULL | `{"k": "v"}` |
| 2101930187108151297 | projects | 1 | LEADER_REVIEW | 2026-09-21 15:03:03 | NULL | `{}` |

- **悬空总数 5**；按 entity_type 分组：products 4（最老 2026-09-05 06:35:22）、projects 1（2026-09-21 15:03:03）、cert_templates 0。
- **成因判定**（三层证据）：
  1. 审批链执行语义 = 原子**软删**目标行（`DeletionRequestServiceImpl.java:279-280`「ADMIN_REVIEW → DELETED 合法（跨域→触发原子软删）」+ `@TableLogic del_flag='1'`）——故已执行链的目标行**仍物理存在**（上表 DELETED 24 条可证）。悬空 5 条 executed_at 全 NULL，**非审批链所致**。
  2. entity_snapshot 为 `{"k":"v"}`/`{"name":"test"}`/`{}` 测试占位数据，非真实业务快照。
  3. entity_id=1/2/3/999 为种子级小值，指向的行从未真实存在。
  → 结论：**SUPER_ADMIN 测试探针残留**（与并行 R217 调查草稿结论一致）。

### 清理方案建议（⚠️ 待 owner 拍板，本轮不执行任何写操作）

> **交叉印证**：并行会话已产出同题草稿 `docs/ipd-系统说明/验收/R217-工具与数据调查-20260925/dangling-fk-cleanup-draft.sql`
> （关联卡 8a088a71 R215-DATA / 5d5c4fcc），其 5 条 id 清单与本报告**逐条一致**、计数一致。
> **全局一致性建议：owner 只拍板一次，以 R217 草稿的软删口径为准执行**，本报告 DELETE 草稿仅作任务字面要求的备选留档。

**方案 A（推荐，与 R217 草稿及 RuoYi @TableLogic 全局惯例一致）——软删：**

```sql
-- ⚠️ 草稿，待 owner 拍板，勿执行
UPDATE deletion_requests
SET del_flag = '1', remark = CONCAT('R215/R217 悬空外键清理 20260925: entity_id 从未真实存在(测试探针)。原remark:', IFNULL(remark,'')), update_by = -1, update_time = NOW()
WHERE del_flag = '0' AND id IN (
  2096381708617248770, 2096373346072539138, 2096381708998930433, 2096381707870662657, 2101930187108151297
);
```

**方案 B（备选，物理 DELETE——任务字面要求留档；理由不推荐：dev 库虽非生产，但 5 条中 2 条 LEADER_REVIEW/1 条 ADMIN_REVIEW 处于"在途"状态机档位，物理删不可逆，软删保留审计痕迹更稳）：**

```sql
-- ⚠️ 草稿，待 owner 拍板，勿执行
DELETE FROM deletion_requests WHERE id IN (
  2096381708617248770, 2096373346072539138, 2096381708998930433, 2096381707870662657, 2101930187108151297
);
```

执行前置核对 SELECT 见 R217 草稿（预期 5 行）。

---

## 任务2b：R215-KANBAN-BUG · vibe-kanban LIST status 过滤失效（只读调查）✅

### 复现（本机 127.0.0.1:62250，Docker `ruoyi-ai-vibe-kanban`，镜像 `ruoyi-ai/vibe-kanban-local:0.0.168`，二进制版本 `v0.0.168-20260202132127`）

| 查询 | 返回 | status 分布 |
|---|---|---|
| `/api/tasks?project_id=01dcf15c-…（ruoyi-ai 项目）` | 529 条 | todo 29 / done 423 / cancelled 59 / inprogress 15 / inreview 3 |
| `…&status=todo` | 529 条 | 与上行完全相同 |
| `…&status=__no_such__`（假值） | 529 条 | 与上行完全相同，**不报错** |

→ `status` 参数被**完全静默忽略**（假值也不校验）。

### 根因定位（上游源码 BloopAI/vibe-kanban，commit 76e06dc6 = tag v0.0.168-20260202132127，与本机二进制版本精确对应；本机无 Rust 源码仓，源码自 GitHub 该 tag 取证）

1. `crates/server/src/routes/tasks.rs:37-40` —— Query 结构体**根本没有定义 status 字段**：
   ```rust
   #[derive(Debug, Serialize, Deserialize)]
   pub struct TaskQuery {
       pub project_id: Uuid,
   }
   ```
2. `crates/server/src/routes/tasks.rs:42-51` —— `get_tasks` 只透传 `query.project_id`；
3. `crates/db/src/models/task.rs:116-164` —— `find_by_project_id_with_attempt_status(pool, project_id)` 的 SQL 为
   `… WHERE t.project_id = $1 ORDER BY t.created_at DESC`，**WHERE 无 status 条件**。

serde 对未知 query 字段默认忽略（无 `deny_unknown_fields`），故 `?status=…` 被静默丢弃。
**定性**：不是「过滤逻辑写错」，而是上游 v0.0.168 **从未实现**服务端 status 过滤（上游设计为 WS 流式全量拉取 + 前端本地分列渲染；前端消费模式见 `stream_tasks_ws` 同文件 :53-）。

### 处置：只出方案，不修

不修理由：① 本机仅有编译产物（容器内 `/opt/vibe-kanban` 二进制 + 宿主 `build/vibe-kanban`），无 Rust 源码仓可写；② 修复非一行级——需改 `TaskQuery` 加 `Option<TaskStatus>` 字段 + handler 透传 + sqlx 动态条件（`query!` 宏对可选条件需改 `query_as`+bind 或 QueryBuilder，3 文件约 10-20 行）；③ 属上游开源项目行为。方案备选：
- **A（零改动，当前可用）**：调用方拿全量后本地按 `status` 过滤（与官方前端一致）。
- **B（上游式补丁）**：按上述 3 处补服务端过滤（若挂卡，建议同步评估升级——上游新版已将 task 域重构为 issue 域，API 形态有变）。
- **C（升级 vibe-kanban）**：项目已宣告 sunsetting（仓库公告），升级需迁移评估，单独挂卡。

### 对账脚本注意事项与复现命令（R217 只读调查补充，原独立报告 kanban-list-status-bug.md 已按防双轨原则并入本节并删除）

- **禁止**在 URL 中追加 `&status=xxx` 并期望服务端过滤（会产生“已过滤”假象）
- 如需统计各状态数量，必须全量拉取后 `collections.Counter(t['status'] for t in tasks)`
- `updated_at` 不可作为“最近变更”排序依据（恒等于 `created_at`）

复现命令（可直接重跑）：

```bash
PID=01dcf15c-86bb-4c7b-957c-8fe44bddd10d
for st in "" "status=todo" "status=done" "statuses=todo" "state=todo"; do
  sep=$([ -z "$st" ] && echo "" || echo "&")
  n=$(curl -s "http://127.0.0.1:62250/api/tasks?project_id=$PID$sep$st" | python3 -c "import json,sys;d=json.load(sys.stdin);t=d.get('data',d);print(len(t) if isinstance(t,list) else len(t.get('tasks',[])))")
  echo "${st:-<none>} -> $n"
done
```

预期输出（全部 529）：

```
<none> -> 529
status=todo -> 529
status=done -> 529
statuses=todo -> 529
state=todo -> 529
```

---

## 任务2c：IpdPermissionCode.java「注解侧无人用」死码清单（事实底账，owner 拍板后清理）✅

**口径**：全仓 2143 个 Java 文件（ruoyi-modules/ruoyi-admin/ruoyi-common/ruoyi-extend/ruoyi-ai/tests，排除 target）全量 grep `@SaCheckPermission(...)`（跨行匹配），提取「字符串字面量权限码」与「IpdPermissionCode.XXX 常量引用」两种形态，与常量表 92 个常量做差集。

**总况**：注解侧常量引用 88 个常量、字符串权限码 182 个（去重）；**注解侧 `ipd:` 裸字面量（不在常量表）= 0 条**——常量化治理反向一致性 100%。

### 死码清单（常量表有、注解侧无人用，共 4 条）

| # | 权限码 | 常量名 | 注解外引用 | 定性与清理建议 |
|---|---|---|---|---|
| 1 | `ipd:stage-action:add` | OPERATION_STAGE_ACTION_INSTANTIATE（IpdPermissionCode.java:22） | IpdRolePermissionCatalog.java:72 | **同值冗余别名**：与 OPERATION_STAGE_ACTION_DELIVERABLE 同值（:21），Controller 3 处注解（StageActionController.java:78/95/108）全用 DELIVERABLE。权限码本身活跃，死的只是 INSTANTIATE 这个名字。可清理=删常量+catalog 引用行，零运行时影响 |
| 2 | `ipd:gate-review:add` | OPERATION_GATE_REVIEW_INITIATE（:48） | IpdRolePermissionCatalog.java:73 | **预留未接线码**：R-NEW-SEC-5（2026-09-07）定义，全 Controller 注解侧 0 引用（gate-review 域现役 Controller 仅 GateMaterialController.java:37 用 list 码）。catalog 已登记 BUSINESS_WRITE。清理需连 catalog 一起出，**须 owner 确认无近期接线计划** |
| 3 | `ipd:gate-review:edit` | OPERATION_GATE_REVIEW_APPROVE（:49） | IpdRolePermissionCatalog.java:74 | 同上（同批预留码） |
| 4 | `ipd:switching-acceptance:admin` | OPERATION_SWITCHING_ACCEPTANCE_ADMIN（:125） | RnewPermissionContractTest.java:112/:175 | **负向契约常量·禁止删除**：常量注释（:117-124）明示被两条契约测试双锁（switchingAcceptanceAdminAliasNotRegistered + switchingControllerDoesNotUseUnregisteredAdminAlias），删除会使契约测试编译失败。**不列入清理**，仅入账备查。前端 switching-acceptance 相关页若存在 `:admin` 权限码镜像，属前端侧冗余，随前端轮清理 |

> 补充背景（对齐任务描述「:admin/:query 镜像」）：后端口径 `ipd:switching-acceptance:query`（:116）**在用**（run/get/list 注解），死的仅 `:admin` 别名一条；若前端硬编码镜像了 `:admin`，对应镜像即冗余，前端轮清理时以本清单为准。

---

## 任务3：stale_whitelist 清理建议（不直接编辑白名单文件，待挂卡人审）✅

**背景**：`docs/ipd-系统说明/api-internal-whitelist.json` 中 compliance 条目原依据 owner 裁决卡 d81af12c（2026-09-07）整体判 B 预留能力登记豁免；前端本轮（R215 GAP-F6）已接线消费（`ruoyi-ipd-web/apps/web-antd/src/api/ipd/compliance.ts`），豁免理由消失。

### 条目原文（3 条任务指定 + 1 条任务1 修复后新增转 stale，共 4 条）

**① `/api/v1/compliance/audit-trail/{VAR}/{VAR}`（GET）**——修复前因采集盲区漏扫未判 stale，任务1 修复后转 stale（本轮新增）：
```json
{ "path": "/api/v1/compliance/audit-trail/{VAR}/{VAR}", "methods": ["GET"],
  "reason": "owner 裁决卡 d81af12c(2026-09-07)：合规审计 4 端点整体判 B 预留能力，保留不建前端不删除",
  "owner_card": "7b76b7cd", "review_date": "2026-09-24", "expire": "2026-12-31", "added_by": "7b76b7cd", "evidence": "ComplianceController.java:65" }
```

**② `/api/v1/compliance/data-retention-rules`（GET）**：
```json
{ "path": "/api/v1/compliance/data-retention-rules", "methods": ["GET"],
  "reason": "owner 裁决卡 d81af12c：数据保留规则属 B 预留能力，ZK-IPD spec 49 页无合规页",
  "owner_card": "7b76b7cd", "review_date": "2026-09-24", "expire": "2026-12-31", "added_by": "7b76b7cd", "evidence": "ComplianceController.java:50" }
```

**③ `/api/v1/compliance/permission-separation/{VAR}`（GET）**：
```json
{ "path": "/api/v1/compliance/permission-separation/{VAR}", "methods": ["GET"],
  "reason": "owner 裁决卡 d81af12c：权限分离查询属 B 预留能力，AC-COMP spec 零命中",
  "owner_card": "7b76b7cd", "review_date": "2026-09-24", "expire": "2026-12-31", "added_by": "7b76b7cd", "evidence": "ComplianceController.java:77" }
```

**④ `/api/v1/compliance/data-deletion-request`（POST）**：
```json
{ "path": "/api/v1/compliance/data-deletion-request", "methods": ["POST"],
  "reason": "owner 裁决卡 d81af12c：数据删除请求属 B 预留能力，同批 4 端点整体裁决",
  "owner_card": "7b76b7cd", "review_date": "2026-09-24", "expire": "2026-12-31", "added_by": "7b76b7cd", "evidence": "ComplianceController.java:58" }
```

### stale 判定证据（前端消费点，file:line）

| 条目 | 前端消费点（ruoyi-ipd-web/apps/web-antd/src/api/ipd/compliance.ts） |
|---|---|
| audit-trail | :121-122 `ipdGet<Partial<IpdPage<Record<string, unknown>>>>('/compliance/audit-trail/${…}/${…}')`（fetchAuditTrail） |
| data-retention-rules | :81 `ipdGet<unknown[]>('/compliance/data-retention-rules')`（fetchRetentionRules） |
| permission-separation | :150-151 `ipdGet<Record<string, unknown>>('/compliance/permission-separation/${…}')`（checkPermissionSeparation） |
| data-deletion-request | :103 `ipdPost<Record<string, unknown>>('/compliance/data-deletion-request', …)`（createDataDeletionRequest） |

机器判定：修后运行 `--json`，`orphan_gate.stale_whitelist` 共 9 条，其中 compliance 4 条（另 5 条 person-sync×4/p0-escalation×1 为既有 stale，不在本任务范围）。

### 处置建议

- **建议删除条目**（不转归档）：4 条豁免的成立前提（"保留不建前端"）已被前端接线推翻，条目失去存在意义；白名单 JSON 有 git 历史，无需归档副本。strict 模式下 stale 会阻断 CI（exit 1），挂卡尽快清理。
- **执行约束**：白名单文件受 ratchet-data-guard 保护，agent 禁止直接编辑；须挂看板卡（沿用 owner_card 7b76b7cd 或新卡）由卡 owner 执行删条目，随后按棘轮流程 `--update-baseline` 收缩 baseline（只减不增）。

---

## 执行边界确认（本轮合规自查）

| 项 | 状态 |
|---|---|
| 写入文件 | 仅 `scripts/check-api-contract-fe-be.mjs`（任务1 指定）+ 本报告 ✅ |
| git commit/push/add | 未执行（主会话统一提交）✅ |
| DELETE/DDL/UPDATE 执行 | 未执行（2a 仅 SELECT，方案以草稿留档）✅ |
| Java 源码改动 | 无 ✅ |
| 白名单/baseline 编辑 | 无（任务3 仅出建议）✅ |
| vibe-kanban 修复 | 未修（非一行级 + 无源码仓，只出方案）✅ |
| 新机制/新台账 | 无 ✅ |

**待 owner 拍板事项汇总**：① 2a 悬空 5 条清理（建议采 R217 草稿软删口径，一次拍板避免双稿）；② 2c 死码 1-3 清理与 catalog 同步出账（第 4 条禁删）；③ 任务3 白名单 4 条 compliance stale 删条目挂卡；④ 2b vibe-kanban status 过滤是否挂卡（方案 A 零改动可用）。
