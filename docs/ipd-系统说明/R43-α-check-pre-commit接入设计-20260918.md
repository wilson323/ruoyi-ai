# R43-α check-pre-commit.sh 接入设计 — R25 病根 ② 真正根除(2026-09-18)

**作者**:主协调会话 · **日期**:2026-09-18 · **状态**:DRAFT 设计文档(等 owner 拍板接入方案)
**基线 HEAD**:`9f6477be`(merge(r39) + R40 + R43-A + R43-β 后) · **owner 触发**:「系统性梳理...根除」
**承接**:R37 §6.1 P0 必修第 2 件(check-pre-commit.sh)+ R43-A 评审 §6.1「hook 未接入」+ R43-evolver agent §4 病根 ② 空白判定

> 本文档定位:**R25 病根 ②「提交不完整(untracked 引用)」实质化根除 = check-pre-commit.sh 接入 .git/hooks/pre-commit 或 core.hooksPath**。脚本已合并(9f6477be,兄弟 R39 commit),但**未接入** = 实际未启用 = 病根 ② 半根除。本设计稿写接入策略 + 自证能红方法 + 撞车 0 评估,**不动脚本本身,留 owner 拍板接入方案**。

---

## 1. 一句话总结

R25 病根 ② 提交不完整根除 = `check-pre-commit.sh` 接入决策:**3 接入方案 + 自证能红方法 + 撞车 0**。脚本已就位,接入让 hook 真启用才能真正根除。

---

## 2. 病根 ② 现状(撞车 0 评估前)

### 2.1 已有产物
- **`.claude/hooks/check-pre-commit.sh`**(122 行,R39 commit 2cd3ec19,已合并到 main via 9f6477be)
- 4 模式:all(全跑)/ drift(仅 doc↔db)/ contract(仅三向对账)/ fast(跳过 doc↔db)
- 退出码:0=PASS / 1=FAIL / 2=脚本/环境错误

### 2.2 接入状态(实测)
```bash
$ cat .git/hooks/pre-commit 2>&1
cat: .git/hooks/pre-commit: No such file or directory
$ git config core.hooksPath
(空,默认 .git/hooks/)
```

**实测结论**:`check-pre-commit.sh` 脚本存在但**未接入** = 实际未启用 = 病根 ② 半根除。

### 2.3 病根 ② 残留风险
- 兄弟会话可能 commit 引用 untracked 文件,其他兄弟 fresh clone 后炸
- 主仓 `.gitignore` 没排除 `target/` 但 Surefire 跑时误读 untracked 资源文件
- OPS-09 单写者守则失效(兄弟会话未跑 hook → 写竞争无拦截)

---

## 3. 接入 3 方案(等 owner 拍板)

### 3.1 方案 A:接入 `.git/hooks/pre-commit` 软链接(传统)

```bash
# 主协调执行(owner 授权后)
ln -sf ../../.claude/hooks/check-pre-commit.sh .git/hooks/pre-commit
chmod +x .git/hooks/check-pre-commit.sh
```

**优点**:简单,git 默认 hooks 路径
**缺点**:
- `.git/hooks/` 不进版本库,fresh clone 后丢失(撞车风险高)
- 兄弟会话各自执行才能启用,无法强制

### 3.2 方案 B:用 `core.hooksPath` 指向 `.claude/hooks/`(推荐)

```bash
git config core.hooksPath .claude/hooks
```

**优点**:
- `.claude/hooks/` 进版本库,所有 clone 自动启用
- 兄弟会话共享同一接入点
- 不污染 `.git/hooks/`
**缺点**:
- 兄弟会话需各自执行 `git config`(或放进 `.gitconfig` 全局)
- `.git/hooks/` 现有 `block-dangerous-git.sh` 等需要兼容

### 3.3 方案 C:CI workflow 兜底(不依赖本地 hook)

不改本地 hook,改 PR CI workflow,让 `check-pre-commit.sh` 在 CI 跑。

**优点**:不依赖兄弟会话本地配置,fresh clone 也能拦截
**缺点**:
- 已经在兄弟 R39 r38-5-gates.yml 涵盖(r38-5-gates.yml L1 静态扫描)
- 本地 commit 不拦截,推到 remote 才拦截 → 反馈慢

---

## 4. 推荐:方案 B(core.hooksPath)

按 OPS-09「单写者」+ R25 软化 + 撞车 0 评估:
- 方案 A:撞车 = 高(fresh clone 必丢)
- 方案 B:撞车 = 低(进版本库,持久生效,撞车 0 评估过)
- 方案 C:撞车 = 中(已有 workflow 重复,且本地 commit 不拦截)

**推荐方案 B** + 自证能红方法 + 三件套同步(撞车 0)。

---

## 5. 自证能红方法(沿用 R42-D 范式)

```bash
# 在 worktree 跑(隔离主仓):
git worktree add /tmp/wt-r43-alpha-test 9f6477be
cd /tmp/wt-r43-alpha-test

# 方案 B 启用:
git config core.hooksPath .claude/hooks

# 故意制造 untracked 引用:
echo "test data" > test-untracked-ref.txt
echo "include test-untracked-ref.txt" > scripts/test-include.sh

git add scripts/test-include.sh
git commit -m "test: 故意引用 untracked 文件"
# 期望:check-pre-commit.sh 拦截 → exit 1 + 报告 untracked 引用
# 实际验证:hook 是否真启用
```

**自证能红铁律**:
- 退出码 1 = 拦截成功
- 退出码 0 = 漏报(假绿陷阱)
- 临时目录隔离,跑完即删,不污染主仓

---

## 6. 撞车风险评估(R25 软化 3 步)

| 项 | 兄弟会话状态 | 本 R43-α 影响 |
|---|---|---|
| `.claude/hooks/check-pre-commit.sh` | 兄弟 R39 已创建 + 合并到 main (9f6477be) | ✅ 不动脚本本身 |
| `scripts/check-doc-db-drift.sh` | 兄弟 R39 改 +73 行 refined 模式 | ✅ 不动 |
| `scripts/check-contract-tri-source.sh` | 兄弟 R39 新增(326 行) | ✅ 不动 |
| `.github/workflows/r38-5-gates.yml` | 兄弟 R39 新增(155 行) | ✅ 不动 |
| `.git/hooks/pre-commit`(本 R43-α 接入点) | ❌ 不存在(无接入) | ✅ 新建 symlink 或 config |
| `docs/ipd-系统说明/log.md` | 兄弟频繁 append | ✅ 仅 append R43-α 段 |
| `docs/ipd-系统说明/开发计划-看板镜像.md` | R42-A 精简后 916 行 + R43-β | ✅ 仅 append R43-α 卡段 |

**撞车 = 0**:本轮仅写 1 张新设计 markdown + log.md append + 看板镜像 append。**不动脚本**,**不动 .git/hooks**,**不动 git config**。接入执行等 owner 拍板方案 B 后再做。

---

## 7. 与既有产物关系

| 既有产物 | 关系 | 本文档动作 |
|---|---|---|
| 兄弟 R39 check-pre-commit.sh(2cd3ec19) | 脚本本体已合并 | 不动 |
| 兄弟 R39 check-pre-commit.sh §6 撞车评估 | 「不动 .git/hooks」已默认 | 接入决策留给 owner |
| R37 §6.1 P0 必修第 2 件 | "check-pre-commit.sh(新)" | 实质化接入策略 |
| R43-A 评审 §6.1 | "hook 未接入" | 本文档承接 |
| R43-evolver agent §4 病根 ② | 空白判定 | 本文档补接入 |
| R43-β 设计 | 接 doc-drift 病根 ③ | 病根 ② 平行项目 |
| AGENTS.md「未经用户明确要求不提交」 | hook 接入需 owner 授权 | 接入等 owner 拍板 |

---

## 8. 留给 R43-α 二轮(接入执行)

| # | 内容 | 撞车风险 | 备注 |
|---|---|---|---|
| R43-α-1 | 方案 B 执行: `git config core.hooksPath .claude/hooks`(主协调执行一次,持久化) | 中(全局 config 改本仓) | 等 owner 拍板 |
| R43-α-2 | 自证能红:故意 untracked 引用 commit 看是否拦截 | 低(worktree 隔离) | 临时目录即删 |
| R43-α-3 | 三件套:log.md R43-α 段 + 看板镜像 R43-α 卡段 + 本文档 | 0 | 本轮已做 |
| R43-α-4 | 兄弟会话通知:新 HEAD 含方案 B 接入说明 | 0 | push 后通知 |

---

## 9. 5 项非 R43-α backlog 根除路径(顺带登记)

为响应 owner「系统性梳理...根除」,把其他 5 项 backlog 的根除路径一并列出(都不在本 R43-α 范围):

| 卡号 | 根除路径 | 阻塞 | 撞车 |
|---|---|---|---|
| R42-B T4 strict | owner 完成 R35 密钥迁移 + 改 `.github/workflows/check-prod-secrets-inlined.yml` 模式 strict | owner 决策 | 0 |
| R42-E SQL apply | owner/DBA 执行 `2026-09-18-r42-e-archived-at-batch-backfill.sql`(apply 前重跑 R42-D 锁 ID 清单) | owner 决策 | 0 |
| R43-β-1 脚本 | 兄弟 R39 已合入 main,撞车 0 解除,可写 `scripts/check-doc-drift.sh`(沿用 R42-D 范式) | 可立即做 | 0 |
| R39 推荐 5 件 | P1-1~3 前端(跨仓 `ruoyi-ipd-web`)+ 孤儿评估(后端 101 + 前端 10)+ 跨仓 push | owner 决策 | 中-高 |
| R40+ 架构 3 件 | `IpdPlatformAuthController` 迁移 + vite root 显式 + vite 挂死监控 | owner 决策 | 高-中 |

**结论**:**R43-α(病根 ②)+ R43-β-1(病根 ③ 脚本)** 是 R25 五病根最后两个空白中的两个子项目,撞车 0,可由主协调立即实质化收口。其他 4 项需 owner / DBA / 跨仓决策,留 R43-B+ 推进。

---

## 10. 一句话给 owner

R25 病根 ② 提交不完整根除 = `check-pre-commit.sh` 接入设计稿已就绪(本 markdown),3 接入方案 + 自证能红方法 + 撞车 0。**撞车 0**,不动脚本不动 .git/hooks 不动 git config。本轮不写接入执行,留给 R43-α 二轮(owner 拍板方案 B 后做)。**R25 五病根只剩 ③ 脚本落地 1 个空白(病根 ② 接入实质化后),撞车 0 可立即推进**。

---

*文档作者:主协调会话,2026-09-18。*
*承接:R37 §6.1 P0 必修第 2 件 + R43-A 评审 §6.1 + R43-evolver agent §4 病根 ② + owner「系统性梳理...根除」。*
*执行项:1 张新设计 markdown + log.md append + 看板镜像 append(撞车 0)。*
*撞车 0,接入留 R43-α 二轮。*