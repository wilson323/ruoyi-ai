#!/bin/bash
# scripts/pointer-trigger.sh — 指针驱动飞轮元脚本
# 角色：「知道哪个工具对哪个指针负责」的索引器
# 用法：bash scripts/pointer-trigger.sh [--filter-type X] [--filter-severity Y] [--dry-run]
# R132 基线：2026-09-20
set -eo pipefail
POINTER_DIR="${POINTER_DIR:-.harness/memory}"
LOG_FILE="${LOG_FILE:-docs/ipd-系统说明/log.md}"

FILTER_TYPE=""; FILTER_SEVERITY=""; DRY_RUN="false"
while [ $# -gt 0 ]; do
  case "$1" in
    --filter-type) FILTER_TYPE="$2"; shift 2;;
    --filter-severity) FILTER_SEVERITY="$2"; shift 2;;
    --dry-run) DRY_RUN="true"; shift;;
    *) echo "Unknown arg: $1"; exit 1;;
  esac
done

# 提取字段：grep 行 → 删前缀 → 取中文冒号后
parse_field() {
  local file="$1" key="$2"
  grep -F "**$key**：" "$file" 2>/dev/null | head -1 | sed -E "s/.*\*\*$key\*\*：//; s/\*//g; s/[[:space:]]+$//"
}

main() {
  local count_total=0 count_hit=0 ts
  ts="$(date +%Y%m%d-%H%M%S)"
  echo "=== pointer-trigger.sh 启动 @ $ts ==="
  echo "[POINTER_DIR=$POINTER_DIR] [TYPE=$FILTER_TYPE] [SEV=$FILTER_SEVERITY] [DRY=$DRY_RUN]"
  echo
  for f in "$POINTER_DIR"/pointer-*.md; do
    [ -f "$f" ] || continue
    count_total=$((count_total + 1))
    local pid ptype severity trigger red
    pid="$(basename "$f" .md | sed 's/pointer-//')"
    ptype="$(parse_field "$f" '类型')"
    severity="$(parse_field "$f" '严重度' | grep -oE '[🔴🟡🟢]' | head -1 || true)"
    trigger="$(parse_field "$f" '触发')"
    [ -n "$FILTER_TYPE" ] && [ "$ptype" != "$FILTER_TYPE" ] && continue
    [ -n "$FILTER_SEVERITY" ] && [ "$severity" != "$FILTER_SEVERITY" ] && continue
    echo "[Pointer #$pid] type=$ptype sev=$severity trigger=$trigger"
    red="$(awk '/^## 自证能红/{f=1;next} /^## /{f=0}f' "$f" 2>/dev/null | head -1 || true)"
    if [ -n "$red" ] && [ "$DRY_RUN" != "true" ]; then
      count_hit=$((count_hit + 1))
      echo "    ✓ 自证能红：$red"
    fi
    if [ "$DRY_RUN" != "true" ] && [ -w "$LOG_FILE" ]; then
      echo "## Pointer-#$pid-触发-$ts" >> "$LOG_FILE"
      echo "- type=$ptype sev=$severity" >> "$LOG_FILE"
      echo "- trigger=$trigger" >> "$LOG_FILE"
      echo
    fi
  done
  echo
  echo "=== 完成 === [总=$count_total 命中=$count_hit ts=$ts]"
}
main "$@"
