# R218-AC续跑 · L3 车道归因报告（KPI/DEL/HAND/REQ/PROD）

- 基线：http://127.0.0.1:16039（fresh jar，未重启/未构建）；真库 ipd_dev 只读校验；fixture 前缀 LANE3-
- 产物：`run_kpi.py / run_kpi_fix.py / run_del.py / run_hand.py / run_req.py / run_req_final.py / run_prod.py`、`执行清单-lane3.json`、`写库清单-lane3.json`、本报告
- 判定分布（定稿）：KPI 77 ／ DEL 24 ／ HAND 19 ／ REQ 21 ／ PROD 16，合计 **157** 条（明细见第六节）

## 一、真缺陷候选（只描述，不立卡）

### D-1【KPI-01】通用 PUT /system-configs 绕过 30% 共担权重下限
- 现象：对功能权重键直接 PUT 写 0.2 成功；`KpiScoreCalculator.validateFunctionalWeight(MIN_SHARED_WEIGHT=0.30)` 只存在于 service 层，grep 全仓 0 个 Controller 调用。
- 证据：执行清单 `AC-KPI-01 / 30% 下限 HTTP 守卫`；写库清单对应 PUT 记录 + 真库 system-configs 值回读。

### D-2【DEL-08】purge 归档清除全库不可用（notLike 对 NULL 三值逻辑）
- 现象：首次 purge 恒 409「清除冲突：并发或已清除」。
- 根因：`DeletionArchiveService.purge` 原子守卫 `.notLike(remark, PURGED_MARK)`——目标行 `remark IS NULL` 时 SQL 三值逻辑不为 TRUE → UPDATE 0 行。
- 铁证：真库全部 DELETED 归档行 remark=NULL；`audit_logs action='DELETE_ARCHIVE_PURGE'` COUNT=0（历史上从未有人 purge 成功过）。

### D-3【DEL 权限包络】越权 403 变 HTTP 500 裸 Spring 错误体（无 code/message/traceId 包络）
- 现象：非超管/非组长调 admin-decision / overdue-admin-review / leader-decision → HTTP 500 非包络体。
- 根因：`DeletionRequestController` 局部 `@ExceptionHandler(NotPermissionException)` 对非 withdraw 方法 rethrow，但此时异常已过 HandlerExceptionResolver 链无法二次解析（sys-error.log：NotPermissionException 自 SaInterceptor.preHandle）。对照 HandoverController 同类越权正常 403。

### D-4【HAND-01d】离职全清后 auto-DISABLED 不可达（守卫态互斥）
- 现象：resign→批量移交全清后，F01 停留 FROZEN_PENDING_HANDOVER；`ACCOUNT_DISABLED_AFTER_HANDOVER` 审计 0 条。
- 根因：`HandoverService.disableIfAllCleared` UPDATE 守卫 `.eq(account_status,"ACTIVE")`，而 resign 后人恒为 FROZEN_PENDING_HANDOVER → 永不命中；PersonService:79 注释明言 DISABLED 由 HandoverService 置入，两处设计互斥。

### D-5【REQ-04/04b】游客撤回/补充无 HTTP 路由（服务层成死码）
- 现象：`GuestDemandService.withdraw()/supplement()/routeDualPm()` 已实现（含 24h 窗口、仅 SUBMITTED 可撤、STATE_CONFLICT 守卫），但 controller 层无任何映射；PUT/POST `/api/v1/public/demands/{code}/withdraw|supplement` 均 404。
- 连带：trace 视图仍下发 withdraw 倒计时字段（游客可见却不可操作）。AC-REQ-09 缺陷卡已另行在册（不重复）。

### D-6【PROD-07/06 连带】在研/在售产品在游客门户不可提需求（状态门口径错位）
- 现象：游客 submit 门为 `!"ACTIVE".equals(product.status)` → 40401「产品已下架」；而 AC-PROD-06 批量导入默认 `ON_SALE`、AC-PROD-07 PM 新增默认 `IN_RD`，两者均 ≠ACTIVE → 卡片承诺的「在研产品可在其下提交需求」实际不可达（实测 40401/429 组合证据见清单）。
- 佐证：本车道 KPI 夹具产品（IN_RD）游客提交同样 40401；需 changeStatus 旁路改 ACTIVE 才能提。

### D-7【PROD-08】「待指派」状态在提交路径不可达
- 现象：productId=null / 无项目 / 项目无在职 PM 三种不路由情形，库内一律 `status=SUBMITTED`；全库 requirements status 枚举无 UNASSIGNED。routeDualPm 注释与验收卡均引用『UNASSIGNED/待指派』态，属口径未落地。

### D-8【REQ-04 观察项】trace 不存在码返回 50001（内部错误码）而非 40401 NOT_FOUND
- 现象：`GET /public/demands/ZZZZZZZZ` → code=50001。防枚举"三态同码"仍成立（两码同值），但用 INTERNAL_ERROR 承载 NOT_FOUND 语义，HTTP 404 + 语义码错位，建议归入 D-5 一并修。

## 二、FAIL-ENV（疑似兄弟车道串扰/环境基线漂移，未重试轰炸）
| 簇 | 记录 | 证据与口径 |
|---|---|---|
| KPI 50002 簇 | 12 条（KPI 评定链 409） | eval_proj(2103689168134647809) 成员行 03:30 前同通道 200/0，03:32 起坍缩为仅陈市场 1 人（刘研发 member 行消失）→ requireSingleMember 拒评；本车道无删除成员的操作在飞 |
| KPI-16 view 空 2 条 | 原 FAIL 改判 FAIL-ENV | 同上游：成员行坍缩 + 陈市场凭证被历史波次改写不可登录 |
| PROD-08 首轮 | 1 条 | 127.0.0.1 游客提交滑窗被本车道前置 12 连发耗尽（429/40011），窗口重置后由 run_req_final 补跑 |

## 三、fixture 假红（已改判并留痕）
- REQ-01/02 首轮：误用 IN_RD 夹具产品（kpi_prod）→ 40401，非缺陷；换 ACTIVE+有项目+双PM在职 的 9130001 重验（见 run_req_final）。
- DEL snapshot 500：`deletion_requests.entity_snapshot` 为 MySQL JSON 列，传非 JSON 字符串必 500（MysqlDataTruncation）——夹具问题，改传 `{"mark":...}` 后全链 PASS。
- HAND-06 onBehalf 400：拒在任人员代办是正确守卫，断言靶选错（T02 在任）→ PARTIAL。
- HAND-05：`/kpi/performance` 仅需 period 参数，早前按 personId 调用报 400 属用法错误 → 复测 200 改 PARTIAL。
- 表/列名勘正：allowance_ledgers、cert_templates(country_code/cert_name)、products 无 name 列（是 product_name）、persons 无 account_id（username 直挂）、无 person_roles 表（person_type 即角色）。

## 四、环境/凭证假红
- 陈市场/刘研发（person-sync 人）登录 400/10001：hash 已被历史波次改写、must_change_pwd=0；`Ipd@123456` 初口令仅对新建设备人生效 → AC-KPI-16 相关 BLOCKED/FAIL-ENV。
- ipd-market/ipd-rd 均满 3 项目绑约（第 4 项目一律拒）→ 部分正向断言靶改用 seed 项目 9140001 系。
- 游客提交限流：127.0.0.1 共享滑窗 10/h（拒绝路径也计数）——**兄弟车道须知**：本车道于 03:47–04:07 UTC 有意消耗两个窗口的提交配额与 trace 配额。

## 五、NOT-RUN（纯 UI/无凭证，诚实记账）
- REQ-07 看板渲染、KPI 1 条（前端聚合展示）、HAND-07 PASS 路径（T02 无登录凭证不可模拟接手）、DEL 若干展示项。

## 六、五域计数（定稿，共 157 条记录）
| 域 | 条数 | PASS | PARTIAL | FAIL | FAIL-ENV | BLOCKED | NOT-RUN |
|---|---|---|---|---|---|---|---|
| KPI | 77 | 48 | 10 | 1 | 14 | 3 | 1 |
| DEL | 24 | 17 | 1 | 6 | 0 | 0 | 0 |
| HAND | 19 | 14 | 4 | 1 | 0 | 0 | 0 |
| REQ | 21 | 10 | 6 | 2 | 1 | 1 | 1 |
| PROD | 16 | 14 | 0 | 1 | 1 | 0 | 0 |
| 合计 | 157 | 103 | 21 | 11 | 16 | 4 | 3 |

- KPI 唯一 FAIL = D-1 真缺陷；DEL 6 FAIL = D-2(purge簇3条)+D-3(500包络2条)+1 展示口径；HAND 1 FAIL = D-4；REQ 2 FAIL = D-5（withdraw/supplement 无路由）；PROD 1 FAIL = D-6/D-7（在研产品不可提需求）。
- REQ 域补跑说明：游客提交限流（127.0.0.1 共享滑窗 10/h，拒绝路径亦计数）导致首轮夹具假红 + 配额耗尽；窗口 04:50 UTC 重置后 `run_req_final.py` 用 seed 产品 9130001(ACTIVE/项目9140001/双PM在职) 完成 REQ-01/03/06/08 主链与 PROD-08 真验证，全部 PASS/PARTIAL 收口。
- 旁证：本轮在库内观察到 lane2 车道游客提交（LANE2-L2T211458-CUST, ADOPTED），证实多车道并发共用 127.0.0.1 限流池——各车道 40011 类失败建议按 FAIL-ENV 处置。

写操作全部走 HTTP 并登记 `写库清单-lane3.json`（108+ 条，LANE3- 前缀），零直接 SQL 写；后端 16039 未重启、未构建、未杀端口。
