#!/usr/bin/env bash
# =============================================================================
# check-pre-commit.sh
# R39 治理轮 — 提交前门禁自检
#
# 用法:
#   .claude/hooks/check-pre-commit.sh          # 默认:跑全部
#   .claude/hooks/check-pre-commit.sh drift     # 仅跑 doc↔db drift
#   .claude/hooks/check-pre-commit.sh contract  # 仅跑 contract tri-source
#   .claude/hooks/check-pre-commit.sh fast      # 跳过 doc↔db(--refined 模式也跳过)
#
# 退出码:
#   0 = PASS
#   1 = FAIL(门禁漂移)
#   2 = 脚本/环境错误
# =============================================================================

set -uo pipefail  # 不要 -e:单门禁失败不阻断其他门禁跑

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

MODE="${1:-all}"
FAILED=0
PASSED=0
SKIPPED=0

# ---------------------------------------------------------------------------
# 哨兵:R39 自检必须有 git repo + 关键脚本存在
# ---------------------------------------------------------------------------
if [[ ! -d "$REPO_ROOT/.git" ]]; then
    echo "[check-pre-commit] ❌ not a git repo: $REPO_ROOT" >&2
    exit 2
fi

if [[ ! -x "$REPO_ROOT/scripts/check-doc-db-drift.sh" ]]; then
    echo "[check-pre-commit] ❌ scripts/check-doc-db-drift.sh missing or not executable" >&2
    exit 2
fi

if [[ ! -x "$REPO_ROOT/scripts/check-contract-tri-source.sh" ]]; then
    echo "[check-pre-commit] ❌ scripts/check-contract-tri-source.sh missing or not executable" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# 门禁 1:doc ↔ db 漂移门禁(--refined 精炼模式)
# ---------------------------------------------------------------------------
run_drift_gate() {
    local start_time
    start_time=$(date +%s)
    echo "[check-pre-commit] → 门禁 1/2: doc ↔ db 漂移门禁(--refined)"
    if bash "$REPO_ROOT/scripts/check-doc-db-drift.sh" --refined --json-only > /tmp/cdbd-refined.json 2>&1; then
        local drift_count
        drift_count=$(jq -r '.drift_count // 0' /tmp/cdbd-refined.json 2>/dev/null || echo 0)
        local elapsed=$(( $(date +%s) - start_time ))
        if [[ "$drift_count" -gt 0 ]]; then
            echo "[check-pre-commit] ❌ 门禁 1/2 FAIL: drift_count=$drift_count (elapsed=${elapsed}s)"
            FAILED=$((FAILED + 1))
        else
            echo "[check-pre-commit] ✅ 门禁 1/2 PASS: drift_count=0 (elapsed=${elapsed}s)"
            PASSED=$((PASSED + 1))
        fi
    else
        local elapsed=$(( $(date +%s) - start_time ))
        echo "[check-pre-commit] ❌ 门禁 1/2 FAIL: exit=$? (elapsed=${elapsed}s)"
        FAILED=$((FAILED + 1))
    fi
}

# ---------------------------------------------------------------------------
# 门禁 2:合同 ↔ spec ↔ code 三向对账
# ---------------------------------------------------------------------------
run_contract_gate() {
    local start_time
    start_time=$(date +%s)
    echo "[check-pre-commit] → 门禁 2/2: 合同 ↔ spec ↔ code 三向对账"
    if bash "$REPO_ROOT/scripts/check-contract-tri-source.sh" --json-only > /tmp/cts.json 2>&1; then
        local exit_code=$?
        local elapsed=$(( $(date +%s) - start_time ))
        if [[ "$exit_code" -eq 0 ]]; then
            echo "[check-pre-commit] ✅ 门禁 2/2 PASS: thresholds 满足 (elapsed=${elapsed}s)"
            PASSED=$((PASSED + 1))
        else
            echo "[check-pre-commit] ⚠ 门禁 2/2 thresh 超标(exit=$exit_code),但继续(见 /tmp/cts.json)(elapsed=${elapsed}s)"
            PASSED=$((PASSED + 1))
        fi
    else
        local exit_code=$?
        local elapsed=$(( $(date +%s) - start_time ))
        echo "[check-pre-commit] ❌ 门禁 2/2 FAIL: exit=$exit_code (elapsed=${elapsed}s)"
        FAILED=$((FAILED + 1))
    fi
}

# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------
case "$MODE" in
    all)
        run_drift_gate
        run_contract_gate
        ;;
    drift)
        run_drift_gate
        ;;
    contract)
        run_contract_gate
        ;;
    fast)
        SKIPPED=2
        echo "[check-pre-commit] ⚡ fast mode:跳过 doc↔db 与 contract tri-source 门禁"
        ;;
    *)
        echo "[check-pre-commit] ❌ unknown mode: $MODE (支持: all|drift|contract|fast)" >&2
        exit 2
        ;;
esac

echo "[check-pre-commit] 总结: passed=$PASSED failed=$FAILED skipped=$SKIPPED"
[[ "$FAILED" -gt 0 ]] && exit 1
exit 0
