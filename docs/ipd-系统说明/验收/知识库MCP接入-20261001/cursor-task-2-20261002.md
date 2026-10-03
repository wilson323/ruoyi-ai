# Cursor任务2：MCP真实失败诊断与单轨闭环

这是用户授权分给Cursor并行执行的现有R242子任务，不是新业务计划。先读两仓AGENTS及 /Users/mac/.agents/skills/ai-native-sdlc/SKILL.md；SDK改动读后端agentscope-harness。只认总画布 /Users/mac/.cursor/projects/Users-mac-Documents-ruoyi-ipd-web/canvases/ipd-execution-plan.canvas.tsx 与后端开发计划-看板镜像.md。仅限ruoyi-ai/ruoyi-ipd-web，禁止扩大到IAP/ZK-IPD/其他软件。SDK锁2.0.3，保持ProjectAgent单轨；禁止DDL、生产写/发布、强推、reset/clean、git add -A、全局改配置、第二套知识库/运行接口、业务subagents/dynamicSkills/planmode。
用户已授权计划内代码、本机验证写/模型出站及验收节点提交推送，但本并行分区共享索引和主树，提交/推送/包加载/总画布/镜像/log统一交Codex协调，执行者不要抢这些共享写窗口。不删除工作树。原Codex F/G/E已停写、停Maven，现有补丁保留。每次改代码前按AGENTS列六行缺口。不得覆盖其他分区改动。
并行编译必须用自己的临时源码快照与target；禁止各任务同时写主树Maven target或主前端dist/node_modules。先作只读/独立测试；需主树构建由协调者串行集成。真实验收只接127.0.0.1:16039/15666，不杀其他进程，不打印口令/token/敏感地址。测试DB仅ipd_dev本机13306，凭据读现有本地配置不输出。
当前正式HEAD：BE29b64b97c1729335abe22955b06a7597d68a9fab、FE2797223d4c68490e9eeb9b4c75a718f937ce3c3a（执行前现查）。运行PID13520 immutable SHA729e587af6504d12ebdf20e4320141f688408fafeda15511482931e2ad8e1003；仅已加载上下文隔离，不含本次来源/诊断新补丁。旧全量chat331/aiflow143/ipd3710零失败、24skip不能覆盖新补丁。业务QA project2103659612308828162，v2 doc2106061816428781569仍REJECTED；v3 run2106064774604263425虽SUCCEEDED但来源混称，artifact69b9c84f9fe34eb088c9d0c7920a093e仍DRAFT，禁止为了验收人工批准。
只写本任务列明源码和对应test、原验收目录cursor-task-N-result-20261002.md/json（N为本任务号）。结果含实际diff、命令/退出码/原日志、未验证项及交集文件。没有通过真实验收写PARTIAL/PENDING_VALIDATION。不要再开计划/看板/全局记忆；完成后在本Cursor会话报告，由Codex统一整合。

## 本分区工作

允许路径仅agent/kernel/ProductLineMcpQuery.java、ProductLineMcpTool.java及同名tests；必要ManagedMcpAsyncClient改动先向协调者明确影响，不改任务1来源/完成门文件。
v3实际2次MCP SOURCE FAILED，但官方SDK public query和实际第一问题20s重放均成功（约11.27s），不允许武断远端不可用/中文展示名不匹配。G已加入INITIALIZE/LIST_TOOLS/CALL_TOOL阶段及安全异常类型链/TIMEOUT协议原因码，新补丁未包加载；原catch抹掉旧失败异常无法追溯，不伪造根因。
验证新增诊断能区分握手/列工具/调用/远端isError/超时/取消，保持唯一ManagedMcpAsyncClient/McpClient.async单轨；不手写JSON-RPC、不新增服务、不升级SDK。不得将原error message、endpoint/token回传模型或日志。
利用现SDK20s独立probe和具体schema/问题重放获取可复核证据，失败默认最多2次有依据重试，不乱延长timeout。真实新包由Codex串行加载后再交新run验收；工具失败必须显式FAILED，不伪0命中或挂载数字。输出安全reasonCode字段合同给任务5。
