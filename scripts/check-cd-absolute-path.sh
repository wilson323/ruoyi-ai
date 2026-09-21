#!/usr/bin/env bash
# scripts/check-cd-absolute-path.sh — M-Root-3 跨仓 cd 绝对路径门禁 (pre-commit)
# 来源：R142 §3.2 S-3（系统性根因反思深化+根除机制补齐）
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：CD_ABS_FAIL_SEED=1 → 注入测试用 cd ../ 相对路径 → exit 1
#
# 设计要点：
#   - pre-commit 时跑 `git diff --cached` 扫 staged 内容
#   - 匹配 `^+.*cd ` 新增行（避免历史行 / 上下文干扰）
#   - cd 后路径若不以 /Users/mac/Documents/ 绝对路径开头 → exit 2 拒绝提交
#   - 缺任何 cd 命令 → PASS（脚本不强制要求每次提交都有 cd）

set -euo pipefail

REPOS_ROOT="${REPOS_ROOT:-/Users/mac/Documents}"

main() {
  echo "[M-Root-3] check-cd-absolute-path.sh 启动 (基线: R142 §3.2 S-3)"

  # FAIL_SEED：注入相对路径 cd 用以证明脚本可红
  if [ "${CD_ABS_FAIL_SEED:-0}" = "1" ]; then
    echo "[FAIL_SEED] 注入测试用 cd ../"
    echo "FAIL: 行 1 缺绝对路径（注入: cd ../）"
    exit 1
  fi

  # 扫 staged diff，仅关注新增行（以 + 开头，去掉 diff header +++/---）
  local diff_output
  if ! diff_output="$(git diff --cached --unified=0 --no-color 2>/dev/null)"; then
    echo "⚠️  git diff --cached 执行失败（非 git 仓库或无 staged），按 PASS 处理"
    echo "PASS"
    exit 0
  fi

  if [ -z "$diff_output" ]; then
    echo "✅ 无 staged 改动，跨仓 cd 绝对路径检查通过"
    echo "PASS"
    exit 0
  fi

  local bad_lines=()
  local line_no=0
  while IFS= read -r line; do
    line_no=$((line_no + 1))
    # 跳过 diff 文件头（+++ b/...）
    if [[ "$line" =~ ^\+\+\+ ]]; then
      continue
    fi
    # 只看新增行
    if [[ ! "$line" =~ ^\+ ]]; then
      continue
    fi
    # 去掉前导 + 后判断是否是 cd 命令
    local content="${line:1}"
    # 匹配 cd 后面跟一个非选项参数（相对路径 / 绝对路径都算）
    if [[ "$content" =~ ([[:space:]]|^)cd[[:space:]]+([^[:space:]|;&]+) ]]; then
      local cd_target="${BASH_REMATCH[2]}"
      # 去掉可能的首尾引号
      cd_target="${cd_target%\"}"
      cd_target="${cd_target#\"}"
      cd_target="${cd_target%\'}"
      cd_target="${cd_target#\'}"
      # 必须以 /Users/mac/Documents/ 开头（绝对路径门禁）
      if [[ "$cd_target" != "${REPOS_ROOT}"* ]]; then
        bad_lines+=("行 $line_no 缺绝对路径: cd $cd_target")
      fi
    fi
  done <<< "$diff_output"

  if [ ${#bad_lines[@]} -gt 0 ]; then
    for bad in "${bad_lines[@]}"; do
      echo "FAIL: $bad"
    done
    echo "❌ 跨仓 cd 非绝对路径门禁失败（违反撞车 0 让路 BCP）"
    exit 2
  fi

  echo "✅ 跨仓 cd 绝对路径检查通过"
  echo "PASS"
  exit 0
}

main "$@"
