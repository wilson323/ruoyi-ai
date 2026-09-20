# Paiban-12 决策包 — 后端 Entity BaseEntity

> 派单智能体：security-reviewer         拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：让路（Java 跨 wt）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：0.5 hr                       类别：B（低风险 / 7d 未决自动通过）

## 一、背景（200 字）
8 个 Entity 缺 `extends BaseEntity`，违反底座约定（统一审计字段 create_by/update_time 等）。R128 §四 推荐全量补父类。属 B 类低风险。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A 8 Entity 加 `extends BaseEntity` | IDEA 重构 + mvn test | 符合底座 | 跨 wt 改 |
| B 仅新 Entity 加父类 | 历史不动 | 增量 | 永久双轨 |
| C 暂不改 | 接受现状 | 零风险 | 违反底座 |

## 三、推荐方案
**A 8 Entity 加 `extends BaseEntity`**（R128 §四 推荐）：IDEA 重构 + mvn test 验证。
- 工作量：0.5 hr（重构 20 min + 验证 10 min）
- 风险：低（新增父类继承，需补字段映射）
- 回滚 SOP：git revert wt

## 四、非 owner 拍板自动通过判定
**是**（B 类低风险 + 决策包完整 + 回滚 SOP 完整），7d 未决自动通过。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- 8 Entity 加父类 = **让路**（跨 wt Java 改动）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
