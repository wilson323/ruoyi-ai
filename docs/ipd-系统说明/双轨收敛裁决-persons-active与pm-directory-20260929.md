# 双轨收敛裁决：`persons/active` ↔ `pm-directory`（2026-09-29）

> 裁决人：owner 授权会话执行 · 范围：R118 契约端点与 PM 目录端点的重复度收敛
> 结论：**保留双轨 + 明确分工 + 补齐 MOCK 排除缺口**；不删任何端点（删 = 改 R118 契约，需 owner 拍板，本次不越权）。

## 一、事实（现盘取证 2026-09-29）

| 维度 | `GET /api/v1/persons/active` | `GET /api/v1/pm-directory` |
|---|---|---|
| 实现 | `PersonController#listActive` → `PersonService.listActive` | `PmDirectoryController#directory` |
| 在职口径 | **雇佣维度** `employment_status='ACTIVE'`（DISABLED/FROZEN 仍返回） | **账户维度** `account_status='ACTIVE'` |
| MOCK 排除 | **缺失（缺口）** → 本轮补齐 | R46-A3 三重：`account_status≠MOCK` + `name NOT LIKE 'Mock-%'` + `username NOT LIKE 'u_QA-SYNC-%'` |
| 字段 | id/name/personType/groupId（轻量四字段） | id/name/employeeNo/personType/level/groupId/groupName（富七字段） |
| 权限 | 方法内 `requireInternal`（内部全员可读） | `@SaCheckPermission(OPERATION_MODULE_PROJECT)` |
| 消费方 | 内部角色选择器的在职名册（R118 契约；前端 API 层已接 `listActivePersons`） | 顶栏/招募/项目空间/协作圈/移交接任人等选人下拉（多 UI） |

- **R118 契约原文**（R121-真活E2E-拍板包 L24，SSOT=`scripts/check-e2e-fe-be.sh` L169）：
  `GET /api/v1/persons/active` → HTTP 200 + `data.persons[].id/name`（**非 MOCK**）。
- **历史裁决**（log.md:12922）：「persons/active 判定无需接线（PM 下拉已用 /pm-directory 正常工作，防为用而用）」——UI 侧不换数据源已被接受。
- **R33 事故教训**（PmDirectoryController 注释在案）：Mock-QA-SYNC-20260910B 账号 `account_status=ACTIVE`，被纯状态过滤漏进移交接任人下拉；R46-A3 治本 = 双前缀过滤 + 哨兵值双保险。

## 二、裁决

1. **双轨保留**：两端点语义差异真实（雇佣维度 vs 账户维度；轻量名册 vs 富目录），非纯冗余。删任一端 = 改 R118 契约或回归多 UI 选择器，均需 owner 级拍板，不在本次授权内。
2. **收敛 = 补缺口 + 同口径**：`PersonService.listActive` 补 R46-A3 同款 MOCK 三重排除，履行契约明文「非 MOCK」（此前未满足，属 R33 同款缺口，非设计）。
3. **分工固化**：
   - 内部角色选择器「在职名册」（含雇佣在职但账户暂不可登录者）→ `persons/active`；
   - 需要组名/等级/工号的选人下拉 → `pm-directory`（现状不动）。
4. **防误用**：两端点 javadoc/注释互指分工文档；测试锁分工差异（含「DISABLED 不滤」口径锁），防未来漂移回单一维度。

## 三、实施（2026-09-29）

- `PersonService.listActive()`：+`.ne(accountStatus,'MOCK')` +`.notLike(name,'Mock-%')` +`.notLike(username,'u_QA-SYNC-%')`（口径不变：不滤 DISABLED/FROZEN）。
- `PersonActiveEndpointContractTest`：+`[R118-7]` MOCK 排除锁 + 分工口径锁；类 javadoc 补「非 MOCK」口径。
- `ruoyi-ipd-web` `api/ipd/person.ts`：注释补与 `pm-directory` 的分工说明（不换 UI 数据源）。

## 四、验证记录（2026-09-29 新鲜跑，命令与原始输出摘要）

| # | 层级 | 命令 / 手段 | 结果 |
|---|---|---|---|
| 1 | 契约单测 | `mvn -o -pl ruoyi-modules/ruoyi-ipd surefire:test -Dtest=PersonActiveEndpointContractTest`（21:41:57） | `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0` · **BUILD SUCCESS**。`Skipped=0` 证明未被 Surefire `<groups>` 静默跳过（假绿陷阱已排）。用 `surefire:test` 绕 compile，是因兄弟在途改 `HrTokenClient.postJson` 签名（mtime 21:40:49、` M`）致 `HrApiClient:86` main 编译瞬时红，与本改动无关，不代写。 |
| 2 | 红-绿自证（R134） | 备份 → 删三重过滤三行 → 复跑 → 恢复 → 复跑 | 删后 `Failures: 1` **BUILD FAILURE**（测试确能红）；恢复后（grep 确认 `.ne(MOCK)`/`.notLike(Mock-%)`/`.notLike(u_QA-SYNC-%)` 三行在位）7/7 PASS。 |
| 3 | 影响面回归 | `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest='PersonActiveEndpointContractTest,P213PersonStateAcceptanceTest,P241AcceptanceTest,P034AcceptanceTest' test` | **36 tests / 0 failures / BUILD SUCCESS**（7+12+8+9）。消费面：grep 全仓 `PersonService.listActive` 唯一调用点 = `PersonController.java:85`。 |
| 4 | 真库量化（ipd_dev） | `mysql --defaults-extra-file=.codex/ipd-dev/config/mysql-client.cnf`（凭据不上命令行） | 改前口径 **111** 人 → 改后 **25** 人，被排除 Mock 种子 **86（77%）**；即改前「在职名册」近八成为 Mock 污染，R118「非 MOCK」确属真实缺口（R33 事故放大版）。 |
| 5 | 集合关系 | 同上（补 `del_flag='0'` 精算） | `pm-directory` **22** ⊂ `persons/active` **25**；独有 3 人 = `9110001 傅志谦`、`2096266884100935682 系统管理员`、`2114000000000000001 R214市场PM`（三者均 `employment_status=ACTIVE` + `account_status=DISABLED`）→ **两端点为真包含关系而非冗余**，裁决 1「双轨保留」有数据支撑。 |
| 6 | 产物字节码 | `unzip ruoyi-admin.jar → BOOT-INF/lib/ruoyi-ipd-3.1.0.jar → javap -p -c PersonService.class`（21:39） | `listActive()` 字节码含 `ldc ACTIVE` / `ldc MOCK` / `ldc Mock-%` / `ldc u_QA-SYNC-%`（顺序与实现一致）= 新过滤**已编入可部署 jar**，后端重启即生效。 |
| 7 | 运行态 HTTP（门禁） | `bash scripts/check-e2e-fe-be.sh`（内建登录取 token，L169 probe R118） | **PASS · 真实 RC=0**（不取管道尾退出码）。报告 `E2E-验收-20260929-2155.md`：`✅ 登录取 token 成功（token_len=187，凭据不入报告）` · `R118 /api/v1/persons/active → 200 / code=0/IPD / PASS` · `STATUS: PASSED（5 项契约全 PASS）`。注：21:32 曾 RC=1 判「后端未启」，原 java PID 96442 已退出；后端于 **21:52:16** 重启（PID 88895，jar 打包于 21:44:05 已含本改动）后复跑即绿。 |
| 8 | 运行态精确核对 | 复用脚本同款登录取 token → `curl -H "Authorization: Bearer"` 实调，python3 解析 | `code=0`、**`n=25`**（与验证 4/5 的真库 SQL 精算 25 逐数对上）、**`mock_hits=[]`**（零 Mock 污染）；`fields=['groupId','id','name','personType']`（轻量四字段符 R118）；名单含「傅志谦」（`account=DISABLED` + `employment=ACTIVE`）→ 实证「DISABLED 不滤」口径未被误伤，仅排 MOCK 哨兵值。 |
| 9 | 前端回归 | `pnpm run check:type` + `pnpm exec vitest run --config vitest.ipd.config.mts`（ruoyi-ipd-web） | `CHECKTYPE_RC=0`；vitest **1803 passed / 37 skipped / 0 failed**（175 文件 passed、5 skipped），`VITEST_RC=0`。 |
| 10 | 跨仓契约门禁 | `bash scripts/check-cross-repo-contract.sh` | **RC=0**：白屏风险 0 / 孤儿端点 43（baseline=43、**新孤儿=0**）/ 文档未实现 16；报告 `lint-reports/contract-drift-20260929-215636.md`。 |

**状态：`VERIFIED`** —— 静态 / 契约单测 / 红绿自证 / 影响面回归 / 真库量化 / 产物字节码 / 运行态 HTTP（含 25 人与零 Mock 精确核对）/ 前端全量 / 跨仓门禁共十层均有新鲜证据；无未处理关键失败，无超范围修改。

## 五、遗留

- 若 owner 希望**彻底单轨**：候选方向是废弃 `persons/active` 并把 R118 契约改指向 `pm-directory`（=改需求）；或反之让 `pm-directory` 委托 `persons/active` 口径（会改变现有下拉行为，需回归）。两者均显式挂起待拍。
- ~~**待补**：后端 16039 恢复监听后重跑 `check-e2e-fe-be.sh`~~ → **已闭环**（21:55 后端重启后复跑，验证 7/8：门禁 PASSED + 运行态 `n=25`、`mock_hits=[]`）。
- 独有 3 人（傅志谦 / 系统管理员 / R214市场PM）账户 `DISABLED` 但雇佣 `ACTIVE`，会出现在 `persons/active` 而不出现在选人下拉——此为裁决 3 分工的**预期行为**，若 owner 认为内部角色选择器也应排除 DISABLED，属改口径需求，需另行拍板。
