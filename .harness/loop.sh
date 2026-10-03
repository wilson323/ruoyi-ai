#!/usr/bin/env bash
# Bounded feedback cycle. No automatic commit/push, code edits or business effects.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
if [ "${1:-}" = "--dry-run" ]; then
  echo "Run existing verify entry; archive failures; enable fixed checks only after regression. No action executed."
  exit 0
fi
exec bash "$ROOT/.harness/verify.sh" governance "${1:?Pass the existing task/card identifier}"
