#!/usr/bin/env bash
# scripts/check-agentscope-abstract-baseline.sh — AgentScope 官方抽象基线门禁
#
# 用途: 把「官方 AgentScope 有哪些抽象、我们用了哪些」固化成可执行检查, 防止后续有人再犯
#       「以为官方没有、于是另起炉灶」的错误(本项目 langchain4j → AgentScope 内核替换期间
#       已多次出现「官方明明有、我们自研了一套」的返工)。
#
# 基线来源: 官方源码仓(默认 /Users/mac/Documents/agentscope-java), 每条断言都是实证事实,
#           升级官方版本后必须重审基线而不是删断言。
#
# 判定语义:
#   PRESENT  断言变为「找不到」 -> FAIL(官方删了/改名了, 基线需重审)
#   ABSENT   断言变为「找得到」 -> FAIL(官方新增了同类能力, 我们该复用而不是自研)
#   官方仓路径不存在            -> SKIP + EXIT 0(不因没 clone 源码仓而卡住门禁)
#
# 用法:
#   bash scripts/check-agentscope-abstract-baseline.sh                     # 常规检查
#   bash scripts/check-agentscope-abstract-baseline.sh --self-test         # 自证能红(夹具)
#   AGENTSCOPE_OFFICIAL_REPO=/path/to/agentscope-java bash ...            # 指定官方仓
#
# 退出码: 0=PASS 或 SKIP / 1=FAIL(基线漂移) / 2=参数非法或环境错

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OFFICIAL_REPO_DEFAULT="/Users/mac/Documents/agentscope-java"

MODE="check"
case "${1:-}" in
    --self-test) MODE="self-test" ;;
    "")          MODE="check" ;;
    *)           echo "ERROR: 未知参数: ${1} (支持: --self-test)"; exit 2 ;;
esac

# ---------------------------------------------------------------------------
# 基线断言表
#   字段: id|expect|path_glob|说明|content_ere
#   注意 content_ere 放最后: 允许正则里出现 | 元字符(read 末位变量会吸收剩余分隔符)
#   expect = PRESENT 文件(路径 glob, 可选内容正则)必须存在
#   expect = ABSENT  整个官方仓(仅 .java)内容中不得出现该模式
#   路径 glob 以 * 跨目录, 相对官方仓根。
# ---------------------------------------------------------------------------
BASELINE_ROWS=(
"official-command-validator|PRESENT|*tool/coding/CommandValidator.java|可执行白名单校验层 SPI|interface CommandValidator"
"official-shell-command-tool|PRESENT|*tool/coding/ShellCommandTool.java|可执行白名单校验层(799 行)|class ShellCommandTool"
"official-unix-command-validator|PRESENT|*tool/coding/UnixCommandValidator.java|Unix 白名单校验(215 行)|implements CommandValidator"
"official-windows-command-validator|PRESENT|*tool/coding/WindowsCommandValidator.java|Windows 白名单校验(247 行)|implements CommandValidator"
"official-schema-only-tool|PRESENT|*core/tool/SchemaOnlyTool.java|工具外部执行-回填协议|extends ToolBase"
"official-tool-suspend-exception|PRESENT|*core/tool/ToolSuspendException.java|工具挂起异常(回填协议配套)|class ToolSuspendException"
"official-toolkit-is-external-tool|PRESENT|*core/tool/Toolkit.java|Toolkit#isExternalTool 判定|isExternalTool"
"official-skill-promotion-gate|PRESENT|*skill/curator/SkillPromotionGate.java|审批门|PromotionGate"
"official-promotion-decision-sealed|PRESENT|*skill/curator/SkillPromotionGate.java|审批门三态封闭决策|sealed interface PromotionDecision"
"official-state-store-cas|PRESENT|*core/state/AgentStateStore.java|状态版本化 CAS|saveIfVersion"
"official-state-store-versioned-read|PRESENT|*core/state/AgentStateStore.java|状态版本化读取|getVersioned"
"official-conflict-policy|PRESENT|*core/state/ConflictPolicy.java|冲突策略|enum ConflictPolicy"
"official-sandbox-spi-client|PRESENT|*sandbox/SandboxClient.java|沙箱 SPI|interface SandboxClient"
"official-sandbox-spi-sandbox|PRESENT|*sandbox/Sandbox.java|沙箱 SPI|interface Sandbox "
"official-sandbox-execution-guard|PRESENT|*sandbox/SandboxExecutionGuard.java|沙箱执行守卫|interface SandboxExecutionGuard"
"official-middleware-base|PRESENT|*core/middleware/MiddlewareBase.java|中间件扩展点基类|enum ExtensionPoint"
"official-extension-point-6|PRESENT|*core/middleware/MiddlewareBase.java|6 个扩展点齐备|ON_AGENT_STATE_READY"
"official-middleware-order-hook|PRESENT|*core/middleware/MiddlewareBase.java|可覆盖 order() 决定执行顺序|default int order"
"official-compaction-default-on|PRESENT|*harness/agent/HarnessAgent.java|compaction 默认开启(见下方文档矛盾提醒)|boolean disableCompaction = false"
"absent-state-graph|ABSENT|-|LangGraph 式图编排: 官方没有|StateGraph"
"absent-add-edge|ABSENT|-|同上|addEdge\("
"absent-add-node|ABSENT|-|同上|addNode\("
"absent-add-conditional-edges|ABSENT|-|同上|addConditionalEdges\("
"absent-compiled-graph|ABSENT|-|同上|CompiledGraph"
"absent-checkpoint|ABSENT|-|同上|Checkpoint\.class|interface Checkpoint|class Checkpoint|Checkpoint\("
"absent-checkpointer|ABSENT|-|同上(大小写不敏感)|checkpointer"
"absent-resume-from|ABSENT|-|同上|resumeFrom\("
)

# 官方文档与代码矛盾的常驻提醒(不是断言, 是必须让人看见的事实)
print_contradiction_notice() {
    cat <<'EOF'
--------------------------------------------------------------------------------
⚠️  官方文档与代码矛盾(以代码为准)
    官方文档称 compaction「默认全不开」, 但官方源码 HarnessAgent.java 字段初值是
    compactionConfig = CompactionConfig.builder().build() + disableCompaction = false
    → 实际是【默认开启】。任何基于「官方默认关闭」的设计判断都是错的。
--------------------------------------------------------------------------------
EOF
}

# 命中判定: 1=命中 0=未命中
assert_present() {
    local off="$1" glob="$2" ere="$3" f
    while IFS= read -r f; do
        [ -n "$f" ] || continue
        if [ -z "$ere" ]; then return 0; fi
        if grep -qE "$ere" "$f" 2>/dev/null; then return 0; fi
    done < <(find "$off" -path "$off/*${glob}" -type f 2>/dev/null)
    return 1
}

assert_absent() {
    local off="$1" ere="$2"
    if grep -rqE "$ere" --include='*.java' "$off" 2>/dev/null; then
        return 1
    fi
    return 0
}

# run_check [官方仓路径]; 缺省取 $AGENTSCOPE_OFFICIAL_REPO, 再缺省取默认路径
run_check() {
    local off="${1:-${AGENTSCOPE_OFFICIAL_REPO:-${OFFICIAL_REPO_DEFAULT}}}"
    if [ ! -d "$off" ]; then
        echo "SKIP: 官方源码仓不存在: ${off}"
        echo "      (未 clone 官方仓的人不会被门禁卡住; 装了仓请设 AGENTSCOPE_OFFICIAL_REPO)"
        print_contradiction_notice
        return 0
    fi
    echo "官方源码仓: ${off}"
    echo "基线断言条数: ${#BASELINE_ROWS[@]}"
    echo "--------------------------------------------------------------------------------"

    local fail=0 id expect glob ere note hit
    for row in "${BASELINE_ROWS[@]}"; do
        IFS='|' read -r id expect glob note ere <<<"$row"
        if [ "$expect" = "PRESENT" ]; then
            if assert_present "$off" "$glob" "$ere"; then
                printf '✅ %-36s PRESENT  %s\n' "$id" "$note"
            else
                printf '❌ %-36s MISSING  %s\n' "$id" "$note"
                fail=$((fail + 1))
            fi
        else
            if assert_absent "$off" "$ere"; then
                printf '✅ %-36s ABSENT   %s\n' "$id" "$note"
            else
                printf '❌ %-36s APPEARED %s\n' "$id" "$note"
                fail=$((fail + 1))
            fi
        fi
    done

    echo "--------------------------------------------------------------------------------"
    print_contradiction_notice
    if [ "$fail" -gt 0 ]; then
        echo "FAIL: ${fail} 条基线漂移 —— 官方 AgentScope 版本已变, 必须重审基线:"
        echo "      - PRESENT 变 MISSING: 官方删除/改名了该抽象 → 更新本脚本 BASELINE_ROWS 并复核自研代码"
        echo "      - ABSENT 变 APPEARED: 官方新增了该能力   → 优先复用官方, 不要继续自研"
        return 1
    fi
    echo "PASS: 官方抽象基线 ${#BASELINE_ROWS[@]} 条全部吻合"
    return 0
}

run_self_test() {
    local tmpdir rc
    tmpdir="$(mktemp -d)"
    trap 'rm -rf "$tmpdir"' RETURN

    # 用例 1: 真实官方仓(存在时)必须 PASS
    if [ -d "$OFFICIAL_REPO_DEFAULT" ]; then
        run_check "$OFFICIAL_REPO_DEFAULT" >/dev/null 2>&1
        rc=$?
        [ "$rc" -eq 0 ] || { echo "SELF-TEST FAIL: 真实官方仓应 PASS, got ${rc}"; return 1; }
        echo "  [用例1] 真实官方仓 -> EXIT 0 ✅"
    else
        echo "  [用例1] 跳过(本机无官方源码仓)"
    fi

    # 用例 2: 官方仓不存在 -> 必须 SKIP/EXIT 0(优雅降级)
    run_check "${tmpdir}/not-exist" >/dev/null 2>&1
    rc=$?
    [ "$rc" -eq 0 ] || { echo "SELF-TEST FAIL: 官方仓缺失应 SKIP(0), got ${rc}"; return 1; }
    echo "  [用例2] 官方仓缺失 -> SKIP EXIT 0 ✅"

    # 用例 3: 夹具仓里删掉 CommandValidator -> PRESENT 断言必须变红(EXIT 1)
    local fx="${tmpdir}/fixture"
    mkdir -p "${fx}/agentscope-core/src/main/java/io/agentscope/core/tool/coding"
    printf 'public interface CommandValidator {}\n' \
        > "${fx}/agentscope-core/src/main/java/io/agentscope/core/tool/coding/CommandValidator.java"
    printf 'public class Fake {}\n' > "${fx}/agentscope-core/src/main/java/io/agentscope/core/tool/Fake.java"
    run_check "$fx" >/dev/null 2>&1
    rc=$?
    [ "$rc" -eq 1 ] || { echo "SELF-TEST FAIL: 夹具缺 PRESENT 项应 FAIL(1), got ${rc}"; return 1; }
    echo "  [用例3] PRESENT 能力消失 -> EXIT 1 ✅"

    # 用例 4: 夹具仓里混入 StateGraph -> ABSENT 断言必须变红(EXIT 1)
    printf 'public class StateGraph {}\n' > "${fx}/agentscope-core/src/main/java/io/agentscope/core/tool/Fake.java"
    run_check "$fx" >/dev/null 2>&1
    rc=$?
    [ "$rc" -eq 1 ] || { echo "SELF-TEST FAIL: 夹具出现 ABSENT 项应 FAIL(1), got ${rc}"; return 1; }
    echo "  [用例4] ABSENT 能力出现(官方新增) -> EXIT 1 ✅"

    # 用例 5: 非法参数 -> EXIT 2
    bash "$0" --bogus >/dev/null 2>&1
    rc=$?
    [ "$rc" -eq 2 ] || { echo "SELF-TEST FAIL: 非法参数应 EXIT 2, got ${rc}"; return 1; }
    echo "  [用例5] 非法参数 -> EXIT 2 ✅"

    echo "SELF-TEST PASS: 4/5 + 非法参数 护栏全部会红(缺官方仓时第 1 例跳过)"
    return 0
}

case "$MODE" in
    check)     run_check; exit $? ;;
    self-test) run_self_test; exit $? ;;
esac
