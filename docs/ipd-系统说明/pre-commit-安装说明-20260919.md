# R123 pre-commit / post-commit hook 安装说明 — R119 病根 #1 #2 常态化

> 日期: 2026-09-19  
> 链路号: R123  
> 关联 commit: a34a0002 (R119 5 脚本) → 待落 R123 commit  
> OPS-09: 撞车 0 严守,不动 R30+ 治理 hook

## 1. 目标

把 R119 病根 #1(测试覆盖率按域)和病根 #2(提交完整度)分别接 pre-commit / post-commit,从"靠自觉"升级到"靠机制常态化执行"。

## 2. 涉及文件

| 文件 | 角色 | OPS-09 白名单 |
|---|---|---|
| `.claude/hooks/pre-commit-coverage.sh` | C2 hook 源码(阻断型) | ✅ .claude/hooks/* |
| `.claude/hooks/post-commit-completeness.sh` | C3 hook 源码(通知型) | ✅ .claude/hooks/* |
| `scripts/install-coverage-pre-commit.sh` | C2 一键安装(默认 --dry-run) | ✅ scripts/* |
| `scripts/install-completeness-post-commit.sh` | C3 一键安装(默认 --dry-run) | ✅ scripts/* |
| `scripts/check-test-coverage-by-domain.sh` | R119 病根 #1 主脚本 | ✅ scripts/* |
| `scripts/check-commit-completeness.sh` | R119 病根 #2 主脚本 | ✅ scripts/* |

## 3. C2 pre-commit-coverage 安装(阻断型)

### 当前 hook 架构(已 `git config core.hooksPath = .claude/hooks`)

- 真触发位:`.claude/hooks/<hook-name>`
- 链路:`.claude/hooks/pre-commit` (wrapper) → `.claude/hooks/check-pre-commit.sh` (R43-α 调度)
- 安装 C2 目标:`.claude/hooks/pre-commit.d/coverage.sh`(在调度子目录,不撞 main wrapper)

### 行为
- 若 staged 文件含 `.java` → 跑覆盖率门禁 → 不达标 exit 1 阻断
- 若 staged 全是 governance(`.claude/` `docs/ipd-系统说明/治理/` `scripts/` `AGENTS.md` 等)→ exit 0 不阻
- 若 hook 源被 owner 临时绕过:`SKIP_COVERAGE_GATE=1 git commit`

### 安装步骤

```bash
cd /Users/mac/Documents/ruoyi-ai

# dry-run 预览
bash scripts/install-coverage-pre-commit.sh --dry-run

# 真安装(创建 .claude/hooks/pre-commit.d/coverage.sh)
bash scripts/install-coverage-pre-commit.sh --install

# 验证
bash scripts/install-coverage-pre-commit.sh --status
```

### ⚠️ 重要前提

.claude/hooks/pre-commit (由 core.hooksPath 指定) 当前是 wrapper(由 R30+ 治理设定),**不会自动调度 `pre-commit.d/*.sh`**。

要让本 hook 真触发,owner 需三选一:

**a)** 在 `.githooks/pre-commit` 末尾追加调度逻辑(推荐,需 OPS-09 授权改 .githooks):
```bash
# 在 .githooks/pre-commit 末尾追加:
for h in ".claude/hooks/pre-commit.d/"*.sh; do
  [ -x "$h" ] && bash "$h" "$@"
done
```

**b)** 修改 `git config core.hooksPath`(本次不自动改):
```bash
git config core.hooksPath .githooks
```

**c)** 临时绕过:`SKIP_COVERAGE_GATE=1 git commit`(R30+ 治理允许)

## 4. C3 post-commit-completeness 安装(通知型)

### 行为
- 每次 commit 完成后跑完整度门禁
- 失败只打印警告,**不阻断**(commit 已发生)
- 报告落到 `docs/ipd-系统说明/提交完整度-YYYYMMDD-HHMMSS.md`

### 安装步骤

```bash
cd /Users/mac/Documents/ruoyi-ai

# dry-run 预览
bash scripts/install-completeness-post-commit.sh --dry-run

# 真安装(创建 .claude/hooks/post-commit.d/completeness.sh)
bash scripts/install-completeness-post-commit.sh --install

# 验证
bash scripts/install-completeness-post-commit.sh --status
```

### ⚠️ 重要前提

.claude/hooks/post-commit (由 core.hooksPath 指定) 已存在(R43-α 设置),**不会自动调度 `post-commit.d/*.sh`**。

调度逻辑由 owner 手动追加(同 C2 方案)。

## 5. OPS-09 撞车 0 严守清单

- [x] 不动 `scripts/pre-commit-main-tree-block.sh`(R30+ 治理源)
- [x] 不动 `.claude/hooks/check-pre-commit.sh`(R43-α wrapper (`.claude/hooks/check-pre-commit.sh`))
- [x] 不动 `.githooks/pre-commit` `.githooks/post-commit`
- [x] 不动 .claude/hooks/pre-commit (由 core.hooksPath 指定) .claude/hooks/post-commit (由 core.hooksPath 指定)
- [x] 默认 `--dry-run`,不真改 `.claude/hooks/pre-commit.d/` .claude/hooks/post-commit.d/
- [x] hook 源码放 `.claude/hooks/`(OPS-09 白名单,可主仓直写)

## 6. 一键命令汇总

```bash
# C2 dry-run
bash scripts/install-coverage-pre-commit.sh --dry-run

# C3 dry-run
bash scripts/install-completeness-post-commit.sh --dry-run

# C2 status
bash scripts/install-coverage-pre-commit.sh --status

# C3 status
bash scripts/install-completeness-post-commit.sh --status

# owner 临时绕过覆盖率门禁
SKIP_COVERAGE_GATE=1 git commit ...

# owner 临时绕过完整度检查
SKIP_COMPLETENESS=1 git commit ...
```

## 7. 撞车规避备忘

| 已存在 | 角色 | 本次是否动 |
|---|---|---|
| `scripts/pre-commit-main-tree-block.sh` | R30+ 治理源 | ❌ 不动 |
| `.claude/hooks/pre-commit` | wrapper(→check-pre-commit.sh),core.hooksPath 指定 | ❌ 不动 |
| `.claude/hooks/check-pre-commit.sh` | R43-α 多门禁调度 | ❌ 不动 |
| `.githooks/pre-commit` | 实际执行入口 | ❌ 不动 |
| .claude/hooks/pre-commit (由 core.hooksPath 指定) | git 触发位 | ❌ 不动 |
| `.claude/hooks/post-commit-update-kanban.cjs` | 已存在 | ❌ 不动 |
| `.githooks/post-commit` | post-commit 入口 | ❌ 不动 |
| .claude/hooks/post-commit (由 core.hooksPath 指定) | git 触发位 | ❌ 不动 |

## 8. 相关文档

- 主交付:`docs/ipd-系统说明/R123-cron+pre-commit-hook常态化-R119-5脚本-20260919.md`
- cron 配套:`docs/ipd-系统说明/cron-安装说明-20260919.md`
- 关联:R119 `a34a0002` commit message
