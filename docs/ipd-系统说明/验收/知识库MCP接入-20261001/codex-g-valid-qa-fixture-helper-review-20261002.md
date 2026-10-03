# G 合法 QA 夹具只读 helper

裁决：READ_ONLY_INVENTORY_VERIFIED，业务负例未验收。helper 仅固定 SELECT，并以 READ ONLY 事务执行；没有 HTTP、登录、模型、INSERT/UPDATE/DELETE 或开工实现。AST 解析通过；inventory 退出 0；mutation 模式明确拒绝，退出 2。首次 document 查询用了错误历史字段名，退出 1；已按真实 SHOW COLUMNS 更正为 parent_version_id/version_no，未产生写入。

当前 CT3：项目2106072674957529089待开工，main_group_id2096339266916327426；候选900102组900001，当前不是同组，不可宣称同组非成员夹具成立。项目仅900101成员；900102产品线成员已EXITED；产品线2106072393398095874 INACTIVE，负责人900101。文档2106072675242741762为该项目PRD/GENERATED。阶段、动作均0。900101/102/103/104分别为在职ACTIVE真实Person SUPER_ADMIN/GROUP_LEADER/MARKET_PM/RD_PM；只读DB并不证明当前已认证Person会话。唯一启用模型配置MiniMax-M3，其余候选禁用，不启用测试模型。

最小合法路径由root显式调用：核实际Person会话、项目和产品线权限后，POST /api/v1/projects/{id}/approve-start，仅当前线负责人可批（无负责人时超管）。真实审批生成产品、TEAMING项目、CONCEPT阶段、六阶段/目录动作并同步认证项。ProjectBootstrapService尾调用AiExecReviewHook.onBootstrapped，选择可调度未开始动作，AiExecutionTrigger可在afterCommit派发AI。禁止把审批当成零副作用fixture切换。

当前CT3不优先：停用线没有控制器reactivate入口，项目组与候选组不同，缺合法RD_PM成员。建议root使用已核ACTIVE独立QA线或通过POST /api/v1/ipd/product-lines建立独立QA线，再由同一批真实用户经加入审核/负责人规则和POST /api/v1/projects创建独立QA项目，显式mainGroupId900001并配置RD_PM成员；请求DTO与权限仍须root现场核对，不能复用业务项目。不得SQL改批准或DONE。

有效小动作负例可选实例化后的C05：CONCEPT/RD_PM/LIGHT。真实项目RD_PM成员需有真实完成依据，经POST /api/v1/stage-actions/{id}/fields写actualDoneAt及合法transit完成；再按实际目标验证非成员fields/transit/accept，核403及原表零变化。accept在DONE前先返回状态400，不能算成员拒绝覆盖；若只是fields/transit负例，无需伪造完成。阶段推进另需真实阶段与既有Gate/完成证据，不能把动作负例扩成阶段批准闭环。

恢复：仅已授权临时组/成员字段可冲突检查后恢复。批准后产品、阶段、动作、认证、任务、文档、事件、审计应保留；不SQL回退PENDING_START，不删除自动AI产物或审计。失败保留证据，root决定暂停/后续合法业务状态路径。helper contract是后续执行约束，不是已开工或验收结论。

证据：codex-g-valid-qa-fixture-inventory-20261002.json；生产源码ProjectStartService、ProjectBootstrapService、AiExecReviewHook、AiExecutionTrigger、StageActionController、ActionCatalog、ProjectCreateReq。

## 独立 create-qa dry-run 扩展

原helper现支持 --mode create-qa --dry-run，仅读取inventory并产生9步精确API请求；不含HTTP发送实现。省略--dry-run退出2，mutation仍拒绝。AST通过，dry-run退出0。请求原文件codex-g-create-qa-request-dry-run-20261002.json，可由root逐步替换真实返回lineId并读回，不可猜id。

fixtureowner：900103 MARKET_PM创建，900104 RD_PM成员；mainGroup900001与候选900102 GROUP_LEADER真实组一致。900101 SUPER_ADMIN仅管理独立QA空间；创建空间ACTIVE且无leader，然后900102/103/104各自申请并由超管review approve，再由超管指定已ACTIVE的900102负责人。项目创建只绑定900103/104，900102不成为项目成员。全程不改Person角色/全局permission；真实会话需核SaCheckPermission与actor双层权限，不能伪造role。

源码合同：ProductLineSpaceController CreateRequest(code,name)、ReviewRequest(approve)；ProductLineSpaceService创建ACTIVE、审核需ACTIVE Person、appointLeader需ACTIVE空间成员。ProjectCreateReq白名单接受mainGroupId/productLineId/双PM；ProjectService校验SOFTWARE模板、非空targetMarkets、四基准目标、级别A默认系数，并校验创建人ACTIVE空间成员，写PENDING_START后PM成员与PROJECT_CREATE审计。请求1/0/0/0、SOFTWARE/CN/A显式QA合成输入，不是商业事实；PM实际等级和现allowance.Lx正值是创建前阻断项，helper已核等级合法但未替代运行配置/API验证。没有偷偷带入status/currentStage/id/code，也没有伪DONE。

开工单独root gate：负责人900102在读回同组、非项目成员及空间/项目归属后，显式POST /api/v1/projects/{returnedProjectId}/approve-start。此模式绝不自动执行，AI调度/产品/阶段/认证影响按前述审计保留。创建本身也有状态机postCommit及审计，不能声称零副作用。停用独立线/离开或移除成员是另行合法API补偿；保留新项目/成员历史/审计，不SQL清理。C05完成证据和403负例另步，空阶段或400均不算权限验收。

观察helper SHA41db9915ea1a2f3ddd7345f3d0b4862fa321368d13cd60675d3a634413cde50a。首次候选组字段name只读查询失败，按SHOW COLUMNS改group_name后成功，无写入。
