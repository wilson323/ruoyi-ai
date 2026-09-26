#!/usr/bin/env bash
# scripts/check-m1m5-landed.sh — M-Root-4 M1-M5 落地门禁
# 来源：R142 §3.2 S-4；R224 判据升级
# 撞车 0 让路：✅ scripts/ 白名单
# 关系：让 R131 §2.2 写的 5 个验证脚本从"文档名"升级为"可跑门禁" — 任何 1 个未实装 = FAIL
# 自证能红：FAIL_SEED=1 → 模拟 5 脚本全 0 hit → exit 1
#
# R224 升级：旧版只查 find 命中数，所以 M4 实跑永远 rc=1、M1 默认路径根本不存在
#   （docs/ipd-系统说明/看镜像.md 从未存在）时，它仍报"5/5 全部实装 ✅"。
#   现在逐脚本验四件事：存在 → bash -n 可解析 → 实跑退出码 ∈ 该脚本自己文档声明的词表
#   → 是否被任何宿主接线；rc=0 但输入不存在则记"空跑"。前两项与退出码不符 = FAIL，
#   接线/空跑只记账不阻断（是否上岗属 owner 拍板范围）。

set -euo pipefail

# 5 个 R131 §2.2 M1-M5 验证脚本清单（R142 §3.2 S-4 锁定）
M1M5_SCRIPTS=(
  "check-kanban-section-shape.sh"
  "check-time-redline.sh"
  "check-dispatch-sequence.sh"
  "check-cross-repo-cd-guard.sh"
  "check-e2e-block-gate.sh"
)

# 每个脚本自己文档（文件头注释）声明的正常退出码词表；实跑落在词表外 = 未真正落地
# M1: 头部 "正常→exit 0 / 缺子标题→exit 2"，实测 0（但属空跑，见下方告警）
# M2: 实测 0；M3: 实测 0
# M4: 头部只声明 "正常→exit 0 / FAIL_SEED→exit 2"，实测永远 1（pipefail + grep 无匹配）
# M5: 实测 0（R224 判据修正后）
declared_rc() {
  case "$1" in
    check-kanban-section-shape.sh) echo "0 2" ;;
    check-time-redline.sh)         echo "0 1 2" ;;
    check-dispatch-sequence.sh)    echo "0 1" ;;
    check-cross-repo-cd-guard.sh)  echo "0 2" ;;
    check-e2e-block-gate.sh)       echo "0 1" ;;
    *)                             echo "0" ;;
  esac
}

# 查该脚本是否被任何宿主引用（pre-commit hook / CI workflow / githooks / package.json）
wired_hosts() {
  local name="$1" hits=()
  while IFS= read -r h; do
    # set -e 下不用 [ ] && ：复合命令尾行的非 0 退出码会传染给 while/脚本
    if [[ -n "$h" ]]; then
      hits+=("$h")
    fi
  done < <(grep -rl -- "$name" .claude/hooks .githooks .github/workflows package.json 2>/dev/null || true)
  echo "${#hits[@]}"
}

main() {
  echo "[M-Root-4] check-m1m5-landed.sh 启动 (基线: R142 §3.2 S-4, 判据 R224)"

  # FAIL_SEED：模拟 5 脚本全 0 hit
  if [ "${M1M5_FAIL_SEED:-0}" = "1" ]; then
    echo "[FAIL_SEED] 模拟 5 脚本全 0 hit"
    echo "❌ M1M5_FAIL_SEED 注入 → M1-M5 验证脚本视为未实装"
    exit 1
  fi

  local broken=0
  local total_wired=0
  local out rc allowed ok
  printf '%-34s %-6s %-6s %-12s %-8s %s\n' "脚本" "存在" "语法" "rc(实/预)" "接线宿主" "备注"
  for name in "${M1M5_SCRIPTS[@]}"; do
    local path="scripts/${name}"
    if [ ! -f "$path" ]; then
      printf '%-34s %-6s %-6s %-12s %-8s %s\n' "$name" "❌缺" "-" "-" "-" "find → 0 hit"
      broken=$((broken + 1))
      continue
    fi
    if ! bash -n "$path" 2>/dev/null; then
      printf '%-34s %-6s %-6s %-12s %-8s %s\n' "$name" "✅" "❌" "-" "-" "bash -n 解析失败"
      broken=$((broken + 1))
      continue
    fi
    out=$(bash "$path" 2>&1) && rc=0 || rc=$?
    allowed=$(declared_rc "$name")
    ok=0
    for a in $allowed; do
      # 同上：这里必须用 if，否则尾项不匹配时 for 返回 1，set -e 会直接掉进程
      if [ "$rc" = "$a" ]; then
        ok=1
      fi
    done
    local nhosts
    nhosts=$(wired_hosts "$name")
    total_wired=$((total_wired + nhosts))
    local note=""
    if [ "$ok" != "1" ]; then
      note="退出码 $rc 不在自身声明词表($allowed)"
      printf '%-34s %-6s %-6s %-12s %-8s %s\n' "$name" "✅" "✅" "$rc/{${allowed}}" "$nhosts" "❌ $note"
      broken=$((broken + 1))
      continue
    fi
    if [ "$rc" = "0" ] && printf '%s' "$out" | grep -qE "不存在|文件缺失"; then
      note="⚠️ 空跑：输入不存在仍 exit 0"
    fi
    printf '%-34s %-6s %-6s %-12s %-8s %s\n' "$name" "✅" "✅" "$rc/{${allowed}}" "$nhosts" "$note"
  done

  echo ""
  echo "[M-Root-4] 宿主接线总数：${total_wired}（>0 才说明真的有门禁在跑，不是只躺着）"
  if [ "$total_wired" -eq 0 ]; then
    echo "⚠️  M1-M5 无一被 pre-commit / CI / githooks 引用 → 上岗与否待 owner 拍板"
  fi

  if [ "$broken" -gt 0 ]; then
    echo "🔴 M-Root-4 M1-M5 有 ${broken} 个未真正落地（缺文件 / 语法坏 / 退出码超出自身词表），exit 1"
    exit 1
  fi

  echo "✅ M-Root-4 M1-M5 验证脚本全部实装且实跑退出码符合各自声明"
  exit 0
}

main "$@"
