# R218 QA-08 业务域 12 条 FAIL 复测归因（2026-09-25）

- **执行环境**：B39 = http://127.0.0.1:16039（含全部已入库代码的新 jar）；凭证读 `.codex/ipd-dev/config/dev-accounts.yaml`（仅内存使用，全程零打印）
- **纪律**：0 改主代码、0 改测试代码、0 翻看板卡、0 改写 `执行清单-r218.json`；复测新增数据登记于本目录 `写库清单-归因复测.json`
- **工具**：`r218_fail12_retest.py` + `r218_fail12_retest_chain.py`（复用 R218-QA08 执行器 `r218_lib.py`）；原始记录 20 条见 `复测记录.json`
- **总结论**：**12 条 = 10 条假红（均归「数据演进/用例设计缺陷-用例需修 fixture」族，其中 8 条本轮复测直接转绿）+ 2 条真缺陷（AC-REQ-09、AC-PROD-09，建议立卡）**

## 结论总表

| # | 原始FAIL | 原始错误 | 复测结果 | 结论（三选一） | 初判校对 |
|---|---------|---------|---------|--------------|---------|
| 1 | AC-PROD-03 创建SOFTWARE项目 | 400 目标销售额必填 | 单缺该字段→400复现；补齐→**200**（项目 id=2103636939180605441） | 数据演进假红-用例需修 fixture | ✅证实 |
| 2 | AC-PROD-03 创建SOFTWARE项目 | 400 项目必须归属产品 | 单缺 productId→400复现；挂真实在售产品→**200** | 数据演进假红-用例需修 fixture | ✅证实 |
| 3 | AC-PROD-04 存量导入 P4-1R | 400 目标市场必填 | 缺 targetMarkets→400复现 | 数据演进假红-用例需修 fixture | ✅证实 |
| 4 | AC-PROD-04 存量导入 P4-1R2 | 400 目标渠道商数必填 | 补市场缺渠道数→400复现；全补齐→**200**（LEGACY id=2103636939616813058） | 数据演进假红-用例需修 fixture | ✅证实（同款必填字段族） |
| 5 | AC-PROD-04 P4-3R3 直跳 ACTIVE | 400 状态机非法迁移 DRAFT→ACTIVE | 复现400；合法链 TEAMING→ACTIVE **200/200 终态=ACTIVE** | 数据演进假红-用例需修 fixture（用例设计缺陷子型） | ➕执行器直跳非法迁移，守卫 fail-closed 正确 |
| 6 | AC-PROD-04 P4-3R4 链含 CONFIRMED | 400 终态DRAFT | 复现400（DRAFT→CONFIRMED 非法） | 数据演进假红-用例需修 fixture（用例设计缺陷子型） | ➕CONFIRMED 不在 projects 状态机枚举（ProjectService.java:54-59），执行器误用他域状态名 |
| 7 | AC-HAND-01c 组长代移交 | 403 无权操作 | 跨组复现403；同组正例先前已绿（H1R2 PASS + 本轮 6c admin 腿 200） | 数据演进假红-用例需修 fixture | ❌推翻「真缺陷候选」：GROUP_LEADER 有 onBehalf 权（requireLeaderOrAdmin 注解放行），403 是 SEC-REV-HANDOVER-01 同组守卫（HandoverService.java:173-179 `assertSameGroupIpd`）**设计内**跨组拒绝；夹具把组长留在 900001 组打 9120002 组项目 |
| 8 | AC-HAND-01c 代移交 | 400 接手人不能与原负责人相同 | 复现400；接手人改胡9110005→**200 COMPLETED**→cancel 复位 ROLLED_BACK | 数据演进假红-用例需修 fixture | ✅证实「fixture撞自己」：from 由服务端反查在任绑定（赵，exit_date IS NULL 按 id 升序第一条），与 to=赵 同人被拒正确（HandoverService.java:367-368）；多轮移交+撤销演进使赵回到在任位 |
| 9 | AC-INC-34 报表导出 | 400 ct=json 144bytes | 无参复现400 **msg=缺少必需参数: month**；带 month→**200 code=0 9820bytes** | 数据演进假红-用例需修 fixture（用例建档错误子型） | ✅证实为**参数错**非权限错：month 是 @RequestParam 必填（ReportController.java:84-88）；原用例假设 projectId 参数属臆测——本端点契约=month/productId(可选)/keyword(可选) |
| 10 | AC-REQ-09 需求池删除申请 | 400 不支持的 entity_type: requirements | 新 jar **仍复现 400** | **真缺陷-建议立卡** | ✅证实（详见下） |
| 11 | AC-PROD-09 待指派超5工作日提醒 | 扫描方法零触发路径 | 静态+真库取证**仍成立** | **真缺陷-建议立卡** | ✅证实（详见下） |
| 12 | AC-IPD-03 轻管 transit DONE | 409 db=NOT_STARTED | 复现：market 写 RD_PM 动作→**409 envelope=40004 角色固定不可跨**；operator 改对后 fields→IN_PROGRESS→DONE **200/200/200 db=DONE+actual_done_at 落库** | 数据演进假红-用例需修 fixture | ❌修正初判：409 **不是前置态不满足**，是 `IpdPermission.requireActionWriter`（IpdPermission.java:153-161）ROLE_LOCKED 角色锁（action C05 owner_role=RD_PM，operator ipd-market）；R3 轮 body 还混入白名单外 remark+非 ISO 日期（r4 L2 记录已登记）。服务侧轻管完成路径无缺陷（原 C05 现 status=DONE 即 rd 腿走通的存量旁证） |

## 真缺陷详情（2 条）

### DEF-A：AC-REQ-09 需求池删除未接双层审核链
- **AC 原文**（`docs/ipd-系统说明/外部资源/IPD系统_验收清单.md:346`）：`AC-REQ-09 | 需求池记录删除 | 强制双层审核（组长初审 + 超管终审）`——**未废弃、无口径变更**；建表注释亦写明「需求池（AC-REQ-09 双层审核删除）」（Wave2-8Agent并行实施规格包-20260905.md:395）
- **源码证据**：`DeletionRequestServiceImpl.java:438-439` `SUPPORTED_ENTITY_TYPES = Set.of("projects","products","persons","cert_templates","gates")` 缺 `requirements`；软删执行器全仓仅 5 个（Project/Product/Person/CertTemplate/Gate），**无 RequirementSoftDeleteExecutor**；`entityExists` switch 亦无 requirements 分支（fail-closed）
- **复测证据**：新 jar B39 上 `POST /api/v1/deletion-requests {entityType:"requirements", entityId:2103338131276259329(真库存在,SUBMITTED)}` → 400 不支持（复测记录 #5）
- **附加发现**：`log.md:1064`（2026-09-05 第十七轮）曾判「AC-REQ-09 ✅」——当时仅以通用 entityType 走通双层链，**属误标**，建议归因卡一并勘误
- **建议卡题**：`【AC-REQ-09·真缺陷】需求池记录删除接入双层审核链：SUPPORTED_ENTITY_TYPES 增 requirements + RequirementSoftDeleteExecutor + entityExists 分支 + 双层审核端到端验收（并勘误 log.md 第十七轮误标）`

### DEF-B：AC-PROD-09 待指派超时提醒零触发路径 + 通知对象双重偏差
- **AC 原文**（验收清单.md:100）：`AC-PROD-09 | 「待指派」需求超过 5 个工作日未处理 | 提醒该产品组组长兜底处理`
- **零触发取证**：`GuestDemandService.notifyOverdueUnassigned()`（GuestDemandService.java:354-371）src/main 全量 grep 引用=1（仅定义处）；11 个 @Scheduled 类（P0Escalation/GateLegacy/GateSign/KpiSharedDeadline/HandoverOverdue/PersonResign/ProjectScore/NotificationOutbox 等）**无一包装本方法**；无任何 Controller 暴露；真库 `audit_logs action LIKE '%overdue_unassigned%'` = **0 行**（从未在真实环境跑过）
- **双重偏差**：①方法体只写 audit 留痕，注释自认「真实通知由 scheduler 在 audit 后调用 publish（见后续 P2-4.1 桥接）」——桥接未发生；②AC 要求提醒**产品组组长**，实现注释口径为「通知超管」
- **单测掩盖根因**：P413AcceptanceTest 直接调用 service 方法断言返回值（9 测全绿），不覆盖触发路径——看板卡 P4-1.3 据此翻 done，属「实现完成但入口不可达」复发（同 HandoverController R-NEW-B-1 注释所述模式）
- **建议卡题**：`【AC-PROD-09·真缺陷】notifyOverdueUnassigned 接线：新增 @Scheduled 调度器（错峰登记 IpdSchedulingConfig）+ 超管手动扫描端点 + 通知改 publish 且对象对齐 AC 口径（产品组组长）`

## 复测写库登记（详见 写库清单-归因复测.json）
| 对象 | 说明 | 处置 |
|------|------|------|
| products A/B (R218-归因复测产品-0925) | PM_NEW 夹具 | 留存（与历轮 QA08 夹具同池） |
| projects id=2103636939180605441 | 完整夹具正例（+全套 stages/actions） | 留存；其 LIGHT 动作 D08 被复测流转至 DONE |
| projects id=2103636939616813058 | LEGACY 存量导入正例 | 留存；状态链复测终态=ACTIVE |
| handover_records id=2103636940560531457 | 代移交正例（赵→胡） | **已 cancel 撤销=ROLLED_BACK**，9140005 MARKET_PM 绑定复位（赵回在任 exit NULL、胡回退出态） |

## 对 QA 下一轮的修例建议（不构成翻卡）
执行清单-r218.json 中 12 条 FAIL 建议按上表改判为「假红-用例修 fixture」×10（P3-1R/P4-1R3/N5R/H1R2/P4-3R5/L 系列已在 R3/R4 补丁轮转绿，本轮在新 jar B39 全部复核成立）；AC-REQ-09、AC-PROD-09 两条**维持 FAIL 并转立卡流程**。
