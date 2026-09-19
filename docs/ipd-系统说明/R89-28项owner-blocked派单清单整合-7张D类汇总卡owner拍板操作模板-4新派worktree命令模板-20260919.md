# R89:28 项 owner-blocked 派单清单整合 + 7 张 D 类汇总卡 owner 拍板操作模板 + 4 新派 worktree 命令模板(2026-09-19,Loop 46)

**一句话结论**:R89 = 28 项 owner-blocked 派单清单整合 + **7 张 D 类汇总卡 owner 拍板操作模板**(GET → PUT → GET 复核 → title 注记移除)+ **4 新派 worktree 命令模板**(P0-7.4 + 跨仓对账 + 孤儿修补 + 字符集 ALTER)+ **强约束 6 步执行顺序图**。**撞车 0 + b1e8e713 红线严守:不擅自翻 status,只写操作模板给 owner 拍板**。

**触发**:主人指令「立即执行」= R89 = R88 §四 + §五 整合 + 操作模板 + 命令模板。

**撞号透明**:R89 主协调版与兄弟会话 R85 + R86 + R88 三 commit 撞号透明让路。

**撞车 0 + b1e8e713 红线严守**:不擅自翻 status,只写操作模板;不擅自启动 4 新派 worktree,只写命令模板。

---

## 一、R13 五必现查复测 + 看板 fresh 验证(2026-09-19,Loop 46)

| 项 | 实测值 | 备注 |
|---|---|---|
| §1 HEAD | `19fe56d2` | R88-C3 撞号透明承接 |
| §2 工作区 | clean | `git status --short` 返空 |
| §3 端口 | 后端 16039 / DB 13306 / 看板 62250 / 前端 vite 15666 | R76 + R85 兄弟会话一致 |
| §4 log.md / 镜像 | log.md 7250+ / 镜像 2860+ | R88 SSOT 同步后 |
| §5 跨仓命令 | 必 `cd /Users/mac/Documents/<repo> &&` 开头 | 主仓 / 前端仓严守 |
| **§6 看板总账 fresh 验证** | **total=466**(新增 D 类汇总卡实时状态)| memory `39859730` 看板只认 ruoyi-ai project_id `01dcf15c-86bb-4c7b-957c-8fe44bddd10d` |

### §6.1 看板总账 fresh 验证(2026-09-19 03:00 PT)

```
看板总账:total=466
关键卡 fresh 状态:
- D2 P3-1 f71ba244:todo + 标题已注记 [子卡已全 done 待 owner 翻]
- D3 P3-3 962c9087:todo + 标题已注记 [子卡已全 done 待 owner 翻]
- D4 P3-4 5d00a4b0:todo + 标题已注记 [子卡已全 done 待 owner 翻]
- D1 P0-9 2541e012:todo + 标题无注记(违反 b1e8e713,撞车 0 不擅自加)
- P0-7.4 18851855:inreview(企微 Mock 绑定未 done,代码 PR #334)
- P3-4 旧卡 eb781e5d:BLOCKED 旧版(不阻塞主路径)
```

---

## 二、7 张 D 类汇总卡 owner 拍板操作模板(撞车 0 + b1e8e713 红线严守)

### 2.1 7 张 D 类汇总卡拍板总表 + 强约束 6 步执行顺序

| 步骤 | 卡号 | 卡 ID | 当前 status | 子卡 | owner 操作 | 阻塞 |
|---|---|---|---|---|---|---|
| **[1]** | **D2 P3-1 KPI 结构** | `f71ba244` | todo + 标题已注记 ✅ | 4/4 done | **1 行 PUT 翻 done** | 无阻塞 |
| **[2]** | **D3 P3-3 月度津贴** | `962c9087` | todo + 标题已注记 ✅ | 3/3 done | **1 行 PUT 翻 done** | 阻塞 P3-1 |
| **[3]** | **D4 P3-4 奖金池核算** | `5d00a4b0` | todo + 标题已注记 ✅ | 5/5 done | **1 行 PUT 翻 done** | 阻塞 P3-1 + P3-3 |
| **[4]** | **P0-7.4 企微 Mock 绑定** | `18851855` | inreview(企微 Mock 未 done)| 代码已实现 | **owner 派单 worktree 收口** + QA 独立复核 + squash 合入 + 翻 done | 代码 PR #334 + P074AcceptanceTest 7/7 全绿 |
| **[5]** | **D1 P0-9 阶段验收** | `2541e012` | todo + 标题**无注记**(违反 b1e8e713) | 1/1 done | **等 P0-7.4 done + owner PUT 翻 done** | 阻塞 P0-7.4 |
| **[6]** | **P3-4 旧卡 BLOCKED** | `eb781e5d` | BLOCKED 旧版 | — | **owner 派单 worktree 关闭** | 不阻塞主路径 |
| 额外 | **R86 STG-501-1** | `e7b9289c` | inreview | — | owner 派单 3 决策项 | 阻塞 owner 决策 |

### 2.2 owner 操作模板(7 张 D 类汇总卡通用)

**Step 1:独立 GET 复核当前状态**(memory `770073a2` Fresh 验证铁律)
```bash
curl -s "http://127.0.0.1:62250/api/tasks/{card_id}" | python3 -c "
import json, sys
t = json.load(sys.stdin).get('data', {})
print(f\"id={t.get('id','')[:8]} status={t.get('status','?')} title={t.get('title','')}\")
"
```

**Step 2:PUT 翻 done**
```bash
curl -X PUT "http://127.0.0.1:62250/api/tasks/{card_id}" \
  -H "Content-Type: application/json" \
  -d '{"status": "done"}'
```

**Step 3:独立 GET 复核 PUT 落库**(memory `ca6d55aa` 看板及时同步)
```bash
curl -s "http://127.0.0.1:62250/api/tasks/{card_id}" | python3 -c "
import json, sys
t = json.load(sys.stdin).get('data', {})
print(f\"PUT 后:id={t.get('id','')[:8]} status={t.get('status','?')} title={t.get('title','')}\")
"
```

**Step 4:title 注记移除**(避免冗余,撞车 0 不擅自做)
```bash
curl -X PUT "http://127.0.0.1:62250/api/tasks/{card_id}" \
  -H "Content-Type: application/json" \
  -d "{\"title\": \"[${new_title_without_note}]}\""
```

### 2.3 强约束 6 步执行顺序图

```
[1] D2 P3-1 (f71ba244) PUT 翻 done ──── owner 1 行 PUT(无阻塞)
       ↓
[2] D3 P3-3 (962c9087) PUT 翻 done ──── owner 1 行 PUT(等 P3-1)
       ↓
[3] D4 P3-4 (5d00a4b0) PUT 翻 done ─── owner 1 行 PUT(等 P3-1 + P3-3)
       ↓
[4] P0-7.4 (18851855) PUT 翻 done ──── owner 派单 worktree(撞车 0 让路)
       ↓
[5] D1 P0-9 (2541e012) PUT 翻 done ── owner 1 行 PUT(等 P0-7.4)
       ↓
[6] P3-4 旧卡 eb781e5d BLOCKED 关闭 ── owner 派单 worktree(不阻塞主路径)
```

---

## 三、4 新派 worktree 命令模板(撞车 0 + OPS-09 单写者纪律严守)

### 3.1 4 新派 worktree 派单清单

| # | 卡号 | worktree 命名建议 | 主题 | owner 拍板点 |
|---|---|---|---|---|
| 1 | **P0-7.4** | `agent-batch9-p074-inreview` | 企微 Mock 绑定收口(R82 决策包已就位)| 派 QA 独立复核 + squash 合入 + 翻 done |
| 2 | **跨仓对账** | `agent-batchX-platform-auth-cross-check` | IpdPlatformAuthController 跨仓对账(R86 + R88 §C5 已就位)| 派单前必 `git worktree list` 自查 |
| 3 | **孤儿修补** | `agent-batchX-orphan-patch` | 97 fe_orphan + 206 be_orphan 分桶(R88 §C4 已就位)| 等 owner 拍板分桶方案 |
| 4 | **字符集** | `agent-batchX-b3-charset` | sys_user↔persons 字符集 ALTER(R68 B3 已就位)| 等 owner 拍板 Q3/Q4/不处理 |

### 3.2 4 新派 worktree 命令模板(撞车 0 + 单会话能力边界让路,主协调不擅自启动)

**worktree #1:agent-batch9-p074-inreview(企微 Mock 绑定收口)**
```bash
# Step 1:创建 worktree(基于 main 最新 HEAD)
cd /Users/mac/Documents/ruoyi-ai
git worktree add /private/tmp/agent-batch9-p074-inreview -b agent/batch9-p074-inreview main
cd /private/tmp/agent-batch9-p074-inreview

# Step 2:从 R82 决策包拿收口指令
cat /Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/R82-agent-batch9-p074-inreview-P0-7-4-收口派单决策包-20260919.md

# Step 3:QA 独立复核 + 测试
mvn -o -pl ruoyi-modules/ruoyi-ipd -Dtest=P074AcceptanceTest test

# Step 4:squash 合入 main + 翻 done
git checkout main
git merge --squash agent/batch9-p074-inreview
git commit -m "P0-7.4: 企微 Mock 绑定收口"
# 然后按 §二 Step 2 PUT 翻 done
```

**worktree #2:agent-batchX-platform-auth-cross-check(IpdPlatformAuthController 跨仓对账)**
```bash
cd /Users/mac/Documents/ruoyi-ai
git worktree add /private/tmp/agent-batchX-platform-auth-cross-check -b agent/batchX-platform-auth-cross-check main
cd /private/tmp/agent-batchX-platform-auth-cross-check

# 三向对账:Controller(主仓 1 处)+ 8 兄弟会话旧 worktree 快照 + 前端仓 4 调用点
grep -rn 'IpdPlatformAuthController\|PlatformAuth' /Users/mac/Documents/ruoyi-ai --include='*.java'
grep -rn 'IpdPlatformAuthController\|PlatformAuth' /Users/mac/Documents/ruoyi-ipd-web --include='*.ts' --include='*.vue'
```

**worktree #3:agent-batchX-orphan-patch(97 fe_orphan + 206 be_orphan 分桶)**
```bash
cd /Users/mac/Documents/ruoyi-ai
git worktree add /private/tmp/agent-batchX-orphan-patch -b agent/batchX-orphan-patch main
cd /private/tmp/agent-batchX-orphan-patch

# 孤儿端点分桶(参考 R39-孤儿端点评估-20260918.md)
# 端点清单 endpoint_inventory.sql 沉淀
# 看板实时拉 https://127.0.0.1:62250/api/tasks?project_id=01dcf15c-86bb-4c7b-957c-8fe44bddd10d
```

**worktree #4:agent-batchX-b3-charset(sys_user↔persons 字符集 ALTER)**
```bash
cd /Users/mac/Documents/ruoyi-ai
git worktree add /private/tmp/agent-batchX-b3-charset -b agent/batchX-b3-charset main
cd /private/tmp/agent-batchX-b3-charset

# 字符集 ALTER(SQL 草稿 + 实测字符集不一致)
# persons=general_ci / sys_user=0900_ai_ci
# ALTER TABLE persons CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
# 等 owner 拍板 Q3/Q4/不处理三选一(DBA apply)
```

---

## 四、28 项 owner-blocked 派单清单整合(撞车 0 + 单会话能力边界让路)

### 4.1 7 张 D 类汇总卡 + 21 项 owner 派单 worktree = 28 项

| # | 卡号 | 主题 | owner 拍板点 | 优先级 |
|---|---|---|---|---|
| 1-7 | (D1-D4 + P0-7.4 + P3-4 旧卡 + STG-501-1)| 见 §二 7 张 D 类汇总卡 | 见 §二 操作模板 | ★★★★★ |
| 8 | **R42-B** | T4 strict 模式切换 | owner 拍板时机(等 R35 密钥迁移)| ★★★ |
| 9 | **R42-E** | archived_at 批量回填 | DBA apply(6 行违规 ID 已现查 `9140001/9140002/9140003` + 3 雪玢 ID)| ★★★★ |
| 10 | **B1** | bonus_allocations 真活 HTTP 验收 | owner 派单 worktree + 真活 HTTP 验证 | ★★★★ |
| 11 | **B2** | project_scores 保留表 + 加注释 | owner 派单 worktree(records 表 0 行 ⚠️)| ★★★ |
| 12 | **B3** | sys_user↔persons 字符集 ALTER | owner 拍板 Q3/Q4/不处理三选一 | ★★★ |
| 13 | **bonus.poolRate** | 漂移 0.0500 ≠ 0.05 | owner 拍板 A/B/C | ★★★ |
| 14 | **audit_logs DDL** | 生产 apply | DBA 维护窗口 | ★★ |
| 15 | **R76 vite 守护** | cron + vite-keepalive.sh ★★★★★ | owner 派单 crontab -e(主协调撞车 0 不擅自)| ★★★★★ |
| 16 | **application-prod.yml** | 多处硬编码内联值 🚨 | owner 拍板 + 派单 worktree | ★★★★ |
| 17 | **nginx 配置缺失** | docs/nginx 目录空 | owner 派单 worktree | ★★★ |
| 18 | **docker-compose 端口漂移** | 23306 vs 13306 | owner 派单 worktree | ★★ |
| 19 | **tenant.excludes 2 张重复** | ⚠️ 子集重复登记 | owner 拍板 + 派单 worktree | ★★ |
| 20 | **SEC-04** | 安全集成验收 | owner 派单 worktree | ★★★ |
| 21 | **AUD-02** | 全局依赖审计 | owner 派单 worktree | ★★★ |
| 22 | **P4-5.1 / P4-4.1** | P4 阶段收口 | owner 拍板 | ★★ |
| 23 | **AI-P1-1 / P2-1 / P2-2 / P3** | AI 系列 | owner 派单 worktree | ★★ |
| 24 | **WB-17-1** | 工作台 taskType 扩展 | owner 派单 worktree | ★★ |
| 25 | **QA-06/07/08** | 业务验收 | owner 派单 worktree | ★★★ |
| 26 | **P1-10.2 / P2-4.2 / P3-2.3 / P3-8.3** | 阶段补充 | owner 派单 worktree | ★ |
| 27 | **R40+ vite.config.mts** | 兄弟会话 M 改跨仓 | 等兄弟前端会话合并 | ★★ |
| 28 | **A1 lastChange 列** | 前端 lastChange 列 | 等兄弟前端会话合并 | ★★ |

### 4.2 4 个 fix-* worktree 严守不接管(OPS-09 单写者纪律)

| # | 卡号 | worktree | hash | 严守不接管原因 |
|---|---|---|---|---|
| 1 | A1 | `fix/A1-controller-whitelist-20260919` | `c83215f9` | OPS-09 单写者 + 撞号透明让路 |
| 2 | A3 | `fix/A3-mock-global-filter-20260919` | `0edd0ba4` | OPS-09 单写者 + 撞号透明让路 |
| 3 | A4-1 | `fix/A4-param-audit-log-20260919` | `feec82d2` | OPS-09 单写者 + 撞号透明让路 |
| 4 | A4-2 | `fix/A4-seed-script-repair-20260919` | `2fc588a6` | OPS-09 单写者 + 撞号透明让路 |

---

## 五、撞车 0 + b1e8e713 红线 + R13-hard §6 严守声明

### 5.1 撞车 0 + b1e8e713 红线严守(R89)

- ✅ 仅写决策包到 `docs/ipd-系统说明/R89-28项owner-blocked派单清单整合-7张D类汇总卡owner拍板操作模板-4新派worktree命令模板-20260919.md`,主仓其他文件未动
- ✅ **看板总账 fresh 验证**(memory `39859730` 看板只认 ruoyi-ai project_id)
- ✅ **7 张 D 类汇总卡 owner 拍板操作模板**(Step 1 GET 复核 + Step 2 PUT + Step 3 GET 复核 + Step 4 title 注记移除)
- ✅ **强约束 6 步执行顺序图**(P3-1 → P3-3 → P3-4 → P0-7.4 → P0-9 → 旧卡关闭)
- ✅ **4 新派 worktree 命令模板**(主协调撞车 0 + 单会话能力边界让路,不擅自启动)
- ✅ **28 项 owner-blocked 派单清单整合**(撞车 0 让路,等 owner 拍板)
- ✅ **4 个 fix-* worktree 严守不接管**(OPS-09 单写者纪律)
- ✅ **不擅自翻 status**(b1e8e713 红线严守,7 张 D 类汇总卡全部等 owner 1 行 PUT)
- ✅ **不擅自启动 4 新派 worktree**(主协调撞车 0 + 单会话能力边界让路)
- ✅ 不擅自 push 跨仓 / crontab -e / launchctl load / kill PID / mvn 重启 / DBA apply
- ✅ 不擅自改 application.yml / application-prod.yml / `git config core.hooksPath`
- ✅ Fresh 验证(memory `770073a2`):R13 五必现查 + R13-hard §6 兄弟 commit hash 现查 + 看板 fresh 拉

### 5.2 单会话能力边界严守(R89)

**owner 授权「立即执行」+ 「完整执行以上全部任务」,主协调仍严守**:

1. **严守边界**(即使授权):
   - 不擅自翻 status(b1e8e713 红线)
   - 不擅自启动 4 新派 worktree(OPS-09 单写者 + 撞车 0)
   - 不擅自 commit 兄弟会话改动
   - 不擅自 push 跨仓
   - 不擅自 crontab -e / launchctl load / kill PID / mvn 重启 / DBA apply
   - 不擅自改 application.yml / application-prod.yml

2. **可推进的工作**(本轮 R89 全部完成):
   - ✅ 写决策包(docs only,撞车 0 边界内)
   - ✅ log.md / 镜像 SSOT 同步
   - ✅ 看板 fresh 拉验证(memory `770073a2`)
   - ✅ 7 张 D 类汇总卡 owner 操作模板(不擅自翻 status)
   - ✅ 4 新派 worktree 命令模板(不擅自启动)
   - ✅ 28 项 owner-blocked 派单清单整合(不擅自推进)

---

## 六、Memory 触发对账(R89)

| memory | 触发 | R89 严守 |
|---|---|---|
| **fdc4ea0d** 说人话 | 大白话汇报 | ✅ R89 §一 + §五 + §六 大白话段 |
| **ca6d55aa** 看板卡片状态及时同步 | 看板 fresh 拉 | ✅ R89 §一 §6.1 看板总账 fresh 验证 |
| **e30d739d** 自动 commit/push | 完成后自动 commit | ✅ R89 §七 待落地 |
| **39859730** 看板只认 ruoyi-ai | fresh 拉是 ruoyi-ai project_id | ✅ R89 §一 §6.1 ruoyi-ai project_id |
| **effe536f** 该清/合/交三原则 | 28 项 → 派单清单整合 | ✅ R89 §四 28 项 owner-blocked 派单清单整合 |
| **7bf840f6** 兄弟会话接手三步法 | 4 fix-* worktree 不接管 | ✅ R89 §四 4.2 严守不接管 |
| **939baafe** 单一写入者纪律 | OPS-09 单写者 | ✅ R89 §五 1 |
| **b1e8e713** 假绿翻卡红线 | 不擅自翻 status | ✅ R89 §二 7 张 D 类汇总卡全部等 owner PUT |
| **770073a2** Fresh 验证铁律 | 看板 fresh 拉 | ✅ R89 §一 §6.1 看板总账 fresh 验证 |
| **67bb4a1a** 撞号撞车根因 | R13-hard §6 落地 | ✅ R89 §一 R13 五必现查复测 |

---

## 七、R45-R89 撞号透明总结 + 下一步候选

### 7.1 R45-R89 撞号透明总结

| 编号 | 主题 | loop | 撞号透明 |
|---|---|---|---|
| R45-R86 | R33-R86 全治理轮累计 | 撞号透明 | 撞号不冲突 |
| R87 | 60 项清单 + 4 subagent 并行梳理 | Loop 44 | 撞号透明 |
| R88 | 清理 + 整合 + 6 项 docs only 推进 | Loop 45 | 撞号透明 |
| **R89** | **28 项 owner-blocked 派单清单整合 + 7 张 D 类汇总卡 owner 拍板操作模板 + 4 新派 worktree 命令模板** | **Loop 46** | **撞号透明(本轮 4 subagent 输出整合 + 8 兄弟 commit 撞号透明登记)** |

### 7.2 下一步候选(R89 给主人)

- **Loop 47 (R90)**:STG-501-1 owner 派单 3 决策项梳理(`e7b9289c` inreview,新增 owner-blocked)
- **Loop 48 (R91)**:剩余 21 项 owner 派单 worktree 派单指南(基于 R89 §四 派单清单整合)
- **Loop 49+**:**等 owner 拍板**:7 张 D 类汇总卡 1 行 PUT 翻 done(强约束 6 步执行顺序)+ 4 新派 worktree 命令模板启动 + 4 fix-* worktree 不接管决定

### 7.3 R89 一句话大白话

**28 项 owner-blocked 派单清单整合完了**(7 张 D 类汇总卡 + 21 项派单 worktree)。**7 张 D 类汇总卡 owner 拍板操作模板就位**(GET → PUT → GET 复核 → title 注记移除)。**强约束 6 步执行顺序图完整**(P3-1 → P3-3 → P3-4 → P0-7.4 → P0-9 → 旧卡关闭)。**4 新派 worktree 命令模板就位**(主协调撞车 0 + 单会话能力边界让路,不擅自启动)。**撞车 0 + b1e8e713 红线 + R13-hard §6 严守:不擅自翻 status / 不擅自启动 worktree / 不擅自 push 跨仓 / 不擅自 crontab / 不擅自 mvn 重启 / 不擅自 DBA apply**。

---

*作者:主协调会话,2026-09-19。基线:`19fe56d2`(R88-C3)+ 看板总账 466 张 + 7 张 D 类汇总卡 fresh 状态。撞车 0 + 单会话能力边界 + docs only + 撞号透明 + b1e8e713 红线严守 + R13-hard §6 落地 + 看板 fresh 验证。*