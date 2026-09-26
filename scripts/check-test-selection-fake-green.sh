#!/usr/bin/env bash
# scripts/check-test-selection-fake-green.sh
# ----------------------------------------------------------------------
# 判据：每个被 Surefire 默认包含规则识别的测试源文件，至少要有一个方法真的会被执行。
#
# 为什么另写一个（2026-09-25 本机实测，非推断）：
#   父 pom.xml <build><plugins> 的 surefire 配 <groups>${profiles.active}</groups>，
#   且 activeByDefault 在 dev → 默认 groups=dev；没有任何子模块 pom 覆盖 <groups>。
#   于是"没打 @Tag("dev") 的测试类"不是失败，而是**根本不进入执行**，构建照报 SUCCESS：
#     mvn -o -pl ruoyi-common/ruoyi-common-trace test
#       → RC=0 BUILD SUCCESS  Tests run: 8  只产出 2 份 XML
#       → 无 @Tag 的 TraceContextTest 零报告、零失败、零跳过提示
#   旧件 check-surefire-fake-green.sh 的判据是"全模块 @Tag 唯一值种类数"，实测三处不成立：
#     (1) 诊断写死"默认 profile=local 下 0 个测试运行"，实际默认是 dev、真跑 2507 个；
#     (2) 唯一值 >=2 时打印"✅ 无单一过滤风险"，而新增一个 @Tag("unit") 的类正是"整类不跑"；
#     (3) 完全无 @Tag 的文件不进任何统计（夹具实测：文件数 2→3，errors 恒定不变）。
#   本件只判一件事，且这一件事可静态判定：该文件是否被 groups 选中。
#
# 分层（有意区分"阻断"与"记账"）：
#   阻断 exit 1 = 出现基线之外的"整类不会被执行"的测试源文件（新增静默跳过）
#   记账 exit 0 = 基线内已登记的存量（逐条消，不得往里加）+ <groups> 动态 profile 耦合
#   环境错 exit 2 = 拿不到 pom / 不在 git 仓 / 基线文件缺失 —— 读不到就当过是本仓 M2/M3 的老病，不继承
#
# 用法（仓根）:
#   bash scripts/check-test-selection-fake-green.sh              # 实跑
#   bash scripts/check-test-selection-fake-green.sh --json       # 只输出 JSON
#   bash scripts/check-test-selection-fake-green.sh --self-test  # 夹具自测（护栏必须真会拦）
#   bash scripts/check-test-selection-fake-green.sh --baseline PATH
#
# 存量来源：见 docs/ipd-系统说明/log.md 对应轮次；基线只允许单调收敛。
# ----------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
BASELINE="${BASELINE:-$REPO_ROOT/scripts/ci/test-selection-fake-green-baseline.txt}"
JSON_ONLY=0
SELF_TEST=0

while [ $# -gt 0 ]; do
  case "$1" in
    --json)       JSON_ONLY=1; shift ;;
    --self-test)  SELF_TEST=1; shift ;;
    --baseline)   BASELINE="$2"; shift 2 ;;
    -h|--help)    sed -n '2,40p' "$0"; exit 0 ;;
    *)            echo "ERROR: 未知参数 $1" >&2; exit 2 ;;
  esac
done

say() { [ "$JSON_ONLY" = "1" ] || echo "$@"; }
die() { echo "❌ $*" >&2; exit 2; }

# ---- 解析当前生效的 groups（不写死 local/dev，一律从 pom 现查）----
resolve_groups() {  # $1 = pom path; echoes "<groups>|<excludedGroups>|<来源profile>"
  local pom="$1"
  [ -f "$pom" ] || die "pom.xml 不存在: $pom"
  python3 - "$pom" <<'PY'
import re, sys
s = open(sys.argv[1], encoding='utf-8').read()
g = re.search(r'<groups>([^<]*)</groups>', s)
x = re.search(r'<excludedGroups>([^<]*)</excludedGroups>', s)
if not g:
    print("NO_GROUPS||"); sys.exit(0)
val = g.group(1).strip()
pid = '-'
m = re.search(r'<activeByDefault>\s*true\s*</activeByDefault>', s)
if '${profiles.active}' in val:
    if not m:
        print("NO_ACTIVE_BY_DEFAULT||"); sys.exit(0)
    for blk in re.finditer(r'<profile>(.*?)</profile>', s, re.S):
        b = blk.group(1)
        if 'activeByDefault>true' in b.replace(' ', '') or 'activeByDefault>true' in b:
            i = re.search(r'<id>([^<]+)</id>', b)
            pa = re.search(r'<profiles\.active>([^<]+)</profiles\.active>', b)
            pid = i.group(1) if i else '?'
            val = pa.group(1).strip() if pa else "UNRESOLVED"
            break
print(f"{val}|{x.group(1).strip() if x else ''}|{pid}")
PY
}

# ---- 列出被 Surefire 默认包含规则识别的测试源文件 ----
list_test_sources() {  # $1 = root（必须是 git 仓）
  ( cd "$1" && git ls-files \
      '*Test.java' '*Tests.java' '*TestCase.java' 'Test*.java' ) 2>/dev/null | grep '/src/test/' || true
}

# ---- 主判定 ----
run_check() {  # $1 = repo root, $2 = baseline path, $3 = expect_blocking_gate(1/0)
  local root="$1" baseline="$2"
  local parsed groups excluded src_profile
  parsed="$(resolve_groups "$root/pom.xml")"
  case "$parsed" in
    NO_GROUPS*)              say "ℹ️ <groups> 未配置 → Surefire 无标签过滤，全部测试类都会跑"; return 0 ;;
    NO_ACTIVE_BY_DEFAULT*)   die "<groups>=\${profiles.active} 但 pom 里找不到 activeByDefault，无法判定实际生效标签";;
  esac
  IFS='|' read -r groups excluded src_profile <<<"$parsed"
  if [ "$groups" = "UNRESOLVED" ]; then
    die "profile $src_profile 未定义 profiles.active，无法判定"
  fi

  say "▶ 现查生效配置: <groups>=${groups} （来自 activeByDefault profile=${src_profile}） excluded=${excluded}"

  local files
  files="$(list_test_sources "$root")"
  if [ -z "$files" ]; then
    die "git ls-files 在本仓没列出任何 *Test.java —— 采集方式失效，拒绝默认通过"
  fi

  local total=0 unselected=""
  while IFS= read -r f; do
    if [ -z "$f" ]; then continue; fi
    if [ ! -f "$root/$f" ]; then continue; fi
    total=$((total + 1))
    local tags hit=0
    tags="$(grep -hoE '@Tag\("[^"]+"\)' "$root/$f" 2>/dev/null | sed -E 's/@Tag\("([^"]+)"\)/\1/' | sort -u || true)"
    if [ -n "$tags" ]; then
      while IFS= read -r t; do
        if [ "$t" = "$groups" ]; then hit=1; fi
      done <<<"$tags"
    fi
    if [ "$hit" = "0" ]; then
      unselected="${unselected}${f}"$'\n'
    fi
  done <<<"$files"

  if [ ! -f "$baseline" ]; then
    die "基线文件缺失: ${baseline} （存量债务已登记在此，缺失即拒绝判定，不得默认通过）"
  fi
  local base_count
  base_count="$(grep -cvE '^[[:space:]]*(#|$)' "$baseline" || true)"

  local fresh="" stale=""
  while IFS= read -r u; do
    if [ -z "$u" ]; then continue; fi
    grep -qxF "$u" "$baseline" || fresh="${fresh}${u}"$'\n'
  done <<<"$unselected"
  while IFS= read -r b; do
    if [ -z "$b" ]; then continue; fi
    if [ -n "$(printf '%s' "$unselected" | grep -xF "$b" || true)" ]; then :; else stale="${stale}${b}"$'\n'; fi
  done < <(grep -vE '^[[:space:]]*(#|$)' "$baseline" || true)

  local fresh_n stale_n unselected_n
  fresh_n=$(printf '%s' "$fresh" | grep -c . || true)
  stale_n=$(printf '%s' "$stale" | grep -c . || true)
  unselected_n=$(printf '%s' "$unselected" | grep -c . || true)

  say "  测试源文件总数: $total"
  say "  整类不会被执行: ${unselected_n} （基线内 $((unselected_n - fresh_n))，基线外 ${fresh_n}）"
  if [ "$fresh_n" -gt 0 ]; then
    say "  ⛔ 基线之外的静默跳过测试类（新增即阻断）:"
    printf '%s\n' "$fresh" | grep . | sed 's/^/      /'
    say "    修法：给该类加 @Tag(\"$groups\")，或确认它本就该跑后按基线规则登记（不得随意加基线）"
  fi
  if [ "$stale_n" -gt 0 ]; then
    say "  🔧 基线可收敛（已不在静默跳过名单，请从基线删除）:"
    printf '%s\n' "$stale" | grep . | sed 's/^/      /'
  fi
  say "  📚 记账：<groups> 为 \${profiles.active} 动态耦合，存量基线 $base_count 条待逐条消"
  say "JSON: {\"check\":\"test-selection-fake-green\",\"groups\":\"$groups\",\"profile\":\"$src_profile\",\"total\":$total,\"unselected\":$unselected_n,\"baseline\":$base_count,\"fresh_violations\":$fresh_n,\"stale_baseline\":$stale_n,\"pass\":$([ "$fresh_n" -eq 0 ] && echo true || echo false)}"

  if [ "$fresh_n" -gt 0 ]; then
    return 1
  fi
  return 0
}

# ---- 自测：护栏必须真会拦（正向过≠有用）----
self_test() {
  local sb; sb="$(mktemp -d)"; trap 'rm -rf "$sb"' RETURN
  mkdir -p "$sb/scripts" "$sb/ruoyi-modules/m/src/test/java/x" "$sb/scripts/ci"
  cp "$SCRIPT_DIR/$(basename "$0")" "$sb/scripts/"
  cat > "$sb/pom.xml" <<'EOF'
<project><profiles><profile><id>dev</id><properties>
<profiles.active>dev</profiles.active></properties>
<activation><activeByDefault>true</activeByDefault></activation></profile></profiles>
<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration>
<groups>${profiles.active}</groups><excludedGroups>exclude</excludedGroups>
</configuration></plugin></plugins></build></project>
EOF
  git -C "$sb" init -q && git -C "$sb" config user.email t@t && git -C "$sb" config user.name t
  printf '@Tag("dev")\nclass ATest {}\n' > "$sb/ruoyi-modules/m/src/test/java/x/ATest.java"
  : > "$sb/scripts/ci/test-selection-fake-green-baseline.txt"
  git -C "$sb" add -A

  local rc
  "$sb/scripts/$(basename "$0")" >/dev/null 2>&1 && rc=0 || rc=$?
  [ "$rc" = "0" ] && echo "  ✓ C1 全选中 → 绿" || { echo "  ✗ C1 期望0 实=$rc"; return 1; }

  printf 'class BTest {}\n' > "$sb/ruoyi-modules/m/src/test/java/x/BTest.java"
  git -C "$sb" add -A
  "$sb/scripts/$(basename "$0")" >/dev/null 2>&1 && rc=0 || rc=$?
  [ "$rc" = "1" ] && echo "  ✓ C2 新增无 @Tag 的类 → 真会拦（exit 1）" || { echo "  ✗ C2 期望1 实=$rc"; return 1; }

  printf '@Tag("unit")\nclass BTest {}\n' > "$sb/ruoyi-modules/m/src/test/java/x/BTest.java"
  git -C "$sb" add -A
  "$sb/scripts/$(basename "$0")" >/dev/null 2>&1 && rc=0 || rc=$?
  [ "$rc" = "1" ] && echo "  ✓ C3 新增 @Tag(unit) 的类（整类不跑）→ 真会拦" || { echo "  ✗ C3 期望1 实=$rc"; return 1; }

  echo "ruoyi-modules/m/src/test/java/x/BTest.java" > "$sb/scripts/ci/test-selection-fake-green-baseline.txt"
  git -C "$sb" add -A
  "$sb/scripts/$(basename "$0")" >/dev/null 2>&1 && rc=0 || rc=$?
  [ "$rc" = "0" ] && echo "  ✓ C4 登记基线后 → 存量记账不阻断" || { echo "  ✗ C4 期望0 实=$rc"; return 1; }

  rm -f "$sb/scripts/ci/test-selection-fake-green-baseline.txt"
  "$sb/scripts/$(basename "$0")" >/dev/null 2>&1 && rc=0 || rc=$?
  [ "$rc" = "2" ] && echo "  ✓ C5 基线缺失 → exit 2（不读不到就放行）" || { echo "  ✗ C5 期望2 实=$rc"; return 1; }

  rm -f "$sb/pom.xml"
  "$sb/scripts/$(basename "$0")" >/dev/null 2>&1 && rc=0 || rc=$?
  [ "$rc" = "2" ] && echo "  ✓ C6 pom 缺失 → exit 2" || { echo "  ✗ C6 期望2 实=$rc"; return 1; }

  cat > "$sb/pom.xml" <<'EOF'
<project><profiles><profile><id>local</id><properties>
<profiles.active>local</profiles.active></properties>
<activation><activeByDefault>true</activeByDefault></activation></profile></profiles>
<build><plugins><plugin><artifactId>maven-surefire-plugin</artifactId><configuration>
<groups>${profiles.active}</groups></configuration></plugin></plugins></build></project>
EOF
  printf '@Tag("dev")\nclass ATest {}\n' > "$sb/ruoyi-modules/m/src/test/java/x/ATest.java"
  : > "$sb/scripts/ci/test-selection-fake-green-baseline.txt"
  git -C "$sb" add -A
  "$sb/scripts/$(basename "$0")" >/dev/null 2>&1 && rc=0 || rc=$?
  [ "$rc" = "1" ] && echo "  ✓ C7 有人把默认 profile 改成 local（全仓 0 个测试跑）→ 真会拦" || { echo "  ✗ C7 期望1 实=$rc"; return 1; }

  echo "✅ 自测 7/7 通过：本门禁确实会拦，不是摆设"
  return 0
}

cd "$REPO_ROOT" || exit 2
if [ "$SELF_TEST" = "1" ]; then
  self_test; exit $?
fi
run_check "$REPO_ROOT" "$BASELINE"
exit $?
