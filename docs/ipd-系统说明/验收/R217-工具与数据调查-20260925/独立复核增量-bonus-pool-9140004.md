# 卡 5d5c4fcc · bonus_pools 悬空外键（project_id=9140004）调查报告

- **调查时间**: 2026-09-25（R217 并行波次）
- **执行人**: R217 data lane（数据治理调查员）
- **只读声明**: 全程仅 SELECT / SHOW / git log / grep / **mysqlbinlog 只读解码**；未执行任何 INSERT/UPDATE/DELETE/DDL；未改动仓库文件。凭证未上命令行（`mysql --defaults-extra-file=…/mysql-client.cnf`，socket 探针）。
- **目标库**: ipd_dev @127.0.0.1:13306（MySQL 8.0.46）

---

## 一、现状复核

- R212 全量对账矩阵（`docs/ipd-系统说明/R212-全量对账明细矩阵-20260924.md:315`）记录「19 池 1 悬空」；**本次现查为 20 池 1 悬空**——第 20 池 create_time=2026-09-25 18:03:30（对账后新增，正常业务行），悬空仍唯一：
  - `bonus_pools.id=2097169984949182465`，project_id=**9140004**，status=**DISTRIBUTED**，final_pool=42000.00，create_time=2026-09-08 11:47:43，update_time=2026-09-24 19:25:17，del_flag='0'。
- bonus_allocations 对该池有 **2 行台账**（person 9110003 段进科 MARKET_PM 25200.00 / person 9110009 程龙 RD_PM 16800.00，contribution_rate 0.48/0.32，均 2026-09-24 19:25:17 写入）。
- 全库悬空引用面（不止本卡一处，物理删 1 行 projects 造成 **≥11 行悬空引用**）：bonus_pools 1、contributions 1（DRAFT）、kpi_records 2、kpi_shared_confirms 4、project_members 2、products#9130004（软删行仍指 project_id=9140004）。

## 二、成因定位（binlog 实证，超越「无法定论」）

**binlog 本次可达**（log_bin=ON，ROW 格式，`@@log_bin_basename=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/data/mysql/binlog*`），已解码复核。完整时间线：

| 时间 | 事件 | 证据 |
|---|---|---|
| 09-06 18:37:56 | seed 创建 projects.9140004（ZK-GATE-TEST/如门禁测试，level=B，target_sales=1000000） | `2026-09-06-ipd-zk-scenario-seed.sql:129` |
| 09-08 11:47:42 | 池 compute 成功（项目当时存活） | audit_logs seq=2294 BONUS_POOL_COMPUTE |
| 09-18 16:04:42 | R35 治理：**软删** 9140004（del_flag→1，status→ARCHIVED） | `2026-09-18-r35-data-cleanup-batch.sql:64-69` + binlog.000009 Update_rows @16:04:42 |
| 09-18 20:18:11 | R36 C1.1 回填 archived_at | binlog 中被删行镜像值 @32='2026-09-18 20:18:11'、@33 remark='R36 archived_at 一致性回填…'；log.md:3828 |
| **09-18 21:37:58 (PDT)** | **物理 DELETE 单行**：`DELETE FROM ipd_dev.projects WHERE id=9140004`（thread_id=1131，独立小事务 transaction_length=637，删除镜像 del_flag='1'/status='ARCHIVED' 即已软删行被手工清除） | **binlog.000009 # at 80674→81154，Decode 件留存 `/tmp/r217-evidence/binlog9-decoded.txt:7933-8000`** |
| 09-18 21:15:52 | 同前一事务为 persons Mock 账号打 MOCK 标（01-mark-mock-accounts.sql，属 97bd709c 会话） | binlog Update_rows persons @21:15:52 |
| 09-18 21:43:43 | commit `97bd709c`（PmDirectory Mock 过滤，提交信息**未自述**删项目动作） | git log |
| 09-19 07:40 | R97 发现「ZK-GATE-TEST 行物理消失，删除源头未查（audit_logs 无线索，疑兄弟会话测试清理）」 | log.md:7651、7659 |
| 09-24 19:25:16-17 | 池 freeze（seq=3516）+ distribute（seq=3517/3518）**成功执行**并写 2 行台账 | audit_logs + bonus_allocations 实查 |

**成因结论**：9140004 非任何已登记 SQL 所为；物理删除发生于 **2026-09-18 21:37:58**，紧邻 97bd709c 会话的 21:15 mock 打标事务、且 22 分钟后该会话 commit——**最可疑执行主体 = 该时间窗内的兄弟交互会话手工 DELETE（未登记、未上报 audit）**。证据边界：仓库全部 SQL/脚本 grep 无 `DELETE FROM projects` 语句；audit_logs 无对应动作；binlog 不记录客户端身份（无 general log 佐证）→「谁执行的」**无法 100% 定论**，但「何时、何种语句形态、删的是已软删行」已由 binlog 坐实。

## 三、freeze/distribute 门禁核验（卡面预期被证伪）

卡面假设「该池 freeze/distribute 路径被门禁拒绝且无其他运行时暴露」——**实测相反**：

1. **代码读证**（`BonusPoolService.java`）：`freeze()` L1036 与 `distribute()` L1071 均只 `requireById(pool)` + 状态机校验；distribute 的业务门禁 `requireConfirmedContribution()` L1193 仅查 **contributions 表**（按 pool.project_id 取最新行，不校验项目存在）。`projectMapper.selectById==null → "项目不存在"` 守卫**只存在于 buildPoolFromProject*（compute 路径）** L661-666/L760-765。
2. **运行时实证**：09-24 19:25 freeze+distribute 在项目物理消失 6 天后成功（audit seq=3516-3518），并落 2 行 bonus_allocations 真台账（合计 42000）。当时贡献度行（9140004，现 DRAFT）在 09-08 21:10 曾 CONFIRM（audit seq=2539），满足门禁。
3. **其他运行时暴露**：① R212 前端遍历 11 条 withErrors = projectId 兜底 9140004 → `GET /api/v1/projects/9140004` 404（`traverse-result-rerun.json`）；② 台账侧 contributions/kpi_records/project_members 等 10 行悬空引用（§一）持续可被查询命中；③ 页面奖金池列表若 join 项目名会断链（log.md:12167「页面选不到本池」）。

**结论**：悬空池不仅未被门禁挡住，反而走通了「冻结→分配→写台账」全链——这是真实缺陷（freeze/distribute 缺项目存在性校验），兄弟 survey §3.2 已给出修法建议。

## 四、处置建议（二选一 + 本 lane 推荐）

| 方案 | 内容 | 优点 | 缺点 |
|---|---|---|---|
| A. UPDATE 池行指向有效项目 | `UPDATE bonus_pools SET project_id=<有效id> WHERE id=2097169984949182465` | 保留 DISTRIBUTED 财务事实与 2 行台账 | **伪造数据血缘**：final_pool=42000 按 9140004 的 B 级系数/贡献度算出，换项目后金额与任何项目都对不上；唯一约束 uk_bp_project 可能撞号 |
| B. DELETE 池行（+台账） | 物理删池与 2 行 allocations | 孤儿清零 | 销毁已发生的 DISTRIBUTED「财务事实」与 R211c 验收证据链（audit 链仍在但台账断）|
| **C.（本 lane 推荐）整组保留 + 白名单标注 + 补代码门禁** | 池与 10 行关联悬空引用（含 allocations/contributions/kpi/members）统一登记为「幽灵项目 9140004 遗产组」白名单；freeze/distribute 加项目存在性校验（兄弟 survey §3.2）防再发；若 owner 要终局清理，则整组（池+台账+关联 10 行）一起清，不单独动池行 | 数据自洽（钱、贡献度、成员都指同一幽灵），回归样本完整 | 对账口径永久需白名单 1 项 |

⚠️ 单删池行而留 contributions/kpi/members 悬空 = 只藏起一个指标，不解决问题。

## ⚠️ 待 owner 拍板标记
- [ ] 处置方案：A（UPDATE）/ B（DELETE 池行）/ **C（整组保留+白名单，本调查推荐）** / D（整组终局清理）
- [ ] 「幽灵项目 9140004 遗产组」全清单（11+ 行）是否纳入 R212 对账白名单口径
- [ ] freeze/distribute 补项目存在性校验的代码修复卡是否立项（本卡调查已给出证据：运行时暴露真实发生）

*交叉引用：兄弟会话 `docs/ipd-系统说明/验收/R217-工具与数据调查-20260925/dangling-fk-survey.md`（其「物理删除时间不明/推测 DBA」由本报告 binlog 证据补强至 2026-09-18 21:37:58；其运行时暴露证伪结论与本报告一致）。原始解码件：`/tmp/r217-evidence/binlog9-decoded.txt`。*
