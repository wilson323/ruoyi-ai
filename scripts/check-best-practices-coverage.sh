#!/usr/bin/env bash
# 只校验既有最佳实践登记结构和所引用门禁入口，不证明实践已执行。
# 没有经过验证的验收集合与分母，不计算或宣称落地率。
set -euo pipefail
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ "${BP_FAIL_SEED:-0}" == "1" ]]; then
  echo '[FAIL] BP_FAIL_SEED injected failure (not validation evidence)'
  exit 1
fi
command -v python3 >/dev/null || { echo '[FAIL] python3 unavailable' >&2; exit 2; }
python3 - "$REPO" <<'PY'
from pathlib import Path
import re
import sys
root = Path(sys.argv[1])
registries = sorted((root / 'docs/ipd-系统说明').glob('最佳实践应用登记位-*.md'))
if not registries:
    sys.exit('[FAIL] required registry missing')
required_scripts = {
    'scripts/check-best-practices-coverage.sh': 'BP_FAIL_SEED',
    'scripts/check-naming-convention.sh': 'NAMING_FAIL_SEED',
    'scripts/check-doc-code-sync.sh': 'DOCSYNC_FAIL_SEED',
    'scripts/check-memory-leak-pattern.sh': 'LEAK_FAIL_SEED',
    'scripts/check-a11y-basics.sh': 'A11Y_FAIL_SEED',
}
for name, marker in required_scripts.items():
    file = root / name
    if not file.is_file() or not file.stat().st_size:
        sys.exit(f'[FAIL] required script missing or empty: {name}')
    if marker not in file.read_text():
        sys.exit(f'[FAIL] required injection entry absent: {name}')
for registry in registries:
    if not registry.stat().st_size:
        sys.exit(f'[FAIL] empty registry: {registry.name}')
    entries = {}
    for line in registry.read_text().splitlines():
        if not re.match(r'^\s*\|\s*\*\*BP-\d{3}\*\*\s*\|', line):
            continue
        fields = [field.strip() for field in line.strip().strip('|').split('|')]
        if len(fields) != 8 or not all(fields):
            sys.exit(f'[FAIL] expected 8 nonempty fields: {registry.name}')
        identifier = fields[0].strip('*')
        if identifier in entries:
            sys.exit(f'[FAIL] duplicate entry: {identifier}')
        entries[identifier] = fields
    expected = {f'BP-{number:03d}' for number in range(1, 16)}
    if set(entries) != expected:
        sys.exit(f'[FAIL] required IDs differ: missing={sorted(expected-set(entries))}, unexpected={sorted(set(entries)-expected)}')
    print(f'[PASS] registry structure only: {registry.name}; 15 unique entries, 8 nonempty fields')
print('[PASS] required script entries present; injection markers are text checks, not executed counterexamples')
print('[PENDING_VALIDATION] implementation, runtime and business acceptance are not evaluated; implementation coverage is unknown')
PY
