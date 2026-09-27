# OPS-06 只读监测与人工报警

2026-09-05，Codex/swarm-94ae。适用本仓库 `.codex/ipd-dev/` 的本机原生实例；依据 [DOC-08](../工程合同/DOC-08.md)、[OPS-01](本机原生开发环境.md)、[OPS-04](ops04-持久调度与业务日历.md) 和当前源码。监测工具已运行，**OPS-06 整卡仍为 PARTIAL**：共享库调度表不可见，审计故障 trace 接线未验证。看板状态由本轮主协调器统一回写。

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
