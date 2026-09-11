#!/usr/bin/env bash
# scripts/check_demo_default_off.sh
# R30+ 治理门禁：demo 模式默认必须关闭，否则拦全部写操作。
# 一次 demo 误开启 -> 全部 IPD 写操作返回 "演示模式，不允许操作"。

set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

STRICT=0
while [ $# -gt 0 ]; do
  case "$1" in
    --strict) STRICT=1; shift ;;
    -h|--help) sed -n '2,5p' "$0"; exit 0 ;;
    *) echo "ERROR: unknown arg $1" >&2; exit 2 ;;
  esac
done

cd "$REPO_ROOT" || exit 2

echo "[check_demo_default_off] strict=$STRICT"

# Sentinel: scan path integrity
APP_FILES=$(find ruoyi-admin/src/main/resources ruoyi-extend -name "application*.yml" \
  -not -path "*/node_modules/*" -not -path "*/target/*" -not -path "*/.claude/worktrees/*" 2>/dev/null)
APP_COUNT=$(printf '%s\n' "$APP_FILES" | wc -l | tr -d ' ')
echo "Scan found $APP_COUNT application*.yml files (sentinel >= 3)"
[ "$APP_COUNT" -lt 3 ] && { echo "ERROR: scan path wrong, less than 3 files"; exit 3; }

problems=0
warnings=0

# Helper: extract demo.enabled value from a YAML file
extract_demo() {
  local f="$1"
  awk '/^demo:/{flag=1;next} /^[a-zA-Z]/{flag=0} flag && /enabled:/{gsub(/[# ]/,"",$2); print $2; exit}' "$f" 2>/dev/null
}

# Check 1: parent application.yml
echo
echo "[1/3] Parent application.yml demo.enabled..."
PARENT="ruoyi-admin/src/main/resources/application.yml"
if [ -f "$PARENT" ]; then
  V=$(extract_demo "$PARENT")
  if [ "$V" = "false" ]; then
    echo "  OK: parent demo.enabled=$V"
  elif [ "$V" = "true" ]; then
    echo "  FAIL: parent demo.enabled=true -- blocks all writes"
    problems=$((problems + 1))
  elif [ -z "$V" ]; then
    echo "  OK: parent demo.enabled absent (default off)"
  else
    if printf '%s' "$V" | grep -qE '\$\{|\{|:'; then
      echo "  FAIL: parent demo.enabled uses env injection: $V (must be literal)"
      problems=$((problems + 1))
    else
      echo "  FAIL: parent demo.enabled unexpected value: $V"
      problems=$((problems + 1))
    fi
  fi
else
  echo "  FAIL: parent config missing: $PARENT"
  problems=$((problems + 1))
fi

# Check 2: prod profile
echo
echo "[2/3] application-prod.yml demo.enabled..."
PROD="ruoyi-admin/src/main/resources/application-prod.yml"
if [ -f "$PROD" ]; then
  V=$(extract_demo "$PROD")
  if [ "$V" = "false" ]; then
    echo "  OK: prod demo.enabled=$V"
  elif [ "$V" = "true" ]; then
    echo "  FAIL: prod demo.enabled=true -- production dangerous"
    problems=$((problems + 1))
  elif [ -z "$V" ]; then
    echo "  OK: prod demo.enabled absent (inherits parent)"
  else
    if printf '%s' "$V" | grep -qE '\$\{|\{|:'; then
      echo "  FAIL: prod demo.enabled uses env injection: $V"
      problems=$((problems + 1))
    else
      echo "  WARN: prod demo.enabled unexpected: $V"
      warnings=$((warnings + 1))
    fi
  fi
else
  echo "  SKIP: no application-prod.yml"
fi

# Check 3: dev profile (allowed to override)
echo
echo "[3/3] application-dev.yml demo.enabled..."
DEV="ruoyi-admin/src/main/resources/application-dev.yml"
if [ -f "$DEV" ]; then
  V=$(extract_demo "$DEV")
  if [ -z "$V" ]; then
    echo "  OK: dev demo.enabled absent (inherits parent=false)"
  elif [ "$V" = "false" ]; then
    echo "  OK: dev demo.enabled=false"
  elif [ "$V" = "true" ]; then
    if [ "$STRICT" = "1" ]; then
      echo "  FAIL: --strict blocks dev demo.enabled=true"
      problems=$((problems + 1))
    else
      echo "  OK: dev demo.enabled=true (dev override allowed)"
    fi
  else
    if printf '%s' "$V" | grep -qE '\$\{|\{|:'; then
      echo "  WARN: dev demo.enabled env injection: $V (allowed for dev)"
      warnings=$((warnings + 1))
    else
      echo "  WARN: dev demo.enabled unexpected: $V"
      warnings=$((warnings + 1))
    fi
  fi
else
  echo "  SKIP: no application-dev.yml"
fi

echo
echo "==== Summary ===="
echo "  Blocking issues: $problems"
echo "  Warnings:        $warnings"

[ "$problems" -gt 0 ] && {
  echo
  echo "FAIL: demo default off violation detected"
  echo "  Fix: parent application.yml demo.enabled: false (literal)"
  echo "       application-prod.yml demo.enabled: false"
  echo "       application-dev.yml may override to true (demo mode)"
  echo "       See AGENTS.md and .claude/skills/ipd-guard/SKILL.md"
  exit 1
}

[ "$warnings" -gt 0 ] && [ "$STRICT" = "1" ] && {
  echo "WARN escalated to FAIL by --strict"
  exit 1
}

echo
echo "OK: demo defaults correctly off in production paths"
exit 0