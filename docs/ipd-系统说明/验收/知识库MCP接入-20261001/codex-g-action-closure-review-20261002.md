# G 动作能力闭包只读复核

裁决：PARTIAL。没有新建69动作总账或修改原合同/计划/源码/DB。以下是原合同6.5的校正依据及首片下一依赖。

## 69 分类独立复算

现态ActionCatalog.ALL按实际new ActionDef行读取69条。根据八执行器实际supportedActionCodes与生产调用：GenerateExecutor24 + AgentEvidenceExecutor16 + DeepDirectExecutor中C08/L08缺字段2 = 缺能力42；LightDirectExecutor14 + GatePrepExecutor5 = 外部执行19；DeepDirectExecutor D11(certNo/certPassedAt)/V02(far/frr)领域字段落点 = 已接线2；KpiSharedReconcileExecutor K01–04加LC03/LC04对账不代完成 = 待验收6。八执行器集合合计69，和原6.5每条类别比较零差异。独立重算结果仍2/19/42/6，不是照抄；不应因项目智能体能生成文档就增加“已接线”数量。

## 当前技能事实

只读真实ipd_dev：ipd_action_skill_map共69，skill_names非空46。C01=["market-opportunity-research-ipd"]，C02=["competitor-analysis-ipd"]。该查询不证明绑定已由owner正式批准，也不证明当前运行使用这些技能。

classpath capability-packs.json登记45技能，逐文件原始字节SHA检查45/45匹配、无缺文件。C01技能version1.1.0，SHA c116dac4094ed5dea7013a7952943f5482e1632880c40287b4f0130641223c28；C02 version1.1.0，SHA f6f7d41621e897096980e338b4375256626672a25e8060f24d52cefaaf5f0ad2。

ProjectAgentSkillCatalog.load/inspect通过ClassPathSkillRepository读取正文与元数据、校验原始SHA；Planner.plan合并请求技能与本次actionCode DB绑定并冻结模型/toolIds/技能SHA。执行时FrozenProjectAgentSkills再次读同classpath校SHA/version/body，只暴露selected snapshot；Kernel:263-301把它注册为middleware(selectedSkills)，通过onSystemPrompt注入。skillsEnabled(false)关闭SDK默认发现不代表冻结技能未执行。

## C01 确定阻塞

当前manifest仅有market-research/v1一个包，actionCodes只有C02、skills只有competitor-analysis-ipd。CapabilityManifest.pack只读该清单，无当前DB包覆盖实现。Planner.plan:109-116要求本次动作属于包，C01无法以actionCode=C01创建运行，即便DB绑定与技能正文存在。此处是包适用动作缺口，不能写成C01全链可用；不应该去DB晋升新草案或另开运行轨。

原业务事实：外部资源/IPD系统_六阶段标准动作清单_v3.md:81，C01市场PM负责、深管阻断、产物市场调研报告。当前C01 SKILL输入为产品定位/问题空间、一手访谈走访或意向书、二手背景材料，源限本轮用户输入或项目知识片段；输出客户—痛点证据表、可信度、机会结论和缺项。5家一手/1家书面意向只能如实核材料，事实缺失不能模型补齐或代判Gate。

最小候选：在既有market-research/v1包增加C01适用动作和已登记的market-opportunity-research-ipd，更新现包描述为市场/竞品资料研究，不增工具、不改技能正文/hash、不写DB。正反测试覆盖C01 DB技能合并冻结及消费者注入、C02回归、其他未列动作拒绝、显式别包技能拒绝、坏hash不可用且run创建零写。owner批准绑定凭据如需核对，回原卡，不据DB行自授晋升。

## C02 已接线的源码范围

原合同6.5 C02依据写“GenerateExecutor；仍是提示词”不充分：旧aiexec GenerateExecutor确仍走AiGenerationService生成草稿至IN_PROGRESS；用户独立项目智能体路径则是ProjectAgentRunPlanner冻结 → RunExecutor/spec → Kernel按本次model assembler、只读project_knowledge_search及MCP选中工具 → CompletionGate/ArtifactVersion → RunService.applyArtifact →既有AiDocumentService.createGeneratedAuthorized/reviseGeneratedAuthorized → markApplied与rememberDeliverable事务。该轨确有源码接线，仍没有本报告当前HTTP/浏览器/审核读回证据，不能判业务完成。

原C01/C02行类别保持“缺能力”，依据应补“双入口区分；C01能力包未适用；C02项目智能体有检索、冻结技能和文档待审核链源码，待真实来源/定档审核/动作批准/Gate验收”。原6.5“07:15包”等日期是历史事实，须由主协调者用当前不可变JAR/PID/HTTP证据替换，不由本报告推测。总画布原C01/C02首片与69合同指针保持同一轨。

## 下一可开发依赖

优先补C01包漏适用动作的最小合同并跑Planner/冻结Skill正反测试；再以真实Person、本次指定模型、权限内真实访谈/竞品资料分别创建绑定C01/C02的运行，验证sourceEvidence与未取得、生成时预览、apply同ai_documents待审核链、责任人审核退回/新尝试、成员负责人批准及Gate前置。运行SUCCESS和文档存在均不等于动作批准或Gate通过。69其他领域字段缺口不由这个首片自动填齐。

未修改计划、原69分类、数据库或源码；没有独立跑Maven/运行包/浏览器，本报告不能替代A/E/FE当前验收。
