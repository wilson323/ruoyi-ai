# Paiban-02 决策包 — kpi_rules 表方案

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：强推进（决策包）/ 让路（DDL apply）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：0.5 hr                       类别：C（owner 必拍 / DB schema 变更）

## 一、背景（200 字）
前端缺 `/kpi/rules` 端点，三种方案抉择：(A) 新建 `kpi_rules` 表；(B) 改用 `/api/v1/kpi/shared` 共享端点，零 DB 变更；(C) 融合（共享端点 + 后续迁移）。R128 推荐 B 零 DB 变更方案。DDL apply 属 SRE 专属通道（#15 元规则）。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A 新建 kpi_rules 表 | CREATE TABLE + Flyway 迁移 | 数据隔离 | DDL apply 必经 SRE |
| B 改 `/api/v1/kpi/shared` 端点 | 复用现有 KPI 表，零 DB 变更 | 零风险，0.5 hr | 长期需重构 |
| C 融合（短期 B + 长期 A）| 先 B 后 A | 折中 | 两次工程 |

## 三、推荐方案
**B 改端点**（R128 §四 推荐）：复用现有 KPI 表，返回 `[{rule_key, rule_value}]` 列表。
- 工作量：0.5 hr（改 Controller + 前端适配）
- 风险：极低
- 回滚 SOP：git revert + 删 Controller 端点

## 四、非 owner 拍板自动通过判定
**否**（DB schema 决策的派单权属 + DDL apply 必经 SRE），必须 owner 拍板。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- 实际建表 DDL apply = **让路**（SRE 专属 #15）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
