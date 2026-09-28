# ChainSpec 审批链抽象 · 设计方案（R33，marker r33-chainspec）

> 补遗 §5 优先序第 7 项（《工作流系统性梳理-补遗-20260927.md》:182）。本文=双智能体全量盘点（2026-09-27，只读取证）整合 + 分期方案。所有断言带 文件:行号 证据；路径缩写 `IPD/` = `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/`。

## 1. 实然盘点结论（7 条审批链）

| 链 | 形态 | 签位 | 并发防线 | 超时 | Guard 接线 | 关键证据 |
|---|---|---|---|---|---|---|
| C1 上市日期变更 | 串行双签（预落第二签人） | 2 | @Version + DB 唯一键（最完备） | 无 | ✅ | IPD/service/LaunchDateChangeService.java:119,202 |
| C2 系数定值 | 联合提议+组长单审 | 提议即含两签+终审 1 | CAS 谓词翻转 | 无 | ✅ | CoefficientChangeService.java:102,167 |
| C3 删除申请 | 两级审核（组长→超管） | 2 | 单条裸 check-then-update / 批量 CAS | ✅工作日+手动 escalate | ✅ | DeletionRequestServiceImpl.java:141,269,326,371 |
| C4 Gate 评审 | 双盲签+仲裁/终裁+重评轮次 | 2+仲裁链 | DB 唯一键(签行) / settle 裸写 | ✅ @Scheduled | ✅ | GateReviewService.java:207,477,532,622 |
| C5 共担 KPI 确认 | 两组长抢签（无预落名） | 2 | **最薄弱**：裸 updateById，第二签并发双写窗口 | ✅ 09:10 调度 | ❌ 未接 | KpiSharedConfirmService.java:246,284 |
| C6 需求变更 | 并行双签（";"字符串聚合） | 2 | **四道防线全缺**（无乐观锁/CAS/唯一键/串行化） | 无（靠跳阶倒逼） | ✅ | RequirementChangeService.java:207,219-244 |
| C7 移交承接 | 一签变体+24h 撤销窗 | 1+撤销签位 | check-then-update + 原子副作用中止 | ✅ 双系统并存 | ✅ | HandoverService.java:225,474,747 |

排除项（取证后确认非审批链）：KpiSharedReconcileService（只读对账:82）、PostLaunchReviewService（单方操作:163）、BidResponseService（签位由遴选链回写）、WorkbenchService（只读消费方:391-451）。

## 2. 高频重复骨架（收编对象）

| 骨架 | 重复度 | 拷贝数 | 位置 |
|---|---|---|---|
| preCheckGuard/registerPostCommit 接线六连（fail-closed 抛错+afterCommit 双路径+无事务降级） | ~95%，约 180 行 | 6 | LDC:71-99 / COEF:60-88 / Gate:124-155 / Handover:128-159 / Deletion:717-746 / ReqChange:96-126 |
| 终态守卫前置检查（`if (!FROM.equals(status)) throw`） | ~85% | 6+ | LDC:211 / COEF:182 / Kpi:263 / Gate:332 / Handover:234 / ReqChange:182,216 |
| 决策 CAS 谓词翻转（`update(null, wrapper.eq(status,FROM).set(...)`) | ~70% | 4 | COEF:205-218 / Deletion:385-390 / Kpi 清零:132-137 / Gate reopen:492-498 |
| 在途单唯一预检（selectCount eq PENDING 抛错） | ~90% | 2 | LDC:151-153 / COEF:131-133 |
| publishDaily 幂等催办 + isNull(escalatedAt) 一次性升级 | ~75% | 5 | Deletion:401-417 / Gate:608 / HandoverScanner:94 / Handover:711 / KpiCollection:410,574,592 |
| 可注入 Clock（setClock 测试摇摆消除） | 逐字级 | 5 | Gate:190 / Handover:117 / Deletion:123 / Scanner:43 / KpiScheduler:41 |
| audit helper → append(builder) | ~80% | 5+ | 见各链 |

## 3. 真正业务特化（留扩展槽，不硬抽象）

盲评掩码三件套 / 仲裁→免终裁三级升级 / 重评轮次 round+1 / G3-G4 单签主导分叉（以上 C4，GateReviewService:245-268,622-700,477-501,197-202）；跨域原子软删（C3:336-341）；撤回双路径报错版/掩错版（C3:176 vs 231）；afterData 快照与 OVERDUE 读时派生（C5:293-299,44）；签名字符串聚合正则判齐 + 回写失败 REQUIRES_NEW 审计（C6:221-254,297-302）；第二签人预落（C1:136-138）；承接资格复核+撤销窗（C7:255-260,489-498）。

## 4. 既有资产与红线（双轨防线）

**ChainSpec 必须建于其上**：①`DefaultStateMachineGuard` 规则表（50 条/10 机，热注册 :517-522，rulesSnapshot 导出 :640-642，`guardClass` 死字段 :59-60 可作挂点）＝迁移拓扑唯一事实源（《三套工作流引擎定位裁定》:12）；②setter 注入 fail-closed 接线样板；③契约 JSON 双仓链路（现 50 条全等，schema 五字段，扩展须向后兼容同文件重导出）；④`rd_replacement_approvals` 每步一条留痕表模板（draft sql:95-115，uk 防重签）；⑤due_at/超时 trigger/Scanner 三件套先例；⑥postCommit 副作用车道（:586-605）；⑦workbench 17 类待办契约；⑧哨兵测试族（ruleCount==50）。

**红线（违反即双轨/越权）**：R-1/R-2 不用 Warm-Flow、ruoyi-aiflow 承接 IPD 审批（三套裁定:10-11）；R-3 不重写 Gate 签署语义（:15 owner 级）；R-4 不另建链定义表/配置；R-5 不另起前端契约 JSON；R-6 不为四新链再造新表（5 表已 apply ipd_dev，schema-snapshot:1154,1740,1968,2369）；**R-7 owner 拍板前不接 waiver**（Q4 签位口径三处矛盾，补遗:158,163,190）；R-8 不延续 ";" 拼接签名串为新链存储；R-9 前端 IPD 决策件与平台弹窗 schema 不合并。

盘点见闻缺陷（登记）：C4 `settleTimeout` 规则重复注册（Guard:207 与 :396 两条）；`deletion.escalateTimeoutHours` 键被用作撤回时限（语义漂移，Deletion:187,191）；死键 gate.dualSignCount/kpi.approvalRole/scenario.approvalRole；口径文档"42 条"滞后于实测 50 条。

## 5. 分期方案（最小完整切片，反对 big-bang）

**第一期（R33 本轮）= 共享骨架收编，行为零变更**：
1. 新建 `org.ruoyi.ipd.approval` 包：`ApprovalGuardSupport`（收编 preCheckGuard/registerPostCommit 六连拷贝 + 终态守卫 + 在途唯一预检 + 决策 CAS 谓词翻转 + setClock）——纯抽取，不改任何状态语义；
2. 7 链 Service（C1-C7）改为继承/委托该支持类，逐链迁移；C5 KpiSharedConfirmService 顺带补 StateMachineGuard 接线（5 类病根③「接线点靠人肉对账」）；
3. **缺陷修复随批**（根因修复非掩盖）：C6 需求变更并发防线全缺 → 第二签决策改 CAS 谓词翻转（对齐 C2 模式）；C5 两组长并发抢第二签双写窗口 → 同改 CAS；
4. 契约测试：每链迁移前后各态断言钉死（自证能红）；既有哨兵全绿（ruleCount==50 不变——第一期末不新增规则则哨兵不动；若 C5 补接线新增迁移则同步改哨兵与契约 JSON 重导出，走口径 §6 纪律）；
5. 禁止项：不建表、不动前端契约 schema、不接 waiver/retirement/capacity/rd_replacement 四新链。

**第二期（另案，待 owner 拍板）**：签位维度/否决组合规则/超时策略参数化进 StateTransitionRule schema 扩展（guardClass 挂点激活 + 契约五字段向后兼容扩展）+ 四新链接入（waiver 三签按 Q4 拍板口径）。~2 人日总量=一期 ~1 人日 + 二期 ~1 人日。

## 6. 验收标准（一期）

- aiflow 无关；`mvn -o -pl ruoyi-modules/ruoyi-ipd test` 全绿（现基线含兄弟在途批次，以集成点复跑为准）；
- 新增审批链契约测试全 @Tag("dev")，含自证能红（拆共享组件调用点必红）；
- C6/C5 并发修复带红绿对照测试（并发双写→CAS 后单胜）；
- check-api-contract-fe-be 棘轮不新增孤儿；StateMachineGuard 规则数与双仓 JSON 一致性不破坏；
- SSOT 三件套登记（log.md R33 行 + 镜像段 + 补遗 §5-7 注记）。
