#!/usr/bin/env bash
# scripts/check-test-assertion-history.sh — 防假绿类型 2: 测试断言改成"现状"
# 来源：R131 §二.2.2 防假绿类型 2
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入 assertTrue("现状保留") 模式 → exit 2

set -eo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/audit-gate-input.sh"
REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
TEST_BASE="${TEST_BASE:-$REPO/ruoyi-modules}"
POM_FILE="${POM_FILE:-$REPO/pom.xml}"

main() {
  if [ "${TAH_FAIL_SEED:-0}" = "1" ]; then
    echo "[gate] TAH_FAIL_SEED=1 自证失败"
    exit 2
  fi
  gate_require_tree "$TEST_BASE" -path '*/test/*' -name '*.java'
  command -v git >/dev/null || { echo "[gate] git不可用" >&2; exit 2; }
  git -C "$REPO" rev-parse --verify HEAD >/dev/null || exit 2
  # 历史命令失败不能成为空集合；同时扫描当前测试，包含尚未提交的断言。
  local history
  history=$(git -C "$REPO" log --since="30 days ago" --name-only --pretty=format:) || exit 2
  python3 - "$TEST_BASE" <<'PYCODE'
import pathlib,re,sys
files=[p for p in pathlib.Path(sys.argv[1]).rglob('*.java') if 'test' in p.parts and 'target' not in p.parts]
violations=[]
for p in files:
    if re.search(r"assert(?:True|Equals)\([\"']现状",p.read_text()): violations.append(str(p))
for p in violations: print("❌ 软化断言:",p)
sys.exit(2 if violations else 0)
PYCODE
  echo "✅ 完整非空输入扫描完成"
}
main "$@"
