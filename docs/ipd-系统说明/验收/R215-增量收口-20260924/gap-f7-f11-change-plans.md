# R215-GAP F7~F11 前端缺口卡变更计划（树一释放即照单施工）

> 只读勘察产物，2026-09-25（第二批，格式对齐 gap-f1-f6-change-plans.md）。后端证据均为磁盘现态 `文件:行号`；前端冲突基线 = 2026-09-25 `cd /Users/mac/Documents/ruoyi-ipd-web && git status --short` 实测（**40 脏项：32 M + 8 ??**，较 F1~F6 批基线 31 项已扩容，`api/ipd/kpi.ts`、`api/ipd/kpi.test.ts`、`api/ipd/project.ts` 等新入脏）。
> 本文档不修改任何代码；施工时逐卡按「文件级变更清单」执行即可。
> 看板核对：五卡原文经 `GET /api/tasks?project_id=01dcf15c…`（{success,data} 解包）逐字复核。**卡面-真值纠偏见文末「勘察纠偏」节——本批五卡端点归属全部属实，但存在 F7 漏计 1 端点、F10/F11 卡题与任务简报对调等 6 处出入。**

## 0. 全局施工口径（五卡通用）

**响应包络**：五卡全部走 IPD `ApiV1Response` code=0 包络 `{code,message,data,timestamp,traceId}`（`ruoyi-modules/ruoyi-ipd/.../common/ApiV1Response.java:14-39`，CODE_SUCCESS=0 @:24）。业务 api 文件一律复用 `ipdGet/ipdPost/ipdPut/ipdDelete`（`apps/web-antd/src/api/ipd/http.ts:22-40`），不碰 requestPortal/requestClient。
- `ipdPost(path, body?, query?)` 支持第三参 query（http.ts:27）→ **F8 instantiate 的 `?projectId=` POST 场景直接可用**；`ipdPut(path, body?)` 无 query 形参（http.ts:32，上一批已立的坑）→ 本批五卡无 PUT 端点，不受影响。
- Long ID 双形态：>2^53 雪花经全局 BigNumberSerializer 以 JSON string 下发，安全区间内为 number（`JacksonConfig.java:37-39` 注册 Long/BigInteger→BigNumberSerializer；BigDecimal→ToStringSerializer @:40）→ 前端展示/传参一律 `String()` 归一透传（样板 `api/ipd/hr-sync.test.ts:76-95` 19 位雪花逐字符断言）。
- 日期字段：全局仅定制了 LocalDateTime（`yyyy-MM-dd HH:mm:ss`）与 Date 反序列化（`JacksonConfig.java:42-44`），**Instant 与 java.util.Date 序列化走 Jackson 默认**（Spring Boot 默认禁 timestamps → ISO 串）→ F7 的 `nextRetryAt/createdAt/updatedAt`（Instant）与 F8 的 `instantiatedAt`（Date）前端**按 string 原样透传展示，联调时抓一次真响应钉格式**，严禁前端做日期运算。

**禁止项（全局红线，继承 F1~F6 批）**：
1. ❌ 任何 `Number(id)` / `parseInt(id)` / `+id` 对雪花 ID 归一——兄弟会话本批仍在 3 个 api 文件犯此 P0，新代码零容忍；ID 一律 string 透传，仅计数/分页类字段允许 `Number()`。
2. ❌ 裸字面量权限码——路由 meta.access / v-access 必须引 `_shared/ipd-permission-codes` 常量。
3. ❌ 直跑 `npx vitest run <path>`（假失败）——正确口径见下。
4. ❌ 前端透传服务端权威字段（F9 的 actor、F10 的 operatorId 均由后端会话推导）。
5. ❌ 触碰脏清单文件（各卡冲突检查表逐文件标注）。

**验证命令口径**（在 `/Users/mac/Documents/ruoyi-ipd-web` 执行）：
```bash
npx turbo run typecheck --force --filter=@vben/web-antd
npx vitest run --dom -c vitest.ipd.config.mts apps/web-antd/src/api/ipd/<file>.test.ts
```

**横切冲突（本批增量）**：
- `views/ipd/_shared/ipd-permission-codes.test.ts` 仍在脏清单（兄弟在改，硬断言 keys=71/distinct=70，test:15-26）→ 唯一需要**新增权限码常量**的卡是 **F10**（`ipd:permanent-delete:execute` 未在 ipd-permission-codes.ts 登记，实测 grep 零命中；仅 `ipd-enums.ts:170` 有现成中文 label『永久清除数据』）→ 登记步骤 ⛔ 等树，过渡=路由 `meta.authority:['SUPER_ADMIN']`（先例 ipd.ts:407、:444-449）。F7/F8/F9/F11 权限码全部已登记（`SOP_TEMPLATE_LIST` :85 / `KPI_QUERY` :88 / `PROJECT_QUERY` :20），零撞车。
- `api/ipd/kpi.ts` + `kpi.test.ts` 本批新入脏 → F11 绕行新建 `api/ipd/kpi-rules.ts`，零冲突（见该卡）。
- `api/ipd/bid.ts` 干净，但 F1/F2（前批计划）亦将追加函数 → F9 若与前批同分支施工，天然并入同一 commit；若分叉并行，注意同文件 append 型 merge 冲突。

**决策点总览（等 owner 拍板）**：F10、F11 两卡卡面各含「做 UI vs 裁定下线」分支（任务简报把此决策点记在 F10=KPI 名下，系卡题对调误植，见纠偏节①）。两方案成本均已在各卡内给出，拍板前其余可先行项照常。

---
## F7（e9721c5c）人员同步任务页（person-sync 域）

### 端点表（后端真值 PersonSyncController.java，基路径 `/api/v1/person-sync` :29）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/person-sync/jobs` | POST | **body** `SubmitRequest{employeeNo: string(@NotBlank), idempotencyKey?: string}`（record :38） | `SubmitResponse{jobId: string, status: string}`（record :41） | 代码内 `requireLeaderOrAdmin`（:53，超管+组长） | PersonSyncController.java:51-57 |
| `/person-sync/jobs/{id}/retry` | POST | path jobId **string**（格式 `sync-<uuid8>-<seq>`，PersonSyncService.java:114——非雪花但同样禁 Number 化）；无 body | `SyncJobView` | requireLeaderOrAdmin（:62）；仅 FAILED 可重试、超 maxAttempts 拒（javadoc :59） | :60-66 |
| `/person-sync/jobs/retry-all` | POST | 无 | `BatchRetryView{retried,succeeded,failed,skipped}`（int 四计数，record :44） | **requireAdmin 仅超管**（:71） | :69-77 |
| `/person-sync/jobs` | GET | 无 | `List<SyncJobView>` | requireAdmin（:82） | :80-85 ⚠️ **卡面未列的第 5 条孤儿**（见纠偏节②） |
| `/person-sync/jobs/abnormal` | GET | 无 | `List<SyncJobView>`（FAILED only） | requireAdmin（:90） | :88-93 |

`SyncJobView` 字段（PersonSyncService.java:52-55）：`jobId(string)、employeeNo、status(PENDING|SUCCESS|FAILED|RETRYING，枚举 :46)、attempts(int)、maxAttempts(int)、failureKind(TRANSIENT|PERMANENT|null，枚举 :49)、failureReason、nextRetryAt/createdAt/updatedAt(Instant→string 透传，见全局口径日期注)`。幂等语义：同 (operatorId, groupId, idempotencyKey) 重放返原 jobId 不新建（service :100-111）——前端重试按钮可安全连点，但 UI 仍应防抖。

### 前端挂载点（实测）
- 全仓 `person-sync` grep 零命中（api/views/router 三面），卡面「现状：无封装文件、identity-sync 页零调用」属实。
- **落点裁决：新建独立子页，不在 identity-sync 页加 Tab**——`views/ipd/admin/identity-sync/index.vue` 实测无 Tabs 骨架（grep a-tabs/Tabs 零命中，单表页 + ACTION_META 弹链 :123-140 区域），改造为 Tab 页会大动该文件且与 F3（rehire 卡，同文件）撞车。新页 `views/ipd/admin/person-sync/index.vue` + IpdAdmin.children 追加路由（ipd.ts children 区间 :418-504，追加于 handover :498-503 之后），`meta.authority:['SUPER_ADMIN','GROUP_LEADER']`（submit/retry 组长可用；list/abnormal/retry-all 按钮再按角色收敛，`useIpdAuthStore` 角色判定先例 identity-sync :32）。
- 权限码零登记（端点无 @SaCheckPermission 注解码，纯代码内 require*，凭空登记=镜像污染，沿 F3 卡裁决）。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/person-sync.ts`（新建 5 函数 + 3 类型） | 不存在 | ✅ 零冲突可先行 |
| `api/ipd/person-sync.test.ts`（新建） | 不存在 | ✅ 可先行 |
| `views/ipd/admin/person-sync/index.vue` + `index.test.ts`（新建） | 不存在 | ✅ 可先行 |
| `router/routes/modules/ipd.ts`（IpdAdmin children 追加） | 干净 | ✅ 可先行 |

**本卡完全零冲突。**（GET /jobs 搭车接上后，本卡消化孤儿 5 条而非卡面 4 条。）

### 文件级变更清单
1. 新建 `api/ipd/person-sync.ts`：`submitSyncJob(employeeNo: string, idempotencyKey?: string)`（POST body，idempotencyKey undefined 不塞 null）、`retrySyncJob(jobId: string)`（path 段 `encodeURIComponent`）、`retryAllSyncJobs()`、`listSyncJobs()`、`listAbnormalSyncJobs()`；`normalizeJob()` 全字段 string/number 按上文类型归一（attempts/maxAttempts 才 Number()）。
2. 新建 `views/ipd/admin/person-sync/index.vue`：任务全量表（listSyncJobs）+「仅看异常」切换（abnormal）+ 行「重试」按钮（FAILED 行显示，组长可见）+ 顶栏「批量回补」按钮（**仅 SUPER_ADMIN 显示**，成功后 toast 展示 retried/succeeded/failed/skipped 四计数）+「提交同步任务」表单（employeeNo 必填 + idempotencyKey 选填自动生成 `ui-<ts>` 串防连点重复建任务）。五态对齐 `_shared` 既有范式。
3. `ipd.ts`：IpdAdmin.children 追加 `{ path:'person-sync', name:'IpdAdminPersonSync', meta:{ authority:['SUPER_ADMIN','GROUP_LEADER'], title:'同步任务' } }`。

### 用例清单（person-sync.test.ts，仿 hr-sync.test.ts 范式）
- `submitSyncJob('E001') → POST /person-sync/jobs，body 恰 {employeeNo}；带 idempotencyKey → body 双键`
- `retrySyncJob('sync-ab12cd34-7') → POST /jobs/<id>/retry，path 逐字符无损；'a/b' 形态 id → URL 编码`
- `retryAllSyncJobs() → POST /jobs/retry-all 无 body 无 query；BatchRetryView 四计数 Number 且 typeof number`
- `listSyncJobs/listAbnormalSyncJobs → GET 两口径 URL 正确、数组透传`
- `行归一：jobId/employeeNo/failureReason string 原样；status/failureKind 枚举值域守卫（未知值不炸）；Instant 字段 string 透传不 Date()`
- `负例：组长调 retry-all 403/30001（requireAdmin）；非 FAILED retry → 业务错 code≠0 抛 IpdRequestError 不吞错`

### 本卡禁止项
- ❌ `Number(jobId)` 或任何 jobId 数值化（格式本身非纯数字，Number 直接 NaN）；❌ 给批量回补按钮配组长可见（后端 :71 仅超管）；❌ 前端轮询打 list 端点做过频 GET（页级 onMounted 一次 + 手动刷新即可）；❌ 在 identity-sync 页就地加 Tab（与 F3 撞车 + 无 Tab 骨架）。

---
## F8（76b72484）SOP 实例化 + 快照查看（sop-templates 域）

> **域归属钉死**：本卡只对应 `SopTemplateController` 两孤儿端点；`POST /stage-actions/instantiate`（StageActionController:87）是**另一张卡 B2**（后端触发方收口）的端点，任务简报「对应 stage-actions/sop-template 域」的并表说法不成立，两域两表两会话勿混（见纠偏节③）。

### 端点表（后端真值 SopTemplateController.java，基路径 `/api/v1/sop-templates` :36）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/sop-templates/{templateId}/instantiate` | POST | path templateId(Long)；**query** `projectId`（@RequestParam Long :100，**无 body**） | `SopTemplateInstance` | 注解码 `ipd:sop-template:list`（@:98；值 IpdPermissionCode.java:80）+ requireInternal（:101）；**service 层再硬校验角色白名单 MARKET_PM/RD_PM/GROUP_LEADER/SUPER_ADMIN**（SopTemplateService.java:334-342）+ 项目成员 IDOR fail-closed（:350-351）+ 仅 PUBLISHED 可实例化否则 409/STATE_CONFLICT（:354-357） | :97-103 |
| `/sop-templates/instances` | GET | **query** `projectId`（@RequestParam Long，必填 :108） | `List<SopTemplateInstance>` | 同注解码 + requireInternal（:109） | :106-111 |

`SopTemplateInstance` 字段（domain/SopTemplateInstance.java:28-56）：`id/templateId/instanceVersion/projectId(Long→String() 归一)、snapshotJson(不可变快照 JSON 串，前端 JSON.parse 失败要兜底原文展示)、instantiatedAt(Date→string 透传)、instantiatedBy(后端即 String person ID)、status(ACTIVE|SUPERSEDED|ARCHIVED :63-67)、delFlag/tenantId(不展示)`。同项目同 templateId 旧 ACTIVE 自动 SUPERSEDED（service javadoc :328-329）。

**入参形态坑**：instantiate 是 POST+query 无 body → `ipdPost(path, undefined, { projectId })` 三参形态（http.ts:27），**不要**塞 body（上一批 ipdPut 无 query 形参的坑在 POST 侧不存在，但方向反了：此端点吃 query 不吃 body）。

### 前端挂载点（实测）
- `api/ipd/sop-template.ts` 现 7 函数（list :59 / get :65 / current :72 / copy :83 / update :90 / publish :100 / revert :107，文件止于 :109）——卡面「已接 7/9」属实；但**文件头注 :12-13 自称『9 函数均已对齐真活路径』为虚**（实测 instantiate/instances 缺失），头注纠偏列入本卡变更。
- 视图落点：`views/ipd/project/detail/flow.vue`（页11 IPD 流程/阶段页，卡面「项目详情阶段页」即此）——现文件零 sop 引用（grep 实测），追加：①「实例化当前阶段 SOP」按钮（对当前阶段 actionCode 取 `currentSopTemplate(actionCode)` 得 PUBLISHED 版本 id → instantiate(projectId)）②「项目 SOP 快照」Drawer（listSopTemplateInstances(projectId) 列表 + snapshotJson 展开详情，status tag 三色 ACTIVE 绿/SUPERSEDED 灰/ARCHIVED 红）。
- 管理页 `views/ipd/admin/sop-template/index.vue`（路由 :450-457 干净）不强制改动（快照属项目维度，归 flow 页）。
- 权限码零登记：`SOP_TEMPLATE_LIST` 已在 ipd-permission-codes.ts:85，PAGE_PERMISSIONS['/ipd/admin/sop'] :197 已挂；flow 页按钮显隐用 `useIpdAuthStore` 角色白名单四值判定（对齐 service :336-340，防 403 后知）。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/sop-template.ts`（+2 函数 +1 类型 + 头注纠偏） | 干净 | ✅ 零冲突可先行 |
| `api/ipd/sop-template.test.ts`（新建，现无） | 不存在 | ✅ 可先行 |
| `views/ipd/project/detail/flow.vue`（+按钮/Drawer） | 干净（脏的是同目录 overview.vue，不触碰） | ✅ 可先行 |
| `views/ipd/project/detail/flow.test.ts`（新建，现无） | 不存在 | ✅ 可先行 |

**本卡完全零冲突。**

### 文件级变更清单
1. `sop-template.ts`：`IpdSopInstance` 接口（id/templateId/projectId/instanceVersion: string；snapshotJson: string；status: 'ACTIVE'|'SUPERSEDED'|'ARCHIVED'|string 兜底）；`instantiateSopTemplate(templateId: string, projectId: string): Promise<IpdSopInstance>` → `ipdPost(\`/sop-templates/${id}/instantiate\`, undefined, { projectId })`；`listSopTemplateInstances(projectId: string): Promise<IpdSopInstance[]>`；normalizeInstance() 仿 normalizeItem(:46-55) 写法；头注 :12-13 改为与实物一致的准确表述。
2. `flow.vue`：如上挂载点①②；实例化成功 message 带新实例 id；409（非 PUBLISHED/已有草稿类冲突）经 `ipdErrorText` 呈现不吞错。
3. 新建两测试文件。

### 用例清单（sop-template.test.ts）
- `instantiateSopTemplate('2096266884247736321','123') → POST /sop-templates/2096266884247736321/instantiate?projectId=123 且无 body（19 位雪花 path 逐字符无损，禁 Number）`
- `projectId 缺省/空串 → api 层不发请求或后端 PARAM_INVALID 透传（二选一实现，测锁定）`
- `listSopTemplateInstances → GET /sop-templates/instances?projectId=<string>`
- `实例归一：id/templateId/projectId/instanceVersion string；snapshotJson 非法 JSON 时 Drawer 兜底原文不抛`
- `status 未知值域（如 FOO）原样透传不炸列表`
- `负例：非项目成员 instantiate → 403/IpdRequestError（IDOR fail-closed :350）；非 PUBLISHED → 409/STATE_CONFLICT 不吞`

### 本卡禁止项
- ❌ `Number(instanceVersion)` 之外三 ID 的数值化（instanceVersion 若 >2^53 理论可能，稳妥也 string 透传，仅计数展示）；❌ projectId 塞 JSON body（后端 @RequestParam 只读 query）；❌ 前端自建「模板版本编辑」新入口（写口 4 端点已接且仅超管，勿扩大面）；❌ 接 `/stage-actions/instantiate`（那是 B2 卡的端点）。

---
## F9（3ef76d39）研发PM「我的应标」列表（bid 域）

### 端点表（后端真值 BidController.java，类注 @RequestMapping("/api/v1") :38）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/bid-responses/by-rd-pm/{rdPmId}` | GET | path rdPmId(Long→string 透传)；**query** `pageNo`（默认 1）、`pageSize`（默认 20，**service 硬上限 200**，javadoc :167） | `IPage<BidResponse>`（复用 bid.ts `IpdPage` :58-64 + `BidResponse` :42-56） | 注解码 `ipd:project:query`（@:169；IpdPermissionCode.java:11）+ requireInternal（:176）；**service 层 IDOR 三分支：本人 / SUPER_ADMIN / 关联项目在职 ProjectMember**（javadoc :166 + W5-E-2.4 注释 :175） | BidController.java:169-178（@GetMapping :170；卡面写 :169 为注解行，归属属实） |

前端 `by-rd-pm` 全仓零命中（api/views/router 三面 grep 实测），卡面现状属实；既有应标读口只有 invitation 维度 `listResponses`（bid.ts:165-168 → `/bid-invitations/{id}/responses`）。

**取数坑**：rdPmId 是 Person ID。当前登录人 id 用 `useIpdAuthStore` 的 identity `/auth/me` `person.id`（api/ipd/auth.ts:7 `id: string`，且 :102 有 `/^\d+$/` 校验先例）；**勿从 vben userStore 取 `userId`**——store/ipd-auth.ts:135 处它是 `as unknown as number` 类型强转（运行时仍 string，但类型面诱导数值运算，19 位雪花一经算术即精度碎）。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/bid.ts`（追加 1 函数 + 1 参数类型） | 干净 | ✅ 零冲突可先行；⚠️ 但 F1/F2 前批计划也改此文件+`bid/list/index.vue`——**同分支合批施工**（卡面「可并入 F1/F2 卡」即此意），分叉并行则 bid.ts 为 append 型冲突低风险、list 视图不重叠（本卡不动它） |
| `api/ipd/bid.test.ts`（F1/F2 批已规划新建） | 视 F1/F2 进度 | 并入同一测试文件，追加用例 |
| `views/ipd/bid/my-responses/index.vue` + `index.test.ts`（新建，避开 F1 已占的 bid/list/index.vue） | 不存在 | ✅ 可先行 |
| `router/routes/modules/ipd.ts`（IpdBids.children :194-210 追加一条） | 干净 | ✅ 可先行 |
| `ipd-permission-codes.ts`（仅去 `PROJECT_QUERY` :20 的 reserved 注释——键数不变） | 干净（.ts 本体） | ✅ 可先行（沿 F6 批去注释先例；稳妥亦可并树后） |

**本卡零冲突（对兄弟脏清单）。** 权限码零新增（PROJECT_QUERY 已登记；PAGE_PERMISSIONS['/ipd/bids'] :157 既有，子路由沿用 activePath 归组不需新键）。

### 文件级变更清单
1. `bid.ts`：`ListMyBidResponsesParams { pageNo?: number; pageSize?: number }`；`listBidResponsesByRdPm(rdPmId: string, params?): Promise<IpdPage<BidResponse>>` → `ipdGet(\`/bid-responses/by-rd-pm/${encodeURIComponent(rdPmId)}\`, params)`；records 归一沿用 BidResponse 现形（id/invitationId/rdPmId 已 string）。
2. 新建 `views/ipd/bid/my-responses/index.vue`：从 ipd-auth store 取本人 person.id → 分页表（列：所属招标单 invitationId 链接化、状态 BidResponseStatus tag、responseNote、respondedAt 原样串）；`hideInMenu` 子路由 `{ path:'my-responses', name:'IpdBidMyResponses', meta:{ activePath:'/ipd/bids', hideInMenu:true, title:'我的应标' } }`，入口按钮挂在 bid/list 页头工具区由 F1 施工顺路带（或本卡以直达 URL 交付，二选一，不阻塞）。超管/项目成员代查场景本期不做 UI（端点天然支持三分支，卡名聚焦「我的」）。
3. `bid.test.ts` 追加本卡用例（与 F1/F2 用例同文件分 describe）。

### 用例清单（bid.test.ts 追加）
- `listBidResponsesByRdPm('2096266884247736321') → GET /bid-responses/by-rd-pm/2096266884247736321，19 位雪花 path 逐字符无损（禁 Number）`
- `params {pageNo:2,pageSize:50} → ?pageNo=2&pageSize=50；缺省不拼空 query`
- `IPage 包络归一：records[].id/invitationId/rdPmId string、total/pages/size/current Number`
- `rdPmId 含特殊字符 → encodeURIComponent`
- `负例：非本人且非超管非成员 → 403/IpdRequestError（IDOR 三分支拒）；pageSize=500 → 后端钳 200 或直接业务拒（以联调钉死断言）`

### 本卡禁止项
- ❌ `Number(rdPmId)` / 从 userStore.userId 数值态取本人 id；❌ 前端拼 `?requesterId=` 等参数试图绕过三分支（服务端会话推导）；❌ 把列表塞进 bid/list/index.vue 现有表格（与 F1 行内按钮改造同文件撞车，独立视图解耦）。

---
## F10（eedf10f1）超管永久清除工作台（admin/permanent-delete 域）⚠️ 含 owner 决策点

> **卡号纠偏**：任务简报把「KPI 生效规则面板」记在本卡 uuid（eedf10f1）名下——看板实测 eedf10f1 = 「[R215-GAP-F10] 超管永久清除工作台」，KPI 卡是 83f68178=F11（见 F11 卡与纠偏节①）。本卡按看板原文施工规划。

### 端点表（后端真值 AdminPermanentDeleteController.java，基路径 `/api/v1/admin/permanent-delete` :43）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/admin/permanent-delete/{entityType}/{id}` | POST | path `entityType`（@Pattern 硬约束 `person\|project\|kpi_record` :58，白名单同源 PermanentDeleteService.java:52）、`id`（Long @NotNull :59）；**body** `ExecuteReq{confirmCode}`（@NotBlank ≤64，record :88-90），值必须**恰等于** `"PERMANENT_DELETE_CONFIRMED"`（Service.REQUIRED_CONFIRM_CODE :49） | `Map{auditId(Long→string), entityType, entityId(Long→string), operatorId(Long→string), operatorName, permanentlyDeleted:true}`（:64-70） | 注解码 `ipd:permanent-delete:execute`（@:56；IpdPermissionCode.java:210）+ `requireAdmin` **仅 SUPER_ADMIN**（:61，service 再校验一次 :61 注） | :57-74（卡面 :56-80 为区间概写，mapping 实际行 :57/:76） |
| `/admin/permanent-delete/audit` | GET | **query** `entityType?`（选填 :78）、`limit`（默认 50 :79） | `List<PermanentDeleteAudit>{id,operatorId,operatorName,entityType,entityId,originalDataJson,deletedAt,ipAddress,tenantId,delFlag}`（domain/PermanentDeleteAudit.java:46-83；BaseEntity 继承字段原样） | 同注解码 + requireAdmin；javadoc 自证「**前端对账视图用**」（:76） | :76-85 |

不可逆操作语义：物理清除 + 审计永久保留（类注 :31-:33）；`scenario` 类型未建模，传入即 400（类注 :35-38）→ 前端下拉只放三白名单值。

### 决策点：方案 A（做 UI）vs 方案 B（裁定「运维 curl 合法孤儿」下线本卡）
| | 方案 A 建页 | 方案 B 裁定下线 |
|---|---|---|
| 成本 | 新建 4 文件 + 路由 + **权限码登记**（唯一等树项）≈ **1~1.5 人日**；label 层 ipd-enums.ts:170 中文名已备好 | 0 代码；orphan-triage 文档 §3.2 表追加一行裁定 + 翻卡闭环 ≈ 0.5 小时 |
| 代价/风险 | 高危操作面从「仅 curl」扩到「UI 三连点」，需前端把确认门槛做重（见变更清单③） | 契约扫描孤儿 -2 目标不达，47 账目留 2 常态存量；**与 BE javadoc :76「前端对账视图用」自证矛盾**（后端设计意图明确要 UI）；运维面恢复清除结果对账只能人肉 curl |
| 建议 | ✅ 倾向 A（端点注释自称给前端用，不接=违背 BE 设计意图；且 R215 已给 B4 同类「待接」卡做了调度收口先例） | 若 owner 以「超管低频+防误操作」裁 B，则 A 案全部工作作废无沉没成本（新文件独立） |

**两方案共同点**：无论 A/B，本卡树前均无可施工冲突项（A 案除权限码登记外全部零冲突，可先行到「树释放后一键补登记」状态）。

### 冲突检查表（按方案 A）
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/admin-permanent-delete.ts` + `.test.ts`（新建；文件名带 admin- 前缀避让他域） | 不存在 | ✅ 零冲突可先行 |
| `views/ipd/admin/permanent-delete/index.vue` + `index.test.ts`（新建） | 不存在 | ✅ 可先行 |
| `router/routes/modules/ipd.ts`（IpdAdmin children 追加，先例 :445-449 role-permission 单超管门禁） | 干净 | ✅ 可先行（过渡 `meta.authority:['SUPER_ADMIN']`） |
| `ipd-permission-codes.ts` + `.test.ts`（登记 `PERMANENT_DELETE_EXECUTE: 'ipd:permanent-delete:execute'`，keys 71→72 / distinct 70→71） | **.test 脏（兄弟在改，硬断言 15-26）** | ⛔ **必等树**：登记与计数断言同步改，树释放前用 authority 过渡不降安全 |

### 文件级变更清单（方案 A）
1. 新建 `api/ipd/admin-permanent-delete.ts`：类型 `PermanentDeleteAuditRow`（id/operatorId/entityId: string；deletedAt: string 透传；originalDataJson: string）、`PermanentDeleteResult`；函数 `executePermanentDelete(entityType: 'person'\|'project'\|'kpi_record', id: string, confirmCode: string)`（POST body {confirmCode}）、`listPermanentDeleteAudit(entityType?, limit?)`（GET query）。
2. 新建 `views/ipd/admin/permanent-delete/index.vue` 双区块：①清除执行表单——entityType 下拉三值白名单 + id 文本框（`/^\d+$/` string 校验）+ **confirmCode 输入框不预填、不复制按钮**（必须人手工敲入字面量，保住「二次确认」的人意语义）+ 提交前 Modal 复述目标三元组；②审计对账表——entityType 过滤 + limit（默认 50）+ deletedAt/originalDataJson（截断+展开）列。整页 `meta.authority:['SUPER_ADMIN']` 双闸。
3. `ipd.ts` 追加路由；树释放后：权限码登记 + meta 换 `access:[IPD_PERMISSION_CODES.PERMANENT_DELETE_EXECUTE]` + 脏 test 计数 72/71 同步。

### 用例清单（admin-permanent-delete.test.ts）
- `executePermanentDelete('project','2096266884247736321','PERMANENT_DELETE_CONFIRMED') → POST /admin/permanent-delete/project/2096266884247736321，body 恰 {confirmCode}，19 位雪花 path 无损`
- `entityType 白名单外（如 'scenario'）→ api 层类型拒 + 视图下拉不可达（后端 @Pattern 兜底负例：400 透传）`
- `confirmCode 空串 → 视图拦截不发（对齐 @NotBlank :89）`
- `listPermanentDeleteAudit() → GET /audit 不拼多余 query；('kpi_record',10) → ?entityType=kpi_record&limit=10`
- `audit 行归一：id/operatorId/entityId string；originalDataJson 非法 JSON 兜底原文；permanentlyDeleted === true 严格布尔`
- `负例：组长调用 403/30001（requireAdmin :61/:78 双处）`

### 本卡禁止项
- ❌ `Number(id/auditId/entityId/operatorId)`；❌ 前端预填/一键复制 confirmCode（二次确认形同虚设，安全回归）；❌  entityType 扩到白名单四值外（scenario 未建模必 400）；❌ 提前登记权限码（撞兄弟脏 test 计数断言）；❌ 给 GET audit 加 `confirmCode`（该参数只属 POST）。

---
## F11（83f68178）KPI 生效规则说明面板（kpi 域）⚠️ 含 owner 决策点（卡面原文「或后端确认废弃」）

> **卡号纠偏**：本卡（83f68178）才是任务简报所称「KPI 生效规则面板（含 owner 决策点）」；简报把它标为 F10/eedf10f1 系对调误植（纠偏节①）。

### 端点表（后端真值 KpiRulesController.java，基路径 `/api/v1/kpi` :32）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/kpi/rules` | GET | **无**（零 path/query 参数） | `List<KpiRuleView{ruleKey: string, ruleValue: string}>`（record vo/KpiRuleView.java:15——数值也统一 string 化，注释 :11-12 明防 BigInt 截断） | 注解码 `ipd:kpi:query`（@:40；IpdPermissionCode.java:83）+ requireInternal（:43）；四角色可读（类注 :26-28：MARKET_PM/RD_PM/GROUP_LEADER/SUPER_ADMIN） | :40-44（卡面 :40 属实） |

数据源语义：`kpi_rule_snapshots` 最新快照 rule_json 拍平 → 回退 `system_configs` 的 `kpi.*` 键；**空源返回空列表不 404**（javadoc :37）→ 前端空数组渲染「暂无生效规则」态而非报错。

### 前端挂载点（实测）
- `kpi/rules`、`ruleKey` 前端零命中（api 面 grep 实测），卡面现状属实。
- api 层：**新建 `api/ipd/kpi-rules.ts`**（不动脏文件 `api/ipd/kpi.ts`——该文件+`kpi.test.ts` 均在兄弟脏清单 M；若并入 kpi.ts 必撞车）。
- 视图：`views/ipd/kpi/index.vue`（干净；实测该文件即「功能 KPI」页组件，路由 :283-288 挂载）页内追加可折叠「KPI 生效规则」Card：ruleKey/ruleValue 两列只读表 + 空态/加载/错误三态。**零新路由、零权限码登记**（`KPI_QUERY` 已登记 ipd-permission-codes.ts:88，PAGE_PERMISSIONS['/ipd/kpi/functional'] :163 已覆盖本页面门禁）。
- ⚠️ 若树释放后兄弟版 kpi.ts 已自行补了 rules 函数（防不住），施工首步 `git pull` 后 grep 复核，命中即本卡 api 面缩为「复用+删新建文件」。

### 决策点：方案 A（做面板）vs 方案 B（裁定下线）
| | 方案 A 做面板 | 方案 B 后端确认废弃 |
|---|---|---|
| 成本 | 新 api 文件 + index.vue 一区块 + 测试 ≈ **0.5 人日**（全量零冲突，五卡最廉） | 需后端删/下线 KpiRulesController + KpiRulesService（R108 paiban-02 方案 B 设计产物，零 DB 变更轻方案）≈ 后端 0.5 人日 + 契约对账翻账；或保端点不接=孤儿账 -1 目标不达 |
| 依据 | R108 设计意图明文「前端展示规则清单」（类注 :21-27），四角色可读=面向业务侧透明化，非运维口 | 仅当 owner 认定「规则属运营参数、业务侧无需感知」才成立 |
| 建议 | ✅ 倾向 A：成本最低且与 BE 设计注释同向 | B 需后端动手撤，负产出（删已交付代码），无前端沉没成本 |

### 冲突检查表（按方案 A）
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/kpi-rules.ts` + `.test.ts`（新建，绕脏 kpi.ts） | 不存在 | ✅ 零冲突可先行 |
| `views/ipd/kpi/index.vue`（+规则面板区块） | **干净**（脏的是 functional/、shared/、raw-records 三兄弟，不含本文件） | ✅ 可先行 |
| `views/ipd/kpi/kpi.test.ts`（若存在则更新断言） | 干净 | ✅ 可先行 |
| `api/ipd/kpi.ts` / `kpi.test.ts` / `ipd-permission-codes*.ts` | **脏** / **脏** / test **脏** | 🚫 本计划**不触碰**（这就是绕行的全部意义） |

**本卡（方案 A）完全零冲突。**

### 文件级变更清单（方案 A）
1. 新建 `api/ipd/kpi-rules.ts`：`interface KpiRuleView { ruleKey: string; ruleValue: string }`；`fetchActiveKpiRules(): Promise<KpiRuleView[]>` → `ipdGet('/kpi/rules')` + 数组守卫（非数组→[]）+ 逐行 String() 归一（ruleValue 可能形如 "0.15" 的数值串，**原样展示不 parseFloat**）。
2. `views/ipd/kpi/index.vue`：月度聚合区下方追加 `<Card title="KPI 生效规则" :collapsible>`：onMounted 懒拉（切面板首次展开时再取数亦可，防冷启动多请求）；表格两列 + 空态文案「当前无生效规则快照」+ 失败 ipdErrorText 呈现不吞。
3. 新建 `api/ipd/kpi-rules.test.ts`。

### 用例清单（kpi-rules.test.ts）
- `fetchActiveKpiRules → GET /api/v1/kpi/rules 无 query 无 body`
- `行归一：ruleKey/ruleValue 恒 string（ruleValue 数值串 "0.15" 不做 Number()/parseFloat 断言）`
- `空源 [] → 返回空数组不抛（对齐 javadoc 空列表语义）`
- `非数组脏数据 → 守卫归 []（或抛 IpdRequestError，测锁定实现选择）`
- `负例：无 kpi:query 权限角色 → 403/30001 透传 IpdRequestError`

### 本卡禁止项
- ❌ 改 `api/ipd/kpi.ts`（脏清单文件，撞兄弟）；❌ `Number(ruleValue)` / `parseFloat`（后端刻意 string 化防截断，vo 注释 :11-12）；❌ 把面板做成写口（端点只读 GET，无任何 rules CRUD 后端）；❌ 新登记权限码（KPI_QUERY 已在册，重复登记=镜像污染）。

---

## 总表：可先行 vs 必须等树释放 vs 等拍板

| 卡 | 端点数（磁盘真值） | 完全零冲突（树释放前整体可施工） | 必须等树部分 | 等拍板 | 一行结论 |
|---|---|---|---|---|---|
| F7 人员同步任务页 | **5**（卡面 4 + 漏计 GET /jobs） | ✅ 全部（新建 4 文件 + 干净路由） | 无 | — | **零冲突可先行** |
| F8 SOP 实例化+快照 | 2 | ✅ 全部（sop-template.ts 干净 + flow.vue 干净 + 2 新文件） | 无 | — | **零冲突可先行** |
| F9 研发PM我的应标 | 1 | ✅ 全部（bid.ts 干净；视图新建） | 无（与 F1/F2 同文件系批内合批，非兄弟冲突） | — | **零冲突可先行（建议与 F1/F2 合批 commit）** |
| F10 永久清除工作台 | 2 | api/视图/路由 全部 | 权限码登记 `PERMANENT_DELETE_EXECUTE`（keys 71→72，必撞脏 test） | ✅ A 做 UI vs B 裁孤儿（**倾向 A**，BE javadoc 自证要前端） | **部分等树 + 等拍板** |
| F11 KPI 生效规则面板 | 1 | ✅ 全部（kpi-rules.ts 新建绕脏 kpi.ts + index.vue 干净） | 无 | ✅ A 做面板 vs B 确认废弃（**倾向 A**，0.5 人日最廉） | **零冲突可先行 + 等拍板** |

**结论**：F7、F8、F9、F11 四卡**完全零冲突**（F11 以新建 kpi-rules.ts 绕开脏 kpi.ts 为前提）；F10 功能链路零冲突、仅权限码登记一步等树（过渡 `meta.authority:['SUPER_ADMIN']` 不降安全）。F10、F11 两卡带 owner 决策点，拍板前可先把 F10 非权限码部分与 F11 全量做掉（F11 若裁 B 沉没成本仅 0.5 人日新文件，删除即清）。全部脏文件规避清单：`ipd-permission-codes.test.ts`、`kpi.ts/kpi.test.ts`、`project.ts`（F9 不依赖其数据源——by-rd-pm 是独立 GET，无项目列表 join；若 UI 加项目筛选再 import 不改）。

**每卡收尾五连（施工完成判定口径，沿前批）**：
1. `npx turbo run typecheck --force --filter=@vben/web-antd` EXIT=0
2. `npx vitest run --dom -c vitest.ipd.config.mts apps/web-antd/src/api/ipd/<新test>.test.ts` 全绿
3. `node scripts/check-api-contract-fe-be.mjs --json`（ruoyi-ai 仓）孤儿递减预期：F7 **-5**（含卡面未列的 GET /jobs；若扫描器本就没数它则 -4，以首跑 diff 为准）、F8 -2、F9 -1、F10 -2（仅方案 A 达成）、F11 -1（仅方案 A 达成）
4. 契约测试含 19 位雪花逐字符无损断言（hr-sync.test.ts:85-87 同款）
5. 翻卡以 commit 落地为准（未提交不翻 done，防假绿）

---

## 勘察纠偏（与建卡时定性不符的事实，按卡逐条）

| # | 卡 | 建卡定性 | 磁盘/看板真值 | 影响 |
|---|---|---|---|---|
| ① | 任务简报 | 「F10（eedf10f1）KPI 生效规则面板」 | 看板 eedf10f1=**F10 超管永久清除工作台**；83f68178=**F11 KPI 生效规则**。两卡各自都含「或裁定下线」决策点（简报只归给一张） | 本档两卡分别给了 A/B 成本；无施工影响 |
| ② | F7 | 「4 端点」「组长/超管操作面」 | PersonSyncController 实有 **5** 条孤儿：卡面漏 `GET /jobs`（:80-85，仅超管）；且 list/abnormal/retry-all 三条是 **SUPER_ADMIN only**，组长只有 submit/retry | 计划已并入第 5 端点，孤儿账 -5 而非 -4；组长按钮面按端点分级收敛 |
| ③ | F8 | 简报「对应 stage-actions/sop-template 域」 | 两域两卡：F8=SopTemplateController 两端点；`/stage-actions/instantiate` 归 **B2**（后端触发方收口卡，看板 341ae625） | 施工严禁互接；快照机制空转论仅对本卡两真孤儿成立 |
| ④ | F8 | 卡面「管理页已接 7/9」属实；但 sop-template.ts 头注 :12-13 自称「9 函数均已对齐」 | 实测该文件仅 7 export（止于 :109），无 instantiate/instances | 卡面无错，**前端文件头注虚标**，已列入本卡纠偏变更 |
| ⑤ | F9 | 卡面「BidController:169」 | @GetMapping 在 :170（:169 是权限注解行）；pageSize 有 **200 硬上限**、IDOR 三分支放行（卡面未提） | 行号微偏不改义；用例已钉上限/越权负例 |
| ⑥ | F10 | 卡面「:56-80；现状前端全仓 permanent-delete 零命中」 | mapping 行 :57/:76；前端 `permanent-delete` api/views/router 确零命中，但 **ipd-enums.ts:170 已有中文 label**（label 层半就绪，常量层缺登记） | 权限码登记时 label 零成本；行号微偏不改义 |
| ⑦ | F11 | 卡面「KpiRulesController:40；前端 kpi/rules、ruleKey 零命中」 | :40-44 属实；零命中属实；唯一坑=兄弟脏了 kpi.ts/kpi.test.ts，卡面「落点 views/ipd/kpi/index.vue」仍成立（该文件干净），但 api 落点须从「kpi.ts 追加」改为「新建 kpi-rules.ts」 | 冲突面已消解 |

**五卡端点归属全部核实属实（无 F2 卡名级错误）；最大偏差是 F7 漏计一条孤儿端点。**
