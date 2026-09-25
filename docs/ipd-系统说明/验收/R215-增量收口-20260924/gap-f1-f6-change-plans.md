# R215-GAP F1~F6 前端缺口卡变更计划（树一释放即照单施工）

> 只读勘察产物，2026-09-25。后端证据均为磁盘现态 `文件:行号`；前端冲突基线 = 2026-09-25 `cd /Users/mac/Documents/ruoyi-ipd-web && git status --short` 实测（31 脏项：23 M + 8 ??）。
> 本文档不修改任何代码；施工时逐卡按「文件级变更清单」执行即可。

## 0. 全局施工口径（六卡通用）

**响应包络**：六卡全部走 IPD `ApiV1Response` code=0 包络 `{code,message,data,timestamp,traceId}`（`ruoyi-modules/ruoyi-ipd/.../common/ApiV1Response.java:14-39`，CODE_SUCCESS=0 @:24）。前端包络校验在 `requestIpd`/`authenticatedRequest` 层（`apps/web-antd/src/api/ipd/http.ts:1-7` 头注），**业务 api 文件一律复用 `ipdGet/ipdPost/ipdPut/ipdDelete`（http.ts:22-40），不碰 requestPortal/requestClient**。
- `ipdPost(path, body?, query?)` 支持第三参 query（http.ts:27）；`ipdPut(path, body?)` **无 query 形参**（http.ts:32）→ 需要 query 的 PUT 参照 `bid.ts:143-145` select 的 URL 内联拼法。
- Long ID 两形态：>2^53 雪花经全局 BigNumberSerializer 以 JSON string 下发，安全区间内为 number（`ApiV1Response.java:31` 注）→ 前端展示/传参一律 `String()` 归一透传（样板：`api/ipd/hr-sync.test.ts:76-95`）。**例外**：PersonController.PersonView 的 id 是后端 `String.valueOf` 恒字符串（`PersonController.java:61`）。

**禁止项（全局红线）**：
1. ❌ 任何 `Number(id)` / `parseInt(id)` / `+id` 对 19 位雪花 ID 归一——兄弟会话本批已在 3 个 api 文件犯此 P0，新代码零容忍；ID 一律 string 透传，计数类字段才允许 `Number()`。
2. ❌ 裸字面量权限码——路由 meta.access / 按钮 v-access 必须引 `_shared/ipd-permission-codes` 常量（该文件头注 19-21 行裁决）。
3. ❌ 直接 `npx vitest run <path>`（会假失败）——正确口径见下。
4. ❌ 前端透传服务端权威字段（如 compliance 的 requesterId，`ComplianceController.java:38` SEC-API-01）。
5. ❌ 触碰脏清单文件（各卡冲突检查表已逐文件标注）。

**验证命令口径**（在 `/Users/mac/Documents/ruoyi-ipd-web` 执行）：
```bash
npx turbo run typecheck --force --filter=@vben/web-antd
npx vitest run --dom -c vitest.ipd.config.mts apps/web-antd/src/api/ipd/<file>.test.ts
```

**横切冲突（重要）**：`views/ipd/_shared/ipd-permission-codes.ts` 本体干净，但其契约测试 `ipd-permission-codes.test.ts` 在脏清单（兄弟在改，硬断言 keys=71/distinct=70，test:15-31）。凡需**新增权限码常量**的动作（F2 BID_INVITATION_CREATE、F4 P0_ESCALATION_READ、F5 SWITCHING_ACCEPTANCE_LOCK/UNLOCK）必须等树释放后一并改 + 同步计数；树释放前过渡方案=路由 `meta.authority` 角色门禁（既有先例：ipd.ts:436、:443）。

---

## F1 招标单 modify + admin-assign（bid 域）

### 端点表（后端真值 BidController.java）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/api/v1/bid-invitations/{id}/modify` | PUT | **query**：title?、content?、expireAt?（`yyyy-MM-dd HH:mm:ss` @DateTimeFormat）| `BidInvitation`（复用 bid.ts:23-39 接口）| `ipd:project:edit` + requireInternal + 发起人校验（service 层，AC-TEAM-13 有效期内）| BidController.java:124-134 |
| `/api/v1/bid-invitations/{id}/admin-assign` | PUT | **query**：targetPersonId（Long，必填 @RequestParam）| `BidInvitation` | `ipd:bid-invitation:admin-assign`（IpdPermissionCode.java:149）+ requireAdmin（:144；挂起超 30 日，AC-TEAM-09）| BidController.java:139-147 |

包络/封装：code=0 包络；两 PUT 参数全走 query 非 body（后端 @RequestParam，@:127-130/:142-143），`ipdPut` 无 query 形参 → **URL 内联拼 query，抄 bid.ts:143-145 select 先例**。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/bid.ts`（追加 2 函数 + 2 类型） | 干净 | ✅ 零冲突可先行 |
| `api/ipd/bid.test.ts`（新建，现无此文件） | 不存在 | ✅ 新建文件零冲突可先行 |
| `views/ipd/bid/list/index.vue`（行内「修改」「超管指派」入口，按钮区 :84-94 旁） | 干净 | ✅ 零冲突可先行 |
| `views/ipd/bid/list/index.test.ts` | 干净 | ✅ 可先行 |

**本卡完全零冲突。** 权限码无新增（`ipd:project:edit`=PROJECT_STATUS_CHANGE、admin-assign=BID_INVITATION_ADMIN_ASSIGN 均已登记于 ipd-permission-codes.ts:129，且已挂 PAGE_PERMISSIONS['/ipd/bids'] :157）。

### 文件级变更清单
1. `bid.ts`：新增 `ModifyBidInvitationBody { title?, content?, expireAt? }`（全 string）；`modifyBidInvitation(id, body): Promise<BidInvitation>` → PUT 拼 query（encodeURIComponent 逐值；空值不拼）；`adminAssignBidInvitation(id, targetPersonId: string): Promise<BidInvitation>` → PUT `.../admin-assign?targetPersonId=<string>`。
2. `bid/list/index.vue`：OPEN 且本人发起 → 「修改」Modal（title/content/expireAt 三字段，expireAt 用 dayjs 格式化 `yyyy-MM-dd HH:mm:ss`）；SUPER_ADMIN（v-access 或 store 角色判定）对挂起行 → 「超管指派」Modal（人员 ID 输入框，string 校验 `/^\d+$/`，参照 bid/create/index.vue:167 的 targetPersonId 校验先例）。
3. 新建 `api/ipd/bid.test.ts`。

### 用例清单（bid.test.ts，仿 hr-sync.test.ts 范式）
- `modifyBidInvitation(9n,{title})→PUT /bid-invitations/9/modify?title=…，省略字段不拼 query`
- `modifyBidInvitation expireAt 含空格 → URL 编码为 %20 分隔串原样送达`
- `adminAssignBidInvitation('2096266884247736321')→ targetPersonId 逐字符无损（19 位雪花断言，禁 Number）`
- `id 路径编码防注入（id="..%2F" 形态）`
- `权限负例：非发起人 modify 403/30001 → IpdRequestError`
- `包络负例：code=50002 业务拒 → 抛 IpdRequestError 不吞错`

### 本卡禁止项
- ❌ 把 modify/admin-assign 参数放 JSON body（后端 @RequestParam 只读 query，body 会被静默忽略造成"改了个寂寞"P0）。
- ❌ `Number(targetPersonId)`；❌ expireAt 传 ISO 带 T/Z 串（后端 pattern 无 T，BidController.java:130）。

---

## F2 招标单创建切 p231-create 端点（原卡名"需求创建切 p231-create"）

> **卡面纠偏（实证）**：全后端仓 `p231-create` 仅命中 BidP231Controller 一处，DemandController（/api/v1/demands）无此端点——本卡是「**招标单**校验型创建切 p231-create」，非需求池。

### 端点表
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/api/v1/bid-invitations/p231-create` | POST | body `CreateBidInvitationRequest`：projectId(Long @NotNull)、mode(@NotBlank `ONE_TO_ONE\|PUBLIC`)、targetPersonId?(1:1 必填/PUBLIC 禁)、title(@NotBlank ≤200)、content?(≤4000)、expireAt(@NotNull @Future Date)、requiredLevel?(`L[1-5]`)、slaDays?(1..90) | `BidInvitation` | `ipd:bid-invitation:create`（IpdPermissionCode.java:147）+ requireProjectCreator + 项目同组校验 | BidP231Controller.java:42-47；DTO CreateBidInvitationRequest.java:30-60 |

包络 code=0；body 走 `ipdPost(path, body)` 即可。**施工核对点**：expireAt 为 Java `Date` 且 DTO 无 @DateTimeFormat（:49-51）——请求体日期格式以既有 `POST /bid-invitations`（实体 BidInvitation.expireAt 同 Date）现网口径 `yyyy-MM-dd HH:mm:ss` 为准，联调时抓一次真请求确认。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/bid.ts`（createBidInvitation 切 URL + body 扩 projectId/requiredLevel/slaDays） | 干净 | ✅ 可先行 |
| `views/ipd/bid/create/index.vue`（新增项目选择字段——**现视图无 projectId**，grep 证据 :37-93 仅 title/content/mode/targetPersonId/expireAt） | 干净 | ✅ 可先行 |
| `views/ipd/bid/create/index.test.ts`（更新 body 断言 :76/:97） | 干净 | ✅ 可先行 |
| `api/ipd/bid.test.ts`（新建，并入 F1 用例文件） | 新建 | ✅ 可先行 |
| `views/ipd/_shared/ipd-permission-codes.ts` + `.test.ts`（登记 `BID_INVITATION_CREATE: 'ipd:bid-invitation:create'`） | .ts 干净 / **test 脏（兄弟在改，计数硬断言 71/70）** | ⛔ **等兄弟 commit 后基于新版施工** |
| 项目选择器数据源：仅 **import** `api/ipd/project.ts` 既有函数 | project.ts 脏 | ⚠️ 不修改该文件=无冲突；若兄弟改签名，施工时以新版为准 |

### 文件级变更清单
1. `bid.ts`：`CreateBidInvitationBody` 增加 `projectId: string`、`requiredLevel?: string`、`slaDays?: number`；`createBidInvitation` 改 POST `/bid-invitations/p231-create`；旧端点函数保留改名 `createBidInvitationLegacy` 一个迭代（防他处引用断链，grep 现仅 create/index.vue:118 一处消费，可直接切）。
2. `bid/create/index.vue`：新增「所属项目」Select（project.ts 列表函数拉候选，showSearch 按名称过滤，**value 存 string ID**）；PUBLIC 模式追加 requiredLevel/slaDays 两个选填控件（对齐 DTO 语义 :53-59）；提交前校验 projectId 非空。
3. 路由无变更（IpdBidCreate 已存在 ipd.ts:196-198；页面 access 待权限码登记后再挂，暂维持无 access meta 现状）。

### 用例清单（并入 bid.test.ts + create/index.test.ts 更新）
- `createBidInvitation 打到 /bid-invitations/p231-create（旧 /bid-invitations 不再被调用）`
- `body 契约：projectId 字符串逐字符无损（19 位雪花断言）、mode 枚举、expireAt 格式 yyyy-MM-dd HH:mm:ss`
- `PUBLIC 模式 body 不含 targetPersonId（后端禁填）；ONE_TO_ONE 必填校验在视图层拒空`
- `缺 projectId → 视图校验拦截，api 不被调用（仿 create/index.test.ts:51 先例）`
- `403（非项目创建人）→ IpdRequestError 透传`

### 本卡禁止项
- ❌ `Number(projectId)`；❌ PUBLIC 模式携带 targetPersonId；❌ 提前把 BID_INVITATION_CREATE 塞进权限码文件（会撞兄弟脏 test 的计数断言，制造假红）。

---

## F3 人员复职 rehire（person 域）

### 端点表
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/api/v1/persons/{id}/rehire` | POST | body `RehireRequest{ note?: string }`（选填） | `PersonView{ id(String 恒字符串), name, employmentStatus, accountStatus, wecomUserId(脱敏"***") }` | **无 @SaCheckPermission 注解**，代码内 `requireLeaderOrAdmin`（SUPER_ADMIN/GROUP_LEADER） | PersonController.java:88-95（record :43；PersonView :58-65，String.valueOf @:61） |

包络 code=0；封装 `ipdPost`。**前端既有同域用法**：`person.ts:43-50` resignPerson/unbindWecom 均 `ipdPost<PersonView>('/persons/{id}/…', { reason })`，rehire 照抄形态。id 透传既有 `encodeURIComponent`。
语义要点：unbind/resign 后置 DISABLED（person.ts:10-12 实测注），rehire 即恢复路径；PersonView.id 后端恒 string，**无 number 形态分支**，但请求 URL 中 personId 仍必须来自 string 源。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/person.ts`（追加 rehirePerson + RehireResult 复用 PersonOperationView） | 干净 | ✅ 零冲突可先行 |
| `api/ipd/person.test.ts`（追加用例） | 干净 | ✅ 可先行 |
| `views/ipd/admin/identity-sync/index.vue`（ACTION_META :123-140 扩 `rehire` 行 + 行按钮按 accountStatus==='DISABLED' 显隐） | 干净 | ✅ 可先行 |
| `views/ipd/admin/identity-sync/index.test.ts`（若存在则更新） | 干净 | ✅ 可先行 |

**本卡完全零冲突**（无权限码登记需求——端点无注解码，路由既有 `meta.authority:['SUPER_ADMIN']`（ipd.ts:406-408）已覆盖页面门禁；组长侧入口如需放开，仅改该行 authority 数组为 `['SUPER_ADMIN','GROUP_LEADER']`，同文件仍干净）。

### 文件级变更清单
1. `person.ts`：`rehirePerson(personId: string, note?: string): Promise<PersonOperationView>` → `ipdPost('/persons/{id}/rehire', note ? { note } : {})`；头注补 AC-USER-09 与 DISABLED→rehire 状态机说明。
2. `identity-sync/index.vue`：`PersonAction` 联合类型加 `'rehire'`；ACTION_META 加 rehire 条目（danger:false、okText『确认复职』、tip 写明仅 HR=超管/组长可操作、note 选填）；弹窗复用现有 reason→note 字段改名分支（仅 rehire 显示『备注（选填）』非必填）。
3. 行操作列：DISABLED 行展示「复职」，ACTIVE 行隐藏（对齐 resign 显隐反向逻辑）。

### 用例清单（person.test.ts 追加）
- `rehirePerson('900101') → POST /persons/900101/rehire，body {} （note 省略不塞 null）`
- `rehirePerson(id,'返岗说明') → body 仅含 note 键`
- `PersonView 透传：id 恒 string 断言 + wecomUserId 脱敏 "***"`
- `19 位雪花 personId URL 逐字符无损（'2096266884247736321'）`
- `权限负例：非组长/超管 403 → IpdRequestError`

### 本卡禁止项
- ❌ `Number(personId)`；❌ 给 rehire 造 `@SaCheckPermission` 对应的前端新权限码常量（后端本就无注解码，凭空登记=镜像污染）；❌ note 传空串 `''`（后端 @Valid 无 @NotBlank 但审计字段留空串脏，统一 undefined→不传）。

---

## F4 P0 升级链处置视图（p0/escalation-chain）

### 端点表（P0EscalationController.java）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/api/v1/p0/escalation-chain?projectId=` | GET | query projectId?（Long→string 透传） | `List<P0EscalationChain>`：id/projectId/p0EventId(Long)、escalationCount(Integer)、lastEscalationAt/nextThresholdAt(Date)、status(`PENDING\|ESCALATED\|RESOLVED`)、remark、tenantId、delFlag | `ipd:p0-escalation:read`（:47）+ requireLeaderOrAdmin | :47-52；实体 P0EscalationChain.java:42-75；状态值域 P0EscalationService.java:54-56 |
| `/api/v1/p0/escalation-chain/check` | POST | 无 | `{ escalated: number }` | 同码 + requireAdmin（仅超管） | :57-63 |
| `/api/v1/p0/escalation-chain/{id}/resolve` | POST | **query** remark?（@RequestParam，无 body） | `{ id, resolved: boolean }` | 同码 + requireLeaderOrAdmin | :68-75 |

封装：GET/POST 用 ipdGet/ipdPost；resolve 用 `ipdPost(path, undefined, { remark })` 三参形态（http.ts:27）。Long→BigNumberSerializer 双形态 → list 行 `String()` 归一（hr-sync.ts 同法）。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/p0-escalation.ts`（新建） | 不存在 | ✅ 零冲突可先行 |
| `api/ipd/p0-escalation.test.ts`（新建） | 不存在 | ✅ 可先行 |
| `views/ipd/admin/p0-escalation/index.vue` + `index.test.ts`（新建） | 不存在 | ✅ 可先行 |
| `router/routes/modules/ipd.ts`（IpdAdmin children 追加一条，先例 :419-424） | 干净 | ✅ 可先行 |
| `ipd-permission-codes.ts/.test.ts`（登记 `P0_ESCALATION_READ: 'ipd:p0-escalation:read'`） | **test 脏** | ⛔ 等树；过渡：路由用 `meta.authority:['SUPER_ADMIN','GROUP_LEADER']`（先例 ipd.ts:436），不碰权限码文件 |

**本卡除"权限码登记"附属步骤外全部可先行；视图/路由/api 零冲突。**

### 文件级变更清单
1. 新建 `api/ipd/p0-escalation.ts`：类型 `P0EscalationChainView`（id/projectId/p0EventId: string；escalationCount: number；status 枚举）；函数 `listEscalationChains(projectId?: string)`、`checkEscalation()`、`resolveEscalationChain(id: string, remark?: string)`；ID `String()` 归一（禁 Number）。
2. 新建 `views/ipd/admin/p0-escalation/index.vue`：列表（项目过滤 + 状态 tag：PENDING 灰/ESCALATED 红/RESOLVED 绿）+「触发扫描」按钮（authority 仅超管显示；展示返回 escalated 数）+ 行「标记处置完成」Modal（remark 选填）。五态（加载/空/错/无权限/禁用）对齐 `_shared/three-state.vue` 既有范式。
3. `router/routes/modules/ipd.ts`：IpdAdmin.children 追加 `{ path:'p0-escalation', name:'IpdAdminP0Escalation', component: views/ipd/admin/p0-escalation/index.vue, meta:{ authority:['SUPER_ADMIN','GROUP_LEADER'], title:'P0 升级链' } }`。树释放后补登权限码并把 meta 换 `access:[IPD_PERMISSION_CODES.P0_ESCALATION_READ]`。

### 用例清单（p0-escalation.test.ts）
- `listEscalationChains() → GET /p0/escalation-chain 不拼 query；(pid) → ?projectId=<string 逐字符>`
- `行归一：id/projectId/p0EventId 19 位雪花无损 string；escalationCount Number()；status 原样`
- `checkEscalation() → POST /p0/escalation-chain/check，返回 {escalated} 透传`
- `resolveEscalationChain('2096…', '已闭环') → POST …/resolve?remark=%E5%B7%B2… 且无 body`
- `负例：普通成员 list 403/30001；非超管 check 403（:60 requireAdmin）`

### 本卡禁止项
- ❌ `Number(id)` 归一升级链三 ID；❌ resolve 把 remark 放 JSON body（后端 @RequestParam）；❌ 前端伪造"新建升级链"入口（后端刻意不暴露 recordP0Unresolved HTTP，Controller 头注 :32-33 防越权伪造）；❌ 为 check 按钮配组长可见（后端仅超管）。

---

## F5 月度切换验收 5 端点（switching-acceptance，原 A23 预留）

### 端点表（SwitchingAcceptanceController.java）
| 端点 | 方法 | 入参 | 出参 | 权限（2026-09-09 收紧注 :36-39） | 证据 |
|---|---|---|---|---|---|
| `/api/v1/switching-acceptance/{month}/run` | POST | path month（`yyyy-MM`） | `SwitchingAcceptanceReport` | `ipd:switching-acceptance:lock`（写口挂 LOCK） | :40-45 |
| `/api/v1/switching-acceptance/{month}` | GET | — | 同上 | `ipd:switching-acceptance:query` | :47-51 |
| `/api/v1/switching-acceptance/{month}/lock` | POST | — | 同上 | `…:lock` | :53-58 |
| `/api/v1/switching-acceptance/{month}/unlock` | POST | body `SwitchingAcceptanceUnlockReq{ reason: string }`（@NotBlank 5~500） | 同上 | `…:unlock` | :60-67；DTO SwitchingAcceptanceUnlockReq.java:11-13 |
| `/api/v1/switching-acceptance` | GET | — | `List<SwitchingAcceptanceReport>` | `…:query` | :69-73 |

`SwitchingAcceptanceReport` 字段（record，SwitchingAcceptanceReport.java:25-54）：month、ranAt、ranBy(Long)、isLocked、lockedAt、lockedBy(Long)、diffRate(BigDecimal→**string 透传**)、passed、checks[CheckResult{name,passed,expected,actual,diff,note,duplicateCount,kpiScoreSum,bonusDistributionSum}]、summary(Map<string,number>)、unlockReason、unlockedAt、unlockedBy；`@JsonInclude(NON_NULL)` → 前端字段全按可空处理。
权限集合：LOCK/UNLOCK 属 ADMIN_WRITE 仅 SUPER_ADMIN（控制器注 :36-39；码值 IpdPermissionCode.java:174/:176）。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/switching-acceptance.ts` + `.test.ts`（新建） | 不存在 | ✅ 零冲突可先行 |
| `views/ipd/operation/switching-acceptance.vue` + 同名 test（新建；挂「运营管理」分组） | 不存在 | ✅ 可先行 |
| `router/routes/modules/ipd.ts`（IpdOperation.children 追加，先例 :514-527） | 干净 | ✅ 可先行 |
| `ipd-permission-codes.ts/.test.ts`：登记 `SWITCHING_ACCEPTANCE_LOCK`/`SWITCHING_ACCEPTANCE_UNLOCK`，并处置死别名 `SWITCHING_ACCEPTANCE_ADMIN`（:112 已登记但后端注解侧已弃用，常量仍在 IpdPermissionCode.java:125 → 镜像保留、去 reserved 注释即可） | **test 脏（计数硬断言）** | ⛔ 等树；过渡：路由 `meta.authority:['SUPER_ADMIN']`（先例 ipd.ts:389、:443） |

### 文件级变更清单
1. 新建 `api/ipd/switching-acceptance.ts`：`runSwitchingAcceptance(month)`/`getSwitchingAcceptance(month)`/`lockSwitchingAcceptance(month)`/`unlockSwitchingAcceptance(month, reason)`/`listSwitchingAcceptance()`；`normalizeReport()` 把 ranBy/lockedBy/unlockedBy `String()` 归一、diffRate/diff/kpiScoreSum/bonusDistributionSum 原样 string。
2. 新建 `views/ipd/operation/switching-acceptance.vue`：月份选择（DatePicker mode=month → 格式 `YYYY-MM`）+ 已 run 月份列表（GET list）+ 报告详情（checks 五类校验明细表 + summary 计数 + diffRate 百分比展示，**展示层不改数值**）+ run/lock/unlock 按钮（unlock Modal reason 5~500 字前端预校验，对齐 @Size）。
3. `ipd.ts` IpdOperation.children 追加 `{ path:'switching-acceptance', name:'IpdOperationSwitchingAcceptance', meta:{ authority:['SUPER_ADMIN'], title:'切换验收' } }`；树释放后换 access 常量。

### 用例清单（switching-acceptance.test.ts）
- `run → POST /switching-acceptance/2026-08/run（month 入 URL 编码位）`
- `get/list 查询口 query 封装零多余参数`
- `unlock body 契约：仅 {reason}；reason 4 字 → 前端拦截不发（对齐 :12 @Size(5,500)）`
- `报告归一：ranBy/lockedBy/unlockedBy 19 位雪花无损 string；diffRate "0.0123" string 原样；NON_NULL 缺键 → undefined 不炸`
- `权限负例：组长调用 lock 403/30001（ADMIN_WRITE 仅超管）`

### 本卡禁止项
- ❌ `Number(ranBy)` / `Number(unlockedBy)`；❌ `parseFloat/Number(diffRate 等金额率)`（BigDecimal 精度串原样进 UI，聚合再谈）；❌ 用 `ipd:switching-acceptance:admin` 做前端门禁（后端注解实际吃 LOCK/UNLOCK，admin 别名是死码，控制器注 :37-38 自证）；❌ month 传 `2026-8`（须补零 `yyyy-MM`，施工时以 GET 一次真库 404/200 验证格式）。

---

## F6 合规中心 4 端点（compliance，原 A23 预留）

### 端点表（ComplianceController.java）
| 端点 | 方法 | 入参 | 出参 | 权限 | 证据 |
|---|---|---|---|---|---|
| `/api/v1/compliance/data-retention-rules` | GET | — | `List<DataRetentionRuleVO>{resourceType, retentionDays(int), deletionPolicy, legalBasis}` | `ipd:compliance:read` + requireInternal | :49-54；VO DataRetentionRuleVO.java:21-26 |
| `/api/v1/compliance/data-deletion-request` | POST | body `DataDeletionRequestDTO{resourceType ≤64, resourceId(Long), reason ≤1024}` | `DataDeletionRequestVO{id,resourceType,resourceId,requesterId,reason,status(PENDING/PROCESSED/REJECTED),deadlineAt,createdAt}` | `ipd:compliance:write`（actor 服务端推导，:60） | :57-61；DTO :23-35；VO :28-37 |
| `/api/v1/compliance/audit-trail/{resourceType}/{resourceId}?pageNo=&pageSize=` | GET | path 两段 + 分页 | `IPage<AuditEntryVO>{seq,actorId,actorName,action,before,after,createTime,entityType,entityId}` | `ipd:compliance:read` | :64-73；VO AuditEntryVO.java:28-38；IPage 复用 bid.ts:58-64 |
| `/api/v1/compliance/permission-separation/{userId}` | GET | path userId | `PermissionSeparationVO{userId,hasReadRole,hasWriteRole,conflict,roleList[]}` | `ipd:compliance:read` | :76-81；VO :26-32 |

封装 code=0 包络 ipdGet/ipdPost。**权限码零登记需求**：`COMPLIANCE_READ/WRITE` 已在 ipd-permission-codes.ts:120-121（仅 reserved 注释需去除——.ts 干净、纯注释/键值不动计数，不触脏 test 断言，可先行；稳妥亦可并入树后批）。

### 冲突检查表
| 计划触碰文件 | git status | 结论 |
|---|---|---|
| `api/ipd/compliance.ts` + `.test.ts`（新建） | 不存在 | ✅ 零冲突可先行 |
| `views/ipd/admin/compliance/index.vue` + `index.test.ts`（新建，四区块） | 不存在 | ✅ 可先行 |
| `router/routes/modules/ipd.ts`（IpdAdmin.children 追加） | 干净 | ✅ 可先行 |
| `ipd-permission-codes.ts`（仅去 :120-121 reserved 注释） | 干净（键数不变） | ✅ 可先行（低风险） |

**本卡完全零冲突。**

### 文件级变更清单
1. 新建 `api/ipd/compliance.ts`：`fetchRetentionRules()`、`createDataDeletionRequest({resourceType, resourceId: string, reason})`、`fetchAuditTrail(resourceType, resourceId, pageNo?, pageSize?)`、`checkPermissionSeparation(userId)`；VO 中 id/resourceId/actorId/entityId/requesterId/seq 全部 `String()` 归一，retentionDays/pageNo `Number()`。
2. 新建 `views/ipd/admin/compliance/index.vue` 四 Tab：①保留规则表（只读）②删除请求表单（resourceType 下拉 + resourceId 文本框 string + reason textarea → 提交后展示 deadlineAt 30 天期限）③审计链查询（type+ID 输入 → 分页表）④R/W 分离判定（userId 输入 → conflict 红标 + roleList tags）。
3. `ipd.ts`：IpdAdmin.children 追加 `{ path:'compliance', name:'IpdAdminCompliance', meta:{ access:[IPD_PERMISSION_CODES.COMPLIANCE_READ], title:'合规中心' } }`（码已存在，无需 authority 过渡）。

### 用例清单（compliance.test.ts）
- `fetchRetentionRules → GET /compliance/data-retention-rules，数组透传`
- `createDataDeletionRequest：body 恰三键（无 requesterId/operatorId，SEC-API-01 :38）；resourceId 19 位雪花 string 无损`
- `fetchAuditTrail('project','2096…',2,50) → GET …/audit-trail/project/2096…?pageNo=2&pageSize=50；records.seq/actorId/entityId 归一 string`
- `checkPermissionSeparation → GET …/permission-separation/<id>；conflict=true 布尔严格断言`
- `负例：无 write 码创建删除请求 403/30001；非内部人 401`

### 本卡禁止项
- ❌ `Number(resourceId/userId/entityId)`；❌ body 夹带 requesterId（后端会话推导，传了也无效且违反 SEC-API-01 审计口径）；❌ 与 DeletionRequestController 域（api/ipd/deletion.ts）混用——两套后端表同屏不同链，本卡是 compliance 侧删除**请求登记**，勿接 `/deletion-requests` 审批链。

---

## 总表：可先行 vs 必须等树释放

| 卡 | 完全零冲突（树释放前可整体施工） | 可先行部分 | 必须等树释放部分（等兄弟 commit 后基于新版） |
|---|---|---|---|
| F1 modify+admin-assign | ✅ 是 | 全部 | 无 |
| F2 切 p231-create | ❌ 否 | bid.ts 切换 + create 视图/测试 + bid.test.ts 新建（project.ts 仅 import） | 权限码登记 `BID_INVITATION_CREATE`（ipd-permission-codes.ts + 脏 test 计数断言） |
| F3 rehire | ✅ 是 | 全部 | 无 |
| F4 P0 升级链视图 | ❌ 否（仅权限码附属项） | p0-escalation.ts/test + 视图 + 路由（authority 过渡门禁） | 权限码登记 `P0_ESCALATION_READ` + meta.access 切换 |
| F5 切换验收 | ❌ 否（仅权限码附属项） | switching-acceptance.ts/test + 视图 + 路由（authority 过渡门禁） | 权限码登记 `SWITCHING_ACCEPTANCE_LOCK/UNLOCK`（+死别名 ADMIN 处置）+ meta.access 切换 |
| F6 合规中心 | ✅ 是 | 全部（含去 reserved 注释，键数不变） | 无 |

**结论**：F1、F3、F6 三卡**完全零冲突**，现在即可整体施工；F4、F5 功能链路零冲突、仅「权限码常量登记 + meta.access 替换」两步等树（过渡期用 `meta.authority` 角色门禁，不降安全）；F2 主链路可先行、等树项同为权限码登记。全部脏文件集中在 `views/ipd/_shared/ipd-permission-codes.test.ts` 一个兄弟在改文件 + `api/ipd/project.ts`（仅 import 不修改）。

**每卡收尾五连（施工完成的判定口径）**：
1. `npx turbo run typecheck --force --filter=@vben/web-antd` EXIT=0
2. `npx vitest run --dom -c vitest.ipd.config.mts apps/web-antd/src/api/ipd/<新test>.test.ts` 全绿
3. `node scripts/check-api-contract-fe-be.mjs --json`（ruoyi-ai 仓）孤儿数对应递减：F1 -2、F2 -1（存量 /bid-invitations 若保留 legacy 引用则不减，裁撤旧口 -1+0）、F3 -1、F4 -3、F5 -5、F6 -4
4. 契约测试含 19 位雪花逐字符无损断言（hr-sync.test.ts:85-87 同款）
5. 翻卡以 commit 落地为准（前档 §二 裁决：未提交不翻 done，防假绿）
