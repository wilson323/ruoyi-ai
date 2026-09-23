# IPD P0 派单清单 — 2026-09-23

> 基于 R170 记忆 + 现状 fresh 探查。**仅派单材料**，每项列出「现态 + 影响 + 推荐处理顺序」，不擅自动手。
> 关联 R28 残留三件已 FULL CLOSED（见 `e2e-prod-readiness-20260923/REPORT.md`）。

## P0 现状全局

- 后端 HEAD=5310f551（origin/main 同步，clean）
- 前端 HEAD=3264ac5（origin/main 同步，clean）
- 真库 157 表连通，drift-check 0，audit_logs 8 索引全在
- 登录链路三证律 FULL PASS

## P0 清单 13 项 · 8 项仍待办（owner 拍板）

| # | 项 | 现态（fresh） | 影响/风险 | 推荐处理 |
|---|---|---|---|---|
| P0-1 | 双线合流 | main=origin/main 已同步，R170 记忆的「落后」事实过期 | low（已事实同步） | **建议直接 closed**——查 commit 数差异 ≤5 即可关闭 |
| P0-3 | platform-token 加固 | AsyncConfigurer 单实例部署正常，多实例未测 | med（多实例部署风险） | 现态部署单实例即可，建议**先 closed + 文档化多实例注意事项** |
| P0-7 | @Autowired 字段注入 48 处 | 现仓 48 处分布广泛（Controller/Service/Multi-dto） | med（架构债务） | **推荐两段式**：①owner 拍板保留/改造 ②若改造，按 R127 模板用 Lombok @RequiredArgsConstructor 一键迁移，单测保真即可 |
| P0-8 | 评审要素按钮 | 前端 UI owner 待拍按钮位置/行为/权限 | med（涉及 UX 决策） | **需 owner 提供 UI 稿或 mockup**，技术实现已无障碍 |
| P0-10 | c-batch-4 DDL apply | docs/script/sql/update/ 下 SQL 已 commit，apply 待 DDL | medium-high（DDL 不可逆） | **需 owner 拍板业务规则 + DBA 窗口**；本会话可生成 EXPLAIN 预览不 apply |
| P0-11 | 登录会话收口（P0-10.1/10.2） | 前端 `requestIpd` 调用模式部分修复，登录域两块未闭环 | med（功能影响） | **本会话可推进**：定位未闭环代码段 + 出 diff 预览，owner 拍板后 apply |
| P0-12 | 审计 hash 链多实例断裂 | 1940 行哈希链已连续，单实例 OK；多实例部署会断链 | high（数据安全） | **需 owner 拍板多实例部署策略**：单实例则 closed；多实例必须修（推荐 Redis 集中 prev_hash 协调） |
| P0-13 | 守卫配置类 10 测试失败 | 现仓 R99 残余，需 fresh 单测复现确认 | low-med | **本会话可推进**：fresh 跑 mvn test 复现 → 派单给兄弟会话处理 |

## 派单原则

- **owner 拍板项**（P0-1/3/7/8/10/12）：本会话只能出勘察材料 + 推荐建议，不擅自决定
- **AI 可推进项**（P0-11/13）：本会话可定位代码 + 派单卡 + 写补丁（owner 拍板后 apply）
- **不可逆 DDL**（P0-10）：必须 owner 点头 + DBA 窗口，本会话不 apply

## 阻塞依赖

- P0-7 @Autowired 改造：依赖 P0-3 platform-token 单实例确认（否则多实例下 DI 双构造路径与字段注入行为不一致）
- P0-11 登录会话收口：依赖 P0-12 多实例策略确认（hash 链断裂影响 token 撤销逻辑）
- P0-13 守卫测试失败：fresh 复现才能派单，需要 `mvn -pl ruoyi-ipd test` 跑一遍（按 R9a 复测规则：错峰+单模块+不带 -am/clean）

## 推荐处理顺序

按阻塞依赖拓扑 + 影响面递减排：
1. **P0-1**（建议直接 closed，验证 1 分钟）
2. **P0-13**（fresh 复现派单，30 分钟内可闭环）
3. **P0-11**（代码定位 + 派单，1-2 小时）
4. **P0-3 / P0-12**（多实例部署策略拍板，owner 一句话可解）
5. **P0-7**（架构债务，需批量改造，半天到一天）
6. **P0-10**（DDL 高风险，需 DBA 窗口）
7. **P0-8**（UI 决策，依赖 owner 稿）

## 责任边界

- **本会话已做**：三证律真活验证 + R28 残留三件 FULL CLOSED 事实修正 + P0 8 项派单清单
- **未做**：任何 P0 实际代码改动（owner 拍板 + AI 推进均不在本轮范围）
- **撞号透明**：本段接续 5310f551 E2E 收口段，登记 P0 派单与兄弟 R179-P0 收口段平行不冲突

## 后续建议（给用户决策）

- 如果「生产就绪」= 登录链路 + 审计日志真活 + 业务表数据可见：**已达**（本轮 FULL CLOSED）
- 如果「生产就绪」= 全部 P0 闭环：还需 6-8 轮治理（按依赖顺序推 P0-1/13/11/3+12/7/10/8）
- 如果「生产就绪」= R170 12 项 P0 + 真活 + 单测覆盖：还需 R179-P1 真活验证（业务页端到端）+ 单测覆盖率统计