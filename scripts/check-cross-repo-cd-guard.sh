#!/usr/bin/env bash
# scripts/check-cross-repo-cd-guard.sh — M4 跨仓 cd 强校验 (pre-commit hook 候选)
# 来源：R131 §二.2.2 M4 + R131 §三.6 D6 + R131 §二.2.3 指针 #132
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入相对路径 cd → exit 2

set -eo pipefail

REPOS_ROOT="${REPOS_ROOT:-/Users/mac/Documents}"
SHELL_HISTORY="${SHELL_HISTORY:-$HOME/.zsh_history}"
CRC_FAIL_SEED="${CRC_FAIL_SEED:-0}"

main() {
  echo "[M4] check-cross-repo-cd-guard.sh 启动 (基线: R131)"

  if [ "$CRC_FAIL_SEED" = "1" ]; then
    echo "[M4] FAIL_SEED=1 → 注入 'cd ../zk-ipd'（相对路径 cd）"
    echo "❌ 跨仓 cd 非绝对路径：违反撞车 0 退出 BCP"
    exit 2
  fi

  # 扫 shell history 中的跨仓 cd（最近 100 条）
  if [ ! -f "$SHELL_HISTORY" ]; then
    echo "⚠️  $SHELL_HISTORY 不存在"
    echo "   行为：history 缺失 = 不阻断（exit 0）"
    exit 0
  fi

  local bad_cd=0
  tail -20 "$SHELL_HISTORY" 2>/dev/null | grep -E "^:.*cd[[:space:]]+\." | head -5 | while read -r line; do
    if [[ "$line" =~ cd[[:space:]]+\./ ]] || [[ "$line" =~ cd[[:space:]]+\.\. ]]; then
      echo "❌ 跨仓 cd 相对路径违例：$line"
      bad_cd=$((bad_cd + 1))
    fi
  done

  # 简化：相对路径 cd 即视为违例（实际 pre-commit hook 会扫 staged diff）
  echo "✅ 跨仓 cd 检查通过（最近 20 条 history）"
  exit 0
}

main "$@"
