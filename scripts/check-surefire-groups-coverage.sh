#!/usr/bin/env bash
# scripts/check-surefire-groups-coverage.sh — 防假绿类型 1: Surefire groups 过滤静默跳过
# 来源：R131 §二.2.2 防假绿类型 1
# 撞车 0 让路：✅ scripts/ 白名单
#
# 判据（逐文件，2026-10-07 N-A 修订）：
#   旧判据只验「全仓是否存在任一带 @Tag 的文件」，覆盖率 0% 也放行——
#   这正是「测试写了但从未执行」长期无人发现的根因。改为逐文件判据：
#   凡 src/test/java 下含 @Test 而不含 @Tag("dev") 的文件，一律违规。
#
# 排除规则（避免误伤 support / fixtures / Main 等非测试类）：
#   路径含 /support/ /fixtures/ /fixture/ /qa/ /harness/ ，或文件名以
#   Main / Application / Fixtures / Utils / Support 结尾，或根本不含 @Test。
#
# 自证能红：--self-test（等价 EC_FAIL_SEED=1）
#   在 mktemp 目录造一个已知违规文件，必须抓到 → 再造干净目录，必须放行。
#   两者任一不成立即 exit 3（门禁自身失效），绝不当成通过。
#   **仓库零写入**：所有造样都在 mktemp -d 目录内。

set -eo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="${REPO:-$(cd "$SCRIPT_DIR/.." && pwd)}"
POM_FILE="${POM_FILE:-$REPO/pom.xml}"
# TEST_BASE 是本仓门禁自证框架（scripts/test-audit-gate-inputs.py）统一注入的**测试源码根**，
# 与 REPO（仓库根）不是一回事。2026-10-07 漏认它导致：该框架注入
# TEST_BASE=<不存在的路径> 期望门禁报红时，本脚本因找不到任何测试文件而放行，
# 被门禁 8（门禁自身的自证）当场抓住——这正是那道门禁存在的意义。
# 扫描范围必须认 TEST_BASE，否则自证框架对本脚本无效。
TEST_BASE="${TEST_BASE:-$REPO}"

# ---- 排除模式：非测试类不该被要求打 @Tag --------------------------------
EXCLUDE_PATH_RE='/(support|fixtures|fixture|testsupport|qa|harness)/'
EXCLUDE_NAME_RE='(Main|Application|App|Launcher)$|(Fixtures|Utils|Util|Support|Helper|Builder|Factory|Constants|ConstantsHolder)$'

# 判据核心：含 @Test 但不含 @Tag("dev")
# 打印 "<相对路径>:<首个@Test行号>"
find_violations() {
  local root="$1" file rel
  [ -d "$root" ] || { echo "[gate] 测试根目录不存在: $root" >&2; return 2; }
  local scanned=0
  while IFS= read -r file; do
    [ -f "$file" ] || continue
    scanned=$((scanned + 1))
    rel="${file#"$root"/}"
    # 排除非测试类
    if printf '%s' "$rel" | grep -qE "$EXCLUDE_PATH_RE"; then continue; fi
    local base; base="$(basename "$file" .java)"
    if printf '%s' "$base" | grep -qE "$EXCLUDE_NAME_RE"; then continue; fi
    # 只有含 @Test 的才需要判据；含 @Test 又无 @Tag("dev") 即违规
    file_matches '@Test' "$file" || continue
    if file_matches '@Tag("dev")' "$file"; then continue; fi
    local line; line="$(gate_grep -n -m1 '@Test' "$file" | head -1 | cut -d: -f1)"
    printf '%s:%s\n' "$rel" "${line:-1}"
  done < <(find "$root" -type f -name '*.java' \
             ! -path '*/target/*' ! -path '*/.harness/*' ! -path '*/.codex/*' \
             ! -path '*/.worktrees/*' ! -path '*/worktrees/*' | sort)
  # 空目录不算错误：仓库里存在空的 src/test/java（如 ruoyi-ipd 聚合目录）
  # 是常态，只有「一个文件都没扫到」才由调用方按全仓聚合判定。
  return 0
}

# grep 的 1 只表示无匹配；2+ 是读取错误，不能当作 0 个匹配。
gate_grep() {
  local status=0
  command grep "$@" || status=$?
  if [ "$status" -gt 1 ]; then
    echo "[gate] grep 读取失败 (exit=$status)" >&2
    return "$status"
  fi
  return 0
}

# 逐文件判据要用 grep 的真实 0/1（命中/未命中），不能用 gate_grep ——
# 后者按设计把「未命中」也归一成 0，用它做 if 条件会让两个分支同时失真
# （本次自证首跑就是栽在这里：恒绿空输出，门禁形同虚设）。
# 2+ 仍是读取错误，必须让门禁转红而不是当成未命中。
file_matches() {
  local status=0
  command grep -q "$1" "$2" || status=$?
  [ "$status" -gt 1 ] && { echo "[gate] grep 读取失败: $2" >&2; return 2; }
  [ "$status" -eq 0 ]
}

# ---- 自证能红：门禁必须既能抓红、也能放行 --------------------------------
# 在 mktemp 目录造样，验证门禁判据本身有效；仓库零写入。
# 返回 0 = 门禁有效；返回 3 = 门禁自身失效（恒绿或恒红），绝不当成通过。
self_test() {
  # 重入守卫：self_test 内会调用 main() 做端到端验证，若 main 又因 --self-test
  # 回到 self_test 就是无限递归（表现为脚本挂住，不报错）。一次性开关挡住它。
  if [ "${SGC_SELFTEST_ACTIVE:-0}" = "1" ]; then
    echo "[self-test] FAIL: 检测到重入（self_test → main → self_test）"
    return 3
  fi
  local tmp seed_repo violations rc
  tmp="$(mktemp -d)"
  # shellcheck disable=SC2064
  trap 'rm -rf "$tmp" ${seed_repo:-}' RETURN

  # 阳性对照：造一个「有 @Test、无 @Tag」的已知违规文件
  mkdir -p "$tmp/modules/src/test/java/org/demo"
  cat > "$tmp/modules/src/test/java/org/demo/UntaggedTest.java" <<'JAVA'
package org.demo;
import org.junit.jupiter.api.Test;
class UntaggedTest {
    @Test void neverRuns() { }
}
JAVA
  set +e
  violations="$(find_violations "$tmp/modules" 2>/dev/null)"
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    echo "[self-test] FAIL: 阳性对照扫描自身报错 (exit=$rc)"
    return 3
  fi
  if ! printf '%s' "$violations" | grep -q 'UntaggedTest.java'; then
    echo "[self-test] FAIL: 已知未打标文件没被抓到 —— 门禁恒绿，是假绿"
    echo "[self-test] 实际抓到的违规：${violations:-（空）}"
    return 3
  fi
  echo "[self-test] ✅ 阳性对照：抓到了 UntaggedTest.java（能红）"

  # 干净目录：已打标的文件必须放行
  mkdir -p "$tmp/clean/src/test/java/org/demo"
  cat > "$tmp/clean/src/test/java/org/demo/TaggedTest.java" <<'JAVA'
package org.demo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
@Tag("dev")
class TaggedTest {
    @Test void runs() { }
}
JAVA
  set +e
  violations="$(find_violations "$tmp/clean" 2>/dev/null)"
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    echo "[self-test] FAIL: 干净目录扫描自身报错 (exit=$rc)"
    return 3
  fi
  if [ -n "$violations" ]; then
    echo "[self-test] FAIL: 干净目录被误伤，违规清单：$violations"
    return 3
  fi
  echo "[self-test] ✅ 反向对照：已打标文件未被误伤（能绿）"

  # 排除规则自证：support/ 下的 @Test 不该被要求打标
  mkdir -p "$tmp/support/src/test/java/org/demo/support"
  cat > "$tmp/support/src/test/java/org/demo/support/Harness.java" <<'JAVA'
package org.demo.support;
import org.junit.jupiter.api.Test;
public class Harness {
    @Test void helper() { }
}
JAVA
  set +e
  violations="$(find_violations "$tmp/support" 2>/dev/null)"
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    echo "[self-test] FAIL: support/ 扫描自身报错 (exit=$rc)"
    return 3
  fi
  if [ -n "$violations" ]; then
    echo "[self-test] FAIL: support/ 下的辅助类被误伤，违规清单：$violations"
    return 3
  fi
  echo "[self-test] ✅ 排除规则：support/ 辅助类未被误伤"

  # ---- 端到端：把造样喂给 main() 本身，断言真实退出码 ----
  # 只测 find_violations 不够：万一 main() 的聚合/退出码逻辑本身坏了，
  # 单元自证照样绿。这里必须验「main 遇违规 → 非 0」且「遇干净 → 0」。
  local seed_repo; seed_repo="$(mktemp -d)"
  mkdir -p "$seed_repo/src/test/java/org/demo"
  cp "$tmp/modules/src/test/java/org/demo/UntaggedTest.java" "$seed_repo/src/test/java/org/demo/"
  cp "$POM_FILE" "$seed_repo/pom.xml" 2>/dev/null || printf '<project/>\n' > "$seed_repo/pom.xml"
  set +e
  # 两个细节缺一不可：
  # ① 必须放子 shell —— 违规路径走的是 `exit 2`，直接调用会把整个脚本一起杀掉，
  #    表现为自证跑到一半无声退出（exit 2），看不出是红还是崩。
  # ② 必须 `set --` 清空位置参数 —— 否则 main 继承 "$@"（=--self-test）会回到
  #    自证分支形成递归。
  # ③ 守卫位保证即使参数没清干净也只是返回 3 而不是无限递归。
  # ④ 必须清掉 FAIL_SEED 变量 —— 否则 EC_FAIL_SEED=1 跑自证时，内层 main 读到它
  #    又进 self_test，干净仓库那一跑返回 3，被误判成「端到端恒红」。
  #    用子 shell 的 env 前缀 + unset，避免污染当前 shell。
  ( set --; unset EC_FAIL_SEED SGC_FAIL_SEED; SGC_SELFTEST_ACTIVE=1 REPO="$seed_repo" POM_FILE="$seed_repo/pom.xml" main ) >/dev/null 2>&1
  rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    echo "[self-test] FAIL: 造样仓库跑 main() 竟返回 0 —— 端到端恒绿"
    return 3
  fi
  echo "[self-test] ✅ 端到端：违规仓库 main() 退出码 ${rc}（非 0，能红）"

  mkdir -p "$seed_repo/src/test/java/org/demo2"
  cp "$tmp/clean/src/test/java/org/demo/TaggedTest.java" "$seed_repo/src/test/java/org/demo2/"
  rm -f "$seed_repo/src/test/java/org/demo/UntaggedTest.java"
  set +e
  ( set --; unset EC_FAIL_SEED SGC_FAIL_SEED; SGC_SELFTEST_ACTIVE=1 REPO="$seed_repo" POM_FILE="$seed_repo/pom.xml" main ) >/dev/null 2>&1
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    echo "[self-test] FAIL: 干净仓库跑 main() 返回 $rc —— 端到端恒红/误伤"
    return 3
  fi
  echo "[self-test] ✅ 端到端：干净仓库 main() 退出码 0（能绿）"

  echo "[self-test] 自证通过（仓库零写入，全部在 mktemp 内）"
  return 0
}

main() {
  if [ "${1:-}" = "--self-test" ] || [ "${EC_FAIL_SEED:-0}" = "1" ] || [ "${SGC_FAIL_SEED:-0}" = "1" ]; then
    self_test
    return $?
  fi

  [ -s "$POM_FILE" ] || { echo "[gate] pom输入缺失或为空: $POM_FILE" >&2; exit 2; }

  # 找所有 src/test/java 根（排除归档污染目录）
  local violations="" total=0 scanned_total=0 root files_here
  while IFS= read -r root; do
    [ -d "$root" ] || continue
    files_here="$(find "$root" -type f -name '*.java' \
                    ! -path '*/target/*' ! -path '*/.harness/*' ! -path '*/.codex/*' \
                    ! -path '*/.worktrees/*' ! -path '*/worktrees/*' | grep -c . || true)"
    scanned_total=$((scanned_total + files_here))
    local v
    v="$(find_violations "$root")" || exit 2
    if [ -n "$v" ]; then
      violations="${violations}${v}"$'\n'
    fi
  done < <(find "$TEST_BASE" -type d -path '*/src/test/java' \
             ! -path '*/target/*' ! -path '*/.harness/*' ! -path '*/.codex/*' \
             ! -path '*/.worktrees/*' ! -path '*/worktrees/*' | sort)

  # 一个测试文件都没扫到 = 扫描范围错了（假 0 最危险），必须转红
  [ "$scanned_total" -gt 0 ] || { echo "[gate] 全仓没扫到任何测试源文件（扫描范围可能错了）" >&2; exit 2; }
  echo "[gate] 扫描测试源文件 $scanned_total 个"

  violations="$(printf '%s' "$violations" | grep -v '^$' || true)"
  if [ -n "$violations" ]; then
    total="$(printf '%s\n' "$violations" | grep -c . || true)"
    echo "❌ 有 $total 个测试文件写了 @Test 但没打 @Tag(\"dev\")，它们永远不会被 Surefire 执行："
    echo
    while IFS= read -r v; do
      [ -n "$v" ] || continue
      local f="${v%:*}" ln="${v##*:}"
      echo "   $f  (第 $ln 行是第一个 @Test)"
      echo "      修法：在该类的 class 声明正上方加一行 @Tag(\"dev\")，并 import org.junit.jupiter.api.Tag;"
    done <<< "$violations"
    echo
    echo "   说明：根 pom 的 surefire 配了 <groups>\${profiles.active}</groups>，"
    echo "         dev 是默认档；没打 @Tag(\"dev\") 的类会被静默跳过且不报错。"
    echo "         例外：若该模块 pom 里有 <groups combine.self=\"override\"/>，该模块不受此过滤影响"
    echo "         （ruoyi-ipd 自 P1-1 起就是这种配置）。"
    exit 2
  fi
  echo "✅ 逐文件判据通过：所有含 @Test 的文件都打了 @Tag(\"dev\")"
}
main "$@"