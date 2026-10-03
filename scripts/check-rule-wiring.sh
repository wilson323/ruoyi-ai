#!/usr/bin/env bash
# R119 病根 #3 根除: 规则表接线率门禁
# 扫 system_config_versions vs system_configs (需先连真库 ipd_dev)
# 扫 audit_log_chain_heads vs 实际写入位置
# 输出 SSOT "规则有版本但未生效" 清单
#
# ── 2026-10-03 重写：修 3 个叠加缺陷 ─────────────────────────────
# 实测（bash -x 追踪）发现原版**永远返回 1 且无任何诊断**：
#
#   ① SQL 错误：第 52 行查 `audit_logs WHERE del_flag='0'`，但该表
#      **没有 del_flag 列**（实测列：id/entity_type/entity_id/action/
#      operator_*/before_data/after_data/prev_hash/curr_hash/hash_version/
#      seq/trace_id/tenant_id/ip_address/reason/create_time）。
#      RuoYi 的 del_flag 软删除只加在业务表，audit_logs 走 append-only。
#      → 已去掉该过滤条件。
#   ② 错误被吞：全部 mysql 调用都带 `2>/dev/null`，SQL 报错时只剩退出码。
#   ③ 无诊断退出：`set -e` 撞上失败的 mysql → 脚本静默终止。
#      表现为「门禁永远红，但没人知道为什么」——比假绿更难排查。
#      → 已改为：显式捕获退出码，报出人话诊断，SQL 错 → exit 2（环境/门禁自身错）。
#
# 退出码语义（与 ownership-gate.yml / static-gates.yml 一致）：
#   0 = 接线完整（无未版本化配置）
#   1 = 有未版本化配置（真实违规，阻断）
#   2 = 门禁自身错误（连不上库 / SQL 错 / 表不存在）—— 也是红，不许当通过
#
# 副作用：仍会写报告到 docs/。这是它被引用的既有行为，保留。
# 若只想判定不想落文件，用 --no-report。

set -uo pipefail   # 刻意去掉 -e：要自己控制每一步的失败处理

CNF="${MYSQL_CNF:-.codex/ipd-dev/config/mysql-client.cnf}"
DB="${MYSQL_DB:-ipd_dev}"
REPORT="docs/ipd-系统说明/规则接线率-$(date +%Y%m%d).md"
WRITE_REPORT=1

for arg in "$@"; do
  case "$arg" in
    --no-report) WRITE_REPORT=0 ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
  esac
done

say() { echo "$@" >&2; }

# ── 前置检查：库连不上就直接 exit 2，不做任何判定 ──────────────
if ! command -v mysql >/dev/null 2>&1; then
  say "❌ [门禁自身错误] mysql 客户端不在 PATH，无法执行接线率门禁"
  exit 2
fi
if [ ! -f "$CNF" ]; then
  say "❌ [门禁自身错误] 找不到 mysql 配置文件: $CNF"
  say "   （本门禁需要真库；无库环境请勿把它当通过）"
  exit 2
fi
if ! mysql --defaults-file="$CNF" -N -e "SELECT 1;" >/dev/null 2>&1; then
  say "❌ [门禁自身错误] 连不上数据库（${CNF} / ${DB}）"
  say "   这不是「接线完整」，是「查不了」。两者必须区分。"
  exit 2
fi

# ── 查询封装：失败即 exit 2，绝不静默继续 ───────────────────────
q() {
  local desc="$1" sql="$2" out
  if ! out=$(mysql --defaults-file="$CNF" -N -e "$sql" 2>&1); then
    say "❌ [门禁自身错误] 查询失败: $desc"
    say "   SQL: $sql"
    say "   错误: $out"
    exit 2
  fi
  printf '%s' "$out"
}

# 表存在性检查：把「表不存在」和「查到 0 行」区分开
has_table() {
  local t="$1"
  [ "$(mysql --defaults-file="$CNF" -N -e \
      "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DB' AND table_name='$t';" 2>/dev/null)" = "1" ]
}

# ── 开始采集 ──────────────────────────────────────────────────
configs=$(q "system_configs 当前生效配置" \
  "SELECT config_key FROM $DB.system_configs WHERE del_flag='0';")
versions=$(q "system_config_versions 历史版本" \
  "SELECT DISTINCT config_key FROM $DB.system_config_versions;")

# 未版本化 = 生效中但无任何 history 记录
not_versioned=$(comm -23 \
  <(printf '%s\n' "$configs" | sed '/^$/d' | sort -u) \
  <(printf '%s\n' "$versions" | sed '/^$/d' | sort -u))

if has_table "audit_log_chain_heads"; then
  chain_count=$(q "audit_log_chain_heads 链路数" "SELECT COUNT(*) FROM $DB.audit_log_chain_heads;")
  chain_keys=$(q  "audit_log_chain_heads 链键"   "SELECT chain_key FROM $DB.audit_log_chain_heads;")
else
  chain_count="(表不存在)"; chain_keys=""
fi

# audit_logs 是 append-only 表，**没有 del_flag 列**（2026-10-03 实测确认）
# 注意：COUNT(*) 是聚合，别名后 ORDER BY 必须用别名 n，不能用序号 2——
# CONCAT 后的结果集列名已变，写 ORDER BY 2 会报 Unknown column '2'。
if has_table "audit_logs"; then
  entity_dist=$(q "audit_logs entity_type 分布" \
    "SELECT CONCAT(entity_type, '\t', COUNT(*)) AS row_desc FROM $DB.audit_logs
     GROUP BY entity_type ORDER BY COUNT(*) DESC LIMIT 10;")
else
  entity_dist="(表不存在)"
fi

live_drift=$(q "真活漂移登记" \
  "SELECT CONCAT(config_key,'\t',config_value,'\t',COALESCE(update_by,'-'),'\t',COALESCE(update_time,'-'))
   FROM $DB.system_configs
   WHERE config_key IN ('bonus.poolRate','bonus.poolAmount','bonus.coefficient') AND del_flag='0';")

n_cfg=$(printf '%s\n' "$configs" | sed '/^$/d' | wc -l | tr -d ' ')
n_ver=$(printf '%s\n' "$versions" | sed '/^$/d' | wc -l | tr -d ' ')
n_missing=$(printf '%s\n' "$not_versioned" | sed '/^$/d' | wc -l | tr -d ' ')

# ── 落报告 ────────────────────────────────────────────────────
if [ "$WRITE_REPORT" = "1" ]; then
  mkdir -p "$(dirname "$REPORT")"
  {
    echo "# 规则接线率-$(date +%Y%m%d)"
    echo
    echo "## 1. system_configs vs system_config_versions"
    echo
    echo "**当前生效配置**: $n_cfg 条"
    echo "**历史版本记录**: $n_ver 条"
    echo
    if [ "$n_missing" -gt 0 ]; then
      echo "### ❌ 未版本化配置（生效中但无 history 记录）：$n_missing 条"
      printf '%s\n' "$not_versioned" | sed '/^$/d' | while read -r k; do echo "- $k"; done
    else
      echo "### ✓ 所有生效配置都有历史版本"
    fi
    echo
    echo "## 2. audit_log_chain_heads 实体链路"
    echo
    echo "**当前链数**: $chain_count 条"
    echo
    echo "### 链路清单"
    printf '%s\n' "$chain_keys" | sed '/^$/d' | while read -r k; do echo "- $k"; done
    echo
    echo "## 3. audit_logs entity_type 实际分布"
    echo
    echo '```'
    printf '%s\n' "$entity_dist"
    echo '```'
    echo
    echo "## 4. 真活漂移登记"
    echo
    echo '```'
    printf '%s\n' "$live_drift"
    echo '```'
  } > "$REPORT"
  say "📄 报告已落 $REPORT"
fi

# ── 判定 ──────────────────────────────────────────────────────
if [ "$n_missing" -gt 0 ]; then
  say "❌ 规则接线率门禁 FAIL：有 $n_missing 条生效配置从未版本化"
  say "   含义：这些配置改了没有历史，出问题无法回溯到「谁在什么时候改的」"
  printf '%s\n' "$not_versioned" | sed '/^$/d' | head -20 | while read -r k; do say "   - $k"; done
  exit 1
fi

say "✅ 规则接线率门禁 PASS：$n_cfg 条生效配置全部有版本化历史"
exit 0
