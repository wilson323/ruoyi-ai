#!/usr/bin/env bash
# =============================================================================
# check-pre-commit.sh
# R39 治理轮 — 提交前门禁自检
# R43-α 二轮接管扩展 — 补 untracked 引用检测门禁(病根 ② 实质化)
#
# 用法:
#   .claude/hooks/check-pre-commit.sh          # 默认:跑全部(含孤儿棘轮门禁3)
#   .claude/hooks/check-pre-commit.sh drift     # 仅跑 doc↔db drift
#   .claude/hooks/check-pre-commit.sh contract  # 仅跑 contract tri-source
#   .claude/hooks/check-pre-commit.sh untracked # 仅跑 untracked 引用检测(R43-α 二轮新增)
#   .claude/hooks/check-pre-commit.sh fast      # 跳过 doc↔db 与 contract(untracked 与孤儿棘轮门禁3 仍跑,病根 ② 实质化)
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
if [[ ! -e "$REPO_ROOT/.git" ]]; then
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
# 门禁 0:R43-α 二轮新增 — untracked 引用检测(病根 ② 实质化)
# 扫描 staged 内容是否引用了 untracked 文件 — 例如 .md 写了 `see foo.md` 但 foo.md 未 git add
# 快速、便宜(<1s)、所有 hook 模式默认跑
# ---------------------------------------------------------------------------
run_untracked_gate() {
    local start_time
    start_time=$(date +%s)
    echo "[check-pre-commit] → 门禁 0: untracked 引用检测(R43-α 二轮新增)"

    # 取所有 staged 文件名
    local staged_files
    staged_files=$(git -C "$REPO_ROOT" diff --cached --name-only 2>/dev/null)

    # 取所有 untracked 文件名
    local untracked_files
    untracked_files=$(git -C "$REPO_ROOT" ls-files --others --exclude-standard 2>/dev/null)

    if [[ -z "$untracked_files" ]]; then
        local elapsed=$(( $(date +%s) - start_time ))
        echo "[check-pre-commit] ✅ 门禁 0 PASS: 无 untracked 文件 (elapsed=${elapsed}s)"
        PASSED=$((PASSED + 1))
        return 0
    fi

    # 拼接 untracked 文件名为 regex(纯 basename,不要求完整路径)
    local untracked_names=()
    while IFS= read -r f; do
        [[ -n "$f" ]] && untracked_names+=("$f")
    done <<< "$untracked_files"

    # 在 staged 文件内容里 grep 每个 untracked 文件名(basename)
    local hits=()
    local staged_count=0
    while IFS= read -r staged_file; do
        [[ -z "$staged_file" || ! -f "$REPO_ROOT/$staged_file" ]] && continue
        staged_count=$((staged_count + 1))
        # 只查文本类文件(避免 binary 读不出来)
        case "$staged_file" in
            *.md|*.txt|*.java|*.yml|*.yaml|*.xml|*.json|*.sh|*.py|*.js|*.ts|*.tsx|*.vue|*.sql|*.md) ;;
            *) continue ;;
        esac
        for untracked in "${untracked_names[@]}"; do
            # 用 basename 做引用匹配(避免路径噪音)
            local basename_untracked
            basename_untracked=$(basename "$untracked")
            # 跳过 .gitignore / 临时文件
            [[ "$basename_untracked" == ".gitignore" ]] && continue
            [[ "$basename_untracked" == *.swp ]] && continue
            if grep -qF "$basename_untracked" "$REPO_ROOT/$staged_file" 2>/dev/null; then
                hits+=("$staged_file → 引用 untracked 文件 '$untracked'")
            fi
        done
    done <<< "$staged_files"

    local elapsed=$(( $(date +%s) - start_time ))
    if [[ ${#hits[@]} -gt 0 ]]; then
        echo "[check-pre-commit] ❌ 门禁 0 FAIL: ${#hits[@]} 处 untracked 引用 (elapsed=${elapsed}s)"
        for hit in "${hits[@]}"; do
            echo "[check-pre-commit]   - $hit"
        done
        echo "[check-pre-commit]   提示: 这些文件还没 git add,会被 git commit 漏掉 → 兄弟会话 fresh clone 炸"
        FAILED=$((FAILED + 1))
    else
        echo "[check-pre-commit] ✅ 门禁 0 PASS: ${staged_count} staged 文件,无 untracked 引用 (elapsed=${elapsed}s)"
        PASSED=$((PASSED + 1))
    fi
}

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
# 门禁 3:R212 孤儿端点「只减不增」棘轮门禁(卡 7b76b7cd API-GATE-RATCHET)
# node scripts/check-api-contract-fe-be.mjs 默认 ratchet=fail;exit 位掩码 0/1/2/4 可叠加
# 单跑 ~0.1s;照 R43-α untracked 先例,fast 模式也跑(设计文档 §5-A 推荐组合 A+D)
# node 不可用时 SKIP 不误报(设计 §5-A: 哨兵段须防无 node 环境 env error)
# ---------------------------------------------------------------------------
run_ratchet_gate() {
    local start_time
    start_time=$(date +%s)
    echo "[check-pre-commit] → 门禁 3: API 契约孤儿棘轮 R212(只减不增)"
    if ! command -v node >/dev/null 2>&1; then
        echo "[check-pre-commit] ⚠ 门禁 3 SKIP: node 不可用(跳过而非误报 env error;本仓脚本依赖 node)"
        SKIPPED=$((SKIPPED + 1))
        return 0
    fi
    if [[ ! -f "$REPO_ROOT/scripts/check-api-contract-fe-be.mjs" ]]; then
        echo "[check-pre-commit] ⚠ 门禁 3 SKIP: scripts/check-api-contract-fe-be.mjs 不存在"
        SKIPPED=$((SKIPPED + 1))
        return 0
    fi
    local out rc
    out=$(node "$REPO_ROOT/scripts/check-api-contract-fe-be.mjs" 2>&1)
    rc=$?
    local elapsed=$(( $(date +%s) - start_time ))
    if [[ "$rc" -eq 0 ]]; then
        echo "[check-pre-commit] ✅ 门禁 3 PASS: 孤儿棘轮无新孤儿/白名单防伪通过 (elapsed=${elapsed}s)"
        echo "$out" | grep -E "vs baseline" || true
        PASSED=$((PASSED + 1))
    else
        echo "[check-pre-commit] ❌ 门禁 3 FAIL: exit=$rc (位掩码: 1=P0孤儿路径/strict, 2=环境错/防伪失败/基线被改, 4=新孤儿未白名单; elapsed=${elapsed}s)"
        echo "$out" | grep -E "^\s*(❌|\[|exit code)" | tail -25 || echo "$out" | tail -15
        echo "[check-pre-commit]   处置: 新孤儿→补前端消费/删端点/白名单登记(卡号+reason+expire); 基线→--update-baseline"
        FAILED=$((FAILED + 1))
    fi
}

# ---------------------------------------------------------------------------
# 门禁 4：R224 shell 变量吞字节（$VAR 紧跟非 ASCII）
# 病根：bash 变量名解析会吃掉紧随的多字节字符首字节 → 变量展开为空 + 输出乱码
#   （实测 printf "（:$B）" → efbc88 3a bc89，16039 整个消失）
# 只扫 staged *.sh（读工作树内容），单跑 ~0.2s；fast 模式同 untracked/棘轮先例仍跑
# 修法和上下文一样无害：改写为 ${VAR}
# ---------------------------------------------------------------------------
run_shell_var_gate() {
    local start_time
    start_time=$(date +%s)
    echo "[check-pre-commit] → 门禁 4: shell 变量吞字节 R224(\$VAR 紧跟非 ASCII)"
    if ! command -v python3 >/dev/null 2>&1; then
        echo "[check-pre-commit] ⚠ 门禁 4 SKIP: python3 不可用(跳过而非误报 env error)"
        SKIPPED=$((SKIPPED + 1))
        return 0
    fi
    if [[ ! -f "$REPO_ROOT/scripts/check-shell-var-multibyte.sh" ]]; then
        echo "[check-pre-commit] ⚠ 门禁 4 SKIP: scripts/check-shell-var-multibyte.sh 不存在"
        SKIPPED=$((SKIPPED + 1))
        return 0
    fi

    local sh_files=()
    while IFS= read -r p; do
        [[ -n "$p" ]] && sh_files+=("$p")
    done < <(git -C "$REPO_ROOT" -c core.quotePath=false diff --cached --name-only --diff-filter=ACM 2>/dev/null | grep -E '\.sh$' || true)

    local elapsed=$(( $(date +%s) - start_time ))
    if [[ "${#sh_files[@]}" -eq 0 ]]; then
        echo "[check-pre-commit] ⚠ 门禁 4 SKIP: staged 无 *.sh (elapsed=${elapsed}s)"
        SKIPPED=$((SKIPPED + 1))
        return 0
    fi
    local out rc
    out=$(bash "$REPO_ROOT/scripts/check-shell-var-multibyte.sh" "${sh_files[@]}" 2>&1)
    rc=$?
    elapsed=$(( $(date +%s) - start_time ))
    if [[ "$rc" -eq 0 ]]; then
        echo "[check-pre-commit] ✅ 门禁 4 PASS: staged ${#sh_files[@]} 个 .sh 无变量吞字节违例 (elapsed=${elapsed}s)"
        PASSED=$((PASSED + 1))
    else
        echo "[check-pre-commit] ❌ 门禁 4 FAIL: exit=$rc (elapsed=${elapsed}s)"
        echo "$out" | tail -25
        echo '[check-pre-commit]   处置: 把 $NAME 改写为 ${NAME}（整行注释不计违例）'
        FAILED=$((FAILED + 1))
    fi
}

# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------
case "$MODE" in
    all)
        # R43-α 二轮: 所有 hook 模式默认跑门禁 0(untracked 引用检测) < 1s
        run_untracked_gate
        run_drift_gate
        run_contract_gate
        run_ratchet_gate
        run_shell_var_gate
        ;;
    drift)
        run_untracked_gate
        run_drift_gate
        ;;
    contract)
        run_untracked_gate
        run_contract_gate
        ;;
    untracked)
        # R43-α 二轮新增独立模式:仅跑 untracked 引用检测(为快速预检)
        run_untracked_gate
        ;;
    fast)
        # R43-α 二轮: fast 模式仍跑 untracked 门禁 0(快速 < 1s,病根 ② 实质化)
        # R212: fast 模式也跑门禁 3(孤儿棘轮,单跑 ~0.1s,同 untracked 实质化先例)
        # R224: fast 模式也跑门禁 4(shell 变量吞字节,单跑 ~0.2s,同为实质化先例)
        run_untracked_gate
        run_ratchet_gate
        run_shell_var_gate
        SKIPPED=2
        echo "[check-pre-commit] ⚡ fast mode:跳过 doc↔db 与 contract tri-source 门禁(untracked 与孤儿棘轮门禁3 仍跑)"
        ;;
    *)
        echo "[check-pre-commit] ❌ unknown mode: $MODE (支持: all|drift|contract|untracked|fast)" >&2
        exit 2
        ;;
esac

echo "[check-pre-commit] 总结: passed=$PASSED failed=$FAILED skipped=$SKIPPED"
[[ "$FAILED" -gt 0 ]] && exit 1
exit 0
