# Paiban-17 决策包 — 派单顺序元规则

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：⚡ 24h（元规则）          撞车 0 让路：让路（Java 跨 wt）
> 创建时间：2026-09-20                截止：2026-09-21
> 工作量：0.5 hr                       类别：A（AI 自主派单顺序 / docs-only 部分）

## 一、背景（200 字）
派单顺序：P0-① Service → P0-② Mapper → P0-③ 异常 → P0-④ Transactional → P0-⑤ 注入 → P1-① Controller → P1-② DTO → P1-③ Entity。R128 §四 推荐。本拍板项的「决策包撰写」是 docs-only 强推进，但「派单 wt 内执行」属跨 wt Java 改动=让路。

## 二、3 个候选方案
| 方案 | 派单顺序 | 优势 | 劣势 |
|---|---|---|---|
| A 严格顺序 | Service → Mapper → 异常 → Transactional → 注入 → Controller → DTO → Entity | 依赖清晰 | 串行慢 |
| B 并行派单 | 多个 wt 并行 | 快 | 撞 wt 风险 |
| C 按优先级排 | P0/P1/P2 三档 | 灵活 | 需每轮重排 |

## 三、推荐方案
**A 严格顺序**（R128 §四 推荐）：Service 接口化先行（#5），其余按依赖链。
- 工作量：0.5 hr（写元规则 + 看板登记）
- 风险：串行慢但稳定
- 回滚 SOP：N/A（决议可改）

## 四、非 owner 拍板自动通过判定
**否**（元规则），必须 owner 拍板。

## 五、撞车 0 让路边界（两阶段分离）
- 写决策包 = **强推进**（docs-only）
- 派 wt 内 Java 改动 = **让路**（跨 wt Java = #17 元规则本身约束的让路）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
