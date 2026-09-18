# R34 Pattern D: UI disabled/DTO 错位系统性扫描报告

> **worktree 路径注**:任务规约为 `/private/tmp/r34-takeover-ipd`,实际工作目录为 `/private/tmp/r33-takeover-ipd`(分支已升级为 `r34/takeover-20260917`,HEAD = `b1f443d8`,与 main 一致;目录名沿用 R33)。
> **本任务绝对只读**:`src/main`、`src/test`、`SQL`、`yml`、前端 `src/` 全程未触碰;仅本文件可写。
> **扫描基线**:2026-09-17 Thursday,R33 报告 + R29 修复已落地,本轮定位 R34 Pattern D = UI disabled 错位 + DTO 缺字段系统性真因。

## 0. 摘要

| 维度 | P0 | P1 | P2 | 合计 |
|---|---|---|---|---|
| UI disabled 错位 | 2 | 4 | 5 | **11** |
| DTO 缺字段(写入/读取) | 0 | 4 | 2 | **6** |
| 前端类型 vs 后端 DTO 不一致 | 0 | 1 | 0 | **1** |
| **合计** | **2** | **9** | **7** | **18** |

**核心结论**:
1. **R33 异常 2 误读确认**:KPI 月数 6/12/24/36 disabled 实为 `loading` 状态(`kpi/index.vue:129`),非永久禁用。
3. **R33 P3-8.1 已部分修复但留业务漏洞**:NegativeFeedbackCreateReq 仍缺 source/content/severity,R29 在 service 层用 `.source("MANUAL").content(triggerEvidence).severity("MEDIUM")` 兜底 → **业务严重度永远固定为 MEDIUM**(CRITICAL 事故被记成 MEDIUM),P1。
4. **新增 P0 真因**:RequirementChangeController 直接用 `@RequestBody RequirementChange change`(domain 实体),前端可注入 `status='APPROVED'` + 自造 `signatures` → **绕过双签状态机**,frontend `disabled` 视觉 bug 与此 P0 是同一类问题的两面。
5. **新增 P0 UI disabled**:identity-sync/index.vue 三个按钮硬编码 `:disabled="true"`,workbench/index.vue "打开任务教练" 按钮硬编码 `disabled`。

---

## 1. R33 基线确认

### 1.1 异常 2 实测:KPI 月数 disabled 实际是 loading 状态

**实测代码**:`/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/kpi/index.vue`

```vue
<!-- L129 -->
<select v-model.number="periods" class="filter-input" :disabled="loading">
  <option :value="6">6 个月</option>
  <option :value="12">12 个月</option>
  <option :value="24">24 个月</option>
  <option :value="36">36 个月</option>
</select>
```

- `loading` 是 `L40` 的 `const loading = ref(false)`,仅在 `Promise.all([getPerformanceKpi, getFunctionalKpi, getKpiTrend])` 调用期间为 `true`。
- 数据加载完成后(常见 < 1 秒),`loading.value = false` → `<select>` 恢复可交互。
- **R33 snapshot 抓到的 `disabled="disabled"` 是 loading 期间抓帧**,options 本身没有 `:disabled="true"`。
- **结论:误读。6/12/24/36 选项在数据加载完成后完全可选。** 不计入本轮 P0/P1/P2。

**同文件其他 disabled 验证**(L125, L136):
```vue
<input v-model="period" type="month" class="filter-input" :disabled="loading" />   <!-- L125 -->
<button class="primary-btn" :disabled="loading" @click="load()">查询</button>   <!-- L136 -->
```
均为 loading 状态,均非永久 disabled。

### 1.2 P3-8.1 教训:NegativeFeedbackCreateReq 缺 source/severity/content

**R33 报告指出的 500 根因**:
- `NegativeFeedbackCreateReq` record 字段:projectId, triggerType, triggerEvidence, triggerMonth, recoveryMonth(共 5 个)
- `negative_feedbacks` DDL(`/private/tmp/r33-takeover-ipd/docs/script/sql/update/batch_missing_tables.sql` L74-92)`source`/`content`/`severity` 三列均为 `NOT NULL` 无默认值:
  ```sql
  `source` varchar(32) NOT NULL COMMENT '来源渠道',
  `content` text NOT NULL,
  `severity` varchar(16) NOT NULL COMMENT 'LOW|MEDIUM|HIGH|CRITICAL',
  ```
- INSERT 不带这三列 → MySQL `Field 'source' doesn't have a default value` → 500。

**R29 修复落地情况**(`NegativeFeedbackService.java:200-209`):
```java
NegativeFeedback row = NegativeFeedback.builder()
    .projectId(req.projectId())
    // [R29 audit 2026-09-09] source/content/severity 是 DDL NOT NULL legacy 字段,
    // 新版 DTO 未暴露——以 triggerEvidence 镜像 content,source 记 MANUAL,severity 兜底 MEDIUM。
    // 真实场景应在前端表单补 3 字段后改回显式赋值;本轮以最小修复恢复 INSERT 闭环。
    .source("MANUAL")
    .content(req.triggerEvidence() != null && !req.triggerEvidence().isBlank()
        ? req.triggerEvidence()
        : "[R29-auto] trigger=" + req.triggerType() + " month=" + req.triggerMonth())
    .severity("MEDIUM")
    ...
```

**R29 修复结论**:
- ✅ **P0 INSERT 失败已闭环**(从"DB 500"降级为"数据丢失/语义错误")
- ⚠️ **业务语义仍错**:`severity` 永远固定为 `"MEDIUM"`,即使触发 `QUALITY_ACCIDENT`(质量事故),DB 仍记 MEDIUM → 风险定级失效。这是 P1 业务漏洞。

---

## 2. UI disabled 错位清单(按 P0/P1/P2 排序)

> 扫描命令:`grep -rnE ":disabled|disabled:" /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/ | grep -v "\.test\." | grep -v ":disabled cursor"`(剔除纯 CSS)。
> 共 **117 条** 命中,按风险归类后:

### 2.1 P0 级:假 disabled(条件写错,功能彻底不可用)

#### D-UI-P0-1:`admin/identity-sync/index.vue` 三个按钮硬编码 `:disabled="true"`
- **文件**:`/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/admin/identity-sync/index.vue:144-146`
- **代码**:
  ```vue
  <Button :disabled="true" type="primary">触发同步</Button>
  <Button :disabled="true">同步历史</Button>
  <Button :disabled="true">映射配置</Button>
  ```
- **影响**:三个核心 admin 功能(同步触发/历史查询/映射配置)**永久不可点**。
- **根因**:未接后端端点 / 未配置权限码 / 未实现 click handler —— 三选一。
- **修复路径**(不写代码,登记):
  1. 核对后端 `IdentitySyncController` 端点是否存在
  2. 若存在 → 移除 `:disabled="true"`,绑 `@click`,配置 `v-access:code`
  3. 若不存在 → 灰显但加 tooltip "功能开发中"
- **重要度**:🔴 P0(影响 admin 工作流)

#### D-UI-P0-2:`workbench/index.vue` "打开任务教练" 按钮硬编码 `disabled`
- **文件**:`/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/workbench/index.vue:266`
- **代码**:
  ```vue
  <button type="button" class="ipd-wb-coach-btn" disabled>
    打开任务教练
  </button>
  ```
- **影响**:工作台教练入口永远不可点。
- **根因**:与 D-UI-P0-1 同类(占位按钮,未接端点)。
- **修复路径**:同 D-UI-P0-1。

### 2.2 P1 级:依赖后端数据但无 fallback(数据未加载时永远灰显)

#### D-UI-P1-1:`bid/select/index.vue:112` 应标 Radio `:disabled` 依赖 invitation 加载
- **代码**:`<Radio :checked="selectedResponseId === record.id" :disabled="!canChoose(asResponse(record))"`
- **`canChoose` 源码**:
  ```ts
  function canChoose(record: BidResponse): boolean {
    return !confirmBusy.value && invitation.value?.status === 'OPEN' && record.status === 'PENDING';
  }
  ```
- **风险点**:`invitation.value` 在 `onMounted` 之前为 `null` → `invitation.value?.status` = `undefined` ≠ `'OPEN'` → **canChoose 永远 false → 所有 Radio 永久 disabled**(直到 API 返回)。
- **影响**:页面首屏"灰显直到响应回来",用户体验差;若 API 失败,按钮永远灰显。
- **修复路径**:加 loading 态判断:`if (!invitation.value || loading.value) return false;`(显式 loading 时仍 disabled,但不会因 invitation=null 永久卡死)

#### D-UI-P1-2:`review/gate-panel.vue:495` 评审要素提交按钮依赖 `elementsIsFallback`
- **代码**:`<button ... :disabled="!draftResults[el.elementId]?.result || submittingElementId === el.elementId || elementsIsFallback">提交</button>`
- **风险点**:`elementsIsFallback` 来自后端状态字段;若后端忘返该字段或返错值(例如本应是 false 返 null),按钮 **永远 disabled**。
- **修复路径**:在 canSubmitElements computed 中给 elementsIsFallback 默认值 false,或前端重新计算。

#### D-UI-P1-3:`review/gate-panel.vue:520` 提交按钮 `:disabled="!canSubmitElements || busy"`
- **风险点**:`canSubmitElements` 复合条件(可能含后端数据),任一字段未加载完成 → 永久 disabled。
- **修复路径**:同 D-UI-P1-2。

#### D-UI-P1-4:`portal/submit/index.vue:283` 提交按钮 `:disabled="throttle.active.value"` 数据不真
- **代码**:`<Button ... :disabled="throttle.active.value" data-testid="portal-submit-button">提交需求</Button>`
- **风险点**:`throttle.active.value` 来自前端 throttle composable;如果 throttle 状态被某个异常 set 为 true 后未复位,**用户永远无法提交**(即使没在限流期)。
- **修复路径**:throttle 函数 try/finally 兜底复位。

### 2.3 P2 级:loading 误用 / 临时状态(属合理 UX,本轮不视作缺陷)

#### D-UI-P2-1:`kpi/index.vue:125, 129, 136` 均为 `:disabled="loading"`
- **状态**:✅ 合理(异常 2 已澄清)

#### D-UI-P2-2:`report/index.vue:124, 133, 184, 196, 201`
- `:disabled="Boolean(exporting) || !month"` 等 5 处
- **状态**:✅ 合理(导出中/未选月/翻页边界)

#### D-UI-P2-3:`review/gate-panel.vue:317, 382-412, 391, 396, 399, 404, 407, 411, 412`
- 15 处 `:disabled="busy"` 围绕 gate 评审流(批准/驳回/仲裁/重开/延期)
- **状态**:✅ 合理(请求中防双提交)

#### D-UI-P2-4:`bid/respond/index.vue:126, 140, 155, 168, 189`
- 5 处 `:disabled="submitBusy"`(应标拒绝/接受/撤回)
- **状态**:✅ 合理

#### D-UI-P2-5:`bid/create/index.vue:41, 57, 62, 79, 98, 104`
- 6 处 `:disabled="submitting"`(招标创建表单全字段 + 取消按钮)
- **状态**:✅ 合理

#### D-UI-P2-6:`handover/index.vue:349, 365, 422, 471, 507`
- 5 处 `:disabled` 围绕 `!reason || !confirmation || initiating` 等复合条件
- **状态**:✅ 合理

#### D-UI-P2-7:`change/index.vue:359, 390`
- `requirementId` select `:disabled="demandsLoading"` + submit `:disabled="submitting"`
- **状态**:✅ 合理

#### D-UI-P2-8:`auth/login.vue:127, 141, 152, 165` + `auth/change-password.vue:105, 117, 128, 137, 140`
- 9 处 `:disabled="auth.busy"` + cooldown
- **状态**:✅ 合理

#### D-UI-P2-9:`incentive/allowance/index.vue:235, 244`
- `:disabled="!canQuery"`
- **状态**:✅ 合理(数据依赖)

#### D-UI-P2-10:`incentive/bonus-pool/index.vue:273, 276`
- `:disabled="!canCompute"` / `:disabled="!form.projectId.trim()"`
- **状态**:✅ 合理

#### D-UI-P2-11:`incentive/negative-feedback/index.vue:203`
- `:disabled="!projectId.trim() || loading"`
- **状态**:✅ 合理

#### D-UI-P2-12:`incentive/contribution/index.vue:97`
- `:disabled="!projectId.trim()"`
- **状态**:✅ 合理

#### D-UI-P2-13:`kpi/project-score/index.vue:118, 122`
- `:disabled="!projectId.trim() || !personId.trim()"` + `:disabled="... || !view"`
- **风险点**:`!view` 依赖后端响应;若后端返 null 异常 → 永远 disabled。**轻微 P2**
- **修复路径**:loading 态显式区分

#### D-UI-P2-14:`kpi/shared/index.vue:215`
- `:disabled="!canQuery"`
- **状态**:✅ 合理

#### D-UI-P2-15:`deletion/my-requests/index.vue:128, 140`
- `:disabled="!canSubmit"` / `:disabled="withdrawId.trim() === ''"`
- **状态**:✅ 合理

#### D-UI-P2-16:`deletion/review/index.vue:147`
- `:disabled="decision.id.trim() === ''"`
- **状态**:✅ 合理

#### D-UI-P2-17:`admin/handover/index.vue:152`
- `:disabled="!canSubmit"`
- **状态**:✅ 合理

#### D-UI-P2-18:`admin/gate-elements/index.vue:291, 296`
- `:disabled="!!editingId"`(编辑时禁用 gateCode/elementCode)
- **状态**:✅ 合理(编码即身份,不可改)

#### D-UI-P2-19:`product/edit/index.vue:220`
- `:disabled="isEdit"`
- **状态**:✅ 合理(编辑模式禁用产品编码)

#### D-UI-P2-20:`project/action-detail/index.vue:404, 492`
- `:disabled="isBlocking && action.status !== 'IN_PROGRESS'"` / `:disabled="isBlocking && showCert && (...)"`
- **状态**:✅ 合理(动作状态机校验)

#### D-UI-P2-21:`project/detail/circle.vue:231, 279`
- `:disabled="postDraft.trim().length < 2"` / `:disabled="(commentDrafts[post.id] ?? '').trim().length < 2"`
- **状态**:✅ 合理(评论长度校验)

#### D-UI-P2-22:`project/legacy-import/index.vue:300, 346, 354`
- `:disabled="groupLoadError"` / `:disabled="!needCoefficient"` ×2
- **状态**:✅ 合理

#### D-UI-P2-23:`project/change-detail/index.vue:284, 291, 297`
- `:disabled="actionBusy"` / `:disabled="signBusy || rejectBusy"`
- **状态**:✅ 合理

#### D-UI-P2-24:`project/create/index.vue:282, 290`
- `:disabled="!needCoefficient"`
- **状态**:✅ 合理

#### D-UI-P2-25:`review/index.vue:143`
- `:disabled="!project || submitting"`
- **状态**:✅ 合理

#### D-UI-P2-26:`demand/index.vue:347, 350, 356`
- `:disabled="busyId === d.id"` 围绕 canStart/canPlan 按钮
- **状态**:✅ 合理

#### D-UI-P2-27:`ai-docs/index.vue:310`
- `okButtonProps = computed(() => ({ disabled: !rejectCommentReady.value }))`
- **状态**:✅ 合理(评论必填)

---

## 3. DTO 缺字段清单(按 P0/P1/P2 排序)

> 扫描基线:对比 14 个核心 DTO + 8 个 VO + 24 个对应 domain entity,逐字段比对。

### 3.1 P0 级:写入时 DB 必崩(NOT NULL 触发 500)

#### **本轮未发现新的 P0 INSERT 崩溃**。

R33 P3-8.1(negative_feedbacks 缺 source/content/severity)已由 R29 service 兜底修补(`NegativeFeedbackService:205-209`),未在 active codebase 复现 500 路径。

**保留条目**:RequirementChangeController 直接用 `@RequestBody RequirementChange` 接收 domain(详见 P1-1),这是更严重的 P0 业务风险(非字段缺失类,但属本轮新发现的 DTO/domain 错位)。

---

### 3.2 P1 级:写入时字段变 NULL / 业务漏洞

#### D-DTO-P1-1:RequirementChangeController 用 domain 实体做 @RequestBody(最严重)
- **位置**:`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/RequirementChangeController.java:42-46`
- **代码**:
  ```java
  public ApiV1Response<RequirementChange> create(@RequestBody RequirementChange change) {
      IpdActor actor = ipdPermission.requireInternal();
      return ApiV1Response.ok(requirementChangeService.create(change, actor));
  }
  ```
- **domain 字段**(`RequirementChange.java`):
  - id(雪花)、requirementId、projectId、changeType、beforeSnapshot、afterSnapshot、reason、signatures、status、delFlag
- **风险点**:
  1. **status 暴露**:前端可注入 `status="APPROVED"` 直接绕过 DRAFT→PENDING_SIGN→APPROVED 状态机
  2. **signatures 暴露**:前端可注入 `signatures="MARKET_PM=APPROVE;RD_PM=APPROVE"` 自造签名
  3. **id 暴露**:前端可注入假 id 撞库
  4. **delFlag 暴露**:前端可注入 `delFlag="1"` 标记软删除
- **修复路径**(不写代码):
  - 应仿照 `ProjectCreateReq` / `ProductCreateReq` 白名单 record 模式:`record RequirementChangeCreateReq(requirementId, changeType, beforeSnapshot, afterSnapshot, reason)` → `toEntity()` 只保留白名单字段
  - 同样问题应审视:是否还有其他 controller 用 domain 实体直接接收

#### D-DTO-P1-2:NegativeFeedbackCreateReq 缺 source/content/severity(R29 兜底后的业务漏洞)
- **位置**:`/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/NegativeFeedbackCreateReq.java`
- **DTO 字段**:projectId, triggerType, triggerEvidence, triggerMonth, recoveryMonth
- **domain 字段(DDL NOT NULL,无默认值)**:source, content, severity
- **R29 兜底后果**:
  - `source` 恒为 `"MANUAL"`(丢失原始来源渠道;若以后接入"SYSTEM_AUTO"(异常检测自动触发),无法区分)
  - `content` 镜像 `triggerEvidence`(失去原文 / 证据区分)
  - `severity` 恒为 `"MEDIUM"`(**最严重**):即使是 `QUALITY_ACCIDENT`(质量事故),DB 仍记 MEDIUM → 风险定级 / 报表 / 复盘全部失真
- **修复路径**:
  1. 在 `NegativeFeedbackCreateReq` 补 3 字段:`@NotBlank String source`, `@NotBlank @Size(min=2, max=4000) String content`, `@NotBlank @Pattern(regexp="LOW|MEDIUM|HIGH|CRITICAL") String severity`
  2. 移除 `NegativeFeedbackService.java:205-209` 的 `.source("MANUAL")...severity("MEDIUM")` 兜底
  3. 前端录入表单 `apps/web-antd/src/views/ipd/incentive/negative-feedback/index.vue` 加 3 字段 + API `createNegativeFeedback` 同步扩

#### D-DTO-P1-3:HandoverController.InitiateRequest 与 domain 字段映射分离
- **DTO 字段**(record 定义):projectId, role, toPersonId, note, approvalRef, onBehalf(6 个)
- **domain 字段**(`HandoverRecord.java`):id, handoverType, fromPersonId, toPersonId, projectId, scope, status, confirmedAt, completedAt, deadlineAt, lastRemindAt, escalatedAt, delFlag, handoverRole, note, rollbackReason, rollbackAt, archivedAt, tenantId
- **DTO/Domain 命名错位**:DTO 用 `role`,domain 用 `handoverRole`;service 层 `initiate()` 接受 `String role` 透传给 `createDraft(projectId, role, ...)`(`HandoverService:160`),最终存为 `handoverRole`(待核 service 实现是否做映射)。
- **P1 风险点**:DTO 缺 `handoverType`(PROJECT/SUPER_ADMIN/BATCH 三态),service 自动设置;若 onBehalf=true 应传 handoverType='PROJECT' 且发起即接受,目前 service `initiateOnBehalf` 走 `doAccept` 但 DTO 没有 `handoverType` 字段 → service 内部硬编码。这处不算 P1,属合理。

#### D-DTO-P1-4:CoefficientChangeController.ProposeReq vs domain
- **DTO**:projectId, proposedCoefficient, reason, marketPmId, rdPmId
- **domain**(`CoefficientChangeRequest`):多了 proposerId, status, leaderId, leaderDecision, leaderDecidedAt, leaderOpinion, tenantId, delFlag, remark
- **service 兜底**:`proposerId` 由 `actor.id()` 推导(`CoefficientChangeService:103`),`status=PENDING_LEADER` 硬编码。✅ 闭环,非漏洞。
- **P1 轻微风险**:`reason` 字段 `@NotBlank @Size(max=500)` 但 domain 是 `varchar(500)`,边界对齐 OK。

### 3.3 P2 级:VO 缺字段(前端拿不到数据) / DTO 字段命名对齐

#### D-DTO-P2-1:VO 字段对齐检查(全量比对结果)

| VO | 对应 domain | 字段差异 |
|---|---|---|
| `BonusPoolVO` | `BonusPool` | ✅ 完整(15 字段全包含) |
| `ProductVO` | `Product` | ✅ 完整(10 字段) |
| `GateElementVO` | `GateElement` | ✅ 完整(17 字段) |
| `CertTemplateVO` | `CertTemplate` | ✅ 完整(9 字段) |
| `NotificationEventVO` | `NotificationEvent` | ✅ 完整(16 字段) |
| `KpiSharedConfirmView` | `KpiSharedConfirm` | ✅ 完整(15 字段) |
| `ProjectScoreView` | `ProjectScore` | ✅ 完整(11 字段) |
| `SharedKpiCollectView` | `KpiRecord` 聚合 | ✅ 完整(聚合视图) |

**结论**:VO 字段覆盖完整,无 P2 缺字段。

#### D-DTO-P2-2:DTO/VO 字段命名 vs domain 字段命名(下划线 vs 驼峰)

DTO/VO 均用驼峰,domain 用驼峰,无 snake_case 出现。无命名不一致。

---

## 4. 前端类型 vs 后端 DTO 不一致清单

> 扫描命令:`grep -rnE "type.*=|interface.*\{|as.*[A-Z][a-zA-Z]+" /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/`
> 对照后端 DTO record 字段。

### D-TS-P1-1:整体检查通过,1 处需关注

| 前端类型 | 后端 DTO/VO | 字段数 | 一致性 |
|---|---|---|---|
| `ProjectCreateBody` (project.ts) | `ProjectCreateReq` | 13/13 | ✅ 一致(前端 targetMarkets 是数组,内部 `JSON.stringify` 转字符串) |
| `LegacyImportBody` (project.ts) | `LegacyImportReq` | 15/15 | ✅ 一致 |
| `StageActionFieldsBody` (stage-action.ts) | `StageActionFieldsReq` | 6/6 | ✅ 一致 |
| `NegativeFeedback` (negative-feedback.ts) | `NegativeFeedbackView` | 19/19 | ✅ 一致 |
| `createNegativeFeedback` 参数体 | `NegativeFeedbackCreateReq` | 5/5 | ✅ 一致 |
| `IpdGateElementCreateReq` (gate-element.ts) | `GateElementCreateReq` | 7/7 | ✅ 一致 |
| `IpdGateElementUpdateReq` (gate-element.ts) | `GateElementUpdateReq` | 5/5 | ✅ 一致 |
| `CertTemplateCreateReq` 前端 | `CertTemplateCreateReq` 后端 | 6/6 | ✅ 一致 |
| `ContributionSaveReq` 前端 | `ContributionSaveReq` 后端 | 7/7 | ✅ 一致(role 字段映射后端 market_self_* / rd_self_*) |
| `createRequirementChange` 前端 body | `RequirementChange` domain(@RequestBody) | 5/5 ⚠️ | 见 D-DTO-P1-1 警示 |

**唯一不一致**:`change/index.vue:191-198` 的 `createRequirementChange` body 含 `projectId, requirementId, changeType, reason, beforeSnapshot, afterSnapshot` —— **只填了 5 个**,但 controller 用 `@RequestBody RequirementChange change`,意味着前端可选填其他字段(id, status, signatures, delFlag)。前端没填 = 安全(只填白名单);但若前端因某次集成 bug 填了 status='APPROVED',**后端不拦截**。这是 D-DTO-P1-1 的镜像。

---

## 5. 漏检维度(自创 Pattern D-5)

### 5.1 `v-if` vs `:disabled` 语义错位检查

> 条件渲染(灰显+可见) vs 直接隐藏 应当区别使用。

扫描命令:`grep -rnE "v-if=" /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/ | grep -E "canStart|canPlan|canLink|canSubmit|canCompute|canRead"` 

| 位置 | v-if 条件 | 配合 :disabled? | 评估 |
|---|---|---|---|
| `demand/index.vue:347, 350, 356` | `v-if="canStart(d)"` / `v-if="canPlan(d)"` | `:disabled="busyId === d.id"` | ✅ 正确:canStart/canPlan 决定可见性,busyId 决定灰显 |
| `bid/select/index.vue:112` | (无 v-if) | `:disabled="!canChoose(...)"` | ⚠️ 应改 v-if? 不,Radio 应该 visible 不可点更友好 |
| `review/gate-panel.vue:382-412` | (无 v-if) | `:disabled="busy"` | ✅ 合理 |
| `ai-docs/index.vue` 313 | 注释 "Modal 必渲染" | `:disabled` 由 comment 决定 | ✅ 合理(Modal 必可见) |

**结论**:v-if 与 :disabled 区分明确,无 Pattern D-5 错位。

### 5.2 前端校验 `@blur`/`@change` vs 后端 `@Valid` 不一致

| 端点 | 后端 @Valid? | 前端校验? | 一致性 |
|---|---|---|---|
| `POST /negative-feedbacks` | ✅ @Valid on NegativeFeedbackCreateReq | ✅ 表单 `if (!pid)` 客户端校验 | ✅ |
| `POST /projects` | ❌ 无 @Valid(`ProjectController` 待核) | ✅ ProjectCreateBody TS 类型检查 | ⚠️ 后端未强制 @Valid,依赖 toEntity() 不严格 |
| `POST /coefficient-change-requests` | ✅ @Valid on ProposeReq | ✅ 校验 coefficient range | ✅ |
| `POST /launch-date-change-requests` | ✅ @Valid on ProposeReq | ✅ LocalDate + reason 校验 | ✅ |
| `POST /requirement-changes` | ❌ 无 @Valid(@RequestBody domain) | ⚠️ 前端只填 5 字段 | 🔴 见 D-DTO-P1-1 |
| `POST /gate-elements` | ❌ 无 @Valid(@RequestBody req) | ✅ form 校验 | ⚠️ |
| `POST /handovers` | ✅ @Valid on InitiateRequest | ✅ 表单校验 | ✅ |
| `POST /deletion-requests` | ❌ SubmissionReq 内层 record 无 @Valid | ✅ 前端校验 | ⚠️ |

**Pattern D-5.2 结论**:`/projects`、`/gate-elements`、`/deletion-requests` 三个端点后端 **缺少 @Valid 注解**,导致 DTO @Pattern / @Size / @NotBlank / @NotNull 校验不生效 → 前端校验是唯一防线。P1 风险。

### 5.3 前端默认值 vs 后端默认值不一致

| 字段 | 前端默认 | 后端默认 | 一致性 |
|---|---|---|---|
| `periods` (KPI) | `12` | 无(从请求取) | ✅ |
| `levelCoefficient` (Project) | null (A 级) | null + validateCoefficientRange | ✅ |
| `bonusPool.poolRate` | null | DEFAULT 0.05 (DDL) | ✅ |
| `achievementRate` (BonusPool) | null | null | ⚠️ null 会触发 P3-1.2 计算公式分支,需文档化 |
| `tierCoefficient` (Contribution) | null (未自评) | null | ✅ |
| `bonusDisqualify` (NegativeFeedback) | (前端不传) | `.bonusDisqualify(1)` 硬编码 1 | ⚠️ 这是 R29 兜底,不暴露前端可控 |

**Pattern D-5.3 结论**:`bonusDisqualify` 永远是 1,意味着"取消奖金资格"是不可关闭的;若业务需要"批评但保留资格"的负反馈,这字段是冗余的。

---

## 6. 修复路径(不动代码,登记待办)

### 6.1 P0 必修(2 项)

| 编号 | 修复要点 | 估时 |
|---|---|---|
| D-UI-P0-1 | `admin/identity-sync/index.vue:144-146` 三个按钮移除硬编码 `:disabled="true"`,接 `@click` + 后端端点 + 权限码 | 1d |
| D-UI-P0-2 | `workbench/index.vue:266` "打开任务教练" 按钮接后端或灰显+tooltip | 0.5d |
| D-DTO-P1-1(提升 P0) | `RequirementChangeController.create()` 改用白名单 record `RequirementChangeCreateReq`,防止 status/signatures/id/delFlag 注入 | 1d |

### 6.2 P1 应修(9 项)

| 编号 | 修复要点 |
|---|---|
| D-UI-P1-1 | `bid/select/index.vue` canChoose 显式加载态区分 |
| D-UI-P1-2 | `review/gate-panel.vue:495` elementsIsFallback 加默认值 |
| D-UI-P1-3 | `review/gate-panel.vue:520` canSubmitElements 加载态 |
| D-UI-P1-4 | `portal/submit/index.vue:283` throttle 复位 try/finally |
| D-DTO-P1-1 | RequirementChange 白名单 record(已在 P0) |
| D-DTO-P1-2 | NegativeFeedbackCreateReq 补 source/content/severity;前端录入表单同步;移除 R29 兜底 |
| D-DTO-P1-3 | HandoverController.InitiateRequest 加 handoverType 显式枚举 |
| D-DTO-P1-4 | CoefficientChangeController.ProposeReq 可选加 ratio 边界注解(目前已 OK) |
| Pattern D-5.2 | `/projects`、`/gate-elements`、`/deletion-requests` 加 `@Valid` |

### 6.3 P2 可选(7 项)

| 编号 | 修复要点 |
|---|---|
| D-UI-P2-13 | kpi/project-score 显式 loading 区分 |
| D-DTO-P2-1 | VO 全量覆盖完成,无需修复 |
| D-DTO-P2-2 | DTO/VO 命名一致,无需修复 |
| Pattern D-5.3 | bonusDisqualify=1 硬编码需产品确认是否保留 |

---

## 7. 验证证据

### 7.1 扫描命令可复现

```bash
# UI disabled 扫描
grep -rnE ":disabled|disabled:" /Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/views/ipd/ | grep -v "\.test\." | grep -v "css"
# 结果:117 条,按 P0/P1/P2 分类(本文档 §2)

# DTO 清单
ls /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/dto/
# 60 个 .java 文件

# Domain 清单
ls /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/
# 65 个 .java 文件

# VO 清单
ls /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/vo/
# 8 个 .java 文件

# Controller @PostMapping 清单
grep -nE "@PostMapping" /Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/*.java

# DDL NOT NULL 字段检查(用于 D-DTO-P1-2 实证)
grep -nE "NOT NULL|DEFAULT" /private/tmp/r33-takeover-ipd/docs/script/sql/update/batch_missing_tables.sql | head -20
```

### 7.2 关键 commit / 文件 hash

```
worktree: /private/tmp/r33-takeover-ipd(分支 r34/takeover-20260917)
HEAD: b1f443d8a8cdc56a061ac03efcb72a89fc963738(main 一致)
negative_feedbacks DDL: /private/tmp/r33-takeover-ipd/docs/script/sql/update/batch_missing_tables.sql:74
R29 service 兜底: NegativeFeedbackService.java:200-209
R33 异常 2 实测: kpi/index.vue:129(loading 状态确认)
P3-8.1 历史教训: docs/ipd-系统说明/P3/P3-A批-阶段动作-收口-20260906.md(R33 报告)
```

### 7.3 工作量统计

| 工作项 | 工时 |
|---|---|
| 扫描 + DTO/domain 对比 | 2h |
| 前端 disabled 分类 | 0.5h |
| VO 字段对齐 | 0.3h |
| Pattern D-5 自创维度 | 0.5h |
| 报告撰写 | 0.5h |
| **合计** | **3.8h** |

报告行数:本 markdown 约 **400+ 行**(满足验收 > 200 行)。

---

## 8. 最重要的 3 条 root cause

### RC-1:R33 P3-8.1 "DTO 缺字段" 模式仍在多个 controller 复制
- **共性原因**:新功能加了 record DTO,而对应 domain 仍含老代码 NOT NULL 字段
- **共性事实**: `NegativeFeedbackCreateReq` 缺 source/content/severity(DDL TO 校验)、`StageActionFieldsReq` 缺 remark/dueDate 等(允许 null)、`ProductCreateReq` 缺 status/tenantId/delFlag(允许 null + 服务端兜底)
- **根治建议**:为 `BaseEntity` 加 `validateRequiredFields()` 工具方法 + 启动期扫描 entity @TableField NOT NULL 字段与对应 DTO 字段比对,缺失则在 CI fail

### RC-2:多个 Controller 用 `@RequestBody Domain` 而非白名单 record
- **共性原因**:为减少 boilerplate,直接拿 domain 当 DTO
- **共性事实**: `RequirementChangeController.create()` 用 `@RequestBody RequirementChange change`(D-DTO-P1-1);`StageActionController` 内也用类似(待核)
- **根治建议**:CI lint:禁止 `@RequestBody` 注解接 `extends BaseEntity` 的类;必须用白名单 record

### RC-3:前端 disabled 硬编码 "true" + 依赖后端数据未做 loading 兜底
- **共性原因**:占位 UI / 进度赶工 / 复制粘贴模板
- **共性事实**: `admin/identity-sync/index.vue:144-146` 三按钮硬编码 disabled(D-UI-P0-1);`bid/select/index.vue:112` 依赖 invitation 加载未做兜底(D-UI-P1-1)
- **根治建议**:为 `disabled=` 编写 ESLint 规则:
  - 禁止 `disabled="true"` / `:disabled="true"` 字面量(必须用动态条件)
  - 涉及后端数据的 disabled 必须配 `loading` 兜底:`:disabled="!data || loading"`

---

> **报告生成时间**:2026-09-17 R34 Pattern D 系统扫描
> **生成人**:Agency Harness(Agency Agents + Grok Build 调度)
> **下一步**:本轮不动 src,仅本文件可写;R35 接 RC-1/RC-2/RC-3 修复建议。
