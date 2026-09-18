#!/usr/bin/env bash
# .harness/gate.sh — 门禁执行(R30+ 三层哨兵 + 负向验证)
#
# 用法:
#   bash .harness/gate.sh                # 跑全部门禁
#   bash .harness/gate.sh --gate=N       # 跑指定门禁(N=1..N)
#   bash .harness/gate.sh --list         # 列出所有门禁
#
# 三层哨兵(R30+):
#   - 输入层:文件存在 + 退出码正确 + JSON 格式
#   - 解析层:grep + jq + awk 字段提取
#   - 负向验证:自证能红(fixture + 反向用例)
#
# 门禁列表(可扩展):
#   1. drift         doc ↔ db 漂移门禁(沿用兄弟 R38)
#   2. contract      合同 ↔ spec ↔ code 三向对账(沿用兄弟 R39)
#   3. compile       ipd 编译门禁(沿用)
#   4. grant-sql     ipd grant SQL 门禁(沿用)
#   5. frontend-drift ipd 前端 drift(沿用)
#   6. r41-done      39 张卡 done 门禁(R41 新增,QA 门禁矩阵)
#   7. fail-blank    失败必现(空 failures.jsonl 时 fail)
#   8. archived-at   archived_at 一致性门禁(沿用 R42-D)
#
# 退出码:
#   0 = PASS
#   1 = FAIL
#   2 = 输入层缺失(文件/依赖/参数)
#   3 = 哨兵失败(三层哨兵负向验证)
set -o pipefail
cd "$(dirname "$0")/.."

GATE=""
LIST_ONLY=false
[[ "${1:-}" == "--list" ]] && LIST_ONLY=true
[[ "${1:-}" =~ --gate= ]] && GATE="${1#--gate=}"

# 门禁列表(可扩展) — 三个 parallel indexed arrays(zsh 兼容)
GATE_KEYS=(drift_1 contract_2 compile_3 grantsql_4 frontend_5 done_6 failblank_7 archived_8)
GATE_LABELS=(drift contract compile grant-sql frontend-drift r41-done fail-blank archived-at)
GATE_CMDS=(
    "scripts/check-doc-db-drift.sh --refined --json-only"
    "scripts/check-contract-tri-source.sh --json-only"
    "mvn -pl ruoyi-modules/ruoyi-ipd -am -DskipTests -o compile"
    "scripts/check-grant-sql.sh --json-only"
    "scripts/check-ipd-frontend-drift.sh"
    "python3 scripts/check-done-gate.py --all"
    "bash -c '[[ -s .harness/evolve/failures.jsonl ]] && exit 1 || exit 0'"
    "scripts/check-merge-gate-archived-at.sh"
)

if $LIST_ONLY; then
    echo "GEP Gate 列表:"
    for i in "${!GATE_KEYS[@]}"; do
        printf "  %-15s %-30s %s\n" "${GATE_KEYS[$i]}" "${GATE_LABELS[$i]}" "${GATE_CMDS[$i]}"
    done
    exit 0
fi

FAIL=0
PASS=0
SKIP=0

run_gate() {
    local key="$1"
    local idx=-1
    for i in "${!GATE_KEYS[@]}"; do
        if [[ "${GATE_KEYS[$i]}" == "$key" ]]; then
            idx=$i
            break
        fi
    done
    if [[ $idx -eq -1 ]]; then
        echo "✗ 未知 gate: $key"
        FAIL=$((FAIL + 1))
        return
    fi
    local cmd="${GATE_CMDS[$idx]}"
    local label="${GATE_LABELS[$idx]}"
    local cmd_label="${cmd%% *}"
    echo ""
    echo "── [gate $key / $label] \$ $cmd"
    if [[ ! -f "$cmd_label" ]] && [[ "$cmd_label" != "mvn" ]] && [[ "$cmd_label" != "bash" ]] && [[ "$cmd_label" != "python3" ]]; then
        echo "   ⚠ 脚本不存在: $cmd_label — 跳过(Layer 1 自动 / 留 owner 装)"
        SKIP=$((SKIP + 1))
        return
    fi
    if OUT=$(eval "$cmd" 2>&1); then
        echo "$OUT" | tail -10
        echo "   ✓ PASS"
        PASS=$((PASS + 1))
    else
        CODE=$?
        echo "$OUT" | tail -20
        echo "   ✗ FAIL (exit $CODE)"
        FAIL=$((FAIL + 1))
        # 记录到 failures.jsonl
        mkdir -p .harness/evolve
        echo "{\"gate\":\"$label\",\"ts\":\"$(date -u +%FT%TZ)\",\"exit\":$CODE}" >> .harness/evolve/failures.jsonl 2>/dev/null || true
    fi
}

# 跑全部或指定门禁
if [[ -n "$GATE" ]]; then
    run_gate "$GATE"
else
    echo "════════ GEP GATE START $(date '+%F %T') ════════"
    for k in "${GATE_KEYS[@]}"; do
        run_gate "$k"
    done
fi

echo ""
echo "════════ GEP GATE: PASS=$PASS FAIL=$FAIL SKIP=$SKIP ════════"
if [[ $FAIL -gt 0 ]]; then
    exit 1
fi
exit 0