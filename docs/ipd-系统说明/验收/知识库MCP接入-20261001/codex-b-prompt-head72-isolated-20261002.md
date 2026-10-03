# Prompt 两路径独立候选：PENDING_CLOSURE

仓库：ruoyi-ai；入口：ProjectAgentPrompt.build；服务边界：系统提示组装至现 AgentScopeProjectAgentKernel；表字段：此切片不写库，项目事实字段需 RunSpec/RunService 生产传递；现状与目标：当前两文件不能独立覆盖 HEAD72；证据等级：源码差异、固定快照及实际离线编译 A 级。

实际命令：`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dprofiles.active= -Dgroups= -Dtest=ProjectAgentPromptTest,AgentScopeProjectAgentKernelTest test`。退出1，编译640个生产源时找不到 ProductLineMcpCatalog；11.923秒。测试未进入，执行数0，不能声称7个 PromptTest或 Kernel canary通过。日志及固定SHA见同名JSON。

其他明确闭包缺口：HEAD72 ProjectAgentRunSpec没有 projectFacts/requirementId/catalogAppendix；当前 Prompt 删除内联技能正文，而HEAD Kernel消费者测试 buildAgentExposesOnlySelectedTools 仍要求 ProjectAgentPrompt.build(spec) 含 Skill: competitor-analysis-ipd。当前工作树改用 FrozenProjectAgentSkills，不能借入该 dirty Kernel/Test让本节点变绿。当前 PromptTest 的 createCopiesDatabaseFacts、rejectedHeadCommentIsVisibleOnRunStarted 等也消费未提交 RunService fixture/事实合同。

最小可兼容 HEAD 的建议切片：保留 HEAD 原技能正文注入、RunSpec10参数及现工具判断，只在 PROJECT_KNOWLEDGE_SEARCH 返回来源说明旁增加 SOURCE 身份约束：只有 PROJECT_DOCUMENT 且 REVIEWED 可称已审核文档，知识片段不能冒名；项目上下文不是审核文档，来源摘要/候选不可宣称原始官网核验。对应纯 build(spec) 正反文案测试可用 HEAD 构造器，既有 buildAgentExposesOnlySelectedTools 是实际 selected-skill 组装消费者 canary。此建议未编码/未测试。

MCP选择专属规则依赖当前 ProductLineMcpCatalog；项目事实生产输入规则依赖 RunSpec/RunService 授权事实提取；原生技能迁移依赖 FrozenProjectAgentSkills/Kernel。三者应等待各自闭包，不能把新增固定文案测试冒充生产事实已注入。保持 PENDING_CLOSURE，不提交两文件整包。

无主源码、主target、index、DB或运行写入。隔离依赖使用现有离线 ~/.m2 项目/外部缓存，不证明全 reactor 或已加载运行包。
