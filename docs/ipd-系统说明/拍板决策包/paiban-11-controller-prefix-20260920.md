# Paiban-11 决策包 — 后端 Controller 去 Ipd 前缀

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：让路（Java 跨 wt）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：0.5 hr                       类别：C（owner 必拍 / 路径变更）

## 一、背景（200 字）
4 个 Controller 带 `Ipd` 前缀（`IpdProjectController` 等），违反底座 `XxxController` 命名约定。R128 §四 推荐重命名（保留 `/v1/api/ipd/*` 路径不变）。路径变更需安全评估。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A 重命名 4 Controller（保路径）| `IpdProjectController` → `ProjectController`，路径不变 | 符合底座 | 跨 wt 改 |
| B 重命名 + 路径改 `/v1/api/` | 同步去 `ipd` 路径前缀 | 彻底统一 | 安全风险 |
| C 暂不改 | 接受现状 | 零风险 | 违反底座 |

## 三、推荐方案
**A 重命名 4 Controller**（R128 §四 推荐）：类名重命名 + 路径不变（保留 `/v1/api/ipd/*`）。
- 工作量：0.5 hr（重构 15 min + 安全评估 15 min）
- 风险：低（类名变 + 路径保）
- 回滚 SOP：git revert wt

## 四、非 owner 拍板自动通过判定
**否**（路径变更需安全评估 + 跨 wt 改），必须 owner 拍板。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- 4 Controller 重命名 = **让路**（跨 wt Java 改动）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
