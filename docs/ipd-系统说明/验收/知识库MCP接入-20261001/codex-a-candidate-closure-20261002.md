# A 候选闭包只读审查

裁决：`PARTIAL`，不批准按本轮32个路径直接提交，也不批准全dirty或全index提交。正式HEAD：`2dd0be6f4f6cea335ce8e779e9d1a3e25935360b`。已有index未改变，无git add/commit、无target读写、无构建。本文件与对应JSON仅为证据。

## 为什么窄文件提交不闭合

当前32个候选种子（B来源/C MCP/D权限/E恢复/F后端状态搜索/G C01及对应测试）经源码声明包、import、同包类、完整限定类引用，递归关联到69个当前变更路径，其中24个不在HEAD；不能把已暂存A/AM当成HEAD已有能力。逐路径状态和当前SHA在JSON；引用边可直接复核。扫描对注释及同名符号保守纳入，不是精确编译分析，也未覆盖反射、资源、SQL/配置与所有删除影响，所以69个路径不是提交授权清单或完整构建证明。

具体必带缺HEAD依赖：C的ProductLineMcpQuery/Tool引用ProductLineMcpCatalog、ProductLineNameMapper；B的Configuration当前引用新Retriever/TextSearch/VectorSearch/FragmentHit/Mapper和DemandCatalogBinder/DemandTriageRun/ProjectAgentRunRecovery；G实际Kernel消费依赖FrozenProjectAgentSkills/ProjectAgentTemporaryStateStore和当前ownership/store/contracts。仅取B本轮SOURCE改动却提交整个Configuration文件会同时带入早先shutdown/需求分拣/ownership装配，因此必须逐块确认原在途归属。

D的StageAcceptanceService与测试本身当前AM、HEAD没有，不是只有4个已有类小补丁。ProjectService的当前全文件差异还有先前业务内容，不能把D非成员守卫授权泛化为允许所有旧差异。F状态查询Store/Test为MM；只git add显式路径会更新这些已有index内容，不能声称原索引毫无变化。应由协调者决定审查接受完整文件，或在独立候选区重建最小基线兼容切片后独立编译；此审查没有做该操作。

## 可明确切分的路径

- E当前5个已有HEAD源/测试文件：`WorkflowEngine.java`、`WorkflowExecutionPlan.java`、`WorkflowStarter.java`、`WorkflowRuntimeService.java`、`WorkflowRuntimeZombieDisposeTest.java`；另`WorkflowEngineLeaseBoundaryTest.java`是未跟踪新增，必须显式包含。共享`JdbcCheckpointSaver.java`已在HEAD且clean；不能因为被依赖而无关改动或重新暂存。
- F前端SOURCE显示闭包为前端仓4文件：`apps/web-antd/src/views/ipd/_shared/ai-agent/timeline-model.ts`、`timeline-model.test.ts`、`run-timeline.vue`、`run-timeline.test.ts`。当前均未暂存M，`apps/web-antd/src/api/ipd/project-agent.ts`clean。后端状态q为Store/Test两路径；其HEAD兼容性仍应以独立候选编译验证，不能混用主dirty成功来断言。
- G清单与测试明确三路径：`capability-packs.json`、`ProjectAgentRunPlannerActionSkillTest.java`、`ProjectAgentSkillCatalogTest.java`；但Planner/Kernel当前MM及新增FrozenSkills不在HEAD，实际消费者测试闭包不能只这三路径。清单登记的45个SKILL.md均可在HEAD找到；技能正文无dirty，不应重复加入或改动。
- 共享MCP客户端、AgentScopeRedisStateStores和根/chat POM经git cat-file核在HEAD，状态clean；本次没有证据要求将其重新加入候选。

## 路径审查矩阵（相对后端仓库根）

M/MM/A/AM/?分别为git status当前原始状态，HEAD有无独立核对；此表不执行暂存。

| 路径 | HEAD | status | 归类 |
|---|---|---|---|
| `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/service/WorkflowRuntimeService.java` | 有 | ` M` | 分区候选 |
| `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowEngine.java` | 有 | ` M` | 分区候选 |
| `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowExecutionPlan.java` | 有 | ` M` | 分区候选 |
| `ruoyi-modules/ruoyi-aiflow/src/main/java/org/ruoyi/workflow/workflow/WorkflowStarter.java` | 有 | ` M` | 分区候选 |
| `ruoyi-modules/ruoyi-aiflow/src/test/java/org/ruoyi/workflow/service/WorkflowRuntimeZombieDisposeTest.java` | 有 | ` M` | 分区候选 |
| `ruoyi-modules/ruoyi-aiflow/src/test/java/org/ruoyi/workflow/workflow/WorkflowEngineLeaseBoundaryTest.java` | 无 | `??` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/catalog/ProductLineMcpCatalog.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/catalog/ProjectAgentModelCatalog.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/catalog/ProjectAgentToolCatalog.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/config/ProjectAgentConfiguration.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/dto/AgentRunCreateReq.java` | 有 | `MM` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java` | 有 | `MM` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/FrozenProjectAgentSkills.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProductLineMcpQuery.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProductLineMcpTool.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentEventSink.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentModelAssembler.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentPrompt.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentRunSpec.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentTemporaryStateStore.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectAgentUsageSink.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeFragmentHit.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeFragmentTextSearch.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeRetriever.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeSearchTool.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeVectorSearch.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/DemandCatalogBinder.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/DemandTriageRun.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGate.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentErrorTexts.java` | 有 | `MM` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentRunExecutor.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentRunHandle.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentRunOwnership.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentRunPlanner.java` | 有 | `MM` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentRunRecovery.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/service/ProjectAgentRunService.java` | 有 | `MM` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/store/AgentRunStore.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/store/ArtifactVersionStore.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/store/MybatisAgentRunStore.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/agent/vo/ProjectAgentViews.java` | 有 | ` M` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Product.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Project.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/Requirement.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/AiDocumentMapper.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/ProductLineNameMapper.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/ProjectKnowledgeFragmentMapper.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/mapper/ProjectMapper.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/seed/ActionCatalog.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiDocEmbeddingService.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiDocumentService.java` | 有 | `MM` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/GateEngine.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ProjectService.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageAcceptanceService.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/impl/DefaultStateMachineGuard.java` | 有 | `M ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/main/resources/ipd-skills/capability-packs.json` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/catalog/ProjectAgentSkillCatalogTest.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProductLineMcpQueryTest.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeFragmentTextSearchTest.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeRetrieverFailureTest.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeSearchToolTest.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeVectorSearchTest.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/service/ProjectAgentCompletionGateTest.java` | 无 | `AM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/service/ProjectAgentRunPlannerActionSkillTest.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/store/MybatisAgentRunStoreTest.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/agent/support/AgentOwnershipTestTransactions.java` | 无 | `A ` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/P122AcceptanceTest.java` | 有 | ` M` | 当前修改依赖 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/ProjectServiceTest.java` | 有 | `MM` | 分区候选 |
| `ruoyi-modules/ruoyi-ipd/src/test/java/org/ruoyi/ipd/service/StageAcceptanceServiceTest.java` | 无 | `AM` | 分区候选 |

## 最终提交门仍需什么

1. 对69个路径逐项确认范围与原在途归属，拒绝把依赖线索当授权。允许路径必须精确到文件/必要hunk，而不是git add -A或整个目录。
2. 保留原主index；如协调者另建临时候选index/包，必须单独隔离且不覆盖主index。当前主dirty Maven绿只证明该磁盘组合，不证明拟提交闭包在HEAD基线成立。
3. 在独立候选快照实际编译与相关测试，并核新文件、资源、依赖声明、删除调用者完整；本报告只读静态检查不能替代fresh checkout验证。
4. 最后由用户明确授权提交/推送；本审查没有该授权且未执行任何Git写操作。

机器证据：`codex-a-candidate-closure-20261002.json`（seed、69path的HEAD/status/SHA、37额外依赖与引用边）。
