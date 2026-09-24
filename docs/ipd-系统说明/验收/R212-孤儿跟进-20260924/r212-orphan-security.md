# R212 IPD 后端 96 个前端无调用孤儿端点 —— 安全暴露面评估（纯静态只读审计）

**审计方式**：静态源码审计。未修改任何代码，未对 16039 端口发出任何 HTTP 请求，未执行任何写操作。
**证据基准**：磁盘现态（`ruoyi-modules/ruoyi-ipd/.../controller/`），解析结果经窗口法独立交叉验证（96/96 一致，0 处偏差）。

## 0. 全局拦截器覆盖结论（判定基线）

| 机制 | 证据 | 语义 |
|---|---|---|
| IPD 全局登录兜底 | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/config/IpdWebSecurityConfig.java:42-54` | `addPathPatterns("/api/v1/**")`，preHandle 调 `session.currentPerson()`，`NotLoginException` → 401 |
| 注解鉴权拦截器 | 同文件 `:57-60` | `new SaInterceptor().isAnnotation(true)` 同样 `addPathPatterns("/api/v1/**")`，`@SaCheckPermission(type="ipd")` 在此生效 |
| 豁免路径（匿名可达） | 同文件 `:53` 与 `:59` | `/api/v1/auth/login`、`/api/v1/auth/wecom/qr-login`、`/api/v1/public/**`、`/api/v1/resource/**` |
| 基线 SecurityConfig 不管 /api/v1 | `ruoyi-admin/src/main/resources/application.yml:289`（`- /api/v1/**` 在 `security.excludes`）+ `ruoyi-common/ruoyi-common-security/.../SecurityConfig.java` | 基线 sys_user 会话对 /api/v1 放行，IPD 自管 |
| 登录态语义 | `security/IpdAuthSession.java:39-47` | StpLogic("ipd") JWT + credentialMarker，改密即旧 token 失效 |

**结论**：96 条孤儿路径全部以 `/api/v1/` 开头（已逐条核对），因此除命中上述 4 条豁免模式者外，**均至少受全局登录兜底保护**；真正匿名可达的孤儿仅 4 条（3 条 `/api/v1/public/**` + 1 条 `/api/v1/auth/wecom/qr-login`）。

## 1. 三档危害分级统计

| 风险档 | 数量 |
|---|---|
| 高 | 7 |
| 中 | 14 |
| 低 | 75 |
| 合计 | 96 |

## 2. "高"档完整清单

| 方法+路径 | 现有注解 | 类级兜底 | 全局拦截 | 风险档 | 一句话理由 | 建议 |
|---|---|---|---|---|---|---|
| POST `/api/v1/gates/{gateId}/observers/invite` `GateReviewController.java:131` | （无） | （无） | 覆盖(需登录) | 高 | 控制器仅 requireInternal，服务层 GateReviewService.inviteObservers 做了角色复检（超管/组长）但无 gate 归属（组）校验 → 任一组长可向他组 gate 邀请列席人并触发通知（跨组写+骚扰） | 在 inviteObservers 内补 IpdIdorGuard.assertSameGroupIpd(actor, gate 所属组)；控制器补 @SaCheckPermission |
| POST `/api/v1/auth/wecom/qr-login` `IpdAuthController.java:81` | （无） | （无） | 豁免(匿名可达) | 高 | 匿名端点（在全局拦截器 exclude 列表内）仅凭请求体 wecomUserId 查库即签发 FULL scope JWT，无口令/无 code 换验/无 @RateLimiter（对照 /login 有 5次/60s），任何网络可达者可冒充任意已知企微 ID 的员工 → 认证绕过+账号接管 | 立即下线该 Mock 端点（或加 profile 开关默认关闭）；若必须保留，改为真实企微 OAuth2 code→userinfo 换取，并补 @RateLimiter(limitType=IP) + 绑定二次校验 |
| POST `/api/v1/projects/{id}/cert-items/sync` `ProjectController.java:252` | @SaCheckPermission(value = IpdPermissionCode.OPERATION_MODULE_PROJECT_STATUS_CHANGE, type = IpdAuthSession.LOGIN_TYPE) | （无） | 覆盖(需登录) | 高 | 虽有 @SaCheckPermission(STATUS_CHANGE) 角色级码，但服务层 ProjectCertServiceImpl.syncFromProject 仅收 operatorId 作审计，无组归属校验，控制器直接 projectService.getById(id) 取任意项目 → 跨组写入认证清单 | 补 IpdIdorGuard.assertSameGroupIpd/requireProjectMemberOrSuperAdmin；同步收紧 STATUS_CHANGE 码的授予范围 |
| POST `/api/v1/projects/{projectId}/members` `ProjectMemberController.java:49` | （无） | （无） | 覆盖(需登录) | 高 | 控制器仅 permission.requireProjectCreator()（角色门 MARKET_PM/GROUP_LEADER/SUPER_ADMIN，不校验该 projectId 归属），服务层 ProjectMemberServiceImpl.bindMember 全程无组级/创建者比对 → 任意内部 PM 可跨组向他人项目绑定成员并触发评级快照与津贴基数锁定（财务影响） | 补 @SaCheckPermission(项目成员写权限码) 并在 bindMember 前调 IpdIdorGuard.assertSameGroupIpd(actor, project.getMainGroupId()) 或 requireProjectMemberOrSuperAdmin |
| POST `/api/v1/public/demands` `PublicPortalController.java:38` | （无） | （无） | 豁免(匿名可达) | 高 | 匿名可写端点（位于 exclude 的 /api/v1/public/**）。属 P4-1.1 设计公开，且已核实补偿控制：honeypot 拒绝 + 同 IP 10 次/小时限流 + validate()（GuestDemandService.java:100-108）；风险限于垃圾内容注入与配额挤占 | 保留但加固：补 CAPTCHA/签名、字段长度与内容审查、spam 审计告警；确认限流器在生产为分布式实现而非 InMemoryHourRateLimiter |
| POST `/api/v1/stage-actions/ensure-bio-compliance` `StageActionController.java:100` | @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION_DELIVERABLE, type = IpdAuthSession.LOGIN_TYPE) | （无） | 覆盖(需登录) | 高 | 同上：服务层 ensureBioComplianceMount(Long projectId) 无 actor 形参（StageActionService.java:278），控制器仅 requireInternal → 跨组挂载 C12 合规动作 | 补 @SaCheckPermission + 组级校验；若为内部运维触发，应改由定时任务/管理端口触发而非开放 HTTP |
| POST `/api/v1/stage-actions/instantiate` `StageActionController.java:87` | @SaCheckPermission(value = IpdPermissionCode.OPERATION_STAGE_ACTION_DELIVERABLE, type = IpdAuthSession.LOGIN_TYPE) | （无） | 覆盖(需登录) | 高 | 控制器仅 ipdPermission.requireInternal()（宽角色门），服务层 StageActionService.instantiate 无 actor 入参、仅 assertProjectWritable 做状态门禁（暂停/归档），无归属校验 → 任意内部 PM 可向任意项目批量物化阶段动作 | 补细粒度 @SaCheckPermission + 服务方法增加 actor 参数并做 assertSameGroupIpd；无前端调用则优先下线 |

## 3. 完整评估表（96 条）

| 方法+路径 | 现有注解 | 类级兜底 | 全局拦截 | 风险档 | 一句话理由 | 建议 |
|---|---|---|---|---|---|---|
| POST `/api/v1/gates/{gateId}/observers/invite` `GateReviewController.java:131` | （无） | （无） | 覆盖(需登录) | 高 | 控制器仅 requireInternal，服务层 GateReviewService.inviteObservers 做了角色复检（超管/组长）但无 gate 归属（组）校验 → 任一组长可向他组 gate 邀请列席人并触发通知（跨组写+骚扰） | 在 inviteObservers 内补 IpdIdorGuard.assertSameGroupIpd(actor, gate 所属组)；控制器补 @SaCheckPermission |
| POST `/api/v1/auth/wecom/qr-login` `IpdAuthController.java:81` | （无） | （无） | 豁免(匿名可达) | 高 | 匿名端点（在全局拦截器 exclude 列表内）仅凭请求体 wecomUserId 查库即签发 FULL scope JWT，无口令/无 code 换验/无 @RateLimiter（对照 /login 有 5次/60s），任何网络可达者可冒充任意已知企微 ID 的员工 → 认证绕过+账号接管 | 立即下线该 Mock 端点（或加 profile 开关默认关闭）；若必须保留，改为真实企微 OAuth2 code→userinfo 换取，并补 @RateLimiter(limitType=IP) + 绑定二次校验 |
| POST `/api/v1/projects/{id}/cert-items/sync` `ProjectController.java:252` | @SaCheckPermission(OPERATION_MODULE_PROJECT_STATUS_CHANGE) | （无） | 覆盖(需登录) | 高 | 虽有 @SaCheckPermission(STATUS_CHANGE) 角色级码，但服务层 ProjectCertServiceImpl.syncFromProject 仅收 operatorId 作审计，无组归属校验，控制器直接 projectService.getById(id) 取任意项目 → 跨组写入认证清单 | 补 IpdIdorGuard.assertSameGroupIpd/requireProjectMemberOrSuperAdmin；同步收紧 STATUS_CHANGE 码的授予范围 |
| POST `/api/v1/projects/{projectId}/members` `ProjectMemberController.java:49` | （无） | （无） | 覆盖(需登录) | 高 | 控制器仅 permission.requireProjectCreator()（角色门 MARKET_PM/GROUP_LEADER/SUPER_ADMIN，不校验该 projectId 归属），服务层 ProjectMemberServiceImpl.bindMember 全程无组级/创建者比对 → 任意内部 PM 可跨组向他人项目绑定成员并触发评级快照与津贴基数锁定（财务影响） | 补 @SaCheckPermission(项目成员写权限码) 并在 bindMember 前调 IpdIdorGuard.assertSameGroupIpd(actor, project.getMainGroupId()) 或 requireProjectMemberOrSuperAdmin |
| POST `/api/v1/public/demands` `PublicPortalController.java:38` | （无） | （无） | 豁免(匿名可达) | 高 | 匿名可写端点（位于 exclude 的 /api/v1/public/**）。属 P4-1.1 设计公开，且已核实补偿控制：honeypot 拒绝 + 同 IP 10 次/小时限流 + validate()（GuestDemandService.java:100-108）；风险限于垃圾内容注入与配额挤占 | 保留但加固：补 CAPTCHA/签名、字段长度与内容审查、spam 审计告警；确认限流器在生产为分布式实现而非 InMemoryHourRateLimiter |
| POST `/api/v1/stage-actions/ensure-bio-compliance` `StageActionController.java:100` | @SaCheckPermission(OPERATION_STAGE_ACTION_DELIVERABLE) | （无） | 覆盖(需登录) | 高 | 同上：服务层 ensureBioComplianceMount(Long projectId) 无 actor 形参（StageActionService.java:278），控制器仅 requireInternal → 跨组挂载 C12 合规动作 | 补 @SaCheckPermission + 组级校验；若为内部运维触发，应改由定时任务/管理端口触发而非开放 HTTP |
| POST `/api/v1/stage-actions/instantiate` `StageActionController.java:87` | @SaCheckPermission(OPERATION_STAGE_ACTION_DELIVERABLE) | （无） | 覆盖(需登录) | 高 | 控制器仅 ipdPermission.requireInternal()（宽角色门），服务层 StageActionService.instantiate 无 actor 入参、仅 assertProjectWritable 做状态门禁（暂停/归档），无归属校验 → 任意内部 PM 可向任意项目批量物化阶段动作 | 补细粒度 @SaCheckPermission + 服务方法增加 actor 参数并做 assertSameGroupIpd；无前端调用则优先下线 |
| GET `/api/v1/gates/{gateId}/legacy` `GateElementResultController.java:90` | （无） | （无） | 覆盖(需登录) | 中 | 仅 requireInternal，无注解；服务层归属未逐一验证 | 补 @SaCheckPermission（当前仅宽角色门） |
| POST `/api/v1/gates/{gateId}/submit` `GateElementResultController.java:127` | （无） | （无） | 覆盖(需登录) | 中 | 仅 requireInternal，无注解；提交评审为高影响写，归属校验依赖服务层 | 补 @SaCheckPermission（当前仅宽角色门） |
| GET `/api/v1/gates/{gateId}/observers` `GateReviewController.java:153` | （无） | （无） | 覆盖(需登录) | 中 | 仅 requireInternal；服务层 listObservers 做组长/超管角色复检但无组归属（GateReviewService.java:748）→ 跨组读列席名单与意见 | 补 @SaCheckPermission（当前仅宽角色门） |
| POST `/api/v1/gates/{gateId}/observers/{observerId}/opinion` `GateReviewController.java:143` | （无） | （无） | 覆盖(需登录) | 中 | 无注解；服务层已核实仅本人（GateReviewService.java:720-723） | 补 @SaCheckPermission（当前仅宽角色门） |
| GET `/api/v1/handovers/monthly-attribution` `HandoverController.java:191` | （无） | （无） | 覆盖(需登录) | 中 | 仅 requireInternal，无注解；返回全员归属人日数据（绩效相关） | 补 @SaCheckPermission（当前仅宽角色门） |
| POST `/api/v1/handovers/{id}/archive` `HandoverController.java:165` | （无） | （无） | 覆盖(需登录) | 中 | 无注解；服务层已核实 requireAuthenticated + assertArchivePermission(rec,actor)（HandoverService.java:877-892）→ 有等价对象级校验 | 补 @SaCheckPermission（当前仅宽角色门） |
| GET `/api/v1/auth/me` `IpdAuthController.java:89` | （无） | （无） | 覆盖(需登录) | 中 | 仅宽角色门（session.currentPerson）可读数据，无权限码约束 | 补 @SaCheckPermission（不强制） |
| POST `/api/v1/auth/change-password` `IpdAuthController.java:138` | （无） | （无） | 覆盖(需登录) | 中 | 无注解；服务层已核实仅本人+超管豁免（IpdAuthService.java:194） | 补 @SaCheckPermission（当前仅宽角色门） |
| POST `/api/v1/auth/logout` `IpdAuthController.java:100` | （无） | （无） | 覆盖(需登录) | 中 | 无注解且控制器无守卫（全局拦截器仍要求登录） | 补 @SaCheckLogin 显式声明 + 按业务补权限码 |
| GET `/api/v1/system/menu/getRouters` `MenuController.java:60` | @SaCheckLogin(type = IpdAuthSession.LOGIN_TYPE) | （无） | 覆盖(需登录) | 中 | 仅 @SaCheckLogin，任何登录用户可调用 | 评估是否升级为 @SaCheckPermission |
| POST `/api/v1/persons/{id}/resign` `PersonController.java:71` | （无） | （无） | 覆盖(需登录) | 中 | 无注解；控制器内已核实 self-or-HR 判定（PersonController.java:75-78） | 补 @SaCheckPermission（当前仅宽角色门） |
| GET `/api/v1/projects/{id}/cert-items` `ProjectController.java:239` | @SaCheckPermission(OPERATION_MODULE_PROJECT_QUERY) | （无） | 覆盖(需登录) | 中 | 控制器有 @SaCheckPermission(PROJECT_QUERY)+requireInternal，但服务层 listView(projectId) 不接收 actor（ProjectCertServiceImpl.java:140）→ 跨组可读认证清单 | 补服务层归属校验（当前仅角色级权限码） |
| GET `/api/v1/public/demands/{code}` `PublicPortalController.java:50` | （无） | （无） | 豁免(匿名可达) | 中 | 无注解且控制器无守卫（全局拦截器仍要求登录） | 补 @SaCheckLogin 显式声明 + 按业务补权限码 |
| GET `/api/v1/public/products` `PublicPortalController.java:44` | （无） | （无） | 豁免(匿名可达) | 中 | 无注解且控制器无守卫（全局拦截器仍要求登录） | 补 @SaCheckLogin 显式声明 + 按业务补权限码 |
| GET `/api/v1/admin/permanent-delete/audit` `AdminPermanentDeleteController.java:76` | @SaCheckPermission(OPERATION_PERMANENT_DELETE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/admin/permanent-delete/{entityType}/{id}` `AdminPermanentDeleteController.java:57` | @SaCheckPermission(OPERATION_PERMANENT_DELETE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/ai-copilot/chat` `AiCopilotController.java:65` | @SaCheckPermission(OPERATION_AI_COPILOT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/ai-documents/{id}/history` `AiDocumentController.java:120` | @SaCheckPermission(OPERATION_AI_DOCUMENT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/audit-logs` `AuditLogController.java:62` | @SaCheckPermission(OPERATION_AUDIT_LOG_LIST) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/audit-logs/export` `AuditLogController.java:97` | @SaCheckPermission(OPERATION_AUDIT_LOG_EXPORT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/bid-responses/by-rd-pm/{rdPmId}` `BidController.java:170` | @SaCheckPermission(OPERATION_MODULE_PROJECT_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| PUT `/api/v1/bid-invitations/{id}/admin-assign` `BidController.java:140` | @SaCheckPermission(OPERATION_BID_INVITATION_ADMIN_ASSIGN) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| PUT `/api/v1/bid-invitations/{id}/modify` `BidController.java:125` | @SaCheckPermission(OPERATION_MODULE_PROJECT_STATUS_CHANGE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/bid-invitations/p231-create` `BidP231Controller.java:42` | @SaCheckPermission(OPERATION_BID_INVITATION_CREATE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/bonus-pool/page` `BonusPoolController.java:178` | @SaCheckPermission(OPERATION_BONUS_POOL_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/bonus-pool/auto-compute` `BonusPoolController.java:94` | @SaCheckPermission(OPERATION_BONUS_POOL_COMPUTE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/bonus-pool/coefficient/preview` `BonusPoolController.java:196` | @SaCheckPermission(OPERATION_BONUS_POOL_COMPUTE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/compliance/audit-trail/{resourceType}/{resourceId}` `ComplianceController.java:65` | @SaCheckPermission(OPERATION_COMPLIANCE_READ) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/compliance/data-retention-rules` `ComplianceController.java:50` | @SaCheckPermission(OPERATION_COMPLIANCE_READ) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/compliance/permission-separation/{userId}` `ComplianceController.java:77` | @SaCheckPermission(OPERATION_COMPLIANCE_READ) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/compliance/data-deletion-request` `ComplianceController.java:58` | @SaCheckPermission(OPERATION_COMPLIANCE_WRITE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/gates/sign/scan-remind` `GateSignScanController.java:38` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/gates/sign/scan-timeout` `GateSignScanController.java:32` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/handovers/scan-overdue` `HandoverController.java:156` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| GET `/api/v1/hr-sync/last-run` `HrSyncController.java:194` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| GET `/api/v1/hr-sync/pending-handovers` `HrSyncController.java:131` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireLeaderOrAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/hr-sync/escalate-stale-resignations` `HrSyncController.java:120` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/hr-sync/mark-resigned` `HrSyncController.java:108` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/hr-sync/sync-now` `HrSyncController.java:151` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/hr-sync/sync-one` `HrSyncController.java:167` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| DELETE `/api/v1/kpi/functional-metrics/{id}` `KpiFunctionalMetricsController.java:102` | @SaCheckPermission(OPERATION_KPI_CONFIG) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/kpi/functional-metrics` `KpiFunctionalMetricsController.java:59` | @SaCheckPermission(OPERATION_KPI_CONFIG_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/kpi/functional-metrics/codes` `KpiFunctionalMetricsController.java:72` | @SaCheckPermission(OPERATION_KPI_CONFIG_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/kpi/raw-records/types` `KpiRawRecordController.java:82` | @SaCheckPermission(OPERATION_KPI_RAW_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/kpi/rules` `KpiRulesController.java:41` | @SaCheckPermission(OPERATION_KPI_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/gates/legacy/scan-overdue` `LegacyScanController.java:27` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| GET `/api/v1/negative-feedbacks/by-project/{projectId}` `NegativeFeedbackController.java:104` | @SaCheckPermission(OPERATION_NEGATIVE_FEEDBACK_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/negative-feedbacks/by-severity/{severity}` `NegativeFeedbackController.java:112` | @SaCheckPermission(OPERATION_NEGATIVE_FEEDBACK_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| PUT `/api/v1/negative-feedbacks/{id}/status` `NegativeFeedbackController.java:120` | @SaCheckPermission(OPERATION_NEGATIVE_FEEDBACK_DECIDE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/notifications/async-dispatch` `NotificationController.java:103` | @SaCheckPermission(OPERATION_NOTIFICATION_DISPATCH) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/notifications/dispatch-pending` `NotificationController.java:89` | @SaCheckPermission(OPERATION_NOTIFICATION_DISPATCH) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/p0/escalation-chain` `P0EscalationController.java:48` | @SaCheckPermission(OPERATION_P0_ESCALATION_READ) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/p0/escalation-chain/check` `P0EscalationController.java:58` | @SaCheckPermission(OPERATION_P0_ESCALATION_READ) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/p0/escalation-chain/{id}/resolve` `P0EscalationController.java:69` | @SaCheckPermission(OPERATION_P0_ESCALATION_READ) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/persons/{id}/rehire` `PersonController.java:88` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireLeaderOrAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/persons/{id}/wecom/unbind` `PersonController.java:101` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireLeaderOrAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| GET `/api/v1/person-sync/jobs/abnormal` `PersonSyncController.java:88` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/person-sync/jobs` `PersonSyncController.java:51` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireLeaderOrAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/person-sync/jobs/retry-all` `PersonSyncController.java:69` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| POST `/api/v1/person-sync/jobs/{id}/retry` `PersonSyncController.java:60` | （无） | （无） | 覆盖(需登录) | 低 | 无注解但有等价编程式强角色门（permission.requireLeaderOrAdmin） | 建议补声明式 @SaCheckPermission 以利审计（非强制） |
| GET `/api/v1/post-launch-reviews/pending` `PostLaunchReviewController.java:83` | @SaCheckPermission(OPERATION_POST_LAUNCH_REVIEW_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/post-launch-reviews` `PostLaunchReviewController.java:93` | @SaCheckPermission(OPERATION_POST_LAUNCH_REVIEW_CREATE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/post-launch-reviews/{id}/complete` `PostLaunchReviewController.java:105` | @SaCheckPermission(OPERATION_POST_LAUNCH_REVIEW_COMPLETE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/products/{id}/unbind-project` `ProductController.java:100` | @SaCheckPermission(OPERATION_PRODUCT_GROUP_BIND_PROJECT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/product-groups/{id}/remove` `ProductGroupController.java:74` | @SaCheckPermission(OPERATION_PRODUCT_GROUP_BIND_PROJECT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/projects/legacy-import/batch` `ProjectController.java:178` | @SaCheckPermission(OPERATION_MODULE_PROJECT_CREATE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/projects/{id}/baselines` `ProjectController.java:137` | @SaCheckPermission(OPERATION_MODULE_PROJECT_STATUS_CHANGE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/projects/{id}/cert-items/{itemId}/status` `ProjectController.java:283` | @SaCheckPermission(OPERATION_MODULE_PROJECT_STATUS_CHANGE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/projects/{id}/launch-date` `ProjectController.java:190` | @SaCheckPermission(OPERATION_MODULE_PROJECT_STATUS_CHANGE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/project-score-tasks/scan` `ProjectScoreTaskController.java:27` | @SaCheckPermission(OPERATION_KPI_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/receipt-ledgers/by-project/{projectId}` `ReceiptLedgerController.java:76` | @SaCheckPermission(OPERATION_BONUS_POOL_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/receipt-ledgers` `ReceiptLedgerController.java:46` | @SaCheckPermission(OPERATION_BONUS_POOL_COMPUTE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/receipt-ledgers/{projectId}/refunds` `ReceiptLedgerController.java:68` | @SaCheckPermission(OPERATION_BONUS_POOL_COMPUTE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/requirement-changes/open` `RequirementChangeController.java:85` | @SaCheckPermission(OPERATION_MODULE_PROJECT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/kpi/shared/confirms` `SharedKpiController.java:131` | @SaCheckPermission(OPERATION_KPI_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/kpi/shared/deadline-config` `SharedKpiController.java:116` | @SaCheckPermission(OPERATION_KPI_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/kpi/shared/deadline-scan` `SharedKpiController.java:74` | @SaCheckPermission(OPERATION_KPI_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/kpi/shared/{id}/confirm` `SharedKpiController.java:153` | @SaCheckPermission(OPERATION_KPI_SHARED_CONFIRM_SIGN) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/sop-templates/instances` `SopTemplateController.java:106` | @SaCheckPermission(OPERATION_SOP_TEMPLATE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/sop-templates/{templateId}/instantiate` `SopTemplateController.java:97` | @SaCheckPermission(OPERATION_SOP_TEMPLATE) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/switching-acceptance` `SwitchingAcceptanceController.java:70` | @SaCheckPermission(OPERATION_SWITCHING_ACCEPTANCE_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/switching-acceptance/{month}` `SwitchingAcceptanceController.java:48` | @SaCheckPermission(OPERATION_SWITCHING_ACCEPTANCE_QUERY) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/switching-acceptance/{month}/lock` `SwitchingAcceptanceController.java:55` | @SaCheckPermission(OPERATION_SWITCHING_ACCEPTANCE_LOCK) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/switching-acceptance/{month}/run` `SwitchingAcceptanceController.java:42` | @SaCheckPermission(OPERATION_SWITCHING_ACCEPTANCE_LOCK) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| POST `/api/v1/switching-acceptance/{month}/unlock` `SwitchingAcceptanceController.java:63` | @SaCheckPermission(OPERATION_SWITCHING_ACCEPTANCE_UNLOCK) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/system-configs/{key:.+}` `SystemConfigController.java:68` | @SaCheckPermission(OPERATION_SYSTEM_CONFIG_READ) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/system-configs/{key:.+}/as-of` `SystemConfigController.java:155` | @SaCheckPermission(OPERATION_SYSTEM_CONFIG_LIST) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/workbench/my-initiated` `WorkbenchController.java:51` | @SaCheckPermission(OPERATION_MODULE_PROJECT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |
| GET `/api/v1/workbench/my-pending-approvals` `WorkbenchController.java:63` | @SaCheckPermission(OPERATION_MODULE_PROJECT) | （无） | 覆盖(需登录) | 低 | 有细粒度 @SaCheckPermission（type=ipd），注解鉴权拦截器强制 | 维持 |

## 4. 清单声明列 vs 磁盘现态 差异核算（交付项 3）

| 清单"权限注解"声明 | 条数 | 磁盘现态 | 判定 |
|---|---|---|---|
| `const-ref` | 43 | 43/43 确有 `@SaCheckPermission(value = IpdPermissionCode.X, type = IpdAuthSession.LOGIN_TYPE)` | **一致，0 差异** |
| `@SaCheckLogin` | 1 | 确有 `@SaCheckLogin`（MenuController `getRouters`） | **一致，0 差异** |
| `(类级/兜底)` | 52 | 20 条实际有**方法级** `@SaCheckPermission`；32 条**无任何 @Sa 注解** | **标签名实不符（见下）** |

- **权限注解差异条数 = 0**：未发现兄弟会话修改鉴权注解的迹象，清单与磁盘在"有无权限码"层面完全一致。
- **但 `(类级/兜底)` 这一标签在全仓层面是错的**：39 个孤儿 Controller 的**类级 @Sa 注解总数 = 0**（窗口法独立核查，唯一命中为 `PostLaunchReviewController.java:31` 的 javadoc 文本，非注解）。真正的"兜底"不是类级注解，而是 `IpdWebSecurityConfig` 的**全局登录拦截器**。
- 该标签的误导性在于：52 条被标"有兜底"中，有 32 条实际上**只有登录兜底、没有任何权限码**，其授权强度取决于方法体内 `permission.requireXxx()` 的门禁层级。

## 5. 逐问结论

### Q1 无鉴权孤儿（最高风险项）
**0 条**属于"既无方法级注解、又无类级注解、且不在全局拦截器覆盖内"的意外裸奔。96 条路径全部以 `/api/v1/` 开头，均落入 `IpdWebSecurityConfig.java:52` 的 `addPathPatterns("/api/v1/**")`；唯一匿名可达的是命中 `:53/:59` 豁免列表的 4 条（`/api/v1/public/**` ×3 + `/api/v1/auth/wecom/qr-login` ×1），其中 3 条为 P4-1.1 设计公开、**1 条（qr-login）为高危设计缺陷**。

写操作且参数含 id 的关注项（详见第 2 节"高"档）：`projects/{projectId}/members`、`projects/{id}/cert-items/sync`、`stage-actions/instantiate`（批量物化）、`gates/{gateId}/observers/invite`、`public/demands`（匿名写）。

### Q2 越权面（仅登录/仅宽角色门，无权限码）
- 严格"`@SaCheckLogin` 有但 `@SaCheckPermission` 无"：**仅 1 条**（`GET /api/v1/system/menu/getRouters`，`MenuController.java`）。
- 实际越权面应按"无权限码 + 仅宽角色门"口径统计：**32 条**无 `@Sa` 注解（其中 27 条有 `permission.requireXxx()` 编程式门禁、5 条完全依赖全局拦截器）。
- 涉及跨组/跨租户数据的（IPD 为单企业部署，26+2 张表在 `application.yml:311-313` 显式**排除租户过滤**，故横向隔离完全依赖 `groupId` 归属校验）：已核实**缺失归属校验**的写端点 = `projects/{projectId}/members`、`stage-actions/instantiate`、`stage-actions/ensure-bio-compliance`、`gates/{gateId}/observers/invite`、`projects/{id}/cert-items/sync`；已核实**存在等价校验**因而可豁免的 = `handovers/{id}/archive`、`persons/{id}/resign`、`gates/{gateId}/observers/{observerId}/opinion`、`auth/change-password`、`sop-templates/{templateId}/instantiate`。

### Q3 信息泄露（GET 类）
Person 实体确实含 `passwordHash`（`domain/Person.java:47`）、`wecomUserId`、`employeeNo`、`level`、`accountStatus` 等高敏字段，但**未发现任何孤儿 GET 直接序列化 Person 实体**：`/api/v1/auth/me` 走 `MeView(PersonView.from(person))`，`persons/{id}/rehire|wecom-unbind` 返回 `PersonView`（白名单投影），故 hash 未泄露。

按剩余敏感度排序的 Top 风险（均为"登录即可读、无权限码或仅角色码"）：
1. `GET /api/v1/handovers/monthly-attribution` — 全员归属人日/角色（绩效相关），仅 `requireInternal`。
2. `GET /api/v1/gates/{gateId}/observers` — 跨组可读评审列席名单与列席意见，服务层仅角色复检无组归属（`GateReviewService.java:748`）。
3. `GET /api/v1/projects/{id}/cert-items` — 跨组可读认证清单，服务层 `listView(projectId)` 不接收 actor（`ProjectCertServiceImpl.java:140`）。
4. `GET /api/v1/compliance/permission-separation/{userId}` 与 `audit-trail/{resourceType}/{resourceId}` — 有 `OPERATION_COMPLIANCE_READ` 权限码，但按 userId/资源 ID 直查，属横向可读面。
5. `GET /api/v1/kpi/shared/confirms`、`GET /api/v1/bonus-pool/page`、`GET /api/v1/receipt-ledgers/by-project/{projectId}` — 奖金/回款/绩效金额，均有权限码约束（相对可控）。

### Q4 危害分级汇总
高 **7** ／ 中 **14** ／ 低 **75**（合计 96）。

### Q5 修复建议
"高"档 7 条已逐条给最小修复（见第 2 节建议列）；核心两类动作：
1. **立即**：下线或改造 `POST /api/v1/auth/wecom/qr-login`（唯一认证绕过级）。
2. **本迭代**：为 5 个跨组写端点补 `@SaCheckPermission` + 服务层 `IpdIdorGuard.assertSameGroupIpd(...)`。
中档建议（不强制）：32 条无权限码端点补声明式注解以利审计与统一治理，并把已散落在服务层的等价校验收敛为注解 + 切面。

## 6. 审计方法与局限
- 方法：以脚本从 `/tmp/r212_l1_table.md` 抽取 96 行 → 定位 Controller 文件 → 解析映射注解区（含注解在 `@PostMapping` **上方与下方**两种书写顺序）→ 与独立窗口法扫描交叉验证（96/96 一致，0 偏差）。
- 局限（需下一轮补验）：服务层组级归属校验仅对第 5 节列出的 12 个端点做了**逐行阅读确认**；其余写端点标注为"服务层归属校验未逐一验证"，未在无证据时下结论。
- 已排除的干扰：`.claude/worktrees/`、`.codex/`、`.harness/.backup/` 下的历史副本未纳入（仅审计主工作树）。
