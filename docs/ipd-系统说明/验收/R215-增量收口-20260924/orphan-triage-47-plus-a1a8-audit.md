# R215 WP3.4 孤儿端点 47 条定性 + A1-A8 接线审计（2026-09-25）

> 前档 `orphan-triage-87.md` 的续篇。本轮由两个只读专业智能体并行盘点（agency-harness × 2，2026-09-25 03:00-03:20 磁盘现态），主会话复核落盘。

## 一、门禁五连修正（49→47）

`scripts/check-api-contract-fe-be.mjs` 本轮修四处：
1. **BE 侧多属性注解盲区**：方法级正则旧式要求引号后紧跟 `)`，`@GetMapping(value="/x", produces=...)` 解析退化为类前缀幽灵路径 → `GET /ai-copilot`、`GET /resource` 两条假孤儿。修正后解析出真实 `/ai-copilot/chat/stream`、`/resource/sse`。
2. **FE 采集模式 3a**：raw `fetch('/api/v1/...')` 字面量（ai-copilot.ts:148 SSE 流不经封装层）。
3. **FE 采集模式 3b**：`` `${apiURL}/resource/sse...` `` 模板字面量（utils/message.ts:31，apiURL 已含 /api/v1）。
4. **防误采双闸**：模式 3 只扫剥除整行注释后的源码（message.ts:29 注释里的历史串曾造出 `/api/v1/v1/resource/sse` 假孤儿）；跳过 `/api/v1/public${...}`（portal.ts:101 已由模式 2 requestPortal 归一，重复采集产生拼接形态假孤儿）。
5. FE 扫描面扩 `src/utils`。

复核：孤儿 49→**47**、orphan_paths 0、field 0、pass=True（2026-09-25 实测）。

## 二、A1-A8 + A12 接线审计（9 卡 26 端点 100% 接通）

兄弟会话在工作树完成 A1-A8+A12 全部接线（31 文件 +4279 行未提交，vue-tsc EXIT=0 @03:03）。只读审计逐端点核对「api 封装 → 视图真调用 → 契约测试」三段链，**26/26 全接通且有测试**，无假接线。关键证据抽样：
- A1 observers: gate-review.ts:183→admin/gate-detail/index.vue:202→gate-review.test.ts:27
- A4 奖金池: bonus.ts:88,111,129→bonus-pool/index.vue:182,292,339→bonus.test.ts:76,101,116
- A5 回款: receipt-ledger.ts:69,74,79→bonus-pool/index.vue:393,406,429→receipt-ledger.test.ts:51,61,89
- A6 codes/raw-records/types: kpi.ts:230,212,240→functional/index.vue:284,132+raw-records.vue:162→kpi.test.ts:184,129,207
- A8 移交: handover.ts:147,152+hr-sync.ts:44→handover/index.vue:295,331,356→handover.test.ts:64,84,1028,1051,1102
- A12 扫码: auth-wecom.ts:53→auth/login.vue:12,117→auth-wecom.test.ts:53

**处置**：翻卡以 commit 落地为准（未提交不翻 done，防假绿）。commit 落地后 A1-A8/A12 一次性翻卡。
备注：A12 正向联调需 owner 开 `ipd.auth.qr-login.enabled`（后端默认 false）+ `VITE_IPD_WECOM_QR_LOGIN`（前端默认关），接线本身完整。

## 三、剩余 47 条孤儿定性（并行智能体逐条磁盘证据）

### 3.1 真缺口 → 派单 GAP-F*（前端 11 卡）/ GAP-B*（后端 4 卡）
见看板卡面（本档建卡 15 张，前缀 R215-GAP）。摘要：
- F1 招标 modify+admin-assign｜F2 创建切 p231-create｜F3 人员复职 rehire｜F4 P0升级链处置视图｜F5 切换验收 5 端点(A23)｜F6 合规中心 4 端点(A23)｜F7 人员同步任务页 4 端点｜F8 SOP 实例化+快照｜F9 研发PM我的应标｜F10 超管永久清除 2 端点｜F11 KPI生效规则面板
- B1 deadline-scan 补 @Scheduled｜B2 stage-actions/instantiate 触发方收口｜B3 negative-feedbacks/{id}/status 裸 setter 下线/加守卫｜B4 gates scan 三件套+p0 check 的 OPS-04 调度接线

### 3.2 裁定合法孤儿（不建卡，登记即闭环）
| 组 | 端点 | 依据 |
|---|---|---|
| @Scheduled 实证扫描 5 | handovers/scan-overdue、hr-sync escalate/sync-now/sync-one、project-score-tasks/scan | HandoverOverdueScanner:56、PersonResignEscalator:35、HrSyncJob:51、ProjectScoreScheduleService:160；HTTP 仅手动兜底；前端全仓无运维触发按钮先例 |
| 通知 outbox 2 | dispatch-pending、async-dispatch | NotificationOutboxScanner:61 30s 轮询 + notification.ts:8 明文不接 |
| HR 系统回调 1 | hr-sync/mark-resigned | HrSyncController:106 机器对机器直推（R212 B 桶裁定） |
| legacy/冗余读口 4 | audit-logs、audit-logs/export、negative-feedbacks by-project/by-severity | FE 已接 scope 化等价路径（audit.ts:47,65；negative-feedback.ts:80），BE 注释自证保留 |
| 设计即拒绝守卫 1 | product-groups/{id}/remove | Service 恒抛「禁止直删走 DeletionRequest」（ProductGroupService:105-107），正链已接 |
| 运维回填工具 1 | stage-actions/ensure-bio-compliance | P1-8.1 幂等补挂 C12，回填语义可 curl 重放 |

**账目**：47 = 21（真缺口，F1-F11/B1-B4 覆盖，含 compliance 4、switching 5、person-sync 4 多端点卡合并计）+ 26 合法孤儿（含待调度接线的 scan 类 4 条计入 B4 卡附注）。字段错位 0、误报 0（ai-copilot/resource 已随五连修正归真）。

## 四、复核口径

下轮对账命令：`cd /Users/mac/Documents/ruoyi-ai && node scripts/check-api-contract-fe-be.mjs --json`（预期孤儿数 = 47 - 已派单消化数；合法孤儿不随接线减少，属常态存量，如需清零需给门禁加裁定白名单——本轮未做，遗留为治理项）。
