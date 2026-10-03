#!/usr/bin/env bash
# Engineering checks only; Java/package/runtime acceptance stays in the original plan.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
FRONTEND_ROOT=${IPD_FRONTEND_ROOT:-/Users/mac/Documents/ruoyi-ipd-web}
RUNNER="$FRONTEND_ROOT/scripts/engineering_harness.py"
if [ ! -f "$RUNNER" ]; then
  echo "Required IPD engineering runner missing: $RUNNER" >&2
  exit 2
fi
exec python3 "$RUNNER" --root "$ROOT" verify --profile "${1:-governance}" --task "${2:?Pass the existing task/card identifier as the second argument}"
