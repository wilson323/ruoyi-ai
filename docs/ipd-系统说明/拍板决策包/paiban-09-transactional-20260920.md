# Paiban-09 决策包 — 后端 @Transactional 方法级

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：让路（Java 跨 wt）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：0.5 hr                       类别：B（低风险 / 7d 未决自动通过）

## 一、背景（200 字）
~7 处类级 `@Transactional` 违反底座约定（应方法级 + `@ReadOnlyTransaction` / `@WriteTransaction`）。R128 §四 推荐全量改方法级。属 B 类低风险。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A 7 处全量改方法级 | 删除类注解 + 加 `@ReadOnlyTransaction`/`@WriteTransaction` | 符合底座 | 跨 wt 改 |
| B 保留类注解 | 接受现状 | 零风险 | 违反底座 |
| C 仅新代码用方法级 | 历史不动 | 增量 | 永久双轨 |

## 三、推荐方案
**A 7 处全量改方法级**（R128 §四 推荐）：IDEA 重构 + mvn test。
- 工作量：0.5 hr（重构 20 min + 验证 10 min）
- 风险：极低（注解位置调整）
- 回滚 SOP：git revert wt

## 四、非 owner 拍板自动通过判定
**是**（B 类低风险 + 决策包完整 + 回滚 SOP 完整），7d 未决自动通过。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- 7 处 @Transactional 改方法级 = **让路**（跨 wt Java 改动）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
