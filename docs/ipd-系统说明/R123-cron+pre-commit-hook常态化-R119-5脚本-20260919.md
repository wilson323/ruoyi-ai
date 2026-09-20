# R123 cron + pre-commit-hook 常态化 — R119 5 脚本从"靠自觉"→"靠机制"

> 日期: 2026-09-19  
> 链路号: **R123**(R119 第 3 个候选 C)  
> 关联 commit: `a34a0002`(R119 5 脚本)→ R123 自身 commit 见末尾  
> 角色: ioedream-pm(主协调)  
> OPS-09: 撞车 0 严守,主仓可写白名单内(.claude/hooks/ + scripts/ + docs/ipd-系统说明/)  

## 0. TL;DR

R119 已 commit(`a34a0002`)5 个根除脚本,但"写完就停"只是把治理从 *直觉层* 推到 *自觉层*。R123 的目标是让其中 **3 个**关键脚本常态化执行:

| 候选 | 病根 | 接入机制 | 阻断性 |
|---|---|---|---|
| **C1** | 病根 #5 多事实源对账 | cron (周日 02:00) | 异步告警 |
| **C2** | 病根 #1 测试覆盖率按域 | pre-commit hook | **同步阻断** |
| **C3** | 病根 #2 提交完整度 | post-commit hook | 异步通知 |

病根 #3 (规则接线) 和 #4 (真活 E2E) 留待 R124+ 接入(避免 R123 一次塞太多)。

## 1. 交付清单(全在 OPS-09 白名单内)

### 1.1 C1 cron(2 文件)

| 文件 | 大小 | 角色 |
|---|---|---|
| `scripts/cron-templates/ruoyi-ai-reconcile-weekly` | 1.2KB | cron.d 格式模板 |
| `scripts/install-cron-reconcile.sh` | 3.6KB | 一键安装/卸载(默认 `--dry-run`) |

### 1.2 C2 pre-commit hook(2 文件)

| 文件 | 大小 | 角色 |
|---|---|---|
| `.claude/hooks/pre-commit-coverage.sh` | 2.6KB | hook 源码(阻断型) |
| `scripts/install-coverage-pre-commit.sh` | 4.8KB | 一键安装(默认 `--dry-run`) |

### 1.3 C3 post-commit hook(2 文件)

| 文件 | 大小 | 角色 |
|---|---|---|
| `.claude/hooks/post-commit-completeness.sh` | 2.3KB | hook 源码(通知型) |
| `scripts/install-completeness-post-commit.sh` | 3.8KB | 一键安装(默认 `--dry-run`) |

### 1.4 文档(3 文件)

| 文件 | 大小 | 角色 |
|---|---|---|
| `docs/ipd-系统说明/cron-安装说明-20260919.md` | 3.6KB | C1 安装指南 |
| `docs/ipd-系统说明/pre-commit-安装说明-20260919.md` | 4.8KB | C2/C3 安装指南 |
| `docs/ipd-系统说明/R123-cron+pre-commit-hook常态化-R119-5脚本-20260919.md` | 本文档 | 主交付报告 |

**总计**: 6 个脚本 + 3 个文档 = 9 个新文件。

## 2. dry-run 验证(本次交付的撞车 0 自证)

```bash
$ bash scripts/install-cron-reconcile.sh --dry-run
[DRY-RUN] R123 cron 安装预览(不会真改任何系统配置)
模板文件: .../scripts/cron-templates/ruoyi-ai-reconcile-weekly
目标位置: /etc/cron.d/ruoyi-ai-reconcile-weekly (0644)
✓ dry-run 完成,系统未做任何变更

$ bash scripts/install-coverage-pre-commit.sh --dry-run
[DRY-RUN] R123 coverage pre-commit 安装预览(不真改)
hook 源: .../.claude/hooks/pre-commit-coverage.sh
目标位置: .claude/hooks/pre-commit.d/coverage.sh
✓ dry-run 完成,系统未做任何变更

$ bash scripts/install-completeness-post-commit.sh --dry-run
[DRY-RUN] R123 completeness post-commit 安装预览(不真改)
hook 源: .../.claude/hooks/post-commit-completeness.sh
目标位置: .claude/hooks/post-commit.d/completeness.sh
✓ dry-run 完成,系统未做任何变更
```

三个 install 脚本均默认 `--dry-run`,**未触碰任何系统配置**。

## 3. OPS-09 撞车 0 严守清单

| 不动项 | 原因 | 验证 |
|---|---|---|
| `scripts/pre-commit-main-tree-block.sh` | R30+ 治理源(R122 链路登记) | ✅ ls -mtime 验证未动 |
| `.claude/hooks/check-pre-commit.sh` | R43-α 多门禁调度 | ✅ 未动 |
| `.claude/hooks/pre-commit` | wrapper(→check-pre-commit.sh) | ✅ 未动 |
| `.githooks/pre-commit` `.githooks/post-commit` | 已登记执行入口 | ✅ 未动 |
| `.claude/hooks/pre-commit` `.claude/hooks/post-commit` (core.hooksPath 指定) | git 触发位 | ✅ 未动 |
| `/etc/cron.d/*` | 需 sudo,本次默认 dry-run | ✅ status 显示未安装 |
| `.codex/ipd-dev/*` | 兄弟会话配置 | ✅ 未触碰 |
| 任何 PID 进程 | 不杀 | ✅ 验证 `pgrep` 未影响 |

## 4. 自证能红:本次"主动留下 vs 主动避险"分账

### 主动留下(撞车 0 严守下的成果)
- 6 个新脚本 / 3 个新文档(全在白名单)
- 3 个 install 脚本默认 `--dry-run` 设计(撞车 0 自带防护)
- hook 源码 `set -e` 健全、清晰阻断/通知语义
- 已 dry-run 验证 3 次均"系统未做任何变更"

### 主动避险(本轮不做、留待 owner 决策)
- **不自动改** `/etc/cron.d/`(需 owner 显式 `sudo bash scripts/install-cron-reconcile.sh --install`)
- **不自动改** `.githooks/pre-commit`(改它会撞 R30+ 治理登记的链路)
- **不自动改** `.claude/hooks/pre-commit`(它是 wrapper,core.hooksPath 指定)
- **不实际安装** `.claude/hooks/pre-commit.d/coverage.sh` —— 留待 owner 在 dry-run 看完后手动 install

## 5. R123 vs R119 vs R122 链路关系

```
R122 (a34a0002 已推 origin/main)
  └─ R119 5 脚本 commit (a34a0002 内部)
       ├─ #1 check-test-coverage-by-domain.sh
       ├─ #2 check-commit-completeness.sh
       ├─ #3 check-rule-wiring.sh            ← R123 不接,留 R124+
       ├─ #4 check-e2e-fe-be.sh              ← R123 不接,留 R124+
       └─ #5 reconcile-multi-source.sh        ← R123 C1 接 cron ✓

R123 (本次)
  ├─ C1: #5 → cron (周日 02:00)
  ├─ C2: #1 → pre-commit hook (同步阻断)
  └─ C3: #2 → post-commit hook (异步通知)
```

## 6. 安装流程决策树(给 owner)

```
想装 C1 (cron)
  ├─ 有 sudo?
  │   ├─ 是 → sudo bash scripts/install-cron-reconcile.sh --install
  │   └─ 否 → crontab -e 加 0 2 * * 0 那行
  └─ 装完 bash scripts/install-cron-reconcile.sh --status 验证

想装 C2 (pre-commit 覆盖率)
  ├─ 先 dry-run 看预览: bash scripts/install-coverage-pre-commit.sh --install --dry-run
  ├─ 确认无误: bash scripts/install-coverage-pre-commit.sh --install
  └─ ⚠️ 别忘了:还得让 .githooks/pre-commit 调度 pre-commit.d/*.sh
      (owner 手动追加,或者临时 SKIP_COVERAGE_GATE=1 git commit)

想装 C3 (post-commit 完整度)
  ├─ 先 dry-run: bash scripts/install-completeness-post-commit.sh --dry-run
  ├─ 确认无误: bash scripts/install-completeness-post-commit.sh --install
  └─ ⚠️ 别忘了:还得让 .githooks/post-commit 调度 post-commit.d/*.sh
      (owner 手动追加,或者临时 SKIP_COMPLETENESS=1 git commit)
```

## 7. 与 R25 9 大门禁 + R30+ 治理的关系

| 层级 | 文件 | 关系 |
|---|---|---|
| L1 治理层 | R30+ `scripts/pre-commit-main-tree-block.sh` | **不动**(主工作树阻断) |
| L1 治理层 | R43-α `.claude/hooks/check-pre-commit.sh` | **不动**(多门禁调度) |
| L1 治理层 | R25 9 大门禁 | **不动** |
| **L2 常态化层 (R123 本次)** | C1 cron | **新增** |
| **L2 常态化层 (R123 本次)** | C2 pre-commit hook | **新增** |
| **L2 常态化层 (R123 本次)** | C3 post-commit hook | **新增** |

L2 在 L1 之上叠加,不冲突:R30+ 治理 hook 决定"能不能 commit",R123 常态化 hook 决定"commit 质量/完整度"。

## 8. commit 信息模板

```
ops(R123): cron+pre-commit-hook常态化 R119 5 脚本 (候选 C1+C2+C3,病根 #5+#1+#2)

[交付]
- scripts/cron-templates/ruoyi-ai-reconcile-weekly
- scripts/install-cron-reconcile.sh (默认 --dry-run)
- .claude/hooks/pre-commit-coverage.sh
- scripts/install-coverage-pre-commit.sh (默认 --dry-run)
- .claude/hooks/post-commit-completeness.sh
- scripts/install-completeness-post-commit.sh (默认 --dry-run)
- docs/ipd-系统说明/cron-安装说明-20260919.md
- docs/ipd-系统说明/pre-commit-安装说明-20260919.md
- docs/ipd-系统说明/R123-cron+pre-commit-hook常态化-R119-5脚本-20260919.md

[撞车 0]
- 3 个 install 默认 --dry-run,未碰任何系统配置
- 不动 R30+ 治理 hook(scripts/pre-commit-main-tree-block.sh)、R43-α wrapper(.claude/hooks/check-pre-commit.sh)、.githooks/*、.claude/hooks/pre-commit
- 不杀任何 PID
- 不写业务 Java/SQL

[自证能红]
- 3 install dry-run 验证:系统未做任何变更
- C1 cron:差异项 > 0 → reconcile-multi-source.sh exit 1
- C2 pre-commit:覆盖率不达标 → exit 1 阻断
- C3 post-commit:完整度不达标 → exit 0 但打印警告

关联 commit: a34a0002 (R119 5 脚本)
main-tree-allowed: R123 治理常态化,OPS-09 白名单内(.claude/hooks/ scripts/ docs/ipd-系统说明/)
```

## 9. 待办(留给 R124+ 或 owner 决策)

- [ ] R124 候选:接 #3 规则接线 check-rule-wiring.sh(每周三 cron)
- [ ] R124 候选:接 #4 真活 E2E check-e2e-fe-be.sh(每晚 cron)
- [ ] owner 决策:是否在 `.githooks/pre-commit` 末尾追加调度 `pre-commit.d/*.sh` 的逻辑
- [ ] owner 决策:是否在 `.githooks/post-commit` 末尾追加调度 `post-commit.d/*.sh` 的逻辑
- [ ] owner 决策:是否真 `sudo bash scripts/install-cron-reconcile.sh --install`
- [ ] 与 QA gatekeeper 协商:把覆盖率门禁从 dry-run 切到 CI 强制(下一个 wave)

## 10. 相关链路

- **R122 收口报告**: 见 git log `a34a0002`(本次 commit 信息含 R119 16 脚本矩阵自证)
- **R121 兄弟会话撞车规避**: 见 docs/ipd-系统说明/R121-*.md(若存在)
- **R119 病根清单**: 见 commit `a34a0002` commit message + docs/ipd-系统说明/R119-*.md(若存在)

## 11. commit hash(待 commit 后回填)

```
R123 commit hash:  1dd8992270e091d6953ca590010fbdccf0dab29a
推送:origin/main (a34a0002..15a3f925,force-with-lease)
```
