#!/usr/bin/env bash
# R119 病根 #2 根除：提交完整度全量检查
# R30+ pre-commit-main-tree-block.sh 已阻断主工作树 commit（局部防线）
# R119 补缺：全量提交完整性（untracked / ahead commit / 兄弟会话进程撞车）
#
# 3 项检查：
#   A. untracked 文件不应当大量存在（提交前必带 git add）
#   B. ahead commit 不超过 14 天未动（> 14 天 + ahead > 0 → 落后兄弟会话）
#   C. 兄弟会话后端进程撞车检查（lsof 16039 + 进程 command 含 RuoYiAI）
#
# 输出 SSOT docs/ipd-系统说明/提交完整度-YYYYMMDD.md

set -e

REPORT="docs/ipd-系统说明/提交完整度-$(date +%Y%m%d).md"
mkdir -p "$(dirname "$REPORT")"
echo "# 提交完整度-$(date +%Y%m%d)" > "$REPORT"
echo "" >> "$REPORT"

FAIL=0

# A. untracked 文件检查
echo "## A. untracked 文件检查" >> "$REPORT"
UNTRACKED=$(git ls-files --others --exclude-standard 2>/dev/null | wc -l | tr -d ' ')
UNTRACKED_LIST=$(git ls-files --others --exclude-standard 2>/dev/null | head -10)
echo "" >> "$REPORT"
echo "| 项 | 值 | 阈值 | 状态 |" >> "$REPORT"
echo "|---|---|---|---|" >> "$REPORT"
if [ "$UNTRACKED" -gt 5 ]; then
  echo "| untracked 文件数 | $UNTRACKED | ≤5 | ❌ |" >> "$REPORT"
  FAIL=1
else
  echo "| untracked 文件数 | $UNTRACKED | ≤5 | ✅ |" >> "$REPORT"
fi
echo "" >> "$REPORT"
if [ -n "$UNTRACKED_LIST" ]; then
  echo "### 前 10 个 untracked 文件" >> "$REPORT"
  echo '```' >> "$REPORT"
  echo "$UNTRACKED_LIST" >> "$REPORT"
  echo '```' >> "$REPORT"
fi

# B. ahead commit 检查（> 14 天未动 + ahead > 0）
echo "" >> "$REPORT"
echo "## B. ahead commit 检查（> 14 天未动）" >> "$REPORT"
echo "" >> "$REPORT"
echo "| 分支 | ahead | 最后提交 | 状态 |" >> "$REPORT"
echo "|---|---|---|---|" >> "$REPORT"
CUTOFF=$(date -v-14d +%s 2>/dev/null || date -d "14 days ago" +%s)
STALE_AHEAD=0
for b in $(git for-each-ref --format='%(refname:short)' refs/heads/); do
  if [ "$b" = "main" ]; then continue; fi
  ahead=$(git rev-list --count origin/main.."$b" 2>/dev/null || echo "0")
  if [ "$ahead" = "0" ]; then continue; fi
  last_ts=$(git log -1 --format=%ct "$b" 2>/dev/null || echo "0")
  if [ "$last_ts" -lt "$CUTOFF" ]; then
    echo "| $b | $ahead | $(git log -1 --format=%cd --date=short $b) | ❌ 落后兄弟会话超 14 天 |" >> "$REPORT"
    STALE_AHEAD=$((STALE_AHEAD + 1))
    FAIL=1
  else
    echo "| $b | $ahead | $(git log -1 --format=%cd --date=short $b) | ✅ |" >> "$REPORT"
  fi
done
if [ "$STALE_AHEAD" = "0" ]; then
  echo "" >> "$REPORT"
  echo "**全部分支 ahead commit 都在 14 天内**" >> "$REPORT"
fi

# C. 兄弟会话后端进程撞车检查
echo "" >> "$REPORT"
echo "## C. 兄弟会话后端进程撞车检查" >> "$REPORT"
echo "" >> "$REPORT"
if lsof -i :16039 >/dev/null 2>&1; then
  PROC=$(lsof -i :16039 2>/dev/null | tail -1 | awk '{print $1, $2}')
  PID=$(echo "$PROC" | awk '{print $2}')
  CMD=$(ps -p "$PID" -o command= 2>/dev/null | head -c 200 || echo "?")
  # 检查 command 是否含 RuoYiAI 或 ipd 字样（撞车兄弟会话的 ry-vue 后端）
  # 用 awk 替代 grep -P（macOS BSD grep 无 PCRE）
  if echo "$CMD" | awk 'BEGIN{IGNORECASE=1} /ry-vue/{exit 0} /ruoyi/ && !/ipd/{exit 0} {exit 1}'; then
    echo "| 撞车兄弟会话 | PID=$PID, CMD=$CMD | ❌ 撞车兄弟会话 ry-vue 后端 |" >> "$REPORT"
    FAIL=1
  else
    echo "| IPD 后端 | PID=$PID, CMD=$CMD | ✅ |" >> "$REPORT"
  fi
else
  echo "| 后端进程 | 16039 无监听 | ⚠️ IPD 后端未启 |" >> "$REPORT"
fi

echo "" >> "$REPORT"
if [ "$FAIL" = "1" ]; then
  echo "## ❌ 提交完整度有失败项" >> "$REPORT"
  exit 1
else
  echo "## ✅ 提交完整度全绿" >> "$REPORT"
  exit 0
fi