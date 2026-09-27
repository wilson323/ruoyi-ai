#!/usr/bin/env bash
# scripts/check-e2e-block-gate.sh — M5 E2E 报告缺终态标记阻断
# 注：本门禁只查"有没有结论"，不查"结论是好是坏"；FAILED 终态同样放行，
#     但会在汇总行显式报四分类份数，避免 ✅ 被误读成"E2E 已闭环"（R224）
# 来源：R131 §二.2.2 M5 最小验证
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：R231 改造 — EBG_FAIL_SEED=1 → 复制真实报告剥掉终态行 → 真判据 → exit 1
#
# R231 病灶修复（看板 8500d227 待拍2 + 待拍1 配套）：
#   病灶1 E2E_DIR 缺失 exit 0 = 假绿 → 改 exit 1（期望产物目录丢失是配置错，要红）
#   病灶2 判据措辞（R224 曾补「有失败项」，本版继续对齐 check-e2e-fe-be.sh 产物）：
#     终态识别按 owner 拍板 (c) 的四类分流——
#       通过      ^状态:/^STATUS: *PASSED（含旧版 ✅…通过 兜底）
#       契约失败  FAILED 且伴 NOT_IMPLEMENTED/ENVELOPE_MISMATCH/「有失败项」（契约未实现或包络不符）
#       服务不可达 FAILED 且伴 UNREACHABLE≥1 / 鉴权失败 NO_AUTH≥1 /「后端未启」（环境类）
#       运行中    ^状态:/^STATUS: *RUNNING 或 🏃…运行中
#     四类之外无终态行 = 缺结论 → 红。（不是把失败改成通过：只是认得报告里已有的失败终态）
# 退出码词表：0=全部报告有终态结论 / 1=缺终态或产物目录缺失

set -eo pipefail

E2E_DIR="${E2E_DIR:-docs/ipd-系统说明}"
EBG_FAIL_SEED="${EBG_FAIL_SEED:-0}"
TMP_SEED_DIR=""

cleanup() {
  if [ -n "$TMP_SEED_DIR" ]; then
    rm -rf "$TMP_SEED_DIR"
  fi
}
trap cleanup EXIT

main() {
  echo "[M5] check-e2e-block-gate.sh 启动 (基线: R131, 判据: R231 四分类终态)"

  local scan_dir="$E2E_DIR"
  if [ "$EBG_FAIL_SEED" = "1" ]; then
    # 负向自证：取一份真实报告，剥掉其全部终态行，让真判据去抓"缺结论"
    local src=""
    for f in "$E2E_DIR"/E2E-验收-*.md; do
      if [ -f "$f" ]; then
        src="$f"
        break
      fi
    done
    if [ -z "$src" ]; then
      echo "❌ EBG_FAIL_SEED 需要至少一份真实报告作底（${E2E_DIR} 下无 E2E-验收-*.md）"
      exit 1
    fi
    TMP_SEED_DIR=$(mktemp -d)
    # 注：macOS sed 对多字节模式会报 illegal byte sequence，改字节安全的 grep -v 剥行
    grep -vE '^状态: |^STATUS: |有失败项|不通过|未通过|^✅.*通过' "$src" \
      > "$TMP_SEED_DIR/E2E-验收-FAIL-SEED.md" || true
    scan_dir="$TMP_SEED_DIR"
    echo "[M5] EBG_FAIL_SEED=1 → 剥掉 $(basename "$src") 的终态行生成种子副本，走真判据"
  fi

  if [ ! -d "$scan_dir" ]; then
    echo "❌ ${scan_dir} 不存在：E2E 产物目录丢失或 E2E_DIR 配置错误 → 红"
    exit 1
  fi

  local marker_missing=0 c_passed=0 c_contract=0 c_unreach=0 c_running=0
  local f verdict
  for f in "$scan_dir"/E2E-验收-*.md; do
    [ -f "$f" ] || continue
    verdict="UNKNOWN"
    if grep -qE "^状态: *RUNNING|^STATUS: *RUNNING|🏃.*运行中" "$f"; then
      verdict="RUNNING"
      c_running=$((c_running + 1))
    elif grep -qE "^状态: *PASSED|^STATUS: *PASSED" "$f"; then
      verdict="PASSED"
      c_passed=$((c_passed + 1))
    elif grep -qE "^状态: *FAILED|^STATUS: *FAILED" "$f"; then
      # 机器可读 FAILED 行存在 → 按分类汇总行细分「服务不可达」vs「契约失败」
      if grep -qE "UNREACHABLE=[1-9]|NO_AUTH=[1-9]|后端未启|前端.*未启|服务不可达" "$f"; then
        verdict="FAILED-服务不可达"
        c_unreach=$((c_unreach + 1))
      else
        verdict="FAILED-契约失败"
        c_contract=$((c_contract + 1))
      fi
    elif grep -qE "❌.*(有失败项|不通过|未通过)" "$f"; then
      # 旧版人读终态（2026-09-19 三份报告格式）：业务契约失败
      verdict="FAILED-契约失败(旧版措辞)"
      c_contract=$((c_contract + 1))
    elif grep -qE "✅.*通过" "$f"; then
      verdict="PASSED(旧版措辞)"
      c_passed=$((c_passed + 1))
    else
      echo "❌ $(basename "$f") 缺终态标记（PASSED/FAILED/RUNNING 四类均不可辨）"
      marker_missing=$((marker_missing + 1))
      continue
    fi
    echo "  · $(basename "$f") → ${verdict}"
  done

  if [ "$marker_missing" -gt 0 ]; then
    echo "🔴 ${marker_missing} 份 E2E 报告缺终态标记 → 阻断，exit 1"
    exit 1
  fi

  echo "✅ 全部 E2E 报告有终态结论：通过=${c_passed} 契约失败=${c_contract} 服务不可达=${c_unreach} 运行中=${c_running}"
  if [ "$c_passed" -eq 0 ]; then
    echo "⚠️  无一份终态为通过：M5 职责是『有结论即可放行』，真活契约闭环与否请看上面分类计数"
  fi
  exit 0
}

main "$@"
