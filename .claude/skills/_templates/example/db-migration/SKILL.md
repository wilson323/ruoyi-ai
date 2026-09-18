---
name: db-migration
description: 为 ruoyi-ai 数据库变更提供标准化流程。封装「新增业务表 / 加字段 / 加索引 / 新增 snailjob 任务 / 跨租户共享表登记」的核心入口与按需披露。Agent 接到"加表/改字段/加索引"任务时先扫本入口，再按踩坑形态打开 references/，跑前必须 scripts/verify.sh 自检。
disable-model-invocation: true
---

# db-migration

RuoYi-AI 数据库变更标准化技能（DisCo 形态示范）。

## 何时用

- 新增业务表（必须新建 entity + mapper + SQL）
- 给已有表加字段 / 加索引 / 改类型
- 新增 / 修改 SnailJob 分布式定时任务
- 跨租户共享表变更
- 涉及 `tenant.excludes` / `demo.excludes` 配置变更

## 必读规约（按踩坑形态打开 references/）

- DDL apply 验证链路 → `references/ddl-apply-rules.md`
- SQL 已 commit ≠ 约束已生效 → `references/ddl-apply-rules.md` §踩坑形态 1
- 实体加字段但 DDL 漏迁移 → `references/ddl-apply-rules.md` §踩坑形态 2

## 跑前自检（必跑）

```bash
bash .claude/skills/db-migration/scripts/verify.sh
```

通过才能 commit DDL。脚本会跑 `python3 docs/script/sql/check-entity-db-drift.py` 对比 entity ↔ 真库字段，扫出 Unknown column 风险。

## 输出交付物

1. 完整文件清单（DDL、Entity、Mapper、Service、Controller、配置）
2. DDL 脚本 + 回滚脚本
3. 必做检查清单的自检结果
4. 影响范围报告（哪些模块、哪些接口、哪些前端）
5. 灰度建议（如适用）

## 禁止清单（精简）

- ❌ Entity 不 `extends BaseEntity`
- ❌ 用 `AUTO_INCREMENT` 主键（必须雪花）
- ❌ 删表 / 删字段不写回滚脚本
- ❌ 改 `tenant.excludes` 不通知用户（P0 事故级）
- ❌ 直接在生产数据库 `ALTER TABLE`（必须走评审 + 备份）