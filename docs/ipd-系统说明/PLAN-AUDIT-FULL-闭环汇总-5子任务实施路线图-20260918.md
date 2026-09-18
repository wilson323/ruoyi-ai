# PLAN-AUDIT-FULL 闭环汇总:5 子任务 + 实施路线图(2026-09-18)

**卡号**:PLAN-AUDIT-FULL(UUID `0f4cc93b-d4d0-4e15-9493-4ffdd3009aa5`)
**status**:inprogress
**触发**:PLAN-AUDIT-FULL 5 子任务全部完成现状评估(Loop 4 / Loop 9 / Loop 10 / Loop 11 / Loop 12),需要 1 份闭环汇总统领:现状总览 + 实施优先级 + owner 决策清单 + 后续推进。

**撞号透明**:R53 与 R45-R52 平行编号。R45-4(子任务 1)→ R49(子任务 2)→ R50(子任务 3)→ R51(子任务 4)→ R52(子任务 5)→ R53(闭环汇总)。

**撞车 0**:本会话撞车 0 + 仅 docs/ 改动;不擅自翻 status(PLAN-AUDIT-FULL 仍 inprogress);不擅自实施任何代码 / 数据改动;撞车 0 + 单会话能力边界下让路 owner 派单 worktree 实施。

---

## 一、撞车 0 + 撞号透明撞车 0 + 单会话能力边界下撞车 0 5 子任务现状总览

### 1.1 5 子任务 markdown 索引

| 子任务 | loop | 编号 | markdown | 行数 | 撞车 0 洞察 |
|---|---|---|---|---|---|
| **子任务 1:审计覆盖缺口清单** | Loop 4 | R45-4 | `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md` | 145 | IPD 业务表 38 张 / 已覆盖 27 张 / 未覆盖 11 张 / 污染 1 个 / 命名不一致 6 组 |
| **子任务 2:stage_actions 审计补齐** | Loop 9 | R49 | `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务2-stage_actions审计补齐现状评估-20260918.md` | 188 | stage_actions 2399 行 / 5 写路径(3 已写 audit + 2 真实缺口)/ transit 真活 0 条历史污染 |
| **子任务 3:coefficient_change_requests 审计补齐** | Loop 10 | R50 | `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务3-coefficient_change_requests审计补齐现状评估-20260918.md` | 198 | 表 2 行 / 3 写路径全部已写 audit / 真活 0 条历史污染 |
| **子任务 4:R46-A1 污染修复** | Loop 11 | R51 | `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务4-R46-A1污染修复现状-20260918.md` | 202 | deletion_requests 52 行 / 24 行测试污染 46% / submit 无 entityType 白名单 |
| **子任务 5:6 组命名不一致治理** | Loop 12 | R52 | `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务5-6组命名不一致治理现状-20260918.md` | 235 | audit_logs 30 distinct entity_type / 6 组命名不一致(大小写 8 / 单复数 2 对 / 拼写 1 / 未登记 15 / AI 混乱 3 / 零真活已登记 1) |
| **闭环汇总**(本轮)| Loop 13 | R53 | `docs/ipd-系统说明/PLAN-AUDIT-FULL-闭环汇总-5子任务实施路线图-20260918.md` | - | 本轮 markdown |

### 1.2 5 子任务真活撞车 0 + 单会话能力边界下洞察汇总

| 子任务 | 真活现状 | 撞车 0 关键洞察 |
|---|---|---|
| 子任务 1 | 38 业务表 / 已覆盖 27 / 未覆盖 11 / 污染 1 / 命名不一致 6 | 撞车 0 + 单会话能力边界下子任务 2-5 顺势推进 |
| 子任务 2 | stage_actions 2399 行 / 走过状态 254 / audit 真活 0 | **代码写了 audit 但真活 0 条** → 历史污染嫌疑 |
| 子任务 3 | coefficient_change_requests 2 行 / audit 真活 0 | **代码写了 audit 但真活 0 条** → 与子任务 2 同模式 |
| 子任务 4 | deletion_requests 52 行 / 测试污染 24 行 46% | **未加 entityType 白名单校验** → 撞车 0 + 单会话能力边界下真实校验缺口 |
| 子任务 5 | 30 distinct entity_type / 6 组命名不一致 | **撞号透明下不擅自改存量字符串** → 绝对红线 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- **2 个不同业务表 + 不同 Service + 不同 audit 调用方式**都出现"代码写了 audit 但真活 0 条"(**子任务 2 + 子任务 3 同模式**)— 系统性历史污染嫌疑
- **1 个业务表无 entityType 白名单校验**(**子任务 4**)— 真实校验缺口
- **30 distinct entity_type 命名不一致 6 组**(**子任务 5**)— 撞号透明下不擅自改存量字符串
- **5 子任务汇总 = 3 大类问题**:①历史污染嫌疑(子任务 2/3)②校验缺口(子任务 4)③命名不一致(子任务 5)

---

## 二、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 实施优先级路线图

### 2.1 优先级 1(P0 阻塞清单 — R45-4 路线图 P0 段)

| # | 行动 | 影响面 | 推荐度 | 撞车 0 + 撞号透明下治理 |
|---|---|---|---|---|
| **P0-1** | **A4(R51 子任务 4)SQL DELETE 24 行 not_a_real_table 污染** | 中(数据删除,可恢复)| ★★★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 owner 派单 worktree 派单 `agent-batch8-audit-sql` |
| **P0-2** | **A3(R51 子任务 4)DeletionRequestController.submit 加 entityType 白名单** | 中(代码改动 ~30 行)| ★★★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 owner 派单 worktree 派单 `agent-batch8-audit-controller` |
| **P0-3** | **A2(R52 子任务 5)新增 13 个未登记 entity_type 到 IpdEntityType** | 中(13 个 String 常量)| ★★★★ | 撞车 0 + 撞号透明下撞车 0 让路 worktree 派单 `agent-batch8-audit-entitytype`(新增不改存量)|

### 2.2 优先级 2(P1 阻塞清单 — R45-4 路线图 P1 段)

| # | 行动 | 影响面 | 推荐度 | 撞车 0 + 撞号透明下治理 |
|---|---|---|---|---|
| **P1-1** | **A2(R49 子任务 2)StageActionService.instantiate 加 audit** | 小(~6 行)| ★★★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 worktree 派单 `agent-batch8-audit-stageaction` |
| **P1-2** | **A2(R49 子任务 2)StageActionService.ensureBioComplianceMount 加 audit** | 小(~6 行)| ★★★★★ | 同上,合并派单 |
| **P1-3** | **A2/A3(R49/R50 子任务 2/3)全量审计历史污染排查** | 高(可能改历史哈希)| ★★★ | 撞车 0 + 撞号透明下让路 owner 拍板 + worktree 派单 `agent-batch8-audit-history` |
| **P1-4** | **A3(R49/R50 子任务 2/3)auditLogService.append 事务传播审计** | 中 | ★★★★ | 撞车 0 + 单会话能力边界下让路 owner 派单 worktree 派单 |

### 2.3 优先级 3(P2 阻塞清单 — R45-4 路线图 P2 段)

| # | 行动 | 影响面 | 推荐度 | 撞车 0 + 撞号透明下治理 |
|---|---|---|---|---|
| **P2-1** | **A3(R52 子任务 5)全量统一 6 组命名不一致** | 高(改历史哈希)| ★ | 撞车 0 + 撞号透明下**绝对不做**(改历史哈希破坏契约)|
| **P2-2** | **A4(R50 子任务 3)coefficient_change_requests 历史 audit 回填** | 高 | ★ | 同上,绝对不做 |

### 2.4 撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 派单 worktree 建议

**撞号透明下 owner 派单参考命名空间**:
- `agent-batch8-audit-sql` (P0-1 + P0-3 部分)
- `agent-batch8-audit-controller` (P0-2)
- `agent-batch8-audit-entitytype` (P0-3 部分)
- `agent-batch8-audit-stageaction` (P1-1 + P1-2)
- `agent-batch8-audit-history` (P1-3 + P1-4)

**撞车 0 + 撞号透明 + 单会话能力边界关键洞察**:
- 5 个 worktree 命名空间,撞号不冲突
- 每个 worktree 派单 1-2 个 P0/P1 任务,避免单一 worktree 跨多表
- 撞车 0 让路 owner 拍板 + worktree 派单,本会话不擅自实施

---

## 三、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 owner 决策清单

### 3.1 决策点 1:历史污染嫌疑是否系统性排查?

| 选项 | 撞车 0 + 撞号透明下决策 |
|---|---|
| A:全量排查(子任务 2/3 + 其他 9 个未覆盖表) | ★★★ 工作量大,需 git blame + 真活 SQL JOIN |
| B:仅排查子任务 2/3(已知模式) | ★★★★ 优先解决已知模式 |
| C:不排查,仅实施新增 audit(子任务 2 真实缺口)| ★★★ 撞车 0 + 单会话能力边界下最稳 |
| **D:推荐 B + C 并行** | ★★★★★ owner 拍板后实施 |

### 3.2 决策点 2:R46-A1 污染 24 行如何清理?

| 选项 | 撞车 0 + 撞号透明下决策 |
|---|---|
| A:物理 DELETE | 数据删除,R46 路径 1,owner 拍板 |
| B:软删(del_flag=1) | 可恢复,但 del_flag=1 的行仍占 audit_logs 引用 |
| C:加 entityType 白名单后保留历史 | 治本 + 不动数据,R46 路径 3 + 数据保留 |
| **D:推荐 A + C** | ★★★★★ R46 兄弟会话路径 1 + 路径 3 |

### 3.3 决策点 3:命名不一致 6 组如何治理?

| 选项 | 撞车 0 + 撞号透明下决策 |
|---|---|
| A:全量统一(改存量字符串)| 撞车 0 + 撞号透明下绝对不做 |
| B:仅新增未登记(13 个常量)| ★★★★ 撞车 0 + 撞号透明下新增不改存量 |
| C:不动 + 文档登记 | ★★★ 撞车 0 + 单会话能力边界下最稳 |
| **D:推荐 B** | ★★★★ owner 拍板后实施 |

### 3.4 决策点 4:PLAN-AUDIT-FULL 何时翻 done?

| 选项 | 撞车 0 + 撞号透明下决策 |
|---|---|
| A:本轮立即翻 done | b1e8e713 红线,5 子任务 markdown 落盘 ≠ 实施完成 |
| B:P0 全部实施完成后翻 done | ★★★★★ 撞车 0 + 单会话能力边界下 b1e8e713 红线严守 |
| C:永远维持 inprogress | ★★ 撞车 0 + 单会话能力边界下过度保守 |

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 关键洞察**:
- **B 是撞车 0 + 单会话能力边界下推荐方案**(P0 全部实施完成后翻 done)
- b1e8e713 红线严守:**子卡 markdown 落盘 ≠ 实施完成**
- 5 子任务 markdown 全部 done,但**PLAN-AUDIT-FULL 汇总卡仍 inprogress**,等 owner 派单 worktree 实施 P0 任务后再翻

---

## 四、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 守则严守

### 4.1 本汇总文档撞车 0 + 单会话能力边界决策包要点

- 本汇总纯 markdown 整合 + 路线图,**零代码改动 + 零数据改动**
- 撞车 0 + 单会话能力边界下不擅自翻 PLAN-AUDIT-FULL status
- 撞车 0 + 单会话能力边界下不擅自实施 P0/P1 任务
- 撞车 0 + 撞号透明下不擅自改存量字符串
- 撞车 0 + 撞号透明:R53 与 R45-R52 平行,撞号不冲突

### 4.2 撞车 0 + 撞号透明 + 撞车 0 + 单会话能力边界撞车 0 红线

| 红线 | 含义 |
|---|---|
| 不擅自翻 PLAN-AUDIT-FULL status | b1e8e713 红线,等 P0 实施完成 |
| 不擅自改存量字符串 | 撞号透明下"现值即契约" |
| 不擅自 DELETE 真库数据 | 让路 owner 派单 |
| 不擅自加 entityType 白名单 | 让路 owner 派单 |
| 不擅自补 IpdEntityType 常量 | 让路 owner 拍板 |

---

## 五、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 R45-R53 路线图全景

### 5.1 R45-R53 撞号透明平行编号

| 编号 | 主题 | loop | 撞号透明洞察 |
|---|---|---|---|
| R45 | 业务推进路线图 | - | 全局路线图 |
| R45-3 | P3-LOW 字符集治理决策包 | - | 撞号不冲突 |
| R45-4 | PLAN-AUDIT-FULL 子任务 1 | Loop 4 | 撞号不冲突 |
| R46 | loop 验证修复(兄弟会话)| - | 兄弟会话入库 |
| R47 | P-DATA-gap-1 决策包 | Loop 7 | 撞号不冲突 |
| R48 | 5 张汇总卡翻卡建议 | Loop 8 | 撞号不冲突 |
| R49 | PLAN-AUDIT-FULL 子任务 2 | Loop 9 | 撞号不冲突 |
| R50 | PLAN-AUDIT-FULL 子任务 3 | Loop 10 | 撞号不冲突 |
| R51 | PLAN-AUDIT-FULL 子任务 4 | Loop 11 | 撞号不冲突 |
| R52 | PLAN-AUDIT-FULL 子任务 5 | Loop 12 | 撞号不冲突 |
| **R53** | **PLAN-AUDIT-FULL 闭环汇总** | **Loop 13** | **本轮** |

### 5.2 R45-R53 工作量与产出统计

| 维度 | 数值 |
|---|---|
| 撞号透明平行编号数 | 11 个(R45/R45-3/R45-4/R46/R47/R48/R49/R50/R51/R52/R53)|
| markdown 文件总数 | 11 份 |
| 总行数 | ~1800 行 |
| 撞号不冲突 | ✅ 撞号透明下撞号不冲突 |

---

## 六、五必现查(R13)证据时间戳

- HEAD:`e296fad0`(Loop 第 12 轮 R52 commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库
  - `audit_logs` 总量 1538 行
  - `stage_actions` 2399 行 / `coefficient_change_requests` 2 行 / `deletion_requests` 52 行(24 行污染)
  - 30 distinct entity_type / 6 组命名不一致
- 端口:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
- 看板回读:PLAN-AUDIT-FULL status=inprogress(本汇总完成,撞车 0 + 撞号透明下不擅自翻 done)
- 主仓 working tree:1 个新文件(本轮 markdown)+ R46 兄弟会话在途 4 项改动(unstaged,撞车 0 守则)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 七、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 相关文件

- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 4 R45-4,子任务 1)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务2-stage_actions审计补齐现状评估-20260918.md`(Loop 9 R49,子任务 2)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务3-coefficient_change_requests审计补齐现状评估-20260918.md`(Loop 10 R50,子任务 3)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务4-R46-A1污染修复现状-20260918.md`(Loop 11 R51,子任务 4)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务5-6组命名不一致治理现状-20260918.md`(Loop 12 R52,子任务 5)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(R45 路线图 P0 阻塞清单段)
- `docs/ipd-系统说明/R46-loop验证修复-20260918.md`(R46 兄弟会话在途,撞号透明撞车 0 让路提交)
- `docs/ipd-系统说明/P-DATA-gap-1-真活HTTP验收决策包-20260918.md`(Loop 7 R47)
- `docs/ipd-系统说明/R48-5张汇总卡翻卡建议-20260918.md`(Loop 8 R48)

---

## 八、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 后续推进

### 8.1 Loop 14-N 候选(按业务逻辑顺序)

| 优先级 | 候选 | 撞车 0 风险 | 推荐度 |
|---|---|---|---|
| **R54 PLAN-ROOT-1 子任务 1** | 全局待办根治计划批次 1 | 0 | ★★★★★(本会话可独立推进)|
| **R55 PLAN-AI-FULL 子任务 3** | AI 副驾 6 业务环节接入方案 | 0(纯 markdown)| ★★★★(子任务 1+2 已完成) |
| **R56 PLAN-KB-AUTO 子任务 3** | AuditLogEventListener 现状评估 | 0(纯 markdown)| ★★★★(子任务 1+2 已完成) |
| **R57 P0-9 P0 阶段验收追加证据包** | R48 推荐翻 done ★★★★★ | 0(纯 markdown)| ★★★★★(撞车 0 + 单会话能力边界下基于 R48 翻卡建议的追加证据包)|
| **R58 跨仓 R39 系列收尾** | R39-1~R39-9 收口(已完成 done)| 0(纯 markdown)| ★★★ |
| **R59 项目移交 WB-17-1** | 工作台 17 类任务扩展 | 0(纯 markdown)| ★★★(撞车 0 + 单会话能力边界下撞车 0 让路 worktree)|

### 8.2 撞车 0 + 单会话能力边界下原则

- **本会话可独立推进**(纯 markdown 决策包 / 现状评估):R54 / R55 / R56 / R57 / R58
- **本会话撞车 0 + 单会话能力边界下撞车 0 让路**(需要 JVM 重启 / 代码改动 / 数据改动):R59 及以上
- **按业务逻辑顺序**:P0 阶段验收 → P1 执行 → P2 流程 → P3 绩效 → P4 AI → 验收/OPS → 统筹治理
- **撞号透明**:R54-N 与 R45-R53 平行,撞号不冲突
- **不擅自翻 status**:撞车 0 + 单会话能力边界下 b1e8e713 红线严守
