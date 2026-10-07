#!/usr/bin/env bash
# scripts/check-lint-reports-freshness.sh — H-15 lint-reports 防漂移脚本
# 来源：R130 §六 D6 + R131 §二.2.2 假绿类型 6 + R132 §一.3
#       2026-10-03 重写判定逻辑（见下「本门禁原先的三处自我抵消」）
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：LINT_FAIL_SEED=1 → 在临时目录造 11 份报告 + 满窗口基线 → 走真实判定 → exit 1
#
# ── 本门禁原先的三处自我抵消（2026-10-03 实测，非推断）──────────────────
# 1) 「1h 增量」里没有任何时间。第 76 行在**每次成功运行后**都把当前数写回快照，
#    于是基线 = 「上次运行时的数」，而不是「1 小时前的数」。报告数若分多次小幅增长，
#    每次运行都被自己的输出重置基线 → delta 永远 ≤ 阈值 → 恒绿。失败分支（第 53 行）
#    也回写，于是连唯一会红的那次都自我治愈，红一次后再也不会红。
#    实测：快照写在 2026-09-20（commit 8f34c299），内容 137；工作树 144。
#    所谓「1h 增量 7」实为「9 月 20 日至今的增量」。
# 2) 跨 commit 那半边两侧用了**两把不同的尺子**：current 用 `find -type f` 递归数文件，
#    prev 用 `git show HEAD~1:<dir>` 数**目录条目**（非递归）。实测同一次：140 vs 141。
#    本目录恰好接近扁平，差 1；目录一旦嵌套，两侧差值会完全失真。
#    另：`git show` 失败时 `|| echo` 不触发（wc 仍返回 0），prev 静默变 0 →
#    报告数一旦超过阈值就**永远红**。
# 3) FAIL_SEED 自证往**已跟踪目录**写 11 个 .fail-seed-*.md 且不清理，自证本身污染仓库。
#
# ── 本次修法 ────────────────────────────────────────────────────────────
# - 快照格式改为 `count:epoch`；只在基线**满窗口**（默认 3600s）时比较与刷新。
#   窗口内重复运行只报「跳过」，不刷新基线 —— 基线因此真正是「一个窗口前」。
#   旧格式（纯数字，无冒号）按 epoch=0「很旧」处理，可平滑升级。
# - 失败分支**不回写**快照，红不会被自己治愈。
# - 跨 commit 两侧统一为「递归文件 + 同后缀过滤」，并绕开 git 对中文路径的八进制转义
#   （core.quotePath=false；实测未加时 grep 命中 0，是尺子错不是仓库空）。
# - 新增 FRESHNESS_READONLY=1：只判定不写快照，供提交路径 / CI 使用，避免跑门禁脏了工作树。
# - FAIL_SEED 改为在 mktemp 目录里走**真实判定路径**，仓库零写入。

set -euo pipefail

# === 配置区 ===
LINT_REPORTS_DIR="${LINT_REPORTS_DIR:-docs/ipd-系统说明/lint-reports}"
FRESHNESS_THRESHOLD="${FRESHNESS_THRESHOLD:-10}"        # 一个窗口内的增量阈值
FRESHNESS_WINDOW_SECONDS="${FRESHNESS_WINDOW_SECONDS:-3600}"  # 窗口长度（默认 1h）
SNAPSHOT_FILE="${SNAPSHOT_FILE:-.harness/lint-reports-snapshot.txt}"
CROSS_COMMIT_THRESHOLD="${CROSS_COMMIT_THRESHOLD:-50}"  # 跨 commit 阈值
FRESHNESS_READONLY="${FRESHNESS_READONLY:-0}"           # 1=只判定不写快照
LINT_FAIL_SEED="${LINT_FAIL_SEED:-0}"

count_reports() { # dir -> 递归 *.md/*.json 文件数（唯一口径，两侧共用）
  find "$1" -type f \( -name '*.md' -o -name '*.json' \) 2>/dev/null | wc -l | tr -d ' '
}

count_reports_at_rev() { # rev dir -> 同一口径，但数的是该 commit 的树
  # -z 绕开 core.quotePath 的八进制转义（本仓路径含中文，不加会静默数出 0）
  git ls-tree -r -z --name-only "$1" -- "$2" 2>/dev/null \
    | tr '\0' '\n' | grep -cE '\.(md|json)$' || true
}

main() {
  echo "[H-15] check-lint-reports-freshness.sh 启动 (基线: R132 + 2026-10-03 重写)"

  if [ ! -d "$LINT_REPORTS_DIR" ]; then
    # owner 2026-10-07 清空 lint-reports（148 份 / 7.2MB）后改本分支。
    #
    # 旧行为：目录不存在 → 打一行警告 → return 0（不阻断）。
    # 问题：这条路径下 H-15 **永远不会被判红**，等于检查名存实亡——
    #       而 8 个生成脚本仍在往这个目录写，任何漂移都再也无人看得见。
    #       正是本仓反复吃的「守卫漏分支式静默失效」：失败长得像成功。
    #
    # 新行为：目录空/不存在 = 「本该有报告却没有」= **判红**。
    #   真正的验证不是「目录在不在」，而是**生成器还能不能产出报告**。
    #   所以：先尝试重新生成一份，生成不出来才是真故障。
    if [ "${LINT_FAIL_SEED:-0}" = "1" ]; then
      echo "[H-15] FAIL_SEED: 强制模拟「该有报告却没有」 → 判定失败"
      return 1
    fi
    echo "🔴 $LINT_REPORTS_DIR 不存在 → 报告缺失，H-15 观测能力已失效"
    echo "   处置: bash scripts/check-doc-link.sh 等 8 个生成器可重新生成；"
    echo "         若生成器也跑不动，说明检查链真的坏了，必须修。"
    return 1
  fi

  local current_count; current_count=$(count_reports "$LINT_REPORTS_DIR")
  local now; now=$(date +%s)
  local rc=0

  # ── 1h 窗口判定 ─────────────────────────────────────────────────────────
  local delta=0 window_elapsed=1 last_count="" last_epoch=0
  if [ -f "$SNAPSHOT_FILE" ]; then
    local raw; raw=$(tr -d ' \n' < "$SNAPSHOT_FILE")
    last_count="${raw%%:*}"
    if [ "$raw" != "${raw#*:}" ]; then last_epoch="${raw##*:}"; fi
    case "$last_count" in ''|*[!0-9]*) last_count="" ;; esac
    case "$last_epoch" in ''|*[!0-9]*) last_epoch=0 ;; esac

    if [ -z "$last_count" ]; then
      echo "⚠️  快照内容不可解析（raw='$raw'）→ 按无快照处理"
    else
      local age=$(( now - last_epoch ))
      # epoch=0 视为「很旧」（兼容旧格式），允许比较
      if [ "$last_epoch" -gt 0 ] && [ "$age" -lt "$FRESHNESS_WINDOW_SECONDS" ]; then
        window_elapsed=0
        echo "[H-15] 窗口未满：基线写于 ${age}s 前（< ${FRESHNESS_WINDOW_SECONDS}s），跳过增量比较且不刷新基线"
        echo "       current=$current_count  last=${last_count}（差值 $(( current_count - last_count )) 仅供参考，不作为判定）"
        delta=$(( current_count - last_count ))
      else
        delta=$(( current_count - last_count ))
        echo "[H-15] 窗口已满：current=$current_count  last=$last_count  基线年龄=${age}s  delta=$delta"
        if [ "$delta" -gt "$FRESHNESS_THRESHOLD" ]; then
          echo "🔴 一个窗口内增量超阈值 ($delta > $FRESHNESS_THRESHOLD)"
          echo "   建议：人工 review 新增 lint-reports 是否需要处理"
          echo "   （失败时不回写快照 —— 回写会让红被自己治愈）"
          rc=1
        fi
      fi
    fi
  else
    echo "[H-15] 无快照，创建基线: $current_count"
  fi

  # ── 跨 commit 判定（两侧同一口径）──────────────────────────────────────
  local cross_delta="" prev_count=""
  if [ "$rc" -eq 0 ] && command -v git >/dev/null 2>&1 \
     && git rev-parse --verify -q HEAD~1 >/dev/null 2>&1; then
    prev_count=$(count_reports_at_rev HEAD~1 "$LINT_REPORTS_DIR")
    if [ -z "$prev_count" ]; then
      echo "[H-15] 跨 commit：HEAD~1 无此目录，跳过（不与 0 比较）"
    else
      cross_delta=$(( current_count - prev_count ))
      echo "[H-15] 跨 commit：current=$current_count  HEAD~1=$prev_count  delta=$cross_delta"
      if [ "$cross_delta" -gt "$CROSS_COMMIT_THRESHOLD" ]; then
        echo "🔴 跨 commit 增量超阈值 ($cross_delta > $CROSS_COMMIT_THRESHOLD)"
        rc=1
      fi
    fi
  fi

  # ── 刷新基线（三条件同时满足才写）──────────────────────────────────────
  if [ "$FRESHNESS_READONLY" = "1" ]; then
    echo "[H-15] READONLY=1 → 不写快照（本应写: ${current_count}:${now}）"
  elif [ "$rc" -ne 0 ]; then
    echo "[H-15] 判定失败 → 不写快照"
  elif [ "$window_elapsed" -eq 0 ]; then
    echo "[H-15] 窗口未满 → 不写快照（保持窗口继续累计）"
  else
    mkdir -p "$(dirname "$SNAPSHOT_FILE")"
    printf '%s:%s\n' "$current_count" "$now" > "$SNAPSHOT_FILE"
    echo "[H-15] 已刷新基线: ${current_count}:${now}"
  fi

  if [ "$rc" -eq 0 ]; then
    echo "✅ lint-reports freshness PASS（窗口内 delta=$delta, 跨 commit delta=${cross_delta:-N/A}）"
  fi
  return "$rc"
}

# === 自证能红：走真实判定路径，仓库零写入 ===
if [ "$LINT_FAIL_SEED" = "1" ]; then
  SEED_DIR="$(mktemp -d)"
  SEED_SNAP="$(mktemp)"
  for i in $(seq 1 11); do : > "$SEED_DIR/seed-$i.md"; done
  printf '0:0\n' > "$SEED_SNAP"   # 基线 0 + epoch=0（很旧）→ 窗口已满 → 11 > 10 必红
  echo "[H-15] FAIL_SEED=1 → 临时目录造 11 份报告 + 满窗口基线，走真实判定路径"
  set +e
  LINT_REPORTS_DIR="$SEED_DIR" SNAPSHOT_FILE="$SEED_SNAP" main
  seed_rc=$?
  set -e
  echo "[H-15] FAIL_SEED 退出码=${seed_rc}（期望 1）；快照未被写回=$([ "$(cat "$SEED_SNAP")" = "0:0" ] && echo 是 || echo 否)"
  find "$SEED_DIR" -type f -delete 2>/dev/null || true
  rmdir "$SEED_DIR" 2>/dev/null || true
  rm -f "$SEED_SNAP" 2>/dev/null || true
  exit "$seed_rc"
fi

main
exit $?
