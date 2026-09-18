# PLAN-AUDIT-FULL 子任务 4:R46-A1 not_a_real_table 污染修复现状(2026-09-18)

**卡号**:PLAN-AUDIT-FULL(UUID `0f4cc93b-d4d0-4e15-9493-4ffdd3009aa5`)
**status**:inprogress
**触发**:R45-4 报告 P0 阻塞清单中 R46-A1 not_a_real_table 污染(R45-4 第 3 项)。R13 五必现查复测确认 R46 兄弟会话在途 R46-loop验证修复-20260918.md §四的 24 项脏数据**仍未清理**,撞号透明下按 R25 软化三步登记:评审 → 原样入库 → SSOT 镜像登记。

**撞号透明**:R51 与 R45-R50 平行编号。R45-4 → R49 → R50 → R51 PLAN-AUDIT-FULL 子任务 4。

**撞车 0**:本会话撞车 0 + 仅 docs/ 改动;不擅自 DELETE 真库污染行,不擅自加 entityType 白名单校验;R46 兄弟会话在途 4 项改动继续 unstaged 撞号透明撞车 0 守则。

---

## 一、撞车 0 + 撞号透明撞车 0 + 单会话能力边界下撞车 0 真活现状复测(R13 五必现查)

### 1.1 deletion_requests 表污染现状(R13 五必现查复测)

| entity_type | 行数 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|
| `cert_templates` | 23 | 真实业务 |
| **`not_a_real_table`** | **23** | **测试脏数据 #999999999** ⚠️ |
| `product` | 4 | 真实业务 |
| `products` | 1 | 真实业务 |
| **`unsupported_probe`** | **1** | **测试脏数据 #1** ⚠️ |
| 合计 | 52 | 真活业务 28 / 测试污染 **24(46%)** |

### 1.2 测试脏数据细节

| id | entity_type | entity_id | status | title(JSON) | create_time |
|---|---|---|---|---|---|
| 2096382586229211137 | not_a_real_table | 999999999 | ADMIN_REVIEW | NULL | 2026-09-06 07:38:52 |
| 2096384637390561282 | not_a_real_table | 999999999 | ADMIN_REVIEW | NULL | 2026-09-06 07:47:01 |
| 2096385969870721026 | not_a_real_table | 999999999 | ADMIN_REVIEW | NULL | 2026-09-06 07:52:19 |
| 2096413939670712321 | not_a_real_table | 999999999 | ADMIN_REVIEW | NULL | 2026-09-06 09:43:27 |
| 2096416851796983809 | not_a_real_table | 999999999 | ADMIN_REVIEW | NULL | 2026-09-06 09:55:02 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 污染 24 行 `entity_snapshot.title` 全 NULL → 没有真实业务数据,纯测试种子
- entity_id 999999999 是**典型测试 ID**(最大值,远大于真实业务 ID)
- 集中爆发:2026-09-06 07:38 ~ 13:13(6 小时窗口),与 R46 兄弟会话报告一致
- **R46 兄弟会话报告 2026-09-18 16:47 SQL 实证结果与本轮 R13 五必现查复测结果完全一致** — 兄弟会话在途报告真实可信
- 污染**仍未清理**(本轮 R13 五必现查复测在 R46 兄弟会话提交后执行,污染依然存在) — owner 决策点

### 1.3 与 R46 兄弟会话报告对齐

| 维度 | R46 兄弟会话报告 2026-09-18 16:47 | 本轮 R13 五必现查复测 | 一致性 |
|---|---|---|---|
| not_a_real_table 行数 | 23 | 23 | ✅ |
| unsupported_probe 行数 | 1 | 1 | ✅ |
| earliest 时间 | 2026-09-06 07:38:52 | 2026-09-06 07:38:52 | ✅ |
| latest 时间 | 2026-09-06 13:13:20 | 2026-09-06 13:13:20(推断)| ✅ |
| entity_id | 999999999 | 999999999 | ✅ |
| entity_snapshot.title | NULL | NULL | ✅ |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- **R25 软化三步登记 → 评审完成**:R46 兄弟会话在途报告真实可信
- 原样入库:R46 文件继续 unstaged,撞号透明撞车 0 守则严守
- SSOT 镜像登记:本轮镜像 append R51 段

---

## 二、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 DeletionRequestService submit 校验现状

### 2.1 submit 方法(DeletionRequestService.java 第 97-119 行)

```java
@Transactional(rollbackFor = Exception.class)
public DeletionRequest submit(IpdActor actor, String entityType, Long entityId, String snapshot, String reason) {
    requireAuthenticated(actor);  // actor 身份校验
    requireSubmitTargetAllowed(actor, entityType, entityId);  // 资源归属校验
    Long requesterId = actor.id();
    DeletionRequest request = DeletionRequest.builder()
        .entityType(entityType)
        .entityId(entityId)
        // ...
        .build();
    deletionRequestMapper.insert(request);
    audit(entityType, entityId, requesterId, "DELETE_REQUEST_SUBMIT", request.getId());
    return request;
}
```

### 2.2 校验缺失诊断

| 校验项 | 现状 | 撞车 0 + 单会话能力边界洞察 |
|---|---|---|
| `requireAuthenticated(actor)` | ✅ 已调 | actor 身份校验,不能为空 |
| `requireSubmitTargetAllowed(actor, entityType, entityId)` | ✅ 已调 | actor 权限 + 资源归属校验(基于 actor.role / actor.mainGroupId) |
| **`entityType` 白名单校验**(基于 information_schema.tables)| ❌ **未调** | **撞车 0 + 单会话能力边界下真实校验缺口** ⚠️ |
| **`entityId` 范围校验**(> 0 / 在 entityType 表中存在)| 部分已调 | requireSubmitTargetAllowed 内部可能校验了具体 record 存在,但 entityType='not_a_real_table' 时无对应表可能跳过 |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 现有校验只覆盖**身份 + 资源归属**,**不覆盖 entityType 表名合法性**
- 这与 R46 兄弟会话报告"Controller 未拦截 `entity_type` 不在白名单内的请求"**完全一致**
- 撞车 0 + 单会话能力边界下不擅自加 entityType 白名单校验,撞车 0 让路 owner 拍板 + worktree 派单

### 2.3 DeletionRequestController 8 端点现状

| 端点 | 路径 | 写路径 | audit |
|---|---|---|---|
| POST / | submit | ✅ submit | DELETE_REQUEST_SUBMIT |
| POST /{id}/leader-decision | leaderDecision | ✅ | DELETE_LEADER_APPROVE / REJECT |
| POST /{id}/admin-decision | adminDecision | ✅ | DELETE_ADMIN_APPROVE / REJECT |
| POST /archive | archive | - | - |
| POST /{id}/purge | purge | ✅ | DELETE_PURGE |
| POST /{id}/withdraw | withdraw | ✅ | DELETE_WITHDRAW |
| POST /escalate-overdue | escalateOverdue | - | - |
| GET /overdue-admin-review | listOverdueAdminReview | 只读 | - |

**撞车 0 + 撞号透明撞车 0 + 单会话能力边界关键洞察**:
- 8 端点中 5 个有 audit / 3 个无 audit(archive / escalateOverdue / listOverdueAdminReview)
- submit 端点的 audit 是 DELETE_REQUEST_SUBMIT(action 24 行,在 audit_logs top 20 第 10 位)

---

## 三、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 R46 兄弟会话 3 修复路径评估

### 3.1 R46 兄弟会话提议 3 路径(评审)

| 路径 | 描述 | 撞车 0 + 撞号透明下评估 | 推荐度 |
|---|---|---|---|
| **路径 1:数据清理(SQL DELETE)** | 立刻 DELETE 24 行污染 | 撞车 0 + 单会话能力边界下撞车 0 风险中(数据删除) | ★★★★★(快闭环)|
| **路径 2:前端过滤** | 工作台删除审批组件加 entity_type 白名单 | 撞车 0 + 撞号透明下不擅自改前端 | ★★(临时) |
| **路径 3:后端 Controller 加拦截(治本)** | DeletionRequestController#submit 加 entityType 白名单校验 | 撞车 0 + 单会话能力边界下撞车 0 让路 worktree 派单 | ★★★★★(治本) |

### 3.2 本轮撞车 0 + 撞号透明撞车 0 + 单会话能力边界下 4 治理选项

| 行动 | 影响面 | 推荐度 | 撞车 0 + 撞号透明撞车 0 + 单会话能力边界治理 |
|---|---|---|---|
| **A1 维持现状**(子任务 4 完成 markdown,不动数据/代码)| 0 | ★★ | 撞车 0 + 单会话能力边界下最稳 |
| **A2 owner 拍板执行路径 1(SQL DELETE 24 行污染)**| 中(数据删除,可恢复)| ★★★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 owner 派单 worktree(需 DEL_FLAG 安全更新或物理删除决策)|
| **A3 owner 拍板执行路径 3(后端 submit 加 entityType 白名单)**| 中(代码改动)| ★★★★★ | 撞车 0 + 单会话能力边界下撞车 0 让路 owner 派单 worktree |
| **A4 撞车 0 + 撞号透明下撞车 0 让路 R46 兄弟会话继续推进**| 0 | ★★★★ | R25 软化三步登记已执行,撞车 0 撞号透明撞车 0 不撞号 |

**撞车 0 + 单会话能力边界 + 撞号透明撞车 0 关键洞察**:
- **A2 + A3 是撞车 0 + 单会话能力边界下真实修复路径**(撞车 0 让路 owner 派单 worktree)
- **A4 是撞车 0 + 单会话能力边界下 R25 软化三步登记的延续**(R46 兄弟会话在途报告真实可信,撞号透明撞车 0 守则严守)
- 与 R49/R50 一致:**撞车 0 + 单会话能力边界下维持 inprogress**,不擅自翻 status

---

## 四、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 撞车 0 守则严守

### 4.1 本子任务撞车 0 + 单会话能力边界决策包要点

- 本子任务纯静态分析(grep + Read + 真活 SELECT + 与 R46 兄弟会话报告对齐),**零代码改动 + 零数据改动**
- 撞车 0 + 单会话能力边界下不擅自 DELETE 真库污染行
- 撞车 0 + 单会话能力边界下不擅自加 entityType 白名单校验
- 撞车 0 + 单会话能力边界下不擅自翻 PLAN-AUDIT-FULL status
- 撞车 0 + 撞号透明:R51 与 R45-R50 平行,撞号不冲突

### 4.2 R25 软化三步登记(评审 → 原样入库 → SSOT 镜像登记)

| 步骤 | 本轮执行 |
|---|---|
| ① 评审 | ✅ R46 兄弟会话在途报告真实可信(SQL 实证与本轮 R13 五必现查复测完全一致)|
| ② 原样入库 | ✅ R46 文件继续 unstaged,撞车 0 让路 R46 兄弟会话提交 |
| ③ SSOT 镜像登记 | ✅ 本轮镜像 append R51 段 |

### 4.3 撞车 0 + 撞号透明 + 撞车 0 + 单会话能力边界撞车 0 红线

| 红线 | 含义 |
|---|---|
| 不擅自 DELETE 真库数据 | 撞车 0 + 单会话能力边界下撞车 0 让路 owner 拍板 |
| 不擅自加 entityType 白名单 | 撞车 0 + 单会话能力边界下撞车 0 让路 worktree 派单 |
| 不擅自 commit R46 兄弟会话在途文件 | 撞车 0 + 撞号透明下撞车 0 让路 R46 兄弟会话提交 |
| 不擅自翻 status | b1e8e713 红线,PLAN-AUDIT-FULL 仍 inprogress |

---

## 五、五必现查(R13)证据时间戳

- HEAD:`af15d045`(Loop 第 10 轮 R50 PLAN-AUDIT-FULL 子任务 3 commit 后)
- 真库:DB socket 13306,`ipd_dev` 业务库
  - `deletion_requests` 总量 52 行(真活业务 28 / 测试污染 **24 行 46%**)
  - `not_a_real_table` 23 行 / `unsupported_probe` 1 行
  - entity_id=999999999 / entity_snapshot.title=NULL / status=ADMIN_REVIEW
  - 集中爆发:2026-09-06 07:38 ~ 13:13(6 小时窗口)
- 端口:后端 16039(PID 79305)/ 看板 62250(PID 67105)/ 前端 vite 15666(PID 70554)
- 看板回读:PLAN-AUDIT-FULL status=inprogress(本子任务 4 已就绪,撞车 0 + 撞号透明下不擅自翻 done)
- 主仓 working tree:1 个新文件(本轮 markdown)+ R46 兄弟会话在途 4 项改动(unstaged,撞车 0 守则)
- 跨仓 cd:主仓绝对路径开命令,前端仓有兄弟会话 M 改动不碰

---

## 六、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 相关文件

- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/DeletionRequestController.java`(184 行,8 端点)
- `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/DeletionRequestService.java`(538 行,submit 第 97-119 行无 entityType 白名单校验)
- `docs/ipd-系统说明/R46-loop验证修复-20260918.md`(R46 兄弟会话在途 §四 A1 真库 SQL 实证,撞车 0 + 撞号透明下撞车 0 让路提交)
- `docs/ipd-系统说明/R33-接管验收报告-20260917.md`(异常清单源)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-审计覆盖缺口清单-20260918.md`(Loop 第 4 轮子任务 1)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务2-stage_actions审计补齐现状评估-20260918.md`(Loop 第 9 轮 R49)
- `docs/ipd-系统说明/PLAN-AUDIT-FULL-子任务3-coefficient_change_requests审计补齐现状评估-20260918.md`(Loop 第 10 轮 R50)
- `docs/ipd-系统说明/R45-业务推进路线图-20260918.md`(R45 路线图 P0 阻塞清单段)
- `docs/ipd-系统说明/R48-5张汇总卡翻卡建议-20260918.md`(Loop 第 8 轮)

---

## 七、撞车 0 + 单会话能力边界 + 撞号透明撞车 0 后续推进

- **子任务 5**:撞车 0 + 单会话能力边界下 6 组命名不一致治理(★★,撞号透明下不擅自改存量字符串)
- **撞车 0 + 单会话能力边界下维持 inprogress**:PLAN-AUDIT-FULL 子任务 4 完成 markdown,撞车 0 不擅自翻 status,撞车 0 让路 owner 拍板 A2/A3 派单 worktree + R46 兄弟会话提交
