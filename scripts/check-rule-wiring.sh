#!/usr/bin/env bash
# R119 病根 #3 根除: 规则表接线率
# 扫 system_config_versions vs system_configs (需先连真库 ipd_dev)
# 扫 audit_log_chain_heads vs 实际写入位置
# 输出 SSOT "规则有版本但未生效" 清单

set -e
CNF=".codex/ipd-dev/config/mysql-client.cnf"
DB="ipd_dev"
REPORT="docs/ipd-系统说明/规则接线率-$(date +%Y%m%d).md"

mkdir -p "$(dirname "$REPORT")"
echo "# 规则接线率-$(date +%Y%m%d)" > "$REPORT"
echo "" >> "$REPORT"

echo "## 1. system_configs vs system_config_versions" >> "$REPORT"
echo "" >> "$REPORT"

# 当前 config_key 集合
configs=$(mysql --defaults-file="$CNF" -N -e "SELECT config_key FROM $DB.system_configs WHERE del_flag='0';" 2>/dev/null | sort -u)
# 历史上 version 过的 config_key 集合
versions=$(mysql --defaults-file="$CNF" -N -e "SELECT DISTINCT config_key FROM $DB.system_config_versions;" 2>/dev/null | sort -u)

echo "**当前生效配置**: $(echo "$configs" | wc -l | tr -d ' ') 条" >> "$REPORT"
echo "**历史版本记录**: $(echo "$versions" | wc -l | tr -d ' ') 条" >> "$REPORT"
echo "" >> "$REPORT"

# 未版本化 (生效但无历史记录)
not_versioned=$(comm -23 <(echo "$configs") <(echo "$versions") | head -20)
if [ -n "$not_versioned" ]; then
  echo "### ❌ 未版本化配置 (生效中但无 history 记录)" >> "$REPORT"
  echo "$not_versioned" | while read k; do echo "- $k" >> "$REPORT"; done
else
  echo "### ✓ 所有生效配置都有历史版本" >> "$REPORT"
fi
echo "" >> "$REPORT"

echo "## 2. audit_log_chain_heads 实体链路" >> "$REPORT"
echo "" >> "$REPORT"

chain_count=$(mysql --defaults-file="$CNF" -N -e "SELECT COUNT(*) FROM $DB.audit_log_chain_heads;" 2>/dev/null)
chain_keys=$(mysql --defaults-file="$CNF" -N -e "SELECT chain_key FROM $DB.audit_log_chain_heads;" 2>/dev/null)

echo "**当前链数**: $chain_count 条" >> "$REPORT"
echo "" >> "$REPORT"
echo "### 链路清单" >> "$REPORT"
echo "$chain_keys" | while read k; do echo "- $k" >> "$REPORT"; done
echo "" >> "$REPORT"

# 已建 audit_log 表但未启用链路的 entity (粗略检查: 查 audit_logs 表 entity_type)
echo "## 3. audit_logs entity_type 实际分布" >> "$REPORT"
mysql --defaults-file="$CNF" -e "SELECT entity_type, COUNT(*) AS n FROM $DB.audit_logs WHERE del_flag='0' GROUP BY entity_type ORDER BY n DESC LIMIT 10;" 2>/dev/null >> "$REPORT"

echo "" >> "$REPORT"
echo "## 4. 真活漂移登记" >> "$REPORT"
mysql --defaults-file="$CNF" -e "SELECT config_key, config_value, update_by, update_time FROM $DB.system_configs WHERE config_key IN ('bonus.poolRate','bonus.poolAmount','bonus.coefficient') AND del_flag='0';" 2>/dev/null >> "$REPORT"

echo "✓ 规则接线率报告已落 $REPORT"
exit 0
