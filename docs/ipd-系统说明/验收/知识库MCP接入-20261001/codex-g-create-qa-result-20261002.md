# G 独立同组 QA 夹具真实创建

裁决：夹具创建闭环，权限负例未闭环。窗口已释放；未approve-start、未模型、未完成动作、未定档/审核，无SQL业务写/DDL/角色权限变更。真实API及DB证据codex-g-create-exact-qa-api-evidence-20261002.json。

线2106098453477072898，code qa-member-7d37b33e5fd2，ACTIVE，负责人900102。900102/900103/900104各通过真实申请及超管审核为ACTIVE成员。项目2106098805312069634，名称QA同组非成员独立项目-7d37b33e5fd2；PENDING_START，creator900103，mainGroup900001，产品线精确匹配，productNULL。项目成员只有900103 MARKET_PM和900104 RD_PM，900102与项目同组且线负责人但非项目成员。项目stages/actions各0。

四真实Person登录及GET auth/me均200/code0，id/role/group/ACTIVE精确核实；employmentStatus和等级不在PersonView返回合同，改由DB在职ACTIVE/L3交叉核实。双PM现津贴allowance.L3=2000正数，未改配置。各实际创建/加入/审批/任命端点1～8均200/code0；第9步首次500/code90001，原SQL错误明确target_markets JSON列不接受普通CN String。FE project.ts:197实际JSON.stringify国家数组；root明确授权仅第9步改DTO String为["CN"]，回读同名项目0后一次成功200/code0。保留原错误payload与安全cause，1～8未重做。

初始预检因错误期待PersonView含employmentStatus/level而停，业务步骤0；修正真实DTO后继续。最初URI重复/api/v1返回404，无同code/name行；错误证据codex-g-create-exact-qa-uri-stop-20261002.json保留，修正固定前缀后真实端点一次执行，404不当业务拒绝证据。首次预检证据codex-g-create-exact-qa-preflight-stop-20261002.json也保留。上述两处是工程helper错误，不晋升生产权限缺陷。

所有独立QA数据和审计保留，不删除。SOFTWARE/A及1/0/0/0为显式合成QA输入，CN为JSON国家数组，不作为销售/产品事实。后续开工必须root单独调用，自动AI及阶段/产品效果按合同保留；C05真实完成证据与403负例另步，不以空图或400当权限验收。原dry-run helper targetMarkets已按真实JSON String更正并AST通过，历史已审核请求文件与失败证据不覆盖。
