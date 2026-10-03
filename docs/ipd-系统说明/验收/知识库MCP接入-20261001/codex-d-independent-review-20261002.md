# D 分区独立 Validator 复核

裁决：当前 D 补丁编译失败，FAILED；成员守卫方向认可，须移除不存在的Project字段引用并重跑编译。未证明真实HTTP、库或已加载包验收。本报告只读审查 StageAcceptanceService/ProjectService 当前补丁、测试、Controller、IpdIdorGuard 与 ProjectMember；未修改源码或运行 Maven/主target。

## 认可范围

- submitStage 在加载可写项目后、阶段查询/Gate/更新前要求成员或超管；advanceStage 同样在状态判断与写前守卫，同组 GROUP_LEADER 不再仅凭组织归属写阶段。
- assertApprover 增加成员守卫后仍要求提交人不得自批、已有负责人须该负责人审批。SUPER_ADMIN 只豁免成员查询，不能跳过已有负责人规则；没有负责人时保留超管审批，符合本会话明确业务约束。
- 撤回原“project.productLineId优先”认可：独立复核此前只看调用与测试，未核Project域符号，属于审查遗漏。A正式编译发现Project.getProductLineId不存在；本次直接读取Project.java及rg确认仅有productId，没有productLineId。StageAcceptanceService:245的getter和Test:159的setter引用均不可编译，不能升级为现有字段合同，也不允许为此擅增Schema。D已受命去掉这些引用，修复后待重新编译。
- 复用 IpdIdorGuard 的当前 tenant + project/person + exit_date IS NULL 条件。ProjectMember.delFlag 有 @TableLogic，守卫没有显式追加del_flag不构成缺漏。
- StageAcceptanceService 保持原 RequiredArgsConstructor 签名，以必需 @Autowired setter 注入成员Mapper；null在手工构造场景非超管fail-closed，生产缺Bean启动失败。当前仓 ProjectService/BonusPoolService 已有同类setter兼容风格。本次不会让Mapper缺失降为许可，也没有为测试开放旁路。

## 具体残留风险

1. 历史 P2 读取旁路：StageAcceptanceService.acceptAction 在 confirmedBy 等于 actor.id 时直接返回 action，然后才会执行成员/负责人核查。StageActionController:92-99 仅模块权限及 requireInternal，未另做项目成员guard。因此原批准人退组或撤职后重试同一个已批准action可回读该动作实体。该分支确实不更新库，不能算新增写权限漏洞；也不能据此声称所有入口退组后均已禁止访问。D报告已披露幂等前置但未将读取权限风险写明，建议本节点决定是否将requireProjectOperator移至幂等前并保留同人幂等结果。
2. 既有跨组限制：advanceStage 新成员guard之后仍调用 assertSameGroupIpd，产线负责人若已入项目但与mainGroupId不同仍会被拒。该规则不是D引入，不能自行删除。真实审批正例必须绑定当前业务合同允许的组/成员关系；不能以一个同组fixture宣布所有负责人路径闭环。
3. tests 默认memberMapper.selectCount=1，断言实际守卫查询谓词仍依赖既有guard测试/HTTP。新增非成员负例验证零update与未触达Gate，方向正确，但未独立跑这些测试；A当前串行Maven结果及真Person HTTP/DB回读必须另关联。

## 验收要求

A在新包加载后验证已有阶段的同组非成员submit/advance与未确认accept均403/code30001且前后DB/审计零写；当前Project域没有productLineId，无产品时直接产品线负责人路径没有实现证据，不纳入本补丁可验收正例；退组原批准人重试已确认accept作为上述读取风险单独裁决。未以空阶段400代替成员负例。

## 新包真实Person同组非成员QA验收

部分闭环。仅旧QA project2106072674957529089/line2106072393398095874/document2106072675242741762及其900102 line-member旧行做最小暂态调整，所有原行已完整保存原值，finally恢复。没有新建stage/action、删除数据/DDL/批准业务/触其他项目。实查Person和GET/auth/me均900102/ipd-leader/GROUP_LEADER/group900001；项目原主组2096339266916327426改至同组900001，line临时ACTIVE/leader900102、其旧lineMember临时ACTIVE，仍无900102项目成员。

真实Person GET项目及阶段均200/code0；文档正确GET列表200/code0且包含指定文档。POST advance-stage和stage-acceptance均403/code30001、明确「非项目成员，无权访问」，同组已排除旧跨组拒绝，submit成员guard在空阶段查找前发生，不再空阶段400。前后完整项目/阶段/动作/文档/成员JSON哈希均2d66edb6d238430afdb471bdefd6f21f897039ca82baecaa8a5e62ae2cd945f0，未产生阶段/签署/正文变化。

仅撤回leader至原900101且仍同组/lineMemberACTIVE时，项目/阶段/文档列表均403/code30001，负责人只读权限即时收回。首次误GET无实现的文档单体路由404，已明确剔除权限判据并补正确列表；不将404伪造读拒绝。凭据/token不进输出或证据，Person登录成功不是框架admin代替。

局限：原QA阶段/动作仍零行，未获创建后删除夹具授权；本范围无法做有效stage/action的小阶段accept负例，不拿假action400或空stage当通过。因此大阶段推进及submit成员边界真实HTTP部分闭环，有效阶段/小动作approve全旅程仍未闭环。

证据codex-d-real-samegroup-nonmember-20261002.json含exact QA原值/安全会话/HTTP结果/恢复：first original与恢复全行sha均6e2625e81acaf33ae57f1242528ed36e258e94c056ed1f177001c58e01c74feb，second finally改动字段全部与备份相同。原PENDING_START/原mainGroup/line INACTIVE leader900101/member EXITED均已恢复；QA写窗口释放，未Maven/target/index/source。
