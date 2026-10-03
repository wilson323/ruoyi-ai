# 独立 Validator 初轮结果（2026-10-02）

状态：PARTIAL；源码装配不等于当前运行生效。

已真实执行：官方 Harness 门禁正控 EXIT=1，self-red EXIT=0；真实 ipd-market Person 登录及项目 9140005、能力包、运行列表只读回读均 code=0；Docker daemon 与所需镜像可用。

当前监听 PID 50240，旧 JAR SHA256 10130f7efb0ea4cd9d567908a1f6ba51f7d78e015dafec53479b913db02ca0cd；内嵌 AgentScope 2.0.3。证据见 codex-validator-runtime-preflight-20261002.json、codex-validator-person-readonly-20261002.json。

源码消费者已明确启用 memory、plan/task/meta、skill curator/management、tracing、pending-tool recovery、subagent middleware、官方 Docker filesystem、AG-UI bridge、artifact provider。Skill草案 Defer 待 owner；visible skills 以已批准内容/版本/资源全等过滤；sandbox network none 与 pull never，无主机降级。

未闭环：native pause timeout 调度仍缺（Executor 仅预执行意图 scheduleAwaitTimeout）。递归恢复、终态归档、DB rollback、跨JVM Redis恢复仍须统一候选运行验收。

门禁原始失败4项：FoundationToolsTest缺workspace，历史GFullDefaultProbe/GSessionProbe缺userId，ChildPreflight无RuntimeContext。ChildPreflight已逐行复核只做目录与checkpoint读回，零模型call/streamEvents，C5按HarnessAgent引用判执行是误报；禁止加无用import掩盖。

可靠执行路径：冻结当前源码到独立 snapshot，根目录设置 JAVA_HOME=/Users/mac/tools/jdk-17/Contents/Home，使用 /Users/mac/tools/maven/bin/mvn -o -pl ruoyi-admin -am -DskipTests package（仅独立 snapshot 允许 -am）；定向测试必须实际 Surefire计数且Tag(dev)，禁止依赖默认退出码。主协调员以现有运行配置启动不可变候选JAR，核PID/JAR SHA与内嵌模块SHA，再执行 IPD_NATIVE_CANDIDATE_SHA=<实测64位SHA> python3 runtime-native-fullstack-20261002.py create-local/create-mcp/poll <run>；apply-concurrent只用ARTIFACT事件逻辑artifactId，并回读真实审核链。脚本Person、模型、tool编号已现核可用，但单C02仍非全能力验收。

本Validator未修改main、共享target、DB、配置、看板或加载包；只新增本前缀证据文件。
