# Paiban-03 决策包 — 3 表名单数整改

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：强推进（决策包）/ 让路（DDL apply）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：2 hr                         类别：C（owner 必拍 / DDL apply）

## 一、背景（200 字）
3 张表名单数违规：`requirement_pool` → `requirement_pools` / `receipt_ledger` → `receipt_ledgers` / `switching_acceptance` → `switching_acceptances`。R128 推荐 in-place RENAME + Java Entity 同步。DDL apply 必经 SRE（#15 元规则）。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A in-place RENAME | RENAME TABLE + Entity 同步 | 1 次 DDL，2 hr | 短期中断 |
| B 视图 + 双写 | 新建复数视图，代码层双写 | 零中断 | 复杂度高，4 hr |
| C 暂不改 | 接受单数现状 | 零风险 | 违反 R128 §三 DB 规范 |

## 三、推荐方案
**A in-place RENAME**（R128 §四 推荐）：SRE apply `ALTER TABLE x RENAME TO xs;` ×3 + Java Entity `@TableName` 同步。
- 工作量：2 hr（DDL 5 min + Java 同步 1 hr + E2E 验证 1 hr）
- 风险：DDL apply 锁表 1-2 秒，Entity 与表名漂移
- 回滚 SOP：SRE 反向 RENAME + git revert Entity

## 四、非 owner 拍板自动通过判定
**否**（DDL apply 必经 SRE + Entity 同步跨 wt），必须 owner 拍板。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- SRE apply DDL + Java Entity 改 = **让路**（#15 + #17 元规则）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
