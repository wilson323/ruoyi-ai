#!/usr/bin/env bash
# 门禁：scripts/ 下做「仓库根递归扫描」的脚本必须内置归档排除。
#
# 为什么（2026-10-03 owner 裁决「统一扫描范围」的机械化落地）：
#   本机 .codex/（19,848 个 .java）与 .harness/（12,786 个）是历史归档副本，
#   CI 检出没有、本机有。不带排除的根递归 grep/find 会把归档旧实现当现役代码
#   （实测：全仓 .java 报 35,166，真实 2,281；SecurityConfig.java 63 份 vs 真实 3 份），
#   且本地与 CI 同一把尺子读数不同。口径全文见 AGENTS.md §「构建 / 测试」同名条目。
#
# 判定：scripts/**/*.{sh,py,mjs} 中出现「根递归扫描」（grep -r 以 . 或 $REPO_ROOT
#   为目标 / find . / find "$REPO_ROOT" / rg .）但没有实际 .codex 和 .harness 排除选项 → FAIL。
#   只扫指定子目录（docs/、ruoyi-modules/…）的脚本不受本门禁约束（它们天然扫不到归档）。
#
# 用法：
#   bash scripts/check-scan-scope-excludes.sh                        # 期望 EXIT=0
#   SCAN_SCOPE_FAIL_SEED=1 bash scripts/check-scan-scope-excludes.sh # 自证态，期望 EXIT=1
# FAIL_SEED 语义同 check-gate-wiring.sh：先完整扫描并输出清单，再强制失败。

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || { echo "[scan-scope] FAIL: 无法进入仓库根目录" >&2; exit 2; }
FAIL_SEED="${SCAN_SCOPE_FAIL_SEED:-0}"

BAD_TMP="$(mktemp)"
trap 'rm -f "$BAD_TMP"' EXIT

# 判定分两步，避免把范围化扫描（如 `find ./logs`、`grep -r x docs/`）误当根递归：
#   ① 行内出现递归扫描命令；② 行内出现**独立成参**的根目标（`.` 单独一个参数、
#     或 $REPO_ROOT/$BACKEND_ROOT 后面直接跟空格/行尾——后接 `/docs` 的是范围化）。
#   首版正则把 `find ./logs` 也判成根递归（实测误报 start.sh），已收紧。
SCAN_CMD_RE='(grep -[rR][a-zA-Z]*|(^|[[:space:]])find([[:space:]]|$)|(^|[[:space:]])rg([[:space:]]|$))'
ROOT_TARGET_RE='(^|[[:space:]])\.([[:space:]]|$)|([[:space:]])"?\$\{?(REPO|BACKEND)_ROOT\}?"?([[:space:]]|$)'

total=0
while IFS= read -r f; do
  [ -f "$f" ] || continue
  # 自引用豁免（同 check-async-configurer-duplication 的 O-6-1 先例）：
  # 本门禁的帮助文本里含 `grep -r … --exclude-dir=…` 示例行，会命中扫描命令形态。
  [ "$f" = "scripts/check-scan-scope-excludes.sh" ] && continue
  total=$((total+1))
  # 逐行判定（同一行内既有扫描命令又有根目标才算）——文件级 AND 会把
  # 「第 10 行有 find、第 80 行有个孤立的 .」误拼成命中（实测误报 10 个）。
  # 注意用 grep→grep 两级而非 awk 动态正则：实测 awk 对含 \{? 的正则解析与
  # grep -E 不一致（同一条 `find "$BACKEND_ROOT/docs" ...` awk 误判命中）。
  if grep -v '^[[:space:]]*#' "$f" 2>/dev/null | grep -E "$SCAN_CMD_RE" | grep -qE "$ROOT_TARGET_RE" \
     && { ! grep -v '^[[:space:]]*#' "$f" | grep -qE -- '(-path|-g|--glob|--exclude-dir)[^#]*\.codex' || \
          ! grep -v '^[[:space:]]*#' "$f" | grep -qE -- '(-path|-g|--glob|--exclude-dir)[^#]*\.harness'; }; then
    printf '  FAIL  %s\n' "$f" >> "$BAD_TMP"
  fi
done < <(git ls-files 'scripts/*.sh' 'scripts/*.py' 'scripts/*.mjs' \
         'scripts/lib/*.sh' 'scripts/lib/*.py' 'scripts/lib/*.mjs' 2>/dev/null | sort -u)

n_bad="$(grep -c . "$BAD_TMP" 2>/dev/null)"; n_bad="${n_bad:-0}"

echo "================================================================================"
echo "[scan-scope] 根递归扫描排除门禁"
echo "================================================================================"
printf '受检脚本数              : %s\n' "$total"
printf '根递归且缺归档排除   : %s\n' "${n_bad:-0}"
if [ "${n_bad:-0}" -gt 0 ]; then
  echo
  cat "$BAD_TMP"
  echo
  echo "  修法：要么收窄到具体子目录（推荐），要么按 AGENTS.md 口径内置："
  echo "    find … -not -path './.codex/*' -not -path './.harness/*' -not -path '*/target/*'"
  echo "    grep -r … --exclude-dir=.git --exclude-dir=.codex --exclude-dir=.harness --exclude-dir=target"
fi

if [ "${n_bad:-0}" -gt 0 ]; then
  echo
  echo "[scan-scope] FAIL: ${n_bad} 个脚本做根递归扫描但未内置归档排除（会读到 .codex/.harness 的历史副本）。"
  exit 1
fi

if [ -n "$FAIL_SEED" ] && [ "$FAIL_SEED" != "0" ]; then
  echo
  echo "[scan-scope] FAIL_SEED 触发：上面 $total 个脚本已扫描完毕，现强制失败以自证能红。"
  exit 1
fi

echo
echo "[scan-scope] PASS: $total 个脚本无「根递归且未排除归档」形态。"
exit 0
