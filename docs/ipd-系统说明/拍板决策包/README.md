# IPD 拍板决策包总目录（Paiban Decision Packages Registry）

> **创建时间**：2026-09-20（周日）
> **目的**：将 R128 18 项 owner 拍板项升级为「AI 出决策包 + owner yes/no」两段式
> **撞车 0 让路**：本目录为 docs-only 强推进白名单，AI 自主落档决策包，owner 仅做 yes/no 拍板

---

## §一 拍板决策包 18 项索引

### A 类：AI 自主拍板（强推进白名单 — R131 立即完成）

| # | 拍板项 | 工作量 | 决策包路径 | 状态 |
|---|---|---|---|---|
| A-1 | 拍板机制建立 | 0.5 hr | `IPD-拍板机制-20260920.md` | ✅ R131 已落档 |
| A-2 | R131 反思层 8 项强推进 | 6 hr | `R131-系统性反思+拍板机制+自主执行-20260920.md` | ✅ R131 已落档 |

### B 类：低风险 6 项（7d 未决 → T2 升级 → PM-OWNED 自动通过）

| # | 拍板项 | 工作量 | 决策包路径 | 状态 |
|---|---|---|---|---|
| #7 | Mapper 注解 | 0.5 hr | `paiban-07-mapper-anno-20260920.md` | 📝 待落档 |
| #8 | 异常处理 | 1 hr | `paiban-08-exception-20260920.md` | 📝 待落档 |
| #9 | @Transactional | 0.5 hr | `paiban-09-transactional-20260920.md` | 📝 待落档 |
| #10 | 构造器注入 | 2 hr | `paiban-10-constructor-20260920.md` | 📝 待落档 |
| #12 | Entity BaseEntity | 0.5 hr | `paiban-12-entity-base-20260920.md` | 📝 待落档 |
| #14 | 前端修 2 错位 | 1 hr | `paiban-14-fe-fix-20260920.md` | 📝 待落档 |
| **合计** | | **5.5 hr** | | 7d = 2026-09-27 自动升级 |

### C 类：owner 必拍 12 项（破坏性/跨域/元规则）

| # | 拍板项 | 工作量 | 决策包路径 | SLA |
|---|---|---|---|---|
| #1 | 启 IPD 后端真活 E2E | 1 hr | `paiban-01-backend-e2e-20260920.md` | ⚡ 24h |
| #2 | kpi_rules 表方案 | 0.5 hr | `paiban-02-kpi-rules-20260920.md` | 🟢 7d |
| #3 | 3 表名单数整改 | 2 hr | `paiban-03-table-plural-20260920.md` | 🟢 7d |
| #4 | 571 字符集整改 | 9.5 hr | `paiban-04-charset-4batches-20260920.md` | 🟡 14d（最大破坏） |
| #5 | Service 接口化 | 4 hr | `paiban-05-service-iface-20260920.md` | 🟢 7d |
| #6 | DTO 后缀收口 | 8 hr | `paiban-06-dto-suffix-20260920.md` | 🟡 14d（最大破坏） |
| #11 | Controller 去 Ipd 前缀 | 0.5 hr | `paiban-11-controller-prefix-20260920.md` | 🟢 7d |
| #13 | 前端补 3 端点 | 1 hr | `paiban-13-fe-endpoints-20260920.md` | 🟢 7d |
| #15 | DDL SRE apply 元规则 | 0.5 hr | `paiban-15-ddl-sre-20260920.md` | ⚡ 24h（元规则） |
| #16 | chain_root + ALTER | — | `paiban-16-chain-root-20260920.md` | 🟢 7d |
| #17 | 派单顺序 元规则 | 0.5 hr | `paiban-17-paiban-order-20260920.md` | ⚡ 24h（元规则） |
| #18 | 跨仓 BCP 元规则 | 1 hr | `paiban-18-cross-repo-bcp-20260920.md` | ⚡ 24h（元规则） |

---

## §二 拍板决策包模板（5 段式）

```markdown
# Paiban-{NN} 决策包 — {topic}

> 派单智能体：{agent}      拍板 owner：{owner}
> 拍板 SLA：{24h/7d/14d}    撞车 0 让路：{强推进|让路}
> 创建时间：2026-09-20     截止：{date}

## 一、背景（200 字）
## 二、3 个候选方案（A/B/C + 优劣对比表）
## 三、推荐方案（含工作量 + 风险 + 回滚 SOP）
## 四、非 owner 拍板自动通过判定（是/否 + 理由）
## 五、撞车 0 让路边界（强推进 vs 让路明示）

owner 拍板位：✅ YES / ❌ NO / 🔄 再议（回 §二）   拍板日期：____
```

---

## §三 拍板自动通过触发链

```
决策包 (paiban-NN-*.md) 创建于 docs/ipd-系统说明/拍板决策包/
    ↓
T2 拍板 SLA 监测（cron 每日 02:00）
    ↓ 若 7 天未拍（B 类）
自动升级：看板标红 🔴 + 飞书 webhook + 决策包自动 sign-off（按推荐方案）
    ↓
PM-OWNED 标签接管 + 自动派 wt（如 #7 mapper-anno 派 fix-r131-mapper-anno wt）
    ↓
撞车 0 让路边界判定：
  ✅ 强推进 wt = 自己仓未冲突源码 / docs / scripts / .claude/hooks
  ❌ 让路 = 兄弟 modified / 端口 / PID / DDL apply / 跨 wt Java
    ↓
wt 闭环 = commit + push + 看板 PUT + git log -1 验证
    ↓
撞车 0 例外白名单 docs/ipd-系统说明/ 同步登记「T-触发记录」段
```

---

## §四 撞车 0 让路边界（决策包内必填）

### 4.1 让路边界（不擅自动手）

| 维度 | 让路 |
|---|---|
| **代码** | 兄弟会话 modified（2 个）+ untracked（66 个） |
| **端口** | 16039（撞车 0 让路下默认不起） |
| **PID** | 34560/70554/29607/65576（兄弟进程，不杀） |
| **DDL** | apply 操作（SRE 专属通道） |
| **Java 源码** | 跨 wt 改他人未提交代码 |
| **跨仓 docs 直写** | ❌ 前仓 docs/，统一回写后仓 docs/ipd-系统说明/ |

### 4.2 强推进白名单（AI 自主可执行）

| 维度 | 强推进 |
|---|---|
| **docs/ipd-系统说明/** | ✅ 撞车 0 例外白名单 |
| **scripts/** | ✅ 撞车 0 例外白名单 |
| **.claude/hooks/** | ✅ 撞车 0 例外白名单 |
| **看镜像 SSOT** | ✅ 例外白名单 |
| **拍板决策包目录** | ✅ docs-only 落档 |
| **H-11~H-17 元脚本骨架** | ✅ scripts/ 白名单 |

### 4.3 边界判例（5 个典型）

| 判例 | 派单阶段 | 执行阶段 | 判例结论 |
|---|---|---|---|
| 拍板 #15（DDL SRE apply）| 写决策包 = **强推进** | SRE apply = **让路** | 两阶段分离 |
| 拍板 #1（启 IPD 后端）| 写决策包 = **强推进** | java -jar = **让路** | 决策包与执行解耦 |
| wt 清理 | 写方案 = **强推进** | wt remove = **让路** | 派方案 ≠ 动 wt |
| T1-T10 落地 | 写脚本 = **强推进** | 第一次跑 = **让路** | 写 ≠ 跑 |
| 看镜像 §X 拍板项 | 写表头 = **强推进** | 看板 PUT = **让路** | 写 ≠ 推 |

---

## §五 R131 派单序列（5 hr M1-M5 + 7 项强推进 + 13 wt 部分依赖）

### P0 立刻派单（不依赖 owner 拍板，强推进白名单）

| # | 任务 | 路径 | 工作量 |
|---|---|---|---|
| R131-S1 | 拍板决策包目录 + 18 骨架 | `docs/ipd-系统说明/拍板决策包/` | 1 hr |
| R131-S2 | IPD 拍板机制独立档 | `IPD-拍板机制-20260920.md` | 0.5 hr |
| R131-S3 | H-13 飞轮转速监控脚本 | `scripts/wheel-stuck-detector.sh` | 1 hr |
| R131-S4 | H-14 GEP Modify-stall | `scripts/modify-stall-detector.sh` | 0.5 hr |
| R131-S5 | H-15 lint-reports freshness | `scripts/check-lint-reports-freshness.sh` | 0.5 hr |
| R131-S6 | H-16 R 报告行数自检（T10） | `scripts/check-r-line-count.sh` | 0.5 hr |
| R131-S7 | H-17 假绿自证能红 | `scripts/check-gate-self-red.sh` | 1 hr |
| R131-S8 | 看镜像 R131 段 + log.md 回填 | 看镜像 + log.md | 1 hr |
| **合计** | | | **6 hr** |

### P1 等 owner 拍板后派单（5 项 wt）

- wt-1 M1 看板化（拍板 #17）/ wt-9 H-7+M5 E2E 阻断（拍板 #1）/ wt-13 F-GREEN 假绿改造（拍板 #4/#6）

### P2 B 类自动通过拍板后派单（6 项）

- #7/#8/#9/#10/#12/#14 = 5.5 hr PM-OWNED 接管

---

## §六 撞车 0 让路下不擅自做的清单（明示给 owner）

| # | 不擅自做的事 | 让路原因 | 解锁条件 |
|---|---|---|---|
| 1 | 启 IPD 后端真活 E2E | 撞兄弟会话 PID + 端口风险 | owner 拍板 #1 |
| 2 | DDL apply | SRE 专属通道 | owner 拍板 #15 |
| 3 | Java 源码改动 | 跨 wt 改他人未提交代码 | owner 拍板 #17 派单顺序 |
| 4 | SQL 整改 | DDL apply 必经 SRE | owner 拍板 #15 |
| 5 | 接管兄弟会话 10 个 wt | 撞车 0 软化规则 | owner 明确授权接管 |
| 6 | 兄弟会话 66 个 untracked 文件 | 撞车 0 让路 | owner 拍板 #18 跨仓 BCP |
| 7 | 兄弟会话 2 个 modified 文件 | 撞车 0 让路 | owner 拍板 #18 |
| 8 | 跨仓 docs 直写（前仓 docs/）| 撞车 0 让路 | owner 拍板 #18 |
| 9 | 杀 PID 34560/70554/29607/65576 | 撞车 0 红线 | 不杀 |

---

**拍板决策包目录创建时间**：2026-09-20 14:30
**撞车 0 严守**：✅ docs-only 落档；不动兄弟会话 modified；不杀 PID / 不擅自动 DDL / 不启后端
**下次刷新**：18 项决策包骨架创建后逐一落档 + 看镜像 R131 段同步
