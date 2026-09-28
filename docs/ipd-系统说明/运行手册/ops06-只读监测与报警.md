# OPS-06 只读监测与人工报警

2026-09-05，Codex/swarm-94ae。适用本仓库 `.codex/ipd-dev/` 的本机原生实例；依据 [DOC-08](../工程合同/DOC-08.md)、[OPS-01](本机原生开发环境.md)、[OPS-04](ops04-持久调度与业务日历.md) 和当前源码。监测工具已运行，**OPS-06 整卡仍为 PARTIAL**：共享库调度表不可见，审计故障 trace 接线未验证。看板状态由本轮主协调器统一回写。（2026-09-27 R240 轮更新：代码态 trace 接线已落、演练已复跑，见文末「R240 轮证据与接线登记」。）

## 使用与判读

在仓库根执行，无新增依赖：

```bash
python3 -B docs/ipd-系统说明/运行手册/ops06-monitor.py
python3 -B docs/ipd-系统说明/运行手册/ops06-test-monitor.py
```

脚本一次运行输出一份 JSON；可用 `--output /绝对路径/新的文件名.json` 同时保存权限0600的新文件。父目录须已存在；已有文件不会覆盖。JSON 顶层 `exit_code` 与进程退出码一致：

| 退出码 / 状态 | 含义 | 人工动作 |
|---|---|---|
| 0 / HEALTHY | 所有检查均有健康证据 | 本版审计接线尚缺，不会凭当前环境给出此结果 |
| 2 / ALERT | 至少一个依赖DOWN或观察到调度/审计故障 | 当班运维先看对应 `checks`，按下表排查 |
| 3 / PARTIAL | 缺数据、权限、接线或活跃性证据 | 保留原卡阻塞/待验事项；不能当作成功 |
| 3 / UNKNOWN | 输入、配置或输出文件错误，未产生完整报告 | 检查受限配置、目录、文件是否已存在，再用新证据文件复跑 |

`OBSERVED_CLEAR` 只表示本次任务快照未见故障，仍不能证明消费者正在工作。`UNKNOWN` 不等于零故障。每个组件独立捕获错误；数据库失败不会阻止Redis/OSS检查。没有设置后台进程、定时器或外部通知；本工具本身不会向任何人发送消息。

## 探测范围与真实边界

| 检查 | 实际操作 | 成功能证明什么 |
|---|---|---|
| DB | 已有MySQL客户端及 `mysql-app.cnf`；校验0600、`ipd_app`、TCP回环13306；`READ ONLY`事务内SELECT后ROLLBACK | 受限运行账号可以连接指定 `ipd_dev` 并读取；不检验所有业务表/事务 |
| Redis | 复用OPS-01客户端，认证后仅 `PING` | 连接、认证与PING；不验证缓存、锁、队列数据 |
| OSS | 回环19000的health；复用OPS-03签名客户端对OPS-01已存在私有fixture对象做HEAD | 服务存活、基础设施身份可访问该对象；未验应用OSS身份或上传下载业务 |
| backend | 匿名GET `16039/api/v1/auth/me` | HTTP401及业务码20001身份边界；不验证登录后业务 |
| scheduler | 应用账号可见三表检查；可见时聚合FAILED、4次耗尽、过期lease、逾期超过5分钟的RETRY | 当前持久状态。无表和无数据均UNKNOWN；未自动启动业务消费者 |
| audit | 审计成功行数上下文；最多读取私有backend.log尾部1MiB | 只识别现有 `[IPD] 未捕获异常 traceId=` 块中100行内的AuditLogService堆栈/具体审计错误；可输出合法UUID trace及窗口定位，不复制原始异常 |

所有MySQL查询限制5秒，客户端整体限制10秒；Redis沿用10秒超时，HTTP3秒，签名HEAD沿用OPS-03的30秒。读操作也可能与并行写入形成不同时间快照，报告记录UTC采样完成时间；日志另有窗口摘要和读期间变化标记。本工具没有跨数据库、Redis、OSS的原子快照承诺。

审计特别限制：`AuditLogService` 的业务 `ServiceException` 当前不写审计故障日志；`IpdServiceExceptionAdvice.serviceException` 只在响应里给trace。现有兜底异常日志可打印原始异常，本工具不会转发它，也不宣称整个应用日志已脱敏。本版即使21条审计记录且日志未见失败，也输出 `AUDIT_TRACE_NOT_WIRED`。日志中的trace最多是 `LOG_ONLY`，不能冒充已证明与客户端响应一致。日志轮换历史、审计hash链、失败追加的端到端验证均未完成。

## 可执行人工报警路径

1. 运行监测器并保留新JSON。出现2或3时，由当前任务执行者将**组件、固定错误码、时间、证据相对路径**交给本机看板主协调器记录；不粘贴私有配置、原日志或原异常。待认领的运维责任人由owner分派，本工具不虚构接收者、不自动发消息。
2. 按以下最小读步骤区分故障。解决配置、权限、迁移或业务接线通常需要对应卡的允许路径和授权，本监测器不会代做。
3. 完成修复后复跑本脚本，比较同一组件前后结果。故障消失但接线仍UNKNOWN时维持PARTIAL。保留首次故障报告；不用后续报告覆盖。

| 信号 | 只读排查与后续责任 |
|---|---|
| DB DOWN / MYSQL_1045 / MYSQL_1142 | 确认本机实例和受限应用账号配置；只看错误码，不换root通过探测。账号/授权问题交SEC-03；连接问题交OPS-01 |
| Redis DOWN或UNKNOWN | 确认本机16379和私有凭据文件权限；仅复跑PING。不得执行FLUSH/SET、清库或自动重启 |
| OSS live失败或HEAD失败 | 区分health状态与签名HEAD状态；核对OPS-01 fixture是否存在、基础设施身份是否仍有效。不得新建对象掩盖原对象丢失；应用附件权限另属P1-4.2 |
| SCHEDULER_SCHEMA_NOT_VISIBLE_OR_MISSING | 应用账号仅能证明“不可见”，不能自行区分缺表与缺授权。由OPS-04/SEC-03对获准目标核对迁移和权限；不会自动建三表或开放权限 |
| FAILED/耗尽/lease过期/RETRY逾期 | 用下述查询读取任务引用、次数、状态及受限错误码。耗尽不清零attempt，不改成PENDING；由业务owner核对是否已产生业务效果，再决定新版本事件或恢复方案 |
| AUDIT_FAILURE_OBSERVED | 用报告 `trace_id`、`window_start_byte`、`line_in_window` 在本机私有日志定位。原始日志只由获准运维本机检查；不要复制到看板。调用链/响应trace一致性由API-01与审计责任卡补验 |
| AUDIT_TRACE_NOT_WIRED | 不能通过等待更多审计行解除；需要部署经验证的审计失败事件与响应/日志关联，再在隔离环境注入审计故障验证。当前脚本不会自动将其改为健康 |

在三表已对运行账号可见后，可从仓库根只读查看最多20条调度问题记录。命令沿用本项目客户端和0600配置；不输出payload、原始event key或任意错误文字：

```bash
.codex/ipd-dev/software/mysql-8.0.46-macos15-arm64/bin/mysql \
  --defaults-file="$PWD/.codex/ipd-dev/config/mysql-app.cnf" \
  --connect-timeout=3 --batch ipd_dev <<'SQL'
SET SESSION time_zone='+08:00';
SET SESSION max_execution_time=5000;
START TRANSACTION READ ONLY;
SELECT SHA2(event_key,256) AS event_ref_sha256, state, attempts,
       CASE WHEN last_error_code IN ('HANDLER_FAILURE','INFRASTRUCTURE_FAILURE',
          'HANDLER_NOT_REGISTERED','CALENDAR_NOT_LOADED','LEASE_EXPIRED')
          THEN last_error_code ELSE 'OTHER_OR_EMPTY' END AS safe_error_code,
       updated_at
FROM ipd_scheduled_tasks
WHERE state='FAILED' OR (state='RUNNING' AND lease_until<NOW())
   OR (state='RETRY' AND next_attempt_at<DATE_SUB(NOW(),INTERVAL 5 MINUTE))
ORDER BY updated_at DESC LIMIT 20;
ROLLBACK;
SQL
```

上述调度详情命令当前因三表不可见尚未在 `ipd_dev` 实跑，不把它计入本轮真库通过。对原始event key的业务回溯须由授权业务owner在本机处理。

## 本轮证据

证据目录：`.codex/ruflo/swarm-94ae/ops/`。

- `execution.json`：实际命令、退出码和stdout/stderr摘要。`tests-stderr.log`：22测试，0失败0跳过；`live-stdout.log`与 `live-readonly.json`：真实只读结果，exit3 / PARTIAL。
- 真实结果：DB app账号UP；Redis PONG；OSS health及既有对象HEAD均200；backend匿名401/20001；调度可见0/3；审计21条、扫描130588字节，识别到0条故障，但状态UNKNOWN。
- 故障验证是**隔离loopback HTTP实例和确定性输入fixture**：连接拒绝、503、403、阻止重定向、超时、单组件失败不阻断其他组件；调度耗尽/永久失败/过期租约/无任务；审计trace、恶意trace去敏、跨日志事件不串trace、尾读上限。调度耗尽并未在共享数据库制造真实失败，审计失败也未在16039制造；不写DB/Redis/OSS、不停共享服务。
- `manifest.json`：本轮新增交付物和证据摘要、状态边界、已知凭据非泄漏检查。历史首次测试中HTTPError资源释放警告已修复，原 `tests-initial.log` 保留；最终22测试无该警告。

恢复方式是停止运行此一次性监测命令，不涉及数据库回滚或共享服务控制。源文件仅新增运行手册脚本/测试/本文；没有修改Java、共享配置、Maven target、账号、生产环境或安装定时器。

## R240 轮证据与接线登记（2026-09-27，marker r240-land-ops6）

**代码态审计 trace 接线（四断点同批落码，待部署）**：①`AuditLog` 增 `trace_id` 列 + 迁移 `docs/script/sql/update/2026-09-27-ipd-audit-trace-id.sql`，**不入哈希链 canonical**（字段清单冻结，加列不触链、无需 rebuildChain）；迁移已实跑留证（17→18 列，历史 6341 行零触碰，证据 `ops06-trace-id-migration-20260927.txt`，`ipd_migrator` 账号执行）。②`AuditLogServiceImpl.append` 抓 MDC `traceId`（尊重调用方预置，X-Trace-Id 兜底；不入 canonical）并在追加失败时发射 `[IPD] 未捕获异常 traceId=… 审计追加失败` 事件。③`IpdServiceExceptionAdvice` 14 个 handler 复用 TraceIdFilter 铸就的 MDC trace（`beginTrace()/endTrace(owned)`，不再每 handler 另铸 UUID）。④`handleUnexpected` 按本监测器契约词形发射 marker。`AuditTraceWiringTest`（`@Tag("dev")`，6 用例）含 runbook 解除条件的 Java 侧证明——**隔离环境注入审计故障**（selectForUpdate→null 确定性注入，断言 marker+trace+`AuditLogService` 堆栈帧）与哈希协议红线（仅 traceId 不同的两行 curr_hash 必须相等）；ruoyi-ipd 批跑 54/54 全绿。

**运行手册依赖复原（如实登记，防误读）**：`native_env.py` 自 `.codex/ipd-integration/20260905-1230-shared` 快照回泊入库（此前从未入库，监测器 ModuleNotFoundError）；`ops03_backup.py` **原件全盘不存在**（历轮已挂账），按 ops06-monitor.py 实际调用面最小复原（NoRedirect 阻断 3xx + SigV4 签名 HEAD），**非原件、不代备份职责**；`.codex/ipd-dev/config/*.cnf` 与 `credentials.json` 权限逸出 644 已复位 0600。

**演练实录（2026-09-27，证据 `ops06-drill-20260927.txt`）**：fixture 22/22 全绿（含 redirect 阻断判据：3xx 抛 RuntimeError 落 PROBE_ERROR，服务端只见首跳）。live 只读监测 `live-readonly-20260927-r2.json`：db/redis/oss/backend 全 UP，oss `SIGNED_HEAD_OK`。**OSS 403 根因与处置**：9-25 R218 轮遗留 TCP 转发器（`/tmp/r218-tcpfwd.py`，19000→9000 冒名 MinIO）+ 原生 MinIO 进程已死，签名被冒名端点拒 `InvalidAccessKeyId`（非签名算法问题）；处置=停该孤儿转发器（R218 为已收口轮次，非兄弟在途）+ 按 native_env 同款 launch 复起 MinIO（credentials.json 身份，数据目录 fixture 完好），HEAD fixture 200 / GET 不存在 key NoSuchKey 证实签名链路有效。监测器 `O_EXCL` 拒覆盖旧证据文件（exit3 `MONITOR_INPUT_OR_OUTPUT_ERROR`）属设计行为，换新证据文件名复跑即出报告。

**告警路径登记（按「可执行人工报警路径」，两条如实维持，不得表述为完成）**：

1. `SCHEDULER_SCHEMA_NOT_VISIBLE_OR_MISSING`（visible 0/3）→ **BLOCKED_DEPENDENCY**：由 OPS-04/SEC-03 对获准目标核对迁移与授权（本工具不建表不授权）；真实调度故障/耗尽报警演练同待该授权后进行。
2. `AUDIT_TRACE_NOT_WIRED`（audit UNKNOWN，record_count=6345，窗口扫描 0 故障事件）→ **BLOCKED 部署态**：16039 共享实例仍运行旧 jar（兄弟会话在用，不做服务控制）。解除条件中「经验证的审计失败事件」与「隔离环境注入审计故障验证」已代码态完成（上文 54/54），余「部署」一项待新 jar 发布后复跑 live 监测确认，届时以 `AUDIT_FAILURE_OBSERVED` 可定位 trace 为准。
