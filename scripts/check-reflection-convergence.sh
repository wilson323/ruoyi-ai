#!/usr/bin/env bash
# scripts/check-reflection-convergence.sh — 治理轮元根因收敛门禁
# ====================================================================
# R157-B5 实装：R142 §3.2 S-6 设计 → M-Root-6（治理轮自我循环不收敛）根除门禁
#
# 检测逻辑：
#   1. 扫 docs/ipd-系统说明/R*-2026*.md 历史 R 治理报告
#   2. grep 提取每个 M-Root-N（N=1~11）首次出现 R 号 + 最近出现 R 号
#   3. R 号差 = 最近 R 号 - 首次 R 号
#   4. 差 ≥ 3 轮仍未消失 → FAIL（治理轮自我循环不收敛）
#
# 来源：R129（5 元根因 M-Root-1~5）+ R131（+ M-Root-6/7）+ R142（+ M-Root-8~11）+ R143/R144 复用
# 撞车风险 = 0：本脚本属 scripts/ 白名单；R39 兄弟会话已合 main，scripts/ 区不再被改。
#
# 自证能红：REFLECT_CONVERGE_FAIL_SEED=1 → 模拟同 M-Root 连续 4 轮示例 → exit 1
# 兼容：使用 /bin/bash 3.2 友好写法（不用 mapfile / 不用 ${var,,}）
# ====================================================================

set -euo pipefail

WORKSPACE="${WORKSPACE:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
DOCS_DIR="$WORKSPACE/docs/ipd-系统说明"

# ============== 哨兵 ==============
if [ ! -d "$DOCS_DIR" ]; then
  echo "::error::$DOCS_DIR 不存在——门禁失效"
  exit 1
fi

for cmd in grep awk sort; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "::error::$cmd 不可用——门禁失效"
    exit 1
  fi
done

# ============== FAIL_SEED 自证能红 ==============
REFLECT_CONVERGE_FAIL_SEED="${REFLECT_CONVERGE_FAIL_SEED:-0}"
if [ "$REFLECT_CONVERGE_FAIL_SEED" = "1" ]; then
  echo "[REFLECT_CONVERGE] FAIL_SEED=1 → 注入同 M-Root 连续 4 轮示例"
  echo "[REFLECT_CONVERGE] FAIL: M-Root-6 连续 4 轮未消除（seed_injected）"
  echo "REFLECT_CONVERGE|FAIL|seed_injected|reflection-stalled"
  exit 1
fi

# ============== 主扫描 ==============
echo "=== R142 §3.2 S-6 治理轮收敛门禁（M-Root-1~11）==="
echo ""

# 收集 R 报告文件列表到临时数组（兼容 bash 3.2：不用 mapfile）
R_FILES_TMP="$(mktemp -t rfiles.XXXXXX)"
trap 'rm -f "$R_FILES_TMP"' EXIT
ls "$DOCS_DIR"/R*-2026*.md 2>/dev/null | sort > "$R_FILES_TMP" || true
R_FILE_COUNT=$(wc -l < "$R_FILES_TMP" | tr -d ' ')

if [ "$R_FILE_COUNT" = "0" ]; then
  echo "::error::$DOCS_DIR/R*-2026*.md 无文件——门禁失效"
  exit 1
fi

# 阈值：R 号差 ≥ 3 触发 FAIL（R142 §3.2 S-6 原文）
THRESHOLD=3

VIOLATIONS=0
echo "M-Root  | first_R | last_R  | diff | 状态"
echo "--------|---------|---------|------|------"

# 遍历 M-Root-1~11（R129+R131+R142 三轮叠加的元根因全集）
HITS_TMP="$(mktemp -t mrhits.XXXXXX)"
trap 'rm -f "$R_FILES_TMP" "$HITS_TMP"' EXIT

for N in 1 2 3 4 5 6 7 8 9 10 11; do
  : > "$HITS_TMP"

  while IFS= read -r f; do
    [ -z "$f" ] && continue
    base=$(basename "$f")
    rnum=$(echo "$base" | grep -oE '^R[0-9]+' | head -1 | tr -d 'R')
    [ -z "$rnum" ] && continue
    rint=$((rnum + 0))
    # 用 [^0-9]M-Root-N[^0-9] 单词边界避免 M-Root-11 误匹配 M-Root-1
    if grep -qE "(^|[^0-9])M-Root-${N}([^0-9]|$)" "$f" 2>/dev/null; then
      printf "%d %d\n" "$rint" "$N" >> "$HITS_TMP"
    fi
  done < "$R_FILES_TMP"

  hit_count=$(wc -l < "$HITS_TMP" | tr -d ' ')

  if [ "$hit_count" = "0" ]; then
    printf "M-Root-%-2d| (未出现) |         |  N/A | (空)\n" "$N"
    continue
  fi

  # 解析首次 R 号 + 最近 R 号 + 命中数（按 R 号升序）
  read first_r last_r < <(sort -n "$HITS_TMP" | awk '
    NR == 1 { first = $1 }
    { last = $1 }
    END { printf "%d %d\n", first, last }
  ')

  diff=$((last_r - first_r))
  if [ "$diff" -ge "$THRESHOLD" ]; then
    status="FAIL"
    VIOLATIONS=$((VIOLATIONS + 1))
  else
    status="OK"
  fi

  printf "M-Root-%-2d| R%-6d | R%-6d | %-4d | %s\n" "$N" "$first_r" "$last_r" "$diff" "$status"
done

echo ""

# ============== 退出码 ==============
if [ "$VIOLATIONS" -gt 0 ]; then
  echo "=== 失真统计: $VIOLATIONS 件 ==="
  echo "[FAIL] 治理轮元根因未收敛：$VIOLATIONS 个 M-Root 连续 ≥${THRESHOLD} 轮未被消除"
  echo "  提示：跑 '$0' 看每个 M-Root 详情；或 REFLECT_CONVERGE_FAIL_SEED=1 $0 自证能红"
  exit 1
fi

echo "[PASS] 所有 M-Root-1~11 元根因收敛（R 号差 < ${THRESHOLD}）"
exit 0
