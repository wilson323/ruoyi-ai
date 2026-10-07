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
    if [[ ! -f "$REPO_ROOT/scripts/check-staged-snapshot.py" ]]; then
        echo "[check-pre-commit] ❌ 门禁 3 FAIL: 暂存快照检查器不存在"
        FAILED=$((FAILED + 1))
        return 1
    fi
    local out rc
    out=$(python3 "$REPO_ROOT/scripts/check-staged-snapshot.py" --be-root "$REPO_ROOT" --fe-root "${IPD_FE_REPO_ROOT:-$REPO_ROOT/../ruoyi-ipd-web}" 2>&1)
    rc=$?
    local elapsed=$(( $(date +%s) - start_time ))
    if [[ "$rc" -eq 0 ]]; then
        echo "[check-pre-commit] ✅ 门禁 3 PASS: 孤儿棘轮无新孤儿/白名单防伪通过 (elapsed=${elapsed}s)"
        echo "$out" | grep -E "vs baseline" || true
        PASSED=$((PASSED + 1))
    else
        echo "[check-pre-commit] ❌ 门禁 3 FAIL: 暂存快照 API 棘轮 exit=$rc (elapsed=${elapsed}s)"
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
# ---------------------------------------------------------------------------
# 门禁 7：钩子输出契约（hook decision schema）
# 病根：钩子往 stdout 吐非法 JSON 时，Claude Code **静默丢弃整份输出**——
#   拦截类钩子等于没装，界面只在控制台刷一行红字。
#   2026-10-03 实测：irreversible-guard（不可逆操作拦截）因写了顶层
#   {"decision":"allow"} 而长期静默失效，递归删除一/二级目录一直没被拦。
# 判据是**实跑钩子验输出**，不是 grep 源码——出事那两处写的是 JS 简写属性
#   {decision, reason}，grep 字面量永远匹配不到（第一版门禁因此假绿，变异自证露馅）。
# 注意：全局钩子（~/.claude/hooks）不在本仓版本控制内，改它们不会触发本门禁；
#   所以本门禁的价值是**在提交时复核全局钩子当前是否健康**，不是拦下那次提交。
# 单跑 ~2s；fast 模式同 untracked/棘轮先例仍跑
# ---------------------------------------------------------------------------
run_hook_schema_gate() {
    local start_time
    start_time=$(date +%s)
    echo "[check-pre-commit] → 门禁 7: 钩子输出契约(实跑验 JSON 形状)"
    if [[ ! -f "$REPO_ROOT/scripts/check-hook-decision-schema.sh" ]]; then
        echo "[check-pre-commit] ❌ 门禁 7 FAIL: scripts/check-hook-decision-schema.sh 不存在"
        FAILED=$((FAILED + 1))
        return 1
    fi
    local out rc
    out=$(bash "$REPO_ROOT/scripts/check-hook-decision-schema.sh" 2>&1)
    rc=$?
    local elapsed=$(( $(date +%s) - start_time ))
    if [[ "$rc" -eq 0 ]]; then
        echo "[check-pre-commit] ✅ 门禁 7 PASS: 钩子输出均符合契约 (elapsed=${elapsed}s)"
        PASSED=$((PASSED + 1))
    else
        echo "[check-pre-commit] ❌ 门禁 7 FAIL: 存在违反输出契约的钩子 exit=$rc (elapsed=${elapsed}s)"
        printf '%s\n' "$out" | tail -30
        echo "[check-pre-commit]   后果: Claude Code 校验失败会丢弃整份输出 → 拦截类钩子静默失效"
        echo "[check-pre-commit]   自证: HOOK_FAIL_SEED=1 bash scripts/check-hook-decision-schema.sh 应 EXIT=1"
        FAILED=$((FAILED + 1))
    fi
}

# ---------------------------------------------------------------------------
# 门禁 8: 门禁自身的自证(2026-10-03 新增)
# 病根: 一个门禁可以「能红」却「乱红」——只验 FAIL_SEED(故意弄坏必须报红)、
#   不验「对已知良好的输入必须放行」时, 门禁会在正确代码上大面积误报而无人察觉。
#   2026-10-03 实测两例: check-entity-complete.sh 报 61 处「Service 无 Controller」
#   而真缺口为 0; check-tenant-excludes-apply.sh 报 74 处「重叠」而真信号为 0。
#   scripts/test-audit-gate-inputs.py 正是干「干净输入必须放行 + 真违规必须拦下」
#   这件事的, 但它此前**没有任何自动调用者**, 自己红了 5 个用例也无人知道。
#   本门禁把它接到必经路径上, 使「门禁被改坏」在下一次提交时立刻可见。
# 代价: 仅在本次提交触及门禁脚本或 hook 时执行(约 3s); 其余提交 SKIP, 不计时。
# 自证能红: 把任一被测门禁改成恒绿(如 check-naming-convention.sh 只写 exit 0),
#   本门禁应 FAIL —— 已实测(2026-10-03), 破坏后 12 用例中 1 个失败。
# ---------------------------------------------------------------------------
run_gate_selftest_gate() {
    local t0 elapsed touched out rc
    t0=$(date +%s)
    # 2026-10-03 收窄了缺口：原模式只认 scripts/ 下的 .sh / .py，于是改了
    # scripts/ownership-gate-exempt.txt（**门禁读取的数据文件**）时自证照报 SKIP——
    # 「改了门禁的输入，但自证没跑」正是本门禁要防的形态。改为 scripts/ 全目录，
    # 覆盖 .sh / .py / .txt / .mjs / .json 等一切门禁实现与其输入。
    touched=$(git diff --cached --name-only 2>/dev/null \
        | grep -E '^(scripts/|\.claude/(hooks|helpers)/)' || true)
    if [[ -z "$touched" ]]; then
        echo "[check-pre-commit] ⏭ 门禁 8 SKIP: 本次未触及门禁脚本/hook"
        SKIPPED=$((SKIPPED + 1))
        return 0
    fi
    echo "[check-pre-commit] → 门禁 8: 门禁自身的自证(干净输入必须放行 + 真违规必须拦下)"
    out=$(python3 "$REPO_ROOT/scripts/test-audit-gate-inputs.py" 2>&1); rc=$?
    elapsed=$(( $(date +%s) - t0 ))
    if [[ "$rc" -ne 0 ]]; then
        echo "[check-pre-commit] ❌ 门禁 8 FAIL: 门禁自证未通过 (exit=$rc, elapsed=${elapsed}s)" >&2
        printf '%s\n' "$out" | grep -E '^(FAIL|ERROR|AssertionError)' | head -10
        echo "[check-pre-commit]   后果: 某个门禁的判定口径已被改坏, 它此后报出的结果不可信"
        FAILED=$((FAILED + 1))
        return 1
    fi
    echo "[check-pre-commit] ✅ 门禁 8 PASS: 门禁自证通过 (elapsed=${elapsed}s)"
    PASSED=$((PASSED + 1))
    return 0
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
        run_symlink_gate
        run_hook_schema_gate
        run_gate_selftest_gate
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
        # 门禁 7: fast 模式也跑(实跑钩子 ~2s,病根是拦截静默失效,不能省)
        run_untracked_gate
        run_ratchet_gate
        run_shell_var_gate
        run_symlink_gate
        run_hook_schema_gate
        SKIPPED=2
        echo "[check-pre-commit] ⚡ fast mode:跳过 doc↔db 与 contract tri-source 门禁(untracked 与孤儿棘轮门禁3 仍跑)"
        ;;
    hooks)
        # 独立模式:仅跑钩子输出契约(改钩子后快速预检)
        run_hook_schema_gate
        ;;
    *)
        echo "[check-pre-commit] ❌ unknown mode: $MODE (支持: all|drift|contract|untracked|fast|hooks)" >&2
        exit 2
        ;;
esac

# ---------------------------------------------------------------------------
# 门禁 5:基准值与代码一致性(2026-10-07 owner「需求整合在一起禁止散落」落地)
#
# 为什么必须有这道门禁:本仓的数字漂了几个月,根子是**数字被手写进文档**。
# 实测「69 个动作」散落 83 份文件、正确的「67」只有 12 份 —— 因为没有东西会检查它。
# 本门禁让两种情况立刻报红:
#   a) 有人手改 docs/ipd-系统说明/治理/基准值.md
#   b) 代码变了(如 ActionCatalog 增删动作)但基准值没跟
# 已变异验证:手改 67->69 报红 / 改 ActionCatalog 报红 / 尺子失效拒绝产出(exit 3)。
# 正确处置:跑 `bash scripts/gen-baseline.sh` 重新生成,**不许手改**基准值.md。
# ---------------------------------------------------------------------------
echo "[check-pre-commit] -> 门禁 5: 基准值与代码一致性"
if [ -f scripts/gen-baseline.sh ]; then
    base_out="$(bash scripts/gen-baseline.sh --check 2>&1)"
    base_rc=$?
    if [ "$base_rc" = "0" ]; then
        PASSED=$((PASSED + 1))
        echo "[check-pre-commit] PASS 门禁 5: 基准值与代码一致"
    elif [ "$base_rc" = "3" ]; then
        # 尺子自身失效 -> 拒绝判绿(宁可误伤不可假绿)
        FAILED=$((FAILED + 1))
        echo "[check-pre-commit] FAIL 门禁 5: 计数尺子失效,拒绝产出基准值" >&2
        printf '%s\n' "$base_out" | head -5 >&2
    else
        FAILED=$((FAILED + 1))
        echo "[check-pre-commit] FAIL 门禁 5: 基准值与代码不一致" >&2
        echo "              处置: 跑 bash scripts/gen-baseline.sh 重新生成,不要手改" >&2
        printf '%s\n' "$base_out" | head -12 >&2
    fi
else
    SKIPPED=$((SKIPPED + 1))
    echo "[check-pre-commit] SKIP 门禁 5: scripts/gen-baseline.sh 不存在"
fi

# ---------------------------------------------------------------------------
# 门禁 6: 治理件接线自检(2026-10-07)
# 病根: 治理件写出来了但没人保证它带自证、挂了提交路径。
#   2026-10-07 实测: 那天产出 7 件治理工具, 事后盘点「有自证 0 件、接线 1 件」——
#   全靠「我手动跑过一次」算数, 而那天已实证「人手记得跑」不够(同类错误一天犯四次)。
#   形状 = 守卫写对了、也挂上了, 但没被调用; 这次更前一步: 连自证都没有。
# 成本: 约 0.2s, 每次提交都跑。
# ---------------------------------------------------------------------------
echo "[check-pre-commit] -> 门禁 6: 治理件接线自检"
if [ -f scripts/check-governance-wiring.sh ]; then
    gw_out="$(bash scripts/check-governance-wiring.sh 2>&1)"
    gw_rc=$?
    if [ "$gw_rc" = "0" ]; then
        PASSED=$((PASSED + 1))
        echo "[check-pre-commit] PASS 门禁 6: 治理件全部自带自证且已接线"
    else
        FAILED=$((FAILED + 1))
        echo "[check-pre-commit] FAIL 门禁 6: 有治理件缺自证或未接线" >&2
        printf '%s\n' "$gw_out" | grep -E '治理件  [0-9]+ 件|自证=否|接线=否' | head -8 >&2
        echo "              处置: 补 --self-test, 或挂进本门禁; 未自证的工具只能靠人手记得跑" >&2
    fi
else
    SKIPPED=$((SKIPPED + 1))
    echo "[check-pre-commit] SKIP 门禁 6: scripts/check-governance-wiring.sh 不存在"
fi

# ---------------------------------------------------------------------------
# 门禁 9: 守卫与形状守卫的测试集(2026-10-07)
# 为什么必须进提交路径: 这两件是「闸门本身」的验收。
#   改 block-dangerous-git.sh 不跑它的 22 条用例 = 闸门可能已经常开而无人知道
#   (2026-10-07 实测: 它曾有 git -C 绕过洞, 单看配置合法、脚本存在, 跑起来才暴露)。
#   output-shape-guard 同理, 它自己坏了必须被发现, 但它坏了不能阻断提交。
# 成本: 合计约 1.5s。
# ---------------------------------------------------------------------------
echo "[check-pre-commit] -> 门禁 9: 守卫测试集"
for _gt in ".claude/hooks/test-block-dangerous-git.sh" ".claude/helpers/test-output-shape-guard.sh"; do
  if [ -f "$_gt" ]; then
    if bash "$_gt" >/tmp/_gate9.log 2>&1; then
      PASSED=$((PASSED + 1))
      echo "[check-pre-commit] PASS 门禁 9: $(basename "$_gt")"
    else
      FAILED=$((FAILED + 1))
      echo "[check-pre-commit] FAIL 门禁 9: $(basename "$_gt")" >&2
      tail -6 /tmp/_gate9.log >&2
    fi
  else
    SKIPPED=$((SKIPPED + 1))
    echo "[check-pre-commit] SKIP 门禁 9: $_gt 不存在"
  fi
done

# ---------------------------------------------------------------------------
# 门禁 10: 基准值一致性（依赖 evidenced-count 的口径，2026-10-07）
# 为什么放在这里: 门禁 5 验的是「基准值文件 vs 代码」；
#   本道验的是「这个数字能不能被交付出去」——数到 0 必须显式声明，
#   数不出数直接拒绝。这治的是「错读数被当成结论交付」这一形态。
# 依赖: scripts/evidenced-count.sh（无它则 SKIP，不误伤）。
# ---------------------------------------------------------------------------
echo "[check-pre-commit] -> 门禁 10: 读数自证器可用性"
if [ -f scripts/evidenced-count.sh ]; then
  ec_out="$(bash scripts/evidenced-count.sh --self-test 2>&1)"
  ec_rc=$?
  if [ "$ec_rc" = "0" ]; then
    PASSED=$((PASSED + 1))
    echo "[check-pre-commit] PASS 门禁 10: 读数自证器自证通过"
  else
    FAILED=$((FAILED + 1))
    echo "[check-pre-commit] FAIL 门禁 10: 读数自证器自证不通过" >&2
    printf '%s\n' "$ec_out" | tail -5 >&2
  fi
else
  SKIPPED=$((SKIPPED + 1))
  echo "[check-pre-commit] SKIP 门禁 10: scripts/evidenced-count.sh 不存在"
fi

# ---------------------------------------------------------------------------
# 门禁 11: 验收矩阵校验器自证(2026-10-07)
# 为什么必须进提交路径: 它的第一版自证「直接调纯函数、自称覆盖接线」,
#   实测把接线改回裸调用它照样 PASS —— 测了但没测到点上, 提供假安全感(作者自述)。
#   T6 改为跑真实 main() 路径后才真的能红。
#   不接进提交路径, 那种接线回归只能等人手工发现。
# ---------------------------------------------------------------------------
echo "[check-pre-commit] -> 门禁 11: 验收矩阵校验器自证"
if [ -f .claude/helpers/acceptance-matrix-validate.cjs ]; then
  mx_out="$(node .claude/helpers/acceptance-matrix-validate.cjs --self-test 2>&1)"
  mx_rc=$?
  if [ "$mx_rc" = "0" ] && printf '%s' "$mx_out" | grep -q '结果：'; then
    PASSED=$((PASSED + 1))
    echo "[check-pre-commit] PASS 门禁 11: 验收矩阵校验器自证通过"
  else
    FAILED=$((FAILED + 1))
    echo "[check-pre-commit] FAIL 门禁 11: 验收矩阵校验器自证不通过" >&2
    printf '%s\n' "$mx_out" | tail -8 >&2
  fi
else
  SKIPPED=$((SKIPPED + 1))
  echo "[check-pre-commit] SKIP 门禁 11: 校验器不存在"
fi

echo "[check-pre-commit] 总结: passed=$PASSED failed=$FAILED skipped=$SKIPPED"
[[ "$FAILED" -gt 0 ]] && exit 1
exit 0
