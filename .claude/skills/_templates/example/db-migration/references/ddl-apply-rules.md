# DDL Apply 规则

## 是什么
项目 DDL 变更必须 apply 到真库（`127.0.0.1:13306` 业务库 `ipd_dev`），并通过 `check-entity-db-drift.py` 验证 entity ↔ 真库 schema 已对齐。

## 为什么踩坑

**形态 1 - SQL 已 commit ≠ 约束已生效**：仓库无 Flyway/Liquibase，`docs/script/sql/update/**` 全靠人工/DBA apply。代码 commit 不代表 DDL 已 apply。

**形态 2 - 实体加字段但 DDL 漏迁移**：真库查询 `Unknown column` 错误。项目记忆"P2-7.4 archived_at"、"notification_events 第二撞"。

**形态 3 - 超前登记**：`tenant.excludes` 里登记了 `person_roles`，但任何库都还没建表，查询时被认为"应该"共享，实际是未被审的"超前承诺"。

## 怎么识别

跑 `python3 docs/script/sql/check-entity-db-drift.py --docker ruoyi-ai-mysql --db ipd_dev`（或 `--cnf socket` 通道）：

- 输出"MISSING"或"DRIFT"行 → DDL 未 apply 或与代码不一致
- 脚本能正确处理 `@TableField("col")` 显式映射与 `exist=false` 排除

## 怎么修

1. 写 DDL 到 `docs/script/sql/update/<version>.sql`
2. 真库执行（用 `.codex/ipd-dev/config/mysql-client.cnf` 凭证，socket 通道即可）
3. 跑 `check-entity-db-drift.py` 确认无 MISSING
4. 才 commit 代码

DDL 文件强制幂等模板：
```sql
-- information_schema 判存在守卫 + prepare stmt 执行 + 尾部 union 回读校验
```

## 验证

- 跑 `bash scripts/verify.sh`：调用 `check-entity-db-drift.py`，应输出无 MISSING/DRIFT

## 来源

- AGENTS.md §构建/测试："本机真实数据源...应用实走 ...ipd_dev 业务库"
- 项目记忆：`SQL 已 commit ≠ 约束已生效`
- 项目记忆：`entity↔真库 drift-check 门禁脚本用法与假阳性教训`
- 项目记忆：`实体加字段但 DDL 漏迁移致真库查询 Unknown column`（P2-7.4 archived_at、notification_events 第二撞）