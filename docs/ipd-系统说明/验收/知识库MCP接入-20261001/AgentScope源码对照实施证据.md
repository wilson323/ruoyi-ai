# AgentScope源码对照实施证据

日期：2026-10-01。状态：源码局部验证通过，运行态PENDING_VALIDATION。

## 变更范围

后端ruoyi-modules/ruoyi-ipd下四个文件：

- src/main/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeSearchTool.java：区分FAILED/PARTIAL/SUCCESS/NO_HIT，零命中且来源失败返回工具错误，SOURCE增加retrievalStatus。
- src/main/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernel.java：复用相同结果契约，并显式使用InMemoryAgentStateStore；保留工作树内其他执行者的MCP改动。
- src/test/java/org/ruoyi/ipd/agent/kernel/ProjectKnowledgeSearchToolTest.java：验证全失败、部分成功、真正无命中。
- src/test/java/org/ruoyi/ipd/agent/kernel/AgentScopeProjectAgentKernelTest.java：验证实际状态存储及经过权限检查的注册工具结果。

实际AgentScope2.0.3的disableSessionPersistence是no-op，不能用该调用宣称关闭持久化。显式内存存储只保证本次选择的短运行不使用默认文件存储，不代表具备跨进程恢复。

## 原始验证

命令（JAVA_HOME=/Users/mac/tools/jdk-17/Contents/Home）：

```sh
/Users/mac/tools/maven/bin/mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=ProjectKnowledgeSearchToolTest,AgentScopeProjectAgentKernelTest,ProjectAgentPromptTest,ProjectKnowledgeVectorSearchTest test
```

最终28 tests，0 failures，0 errors，0 skipped；结束时间2026-10-01T22:03:13-07:00。见[测试原始日志](./AgentScope局部测试.log)。[Harness检查](./Harness检查.log)通过；[反例检查](./Harness反例检查.log)整体退出0，内部五项预期退出序列1/0/1/1/0得到验证。四个文件git diff --check通过。

先前测试失败包括实际失败语义缺口，以及测试误以为SDK原始文本结果立即为SUCCESS、未走权限检查、AgentTool接口静态类型无checkPermissions。随后修正测试以符合真实SDK及生产权限路径，没有删除生产检查以求通过。最终结果以上述完整重跑为准。

## 限制

不包含真实模型、实际MCP tools/call、完整浏览器或故障接管验收。未提交、推送或由本执行者重新加载服务。初次证据PID18686，后续观察16039为PID74001，说明运行环境已被其他操作改变；本轮没有核验新PID加载这四个文件的字节码，不能宣称运行生效。共享工作树既有其他改动，四文件以外不归因于本次修改。
