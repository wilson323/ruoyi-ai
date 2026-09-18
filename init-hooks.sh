#!/usr/bin/env bash
# init-hooks.sh — 一键配置 git core.hooksPath
#
# 目的:R25 病根 ② 「提交不完整(untracked 引用)」实质化根除
# 用法:fresh clone 后跑一次 `./init-hooks.sh`
#
# 机制:
#   - git config core.hooksPath .claude/hooks
#   - 让 git commit 时自动调用 .claude/hooks/pre-commit → check-pre-commit.sh
#   - check-pre-commit.sh 跑门禁 0: untracked 引用检测 + 门禁 1: doc↔db drift + 门禁 2: contract tri-source
#
# 退出码:
#   0 = 配置成功
#   1 = .claude/hooks/check-pre-commit.sh 不存在
#   2 = 不是 git 仓库

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

if [[ ! -e "$REPO_ROOT/.git" ]]; then
    echo "[init-hooks] ❌ not a git repo: $REPO_ROOT" >&2
    exit 2
fi

if [[ ! -x "$REPO_ROOT/.claude/hooks/check-pre-commit.sh" ]]; then
    echo "[init-hooks] ❌ .claude/hooks/check-pre-commit.sh 不存在或不可执行" >&2
    exit 1
fi

# 检查 pre-commit wrapper 是否存在,不存在则建
if [[ ! -x "$REPO_ROOT/.claude/hooks/pre-commit" ]]; then
    cat > "$REPO_ROOT/.claude/hooks/pre-commit" <<'WRAPPER_EOF'
#!/usr/bin/env bash
# pre-commit wrapper — git 只找 hooksPath/<hook-name>, exec check-pre-commit.sh
# R43-α 二轮: 让 check-pre-commit.sh 通过 core.hooksPath 接入
exec "$(dirname "$0")/check-pre-commit.sh" "$@"
WRAPPER_EOF
    chmod +x "$REPO_ROOT/.claude/hooks/pre-commit"
    echo "[init-hooks] ✅ 创建 pre-commit wrapper"
fi

# 设置 hooksPath
git config core.hooksPath .claude/hooks

# 验证
local_hooksPath="$(git config core.hooksPath)"
if [[ "$local_hooksPath" == ".claude/hooks" ]]; then
    echo "[init-hooks] ✅ core.hooksPath = .claude/hooks (本仓库)"
    echo "[init-hooks] 🧪 自检:跑一次 fast 模式看 hook 是否真能跑"
    bash "$REPO_ROOT/.claude/hooks/check-pre-commit.sh" untracked 2>&1
    echo "[init-hooks] 🎉 接入成功 — 下次 git commit 自动跑门禁 0/1/2"
    exit 0
else
    echo "[init-hooks] ❌ core.hooksPath 设置失败: 实际值='$local_hooksPath'" >&2
    exit 1
fi
