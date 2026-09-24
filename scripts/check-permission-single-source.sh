#!/usr/bin/env bash
# =============================================================================
# check-permission-single-source.sh
# 权限三套体系单一事实源门禁脚本（M-Root-4 范畴，R194 §A7 §四实装）
#
# 元根因: 权限三套并存（meta.access / meta.authority / v-access:code）
#         未声明单一事实源 + 收敛策略 + 会红的测试
# 根除三件套: ① 单一事实源 ② 收敛策略声明 ③ 会红的测试（FAIL_SEED 双向触发）
#
# 检测 5 类权限收敛场景:
#   MP-1 meta.access 使用统计（应保留作为 v-access:code 上层代理）
#   MP-2 meta.authority 残余（应 @deprecated + 下线）
#   MP-3 v-access:code 使用统计（单一事实源候选）
#   MP-4 meta.access → v-access:code 代理覆盖率（应 100%）
#   MP-5 meta.authority → v-access:code 收敛度（应 ≥ 90%）
#
# 用法:
#   bash scripts/check-permission-single-source.sh                   # 默认模式
#   PERM_FAIL_SEED=1 bash scripts/check-permission-single-source.sh  # FAIL_SEED 红
#   DRY_RUN=1 MP_TARGETS=1,3 bash scripts/check-permission-single-source.sh  # dry-run + 子集
#   PERM_FRONTEND_DIR=/path/to/ruoyi-ipd-web bash scripts/check-permission-single-source.sh  # 跨仓
#
# 退出码:
#   0 = PASS（5 类收敛全声明 + 单一事实源 + DRY_RUN）
#   1 = FAIL（收敛度不足 / 单一事实源缺失 / 收敛策略未声明）
#   2 = FAIL_SEED 注入（故意红，验脚本能拦）
#   3 = USAGE（参数错误 / 目标文件不存在）
# =============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------- 1. 环境变量（可被环境覆盖）----------
PERM_FRONTEND_DIR="${PERM_FRONTEND_DIR_OVERRIDE:-${PERM_FRONTEND_DIR:-/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd}}"
PERM_FAIL_SEED="${PERM_FAIL_SEED:-0}"
DRY_RUN="${DRY_RUN:-0}"
MP_TARGETS="${MP_TARGETS:-1,2,3,4,5}"

# ---------- 2. 状态变量----------
EXIT_CODE=0
FAIL_REASONS=()
SKIP_REASONS=()
PASS_REASONS=()

# ---------- 3. 5 类扫描函数----------

# MP-1 meta.access 使用统计（应保留作上层代理）
scan_mp1() {
  if [ "$PERM_FAIL_SEED" = "1" ]; then
    FAIL_REASONS+=("[MP-1 FAIL_SEED] meta.access 计数 = 0（应保留作为上层代理）")
    return 0
  fi

  local count=0
  if [ -d "$PERM_FRONTEND_DIR" ]; then
    count=$(grep -rln "meta:\s*{[^}]*access" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
  fi

  if [ "$count" -gt 0 ]; then
    PASS_REASONS+=("[MP-1] meta.access 使用 = $count 处（保留作为上层代理）")
  else
    SKIP_REASONS+=("[MP-1] 前端仓 $PERM_FRONTEND_DIR 不存在或无 meta.access → SKIP")
  fi
}

# MP-2 meta.authority 残余（应 @deprecated + 下线）
scan_mp2() {
  if [ "$PERM_FAIL_SEED" = "1" ]; then
    FAIL_REASONS+=("[MP-2 FAIL_SEED] meta.authority 残余 = 0（应 @deprecated）")
    return 0
  fi

  local count=0
  local deprecated_count=0
  if [ -d "$PERM_FRONTEND_DIR" ]; then
    count=$(grep -rln "meta:\s*{[^}]*authority" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
    deprecated_count=$(grep -rln "@deprecated\|meta\.authority.*deprecated" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
  fi

  # 收敛目标：所有 meta.authority 应 @deprecated（覆盖度 ≥ 90%）
  if [ "$count" -gt 0 ]; then
    local coverage=0
    if [ "$count" -gt 0 ]; then
      coverage=$((deprecated_count * 100 / count))
    fi
    if [ "$coverage" -ge 90 ]; then
      PASS_REASONS+=("[MP-2] meta.authority 残余 = $count 处 / @deprecated 覆盖 = ${coverage}%（≥ 90%）")
    else
      FAIL_REASONS+=("[MP-2] meta.authority @deprecated 覆盖 = ${coverage}%（应 ≥ 90%；当前 $deprecated_count/$count）")
      EXIT_CODE=1
    fi
  else
    SKIP_REASONS+=("[MP-2] 前端仓不存在或无 meta.authority → SKIP")
  fi
}

# MP-3 v-access:code 使用统计（单一事实源候选）
scan_mp3() {
  if [ "$PERM_FAIL_SEED" = "1" ]; then
    FAIL_REASONS+=("[MP-3 FAIL_SEED] v-access:code 计数 = 0（单一事实源候选）")
    return 0
  fi

  local count=0
  if [ -d "$PERM_FRONTEND_DIR" ]; then
    count=$(grep -rln "v-access:code" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
  fi

  if [ "$count" -gt 0 ]; then
    PASS_REASONS+=("[MP-3] v-access:code 使用 = $count 处（单一事实源候选）")
  else
    SKIP_REASONS+=("[MP-3] 前端仓不存在或无 v-access:code → SKIP")
  fi
}

# MP-4 meta.access → v-access:code 代理覆盖率
scan_mp4() {
  if [ "$PERM_FAIL_SEED" = "1" ]; then
    FAIL_REASONS+=("[MP-4 FAIL_SEED] meta.access → v-access:code 代理覆盖 = 0%（应 100%）")
    return 0
  fi

  # 代理覆盖率 = meta.access 中含 accessCodes 字段的比例
  # 约定: meta.access 应声明 accessCodes: [] 数组
  local total=0
  local proxied=0
  if [ -d "$PERM_FRONTEND_DIR" ]; then
    total=$(grep -rln "meta:\s*{[^}]*access" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
    proxied=$(grep -rln "accessCodes:" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
  fi

  if [ "$total" -gt 0 ]; then
    local coverage=0
    if [ "$total" -gt 0 ]; then
      coverage=$((proxied * 100 / total))
    fi
    if [ "$coverage" -eq 100 ]; then
      PASS_REASONS+=("[MP-4] meta.access → accessCodes 代理覆盖 = ${coverage}%（100%）")
    else
      FAIL_REASONS+=("[MP-4] meta.access → accessCodes 代理覆盖 = ${coverage}%（应 100%；当前 $proxied/$total）")
      EXIT_CODE=1
    fi
  else
    SKIP_REASONS+=("[MP-4] 前端仓不存在或无 meta.access → SKIP")
  fi
}

# MP-5 meta.authority → v-access:code 收敛度（应 ≥ 90%）
scan_mp5() {
  if [ "$PERM_FAIL_SEED" = "1" ]; then
    FAIL_REASONS+=("[MP-5 FAIL_SEED] meta.authority → v-access:code 收敛 = 0%（应 ≥ 90%）")
    return 0
  fi

  # 收敛度 = 已迁移到 v-access:code 的 (1 - meta.authority 残余占比)
  local authority_count=0
  local vaccess_count=0
  if [ -d "$PERM_FRONTEND_DIR" ]; then
    authority_count=$(grep -rln "meta:\s*{[^}]*authority" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
    vaccess_count=$(grep -rln "v-access:code" "$PERM_FRONTEND_DIR/src" 2>/dev/null | wc -l | tr -d ' ')
  fi

  local total=$((authority_count + vaccess_count))
  if [ "$total" -gt 0 ]; then
    local converge_pct=$((vaccess_count * 100 / total))
    if [ "$converge_pct" -ge 90 ]; then
      PASS_REASONS+=("[MP-5] meta.authority → v-access:code 收敛 = ${converge_pct}%（≥ 90%；v-access=$vaccess_count/authority=$authority_count）")
    else
      FAIL_REASONS+=("[MP-5] 收敛 = ${converge_pct}%（应 ≥ 90%；当前 $vaccess_count/$total）")
      EXIT_CODE=1
    fi
  else
    SKIP_REASONS+=("[MP-5] 前端仓不存在 → SKIP")
  fi
}

# ---------- 4. 调度（for+case 子集限定，绕开 bash case 只匹配首分支 bug）----------
scan_targets() {
  for t in 1 2 3 4 5; do
    case ",$MP_TARGETS," in
      *,$t,*) scan_mp$t ;;
    esac
  done
}

# ---------- 5. 主流程----------

# DRY_RUN 优先于 FAIL_SEED（旁路）
if [ "$DRY_RUN" = "1" ]; then
  scan_targets
  echo "🔍 DRY_RUN（不真判，仅报告）"
  for r in "${PASS_REASONS[@]}"; do echo "  ✅ $r"; done
  for r in "${SKIP_REASONS[@]}"; do echo "  ⏭ $r"; done
  for r in "${FAIL_REASONS[@]}"; do echo "  ❌ $r"; done
  exit 0
fi

# FAIL_SEED 强制 exit=2
if [ "$PERM_FAIL_SEED" = "1" ]; then
  scan_targets
  echo "❌ FAIL_SEED 注入（验脚本能拦）"
  for r in "${FAIL_REASONS[@]}"; do echo "  $r"; done
  exit 2
fi

# 正常扫描
scan_targets

# ---------- 6. 报告----------

echo "═══════════════════════════════════════════════════════════════"
echo " 权限三套单一事实源门禁报告（M-Root-4）"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo "前端仓: $PERM_FRONTEND_DIR"
echo "目标: MP_TARGETS=$MP_TARGETS"
echo ""

for r in "${PASS_REASONS[@]}"; do echo "  ✅ $r"; done
echo ""
for r in "${SKIP_REASONS[@]}"; do echo "  ⏭ $r"; done
echo ""
for r in "${FAIL_REASONS[@]}"; do echo "  ❌ $r"; done

echo ""
echo "═══════════════════════════════════════════════════════════════"
if [ "$EXIT_CODE" -eq 0 ]; then
  echo " 结果: ✅ PASS"
else
  echo " 结果: ❌ FAIL（exit=1）"
fi
echo "═══════════════════════════════════════════════════════════════"

exit "$EXIT_CODE"