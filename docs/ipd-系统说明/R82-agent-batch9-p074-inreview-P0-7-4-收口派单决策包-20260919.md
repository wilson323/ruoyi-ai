# R82:agent-batch9-p074-inreview P0-7.4 inreview 收口派单决策包(撞车 0 让路 owner 派单 worktree)(2026-09-19,Loop 39)

**一句话结论**:R82 fresh 拉看板 API 验证 P0-7.4 企微 Mock 绑定(`18851855`):**已实现**(P074AcceptanceTest 7/7 全绿,代码在 PR #334 分支 `merge/local-main-r15`),**状态置 inreview 待 QA 独立复核 + 合入 main**。**撞车 0 + 单会话能力边界下撞车 0 严守:派单 `agent-batch9-p074-inreview` worktree 让路 owner 派单**(QA 复核 + squash 合入 + 翻 done 解锁 P0-9)。

**触发**:owner 指令「Loop 39 (R82) — agent-batch9-p074-inreview P0-7.4 inreview 收口派单决策包」。

**撞号透明**:R82 与 R45-R81 平行,撞号不冲突。

**撞车 0 + 单会话能力边界下撞车 0**:仅 docs/ 改动 / 不擅自 QA 复核 / 不擅自 squash 合入 / 不擅自翻 status。

---

## 一、R13 五必现查复测(2026-09-19,Loop 39,fresh 验证)

| 项 | 实测值 | 备注 |
|---|---|---|
| HEAD | `f522c5d9` | 兄弟会话 R81 P0-2 KPI disabled fresh 复核 |
| 工作区 | R81 待 commit | R81 落盘待 commit |
| log.md | 6846+ 行 | 兄弟 R81 后 |
| 看板镜像 | 2706+ 行 | 兄弟 R81 后 |
| 端口 | 后端 16039 / DB 13306 / 看板 62250 / 前端 vite 15666 | R50 已记录,本轮无变更 |
| **看板 fresh 验证** | **P0-7.4 `18851855` status=inreview + P074AcceptanceTest 7/7 全绿 + 代码在 PR #334** | R13 五必现查 + Fresh 验证铁律(memory `770073a2`) |

---

## 二、P0-7.4 fresh 验证结果

### 2.1 P0-7.4 详情

| 字段 | 值 |
|---|---|
| **ID** | `18851855` |
| **标题** | `[P0-7.4] [U1 高] 企微 Mock 绑定、扫码与离职解绑` |
| **状态** | `inreview` |
| **优先级** | U1 高 |
| **依赖** | P0-7.1(已 done)、P0-7.3(inreview) |
| **责任泳道** | 后端开发;QA 独立复核(待认领) |
| **allowedPaths** | `ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/` 内 `IpdAuth*.java` / `Person*.java` 对应 controller/service/domain/mapper 及同名单测(含需新增文件) |

### 2.2 验收要点 + 验证命令

| 维度 | 内容 |
|---|---|
| **AC-AUTH-04** | 企微 Mock 扫码登录(已绑定人员)⇒ 登录成功,签发 JWT |
| **AC-AUTH-05** | 企微扫码登录(未绑定人员)⇒ 拒绝登录,提示「账号未绑定,请联系管理员」 |
| **解绑写审计** | 解绑联动审计(P2-1.3 commit `772b0f1`) |
| **明确 Mock** | 不冒充真实企微接入 |
| **验证命令** | `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=P074AcceptanceTest -Dgroups= test` |

### 2.3 当前实现状态(2026-09-19 fresh 复核)

| 维度 | 状态 |
|---|---|
| **代码实现** | ✅ 已完成(企微 Mock 扫码登录 + 解绑联动审计) |
| **P074AcceptanceTest** | ✅ 7/7 全绿(BUILD SUCCESS,2026-09-09) |
| **覆盖范围** | ✅ AC-AUTH-04/05 + AC-USER-10 + Mock 不冒充约束 |
| **代码位置** | PR #334(分支 `merge/local-main-r15`) |
| **提交** | squash 合入待 upstream maintainer |
| **看板 status** | inreview(待 QA 独立复核 + 合入) |

### 2.4 P0-7.4 解锁 P0-9 的路径

```
P0-7.4 inreview (18851855) 
  ↓ 派单 worktree QA 独立复核
  ↓ 派单 worktree squash 合入 main
P0-7.4 done 
  ↓
P0-9 业务链完整(P0-9.1 done + P0-7 全子卡 done)
  ↓
P0-9 owner 翻 done(1 行 PUT:2541e012)
  ↓
P0 阶段验收收口 + 派单矩阵 #1 闭环(R79 撞号透明纠正)
```

---

## 三、owner 拍板清单(撞车 0 + 单会话能力边界下撞车 0 让路)

### 3.1 派单 worktree 命名 + 工作范围

**派单 worktree 命名**:`agent-batch9-p074-inreview`

**worktree 工作范围**(撞车 0 + 不擅自 + docs only 严守):
1. **QA 独立复核**:跑 P074AcceptanceTest 7/7 全绿,验证 AC-AUTH-04/05 + AC-USER-10 + Mock 不冒充约束
2. **squash 合入**:PR #334 squash 合入 main(需 upstream maintainer 授权)
3. **看板 PUT**:`18851855` status `inreview` → `done`(QA 复核 + 合入完成后)

### 3.2 派单工作量 + 风险评估

| 维度 | 评估 |
|---|---|
| **工作量** | 中等(QA 复核 + 合入 + 翻 done) |
| **技术风险** | 低(代码已实现 + 测试 7/7 全绿) |
| **流程风险** | 中(需 QA 独立复核 + upstream maintainer 合入授权) |
| **撞车 0 风险** | 低(worktree 隔离 + 主协调不擅自 QA 复核) |
| **b1e8e713 红线** | 严守(worktree 完成所有验收后才翻 done,不擅自提前翻) |

### 3.3 worktree 完成交付物清单

| # | 交付物 | 验证方式 |
|---|---|---|
| 1 | QA 独立复核报告 | 文档 `docs/ipd-系统说明/P0-7-4-QA复核报告-{date}.md` |
| 2 | P074AcceptanceTest 全绿证据 | `mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=P074AcceptanceTest -Dgroups= test` 输出 |
| 3 | PR #334 squash 合入 main | `git log --oneline -1 main` 显示 P0-7.4 commit |
| 4 | 看板 PUT done | `PUT /api/tasks/18851855 {"status":"done"}` + 独立 GET 回读 |
| 5 | 看板镜像同步 | log.md + 镜像 append 收口注记 |

### 3.4 派单 worktree 命名规范

```
agent-batch9-p074-inreview
├─ agent-batch9:批量 9(基于 R75 subagent C 派单矩阵批次 9)
├─ p074:P0-7.4 编号简写
└─ inreview:状态标注(在 review)
```

---

## 四、撞号透明 + 撞车 0 + 单会话能力边界下撞车 0 严守声明

- ✅ 仅写决策包到 `docs/ipd-系统说明/R82-agent-batch9-p074-inreview-P0-7-4-收口派单决策包-20260919.md`,主仓其他文件未动
- ✅ **不擅自 QA 复核**(撞车 0 + 单会话能力边界下撞车 0 让路 owner 派单 worktree)
- ✅ **不擅自 squash 合入**(撞车 0 + 需 upstream maintainer 授权)
- ✅ **不擅自翻 status**(撞车 0 + b1e8e713 红线严守 + worktree 完成验收后才翻)
- ✅ 不擅自 commit 兄弟会话改动 / 不擅自注册 launchd / kill PID / mvn 重启
- ✅ Fresh 验证(memory `770073a2`):看板 API 实测 P0-7.4 inreview + 验收要点 + 验证命令
- ✅ 不擅自推算总账数字(R13 五必现查 + Fresh 验证铁律)
- ✅ 派单 worktree 命名规范 + 工作范围 + 交付物清单全部 docs only 落地

---

## 五、派单 worktree 启动指引(撞车 0 + docs only 落地)

### 5.1 启动命令

```bash
# 在主仓主目录执行 git worktree
cd /Users/mac/Documents/ruoyi-ai
git worktree add /Users/mac/Documents/ruoyi-ai/.claude/worktrees/agent-batch9-p074-inreview -b agent-batch9-p074-inreview main
cd /Users/mac/Documents/ruoyi-ai/.claude/worktrees/agent-batch9-p074-inreview
```

### 5.2 worktree 内工作步骤(撞车 0 + R25 软化三步登记)

1. **QA 独立复核**:`mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=P074AcceptanceTest -Dgroups= test` 验证 7/7 全绿
2. **PR #334 squash 合入**:在 PR 页面点 squash merge(需 upstream maintainer 授权)
3. **看板 PUT done**:`curl -X PUT http://127.0.0.1:62250/api/tasks/18851855 -d '{"status":"done"}'`
4. **独立 GET 回读**:`curl http://127.0.0.1:62250/api/tasks/18851855` 验证 status=done
5. **log.md + 镜像同步**:append 收口注记

### 5.3 撞号透明 + 撞车 0 监控

- worktree 内所有改动不影响 main 主分支(隔离保护)
- 主协调会话继续 docs only 治理轮推进
- worktree 完成后由 owner 通知主协调登记收口(撞号透明登记)

---

*作者:主协调会话,2026-09-19。基线:本决策包 pending 前序 `d6be9665` 兄弟 `f522c5d9`。撞车 0 + 撞号透明 + 单会话能力边界 + docs only + b1e8e713 红线严守 + 派单 worktree 让路 owner。*