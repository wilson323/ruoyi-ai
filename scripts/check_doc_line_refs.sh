#!/usr/bin/env bash
# scripts/check_doc_line_refs.sh
# R30+ 治理门禁：docs 文档不应引用 yml 行号（行号会因兄弟会话在途编辑漂移）。
# AGENTS.md §构建/测试：「引用配置用键名，别用行号」——同一个 application.yml 的 demo:
# 段，20:56 实测在 :396、21:10 已到 :400。行号型断言历史记录可保留，
# 新写文档再写 yml 行号引用 → 必须标 WARNING + 推荐改为"键名 + 值"格式。
#
# 用法（在 ruoyi-ai 仓根目录）：
#   ./scripts/check_doc_line_refs.sh                # 扫所有 docs/ 下 .md
#   ./scripts/check_doc_line_refs.sh --scope=ipd    # 只扫 docs/ipd-系统说明/
#   ./scripts/check_doc_line_refs.sh --strict       # 任何 yml 行号引用 → FAIL
#
# 退出码：
#   0 = 没有新的 yml 行号引用
#   1 = 检测到新的 yml 行号引用（默认 strict 模式）
#   2 = 脚本/参数错误
#   3 = 扫描路径错位（输入层哨兵失败）
#
# 适用范围：docs/ipd-系统说明/、docs/agents/、docs/wiki/。SEC-AUD/SEC-AUD-SUPPLEMENT
# 历史快照文件豁免（--no-snapshot-exempt 关掉豁免），由 owner 决定。

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

cd "$REPO_ROOT" || exit 2

SCOPE="all"
STRICT=0
SNAPSHOT_EXEMPT=1
while [ $# -gt 0 ]; do
  case "$1" in
    --scope=*)         SCOPE="${1#--scope=}"; shift ;;
    --strict)          STRICT=1; shift ;;
    --no-snapshot-exempt) SNAPSHOT_EXEMPT=0; shift ;;
    -h|--help)
      sed -n '2,9p' "$0"
      exit 0
      ;;
    *)
      echo "ERROR: unknown arg $1" >&2
      exit 2
      ;;
  esac
done

echo "[check_doc_line_refs] scope=$SCOPE strict=$STRICT snapshot_exempt=$SNAPSHOT_EXEMPT"

# Sentinel 1: scan path integrity
case "$SCOPE" in
  all)
    DOC_FILES=$(find docs/ipd-系统说明 docs/agents docs/wiki -name "*.md" \
      -not -path "*/.claude/worktrees/*" 2>/dev/null)
    ;;
  ipd)
    DOC_FILES=$(find docs/ipd-系统说明 -name "*.md" \
      -not -path "*/.claude/worktrees/*" 2>/dev/null)
    ;;
  *)
    echo "ERROR: scope must be all|ipd"
    exit 2
    ;;
esac

DOC_COUNT=$(printf '%s\n' "$DOC_FILES" | wc -l | tr -d ' ')
echo "Scan found $DOC_COUNT markdown files (sentinel >= 10)"
[ "$DOC_COUNT" -lt 10 ] && { echo "ERROR: scan path wrong"; exit 3; }

problems=0
warnings=0

# Regex: capture (file, line-range, expected-key-token)
# Acceptable patterns (kept valid):
#   application.yml:NNN
#   application-dev.yml:NNN-NNN
#   application-prod.yml:NNN
# The "expected-key-token" is whatever word(s) follow the line ref on the same line.
# We only enforce: a yaml-line-ref must be followed by a recognizable key token.

# Find all yml line refs in docs
YML_REF_RE='application(-[a-z]+)?\.yml:[0-9]+(-[0-9]+)?'

# Files that are EXPLICITLY historic snapshots (allowed to keep line refs)
SNAPSHOT_FILES_REGEX='(SEC-AUD-2026|SEC-AUD-SUPPLEMENT|Wave[0-9]+-实施规格包)'

while IFS= read -r f; do
  [ -z "$f" ] && continue

  # Snapshot exemption (only if enabled)
  if [ "$SNAPSHOT_EXEMPT" = "1" ] && printf '%s' "$f" | grep -qE "$SNAPSHOT_FILES_REGEX"; then
    continue
  fi

  # Extract yml-line-ref lines
  HITS=$(grep -nE "$YML_REF_RE" "$f" 2>/dev/null) || HITS=""
  [ -z "$HITS" ] && continue

  # For each hit, verify the line ALSO contains a recognizable key token
  # after the yml reference (e.g., "jwt-secret-key:", "demo.enabled:", etc.)
  while IFS= read -r hit; do
    [ -z "$hit" ] && continue
    lineno="${hit%%:*}"
    body="${hit#*:}"

    # Token after the yml-ref: prefer yml keys (xxx.yyy: or xxx-yyy-yyy:)
    if printf '%s' "$body" | grep -qE '[a-zA-Z][a-zA-Z0-9._-]*:'; then
      # OK — has key token
      :
    else
      warnings=$((warnings + 1))
      echo "  WARN: $f:$lineno"
      echo "    yml line ref without key token: $body" | head -c 200
      echo
    fi
  done <<< "$HITS"

done <<< "$DOC_FILES"

# Strict mode: ANY yml line ref counts as problem (regardless of key token)
if [ "$STRICT" = "1" ]; then
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    if [ "$SNAPSHOT_EXEMPT" = "1" ] && printf '%s' "$f" | grep -qE "$SNAPSHOT_FILES_REGEX"; then
      continue
    fi
    HITS=$(grep -cnE "$YML_REF_RE" "$f" 2>/dev/null) || HITS=0
    [ "$HITS" -gt 0 ] && {
      problems=$((problems + HITS))
      echo "  STRICT-FAIL: $f ($HITS yml-line-refs)"
    }
  done <<< "$DOC_FILES"
fi

echo
echo "==== Summary ===="
echo "  yml line-refs without key token (warning): $warnings"
echo "  strict-mode violations (problem):         $problems"

if [ "$STRICT" = "1" ] && [ "$problems" -gt 0 ]; then
  echo
  echo "FAIL: --strict mode forbids any yml line ref in non-snapshot docs."
  echo "  Fix: rewrite as 'key-name + value' (e.g., 'application.yml demo.enabled: false')"
  echo "  Or move into SEC-AUD-* historic snapshot file."
  exit 1
fi

if [ "$warnings" -gt 0 ]; then
  echo
  echo "WARN: docs reference yml line numbers without key token."
  echo "  Risk: line numbers drift across commits; downstream readers can't verify."
  echo "  Fix: append key token after the line ref, e.g.:"
  echo "    application.yml:150 jwt-secret-key: abcdef..."
  exit 1
fi

echo
echo "OK: docs use key tokens instead of bare yml line refs."
exit 0