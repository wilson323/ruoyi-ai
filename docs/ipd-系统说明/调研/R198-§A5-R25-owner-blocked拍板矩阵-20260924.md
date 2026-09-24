# R198 §A5 R25 owner-blocked 5 项拍板矩阵（2026-09-24）

> **撰写者**：主协调会话（按 R94「主协调不替 owner 拍板」+ R195 §六 第 3 项 §A5 落档 docs-only 拍板矩阵）
> **目的**：把 R194 §A5 调研报告 §七「5 项汇总拍板点」展开为 5 项 × 3 选项矩阵 + 工作量 + 风险 + 验证 + 回滚
> **撞车 0 严守**：仅 docs/ipd-系统说明/ 落档摆选项，不动 Java/SQL/yml/真库/端口/PID

> **撞号透明登记**：本盘与已 push `852992ef R198 R197-W3 CI 集成拍板材料` 同编号 R198 但分主题（W3 CI 集成 vs §A5 拍板矩阵）。按"撞号透明协议"+ R195 §六 顺序，本盘落档在 `§A5` 子主题下，与 §A5 调研报告并列；BCP §三十六 透明登记。

---

## 一、任务背景

R189 实装完成 R25 7 项 DONE（R190 doc sweep 验证 commit `30955833`），剩余 5 项 owner-blocked 待 owner 拍板：

| # | R25 § | 项 | 风险 | 阻塞原因 |
|---|------|----|------|---------|
| 1 | §A1 #1 | AllowanceService 删 | 高 | R14 AllowanceService 误删同类教训警示 |
| 2 | §A1 #4 | RequirementPool Entity 删 | 高 | 真库表存在 + 实体双轨（Entity 单数 vs 真库复数）|
| 3 | §A1 #5 | PersonResignEscalator 休眠 | 中 | 需启服务看真日志确认是否休眠 |
| 4 | §A7 #5 | KPI_REVISION_MODE 删 | 低 | BusinessConfigServiceTest.java:104 引用未消除 |
| 5 | §A7.2 #1~3 | POST_LAUNCH_REVIEW_* 3 常量 | 中 | R25 判定失真：判定时 main 未合并 P2-5.6 实装，R189 现查 main 全链路可达 |

---

## 二、5 项 × 3 选项拍板矩阵

### 项 1 / §A1 #1 AllowanceService

| 选项 | 描述 | 工作量 | 撞车风险 | 验证 | 推荐（采纳）|
|---|---|---|---|---|---|
| **A 保留** | 取消 R25 "死代码"判定（R194 §A5 §二调研：test 3 文件 `new` 真用）| 0 | 中（保留=不删，零风险）| mvn test 仍 2366 PASS | **是**（取消失真）|
| **B 删 + 改 test** | 删 AllowanceService.java + 改 3 测试文件（移除 `new AllowanceService`）| 2-3h | 高（删 Service 触发 test 编译失败，参考 R14 AllowanceService 误删教训）| mvn -Dtest=Allowance* test → 仍 PASS |
| **C 标 `@Deprecated` + 留** | 加 `@Deprecated` 注释 + 不删，下版本再议 | 0.5h | 0 | mvn test 仍 PASS |

**R194 §A5 §七调研结论**：保留（R25 失真，test 真用，保留）

**owner 拍板点**：是否接受"R25 判定时 test 文件未被发现 = 扫描盲区"失真登记

### 项 2 / §A1 #4 RequirementPool Entity

| 选项 | 描述 | 工作量 | 撞车风险 | 验证 | 推荐（采纳）|
|---|---|---|---|---|---|
| **A 删 Entity + 不 drop 真库** | 删 Entity + @TableName 引用 + 不动真库表 | 1h | 低（真库表保留，访问路径变 0）| mvn test 仍 PASS + 真库 SELECT 仍 OK | — |
| **B 改 Entity 对齐真库** | Entity `@TableName("requirement_pools")` 改复数对齐真库 | 0.5h | 0 | mvn test PASS + 真库字段映射正常 | **是**（最低风险）|
| **C 删 Entity + drop 真库表** | 删 Entity + DBA apply DROP TABLE | 1h+DBA 窗口 | 中（drop 不可逆，需 DBA 备份）| DBA apply + mvn test PASS |

**R194 §A5 §七调研结论**：先校正后判（默认 Option B 改 Entity）

**owner 拍板点**：DBA 决策是否 drop 真库表（需 P3-LOW DBA 窗口 + 备份）

### 项 3 / §A1 #5 PersonResignEscalator

| 选项 | 描述 | 工作量 | 撞车风险 | 验证 | 推荐（采纳）|
|---|---|---|---|---|---|
| **A 保留** | 取消 R25 "休眠"判定（R194 §A5 §四调研：`@Scheduled` 已生效 + 4 注释引用）| 0 | 0 | 启服务看真日志有定时执行记录 | **是**（取消失真）|
| **B 删 + 删 4 注释** | 删 Service + 删 4 处注释引用 | 1h | 低（删 Service 触发编译，需连改 4 文件）| mvn compile + 真活测试 |
| **C 标 `@Deprecated` + 保留** | 加注释"暂留观察"，下版本再议 | 0.5h | 0 | 真活日志观察 1 周 |

**R194 §A5 §七调研结论**：保留（取消 R25 休眠判定）

**owner 拍板点**：是否接受"Spring 自发现调度 ≠ 休眠" 失真登记

### 项 4 / §A7 #5 KPI_REVISION_MODE

| 选项 | 描述 | 工作量 | 撞车风险 | 验证 | 推荐（采纳）|
|---|---|---|---|---|---|
| **A 保留** | 默认挂 B-RULE-02（test 引用未消除前不动）| 0 | 0 | mvn test PASS + 灰度观察 | **是**（最稳）|
| **B 删 + 改 test** | 删 `KPI_REVISION_MODE` 常量 + 改 `BusinessConfigServiceTest.java:104` 用默认 `REVISION_ALLOWED` | 1-2h | 中（test 改触发编译失败）| mvn -Dtest=BusinessConfig* test PASS |
| **C 保留 + test 移除引用** | 常量保留 + test 改用新写法（不依赖常量）| 0.5h | 0 | mvn test PASS |

**R194 §A5 §七调研结论**：保留（默认 A）

**owner 拍板点**：是否接受 test 引用未消除 = 优先保活而非切 B-C

### 项 5 / §A7.2 #1~3 POST_LAUNCH_REVIEW_* 3 常量

| 选项 | 描述 | 工作量 | 撞车风险 | 验证 | 推荐（采纳）|
|---|---|---|---|---|---|
| **A 保留** | 取消 R25 "全链路不可达"判定（R194 §A5 §六调研：main 现查全链路可达 = Controller @SaCheckPermission 真用 + 角色绑定 + Service + Mapper + Domain + SQL + test）| 0 | 0 | mvn test PASS + 7 个活文件全在 | **是**（取消失真）|
| **B 删 + 改 Controller + test** | 删 3 常量 + 改 PostLaunchReviewController 3 处 @SaCheckPermission + 改 test | 3-4h | 高（删权限常量 = 改权限链路 = 影响所有 PostLaunchReview 端点）| mvn test PASS + HTTP POST /post-launch-review 真活 |
| **C 工作树外链清理** | 删 wt-r127-be / wt-r157-e-b 各 6 文件同名残留（兄弟会话 worktree 残留）| 0.5h | 中（兄弟会话 worktree，主协调不动）| 历史分支残留，合并入 main 后已无作用 |

**R194 §A5 §七调研结论**：保留（取消 R25 失真）

**owner 拍板点**：是否接受"R25 判定时 main 尚未合并 P2-5.6 实装"失真登记

---

## 三、汇总拍板矩阵速览

| 项 | A 选项 | B 选项 | C 选项 | 默认推荐 |
|---|---|---|---|---|
| 1 AllowanceService | 保留（取消失真）| 删 + 改 test | @Deprecated + 留 | **A**（取消失真）|
| 2 RequirementPool Entity | 删 Entity 不 drop | 改 Entity 对齐真库 | 删 Entity + drop | **B**（最低风险）|
| 3 PersonResignEscalator | 保留（取消失真）| 删 + 删注释 | @Deprecated + 保留 | **A**（取消失真）|
| 4 KPI_REVISION_MODE | 保留（默认挂 B-RULE-02）| 删 + 改 test | 保留 + test 移除引用 | **A**（最稳）|
| 5 POST_LAUNCH_REVIEW_* | 保留（取消失真）| 删 + 改链路 | 工作树外链清理 | **A**（取消失真）|

**4/5 推荐 A（取消 R25 失真）**，1/5 推荐 B（真库对齐）。

---

## 四、拍板后实装路径（owner 决策后由 R199+ 轮执行）

### 项 1/3/5（推荐 A = 0 工作量）

拍板后无需 Java 改动，仅 docs-only 落档：
- BCP-Registry §三十七登记"接受失真"决议
- R25 清单 §A1 #1 / §A1 #5 / §A7.2 #1~3 项改为「已确认保留（失真纠正）」
- 看板对应卡（AllowanceService / PersonResignEscalator / POST_LAUNCH_REVIEW_*）status 翻 done
- 工作量：~0.5h docs-only

### 项 2（推荐 B = 0.5h 改动）

拍板后需 1 个 Java 改动：
- `RequirementPool.java` `@TableName("requirement_pool")` → `@TableName("requirement_pools")`
- 验证：mvn -Dtest=Requirement* test PASS + 真库 SELECT * FROM requirement_pools LIMIT 1 字段映射正确
- 工作量：~0.5h Java + 1h 验证

### 项 4（推荐 A = 0 工作量，但需 owner 决策是否走 B-C）

如选 B：
- `BusinessConfigKeys.java` 删 `KPI_REVISION_MODE` 常量
- `BusinessConfigServiceTest.java:104` 改用 `BusinessConfigKeys.DEFAULT_REVISION` 或新 default
- 工作量：~1.5h Java + 0.5h 验证

---

## 五、与既有范式对齐

- **R194 §A5 调研报告**：本盘 §七「5 项汇总拍板点」3 选项展开依据
- **R190 doc sweep 验证链**：mvn test 2366 PASS / 0 FAIL / p1-ddl-apply-check.py 双库 6 项 APPLIED + GRANT 20/20（基线已绿）
- **R25 失真纠正登记**：5 项中 3 项是 R25 扫描盲区失真（R14 AllowanceService 误删教训警示）
- **OPS-09 单写入者约束**：本盘 docs-only 不动 Java/SQL/yml，与兄弟会话零撞车

---

## 六、撞车 0 兑现

| 维度 | 现状 |
|---|---|
| docs/ipd-系统说明/调研/ 新增 | +1 文件（拍板矩阵），不动既有调研 |
| Java/SQL/yml | 未触（拍板后由 R199+ 实装）|
| 真库/端口/PID | 未触 |
| 看板卡 status | 未翻（拍板后由 owner 翻 done）|
| 兄弟会话 modified json | 主工作区剩 1 modified = 兄弟会话 ddl-apply-check-result-20260923.json，未捎带 |
| 兄弟 worktree | 17 个完整保留（含本会话新增 wt-r198b 待 cleanup）|

---

## 七、下一步（owner 拍板权）

owner 可选动作：
- **A 全 A 选项**（4 项取消失真 + 1 项真库对齐）→ 主协调 R199 docs-only 落档"接受失真"决议 + R200 实装 RequirementPool 1 个 Java 改动
- **B 全 B 选项**（5 项全删） → 主协调 R199 docs-only + R200 实装 5 项删除（撞车风险高，需评估）
- **C 项 2 走 C（drop 真库表）** → 主协调 R199 准备 DBA apply 脚本 + 等 DBA 窗口 + R200 落地

要继续 R198 §A2 / §A3 拍板矩阵（同样 docs-only 落档 R194 §A2 §A3 调研结论）还是停下来等 owner 拍 R198 §A5 5 项？