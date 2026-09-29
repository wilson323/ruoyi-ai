# DOC-06 49页API、DTO、状态与错误合同

> 2026-09-05定点更新：五个auth端点以固定源码 `.codex/ipd-integration/20260905-0500-integration` 与运行包 `32720f79f944b06fb78468e56e1e29d1c249597b949da42fc9834d7e33b76ce0` 为证据；其他端点保留原HEAD `71c240950b44db63d58d2f5b7f4108605c66e133` 的历史盘点，未全量重新验收。当前共享源码归并仍待确认，不能假定与固定副本全量一致。本卡只修改文档/机器表，不修改Java或SQL。正式前端为用户选择的独立RuoYi Vue工程 /Users/mac/Documents/ruoyi-ipd-web。

完整机器表：[DOC-06.routes.json](DOC-06.routes.json)。routes逐项给出规范方法/路径、别名、原页/行、当前Java方法、请求白名单、响应字段、身份、状态、错误和后续卡；pages保留49页与字段原行；sourceOccurrences/sourceTokens保存每处API来源。字段原行是历史证据，不是可直接批量赋值的DTO。

## 1. 权威与实现状态

[用户决定](业务决策确认-20260905.md)及[DOC-01](DOC-01.md)、[DOC-02](DOC-02.md)、[DOC-03](DOC-03.md)、[DOC-04](DOC-04.md)、[DOC-05](DOC-05.md)控制当前语义。禁止复活五级串签、首签者主导、双超时通过、需求撤回24h、冻结补发、共担重复乘40%、跨项目评分平均和G2-6硬卡。

[主规格TS-09～13](../外部资源/IPD系统_AI开发主Prompt_v3.md)、[页01～12](../../开发说明/spec/batch-01-pages-01-12.md)、[页13～24](../../开发说明/spec/batch-02-pages-13-24.md)、[页25～37](../../开发说明/spec/batch-03-pages-25-37.md)、[页38～49](../../开发说明/spec/batch-04-pages-38-49.md)提供页面意图、字段和约束。页内server.mjs/closure.mjs及“现有/已实现”描述的是旧React原型，不能证明当前Java后端已有接口。

工程约束见[二开规范](../二次开发规范.md)、[命名](../naming-convention.md)、[类型映射](../type-mapping.md)。implemented仅表示当前Controller存在完整方法/路径源码；planned表示尚无该Java映射、待实现；verified要求同版本真实HTTP+权限+DB业务证据，本次仍不将任一接口标为全量verified；五个auth端点的具体运行场景另有证据，httpVerified仅指所列已观测场景，不等于完整接口或产品验收。机器表exposure中的INTERNAL_ONLY、DEMO_ONLY、FORBIDDEN_BY_SOURCE分别为内部调度、隔离演示、原文明确禁止操作，保留来源不表示必须公开挂载。

## 2. 统一网络合同

| 项目 | 当前事实 | 目标合同 |
|---|---|---|
| JSON包络 | ApiV1Response={code,message,data,timestamp,traceId}，code=0；timestamp是Date | 普通JSON成功HTTP200/code0；空结果data=null；不与RuoYi的code200/msg混用。新建也沿用HTTP200，未声称已返回201 |
| 错误 | 固定运行包已用IPD限定Advice/认证拦截器返回五字段；显式登记code映射HTTP，未知或无码ServiceException为500/90001 | 失败data=null，固定安全消息；基线其他模块仍用R；业务来源与完整流程需各卡独立验收 |
| trace | 当前IPD响应traceId非空、匹配X-Trace-Id；认证前由服务端生成并写入脱敏日志，忽略客户端trace | 不将某次trace关联通过扩散为全链审计/所有异步处理已验收 |
| ID | Long全局按安全整数范围输出number/string | IPD DTO所有ID输出string；禁止Number(id)。外部UUID保留映射，不截断。查询码是独立随机凭证 |
| 小数 | BigDecimal输出string；旧页元/万元/%混写 | 十进制string；amountYuan/targetSalesAmount/bonusPool/calculatedAmount为元，impactCostWan为万元；marketPct/achievementRate为百分数，系数为倍数，FAR/FRR为比例。命名/单位转换集中在DTO适配 |
| 时间 | Date/LocalDateTime、主机默认时区混用，现格式不必带偏移 | 日期YYYY-MM-DD，月份YYYY-MM，季度YYYY-Qn；时刻RFC3339含+08:00或等价Z，业务Asia/Shanghai；DB精度/回读另由DOC-08验证，旧无时区响应不猜时区 |
| 布尔/结构 | isVeto/isMandatory等为0/1字符串，targetMarkets为JSON字符串 | DTO显式boolean、string[]、对象；每字段独立转换；前端不递归猜JSON文本 |
| 分页 | 当前项目/产品等直接List，无分页和total | 无界列表pageNum≥1/pageSize1～200，默认1/20；data={rows,total,pageNum,pageSize}。空rows=[]。有界配置选项可数组但端点明确，现List属于差距 |
| 文件 | 原页链接/JSON混合 | 授权下载/导出成功为Content-Type/Content-Disposition文件流，失败仍JSON包络。前端先检查HTTP和Content-Type，不将错误JSON下载成文件 |
| 未知 | 旧字段默认0/空串可能混淆 | null+dataStatus表示缺失，0表示有效零；不得为闭环补0或生成评分 |

写请求使用专用DTO及jakarta.validation，字段白名单见逐路由request.allowed，条件必填/长度/单位见sourceFieldPageRefs和DOC覆盖。id/operatorId/actorId/tenantId/delFlag/createBy/updateBy/signedAt/reviewedBy/初始终态/计算金额不接受批量赋值。GET中的actorId只是审计筛选；toPersonId/rdPmId是目标业务对象，仍校验归属。新对象来源、初态、编码由服务端推导。

身份来自有效SaToken会话→IPD Person映射，LoginHelper.getUserId()不直接等于persons.id。首登只允许改密/退出及最小me，冻结只移交与身份恢复，禁用或无合法状态拒绝；主组、协同组、本人、其他PM、游客分别验权。login/me/logout/change-password/refresh五个映射已存在；扫码等其他入口仍按逐接口planned状态判断。写已有对象带expectedVersion；Idempotency-Key绑定主体/操作/对象/规范化载荷，同键异载荷409。同一成功业务、审计、幂等回执原子提交；PATCH缺字段不改值，null仅可清空明确可空字段；拒绝不修改业务数据；认证安全审计可以独立持久化。

认证参考只读与管理权限分开：页18:442明确普通PM可只读查阅，GET `/api/v1/cert-templates`、`/country-counts`、`/resolve`及planned `/lookup`按有效内部FULL会话开放；写入、版本发布及`/admin/cert-templates`管理操作仍只给超管。GET `/api/v1/gate-elements`是页47的超管管理列表，PM评审要素读取走项目评审接口，不能混用。

### 2.1 字段和状态覆盖

| 原字段/旧值 | 目标与约束 |
|---|---|
| product.name/model_code/lifecycle_status | productName/modelCode及独立lifecycleStatus；现Product.status仅ACTIVE/INACTIVE启停，不能冒认为在售/退市，需要模型/迁移 |
| productType/strategicLevel/targetSales/targetChannels/targetScenarios | templateType/level/targetSalesAmount/targetChannelCount/targetSceneCount；四基准锁定变更留审批版本 |
| concept/plan/develop/verify/launch/lifecycle | CONCEPT/PLAN/DEV/VALID/LAUNCH/LIFECYCLE；动作code保留69目录 |
| workItemId/note/actual_done_at | stageActionId/remark/actualDoneAt；轻管三字段及例外数值；G2-6/P10计划放行不扩展到V02/G4实际结果 |
| gate.status=ABSTAINED_TIMEOUT、leadSide先签者 | 槽位弃权与评审TIMEOUT_UNRESOLVED、业务applied分开；主导MARKET_PM/RD_PM预配快照；G2/G3/G4不套双PM否决 |
| decisionChain五节点/currentApprover | attemptId/round/slots/deadlineVersion/canSign/blockingReasons；人员/材料/期限版本隔离，盲签服务器过滤 |
| targetType/targetId、leadDecision | entityType/entityId白名单；leaderDecision=APPROVE/REJECT/null；普通一级/重大二级；DELETED需目标实际软删 |
| demand/requirement混用、CANDIDATE/BASELINED | Demand产品需求池与Requirement项目需求独立；七态SUBMITTED/ACCEPTED/EVALUATING/SCHEDULED/PROCESSING/CLOSED/ARCHIVED；游客撤回WITHDRAWN，不收录CLOSED+原因 |
| 共担score0～100、综合封顶100 | weightedScore/maxScore为15/10/10/5；W0～40，项目F×0.6+W；percentScore单独展示。个人同期间跨项目求和可>100，每项目奖金系数独立 |
| 客户端金额/评分/责任、source=erp就可信 | 原始凭证与ruleVersion/calculationTrace可追溯，结果只读；净负回款待人工，最终HALF_UP到分、尾差另账 |
| userId/memberUserId/role | 框架accountId与业务personId分开，四角色来自真实映射；不增加代理组长或前端改专业角色 |

### 2.2 HTTP状态/业务码

| HTTP | code | 语义与当前差距 |
|---|---|---|
| 200 | 0 OK | 请求成功，仍查看明确业务status/applied，不能当流程已终结 |
| 400 | 10001 PARAM_INVALID | 缺字段/范围/格式/未知可写字段；旧10003等未登记码不沿用 |
| 401 | 20001 UNAUTHORIZED | 未登录或令牌失效；账号不存在/错误密码统一提示 |
| 403 | 20002 ACCOUNT_FROZEN_PENDING_HANDOVER | 冻结请求移交以外动作；不是旧页token过期 |
| 403 | 20003 ACCOUNT_PASSWORD_CHANGE_REQUIRED | 首登受限会话访问非改密相关资源；保留当前会话并引导改密，不能当401清除登录；2026-09-05已进入固定042045运行包 |
| 403 | 30001 FORBIDDEN | 合法身份无节点/资源权限，不泄漏名称 |
| 409 | 40001 GATE_NOT_PASSED / 40002 DUAL_SIGN_INCOMPLETE | 适用门禁/双签；参数错不复用40002，G3不造否决 |
| 409 | 40003 OVER_QUOTA_NOT_REGISTERED / 40004 ROLE_LOCKED | 超项未备案/专业角色互斥 |
| 409 | 40005 DELETE_NOT_ALLOWED_DIRECT / 40006 HANDOVER_REQUIRED_BEFORE_DISABLE | 禁止直接删除/未移交不得禁用；40005固定消息已按DOC-03改为数据分级审核 |
| 404 | 50001 NOT_FOUND | 不存在或不可见；历史盘点存在null成功缺口，固定运行包已观测产品缺失404/50001；其他详情按原盘点逐卡验收 |
| 409 | 50002 STATE_CONFLICT | 非法迁移、旧版本、过期签署、第4次延期、同键异载荷、绑定冲突、已锁定 |
| 429 | 40011 RATE_LIMITED（枚举及映射已登记） | 目标为限流429；当前login限流器仍抛无码异常而退500，不能称429已接通 |
| 413 | 40012 ATTACHMENT_TOO_LARGE（枚举及映射已登记） | 附件数量/大小超限，其他格式错400/10001 |
| 409 | 40013 AI_BUDGET_EXCEEDED（枚举及映射已登记） | AI预算超限的枚举及409映射已登记；预算业务流程未据此宣称完成 |
| 405 | 10001 PARAM_INVALID | 错误HTTP方法，message明确；API-01如需更细码统一登记 |
| 500 | 90001 INTERNAL_ERROR | 未预期错误，脱敏输出 |

旧页410已撤回统一409/50002且安全描述。40011/40012/40013已在固定源码ApiV1ErrorCode登记并由API-01验证映射；映射存在不等于限流、附件或预算业务已接通。业务码不是HTTP状态码。405保留Allow；IPD媒体协商406/415统一使用10001安全JSON。首登受限的受保护业务请求返回403/20003，冻结为403/20002；五个auth端点中的恢复/身份读取例外见下文。

## 3. 别名和资源语义

| 旧来源 | 唯一目标/处理 | 参数及语义限制 |
|---|---|---|
| /api/... | /api/v1/... | 只是目标前缀，不证明旧React已迁移；正式Vue代理保留/api/v1 |
| work-items的GET/PUT/PATCH/quality/attachments | stage-actions；PUT保存归并PATCH | workItemId→stageActionId；W001、P10-id是样例，不生成固定路由 |
| projects/:id/stage-advance | projects/{id}/advance-stage | 复用现Java路径，但门禁缺陷仍待修，不并列同义推进 |
| key-gates/:gateId/sign、gates/:id/reviews | POST gates/{id}/reviews | gateId关联真实Gate、attemptId在body；DOC-02盲签 |
| key-gates/:stageId/submit | POST projects/{projectId}/stages/{stageId}/gate-submissions | stageId不是gateId，需解析所属项目与Gate，禁止盲替换ID |
| key-gates/:id/element-results、elements/:code/judge | POST gates/{id}/elements/{code}/judge | 批量来源先映射每项或同事务适配；elementCode不可丢；要素和签署分开 |
| key-gates/:id/elements/all | GET gates/{id}/elements授权投影 | 实际仲裁权限单独审计；管理身份不解除正常盲签 |
| collaboration/decisions/:id/sign、changes/:id/decision | POST changes/{id}/reviews | 先由decisionId解析change/attempt，不能当changeId；取消旧串行捷径 |
| ai/run、TS12 ai/generate | POST ai/generate | projectId/stageActionId/promptType/sourceText/params；GET context-preview归并POST承载来源选择，预览不产生业务写入 |
| projects/:id/delete-request、requirements/:id/deletion-request | POST deletion-requests | entityType/entityId白名单，无字符串拼表名 |
| deletion-requests/:id/decision | POST deletion-requests/{id}/review | 初审终审由真实状态/节点决定，不由客户端审批角色决定 |
| admin/gate-elements的PUT/DELETE | gate-elements/{id}/update、/{id}/disable | 当前已有Java路径；草稿/发布/项目快照仍待实现；不转物理删 |
| cron/auto-approve-overdue-gates | internal/gates/scan-deadlines | 内部事件；DOC-02三类超时，前端禁止调用“自动批准” |
| admin/people-sync/run、sync-config/run-now | integration/hr/sync | TS10签名集成身份；UI手动触发须超管，不混成匿名入口 |
| performance/closeout-readiness/:projectId | projects/{id}/closeout-readiness | 各类缺项在同一readiness响应表达 |
| admin/handover-batch | handoff-batches | 批次与单项目handovers不同资源；DOC-04冻结/恢复 |
| TS13 requirements“游客提交”与页41内部需求 | public/demands 与 requirements独立 | 禁止为游客放开内部requirements；公开查询public/demands/{code} |
| TS13 kpi/projects、allowance/ledgers、bonus/pools简表 | 采用机器表performance详细族 | 不新增并列同义路由；奖金preview/confirm/lock是预览、核定、锁定不同操作 |

保留的不同语义：产品启停≠退市；新建项目、组队确认成项目、既有项目补录分别校验；国家/版本模板视图≠当前扁平认证项；保存原值≠组长评分审核。命名空间通配符只记录范围，不凭/api/search/*造未定义方法。

## 4. Java映射及差距（非auth保留历史盘点）

机器表javaMappings保存签名/位置/哈希；本次仅auth五条刷新到固定0500，其他映射及currentEntityModels保留原盘点类型和BaseEntity字段。下表J01/J02/J03/J05～J08为历史缺口分组，未在本次重新认定其当前完成状态；J04按本次运行证据更新。未据历史盘点声称当前挂载、鉴权、事务或业务完成。

| 差距 | 当前证据 | 后续 |
|---|---|---|
| J01 会话身份与角色 | SEC-01已从五个原控制器移除operatorId参数，23路由显式调用IpdPermission；操作人来自IpdAuthSession.currentPerson，实例化也传入真实IpdActor | 此为源码事实；角色与认证验收见SEC-01/P0-7.1，SEC-02对象范围与P2成员指派仍待验 |
| J02 Entity批量赋值 | Product/Project/CertTemplate/GateElement为RequestBody；Project.create仅为空才设DRAFT | API-02请求DTO白名单、服务端初态/编码 |
| J03 范围/分页/空详情 | 原list仅keyword/项目ID，无完整范围分页；Project/Product.getById直接null | SEC-02、API-01，404与空集合区分 |
| J04 错误包络 | 固定运行包已接认证/权限优先、通用IPD错误、404/405/406/415路由错误与服务端trace；1153选定测试及43运行断言可追溯 | Login/Password严格unknown拒绝和login限流429仍缺；完整业务流程与所有路由不据此宣称完成 |
| J05 1:1及阶段 | Product.bind只改产品侧；项目创建接受非空status；阶段门禁待完整修复 | P1-1.1、P1-2.1/2.2、P1-5相关卡 |
| J06 动作/附件 | 缺完整payload/remark写入接口，交付仅fileName可登记且ossId可空 | P1-3/4、P1-10.2，真实OSS归属/有效证据 |
| J07 模板版本 | CertTemplate扁平项、GateElement定义存在，缺国家/草稿/发布/回退/快照全链 | P1-7.1、P1-6.1、P0-3，不能称页18/47已完整 |
| J08 网络类型 | ID混合类型、Date时区、JSON字符串、Entity内部字段 | API-02/DOC-08局部DTO显式转换，不全局改Jackson破坏基线 |

本表定点修订后追踪计数：49页、458源行、535字面量、608字段原行、273规范路由、28条源码映射（新增refresh），完整verified仍为0；运行观测涉及5个auth端点。非auth映射仍是历史快照，不把该计数当作当前仓库全量扫描。

源码入口：[ApiV1Response](../../../ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/common/ApiV1Response.java)、[ApiV1ErrorCode](../../../ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/common/ApiV1ErrorCode.java)、[全局异常处理](../../../ruoyi-common/ruoyi-common-web/src/main/java/org/ruoyi/common/web/handler/GlobalExceptionHandler.java)、[Jackson配置](../../../ruoyi-common/ruoyi-common-json/src/main/java/org/ruoyi/common/json/config/JacksonConfig.java)。

| 历史方法/完整路径（auth按固定0500刷新） | 参数/方法签名 | 返回类型 | Java位置 |
|---|---|---|---|
| GET /api/v1/cert-templates/resolve | ApiV1Response<List<CertTemplate>> resolve(@RequestParam String markets) | ApiV1Response<List<CertTemplate>> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/CertTemplateController.java:32 |
| GET /api/v1/cert-templates | ApiV1Response<List<CertTemplate>> list() | ApiV1Response<List<CertTemplate>> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/CertTemplateController.java:38 |
| GET /api/v1/cert-templates/country-counts | ApiV1Response<Map<String, Long>> countryCounts() | ApiV1Response<Map<String, Long>> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/CertTemplateController.java:44 |
| POST /api/v1/cert-templates | ApiV1Response<CertTemplate> create(@RequestBody CertTemplate template) | ApiV1Response<CertTemplate> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/CertTemplateController.java:50 |
| POST /api/v1/cert-templates/{id}/remove | ApiV1Response<Void> remove(@PathVariable Long id) | ApiV1Response<Void> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/CertTemplateController.java:57 |
| GET /api/v1/gate-elements | ApiV1Response<List<GateElement>> list(@RequestParam(required = false) String gate) | ApiV1Response<List<GateElement>> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/GateElementController.java:31 |
| POST /api/v1/gate-elements | ApiV1Response<GateElement> create(@RequestBody GateElement element) | ApiV1Response<GateElement> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/GateElementController.java:37 |
| POST /api/v1/gate-elements/{id}/update | ApiV1Response<GateElement> update(@PathVariable Long id, @RequestBody GateElement patch) | ApiV1Response<GateElement> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/GateElementController.java:44 |
| POST /api/v1/gate-elements/{id}/disable | ApiV1Response<GateElement> disable(@PathVariable Long id) | ApiV1Response<GateElement> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/GateElementController.java:52 |
| POST /api/v1/auth/login |     public ApiV1Response<LoginView> login(@Valid @RequestBody LoginRequest request) | ApiV1Response<LoginView> | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:45 |
| POST /api/v1/auth/refresh |     public ApiV1Response<LoginView> refresh(@Valid @RequestBody RefreshRequest request) | ApiV1Response<LoginView> | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:51 |
| GET /api/v1/auth/me |     public ApiV1Response<MeView> me() | ApiV1Response<MeView> | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:63 |
| POST /api/v1/auth/logout |     public ApiV1Response<Void> logout() | ApiV1Response<Void> | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:70 |
| POST /api/v1/auth/change-password |     public ApiV1Response<Void> password(@Valid @RequestBody PasswordRequest request) | ApiV1Response<Void> | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:76 |
| GET /api/v1/products | ApiV1Response<List<Product>> list(@RequestParam(required = false) String keyword) | ApiV1Response<List<Product>> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProductController.java:31 |
| GET /api/v1/products/{id} | ApiV1Response<Product> get(@PathVariable Long id) | ApiV1Response<Product> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProductController.java:37 |
| POST /api/v1/products | ApiV1Response<Product> create(@RequestBody Product product) | ApiV1Response<Product> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProductController.java:43 |
| POST /api/v1/products/{id}/bind-project | ApiV1Response<Void> bindProject(@PathVariable Long id, @RequestParam Long projectId) | ApiV1Response<Void> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProductController.java:50 |
| POST /api/v1/products/{id}/status | ApiV1Response<Void> changeStatus(@PathVariable Long id, @RequestParam String status) | ApiV1Response<Void> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProductController.java:57 |
| GET /api/v1/projects | ApiV1Response<List<Project>> list(@RequestParam(required = false) String keyword) | ApiV1Response<List<Project>> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProjectController.java:30 |
| GET /api/v1/projects/{id} | ApiV1Response<Project> get(@PathVariable Long id) | ApiV1Response<Project> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProjectController.java:36 |
| POST /api/v1/projects | ApiV1Response<Project> create(@RequestBody Project project) | ApiV1Response<Project> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProjectController.java:42 |
| POST /api/v1/projects/{id}/status | ApiV1Response<Project> changeStatus(@PathVariable Long id, @RequestParam String target) | ApiV1Response<Project> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProjectController.java:49 |
| POST /api/v1/projects/{id}/advance-stage | ApiV1Response<Project> advanceStage(@PathVariable Long id) | ApiV1Response<Project> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProjectController.java:55 |
| GET /api/v1/stage-actions | ApiV1Response<List<StageAction>> list(@RequestParam Long projectId) | ApiV1Response<List<StageAction>> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/StageActionController.java:31 |
| POST /api/v1/stage-actions/{id}/transit | ApiV1Response<StageAction> transit(@PathVariable Long id,<br>                                              @RequestParam String target) | ApiV1Response<StageAction> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/StageActionController.java:38 |
| POST /api/v1/stage-actions/{id}/deliverables | ApiV1Response<Deliverable> addDeliverable(@PathVariable Long id,<br>                                                     @RequestParam String fileName,<br>                                                     @RequestParam(required = false) Long ossId) | ApiV1Response<Deliverable> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/StageActionController.java:46 |
| POST /api/v1/stage-actions/instantiate | ApiV1Response<Integer> instantiate(@RequestParam Long projectId,<br>                                              @RequestParam Long stageId,<br>                                              @RequestParam String stage) | ApiV1Response<Integer> | ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/StageActionController.java:55 |

### 当前Entity返回字段

| Entity | 当前字段（精确类型见机器表） | DTO目标 |
|---|---|---|
| Product | id, productCode, productName, modelCode, source, projectId, groupId, status, tenantId, delFlag | ProductView；不透出通用审计/租户/删除字段；缺失页面业务字段需模型/迁移落实 |
| Project | id, code, name, productId, templateType, targetMarkets, level, levelCoefficient, levelCoefficientReason, targetSalesAmount, targetChannelCount, targetNps, targetSceneCount, launchDate, currentStage, lifecycleStatus, source, status, mainGroupId, tenantId, delFlag | ProjectView；不透出通用审计/租户/删除字段；缺失页面业务字段需模型/迁移落实 |
| StageAction | id, projectId, stageId, actionCode, actionName, ownerRole, depth, status, isBlocking, actualDoneAt, farValue, frrValue, certNo, certPassedAt, algoType, isBioFeature, dueDate, sopId | StageActionView；不透出通用审计/租户/删除字段；缺失页面业务字段需模型/迁移落实 |
| Deliverable | id, actionId, projectId, fileName, ossId, fileUrl, fileSize, uploadedBy, uploadedAt | DeliverableView；不透出通用审计/租户/删除字段；缺失页面业务字段需模型/迁移落实 |
| GateElement | id, gateCode, elementCode, elementName, passStandard, isVeto, sortOrder, enabled | GateElementView；不透出通用审计/租户/删除字段；缺失页面业务字段需模型/迁移落实 |
| CertTemplate | id, countryCode, countryName, certName, certAuthority, requirementDesc, isMandatory, tenantId, delFlag | CertTemplateView；不透出通用审计/租户/删除字段；缺失页面业务字段需模型/迁移落实 |


### 本轮新增身份接口：源码已写，分层验证

五个认证端点的固定源码与运行证据已更新；权威源码路径为 `.codex/ipd-integration/20260905-0500-integration/`，AuthController SHA为 `c92338b8808bf93f5ba226bb5a2bf6a703e930c8213ccd0c198802c05d85b467`。当前共享源码与该副本的归并状态尚待确认。源码使用独立IPD身份，不将Person.id当sys_user登录。

| 端点 | 当前请求与行为 | 当前响应 |
|---|---|---|
| POST /api/v1/auth/login | username/password；额外字段按当前Jackson默认忽略，未严格unknown拒绝；每次成功创建独立family | LoginView含token、refreshToken、expiresIn、refreshExpiresIn、scope、mustChangePwd、person，tokenType=Bearer |
| GET /api/v1/auth/me | 有效access；逐次校验Person/family/generation；首登和冻结可读最小身份 | person、scope、mustChangePwd |
| POST /api/v1/auth/logout | 当前有效access；撤销当前family，保留其他独立登录family；不绑定请求body | data=null |
| POST /api/v1/auth/change-password | currentPassword/newPassword；额外字段尚未严格拒绝；新密码还要求UTF-8≤72字节；成功提交后撤销本人全部family | data=null；需重新登录；可修正失败400且保持会话，失败审计可独立持久化 |
| POST /api/v1/auth/refresh | 仅refreshToken，非空≤128字符；JsonAnySetter拒绝unknown含null；无需有效access头；单次CAS轮换，重放401且不能撤销成功后继 | 与login同一LoginView；旧refresh和旧generation access失效 |

源码默认access900秒、refresh family604800秒，可由配置覆盖；family自登录起使用绝对寿命，轮换不续期，refreshExpiresIn为剩余秒数。字段名保持token，未新增旧页accessToken/user别名。首登与冻结同时存在时PASSWORD_CHANGE_REQUIRED优先；首登/冻结允许me、change-password、logout，login/refresh精确POST入口独立验证凭据。其他受保护业务请求分别返回403/20003与403/20002。

证据：[1153项clean测试](../../../.codex/ipd-integration/20260905-0500-integration/root-evidence/integrated-clean-2/summary.json)、[包校验](../../../.codex/ipd-integration/20260905-1215-runtime/package-evidence/package-verification.json)、[43项实际运行断言](../../../.codex/ipd-dev/evidence/api-auth-integrated-runtime.json)。运行jar SHA为`32720f79f944b06fb78468e56e1e29d1c249597b949da42fc9834d7e33b76ce0`；独立匿名HTTP验证记录见[DOC-06定点复核](../../../.codex/ruflo/api01-20260905/doc06-refresh/independent-verification.json)。43是断言数，不是43条完整路由；其中改密覆盖可修正拒绝和失败审计，不能挪用旧运行包的成功改密结果。当前timestamp实测为epoch毫秒，RFC3339仍是目标合同。

统一错误和trace已实现；错误消息不回显SQL、堆栈或凭据。Login/Password unknown字段严格拒绝尚未实现；login限流注解虽存在，无码限流异常当前返回500/90001而非429/40011。审计不可用也可使通常的400/401路径返回500。其他268条路由及非auth Java映射保持原盘点，不能从此节扩散实现/验收状态。

| 当前record DTO | 精确声明（含校验） | 源位置 |
|---|---|---|
| LoginRequest | public record LoginRequest(@NotBlank @Size(max = 64) String username,<br>                               @NotBlank @Size(max = 72) String password) | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:23 |
| PasswordRequest | public record PasswordRequest(@NotBlank @Size(max = 72) String currentPassword,<br>                                  @NotBlank @Size(min = 8, max = 72) String newPassword) | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:25 |
| RefreshRequest | public record RefreshRequest(@NotBlank @Size(max = 128) String refreshToken) | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:27 |
| PersonView | public record PersonView(String id, String name, String username, String personType,<br>                             String groupId, String accountStatus) | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:31 |
| LoginView | public record LoginView(String token, String tokenType, long expiresIn, String scope,<br>                            boolean mustChangePwd, PersonView person, String refreshToken, long refreshExpiresIn) | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:38 |
| MeView | public record MeView(PersonView person, String scope, boolean mustChangePwd) | 固定0500/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/IpdAuthController.java:40 |

## 5. 49页契约矩阵

每页对应本机前端叶子卡P0-10.<页号>。输入为该页可用字段集合，端点按§6及机器表进一步收窄，不是通传整页到任意操作。原必填/长度/单位/条件校验在pages[].fields逐行保留，适用本合同类型与业务覆盖。

| 页/名称（源起始行） | 规范API ID | 可写/过滤输入 | 返回数据 | 身份/状态 | 主依赖卡 |
|---|---|---|---|---|---|
| 01 登录页（batch-01-pages-01-12.md:1） | API-027, API-029, API-030, API-031, API-035, API-136, API-138, API-139, API-140, API-141, API-142 | username,password；refreshToken用于已实现refresh；OAuth仍planned | LoginView新增refreshToken/refreshExpiresIn；family绝对期限与单次轮换见认证节 | AUTH；未登录→已认证或首登受限；禁用/离职拒绝 | P0-7.1 |
| 02 首次登录强制改密（batch-01-pages-01-12.md:75） | API-028, API-035, API-135, API-137 | currentPassword,newPassword | 改密成功data=null并撤销全部family；可修正失败保留会话且失败审计可持久化 | SELF；首次受限→密码更新并退出；旧密码失效，失败不改密码 | P0-7.2 |
| 03 工作台（batch-01-pages-01-12.md:139） | API-035, API-068, API-100, API-105, API-196, API-253 | bucket,type,pageNum,pageSize,projectId | rows,total,stats{pending,overdue,unread,completed},allowedActions | SCOPED；只读；read-all只标本人通知已读 | P4-3.1 |
| 04 删除审核-我的申请（batch-01-pages-01-12.md:208） | API-047, API-160, API-161, API-162, API-163 | entityType,entityId,reason,replacementNote | id,status,criticality,approvalNodes,submittedAt,withdrawDeadlineAt | OWNER；合法对象→LEADER_REVIEW；24h内未终态→WITHDRAWN，详DOC-03 | P0-6.1 |
| 05 删除审核-待我审核（batch-01-pages-01-12.md:276） | API-047, API-048, API-160, API-162 | decision(APPROVE/REJECT),opinion | id,status,leaderDecision,adminDecision,executedAt,auditTrail | APPROVER；LEADER_REVIEW→DELETED或ADMIN_REVIEW；ADMIN_REVIEW→DELETED；任级可REJECTED | P0-6.1 |
| 06 审计日志（batch-01-pages-01-12.md:341） | API-024, API-025, API-026, API-088, API-089 | projectId,actorId,entityType,actionType,scope,dateFrom,dateTo,pageNum,pageSize | rows,total; verify{valid,checkedCount,fromSeq,toSeq,failureSeq}; export文件 | AUDIT；只读且范围过滤；不得写审计内容 | P0-5.4 |
| 07 我的项目-列表（batch-01-pages-01-12.md:408） | API-035, API-047, API-078, API-079, API-080, API-081, API-082, API-086, API-160, API-162, API-238 | keyword,scope,catchup,pageNum,pageSize | rows,total; ProjectView; members,stages,certChecklist,allowedActions | PROJECT；本人项目/本组组长/协同组只读；推进受当前门禁约束 | P1-2.1 |
| 08 新建项目（batch-01-pages-01-12.md:484） | API-032, API-034, API-076, API-079, API-081, API-143, API-147, API-148, API-235, API-237 | productId,templateType,targetMarkets,level,levelCoefficient,levelCoefficientReason,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,recruitmentId | ProjectView{id,code,status,currentStage,source},assignment | MARKET；无→DRAFT/TEAMING由服务端；组队完成→ACTIVE；不接受初始终态 | P1-2.1 |
| 09 存量项目导入（batch-01-pages-01-12.md:559） | API-236, API-239, API-240, API-241, API-242 | name,productId,templateType,targetMarkets,declaredCurrentStageCode,marketPmId,rdPmId,level,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,targetLaunchDate,marketWindow | ProjectView,legacyMissingItems,sceneReviewDeadlineAt,importResult | MARKET；无/存量项目→合法存量阶段；缺历史标缺失，不伪造已签 | P1-9.1 |
| 10 项目详情-项目概览（batch-01-pages-01-12.md:633） | API-035, API-081, API-083, API-096, API-106 | projectId; levelCoefficient,reason,expectedVersion | ProjectOverview,baselineSnapshot,certChecklist,replacement,canEdit | PROJECT；系数需合法范围与确认版本，不直接覆盖锁定基准 | P1-2.2 |
| 11 项目详情-IPD 流程（batch-01-pages-01-12.md:703） | API-035, API-054, API-081, API-087, API-107, API-177, API-178, API-181, API-182, API-183, API-184, API-185, API-238, API-244, API-252, API-256, API-261 | projectId,stageId; action合法字段; gate材料/评审输入 | stages,actions,gates,elementResults,blockingReasons | PROJECT；当前阶段前置齐全才推进；DOC-02/05例外及双签适用范围 | P1-5.1 |
| 12 深管动作详情（batch-01-pages-01-12.md:800） | API-035, API-107, API-131, API-132, API-133, API-134, API-253, API-256, API-257, API-258, API-259 | payload,checklist; status,actualDoneAt,remark; ossId; expectedVersion | StageActionView,sop,qualityRuns,deliverables,revision,allowedActions | ACTION；深管DRAFT/进行中→DONE须真实交付、质量与例外数值；NA须授权 | P1-4.3 |
| 13 轻管动作详情（batch-02-pages-13-24.md:1） | API-102, API-107, API-255 | status,actualDoneAt,remark; farValue,frrValue,certNo,certPassedAt仅适用动作 | StageActionView,depth,sop,requiredFields,revision | ACTION；轻管只三字段，例外数值按目录；不接受随意depth/owner变更；G2-6/P10按DOC-05 | P1-4.1 |
| 14 项目详情-文档与交付物（batch-02-pages-13-24.md:98） | API-023, API-051, API-103, API-134 | projectId,q,category,stageId,stageActionId,description,file | DocumentView[],AttachmentView{id,fileName,size,mimeType,downloadUrl} | PROJECT；上传→待关联/有效附件；下载验归属；不能以文件名充有效交付 | P1-10.2 |
| 15 项目详情-项目日志（batch-02-pages-13-24.md:179） | API-088, API-089, API-090, API-091 | projectId,actionId,from,to,pageNum,pageSize | ProjectTimeline{events,stages,decisions,receipts,changes,certificates},total | PROJECT；只读且按角色脱敏、过滤关联资源 | P0-5.4 |
| 16 产品管理-产品目录（batch-02-pages-13-24.md:266） | API-076, API-229, API-230, API-273 | file; batchId; targetMarkets,isBioFeature | ProductView[],ImportPreview{batchId,summary,errors},ImportResult | PRODUCT_ADMIN；预览不入业务库；确认同批去重；在售/在研/占位三来源 | P1-1.2 |
| 17 产品新增/编辑（batch-02-pages-13-24.md:352） | API-077, API-104, API-232, API-233, API-272 | productName,modelCode,productLine,currentVersion,versionDate,targetMarkets,isBioFeature,notes | ProductView{id,productCode,productName,modelCode,source,lifecycleStatus,projectId,targetMarkets} | PRODUCT；PM_NEW合法创建/编辑；退市独立动作；双向1:1；ACTIVE不可冒认在售 | P1-1.2 |
| 18 国别认证清单模板库（batch-02-pages-13-24.md:438） | API-001, API-005, API-006, API-038, API-112, API-113, API-232, API-263 | countryCode,countryName,certName,certAuthority,requirementDesc,isMandatory; effectiveFrom,itemCode,appliesTo,evidenceField,sortOrder | CertCountryView,CertItemView[],version,appliedProductCount | ADMIN维护；INTERNAL_REFERENCE_READ查阅；草稿维护→版本发布；在途快照不改；引用项停用保留 | P1-7.1 |
| 19 招标组队-招标单列表（batch-02-pages-13-24.md:533） | API-032, API-033, API-143, API-144, API-145, API-146, API-147, API-148, API-237, API-267 | title,customerProblem,applicationScenario,coreFeatures,templateType,targetLaunchDate,marketWindow,level,deadline,recruitmentMode,rdPmIds | BidInvitationView{id,code,status,termsVersion,targets,responses,approvalNodes} | BID；创建/发布→OPEN；遴选→SELECTED；过期EXPIRED；关闭CLOSED | P2-3.1 |
| 20 发起招标（batch-02-pages-13-24.md:635） | API-032, API-079, API-143 | title,customerProblem,applicationScenario,coreFeatures,templateType,targetLaunchDate,marketWindow,level,deadline,recruitmentMode,rdPmIds | BidInvitationView,createOptions | MARKET；本人市场PM发起；targeted需合法研发候选；人员由服务端解析 | P2-3.1 |
| 21 应标（batch-02-pages-13-24.md:717） | API-147 | decision(accept/reject),solutionSummary,estimatedDays,resourceCommitment,majorRisks,reconfirmAcknowledged,termsVersion | responseId,accepted,reconfirmRequired | BID_RD；OPEN且被邀请/公开合法应标；拒绝匿名计数不关联个人留痕 | P2-3.2 |
| 22 遴选（batch-02-pages-13-24.md:787） | API-144, API-148 | rdPmId,reason | status:SELECTED,awardApprovalId | MARKET；仅真实应标候选、最新条件；确认后组队，未中标通知 | P2-3.2 |
| 23 项目详情-Gate 评审（batch-02-pages-13-24.md:851） | API-092, API-177, API-180, API-181, API-182, API-183, API-184, API-194, API-252 | attemptId,expectedVersion,decision(AGREE/REJECT),opinion,evidenceIds; newDueAt,reason | GateReviewView{id,attemptId,round,status,dueAt,deadlineVersion,slots,canSign,blockingReasons} | GATE；DOC-02：并行盲签/固定主导/三超时/重发/仲裁；非双签按RACI | P2-5.1 |
| 24 Gate 评审详情（batch-02-pages-13-24.md:956） | API-053, API-054, API-055, API-104, API-177, API-178, API-179, API-182, API-183 | attemptId,elementCode,decision(passed/conditional/failed),responsiblePersonId,closeDeadline,evidenceNote,comment | elements,myJudgment,othersSubmitted,overallDecision,allowedActions | GATE；conditional需责任人/期限；适用否决不能通过；普通视图盲签、仲裁访问独立 | P2-5.2 |
| 25 项目详情-需求与变更（batch-03-pages-25-37.md:1） | API-040, API-041, API-042, API-044, API-097, API-134, API-151, API-152, API-153, API-154, API-155, API-156, API-157, API-250 | projectId,requirementId,title,reason,impactScope,impactScheduleDays,impactCostWan,evidenceIds | ChangeView{id,code,status,attemptId,projectId,requirementId,implementation} | CHANGE；申请→双PM盲签；通过回写采纳/PRD；未闭环仍阻断阶段 | P2-6.1 |
| 26 需求变更单详情（batch-03-pages-25-37.md:162） | API-024, API-040, API-041, API-042, API-043, API-152, API-153, API-154, API-155, API-156, API-157 | attemptId,expectedVersion,decision,opinion; note,evidenceIds,verificationNote | ChangeView,reviewDetail,implementation,evidence,closedAt | CHANGE；DOC-02：审批≠实施闭环；基线/实施/双PM验证→CLOSED；终态只读 | P2-6.2 |
| 27 项目移交（batch-03-pages-25-37.md:304） | API-022, API-056, API-057, API-058, API-083, API-084, API-105, API-116, API-117, API-186, API-187, API-188, API-189, API-190, API-191, API-192, API-193 | projectId,toPersonId,handoffScope,includeProduct,note; batchTitle,batchNote,legs; decision,comment | HandoverView,preview,affectedWork,allowancePreview,status | HANDOVER；发起→PENDING→ACCEPTED/REJECTED/CANCELLED；冻结只移交；DOC-04不发冻结津贴 | P2-7.1 |
| 28 组织架构（batch-03-pages-25-37.md:456） | API-007, API-008, API-009, API-010, API-011, API-012, API-013, API-118, API-122, API-123, API-124, API-193, API-264 | groupId; syncType,since; sourceConfig非机密引用 | OrganizationTree,groups,members,syncStatus,lastSyncedAt | ORG_READ；来源API权威；禁止手工新组/改组长；同步预览→确认→记录 | P2-1.1 |
| 29 KPI 考核-功能 KPI（batch-03-pages-25-37.md:598） | API-002, API-067, API-069, API-208, API-215, API-216, API-269 | projectId,metricCode,period,rawValue,evidenceNote,evidenceIds; score仅合法组长评分节点 | KpiRecord{rawValue,targetValue,score,dataStatus,ruleVersion,evidence,status} | KPI；DRAFT→SUBMITTED→REVIEWED/REJECTED；缺量表/PPM目标待补充，不编分 | P3-1.1 |
| 30 KPI 考核-共担 KPI 归集（batch-03-pages-25-37.md:732） | API-019, API-069, API-072, API-073, API-074, API-195, API-225, API-226, API-227, API-271 | projectId,period,metricCode,rawValue,sampleSize,source,evidenceNote,evidenceIds | SharedKpi{achievementRate,weightedScore,maxScore,percentScore,status,deadlineAt,dataStatus} | KPI_SHARED；15/10/10/5权重分；不足5点按比例；回款来源；提交→审核→锁定；DOC-01/04 | P3-1.2 |
| 31 项目绩效评定（batch-03-pages-25-37.md:881） | API-019, API-069, API-070, API-217, API-218, API-219, API-270 | projectId,period,reviewType,ownScore,evidenceNote; decision,comment | ProjectScoreView{marketCompositeScore,rdCompositeScore,performanceCoefficient,status,ruleVersion} | PROJECT_SCORE；按评定节点20/40/40；30/90日版本分开；锁定只读，不拿跨项目合计套奖金系数 | P3-2.1 |
| 32 项目详情-KPI 考核（batch-03-pages-25-37.md:1025） | API-063, API-067, API-069, API-080, API-093, API-215, API-269 | projectId,period,metricCode,rawValue,evidenceNote; score仅评分节点 | KpiSummary{functionalScore,sharedWeightedScore,sharedPercentScore,compositeScore,dataStatus,projects} | KPI；单项目F×0.6+W；同人同期间多项目求和可>100，缺项不补0 | P3-1.3 |
| 33 激励管理-津贴台账（batch-03-pages-25-37.md:1166） | API-059, API-061, API-069, API-075, API-197, API-198, API-199, API-200 | projectId,period; stopReason,evidenceIds; decision,opinion | AllowanceLedger{baseAmount,calculatedAmount,cappedAmount,status,outputSnapshot,stopOrderStatus} | ALLOWANCE；生成→审核→锁定；既有金额封顶保留；冻结不发，无产出人工确认 | P3-3.3 |
| 34 激励管理-奖金池核算（batch-03-pages-25-37.md:1312） | API-018, API-071, API-201, API-202, API-203, API-204, API-220, API-221, API-222, API-223, API-224 | projectId,entryType,amountYuan,occurredOn,description,evidenceIds; calculationId,expectedVersion | ReceiptLedger,BonusCalculation{inputs,ruleVersion,steps,amountYuan,roundingResidual,dataStatus} | BONUS；净负回款待人工；预览不发放；确认/锁定去重；最终到分HALF_UP，尾差另账 | P3-4.4 |
| 35 贡献度评定（batch-03-pages-25-37.md:1555） | API-018, API-069, API-205, API-206, API-207, API-268 | projectId,dimensions,marketPct,rdPct,evidenceNote,evidenceIds; decision,opinion | ContributionView{dimensions,weights,marketPct,rdPct,status,confirmations} | CONTRIBUTION；仅G5窗口；双PM自评+各组长；40–65/35–60且总100；锁定只读 | P3-6.2 |
| 36 负反馈执行（batch-03-pages-25-37.md:1701） | API-021, API-064, API-065, API-066, API-209, API-210, API-211, API-212, API-213, API-214 | projectId,feedbackType,description,evidenceNote,evidenceIds; decision,comment; remediationNote,remediationEvidence | FeedbackView{responsibility,allowanceImpact,bonusImpact,status,approvalNodes} | FEEDBACK；PENDING→LOCKED或REJECTED；整改→REMEDIATION_REVIEW→RESOLVED；责任由类型计算 | P3-8.2 |
| 37 项目详情-激励台账（batch-03-pages-25-37.md:1867） | API-020, API-024, API-059, API-060, API-062, API-064, API-069, API-071, API-082, API-085 | projectId,period,from,to | IncentiveSummary,ledgers,calculationTrace,readiness,export | INCENTIVE_READ；只读；项目金额分别计算后汇总，不能用个人总评分重算每项目 | P4-4.1 |
| 38 需求门户-游客提交（batch-04-pages-38-49.md:1） | API-095, API-245, API-247, API-248, API-249 | customerName,feedbackPerson,contact,productId,rawModel,functionalRequirement,website(空),files | code,uploadToken,status:SUBMITTED,submittedAt | PUBLIC_SUBMIT；游客提交→SUBMITTED；服务端解析双PM；其他类待指派；不得返回身份/内部审批细节 | P4-1.1 |
| 39 需求门户-查询进度（batch-04-pages-38-49.md:97） | API-094, API-247, API-248；DOC-03补充：API-246 | code,supplementNote,withdrawReason; uploadToken仅上传 | GuestDemandView{code,status,maskedCustomerName,timeline,attachments,canSupplement,canWithdraw} | PUBLIC_CODE；受理前可补充/撤回，不受旧24h限制；受理后仅追加评论；关闭/归档只读 | P4-1.2 |
| 40 需求池（batch-04-pages-38-49.md:176） | API-049, API-050, API-098, API-164, API-165, API-166, API-167, API-169, API-170, API-171, API-172, API-173；DOC-03补充：API-168 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | DemandView{id,status,assignedMarketPmId,assignedRdPmId,history,allowedActions} | DEMAND；SUBMITTED→ACCEPTED→EVALUATING→SCHEDULED→PROCESSING→CLOSED→ARCHIVED；不收录CLOSED+原因 | P4-1.3 |
| 41 需求详情-处理（batch-04-pages-38-49.md:268） | API-097, API-098, API-099, API-160, API-250, API-251 | projectId,title,description,priority,acceptanceCriteria,stageCode,stageActionId; targetStatus,reason | RequirementView{id,code,status,projectId,stageActionId,history} | REQUIREMENT；项目需求与产品需求池分开；关联同项目动作；七态按DOC-03；变更进入双签，不五级链 | P4-1.4 |
| 42 AI 文档助手（batch-04-pages-38-49.md:343） | API-003, API-004, API-108, API-109, API-110, API-111, API-131, API-132, API-133, API-262 | projectId,stageActionId,promptType,sourceText,selectedSources,params; decision,comment | AiGeneration{documentId,versionId,content,tokenUsage,latencyMs,status} | AI；生成→待审核；编辑留版本；人工审核才归档；60秒/预算/重试按原文 | P4-2.3 |
| 43 删除审核-归档区（batch-04-pages-38-49.md:439） | API-045, API-046, API-158, API-159, API-160 | status,entityType,from,to; confirmTail,clearedReason; restoreReason | ArchiveEntry,hashChain,restoreResult,clearResult | ARCHIVE_ADMIN；无自动清理；恢复重验引用/权限；有引用不直接清除；二次确认，审计永久 | P0-6.4 |
| 44 人员管理（batch-04-pages-38-49.md:511） | API-008, API-013, API-016, API-119, API-120, API-121, API-186, API-193 | personId; syncType,since; reason; handover配置输入 | PersonPublicView,syncRecords,freezeState,handoverProgress | ADMIN；HR ACTIVE/RESIGNED驱动；冻结→完成移交→DISABLED；误报恢复按DOC-04 | P2-2.3 |
| 45 参数配置（batch-04-pages-38-49.md:590） | API-017, API-128, API-129, API-266 | params,draftId,targetVersionId,reason | current,history,draftId,publishedVersion,validationErrors | ADMIN；草稿校验→发布；规则类型/范围/生效快照；回退新版本不改历史 | P0-3.2 |
| 46 SOP 模板（batch-04-pages-38-49.md:705） | API-014, API-015, API-125, API-126, API-127, API-265 | templateId,fields,checklist,content,reason | SopVersion,SopTemplate,versionHistory | ADMIN；复制发布版成草稿→修改→发布；回退留新事件；在途项目不改快照 | P1-3.3 |
| 47 Gate 评审要素（batch-04-pages-38-49.md:778） | API-052, API-114, API-115, API-174, API-175, API-176 | gateCode,elementCode,elementName,passStandard,isVeto,sortOrder; reason | GateElementView{id,gateCode,elementCode,enabled,version},history | ADMIN；33/14当前基准；版本草稿修改/停用，发布留快照；不删除在途证据 | P1-6.1 |
| 48 AI 模型配置（batch-04-pages-38-49.md:857） | API-003, API-004, API-108, API-109, API-110, API-111, API-132, API-262 | name,providerType,endpoint,apiKey(writeOnly),model,temperature,maxTokens,timeoutMs,retryTimes,monthlyTokenBudget,rpmLimit,enabled | ProviderView{id,name,providerType,endpoint,model,keyConfigured,keyFingerprint,enabled,isDefault},callLogs | ADMIN；密钥只写加密不回显；启停/设默认唯一；日志去凭证 | P4-2.1 |
| 49 超级管理员移交（batch-04-pages-38-49.md:934） | API-008, API-022, API-130 | toPersonId,replacementLeadId,confirmation,currentPassword | TransferReadiness,TransferResult{transferId,newAdminId,oldAccountDisabled} | SUPER_ADMIN；真实超管二次确认；移交原子完成才禁用旧账户和会话；非前端改角色 | P2-7.3 |

## 6. 全部规范接口记录

I=implemented（仅源码映射）；P=planned（未有该映射）；V=完整verified（仍为0；五auth的局部运行证据另列）。每条完整response/identityPolicy/stateContract/errors/sourceFieldPageRefs/Java证据见同ID机器记录，不能将领域的实现标签扩散到全部端点。

| ID | 方法/唯一规范路径 | 状态/开放范围 | 源页 / DTO页 | 请求白名单（路径ID另计） | 后续主卡 |
|---|---|---|---|---|---|
| API-001 | DELETE /api/v1/admin/cert-templates/{countryId}/items/{itemId} | P / AUTHENTICATED | 18 / PAGE-18 | countryCode,countryName,certName,certAuthority,requirementDesc,isMandatory; effectiveFrom,itemCode,appliesTo,evidenceField,sortOrder | P1-7.1 |
| API-002 | DELETE /api/v1/performance/kpis/{projectId}/{metricCode}/evidence/{attachmentId} | P / AUTHENTICATED | 29 / PAGE-29 | projectId,metricCode,period,rawValue,evidenceNote,evidenceIds; score仅合法组长评分节点 | P3-1.1 |
| API-003 | GET /api/v1/admin/ai-call-logs | P / AUTHENTICATED | 42,48 / PAGE-48 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-2.1 |
| API-004 | GET /api/v1/admin/ai-providers | P / AUTHENTICATED | 42,48 / PAGE-48 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-2.1 |
| API-005 | GET /api/v1/admin/cert-templates | P / AUTHENTICATED | 18 / PAGE-18 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-7.1 |
| API-006 | GET /api/v1/admin/cert-templates/{countryId} | P / AUTHENTICATED | 18 / PAGE-18 | 仅path/query：countryId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-7.1 |
| API-007 | GET /api/v1/admin/identity-source | P / AUTHENTICATED | 28 / PAGE-28 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-1.1 |
| API-008 | GET /api/v1/admin/members | P / AUTHENTICATED | 28,44,49 / PAGE-44 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-2.3 |
| API-009 | GET /api/v1/admin/organization | P / AUTHENTICATED | 28 / PAGE-28 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-1.1 |
| API-010 | GET /api/v1/admin/organization/groups/{groupId} | P / AUTHENTICATED | 28 / PAGE-28 | 仅path/query：groupId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-1.1 |
| API-011 | GET /api/v1/admin/organization/sync-status | P / AUTHENTICATED | 28 / PAGE-28 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-1.1 |
| API-012 | GET /api/v1/admin/organization/tree | P / AUTHENTICATED | 28 / PAGE-28 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-1.1 |
| API-013 | GET /api/v1/admin/people-sync/history | P / AUTHENTICATED | 28,44 / PAGE-28 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-1.1 |
| API-014 | GET /api/v1/admin/sop-versions | P / AUTHENTICATED | 46 / PAGE-46 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-3.3 |
| API-015 | GET /api/v1/admin/sop-versions/{id} | P / AUTHENTICATED | 46 / PAGE-46 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-3.3 |
| API-016 | GET /api/v1/admin/sync-config | P / AUTHENTICATED | 44 / PAGE-44 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-2.3 |
| API-017 | GET /api/v1/admin/system-config | P / AUTHENTICATED | 45 / PAGE-45 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-3.2 |
| API-018 | GET /api/v1/admin/system-config/bonus | P / AUTHENTICATED | 34,35 / PAGE-45 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-3.2 |
| API-019 | GET /api/v1/admin/system-config/kpi | P / AUTHENTICATED | 30,31 / PAGE-45 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-3.2 |
| API-020 | GET /api/v1/admin/system-config/performance | P / AUTHENTICATED | 37 / PAGE-45 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-3.2 |
| API-021 | GET /api/v1/admin/system-config/performance/feedback | P / AUTHENTICATED | 36 / PAGE-36 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-8.2 |
| API-022 | GET /api/v1/admin/transfer-readiness | P / AUTHENTICATED | 27,49 / PAGE-49 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-7.3 |
| API-023 | GET /api/v1/attachments/{id} | P / AUTHENTICATED | 14 / PAGE-14 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-10.2 |
| API-024 | GET /api/v1/audit-logs | P / AUTHENTICATED | 6,26,37 / PAGE-06 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-5.4 |
| API-025 | GET /api/v1/audit-logs/export | P / AUTHENTICATED | 6 / PAGE-06 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-5.4 |
| API-026 | GET /api/v1/audit-logs/verify | P / AUTHENTICATED | 6 / PAGE-06 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-5.4 |
| API-027 | GET /api/v1/auth/me | I / AUTHENTICATED | 1 / PAGE-01 | GET无声明path/query业务参数；不绑定请求body，不保证额外query/body均返回400 | P0-7.1 |
| API-028 | GET /api/v1/auth/password-policy | P / AUTHENTICATED | 2 / PAGE-02 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-7.2 |
| API-029 | GET /api/v1/auth/wecom/callback | P / AUTH_ENTRY_SCOPED | 1 / PAGE-01 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-7.1 |
| API-030 | GET /api/v1/auth/wecom/demo/accounts | P / DEMO_ONLY; no production compatibility route | 1 / PAGE-01 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-7.1 |
| API-031 | GET /api/v1/auth/wecom/url | P / AUTH_ENTRY_SCOPED | 1 / PAGE-01 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-7.1 |
| API-032 | GET /api/v1/bid-invitations | P / AUTHENTICATED | 8,19,20 / PAGE-19 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-3.1 |
| API-033 | GET /api/v1/bid-invitations/{id} | P / AUTHENTICATED | 19 / PAGE-19 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-3.1 |
| API-034 | GET /api/v1/bid-invitations/{id}/responses | P / AUTHENTICATED | 8 / PAGE-19 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-3.1 |
| API-035 | GET /api/v1/bootstrap | P / AUTHENTICATED | 1,2,3,7,10,11,12 / PAGE-03 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-3.1 |
| API-036 | GET /api/v1/cert-templates | I / AUTHENTICATED |  / PAGE-18 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-7.1 |
| API-037 | GET /api/v1/cert-templates/country-counts | I / AUTHENTICATED |  / PAGE-18 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-7.1 |
| API-038 | GET /api/v1/cert-templates/lookup | P / AUTHENTICATED | 18 / PAGE-18 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-7.1 |
| API-039 | GET /api/v1/cert-templates/resolve | I / AUTHENTICATED |  / PAGE-18 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-7.1 |
| API-040 | GET /api/v1/changes | P / AUTHENTICATED | 25,26 / PAGE-25 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-6.1 |
| API-041 | GET /api/v1/changes/{id} | P / AUTHENTICATED | 25,26 / PAGE-26 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-6.2 |
| API-042 | GET /api/v1/changes/{id}/implementation | P / AUTHENTICATED | 25,26 / PAGE-26 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-6.2 |
| API-043 | GET /api/v1/changes/{id}/review-detail | P / AUTHENTICATED | 26 / PAGE-26 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-6.2 |
| API-044 | GET /api/v1/collaboration | P / AUTHENTICATED | 25 / PAGE-25 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-6.1 |
| API-045 | GET /api/v1/deletion-archive | P / AUTHENTICATED | 43 / PAGE-43 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-6.4 |
| API-046 | GET /api/v1/deletion-archive/{id}/hash-chain | P / AUTHENTICATED | 43 / PAGE-43 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-6.4 |
| API-047 | GET /api/v1/deletion-requests | P / AUTHENTICATED | 4,5,7 / PAGE-04 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-6.1 |
| API-048 | GET /api/v1/deletion-requests/{id}/audit-trail | P / AUTHENTICATED | 5 / PAGE-04 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-6.1 |
| API-049 | GET /api/v1/demands | P / AUTHENTICATED | 40 / PAGE-40 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-1.3 |
| API-050 | GET /api/v1/demands/unassigned | P / AUTHENTICATED | 40 / PAGE-40 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-1.3 |
| API-051 | GET /api/v1/documents | P / AUTHENTICATED | 14 / PAGE-14 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-10.2 |
| API-052 | GET /api/v1/gate-elements | I / AUTHENTICATED | 47 / PAGE-47 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-6.1 |
| API-053 | GET /api/v1/gates/{id} | P / AUTHENTICATED | 24 / PAGE-23 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-5.1 |
| API-054 | GET /api/v1/gates/{id}/elements | P / AUTHENTICATED | 11,24 / PAGE-24 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-5.2 |
| API-055 | GET /api/v1/gates/{id}/reviews | P / AUTHENTICATED | 24 / PAGE-23 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-5.1 |
| API-056 | GET /api/v1/handoff-batches | P / AUTHENTICATED | 27 / PAGE-27 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-7.1 |
| API-057 | GET /api/v1/handovers/inbox | P / AUTHENTICATED | 27 / PAGE-27 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-7.1 |
| API-058 | GET /api/v1/handovers/{id}/preview | P / AUTHENTICATED | 27 / PAGE-27 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-7.1 |
| API-059 | GET /api/v1/performance/allowances | P / AUTHENTICATED | 33,37 / PAGE-33 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-3.3 |
| API-060 | GET /api/v1/performance/bonuses/{projectId} | P / AUTHENTICATED | 37 / PAGE-34 | 仅path/query：projectId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-4.4 |
| API-061 | GET /api/v1/performance/capacity-approvals | P / AUTHENTICATED | 33 / PAGE-33 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-3.3 |
| API-062 | GET /api/v1/performance/contributions/{projectId} | P / AUTHENTICATED | 37 / PAGE-35 | 仅path/query：projectId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-6.2 |
| API-063 | GET /api/v1/performance/cycles | P / AUTHENTICATED | 32 / PAGE-32 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-1.3 |
| API-064 | GET /api/v1/performance/feedback | P / AUTHENTICATED | 36,37 / PAGE-36 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-8.2 |
| API-065 | GET /api/v1/performance/feedback/{id} | P / AUTHENTICATED | 36 / PAGE-36 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-8.2 |
| API-066 | GET /api/v1/performance/feedback/{id}/attachments | P / AUTHENTICATED | 36 / PAGE-36 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-8.2 |
| API-067 | GET /api/v1/performance/kpis/{projectId}/{metricCode}/evidence | P / AUTHENTICATED | 29,32 / PAGE-29 | 仅path/query：projectId,metricCode；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-1.1 |
| API-068 | GET /api/v1/performance/no-output-alerts | P / AUTHENTICATED | 3 / PAGE-03 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-3.1 |
| API-069 | GET /api/v1/performance/overview | P / AUTHENTICATED | 29,30,31,32,33,35,37 / PAGE-32 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-1.3 |
| API-070 | GET /api/v1/performance/project-reviews/{projectId} | P / AUTHENTICATED | 31 / PAGE-31 | 仅path/query：projectId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-2.1 |
| API-071 | GET /api/v1/performance/receipts/{projectId} | P / AUTHENTICATED | 34,37 / PAGE-34 | 仅path/query：projectId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-4.4 |
| API-072 | GET /api/v1/performance/shared-kpis/collect | P / AUTHENTICATED | 30 / PAGE-30 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-1.2 |
| API-073 | GET /api/v1/performance/shared-kpis/reconcile | P / AUTHENTICATED | 30 / PAGE-30 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-1.2 |
| API-074 | GET /api/v1/performance/shared-kpis/{id}/evidence | P / AUTHENTICATED | 30 / PAGE-30 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-1.2 |
| API-075 | GET /api/v1/performance/substantive-output | P / AUTHENTICATED | 33 / PAGE-33 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-3.3 |
| API-076 | GET /api/v1/products | I / AUTHENTICATED | 8,16 / PAGE-16 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-1.2 |
| API-077 | GET /api/v1/products/{id} | I / AUTHENTICATED | 17 / PAGE-17 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-1.2 |
| API-078 | GET /api/v1/projects | I / AUTHENTICATED | 7 / PAGE-07 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-079 | GET /api/v1/projects/create-options | P / AUTHENTICATED | 7,8,20 / PAGE-07 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-080 | GET /api/v1/projects/{id} | I / AUTHENTICATED | 7,32 / PAGE-07 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-081 | GET /api/v1/projects/{id}/cert-checklist | P / AUTHENTICATED | 7,8,10,11 / PAGE-07 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-082 | GET /api/v1/projects/{id}/closeout-readiness | P / AUTHENTICATED | 7,37 / PAGE-07 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-083 | GET /api/v1/projects/{id}/handoff-candidates | P / AUTHENTICATED | 10,27 / PAGE-07 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-084 | GET /api/v1/projects/{id}/handover-allowance-preview | P / AUTHENTICATED | 27 / PAGE-07 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-085 | GET /api/v1/projects/{id}/incentives/export | P / AUTHENTICATED | 37 / PAGE-37 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-4.1 |
| API-086 | GET /api/v1/projects/{id}/members | P / AUTHENTICATED | 7 / PAGE-07 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.1 |
| API-087 | GET /api/v1/projects/{id}/stages | P / AUTHENTICATED | 11 / PAGE-11 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-5.1 |
| API-088 | GET /api/v1/projects/{id}/timeline | P / AUTHENTICATED | 6,15 / PAGE-15 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-5.4 |
| API-089 | GET /api/v1/projects/{id}/timeline/actions/{workItemId} | P / AUTHENTICATED | 6,15 / PAGE-15 | 仅path/query：id,workItemId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-5.4 |
| API-090 | GET /api/v1/projects/{id}/timeline/certificates | P / AUTHENTICATED | 15 / PAGE-15 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-5.4 |
| API-091 | GET /api/v1/projects/{id}/timeline/gate-element-results | P / AUTHENTICATED | 15 / PAGE-15 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P0-5.4 |
| API-092 | GET /api/v1/projects/{projectId}/gates | P / AUTHENTICATED | 23 / PAGE-23 | 仅path/query：projectId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-5.1 |
| API-093 | GET /api/v1/projects/{projectId}/performance/summary | P / AUTHENTICATED | 32 / PAGE-32 | 仅path/query：projectId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P3-1.3 |
| API-094 | GET /api/v1/public/demands/{code} | P / PUBLIC_CODE_SCOPED | 39 / PAGE-39 | 仅path/query：code；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-1.2 |
| API-095 | GET /api/v1/public/products | P / PUBLIC_CODE_SCOPED | 38 / PAGE-39 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-1.2 |
| API-096 | GET /api/v1/rd-replacements/{replacementId} | P / AUTHENTICATED | 10 / PAGE-10 | 仅path/query：replacementId；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-2.2 |
| API-097 | GET /api/v1/requirements | P / AUTHENTICATED | 25,41 / PAGE-41 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-1.4 |
| API-098 | GET /api/v1/requirements/{id} | P / AUTHENTICATED | 40,41 / PAGE-41 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-1.4 |
| API-099 | GET /api/v1/requirements/{id}/history | P / AUTHENTICATED | 41 / PAGE-41 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-1.4 |
| API-100 | GET /api/v1/saved-items | P / AUTHENTICATED | 3 / PAGE-03 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-3.1 |
| API-101 | GET /api/v1/stage-actions | I / AUTHENTICATED |  / PAGE-12 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-4.3 |
| API-102 | GET /api/v1/stage-actions/{id} | P / AUTHENTICATED | 13 / PAGE-12 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-4.3 |
| API-103 | GET /api/v1/stage-actions/{id}/attachments | P / AUTHENTICATED | 14 / PAGE-12 | 仅path/query：id；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P1-4.3 |
| API-104 | GET /api/v1/users | P / AUTHENTICATED | 17,24 / PAGE-28 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P2-1.1 |
| API-105 | GET /api/v1/workflow/tasks | P / AUTHENTICATED | 3,27 / PAGE-03 | 仅path/query：无路径参数；查询参数取sourceQueries及本页过滤字段；禁止GET产生业务写入 | P4-3.1 |
| API-106 | PATCH /api/v1/projects/{id}/level-coefficient | P / AUTHENTICATED | 10 / PAGE-07 | keyword,scope,catchup,pageNum,pageSize | P1-2.1 |
| API-107 | PATCH /api/v1/stage-actions/{id} | P / AUTHENTICATED | 11,12,13 / PAGE-13 | status,actualDoneAt,remark; farValue,frrValue,certNo,certPassedAt仅适用动作 | P1-4.1 |
| API-108 | POST /api/v1/admin/ai-providers | P / AUTHENTICATED | 42,48 / PAGE-48 | name,providerType,endpoint,apiKey(writeOnly),model,temperature,maxTokens,timeoutMs,retryTimes,monthlyTokenBudget,rpmLimit,enabled | P4-2.1 |
| API-109 | POST /api/v1/admin/ai-providers/{id}/rotate-key | P / AUTHENTICATED | 42,48 / PAGE-48 | apiKey(writeOnly),expectedVersion,reason；响应只含keyConfigured/keyFingerprint | P4-2.1 |
| API-110 | POST /api/v1/admin/ai-providers/{id}/set-default | P / AUTHENTICATED | 42,48 / PAGE-48 | expectedVersion；唯一默认原子切换 | P4-2.1 |
| API-111 | POST /api/v1/admin/ai-providers/{id}/sync | P / AUTHENTICATED | 42,48 / PAGE-48 | name,providerType,endpoint,apiKey(writeOnly),model,temperature,maxTokens,timeoutMs,retryTimes,monthlyTokenBudget,rpmLimit,enabled | P4-2.1 |
| API-112 | POST /api/v1/admin/cert-templates/{countryId}/copy | P / AUTHENTICATED | 18 / PAGE-18 | countryCode,countryName,certName,certAuthority,requirementDesc,isMandatory; effectiveFrom,itemCode,appliesTo,evidenceField,sortOrder | P1-7.1 |
| API-113 | POST /api/v1/admin/cert-templates/{countryId}/items | P / AUTHENTICATED | 18 / PAGE-18 | countryCode,countryName,certName,certAuthority,requirementDesc,isMandatory; effectiveFrom,itemCode,appliesTo,evidenceField,sortOrder | P1-7.1 |
| API-114 | POST /api/v1/admin/gate-element-versions/{id}/publish | P / AUTHENTICATED | 47 / PAGE-47 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P1-6.1 |
| API-115 | POST /api/v1/admin/gate-element-versions/{id}/revert | P / AUTHENTICATED | 47 / PAGE-47 | gateCode,elementCode,elementName,passStandard,isVeto,sortOrder; reason | P1-6.1 |
| API-116 | POST /api/v1/admin/handoff-batches/assign-frozen-status | P / AUTHENTICATED | 27 / PAGE-27 | projectId,toPersonId,handoffScope,includeProduct,note; batchTitle,batchNote,legs; decision,comment | P2-7.1 |
| API-117 | POST /api/v1/admin/handoff-batches/unfreeze | P / AUTHENTICATED | 27 / PAGE-27 | projectId,toPersonId,handoffScope,includeProduct,note; batchTitle,batchNote,legs; decision,comment | P2-7.1 |
| API-118 | POST /api/v1/admin/identity-source/test | P / AUTHENTICATED | 28 / PAGE-28 | groupId; syncType,since; sourceConfig非机密引用 | P2-1.1 |
| API-119 | POST /api/v1/admin/members/{id}/confirm-handover | P / AUTHENTICATED | 44 / PAGE-44 | personId; syncType,since; reason; handover配置输入 | P2-2.3 |
| API-120 | POST /api/v1/admin/members/{id}/freeze-handover | P / AUTHENTICATED | 44 / PAGE-44 | personId; syncType,since; reason; handover配置输入 | P2-2.3 |
| API-121 | POST /api/v1/admin/members/{id}/resign | P / AUTHENTICATED | 44 / PAGE-44 | personId; syncType,since; reason; handover配置输入 | P2-2.3 |
| API-122 | POST /api/v1/admin/people-sync/confirm | P / AUTHENTICATED | 28 / PAGE-28 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P2-1.1 |
| API-123 | POST /api/v1/admin/people-sync/preview | P / AUTHENTICATED | 28 / PAGE-28 | groupId; syncType,since; sourceConfig非机密引用 | P2-1.1 |
| API-124 | POST /api/v1/admin/product-groups | P / FORBIDDEN_BY_SOURCE; do not mount a write implementation | 28 / PAGE-28 | groupId; syncType,since; sourceConfig非机密引用 | P2-1.1 |
| API-125 | POST /api/v1/admin/sop-versions/{id}/copy | P / AUTHENTICATED | 46 / PAGE-46 | templateId,fields,checklist,content,reason | P1-3.3 |
| API-126 | POST /api/v1/admin/sop-versions/{id}/publish | P / AUTHENTICATED | 46 / PAGE-46 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P1-3.3 |
| API-127 | POST /api/v1/admin/sop-versions/{id}/revert | P / AUTHENTICATED | 46 / PAGE-46 | templateId,fields,checklist,content,reason | P1-3.3 |
| API-128 | POST /api/v1/admin/system-config/revert | P / AUTHENTICATED | 45 / PAGE-45 | params,draftId,targetVersionId,reason | P0-3.2 |
| API-129 | POST /api/v1/admin/system-config/{draftId}/publish | P / AUTHENTICATED | 45 / PAGE-45 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P0-3.2 |
| API-130 | POST /api/v1/admin/transfer | P / AUTHENTICATED | 49 / PAGE-49 | toPersonId,replacementLeadId,confirmation(必须为确认移交管理员),currentPassword；密码不写审计/日志 | P2-7.3 |
| API-131 | POST /api/v1/ai/context-preview | P / AUTHENTICATED | 12,42 / PAGE-42 | projectId,stageActionId,selectedSources,fieldKey,promptType；来源逐项授权 | P4-2.3 |
| API-132 | POST /api/v1/ai/generate | P / AUTHENTICATED | 12,42,48 / PAGE-42 | projectId,stageActionId,promptType,sourceText,selectedSources,params; decision,comment | P4-2.3 |
| API-133 | POST /api/v1/ai/outputs/{id}/decision | P / AUTHENTICATED | 12,42 / PAGE-42 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P4-2.3 |
| API-134 | POST /api/v1/attachments | P / AUTHENTICATED | 12,14,25 / PAGE-14 | projectId,q,category,stageId,stageActionId,description,file | P1-10.2 |
| API-135 | POST /api/v1/auth/change-password | I / AUTHENTICATED | 2 / PAGE-02 | currentPassword(string必填,max72字符),newPassword(string必填,min8,max72字符且服务层UTF-8≤72字节)；confirmPassword仅前端 | P0-7.2 |
| API-136 | POST /api/v1/auth/login | I / AUTH_ENTRY_SCOPED | 1 / PAGE-01 | username(string非空,max64字符),password(string非空,max72字符)；不能以额外personId/role/scope取得身份 | P0-7.1 |
| API-137 | POST /api/v1/auth/logout | I / AUTHENTICATED | 2 / PAGE-02 | 无声明请求body；使用当前有效IPD access；额外body不绑定，不等于严格拒绝所有非空body | P0-7.3 |
| API-138 | POST /api/v1/auth/refresh | I / AUTH_ENTRY_SCOPED | 1 / PAGE-01 | 仅refreshToken(string非空,max128字符)；不需要有效access头；unknown字段含null均拒绝 | P0-7.3 |
| API-139 | POST /api/v1/auth/wecom/bind | P / AUTH_ENTRY_SCOPED | 1 / PAGE-01 | username,password；refreshToken与OAuth code/state分别只用于其planned端点 | P0-7.1 |
| API-140 | POST /api/v1/auth/wecom/demo/login | P / DEMO_ONLY; no production compatibility route | 1 / PAGE-01 | username,password；refreshToken与OAuth code/state分别只用于其planned端点 | P0-7.1 |
| API-141 | POST /api/v1/auth/wecom/start | P / DEMO_ONLY; no production compatibility route | 1 / PAGE-01 | username,password；refreshToken与OAuth code/state分别只用于其planned端点 | P0-7.1 |
| API-142 | POST /api/v1/auth/wecom/unbind | P / AUTH_ENTRY_SCOPED | 1 / PAGE-01 | username,password；refreshToken与OAuth code/state分别只用于其planned端点 | P0-7.1 |
| API-143 | POST /api/v1/bid-invitations | P / AUTHENTICATED | 8,19,20 / PAGE-19 | title,customerProblem,applicationScenario,coreFeatures,templateType,targetLaunchDate,marketWindow,level,deadline,recruitmentMode,rdPmIds | P2-3.1 |
| API-144 | POST /api/v1/bid-invitations/{id}/approvals | P / AUTHENTICATED | 19,22 / PAGE-19 | title,customerProblem,applicationScenario,coreFeatures,templateType,targetLaunchDate,marketWindow,level,deadline,recruitmentMode,rdPmIds | P2-3.1 |
| API-145 | POST /api/v1/bid-invitations/{id}/close | P / AUTHENTICATED | 19 / PAGE-19 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P2-3.1 |
| API-146 | POST /api/v1/bid-invitations/{id}/publish | P / AUTHENTICATED | 19 / PAGE-19 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P2-3.1 |
| API-147 | POST /api/v1/bid-invitations/{id}/responses | P / AUTHENTICATED | 8,19,21 / PAGE-21 | decision(accept/reject),solutionSummary,estimatedDays,resourceCommitment,majorRisks,reconfirmAcknowledged,termsVersion | P2-3.2 |
| API-148 | POST /api/v1/bid-invitations/{id}/select | P / AUTHENTICATED | 8,19,22 / PAGE-22 | rdPmId,reason,termsVersion,expectedVersion | P2-3.2 |
| API-149 | POST /api/v1/cert-templates | I / AUTHENTICATED |  / PAGE-18 | countryCode,countryName,certName,certAuthority,requirementDesc,isMandatory; effectiveFrom,itemCode,appliesTo,evidenceField,sortOrder | P1-7.1 |
| API-150 | POST /api/v1/cert-templates/{id}/remove | I / AUTHENTICATED |  / PAGE-18 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P1-7.1 |
| API-151 | POST /api/v1/changes | P / AUTHENTICATED | 25 / PAGE-25 | projectId,requirementId,title,reason,impactScope,impactScheduleDays,impactCostWan,evidenceIds | P2-6.1 |
| API-152 | POST /api/v1/changes/{id}/baseline | P / AUTHENTICATED | 25,26 / PAGE-26 | note,expectedVersion；必须已有有效批准决定并原子关联PRD版本 | P2-6.2 |
| API-153 | POST /api/v1/changes/{id}/close | P / AUTHENTICATED | 25,26 / PAGE-26 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P2-6.2 |
| API-154 | POST /api/v1/changes/{id}/evidence | P / AUTHENTICATED | 25,26 / PAGE-26 | ossId,evidenceNote,expectedVersion；维度证据另需dimensionCode；对象须真实、合法且属于当前资源 | P2-6.2 |
| API-155 | POST /api/v1/changes/{id}/implementation | P / AUTHENTICATED | 25,26 / PAGE-26 | note,evidenceIds,expectedVersion；仅实施责任人 | P2-6.2 |
| API-156 | POST /api/v1/changes/{id}/reviews | P / AUTHENTICATED | 25,26 / PAGE-26 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P2-6.2 |
| API-157 | POST /api/v1/changes/{id}/verify | P / AUTHENTICATED | 25,26 / PAGE-26 | verificationNote,evidenceIds,expectedVersion；双PM各自验证槽 | P2-6.2 |
| API-158 | POST /api/v1/deletion-archive/{id}/clear | P / AUTHENTICATED | 43 / PAGE-43 | expectedVersion,confirmTail,clearedReason；二次确认+引用检查 | P0-6.4 |
| API-159 | POST /api/v1/deletion-archive/{id}/restore | P / AUTHENTICATED | 43 / PAGE-43 | expectedVersion,restoreReason；重验权限/引用/合法目标状态 | P0-6.4 |
| API-160 | POST /api/v1/deletion-requests | P / AUTHENTICATED | 4,5,7,41,43 / PAGE-04 | entityType,entityId,reason,replacementNote | P0-6.1 |
| API-161 | POST /api/v1/deletion-requests/{id}/escalate | P / AUTHENTICATED | 4 / PAGE-04 | entityType,entityId,reason,replacementNote | P0-6.1 |
| API-162 | POST /api/v1/deletion-requests/{id}/review | P / AUTHENTICATED | 4,5,7 / PAGE-05 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P0-6.1 |
| API-163 | POST /api/v1/deletion-requests/{id}/withdraw | P / AUTHENTICATED | 4 / PAGE-04 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P0-6.1 |
| API-164 | POST /api/v1/demands/{id}/accept | P / AUTHENTICATED | 40 / PAGE-40 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | P4-1.3 |
| API-165 | POST /api/v1/demands/{id}/archive | P / AUTHENTICATED | 40 / PAGE-40 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P4-1.3 |
| API-166 | POST /api/v1/demands/{id}/claim | P / AUTHENTICATED | 40 / PAGE-40 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | P4-1.3 |
| API-167 | POST /api/v1/demands/{id}/close | P / AUTHENTICATED | 40 / PAGE-40 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P4-1.3 |
| API-168 | POST /api/v1/demands/{id}/comments | P / AUTHENTICATED |  / PAGE-40 | commentText(非空),evidenceIds,expectedVersion；保留原内容不覆盖 | P4-1.3 |
| API-169 | POST /api/v1/demands/{id}/evaluate | P / AUTHENTICATED | 40 / PAGE-40 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | P4-1.3 |
| API-170 | POST /api/v1/demands/{id}/link-project | P / AUTHENTICATED | 40 / PAGE-40 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | P4-1.3 |
| API-171 | POST /api/v1/demands/{id}/process | P / AUTHENTICATED | 40 / PAGE-40 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | P4-1.3 |
| API-172 | POST /api/v1/demands/{id}/schedule | P / AUTHENTICATED | 40 / PAGE-40 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | P4-1.3 |
| API-173 | POST /api/v1/demands/{id}/triage | P / AUTHENTICATED | 40 / PAGE-40 | productId,source,priority; evaluation,evidenceIds,projectId,stageActionId,closeReason,closeResult,comment | P4-1.3 |
| API-174 | POST /api/v1/gate-elements | I / AUTHENTICATED | 47 / PAGE-47 | gateCode,elementCode,elementName,passStandard,isVeto,sortOrder; reason | P1-6.1 |
| API-175 | POST /api/v1/gate-elements/{id}/disable | I / AUTHENTICATED | 47 / PAGE-47 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P1-6.1 |
| API-176 | POST /api/v1/gate-elements/{id}/update | I / AUTHENTICATED | 47 / PAGE-47 | gateCode,elementCode,elementName,passStandard,isVeto,sortOrder; reason | P1-6.1 |
| API-177 | POST /api/v1/gates/{id}/arbitrate | P / AUTHENTICATED | 11,23,24 / PAGE-23 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P2-5.1 |
| API-178 | POST /api/v1/gates/{id}/elements/{code}/judge | P / AUTHENTICATED | 11,24 / PAGE-24 | attemptId,expectedVersion,decision(passed/conditional/failed),responsiblePersonId,closeDeadline,evidenceNote,comment；elementCode由路径 | P2-5.2 |
| API-179 | POST /api/v1/gates/{id}/elements/{code}/legacy-todo | P / INTERNAL_ONLY; frontend never calls timer/automatic approval | 24 / PAGE-24 | 内部调度事件标识与版本；不公开为前端业务写接口 | P2-5.2 |
| API-180 | POST /api/v1/gates/{id}/extend | P / AUTHENTICATED | 23 / PAGE-23 | attemptId,expectedVersion,newDueAt,reason；延期次数由服务器校验≤3 | P2-5.1 |
| API-181 | POST /api/v1/gates/{id}/materials | P / AUTHENTICATED | 11,23 / PAGE-23 | attemptId,expectedVersion,decision(AGREE/REJECT),opinion,evidenceIds; newDueAt,reason | P2-5.1 |
| API-182 | POST /api/v1/gates/{id}/reopen | P / AUTHENTICATED | 11,23,24 / PAGE-23 | attemptId,expectedVersion,revisedMaterialVersion,reason | P2-5.1 |
| API-183 | POST /api/v1/gates/{id}/reviews | P / AUTHENTICATED | 11,23,24 / PAGE-23 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P2-5.1 |
| API-184 | POST /api/v1/gates/{id}/submit | P / AUTHENTICATED | 11,23 / PAGE-23 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P2-5.1 |
| API-185 | POST /api/v1/gates/{stageId}/waive | P / AUTHENTICATED | 11 / PAGE-23 | attemptId,expectedVersion,decision(AGREE/REJECT),opinion,evidenceIds; newDueAt,reason | P2-5.1 |
| API-186 | POST /api/v1/handoff-batches | P / AUTHENTICATED | 27,44 / PAGE-27 | projectId,toPersonId,handoffScope,includeProduct,note; batchTitle,batchNote,legs; decision,comment | P2-7.1 |
| API-187 | POST /api/v1/handoff-batches/preview | P / AUTHENTICATED | 27 / PAGE-27 | projectId,toPersonId,handoffScope,includeProduct,note; batchTitle,batchNote,legs; decision,comment | P2-7.1 |
| API-188 | POST /api/v1/handoff-batches/{batchId}/legs/{legId}/decision | P / AUTHENTICATED | 27 / PAGE-27 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P2-7.1 |
| API-189 | POST /api/v1/handovers | P / AUTHENTICATED | 27 / PAGE-27 | projectId,toPersonId,handoffScope,includeProduct,note; batchTitle,batchNote,legs; decision,comment | P2-7.1 |
| API-190 | POST /api/v1/handovers/{id}/cancel | P / AUTHENTICATED | 27 / PAGE-27 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P2-7.1 |
| API-191 | POST /api/v1/handovers/{id}/decision | P / AUTHENTICATED | 27 / PAGE-27 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P2-7.1 |
| API-192 | POST /api/v1/handovers/{id}/product-continuation | P / AUTHENTICATED | 27 / PAGE-27 | projectId,toPersonId,handoffScope,includeProduct,note; batchTitle,batchNote,legs; decision,comment | P2-7.1 |
| API-193 | POST /api/v1/integration/hr/sync | P / AUTHENTICATED | 27,28,44 / PAGE-28 | syncType(FULL/INCREMENTAL),since(ISO8601 optional)；不得提交人员角色作为操作人 | P2-1.1 |
| API-194 | POST /api/v1/internal/gates/scan-deadlines | P / INTERNAL_ONLY; frontend never calls timer/automatic approval | 23 / PAGE-23 | 内部调度事件标识与版本；不公开为前端业务写接口 | P2-5.1 |
| API-195 | POST /api/v1/internal/shared-kpis/scan-deadlines | P / INTERNAL_ONLY; frontend never calls timer/automatic approval | 30 / PAGE-30 | 内部调度事件标识与版本；不公开为前端业务写接口 | P3-1.2 |
| API-196 | POST /api/v1/notifications/read-all | P / AUTHENTICATED | 3 / PAGE-03 | 无对象/人员输入，仅当前会话本人 | P4-3.1 |
| API-197 | POST /api/v1/performance/allowance-stop-orders | P / AUTHENTICATED | 33 / PAGE-33 | projectId,period; stopReason,evidenceIds; decision,opinion | P3-3.3 |
| API-198 | POST /api/v1/performance/allowance-stop-orders/{id}/decision | P / AUTHENTICATED | 33 / PAGE-33 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-3.3 |
| API-199 | POST /api/v1/performance/allowances/{id}/decision | P / AUTHENTICATED | 33 / PAGE-33 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-3.3 |
| API-200 | POST /api/v1/performance/allowances/{projectId}/generate | P / AUTHENTICATED | 33 / PAGE-33 | projectId,period; stopReason,evidenceIds; decision,opinion | P3-3.3 |
| API-201 | POST /api/v1/performance/bonus-calculate/confirm | P / AUTHENTICATED | 34 / PAGE-34 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P3-4.4 |
| API-202 | POST /api/v1/performance/bonus-calculate/preview | P / AUTHENTICATED | 34 / PAGE-34 | projectId,entryType,amountYuan,occurredOn,description,evidenceIds; calculationId,expectedVersion | P3-4.4 |
| API-203 | POST /api/v1/performance/bonuses/{id}/lock | P / AUTHENTICATED | 34 / PAGE-34 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P3-4.4 |
| API-204 | POST /api/v1/performance/bonuses/{projectId}/generate | P / AUTHENTICATED | 34 / PAGE-34 | projectId,entryType,amountYuan,occurredOn,description,evidenceIds; calculationId,expectedVersion | P3-4.4 |
| API-205 | POST /api/v1/performance/contribution-evidence | P / AUTHENTICATED | 35 / PAGE-35 | ossId,evidenceNote,expectedVersion；维度证据另需dimensionCode；对象须真实、合法且属于当前资源 | P3-6.2 |
| API-206 | POST /api/v1/performance/contributions/{projectId}/confirm | P / AUTHENTICATED | 35 / PAGE-35 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P3-6.2 |
| API-207 | POST /api/v1/performance/contributions/{projectId}/decision | P / AUTHENTICATED | 35 / PAGE-35 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-6.2 |
| API-208 | POST /api/v1/performance/cycles/generate | P / AUTHENTICATED | 29 / PAGE-29 | projectId,metricCode,period,rawValue,evidenceNote,evidenceIds; score仅合法组长评分节点 | P3-1.1 |
| API-209 | POST /api/v1/performance/feedback | P / AUTHENTICATED | 36 / PAGE-36 | projectId,feedbackType,description,evidenceNote,evidenceIds; decision,comment; remediationNote,remediationEvidence | P3-8.2 |
| API-210 | POST /api/v1/performance/feedback/{id}/attachments | P / AUTHENTICATED | 36 / PAGE-36 | projectId,feedbackType,description,evidenceNote,evidenceIds; decision,comment; remediationNote,remediationEvidence | P3-8.2 |
| API-211 | POST /api/v1/performance/feedback/{id}/close | P / AUTHENTICATED | 36 / PAGE-36 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P3-8.2 |
| API-212 | POST /api/v1/performance/feedback/{id}/decision | P / AUTHENTICATED | 36 / PAGE-36 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-8.2 |
| API-213 | POST /api/v1/performance/feedback/{id}/remediation | P / AUTHENTICATED | 36 / PAGE-36 | projectId,feedbackType,description,evidenceNote,evidenceIds; decision,comment; remediationNote,remediationEvidence | P3-8.2 |
| API-214 | POST /api/v1/performance/feedback/{id}/remediation-review | P / AUTHENTICATED | 36 / PAGE-36 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-8.2 |
| API-215 | POST /api/v1/performance/kpis/{projectId}/review | P / AUTHENTICATED | 29,32 / PAGE-29 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-1.1 |
| API-216 | POST /api/v1/performance/kpis/{projectId}/{metricCode}/evidence | P / AUTHENTICATED | 29 / PAGE-29 | ossId,evidenceNote,expectedVersion；维度证据另需dimensionCode；对象须真实、合法且属于当前资源 | P3-1.1 |
| API-217 | POST /api/v1/performance/project-reviews/{projectId}/decision | P / AUTHENTICATED | 31 / PAGE-31 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-2.1 |
| API-218 | POST /api/v1/performance/project-reviews/{projectId}/lock | P / AUTHENTICATED | 31 / PAGE-31 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P3-2.1 |
| API-219 | POST /api/v1/performance/project-reviews/{projectId}/submit | P / AUTHENTICATED | 31 / PAGE-31 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P3-2.1 |
| API-220 | POST /api/v1/performance/receipts/{id}/evidence | P / AUTHENTICATED | 34 / PAGE-34 | ossId,evidenceNote,expectedVersion；维度证据另需dimensionCode；对象须真实、合法且属于当前资源 | P3-4.4 |
| API-221 | POST /api/v1/performance/receipts/{id}/submit | P / AUTHENTICATED | 34 / PAGE-34 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P3-4.4 |
| API-222 | POST /api/v1/performance/receipts/{projectId} | P / AUTHENTICATED | 34 / PAGE-34 | projectId,entryType,amountYuan,occurredOn,description,evidenceIds; calculationId,expectedVersion | P3-4.4 |
| API-223 | POST /api/v1/performance/receipts/{projectId}/settlement | P / AUTHENTICATED | 34 / PAGE-34 | projectId,entryType,amountYuan,occurredOn,description,evidenceIds; calculationId,expectedVersion | P3-4.4 |
| API-224 | POST /api/v1/performance/receipts/{projectId}/settlement-decision | P / AUTHENTICATED | 34 / PAGE-34 | projectId,entryType,amountYuan,occurredOn,description,evidenceIds; calculationId,expectedVersion | P3-4.4 |
| API-225 | POST /api/v1/performance/shared-kpis/collect | P / AUTHENTICATED | 30 / PAGE-30 | projectId,period,metricCode,rawValue,sampleSize,source,evidenceNote,evidenceIds | P3-1.2 |
| API-226 | POST /api/v1/performance/shared-kpis/collect/{id}/review | P / AUTHENTICATED | 30 / PAGE-30 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P3-1.2 |
| API-227 | POST /api/v1/performance/shared-kpis/lock | P / AUTHENTICATED | 30 / PAGE-30 | expectedVersion,reason及该对象草稿/批次ID；不得提交服务器计算的金额/身份/终态 | P3-1.2 |
| API-228 | POST /api/v1/products | I / AUTHENTICATED |  / PAGE-17 | productName,modelCode,productLine,currentVersion,versionDate,targetMarkets,isBioFeature,notes | P1-1.2 |
| API-229 | POST /api/v1/products/import-confirm | P / AUTHENTICATED | 16 / PAGE-16 | batchId,expectedVersion；不得上传任意解析后产品数组绕过预览 | P1-1.2 |
| API-230 | POST /api/v1/products/import-preview | P / AUTHENTICATED | 16 / PAGE-16 | file(.xlsx,multipart)；仅超管 | P1-1.2 |
| API-231 | POST /api/v1/products/{id}/bind-project | I / AUTHENTICATED |  / PAGE-17 | projectId,expectedVersion；双向1:1、来源与归属验证 | P1-1.2 |
| API-232 | POST /api/v1/products/{id}/cert-templates/derive | P / AUTHENTICATED | 17,18 / PAGE-18 | countryCode,countryName,certName,certAuthority,requirementDesc,isMandatory; effectiveFrom,itemCode,appliesTo,evidenceField,sortOrder | P1-7.1 |
| API-233 | POST /api/v1/products/{id}/retirement | P / AUTHENTICATED | 17 / PAGE-17 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P1-1.2 |
| API-234 | POST /api/v1/products/{id}/status | I / AUTHENTICATED |  / PAGE-17 | targetStatus,expectedVersion,reason；按明确状态机，不得直接保存任意status | P1-1.2 |
| API-235 | POST /api/v1/projects | I / AUTHENTICATED | 8 / PAGE-08 | productId,templateType,targetMarkets,level,levelCoefficient,levelCoefficientReason,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,recruitmentId | P1-2.1 |
| API-236 | POST /api/v1/projects/catch-up | P / AUTHENTICATED | 9 / PAGE-09 | name,productId,templateType,targetMarkets,declaredCurrentStageCode,marketPmId,rdPmId,level,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,targetLaunchDate,marketWindow | P1-9.1 |
| API-237 | POST /api/v1/projects/from-recruitment | P / AUTHENTICATED | 8,19 / PAGE-08 | productId,templateType,targetMarkets,level,levelCoefficient,levelCoefficientReason,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,recruitmentId | P1-2.1 |
| API-238 | POST /api/v1/projects/{id}/advance-stage | I / AUTHENTICATED | 7,11 / PAGE-11 | expectedVersion；不接受客户端targetStage，服务端按当前阶段计算下一阶段 | P1-5.1 |
| API-239 | POST /api/v1/projects/{id}/biweekly-reviews | P / AUTHENTICATED | 9 / PAGE-08 | productId,templateType,targetMarkets,level,levelCoefficient,levelCoefficientReason,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,recruitmentId | P1-2.1 |
| API-240 | POST /api/v1/projects/{id}/catchup-milestone | P / AUTHENTICATED | 9 / PAGE-09 | name,productId,templateType,targetMarkets,declaredCurrentStageCode,marketPmId,rdPmId,level,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,targetLaunchDate,marketWindow | P1-9.1 |
| API-241 | POST /api/v1/projects/{id}/catchup-stage-completed | P / AUTHENTICATED | 9 / PAGE-09 | name,productId,templateType,targetMarkets,declaredCurrentStageCode,marketPmId,rdPmId,level,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,targetLaunchDate,marketWindow | P1-9.1 |
| API-242 | POST /api/v1/projects/{id}/legacy-import | P / AUTHENTICATED | 9 / PAGE-09 | name,productId,templateType,targetMarkets,declaredCurrentStageCode,marketPmId,rdPmId,level,targetSalesAmount,targetChannelCount,targetNps,targetSceneCount,targetLaunchDate,marketWindow | P1-9.1 |
| API-243 | POST /api/v1/projects/{id}/status | I / AUTHENTICATED |  / PAGE-08 | targetStatus,expectedVersion,reason；按明确状态机，不得直接保存任意status | P1-2.1 |
| API-244 | POST /api/v1/projects/{projectId}/stages/{stageId}/gate-submissions | P / AUTHENTICATED | 11 / PAGE-11 | projectId,stageId; action合法字段; gate材料/评审输入 | P1-5.1 |
| API-245 | POST /api/v1/public/demands | P / PUBLIC_CODE_SCOPED | 38 / PAGE-38 | customerName,feedbackPerson,contact,productId,rawModel,functionalRequirement,website(空),files | P4-1.1 |
| API-246 | POST /api/v1/public/demands/{code}/comments | P / PUBLIC_CODE_SCOPED |  / PAGE-39 | commentText(非空),evidenceIds,expectedVersion；保留原内容不覆盖 | P4-1.2 |
| API-247 | POST /api/v1/public/demands/{code}/supplement | P / PUBLIC_CODE_SCOPED | 38,39 / PAGE-39 | supplementNote,expectedVersion；受理前追加不覆盖原文 | P4-1.2 |
| API-248 | POST /api/v1/public/demands/{code}/withdraw | P / PUBLIC_CODE_SCOPED | 38,39 / PAGE-39 | expectedVersion,reason；只允许该动作输入，不接受任意status/实体字段 | P4-1.2 |
| API-249 | POST /api/v1/public/demands/{id}/attachments | P / PUBLIC_CODE_SCOPED | 38 / PAGE-38 | customerName,feedbackPerson,contact,productId,rawModel,functionalRequirement,website(空),files | P4-1.1 |
| API-250 | POST /api/v1/requirements | P / AUTHENTICATED | 25,41 / PAGE-41 | projectId,title,description,priority,acceptanceCriteria,stageCode,stageActionId; targetStatus,reason | P4-1.4 |
| API-251 | POST /api/v1/requirements/{id}/status | P / AUTHENTICATED | 41 / PAGE-41 | targetStatus,expectedVersion,reason；按明确状态机，不得直接保存任意status | P4-1.4 |
| API-252 | POST /api/v1/reviews/{id}/decision | P / AUTHENTICATED | 11,23 / PAGE-11 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P1-5.1 |
| API-253 | POST /api/v1/saved-items | P / AUTHENTICATED | 3,12 / PAGE-03 | targetType,targetId；服务器校验目标可见，不接受savedBy | P4-3.1 |
| API-254 | POST /api/v1/stage-actions/instantiate | I / AUTHENTICATED |  / PAGE-12 | projectId,stageId,stageCode；内部业务编排或授权管理动作，三者数据库关系一致 | P1-4.3 |
| API-255 | POST /api/v1/stage-actions/{id}/convert-to-deep | P / AUTHENTICATED | 13 / PAGE-12 | payload,checklist; status,actualDoneAt,remark; ossId; expectedVersion | P1-4.3 |
| API-256 | POST /api/v1/stage-actions/{id}/deliverables | I / AUTHENTICATED | 11,12 / PAGE-12 | ossId,fileName,description,expectedVersion；OSS对象必须真实且有权；文件名不是充分证据 | P1-4.3 |
| API-257 | POST /api/v1/stage-actions/{id}/quality-check | P / AUTHENTICATED | 12 / PAGE-12 | payload,checklist; status,actualDoneAt,remark; ossId; expectedVersion | P1-4.3 |
| API-258 | POST /api/v1/stage-actions/{id}/quality-runs/{runId}/review | P / AUTHENTICATED | 12 / PAGE-12 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P1-4.3 |
| API-259 | POST /api/v1/stage-actions/{id}/sop-preview | P / AUTHENTICATED | 12 / PAGE-12 | payload,checklist; status,actualDoneAt,remark; ossId; expectedVersion | P1-4.3 |
| API-260 | POST /api/v1/stage-actions/{id}/transit | I / AUTHENTICATED |  / PAGE-12 | targetStatus,expectedVersion,reason；按明确状态机，不得直接保存任意status | P1-4.3 |
| API-261 | POST /api/v1/waivers/{id}/decision | P / AUTHENTICATED | 11 / PAGE-11 | expectedVersion,decision,opinion,evidenceIds；Gate/变更额外attemptId；主体/签署槽服务器解析 | P1-5.1 |
| API-262 | PUT /api/v1/admin/ai-providers/{id} | P / AUTHENTICATED | 42,48 / PAGE-48 | name,providerType,endpoint,apiKey(writeOnly),model,temperature,maxTokens,timeoutMs,retryTimes,monthlyTokenBudget,rpmLimit,enabled | P4-2.1 |
| API-263 | PUT /api/v1/admin/cert-templates/{countryId}/items/{itemId} | P / AUTHENTICATED | 18 / PAGE-18 | countryCode,countryName,certName,certAuthority,requirementDesc,isMandatory; effectiveFrom,itemCode,appliesTo,evidenceField,sortOrder | P1-7.1 |
| API-264 | PUT /api/v1/admin/product-groups/{id}/lead | P / FORBIDDEN_BY_SOURCE; do not mount a write implementation | 28 / PAGE-28 | groupId; syncType,since; sourceConfig非机密引用 | P2-1.1 |
| API-265 | PUT /api/v1/admin/sop-templates/{templateId} | P / AUTHENTICATED | 46 / PAGE-46 | templateId,fields,checklist,content,reason | P1-3.3 |
| API-266 | PUT /api/v1/admin/system-config | P / AUTHENTICATED | 45 / PAGE-45 | params,draftId,targetVersionId,reason | P0-3.2 |
| API-267 | PUT /api/v1/bid-invitations/{id}/terms | P / AUTHENTICATED | 19 / PAGE-19 | title,customerProblem,applicationScenario,coreFeatures,templateType,targetLaunchDate,marketWindow,level,deadline,recruitmentMode,rdPmIds | P2-3.1 |
| API-268 | PUT /api/v1/performance/contributions/{projectId} | P / AUTHENTICATED | 35 / PAGE-35 | projectId,dimensions,marketPct,rdPct,evidenceNote,evidenceIds; decision,opinion | P3-6.2 |
| API-269 | PUT /api/v1/performance/kpis/{projectId}/{metricCode} | P / AUTHENTICATED | 29,32 / PAGE-29 | projectId,metricCode,period,rawValue,evidenceNote,evidenceIds; score仅合法组长评分节点 | P3-1.1 |
| API-270 | PUT /api/v1/performance/project-reviews/{projectId}/draft | P / AUTHENTICATED | 31 / PAGE-31 | projectId,period,reviewType,ownScore,evidenceNote; decision,comment | P3-2.1 |
| API-271 | PUT /api/v1/performance/shared-kpis/collect/{id} | P / AUTHENTICATED | 30 / PAGE-30 | projectId,period,metricCode,rawValue,sampleSize,source,evidenceNote,evidenceIds | P3-1.2 |
| API-272 | PUT /api/v1/products/{id} | P / AUTHENTICATED | 17 / PAGE-17 | productName,modelCode,productLine,currentVersion,versionDate,targetMarkets,isBioFeature,notes | P1-1.2 |
| API-273 | PUT /api/v1/products/{id}/target-markets | P / AUTHENTICATED | 16 / PAGE-17 | productName,modelCode,productLine,currentVersion,versionDate,targetMarkets,isBioFeature,notes | P1-1.2 |

## 7. 前端和验收

RuoYi Vue原request.ts处理code200/msg，IPD使用独立ApiV1适配处理code0/message；/api/v1代理保持完整前缀。禁止全局把0或200同时视为所有接口成功。未implemented接口可以依据契约做界面，Mock须显式标识，不以原React假数据证明联调。

每条TC-API验收均包含：有效会话/资源/初态/输入→正确字段及真实DB/审计/通知结果；未登录、越权、伪造operatorId、跨对象、非法字段、旧版本、同键异载荷→HTTP/业务码正确且业务零写入。按需验证大ID/小数/空页/缺数据/文件错误JSON、多身份盲签及冻结会话。Surefire须Tag dev且tests>0；只测Service不能标verified。

独立验证器重读4份规格、Java映射及机器表，对49页、API源行/字面量、规范路由做双向集合校验，检查唯一性、来源位置、所有后续卡、链接、状态标签并运行删除/重复来源及路由的内存破坏夹具。证据目录为.codex/ruflo/swarm-20260905-decisions/api/。PASS仅为工程合同验收，不代表业务HTTP或数据库已通过。

本次实际运行：python3 .codex/ruflo/swarm-20260905-decisions/api/verify_contract.py，退出码0，3051项检查通过，1475个删除/重复/伪造状态等负例被拒绝。覆盖49页、458源行、535个API字面量、608字段原行；该历史验证版本为273个规范接口、27个源码映射、246个planned、0个verified。后续本次仅更新五auth，表内追踪源码映射28、planned245、完整verified仍0，不能沿用旧验证计数作为本次运行结果。两条comments接口由DOC-03已确认的“受理后追加评论”补充，使用supplementalRouteIds关联页39/40，未伪造页级API原行；内部/演示/明确禁止记录的开放范围保持独立。

验证期间发现“删除纯Java来源接口可能漏检”的验证器缺口，已补源码映射→规范路由反向完整性；本轮再按稳定Controller刷新位置/哈希/签名，并独立比较原始方法签名，增加伪造operatorId签名反例。逐一执行全部接口删除/重复负例，未跳过失败。逐项原始结果在verification.json；完整命令/退出码/输出在artifact-validation.json。以上为文档静态层，后续真实MVC、DB/Redis、页面与业务证据仍按各卡提交。

### 本次定点更新校验边界

本次保留273条路由及全部源行/字面量；仅改五auth记录、相关DTO/页面01–02合同和统一错误/trace说明。其他268条路由逐对象相等检查通过。旧`verify_contract.py`强制所有条目source_only并要求全量共享源码完全匹配，不能直接验证本次具有局部运行证据的混合版本盘点；本次未修改该验证器，也未声称其旧3051项已再次通过。候选结构、固定源SHA/签名、未触记录与运行证据的复核结果记录于DOC-06定点复核目录。
