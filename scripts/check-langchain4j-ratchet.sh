#!/usr/bin/env bash
# scripts/check-langchain4j-ratchet.sh — G2 langchain4j 双轨面积「只减不增」棘轮门禁
# 模式复刻: R212 孤儿端点棘轮(scripts/check-api-contract-fe-be.mjs)+ ratchet-data-guard 基线约定
#
# 背景: 单轨内核替换(W1~W8)前,langchain4j 引用面积只许收缩不许增长;每次新增引用必须
#       显式走 --update-baseline 登记,防双轨面积静默回潮。
#
# 用法:
#   bash scripts/check-langchain4j-ratchet.sh                    # 检查: count > baseline -> EXIT 1
#   bash scripts/check-langchain4j-ratchet.sh --update-baseline  # 基线唯一写入口(重算落盘 + growth_log 登记)
#   bash scripts/check-langchain4j-ratchet.sh --self-test        # 夹具自测: 护栏必须真会拦(不改真基线)
#
# 退出码: 0=PASS(持平或只减) / 1=FAIL(面积增长) / 2=环境错(无 python3 / 缺基线且非 update 模式)
# 基线文件: scripts/baselines/langchain4j-count.json — 脚本外禁改(ratchet-data-guard 约定)
# 扫描口径: grep -rl "dev.langchain4j" ruoyi-modules ruoyi-common --include="*.java" | grep -v target
#           (每文件计 1,与方案 G2 口径一致;新增 langchain4j 文件即计数增长)

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="${LANGCHAIN4J_RATCHET_BASELINE:-${REPO_ROOT}/scripts/baselines/langchain4j-count.json}"

command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 not available"; exit 2; }

MODE="check"
case "${1:-}" in
    --update-baseline) MODE="update" ;;
    --self-test)       MODE="self-test" ;;
    "")                MODE="check" ;;
    *)                 echo "ERROR: unknown arg: ${1}"; exit 2 ;;
esac

# 计数 + 文件清单(stdout: 第 1 行 count, 其后每行一个相对路径)
scan_files() {
    ( cd "${REPO_ROOT}" && grep -rl "dev.langchain4j" ruoyi-modules ruoyi-common --include="*.java" 2>/dev/null | grep -v target | sort )
}

do_update() {
    local files count git_head now
    files="$(scan_files)"
    count="$(printf '%s\n' "${files}" | grep -c . || true)"
    git_head="$(git -C "${REPO_ROOT}" rev-parse HEAD 2>/dev/null || echo unknown)"
    now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    SCAN_FILES="${files}" SCAN_COUNT="${count}" GIT_HEAD="${git_head}" NOW="${now}" BASELINE="${BASELINE}" python3 - <<'PY'
import hashlib, json, os, pathlib
files = [l for l in os.environ["SCAN_FILES"].splitlines() if l.strip()]
count = int(os.environ["SCAN_COUNT"])
base_path = pathlib.Path(os.environ["BASELINE"])
old_count = None
growth_log = []
if base_path.exists():
    old = json.loads(base_path.read_text())
    old_count = old.get("count")
    growth_log = old.get("growth_log", [])
growth_log.append({"at": os.environ["NOW"], "from": old_count, "to": count,
                   "git_head": os.environ["GIT_HEAD"],
                   "reason": "manual --update-baseline"})
doc = {
    "$schema_version": 1,
    "policy": "langchain4j 双轨面积只减不增(R212 棘轮模式复刻; 脚本外禁改基线)",
    "generated_at": os.environ["NOW"],
    "git_head": os.environ["GIT_HEAD"],
    "gen_cmd": "bash scripts/check-langchain4j-ratchet.sh --update-baseline",
    "scan_cmd": "grep -rl dev.langchain4j ruoyi-modules ruoyi-common --include=*.java | grep -v target | wc -l",
    "count": count,
    "paths": files,
    "paths_sha256": hashlib.sha256("\n".join(files).encode()).hexdigest(),
    "growth_log": growth_log[-50:],
}
base_path.parent.mkdir(parents=True, exist_ok=True)
base_path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n")
print(f"baseline updated: {base_path} count={count} (old={old_count})")
PY
}

do_check() {
    if [ ! -f "${BASELINE}" ]; then
        echo "ERROR: baseline missing: ${BASELINE} (先跑 --update-baseline 建基线)"
        exit 2
    fi
    local files count
    files="$(scan_files)"
    count="$(printf '%s\n' "${files}" | grep -c . || true)"
    SCAN_FILES="${files}" SCAN_COUNT="${count}" BASELINE="${BASELINE}" python3 - <<'PY'
import hashlib, json, os, pathlib, sys
files = [l for l in os.environ["SCAN_FILES"].splitlines() if l.strip()]
count = int(os.environ["SCAN_COUNT"])
base = json.loads(pathlib.Path(os.environ["BASELINE"]).read_text())
b_count = base.get("count")
b_paths = base.get("paths", [])
b_sha = base.get("paths_sha256", "")
real_sha = hashlib.sha256("\n".join(b_paths).encode()).hexdigest()
print(f"count={count} vs baseline={b_count}")
if b_sha and b_sha != real_sha:
    print("ERROR: baseline 防伪失败: paths 与 paths_sha256 不一致(基线被脚本外手改?)")
    sys.exit(2)
if count > b_count:
    added = sorted(set(files) - set(b_paths))
    print(f"FAIL: langchain4j 引用面积增长 {b_count} -> {count} (只减不增棘轮)")
    for p in added[:20]:
        print(f"  + {p}")
    print("  处置: 删 langchain4j 引用 / 或确需增长走 --update-baseline 登记(脚本外禁改基线)")
    sys.exit(1)
if count < b_count:
    print(f"NOTE: 面积收缩 {b_count} -> {count},建议 --update-baseline 收紧紧箍(可选)")
print("PASS: langchain4j 引用面积未增长")
PY
}

do_self_test() {
    local tmpdir rc
    tmpdir="$(mktemp -d)"
    # 用例 1: 真基线 -> 必须 PASS(0)
    LANGCHAIN4J_RATCHET_BASELINE="${BASELINE}" bash "$0" >/dev/null 2>&1
    rc=$?
    [ "${rc}" -eq 0 ] || { echo "SELF-TEST FAIL: 真基线应 PASS, got ${rc}"; rm -rf "${tmpdir}"; exit 1; }
    # 用例 2: 基线 -1 -> 必须 FAIL(1)(护栏真会拦)
    SCAN_TMP="${tmpdir}/baseline.json" BASELINE="${BASELINE}" python3 - <<'PY'
import json, os, pathlib
base = json.loads(pathlib.Path(os.environ["BASELINE"]).read_text())
base["count"] = base["count"] - 1
pathlib.Path(os.environ["SCAN_TMP"]).write_text(json.dumps(base, ensure_ascii=False))
PY
    LANGCHAIN4J_RATCHET_BASELINE="${tmpdir}/baseline.json" bash "$0" >/dev/null 2>&1
    rc=$?
    rm -rf "${tmpdir}"
    [ "${rc}" -eq 1 ] || { echo "SELF-TEST FAIL: 基线-1 应 FAIL(1), got ${rc}"; exit 1; }
    echo "SELF-TEST PASS: 护栏真会拦(基线-1 -> EXIT 1)"
}

case "${MODE}" in
    update)    do_update ;;
    check)     do_check ;;
    self-test) do_self_test ;;
esac
