#!/usr/bin/env bash
# r35-merge-gate.sh — merge 前置 gate 校验(防 R25 病根 ②⑤)
#
# 承接:R34 系统性扫描报告 + R34 大白话反思版 §6.2 任务 4
# 范围:6 项轻量校验,确认 R35 takeover 分支可安全 merge 到 main
# - ⚠️ 不跑 mvn compile(假红陷阱:AGENTS.md 红线)
# - ⚠️ 不跑 mvn test(假绿陷阱:dev profile 静默跳过非 @Tag("dev") 测试)
# 撞车风险:0(只读 main 工作树 + takeover worktree)

set -euo pipefail

MAIN_REPO="${MAIN_REPO:-/Users/mac/Documents/ruoyi-ai}"
TAKEOVER_WORKTREE="${TAKEOVER_WORKTREE:-/private/tmp/r35-takeover-ipd}"
EXPECTED_BRANCH="${EXPECTED_BRANCH:-r35/takeover-20260918}"
MYSQL_CNF="${MYSQL_CNF:-$MAIN_REPO/.codex/ipd-dev/config/mysql-client.cnf}"

cd "$MAIN_REPO"

echo "=== R35 merge gate — 6 项轻量校验 ==="
echo "主仓:$MAIN_REPO"
echo "takeover:$TAKEOVER_WORKTREE"
echo "目标分支:$EXPECTED_BRANCH"
echo

# gate 1: 主仓工作树必须干净(防 R25 病根 ②)
echo "--- gate 1: 主仓工作树干净 ---"
if [[ -n "$(git status --short)" ]]; then
  echo "❌ 主仓工作树 dirty:"
  git status --short
  echo "请先 commit 或 stash 后再跑 merge"
  exit 2
fi
echo "✅ 工作树干净"
echo

# gate 2: 本地 main 领先 origin 之上没有未发布 commit(避免 merge 完忘记 push 撞车)
echo "--- gate 2: 本地 vs origin/main 差距 ---"
local_ahead=$(git rev-list --left-right --count main...origin/main 2>/dev/null | awk '{print $1}')
echo "本地 main 领先 origin/main $local_ahead commit"
if [[ "$local_ahead" -gt 10 ]]; then
  echo "⚠️ 领先过多,owner 后续推到 topic 风险高"
fi
echo

# gate 3: R35 takeover HEAD 必须基于当前 main(防分支漂移)
echo "--- gate 3: R35 takeover 分支基于 main ---"
main_hash=$(git rev-parse HEAD)
takeover_hash=$(cd "$TAKEOVER_WORKTREE" && git rev-parse HEAD)
echo "main HEAD:$main_hash"
echo "takeover HEAD:$takeover_hash"
if [[ "$(cd "$TAKEOVER_WORKTREE" && git merge-base main HEAD)" != "$main_hash" ]]; then
  echo "❌ R35 takeover 不基于当前 main(分支漂移)"
  exit 3
fi
echo "✅ R35 takeover 基于 main"
echo

# gate 4: R35 takeover 工作树 dirty 检查(防提交不完整)
echo "--- gate 4: takeover 工作树状态 ---"
cd "$TAKEOVER_WORKTREE"
if [[ -n "$(git status --short)" ]]; then
  echo "❌ R35 takeover 工作树 dirty:"
  git status --short
  exit 4
fi
echo "✅ R35 takeover 工作树干净(全 commit)"
echo

# gate 5: takeover 内本次改动幅度合理性(防单 commit 万行提交)
echo "--- gate 5: R35 改动幅度 ---"
diff_stats=$(git diff --stat main HEAD | tail -1)
echo "$diff_stats"
file_count=$(git diff --name-only main HEAD | wc -l | tr -d ' ')
echo "改动文件数:$file_count"
if [[ "$file_count" -gt 30 ]]; then
  echo "⚠️ 改动文件 > 30,owner 应 review diff 再 merge"
fi
echo

# gate 6: takeover 内 SQL 字段名校验(防 R25 病根 ① 字段名错)
echo "--- gate 6: SQL 字段名实际存在性 ---"
sql_files=$(git diff --name-only main HEAD | grep '\.sql$' || true)
if [[ -n "$sql_files" ]]; then
  for sql in $sql_files; do
    echo "检查:$sql"
    # 抽 UPDATE ... SET 子句中的字段名(用 awk 安全分隔)
    fields=$(awk '/UPDATE [a-z]+$/,/WHERE/' "$sql" | grep -oE "^[[:space:]]+[a-z_]+=" | sed 's/[[:space:]]*=//' | sort -u || true)
    if [[ -n "$fields" ]]; then
      echo "  字段:$(echo "$fields" | tr '\n' ' ')"
    fi
  done
  echo "⚠️ 上面是 dry-run 字段名清单,owner apply 前必须 mysql < sql 试跑 + 验证"
fi
echo "✅ SQL 已 commit,但**不证明已 apply**(AGENTS.md:commit ≠ 约束生效)"
echo

# gate 7: DB 状态 — R35 cleanup SQL 应已 apply(41 条全 del_flag=1)
echo "--- gate 7: DB 真活校验(41 条全清理) ---"
products_active=$(mysql --defaults-file="$MYSQL_CNF" ipd_dev -N -e "SELECT COUNT(*) FROM products WHERE create_by=-1 AND status='ACTIVE' AND product_name REGEXP '^(P[0-9]|QA[0-9]|E2E|R3)';" 2>/dev/null)
projects_active=$(mysql --defaults-file="$MYSQL_CNF" ipd_dev -N -e "SELECT COUNT(*) FROM projects WHERE create_by=-1 AND status='ACTIVE' AND name REGEXP 'QA03|R30|R31|矩阵|HARDWARE|探针';" 2>/dev/null)
zk_active=$(mysql --defaults-file="$MYSQL_CNF" ipd_dev -N -e "SELECT (SELECT COUNT(*) FROM products WHERE id IN (900001,9130004) AND del_flag='0') + (SELECT COUNT(*) FROM projects WHERE id IN (9140004) AND del_flag='0');" 2>/dev/null)
echo "products ACTIVE 残留:$products_active"
echo "projects ACTIVE 残留:$projects_active"
echo "ZK-GATE-TEST 跨表残留:$zk_active"
if [[ "$products_active" -ne 0 || "$projects_active" -ne 0 || "$zk_active" -ne 0 ]]; then
  echo "❌ R35 cleanup SQL 未完全 apply"
  exit 5
fi
echo "✅ DB 真活校验:41 条全清理"
echo

# 全部 gate 通过
cd "$MAIN_REPO"
echo "=== ✅ 全部 7 项 gate 通过,可以 merge ==="
echo "下一步:"
echo "  bash $MAIN_REPO/scripts/r35-merge-execute.sh  # 由脚本触发 merge,不冲突"