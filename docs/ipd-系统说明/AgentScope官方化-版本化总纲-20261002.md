> 版本：1.1.0。2026-10-02 本轮修订。实施编号与启动条件统一见 [六计划总览](AgentScope官方化-六计划总览-20261002.md)。下文旧 P 编号、装配点数量和行号仅是先前观察，开工重新查源码；不能以它们证明当前运行态。版本化不授权自动提交、推送、迁移或发布。

# AgentScope 官方化 · 版本化总纲（2026-10-02）

> 定位：六计划总览 v1.1.0 的共同版本合同（由 P1 维护、P6 验收），治理文档（现状固化 + 变更流程定义，非新建设计）。实施编号仅认六计划总览v1.1.0，本文不另排实施顺序。
> 核心命题（源自 agentscope-harness skill 交付物清单节）：**模型版本与 harness 行为配置必须共同版本化，否则线上结果不可复现、不可回滚。**
> 原则：不新造版本化机制——本项目已有 9 项机制，AgentScope 装配面逐一挂进去。

> v1.1.0 当前修订：本轮七个专业工程分区只是同一总计划的实施分工，不开启产品 subagents，也不引入 AgentScope Service/Vault/Sandbox/Temporal。aiflow 全图与旧检查点兼容读取正在迁移，尚未完成统一打包、加载及真实恢复验收，当前为 PARTIAL。确定性业务 DAG 负责既有业务节点顺序、条件和并行，不构成第二套智能体推理执行轨；智能体能力仍统一使用锁定 AgentScope 2.0.3，不能保留旧 LangGraph 引擎作为失败回落。

## 一、版本化对象清单（什么必须被版本化）

### 1.1 三层对象

| 层 | 对象 | 版本化载体 |
|---|---|---|
| 依赖层 | `io.agentscope:*:2.0.3`（禁止自动升级）、okhttp 5.3.2 钉版；当前根 POM 已无 LangChain4j BOM，不恢复旧依赖 | 根 pom / ruoyi-chat pom + git |
| 装配层 | 三个 `HarnessAgent.builder()` 装配点的完整链（见 §1.2） | git（代码即配置）+ log.md 登记 |
| 行为层 | 模型配置（ai_model_configs 行）、技能内容（ipd-skills/*/SKILL.md）、系统提示词拼装 | 库表 + SHA-256 清单 + git |

### 1.2 装配面清单（2026-10-02 现状，三装配点）

| 装配点 | 文件 | 链上要素 |
|---|---|---|
| 项目智能体内核 | `ruoyi-ipd/.../agent/kernel/AgentScopeProjectAgentKernel.java` | model（AgentScopeModelFactory）/ hook(audit) / middleware(deadline+Ownership) / toolkit / maxIters / **ExecutionConfig 双挂**（:277-282）/ **compaction 显式固化** / FrozenProjectAgentSkills / skillsEnabled(false) / workspace / stateStore |
| 聊天内核 | `ruoyi-chat/.../kernel/AgentScopeChatKernel.java` | 同构双挂（V1 验收 2026-10-02，单测 10/10） |
| 编程助手 | `ruoyi-chat/.../coding/impl/CodingServiceImpl.java` | 第三装配点（P3/2026-10-02 补齐：ExecutionConfig 双挂 + compaction 固化，:119-124） |

**每行的版本化意义**：builder 链任何一行变更 = 行为变更 = 必须形成可审查差异 + log.md 登记 + 相关验证同步；Git commit 仅在用户明确授权后执行。特别是：
- `disable*` 系列：不是技术开关，是**业务语义落档**（memory 双关 = MEMORY.md 不当事实源；disableSubagents = 单智能体单轨；skillsEnabled(false) = 技能晋升走 owner 闸门）。改任何一个都要在总矩阵 §二表里同步裁决列。
- `ExecutionConfig.MODEL_DEFAULTS/TOOL_DEFAULTS`：禁止叠加独立应用重试循环；SDK内部显式重试策略按锁定API和次数/超时证据评估，不以一个方法名判双轨。
- `compaction(CompactionConfig.builder().build())`：与官方默认等价的**意图固化行**（注释已注明，不得改写成「修复」）。

## 二、9 项既有机制 × 装配面挂载点（严格匹配表）

| # | 既有机制 | 装配面如何挂入 | 漂移防线 |
|---|---|---|---|
| 1 | **git commit + 锁版** | 装配代码随 commit；`agentscope.version` 根 pom 锁定；升级 AgentScope 必须整表重跑验证（§四 R 流程） | git diff 可回溯 |
| 2 | **技能 SHA-256 清单 + ipd_action_skill_map** | classpath `ipd-skills/<name>/SKILL.md` 经 SHA-256 与清单一致才注入系统提示——提示词拼装版本化 | 清单不一致即拒绝注入（fail-closed） |
| 3 | **装配面随 git 版本化**（代码即配置） | §1.2 三装配点全部落 git；**禁止**把装配参数外置到 yml/库表去追求「热更新」（会脱离 git 审计面） | code review 看装配 diff |
| 4 | **log.md 登记** | 每次动装配面/依赖版本/技能清单 → log.md 登记 marker + commit 号 | 收口四步法第①步核对 |
| 5 | **ADR 目录** | 架构级决策（官方化基线 ADR-0077、后续 subagent/Channel/langgraph4j 拍板）各立 ADR | 总矩阵引用 ADR 编号 |
| 6 | **看板 SSOT 镜像** | 摘链/迁移类大刀（P2 四刀、langgraph4j）按卡登记，镜像与看板对账 | manage.py 哈希中止机制 |
| 7 | **.claude/skills 事实源 + .agents/skills 镜像** | agentscope-harness skill 本身的版本化：改事实源必须 核对差异后可恢复地同步镜像，不默认执行目录删除 | skill-lint L5 `diff -r`（不一致 FAIL） |
| 8 | **baselines 脚本独占写** | API 契约基线（`--update-baseline` 独占）；装配面新增对外行为（如 P4 Verifier 端点）→ 基线同步 | ratchet 门禁 + sha256 自洽 + git show HEAD 硬闸 |
| 9 | **收口四步法** | ①核对差异 ②授权后 commit ③核对工作树保留他人在途 ④验收即更卡面 | R214 定规，log.md 可查 |

## 三、变更流程（动装配面的标准路径）

```text
意图（总矩阵/ADR 有裁决依据）
→ 改装配点代码（最小刀，不动无关行）
→ 单测同步（新行为有断言；假绿三形态自查：@Tag("dev")/断言改现状/mock 造不可能数据）
→ bash .claude/skills/agentscope-harness/scripts/verify.sh（EXIT=0）
→ bash .claude/skills/agentscope-harness/scripts/verify.sh --self-red（EXIT=0，门禁自证）
→ mvn -o -pl <module> -Dtest=... test（错峰+单模块，确认 Tests run 非零）
→ log.md 登记（marker + commit 号 + 行为变更摘要）
→ 收口四步法
```

**禁止路径**：
- 跳过 verify/self-red 直接说「已验证」；
- 只改代码不改总矩阵 §二裁决列（disable 开关语义漂移）；
- 把装配参数挪到配置中心/yml 追求免发版热更（脱离 git 审计面 = 不可回滚）；
- 升级 AgentScope 版本不做 §四全量回归。

## 四、AgentScope 版本升级的版本化流程（2.0.3 → 未来版本）

1. **前置**：owner 明确指令（锁版纪律，禁自动升级）；
2. **差异审计**：解包新版本 sources jar，对照总矩阵 §二/§三逐项核 API 面变化（历史案例：`disableSessionPersistence()` 在 2.0.3 是空操作——方法名不等于行为）；
3. **三装配点 + 全部扩展点实现**（MiddlewareBase/Hook/官方 import）过编译；
4. **全量回归**：verify + self-red + 棘轮 + 三模块编译 + agent 相关单测全绿；
5. **登记**：ADR + log.md + 总矩阵更新（版本号、新 API 面实证标记）；
6. **回滚预案**：pom 回退 commit 号先登记。

## 五、可复现性口径（对外承诺的边界）

给定同一 commit + 同一份冻结的非敏感模型配置快照/指纹（行号相同不够） + 同一技能 SHA-256 清单 + 同一输入，运行结果**可复现到「同配置同链路」级**；模型输出本身的非确定性（temperature 等由模型配置行控制）不在本总纲范围。跨实例部署前提见《多用户团队应用设计》目标存储部署前提（原G4编号作废）（StateStore 外置）。

## 六、与兄弟文档的关系

| 文档 | 关系 |
|---|---|
| AgentScope官方化迁移矩阵-20261002.md | 裁决事实源；本总纲 §三 流程的「意图」来源 |
| AgentScope官方化-Quality域Verifier缺口设计 | 其 V-1/V-2 落地时按本总纲 §三 流程走（新表挂机制 1/4/8/9） |
| AgentScope官方化-多用户团队应用设计 | 统一P5切片落地时同上；目标存储部署前提（原G4编号作废） 部署前提由本总纲 §五引用 |
| agentscope-harness skill | 本总纲的契约出处；skill 改版走机制 7（事实源+镜像） |

- marker agentscope-versioning-master-plan-20261002
