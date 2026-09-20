# Paiban-05 决策包 — 后端 Service 接口化

> 派单智能体：ioedream-pm             拍板 owner：项目主理人
> 拍板 SLA：🟢 7d                    撞车 0 让路：让路（Java 跨 wt）
> 创建时间：2026-09-20                截止：2026-09-27
> 工作量：4 hr                         类别：C（owner 必拍 / 阻塞 E2E）

## 一、背景（200 字）
65 个 Service 类全部 `@Service` 类，无 `IXxxService` 接口 + `XxxServiceImpl` 实现。RuoYi-Vue-Plus 底座核心约定，底座二开偏离底座规范是 R127 反思链元根因。R128 §四 推荐拆接口 + impl，4 批分摊。阻塞 R121 真活 E2E。

## 二、3 个候选方案
| 方案 | 做法 | 优势 | 劣势 |
|---|---|---|---|
| A 拆接口 + impl（4 批）| 65 Service 拆 IXxxService + XxxServiceImpl，4 批分摊 | 符合底座，可逐批验证 | 4 hr 全量 |
| B 仅新代码用接口 | 历史 Service 不改，新代码按规范 | 增量收敛 | 永久双轨 |
| C 暂不改 | 接受现状 | 零风险 | 阻塞 E2E + 偏离底座 |

## 三、推荐方案
**A 拆接口 + impl**（R128 §四 推荐）：按业务域分 4 批（access/attendance/consume/visitor 各 1 批）。
- 工作量：4 hr（每批 1 hr = 拆接口 30 min + 改注入 30 min）
- 风险：注入点漂移、Mock 测试失效
- 回滚 SOP：每批可独立 revert（git revert wt + 重新生成 stub）

## 四、非 owner 拍板自动通过判定
**否**（跨 wt 改 65 个 Java 类 = 让路 + 阻塞 E2E），必须 owner 拍板。

## 五、撞车 0 让路边界
- 写决策包 = **强推进**
- Java Service 拆分 = **让路**（跨 wt 改他人未提交代码）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议   拍板日期：____
