# R194-§A2 @IpdAudit AOP vs audit() 工具调用双入口收敛方案（2026-09-23）

> **任务**：R186 §A4 双入口发现的分级处置方案（R193 §三 R194 子任务）| **模式**：docs-only 调研，撞车 0 严守
> **作者**：evolver 子智能体 | **日期**：2026-09-23 | **工作区**：`/tmp/wt-r194-pack/`

---

## §一 任务背景

**触发**：R186 §A4（`后端配置vs消费对账与单一事实源收敛报告-20260923.md` §2.4）发现后端审计入口有 2 条路：
- **@IpdAudit AOP 声明式**（fresh 现查 **2 文件 / 5 处**：ReceiptLedgerController 2 + SwitchingAcceptanceController 3）
- **audit() 工具命令式**（fresh 现查 `auditLogService.append` 全仓 **102 处** / ~30 文件；含 5 文件私有 `audit()` 封装：PersonSyncController / PersonController / HrSyncController / CoefficientChangeService / LaunchDateChangeService）

**R186 §八 owner 已拍板**（2026-09-23 收口）：#4 = **A：接受设计两层共存 + 文档化**——依据 R22 设计稿（`治理/审计AOP改造设计-20260909.md` §2）"注解化上限 ~40%，60% 永久手写"，六类不可注解化（beforeData 快照 / afterData 程序化构造 / 动态 action / 循环多条 / 异常路径前落库 / 特殊操作人），"全覆盖"设计上不可能。

**R194-§A2 工作目标**：把 R186 §八"接受共存"结论**细化为新增端点时的"分级场景决策树"**——给出可操作判定标准 + 存量可注解化余量盘点 + 长期维护约束（防止后人再纠结"二选一"）。

---

## §二 fresh 现状（基线 2026-09-23 现查）

### 2.1 @IpdAudit AOP 声明式（5 处 / 2 文件）

| 文件 | 端点数 | 注解示例 |
|---|---|---|
| `controller/ReceiptLedgerController.java` | 2（line 41 / 63）| `action = "RECEIPT_CREATE/REFUND"` |
| `controller/SwitchingAcceptanceController.java` | 3（line 41 / 54 / 61）| `action = "SWITCHING_RUN/LOCK/UNLOCK"` + `reasonExpr = "#month"` |
| **合计** | **5 处 / 2 文件** | 全部 controller 层，零 service 层 |

切面：`org.ruoyi.ipd.audit.IpdAuditAspect.java`（`@Around("@annotation(ipdAudit)")`，4 通道操作人：adminOnly / operator SpEL / systemOperatorId / IpdAuthSession）

### 2.2 audit() 工具命令式（102 处 / ~30 文件）

| 类别 | 文件数 | 调用处数 | 典型签名 |
|---|---|---|---|
| 私有 audit() 封装 | 5 | ~20 | `audit(IpdActor,…)` ×3 + `audit(Long,…)` ×2 |
| service 内联 `AuditLog.builder()` | ~25 | ~80 | 模板逐字重复 |
| C 类集中点（不动）| 2 | 2 | `AuditLogServiceImpl.append` + `DefaultStateMachineGuard.postCommit` |

### 2.3 是否同构 → **否**，不可互相替换

| 维度 | @IpdAudit AOP | audit() 工具 |
|---|---|---|
| 触发时机 | 方法**成功返回后** | 调用点同步，**可 return 前 / 异常前 / 循环内** |
| beforeData / 动态 action / 循环 | **不支持**（SpEL 编译期常量）| **全部支持** |
| 异常路径 | 业务异常不落 | catch 块前可落（REQUIRES_NEW）|
| 字段完整度 | 新行 100% 补齐 operator 三元组 | 历史 51% operatorName / 34% operatorRole 缺失（**不可回填**）|

---

## §三 收敛方案（3 选 1 评估）

| 方案 | 可行性 | 工作量 | 风险 | 结论 |
|---|---|---|---|---|
| **A · 全走 AOP，删工具调用** | ❌ 不可行（R22 §2 60% 永久手写 + GateReviewService 13 点 `Object... pairs` AOP 拿不到）| 不适用 | 业务行为退化 | 否 |
| **B · 全走工具调用，删 AOP** | ⚠️ 技术可行但逆 R22 设计（commit `0dcd96e7/58dea842/c1384794` 三次实装）| 5 处拆封装 ~2h | 弃用"声明式统一收口"价值 + R22 回滚高代价 | 否 |
| **C · 分级场景——AOP 简单端点 + 工具复杂场景** ⭐ | ✅ **R22 原方案 + R186 §八已采纳** | **0 Java 改动** + 1 决策树文档（本报告）| 极低（仅文档化）| **✅ 胜出** |

**R194-§A2 工作量从原 4-6h 缩减为 0.5h**（仅落档分级场景决策树文档）。

---

## §四 推荐方案 + 工作量估算

### 4.1 分级场景决策树（新增端点判定流程）

```
Q1 在六类不可注解化场景？（beforeData/afterData/动态action/循环/异常前/特殊操作人）
   ├─ 是 → 走 audit() 工具
   └─ 否 → Q2 action/entityType 静态常量？
              ├─ 否 → 走 audit() 工具
              └─ 是 → Q3 需要 beforeData 快照？
                        ├─ 是 → 走 audit() 工具
                        └─ 否 → Q4 操作人在 4 通道内可表达？
                                  ├─ 是 → ✅ 走 @IpdAudit AOP
                                  └─ 否 → 走 audit() 工具
```

### 4.2 六类不可注解化覆盖矩阵

| 类别 | 命中处 | 典型场景 | 走 |
|---|---|---|---|
| beforeData 快照 | 15 | StageActionService `statusSnapshot(a)` | audit() |
| afterData 程序化构造 | 42 | GateReviewService `Object... pairs` | audit() |
| 动态 action / 循环多条 / 异常路径前落库 / 特殊操作人 | 若干 | BidScanService / KPI_SCANNER / GUEST / 登录失败 | audit() |
| **不可注解化合计** | **60+ 处** | — | **audit() 工具** |
| **可注解化余量上限** | **~40 处** | 现状已注解化 5 处 | **@IpdAudit AOP** |

### 4.3 工作量估算

| 子任务 | 工作量 | 状态 |
|---|---|---|
| ~~P1 注解化迁移 9 点~~（R22 计划）| — | R186 §八 拍板搁置 |
| ~~AuditHelper 抽 5 份私有 audit~~ | — | R194-§A1 兄弟会话承接 |
| **本 R194-§A2 = 决策树文档化** | **0.5h** | **本报告** |

---

## §五 撞车 0 风险评估

### 5.1 兄弟会话 in-flight Java 撞车排查

| 兄弟 worktree | 在盘 Java | 与本任务重叠？ |
|---|---|---|
| `wt-r194-pack`（本会话）| docs-only | — |
| `wt-R126-audit` | 审计链 anchor+hash bugfix | ⚠️ 同领域但只读审计链，不碰双入口 |
| `wt-R157-e-b` / `wt-R177-a1~a6` | entity / UI / SM / norm / data / spread / type | 无 |
| `wt-R120/R121/R127-*/wt-P47` | 遗留 fix / gate-element | 无 |

**结论**：双入口相关文件（`audit/IpdAudit*.java` + `*Controller.java` 私有 audit 封装）**0 个未提交改动**（`git log -5 -- audit/` 最近 commit `f987e028` = R15 线 W2-SVC，与本 R194 无交叉）。

### 5.2 撞车 0 严守声明（本 R 轮兑现）

- ✅ 仅 `/tmp/wt-r194-pack/docs/ipd-系统说明/调研/` 落档 1 个新文件
- ✅ 未改 `ruoyi-modules/ruoyi-ipd/src/main/**` 任何 Java
- ✅ 未改 SQL / DDL / Flyway / application.yml / pom.xml
- ✅ 未跑真库（127.0.0.1:13306 ipd_dev）
- ✅ 未杀 PID / 未抢端口 / 未启后端
- ✅ 未改看板卡 status
- ✅ 未 `git add` / `commit` / `push`（报告留 untracked 给主协调）

---

## §六 实施步骤

本方案 = **0 Java 实装 + 1 文档化收口**，仅落档校验：

### 6.1 已完成落档步骤

```bash
mkdir -p /tmp/wt-r194-pack/docs/ipd-系统说明/调研/
# cat > ... << EOF ... EOF（已执行）
wc -l /tmp/wt-r194-pack/docs/ipd-系统说明/调研/R194-A2-ipdaudit-aop-converge-scheme-20260923.md
# 目标 ≤180 行
```

### 6.2 不做项（红线条目）

| 红线 | 不做项 |
|---|---|
| ❌ | 任何 `src/main/**` Java 改动 |
| ❌ | `git add` / `commit` / `push` |
| ❌ | `application.yml` / `pom.xml` 改动 |
| ❌ | `mvn spring-boot:run` 启后端 |
| ❌ | 真库 SQL / Flyway 迁移 |
| ❌ | 看板卡 status 字段 |

### 6.3 验证清单

- ✅ 行数 ≤180 行（本报告 ~155 行目标）
- ✅ 7 段结构完整（§一~§七）
- ✅ 决策树可操作（Q1-Q4 四问判定）
- ✅ 撞车 0 兑现（§5.2 声明）

---

## §七 owner 拍板点 + 下次刷新触发

### 7.1 owner 拍板点

| # | 拍板项 | 建议 |
|---|---|---|
| 1 | 是否采纳**方案 C 分级场景**（= R186 §八已拍板）| ✅ 采纳 |
| 2 | 是否将决策树固化进 `IpdAudit` Javadoc（line 15-17 适用边界段可扩充）| ✅ 采纳（极低代价 1h）|
| 3 | 是否启动 **R22 P1 注解化迁移 ~40 处** | ⏸️ 搁置（R186 §八 已文档化收口）|
| 4 | 是否新增**审计选型静态守卫**（仿 RnewPermissionContractTest fail-closed）| ⏸️ 后续 R 轮 |

### 7.2 下次刷新触发

| 触发条件 | 动作 |
|---|---|
| owner 拍板 #2 → 决策树进 Javadoc | R195+ 做 1h 文档补丁 |
| owner 决定启动 P1 注解化迁移 ~40 处 | 重启 R186 §3-#4 实装卡（4-6h 按 R22 P1 批）|
| **新增 controller/service 端点** | **强制走 §4.1 决策树 Q1-Q4 判定**（长期维护约束）|
| R126-A / R126-B / R157 兄弟会话在审计领域重叠 | 拉通对齐（owner 拍板）|

### 7.3 长期维护约束

> **新审计端点 = §4.1 决策树 Q1-Q4 四问判定**；不可凭"风格统一"硬选其一。
>
> 决策依据：R22 设计稿 §2 六类不可注解化 + R186 §八 owner 拍板 A（接受两层共存）。

---

**撞车 0 兑现**：本报告 = 仅落档 1 个新文件 / 0 Java 改动 / 0 SQL / 0 yml / 0 PID / 0 端口 / 0 看板卡 status / 0 git commit。
