#!/usr/bin/env bash
# scripts/check-assertion-line-drift.sh — 防假绿类型 5: 行号型断言历史漂移
# 来源：R131 §二.2.2 防假绿类型 5 + R131 §二.2.4 T5
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入断言指向已删除行号 → exit 2

set -eo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/audit-gate-input.sh"
REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
TEST_BASE="${TEST_BASE:-$REPO/ruoyi-modules}"
POM_FILE="${POM_FILE:-$REPO/pom.xml}"

main() {
  if [ "${ALD_FAIL_SEED:-0}" = "1" ]; then
    echo "[gate] ALD_FAIL_SEED=1 自证失败"
    exit 2
  fi
  gate_require_tree "$TEST_BASE" -path '*/test/*' -name '*.java'
  python3 - "$TEST_BASE" <<'PYCODE'
import pathlib,re,sys
violations=[]
for p in pathlib.Path(sys.argv[1]).rglob('*.java'):
    if 'test' not in p.parts or 'target' in p.parts: continue
    text=p.read_text()
    numbers=re.findall(r'assert(?:Equals|True|False|NotNull)\([^,\n]+,[^,\n]+,\s*([0-9]{3,})',text)
    if numbers and max(map(int,numbers))>len(text.splitlines()): violations.append(str(p))
for p in violations: print("❌ 疑似行号型断言漂移:",p)
sys.exit(2 if violations else 0)
PYCODE
  echo "✅ 完整非空输入扫描完成"
}
main "$@"
