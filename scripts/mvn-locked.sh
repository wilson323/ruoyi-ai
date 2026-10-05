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
# 行为：按真实工作树根目录统一取锁（所有模块和阶段共享）。持锁期间其它进程排队等待（默认上限 40 分钟），
# 拿不到锁就明确报错退出，绝不并行写同一 target/。
# 释放锁用 trap，保证被中断/超时也会释放。

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_DIR="${RUOYI_MVN_LOCK_DIR:-/tmp/ruoyi-maven-locks}"
WAIT_SECONDS="${RUOYI_MVN_LOCK_WAIT:-2400}"

# 确保 JDK 17 和 Maven 环境变量就绪（防精简 PATH 导致 command not found 或 Java 版本漂移）
export JAVA_HOME="${JAVA_HOME:-/Users/mac/tools/jdk-17/Contents/Home}"
if [ -d "/Users/mac/tools/maven/bin" ]; then
  export PATH="/Users/mac/tools/maven/bin:$PATH"
fi
export PATH="$JAVA_HOME/bin:$PATH"

mkdir -p "$LOCK_DIR"

# reactor 的 -am/-amd 会触及依赖/依赖方，模块列表与阶段名都不能隔离 target。
# 同一工作树统一串行；不同工作树的 target 独立，可并行构建。
KEY="$(printf '%s' "$REPO_ROOT" | shasum -a 256 | awk '{print $1}')"
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
