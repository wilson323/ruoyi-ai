# SQL 脚本说明

## 用途边界（先读这一段，避免装出一个「能启动但没有业务表」的库）

本目录的 `ruoyi-ai.sql` 是 **上游 RuoYi-AI 基线 dump**，不含任何 IPD 业务表。2026-10-03 实测：

| 口径 | 数值 | 复现命令（在 `docs/script/sql/` 下执行） |
| --- | --- | --- |
| `ruoyi-ai.sql` 建表数 | 69 张唯一表（92 条建表语句） | `grep -oiE 'CREATE TABLE (IF NOT EXISTS )?[`a-zA-Z0-9_-]+' ruoyi-ai.sql \| awk '{print $NF}' \| tr -d '\`' \| sort -u \| wc -l` → 69 |
| IPD 业务表 | **0 张** | `for t in projects persons gates requirements handover_records kpi_records audit_logs product_groups; do grep -c "$t" ruoyi-ai.sql; done` → 全部为 0 |
| IPD 真实 schema 基线 | 166 张表 | `grep -c 'CREATE TABLE' ../../ipd-系统说明/schema-baseline-20261003.sql` → 166 |

IPD 的建表 DDL 分散在 [update/](update/) 目录的 139 个脚本里，**没有**合并进 `ruoyi-ai.sql`。

两条通道因此用途不同，别混用：

- **上游 compose 通道**（[docs/docker/ruoyi-ai/docker-compose-all.yaml](../../docker/ruoyi-ai/docker-compose-all.yaml) 与 [Dockerfile.mysql](../../docker/ruoyi-ai/Dockerfile.mysql)）：只用于跑 RuoYi-AI 基线功能。用这条通道建的库**没有 IPD 业务表**，后端能正常启动、启动日志无异常，但每个 IPD 接口都会因 `Table doesn't exist` 报错。
- **IPD 生产通道**（仓库根 [docker-compose.yml](../../../docker-compose.yml)，外接托管 MySQL）：建库走 [生产部署 Runbook](../../ipd-系统说明/治理/生产部署Runbook-20260909.md) 的人工流程，schema 以 `docs/ipd-系统说明/schema-baseline-20261003.sql` 为准。

## 全新安装（仅上游 RuoYi-AI 基线）

执行 [ruoyi-ai.sql](ruoyi-ai.sql) 创建 `ruoyi-ai` 主库和 `snail_job` 调度库，并导入初始化数据。

> 注意：这一步得到的是**基线库**，不含 IPD 业务表（见上一节）。需要 IPD 业务表时不要走这条路径。

```sh
mysql -uroot -p < ruoyi-ai.sql
```

主 SQL 已合并下表中的全部更新，但 [update/kb-partA-ddl-draft-20260928.sql](update/kb-partA-ddl-draft-20260928.sql) 除外：该切片只在开发库 `ipd_dev` 上 apply，尚未回写进主 SQL dump，回写前不得视为已合并。因此执行完主 SQL 后，必须再执行该增量脚本（顺序为先基线、后增量）。[snail_job_mysql.sql](snail_job_mysql.sql) 仅用于单独初始化调度库，执行主 SQL 后无需再执行它。

全量初始化包含 `DROP TABLE` 和调度库的 `DROP DATABASE`，已有数据库升级应使用对应的增量脚本。

## 已有数据库升级

连接目标主库后，按文件名升序执行尚未应用的更新脚本；不要根据文件重命名重复执行已应用的更新。

- `2026-06-15-trace.sql` 原名为 `update-0615-trace.sql`，内容未变；其中菜单插入没有判重，不应重复执行。
- `2026-08-30-mcp-market-tool-tenant.sql` 是一次性迁移，执行前检查脚本内的预检要求；部分执行失败后应按实际表结构续跑。
- 其余脚本包含重复执行保护，具体适用条件见各脚本注释。

## 命名约定

更新脚本统一采用 `YYYY-MM-DD-功能描述.sql`，功能描述使用小写英文和连字符，例如 `2026-09-01-sys-url.sql`。保留已有更新日期，同日脚本按文件名升序执行，并确保依赖顺序一致。

全量入口保留 `ruoyi-ai.sql`，独立调度库入口保留 `snail_job_mysql.sql`。

## 主 SQL 合并清单

截至 2026-09-01，`update` 目录的 9 个脚本均已体现在主 SQL 的最终表结构和初始化数据中。结构变更直接并入建表语句，重复配置按最终值保留一份；旧库回填及兼容逻辑保留在增量脚本中。

| 更新脚本 | 主 SQL 中对应内容 |
| --- | --- |
| [2026-06-15-trace.sql](update/2026-06-15-trace.sql) | `trace_run`、`trace_node` 表、租户字段和索引，以及链路追踪菜单 |
| [2026-07-20-knowledge-fragment-fid.sql](update/2026-07-20-knowledge-fragment-fid.sql) | 附件 `file_hash`、片段 `fid`、可空的 `doc_id` 和知识库租户索引 |
| [2026-07-21-chat-provider-icon-length.sql](update/2026-07-21-chat-provider-icon-length.sql) | `chat_provider.provider_icon` 扩展为 `varchar(1000)` |
| [2026-07-21-sys-config-node-template.sql](update/2026-07-21-sys-config-node-template.sql) | 7 个工作流节点消息模板配置 |
| [2026-07-24-register-default-role-and-missing-menus.sql](update/2026-07-24-register-default-role-and-missing-menus.sql) | 默认注册角色、角色菜单关联、默认角色配置，以及会话、附件和片段权限 |
| [2026-07-29-sys-config-node-template-all.sql](update/2026-07-29-sys-config-node-template-all.sql) | 工作流全部 8 个模板，前 7 个配置与上一批模板合并去重 |
| [2026-07-29-zhipu-web-search-node.sql](update/2026-07-29-zhipu-web-search-node.sql) | 智谱网络搜索节点及其消息模板；内部组件名保留 `Google` 以兼容已有流程 |
| [2026-08-30-mcp-market-tool-tenant.sql](update/2026-08-30-mcp-market-tool-tenant.sql) | `mcp_market_tool` 租户、审计字段和 `idx_tenant_market` 索引 |
| [2026-09-01-sys-url.sql](update/2026-09-01-sys-url.sql) | `sys_url` 表、5 个菜单权限和 2 条公开链接 |

后续增加更新脚本时，同步修改主 SQL 中对应的表结构或初始化数据，并更新本清单及主 SQL 文件头。
