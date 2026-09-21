#!/usr/bin/env bash
# scripts/check-bcp-unit-mismatch.sh
# R157-B6: M-Root-7 BCP 单位对齐门禁（撞车 0 让路下 scripts/ 白名单）
# 设计：R142 §3.2 S-7（docs/ipd-系统说明/R142-系统性根因反思深化+根除机制补齐-20260920.md L159-L162）
# 检测逻辑：扫 BCP-Registry.md §一 BCP-ID 列（如 BCP-014）与 `git worktree list` 输出 wt 名（如 `wt-r141-bcp014`），
#           反向引用比对，缺一 → FAIL
# 自证能红：BCP_UNIT_FAIL_SEED=1 → 模拟 BCP-014 存在但 wt-r141-bcp014 不存在 → exit 1
# 前置依赖：bash 4+、git、grep、awk
# 输入：docs/ipd-系统说明/BCP-Registry.md + git worktree list 输出
# 退出码：0=PASS（all BCP 对齐），1=FAIL（缺 wt 或 BCP-Registry 不存在）

set -euo pipefail

BCP_REGISTRY="docs/ipd-系统说明/BCP-Registry.md"

# === FAIL_SEED 自证能红（M-Root-7 派单单位错配 + 反馈环太短根除）===
if [ "${BCP_UNIT_FAIL_SEED:-0}" = "1" ]; then
  echo "[FAIL_SEED] 模拟 BCP-014 存在但 wt-r141-bcp014 不存在"
  exit 1
fi

# === 前置依赖检查（M-Root-11 工具链假设漂移根除）===
if ! command -v git >/dev/null 2>&1; then
  echo "FAIL: 缺少 git 命令（M-Root-11 工具链假设漂移）"
  exit 1
fi

if [ ! -f "$BCP_REGISTRY" ]; then
  echo "FAIL: BCP-Registry 文件不存在: $BCP_REGISTRY"
  exit 1
fi

# === 1. 从 BCP-Registry.md §一 提取所有 BCP-NNN ID ===
# §一 表头格式: `| BCP-001 | ... |`，锚定 `^\| *BCP-[0-9]{3}` 限定到表行首（避免误抓散落文字引用）
BCP_IDS=$(grep -E "^\| *BCP-[0-9]{3}" "$BCP_REGISTRY" | grep -oE "BCP-[0-9]{3}" | sort -u || true)

# === 2. 从 git worktree list 提取所有 wt 名（wt-rXXX-bcpNNN 模式） ===
# git worktree list 格式: "<path> <hash> [branch]"，第 1 列为路径
# 加 `|| true` 防 set -euo pipefail 在 grep 无匹配时静默 abort
WT_BCP_NUMS=$(git worktree list | awk '{print $1}' | grep -oE "wt-r[0-9]+-bcp[0-9]+" | sed -E 's/.*-bcp([0-9]+)$/\1/' | sort -u || true)

# 计数（用 `|| true` 防 set -euo pipefail；grep -c 已输出 0 时不会重复 echo）
BCP_COUNT=$(printf '%s\n' "$BCP_IDS" | grep -c "^." 2>/dev/null || true)
WT_COUNT=$(printf '%s\n' "$WT_BCP_NUMS" | grep -c "^." 2>/dev/null || true)
BCP_COUNT=${BCP_COUNT:-0}
WT_COUNT=${WT_COUNT:-0}

echo "BCP-Registry §一 提取: ${BCP_COUNT} 个 BCP-NNN"
echo "git worktree list 提取: ${WT_COUNT} 个 wt-rXXX-bcpNNN"
echo "---"

# === 3. 反向引用比对：每个 BCP-NNN 必须有对应 wt 名 ===
FAIL=0
for bcp_id in $BCP_IDS; do
  bcp_num="${bcp_id#BCP-}"
  if printf '%s\n' "$WT_BCP_NUMS" | grep -qx "$bcp_num" 2>/dev/null; then
    echo "  [OK]   $bcp_id 对齐（wt 包含 bcp${bcp_num}）"
  else
    echo "  [FAIL] $bcp_id 无对应 wt（期望 wt-r*-bcp${bcp_num} 存在）"
    FAIL=1
  fi
done

echo "---"
if [ "$FAIL" -eq 1 ]; then
  echo "FAIL: BCP 与 wt 单位不对齐（M-Root-7 派单单位错配）"
  exit 1
fi

echo "PASS: BCP 与 wt 单位对齐（${BCP_COUNT} 个 BCP 全部命中）"
exit 0
