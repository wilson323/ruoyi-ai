#!/usr/bin/env bash
# scripts/check-naked-mvn.sh
# 检查 scripts/ 与 .harness/ 中是否存在绕过 scripts/mvn-locked.sh 直接调用裸 mvn 的情况
#
# 目的：彻底根除并发构建污染、target/classes 被并发冲刷导致的 class 丢失和假红。

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0

echo "[check-naked-mvn] 正在扫描脚本中的裸 mvn 调用..."

# 扫描 scripts/ 和 .harness/ 目录下的 .sh 文件（排除 mvn-locked.sh 和 check-naked-mvn.sh 本身）
while IFS= read -r file; do
  # 查找行首或管道后作为可执行命令调用的 mvn（排除 echo、log、printf、注释）
  matches=$(grep -nE '^[[:space:]]*(\b(mvn)\b)' "$file" | grep -v 'mvn-locked' || true)
  if [ -n "$matches" ]; then
    echo "❌ 发现作为命令执行的裸 mvn 调用: $file"
    echo "$matches"
    FAIL=1
  fi
done < <(find "$REPO_ROOT/scripts" "$REPO_ROOT/.harness" -name "*.sh" -not -name "mvn-locked.sh" -not -name "check-naked-mvn.sh")

if [ "$FAIL" -eq 0 ]; then
  echo "✅ PASS: 所有构建脚本均已接入 scripts/mvn-locked.sh，无裸 mvn 绕过！"
  exit 0
else
  echo "❌ FAIL: 检测到裸 mvn 调用，请替换为 bash scripts/mvn-locked.sh！"
  exit 1
fi
