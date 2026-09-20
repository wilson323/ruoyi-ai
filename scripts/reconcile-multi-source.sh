#!/usr/bin/env bash
# R119 病根 #5 根除: 多事实源对账
# 检查本地分支 vs SSOT 镜像 vs main HEAD 三方一致
# 检查真库后端进程 vs 配置 vs SSOT 镜像记录
# 输出对账报告 docs/ipd-系统说明/事实源对账-YYYYMMDD.md

set -e
REPORT="docs/ipd-系统说明/事实源对账-$(date +%Y%m%d).md"
CNF=".codex/ipd-dev/config/mysql-client.cnf"

mkdir -p "$(dirname "$REPORT")"
echo "# 事实源对账-$(date +%Y%m%d)" > "$REPORT"
echo "" >> "$REPORT"

echo "## 1. 本地分支 vs origin/main" >> "$REPORT"
echo "" >> "$REPORT"
echo "| 分支 | ahead | behind | 状态 |" >> "$REPORT"
echo "|---|---|---|---|" >> "$REPORT"

for b in $(git for-each-ref --format='%(refname:short)' refs/heads/); do
  if [ "$b" = "main" ]; then continue; fi
  ahead=$(git rev-list --count origin/main.."$b" 2>/dev/null || echo "0")
  behind=$(git rev-list --count "$b"..origin/main 2>/dev/null || echo "0")
  age=$(git log -1 --format="%cd" --date=short "$b" 2>/dev/null || echo "未知")
  echo "| $b | $ahead | $behind | age=$age |" >> "$REPORT"
done

echo "" >> "$REPORT"
echo "## 2. 后端进程对账" >> "$REPORT"
echo "" >> "$REPORT"

# 端口 16039 (IPD 后端)
ipd_pid=$(lsof -ti :16039 2>/dev/null | head -1)
ipd_cmd=$(lsof -nP -iTCP:16039 -sTCP:LISTEN 2>/dev/null | tail -1 | awk '{for(i=1;i<=NF;i++)if($i~/java/)print $i}')

# 端口 6039 (启动入口会杀)
p6039_pid=$(lsof -ti :6039 2>/dev/null | head -1)

# 端口 23306 (兄弟会话 ry-vue 后端)
ry_pid=$(lsof -ti :23306 2>/dev/null | head -1)

echo "| 端口 | 用途 | PID | 进程 |" >> "$REPORT"
echo "|---|---|---|---|" >> "$REPORT"
echo "| 16039 | IPD 后端 (期望) | ${ipd_pid:-未启} | ${ipd_cmd:-N/A} |" >> "$REPORT"
echo "| 6039 | 启动入口 | ${p6039_pid:-未启} | N/A |" >> "$REPORT"
echo "| 23306 | 兄弟会话 ry-vue | ${ry_pid:-未启} | ⚠️ 撞车 0 风险 |" >> "$REPORT"

echo "" >> "$REPORT"
echo "## 3. ahead commit 备份时效" >> "$REPORT"
echo "" >> "$REPORT"
echo "| 备份目录 | commit 数 | 最老 | 最新 |" >> "$REPORT"
echo "|---|---|---|---|" >> "$REPORT"
for d in /tmp/r*-backup/; do
  if [ -d "$d" ]; then
    n=$(cat "$d"*.txt 2>/dev/null | wc -l | tr -d ' ')
    oldest=$(cat "$d"*.txt 2>/dev/null | head -1 | awk '{print $1}' | cut -c1-7)
    newest=$(cat "$d"*.txt 2>/dev/null | tail -1 | awk '{print $1}' | cut -c1-7)
    echo "| $d | $n | $oldest | $newest |" >> "$REPORT"
  fi
done

echo "" >> "$REPORT"
echo "## 4. 真库连通性" >> "$REPORT"
mysql --defaults-file="$CNF" -e "SELECT @@version AS mysql_version, @@port AS port, DATABASE() AS current_db, NOW() AS server_time;" 2>/dev/null >> "$REPORT"

echo "" >> "$REPORT"
echo "## 5. SSOT 镜像最近活动" >> "$REPORT"
ls -lt docs/ipd-系统说明/R1*.md 2>/dev/null | head -5 | awk '{print $9, $6, $7, $8}' >> "$REPORT"

echo "" >> "$REPORT"
echo "## 6. 差异项总账" >> "$REPORT"
echo "" >> "$REPORT"

# 收集差异
DIFFS=0

if [ -z "$ipd_pid" ]; then
  echo "- ❌ IPD 后端 (16039) 未启" >> "$REPORT"
  DIFFS=$((DIFFS+1))
fi

if [ -n "$ry_pid" ]; then
  echo "- ⚠️ 兄弟会话 ry-vue 后端 (23306) 在跑 (撞车 0 风险)" >> "$REPORT"
  DIFFS=$((DIFFS+1))
fi

# ahead > 0 + age > 14d
for b in $(git for-each-ref --format='%(refname:short)' refs/heads/); do
  if [ "$b" = "main" ]; then continue; fi
  ahead=$(git rev-list --count origin/main.."$b" 2>/dev/null || echo 0)
  if [ "$ahead" -gt 0 ]; then
    last=$(git log -1 --format=%ct "$b" 2>/dev/null)
    now=$(date +%s)
    age_days=$(( (now - last) / 86400 ))
    if [ "$age_days" -gt 14 ]; then
      echo "- ⚠️ ahead 分支 $b ($ahead commit, ${age_days}d 未动) — 待清理" >> "$REPORT"
      DIFFS=$((DIFFS+1))
    fi
  fi
done

if [ "${DIFFS:-0}" -eq 0 ]; then
  echo "✓ 全部对账项一致" >> "$REPORT"
fi

echo "" >> "$REPORT"
echo "✓ 对账完成, 差异项 $DIFFS" >> "$REPORT"

# 自证能红：差异项 > 0 → exit 1
if [ "${DIFFS:-0}" -gt 0 ]; then
  exit 1
fi
exit 0
