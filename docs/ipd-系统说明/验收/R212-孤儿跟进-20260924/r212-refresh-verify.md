# R212 数字复核（只读现查 · 2026-09-24T14:13Z）

## 0. 环境事实（现查，非假设）
- 后端仓 HEAD = **a4290782**（不是任务书假设的 50d4d281；50d4d281 之后另有 3 个提交：14124c0c docs(R212) / 02b3b030 fix(ipd) / a4290782 feat(ipd-auth)）
- `523e4670`（R212 报告基线）**是** HEAD 祖先；区间 `523e4670..HEAD` = **25** 个提交
- 被扫 controller 目录在该区间只有 1 个文件变更：`IpdAuthController.java`（+12/-2 行，**新增 @*Mapping = 0**，即本轮 BE 合入未引入任何新端点）
- 前端仓 ruoyi-ipd-web HEAD = **3316e56**；`kpi.ts` 最近提交 **997fbc7**（W2-KPI2A 功能指标量表录入）

## 1. 复跑命令原文与退出码
```
cd /Users/mac/Documents/ruoyi-ai && IPD_FE_API_DIR=/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/api/ipd \
  node scripts/check-api-contract-fe-be.mjs --json > /tmp/r212-refresh-contract.json
```
- 开始时间戳（UTC，命令内 `date -u`）：**2026-09-24T14:13:06Z**
- 报告内 `scan.timestamp`：**2026-09-24T14:13:06.490Z**
- **exit code = 0**（默认模式：0 孤儿路径 ⇒ PASS；孤儿端点仅警告域）

## 2. 与 R212 报告口径对比（canonical 主文 §二 / §〇基线表）
| 指标 | R212 报告(523e4670) | 本次复跑(a4290782) | 漂移 |
|---|---:|---:|---|
| orphan_endpoints | 98 | **96** | **-2（消亡，方向正确）** |
| field_mismatches | 15 | **15** | 0（集合逐条一致，key 级 diff 空） |
| orphan_paths（前端调/后端无） | 0 | **0** | 0 |
| fe_calls_non_test | 179 | **182** | +3（kpi.ts 新增 3 处调用） |
| fe_files / be_controllers | 60 / 60 | 60 / 60 | 0 |

## 3. 孤儿集合逐条 diff（(method, path) 集合，A=/tmp/r212-contract.json vs B=/tmp/r212-refresh-contract.json）
- 消亡（A-B，2 条）：**GET /api/v1/kpi/functional-metrics**、**GET /api/v1/kpi/functional-metrics/codes**
- 新增（B-A）：**0 条**
- 共有（A∩B）：96 条；字段错位 15 条 key 级（canonical+feVars+beVars）diff 双向皆空

## 4. 漂移归因（谁让这 2 条消亡）
- 归因 = **前端补调用**，不是后端删端点：`apps/web-antd/src/api/ipd/kpi.ts:196/201/206` 三处 ipdGet/ipdPut 命中该路径；FE 提交 997fbc7（W2-KPI2A）。
- 后端侧该 2 条对应 controller 仍存在（HrSync/Kpi 目录未变更）；BE 25 个合入提交零新增 @*Mapping ⇒ **不可能产生新孤儿**，故本轮无「新增孤儿」风险来源被实测排除。

## 5. 现查 96 孤儿 Top 域（供白名单候选圈定）
gates 8 / kpi 7 / projects 7 / **hr-sync 6** / switching-acceptance 5 / auth 4 / **person-sync 4** / compliance 4 / 其余 ≤3，共 33 域。

## 6. 结论
**无劣化漂移**：98→96 单向消亡，新增 0，字段错位与孤儿路径完全持平。R212 报告数字在 a4290782 上仍然成立（口径需把 98 更新为 96）。

## 7. 写入部分二的设计前提
1. 门禁**未接入任何 CI/hook**：`.github/workflows`(15 个)、`.claude/hooks/*`、根 package.json（不存在）均不调它；唯一外部引用是 `scripts/check-contract-tri-source.sh:62,125` 的**存在性哨兵**（不执行、不继承 exit code）⇒ 改 exit code 语义对既有调用方零影响。
2. 去重口径：`orphan_endpoints` 按 **canonical path** 去重（实测 BE 285 个 @*Mapping 全带 method，报告仅 96 条 ⇒ 同路径多方法被合并，且 method 取扫描先到者）。**白名单必须按 path 记账，method 只作提示，否则 ①白名单不生效 ②豁免不干净**。
3. 路径归一：`normalizePath()` 把所有占位符压成字面量 `{VAR}`（如 `/person-sync/jobs/{id}/retry` → `/api/v1/person-sync/jobs/{VAR}/retry`）。白名单条目入库前**必须过同一函数**，禁止手写原文匹配。
4. 溯源红线：JSON 只含 `scan.timestamp`，**不含 git HEAD / 生成命令**（R212 §六已点名 `contract-0921-baseline.json` 「生成命令未登记」）。baseline 必须自带 HEAD+cmd 字段。
5. 环境耦合：`--be-root` 默认写死 `/Users/mac/Documents/ruoyi-ai`；哨兵阈值 `fe<56 / be<52` 写死 ⇒ CI 化需先参数化。

## 8. 只读声明
本轮零写：未改脚本/代码/文档，未跑任何写命令，未发真库或后端 HTTP 写请求；产物仅 `/tmp/r212-refresh-verify.md`、`/tmp/r212-refresh-contract.json`、`/tmp/r212-gate-design.md`。证据文件：`/tmp/r212-contract.json`(基线) vs `/tmp/r212-refresh-contract.json`(现查)。

## 9. 并发漂移披露（本轮实测，非我方写入）
本轮结束时后端工作树多出 2 个**兄弟会话**并发修改：`service/BonusPoolService.java`、`test/.../BonusPoolAllocationWriteTest.java`（本轮开始时不存在；我方全程只读，未写仓库任何文件）。二者不在门禁扫描域（只扫 2 个 controller 目录 + FE api/ipd）⇒ 已复跑第 2 次确认：`orphan=96 / field=15 / orphan_paths=0`，与第 1 次集合对称差 **0**，exit 0。结论对并发编辑**稳健**。证据：`/tmp/r212-refresh-contract-recheck.json`。
