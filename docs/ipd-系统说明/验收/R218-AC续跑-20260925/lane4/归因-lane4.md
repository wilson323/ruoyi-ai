# R218-AC 续跑 · L4 车道 归因报告（INC / GLB / AUD / AI）


- 车道：L4（INC 增量与对接 / GLB 全局与治理 / AUD 审计链 / AI 生成链）
- 仓库：`/Users/mac/Documents/ruoyi-ai`（IPD 后端 ruoyi-modules/ruoyi-ipd）
- 后端基线：`http://127.0.0.1:16039`（fresh jar，全程未重启/未轮换/未构建/未杀端口）
- 复用资产：`R218-QA08-SEC04-20260925/r218_lib.py` → 本目录 `r219_lib.py`（REC_PATH/写库清单改指本车道；B39=16039，未使用 lib 内旧实例 B46=16046）
- 账号池：ipd-admin / ipd-leader / ipd-market / ipd-rd（sysadmin 已删除，未使用）
- 真库校验：`L.sql()` 只读 ipd_dev（mysql --batch），全程零直接 SQL 写；所有写入均经 /api/v1 HTTP
- 执行时间窗：2026-09-26T12:40:34+08:00 … 2026-09-26T12:40:49+08:00
- 记录数：90 条（含 2 条 fixture 记账）；HTTP 写请求 126 次（写库清单-lane4.json，字段含 run 批次标签）
- verdict 词表：PASS / FAIL / PARTIAL / FAIL-ENV / BLOCKED / NOT-RUN

## 1. 四域计数与覆盖率对账

| 域 | 总池卡数 | 本车道执行卡 | 记录数 | PASS | PARTIAL | FAIL | FAIL-ENV | NOT-RUN | 备注 |
|---|---|---|---|---|---|---|---|---|---|
| INC | 57 | 56 | 56 | 36 | 15 | 3 | 0 | 2 | AC-INC-34 已在 R218 主执行清单覆盖 → 按去重纪律跳过（故 57−1=56） |
| GLB | 12 | 12 | 12 | 2 | 6 | 2 | 0 | 2 | 12/12 全覆盖 |
| AUD | 7 | 7 | 9 | 7 | 2 | 0 | 0 | 0 | 7 卡 9 条记录（AUD-04/AUD-05 各含查询面+导出面 2 条） |
| AI | 10 | 10 | 10 | 8 | 1 | 0 | 1 | 0 | 10/10 全覆盖（`AC-AI-FIXTURE` 单列为前置件，不计入本行） |
| **合计（AC 卡）** | **86** | **85** | **87** | **53** | **24** | **5** | **1** | **4** | 覆盖率 85/86=98.8%（缺 1 卡为去重跳过） |

> 另有 3 条前置件记账不计入 AC 卡：`INC-FIXTURE`、`INC-FIXTURE-P`、`AC-AI-FIXTURE`（合计 90 条记录 = 87 AC + 3 前置件；`AC-AUD-04/05` 各含查询面与导出面 2 条记录，故 AC 记录 87 > 卡 85）。


## 2. 真缺陷候选清单（本车道证据，只描述不立卡）

按影响面排序。每条给：现象 → 判据/证据（HTTP + 真库 + 码证）→ 受影响 AC 卡。

### D-1 月度津贴台账链路未接线（最高优先）
- 现象：`POST /api/v1/allowance/auto-scan` 只 `SELECT COUNT(*)` 当月既有行后返回，不生成任何台账；津贴计算/停发判定（`calculateMonthlyAllowance`、`determineStopReason` 族）在生产代码中零调用方，controller/scheduler 均无入口，也无 `@Scheduled`。
- 证据：AC-INC-04（auto-scan 返回 1 而本轮 M4 已 3 项目活跃绑定、ledger 行数=0；码证 AllowanceLedgerService L228-242 直接 `return selectCount`；`allowanceService.` 生产注入调用 0 处；`determineStopReason` 定义/重载命中 13 处但调用 0 处）；真库 2026-08 6 行 / 2026-09 1 行均为 09-08 脚本灌入非服务生成。
- 受影响：AC-INC-03/04 FAIL；AC-INC-05/06/07/08/10 因此只能 PARTIAL（应发/封顶/停发/绩效联动/离职次月停发全部不可行为验证）。

### D-2 奖金池"重算版本链"与库约束互斥（承诺能力不可兑现）
- 现象：DRAFT 池二次 compute 由业务闸正常拒（HTTP=409/code=50002）；但一旦 freeze（freeze 实际把 status 推进到 **CONFIRMED**，不是字面 FROZEN），再 compute 直接 HTTP=500/code=90001『系统内部错误』。
- 证据：AC-INC-16 双态探针——新项目首算 code=0 → DRAFT 态重算 409/50002 → 对 status=CONFIRMED 的池重算 500/90001；`information_schema` 实测 bonus_pools 仅有 `UNIQUE uk_bp_project(project_id)`（全库重复组=0），BonusPoolService.compute L1000 无条件 insert 撞唯一键；sys-error.log 同栈 `Duplicate entry`。
- 影响：闸文案承诺"freeze/distribute 后可计算新版本"永远不可达，奖金池不可重算/不可纠错。

### D-3 G5 阶段字段错配 → 贡献度与 distribute 永久不可达
- 现象：贡献度评定入口/确认与 `distribute` 前置要求 `projects.status ∈ {LIFECYCLE, POST_LAUNCH}`，而项目状态机不存在通往该二值的迁移，也没有任何生产代码写这两个 status。
- 证据：AC-INC-28/25（负向：非 G5 保存被拒 code=50007 msg=『项目不在 G5 上市后 90 天复盘阶段（当前 status=DRAFT）』；正向：`POST /projects/{id}/status?target=LIFECYCLE` → code=10001 状态机非法迁移 DRAFT→LIFECYCLE）；AC-INC-30（`distribute` code=50002 msg=『项目尚无贡献度评定记录，不允许分配奖金』，bonus_allocations 空）。码证：ContributionService.requireG5Stage L601-605、ProjectService.STATUS_TRANSITIONS L54-59、全仓 `setStatus("LIFECYCLE")` 生产零命中。
- 影响：新建项目走不到奖金分配出口（真库唯一满足态项目=9150001 SQL 验收种子，非本车道 fixture，未动）。

### D-4 涉钱参数只读不消费（配置面形同虚设）
- 现象：`system_configs` 里 6 个 `bonus.*` 键都有值，但 Java 侧代码级 consumer 全为 0；阶梯/系数改以 BonusPoolService 字面量 `DEFAULT_THRESHOLDS / TOP_COEFFICIENT` 硬编码。
- 证据：AC-GLB-09（全量/代码级双计数 + `allowance.L3` 阳性对照证明读取机制本身可用；bonus.salesSource 的 4 处全量命中逐条是 javadoc）；AC-GLB-10 行为侧坐实——同参两个新项目在 `salesSource=RECEIPT` 与 `SHIPMENT` 两态 compute 得 finalPool 均 216000.0（无差异），随后已 PUT 复原为 RECEIPT。
- 影响：改配置不生效，违反 G-08"涉钱参数不得硬编码"；当前只是字面量与配置巧合一致。

### D-5 Gate 要素 is_veto 编码混存 → 部分否决项静默失效
- 现象：`gate_review_elements.is_veto` 同时存在 '1'/'0'（现行 seed）与 'Y'/'N'（老代）两种编码，判定实现只认 `"1".equals`。
- 证据：AC-GLB-12——API 返回 68 条（其中 isVeto '1' 15 条、'Y' 14 条）；真库 published&enabled=1 共 66 条（否决 '1'=15 / 否决 'Y'=14）；is_veto∈{Y,N} 老编码共 33 条（Y=14，恰与卡面 33/14 对齐=卡面数字过期）；码证 GateElementResultService L85/L126/L258 三处 `"1".equals`。附带：`GET /api/v1/gate-elements` 未过滤 status（68=66+2 条 draft&enabled=1）；published 66 条 `threshold_json` 全为空。
- 影响：14 条 'Y' 编码否决项在服务层永不被视为否决项。

### D-6 负反馈对奖金的影响只写不算（write-only 字段）
- 现象：NF create 即落 `bonus_disqualify=1` / `tier_delta=-0.50` 并可在视图回显，但奖金/贡献度执行入口从不读这两个字段；`project_members.bonus_eligible` 只有绑定时置 1 的路径，置 0 路径命中 0；`lift` 只改 status，审计文案却声称"恢复津贴+bonusEligible"。
- 证据：AC-INC-39——真库 `status='EXECUTED'` 分布 `[bonus_disqualify='1', tier_delta='-0.50', cnt=11]`；`getBonusDisqualify|getTierDelta|bonus_disqualify|tier_delta` 在 BonusPoolService/ContributionService/ProjectMemberServiceImpl 的 consumer 命中 0；`setBonusEligible(0|…)` 写路径命中 0；本轮 decide 后相关成员仍全部 `bonus_eligible=1`。码证 NegativeFeedbackService L218-219 + DEFAULT_TIER_DELTA L70、NegativeFeedbackView L27-28、lift 文案 L315。
- 影响：负反馈的"降档/取消资格"在半实现状态（记录在案、无执法效果），且 lift 文档与实现互斥。

### D-7 错过市场窗口：主责 BOTH 实际只停发市场 PM 一人
- 现象：`deriveRoleMapping` 对 MISSED_MARKET_WINDOW 置 mainRole=BOTH / relatedRole=null（javadoc L43 明写"STOP_ALLOWANCE × 2 双PM共同担责"），但被执行人解析分支对 BOTH 只 `ids.add(marketId)`，不补 rdId。
- 证据：AC-INC-38（FAIL）——真库 4 条 `MISSED_MARKET_WINDOW / EXECUTED` 行的 `main_person_id` 全部是本项目 MARKET_PM（逐条 id 见记录 note），同项目在职 RD_PM（9110011 / 9110013 / 2096266884268707841 等）从未出现在 main/related_person_id，覆盖数=0；码证 NegativeFeedbackService L464-470。
- 说明：本卡前几轮曾判 PASS，因判据只核视图角色标签；本轮把判据加深到"被执行人个体覆盖"后下调为 FAIL。

### D-8 项目绑定名额死锁（无解绑/改绑出口）
- 现象：`allowance.projectCountThreshold=3` 为硬上限（备案也不能突破），而 ProjectMemberController 只有 bind/list，无解绑或改绑接口；全库 7 名在职 RD_PM 的活跃绑定数已逐一=3。
- 证据：AC-INC-03（第 3 绑定无备案被拒 code=10001、带备案放行 code=0、第 4 带备案仍硬拒 code=10001 msg=『已达项目数上限 3，禁止再绑定（备案也不能超过上限）』）；AC-INC-36b/37/38 本轮 `nf_target` 选不出"双 PM 在位且该触发未占用"的 LANE4 项目（RD_PM 配额逐人列出）。
- 影响：人员一旦绑满 3 项目即永久占位，任何需要"新双 PM 项目"的用例（含本车道 NF 三场景复跑）不可再构造；清单 AC-INC-03 的"4 个已备案项目"前提与实现口径直接冲突（口径漂移，需卡面裁决）。

### D-9 AI 月度 token 预算：闸在、入口不可配、无超管提示
- 现象：预算预检实现存在（生成前判 `config_json.budgetTokens`，超限 failAudit + 抛 `AI_BUDGET_EXCEEDED`=40013/HTTP 409），但保存 DTO 白名单不含该键且 `@JsonIgnoreProperties(ignoreUnknown=true)`，未知键静默丢弃；也没有"提示超管"的通知出口。
- 证据：AC-AI-08（实测 `POST /api/v1/ai-models` 带 `budgetTokens=100` → code=0，落库 `config_json={"maxTokens": 64, "temperature": 0.7}`，不含 budgetTokens；AiGenerationService 内 notifyAdmin/站内信/MessageService 命中 0；码证 L120-127/L216-217、AiModelSaveReq 字段表、ApiV1ErrorCode L24+L101；前端文案 auth.ts L65 已备）。
- 影响：预算护栏对运营不可达（配置面缺口），"并提示超管"为文案与实现不符。

### D-10 AI 失败错误分类与对外码（观测性缺陷）
- 现象：端点拒连（`java.lang.RuntimeException: java.net.ConnectException`，RetryUtils 重试 2 次后放弃）被归类为 `UNSUPPORTED_PROTOCOL`，而非白名单里更贴切的 `UNREACHABLE`；对客户端只暴露 HTTP=500 / 通用 `INTERNAL_ERROR`(90001)。
- 证据：AC-AI-02 记录内嵌日志级原始证据（同 trace 内 embed 与 chat 各失败一次，`[AI] embed fail … errorCode=UNSUPPORTED_PROTOCOL`）；码证 AiGateway.mapFailure L263-284 的 `ConnectException/UnknownHostException → UNREACHABLE` 分支未命中（疑 langchain4j 重试耗尽后的包装改变了 cause 链）。
- 影响：外部模型故障在 API 面呈现为"系统内部错误"，运维定位被误导；不改变本域功能正确性。

### D-11 达成率无 HTTP 出口（可观测性缺口）
- 现象：`calculateAchievementRate` 在 controller 层零出口，达成率只能靠真库复算观察。
- 证据：AC-INC-16b（P_R 目标 500 万，窗口内 RECEIPT 净额复算 88.0000%；取数口径 SQL 明证 `source='RECEIPT' AND in_window=1`；出库/开票 source 分布中无命中）→ 口径正确但只能 PARTIAL。

### D-12 前端 i18n 未收口（治理债，非功能缺陷）
- 现象：存在 zh-CN 语言包（含中文行数 79），但 `apps/web-antd/src/views` 232 个 .vue 中模板直写中文字面量命中 1000+ 处，集中化与直写并存。
- 证据：AC-GLB-08 PARTIAL。

## 3. 非 PASS 逐条归因（三分法：fixture 假红 / 环境假红 / 真缺陷）

| AC 卡 | verdict | HTTP/code | 归因类别 | 归因说明 |
|---|---|---|---|---|
| AC-INC-03 | FAIL | 400/10001 | 真缺陷 | D-8 名额硬上限 3+无解绑出口 → 卡面『4 项目均备案』前提不可构造；叠加 D-1 使应发/封顶不可观测 |
| AC-INC-04 | FAIL | 200/0 | 真缺陷 | D-1 津贴台账/停发链路未接线（autoScan 只 COUNT） |
| AC-INC-38 | FAIL | 200/0 | 真缺陷 | D-7 主责 BOTH 只落市场 PM 一人（个体覆盖=0）；另叠加 D-8 使本轮不可新做 |
| AC-GLB-09 | FAIL | 200/None | 真缺陷 | D-4 涉钱参数零 consumer，阶梯以字面量硬编码 |
| AC-GLB-10 | FAIL | 200/0 | 真缺陷 | D-4 行为侧坐实：切 salesSource 两态 finalPool 全等 216000.0 |
| AC-INC-05 | PARTIAL | 200/None | 真缺陷 | D-1 连带：determineStopReason 零注入调用，pending-stop 端点只回读 |
| AC-INC-06 | PARTIAL | 200/None | 真缺陷 | D-1 连带：封顶公式有码证但无行为出口；且公式无绩效乘子（卡面含绩效） |
| AC-INC-07 | PARTIAL | 200/0 | 真缺陷+环境 | D-1 连带；60 天无产出需历史 last_activity 回溯，禁 SQL 写下不可零副作用构造 |
| AC-INC-08 | PARTIAL | 200/0 | 真缺陷 | D-1 连带：主/附绑定判定有码证，停发数据侧无从验证 |
| AC-INC-10 | PARTIAL | 200/0 | 真缺陷+口径 | 离职只改 persons（PersonService L95 设计如此），exit_date 依赖移交闭环；次月停发受 D-1 阻断 |
| AC-INC-16 | PARTIAL | 409/50002 | 真缺陷 | D-2 已 freeze(CONFIRMED) 池重算 500/90001 撞 uk_bp_project；矩阵本体 10 点全 PASS |
| AC-INC-16b | PARTIAL | 200/0 | 真缺陷 | D-11 达成率无 HTTP 出口，只能真库复算（口径本身正确：RECEIPT+in_window） |
| AC-INC-17h | PARTIAL | 200/0 | 真缺陷 | D-4：achievementTiers 六档结构与清单同构，但该键 Java 零 consumer，正确系巧合一致 |
| AC-INC-25 | PARTIAL | 409/50007 | 真缺陷 | D-3 G5 入口闸挡住正向联动（市场/研发份额联动不可跑） |
| AC-INC-27 | PARTIAL | 200/None | 真缺陷 | D-3 同因：贡献度五维权重求和只能码证，行为验证不可达 |
| AC-INC-28 | PARTIAL | 409/50007 | 真缺陷 | D-3 阶段字段错配（闸判 projects.status，状态机无 LIFECYCLE/POST_LAUNCH 迁移） |
| AC-INC-30 | PARTIAL | 409/50002 | 真缺陷 | D-3 链式不可达：distribute 前置 CONFIRMED 贡献度永不可得；退出者资格过滤亦不可观测 |
| AC-INC-36b | PARTIAL | 200/0 | 真缺陷 | D-8 名额死锁致本轮不可新做；历史同车道 HTTP 产物回读+deriveRoleMapping 码证支持映射正确 → PARTIAL |
| AC-INC-37 | PARTIAL | 200/0 | 真缺陷 | 同上（D-8）；映射证据同法 |
| AC-INC-39 | PARTIAL | 200/0 | 真缺陷 | D-6 奖金侧 write-only：disqualify/tier_delta 有写无读，bonus_eligible 无置 0 路径，lift 文案与实现互斥 |
| AC-AI-08 | PARTIAL | 500/90001 | 真缺陷+环境 | D-9 预算键 API 不可配+无超管出口；D-10/外部模型不可达使超阈分支被前置条件挡住 |
| AC-AI-02 | FAIL-ENV | 500/90001 | 环境 | 外部模型服务不可达（8765 CLOSED）＝FAIL-ENV；附带 D-10 错误分类缺陷 |
| AC-GLB-01 | PARTIAL | 200/None | 方法性 | 抽样回归口径（全量 57 条越权矩阵已由 R218-SEC04 覆盖，按去重纪律不重跑） |
| AC-GLB-04 | PARTIAL | 200/None | 方法性 | 静态抽查（26 处 ARCHIVED 迁移）；全量 27 卡状态机遍历属专卡范围 |
| AC-GLB-06 | PARTIAL | 200/None | 卡面/数据缺口 | 动作台账未入库，sop_templates 无管理深度列 → 42/27/69/38 无系统侧真值载体，判定悬置 |
| AC-GLB-08 | PARTIAL | 200/None | 治理债 | D-12 i18n 集中化与 views 直写并存（1000+ 处） |
| AC-GLB-11 | PARTIAL | 200/None | 方法性 | person_type 四类合规；越权角色 grep 命中 3 处待人工判定是否真角色 |
| AC-GLB-12 | PARTIAL | 200/0 | 真缺陷+卡面过期 | D-5 'Y' 编码否决静默失效 + API 未过滤 status；卡面 33/14 对应老代编码数据 |
| AC-AUD-02 | PARTIAL | 200/0 | 历史存量（已裁决） | chain=GAP gaps=17 全为本轮之前存量（09-06 GATE 批量事务回滚+09-22 一处），hashBroken=0；owner P0-17 A 方案已接受 |
| AC-AUD-03 | PARTIAL | 200/0 | 执行纪律 | 篡改注毒需写 audit_logs，本波次禁 SQL 写 → 只验判据四态出口齐备，正向断裂引用历史真库冒烟 |

> 归因汇总：真缺陷（含叠加）21 条；环境 1 条；执行纪律/方法性 4 条；卡面-口径 3 条；**fixture 假红 0 条留存**（驱动内部 4 处假红已在跑批中自纠并改判，见 §5）。
> 未列入本表的非 PASS 记录：['AC-INC-39']

## 4. NOT-RUN 清单与原因分类（4 条，集中列示不逐条敷衍）

| AC 卡 | 域 | 原因类别 | 说明 |
|---|---|---|---|
| AC-GLB-05 | GLB | 文档级人工全量对照 | BR 全集 × 实现映射需产品逐条裁决，服务层无对应可观测对象；不硬凑 |
| AC-GLB-07 | GLB | 纯 UI/浏览器走查 | 中文界面英文残留与错别字只能界面走查；R218 已有 UI-walkthrough 专车道产物 |
| AC-INC-09 | INC | 需外部人事/委员会事件 + 时序 | 评级委员会评定事件无 API；"次月生效"需跨月时钟；且现行 `allowance.levelEffectiveRule=BY_BIND_TIME` 与卡面"次月生效"口径不同（口径差异留卡面裁决） |
| AC-INC-11 | INC | 需跨考核期历史数据 + 定时判定 | 需两期历史 KPI 序列，本波次禁 SQL 写且无外数注入端点；实现侧仅有 person resign 人工通道 |

其他长尾未跑类别（本轮已尽量就地替代验证）：
- 外部服务依赖类：AI 外呼（AC-AI-02 已按 FAIL-ENV 记账，含原始错误与日志级证据），非 NOT-RUN。
- 纯前端渲染类：GLB-07 之外，GLB-03/08 用静态源码取证替代（注释行剔除后判 PASS/PARTIAL）。

## 5. 驱动自纠与假红处置（本轮内修复，未留假红入账）

| # | 现象 | 处置 | 类别 |
|---|---|---|---|
| 1 | INC 负反馈三场景因同项目同 trigger 唯一键（50012）与前轮已占用而失败 | 改为每轮动态选"双 PM 在位且该触发未占用"的 LANE4 项目（`nf_target`） | fixture |
| 2 | `AC-GLB-10` 首版探针受"项目已有池"干扰（uk_bp_project）误判 | 改为两个同参新项目分别 compute + 复原配置 | fixture |
| 3 | `AC-AI-07` grep 路径写成 `service/AiChatClient.java`（实际在 `service/ai/`）→ grep rc=2 命中 -1 | 修正检索域为 `service/ai/*`，命中 0 为真值 | fixture |
| 4 | `AC-AI-01` 跨轮 `uk_model_name` 撞名 → 409 | model 名带时间戳 `lane4-mock-<TS>` | fixture |
| 5 | `AC-GLB-09` salesSource/coefficient 全量命中含 javadoc，被误当 consumer | 增加"注释行剔除 + allowance.L3 阳性对照"，改判为代码级 0 | fixture（判据收紧） |
| 6 | `AC-INC-39` 原判 FAIL（"零赋值"） | 域情复核后改判 PARTIAL：赋值有（create 即写）、消费无 | 判据修正 |
| 7 | `AC-GLB-03` 绝对化文案命中 1 处 | 剔除注释/文档引用行后为 0（唯一命中是前端注释引用 ZK 源文档文件名）→ PASS | fixture |

串扰检查：全程单实例 16039、fixture 一律 `LANE4-` 前缀、其它车道未启动本域同名 fixture；未观察到需要标 FAIL-ENV 的串扰型失败（0 次重试轰炸）。

## 6. 已跑透的正向结论（服务层真可跑部分）

1. 阶梯矩阵 10 点全 PASS（每点独立新项目，HTTP 返回 + 真库 `bonus_pools.tier_coefficient` 双回读 + freeze 正常）：
   100→1.0 / 130→1.2 / 120→1.0 / 120.1→1.2 / 110→1.0 / 99.99→0.8 / 90→0.8 / 75→0.6 / 60→0.3 / 45→0（含开闭边界 120 与 120.1 的进档语义）。
2. 池公式复算一致（AC-INC-29/29b/29c/29d）：`finalPool = 实际回款 × 5% × 项目系数(S=1.5/A=1.0) × 阶梯 × 个人系数`，例 100 万回款 100% → 75000；算例 A 450 万→216000、B 650 万→585000、C 350 万(A 级)→105000，全部逐条复算相等。
   口径留档：清单算例用"目标销售额"旧口径（A 应得 30 万池），`[CONSISTENCY-1]` owner 裁决为"实际回款"基数 → 双口径已同时复算，不算缺陷但需文档收口。
3. 绩效系数档位（AC-INC-22/23/24）96→1.0、88→0.8、55→0 与 ≥95/≥85/≥70/≥60/<60 档位一致。
4. 上市日期双签（AC-INC-33）：市场 PM 提议 → 研发 PM 第二签通过 → `audit_logs` 双动作回读；互斥执法码证 LaunchDateChangeService L136-149/L215-228（提议人≠确认人、确认人须预落、须互补角色、同组防 IDOR）。
5. 负反馈三步链（create→submit→decide）与全链审计（AC-INC-40：本轮 9 条动作 CREATE/SUBMIT/DECIDE_EXECUTE 可回读，含 before/after 执行人）。
6. 审计域 7 卡全绿或已裁决：表级 `GRANT SELECT,INSERT`（无 UPDATE/DELETE）+ 应用层防篡改登记；`/audit-logs/verify` 出口 `chain/hashBroken/gaps` 四态齐备（hashBroken=0）；MARKET_PM 仅本人（scope=OWN）、组长本组（6 人集合）、超管 GLOBAL；未登录 401/20001；`audit_logs` 无 `updated_at` 列。
7. AI 文档治理链（除外呼）全绿：登记→未审核拒绝归档（50002）→ review→revise 生成 v2 且 v1 保留→`diff?from=&to=` 两版对比→`versions/history` 全链无缺失；密钥 AES 密文入库（`api_key_encrypted` 非明文）且 API 只回显脱敏；PM 与组长登记权限对等（三角色 code=0）。

## 7. fixture 与写库卫生

- 全部写入经 HTTP `/api/v1`，零直接 SQL 写（`L.sql()` 仅 SELECT/SHOW/information_schema）。
- fixture 命名一律 `LANE4-` 前缀（项目 `LANE4-INC-*`/`LANE4-TIER*`/`LANE4-CASE-*`/`LANE4-AI-P`，人员 `Mock-LANE4*`，产品 `LANE4P-*`，NF 证据 `LANE4-*`），多车道并发不互踩。
- 本轮四批执行 HTTP 写 126 次：bonus-pool 37（compute/freeze）、projects 31（含成员绑定/状态迁移）、products 21、ai-documents 9、person-sync 6、receipt-ledgers 4、ai-models 4、system-configs/allowance 等其余；`who` 分布 ipd-admin 106 / ipd-market 10 / ipd-leader 7 / ipd-rd 3；异常写入 22 次（负向用例与外部依赖失败，含 500/90001 AI 外呼、409 业务闸）。
- 写库清单已修复为跨批次累计（此前每批覆盖式落盘，故本文件登记的是最终一轮四批全量；早前轮次的写入见对应轮次的执行清单记录 note）。
- 复原声明：`bonus.salesSource` PUT 回原值 RECEIPT（GET 回读确认）；AI 启用面 `POST /ai-models/1/enable` 复原为原启用项 id=1（回读 `is_active=1` 集合一致）；不动非本车道 fixture（真库既有种子 9140001/9150001 等只读引用）。
- 遗留物：本轮为验证新增的项目/池/NF/模型配置行为业务数据留在 ipd_dev（前缀可批量识别），未做清理（清理需写/删接口，超出验收车道职责）。

## 8. 产物与复现

| 产物 | 绝对路径 |
|---|---|
| 库适配层 | `/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-AC续跑-20260925/lane4/r219_lib.py` |
| 驱动（GLB/AUD） | `…/lane4/lane4_run.py` |
| 驱动（INC/AI） | `…/lane4/lane4_run2.py` |
| 执行清单（90 条） | `…/lane4/执行清单-lane4.json` |
| 写库清单（126 次） | `…/lane4/写库清单-lane4.json` |
| fixture 台账 | `…/lane4/lane4_fixtures.json` |
| 归因报告 | `…/lane4/归因-lane4.md` |
| 取证探针脚本 | `…/lane4/probe.py`、`probe2.py`、`probe3.py`、`probe4.py` |

复现：`cd …/lane4 && python3 lane4_run.py glb && python3 lane4_run2.py inc && python3 lane4_run.py aud && python3 lane4_run2.py ai`（逐条落盘到执行清单；驱动按 `AC-<域>-` 前缀幂等覆写本域记录，不影响其他车道）。

## 9. 给下一波的建议（只登记，不立卡）

1. D-1 津贴接线（autoScan 只 COUNT）是本域最大功能空洞：一次 `@Scheduled` 或 `POST /allowance/generate` 出口即可解锁 AC-INC-03/05/06/07/08/10 六张卡的行为验证。
2. D-3 与 D-2 是一对：先定 G5 阶段落库路径（`current_stage` 与 `status` 职责分离），再解 `bonus_pools` 单行唯一键（版本链需 `(project_id, version)` 唯一），否则贡献度→distribute→重算三段都不可达。
3. D-4/D-5 属"配置面假绿"：建议统一"涉钱参数必须经 `system_configs` 读取 + 编码字典收口（is_veto 全量重编码为 '1'/'0'）"，并把 `GET /gate-elements` 加 status 过滤。
4. D-7/D-6 是负反馈执法闭环：BOTH 需落两名 PM（或按人拆两行执行），并让 `tier_delta/bonus_disqualify` 真正参与池/贡献度计算，`lift` 需回滚字段。
5. D-8 需要产品裁决：绑定上限是否应支持解绑/改绑出口；否则任何长波次验收都会因名额耗尽而不可复跑（本车道实测：第二轮起 NF 三场景已无法新做）。
6. AI 域需要一个可控 mock：建议提供一个本地 OpenAI 兼容端点（chat+embeddings）并允许 `budgetTokens` 经 API 配置，才能把 AC-AI-02/08 从 FAIL-ENV/PARTIAL 推进到可判定。
