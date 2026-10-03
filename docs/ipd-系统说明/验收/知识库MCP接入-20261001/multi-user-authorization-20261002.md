# 多用户与团队授权独立核验（2026-10-02）

状态：PARTIAL。仅只读核验，未改权限、源码、数据库或执行审核写操作。

## 当前真实 HTTP

真实 Person 900102（GROUP_LEADER）和 900101（SUPER_ADMIN）均登录有效，均包含 `ipd:ai-document:review`。对运行 2106049620076437505、其 events、定档文档 2106050740358258690 的 versions，前者均 HTTP 404 / code 50001「项目不可见」，后者均 HTTP 200 / code 0。原始脱敏响应见同目录 `multi-user-authorization-20261002.json`。

这证明该非项目成员样本无法读取本次管理员运行及产物，不能扩展成所有角色、多团队或同项目不同发起人的完整验收。

## 确定的源码边界

- `ProjectAgentRunService.java:722-729`：先调用 `access.requireVisible` 重新校验项目，再比较可信 tenantId 和 actor.id / run.personId；详情、事件、取消、定档使用同一 owner 闸门。
- `IpdCopilotAccess.java:33-63`：重新读 Person，校验在职、ACTIVE、FULL、角色、组与租户；项目存在及租户匹配后，只给超管豁免或有效未退出项目成员放行。产线负责人不自动获得智能体执行权，符合既定边界。
- `AiDocumentController.java:93-98,152-172,196-202`：审核/退回要求真实 IPD review 权限并校验文档链归属与项目成员权限；未把审核权限单独当作项目访问权。

## 尚未闭合的团队只读合同

既定合同允许产线负责人查看本线项目及产物，但操作仍须项目成员身份。`ProjectService.java:612,747-760` 明确允许产线负责人读取项目；`AiDocumentController.java:113-128,196-199` 的文档列表/版本链却调用 `AiDocumentService.requireProjectVisible`，后者 `AiDocumentService.java:168-172` 直接转给上述只接受成员的 `IpdCopilotAccess`。因此源码不能支持“非项目成员产线负责人能看产物”这一合同，尚需实际负责人账号确认运行态。

修复必须区分产物只读守卫与执行/定档/审核守卫。不得直接给 `IpdCopilotAccess` 加产线负责人豁免，否则会把只读权扩大到运行创建、定档等写入口。复用现有项目可见规则而非建立第二套权限事实；同时保留真实 Person 重读和当前产品线负责人校验。

## 未验收项

1. 同项目成员 A/B 跨发起人访问 run、events、cancel、apply 的真实 HTTP 拒绝；当前 leader 样本在成员校验之前被拒绝。
2. 当前产线负责人非成员只读项目/产物，以及退出空间、撤职后的权限即时回收。
3. 市场 PM、研发 PM、普通成员、冻结/离职、首登限权账号的 HTTP 矩阵。
4. 审核操作与成员撤销并发，以及真实审核审计回读。

这些未验证项应登记到唯一总计划/看板对应节点，不另建权限执行轨。

## 已批准局部修复（待本次产物加载）

仅 AiDocumentController/AiDocumentService 的列表、版本、历史及 diff 读入口改为 requireProjectReadable：先通过原 IpdCopilotAccess 重读真实 Person（projectId=null），再复用 ProjectService.getVisibleById，并校验可信租户。未改变 create/generate/revise/review/reject/archive、运行创建、事件、定档的成员/发起人守卫。沿用既有项目只读规则，未新增角色或权限表。

新增定向测试覆盖非成员产线负责人可读不可写、负责人替换即时收回、普通非成员拒绝、身份过期前置拒绝及跨租户拒绝。测试通过和当前包加载的证据在完成后回填；以上源码不等同当前 HTTP 已生效。

定向 Maven 结果：21 tests，0 failures / errors / skipped，BUILD SUCCESS，原日志 `/tmp/ipd-team-read-authorization-20261002.log`。当前状态 PENDING_RUNTIME_VALIDATION：尚未统一打包重载、负责人真实 HTTP 正反回读。

## 第2次接续候选运行包真实 HTTP 矩阵

本段保留上述历史结论。主协调重载候选 PID53037后，本子任务先GET认证接口取得401，再执行四个现有真实Person登录及本机GET。采集时间UTC2026-10-02T16:38:05，脱敏状态与实际身份在原JSON新增 `currentRuntimeChecks`，不保存token、正文或内部推理。

- 超管900101与市场PM900103都能读项目9140005及其文档list/versions/history/diff（HTTP200/code0，list6条、版本和历史各1条）。组长900102、研发PM900104对该项目及这四类文档读口均HTTP403/code30001，未因拥有review权限获项目访问权。
- 同项目运行A2105573715944271874属于超管900101，运行B2105784759262130178属于市场PM900103：本人events分别HTTP200/code0，74及160事件；另一发起人的events双方均HTTP404/code50001「运行不存在」。超管也不能读市场PM的运行事件，验证了owner守卫，而不是仅在项目成员层被拦。
- 市场PM对A运行cancel及不存在的artifact逻辑编号apply均HTTP404/code50001「运行不存在」。这些请求由已核实同项目不同owner的守卫前置拒绝，不发起运行或改业务权限。随后DB回读A仍SUCCEEDED、事件74、产物1；没有完整全表前后快照，不将此扩成所有表零写入证明。
- 跨项目文档2106050740358258690的versions：超管HTTP200/code0，其余三人HTTP403/code30001。该样本说明指定跨项目访问被拒绝，不扩展为全角色全项目结论。

负责人非成员的正例当前为BLOCKED_TEST_DATA：只读DB查到900102负责ACTIVE产线2104976987897462785、900104负责INACTIVE产线2104977377011433474，但projects.product_line_id直接匹配及products.product_line_id回退匹配均无对应项目。不能把QA项目9140005错误当作这些人的产线项目，也不为验收擅改归属或成员。负责人只读正例、撤职即时回收仍待合法现存业务样本；四个有效凭据不覆盖冻结、离职、首登限权与全部角色。

当前裁决：文档读拒绝、成员读取及同项目跨owner运行隔离已有新包真实HTTP证据，团队负责人只读正例仍待数据，整体PARTIAL。未执行Maven、DDL、业务角色变更或审核写入。
