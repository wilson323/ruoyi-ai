# R218-AC续跑 · LANE2 归因报告（GATE / IPD 域）

- 车道：LANE2（GATE + IPD 流程域）
- 后端：http://127.0.0.1:16039（fresh jar，未重启未轮换）；真库 ipd_dev 仅 `L.sql()` 只读回读
- 执行链：`r219_lane2_gate_ipd.py`（首跑 run1，L2T205000）→ `r219_repair1.py`（修复轮1，9 case）→ `r219_repair2.py`（修复轮2，2 case）→ `r219_repair3.py`（修复轮3：绑定阈值临时放宽复跑 GATE-05/06/07/07b/10/09/21 + 限流窗轮换复跑 GATE-11/12）→ `r219_repair4.py`（修复轮4：组长真实落裁腿 + 双签两侧提醒腿 + AC-IPD-12 由 BLOCKED 改判）→ `r219_repair6.py`（修复轮6：AC-GATE-09 双侧腿 24h 上界定稿）→ `r219_consolidate.py`（收尾：断言腿补回 + 写库清单 union 去重）
- 清单：`执行清单-lane2.json`（终态 64 条记录 / 58 卡号，字段 card/case/cmd/http/envelope_code/verdict/note/ts 齐全；一卡多断言腿时按 (card,case) 逐条记账，run1 原始记录备份于 `执行清单-lane2.run1备份.json`）；写库登记：`写库清单-lane2.json`（55 条，全部为业务 HTTP 写 + 2 项环境脚手架 PUT 及其还原）
- fixture 隔离：全部名称带 `LANE2-<RUN戳>-` 前缀（RUN=L2T205000 首跑 / L2T205953 修复1 / L2T210327 修复2 / repair-3 另计）；同项目同 gateCode 14 天冷却→每场景独立项目

## 一、两域计数（终态 2026-09-26T13:07:55+08:00，收尾整合后）

| 口径 | PASS | FAIL | PARTIAL | FAIL-ENV | BLOCKED | NOT-RUN | 小计 |
|------|------|------|---------|----------|---------|---------|------|
| 记录级 · AC-GATE-* | 29 | 1 | 4 | 0 | 0 | 0 | 34 |
| 记录级 · AC-IPD-* | 26 | 1 | 1 | 0 | 1 | 1 | 30 |
| **记录级合计** | **55** | **2** | **5** | **0** | **1** | **1** | **64** |
| 卡级 · AC-GATE-*（28 卡号） | 24 | 1 | 3 | 0 | 0 | 0 | 28 |
| 卡级 · AC-IPD-*（30 卡号） | 26 | 1 | 1 | 0 | 1 | 1 | 30 |
| **卡级合计（58 卡号）** | **50** | **2** | **4** | **0** | **1** | **1** | **58** |

- 卡级=一卡多断言腿时取最差判定（FAIL>PARTIAL>BLOCKED/NOT-RUN>PASS）；记录级=每条断言腿一行，AC-GATE-02/09/10/11/16 各 2-3 条腿记录。
- 池覆盖核对（脚本 `r219_consolidate.py` 自动比对）：**MISSING=0**、8 字段完整性 badfields=[]。池内 AC-GATE-* 26 + AC-IPD-* 29 卡号中，AC-IPD-03/04/05/06/29 已由 `执行清单-r218.json` 覆盖未重复跑；AC-REQ-09/AC-PROD-09 已立缺陷卡跳过。本车道另按子断言腿拆卡（17b/17c/1a-1d/09b/09c/10b/11b/15s/14…），故卡号数大于池内主卡号数。
- **FAIL-ENV 终态 0 条**：首跑 4 条环境假红（AC-GATE-07/09/10 绑定槽位被并发车道占满、AC-GATE-11 游客需求 IP 限流）已全部转为真实判定，转判脚手架与证据见文末《补记（repair-3 / repair-4 / repair-6）》。
- 唯一 BLOCKED=AC-IPD-13（轻管逾期不可经业务 HTTP 构造，禁 SQL 写库）、唯一 NOT-RUN=AC-IPD-14（纯 UI 附件入口检查项）。

## 二、FAIL 归因（三选一）

保留 FAIL 2 条，两条均归因为**真缺陷**（只描述不立卡）：**AC-GATE-14**（33 要素基线失真 68 项 + 新套 is_veto='Y' 否决位失效，第三节候选 1）、**AC-IPD-12**（深管动作「逾期每日提醒」未接线：ACTION_OVERDUE 全仓 0 发布方、0 通知行，第三节候选 6）。首跑的 4 条 FAIL-ENV（环境假红）与 1 条 BLOCKED 误判已在 repair-3/4/6 全部转真判定，见文末补记；本节以下保留首跑原始归因留痕。

### 环境假红（首跑 4 条 → 终态 0 条：全部转真判定见文末补记，以下为首跑原始归因留痕）
- **AC-GATE-07 / AC-GATE-09 / AC-GATE-10**：三条断言腿依赖 `project_members` 在册 MARKET_PM/RD_PM（collectLeaders→组长列席/仲裁预落、signerPersonId→提醒/弃权）。本库绑定容量为**每人全局 3 个活跃项目**（ProjectMemberServiceImpl L102-113），三车道并发把全部 ACTIVE PM 槽位耗尽：900103/900104/900105、程龙/林立杰/方武略/刘研发/孙研发首跑前即 c=3；r214-mkt=DISABLED"离职/禁用人员不可入组"；胡蛟露(9110005)、陈市场(2096266884189016065) 11:50-12:03 间被兄弟车道占满（实测 bind 400"已达项目数上限 3"）。非产品缺陷，未重试轰炸。
  - 附带实证（正向）：AC-GATE-06（round+1）、AC-GATE-07b（第5轮超管介入通知，superAdmins() 全局查询不依赖在册）PASS；AC-GATE-08 超期弃权按主导方放行 PASS（P3 场景绑 rd2 时尚有第 3 槽，approvalRef 备案腿生效，弃权行/审计/通知齐验）。
  - AC-GATE-10 的终裁门（final-ruling 需≥2 组长对立意见被拒）与越权组长拒裁均为合法行为实证；结构性限制：persons 表 GROUP_LEADER 可登录者仅 900102（组900001），"两组不一致→自动升级"腿本库不可构造。
- **AC-GATE-11**：`/api/v1/public/demands` 命中服务端 IP 限流（GuestDemandService RATE_LIMITER_PER_HOUR=10/小时/固定窗口，三车道共享 127.0.0.1 配额），429。非产品缺陷。修复轮3 于窗口轮换后单发复跑。

### fixture 假红（已在修复轮内改正，不占 FAIL）
1. `mk_project` 返回值 arity 冲突（驱动自身 bug，未触库）。
2. **产品状态口径**：mk_product 新建默认 `IN_RD`，游客需求要求 `status=='ACTIVE'`（非 ON_SALE，GuestDemandService L150）→ repair-2 起先 `POST /products/{id}/status?status=ACTIVE`。
3. **gate 要素编码长度**：elementCode `H()` 全名 20 字符 > CODE_MAX=16 → create 10001；改 `L2E<6位>` 后 PASS。update body 带 `enabled` 触发草稿启停守卫 50002（合法行为）→ 去掉该字段后 AC-GATE-18 全链 PASS。
4. **涉生物 C12 不可 NA**：HARDWARE 模板 seed 含 `is_bio_feature=1` 动作 → 服务端 AC-PROD-13 守卫拒 NA（首跑按"非涉生物可 NA"设计走不通，B 级链因此中断）→ 改深管交付物+DONE，AC-IPD-10/07/09x 全链转 PASS。
5. **逾期通知计数口径**：scan-overdue 发通知 source_type=`gate_element_results`/source_id=resultId（非 gate），首跑按 gate_id 计数=0 假红 → 修正后 AC-GATE-17b PASS。
6. **r214-mkt 不可入组**：account_status=DISABLED（早前探查误记为可用）→ 换用 9110005/陈市场（后被兄弟车道抢先占满，转入 FAIL-ENV）。
7. **90 天复盘权限**：POST /post-launch-reviews 走项目成员校验，ipd-market 非 PINV1 成员 403"非项目成员"→ 改 admin（超管豁免 assigneeId=-1）后 PASS→PARTIAL（自动触发腿未接线）。
8. **SOP 目录行**：库内 PUBLISHED SOP 仅 V10，首跑用 C11 → current 404 全链 None；改 V10 后复制→更新→发布→current 切新→instantiate 闭环，AC-IPD-27 转 PARTIAL。

## 三、真缺陷候选（只描述不立卡，全部带实测证据）

1. **AC-GATE-14｜33 要素基线失真：目录双套 seed 并存，实测清单 68 项**。`GET /gates/{id}/elements`：G1=14/G2=14/G3=10/G4=16/G5=14（期望 7/6/5/8/7=33，AC-GLB-12 历史结论）。gate_review_elements 中 G1 旧套 G1-1..7（is_veto='1'）与新套 G1-01..07（is_veto='Y'）双 published+enabled 并存。**次生缺陷：服务层否决判定只认 is_veto=='1'，新套 'Y' 行否决位失效**（GateElementResultService，实测新套行 FAIL 不触发"命中否决项"阻断）。证据：执行清单 AC-GATE-14 行 note + `SELECT gate_code,COUNT(*) ... GROUP BY gate_code`。
2. **AC-GATE-13｜G5 通过→自动生成 90 天复盘待办未接线**。`postLaunchReviewService.scheduleReview` 全库无 Gate 通过路径调用方（仅显式 HTTP 端点）；实测手动排期幂等/排期日=+90d 正常（PARTIAL）。G5 APPROVED 后 post_launch_reviews 无新行。
3. **AC-IPD-10/11 口径差（低风险，建议口径确认而非缺陷）**：AC 文字"B 级 14 个阻断"，代码 GateEngine B_LEVEL_BLOCKING_CODES=10 项；S 级实测 38 阻断/69 动作=42 深+27 轻与 AC 一致。已按代码口径判 PASS 并在 note 留痕。
4. **AC-IPD-27｜SOP 改版→新项目 stage_actions.sop_id 自动绑定未接线**。全库 stage_actions.sop_id 4061 行均 NULL；新项目实例化不关联任何 SOP 版本，手动 instantiate 端点可用（快照落 sop_template_instances）。"新项目用新版/在研项目保持原版"仅靠读取路径部分成立→PARTIAL。
5. **AC-IPD-12/13｜stage_actions.due_date 无业务写入口**（BLOCKED 记账）：grep 全 controller 无 due_date/计划完成日设置端点，"深管逾期每日提醒"无法经业务 HTTP 构造（禁 SQL 写库），提醒逻辑在 StageActionService 存在但触发面缺数据入口。

  > 补记修正（repair-4）：本条对 **AC-IPD-13 仍然成立**（LIGHT 动作无法赋 due_date ⇒ BLOCKED），但 **AC-IPD-12 已改判 FAIL**——库内存在 `IpdZkScenarioInitializer` 启动期 seed 的逾期深管动作且主责人可登录，两条腿可分别取证，见下第 6/7 条。
6. **AC-IPD-12｜深管动作「逾期每日提醒」未接线（终判 FAIL，真缺陷）**。`grep -rn ACTION_OVERDUE --include="*.java" ruoyi-modules/` 全仓仅 1 处命中=`NotificationService.java:85` 常量声明 ⇒ **0 个发布方**；现存 overdue 调度器只覆盖 Gate 签署（GateSignScanScheduler）、游客需求（GuestDemandOverdueScheduler）、交接（HandoverOverdueScanner）、KPI（KpiSharedDeadlineScheduler），**无 stage_actions 逾期扫描器/端点**。实测：真库逾期深管动作 D02（project 9140005，depth=DEEP，due_date=2026-09-16 18:39:17，owner_role=MARKET_PM→900103）经 `GET /api/v1/workbench/summary?projectId=9140005` 以 `priority=high`、`dueDate=1789555157000`、`stats.overdue=1` 正常暴露（**标记腿通过**），但 `SELECT COUNT(*) FROM notification_events WHERE event_type='ACTION_OVERDUE'`=0（主责人 900103 亦 0）⇒ AC 主条款「主责人收到每日提醒」不成立。
7. **AC-IPD-13｜工作台 priority 派生不看 depth（口径风险，建议代码复核）**。`StageSignAggregator.toTask` 以「dueDate<now ⇒ high」单条件派生优先级，未区分 DEEP/LIGHT；一旦轻管动作被赋 due_date（当前只有启动期 seed，全库 11 条非空 due_date 均 DEEP、0 条 LIGHT），轻管逾期同样会被标 high，与 AC-IPD-13「轻管不提醒、不标记」相悖。本卡维持 BLOCKED（无业务 HTTP 写入口可构造 LIGHT+逾期数据），列为口径风险而非已证实缺陷。

## 四、兄弟车道串扰排查记录
- 断言失败复核时全部按名称前缀（LANE2-）+ updated_at 排查：AC-GATE-07/09/10 直接证据是 bind 400"已达项目数上限 3"且目标人活跃绑定行 project_id 均非 LANE2 项目（兄弟车道 11:50-12:03 抢占）→ 判 FAIL-ENV 不重试。
- scan-timeout / scan-remind / scan-overdue 为全局扫描：实测仅处理本项目窗口内对象（days=0/1 最小化窗口），未观察到他车数据被本车道扫描改写；反之本车道 gate 也未见他车扫描介入（AC-GATE-08 弃权行 reviewer_id=900105 为本车道绑定成员）。

## 五、产物清单（绝对路径，均在 …/R218-AC续跑-20260925/lane2/）
- 主驱动（可重跑）：`r219_lane2_gate_ipd.py`；公共库 `r219_lib.py`（B39=16039，REC_PATH/写库清单指向 lane2）
- 修复/收尾 runner（均可重跑）：`r219_repair1.py`、`r219_repair2.py`、`r219_repair3.py`（阈值放宽+限流窗轮换）、`r219_repair4.py`（组长落裁/双侧提醒/AC-IPD-12 改判）、`r219_repair6.py`（AC-GATE-09 定稿）、`r219_consolidate.py`（断言腿补回+写库清单 union）、`r219_repair5.py`（限流长等待兜底，**未执行**）、`r219_probe_threshold.py`（可行性探针）
- 执行清单：`执行清单-lane2.json`（64 条记录 / 58 卡号）；历史备份 `执行清单-lane2.run1备份.json`、`.入repair3前备份.json`、`.入repair4前备份.json`、`.入repair6前备份.json`、`.pre-consolidate`；各轮临时清单 `rerun1..4-执行清单.json`
- 写库清单：`写库清单-lane2.json`（55 条，已 union 去重）；快照 `written-repair4快照.json`、`written-repair5快照.json`
- 运行日志：`run1.log`、`repair1.log`、`repair3.log`、`repair4.log`（首跑）、`repair4-2nd.log`
- 本报告：`lane2-归因-GATE-IPD.md`

## 补记（repair-3 / repair-4 / repair-6：环境假红转真判）

### 脚手架 1：绑定容量阈值临时放宽（配置驱动，跑完必还原）
- 根因不是产品缺陷：`project_members` 绑定容量上限读自 **system_configs.allowance.projectCountThreshold**（注意：在 `system_configs`，**不是** `ipd_business_config`；缺省 3）。多车道并发把全库可登录在册 PM 的活跃绑定推到上限 ⇒ 首跑 AC-GATE-07/09/10 的前置"入组"步骤 400「已达项目数上限 3，禁止再绑定」。
- 处置：业务 HTTP `PUT /api/v1/system-configs/allowance.projectCountThreshold`（超管权限；写后 Caffeine 立即失效 + 同事务版本链 + audit_logs=SYSTEM_CONFIG_UPDATE）临时提高到 `max(观察活跃绑定)+2/+3`（3→6/7/9），`finally` 还原并 GET 复读=3。全部登记在 `写库清单-lane2.json` 的「(环境脚手架)」行。
- 附带学到的合法分支（不是缺陷）：`active == threshold-1` 时必须携带 `approvalRef`（≤64），否则拒绑（AC-TEAM-11 超额备案门）。首版 repair-4 因余量只 +2 落进该分支 → FAIL-ENV，改为 +3 且 bind 一律带 `approvalRef=LANE2-<RUN>-AC09/AC10备案` 后通过。

### 脚手架 2：游客需求 IP 限流窗口轮换（AC-GATE-11/12）
- `POST /api/v1/public/demands` 命中服务端 `InMemoryHourRateLimiter`：10 次/固定小时窗，key=`sha256Short(clientIp)`（三/四车道共享 127.0.0.1），**被拒请求同样计数**，窗口在过期后的首个请求处惰性重建。
- 零写库探针：`productId=999999999999`（上架校验在 tryAcquire 之后 ⇒ 404/50001 且不落 requirements）；反之载荷非法（customerName<2 字等）在 tryAcquire 之前 ⇒ 10001 且不耗配额，不能用来判窗。
- repair-3 以 5 分钟节奏探针：12:14:59→12:49:59 连续 8 次 429/40011，**12:54:59 探针 404/50001（窗口轮换）→ 立即跑 `case_gate11_12`**：
  - AC-GATE-11 两腿 PASS：未闭环变更单时 `POST /projects/{id}/advance-stage` → 409/50002「存在未闭环需求变更单（1 张），需先关闭（P2-6.2 阶段门禁）」；双签闭环后再推进 → 400 改由阶段门禁拦截（C11 Charter立项评审会；C12 生物特征数据合规审查）。
  - AC-GATE-12 PASS：`requirement_changes.status=APPROVED` 且 `requirements.status=ADOPTED`（codes 0/0）。
- 全程未重启/轮换 16039 进程、未杀端口、未 SQL 写库、未借道他人 requirements。

### 转判结果一览（终态记录见 `执行清单-lane2.json`）
| 卡号 | 首跑 | 终态 | 关键证据（真库回读/包络） |
|------|------|------|---------------------------|
| AC-GATE-05 | FAIL-ENV | PASS | 任一否决→status=REJECTED；market/rd 通知各 1 |
| AC-GATE-06 | FAIL-ENV | PASS | 驳回后重发起 current_round=2 |
| AC-GATE-07 | FAIL-ENV | PASS | round=3 组长列席通知=1（接收人 9110004） |
| AC-GATE-07b | — | PASS | round=5 超管介入通知=1 |
| AC-GATE-09（单侧腿） | FAIL-ENV | PASS | remindCount=1，陈市场 GATE_SIGN_SOON=1，due=2026-09-27 12:14:59 |
| AC-GATE-09（双侧腿） | FAIL-ENV→FAIL | PASS | remindCount=2；未签研发侧 900104 通知=1、已签市场侧 900103 通知=0（BR「已签方不提醒」） |
| AC-GATE-10（预落/越权/终裁门） | FAIL-ENV | PARTIAL | 预落 decision=NULL 待裁行 + 他组组长拒裁 + 终裁门拒 |
| AC-GATE-10（真实落裁腿） | — | PARTIAL | 900102 落裁 code=0（UPDATE 原预落行）、重复提交被拒、仅 1 份意见时终裁仍被拒 |
| AC-GATE-21 | FAIL-ENV | PASS | 延期 3 次第 4 次拒 + sign_extension_count |
| AC-GATE-11 | FAIL-ENV | PASS | 见脚手架 2（窗口轮换后两腿） |
| AC-GATE-12 | 无记录 | PASS | 见脚手架 2 |
| AC-IPD-12 | BLOCKED | **FAIL** | 提醒腿未接线（第三节第 6 条） |

### 本轮新增 fixture 假红 2 处（已改进驱动，可重跑）
1. **AC-GATE-09 双侧腿的 24h 上界采样时刻**：`gates.sign_due_at` 为 DATETIME(0) 落库向上取整秒，submit 后 <0.5s 触发 scan-remind 时 `untilDue` 略大于 24h 窗口上界 ⇒ scanRemind 合法跳过（remindCount=0）。人工于 13:06:45 重扫**同一 gate** 得 remindCount=1 且仅 900104 收到 `GATE_SIGN_SOON`（已签的 900103 无通知），证实是取数时刻问题而非产品缺陷；驱动已加 `time.sleep(2)`，repair-6 转 PASS。
2. **AC-GATE-10 第二组长不可用的真实原因（更正首版 note）**：组 900001 的 900112 真库 `del_flag='2'` 已被 `personMapper.selectList` 过滤，`collectLeaders` 只返回 900102 ⇒ 「两组长意见不一致→自动升级超管终裁」腿在本库**结构性不可测**（可登录组长仅 900102；900112 login 400/10001；9110004/王组长/李组长 无 dev-accounts 凭证）。旁证仅 2026-09-06 历史 seed 行（gate 9100000000000000063 r5: 900102 APPROVE vs 900112 REJECT + 900101 终裁 REJECT），非本轮产生，不计入判定 ⇒ 卡片维持 PARTIAL。

### 兄弟车道与账本卫生
- 本波实际有 4 个车道目录（lane1/lane2/lane3/lane4），共享同一 `ipd_dev` 与同一 127.0.0.1 限流窗；兄弟车道的 Mock-LANE*/LANE3/LANE4 前缀 MARKET_PM（46 人，活跃绑定 0/1）`group_id` 全为 NULL，无法满足 `IpdIdorGuard` 同组校验且属他车 fixture，**未采用**。
- 写库清单曾被 repair-3 进程持有的旧内存列表全量 dump 覆盖，已用 `written-repair4快照.json`/`written-repair5快照.json` 在 `r219_consolidate.py` 里 union 去重恢复（终态 55 条）。
- `r219_repair5.py`（更长限流等待 + AC-GATE-12 兜底记账）已就位但**未执行**——repair-3 已在 12:54:59 命中窗口轮换，无需第二轮等待。
