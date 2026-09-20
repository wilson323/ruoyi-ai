# 跨仓协调 SOP（Cross-Repo Coordination SOP）

> 创建时间：2026-09-20     来源：R132 派单（agency-harness）
> 撞车 0 让路：✅ 强推进 docs-only 落档；不动前仓 docs / 不抢端口 / 不杀 PID
> 关联决策：paiban-17 派单顺序 + paiban-18 跨仓 BCP

---

## §一 三仓边界（主仓 / 前仓 / DB 仓 / SRE 通道）

| 仓 | 路径 | 边界 | 让路项 |
|---|---|---|---|
| **主仓** | `/Users/mac/Documents/ruoyi-ai` | 后端 Java + docs/ipd-系统说明/ + 看镜像 SSOT | docs-only 强推进 |
| **前仓** | `/Users/mac/Documents/ruoyi-ipd-web` | Vue3 + Pinia + src/api/ | docs/ 不直写；src/ 走 wt 派单 |
| **DB 仓** | MySQL 8（kpi_rules / project / person） | DDL apply | SRE 专属通道（拍板 #15 解锁）|
| **SRE 通道** | chain_root + ALTER 元规则 | 拍板 #15 / #16 | 撞车 0 让路下不擅自动 |

---

## §二 跨仓工作流模板（4 步）

```
[1 派单]  agency-harness 写 docs/ipd-系统说明/拍板决策包/paiban-NN-*.md
              ↓
[2 协调]  看镜像 SSOT 同步 + 飞书通知前仓 PM（撞车 0 让路下不实跑，仅写 log.md）
              ↓
[3 落地]  scripts/ 骨架 + .claude/hooks/ 元脚本；Java 源码走 wt 派单
              ↓
[4 同步]  docs/ipd-系统说明/ 看镜像 R132 段回填 + log.md T-触发记录
```

---

## §三 撞车 0 让路边界（让路 vs 强推进分界）

### 3.1 让路（不擅自动手）

- 兄弟会话 2 个 modified + 66 个 untracked 文件
- 端口 16039（默认不起）
- PID 34560/70554/29607/65576（兄弟进程，不杀）
- DDL apply（SRE 通道）
- 跨 wt 改他人未提交 Java 源码
- 前仓 docs/ 直写

### 3.2 强推进（AI 自主可执行）

- docs/ipd-系统说明/（含拍板决策包目录）
- scripts/（H-11~H-17 元脚本 + t2-paiban-sla.sh）
- .claude/hooks/（pre-cd-cross-repo-check.sh 等）
- 看镜像 SSOT 同步
- 拍板决策包目录 docs-only 落档

---

## §四 5 个边界判例（对齐 R131 §四.4.3 飞轮与 GEP 8 阶段）

| 判例 | 派单阶段（强推进）| 执行阶段（让路）| 飞轮齿位 | GEP 阶段 |
|---|---|---|---|---|
| 拍板 #15 DDL SRE apply | 写决策包 docs/ipd-系统说明/ | SRE apply = 让路 | 齿 5 升级 | GEP-6 实施 |
| 拍板 #1 启 IPD 后端 | 写决策包 docs-only | java -jar = 让路 | 齿 4 验证 | GEP-7 部署 |
| wt 清理（fix-wt-*) | 写方案 scripts/ | wt remove = 让路 | 齿 3 派单 | GEP-4 派单 |
| T1-T10 落地 | 写脚本 scripts/ | 第一次跑 = 让路 | 齿 4 验证 | GEP-5 落地 |
| 看镜像 §X 拍板项 | 写表头 docs/ | 看板 PUT = 让路 | 齿 5 升级 | GEP-8 收口 |

---

## §五 跨仓 BCP 派单矩阵

| 仓对仓 | 派单路径 | 工作流 | 撞车 0 让路 |
|---|---|---|---|
| 主仓 → 前仓 | docs/ipd-系统说明/ + 前仓 wt | 派单 → 协调 → 落地 → 同步 | docs 不直写 |
| 主仓 → DB 仓 | docs/ipd-系统说明/paiban-15 | 派单 → SRE 通道 | DDL 让路 |
| 前仓 → DB 仓 | 主仓中转派单 | 派单 → 协调 → 落地 → 同步 | 前仓 docs 让路 |
| 主仓 → SRE | docs/ipd-系统说明/paiban-16 | 派单 → SRE 通道 | ALTER 让路 |

---

## §六 例外白名单（撞车 0 下 AI 自主可执行）

| 路径 | 触发条件 | 验证 |
|---|---|---|
| docs/ipd-系统说明/ 拍板决策包/ | 强推进白名单 | R132 §1 |
| scripts/ | 强推进白名单 | R132 §4 t2-paiban-sla.sh |
| .claude/hooks/ | 强推进白名单 | R131 §四 H-12 |
| 看镜像 SSOT | 例外白名单 | R131 §五.5.5 |

---

**撞车 0 严守**：✅ docs-only 落档；不动兄弟会话 modified；不杀 PID / 不擅自动 DDL / 不启后端
**下次刷新**：R132 派单 18 拍板项闭环后，本 SOP 升级 v2 含飞轮转速数据
