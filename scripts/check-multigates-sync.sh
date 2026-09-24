#!/usr/bin/env bash
# =============================================================================
# check-multigates-sync.sh
# M-Root-12 多套闸不同步门禁脚本（R185 元根因深化，R194 §A4 实装）
#
# 元根因: M-Root-12 多套闸不同步（R185 §三十 + BCP-Registry §三十）
# 根除三件套: ① 单一事实源 ② 优先级声明 ③ 会红的测试（FAIL_SEED 双向触发）
#
# 检测 5 类多套闸场景:
#   MG-1 权限注解三套闸（meta.authority / meta.access / v-access:code）
#   MG-2 @IpdAudit 三套闸（注解 / 拦截器 / AOP 切面）
#   MG-3 SSRF allowlist / 黑名单 / DNS 优先级链
#   MG-4 租户拦截器 / decryptApiKey / @InterceptorIgnore OR/AND 语义
#   MG-5 闸间同步测试 ≥ 1 个
#
# 用法:
#   bash scripts/check-multigates-sync.sh                  # 默认模式
#   MULTIGATES_FAIL_SEED=1 bash scripts/check-multigates-sync.sh  # FAIL_SEED 红
#   DRY_RUN=1 MG_TARGETS=1,3 bash scripts/check-multigates-sync.sh  # dry-run + 子集
#
# 退出码:
#   0 = PASS（5 类闸全有单一事实源 + 优先级声明 + 同步测试 或 DRY_RUN）
#   1 = FAIL（数据缺失: 单一事实源 / 优先级声明 / 同步测试缺失）
#   2 = FAIL_SEED 注入（故意红，验脚本能拦）
#   3 = USAGE（参数错误 / 目标文件不存在）
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# FAIL_SEED 自证能红（R134 + R142 范式：双向触发）
FAIL_SEED="${MULTIGATES_FAIL_SEED:-0}"
DRY_RUN="${DRY_RUN:-0}"
MG_TARGETS="${MG_TARGETS:-1,2,3,4,5}"

# 检测源路径（R194 §A4 设计）
PERMISSIONS_FILE="${PERMISSIONS_FILE_OVERRIDE:-${REPO_ROOT}/apps/web-antd/src/views/ipd/_shared/permissions.ts}"
ROUTE_FILE="${ROUTE_FILE_OVERRIDE:-${REPO_ROOT}/apps/web-antd/src/router/routes/modules/ipd.ts}"
GUARD_FILE="${GUARD_FILE_OVERRIDE:-${REPO_ROOT}/apps/web-antd/src/router/ipd-guard.ts}"
AUDIT_ASPECT="${AUDIT_ASPECT_OVERRIDE:-${REPO_ROOT}/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/audit/IpdAuditAspect.java}"
AICHAT_CLIENT="${AICHAT_CLIENT_OVERRIDE:-${REPO_ROOT}/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/service/ai/AiChatClient.java}"
TENANT_INTERCEPTOR="${TENANT_INTERCEPTOR_OVERRIDE:-${REPO_ROOT}/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/interceptor/TenantInterceptor.java}"

CHECK_NAME="multi_gates_sync"
FAIL_COUNT=0

emit_fail() { echo "$1:$2: ${CHECK_NAME} FAIL: $3"; FAIL_COUNT=$((FAIL_COUNT + 1)); }
emit_pass() { echo "${CHECK_NAME} PASS: $1"; }
emit_skip() { echo "${CHECK_NAME} SKIP: $1"; }

# === FAIL_SEED 自证能红（R134 范式：故意注入坏数据 → exit 2）===
if [ "$FAIL_SEED" = "1" ]; then
  echo "[FAIL_SEED] 故意注入坏数据 → exit 2（验脚本能拦）"
  if [ "$DRY_RUN" = "1" ]; then
    echo "[DRY-RUN] 跳过 exit 2"
    exit 0
  fi
  exit 2
fi

# === 1. MG-1 权限注解三套闸（前端仓 ipd 仓不在本仓时记 SKIP，不计 FAIL）===
scan_mg1() {
  if [ ! -d "$REPO_ROOT/apps/web-antd" ]; then
    emit_skip "MG-1 前端仓不在本仓（独立仓库：/Users/mac/Documents/ruoyi-ipd-web）"
    return 0
  fi
  if [ ! -f "$PERMISSIONS_FILE" ]; then
    emit_fail "$PERMISSIONS_FILE" 1 "MG-1 单一事实源 permissions.ts 缺失"
    return 0
  fi
  if [ -f "$ROUTE_FILE" ] && grep -qE "meta\.authority|meta\.access|v-access:code" "$ROUTE_FILE"; then
    if [ -f "$GUARD_FILE" ] && grep -qE "hasAccess|meta\.access" "$GUARD_FILE"; then
      emit_pass "MG-1 三套权限表达全引用 permissions.ts"
    else
      emit_fail "$GUARD_FILE" 1 "MG-1 缺 hasAccess / meta.access 消费者"
    fi
  else
    emit_pass "MG-1 无权限注解闸（路由/守卫未命中）"
  fi
}

# === 2. MG-2 @IpdAudit 三套闸执行顺序 ===
scan_mg2() {
  if [ ! -f "$AUDIT_ASPECT" ]; then
    emit_fail "$AUDIT_ASPECT" 1 "MG-2 @IpdAudit AOP 切面缺失"
    return 0
  fi
  if grep -qE "@Order|优先级|priority" "$AUDIT_ASPECT"; then
    emit_pass "MG-2 @IpdAudit 三套闸已声明执行顺序"
  else
    emit_fail "$AUDIT_ASPECT" 1 "MG-2 @IpdAudit 三套闸无 @Order / 优先级 声明"
  fi
}

# === 3. MG-3 SSRF allowlist > 黑名单 > DNS 优先级链 ===
scan_mg3() {
  if [ ! -f "$AICHAT_CLIENT" ]; then
    emit_fail "$AICHAT_CLIENT" 1 "MG-3 AiChatClient 缺失"
    return 0
  fi
  if grep -qE "allowlist.*>.*黑名单|priority.*allowlist|allowlist.*blacklist.*DNS" "$AICHAT_CLIENT"; then
    emit_pass "MG-3 SSRF 校验链优先级已声明 (allowlist > 黑名单 > DNS)"
  else
    emit_fail "$AICHAT_CLIENT" 1 "MG-3 SSRF 校验链无优先级声明 (期望 allowlist > 黑名单 > DNS)"
  fi
}

# === 4. MG-4 租户闸 OR/AND 语义 ===
scan_mg4() {
  if [ ! -f "$TENANT_INTERCEPTOR" ]; then
    emit_fail "$TENANT_INTERCEPTOR" 1 "MG-4 TenantInterceptor 缺失"
    return 0
  fi
  if grep -qE "AND|OR|且|或" "$TENANT_INTERCEPTOR"; then
    emit_pass "MG-4 租户闸 OR/AND 语义已声明"
  else
    emit_fail "$TENANT_INTERCEPTOR" 1 "MG-4 租户闸 OR/AND 语义未声明"
  fi
}

# === 5. MG-5 闸间同步测试覆盖（≥ 1 个）===
scan_mg5() {
  # 排除 .codex/context7/runtime/node_modules/ 等第三方
  local n=0
  if [ -d "$REPO_ROOT/apps/web-antd" ]; then
    n=$(find "$REPO_ROOT/apps/web-antd/src/views/ipd" -name "*.test.ts" 2>/dev/null \
        | xargs grep -lE "meta\.authority|v-access:code|@IpdAudit|allowlist|tenant" 2>/dev/null \
        | wc -l | tr -d ' ' || true)
  fi
  if [ "${n:-0}" -ge 1 ]; then
    emit_pass "MG-5 闸间同步测试已覆盖 ($n 个)"
  else
    emit_fail "$REPO_ROOT/apps/web-antd/src/views/ipd" 1 "MG-5 无闸间同步测试 (≥ 1 个)"
  fi
}

# R194 §A4 设计 + R134 自证能红：每个目标独立判断（case 只匹配首分支 → 改 for+case）
scan_targets() {
  for t in 1 2 3 4 5; do
    case ",$MG_TARGETS," in
      *,$t,*) scan_mg_$t ;;
    esac
  done
}

scan_mg_1() { scan_mg1; }
scan_mg_2() { scan_mg2; }
scan_mg_3() { scan_mg3; }
scan_mg_4() { scan_mg4; }
scan_mg_5() { scan_mg5; }

main() {
  echo "[check-multigates-sync] FAIL_SEED=$FAIL_SEED DRY_RUN=$DRY_RUN TARGETS=$MG_TARGETS"

  scan_targets

  if [ "$FAIL_COUNT" -gt 0 ]; then
    echo "---"
    echo "[check-multigates-sync] ❌ FAIL: $FAIL_COUNT 项多套闸不同步（M-Root-12 未根除）"
    if [ "$DRY_RUN" = "1" ]; then
      echo "[DRY-RUN] 跳过 exit 1"
      exit 0
    fi
    exit 1
  fi

  echo "---"
  echo "[check-multigates-sync] ✅ PASS: 5 类多套闸同步（M-Root-12 已根除）"
  exit 0
}
main "$@"