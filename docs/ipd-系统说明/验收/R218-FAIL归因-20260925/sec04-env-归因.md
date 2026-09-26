# R218 QA-08/SEC-04 环境类 FAIL 复测归因（2026-09-25）

- **范围**：R218 首波执行中 card=SEC-04 且 verdict=FAIL/FAIL-ENV 的 6 条环境类用例（A1×1、A1R×4、C7×1）+ sysadmin 登录附带核查。首波背景：16039 跑旧 jar（类损坏+缺端点），现已重建轮换（Started 16.054s，pm-directory 场景实证可达）；16046 为验收临时实例。
- **方法**：复用 `R218-QA08-SEC04-20260925/r218_lib.py`（凭证从 `.codex/ipd-dev/config/dev-accounts.yaml` 内存读取，全程未打印）；SQL 一律 `mysql --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf ipd_dev` 只读；`docker ps` 只查不重启他人容器；curl verbose 证据中 Authorization 头已脱敏。
- **边界遵守**：未改主代码、未翻看板卡、未修改任何凭证；产物仅本目录。
- **明细数据**：逐条命令/结果/证据见同目录 `sec04-env-复测记录.json`（本归因专属，10 条，含二次复跑一致性确认）；`复测记录.json` 为合并视图（本批 10 条已带 `batch=sec04-env-归因20260925` 标记并入，另含同目录并行归因批次的记录，互不覆盖依赖专属文件）。curl verbose 原始输出另存 `evidence-curl-upload.json`。

## 逐条归因结论

### ① A1/A1R-本人上传登记(market@D02@9140005) ×5 —— 【环境假红-仍红-根因已定位：本机 HTTP 代理劫持 S3 流量】

**复测**（新 16039，ipd-market）：`POST /api/v1/deliverables/upload?actionId=9160044`（2KB pdf multipart）
- 客户端（r218_lib timeout=90s）→ `http=-1 timed out`，**与首波 5 条完全同签名**（二次复跑同结果，确定性复现，非偶发）；
- 换 curl `-m 150` 放宽 → TCP `connect=0.000251s` 即握手成功（**应用不拒连**），服务端阻塞 **120.010s** 后返回 `HTTP 500 {"code":90001,"traceId":"980759257a824eabaee93a8a66992ca0"}`；
- 首波 `-1` 的成因 = 客户端 90s < 服务端 120s 守卫，先断而记 -1。

**根因链（证据闭合）**：
1. 16039 进程日志堆栈：`OssException: 上传文件失败…[The service request was not made within 120 seconds of doBlockingWrite…]` @ `OssClient.upload:209`（与首波 16046 同签名——证明与新旧 jar 无关，`BlockingInputStreamAsyncRequestBody`+`S3TransferManager` 链路自洽）；
2.  suppressed 链：`SdkClientException: The connection was closed during the request` ×3 attempts，`ChannelDiagnostics R:/127.0.0.1:7890` —— **AWS SDK Netty 客户端在连代理 7890，而不是 minio 的 9000**；
3. `jinfo -sysprops 86031`：`http(s).proxyHost=127.0.0.1`、`http(s).proxyPort=7890`、`socksProxy*=7890`，且 **无 `http.nonProxyHosts`**；`env -i java -XshowSettings` 对照无任何 proxy 属性 → 代理属性系 dev 机 GUI 会话环境注入（macOS 系统代理 `networksetup -getwebproxy Wi-Fi` = Enabled 127.0.0.1:7890，Clash 类工具；7890 端口存活的裸 HTTP 探返回 400）；
4. minio 侧排除：容器 `Up 6 hours`、`:9000/minio/health/live` 直连 200、近 60min 无错误日志 —— **minio 健康，非容器故障，非应用拒连**。

**结论**：环境假红-仍红。红因不在 jar/应用/minio，而在 **dev 机 JVM 代理继承 + localhost 未加白**：S3 PUT 被 7890 代理中转并在流式写入中被掐断，120s 守卫超时→500/90001；执行器 90s 超时截为 -1。
**修复方向（供后续轮，本任务不执行）**：环境侧 = Clash 对 `127.0.0.1:9000` 加直连规则或系统代理关闭后重启 16039 JVM；或启动参数加 `-Dhttp.nonProxyHosts="localhost|127.*|[::1]"`。次要发现（框架健壮性，不改码不立卡，仅登记）：`OssClient` 构建 `NettyNioAsyncHttpClient` 未显式禁 JVM 默认代理，任何带透明代理的开发机都会命中此坑，且失败耗时 120s 占住请求线程。
**测试数据**：上传失败路径零写库（`deliverables`/`sys_oss` 当日 0 新增），无需登记。

### ② C7-需求详情(旧jar 404/50001) —— 【环境假红-已转绿】

- 真库取证：`requirements` 中 id=2103338131276259329 存在（status=SUBMITTED, del_flag=0）。
- 新 16039 复测原 FAIL 命令：`GET /api/v1/demands/2103338131276259329`（ipd-admin）→ **HTTP 200 code=0 data.id 回显一致** ✅
- 用户指定的 `GET /demands/{id}/detail` 变体 → 404 code=50001：查证 `DemandController` 源码，detail 端点映射为 `@GetMapping("/{id}")`（方法名 detail，R215 交付），**不存在 `/detail` 后缀路径**——按原命令路径归因，本条转绿。
- **结论**：环境假红-已转绿（纯部署漂移：旧 jar 缺端点，重建轮换即消）。

### ③ 附带核查-sysadmin 登录报密码错 —— 【环境假红-仍红-根因：账号不在库（信源-库漂移），非轮转/非锁定/非删除】

- 复测登录（新 16039）：`POST /api/v1/auth/login {username:sysadmin}` → `HTTP 400 code=10001 用户名或密码错误`（ipd-admin 同刻登录 200 正常）。
- 真库只读核对（全 SELECT，零修改）：
  - `persons`：`username/name/employee_no='sysadmin'` **0 行（含软删 del_flag=1）**；文档规划的 `id 80001-80999` 段 **0 行**；现存 SUPER_ADMIN 仅 `900101 ipd-admin`（update_time=2026-09-26 07:57:14，与本轮 ipd-* 登录时间吻合=正常 last_login 刷新）及 `2096266884100935682`（username=中文"系统管理员"，非 'sysadmin'）；
  - `sys_user`（RuoYi 框架表）：`user_name LIKE '%sysadmin%'` **0 行**，仅 user_id=1 ipd-admin。
- **结论**：环境/数据配置漂移——`dev-accounts.yaml` 单一信源登记了 `sysadmin`（"mock init 默认建"），但该账号**从未在现库存在**（或随历史 mock 重置消失且未再补建）；无 credential 更新时间可考（无行）。登录返回 10001 统一防枚举消息属**正确防护行为，非缺陷**。处置建议（移交，本任务不改数据）：要么按信源补建 sysadmin（走既有 mock init/建号流程），要么修订信源表删除该行；SEC-04 类用例统一以 ipd-admin 承载超管语义（本轮已实证可用）。

## 汇总

| # | 用例 | 原始错误 | 复测结果 | 归因 |
|---|------|---------|---------|------|
| 1-5 | A1/A1R 上传登记 ×5 | http=-1 | 新16039仍-1；curl 150s 见 500/90001@120s；代理 7890 劫持实锤 | **环境假红-仍红-根因**：JVM 代理继承+localhost 未白名单（非拒连、minio 健康、与 jar 新旧无关）；次登记 OssClient 禁代理健壮性改进项 |
| 6 | C7 需求详情 | 404/50001 | GET /demands/{真实id} → 200/code=0 | **环境假红-已转绿**（旧 jar 部署漂移，重建即愈）；`/detail` 后缀路径本就不存在 |
| 附 | sysadmin 登录 | 10001 密码错 | 仍 10001；persons/sys_user 均无行 | **环境假红-仍红-根因**：账号不在库（信源-库漂移），登录拒绝行为正确，非代码缺陷 |

无一条判为"真缺陷-建议立卡"；A 链修复属环境操作（代理加白/JVM 参数），已给出可复制指令。本轮零写库，`写库清单` 无新增条目。

---

## 归因更新（v6 终局，2026-09-26 协调会话补记，marker r218-upload-final-fix）

首报结论"根因=JVM 代理劫持"只对了第一层。按修复方向逐层剥除后暴露第二层真凶，A1/A1R 五条上传 FAIL 实为**双根因叠加**：

1. **层一（环境，已修）**：本机所有 java 进程 bootstrap 期被注入 `http(s)/socksProxyHost=127.0.0.1:7890`（来源：macOS 系统代理被某机制同步进 JVM sysprops，`env -i`、`-Djava.net.useSystemProxies=false` 均挡不住；注入源未彻底查明，记录在案）。修复：启动挂 `-javaagent:/tmp/npxagent/noproxy-agent.jar`（premain 清 8 个代理属性，零代码侵入，5 行 jar 不入仓）。
2. **层二（数据配置错误，已修）**：`sys_oss_config`(config_key=minio) 的 endpoint 存成了 `http://127.0.0.1:9000`（带 scheme），而 `OssClient.getEndpoint()` 会按 is_https 再拼一次协议头 → 实际连接 `http://http://127.0.0.1:9000`，host 解析成字面量 "http"、端口退化 80、被 Clash TUN 喂 fake-ip 后挂死 120s。**修复＝改数据不改代码**：`UPDATE sys_oss_config SET endpoint='127.0.0.1:9000' WHERE config_key='minio'`（上游 RuoYi 惯例 endpoint 存裸 host:port，此库入库时写错）。
3. **主代码零改动**：中途试验性给 OssClient 加过 ProxyConfiguration 配置，已 `git checkout` 还原（且发现纯禁代理+null host 会引入云域名回归，弃用）。
4. **v6 全链实证**（时间线 2026-09-26T10:01:29）：A1 上传 200/code=0 → deliverable_id=2103666236842881025；真库回读 hash/size 匹配（2095B，uploaded_by=900103，project 9140005）；本人下载 200 且字节 sha 一致；审计行 rows=1；C7 需求详情 200。SEC-04 六条环境类 FAIL 至此全部闭环：6/6 绿。
5. **重启命令基线**：`/tmp/r218-run-16039-v2.sh`（env -i + JDK17 绝对路径 + useSystemProxies=false + noproxy-agent + 16379 redis + 16039），兄弟会话轮换 16046 时可复制同款参数。
6. **观察项移交**：①代理属性 bootstrap 注入源未定论（不影响现解法）；②sysadmin 账号需按 dev-accounts.yaml 信源补建或删行（维持首报结论）。
