---
name: ipd-guard-ddl-apply
description: ruoyi-ai 仓任何 DDL 变更（加表/加字段/加索引）的"SQL 已 commit ≠ 约束已生效"防御。封装 entity↔真库 drift-check 门禁脚本用法、假阳性识别、新迁移文件幂等模板，避免 Unknown column 500 与假绿测试掩盖 DDL 漏迁移。
---

# ipd-guard-ddl-apply

DDL 变更的事前 / 事中 / 事后门禁。

## 何时使用

- 新增业务表（已有 `db-migration` skill 处理 DDL 模板，本技能负责事后验证）
- 给已有表加字段 / 加索引 / 改类型
- 修改已有表结构（rename / change column）
- 验收任何"声称已 apply 的 DDL"
- 排查 "Unknown column 'xxx'" 类 500 报错
- 收尾卡 done 判定（含"DDL 已 apply"声明的卡）

## 三道门禁

### 门禁 1：commit 前——entity↔DDL 一致性自检

写完 entity + DDL 文件后，跑一次脚本确认字段无遗漏：

```bash
# 列出当前 entity 期望字段 vs DDL 提交字段
diff \
  <(grep -E "^\s*@TableField" ruoyi-modules/<m>/src/main/java/.../<Entity>.java \
    | sed -E 's/.*"([^"]+)".*/\1/' | sort -u) \
  <(grep -E "ADD COLUMN|^\s*\w+\s+(BIGINT|INT|VARCHAR|DATETIME|TEXT|JSON|CHAR)" \
    docs/sql/migration/V*.sql | awk '{print $1}' | sort -u)
```

期望：diff 输出为空。任一字段不一致即失败。

### 门禁 2：commit 后——真库 drift 强制对齐

仓库无 Flyway / Liquibase，"SQL 已 commit ≠ 对象已生效"是系统性积压。**每次声明 DDL 已 apply 必须用脚本验**：

```bash
# docker 通道（推荐，先起 docker container）
python3 docs/script/sql/check-entity-db-drift.py \
  --docker ruoyi-ai-mysql \
  --db ipd_dev

# 或 socket 通道（本地原生 mysqld @ 13306）
python3 docs/script/sql/check-entity-db-drift.py \
  --cnf .codex/ipd-dev/config/mysql-client.cnf \
  --db ipd_dev
```

exit 0 即 entity↔真库列对齐；非 0 即真库缺列 / 缺表。

### 门禁 3：排查时——Unknown column 直接跑 drift-check

线上 / 测试 500 报 `Unknown column 'xxx'` 时，**第一动作**就是跑门禁 2 的脚本定位：

```bash
python3 docs/script/sql/check-entity-db-drift.py \
  --docker ruoyi-ai-mysql --db ipd_dev 2>&1 \
  | grep -A3 "xxx"
```

不是去翻 entity，不是去翻 DDL，是去翻真库。

## 三类假阳性（手写实体扫描器易踩）

如果用 grep 自写扫描器替代上面的官方脚本，**至少**要处理：

1. **`@TableField("col_name")` 显式下划线映射**：实体 `dimension_1` 字段会因驼峰转换被错认为 `dimension1`。要识别 `@TableField` 注解里写的真列名。
2. **`@TableField(exist = false)` 非持久字段**：`BidResponse.decision` 这类仅用于 VO 返回的字段不在真库。要排除 `exist=false` 的字段。
3. **跨上下文引号嵌套**：docker exec 里跑 SQL 含 `CONCAT('...')` 时，外层 `sh -c "..."` 与内层 SQL 引号冲突，需 `sh -c '...'` 包内层双引号。

直接用 `check-entity-db-drift.py` 不用操心上面三条。

## 新迁移文件强制幂等模板

迁移 SQL 必须可重跑（多会话共工时兄弟会话可能已经 apply 过部分）：

```sql
-- 模板：information_schema 判存在 + prepare stmt + 尾部 union 回读校验
SET @col_exists := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ipd_dev'
      AND TABLE_NAME = '<table>'
      AND COLUMN_NAME = '<column>'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE <table> ADD COLUMN <column> <type> COMMENT "<comment>"',
    'SELECT "<column> already exists" AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 校验：尾部 union 回读
SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'ipd_dev' AND TABLE_NAME = '<table>'
UNION ALL SELECT '<column>', '<type>', '<comment>'
WHERE NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = 'ipd_dev' AND TABLE_NAME = '<table>'
      AND COLUMN_NAME = '<column>'
);
```

参考实现：`docs/script/sql/update/2026-09-07-ipd-drift-backfill-entity-gap.sql`。

## 多会话共工的分钟级过期

DB 快照分钟级过期。已实测：盘点间隙兄弟会话可能 apply 掉 10 张表（132→138）。

**强制**动手前重跑门禁 2，**禁止**基于分钟前快照写迁移。两次跑脚本之间的间隔 > 5 分钟就要重跑。

## 必做检查清单

DDL 变更交付前自检：

- [ ] 已写 DDL 文件并 commit
- [ ] 已更新对应 Entity（含 `@TableField`）
- [ ] 已 apply 到真库（不能用"我执行了"代替脚本验证）
- [ ] 门禁 2 脚本 exit 0（drift-check 通过）
- [ ] 新加表已登记 `tenant.excludes`（如选 shared 策略）
- [ ] 新加 controller 写操作已登记 `demo.excludes`
- [ ] 回滚 SQL 已写（即使是加字段也得有）
- [ ] 新表表级 GRANT 已设（避免 ipd_dev 库漏 GRANT 致 UPDATE 拒绝塌缩 500）

## 失败归因

| 现象 | 真因 | 归因分类 | 修法 |
|---|---|---|---|
| 真库缺列 / 缺表 | DDL 未 apply | 知识错（违反门禁 2） | 重跑迁移 + 重验 |
| drift-check 假阳性报缺 | 用了未处理 @TableField 的手写扫描器 | 检查错 | 改用官方 `check-entity-db-drift.py` |
| 多会话共工时刚验完又报缺 | 兄弟会话期间 drop / 修改了表 | 环境错 | 重跑门禁 2，定位冲突会话 |
| 加列后 UPDATE 报权限拒绝 | 新表漏表级 GRANT | 知识错 | 补 GRANT，跑门禁 2 复核 |
| Mock 单测全绿但真库 500 | 单测未连真库 | 知识错（违反 mock-validity） | 改用 @SpringBootTest 连 ipd_dev |

## 禁止清单

- ❌ "DDL 文件已 commit" 代替 "真库已 apply"——commit ≠ 生效
- ❌ 用 grep 手写扫描器代替官方 drift-check 脚本——已知三类假阳性
- ❌ 写不可重跑的 DDL（缺 information_schema 判存在守卫）
- ❌ 加新表不写回滚 SQL
- ❌ 基于"几分钟前的 drift-check 结果"做新决策——必须 fresh 重跑
- ❌ 把"加表 / 加字段"写在生产数据库但不通知 owner（事故级别 P0）

## 版本指纹

- 验证时 mysql 版本：8.0.46 @ 127.0.0.1:13306（socket 通道可达同一实例）
- 验证时 ipd_dev 表数：约 138 张（验证时现查）
- 验证时脚本：`docs/script/sql/check-entity-db-drift.py`（commit 现查）
- 最近验证：2026-09-11（首版蒸馏）
- 过期触发：mysql 升级 / 引入 Flyway / 切真库 / 表数显著变化