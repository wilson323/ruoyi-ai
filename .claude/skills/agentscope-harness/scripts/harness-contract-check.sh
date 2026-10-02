#!/usr/bin/env bash
# harness-contract-check.sh — AgentScope Harness 代码侧契约门禁
# 契约来源：references/agentscope-java.md（四维键收口 / builder 必备项 / 凭据纪律 / 依赖钉版）
#           references/engineering-contracts.md（身份必须随调用传递）
#
# 假阳性防御（本项目吃过亏，见记忆「drift-check 门禁脚本用法与假阳性教训」）：
#   防御 1 —— 扫描范围**只限 AgentScope 接线文件**（引用 io.agentscope 或 RuntimeContext 的 .java）。
#     本仓另有自研 org.ruoyi.service.coding.harness（17 子包 / main 树 254 个 .java，2026-09-28
#     find 现查，零 io.agentscope 引用），其内部大量 `+ ":" +` 复合键属于自身 run-state
#     命名空间，**不得**被本门禁误伤。
#   防御 2 —— pom 门禁（C7/C8）必须按「声明 pom 所属工作树」解析根目录，不能硬编码主树。
#     实测踩坑：agentscope 依赖只在 .worktrees/poc-agentscope-kernel/ 里，okhttp 5.3.2 钉版
#     在该树根 pom、banDuplicateClasses 在 ruoyi-modules/ruoyi-chat/pom.xml、棘轮脚本+基线
#     在该树 scripts/ 下——按主树 REPO_ROOT 查会三项全报缺失，纯假红。
#     故 banDuplicateClasses 允许落在「树根 pom 或 声明模块 pom」任一处。
#
# 可用环境变量：
#   HARNESS_SCAN_ROOTS="dir1 dir2"   覆盖扫描根（--self-red 用）
#   HARNESS_SKIP_POM_GATES=1         跳过 C7/C8 pom 门禁（无仓上下文时）
set -uo pipefail

SKILL_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$SKILL_DIR/../../.." && pwd)"
EXIT_CODE=0
TMPD=""

ok()   { printf '  [ OK ] %s\n' "$1"; }
warn() { printf '  [WARN] %s\n' "$1"; }
info() { printf '  [INFO] %s\n' "$1"; }
fail() { printf '  [FAIL] %s\n' "$1"; EXIT_CODE=1; }
cleanup() { [ -n "$TMPD" ] && [ -d "$TMPD" ] && rm -rf "$TMPD"; }
trap cleanup EXIT

# 统计非空行数。
# 切勿写成 `grep -c . f || echo 0`：空文件时 grep 已经输出 "0" 且退 1，`|| echo 0` 再补一个
# "0" → 变量变成 "0\n0"，后续 [ -eq ] 报 integer expression expected 并走错分支。
# 实测后果（--self-red SR4 揪出）：① WIRED_N 失算 → 绕过诚实 SKIP 变成假绿；
#                ② KEY_N 失算 → 零手拼复合键的最干净仓库反而被判 C2 假红。
count_lines() {
  local n
  n=$(grep -c . "$1" 2>/dev/null)
  [ -n "$n" ] || n=0
  printf '%s' "$n"
}

SCAN_ROOTS="${HARNESS_SCAN_ROOTS:-$REPO_ROOT}"
SKIP_POM="${HARNESS_SKIP_POM_GATES:-0}"

# ---- 扫描宽度守卫 ----
# 真实事故面：本脚本被 verify.sh --self-red 从临时副本调用时，$SKILL_DIR/../../.. 推不出
# 真仓根，默认值会退化成 "/"，于是 grep -r 变成全盘扫描（分钟级挂死 + 权限报错刷屏）。
# 与其靠调用方自觉传 HARNESS_SCAN_ROOTS，不如在这里 fail-fast。
for r in $SCAN_ROOTS; do
  case "$r" in
    /|//|/Users|/home|/root|/tmp|/var|/etc|/opt|/usr|/private|/System)
      echo "harness-contract-check: REFUSE 扫描根过宽: $r" >&2
      echo "  HARNESS_SCAN_ROOTS 必须指向仓库根或其子目录，禁止文件系统级根。" >&2
      exit 2
      ;;
  esac
  if [ ! -d "$r" ]; then
    echo "harness-contract-check: REFUSE 扫描根不存在: $r" >&2
    exit 2
  fi
done

# ---- 收集 AgentScope 接线文件 ----
TMPD="$(mktemp -d "${TMPDIR:-/tmp}/as-harness.XXXXXX")"
: > "$TMPD/wired.txt"
for r in $SCAN_ROOTS; do
  [ -d "$r" ] || continue
  grep -rlE 'io\.agentscope|RuntimeContext' "$r" --include='*.java' 2>/dev/null \
    | grep -vE '/(target|node_modules|\.git)/' >> "$TMPD/wired.txt"
done
sort -u "$TMPD/wired.txt" -o "$TMPD/wired.txt"
# 并发陈旧引用守卫（2026-10-02）：本仓常有多会话共工同一工作树，清单生成后到各 C 段消费
# 之间，兄弟会话可能删除/改名文件。实测 W6 波次删 observability listener 时，C2 的 python 与
# C5 的 grep 各报 5 条「文件不存在」——污染输出、淹没真实判定，且 C2 靠 `|| continue` 吞掉
# 异常等于该文件未经检查（静默漏检）。此处先剔除已消失路径，各消费循环再加 [ -f ] 守卫双保险。
# （注：本段刻意不写字面报错英文原文，避免被输出自检类 grep 反向自命中。）
# 剔除数如实披露，不静默。
STALE_N=0
: > "$TMPD/wired.live"
while IFS= read -r f; do
  [ -z "$f" ] && continue
  if [ -f "$f" ]; then printf '%s\n' "$f" >> "$TMPD/wired.live"; else STALE_N=$((STALE_N+1)); fi
done < "$TMPD/wired.txt"
mv "$TMPD/wired.live" "$TMPD/wired.txt"
WIRED_N=$(count_lines "$TMPD/wired.txt")

echo "== 扫描范围 =="
info "scan roots: $SCAN_ROOTS"
[ "$STALE_N" -gt 0 ] && info "已剔除扫描期间消失的文件 $STALE_N 个（兄弟会话并发改动，非门禁失败）"
info "AgentScope 接线 .java 文件数: $WIRED_N"
if [ "$WIRED_N" -eq 0 ]; then
  echo
  echo "harness-contract-check: SKIP(no HarnessAgent usage) —— 诚实的空对象，不代表通过。"
  echo "  接入后（如 poc/agentscope-kernel 或后续正式分支）本门禁才有判据。"
  exit 0
fi

rel() { printf '%s' "$1" | sed "s|^$REPO_ROOT/||"; }

# 从声明 pom 向上找最近的 .git（主树是目录，worktree 是 gitdir 指针文件）= 所属工作树根
owning_root() {
  local d
  d="$(cd "$(dirname "$1")" && pwd)"
  while [ "$d" != "/" ] && [ -n "$d" ]; do
    if [ -e "$d/.git" ]; then printf '%s' "$d"; return 0; fi
    d="$(dirname "$d")"
  done
  printf '%s' "$REPO_ROOT"
}

# 统计非空行数（定义已上提到顶部工具函数区，bash 顺序执行要求先定义后调用）

# ---- C1: HarnessAgent.builder() 必备 .name( 与 .workspace( ----
echo "== [C1] HarnessAgent.builder() 必备 name + workspace =="
C1_N=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  grep -q 'HarnessAgent\.builder()' "$f" || continue
  C1_N=$((C1_N + 1))
  MISS=""
  grep -qE '\.name\(' "$f"      || MISS="$MISS .name("
  grep -qE '\.workspace\(' "$f" || MISS="$MISS .workspace("
  if [ -z "$MISS" ]; then
    ok "$(rel "$f")"
  else
    fail "$(rel "$f") builder 缺:$MISS —— 缺 workspace 会落默认解析路径，隔离与资产版本化失效"
  fi
done < <(cat "$TMPD/wired.txt")
[ "$C1_N" -eq 0 ] && info "无 HarnessAgent.builder() 装配点（可能只用了 Model/Knowledge 层）"

# ---- C2/C3: 四维复合隔离键必须单一收口 + fail-closed ----
# 判定按「所属工作树」分组（与 C7/C8 的 owning_root 同口径）：每个工作树内 ≤1 处收口。
# 实测教训（2026-09-29 门禁失明）：主树与 .worktrees/poc-agentscope-kernel 各有一份同名
# KernelScopeKey.java（同一实现、不同工作树），按全局计数 KEY_N=2 判「散落」=假红；
# 且 SCOPE_FILE 只取第一份，使 C3 真实安全门被连带 SKIP（假红连带跳过安全门）。
# 跨工作树的同名收口不是散落——同一工作树内散落才是铁律违规。
echo "== [C2] 复合隔离键手拼收口（每工作树内 ≤1 处） =="
: > "$TMPD/keypairs.txt"
while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || continue
  python3 "$SKILL_DIR/scripts/scope-key-statements.py" "$f" || continue
  printf '%s\t%s\n' "$(owning_root "$f")" "$f" >> "$TMPD/keypairs.txt"
done < <(cat "$TMPD/wired.txt")
KEY_N=$(count_lines "$TMPD/keypairs.txt")

# 每个 C2 命中的收口文件都进 SCOPE_LIST，C3 逐份查 fail-closed（不因他组假红连带 SKIP）
SCOPE_LIST="$TMPD/scopelist.txt"
: > "$SCOPE_LIST"
if [ "$KEY_N" -eq 0 ]; then
  ok "无手拼复合键（全部经收口 API）"
else
  C2_FAIL=0
  while IFS= read -r TR; do
    [ -z "$TR" ] && continue
    GRP_N=$(awk -F'\t' -v t="$TR" '$1==t{n++} END{print n+0}' "$TMPD/keypairs.txt")
    if [ "$GRP_N" -eq 1 ]; then
      F=$(awk -F'\t' -v t="$TR" '$1==t {print $2}' "$TMPD/keypairs.txt")
      printf '%s\n' "$F" >> "$SCOPE_LIST"
      ok "工作树 $(rel "$TR")：复合键拼接集中在单一收口文件: $(rel "$F")"
    else
      C2_FAIL=1
      fail "工作树 $(rel "$TR") 内复合键拼接散落到 $GRP_N 个文件 —— 违反「四维隔离键唯一收口」铁律，串桶即数据泄漏："
      awk -F'\t' -v t="$TR" '$1==t {print "         " $2}' "$TMPD/keypairs.txt"
    fi
  done < <(awk -F'\t' '{print $1}' "$TMPD/keypairs.txt" | sort -u)
  [ "$C2_FAIL" -eq 1 ] && fail "  修复：全部改走 KernelScopeKey.of(projectId, userId, agentId, sessionId)，业务代码禁止手拼"
fi

echo "== [C3] 收口文件必须 fail-closed（拒 ':' 与 '..'） =="
if [ ! -s "$SCOPE_LIST" ]; then
  info "SKIP（C2 未命中收口文件）"
else
  while IFS= read -r SCOPE_FILE; do
    [ -z "$SCOPE_FILE" ] && continue
    MISS=""
    grep -qE "indexOf\(':'\)|contains\(\":\"\)|indexOf\(\":\"\)" "$SCOPE_FILE" || MISS="$MISS 冒号段拒绝"
    grep -qF '".."' "$SCOPE_FILE" || MISS="$MISS 路径穿越(..)拒绝"
    grep -qE 'IllegalArgumentException|throw new' "$SCOPE_FILE" || MISS="$MISS 显式抛错"
    if [ -z "$MISS" ]; then
      ok "$(rel "$SCOPE_FILE") fail-closed 三要素齐全（拒 ':' / 拒 '..' / 显式抛错）"
    else
      fail "$(rel "$SCOPE_FILE") fail-closed 缺:$MISS —— 可被复合 key 注入伪造别桶地址"
    fi
  done < "$SCOPE_LIST"
fi

# ---- C4: RuntimeContext.builder() 必须带身份二维 ----
echo "== [C4] RuntimeContext.builder() 必须带 userId + sessionId =="
C4_N=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  grep -q 'RuntimeContext\.builder()' "$f" || continue
  C4_N=$((C4_N + 1))
  MISS=""
  grep -qE '\.userId\(' "$f"    || MISS="$MISS .userId("
  grep -qE '\.sessionId\(' "$f" || MISS="$MISS .sessionId("
  if [ -z "$MISS" ]; then ok "$(rel "$f")"; else fail "$(rel "$f") 缺:$MISS —— 身份缺失即隔离失效"; fi
done < <(cat "$TMPD/wired.txt")
[ "$C4_N" -eq 0 ] && info "无 RuntimeContext.builder() 直接构造点（可能经收口类 toRuntimeContext()）"

# ---- C5: 调用必须携带身份（禁止无 RuntimeContext 的 call/streamEvents） ----
echo "== [C5] call / streamEvents 调用点必须引用 RuntimeContext =="
while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || continue
  grep -qE '\.(streamEvents|call)\(' "$f" || continue
  grep -q 'HarnessAgent' "$f" || continue
  if grep -qE 'RuntimeContext|toRuntimeContext\(\)' "$f"; then
    ok "$(rel "$f") 调用携带身份"
  else
    fail "$(rel "$f") 调用 HarnessAgent 却未引用 RuntimeContext —— 状态会落到无隔离的默认桶"
  fi
done < <(cat "$TMPD/wired.txt")

# ---- C6: 凭据纪律 ----
echo "== [C6] AgentScope 接线文件禁止硬编码凭据字面量 =="
C6_HIT=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  [ -f "$f" ] || continue
  if grep -nE 'set(User|Password)\([[:space:]]*"' "$f" >/dev/null 2>&1; then
    fail "$(rel "$f") 存在硬编码 setUser/setPassword 字面量（凭证必须走 gitignored cnf / 环境变量）"
    grep -nE 'set(User|Password)\([[:space:]]*"' "$f" | head -3 | sed 's|^|         |'
    C6_HIT=1
  fi
done < <(cat "$TMPD/wired.txt")
[ "$C6_HIT" -eq 0 ] && ok "无硬编码凭据字面量"

# ---- C7: 依赖门禁（G1 okhttp 钉版；G2 langchain4j 棘轮随 2026-10-02 字样清零退役） ----
if [ "$SKIP_POM" = "1" ]; then
  echo "== [C7] 依赖门禁 =="
  info "SKIP（HARNESS_SKIP_POM_GATES=1）"
else
  echo "== [C7] 引入 io.agentscope 必须钉 okhttp 全家 + banDuplicateClasses =="
  AS_POMS=$(grep -rl 'io\.agentscope' $SCAN_ROOTS --include=pom.xml 2>/dev/null | grep -vE '/(target|node_modules)/')
  : > "$TMPD/trees.txt"
  if [ -z "$AS_POMS" ]; then
    info "SKIP（pom 未声明 io.agentscope）"
  else
    for p in $AS_POMS; do
      TR="$(owning_root "$p")"
      grep -qxF "$TR" "$TMPD/trees.txt" 2>/dev/null || printf '%s\n' "$TR" >> "$TMPD/trees.txt"
      MISS=""
      # okhttp 版本收敛必须在该树根 pom 的 dependencyManagement（子模块钉不住传递依赖）
      grep -qE 'okhttp' "$TR/pom.xml" 2>/dev/null || MISS="$MISS 树根 pom 无 okhttp 版本钉定"
      # banDuplicateClasses 允许落在树根 pom 或声明模块 pom（实测在 ruoyi-chat/pom.xml）
      grep -qE 'banDuplicateClasses' "$TR/pom.xml" "$p" 2>/dev/null \
        || MISS="$MISS banDuplicateClasses（树根 pom 与 $(rel "$p") 均无）"
      if [ -z "$MISS" ]; then
        ok "$(rel "$p") → 树根 $(rel "$TR/pom.xml")：okhttp 钉版 + 重复类门禁在场（G1 雷已封）"
      else
        fail "$(rel "$p") 缺:$MISS —— agentscope 带入 okhttp5，未钉版即运行态重复类冲突"
      fi
    done
  fi
fi

echo
if [ "$EXIT_CODE" -eq 0 ]; then
  echo "harness-contract-check: PASS（接线文件 $WIRED_N 个）"
else
  echo "harness-contract-check: FAIL"
fi
exit $EXIT_CODE
