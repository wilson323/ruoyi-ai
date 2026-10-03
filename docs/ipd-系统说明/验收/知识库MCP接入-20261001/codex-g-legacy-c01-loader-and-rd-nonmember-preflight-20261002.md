# G legacy C01／loader／RD非成员只读预检

裁决：legacy C01终态完成取文；动作仍待审核，不是DONE。定向Maven原log /tmp/ipd-codex-action-member-targeted-20261002.log 实际30/0fail/0error/0skip，分布9 ActionWriterMember+6 Registry+15 StageActionService。密码范围测试未跑，真实类名IpdPermissionPasswordScopeTest需主线补，不把它算进30。

真实表ai_agent_tasks：task2106100152656728065 C01 EVENT SUCCEEDED attempt0 next_retry_atNULL ai_doc_id2106100389345497089/createBy0/triggeredByNULL。真实表ai_documents：该doc MARKET_RESEARCH GENERATED，MiniMax-M3/token_prompt833/token_completion4945；C01 IN_PROGRESS/confirmedNULL/actualDoneAtNULL；C05 NOT_STARTED。项目TEAMING/CONCEPT。此task终态支持它不再活跃执行，但不能由DB状态保证JVM没有任何其他socket或异步cleanup。

loader精确只读门：SELECT id,project_id,action_code,status,next_retry_at FROM ai_agent_tasks WHERE del_flag='0' AND status IN ('PENDING','RUNNING','FAILED'); RUNNING直接阻断；PENDING/FAILED有排队或退避重试需协调保留，不静默重载漏调度。同步核SELECT id,project_id,action_code,status FROM ipd_agent_run WHERE del_flag='0' AND status IN ('QUEUED','RUNNING'); 活跃任何真实run均等terminal，不取消。此前查询legacy活动0，ProjectAgent2106103242814394369 RUNNING；随后真实safe回读该run SUCCEEDED，create/startDB2026-10-03 03:25:17，finish03:26:23。DB时区须root核后换算，不能直接套UTC。owner900101，C02，market-research/M3，两tools local+门禁MCP，无previous/target/base，幂等prefix h4-jar-a44e858e-20261002。只能以prefix提示H4，不能断言B发起；归属需B确认。safe证据codex-g-unexpected-active-run-safe-20261002.json。重载前仍应再fresh查询活动状态。

成员API核：ProjectMemberController只POST bind和GET active-list，无项目成员leave/remove。产品线leave/remove不等价projectmember退出；不能用它伪夹具。IProjectMemberService.exitForHandover只有内部交接调用；合法移交需要现RD_PM替代者、原RD本人POST /api/v1/handovers(projectId,role=RD_PM,toPersonId,note)、接手人/{id}/accept，退出旧绑定并建立新绑定，再24小时内合法cancel确认撤销恢复，产生不可删除审计；普通在职者不得onBehalf（仅离职/冻结）。这条较大副作用不优先。

更小路线：DB发现现真实900105（ipd-rd2）RD_PM/group900001/ACTIVE/在职，当前非QA项目成员。不必移除900104，只需root核安全现凭据及真实900105会话，在新加载member补丁后测试同C05 fields/transit403与exactprotected业务hash零变化。原safe.accounts()未含ipd-rd2；当前BLOCKED_CREDENTIAL_EVIDENCE，不改密码/角色/新增Person。可只查已有受保护配置键路线，授权与安全凭据满足后才能实际登录测试，不制造退出或SQL修改。此报告无任何业务mutation。

补充实际dispatchCycle细节：排除trigger_type CHAT，扫描PENDING以及next_retry_atNULL或<=当前时刻的FAILED，然后claim CAS(PENDING/FAILED)→RUNNING。loader保守阻断所有非CHAT PENDING/RUNNING/FAILED（含未来退避）防止遗留待派发静默丢失；单次count仍有随后新任务创建/调度竞态，需root冻结新工作入口并fresh确认，不能称持久fencing。

900105凭据只读检查原config JSON/YAML键/username、原安全accounts()及test/原EVDIR fixture标记，未发现可用安全凭据；没有打印/复制值，没有登录尝试/重置。精确合法拒绝输入与zero-write合同codex-g-rd2-nonmember-negative-preparation-20261002.json，当前BLOCKED_CREDENTIAL_EVIDENCE，且memberpatch未加载绝不发请求。millisecond Date仅为有效拒绝请求输入，不是实际QA已完成事实；意外成功须停，不重放或SQL回退。
