# G 官方基础能力独立实测

裁决：PARTIAL。安装依赖锁为官方 2.0.3，候选源码 SHA 与 C 原冻结一致。独立 temp javac、三个新 Docker 容器和两个 UUID 卷实测；官方 shutdown 三次、卷清理两次均成功。

已验证原生文件写读、MemorySave 跨冷容器 Get/Search、无害 Shell、工作目录遍历拒绝、宿主仓库不挂载、不同卷文件与记忆不可见。SessionTree 合成记录真实经 session_list/history/search 返回；restricted builder 实际16，全部默认开关不禁用时实际23（完整名单见 JSON），不能把16当全SDK。

真实负例：容器 /etc/passwd 可读；同卷 sibling run 文件可读。容器隔离不能代替运行目录权限。workspaceVolume 参数仅校验名字格式，不能证明调用者拥有该卷；生产需既有 Person/project owner 扩展绑定，禁止用户任意选合法格式的他人卷。

wait_async_results 经 Toolkit.callTool 两种输入均 ERROR：schema validation content null，原始日志保留；通过实际注册 AgentTool.callAsync 能执行，默认 builder 返回无运行后台任务，restricted builder 则 task repository unavailable。此结果不是异步完成正例，也不能绕 Toolkit 闸门作为生产通过。

官方 web_fetch 实际请求 RFC Editor RFC9110 公共一手页，返回 status200 和 IETF 标记，仅记录安全元数据。首次按截断500字符是否含 HTTP Semantics 判据 false 属探针判据不足，保留原日志；按响应status和首段标记核实。web_search 无 TAVILY，明确阻断，不算成功。

当前 IPD Kernel 仍禁 filesystem/shell/memory/hooks/transcript/workspaceContext/atPathExpansion/subagents/dynamicSubagents/dynamicSkills/defaultWorkspaceSkills，skillsEnabled(false)，tools deny Web与wait。Chat Kernel 同类禁用；CodingService另注册自有受控工具且关闭native，不可全局移除后越界。全部默认 builder 对照只是隔离元数据与原生工具实测，无模型、批准、DB业务写、main构建或加载。owner approval/hooks/middleware、异步任务完成及新能力审计/预算/输出隔离仍待生产集成验收。
