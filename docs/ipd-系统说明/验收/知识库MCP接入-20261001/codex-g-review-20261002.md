# G 独立质量复核（2026-10-02）

裁决：PARTIAL。五项 Cursor 工作不能合并为五项验收完成。只读复核当前源码与原始保存产物；没有构建、重载、业务写入、批准产物或改主计划。应用 ai-native-sdlc 2.0.0 的独立证据审核方法。

## 五任务的真实边界

| 任务 | 实际存在产物 | 可支持的结论 | 不能支持的结论 |
|---|---|---|---|
| 1 | cursor-task-1-result-20261002.md | 来源身份、完成门和嵌入针对单测通过；84片段缺附件已记录 | 新运行与业务页面已生效、84片段成为有效出处 |
| 2 | cursor-task-2-result-20261002.md | 安全原因码单测，单服务官方客户端重放成功 | 旧v3两次失败根因、17服务全部可用、新包已加载 |
| 3 | cursor-task-3-result-20261002.json | 真实Person负责人非成员跨组读取和撤职回收；拒绝用例业务哈希不变 | 空阶段200表示阶段内容验收；跨组403表示同组成员守卫通过；空阶段400表示权限拒绝 |
| 4 | cursor-task-4-result-20261002.json 与 /tmp/engine-recovery-probe-result.json | 两个串行JVM真实Engine/仓储恢复；未知副作用不进入MailSend；新attempt被下游读取 | 同时持锁双副本碰撞、HTTP恢复、已加载运行包、所有并行分支未写输出 |
| 5 | 提示词存在，result文件在复核时不存在 | 当前timeline-model.ts已有reasonCode/sourceEvidence消费及对应测试源码 | 测试执行通过、真实浏览器新合同显示或全项验收完成 |

任务1/2实际Maven写入主target，任务3写Surefire报告，所谓-Dproject.build.directory隔离不成立。后续候选包必须由唯一协调者串行构建并核源码/产物/加载指纹，不能以这些target为并行隔离证据。

## 优先缺口：阶段写入口未检查项目成员

A等级当前源码：

- ProjectController.java:142–149：submitStageAcceptance只做模块权限和requireInternal。
- StageAcceptanceService.java:106–133：submitStage只验证认证、项目可写、阶段存在、Gate；然后修改stage状态/updateBy并写审计。requireWritableProject（182）只查项目存在、暂停/归档，没有成员检查。
- ProjectService.java:526起：advanceStage使用assertSameGroupIpd，再校验阶段批准与Gate；这里没有project_members检查。StageAcceptanceService.assertApprover（207）只检查非自批和产线负责人，不检查项目成员。

因此任务3的阶段负例不能关闭成员写权限边界。跨组项目403由组守卫拒绝；submitStage的400由阶段缺行拒绝，尚未触达可能写库的路径。精确修复范围应为上述两个Service和对应权限测试，先回原卡确认allowedPaths；真实验收必须用同组负责人非成员、已有阶段行且满足其他前置的fixture，查拒绝前后stage/project/audit业务变化。不要通过放宽Gate或成员关系来造通过。

另有待核一致性：StageAcceptanceService.lineLeaderId（227）只沿project.productId→product.productLineId查负责人，而不是直接project.productLineId。新品待开工无productId时会落无负责人分支；须按目标状态核实，不能仅凭这一静态路径宣称已发生越权。

## 首片动作文档链合同与最早依赖

总画布D节仍规定H1–H3→H4当前包真实运行→H5原概念计划审核返工；不能绕过D直接宣称后续E/F生产就绪。

AiDocumentService当前合同：apply登记GENERATED；review仅GENERATED/REJECTED→REVIEWED；archive仅REVIEWED；reject允许链头GENERATED或REVIEWED→REJECTED并保留意见。审核、动作批准、Gate放行是分开的事。review后AiExecReviewHook只闭合ai_agent_tasks中ai_doc_id匹配且SUCCEEDED的旧执行任务；普通项目智能体产物不能仅凭审核通过推定动作DONE。

原始handoff2-context-v3-business-review-20261002.json可复核：运行2106064774604263425产物DRAFT，documentId=null；该项目已审核文档计数0；正文却声称模板/框架来自已审核文档；MCP两SOURCE为FAILED；裁决DO_NOT_APPLY_OR_APPROVE。因此这份产物不能用于原C02验收或Gate。

最早依赖是新来源身份/完成门与诊断补丁的受控整合、串行候选构建与当前包加载核对，随后真实Person新运行必须带可回查出处，正文不得把知识库模板写成已审核项目文档。技术通过后才回原链头退回意见→关联新尝试→新版本→独立人审→动作批准→Gate；保留原失败与版本证据。

## 尚未验收

1. 同组负责人非成员已有阶段时的提交/推进写边界。
2. 双副本同时锁冲突与进程故障后恢复；探针文件status仍PENDING_VALIDATION，不能把退出0提升为全恢复通过。
3. 新SOURCE合同真实前端显示、失败恢复入口与刷新持久化；任务5未有结果。
4. 17个MCP服务覆盖及84条缺出处资料修复。
5. 当前16039包与新源码一致性；此报告不把任务文件记录的PID当作现查运行态。

证据来源：本目录cursor-task-1/2-result-20261002.md、cursor-task-3/4-result-20261002.json、handoff2-context-v3-business-review-20261002.json；/tmp/engine-recovery-probe-result.json；当前上述Java与前端timeline-model.ts；总画布D节。未读取凭据或完整业务原文。
