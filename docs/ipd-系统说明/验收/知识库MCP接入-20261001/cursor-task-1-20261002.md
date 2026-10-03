# Cursor任务1：来源身份与完成门根因修复

这是用户授权分给Cursor并行执行的现有R242子任务，不是新业务计划。先读两仓AGENTS及 /Users/mac/.agents/skills/ai-native-sdlc/SKILL.md；SDK改动读后端agentscope-harness。只认总画布 /Users/mac/.cursor/projects/Users-mac-Documents-ruoyi-ipd-web/canvases/ipd-execution-plan.canvas.tsx 与后端开发计划-看板镜像.md。仅限ruoyi-ai/ruoyi-ipd-web，禁止扩大到IAP/ZK-IPD/其他软件。SDK锁2.0.3，保持ProjectAgent单轨；禁止DDL、生产写/发布、强推、reset/clean、git add -A、全局改配置、第二套知识库/运行接口、业务subagents/dynamicSkills/planmode。
用户已授权计划内代码、本机验证写/模型出站及验收节点提交推送，但本并行分区共享索引和主树，提交/推送/包加载/总画布/镜像/log统一交Codex协调，执行者不要抢这些共享写窗口。不删除工作树。原Codex F/G/E已停写、停Maven，现有补丁保留。每次改代码前按AGENTS列六行缺口。不得覆盖其他分区改动。
并行编译必须用自己的临时源码快照与target；禁止各任务同时写主树Maven target或主前端dist/node_modules。先作只读/独立测试；需主树构建由协调者串行集成。真实验收只接127.0.0.1:16039/15666，不杀其他进程，不打印口令/token/敏感地址。测试DB仅ipd_dev本机13306，凭据读现有本地配置不输出。
当前正式HEAD：BE29b64b97c1729335abe22955b06a7597d68a9fab、FE2797223d4c68490e9eeb9b4c75a718f937ce3c3a（执行前现查）。运行PID13520 immutable SHA729e587af6504d12ebdf20e4320141f688408fafeda15511482931e2ad8e1003；仅已加载上下文隔离，不含本次来源/诊断新补丁。旧全量chat331/aiflow143/ipd3710零失败、24skip不能覆盖新补丁。业务QA project2103659612308828162，v2 doc2106061816428781569仍REJECTED；v3 run2106064774604263425虽SUCCEEDED但来源混称，artifact69b9c84f9fe34eb088c9d0c7920a093e仍DRAFT，禁止为了验收人工批准。
只写本任务列明源码和对应test、原验收目录cursor-task-N-result-20261002.md/json（N为本任务号）。结果含实际diff、命令/退出码/原日志、未验证项及交集文件。没有通过真实验收写PARTIAL/PENDING_VALIDATION。不要再开计划/看板/全局记忆；完成后在本Cursor会话报告，由Codex统一整合。

## 本分区工作

允许路径：ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/AiDocEmbeddingService.java（先rg核真实路径）、ProjectKnowledgeFragmentMapper.java；agent/kernel下ProjectKnowledgeFragmentHit/TextSearch/VectorSearch/Retriever/SearchTool、AgentScopeProjectAgentKernel的Inline SOURCE metadata；agent/service/ProjectAgentCompletionGate；对应tests。禁止动ProductLineMcpQuery/Tool（任务2独占）。
接续G未验补丁，不重做已完成上下文隔离。RetrievalContext.sources已加CitationSource；当前Gate仍压成title set，E独立确定性probe /tmp/ipd-c-sourcegate-review/GateProbe.java证明：知识template-doc和正式doc123同名模板.md时，输出documentId=template-doc sourceType=PROJECT_DOCUMENT reviewStatus=REVIEWED错误放行；普通“评审通过后才能进入下一阶段”被误拒。修成基于真实来源ID/type的分类检查，覆盖同名、否定/条件说明与实际完成宣称，不能泛词禁用。
向量来源目前只把scope knowledgeId套给vector docId/sourceName，必须核当前attach/fragment权威身份，软删/缺出处明确PARTIAL/FAILED，不能编来源。84条catalog-sample缺出处不能造/删，保留阻断。
AiDocEmbeddingServiceTest17项1fail embedAsyncSkipsWhenRagOff：测试{}预期RAG关闭但生产两键缺失会fallback内置；初轮90过曾因异步未执行假绿。查现合同/生产期望，修真正语义或测试同步等待，不能吞异常/放宽断言。知识/文档来源每hit真实id/type/reviewStatus，知识不填REVIEWED。
验收：新增ID正反控、来源DB条件/向量过期来源、异步确定性回归全通过；保留原失败。输出SOURCE字段合同给任务5。真实新包模型验收依赖Codex加载，不抢运行包；尚未加载不能宣称业务生效。
