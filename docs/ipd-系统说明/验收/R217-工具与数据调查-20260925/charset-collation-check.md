# 卡 639de2c8 · sys_user ↔ persons 字符集/排序规则一致性现查结论

- **调查时间**: 2026-09-25（R217 并行波次）
- **执行人**: R217 data lane（数据治理调查员）
- **只读声明**: 全程仅 SELECT / SHOW FULL COLUMNS / information_schema 查询与 git log grep；**未执行任何 ALTER/DDL/DML**；未改动仓库文件。凭证未上命令行（`mysql --defaults-extra-file=…/mysql-client.cnf`，socket 探针，ipd_dev @127.0.0.1:13306）。

---

## 一、历史背景（卡面成因链）

1. **卡建因**：R45-3 决策包（log.md:5379-5391，2026-09-18 09:45 PDT 真库现查）记录 `sys_user.user_name = utf8mb4_0900_ai_ci` vs `persons.username = utf8mb4_general_ci`，**真活 JOIN 复现 `ERROR 1267 Illegal mix of collations` 成功**；当时产出决策包 SQL 草稿 `docs/script/sql/update/2026-09-18-p3low-collation-align.sql`（未 apply，推荐方案 A1：persons→0900_ai_ci）。
2. **兄弟会话 W2-CHARSET**：commit `c3e69481`（2026-09-21 21:23 PDT，R161）对 66 张表执行 4 批 in-place `ALTER … CONVERT TO utf8mb4_0900_ai_ci`（B3 批覆盖 person_*/kpi_*/persons，见 `2026-09-21-w2-charset-4batches-in-place-alter.sql:60`），POST-VERIFY 宣称 157/157 表 100% utf8mb4_0900_ai_ci、0 残留；另 R97 波次（09-19，B3）曾先把 persons 单表 CONVERT（log.md R97 段：persons_bk_b3_20260919 备份留存）。

## 二、现库实测（以今日库内实际 collation 为准）

### 2.1 列级（SHOW FULL COLUMNS + information_schema.COLUMNS）

| 表 | 卡面关注列 | 今日实测 Collation |
|---|---|---|
| sys_user | user_name / tenant_id / nick_name / email / phonenumber 等**全部 13 个字符串列** | `utf8mb4_0900_ai_ci` |
| persons | username / employee_no / tenant_id / name 等**全部 14 个字符串列** | `utf8mb4_0900_ai_ci` |

### 2.2 表级
`TABLE_COLLATION`：sys_user=`utf8mb4_0900_ai_ci`，persons=`utf8mb4_0900_ai_ci` ✅

### 2.3 行为级（最强证据：真 JOIN 直接跑通）
```sql
SELECT COUNT(*) FROM sys_user su JOIN persons p ON p.username = su.user_name;   -- 不报 1267，返回 4
SELECT su.user_name, p.employee_no FROM sys_user su
  INNER JOIN persons p ON p.username=su.user_name AND p.tenant_id=su.tenant_id; -- ipd-admin/ipd-leader/ipd-market/ipd-rd 4 行正常
```
卡面症状 `ERROR 1267` **不可复现**。

## 三、结论与建议

**两列（及两表全部字符串列、表默认排序规则）已统一为 utf8mb4_0900_ai_ci，卡 639de2c8 隐患已消除 → 建议直接销卡（done）**，附本报告 §二 三项实测为销卡证据。

### 残留发现（超出本卡范围，如实登记，不影响销卡判断）
- 库内仍有 **1 张表** collation ≠ utf8mb4_0900_ai_ci：`hr_organizations = utf8mb4_general_ci`（W2-CHARSET 09-21 之后由 R149-HR-Sync 新建，逃过 66 表清扫）。若其字符串列将来与 sys_user/persons JOIN，会复刻 1267 同型问题。建议另记 owner：或并入下次 charset 清扫，或在 R217 看板登记「只减不增」跟踪项。**本 lane 未动任何 DDL。**
- 历史证据文件 `docs/script/sql/update/2026-09-18-p3low-collation-align.sql`（决策包草稿）已被实际执行过的 W2-CHARSET/R97-B3 取代，可标注 obsolete。

## ⚠️ 待 owner 拍板标记
- [ ] 卡 639de2c8 依 §二 证据翻 done（销卡）
- [ ] hr_organizations 残留 1 表是否立项跟进（新发现，非本卡范围）
