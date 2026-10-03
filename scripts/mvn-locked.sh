#!/usr/bin/env bash
# 带互斥锁的 Maven 包装器（治「多 agent 共用 target/ 导致构建产物互相覆盖」）
#
# 为什么需要：本仓曾出现 19 路 agent 同时对 ruoyi-modules/ruoyi-ipd 跑 mvn，
# 全部写同一个 target/，导致字节码在测试运行途中被重写，表现为
# NoClassDefFoundError → Mockito Unfinished mocking session 级联，
# 单次产生 979 个假错误，且全模块基线数字无法复现。
#
# 用法：把 `mvn` 换成 `bash scripts/mvn-locked.sh <原参数...>`
# 例：  bash scripts/mvn-locked.sh -o test -pl ruoyi-modules/ruoyi-ipd
#
# 行为：按模块 + phase 取锁。持锁期间其它进程排队等待（默认上限 40 分钟），
# 拿不到锁就明确报错退出，绝不并行写同一 target/。
# 释放锁用 trap，保证被中断/超时也会释放。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_DIR="${RUOYI_MVN_LOCK_DIR:-/tmp/ruoyi-maven-locks}"
WAIT_SECONDS="${RUOYI_MVN_LOCK_WAIT:-2400}"

mkdir -p "$LOCK_DIR"

PLUGIN="root"
PHASE="other"
prev=""
for a in "$@"; do
  case "$prev" in
    -pl|--projects) PLUGIN="$a" ;;
    -am|-amd|--also-make) PHASE="reactor" ;;
  esac
  case "$a" in
    compile) PHASE="compile" ;;
    test-compile) PHASE="test-compile" ;;
    test) [ "$PHASE" = "other" ] && PHASE="test" ;;
    package) PHASE="package" ;;
  esac
  prev="$a"
done
# -am 会连带构建依赖模块，那些模块的 target 同样会被写；用 reactor 粒度串行化
KEY="$(printf '%s' "$PLUGIN" | tr -c 'A-Za-z0-9._-' '_')__${PHASE}"
LOCK="$LOCK_DIR/$KEY.lock"

acquire() {
  local waited=0
  while ! mkdir "$LOCK" 2>/dev/null; do
    if [ "$waited" -ge "$WAIT_SECONDS" ]; then
      echo "[mvn-locked] ❌ 等锁超时 ${WAIT_SECONDS}s，锁=$LOCK" >&2
      echo "[mvn-locked]    持锁者: $(cat "$LOCK/pid" 2>/dev/null || echo '未知')" >&2
      return 1
    fi
    sleep 3
    waited=$((waited + 3))
  done
  echo "$$" > "$LOCK/pid" 2>/dev/null || true
  return 0
}

release() { rm -rf "$LOCK" 2>/dev/null || true; }

if ! acquire; then
  exit 75   # EX_TEMPFAIL：明确是排队失败，不是构建失败
fi
trap release EXIT INT TERM

echo "[mvn-locked] 🔒 获得锁 $KEY (pid $$)" >&2
cd "$REPO_ROOT" || exit 1
mvn "$@"
rc=$?
release
trap - EXIT INT TERM
echo "[mvn-locked] 🔓 释放锁 $KEY (rc=$rc)" >&2
exit $rc
