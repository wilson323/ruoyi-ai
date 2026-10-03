# Cursor任务5：项目智能体前端单轨与真实浏览器验收

这是用户授权分给Cursor并行执行的现有R242子任务，不是新业务计划。先读两仓AGENTS及 /Users/mac/.agents/skills/ai-native-sdlc/SKILL.md；SDK改动读后端agentscope-harness。只认总画布 /Users/mac/.cursor/projects/Users-mac-Documents-ruoyi-ipd-web/canvases/ipd-execution-plan.canvas.tsx 与后端开发计划-看板镜像.md。仅限ruoyi-ai/ruoyi-ipd-web，禁止扩大到IAP/ZK-IPD/其他软件。SDK锁2.0.3，保持ProjectAgent单轨；禁止DDL、生产写/发布、强推、reset/clean、git add -A、全局改配置、第二套知识库/运行接口、业务subagents/dynamicSkills/planmode。
用户已授权计划内代码、本机验证写/模型出站及验收节点提交推送，但本并行分区共享索引和主树，提交/推送/包加载/总画布/镜像/log统一交Codex协调，执行者不要抢这些共享写窗口。不删除工作树。原Codex F/G/E已停写、停Maven，现有补丁保留。每次改代码前按AGENTS列六行缺口。不得覆盖其他分区改动。
并行编译必须用自己的临时源码快照与target；禁止各任务同时写主树Maven target或主前端dist/node_modules。先作只读/独立测试；需主树构建由协调者串行集成。真实验收只接127.0.0.1:16039/15666，不杀其他进程，不打印口令/token/敏感地址。测试DB仅ipd_dev本机13306，凭据读现有本地配置不输出。
当前正式HEAD：BE29b64b97c1729335abe22955b06a7597d68a9fab、FE2797223d4c68490e9eeb9b4c75a718f937ce3c3a（执行前现查）。运行PID13520 immutable SHA729e587af6504d12ebdf20e4320141f688408fafeda15511482931e2ad8e1003；仅已加载上下文隔离，不含本次来源/诊断新补丁。旧全量chat331/aiflow143/ipd3710零失败、24skip不能覆盖新补丁。业务QA project2103659612308828162，v2 doc2106061816428781569仍REJECTED；v3 run2106064774604263425虽SUCCEEDED但来源混称，artifact69b9c84f9fe34eb088c9d0c7920a093e仍DRAFT，禁止为了验收人工批准。
只写本任务列明源码和对应test、原验收目录cursor-task-N-result-20261002.md/json（N为本任务号）。结果含实际diff、命令/退出码/原日志、未验证项及交集文件。没有通过真实验收写PARTIAL/PENDING_VALIDATION。不要再开计划/看板/全局记忆；完成后在本Cursor会话报告，由Codex统一整合。

## 本分区工作

允许路径仅前端apps/web-antd/src/views/ipd/_shared/ai-agent/**及对应tests、api/ipd/project-agent.ts、ai-document.ts必要字段适配；不改总路由/产品目录/阶段审批/框架身份。
正式26文件节点2797223已独立HEAD候选112tests/type/build11任务过，95声明TS诊断0，scrollbarRef未any；不要重写布局或重复修复。旧任务wt timeline增量已全部在正式实现，不能覆盖最新toolCallId、ERROR/FAILED/待核实识别。工作树清理由Codex，勿删。
在127.0.0.1:15666真实Person核：刷新后历史/搜索/产物保留，原退回“根据退回意见再做”三元组、故障后普通发送不继承返工关联、显式重试正确新尝试、SOURCE PARTIAL/FAILED可区分无命中，工具调用关联/取消/断浏览器后台继续，正文与思考折叠、生成预览当时显示。不把模型/工具失败显示为业务已完成；区分运行成功与动作批准，不能擅改产品文案需求。
任务1/2的新sourceEvidence/reasonCode给到后可只做必要兼容显示与正反tests；未加载新包的浏览器结果只记录PID13520基线，不能写新修已生效。禁止第二条发送轨/副驾回落/加新依赖；模型/技能/工具仍加号内，编号仍能力包toolIds。真实产物不合格不批准，不为了技术绿推进C02/Gate。
按现三栏/六阶段和用户21st收藏交互验收，截图在原证据目录，实际浏览器+HTTP原结果记录；最后复核types/vitest/build需要隔离dist并由协调者确认主构建窗口。
