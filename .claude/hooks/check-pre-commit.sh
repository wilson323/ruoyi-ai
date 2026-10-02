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

# ---------------------------------------------------------------------------
# PATH 补齐（2026-10-02 修）：hook 在非交互 shell（Claude Code / Qoder）下 PATH 精简，
# 缺 /opt/homebrew/bin 时门禁 1/2 的 `command -v mysql` 落空 → 以 exit=2 假失败
# （报「mysql command not found」而非真实漂移），且会拦下全仓 commit。
# 优先仓内自带实例客户端（跟 base-services.sh 同一 mysqld，跳机器稳定），再兜底 Homebrew。
# 注：只补查找路径，不改任何判定逻辑；库未起时仍照原设计 FAIL。
# ---------------------------------------------------------------------------
_path_prefix=""
for _p in "$REPO_ROOT/.codex/ipd-dev/software/mysql-8.0.46-macos15-arm64/bin" /opt/homebrew/bin /usr/local/bin; do
    [[ -d "$_p" ]] && _path_prefix="${_path_prefix}${_p}:"
done
PATH="${_path_prefix}${PATH}"
export PATH
unset _p _path_prefix

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

    # NUL 分隔，保留中文、空格和换行路径；先捕获 Git 失败再读取列表。
    local listing_dir
    listing_dir=$(mktemp -d) || { echo "[check-pre-commit] ❌ 门禁 0 FAIL: 无法创建临时列表" >&2; FAILED=$((FAILED + 1)); return 1; }
    if ! git -C "$REPO_ROOT" diff --cached --name-only --diff-filter=ACM -z > "$listing_dir/staged" ||
       ! git -C "$REPO_ROOT" ls-files --others --exclude-standard -z > "$listing_dir/untracked"; then
        echo "[check-pre-commit] ❌ 门禁 0 FAIL: Git index/untracked 列表读取失败" >&2
        rm -rf "$listing_dir"
        FAILED=$((FAILED + 1))
        return 1
    fi
    if [[ ! -s "$listing_dir/untracked" ]]; then
        rm -rf "$listing_dir"
        echo "[check-pre-commit] ✅ 门禁 0 PASS: 无 untracked 文件"
        PASSED=$((PASSED + 1))
        return 0
    fi
    local untracked_names=()
    local f
    while IFS= read -r -d '' f; do
        untracked_names+=("$f")
    done < "$listing_dir/untracked"

    # 在 staged 文件内容里 grep 每个 untracked 文件名(basename)
    local hits=()
    local hit_count=0
    local staged_count=0
    while IFS= read -r -d '' staged_file; do
        [[ -z "$staged_file" ]] && continue
        staged_count=$((staged_count + 1))
        # 只查文本类文件(避免 binary 读不出来)
        case "$staged_file" in
            *.md|*.txt|*.java|*.yml|*.yaml|*.xml|*.json|*.sh|*.py|*.js|*.ts|*.tsx|*.vue|*.sql|*.md) ;;
            *) continue ;;
        esac
        local staged_content
        if ! staged_content=$(git -C "$REPO_ROOT" show ":$staged_file"); then
            echo "[check-pre-commit] ❌ 门禁 0 FAIL: 无法读取 index 文件: $staged_file" >&2
            rm -rf "$listing_dir"
            FAILED=$((FAILED + 1))
            return 1
        fi
        for untracked in "${untracked_names[@]}"; do
            # 用 basename 做引用匹配(避免路径噪音)
            local basename_untracked
            basename_untracked="${untracked##*/}"
            # 跳过 .gitignore / 临时文件
            [[ "$basename_untracked" == ".gitignore" ]] && continue
            [[ "$basename_untracked" == *.swp ]] && continue
            if [[ "$staged_content" == *"$basename_untracked"* ]]; then
                hits+=("$staged_file → 引用 untracked 文件 '$untracked'")
                hit_count=$((hit_count + 1))
            fi
        done
    done < "$listing_dir/staged"
    rm -rf "$listing_dir"

    local elapsed=$(( $(date +%s) - start_time ))
    if [[ "$hit_count" -gt 0 ]]; then
        echo "[check-pre-commit] ❌ 门禁 0 FAIL: ${hit_count} 处 untracked 引用 (elapsed=${elapsed}s)"
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
    # R245 棘轮：外部白名单吸收 9 个历史漂移标识符（owner 拍板 2026-09-28，只减不增）
    if bash "$REPO_ROOT/scripts/check-doc-db-drift.sh" --refined --json-only --whitelist "$REPO_ROOT/scripts/check-doc-db-drift-whitelist.txt" > /tmp/cdbd-refined.json 2>&1; then
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
        # 必须先捕获 $?：下一行的 local 赋值会把 $? 覆盖成 0，
        # 原写法恒打印「FAIL: exit=0」（自相矛盾，掩盖真实退出码，已误导诊断两轮）。
        local exit_code=$?
        local elapsed=$(( $(date +%s) - start_time ))
        echo "[check-pre-commit] ❌ 门禁 1/2 FAIL: exit=$exit_code (elapsed=${elapsed}s)"
        if [[ "$exit_code" -eq 2 ]]; then
            echo "[check-pre-commit]   提示: exit=2 是环境/脚本错（如 mysql 客户端缺失或 13306 实例未起），非漂移违规"
            echo "[check-pre-commit]   恢复: bash .codex/ipd-dev/base-services.sh start；详情见 /tmp/cdbd-refined.json"
        fi
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
    if python3 "$REPO_ROOT/scripts/check-staged-snapshot.py" --be-root "$REPO_ROOT" --shell-only; then
        PASSED=$((PASSED + 1))
    else
        echo "[check-pre-commit] ❌ 门禁 4 FAIL: indexed shell validation failed"
        FAILED=$((FAILED + 1))
    fi
}

# ---------------------------------------------------------------------------
# 路由
# ---------------------------------------------------------------------------
run_langchain4j_gate() {
    echo "[check-pre-commit] → 门禁 5: LangChain4j 引用面积只减不增"
    if bash "$REPO_ROOT/scripts/check-langchain4j-ratchet.sh"; then
        PASSED=$((PASSED + 1))
    else
        echo "[check-pre-commit] ❌ 门禁 5 FAIL: AgentScope 迁移期间出现新的 LangChain4j 接线" >&2
        FAILED=$((FAILED + 1))
    fi
}

# 已跟踪符号链接若指向仓库外的绝对路径，或目标已不存在，rg --follow 会整次搜索失败。
run_symlink_gate() {
    echo "[check-pre-commit] → 门禁 6: 已跟踪符号链接必须是仓库内相对路径且目标存在"
    local broken=0
    local line meta path mode target
    while IFS= read -r line; do
        meta="${line%%$'\t'*}"
        path="${line#*$'\t'}"
        mode="${meta%% *}"
        [[ "$mode" == "120000" ]] || continue
        target="$(git -C "$REPO_ROOT" show ":$path" 2>/dev/null || true)"
        target="${target%$'\n'}"
        if [[ "$target" == /* ]]; then
            echo "[check-pre-commit] ❌ 绝对路径符号链接: $path -> $target" >&2
            broken=1
            continue
        fi
        if [[ -L "$REPO_ROOT/$path" && ! -e "$REPO_ROOT/$path" ]]; then
            echo "[check-pre-commit] ❌ 目标不存在: $path -> $(readlink "$REPO_ROOT/$path")" >&2
            broken=1
        fi
    done < <(git -C "$REPO_ROOT" ls-files -s)
    if [[ "$broken" -eq 0 ]]; then
        echo "[check-pre-commit] ✅ 门禁 6 PASS"
        PASSED=$((PASSED + 1))
    else
        FAILED=$((FAILED + 1))
    fi
}

run_snapshot_gate() {
    echo "[check-pre-commit] → 暂存快照门禁: BE/FE index 静态合同"
    if python3 "$REPO_ROOT/scripts/check-staged-snapshot.py" --be-root "$REPO_ROOT" --fe-root "${IPD_FE_REPO_ROOT:-$REPO_ROOT/../ruoyi-ipd-web}"; then
        PASSED=$((PASSED + 1))
    else
        echo "[check-pre-commit] ❌ 暂存快照门禁 FAIL"
        FAILED=$((FAILED + 1))
    fi
}
run_snapshot_gate

case "$MODE" in
    all)
        # R43-α 二轮: 所有 hook 模式默认跑门禁 0(untracked 引用检测) < 1s
        run_untracked_gate
        run_drift_gate
        run_contract_gate
        run_ratchet_gate
        run_shell_var_gate
        run_langchain4j_gate
        run_symlink_gate
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
        run_langchain4j_gate
        run_symlink_gate
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
