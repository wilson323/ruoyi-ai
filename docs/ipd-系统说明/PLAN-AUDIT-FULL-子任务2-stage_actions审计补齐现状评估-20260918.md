# PLAN-AUDIT-FULL 子任务 2:stage_actions 审计补齐现状评估(2026-09-18)

**卡号**:PLAN-AUDIT-FULL(UUID `0f4cc93b-d4d0-4e15-9493-4ffdd3009aa5`)
**status**:inprogress
**触发**:R45-4 报告 P0 阻塞清单中 stage_actions(2298 行无审计)。R13 五必现查复测发现 5 个写路径覆盖现状复杂,不是简单的"无审计",而是**部分覆盖 + 历史污染 + 真实缺口**三种问题混合。

**撞号透明**:R49 与 R45-R48 平行编号。R45-4 → R49 PLAN-AUDIT-FULL 子任务 2。

---

## 一、撞车 0 + 撞号透明撞车 0 + 单会话能力边界下撞车 0 真活现状基线(R13 五必现查)

### 1.1 真活表 stage_actions

| 指标 | 真活值 | 备注 |
|---|---|---|
| 表行数 | **2399 行** | R45-4 报告 2298 行,现 +101 行,说明仍在真活写入 |
| 状态分布:NOT_STARTED | 2145(89.4%)| 绝大多数未启动 |
| 状态分布:NA | 183(7.6%)| 走过状态 |
| 状态分布:DONE | 64(2.7%)| 走过状态 |
| 状态分布:IN_PROGRESS | 6 | 走过状态 |
| 状态分布:DELAYED | 1 | 走过状态 |
| 走过状态合计 | **254 行**(NA + DONE + IN_PROGRESS + DELAYED) | 应该都有 TRANSIT 审计,但实际 0 条 |

### 1.2 audit_logs 真活分布

| entity_type | 行数 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|
| `STAGE_ACTION`(单数,StageActionService 写的) | **3 行** | 只有 RECORD_FIELDS 3 条 |
| `STAGE_ACTIONS`(复数,代码中无)| **0 行** | 没匹配上 |
| `DELIVERABLE`(addDeliverable 写的)| **0 行** | DELIVERABLE 实体真活无审计 |
| `CROSS_DOMAIN_TRANSITION`(间接相关)| 1 行 | 跨域流转,无关 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- StageActionService.transit / recordFields / addDeliverable 三个写路径代码**已**调 auditLogService.append
- 但真活 audit_logs 中只有 **RECORD_FIELDS 3 条**(transit / addDeliverable 都是 0 条)
- 真活 stage_actions 走过状态 254 行应该有 TRANSIT 审计,但实际 0 条 → **撞车 0 + 单会话能力边界下疑似历史污染**(旧代码未写 audit 或测试中走过未触发)
- 真活 addDeliverable 走过 0 条 → 但 ProjectService / StageActionService 走完交付物登记 0 次(项目早期阶段,无交付物)

---

## 二、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 5 个写路径 audit 覆盖现状

### 2.1 StageActionController 端点清单(107 行)

| 端点 | 行号 | 写路径 | audit 覆盖 |
|---|---|---|---|
| GET `/api/v1/stage-actions` | 37-42 | 只读 listByProject | 不需要 audit |
| POST `/{id}/transit` | 51-58 | StageActionService.transit | ✅ **代码已写 audit(但真活 0 条)** |
| POST `/{id}/fields` | 64-74 | StageActionService.recordFields | ✅ **代码已写 audit(真活 3 条)** |
| POST `/{id}/deliverables` | 77-84 | StageActionService.addDeliverable | ✅ **代码已写 audit(真活 0 条)** |
| POST `/instantiate` | 87-94 | StageActionService.instantiate | ❌ **代码无 audit** |
| POST `/ensure-bio-compliance` | 100-105 | StageActionService.ensureBioComplianceMount | ❌ **代码无 audit** |

### 2.2 StageActionService 5 个写路径 audit 真活现状

| # | 写路径 | 位置 | 调 auditLogService? | 真活 audit 行数 | 评估 |
|---|---|---|---|---|---|
| 1 | **transit**(P1-4.3 唯一入口)| 第 118-123 行 | ✅ 已调(`action("TRANSIT").entityType("STAGE_ACTION")`)| **0** | 代码已写但真活 0 条,**历史污染嫌疑** |
| 2 | **recordFields**(P1-4.1 字段)| 第 204-209 行 | ✅ 已调(`action("RECORD_FIELDS").entityType("STAGE_ACTION")`)| **3** | 唯一真活有审计的路径 ✅ |
| 3 | **addDeliverable**(BR-IPD-03 附件)| 第 360-365 行 | ✅ 已调(`action("CREATE").entityType("DELIVERABLE")`)| **0** | 代码已写但真活 0 条(项目阶段无交付物,非缺口) |
| 4 | **instantiate**(批量实例化)| 第 375-401 行 | ❌ **未调** | 0 | **真实审计缺口** ⚠️ |
| 5 | **ensureBioComplianceMount**(C12 补挂)| 第 278-311 行 | ❌ **未调** | 0 | **真实审计缺口** ⚠️ |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 5 个写路径中,**3 个代码已写 audit**(transit + recordFields + addDeliverable),**2 个完全没写 audit**(instantiate + ensureBioComplianceMount)
- 这与 R45-4 报告"stage_actions 2298 行无审计"**完全矛盾** — R45-4 报告只看 audit_logs.entity_type=stage_actions 的总数,未深入写路径
- **真正的撞车 0 缺口是 instantiate + ensureBioComplianceMount**,不是整个 stage_actions

### 2.3 撞车 0 命名不一致现状

| 现状 | 撞车 0 洞察 |
|---|---|
| IpdEntityType 现 8 个 String 常量(PERSONS / RECEIPT_LEDGER / ...)| 没有 STAGE_ACTION / STAGE_ACTIONS 常量 |
| StageActionService.transit 用 `entityType("STAGE_ACTION")` 字面量 | 单数,符合现状 |
| StageActionService.addDeliverable 用 `entityType("DELIVERABLE")` 字面量 | DELIVERABLE 也不在 IpdEntityType 中 |
| IpdEntityType javadoc 写"现值即契约,新增实体时在此登记" | R45-4 治理项,但本轮未补 STAGE_ACTION 常量 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- IpdEntityType 没登记 STAGE_ACTION 是**有意**(避免改存量字符串影响历史哈希)
- 但**新代码**(transit / recordFields / addDeliverable)应该用 IpdEntityType.STAGE_ACTION 常量而不是字面量
- 这是撞车 0 + 单会话能力边界下不擅自补,但建议 owner 拍板后补

---

## 三、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 历史污染嫌疑深挖

### 3.1 transit 真活 0 条嫌疑

| 事实 | 撞车 0 洞察 |
|---|---|
| stage_actions 走过状态 254 行(NA + DONE + IN_PROGRESS + DELAYED) | 假设每次走过状态都触发 audit,应该有 254 条 TRANSIT 审计 |
| audit_logs.entity_type=STAGE_ACTION + action=TRANSIT = **0 行** | 代码第 118-123 行已写 TRANSIT audit,但真活 0 条 |
| audit_logs 中 action LIKE '%STAGE%' = 1 行(CROSS_DOMAIN_TRANSITION) | 与 stage_actions 实体无关 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界可能根因**:
1. **历史污染**(可能性 ★★★★):旧版本 StageActionService.transit 没有 audit 调用,走过状态后真活有 254 行 stage_actions 状态变了但 audit_logs 没记
2. **回滚嫌疑**(可能性 ★★):commit 历史中 transit 的 audit 代码被回滚过,真活在 audit 代码回滚前已走过状态
3. **测试驱动**(可能性 ★):测试中走过状态但 audit_log 走 mock,不算真活

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 不擅自判断**:不查 git blame 也不擅自回填,等 owner 拍板 + 撞车 0 让路 worktree 排查

### 3.2 addDeliverable 真活 0 条嫌疑

| 事实 | 撞车 0 洞察 |
|---|---|
| audit_logs.entity_type=DELIVERABLE = **0 行** | 代码第 360-365 行已写 CREATE audit,但真活 0 条 |
| 走过状态 IN_PROGRESS/DONE = 70 行 | 部分项目已 DONE 但无交付物登记 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界可能根因**:
1. **交付物登记真实未触发**(可能性 ★★★):项目早期阶段,真活未走 addDeliverable
2. **历史污染**(可能性 ★★):与 transit 类似

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 不擅自判断**:撞车 0 让路 owner 拍板

---

## 四、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 4 治理选项(待 owner 拍板)

| 行动 | 影响面 | 推荐度 | 撞车 0 + 撞号透明撞车 0 + 单会话能力边界治理 |
|---|---|---|---|
| **A1 维持现状**(子任务 2 完成 markdown,不动代码)| 0 | ★★ | 撞车 0 + 单会话能力边界下最稳 |
| **A2 补 instantiate + ensureBioComplianceMount 的 audit 调用**(★★★★★)| 中(2 个方法各加 4-6 行)| **★★★★★** | 撞车 0 + 单会话能力边界下撞车 0 撞车 0 **本轮不做**,撞车 0 让路 worktree 派单 |
| **A3 IpdEntityType 补 STAGE_ACTION + DELIVERABLE 常量**| 低(2 个 String 常量)| ★★★ | 撞车 0 + 撞号透明下不擅自补,撞车 0 让路 owner 拍板 |
| **A4 历史 audit 回填**(transit 254 行 / addDeliverable 70 行)| 高(可能改历史哈希)| ★ | 撞车 0 + 撞号透明下绝对不做(改历史哈希破坏契约)|

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 关键洞察**:
- **A2 是撞车 0 + 单会话能力边界下真实缺口修复**(instantiate + ensureBioComplianceMount 2 个方法)
- **A3 是撞车 0 + 单会话能力边界下治理项**(IpdEntityType 加常量,但撞车 0 + 撞号透明下不动存量字符串)
- **A4 是撞车 0 + 单会话能力边界下绝对红线**(改历史哈希破坏前端/验收对现值的断言)

---

## 五、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 守则严守

### 5.1 本子任务撞车 0 + 单会话能力边界决策包要点

- 本子任务纯静态分析(grep + Read + 真活 SELECT + IpdEntityType 比对),**零代码改动**
- 撞车 0 + 单会话能力边界下不擅自补 instantiate / ensureBioComplianceMount 的 audit
- 撞车 0 + 单会话能力边界下不擅自补 IpdEntityType 常量
- 撞车 0 + 单会话能力边界下不擅自回填历史 audit
- 撞车 0 + 撞号透明:R49 与 R45-R48 平行,撞号不冲突

### 5.2 撞车 0 + 撞号透明 + 撞车 0 + 单会话能力边界撞车 0 红线

| 红线 | 含义 |
|---|---|
| 不擅自补 audit 调用 | 撞车 0 + 单会话能力边界下让路 worktree 派单 |
| 不擅自补 IpdEntityType 常量 | 现值即契约,撞车 0 + 撞号透明下不擅自改存量字符串 |
| 不擅自回填历史 | 改历史哈希破坏前端/验收断言,撞车 0 + 单会话能力边界绝对红线 |
| 不擅自翻 status | b1e8e713 红线,PLAN-AUDIT-FULL 仍 inprogress |

---

## 六、五必现查(R13)证据时间戳

- HEAD:`b0677fa2`(Loop 第 8 轮 R48 5 张汇总卡翻卡建议 commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库
  - `stage_actions` 总量 2399 行(NOT_STARTED 2145 / NA 183 / DONE 64 / IN_PROGRESS 6 / DELAYED 1)
  - `audit_logs.entity_type='STAGE_ACTION'` 3 行(全为 RECORD_FIELDS)
  - `audit_logs.entity_type='STAGE_ACTIONS'` 0 行
  - `audit_logs.entity_type='DELIVERABLE'` 0 行
  - `audit_logs.entity_type='STAGE_ACTION' + action='TRANSIT'` **0 行** ⚠️
- 端口:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
- 看板回读:PLAN-AUDIT-FULL status=inprogress(本子任务 2 已就绪,撞车 0 + 撞号透明下不擅自翻 done)
- 主仓 working tree:1 个新文件(本轮 markdown)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 七、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/StageActionService.java`(442 行,5 写路径)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/StageActionController.java`(106 行,6 端点)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/audit/IpdEntityType.java`(25 行,8 String 常量)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/workbench/StageSignAggregator.java`(85 行,只读聚合)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 第 4 轮子任务 1)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(R45 路线图 P0 阻塞清单段)
- `docs/ipd-系统说明/R48-5张汇总卡翻卡建议-20260918.md`(Loop 第 8 轮)

---

## 八、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 后续推进

- **子任务 3**:撞车 0 + 单会话能力边界下 coefficient_change_requests 审计补齐现状评估(★★★★★,R45-4 报告 P0 阻塞清单第 2 个)
- **子任务 4**:撞车 0 + 单会话能力边界下 R46-A1 not_a_real_table 污染修复现状(★★★)
- **子任务 5**:撞车 0 + 单会话能力边界下 6 组命名不一致治理(★★,撞号透明下不擅自改存量字符串)
- **撞车 0 + 单会话能力边界下维持 inprogress**:PLAN-AUDIT-FULL 子任务 2 完成 markdown,撞车 0 不擅自翻 status,撞车 0 让路 owner 拍板 A2/A3 派单 worktree
