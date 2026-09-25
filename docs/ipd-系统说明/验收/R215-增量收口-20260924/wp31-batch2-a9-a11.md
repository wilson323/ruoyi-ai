# WP3.1 批次2：A9 上市复盘 3 端点 + A11 离职冻结/企微解绑 2 端点

- 执行：R215 增量收口轮主会话（单写者）
- 日期：2026-09-24 深夜
- 看板卡：A9 `c79c26d2-4b99-411b-97bc-68d04307be78`、A11 `211e689a-2118-48b3-a8da-f2945fd63742`

## 接线内容（前端 4 改 + 2 新建 + 测试 2 新建）

| 文件 | 改动 | 说明 |
|---|---|---|
| `src/api/ipd/person.ts` | +47 新建 | ResignResultView/PersonOperationView + resignPerson/unbindWecom（A11） |
| `src/views/ipd/admin/identity-sync/index.vue` | +95 | 人员目录「治理操作」列（离职冻结红钮/企微解绑）+ reason Modal（审计提示）+ 成功后静默刷新 |
| `src/api/ipd/post-launch-review.ts` | +62 新建 | PostLaunchReviewView + fetchPendingReview/scheduleReview/completeReview（A9） |
| `src/views/ipd/project/detail/kpi.vue` | +113 | 「④ 上市复盘（BR-KPI-08）」卡：pending 展示 + 完成表单（回款/客户反馈/KPI/教训）+ 空态/错误态分诊 |
| `src/api/ipd/person.test.ts` | +90 新建 | 4 契约测（URL/body/脱敏/幂等/403） |
| `src/api/ipd/post-launch-review.test.ts` | +98 新建 | 5 契约测（pending/schedule/complete 空/全/异常） |

## 契约门禁（contract-verify-batch2.json）

- orphan_endpoints：**84 → 78**（消亡 6：resign、wecom/unbind、post-launch-reviews×3、
  unbind-project[批次1 时序差补消]）；orphan_paths 0；field 0；**pass=True**

## 验证（2026-09-25 05:00 实测）

- vitest 直跑：**2 文件 9 测试全绿**（person 4 + post-launch-review 5）
- vue-tsc：**exit 0**（一轮修 InputNumber null→undefined 类型）

## HTTP+DB 真活（ti-a9-a11-live.txt）

- A9 全链（project 9140001）：pending 无待办→业务异常「项目无待完成复盘」；
  schedule（launchDate=今天-91d）→ id=2103348431572488194 PENDING（assignee 9110003）；
  pending 复查 PENDING ✓；complete{回款 880000/反馈/KPI/教训} → COMPLETED + completedAt；
  重复 complete → 10001「复盘已完成，不可重复完成」终态负例 ✓；
  DB：post_launch_reviews 落行 COMPLETED/880000.00 ✓
- A11 unbind 正例（2114000000000000001 R214市场PM）：code 0 → **实测发现联动 account_status→DISABLED**
  （PersonService AC-USER-10 设计语义「清 wecom_user_id+账号 DISABLED」；前端注释与弹窗文案已据此修正）；
  DB：wecom_user_id 清空、DISABLED ✓
- A11 unbind→resign 状态机互斥发现：DISABLED 后 resign 被 50002 拒「当前账户状态不允许离职冻结」
  （已写入 person.ts 注释：unbind 先行会阻塞 resign）
- A11 resign 正例（2096266884247736321 赵市场，R214 蜂群人员）：code 0
  {pendingProjects:0, message:"冻结成功", sessionsRevoked:true, notificationsSent:11}；
  幂等二触 {idempotent:true, message:"已离职，幂等返回"} ✓；
  DB：employment_status=RESIGNED、account_status=FROZEN_PENDING_HANDOVER（无在管项目也走 FROZEN 中间态）✓
- 负例：resign 不存在人员→50001；unbind 空 reason→10001「不能为空」

## 浏览器三证（batch2-*.png）

1. `batch2-identity-actions.png`：治理操作列 20 行全带红「离职冻结」+「企微解绑」
   （实际路由 /ipd/identity-sync，非 /ipd/admin/）
2. `batch2-unbind-modal.png`：Modal 含「联动账号 DISABLED…不可逆」警示 + reason textarea（取消未提交）
3. `batch2-kpi-review-empty.png`：④卡 Empty 空态「暂无待办复盘」——首轮截图暴露空态误判缺陷
   （ipdErrorText 把 10001 映射为通用文案丢失原文「项目无待完成复盘」→ 正则不中），已修为
   用 IpdRequestError.envelopeMessage 判空态，复测生效

## 留库登记（owner 政策：测试数据留库 + 本表登记）

- requirement_changes 2103346457456201730（批次1，DRAFT）
- post_launch_reviews 2103348431572488194（COMPLETED/880000）
- persons 2114000000000000001（R214市场PM：wecom 已解绑、DISABLED）
- persons 2096266884247736321（赵市场：RESIGNED/FROZEN_PENDING_HANDOVER、通知 11 条）
