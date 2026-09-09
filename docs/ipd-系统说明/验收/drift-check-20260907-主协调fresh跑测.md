# entity↔真库 drift-check 实测报告（2026-09-07 主协调 fresh 跑测）

**脚本**：`docs/script/sql/check-entity-db-drift.py`（IPD 蜂群根治轮 2026-09-07 落地）
**通道**：socket/cnf（`.codex/ipd-dev/config/mysql-client.cnf`）
**目标库**：`ipd_dev`（MySQL 8.0.46 @ 127.0.0.1:13306）
**HEAD**：`main @ fa2c207`（W28-3 收尾轮 done 342/todo 2 终态）

---

## 1. 实跑命令与退出码

```
$ python3 docs/script/sql/check-entity-db-drift.py \
    --cnf .codex/ipd-dev/config/mysql-client.cnf \
    --db ipd_dev
✗ drift-check 失败：实体 55 个，异常 23 个
EXIT=1
```

**结论**：23/55 = **41.8%** 实体存在 drift（实体字段领先基线 DDL，真库未补迁移）。

---

## 2. 23 个 drift 实体全清单（按缺失列数倒序）

| 实体文件 | 表 | 缺失列数 | 缺失明细 |
|---|---|---|---|
| Contribution.java | contributions | **20** | market_share / rd_share / market_self_initiation / market_self_innovation / market_self_launch / market_self_market_result / market_self_leadership / rd_self_initiation / rd_self_innovation / rd_self_launch / rd_self_market_result / rd_self_leadership / tier_coefficient / market_comment / rd_comment / leader_id / leader_decision / leader_decided_at / leader_opinion / submitted_at |
| NegativeFeedback.java | negative_feedbacks | **18** | trigger_type / main_role / main_person_id / related_role / related_person_id / main_execution / related_execution / bonus_disqualify / tier_delta / trigger_month / recovery_month / trigger_evidence / triggered_by / decided_by / decided_at / lifted_by / lifted_at / decision_comment |
| SopTemplate.java | sop_templates | **7** | template_code / template_name / description / effective_from / effective_to / category / created_by |
| HandoverRecord.java | handover_records | **6** | deadline_at / last_remind_at / escalated_at / rollback_reason / rollback_at / archived_at |
| ProjectScore.java | project_scores | **6** | pm_role / self_score / market_leader_score / rd_leader_score / weighted_score / scored_at |
| AiDocument.java | ai_documents | **3** | review_comment / archived_at / archived_by |
| GateReview.java | gate_reviews | **3** | project_id / sign_due_at / sign_extension_count |
| BonusPool.java | bonus_pools | **2** | calculated_at / distributed_at |
| Gate.java | gates | **2** | materials_url / meeting_minutes_url |
| GateElement.java | gate_review_elements | **2** | sign_due_at / sign_extension_count |
| GateElementResult.java | gate_element_results | **2** | sign_due_at / element_snapshot |
| KpiRecord.java | kpi_records | **2** | revision / scored_at |
| Requirement.java | requirements | **2** | accepted_at / element_snapshot |
| AllowanceLedger.java | allowance_ledgers | **1** | stop_start_date |

## 3. 9 个整表不存在（实体文件存在但 DDL 未 apply）

| 实体文件 | 期望表 | 备注 |
|---|---|---|
| CorrectionLog.java | correction_logs | — |
| GateReviewObserver.java | gate_review_observers | — |
| IpdBusinessConfig.java | ipd_business_config | — |
| IpdBusinessConfigVersion.java | ipd_business_config_versions | — |
| KpiRuleSnapshot.java | kpi_rule_snapshots | — |
| KpiSharedConfirm.java | kpi_shared_confirms | — |
| ProjectScoreRecord.java | project_score_records | — |
| ProjectScoreTask.java | project_score_tasks | — |
| SwitchingAcceptance.java | switching_acceptance | — |

合计：缺列实体 14 个 + 缺表实体 9 个 = **23 个 drift**。

---

## 4. 对 W28-3 done 342 判定的影响

按 `W28-3 收尾轮：P4 依赖链 5 张卡全 done + 看板 done 342/todo 2` 终态，drift-check 揭示：

- **done 集合中所有涉及这 23 个实体的卡实质未完成**
- AC-GLB-12 fail（否决项 14 → 15 漂移）属本次 drift 共生现象
- 涉及卡面（依实体名映射）：
  - AiDocument → P4-2.2 / P4-2.3 AI 文档版本链（验收报告称 done）
  - BonusPool / AllowanceLedger → P3-3.x 奖金池 / 津贴台账
  - Contribution / NegativeFeedback / ProjectScore → P3 激励域
  - Gate / GateElement / GateElementResult / GateReview / GateReviewObserver → P2 Gate 评审
  - HandoverRecord → P1 交接记录
  - KpiRecord / KpiRuleSnapshot / KpiSharedConfirm → P3 KPI 域
  - Requirement → P1 需求域
  - SopTemplate → P4 SOP 模板
  - IpdBusinessConfig / IpdBusinessConfigVersion / SwitchingAcceptance → 配置域
  - CorrectionLog / ProjectScoreRecord / ProjectScoreTask → 通用域

drift-check 是独立于 Mock 单测的真库门禁，**Mock 单测绿 ≠ 业务链真实**——W28-3 done 342 判定未跑此门禁即放行，属系统性失真。

---

## 5. 修复路线（不写代码，仅列治口）

1. **基线 DDL 回写**：将 23 个实体的缺列 / 缺表补入 `docs/script/sql/update/` 迁移脚本，确保幂等
2. **fresh 重跑**：drift-check 退出 0 后再升卡状态
3. **门禁前置**：CI 接入 `python3 docs/script/sql/check-entity-db-drift.py` 作为 PR 卡点，drift>0 不允许 merge
4. **重审 done 集合**：凡涉及 23 个实体的已 done 卡需 fresh 复核（不依赖兄弟流承诺）

---

## 6. 不动代码的约束遵守

- 未修改任何 Java / yml / SQL / 配置文件
- 仅执行 `check-entity-db-drift.py` 读端检查

---

## 7. 2026-09-07 19:05 | 第二轮回填闭环（owner 授权「全部」）

**执行依据**：SSOT 镜像「门禁建议（不擅自执行）」第 1-2 项。撤销 done 卡属 owner 决策，仅执行回填 + 验证，不翻 status。

### 7.1 兄弟会话先手（18:55~19:00）
- `docs/script/sql/update/2026-09-07-ipd-drift-backfill-entity-gap.sql`（兄弟会话落地）
- 覆盖：correction_logs / kpi_rule_snapshots（整表建）+ 部分缺列
- drift-check 退出码变化：23 → 20（减 3）

### 7.2 主协调增量（19:00~19:05）
- 新建 `docs/script/sql/update/2026-09-07-ipd-drift-backfill-round2-20entities.sql`（402 行，33,922 bytes）
- 范围：
  - 整表缺失 7 张：gate_review_observers / ipd_business_config / ipd_business_config_versions / kpi_shared_confirms / project_score_records / project_score_tasks / switching_acceptance
  - 缺列修复：ai_documents(3) / allowance_ledgers(1) / bonus_pools(2) / contributions(18) / gates(2) / gate_review_elements(2) / gate_element_results(2) / gate_reviews(3) / handover_records(6) / kpi_records(2) / negative_feedbacks(12) / project_scores(6) / requirements(2)
- 幂等策略：information_schema 判存在守卫 + 准备语句（CREATE TABLE IF NOT EXISTS / 动态 ALTER）

### 7.3 apply 结果（多语句 pymysql 直灌）
```
$ python3 /tmp/apply_round2.py
=== apply round2 SQL (33922 bytes) via multi-statement ===
=== OK ===
EXIT=0
```

### 7.4 fresh drift-check 验证
```
$ python3 docs/script/sql/check-entity-db-drift.py \
    --cnf .codex/ipd-dev/config/mysql-client.cnf --db ipd_dev
✓ drift-check 通过：实体 55 个，全部与真库对齐
EXIT=0
```

**硬证据对比**：
| 阶段 | drift 实体数 | 退出码 |
|---|---|---|
| 初始（18:55 W28-3 done 收尾后） | 23 | 1 |
| 兄弟 SQL apply 后 | 20 | 1 |
| round2 SQL apply 后 | **0** | **0** |

### 7.5 真库抽样校验（防幻觉）
| 表 | 之前 | 现在 |
|---|---|---|
| contributions | 16 列 | **36** 列 |
| negative_feedbacks | 15 列 | **33** 列 |
| gate_review_observers | 整表不存在 | **9** 列新建 |
| ipd_business_config | 整表不存在 | **11** 列新建 |

### 7.6 OPS-09 守则遵守
- ✅ 仅 apply SQL + fresh drift-check 验证
- ❌ 未翻任何看板 status
- ❌ 未撤销任何已 done 卡（owner 待决策）
- ❌ 未改 Java / yml / 配置文件
- ❌ 未 commit / push（仅 SSOT 镜像 + 报告落地）

### 7.7 后续 owner 待决策项
1. 重审 done 集合：涉及 23 个实体的已 done 卡需 fresh 复核（business 回归测试）
2. CI 接入 `check-entity-db-drift.py` 为 PR 卡点（门禁建议第 3 项）
3. QA-07 / QA-06 / QA-08 卡面失真卡处理（依赖本轮回填完成后再判定）
- 报告用于治理层 + owner 决策输入
- 不翻任何看板 status（不在本卡授权范围）

---

## 7. 落地清单

- ✅ drift-check 实跑命令 + 退出码 + 23 个 drift 全清单（本文 §1-3）
- ✅ 对 W28-3 done 342 的影响评估（本文 §4）
- ⏳ 待 owner 决策：是否撤销已 done 卡 / 跑迁移 / 接 CI
- ✅ 同步源文档镜像：见 `docs/ipd-系统说明/开发计划-看板镜像.md` 末尾追加段