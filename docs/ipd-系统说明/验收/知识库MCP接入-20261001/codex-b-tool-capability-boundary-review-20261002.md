# SDK 工具能力边界独立复核

裁决：当前能力被过度收缩，需按用户最新设计纠正；只读合同审查，不是实现/运行验收。暂停selected-only技能最小候选快照已构造但未编译/测试，不计完成；路径/SHA在JSON。不得把旧“全部禁用”当不可修改业务决策。

当前真实层次：
- Kernel.disableFilesystemTools/ShellTool/MemoryTools/MemoryHooks是显式禁止注册/运行这些SDK能力；disableWorkspaceContext/AtPathExpansion另禁输入自动来源，不能与文件工具本身混同。disableTranscript/Tracing是审计隐私配置，与read_file能力不同。
- toolsConfig.deny(web_fetch/web_search/wait_async_results)令SDK已注册的Web能力被最终ToolFilter移除，不是SDK不支持。官方HarnessAgent2.0.3:2670–2671注册WebTools，2887应用filter；SDK可搜索仍受Tavily配置有无决定，不能声称恢复注册即真实搜索可用。
- Shell官方只有filesystem instanceof AbstractSandboxFilesystem才注册；LocalFilesystemSpec产生LocalFilesystemWithShell，默认ROOTED限制文件路径，但执行直接host sh-c，project(workspace)只定工作目录/文件根，不是OS进程隔离。恢复execute不能把文件root说成sandbox。
- Planner把请求toolIds限定pack.tools且Catalog可用；Catalog仅本地资料检索＋已登记MCP服务READ/SEARCH，W1拒WRITE/EXECUTE/NETWORK。此为业务选择目录，当前不包含官方read_file/write_file/edit_file/grep_files/glob_files/list_files/execute/Web/Memory。
- build后exposed必须subset spec.toolIds，把业务检索选择误当所有SDK能力白名单，单删disable会立即装配失败。
- 业务工具KernelGovernedTool及OwnershipGuardedTool包装发生builder前，SDK默认工具builder后注册，恢复后不会自动受同一项目权限/epoch约束。这是必须一起解决的接线问题。
- Prompt当前“不能访问互联网”与新设计冲突；现classpath技能自身可能是具体动作只读/不联网合同，不能用全局Prompt抹掉具体工具能力，也不能无证据把某技能视为授权任意执行。

官方源精确版本为本机Maven缓存2.0.3 sources JAR，不用另一repo版本替换。LocalFilesystemSpec默认ROOTED、project默认user.dir、inheritEnv=false、projectWritable=false，文件输出默认overlay workspace；仍需核symlink/绝对路径/upperlower边界实际probe。ToolFilter allow对HarnessPlatformTools有特殊保留，deny总优先，不能照搬模型toolIds集合当平台权限。ProjectAgentWorkspace已有project/person/agent目录和segment/symlink检查，但无run目录，多个同项目同人run共享工作区；ROOTED不是数据库权限，prepare本身不能授权项目文件。

最小完整合同建议（未实施）：
1. 明确同一Kernel由可信业务actor/project/run生成CapabilityContext，分别保留SDK基础工具实际集合和用户选择的业务MCP/项目检索集合；业务toolIds仍走pack/数据库授权，官方基础能力不要求前端伪造业务编号。最终exposed校验应核允许执行的二者并集及真实descriptor，不固定只spec.toolIds。
2. 默认能力保有，但在模型看到前对**最终SDK工具集合**绑定项目/person/run scope与调用时ownership/权限检查；固定文件能力作用于该运行工作区和授权项目只读挂载，写入仅草稿/产物目录。不读工程cwd、凭据目录、其他人员项目；审核/状态/定档仍既有业务API。Kernel提供文件来源与引用映射，不把任意MEMORY/文件都认证为项目事实。
3. Shell保持能力且用SDK支持的实际sandbox filesystem/隔离provider，绑定运行目录、最小挂载、无敏感env继承、资源/超时限制和网络边界；不能仅启LocalFilesystem host执行再宣称安全。Web工具保有搜索/抓取能力，可信服务端网络策略需明确私网/凭据/重定向等范围和可用搜索配置，调用带来源/失败审计；不改全局系统env、不假造缺失provider可用。
4. Memory保有读写/整理功能但作用于可信project/person命名空间，个人偏好与事实证据分离；用户胶囊已有跨项目软偏好合同不能授访问权。模型内部推理不落持久正文；memory不能越过来源完成门/人工审批。
5. 冻结技能仍selected-only可确定注入，不等于关闭文件/Shell/Web/Memory；按实际动作合同限定调用范围，而非给所有动作全球不联网禁令。PlanMode/子agent等本次不自动开启，按用户明确覆盖的能力节点独立确认真实SDK支持与权限。

验收需C真实SDK probe核注册名称/工具schema、合法工作区文件读写、跨root/跨person/符号链接拒绝、Shell实际隔离和env、Web可用/不可用/私网边界、Memory跨scope、调用ownership失效无写；再真实模型输入可见工具+受控调用证明，不仅builder工具名。当前本分区只读，没有实现以上策略或新增依赖/DB授权，不把建议政策说成现已生效。


## temp 实施冻结

不止建议：temp四生产类＋一个新聚合正反test＋三资源/manifest已实际实现，精确patch/SHA在codex-b-native-tool-contract-20261002.json/.patch。当前5JUnit全过、javac0，依赖只读current主classes/test-classes离线缓存，temp类与资源优先。不是HEAD闭包或runtime完成。

ToolCatalog统一16原生基础工具与业务工具目录，准确WRITE/EXECUTE/NETWORK能力；NativeToolReadiness.status(id)由实际providers装配，不默认假就绪。Planner创建前缺任一必要native能力明确不可用，不删除功能继续降级；RunPlan.toolIds与原configSnapshot.toolIds同为16＋业务选择，request仍同现DTO和编号，基础工具显式提交亦有效，不要求登记DB第二工具API。CapabilityService.pack必须同步executionToolIds(entry.tools)才能同API完整展示，已交root待精确接线。

Workspace保持prepare签名，新增runWorkingDirectory可信run子目录及authorizedPath，文件和MEMORY.md/memory只授权project/person根、拒绝absolute/..及symlink；测试真写读授权草稿/记忆并拒otherperson/outside。该Java路径合同不能替代官方容器sandbox/挂载权限与实际MemoryTools/hooks消费者验收，C/root负责，不能凭这些5测试说完整能力都可执行。

Prompt移除全局禁网，提供实际文件/Shell/Web/记忆授权范围，不认证memory为业务事实。C01/C02/FTO三冻结skill不联网原句已按用户明确裁决局部恢复；C01/C021.1.1/FTO1.0.1，manifest6行SHA/version同步；未改C02四维、缺名单停止、G1家数/审核、专利法律审批、DB绑定。三updated skill分别经真实currentKernel Model.stream SYSTEM stub收完整正文，没有旧全局禁网，C02默认分支保留；stub无外部模型调用。

官方生产SDK2.0.3缓存API与本地reference项目pom revision2.0.4-SNAPSHOT不同；实现仅用现2.0.3 APIs，没有升级/借新API。C已证基础live默认16名称，本名单不能假称包含所有可选动态Teams/Subagents/PlanMode/技能学习工具；其他disable恢复和新增实际工具应按root/C最终注册集合冻结/授权，不再沿旧永禁规则。Tavily真实provider当前未就绪由C报根因，未编假完整。source/index/主target/DB/全局env未写，旧skills快照保留暂停未测。

## 最新冻结修正：6项测试、业务能力不降级
临时切片编译0，JUnit6/6，无外部模型。CapabilityService现同一API投影执行工具集；pack是否可用仍只按既有业务工具，native提供方真实状态逐条显示。移除先前整个run的native缺项预检，显式选未配置工具仍拒绝；工具不静默删除。原“缺任一native即全部新run拒绝”结论作废。当前16项冻结是已测小切片，C已证明官方默认23项，后续应扩充7项并重新验证，不能当全量完成。主仓未修改，current WIP依赖只读，不是HEAD闭包或加载运行验收。

## 31项目录冻结
C官方2.0.3全能力builder实际31名单已精确纳本目录与Planner/snapshot，未删除未知默认项。临时compile0/JUnit6/6；test显式31无重复、业务API完整投影、隐式缺provider不关闭业务包。新建AGENTS模板消除read-only research，已有custom文件不覆盖。本人同项目旧run路径读取正例保留；该Java路径合同不证明官方resource路由已经防跨run覆盖。完整工具执行、owner及撤权runtime包装待root/C整合，WebSearch仍未真实成功，PARTIAL。

## 最终稳定候选依赖8项验证与审批边界
固定10130运行候选JAR与944a IPD内库，temp抽取489libs，未再借主target。compile0/JUnit8/8。新增官方SkillVisibilityFilter/SkillPromotionGate typed adapter通过真实HarnessAgent.build/call→Model.stream验证当前selected与其他已owner批准fixture都可发现，自填approved草案不可进提示；review保留Approve/Reject且wrong-context/撤权拒绝。审批authority为测试mock，真实owner批准记录入口仍PENDING；不创建第二登记源/DDL，不把永Defer当批准闭环。现main selectedSkills.filter二次筛选仍会拦otherapproved，root必须移除并用权威adapter替代。
Workspace并发baseline发生漂移（作者未认定）；最终temp保留现main祖先symlink防护与新模板，只增authorizedPath/runWorkingDirectory。macOS/var祖先符号链原失败日志保留，fixture canonical授权根后通过。
默认memory hook触发额外Model.stream调用，最初单reference捕获被memory extraction覆盖，原7/8失败保留；捕获全部调用后8/8，未disable任何官方hook。全模型用量/转录隐私必须root后续接线。以上不是WebSearch真实可用或生产全能力完成。
