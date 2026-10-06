---
name: one-click-test
description: ruoyi-ai 一键测试入口。把「单测 → 仓内静态门禁 → 真活前后端 E2E → 前端仓 typecheck/vitest/build」四段串成一条命令，失败时按「代码错 / 环境缺 / 鉴权缺 / 假绿 / 门禁自身坏」分类并给带时间戳的证据。当用户说"跑一下测试""一键测试""测一下能不能过""提交前验一遍"时用它，不要自己拼 mvn / 门禁 / pnpm 命令。
disable-model-invocation: true
---

# one-click-test

一条命令跑完本仓的测试链，并给出可追溯的失败分类。

**这个 skill 只是一层壳，全部行为都在 `.harness/one-click-test.sh` 里。** 不要在这里复制判据、不要自己拼 `mvn` / `pnpm` / 门禁命令——那会绕过脚本里的防假绿与分类逻辑，且违反本仓「口径必须与实际被拦的动作一致」的纪律。

## 何时用

- 改完代码要确认能不能过
- 提交前自验
- 别人问"现在测试什么状态"
- 拿到一个红，需要判断是代码错还是环境没起

## 怎么跑

```bash
cd /Users/mac/Documents/ruoyi-ai

bash .harness/one-click-test.sh              # 全跑（单模块单测）
bash .harness/one-click-test.sh --no-fe      # 跳过前端段（它含 build，最慢）
bash .harness/one-click-test.sh --only=gate  # 只跑某段：test|gate|e2e|fe
bash .harness/one-click-test.sh --full       # 单测跑全量而非单模块
bash .harness/one-click-test.sh --task=<ID>  # 传给前端 harness 的任务标识
```

退出码：`0` 全绿 / `1` 有失败 / `2` 用法错或前置缺失。

## 输出怎么读

汇总把失败分成六类，**先看分类再决定动作**，别一看红就去改代码：

| 分类 | 含义 | 你该做什么 |
|---|---|---|
| `CODE_FAIL` | 测试或门禁真的失败了 | 去证据列的日志看具体用例，这是要修的东西 |
| `FAKE_GREEN` | 测试跑了 0 个却报绿 | **别信这个绿**。Surefire 按 `@Tag("dev")` 过滤，是过滤口径坏了 |
| `GATE_DEFECT` | 仓内门禁指向已删除的脚本 | 与你的改动无关，是仓内漂移，要单独修门禁 |
| `ENV_MISSING` | 服务没起 / 依赖没装 / 锁被占 | 先弄环境，别改代码 |
| `AUTH_MISSING` | E2E 没拿到 token | 查 `credentials.json` 或会话是否过期 |
| `SKIPPED` | 按参数跳过了 | 无需处理 |

汇总开头一定有**时效三元组**（观测时刻 + git HEAD + 近 20 分钟改动文件）。仓规要求：**没有这三样的结论视为过期**，若怀疑兄弟会话刚改了代码，先重跑再看。

证据落在 `.harness/runs/one-click-<时间戳>/`，含 `stage-1..4.log`，可留作收口证据。

## 报告测试结果时

- 必须带上分类与证据路径，不能只说"测试红了"
- 红了先问是**谁的**问题：代码错 vs 环境缺。别把环境问题写成代码缺陷
- 若涉及既有漂移（如某道门禁自身坏了），明确说"改动前就红"，与本次引入的分开

## 禁止清单

- ❌ 不要用 `mvn clean` / `mvn -am`：并发会话共用 `target/` 会交叉重写，制造大面积假红（`AGENTS.md`）
- ❌ 不要写行首裸 `mvn`：一律走 `scripts/mvn-locked.sh`（`scripts/check-naked-mvn.sh` 会拦）
- ❌ 不要 `mvn package`：会重写 `ruoyi-admin.jar`，把正在 16039 上服务、E2E 段要用的进程打坏
- ❌ 不要把这脚本的结果当作「没有测试」：它只覆盖已接线的段
- ❌ 不要因为汇总红了就自动 commit / push：提交与推送需 owner 明确授权

## 相关

- 单测怎么写：`/gen-test`
- 后端改了接口要通知前端：`/api-contract`
- 数据库变更流程：`/db-migration`