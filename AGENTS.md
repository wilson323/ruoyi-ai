<!-- ORIGIN: 兄弟会话在 2026-09-21 提交的部分内容由 R157-E-A 按 R25 软化三步法原样入库 -->
# AGENTS.md — ruoyi-ai

只收录「读代码推不出来、但直接决定任务成败」的雷区。架构 / 目录 / 完整命令见同目录 `CLAUDE.md`；二开方向总览见 `README-IPD-OVERRIDE.md`（优先级高于根 `README.md`）。

一句话定位：RuoYi-AI（Spring Boot 3.5.8 + Java 17 + AgentScope 的 Maven 多模块**后端**仓库，父 POM revision 3.1.0）正在二开改造为「IPD 产品经理管理系统」。前端在独立仓库（ruoyi-web / ruoyi-admin 前端），别在这里找 Vue 代码。

## 铁律 · 所有任务执行禁止推测，必须有据可依（2026-10-07 owner 明令）

**这是准入条件，不是建议。任何结论出口前先问：「我这条是从哪看来的？」——答不上来就先去查。**

证据等级（由硬到软，只认这些）：
1. **实跑一次**（跑命令、看返回值；改坏再复原，确认真的会红）
2. **真库回读**（`COUNT(*)` / `information_schema`；**不用** `table_rows` 估算值，**不信**文件名的「已执行」）
3. **读当前工作树的源码**并给 文件:行号（不是记忆、不是上一轮读的版本）
4. **需求原文行号**（`docs/ipd-系统说明/外部资源/` 下的主 Prompt / 决策表 / 验收清单）
5. **文档**——只能当线索，**不能当事实**（本仓已实证多处「文档自称 X、实为 Y」）

**明令禁止**（均为本轮实际犯过）：
- 「应该是」「大概是」「按惯例」出现在结论里
- 拿 A 处的证据支撑 B 处的结论（读过不相干的文件就说「已核实」）
- **凭想象写符号**：字段名 / 方法名 / 构造签名 / 配置键 / action 名 / commit SHA。写前先 grep。
- **拿局部事实冒充全貌**：只读了种子常量就断言「库里没有」，不查真实库
- **把「没搜到」说成「不存在」**；把「计算路径错」推断成「数据已坏」
- 拿一次旧读数当现状（本仓环境持续在动：兄弟会话在提交、定时任务在写）

**报告要求**：每条结论带可复现命令 / 文件:行号 / 实跑输出摘要；三者皆无者标「**未取证**」并说明为何查不到。
「查不到」是合法答案，「猜一个像样的答案」不是。子智能体报告同样适用，标不出证据类型的一律按未取证处理。

展开版见 `docs/ipd-系统说明/多会话并发工程-证据与自证工作规范-20261002.md` §规矩零。

## 工程任务的防复发入口

后续工程任务先读正式前端 `.harness/skills/ipd-engineering-feedback/SKILL.md`，运行 `python3 /Users/mac/Documents/ruoyi-ipd-web/scripts/engineering_harness.py --root "$PWD" intake`。本仓 `bash .harness/verify.sh governance <原事项编号>` 复用同一工程验证器，保存真实失败与待反思问题，同事项回归后只启用固定工程检查项；不承担Java打包、已加载运行态或业务验收。原总画布/看板仍唯一，原官方AgentScope能力及业务owner/批准不变；未证明全IDE调用不可绕过；2026-10-07 owner 明令：每次任务执行结束必须整合工作树——本任务产物（代码、文档、台账、log）commit 并 push 到固定远程分支（`origin/baseline/pre-teardown`），推送目标仍限 owner 指定私有仓，兄弟会话在途文件不卷入、保留工作树并在报告中列明。

## 当前任务范围与证据

- 2026-10-02 最新用户目标优先：AgentScope 官方能力全量启用、禁止禁用、禁止降级，以 `/Users/mac/Documents/agentscope-java` 真实源码为参考确保完整应用。此指令覆盖 ADR-0077“按需关闭”及下文历史禁开口径；业务闸门（技能 owner 拍板、审批流、权限、文档审核、动作批准与 Gate）保留并经官方扩展点挂载。能力启用不授予具体业务操作权限；源码版本差异须现核，目标不等于运行验收，禁止静默回退或空实现。


- 2026-10-06 最终分支铁律（owner 明令）：后续全部工作固定在当前分支收口，此为最终分支——本仓（后端 ruoyi-ai）为 `baseline/pre-teardown`，前端仓（ruoyi-ipd-web）为 `teardown/incentive-removal`。不新建分支、不切换分支、不向 `main` 或其他分支合并/变基作为「最终交付」；授权推送时只推各自同名远程分支（`origin/baseline/pre-teardown` / `origin/teardown/incentive-removal`）。本条落实 `CLAUDE.md` 推送铁律二的分支固定；推送授权已由 2026-10-07 规则更新（任务结束必须整合工作树提交推送，见上文工程入口条），等逐次授权与「不主动建议推送」口径废除；推送仍只落固定分支。ZK-IPD 不是 git 仓库，无分支约束。
- IPD 执行顺序和下一刀只认 `/Users/mac/.cursor/projects/Users-mac-Documents-ruoyi-ipd-web/canvases/ipd-execution-plan.canvas.tsx`；本地看板与镜像登记同一计划的事项、allowedPaths 和验收，分节仅作细化。冲突先核总画布，不从旧卡或分节另开执行轨。
- 开始或接续任务先明确本次目标、所属仓库、允许路径和证据来源。用户对上一段话的纠正不是新的业务需求；无关设备、登录项、软件安全调查不推导 IPD 缺陷。现有 Work Buddy 产品资料引用只证明资料来源，不证明软件运行依赖或安全关系；CodeBuddy 等开发工具名称也不证明业务依赖。用户明确扩大任务范围时按本次目标处理。
- 历史会话、记忆、画布和卡面只提供查证线索。PID、包版本、端口、数据库状态、已加载与验收结果必须现查；有时间戳也不代表当前仍有效。未回读用待验证，禁止用一个样本扩成全项目结论。
- 本规则约束工程执行，不给项目智能体追加业务提示词，不修改业务权限；没有证据不把工程助手的错误归因于产品代码。
- 计划或指令变更后运行 `python3 /Users/mac/Documents/ruoyi-ipd-web/scripts/check-ipd-plan-context.py`；它只检查这些入口的已知冲突，不能证明模型永不误判或业务已验收。

## 范围与路由

- `docs/开发说明/**` 是产品设计事实源（"圣经"，G-04）：产品业务决策不可改；owner 已授权**勘误级更新**（错字 / 失效引用 / 数字对齐），勘误须在 `docs/ipd-系统说明/log.md` 登记；工程修复与补充文档一律写到 `docs/ipd-系统说明/` 下。
- IPD 事项走本地看板及 `docs/ipd-系统说明/开发计划-看板镜像.md`。`docs/agents/issue-tracker-github.md` 是历史 GitHub 操作参考，仅用户明确要求外部发布时适用；领域文档地图 → `docs/agents/domain.md`。
- **假红陷阱**（假绿的镜像）：本仓常有多个智能体会话同时工作在同一工作树，并发 `-am` / `clean` 构建会交叉重写 `target/`，制造大面积 `NoClassDefFoundError` 假红（实测 85 跑 71 Error 中 ≥66 为假红；也见过兄弟会话中途删文件导致 `需要 class、interface、enum 或 record` 的瞬时编译错）。复核方一律**错峰 + 单模块 + 不带 `-am` 不带 `clean`**：`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=Xxx test`。下结论前复查同一条命令是否已被兄弟会话改写，证据必带时间戳。
- **并发写单一写入者（OPS-09，2026-09-09 软化）**：Java 源码、SSOT 看板镜像 `docs/ipd-系统说明/开发计划-看板镜像.md` 与本地看板默认由主协调会话串行写，其他会话只做只读探针 + 证据交付。`manage.py` 写前重算 PLAN 哈希、中途变更即中止——触发中止时重试或让路，**不要绕过**；同一指令被多会话重复执行时先登记归属，别各自建卡。
  - **软化（R25，owner 已授权「完整接手兄弟会话在途」）**：兄弟在途未提交工作不再是不可接手红线，但接手必须走三步：①接手前对兄弟 M/文件逐一评审并记录处置结论（原样入库/修改后入库/还原）；②SSOT 镜像 + log.md 登记接手事实与 commit 号；③兄弟自有编号体系（如轮次号撞号）用 `ORIGIN-` 前缀保留史实而非覆盖删除。无登记的静默接手仍视为违规。
- 多智能体协同（Ruflo/claude-flow）的启用门槛、拓扑与命令见 `CLAUDE.md` §「Ruflo 多智能体协同底座」；单文件小改动不要上 swarm。

## 构建 / 测试（每条都吃过亏）

- 只在仓库根执行 `mvn`（父 POM 管 `<modules>`）；不要进子模块目录单独构建。Maven 装在 `/Users/mac/tools/maven/bin/mvn`（3.9.11）、JDK 17 在 `/Users/mac/tools/jdk-17/Contents/Home`，都不在精简 PATH 里——非交互 shell 先补 `export PATH="$HOME/tools/maven/bin:$PATH"` 和 `export JAVA_HOME="$HOME/tools/jdk-17/Contents/Home"`，否则依次报 `mvn: command not found` / `Unable to locate a Java Runtime`。
- **假绿陷阱**：Surefire 按 `<groups>${profiles.active}</groups>` 过滤（pom.xml:472），默认 dev profile 下没有 `@Tag("dev")` 的测试类被**静默跳过**——新测试不加 tag，"测试全绿"毫无意义。另一形态：把测试断言改成"现状"（例如把期望异常类型改成新类型）能让用例转绿而契约缺口仍在，收口前须确认绿的是**契约**不是**既有实现**。第三形态（WB-17-1 仲裁卡教训）：mock 造了真库写入路径不可能产生的数据组合（如 PENDING_SECOND 态配 confirmerId、`decision IS NULL` 但无任何生产者落 NULL 行）——单测全绿但真活卡恒空；三条硬规约与存量违法清单见 `docs/ipd-系统说明/mock合法性与已知死路登记-20260908.md`，生成测试时规约见 `.claude/skills/gen-test/SKILL.md`。
- **闭环率口径（R227 owner 裁决，2026-09-26）**：治理轮闭环率按 AI 侧口径统计（AI 可执行的 A+B 类：派单已合并 AND 验证 EXIT=0 AND 看板卡=已完成，三态全真）；C 类 owner 拍板项不计入分母，以「C 类待拍数」单独披露，禁止与闭环率混算出「全口径」数字。
- `demo.enabled` 现**默认 false**（R8-P0-2 已把默认翻成关闭，父 `application.yml` 的 `demo:` 段）。一旦被改回 true，所有写操作被拦截并返回"演示模式，不允许操作"（白名单在同段 `demo.excludes`）。需要演示模式时在 `application-dev.yml` 显式覆盖，反之用 `-Ddemo.enabled=false`。
- 多租户默认开启：新建"租户共享"表必须登记父 `application.yml` 的 `tenant.excludes`，否则查询被自动追加租户过滤，表现为"数据查不到"。反例同样成立：已登记的 `person_roles` 在任何库都还没建表（属"超前登记"，见 `docs/ipd-系统说明/验收/P1-项5-DDL-apply核验-20260905.md` §3）。
- **引用配置用键名，别用行号**：本仓 yml 行号在分钟级就会漂——同一个 `application.yml` 的 `demo:` 段，20:56 实测在 :396、21:10 已到 :400（成因：多会话共工同一工作树，兄弟在途未提交编辑就会推号；`tenant.excludes` 则从早期文档的 :148 漂到 :198）。行号型断言的历史记录一律按"键名 + 当时的值"复核，而不是去对号。
- 本机真实数据源不是 `application-dev.yml` 写的 `127.0.0.1:3306/ruoyi-ai`（3306 无监听且该库不存在）：应用实走 gitignored 的 `.codex/ipd-dev/config/application-ipd-local.yml` → MySQL 8.0.46 @ `127.0.0.1:13306`，业务库 `ipd_dev`（156 表，2026-09-22 R179 实测；旧文档案的 125 是历史快照，另有隔离库 `ipd_qa04` 供 Qa04 并发测试）。只读探针复用 `.codex/ipd-dev/config/mysql-client.cnf`（注意路径是仓库根下，非 ~/.codex）的 socket 即可（socket 与 13306 是同一实例，`SELECT @@port, @@socket` 已验），凭证不上命令行。仓库无 Flyway/Liquibase，`docs/script/sql/update/**` 全靠人工/DBA apply → "SQL 已 commit"绝不等于"约束已生效"，下结论前跑 `p1-ddl-apply-check.py`。
- **全仓统计 / grep 必须排除 `.codex/`、`.harness/`、`target/`，否则量到的是归档不是现役代码**（2026-10-03 14:38 实测）：`.codex/` 有 **19,848** 个 `.java`、`.harness/` 有 **12,786** 个，含归档全仓 **35,166** 个；排除 `.git/.codex/.harness/target` 后真实源码只有 **2,281** 个——归档是现役的 **15 倍**。单文件重复度：`SecurityConfig.java` 全仓 **63** 份，真实源码里只有 **3** 份；`BaseEntity.java` 42 份、`TaskStatusEnum.java` 41 份。后果是「形状对、数值错」：把归档里的旧实现当现役代码、把归档里的引用当「有人在调用」。本轮已抓到两次——一次报「402 个 yml 里一处都没设过」，实测跟踪入库的 yml 只有 **43** 个（排除归档磁盘上 153、含归档 667，没有 402 这个口径）；一次报「全仓没有测试断言这个守卫」，尺子既没排归档又被 `head -10` 截断，实际 24 个文件命中。命令口径：`find . -name '*.java' -not -path './.git/*' -not -path './.codex/*' -not -path './.harness/*' -not -path '*/target/*'`；`grep -r` 一律带 `--exclude-dir=.git --exclude-dir=.codex --exclude-dir=.harness --exclude-dir=target --exclude-dir=node_modules`。新增全仓扫描脚本必须把这套排除**内置**，不靠调用者记得加。注意真实源码计数本身会随会话写文件而漂（同日 14:35 与 14:38 两次实测差 3），报数必带时间戳。
- **5 类病根框架（R25 全局复盘沉淀，机制化非自觉化）**：历轮异常收敛为五类——①改主代码后测试没跟上；②提交不完整（引用了 untracked 文件，fresh clone 必炸）；③规则表与接线点靠人肉对账；④前后端契约无门禁；⑤多事实源（镜像/log.md/分支）无对账。根除不靠自觉，靠会红的测试（哨兵+表驱动契约）、会拦的门禁（上线前本地实跑自证能红）、会喊对不上的对照（快照+负向验证）；五大根源的根除 commit 对照见 `docs/ipd-系统说明/验收/2026-09-05-治理轮总账.md` §7。
- 启动入口 `RuoYiAIApplication.main()` 会先自动杀 6039 端口（Windows 风格命令；macOS/Linux 无害 no-op）。

## 依赖与代码生成

- gRPC 钉死 1.62.2（根 pom `<grpc.version>`；Weaviate client 传递引入 gRPC，BOM 统一版本防冲突）。
- 新增注解处理器必须同步登记 `maven-compiler-plugin` 的 `<annotationProcessorPaths>`，否则静默不生效。
- trace 新代码：RAG 载荷构建放 `org.ruoyi.argtrace`（ruoyi-chat 内，现仅 RagTraceNodeTypes / RagTracePayloadBuilder 两个文件）；通用 trace 域模型与写库在 `ruoyi-common-trace` 模块（`org.ruoyi.common.trace.*`）——不要散落到 `chat.*`。

## 自动化栈（Claude Code 运行时强制）

- `.claude/hooks/block-dangerous-git.sh` **只拦「推送到非私有仓」**（owner 2026-10-07 口径：「严格限制不推送到非私有库即可」「不用拦，只要管住非私有库」）。判据是**远端 URL 精确比对 `owner/repo` 且锁死 `github.com` 主机名**——只取路径末两段比对会漏掉 `attacker/wilson323/ruoyi-ai` 这类仿冒。**私有仓 push（`wilson323/ruoyi-ai`、`wilson323/ruoyi-admin`）放行**，按 §任务结束必提交推送规则直接执行，无需摘 hook、无需人工代跑。**其余命令一律放行**（包括会丢弃工作区的 `reset --hard` / `clean -f[d]` / `branch -D` / `checkout .` / `restore .`——owner 已明确解除破坏性 git 防护，**被放行不是门禁失效**）。
  - 实测：对抗探针 8/8 全拦（仿冒仓 3 + shell 包裹 / 换行 / 括号 / 反引号绕过 5），正常用例 20/20 不误伤。
  - **一处「看起来像放行」不是漏洞**：`git push <未配置的远端名> <分支>` 守卫会放行——因为解析不出该远端、回落查 `origin`（私有）即判通过。但这**安全**：git 自身会先报 `fatal: '<name>' does not appear to be a git repository` 并拒绝，**无任何数据流出**（2026-10-07 实测确认）。真正的非私有推送只有两种形态（远端已配置但指向非私有、或直推 URL），两者守卫都拦。
  - 留痕落 `.harness/audit/blocked-<日期>.jsonl`；`remote` / `url` / `match_context` 三处均已脱敏，不落明文口令。
- `.claude/helpers/sensitive-field-guard.cjs` 阻断写 `.env*` / `application-prod.yml` / PEM 私钥内容；警告 JWT secret、明文 password 字面量。
- `.claude/helpers/ratchet-data-guard.cjs`（R212 卡 7b76b7cd）阻断 agent 直接编辑 `scripts/baselines/*.json`（baseline 只允许 `node scripts/check-api-contract-fe-be.mjs --update-baseline` 脚本独占写）与 `docs/ipd-系统说明/api-internal-whitelist.json`（白名单变更须挂看板卡人审）。
- API 契约孤儿棘轮门禁（R212，2026-09-24 owner 拍板）：`node scripts/check-api-contract-fe-be.mjs` 默认 `ratchet=fail`，孤儿端点「只减不增」——存量分诊为白名单 25 条（内部/运维口，六条防伪校验）+ baseline 22 条老账（`scripts/baselines/`，sha256 自洽 + `git show HEAD` 硬闸防手工编辑）。**退出码位掩码**：`0` 通过 / `1` P0 孤儿路径（或 strict）/ `2` 环境或输入错（含白名单防伪失败、baseline 被改）/ `4` 新孤儿未白名单，可叠加（如 `6=2|4`）；既有 1/2 语义不变。已接入 `.claude/hooks/check-pre-commit.sh` 门禁 3（fast 模式也跑）。逃生阀 `--ratchet=off`。

- `.claude/helpers/ipd-frontend-drift-guard.cjs` 阻断 IPD 前端工程（`/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/`）写入"漂移产物"——创建 `views/ipd/<domain>/<domain>-error.ts`、重写 `api/ipd/product-group.ts`、在 `api/ipd/*.ts` 新增与既有文件冲突的 export 都会 exit 2。完整规约见 `docs/ipd-系统说明/前端架构规约-20260906.md`。
- `scripts/check-ipd-frontend-drift.sh` 是上述 hook 的 CI/手动版：4 项检查（同名导出 / 错误码文件 / product-group 兼容层 / 手写 BackendPending）。CI 接入位置 `.github/workflows/ipd-frontend-drift.yml`（待补）。
- 改 `pom.xml` 会触发 `pom-edit-hint.cjs` 的非阻断同步提醒（BOM 对齐 / 注解处理器 / gRPC 版本等 5 类）。
- 3 个项目 skill（gen-test / api-contract / db-migration）与 3 个审查 subagent（code-reviewer / security-reviewer / performance-analyzer）在 `.claude/` 下，分工见 `CLAUDE.md` §自动化栈。
- **skill 发现目录不是 `.claude/skills/`**：Qoder 只从 `.agents/skills/` 自动发现（该目录被 `.gitignore:101` 忽略，fresh clone 必然缺）。上面 3 个 skill 因此只能斜杠手调；`agentscope-harness` 是**唯一做了运行时镜像**的，改完事实源必须 `rm -rf .agents/skills/agentscope-harness && cp -R .claude/skills/agentscope-harness .agents/skills/`，否则会出现「文档说 A、模型用 B」。镜像一致性由 `skill-lint.sh` L5 的 `diff -r` 卡住（不一致 FAIL）。
- **`agentscope-harness` skill（agent harness 工程契约，2026-09-28 入库）**：动 `io.agentscope:*` / `HarnessAgent.builder()` / `RuntimeContext` 前必读。两条反直觉的坑：① `verify.sh --self-red` **期望 EXIT=0**（它断言五组正反控全部符合预期；退 != 0 是门禁自身失效，不是代码有违规）；② pom 契约门禁必须按「声明 pom 所属工作树」解析根目录——历史 PoC 核查时 `io.agentscope` 位于 `.worktrees/poc-agentscope-kernel/`；此处不是当前主树断言，实际按本次 POM 和加载包核查。历史记录中 okhttp 5.3.2 钉在该树根 pom、`banDuplicateClasses` 在 `ruoyi-modules/ruoyi-chat/pom.xml`，按主树查会三项全报缺失（纯假红，已实测踩过）。
- **自研 harness 主链已摘链（2026-10-02，随 ADR-0077 落地）**：`org.ruoyi.service.coding.harness` 17 子包 / main 254 `.java` 已删除，仅保留 13 个纯值类型文件闭包（`tool/` 7：`PolicyDecision`/`ToolPolicyEngine`/`ToolDescriptor`/`ToolCapability`/`ToolInvocation`/`ToolPolicyEvaluation`/`ToolPolicyContract`；`model/` 6：`HarnessPermissionMode`/`HarnessApprovalPolicy`/`HarnessToolEffect`/`HarnessToolEffectStatus`/`HarnessOwner`/`HarnessEvent`），被 chat.kernel 与 ruoyi-ipd agent 引用作工具治理契约；Permission/幂等/Plan/预算/Context 契约改由官方 `HarnessAgent.builder()` 装配面承接。禁双轨语义不变：**不得让任何组件长回第二套 harness**。历史提示：原自研内部大量 `+ ":" +` 是自身 run-state 命名空间——门禁扫描范围维持「引用 `io.agentscope` 或 `RuntimeContext` 的 `.java`」，别改宽。
- **owner 2026-10-02 裁决（ADR-0077，方向性 approved）**：harness 一律按官方来——`HarnessAgent.builder()` 是唯一装配面，本项目特性只经官方扩展点（middleware / `ToolBase#checkPermissions` / AgentStateStore 包装 / ToolsConfig / hook / workspace 布局 / 官方 disable 开关）定制；双轨的解法是收敛自研平行执行面，**不是"自研已在所以官方不上"**（该方向已否决）。该条历史“按需关闭”口径已被本次用户指令取代：官方能力全量启用，禁止通过 disable 开关裁剪能力；业务权限、技能 owner 拍板和审批流经官方扩展点执行，具体操作仍须经过原业务授权。coding harness 链（`/coding/harness`，前端零调用、不在契约面）摘链证据、依赖序与跨域保留清单见 `docs/ipd-系统说明/ADR/ADR-0077-harness官方化基线与coding链摘除证据-20261002.md`。
- 修改 `docs/wiki/**` 后必须跑 `node docs/wiki/wiki-lint.cjs`（无 CI 门禁，靠自觉）。

## Learned User Preferences

- 对用户输出必须说人话：用简体中文直接说明具体事项、目前做到哪里、还缺什么，以及怎样才算做完。禁止用任务编号、卡号、状态码、内部缩写或配置键代替事项说明；必要技术名称须先解释含义，编号仅可作为附带查证信息。未完成事项必须区分“还要补实现”和“代码已改但尚未实际验收”，不得只报测试数量或堆术语。

- 全局梳理 / 治理类任务：要用专业智能体与工具做蜂群并行，走「盘点 → 实施 → 验证 → 文档/看板同步」闭环，不要只给建议。
- 执行中必须及时更新看板卡片状态（待办 / 进行中 / 阻塞 / 待审核 / 已完成）；证据不足时标 PARTIAL，不得提前标 done。
- 选定方案后用「继续 / A / 指定卡号」直接落地推进，少停在方案对比。
- 收口三步法扩为四步（R214 建议5，2026-09-24）：①核对本次差异 ②按 2026-10-07 规则：任务结束必须整合工作树 commit+push 到固定分支（原「等明确授权」口径已废除） ③核对工作树，保留其他在途修改 ④**验收完成即更新看板卡面**——补注记、翻状态，不许卡面滞后于事实（f42d37dd 类「假缺口」的根源就是第④步缺失）。

## Learned Workspace Facts

- **测试数据政策（R214，owner 2026-09-24 拍板）**：ipd_dev 验收写入的测试数据默认留库并在 log.md 登记，不必逐轮确认清理；长期 mock server 与 `ai_model_configs` 指向 mock 的配置同理保留，重启约定写在对应卡面（3280f1e2）。
- 本工作区是 `ruoyi-ai`（IPD 后端）；正式前端是 `/Users/mac/Documents/ruoyi-ipd-web`，原型图与业务说明在 `/Users/mac/Documents/ZK-IPD`。前后端看板待办与功能闭环必须一起核对。勿与 `ZKER-staff`（`/Users/mac/Documents/ChatGPT/ZKER- staff`）或用户规则里的 IOE-DREAM 一卡通内容混淆。
- 看板操作走 `user-zker_vibe_kanban` MCP，并与 SSOT 镜像 `docs/ipd-系统说明/开发计划-看板镜像.md` 对齐；Mock/单测绿不等于业务闭环，真库或 HTTP 未过不得伪完成。
- 多会话并行时同一 Controller/测试签名会被兄弟会话改写；验收前以磁盘现态重编译，假红/假绿规则见上文「构建 / 测试」。
- **五必现查规约（R13 立）**：hash / 端口字段 / 段号 / 看板回读 / 跨仓 cd 五类事实源必须现查现写，规约全文见 `docs/ipd-系统说明/事实源五必现查规约-20260908.md`。实测教训：R12.1 marker 凭记忆写 R11 hash、shell cwd 漂到前端仓导致 git log 显示错误 hash、看板 updated_at PUT 后不刷新（验证只认 desc_len + marker content）。跨仓命令必 `cd 绝对路径 &&` 开头。
- 产线知识库 MCP 用 AgentScope 替换本仓分叉接入：客户端按稳定 client name 登记，工具名走 listTools 的协议名。中文产品线展示名对不上不能当成没有知识来源。名称相等闸门和手写 JSON-RPC 客户端必须同一刀删除，两条 MCP 路径不能并存。保留 `ProjectAgentController` 单轨、`AiGateway`（副驾、文档、Gate、招投标、嵌入）、`ai_documents` 审核链、能力包服务标识和 `product_lines.mcp_service_id`。不打开 `chat.kernel.agentscope.enabled`，不给聊天内核 Toolkit，不打开动态技能、子智能体或 plan mode，不用 `plans/PLAN.md` 或 workspace `MEMORY.md` 当业务事实源。这一刀不把 AgentScope 从 2.0.3 升到 2.0.4，不引入 AgentScope Service（Vault/Sandbox/Temporal）。
- 万傲瑞达 V6600、ZKTime、ZKAccess3.5、E-ZKEco Pro、熵基互联是单独的软件产品线，不并进目录「其他」。用户没有明确说之前，不要把已有项目改挂到这些线上。官网目录端点与库内产品线名称不必逐字相等；已有对应关系（例如停车设备对车行产品）按对照挂上，不要因为字符串不相等就停掉对照表。没有事实对应的端点不要模糊别名到相近产品线名称。
- 平台 User 与 IPD Person 不能按相同数字直接等同。AgentScope 状态版本不能替代数据库里的权限、版本和并发约束。工具调用成功或结构化输出不能单独证明事实正确，数字、年份、单位和引用要另验。工程执行授权不代替业务负责人的文档审核、动作批准和 Gate。

<!-- REPOWISE_DISTILL:START — Do not edit below this line. Auto-generated by Repowise. -->
### Output Distillation

- Prefer `repowise distill <cmd>` for noisy commands — test runs, builds, `git status`/`log`/`diff`, searches, file listings. It runs the command unchanged (exit code preserved) and prints a compact, errors-first rendering; every error line survives.
- Output may contain a marker like `[repowise#a1b2c3d4e5f6: 230 lines omitted (~6.1k tokens); restore: repowise expand a1b2c3d4e5f6]`. The omitted content is fully preserved — run `repowise expand <ref>` to retrieve it, or `repowise expand <ref> -q <regex>` for just the matching lines.
- Never re-run a command to see omitted output; expand the marker instead.
- For structure-level questions about a large indexed file ("what's in here", "which function handles X"), `get_context(["path"], include=["skeleton"])` returns the file with bodies elided — every signature plus the bodies of the most central symbols — at a fraction of the cost of a full Read.
<!-- REPOWISE_DISTILL:END -->

## 构建互斥（2026-10-03 新增，治本）

**同一模块禁止多个 Maven 进程并行。** 本仓曾出现 19 路 agent 同时对
`ruoyi-modules/ruoyi-ipd` 跑 `mvn`，全部写同一个 `target/`，字节码在测试运行途中
被重写，表现为 `NoClassDefFoundError` → Mockito `Unfinished mocking session` 级联，
**单次产生 979 个假错误**，且全模块基线数字无法复现（同一天先后拿到
4224/0、4270/0、4305/0、27 失败、979 错误五种互斥结论，全部是构建产物互相覆盖所致）。

**约定：所有 mvn 调用一律走包装器**

```bash
bash scripts/mvn-locked.sh -o test -pl ruoyi-modules/ruoyi-ipd
```

它按「模块 + 阶段」取互斥锁（`mkdir` 原子性），拿不到锁就排队（默认 40 分钟上限，
超时以退出码 75 明确失败，绝不并行写同一 target/），退出时用 trap 释放。

**判读测试数字的前提**：只有在无并发构建污染的窗口下跑出的全模块结果才作数。
拿不到干净窗口时**如实写「未验证」**，不得把被污染的数字当通过或当失败。

## 智能体平台中断（2026-10-04 实测，不是项目代码错）

「Agent execution terminated due to error」「stream reading error: unexpected EOF」「request failed …streamGenerateContent: EOF」「429 model API overloaded」都是**模型服务/网络层**断流，不是 Java、mvn 或本仓代码报错。2026-10-04 两个会话的日志实证：中断发生在模型还没发出任何工具调用时，同一时段没有子智能体也没有 mvn——不得再归因到并发构建或项目代码。

- **先读日志再下结论**：会话日志在 `~/.gemini/antigravity-ide/brain/<会话ID>/.system_generated/logs/transcript.jsonl`，沙箱里 `grep`/`tail` 会报 `Operation not permitted`，改用 `view_file` / `grep_search` 直接读文件，搜 `ERROR_MESSAGE` 拿真实错误码。没读到就写「未核实」，不列推测清单冒充原因。
- **不要把十几 KB 的报告整段当提示词贴进来**：实测两次首条消息过大都在几秒内触发上下文压缩，压缩摘要又把原始日志转义嵌套进去，后续每次请求更重、更易断流。把报告存成仓内文件，提示词只写「读某文件第几节，做某一件事」。
- **一次只派一件事**：「多个智能体并行 + 全部事项」拆成独立小任务分会话做。
- **同一会话连续两次断流就换新会话**，不要在原会话反复重发（实测原会话 4 次重发 8 次全断，新会话正常）；会话中途别来回切模型/思考档位。
- 平台层故障无法在本仓代码里根除；本仓能做的只有以上四条降低触发概率与误判。
