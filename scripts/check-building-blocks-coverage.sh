#!/usr/bin/env bash
# scripts/check-building-blocks-coverage.sh — AgentScope 装配点(disable 档位)一致性门禁
#
# 用途: 防两类漂移
#   1) 装配点 disable 档位不一致 —— 全项目 N 个 HarnessAgent.builder() 主装配点各关各的,
#      官方能力默认全开, 档位低 = 裸奔(多给 LLM 文件/Shell/记忆/子智能体权限)。
#   2) PoC 装配点混进生产源树 —— src/main/java/**/poc/** 下的 HarnessAgent.builder() 若不在
#      已登记豁免名单里且几乎不关能力, 直接 FAIL。
#
# 附注(执行顺序是隐式的): 官方 middleware 全部默认 order()==1 且无一覆盖 order(),
#      即注册顺序 == 执行顺序, 增删一个 middleware 会静默改变别人的相对位置。
#      装配点之间档位不同, 再叠加隐式顺序 = 行为不可复现。本脚本常驻打印该提醒。
#
# 判定语义:
#   新增装配点(基线里没有的第 N 档)      -> FAIL
#   某装配点 disable 项数 > 基线(继续放松) -> FAIL
#   某装配点 disable 项数 < 基线(收紧)     -> WARN(允许, 建议更新基线)
#   档位之间不一致                         -> WARN(已登记的已知状态, 打印差异明细)
#   poc/ 下新装配点 且 disable < 阈值      -> FAIL(除非在 POC_EXEMPT 里显式登记)
#
# 用法:
#   bash scripts/check-building-blocks-coverage.sh                  # 常规检查
#   bash scripts/check-building-blocks-coverage.sh --update-baseline # 打印新基线块(人工核对后粘回本文件)
#   bash scripts/check-building-blocks-coverage.sh --self-test      # 自证能红(夹具源树)
#
# 环境覆盖: BBL_REPO_ROOT(被扫描仓根) / BBL_POC_MIN_DISABLE(PoC 阈值, 默认 1)
#           BBL_OFFICIAL_REPO(仅用于打印 middleware order 提醒, 缺失则跳过)
#
# 退出码: 0=PASS 或 WARN / 1=FAIL(档位漂移或 PoC 混入) / 2=参数非法或环境错
#
# 基线为什么内嵌在本文件而不是 scripts/baselines/*.json:
#   ratchet-data-guard 门禁只允许 check-api-contract-fe-be.mjs 写 scripts/baselines/,
#   本门禁不抢那个独占写入口, 故改为「内嵌基线 + --update-baseline 打印待粘回」。

set -uo pipefail

REPO_ROOT="${BBL_REPO_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
POC_MIN_DISABLE="${BBL_POC_MIN_DISABLE:-1}"
OFFICIAL_REPO="${BBL_OFFICIAL_REPO:-/Users/mac/Documents/agentscope-java}"

command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 不可用"; exit 2; }

MODE="check"
case "${1:-}" in
    --self-test)       MODE="self-test" ;;
    --update-baseline) MODE="update" ;;
    "")                MODE="check" ;;
    *)                 echo "ERROR: 未知参数: ${1} (支持: --self-test / --update-baseline)"; exit 2 ;;
esac

# ---------------------------------------------------------------------------
# 基线: key = "<maven 模块>:<类名>", value = <disable 项数>
# 与本仓 src/main/java 下 HarnessAgent.builder() 装配点一一对应(2026-10-02 实测)。
# ---------------------------------------------------------------------------
read -r -d '' BASELINE_BLOCK <<'EOF'
ruoyi-modules/ruoyi-chat:DurableHarnessRunProcessor=14
ruoyi-modules/ruoyi-chat:AgentScopeChatKernel=10
ruoyi-modules/ruoyi-chat:CodingServiceImpl=9
ruoyi-modules/ruoyi-ipd:AgentScopeProjectAgentKernel=9
ruoyi-modules/ruoyi-chat:PocKernelSupport=0
EOF

# PoC 豁免名单: key = "<模块>:<类名>", value = 登记在案的 disable 项数(现状, 非推荐值)
read -r -d '' POC_EXEMPT_BLOCK <<'EOF'
ruoyi-modules/ruoyi-chat:PocKernelSupport=0
EOF

# --self-test 夹具用: 允许用环境变量替换上面两段内嵌基线(正常跑门禁时不设)
[ -n "${BBL_BASELINE_OVERRIDE:-}" ] && BASELINE_BLOCK="$BBL_BASELINE_OVERRIDE"
[ -n "${BBL_POC_OVERRIDE:-}" ] && POC_EXEMPT_BLOCK="$BBL_POC_OVERRIDE"

print_order_notice() {
    if [ ! -d "$OFFICIAL_REPO" ]; then
        echo "NOTE: 官方源码仓 ${OFFICIAL_REPO} 不存在, 跳过 middleware order() 提醒"
        return 0
    fi
    local total overridden
    total="$(find "$OFFICIAL_REPO/agentscope-core/src/main/java/io/agentscope/core/middleware" \
        "$OFFICIAL_REPO/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware" \
        -name '*.java' ! -name 'package-info.java' 2>/dev/null | grep -c . || true)"
    overridden="$(grep -rl "public int order()" --include='*.java' \
        "$OFFICIAL_REPO/agentscope-core/src/main/java/io/agentscope/core/middleware" \
        "$OFFICIAL_REPO/agentscope-harness/src/main/java/io/agentscope/harness/agent/middleware" \
        2>/dev/null | wc -l | tr -d ' ')"
    cat <<EOF
--------------------------------------------------------------------------------
⚠️  执行顺序是隐式的(提醒, 非判定)
    官方 middleware 目录共 ${total} 个 .java 文件, 覆盖 order() 的有 ${overridden} 个
    → 全部走 default order()==1, 执行顺序 == 注册顺序。
    增删 middleware 会静默改变相对顺序, 叠加「各装配点 disable 档位不同」后行为不可复现。
--------------------------------------------------------------------------------
EOF
}

# 扫描: 输出 "<key>\t<disable_count>\t<is_poc>\t<repo-relative path>"
scan_points() {
    BBL_REPO_ROOT="$REPO_ROOT" python3 - <<'PY'
import os, pathlib, re

root = pathlib.Path(os.environ["BBL_REPO_ROOT"])
roots = ["ruoyi-modules", "ruoyi-common", "ruoyi-admin"]
BUILDER = "HarnessAgent.builder()"
DISABLE = re.compile(r"\.disable[A-Za-z0-9_]*\(")
# 语句终止行: 含 .build() 但不含 builder()（排除 CompactionConfig.builder().build() 这类嵌套）
def is_build_end(line: str) -> bool:
    if "builder()" in line:
        return False
    i = line.find(".build()")
    if i < 0:
        return False
    return re.fullmatch(r"[\s;){}]*", line[i + len(".build()"):] or " ") is not None

for r in roots:
    base = root / r
    if not base.is_dir():
        continue
    for f in sorted(base.rglob("*.java")):
        s = str(f)
        if "/target/" in s or "/src/test/" in s:
            continue          # 只统计生产源树; 测试装配点不参与档位门禁
        try:
            lines = f.read_text(encoding="utf-8", errors="replace").splitlines()
        except OSError:
            continue
        for L, line in enumerate(lines):
            if BUILDER not in line:
                continue
            end = next((i for i in range(L, len(lines)) if is_build_end(lines[i])),
                       min(L + 120, len(lines) - 1))
            seg = "\n".join(lines[L:end + 1])
            n = len(DISABLE.findall(seg))
            parts = f.relative_to(root).parts
            # key 用 <maven 模块路径>:<类名>, 例如 ruoyi-modules/ruoyi-chat:AgentScopeChatKernel
            module = "/".join(parts[:2])
            cls = f.stem
            rel = f.relative_to(root).as_posix()
            is_poc = "1" if ("/poc/" in "/" + rel) else "0"
            print(f"{module}:{cls}\t{n}\t{is_poc}\t{rel}")
PY
}

# 解析 "key=count" 块 -> 行数组
parse_block() {
    printf '%s\n' "$1" | sed '/^[[:space:]]*$/d'
}

do_check() {
    local scan baseline poc_exempt
    scan="$(scan_points)"
    baseline="$(parse_block "$BASELINE_BLOCK")"
    poc_exempt="$(parse_block "$POC_EXEMPT_BLOCK")"

    if [ -z "$scan" ]; then
        echo "FAIL: 扫描到 0 个 HarnessAgent.builder() 装配点 —— 内核替换可能被回滚, 或扫描根路径错"
        return 1
    fi

    echo "被扫描仓根: ${REPO_ROOT}"
    echo "装配点数量: $(printf '%s\n' "$scan" | grep -c .)"
    echo "--------------------------------------------------------------------------------"
    local key count is_poc rel
    while IFS=$'\t' read -r key count is_poc rel; do
        [ -n "$key" ] || continue
        local mark=" "
        if [ "$is_poc" = "1" ]; then mark="!"; fi
        printf '%s %-48s disable=%-3s %s\n' "$mark" "$key" "$count" "$rel"
    done <<<"$scan"
    echo "--------------------------------------------------------------------------------"

    local fail=0 warn=0 seen="" k bcount bkey
    while IFS=$'\t' read -r key count is_poc rel; do
        [ -n "$key" ] || continue
        seen="${seen}${key} "
        bcount=""
        while IFS='=' read -r bkey bv; do
            [ -n "$bkey" ] || continue
            [ "$bkey" = "$key" ] && bcount="$bv" && break
        done <<<"$baseline"

        if [ -z "$bcount" ]; then
            echo "❌ FAIL 新增装配点(基线里没有的第 N 档): ${key} (disable=${count})"
            echo "        处置: 补齐 disable 档位后用 --update-baseline 登记; 不登记就是「以为没人管」"
            fail=$((fail + 1))
            continue
        fi
        if [ "$count" -gt "$bcount" ]; then
            echo "❌ FAIL 档位放松(只增不减): ${key} disable ${bcount} -> ${count}"
            echo "        处置: 官方能力默认全开, 少关 = 多给 LLM 权限; 必须回滚或显式登记"
            fail=$((fail + 1))
        elif [ "$count" -lt "$bcount" ]; then
            echo "⚠️  WARN 档位收紧(允许, 建议登记): ${key} disable ${bcount} -> ${count}"
            warn=$((warn + 1))
        fi

        if [ "$is_poc" = "1" ]; then
            local exempt="" found=0
            while IFS='=' read -r bkey bv; do
                [ -n "$bkey" ] || continue
                if [ "$bkey" = "$key" ]; then exempt="$bv"; found=1; break; fi
            done <<<"$poc_exempt"
            if [ "$count" -ge "$POC_MIN_DISABLE" ]; then
                echo "   PoC 装配点 disable=${count} >= 阈值 ${POC_MIN_DISABLE}, 放行"
            elif [ "$found" = "1" ]; then
                echo "⚠️  WARN PoC 装配点在 main 源树且 disable=${count}(已登记豁免, 现状基线非推荐值): ${key}"
                warn=$((warn + 1))
            else
                echo "❌ FAIL PoC 装配点混入生产源树: ${key} disable=${count} < 阈值 ${POC_MIN_DISABLE}"
                fail=$((fail + 1))
            fi
        fi
    done <<<"$scan"

    # 反向: 基线里有、但源码里没了
    while IFS='=' read -r bkey bv; do
        [ -n "$bkey" ] || continue
        case " ${seen}" in
            *" ${bkey} "*) : ;;
            *) echo "⚠️  WARN 基线登记但源码中已消失的装配点: ${bkey} (可能已重构/删除, 建议 --update-baseline 清理)"; warn=$((warn + 1)) ;;
        esac
    done <<<"$baseline"

    # 档位不一致 -> WARN(已知状态) + 差异明细
    local levels
    levels="$(printf '%s\n' "$scan" | cut -f2 | sort -n | uniq | tr '\n' ' ')"
    local nlevels
    nlevels="$(printf '%s\n' "$scan" | cut -f2 | sort -nu | grep -c . || true)"
    echo "--------------------------------------------------------------------------------"
    if [ "$nlevels" -gt 1 ]; then
        local ref
        ref="$(printf '%s\n' "$scan" | cut -f2 | sort -n | tail -1)"
        echo "⚠️  WARN 装配点 disable 档位不一致(已知状态, 不阻塞): 各档 = ${levels}(最高档 = ${ref})"
        echo "        差异明细(相对最高档 ${ref}):"
        while IFS=$'\t' read -r key count is_poc rel; do
            [ -n "$key" ] || continue
            [ "$count" -lt "$ref" ] || continue
            printf '          %-52s 实际 %-3s 少关 %s 项\n' "$key" "$count" "$((ref - count))"
        done <<<"$scan"
        warn=$((warn + 1))
    else
        echo "✅ 装配点 disable 档位一致(全部 = ${levels% })"
    fi

    print_order_notice
    echo "--------------------------------------------------------------------------------"
    if [ "$fail" -gt 0 ]; then
        echo "FAIL: ${fail} 项硬失败, ${warn} 项提醒"
        return 1
    fi
    echo "PASS: 无档位漂移(0 硬失败), ${warn} 项提醒"
    return 0
}

do_update_baseline() {
    local scan
    scan="$(scan_points)"
    if [ -z "$scan" ]; then
        echo "ERROR: 扫描到 0 个装配点, 不生成基线"
        return 2
    fi
    echo "# 核对无误后, 用下面内容整体替换本脚本的 BASELINE_BLOCK(read -r -d '' .. EOF 之间):"
    printf '\n'
    while IFS=$'\t' read -r key count is_poc rel; do
        [ -n "$key" ] || continue
        printf '%s=%s\n' "$key" "$count"
    done <<<"$scan"
    echo
    echo "# 同法更新 POC_EXEMPT_BLOCK(仅 src/main/java/**/poc/** 且确需豁免的):"
    while IFS=$'\t' read -r key count is_poc rel; do
        [ -n "$key" ] || continue
        [ "$is_poc" = "1" ] && printf '%s=%s\n' "$key" "$count"
    done <<<"$scan"
    return 0
}

do_self_test() {
    local tmpdir rc fixture
    tmpdir="$(mktemp -d)"
    trap 'rm -rf "$tmpdir"' RETURN
    fixture="${tmpdir}/fx"

    # 造一个最小夹具源树: 三个装配点, disable 数 9 / 9 / 0(poc)
    mk_point() { # <rel path> <n>  (生成含 n 个 .disableX() 的 builder 链)
        local rel="$1" n="$2" i
        mkdir -p "${fixture}/$(dirname "$rel")"
        {
            printf 'class Demo {\n'
            printf '    void go() {\n'
            printf '        var a = HarnessAgent.builder()\n'
            for ((i = 0; i < n; i++)); do printf '            .disableFeature%d()\n' "$i"; done
            printf '            .build();\n'
            printf '    }\n}\n'
        } > "${fixture}/${rel}"
    }

    # 基线 4 项, 匹配 fixture
    local M="ruoyi-modules/ruoyi-chat"
    local fx_baseline="${M}:demoA=9
${M}:demoB=9
${M}:demoPoc=0"
    local fx_poc="${M}:demoPoc=0"

    run_fx() { # 用夹具 + 自带基线跑 check
        BBL_REPO_ROOT="$fixture" \
        BBL_BASELINE_OVERRIDE="$1" BBL_POC_OVERRIDE="$2" \
        bash "$0" 2>/dev/null
    }

    # 用例 1: 与基线完全一致 -> PASS(0)
    mk_point "${M}/src/main/java/p/demoA.java" 9
    mk_point "${M}/src/main/java/p/demoB.java" 9
    mk_point "${M}/src/main/java/poc/demoPoc.java" 0
    run_fx "$fx_baseline" "$fx_poc" >/dev/null
    rc=$?
    [ "$rc" -eq 0 ] || { echo "SELF-TEST FAIL: 夹具与基线一致应 PASS(0), got ${rc}"; return 1; }
    echo "  [用例1] 夹具与基线一致 -> EXIT 0 ✅"

    # 用例 2: 新增第 4 档(基线外的装配点) -> FAIL(1)
    mk_point "${M}/src/main/java/p/demoC.java" 5
    run_fx "$fx_baseline" "$fx_poc" >/dev/null
    rc=$?
    [ "$rc" -eq 1 ] || { echo "SELF-TEST FAIL: 新增装配点应 FAIL(1), got ${rc}"; return 1; }
    echo "  [用例2] 新增第 4 档装配点 -> EXIT 1 ✅"
    rm -f "${fixture}/${M}/src/main/java/p/demoC.java"

    # 用例 3: 已知装配点 disable 项数增加 -> FAIL(1)
    mk_point "${M}/src/main/java/p/demoA.java" 11
    run_fx "$fx_baseline" "$fx_poc" >/dev/null
    rc=$?
    [ "$rc" -eq 1 ] || { echo "SELF-TEST FAIL: disable 项数增加应 FAIL(1), got ${rc}"; return 1; }
    echo "  [用例3] 已知装配点 disable 9 -> 11 -> EXIT 1 ✅"
    mk_point "${M}/src/main/java/p/demoA.java" 9

    # 用例 4: 未登记的 poc 装配点(0 项) -> FAIL(1)
    mk_point "${M}/src/main/java/poc/demoQ.java" 0
    local fx_baseline_nopoc="${M}:demoA=9
${M}:demoB=9
${M}:demoQ=0"
    run_fx "$fx_baseline_nopoc" "" >/dev/null
    rc=$?
    [ "$rc" -eq 1 ] || { echo "SELF-TEST FAIL: 未豁免的 PoC 装配点应 FAIL(1), got ${rc}"; return 1; }
    echo "  [用例4] PoC 装配点混入 main 源树(未登记豁免) -> EXIT 1 ✅"
    rm -f "${fixture}/${M}/src/main/java/poc/demoQ.java"

    # 用例 5: 非法参数 -> EXIT 2
    bash "$0" --bogus >/dev/null 2>&1
    rc=$?
    [ "$rc" -eq 2 ] || { echo "SELF-TEST FAIL: 非法参数应 EXIT 2, got ${rc}"; return 1; }
    echo "  [用例5] 非法参数 -> EXIT 2 ✅"

    # 用例 6: 档位不一致只 WARN 不 FAIL
    run_fx "$fx_baseline" "$fx_poc" >/dev/null
    rc=$?
    [ "$rc" -eq 0 ] || { echo "SELF-TEST FAIL: 档位不一致应 WARN(0), got ${rc}"; return 1; }
    echo "  [用例6] 档位不一致 -> WARN 但 EXIT 0 ✅"

    echo "SELF-TEST PASS: 6/6 护栏行为符合预期"
    return 0
}

case "$MODE" in
    check)            do_check; exit $? ;;
    update)           do_update_baseline; exit $? ;;
    self-test)        do_self_test; exit $? ;;
esac
