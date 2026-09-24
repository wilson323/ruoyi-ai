# R194-§A5 R25 owner-blocked 5 项调研报告（2026-09-23）

> **调研者**：evolver 子智能体
> **任务源**：R194 IPD 治理轮（§三 后端阻塞并行处理） / R25 owner-blocked 5 项
> **调研方式**：docs-only + grep 现查（不动 Java/SQL/yml/真库/端口/PID）
> **基线**：`/Users/mac/Documents/ruoyi-ai` main HEAD = `30f876f6`（R189 已 merge `de52088d`）
> **依据**：R25 死代码清单-A 后端 §末尾 R190 状态更新段 + R189-R25实装收口 §六 6.2 + 现查 grep 5 项
> **输出**：仅本文件（docs-only）

---

## §一 任务背景

R25 死代码清单（2026-09-09）扫描发现 21 项后端疑似死代码，**R189 已实装 7 项**（commit `de52088d`）。R190 状态更新段登记剩余 5 项需 owner 拍板：

| # | R25 § | 项 | 风险 | 阻塞原因（R25 原文）|
|---|------|----|------|-------------------|
| 1 | §A1 #1 | AllowanceService 删 | P0 高 | 需 owner 复核 R14 AllowanceService 误删同类教训 |
| 2 | §A1 #4 | RequirementPool Entity 删 | P0 高 | 真库表存在 + 实体双轨，DBA 决策是否 drop 表 |
| 3 | §A1 #5 | PersonResignEscalator 休眠评估 | P2 中 | 需启一次服务看真日志确认是否休眠 |
| 4 | §A7 #5 | KPI_REVISION_MODE 删 | P2 低 | BusinessConfigServiceTest.java:104 引用未消除 |
| 5 | §A7.2 #1~3 | OPERATION_POST_LAUNCH_REVIEW_* 3 项删 | P0 高 | 工作树外链 6 引用待彻底扫描 |

R194 §A5 子任务：基于现查 grep 给出每项"现查证据 + 删/留推荐 + owner 拍板点"。**仅调研方案，不实装**（后续 R195 由 owner 拍板后启动实装）。

---

## §二 §A1 #1 AllowanceService 调研

**现查命令**：
```bash
grep -rln "\bAllowanceService\b" ruoyi-modules/ruoyi-ipd/src/main --include="*.java" | sort -u  # 6 文件
grep -rln "\bAllowanceService\b" ruoyi-modules/ruoyi-ipd/src/test --include="*.java" | sort -u  # 3 文件
```

**main 6 文件全部为 javadoc 注释引用**（0 真实注入，0 `import`，0 `new`）：`AllowanceLedgerController.java:93` / `AllowanceLedgerService.java:40,222` / `IAllowanceLedgerService.java:85` / `IAllowanceService.java:20,22` / `SwitchingAcceptanceService.java:55,484`，全部为 `@link` 或 `* AllowanceService.determineStopReasonP332 ...` 形式 javadoc。

**test 3 文件含真 `new AllowanceService()` 调用**（不是注释）：

| 文件 | 行号 | 调用形式 |
|------|------|---------|
| `test/service/P332AcceptanceTest.java` | :20 | `private final AllowanceService service = new AllowanceService(null, null);` |
| `test/service/P333AcceptanceTest.java` | :39, :55, :249 | `private static AllowanceService allowanceService;` + `new AllowanceService(mockAllowanceLedgerMapper, mockProjectMemberMapper);` + 反射 `AllowanceService.class.getDeclaredMethods()` |
| `test/service/P383AcceptanceTest.java` | :45, :49, :130 | `private AllowanceService allowanceService;` + `new AllowanceService(allowanceLedgerMapper, projectMemberMapper);` |

**调研结论（与 R25 判定相反）**：

| 项 | 现查证据 | 删 vs 留 推荐 | owner 拍板点 | 撞车风险 | 工作量 |
|----|---------|--------------|-------------|---------|--------|
| AllowanceService | main 0 真实；test 3 文件 `new AllowanceService()` 真用 | **保留**（R25 "删除" 建议失真）| owner 是否认同"R25 R14 同类教训"延伸保护到 test 真用场景 | **中**：删后会触发 P332/P333/P383 测试编译失败（3 test 文件 + 1 main 接口连带） | 0（建议不删）|

> **关键**：R25 §A1-#1 表中只列了 main 注释引用，未列 test 真实 `new` 调用 → **R25 清单失真**。这是 R14 AllowanceService 误删教训的"测试引用"扩展场景。

---

## §三 §A1 #4 RequirementPool Entity 调研

**现查命令**：
```bash
grep -rln "\bRequirementPool\b" ruoyi-modules/ruoyi-ipd/src/main --include="*.java" | sort -u  # 仅 self 1 文件
grep -rn "RequirementPool\|requirement_pool" docs/script/sql/update/ docs/script/sql/schema/      # SQL 多处
```

**main 引用**：仅 `domain/RequirementPool.java` self 定义（**0 Mapper / 0 Service / 0 Controller**）。

**SQL 真表存在性扫描**（仅查 SQL 文件，不跑真库）：

| 文件 | 行号 | 关键语句 |
|------|------|---------|
| `sql/update/batch_missing_tables.sql` | :94~95 | `CREATE TABLE IF NOT EXISTS requirement_pools`（**复数 pools**） |
| `sql/update/2026-09-21-ipd-rename-3-tables-plural.sql` | :6, :14 | `RENAME TABLE ipd_dev.requirement_pool TO ipd_dev.requirement_pools;`（2026-09-21 已 apply 复数化） |
| 同上 | :19, :25 | GRANT/REVOKE 同步 `requirement_pools` / `requirement_pool` |

**调研结论（与 R25 判定不完全相反，但有新发现）**：

| 项 | 现查证据 | 删 vs 留 推荐 | owner 拍板点 | 撞车风险 | 工作量 |
|----|---------|--------------|-------------|---------|--------|
| RequirementPool Entity | main self-only；真库表名 `requirement_pools`（复数），Entity `@TableName("requirement_pool")`（单数） | **先校正后判**（默认 Option B）| 删 Entity（保留真表，DBA 不动）| 低（删 Entity 不影响 DDL）| 1 文件 |
| 同上 | 同上 | 同上 | **改 Entity 适配复数表名**（"表+实体双轨孤儿"消解）| 低（仅改 `@TableName`）| 1 文件 |
| 同上 | 同上 | 同上 | 删 Entity + drop 真库表（DBA 决定）| **中**（drop 真表需 DBA 窗口 + OPS-09 准入）| 2 文件 + DBA 决策 |

> **关键发现**：R25 写"`@TableName("requirement_pool")` 实体 + `requirement_pool` 表已 CREATE" — 但 SQL 现查**真表已 RENAME 为复数 `requirement_pools`**（2026-09-21），Entity 未跟进改名 → **实体已脱节真库**。R25 清单失真。

---

## §四 §A1 #5 PersonResignEscalator 调研

**现查命令**：
```bash
grep -rln "\bPersonResignEscalator\b" ruoyi-modules/ruoyi-ipd/src/main --include="*.java" | sort -u  # 5 文件
grep -n "@Scheduled\|@Component\|escalateStaleResignations" PersonResignEscalator.java
```

**5 文件引用分析**：

| 文件 | 行号 | 引用形式 |
|------|------|---------|
| `service/PersonResignEscalator.java` | :21, :22, :35, :38 | **真**：`@Component` + `@RequiredArgsConstructor` + `@Scheduled(cron = "0 0 9 * * ?")` + `hrSyncService.escalateStaleResignations(...)` |
| `config/IpdSchedulingConfig.java` | :14 | 注释 "错峰表：PersonResignEscalator 09:00" |
| `hr/HrSyncJob.java` | :20 | 注释 "错峰原则：与 09:00 PersonResignEscalator" |
| `service/HandoverOverdueScanner.java` | :19 | 注释 "每日 09:05（错开 PersonResignEscalator 的 09:00）" |
| `service/HrSyncService.java` | :106 | 注释 "调度接入：本方法由 {@link PersonResignEscalator} 每日 09:00 调用" |

**调研结论（重大发现：R25 "休眠"判定失真）**：

| 项 | 现查证据 | 删 vs 留 推荐 | owner 拍板点 | 撞车风险 | 工作量 |
|----|---------|--------------|-------------|---------|--------|
| PersonResignEscalator | 4 外部文件全注释引用；self 内 `@Component` + `@Scheduled` + 真调用 HrSyncService 方法 | **保留**（R25 "休眠评估/删除"建议失真 — @Scheduled 已生效）| owner 是否认同"Spring @Scheduled 自发现调度 ≠ 休眠"判定 | **低**（保留无副作用）| 0（建议不删）|

> **关键**：R25 §A1-#5 判定"调度任务入口 escalateStaleResignations 在 HrSyncService 中以注释引用，未实际触发；若保持休眠则可删除" — 但现查发现 `PersonResignEscalator.escalateStaleResignations()`（:35 @Scheduled + :38 调用 `hrSyncService.escalateStaleResignations()`）**已在调度执行**，仅 HrSyncService 那个同名方法没被外层调。
> **调研方案建议**（不实装）：若 owner 坚持"确认 @EnableScheduling 是否启用" → 启服务一次看 `cron "0 0 9 * * ?"` 触发日志即可（`grep "escalateStaleResignations" ruoyi-ai.log`），本调研 docs-only 不启服务。

---

## §五 §A7 #5 KPI_REVISION_MODE 调研

**现查命令**：
```bash
grep -rln "KPI_REVISION_MODE\|kpi.revision.mode" ruoyi-modules/ruoyi-ipd/src --include="*.java" | sort -u  # 2 文件
```

**2 文件引用分析**：

| 文件 | 行号 | 内容 |
|------|------|------|
| `main/common/BusinessConfigKeys.java` | :31 | `public static final String KPI_REVISION_MODE = "kpi.revision.mode";` |
| `test/service/BusinessConfigServiceTest.java` | :104 | `String v = service.getString(BusinessConfigKeys.KPI_REVISION_MODE, "append");` |

**调研结论**：

| 项 | 现查证据 | 删 vs 留 推荐 | owner 拍板点 | 撞车风险 | 工作量 |
|----|---------|--------------|-------------|---------|--------|
| KPI_REVISION_MODE | main 1 定义 + test 1 引用（默认值 "append"）| **保留**（默认推荐 A）| **选项 A** 保留 + 暂挂 B-RULE-02 卡对账（最安全）| 0 | 0 |
| 同上 | 同上 | 同上 | **选项 B** 删 main + 改 test 硬编码字符串（需 owner 确认无未来需求）| 中（删后无法复活除非 git revert）| 2 文件 |

> **关键**：test:104 的 `getString(KPI_REVISION_MODE, "append")` 是**带默认值的查询调用**，不依赖 main 常量仍可工作（可改为 `service.getString("kpi.revision.mode", "append")`）。R25 §A7-#5 "保留评估"标注 = "B-RULE-02 卡对账"——本调研**保留选项 A 默认推荐**。

---

## §六 POST_LAUNCH_REVIEW_* 调研

**现查命令**：
```bash
grep -rn "POST_LAUNCH_REVIEW\|PostLaunchReview\|post_launch_review" ruoyi-modules/ruoyi-ipd/src --include="*.java" --include="*.sql" | grep -v "/test/" | head -30
find .claude/worktrees -name "*postlaunch*" -o -name "*post-launch*" -o -name "*PostLaunch*"
```

**main 全链路可达性扫描（重大发现：R25 "全链路不可达"判定失真）**：

| 文件 | 行号 | 内容 | 状态 |
|------|------|------|------|
| `main/security/IpdPermissionCode.java` | :95, :97, :99 | 3 常量定义 `OPERATION_POST_LAUNCH_REVIEW_*` | ✅ 活 |
| `main/security/IpdRolePermissionCatalog.java` | :47, :91, :92 | 3 角色绑定（用 3 常量） | ✅ 活 |
| `main/controller/PostLaunchReviewController.java` | :81, :91, :103 | `@SaCheckPermission(value = IpdPermissionCode.OPERATION_POST_LAUNCH_REVIEW_*)` 3 处真用 | ✅ 活 |
| `main/service/PostLaunchReviewService.java` | 全文 | Service 实现 | ✅ 活 |
| `main/mapper/PostLaunchReviewMapper.java` | 全文 | `BaseMapper<PostLaunchReview>` 接口 | ✅ 活 |
| `main/domain/PostLaunchReview.java` | 全文 | Domain（含 `@TableName`） | ✅ 活 |
| `sql/update/2026-09-07-ipd-rnew-post-launch-reviews.sql` | 全文 | CREATE TABLE post_launch_reviews（已 apply） | ✅ 活 |
| `test/security/RnewPermissionContractTest.java` | :33~72 | 3 常量 + 角色断言真用 | ✅ 活 |
| `test/controller/PostLaunchReviewAccessTest.java` | :336~345 | 权限断言真用 | ✅ 活 |

**工作树外链残留**：`wt-r127-be/` + `wt-r157-e-b/` 各 6 文件同名残留（兄弟会话 worktree，不在 R194 调研范围）。

**调研结论（与 R25 判定相反 — R25 失真）**：

| 项 | 现查证据 | 删 vs 留 推荐 | owner 拍板点 | 撞车风险 | 工作量 |
|----|---------|--------------|-------------|---------|--------|
| OPERATION_POST_LAUNCH_REVIEW_* 3 常量 | main 现查**全链路可达**（Controller @SaCheckPermission 真用 + 角色绑定 + Service + Mapper + Domain + SQL + test 真用）| **保留**（取消 R25 "全链路不可达"判定）| owner 是否认同"R25 判定时 main 尚未合并 P2-5.6 PostLaunchReview 实装"失真登记 | 0 | 0 |
| 工作树外链 6 文件残留 | wt-r127-be / wt-r157-e-b 各 6 文件同名残留 | **调研记录 + 不处理** | owner 是否同意"工作树外链 = 历史分支残留，合并入 main 后已无作用" | 低 | 0 |

> **关键发现**：R25 §A7.2-#1~3 表中标注"全链路不可达"是基于 R25 当时（2026-09-09）main 分支尚未合并 P2-5.6 PostLaunchReview 实装。但**R189 现查（2026-09-23）**main 已经完整含 7 个活文件 + 2 测试引用 = **R25 判定已失真**。
> **彻底扫描命令**（给 owner 备查）：
> ```bash
> grep -rn "PostLaunchReview\|post_launch_review\|POST_LAUNCH_REVIEW" \
>   ruoyi-modules/ruoyi-ipd/src docs/script/sql \
>   --include="*.java" --include="*.sql" --include="*.yml"
> ```

---

## §七 5 项汇总拍板点

| # | R25 § | 项 | 现查证据摘要 | 删 vs 留 推荐 | owner 拍板点 | 撞车风险 | 工作量 |
|---|------|----|------------|--------------|-------------|---------|--------|
| 1 | §A1 #1 | AllowanceService | main 0 真实 / test 3 文件 `new` 真用 | **保留** | 接受"R25 失真，test 真用，保留" | 中（删触发 test 编译失败）| 0 |
| 2 | §A1 #4 | RequirementPool Entity | Entity 单数 vs 真库复数 `requirement_pools` | **先校正后判**（默认 Option B 改 Entity）| 选 A 删 / B 改 / C 删 + drop | 低~中 | 1~2 文件 |
| 3 | §A1 #5 | PersonResignEscalator | `@Scheduled` 已生效 + 4 注释引用 | **保留**（取消 R25 "休眠"判定）| 接受"Spring 自发现调度 ≠ 休眠" | 低 | 0 |
| 4 | §A7 #5 | KPI_REVISION_MODE | main 1 定义 + test 1 引用（带默认值）| **保留**（默认选项 A 暂挂 B-RULE-02）| 选 A 保留 / B 删 + 改 test | 0~中 | 0~2 文件 |
| 5 | §A7.2 #1~3 | POST_LAUNCH_REVIEW_* 3 常量 | main 现查全链路可达 | **保留**（取消 R25 "全链路不可达"判定）| 接受"R25 失真，main 已合并 P2-5.6" | 0 | 0 |

**5 项汇总统计**：
- 建议**保留** 4 项（AllowanceService / PersonResignEscalator / KPI_REVISION_MODE / POST_LAUNCH_REVIEW_*）
- 建议**校正后再判** 1 项（RequirementPool Entity）
- 建议**删除** 0 项
- 总工作量 0~4 文件（owner 拍板后由 R195 实装）

---

## §八 撞车 0 严守 + 下次刷新触发

### 8.1 撞车 0 严守（docs-only 承诺）

- ✅ **不动 Java** — 仅写 1 个新 md 文件，不动任何 `.java`
- ✅ **不动 SQL** — 不动 `docs/script/sql/update/` 任何 `.sql`
- ✅ **不动 yml** — 不动 `ruoyi-admin/src/main/resources/application.yml`
- ✅ **不跑真库** — RequirementPool 真表存在性靠 SQL 文件扫描 + DBA 决策
- ✅ **不启服务** — PersonResignEscalator @Scheduled 是否真调度靠 grep 静态判定
- ✅ **不改 pom.xml** — 不动任何 Maven 配置
- ✅ **不动看板卡 status** — 不动 `开发计划-看板镜像.md`
- ✅ **不抢 13 兄弟 worktree** — 仅 `/tmp/wt-r194-pack/docs/ipd-系统说明/调研/` 落档
- ✅ **不动 OPS-09 兄弟会话 4 java in-flight**（HrSyncController/PersonController/PersonSyncController/IpdPermissionCode）

### 8.2 调研产出 + 下次刷新触发

- 仅 1 个新文件（本文件，~210 行符合 180~250 行约束）
- R195 由 owner 启动：拍板 §七 任意 1 项 → R195 启动对应实装 + mvn compile + mvn test 验证
- 兄弟会话 P2-5.6 PostLaunchReview 推进 → 维持现状（本调研建议已自然合规）

### 8.3 调研局限

- PersonResignEscalator 调度**真触发**未做运行期验证（仅静态判定 @Scheduled + @Component + cron 存在）— 需 R195 启服务看日志验证（属兄弟会话工作）
- RequirementPool 真库 `requirement_pools` 表是否有数据未查（仅扫 SQL 文件确认已 RENAME + 已 apply + GRANT 已同步）— DBA 决策时 `SELECT COUNT(*) FROM requirement_pools` 验证
- 工作树外链 wt-r127-be / wt-r157-e-b 的 PostLaunchReview 残留是否被合并覆盖 = 兄弟会话决策

---

**R194-§A5 调研报告生成完毕。调研依据：现查 grep 5 项 + R25 原清单 + R189 收口报告。核心结论：4 项建议保留 + 1 项先校正后判。撞车 0 严守：仅落档 1 个 md 文件，主工作区 0 M / 0 ??。**
