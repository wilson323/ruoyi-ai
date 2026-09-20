# Paiban-10 决策包 — 后端构造器注入

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：让路（Java 跨 wt）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：2 hr                         类别：B（低风险 / 7d 未决自动通过）

## 一、背景（200 字）
48 处 `@Autowired` 字段注入，违反 Spring 官方推荐构造器注入约定。R128 §四 推荐全量改 Lombok `@RequiredArgsConstructor` 或显式构造器。属 B 类低风险。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A 48 处全量改 Lombok 构造器 | 加 `@RequiredArgsConstructor` + 删 `@Autowired` | 符合 Spring 官方 | 跨 wt 改 |
| B 改显式构造器 | 手写构造方法 | 不引入 Lombok | 48 处手写冗长 |
| C 暂不改 | 接受现状 | 零风险 | 违反 Spring 官方 |

## 三、推荐方案
**A 48 处全量改 Lombok 构造器**（R128 §四 推荐）：Lombok 自动生成 + mvn test 验证。
- 工作量：2 hr（重构 1 hr + 验证 1 hr）
- 风险：低（构造器签名变化，需测试覆盖）
- 回滚 SOP：git revert wt

## 四、非 owner 拍板自动通过判定
**是**（B 类低风险 + 决策包完整 + 回滚 SOP 完整），7d 未决自动通过。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- 48 处构造器注入 = **让路**（跨 wt Java 改动）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
