# R194-§A1 5 文件私有 audit 抽取方案调研

> 模式：docs-only 调研，不动 Java/SQL/yml/真库/端口/看板卡 status
> 日期：2026-09-23 | worktree：`docs/r194-parallel-pack-20260923` @ `3cfdadfe`
> 拍板建议：维持现状（详见 §四）

## §一 任务背景

R186 配置 vs 消费对账报告（commit `3c533031`，2026-09-23）§A4.5 段发现 5 文件私有 `audit()` 方法重复——同一段 `auditLogService.append(AuditLog.builder()...)` 模板被复制粘贴 5 次，散落 74 处 audit 调用。兄弟会话接手段（commit `e19857ea` ORIGIN-R186-CONVERGE，2026-09-23 18:09 -0700）已对其中 3 个 Controller 落地收敛：删 16 行 `AuditLog.builder()` 模板，改 1 行委托 `auditLogService.append(IpdActor, action, entityType, entityId, reason)` 重载（R21 已建、`IAuditLogService` 接口 line 16 已声明）。R193 §三后端阻塞清单将剩余 2 个 Service 工作量登记为 2-3h。

R194 evolver 子智能体职责：调研剩余 2 个 Service 是否仍需抽取 + 写最小可行实装方案，由后续 owner 拍板实装。本调研为 R186 双轨收口的最后一公里对账，结论将决定 R186 §三 #3「5 文件私有 audit 重复」能否正式闭合（owner 已在 R186 §八 拍板 A，但实装范围仅 3 文件，剩余 2 文件需 R194 给出最终判定）。

## §二 fresh 现状（2026-09-23 现查）

5 文件分两类（Controller 3 + Service 2），私有 `audit()` 形似但语义异：

| # | 文件 | 类 | 私有 `audit()` 签名 | 调用点 | body 模板 | entityType |
|---|---|---|---|---|---|---|
| 1 | PersonSyncController.java | Controller | `(IpdActor, String, Long, String)` | 55/64/73（3 处） | 1 行委托 | `"person_sync_jobs"` |
| 2 | PersonController.java | Controller | `(IpdActor, String, Long, String)` | 80/93/106（3 处） | 1 行委托 | `"persons"` |
| 3 | HrSyncController.java | Controller | `(IpdActor, String, Long, String)` | 113/157/176（3 处） | 1 行委托 | `"persons"` |
| 4 | CoefficientChangeService.java | Service | `(Long, String, Long, String)` | 152/228/234（3 处） | 8 行 builder | `"coefficient_change_requests"` |
| 5 | LaunchDateChangeService.java | Service | `(Long, String, Long, String)` | 184/255/260（3 处）+ line 304 直接 append | 4 行 builder + 1 处直接调 | `"launch_date_change_requests"` |

**关键发现**：

- **3 Controller**（1-3 行）：私有 `audit()` 签名 `(IpdActor, String, Long, String)`，body 已收敛为 1 行委托 `auditLogService.append(actor, action, "<table>", entityId, reason)`，注释明确「R186 消重：委托 IAuditLogService.append(IpdActor,…) 共享重载（R21 已建、接口已声明），消除四份 Controller 同构 builder 拷贝；actor==null 静默跳过语义由重载内部保证，行为等价」。e19857ea 已落地。
- **2 Service**（4-5 行）：私有 `audit()` 签名 `(Long, String, Long, String)`，body 仍为 `AuditLog.builder()…build()` 多行模板，operatorId 是 `Long` 而非 `IpdActor`（与 Controller 签名不一致）；LaunchDateChangeService line 304 额外直接调 `auditLogService.append(AuditLog.builder()…afterData(AuditEventData.json("launchDate", date))…)`，含快照路径，**不可走单一重载**——这条单独留 builder 是因为要附加 `afterData` 序列化。

合计：私有 `audit()` 5 个 + 共 15 处调用 + 1 处直接 append（LaunchDate line 304）。模板同构度 = 3 Controller 同形（已 e19857ea 收敛）+ 2 Service 近形（未收敛但语义异）。

**现存约束小结**：

- 5 文件均已**不存在原始 builder 模板同构问题**（3 Controller 已替换为 1 行委托；2 Service 形似但 line 304 直接调仍需 builder）。
- R186 §A4.5 描述「74 处工具调用」是全仓总数（service 层 + controller 层含 Gate 快照 / before-after 构造 / cipher 掩码等域特化调用），并非 5 文件本地计数；5 文件本地实际只有 16 处。
- 任何抽取方案都需面对「统一签名 vs 类型安全」取舍——Controller 的 `IpdActor` 含 name/role 投影，Service 的 `Long` 仅 ID，强行统一会丢失语义或引入冗余查找。

## §三 抽取方案 3 选 1

| 方案 | 描述 | 优点 | 缺点 |
|---|---|---|---|
| A. BaseController 父类 | 新建 `IpdBaseController`，抽 protected `audit(IpdActor, String, String, Long, String)` | Controller 一改全改，签名集中 | 5 文件 = 3 Controller + 2 Service，Service 不继承 Controller，方案半失效；3 Controller 已收敛为 4 行薄壳（含 2 行注释），提到父类净收益 = -2 行（壳内代码外移），同时引入新基类 |
| B. AuditOps 静态工具类 | 新建 `org.ruoyi.ipd.audit.AuditOps.append*(…)` 静态方法，签名统一 | 5 文件都可用同一入口，方法调用而非继承 | 3 Controller 已直接调 `auditLogService.append(IpdActor,…)`，再加 AuditOps = 双重委托，纯间接层；2 Service 受益需新增 `Long operatorId` 重载（接口扩展 + 调用点改） |
| C. `@IpdAudit` AOP 注解 | 把 15 处调用全换 `@IpdAudit(action=…, entityType=…)` | 一行注解替代 4-8 行 builder，跨切面统一 | R186 §八 已证「注解化上限 ~40%，60% 永久手写」；LaunchDate line 304 含 `afterData`（需 `AuditEventData.json` 序列化）不可注解化；6 个 Service 调用因 action/参数动态也不可注解化；切面 REQUIRES_NEW + 锚行锁时序约束紧，零业务侵入但失去显式审计可读性 |
| **D. 不变（基线）** | 维持 5 文件现状，仅写文档对账 | 0 改动、0 风险、0 测试退化 | 2 Service 私有 builder 留 12 行重复代码（R186 §八 doc 化接受） |

## §四 推荐方案 + 工作量估算

**推荐 D（不变）+ 文档化闭合 R186 §三 #3**

理由三层：

1. **Controller 端 0 收益**：3 Controller 私有 `audit()` 已收敛为 4 行薄壳（2 行注释 + 1 行签名 + 1 行 body），e19857ea 已实装。再抽 BaseController = 净减少 -2 行（壳外移），同时引入新基类，所有 Controller 测试需重新跑全量 `@WebMvcTest` 切片，ROI 为负。
2. **Service 端 ROI 为负**：2 Service 私有 `audit()` 形似但 LaunchDate line 304 走 afterData 快照路径不可走单一重载；强抽 `Long operatorId` 重载 + 删 2 私有方法 = 净收益 12 行 vs 引入 1 个新接口重载 + 改 6 处调用点（line 304 仍需保留 builder 路径）+ 2 处 mock 调整（CoefficientChangeServiceTest / LaunchDateChangeServiceTest 暂无独立测试但 mock 仍存在）+ 1 处 IAuditLogService 接口扩展。改造面 ≥ 收益面。
3. **AOP 端已被 R186 §八 证伪**：注解化上限 ~40%（设计档 §2 已锁死六类不可注解化：beforeData 快照 / afterData 构造 / 动态 action / 循环多条 / 异常路径前落库 / 特殊操作人），本 5 文件均落在 60% 手写区。

ROI 对比表：

| 方案 | 净收益（行） | 引入新接口/类 | 改调用点数 | mock 调整 | 单测补充 | 评审耗时 |
|---|---|---|---|---|---|---|
| A | -2 | BaseController × 1 | 0 | 0 | 全量 WebMvcTest 重跑 | 4h |
| B | +12 | AuditOps × 1 + IAuditLogService 重载 × 1 | 6 | 2 | 1 个接口测试 | 3h |
| C | +15 | 无 | 15（注解替换） | 2 | AOP 切面测试 × 1 | 6h |
| **D** | **0** | **0** | **0** | **0** | **0** | **0** |

**工作量估算 = 0**：0 行 java 改动 / 0 单测补充 / 1 个 docs 文件（即本文）。R186 §三 #3 在 R194 拍板 A 后即可闭合。

## §五 撞车 0 风险评估

兄弟会话 in-flight java 文件（来自 R193 §三登记）：

| # | 文件 | 本调研触及 | 操作 | 撞车 |
|---|---|---|---|---|
| 1 | HrSyncController.java | 否 | 仅 Read 读 line 139-143（私有 `audit()` 体） | 0 |
| 2 | PersonController.java | 否 | 仅 Read 读 line 110-114（私有 `audit()` 体） | 0 |
| 3 | PersonSyncController.java | 否 | 仅 Read 读 line 95-99（私有 `audit()` 体） | 0 |
| 4 | IpdPermissionCode.java | 否 | 仅 Read 引用 §一背景 | 0 |
| 5 | CoefficientChangeService.java | 否 | 仅 Read 读 line 152/228/234/247-256 | 0 |
| 6 | LaunchDateChangeService.java | 否 | 仅 Read 读 line 184/255/260/304/327-331 | 0 |
| 7 | IAuditLogService.java | 否 | 仅 Read 读接口签名 line 14-16 | 0 |
| 8 | AuditLogServiceImpl.java | 否 | 仅 Read 读 line 130-154（IpdActor 重载实现） | 0 |

**撞车点 = 0**（本文仅 Read/Grep 现查，不动 java / sql / yml / pom / 真库 / 端口 / 看板卡 status / git add / commit / push）。本调研与 R186 §三 #3 兄弟会话接手段（e19857ea）不重叠——e19857ea 已 merge main，本调研在 main 基础上做最后一公里对账。

## §六 实施步骤

| 步 | 命令 / 操作 | 验证点 |
|---|---|---|
| 1 | `cd /tmp/wt-r194-pack` worktree 隔离 | branch=`docs/r194-parallel-pack-20260923`，HEAD=`3cfdadfe`，与 main 同 commit；`git worktree list` 已含本路径 |
| 2 | 0 java 文件 stage（grep 只读） | `git status --short` 期望仅显示本文 + 既有 `ddl-apply-check-result-20260923.json`（非本任务引入） |
| 3 | `mvn -pl ruoyi-modules/ruoyi-ipd compile` 跳过 | 推荐结论 = 0 java 改动，无需编译 |
| 4 | `mvn -pl ruoyi-modules/ruoyi-ipd test` 跳过 | 0 单测补充，无需跑测；既有 28 测试（IpdPermissionPasswordScopeTest 7 + ControllerPermissionAnnotationContractTest 4 + RnewPermissionContractTest 17+）不受影响（不触及方法签名） |
| 5 | 写本文 1 docs 文件 | 路径 = `/tmp/wt-r194-pack/docs/ipd-系统说明/调研/R194-A1-5file-audit-extract-scheme-20260923.md` |
| 6 | 主协调统一收口 | 本调研 `git add` / `commit` / `push` 全部跳过，由主协调在 R194 收口时一并处理 |

## §七 owner 拍板点 + 下次刷新触发

**owner 拍板 2 项**：

| 拍板项 | 选项 A（推荐） | 选项 B | 选项 C |
|---|---|---|---|
| 是否同意 R194-§A1 结论「维持现状 + 文档化」？ | ✅ 同意 | ❌ 抽 BaseController | ❌ 抽 AuditOps 工具类 |
| 是否同意关闭 R186 §三 #3 剩余工作量？ | ✅ 同意（3 Controller 已 e19857ea + 2 Service 维持） | ❌ 继续抽取 | — |

**下次刷新触发**：

- 若 owner 拍板 **B/C**：起 R195 实装 → 新开 `wt-r195-audit-long-overload` worktree → 新增 1 个 `IAuditLogService.append(Long operatorId, String action, String entityType, Long entityId, String reason)` 重载 + 删 2 个私有 `audit()` 方法 + 改 6 处调用点（LaunchDate line 304 例外保留 builder）+ 新增 1 个接口方法测试 → 预计 2-3h 实装 + 1h 评审。
- 若 owner 拍板 **A**（维持现状）：R194-§A1 即闭合，下次刷新延后到 R197 或后续轮审计专题（届时可能涉及 `R5-P0-AOP` 注解覆盖率提升 / 14 个非冗余 `audit()` 域特化复查）。


**拍板后的 SSOT 同步动作（owner 同意 A 后）**：

- `log.md` R194 工作会话事项段追加「**§A1 5 文件私有 audit 抽取调研 → 维持现状**」一条
- `BCP-Registry.md` R194 工作会话登记项勘误栏标注「**§三 R186 #3 已闭合**」
- `BCP-Closure-Log.md` R194 工作会话勘误记录追加「**R186 #3 R194 拍板 A 维持现状**」一条
- `开发计划-看板镜像.md` R194 工作会话登记注记栏标注「**§A1 R186 #3 闭合**」

---

**撞车 0 兑现**：
- ✅ 仅落档 1 个 docs 文件（本文件，~120 行 markdown）
- ✅ 未动 java / sql / yml / pom / 真库 / 端口 / 看板卡 status
- ✅ 未 commit / 未 push（主协调统一收口）
- ✅ 未抢 8 个兄弟会话 in-flight java 文件（HrSyncController / PersonController / PersonSyncController / IpdPermissionCode / CoefficientChangeService / LaunchDateChangeService / IAuditLogService / AuditLogServiceImpl）
