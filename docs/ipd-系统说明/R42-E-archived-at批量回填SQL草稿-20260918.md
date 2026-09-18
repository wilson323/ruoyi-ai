# R42-E archived_at 批量回填 SQL 草稿 — 跟进 R42-D 门禁发现的 6 行遗漏(2026-09-18)

**作者**:主协调会话 · **日期**:2026-09-18 · **状态**:DRAFT SQL 草稿完成,等待 DBA / owner apply
**基线 HEAD**:7e8e1139(R42-D commit) · **owner 触发**:R42-D 自证能红发现的 6 行违规跟进
**承接**:R42-D §实测真库违规(6 行)+ R36 C1.1 archived_at-backfill-9140004.sql 单行遗漏

> 本文档定位:**R36 C1.1 archived_at-backfill-9140004.sql 只回了 1 行(9140004),R42-D 门禁自证能红发现另外 5+ 行遗漏。本 SQL 草稿批量回填 status='ARCHIVED' AND archived_at IS NULL 全部行,留待 DBA / owner apply,不在 CI 中自动执行**。

---

## 1. 一句话总结

R42-D merge gate 第 8 项门禁扫到 projects 表 6 行 `status='ARCHIVED'` 但 `archived_at IS NULL` 的违规,R36 C1.1 archived_at-backfill-9140004.sql 只回填 1 行(9140004)。本 SQL 草稿批量回填全部违规行,**草稿落地 + 留待 DBA / owner apply,不在 CI 中自动执行(避免破坏生产数据)**。

---

## 2. 关键认知

| 维度 | R36 C1.1 当时 | R42-E 草稿 |
|---|---|---|
| 范围 | 单行(9130004) | 批量(6 行) |
| WHERE 守护 | `id = 9140004 AND archived_at IS NULL` | `status='ARCHIVED' AND archived_at IS NULL`(幂等) |
| apply 方式 | DBA 手动 apply | DBA 手动 apply(本草稿不在 CI 自动跑) |
| 复核 | 1 row affected | ~6 row affected |
| 跟进门禁 | 无 | R42-D merge gate 第 8 项(后续 PR 合入必跑) |

---

## 3. 实测真库违规(2026-09-18 R42-D 报告)

| ID | 类型 | 来源 | R36 C1.1 覆盖 |
|---|---|---|---|
| 9140004 | 数字型 | R35 cleanup | ✅ 已 R36 C1.1 回填 |
| 9140001 | 数字型 | R35 cleanup | ❌ R36 C1.1 遗漏 |
| 9140002 | 数字型 | R35 cleanup | ❌ R36 C1.1 遗漏 |
| 9140003 | 数字型 | R35 cleanup | ❌ R36 C1.1 遗漏 |
| 2096325036506877954 | 字符串型 | 雪玢 ID, 真实业务 | ❌ R36 C1.1 遗漏 |
| 2096325111970795521 | 字符串型 | 雪玢 ID, 真实业务 | ❌ R36 C1.1 遗漏 |
| +1 | 未知 | sample 未列出前 5 | ❌ R36 C1.1 遗漏 |

---

## 4. 撞车风险评估(R25 软化条款 3 步)

1. **不动兄弟任何文件**: 仅新增 SQL 草稿, 不修改任何 .java / .yml / .sh ✅
2. **撞车主题**: 不涉及兄弟 R41 / wt-r39-integration 工作(纯 SQL DML) ✅
3. **强合入会破坏数据**: 风险高, 必须 owner 决策 + DBA apply + apply 前 SELECT 复核 ✅

**撞车 = 0**。**风险**: 数据修改, 不可自动跑。

---

## 5. 改动清单

| 文件 | 状态 | 行数 |
|---|---|---|
| `docs/script/sql/update/2026-09-18-r42-e-archived-at-batch-backfill.sql` | NEW | 50 行(3 段: SELECT 扫描 + UPDATE 批量 + SELECT 复核) |
| `docs/script/sql/update/2026-09-18-r36-archived-at-backfill-9140004.sql` | UNCHANGED | R36 提交, 不动 |

---

## 6. 三件套

- 报告:本文件
- log.md:R42-E 段(同步 append)
- 看板镜像:R42-E 卡段(同步 append)
- commit:本文件 + SQL 草稿(不 push)

---

## 7. Apply 流程(留给 DBA / owner)

### 7.1 apply 前(必跑)

```bash
# 1. 跑 R42-D 门禁拿到完整 ID 列表
bash scripts/check-merge-gate-archived-at.sh --strict

# 2. 把 ID 列表存到 /tmp/r42-e-id-list.txt
# 期望:6 处违规
```

### 7.2 apply(分两步)

```bash
# 1. 先跑 SELECT 扫描当前违规行
mysql --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf \
  -e "SOURCE docs/script/sql/update/2026-09-18-r42-e-archived-at-batch-backfill.sql;" 2>&1 | head -10
# 仅跑第一步 SELECT,确认范围与 R42-D 报告一致(~6 行)

# 2. 确认后跑 UPDATE + SELECT 复核
mysql --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf \
  -e "SOURCE docs/script/sql/update/2026-09-18-r42-e-archived-at-batch-backfill.sql;" 2>&1
# 期望:UPDATE ~6 row affected, SELECT remaining_violations = 0
```

### 7.3 apply 后(必跑)

```bash
# 复核:跑 R42-D 门禁应显示 0 违规
bash scripts/check-merge-gate-archived-at.sh --strict
# 期望:exit 1 变 0,或者 warning mode 显示 0 violations
```

---

## 8. 留给 R43+

1. R42-B T4 strict 模式切换(owner-blocked, 等密钥迁移完成)
2. R42-D merge gate 第 8 项长期监控(每 PR 合入前必跑)
3. R42-E apply 后清点 archived_at 历史缺口(看是否有其他表遗漏)

---

## 9. 与兄弟 R41 工作的边界

- 不涉及兄弟 R41 任何文件 ✅
- 不动 R36 C1.1 SQL(原 1 行回填保留, 作历史审计) ✅
- 不撞 wt-r39-integration(纯 SQL 草稿, 不动 scripts/ 现有脚本) ✅

---

## 10. 自证能红 + 还原方法

- **自证**: SELECT + UPDATE + SELECT 复核 三段式, apply 前可重跑 SELECT 复核范围
- **还原**: UPDATE 不可逆(批量改 archived_at = NOW()), apply 前必须 owner 决策
- **幂等守护**: `WHERE status='ARCHIVED' AND archived_at IS NULL` 保证二次 apply 0 row affected