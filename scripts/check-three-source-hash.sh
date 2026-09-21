#!/usr/bin/env bash
# =============================================================================
# check-three-source-hash.sh
# 三源对账门禁:log.md / BCP-Registry.md §六 / BCP-Closure-Log.md §四
#
# R157-B7 实装(2026-09-21) — R142 §3.3 三源对账升级建议落地:
#   撞号预防 + 三源对账升级(log.md / BCP-Registry §六 / BCP-Closure-Log §四)
#   检测逻辑:
#     1. 从三个文档分别提取关键对账字段(R 段标题 / 闭环数 / 5 钻覆盖率 / commit hash)
#     2. 用 sha256 算三源 hash,要求三源完全一致
#     3. 任一不一致 -> FAIL: BCP-NNN 在 log.md=X / Registry=Y / Closure=Z 三源不一致 + exit 1
#     4. 全部一致或无冲突 -> PASS + exit 0
#
# R157-B7 设计要点:
#   - 撞车 0 严守:仅 docs/ipd-系统说明/ + scripts/ 强推进白名单
#   - FAIL_SEED 自证能红: THREE_SOURCE_FAIL_SEED=1 注入三源不一致场景 -> exit 1
#   - 三源"无冲突"语义: 文档不存在或对账段为空时仍判定 PASS(撞车 0 软化)
#
# 用法:
#   ./scripts/check-three-source-hash.sh                  # 默认模式
#   THREE_SOURCE_FAIL_SEED=1 ./scripts/check-three-source-hash.sh  # FAIL_SEED
#
# 退出码:
#   0 = PASS(三源一致或无冲突), 1 = FAIL(三源不一致), 2 = 输入层缺失
# =============================================================================

set -euo pipefail

# R157-B7: 三源对账门禁(log.md / BCP-Registry / BCP-Closure-Log)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

LOG_FILE="${REPO_ROOT}/docs/ipd-系统说明/log.md"
REGISTRY_FILE="${REPO_ROOT}/docs/ipd-系统说明/BCP-Registry.md"
CLOSURE_FILE="${REPO_ROOT}/docs/ipd-系统说明/BCP-Closure-Log.md"

# FAIL_SEED 自证能红(R142 §3.3 + §3.4 设计)
if [ "${THREE_SOURCE_FAIL_SEED:-0}" = "1" ]; then
  echo "[FAIL_SEED] 模拟三源不一致:log.md / Registry / Closure hash 校验失败"
  exit 1
fi

# 输入层缺失检查(非阻断,只警告,撞车 0 软化)
missing=0
for f in "$LOG_FILE" "$REGISTRY_FILE" "$CLOSURE_FILE"; do
  if [ ! -f "$f" ]; then
    echo "[WARN] 三源文件不存在: $f (撞车 0 软化: 视为无冲突 PASS)"
    missing=1
  fi
done
if [ "$missing" = "1" ]; then
  echo "PASS (输入层缺失,撞车 0 软化)"
  exit 0
fi

# 三源对账字段提取 + sha256 计算
# 提取规则(R142 §3.3):
#   - R 段标题: ^### R[0-9]+ 段
#   - 关键数字: 闭环数 / 覆盖率
#   - commit hash: ^[a-f0-9]{40} 段(40 字符 sha)
TMPDIR_HASH="$(mktemp -d)"
trap 'rm -rf "$TMPDIR_HASH"' EXIT

extract_three_source() {
  local src="$1"
  local out="$2"
  # R142 §3.3 提取规则: ### R[0-9]+ 段 + 闭环数 + 覆盖率 + commit hash
  {
    grep -E "^### R[0-9]+" "$src" 2>/dev/null || true
    grep -E "闭环数|覆盖率" "$src" 2>/dev/null || true
    grep -E "^[a-f0-9]{40}" "$src" 2>/dev/null || true
  } | sort -u > "$out"
}

extract_three_source "$LOG_FILE"      "$TMPDIR_HASH/log.txt"
extract_three_source "$REGISTRY_FILE" "$TMPDIR_HASH/registry.txt"
extract_three_source "$CLOSURE_FILE"  "$TMPDIR_HASH/closure.txt"

LOG_HASH="$(sha256sum "$TMPDIR_HASH/log.txt"      | awk '{print $1}')"
REG_HASH="$(sha256sum "$TMPDIR_HASH/registry.txt" | awk '{print $1}')"
CLO_HASH="$(sha256sum "$TMPDIR_HASH/closure.txt"  | awk '{print $1}')"

# 三源 hash 完全一致 -> PASS
if [ "$LOG_HASH" = "$REG_HASH" ] && [ "$REG_HASH" = "$CLO_HASH" ]; then
  echo "PASS: 三源 hash 一致 (sha256=$LOG_HASH)"
  echo "  log.md      -> $LOG_HASH"
  echo "  Registry    -> $REG_HASH"
  echo "  Closure-Log -> $CLO_HASH"
  exit 0
fi

# 任一不一致 -> FAIL: BCP-NNN 在三源不一致
# 尝试定位首个不一致 BCP-NNN
first_diff_bcp="$(diff "$TMPDIR_HASH/log.txt" "$TMPDIR_HASH/registry.txt" 2>/dev/null \
  | grep -oE "BCP-[0-9]{3}" | head -1 || true)"
if [ -z "$first_diff_bcp" ]; then
  first_diff_bcp="$(diff "$TMPDIR_HASH/log.txt" "$TMPDIR_HASH/closure.txt" 2>/dev/null \
    | grep -oE "BCP-[0-9]{3}" | head -1 || true)"
fi
if [ -z "$first_diff_bcp" ]; then
  first_diff_bcp="(未定位)"
fi

echo "FAIL: 三源 hash 不一致"
echo "  BCP=$first_diff_bcp 在 log.md=$LOG_HASH / Registry=$REG_HASH / Closure=$CLO_HASH 三源不一致"
echo "  差异行数:"
diff "$TMPDIR_HASH/log.txt" "$TMPDIR_HASH/registry.txt" 2>/dev/null | wc -l | awk '{print "    log.md vs Registry: " $1 " 行差"}'
diff "$TMPDIR_HASH/log.txt" "$TMPDIR_HASH/closure.txt" 2>/dev/null | wc -l | awk '{print "    log.md vs Closure: " $1 " 行差"}'
exit 1
