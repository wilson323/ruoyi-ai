#!/usr/bin/env bash
# scripts/check-m1m5-landed.sh — M-Root-4 M1-M5 落地门禁（有效性探针版）
# 来源：R142 §3.2 S-4；R224 判据升级；R231 再升级（看板 8500d227 待拍2）
# 撞车 0 让路：✅ scripts/ 白名单
# 关系：让 R131 §2.2 的 5 个验证脚本从"文档名"升级为"可跑且会红的门禁"
# 自证能红：M1M5_FAIL_SEED=1 → 直接 exit 1（本门禁自身负向探针）
#
# R231 升级根因：旧版「find -name 命中即报 5/5 全部实装 ✅」= 存在性冒充有效性，
#   M1 默认路径指向不存在文件仍永远 exit 0 的死件时它照样报绿（虚假完成）。
#   现在对每个门禁实跑三探针，用退出码说话：
#     探针1 normal      ：真实数据源下跑，rc 必须落在该脚本自身声明词表内
#     探针2 missing-input：期望输入指向 /nonexistent，rc 必须 ≠0（=0 即"数据丢了还绿"死件）
#     探针3 fail_seed   ：FAIL_SEED=1 注入违规，rc 必须 ≠0（=0 即"违规拦不住"死件）
#   头部含 "DEPRECATED R" 标记的脚本 → 状态=已废弃（文档化废弃，不计死件、不再执行）。
#   配套脚本 check-e2e-fe-be.sh 只做存在+语法检查（运行会产出报告文件且依赖后端存活，
#   由其自身 + M5 分类联动负责，不在本门禁执行，防探针污染产物）。
# 退出码词表：0=名册全部 有效实装/已废弃 / 1=存在死件、坏件或缺失

set -uo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)

# 名册：脚本 | 输入环境变量 | FAIL_SEED 环境变量 | 声明退出码词表
GATE_NAMES=("M1" "M2" "M3" "M4" "M5")
GATE_SCRIPTS=(
  "check-kanban-section-shape.sh"
  "check-time-redline.sh"
  "check-dispatch-sequence.sh"
  "check-cross-repo-cd-guard.sh"
  "check-e2e-block-gate.sh"
)
GATE_INPUT_VARS=("KANBAN_FILE" "PAIBAN_DIR" "WT_REGISTRY" "SHELL_HISTORY" "E2E_DIR")
GATE_SEED_VARS=("KSS_FAIL_SEED" "TR_FAIL_SEED" "DS_FAIL_SEED" "CRC_FAIL_SEED" "EBG_FAIL_SEED")
GATE_VOCAB=("0 2" "0 1 2" "0 2" "0" "0 1")

in_vocab() {
  local rc="$1" vocab="$2" v
  for v in $vocab; do
    if [ "$rc" = "$v" ]; then
      return 0
    fi
  done
  return 1
}

main() {
  echo "[M-Root-4] check-m1m5-landed.sh 启动 (基线: R142 §3.2 S-4, 判据: R231 三探针有效性)"

  if [ "${M1M5_FAIL_SEED:-0}" = "1" ]; then
    echo "[FAIL_SEED] 本门禁自身负向探针 → 强制判定未落地"
    echo "❌ M1M5_FAIL_SEED 注入 → exit 1"
    exit 1
  fi

  local broken=0
  local i name script ivar svar vocab
  local path status p1rc p2rc p3rc verdict note
  printf '%-4s %-34s %-8s %-14s %-14s %-14s %s\n' "编号" "脚本" "状态" "normal rc" "missing rc" "seed rc" "判定依据"
  for i in "${!GATE_NAMES[@]}"; do
    name="${GATE_NAMES[$i]}"
    script="${GATE_SCRIPTS[$i]}"
    ivar="${GATE_INPUT_VARS[$i]}"
    svar="${GATE_SEED_VARS[$i]}"
    vocab="${GATE_VOCAB[$i]}"
    path="${SCRIPT_DIR}/${script}"
    note=""
    p1rc="-"; p2rc="-"; p3rc="-"

    if [ ! -f "$path" ]; then
      printf '%-4s %-34s %-8s %-14s %-14s %-14s %s\n' "$name" "$script" "❌缺失" "-" "-" "-" "文件不存在"
      broken=$((broken + 1))
      continue
    fi
    if ! bash -n "$path" 2>/dev/null; then
      printf '%-4s %-34s %-8s %-14s %-14s %-14s %s\n' "$name" "$script" "❌语法坏" "-" "-" "-" "bash -n 解析失败"
      broken=$((broken + 1))
      continue
    fi
    if head -n 3 "$path" | grep -q "DEPRECATED R"; then
      printf '%-4s %-34s %-8s %-14s %-14s %-14s %s\n' "$name" "$script" "🗑已废弃" "(不执行)" "(不执行)" "(不执行)" "头部 DEPRECATED 标记，已摘出功能名册"
      continue
    fi

    # 探针1：真实数据源（用调用方环境原样跑；env 里不注入空值，防覆盖脚本默认路径）
    probe "$path"
    p1rc="$rc_captured"
    # 探针2：输入缺失（指向不存在路径）
    probe "$path" "${ivar}=/nonexistent/R231-landed-probe"
    p2rc="$rc_captured"
    # 探针3：FAIL_SEED 违规注入
    probe "$path" "${svar}=1"
    p3rc="$rc_captured"

    verdict="✅有效实装"
    if ! in_vocab "$p1rc" "$vocab"; then
      verdict="❌坏件(rc越词表)"
      broken=$((broken + 1))
    elif [ "$p2rc" = "0" ]; then
      verdict="❌死件(输入丢失仍绿)"
      broken=$((broken + 1))
    elif [ "$p3rc" = "0" ]; then
      verdict="❌死件(FAIL_SEED 不红)"
      broken=$((broken + 1))
    fi
    if [ "$p1rc" != "0" ]; then
      note="${note}normal=${p1rc} 属真实状态（红=门禁在说真话） "
    fi
    printf '%-4s %-34s %-8s %-14s %-14s %-14s %s%s\n' "$name" "$script" "$status" "${p1rc}/{${vocab}}" "${p2rc}≠0?" "${p3rc}≠0?" "$verdict" " $note"
  done

  # 配套脚本（不执行探针，只验存在+语法；有效性由 M5 联动 + 后端实跑证据负责）
  local febe="${SCRIPT_DIR}/check-e2e-fe-be.sh"
  if [ -f "$febe" ] && bash -n "$febe" 2>/dev/null; then
    printf '%-4s %-34s %-8s %-14s %-14s %-14s %s\n' "配套" "check-e2e-fe-be.sh" "✅在编" "-" "-" "-" "登录取token+四类记账(R224/R231)；实跑需后端，见其产报告"
  else
    printf '%-4s %-34s %-8s %-14s %-14s %-14s %s\n' "配套" "check-e2e-fe-be.sh" "❌缺失/语法坏" "-" "-" "-" "待拍1 配套脚本异常"
    broken=$((broken + 1))
  fi

  echo ""
  if [ "$broken" -gt 0 ]; then
    echo "🔴 M-Root-4：${broken} 个门禁未达'有效实装/已废弃'，exit 1"
    exit 1
  fi
  echo "✅ M-Root-4：名册内全部门禁 = 有效实装或文档化废弃（三探针：真实跑/缺失红/注入红）"
  exit 0
}

# 执行探针：$1=脚本，其余为 VAR=val 前缀；结果落 rc_captured/out_captured
probe() {
  local path="$1"
  shift
  local out rc
  out=$(env "$@" bash "$path" 2>&1) && rc=0 || rc=$?
  rc_captured="$rc"
  out_captured="$out"
  return 0
}

main "$@"
