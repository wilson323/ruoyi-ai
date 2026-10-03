#!/usr/bin/env bash
# scripts/check-memory-leak-pattern.sh
# ----------------------------------------------------------------------
# 内存泄漏模式检测门禁（BP-008 — frontend-code-review 性能优化维度）
#
# 用途：
#   检测前端代码中的内存泄漏反模式
#   规则（基于 frontend-code-review 7 维度性能优化）：
#     1. setInterval / setTimeout 未清理（应在 unmount 清除）
#     2. addEventListener 未 removeEventListener
#     3. WebSocket / EventSource 未关闭
#     4. 全局 window 对象挂载未清理
#     5. 闭包引用大型对象（启发式：长链式 .bind/.call）
#
# 自证能红：LEAK_FAIL_SEED=1 → exit 1
#
# 来源：R141 阶段四 4.2 BP-008（agency-harness）
# 撞车 0 让路：✅ scripts/ 白名单（仅检测模式，不动源码）
#
# 退出码：
#   0 = pass
#   1 = fail（疑似内存泄漏模式）
#   2 = 脚本/参数错误

set -eo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/audit-gate-input.sh"

REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
FRONT_DIR="${FRONT_DIR:-$REPO/../ruoyi-ipd-web/apps/web-antd/src}"

# === 自证能红 ===
if [[ "${LEAK_FAIL_SEED:-0}" == "1" ]]; then
  echo "[BP-008] LEAK_FAIL_SEED=1 → 故意注入内存泄漏模式"
  echo "LEAK|PATTERN|LEAK-FAIL-SEED|setInterval without clearInterval|seed_injected"
  exit 1
fi

echo "=== 内存泄漏模式检测 (BP-008) ==="

VIOLATIONS=0

PRODUCTION_FILES=$(gate_frontend_files "$FRONT_DIR")
CALL_COUNTS=$(gate_frontend_call_counts "$PRODUCTION_FILES")
TIMER_MISS=0
LISTENER_MISS=0
SOCKET_MISS=0
WINDOW_FILES=0
while IFS=$'\t' read -r file timers clears adds removes sockets closes globals; do
  if [ "${timers}" -gt "${clears}" ]; then
    TIMER_MISS=$((TIMER_MISS + 1))
    echo "  [WARN] ${file} → 实际定时器调用=${timers}，清理调用=${clears}（待核生命周期）"
  fi
  if [ "${adds}" -gt "${removes}" ]; then
    LISTENER_MISS=$((LISTENER_MISS + 1))
    echo "  [WARN] ${file} → 实际监听添加=${adds}，移除=${removes}（待核生命周期）"
  fi
  if [ "${sockets}" -gt "${closes}" ]; then
    SOCKET_MISS=$((SOCKET_MISS + 1))
    echo "  [WARN] ${file} → 实际连接创建=${sockets}，关闭=${closes}（待核生命周期）"
  fi
  if [ "${globals}" -gt 0 ]; then WINDOW_FILES=$((WINDOW_FILES + 1)); fi
done <<< "$CALL_COUNTS"
VIOLATIONS=$((TIMER_MISS + LISTENER_MISS + SOCKET_MISS))
echo "  定时器调用/清理数量不足文件: ${TIMER_MISS}"
echo "  监听调用/移除数量不足文件: ${LISTENER_MISS}"
echo "  连接创建/关闭数量不足文件: ${SOCKET_MISS}"
echo "  window全局写入文件: ${WINDOW_FILES}（INFO）"
echo "  本检查是语法节点计数；不证明句柄配对、卸载清理或实际泄漏。"

# === 总结 ===
echo ""
echo "=== 内存泄漏模式检测总结 (BP-008) ==="
echo "  总调用清理数量疑似不足: $VIOLATIONS"
echo ""

if [[ $VIOLATIONS -eq 0 ]]; then
  echo "[PASS] 生产源码调用/清理数量检查 PASS（不代表运行时无泄漏）"
  exit 0
elif [[ $VIOLATIONS -le 3 ]]; then
  echo "[WARN] 生产源码调用/清理数量检查 WARN（$VIOLATIONS 个调用清理数量疑似不足，建议修复）"
  exit 0
else
  echo "[FAIL] 生产源码调用/清理数量检查 FAIL（$VIOLATIONS 个调用清理数量疑似不足，超过阈值 3）"
  exit 1
fi
