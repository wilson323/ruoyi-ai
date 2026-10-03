#!/usr/bin/env bash
# scripts/check-mock-data-realism.sh — 防假绿类型 3: mock 造真库不可能产生的数据
# 来源：R131 §二.2.2 防假绿类型 3
# 撞车 0 让路：✅ scripts/ 白名单
# 自证能红：FAIL_SEED=1 → 注入 id=99999 不可能的 mock → exit 2

set -eo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/lib/audit-gate-input.sh"
REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
TEST_BASE="${TEST_BASE:-$REPO/ruoyi-modules}"
POM_FILE="${POM_FILE:-$REPO/pom.xml}"

main() {
  if [ "${MDR_FAIL_SEED:-0}" = "1" ]; then
    echo "[gate] MDR_FAIL_SEED=1 自证失败"
    exit 2
  fi
  gate_require_tree "$TEST_BASE" -path '*/test/*' -name '*.java'
  python3 - "$TEST_BASE" <<'PYCODE'
import pathlib,re,sys
violations=[]
for p in pathlib.Path(sys.argv[1]).rglob('*.java'):
    if 'test' not in p.parts or 'target' in p.parts: continue
    text=p.read_text()
    if not re.search(r'Mockito|when\(|mock\(',text): continue
    if re.search(r'when\(.*\)\.thenReturn\([0-9]{5,}|2099|9999',text): violations.append(str(p))
for p in violations: print("❌ mock值域启发式需核实:",p)
# 这里只验证原模式，不声称静态数字能证明真实数据库值域。
sys.exit(2 if violations else 0)
PYCODE
  echo "✅ 完整非空输入扫描完成"
}
main "$@"
