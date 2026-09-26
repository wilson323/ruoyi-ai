# R218-AC 续跑 · LANE1 归因注记（AUTH / TEAM / HR / CFG / ENV）

- 车道：**L1（AUTH/TEAM/HR/CFG/ENV 五域）** ｜ 波次：R218-AC续跑-20260925 ｜ 波次号 RUN=**205339**
- 仓库：`/Users/mac/Documents/ruoyi-ai`（IPD 后端 ruoyi-modules/ruoyi-ipd） ｜ 被测实例：`http://127.0.0.1:16039`（fresh jar，全程未重启/未构建/未杀端口）
- 驱动：`r219_lane1.py`（可重跑；公共库 `r219_lib.py`，REC_PATH/写库清单指向 lane1）
- 复现命令：
  - 全新跑：`cd .../lane1 && python3 r219_lane1.py`（自动取 HHMMSS 作 RUN，建 LANE1- 前缀夹具）
  - 续跑（复用本波夹具、跳过已判定卡）：`R219_RUN=205339 python3 r219_lane1.py`
  - 指定卡重判（先剔除旧记录再真执行）：`R219_RUN=205339 R219_RERUN="AC-AUTH-03 AC-AUTH-06" python3 r219_lane1.py`
- 判定台账：`执行清单-lane1.json`（41 条 = 五域 41 卡全覆盖，逐条带 HTTP/envelope/真库回读/时间戳）
- 写入台账：`写库清单-lane1.json`（59 条，全部经业务 HTTP 接口，零直连 SQL 写库）
- 去重：`执行清单-r218.json` 已覆盖的本域卡仅 **AC-ENV-04**（verdict=BLOCKED），本车道据规则不重复跑并记 NOT-RUN；AC-REQ-09/AC-PROD-09 非本车道域。
- sysadmin：本车道 41 卡中 **0 卡**依赖 sysadmin（信源已删除该账号），故无因此 NOT-RUN 的卡。

## 一、五域计数

| 域 | 卡数 | PASS | PARTIAL | FAIL | FAIL-ENV | BLOCKED | NOT-RUN |
|---|---|---|---|---|---|---|---|
| AUTH | 11 | 5 | 4 | 1 | 0 | 0 | 1 |
| TEAM | 13 | 5 | 3 | 3 | 0 | 0 | 2 |
| HR | 8 | 5 | 0 | 0 | 0 | 0 | 3 |
| CFG | 3 | 1 | 0 | 2 | 0 | 0 | 0 |
| ENV | 6 | 2 | 2 | 0 | 0 | 0 | 2 |
| **合计** | **41** | **18** | **9** | **6** | **0** | **0** | **8** |

PASS 明细：AC-AUTH-01, AC-AUTH-07, AC-AUTH-08, AC-AUTH-10, AC-AUTH-11, AC-CFG-03, AC-ENV-02, AC-ENV-06, AC-HR-03, AC-HR-04, AC-HR-05, AC-HR-07, AC-HR-08, AC-TEAM-02, AC-TEAM-03, AC-TEAM-11, AC-TEAM-12, AC-TEAM-13

## 二、FAIL 逐条归因（三选一 + 修复建议）

### AC-AUTH-09 ｜ 真缺陷 ｜ http=200 code=0 ｜ ts=2026-09-26T12:02:36+08:00
- **归因**：真缺陷（三选一）
- **现象与证据**：同组非成员 PM 越权读他人项目全量详情：以 `ipd-rd`（RD_PM，同组 900001，未绑定该项目）调 `GET /api/v1/projects/{LANE1-P-A}` → `http=200 code=0`，返回体含 `name=LANE1-P-A-205339`；AC 预期 `3xxxx 或无数据，不泄露项目名称等任何字段`。
- **落点**：`ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/ProjectController.java` 的详情查询无“成员/组长/超管”可见性谓词（`IpdPermission` 已有 sameGroup/leader 判定但该方法未接入）
- **修复建议**：在 `ProjectController.get`（及 project 详情 service）加可见性判定：非项目成员且非组长/超管 → 抛 `ApiV1ErrorCode.FORBIDDEN(30001)`，或返回裁剪视图（仅 id+脱敏字段）。注意同组≠可见：AUTH-10 的“组长可看本组”与 AUTH-09 的“同组 PM 不可看”必须以成员关系为界。
- **台账行**：`执行清单-lane1.json` → card=AC-AUTH-09, case=AUTH09-peer-project, cmd=`GET /api/v1/projects/2103694596738428930 (creator=-1, ipd-rd 非creator同组PM)`
- **原始 note（含 HTTP/envelope/真库回读）**：http=200 code=0 返回name=LANE1-P-A-205339 —— 预期'3xxxx或无数据不泄露项目名称'。归因=真缺陷候选(同组RD_PM可读他人项目全量详情,src ProjectController.get 无同组他人隔离)。ts=2026-09-26T12:02:36+08:00

### AC-TEAM-01 ｜ 真缺陷 ｜ http=200 code=0 ｜ ts=2026-09-26T11:54:11+08:00
- **归因**：真缺陷（三选一）
- **现象与证据**：定向邀标发布后两个断言同时不成立：① 被邀人未收到通知（`notification_events` 中被邀 rd 计数=0，预期 >0）；② 列表可见性未过滤，非受邀的 `ipd-leader` 在 `GET /api/v1/bid-invitations` 仍能看到该 `ONE_TO_ONE` 单（=True，预期 False）。夹具单 id=2103694597292077058，create/publish 均 `http=200 code=0`。
- **落点**：`BidInvitationService.publish` 未接通知派发；`list` 查询未对 `mode=ONE_TO_ONE` 做受邀人过滤
- **修复建议**：publish 成功后按受邀人逐个写通知（复用 `NotificationService`/notification_events，事件类型如 `BID_INVITED`），并在列表 SQL/Wrapper 上加 `mode='PUBLIC' OR invitee_person_id = :me` 条件；补一条单测锁死“定向单不可被第三人看见”。
- **台账行**：`执行清单-lane1.json` → card=AC-TEAM-01, case=TEAM01-invite, cmd=`POST /bid-invitations(ONE_TO_ONE->rd) + publish; leader GET /bid-invitations; SQL notification_events`
- **原始 note（含 HTTP/envelope/真库回读）**：创建http=200 code=0 id=2103694597292077058; publish http=200 code=0; 被邀人rd通知数=0(预期>0); 非受邀者(leader)列表可见该单=True(预期False)。命中真缺陷:publish不发送通知且list无可见性过滤 ts=2026-09-26T11:54:11+08:00

### AC-TEAM-08 ｜ 真缺陷 ｜ http=- code=None ｜ ts=2026-09-26T12:02:37+08:00
- **归因**：真缺陷（三选一）
- **现象与证据**：到期不自动关闭也不挂起：夹具 `LANE1-INVX-205339`（id=2103694600123232258，expire_at 已过期 ≥10 分钟）真库回读 `status=OPEN`（预期 EXPIRED 自动关闭），到期通知 0 条；同波另一条 `INVX` 亦同态。
- **落点**：`BidInvitationService.expireOverdue()` 在整个模块**零调用方**——无 `@Scheduled`、无手动端点（代码考古 + 实测双证）；AC-TEAM-06（到期前 3 天提醒）/AC-TEAM-07（7 日未遴选升级）同源，故三卡一起卡在这条未接线的链路上（06/07 记 NOT-RUN）。
- **修复建议**：二选一或并行：① 注册调度 `@Scheduled(cron=`${bid.expireCron}`)` 调 `expireOverdue()`；② 暴露 `POST /api/v1/bid-invitations/expire-scan`（限 SUPER_ADMIN，幂等），并在关闭时写 EXPIRED 通知 + 挂起态与后续 30 日升级判定（TEAM-09 的 admin-assign 依赖 `EXPIRED 且挂起≥30日`，本卡不通则 TEAM-09 正向半边永不可达）。
- **台账行**：`执行清单-lane1.json` → card=AC-TEAM-08, case=TEAM08-auto-close, cmd=`LANE1-INVX(expireAt=+30s) 建立后等待>75s回读 status + 到期通知`
- **原始 note（含 HTTP/envelope/真库回读）**：status=OPEN(预期EXPIRED自动关闭); 到期通知=0条。若仍OPEN: 归因=真缺陷候选——BidInvitationService.expireOverdue() 全模块零调用方(无@Scheduled/无手动端点),到期关闭与挂起链路整体未接线(代码考古+实测双证)。通知有=0。ts=2026-09-26T12:02:37+08:00

### AC-TEAM-10 ｜ 真缺陷 ｜ http=200 code=0 ｜ ts=2026-09-26T11:54:11+08:00
- **归因**：真缺陷（三选一）
- **现象与证据**：角色互斥在“应标侧”缺失：市场 PM（`ipd-market`, MARKET_PM）对研发侧邀标提交应标 → `http=200 code=0` 且 `bid_responses` 真库落 1 行（已即时 withdraw 清理，不残留）。预期 `40004 ROLE_LOCKED / 30001` 拒绝。
- **落点**：`BidResponseService.submit` 全文无 `person_type` 判定；而成员绑定侧已实现同类互斥（`ProjectMemberServiceImpl` L89-92 抛 “角色互斥：人员类型 X 不可绑定为 Y”，本轮 AC-HR-08 实测 400/10001 生效）⇒ 同一业务规则两处实现不一致
- **修复建议**：在应标入口按标的所属角色域校验 `person.getPersonType()`，不一致抛 `ROLE_LOCKED(40004)`；把该校验与 bindMember 的互斥判定抽成单一策略方法（如 `IpdRoleExclusion.assertBindable`）避免再次漂移。
- **台账行**：`执行清单-lane1.json` → card=AC-TEAM-10, case=TEAM10-self-rdpm, cmd=`market POST /bid-responses accept @INV2(PUBLIC)`
- **原始 note（含 HTTP/envelope/真库回读）**：市场PM 应标研发侧未被拒绝: http=200 code=0 且落库 bid_responses 行数=1(已即时withdraw清理)。预期应 40004 ROLE_LOCKED/30001。归因=真缺陷候选(应标服务无角色互斥校验,BidResponseService.submit 全文无 person_type 判定);已复现并登记。ts=2026-09-26T11:54:11+08:00

### AC-CFG-01 ｜ 真缺陷 ｜ http=- code=None ｜ ts=2026-09-26T12:06:59+08:00
- **归因**：真缺陷（三选一）
- **现象与证据**：“系数面值零硬编码”不成立。按业务系数口径检索（原始命中 44 行，剔除 @Size/长度/超时/分页/注释/默认回退常量/测试等噪声后 **7 行真实硬编码**）：`KpiSharedCollectionService.java:141 K04_WEIGHT=new BigDecimal("0.05")`、`ProjectService.java:91 BONUS_POOL_RATE=new BigDecimal("0.05")`、`ProjectService.java:657 与 :684 requireCoefficient(coefficient,"1.5","2.0",…)`、`BonusPoolService.java:289 兜底 new BigDecimal("0.05")`、`:301 COEFFICIENT_S_MIN=new BigDecimal("1.5")`、`:443 文案内嵌 [1.5, 2.0]`。
- **落点**：系数/权重/区间面值以 `static final` 常量落在 service 层，未走 `ipd_business_config`/`system_configs`
- **修复建议**：把 K04 权重、奖金池比例、S 级系数上下界提为 `BusinessConfigKeys`（如 `kpi.k04Weight`、`bonus.poolRate`、`bonus.coefficientRange.S`）并配置优先、常量仅作兜底（兜底需带日志/暴露实际生效源）；文案里的 `[1.5, 2.0]` 改为按实际配置值拼装，避免配置与提示语不一致。
- **台账行**：`执行清单-lane1.json` → card=AC-CFG-01, case=CFG01-hardcode, cmd=`grep -rE '系数面值(1000/1500/.../0.05/1.5)' ruoyi-ipd/src/main/java`
- **原始 note（含 HTTP/envelope/真库回读）**：原始命中44行; 扣除噪声口径(@Size/长度/超时/分页/注释/默认回退常量/测试)后业务系数硬编码命中7行。样例: ["/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/KpiSharedCollectionService.java:141:    static final BigDecimal K04_WEIGHT = new BigDecimal(\"0.05\");", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ProjectService.java:91:    public static final BigDecimal BONUS_POOL_RATE = new BigDecimal(\"0.05\");", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ProjectService.java:657:            case \"S\" -> requireCoefficient(coefficient, \"1.5\", \"2.0\", \"S 级系数区间为 1.5–2.。ts=2026-09-26T12:06:59+08:00

### AC-CFG-02 ｜ 真缺陷 ｜ http=200 code=0 ｜ ts=2026-09-26T12:02:36+08:00
- **归因**：真缺陷（三选一）
- **现象与证据**：改配置对签署期限不生效（双配置源漂移）。真执行完整 `GATE_SUBMIT` 链：先 `PUT /api/v1/system-configs gate.signDeadlineDays=5`（`http=200 code=0`，响应 `invalidated=true`，真库直读=5，`GET` 亦=5）→ 新 Gate id=2103696715994411009 的 14 项启用要素逐项判定 14/14 成功 → `POST /api/v1/gates/{id}/submit`（materialsOssId/meetingMinutesOssId=990001/990002）`http=200 code=0` → 真库回读 **`sign_due_at - started_at = 3.0 天`**（不是 5），3 正等于 `ipd_business_config.gate.signDeadlineDays=3`。实验值已回滚（终值=3）。
- **落点**：`GateElementResultService.resolveSignDeadlineDays()`（L465-475）优先 `IBusinessConfigService(ipd_business_config)`，仅在其抛异常时回退 `system_configs`；而 `GateReviewService`（L95/405/410）与 `/api/v1/system-configs` 读写的是 `system_configs` ⇒ 同名 key 两源并存、UI 改的是一个、生效判定用的是另一个。同类风险也在 `resolveMinCustomerVerifications()`（L212-221，G1-1 客户验证阈值）。
- **修复建议**：确立单一权威源：要么把 `system_configs` 中该 key 的读写代理到 `ipd_business_config`（并在响应体回显“实际生效源”），要么迁移数据后删除其中一源 + Flyway 清理脚本；同时给 `resolve*()` 的“配置源优先级”补一条契约测试（PUT 新值→下一次 submit 的 due 差必须等于新值）。
- **台账行**：`执行清单-lane1.json` → card=AC-CFG-02, case=CFG02-gate-deadline, cmd=`PUT gate.signDeadlineDays=5 -> POST /projects/P-B/gates?gateCode=G1 -> POST /gates/{id}/element-results x14 -> POST /gat`
- **原始 note（含 HTTP/envelope/真库回读）**：改值 http=200 code=0; 新Gate id=2103696715994411009 code=0; 要素判定 14/14 成功(失败样本=-); 提交评审 http=200 code=0 msg=ok; sign_due_at-started_at = 3.0 天(预期5;实测若=3 即双源漂移: ipd_business_config.gate.signDeadlineDays=3 优先于 system_configs=5, 见 resolveSignDeadlineDays L465-475); 回滚 http=200 终值=3。归因=真缺陷候选:双配置源漂移,GATE_SUBMIT 只认 ipd_business_config,/system-configs 改值对签署期限不生效 ts=2026-09-26T12:02:36+08:00

## 三、PARTIAL 缺口（9 条：已真执行的部分 + 未覆盖部分及原因）

| 卡 | http/code | 已真执行 | 未覆盖半边与原因 | 归因 |
|---|---|---|---|---|
| AC-ENV-01 | -/None | TCP 探测 4 组件 + MySQL 实况 | 验收清单基线为 Windows+PG+Redis6379+MinIO，实为免 Docker MySQL@13306/Redis@16379，清单前提不成立 | 环境基线漂移 |
| AC-ENV-05 | -/None | 静态检索 dev 脚本直连库 | 仅命中 `scripts/start-prod.sh:39 docker-compose up -d`（生产脚本），开发期直连自动执行面未复现 | 环境基线漂移 |
| AC-AUTH-03 | 400/10001 | 未登录 401/20001、原密码错 400/10001、新密码<8 位 400/10001 且哈希未被写 | 正向半边（改密成功→旧密码失效+审计）未执行：端点不收 personId 只能改自己，池内 4 账号为兄弟车道共享，成功改密即 `session.revokeAll()` 会踢掉兄弟车道 token（并发纪律禁止） | 环境（共享实例并发约束） |
| AC-AUTH-04 | 409/50019 | 开关关闭态 409/50019，不查库不签 token（行为正确） | 开启态正向路径未跑：需改 `ipd.auth.qr-login.enabled` 并重启，本波禁重启 | 环境 |
| AC-AUTH-05 | 409/50019 | 同上（同一前置被 50019 拦截） | “账号未绑定”提示路径不可观测（`wecomMockLogin` NOT_FOUND 分支在源码存在） | 环境 |
| AC-AUTH-06 | 200/0 | 自有夹具（`POST /api/v1/person-sync/jobs` 建 `Mock-LANE1A06-205339`）→ `mark-resigned` 200/0 → 终态 `FROZEN_PENDING_HANDOVER/RESIGNED`，审计 `RESIGN`+`REVOKE_SESSIONS`，view `sessionsRevoked=true notificationsSent=11` | ① “用其账号登录被拒”：夹具无口令，且 `IpdAuthService.login` L120-127 **先验口令再判 RESIGNED**，错口令只返 BAD_CREDENTIALS，无法区分（探测 1 次已留 LOGIN_FAIL 审计）；② “企微自动解绑+审计”：全库仅 2 行有 wecom 绑定（池账号 ipd-rd 禁动 / r214-mkt 状态机死角），且无 HTTP 端点可绑定 wecom | fixture（无口令 / 无可用绑定行） |
| AC-TEAM-04 | 200/0 | 定向单不可被非受邀者应标 + 改 mode 走“新建公开招标单”路径并验证公开可见 | “原单直接改 mode”无对应端点参数（系统以新建单实现），该半边按实现口径记录 | 实现口径差异 |
| AC-TEAM-05 | 200/0 | 遴选 ACCEPTED/REJECTED 双落库 + BID_WON/BID_LOST 通知各 2 + select 审计 1 行 | AC 的“3 人应标”降级为 2 人：池内可登录研发 PM 仅 2 个（其余为兄弟车道 Mock，不可借用） | fixture/并发车道 |
| AC-TEAM-09 | 409/50002 | 超管直指 OPEN 单被 50002 状态门禁正确拒绝；组长越权 30001 | “挂起超 30 日”正向不可构造（时间推进 + 依赖 TEAM-08 未接线的到期挂起链路） | 真缺陷连带（见 TEAM-08） |

## 四、NOT-RUN（8 条，一律写明原因，不硬凑）

| 卡 | 原因 | 归因 |
|---|---|---|
| AC-ENV-03 | 清单要求备份 Windows pgdata 并做 PG 恢复演练；当前无 PostgreSQL 栈（实为 MySQL），且禁止破坏性备份/恢复 | 环境基线漂移 |
| AC-ENV-04 | 去重规则：该卡已由 R218-QA08-SEC04 覆盖（`执行清单-r218.json` card=AC-ENV-04, verdict=BLOCKED） | 去重 |
| AC-AUTH-02 | 首登强制改密需 `must_change_pwd=1` 且凭证有据的账号：库内候选（刘研发）password_hash 与 dev 信源 initial-password 不匹配，400/10001 “用户名或密码错误” | fixture 数据 |
| AC-TEAM-06 | 到期前 3 天提醒：`bid.expireWarnDays=3` 配置存在但主源码零调用方（无调度/端点接线）+ 时间依赖不可实时观测 | 服务层未实现（与 TEAM-08 同源） |
| AC-TEAM-07 | 7 日未遴选升级组长：`bid.selectDeadlineDays=7` 配置在，无调度实现命中 + 时间依赖 | 服务层未实现（与 TEAM-08 同源） |
| AC-HR-01 | `POST /api/v1/hr-sync/sync-now` → 409/50018 “HR 真源同步未启用（需 ipd.hr.enabled=true）”；`last-run` data=null；`hr.syncCron=0 2 1 * *` 配置在但无 cron 触发可观测 | 环境开关未开 |
| AC-HR-02 | L3→L4 等级变更依赖 HR 真源同步（同 HR-01）；`persons.level/level_updated_at/level_source` 三列存在(3/3)、`hr.levelSource=API_ONLY` | 环境开关未开 |
| AC-HR-06 | “HR 标注组长→同步→自动获组长角色”依赖 `ipd.hr.enabled`（实测 50018）；`hr.syncLeaderRole=true` 配置在 | 环境开关未开 |

## 五、真缺陷候选清单（交主会话统一处置，本车道不立卡）

| # | 卡号 | 一句话 | 证据路径 |
|---|---|---|---|
| 1 | AC-AUTH-09 | 同组非成员 PM 可读他人项目全量详情（200/0 泄露项目名），项目详情无成员可见性谓词 | 执行清单-lane1.json#AC-AUTH-09；controller/ProjectController.java |
| 2 | AC-TEAM-01 | 定向邀标 publish 不发通知（notification_events=0）且列表无 ONE_TO_ONE 受邀人过滤（非受邀组长可见） | 执行清单-lane1.json#AC-TEAM-01（单 id=2103694597292077058） |
| 3 | AC-TEAM-08 | 招标到期不自动关闭/不挂起：`expireOverdue()` 全模块零调用方（无 @Scheduled、无端点），过期单实测仍 OPEN、到期通知 0 | 执行清单-lane1.json#AC-TEAM-08（INVX id=2103694600123232258） |
| 4 | AC-TEAM-06 / AC-TEAM-07 | 与 TEAM-08 同一条未接线链路（到期前提醒 / 7 日未遴选升级）——补 TEAM-08 调度时一并覆盖 | 执行清单-lane1.json#AC-TEAM-06/07 |
| 5 | AC-TEAM-10 | 应标侧缺角色互斥：市场 PM 应研发侧标 200/0 落库成功，与成员绑定侧已实现的互斥（ProjectMemberServiceImpl L89-92）不一致 | 执行清单-lane1.json#AC-TEAM-10；service/BidResponseService.java |
| 6 | AC-CFG-01 | 系数面值仍有 7 处硬编码（K04 权重 0.05、奖金池 0.05、S 级系数 1.5/2.0 上下界与文案） | 执行清单-lane1.json#AC-CFG-01；KpiSharedCollectionService.java:141, ProjectService.java:91/657/684, BonusPoolService.java:289/301/443 |
| 7 | AC-CFG-02 | 双配置源漂移：`sign_due_at` 只认 `ipd_business_config.gate.signDeadlineDays`，`/api/v1/system-configs` 改值 200/0 且 invalidated=true 却不生效（实测期限仍 3.0 天） | 执行清单-lane1.json#AC-CFG-02（Gate id=2103696715994411009）；service/GateElementResultService.java L465-475 |
| 8 | AC-AUTH-06 派生 | persons 状态机不可恢复组合 `account=DISABLED + employment=ACTIVE`（r214-mkt id=2114000000000000001）：rehire 要 RESIGNED、resign 要账户 ACTIVE，两路各返 50002，人员永久卡死且无启用/解冻端点 | 执行清单-lane1.json#AC-AUTH-06；service/PersonService.java L110-119 / L313-318 |
| 9 | 附加观察（不属任一 AC 判定） | `POST /api/v1/projects` 传非 JSON 数组文本的 `targetMarkets` 时穿透为 `http=500 code=90001 系统内部错误`（MySQL JSON 列异常），应为 400/10001 参数校验；实测未落库 | 本次取证 2026-09-26T04:19:22Z traceId=4f293a8224d34839a01e8fbe7e6224fa，前置 products id=2103700936328261633（写库清单已登记），`projects` 回读行数=0 |

## 六、写入与基线复原声明

- 全部写入经业务 HTTP 接口，登记 `写库清单-lane1.json`（59 条）。fixture 名称/编码统一带 `LANE1-`（人员夹具经 person-sync 落为 `Mock-LANE1A06-205339`，工号 `LANE1A06-205339` 仍带 LANE1 前缀）。
- 实验性改值**已回滚到波前基线**：`system_configs.gate.signDeadlineDays 3→5→3`、`system_configs.allowance.L4 2500→2777→2500`（回滚条目见写库清单尾部，均为 `PUT /api/v1/system-configs` 200/0 + 真库直读复核）。
- 有意保留的夹具（供兄弟车道/后续复跑对照，均 LANE1 前缀）：products×4、projects（LANE1-P-A/B/C-205339）、bid_invitations（INV1/INV2/INVX）、bid_responses（已 withdraw 终态）、project_members 绑定证据行（TEAM-12/HR-03 快照 L4/2500 与 L4/2777）、gate+gate_element_results（CFG-02 实证链）、persons `Mock-LANE1A06-205339`（RESIGNED，无口令无会话，不影响他人）、audit_logs `LOGIN_FAIL` 1 行。
- 零直连 SQL 写库：`L.sql()` 仅用于只读回读断言；未重启/轮换/构建 16039 进程，未杀任何端口。

## 七、并发串扰记录（纪律 4）

- 人员项目额度为一次性消耗（`ProjectMemberController` 无 exit/leave 端点、无人员创建端点，仅 person-sync Mock 建员无口令不可登录）：绑定候选人须实时读 `status/active 计数`，本轮实测真实 PM 池被兄弟车道（`Mock-LANE3-*`/`Mock-LANE4*` 与真名 PM 绑定）迅速占满，故 TEAM-11 候选人自适应 + 预热回退，TEAM-12/HR-03 的绑定证据行改为**续跑复用**（不重复绑定）。
- `ipd-rd2`(900105) 在兄弟车道跑批中被反复 resign/rehire 震荡 ⇒ 本车道停用该账号登录，相关用例改取自适应候选，未产生 FAIL-ENV 记录（无因串扰而失败的判定）。
- 未观测到兄弟车道改动本车道 `LANE1-` 夹具的内容：本波 41 卡判定中无 FAIL-ENV / BLOCKED。

## 八、执行日志

- `run-203713.out`（第 2 轮全量）→ `归档-执行清单-lane1-round2.json`（该轮 11 条留档）
- `run-205119.out`（第 3 轮，崩于 stage_actions 列名口径）
- `run-resume.out` / `run-resume2.out` / `run-resume3.out`（第 4/5 轮，含 TEAM-11/12、HR 链、CFG-02 真链）
- `run-resume4.out`（第 6 轮，HR-01/02/06/07 补齐 + CFG-01 口径收紧；AUTH-06 崩于 cmd 格式化）
- `run-resume5.out`（第 7 轮 = AUTH-03/AUTH-06 判据修正后重判，`RUN 205339 DONE recs= 41`）

