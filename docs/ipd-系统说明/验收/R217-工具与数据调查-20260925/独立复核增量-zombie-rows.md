# 卡 8a088a71 · deletion_requests 悬空外键僵尸行调查报告

- **调查时间**: 2026-09-25（R217 并行波次）
- **执行人**: R217 data lane（数据治理调查员）
- **只读声明**: 全程仅 SELECT / SHOW / git log / grep / mysqlbinlog 解码只读；未执行任何 INSERT/UPDATE/DELETE/DDL；未改动仓库任何文件。凭证未上命令行（`mysql --defaults-extra-file=/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf`，socket 探针）。
- **目标库**: ipd_dev @127.0.0.1:13306（MySQL 8.0.46，socket 复用）

---

## 一、事实清单（本次全表孤儿探测实证）

### 1.1 探测方法
deletion_requests 共 **32 行**，entity_type 分布：cert_templates=23 / products=8 / projects=1。
对三类 entity_type 分别 LEFT JOIN 父表（projects / products / cert_templates），父表主键 IS NULL 即悬空。**无 DB 层物理外键约束**（悬空靠应用层门禁拦截）。

### 1.2 悬空行全清单（5 行，与兄弟会话 R217 survey 逐条一致，本次独立复核确认）

| dr.id | entity_type | entity_id | status | create_time | requester_id | reason | entity_snapshot |
|---|---|---|---|---|---|---|---|
| 2096381708617248770 | products | 2 | LEADER_REVIEW | 2026-09-05 06:35:22 | 900101 | `P0-6.3 25h` | {"k":"v"} |
| 2096373346072539138 | products | 999 | REJECTED | 2026-09-06 07:02:09 | 900101 | `P0-6.1 集成验收` | {"name":"test"} |
| 2096381707870662657 | products | 1 | WITHDRAWN | 2026-09-06 07:35:23 | 900101 | `P0-6.3 round9 HTTP` | {"k":"v"} |
| 2096381708998930433 | products | 3 | ADMIN_REVIEW | 2026-09-06 07:35:23 | 900101 | `P0-6.3 escalate` | {"k":"v"} |
| 2101930187108151297 | projects | 1 | LEADER_REVIEW | 2026-09-21 15:03:03 | 900101 | `R152-B4 acceptance: verify Workdays.add for leader deadline` | {} |

其余 27 行：cert_templates 23 行（22 DELETED+实体已软删 / 1 在途且实体存活）、products 非悬空 4 行（含 2 DELETED 终态）均正常，**0 新增悬空行**（本次扫描口径含父行物理不存在判定，软删父行不算悬空）。

### 1.3 关键事实
- 5 行 entity_id 均为小整数（1/2/3/999），非雪花 ID；products 表最小 id=900001、projects 表无 id=1 → **目标实体从未存在过**（不是"存在后被删"）。
- 全部由 900101（ipd-admin，SUPER_ADMIN）在 P0-6.x / R152-B4 验收轮创建；reason/snapshot 均为测试标记。
- 2 行已终态（REJECTED / WITHDRAWN），不再流转；**3 行在途僵尸**：products#2 LEADER_REVIEW、products#3 ADMIN_REVIEW（即 R215 ti5c 证据行）、projects#1 LEADER_REVIEW（卡面主角，leader_due_at 2026-09-24 15:03 已过期）。
- audit_logs 中 products 1/2/3/999 无 DELETE_REQUEST_SUBMIT 行（仅 projects#1 有，seq=3021）→ 前 4 行疑似直插 DB 或早期审计未落名，**证据到此为限**。

## 二、成因追溯（三重证据）

1. **代码成因（根因）**：`DeletionRequestServiceImpl.requireSubmitTargetAllowed()`（L444-460）对 `SUPER_ADMIN` **直接 return 豁免，不校验目标实体存在性**；非超管路径 resolveScope 解析不到 → fail-closed。故超管验收脚本可对不存在的 entity_id 建申请。
2. **卡面 30001 拦截机理**：`leaderDecision()`（L218-236）GROUP_LEADER 初审时 `resolveScope('projects',1)` → project=null → groupId=null → fail-closed `FORBIDDEN(30001)`。实测证据：`验收/R215-增量收口-20260924/ti5d-deletion-fullchain.txt`：`code: 30001 仅目标所属组组长可初审删除申请`。
3. **git/SQL 佐证**：R46.1（commit `6cd4fc5d` 2026-09-18 16:29，owner 拍板）曾**物理 DELETE 同类垃圾申请 24 行**（unsupported_probe/not_a_real_table，binlog.000009 @16:27:03 Delete_rows 与本次扫描完全对账）→ 同类僵尸物理清理有 owner 授权先例。

### 2.1 卡面表述修正（诚实边界）
「该申请永远无法推进」**过强**。代码读证：SUPER_ADMIN 豁免初审 scope 校验（L229-235），可将行推至 ADMIN_REVIEW；终审 `DeleteAuditService.approveAndExecute` 对不存在目标走 **DELETE_NOOP 分支**（`ProjectSoftDeleteExecutor.isDeleted`: null→true，L77-132），照样落 DELETED 终态。即：**组长侧永久 30001 卡死，超管侧可 NOOP 通关**——这是设计语义（幂等），不是硬死锁。

## 三、处置方案对比

| 维度 | 方案 A：物理 DELETE 5 行 | 方案 B：保留作 fail-closed 回归样本 |
|---|---|---|
| 一致性 | 孤儿计数归零，棘轮门（API-GATE-RATCHET 孤儿只减不增）指标干净 | 扫描口径需白名单 5 行，否则每轮对账重复报告 |
| 审计链 | audit_logs 只增不删，DELETE 申请行**不破坏哈希链**（seq=3021 等仍在）；R46.1 先例可循 | 完整保留 P0-6.x / R152-B4 / R215 ti5c-ti5d 验收现场 |
| 回归价值 | 30001 fail-closed 负向样本消失，需再造数据才能回归 | products#3（ADMIN_REVIEW 悬空）+ projects#1（LEADER_REVIEW 悬空）恰是 SUPER_ADMIN 豁免缺陷（§二-1）活体回归数据；删行即销毁证据 |
| 风险 | 若未来有人复查 ti5c/ti5d，DB 现场与验收文档失配 | 僵尸在途行 leader_due_at/admin_due_at 已过期，理论上污染工作台待办计数（现库 pending 含 deletion_review=1，见 ti5c）|
| 折中 A′ | 仅 DELETE 2 行终态（REJECTED/WITHDRAWN，无回归价值），保留 3 行在途僵尸并加 remark 白名单标注 | — |

## 四、推荐（data lane 意见）

**推荐 A′ 折中**：
1. 保留 3 行在途僵尸（products#2、products#3、projects#1）作为「SUPER_ADMIN submit 不校验实体存在」根因修复卡（兄弟 survey §3.1 建议）的 fail-closed 回归样本，待修复卡上线并复验后再清理；
2. 2 行已终态（REJECTED/WITHDRAWN）的无回归价值，可按 R46.1 先例申请 owner 授权物理 DELETE；
3. 无论选哪种，**先落代码根因修复**（submit 处对全角色加实体存在性校验），否则新僵尸还会产生。

配套：`DeletionRequestServiceImpl` 根因修法、DB 层防呆（多态 FK 无法加约束，应用层守）见兄弟会话 `docs/script/sql/update/ipd_r217_dangling_fk_cleanup_draft_20260925.sql`（草稿，未 apply）。

## ⚠️ 待 owner 拍板标记
- [ ] 处置方案选择：A / B / **A′（本调查推荐）** —— **本 lane 零写库，任何 DELETE 未执行**
- [ ] 若选 DELETE：授权执行窗口与执行人（参照 R46.1 「owner 拍板后执行」流程）
- [ ] 根因修复卡是否立项（submit 实体存在性校验）

*交叉引用：兄弟会话版本 `docs/ipd-系统说明/验收/R217-工具与数据调查-20260925/dangling-fk-survey.md`（结论一致；本报告增量=ti5d 30001 实测原文、DELETE_NOOP 超管可通关的代码读证、R46.1 物理清理先例的 binlog 对账）。*
