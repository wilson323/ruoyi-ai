# Paiban-08 决策包 — 后端异常处理收口

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：让路（Java 跨 wt）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：1 hr                         类别：B（低风险 / 7d 未决自动通过）

## 一、背景（200 字）
~20 处 `IllegalArgumentException` 散落，违反 RuoYi-Vue-Plus 底座约定（应抛 `ServiceException`）。R128 §四 推荐收口到 `IpdBusinessException`。属 B 类低风险。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A 全量 20 处改 `IpdBusinessException` | IDEA 重构 + 全局替换 | 一次性收口 | 跨 wt 改 |
| B 仅新代码用新异常 | 历史代码不动 | 增量收敛 | 永久双轨 |
| C 暂不改 | 接受现状 | 零风险 | 违反底座 |

## 三、推荐方案
**A 全量 20 处改**（R128 §四 推荐）：IDEA 重构 + 全局替换 + mvn test 验证。
- 工作量：1 hr（替换 30 min + 验证 30 min）
- 风险：极低（异常类型替换，不改逻辑）
- 回滚 SOP：git revert wt

## 四、非 owner 拍板自动通过判定
**是**（B 类低风险 + 决策包完整 + 回滚 SOP 完整），7d 未决自动通过。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- 20 处异常替换 = **让路**（跨 wt Java 改动）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
