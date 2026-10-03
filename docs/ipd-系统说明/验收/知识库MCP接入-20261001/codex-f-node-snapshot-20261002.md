# F 隔离快照独立复核

四候选文件snapshot/main/冻结hash完全一致，原json路径复用。离线ignore-scripts frozen安装日志Done43.1秒/pnpm10.14.0。只读检查37个node_modules目录538链接，未发现指向主dirty源码；实际@vben/hooks/stores/vite-config链接均为snapshot相对路径。28文件相对导入闭包与主目录一致，注释中两个伪import锚已剔除，不作缺依赖结论；这不是编译器完整graph。精简哈希及链接检查 codex-f-node-snapshot-independent-20261002.json。

ignore-scripts跳过原postinstall stub，15 workspace main dist不存在。timeline-model仅type import本地API，运行不依赖@vben；组件测试importOriginal(project-agent)→http→ipd-auth→hooks/stores barrels及core依赖，development条件可指src，不能仅main缺dist断言targeted必失败。typecheck turbo-run、vite-config/node-utils若走default dist应由A在隔离目录stub，禁止借主源dist伪隔离。

A实际targeted20个model通过，component suite在safe-markdown.ts无法resolve dompurify而未collect（原/tmp/ipd-codex-f-isolated-tests）。独立核HEAD safe-markdown已import dompurify和marked，HEAD app package没直接声明，故不是本次SOURCE逻辑失败；snapshot .pnpm两包均存在但app direct links缺。当前app package对HEAD只有dompurify3.4.16/marked16.4.2两个新增。HEAD lock已含两版package/snapshot与trusted-types optional；只缺apps/web-antd importer对应specifier/version，共6行。

建议必要支持切片仅package两行+HEAD lock importer六行，A纳候选事务index后在隔离snapshot offline frozen install生成direct links。当前主lock与HEAD差220行，混root jsdom26.1.0/override、peer snapshot及传递依赖，不能整锁文件照抄为本片。未改package/lock/index/source，未pnpm/Maven/commit/target；真实候选测试裁决由A运行。

## 隔离类型退出2分层

/tmp/ipd-codex-f-isolated-types-20261002.log仅三诊断：safe-markdown.ts:3/4的dompurify/marked TS2307、:8 renderer.html参数text TS7031。前两为HEAD既有import缺app direct dependency；已知marked16.4.2 lib/marked.d.ts:176声明html({text}:Tokens.HTML|Tokens.Tag)，故第三高度符合无法解析marked导致上下文类型丢失的次生错误，先装最小支持切片复跑，不加any/绕type。日志没有新增真实类型错误或缺dist诊断，不能把此前观察到缺dist当本次退出2根因。37 scope实际仅web-antd有typecheck task，root直接turbo run typecheck而非turbo-run脚本。

root postinstall实际pnpm -r run stub --if-present，ignore-scripts会跳它。精确7包有stub：internal/node-utils、vite-config、tailwind-config、lint-configs/eslint-config、scripts/turbo-run、vsh、packages/@core/base/shared，全部pnpm unbuild --stub。已读7 configs：clean:true/declaration:true及本包src entries；tailwind多postcss entry/emitCJS，shared多个src entry，没有业务启动/DB/网络hook配置。已读本机unbuild3.6.1 implementation，clean会删除本包outDir(dist)，stub读取源并写jiti代理与声明，因此有隔离dist写/清理副作用；不应在主目录执行。

现typecheck无需要stub的证据；等最小dep修后实际结果决定。若后续隔离应用build因vite-config默认dist缺失败，仅在隔离目录对其node-utils依赖与vite-config定向stub，再按真实缺项扩tailwind等，不全面复制主dirty dist或依赖。此复核未运行任何脚本，不写source/target/index。

## 独立候选构建开始

读取supported-tests原日志2文件41测试通过（timeline-model20，run-timeline21）；supported-types原日志1实际task成功，scope37不冒称37task均执行。六路径边界是原4SOURCE候选+app package依赖2行+lock importer6行，snapshot六hash与原冻结一致；未纳主root jsdom或其他dirty修改。

仅隔离snapshot pnpm run build:antd首跑exit1：@vben-core/design vite.config无法解析@vben/vite-config default dist，确认为ignore-scripts跳postinstall支持缺失。按真实配置依赖（vite-config用node-utils，app postcss/tailwind import tailwind-config）仅snapshot三包定向pnpm --filter @vben/node-utils --filter @vben/vite-config --filter @vben/tailwind-config run stub，exit0；原日志/tmp/ipd-codex-f-isolated-build-stubs-20261002.log。没有执行其余四stub、复制主dist、改main/index/package/lock。第二build原日志/tmp/ipd-codex-f-isolated-build-supported-20261002.log，待最终退出。已读取本轮tabs-ui/dist/use-tabs-view-scroll.d.ts，scrollbarRef为Vue.Ref<{ $el: HTMLElement }|null,...>，未降any；其他handleScrollAt原any不能混同该字段。

最终隔离build exit0，11实际任务全部成功、无缓存，2m1.211s；Vite app构建56.20s，app dist/index.html真实存在。整日志TS诊断计数0。scrollbarRef声明区实际含HTMLElement且不含any，声明hash与六候选hash后验见codex-f-node-snapshot-build-result-20261002.json。先失败/必要stub/成功日志全部保留，不只报exit0。仅候选工程验证闭环，不代表已纳主index/已commit/16039运行更新/浏览器验收；主仓源码索引package/lock未修改。

## 正式15666实际供应与新运行来源只读复核

真实Person登录后GET run2106092487658545153事件，仅筛SOURCE，不输出token/内部思考。实际只有1条SOURCE(seq8)FAILED/INITIALIZE/PROTOCOL_OR_TRANSPORT，内含6条安全frames；不是6次失败调用。6帧分别McpJsonInternal的mapper初始化方法、McpJsonMapper#getDefault、HTTP transport Builder#build及本项目ManagedMcpAsyncClient#streamableHttp，均属于当前9类精确白名单，method合法且有限，line>=-1、总6<=8。未出现原异常message/URL/Bearer/token/key文本于来源preview；只保存preview hash。首次检查沿用旧3类清单误判frameSafe=false，已重新读取实际Query9类纠正，最终allFramesSafe=true，不把版本漂移假报泄漏。

15666 GET真实供应timeline-model transformed模块HTTP200；模块SHA39ae5ee4a94c6a8a53dc7213b6b5d7d5e57da0edb85fd114328751c03191196c。Vite inline sourcemap sourcesContent独立SHA精确等于冻结源码b94a72b6ad50ea6191a2eb3a28d7d01edf402004e5839d2729a4785b22de3567，证明本轮供应该候选映射。实际模块含own-property guard、「知识库服务暂未提供查询能力」与「无权查看」。本运行是PROTOCOL_OR_TRANSPORT，正常应显示「没有查成 / 连接没有完成」，不能拿capmissing白话冒充本运行原因。SDK frames不进入timeline VM字段；已通过的20model+21component测试是渲染合同证据。

浏览器能力可用但现Chrome受控tabs为空，没有复用合法已登录历史页；未为验证创建新run或注入会话。当前结论止于真实HTTP事件+Vite实际供应+既有渲染测试，未覆盖真实浏览器历史DOM，也没有本次UNAUTHORIZED来源事件样本；无权文案仍只有既有测试及正式供应映射。证据codex-f-live-source-readonly-20261002.json。没有定档/审核/出站模型/数据改动。
