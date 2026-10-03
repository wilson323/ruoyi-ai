# 独立 Validator 初轮结果（2026-10-02）

状态：PARTIAL；源码装配不等于当前运行生效。

已真实执行：官方 Harness 门禁正控 EXIT=1，self-red EXIT=0；真实 ipd-market Person 登录及项目 9140005、能力包、运行列表只读回读均 code=0；Docker daemon 与所需镜像可用。

当前监听 PID 50240，旧 JAR SHA256 10130f7efb0ea4cd9d567908a1f6ba51f7d78e015dafec53479b913db02ca0cd；内嵌 AgentScope 2.0.3。证据见 codex-validator-runtime-preflight-20261002.json、codex-validator-person-readonly-20261002.json。

源码消费者已明确启用 memory、plan/task/meta、skill curator/management、tracing、pending-tool recovery、subagent middleware、官方 Docker filesystem、AG-UI bridge、artifact provider。Skill草案 Defer 待 owner；visible skills 以已批准内容/版本/资源全等过滤；sandbox network none 与 pull never，无主机降级。

未闭环：native pause timeout 调度仍缺（Executor 仅预执行意图 scheduleAwaitTimeout）。递归恢复、终态归档、DB rollback、跨JVM Redis恢复仍须统一候选运行验收。

门禁原始失败4项：FoundationToolsTest缺workspace，历史GFullDefaultProbe/GSessionProbe缺userId，ChildPreflight无RuntimeContext。ChildPreflight已逐行复核只做目录与checkpoint读回，零模型call/streamEvents，C5按HarnessAgent引用判执行是误报；禁止加无用import掩盖。

可靠执行路径：冻结当前源码到独立 snapshot，根目录设置 JAVA_HOME=/Users/mac/tools/jdk-17/Contents/Home，使用 /Users/mac/tools/maven/bin/mvn -o -pl ruoyi-admin -am -DskipTests package（仅独立 snapshot 允许 -am）；定向测试必须实际 Surefire计数且Tag(dev)，禁止依赖默认退出码。主协调员以现有运行配置启动不可变候选JAR，核PID/JAR SHA与内嵌模块SHA，再执行 IPD_NATIVE_CANDIDATE_SHA=<实测64位SHA> python3 runtime-native-fullstack-20261002.py create-local/create-mcp/poll <run>；apply-concurrent只用ARTIFACT事件逻辑artifactId，并回读真实审核链。脚本Person、模型、tool编号已现核可用，但单C02仍非全能力验收。

本Validator未修改main、共享target、DB、配置、看板或加载包；只新增本前缀证据文件。


## 后续实施与修正

原批准等待期限缺口描述已撤回：定向工程合同/ADR未建立人工等待期限，只有历史代码注释；不擅自增加超时失败。根协调员复核现owned intent会pauseForApproval并释放handle。

门禁修复已实施：默认范围仅编译模块，C5识别SDK actor实际执行接收者而非receipt.call()访问器；五项定向正反控与原self-red均通过。原契约门禁仍保留FoundationToolsTest缺workspace真红，未篡改输出。运行时技能镜像由协调员同步。

官方Artifact生产窄链已落main：仅评审后的Delivery/Origin/Access三类及专用Tag(dev)测试；在原RunService加入必须装配的apply来源guard/download权限消费者，Controller加入经Person权限的版本下载。Origin沿用原ARTIFACT事件，不新增表或文档轨；普通文本必须有原事件绑定，二进制只下载，native来源必须验证持久事件/bytes/sidecar/hash。新增原逻辑版本/title被篡改拒绝用例。

独立官方2.0.4-SNAPSHOT classpath编译和8/8 JUnit通过；详见codex-validator-artifact-eight-20261002.json与原始日志。该证据只覆盖新3类确定性契约（map事务double），不证明真实MySQL rollback、Spring Config消费者、原RunService/Controller统一编译或当前生产2.0.3包加载。当前任务保持PARTIAL，协调员完成生产factory与统一candidate验收。


## 最终授权窗口结果

前端沿原Person authenticatedRequest换票/错误包络增加二进制下载支持；只接受成功octet-stream，错误JSON和code0伪附件拒绝。字符串版本ID精确透传，Panel只对native交付事件展示下载按钮，普通预览保留。最终146/146测试、真实typecheck cache miss与build:antd均通过；构建TS诊断0、dist/index存在、scrollbarRef声明未any。证据codex-validator-fe-download-result-20261002.json。

独立评审发现生产Factory裸Person/run身份与四维scope冲突、reserve在事务外两处问题，协调员已修为可信复合scope和原事务afterCompletion回调。新增ProductionArtifactsTransactionTest3项，在真实2.0.3依赖与当前main编译类上通过：复合scope/裸身份拒绝、reserve授权在原事务、成功claim只消费一次、commit failure版本/事件/bytes/sidecar全回滚且claim废止。数据库仍为double，无真库完成声明。

ApplyStatusTest20项旧fixture已补合法run/tenant/正文SHA/原ARTIFACT事件与真实只读Delivery+Origin guard；CAS冲突仍要求rollback且nevercommit，新增顺序约束保证来源只在文档写前读一次。独立组合23/23通过；详见codex-validator-production-apply203-result-20261002.json与final compile/tests原始日志。
