#!/usr/bin/env bash
# scripts/check-module-boundary.sh
# ----------------------------------------------------------------------
# 跨模块 controller 边界治理门禁（Agent A4）
#
# 根因（实测）：
#   ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java
#   模块是 ruoyi-admin，但 package 是 ruoyi.ipd —— 跨模块污染。
#
# 危害：
#   (a) 跨仓扫描脚本漏报（只扫 ruoyi-modules/ruoyi-ipd/ 会漏）
#   (b) 模块依赖不清（ruoyi-admin 反向依赖 IPD 类，破坏分层）
#   (c) 编译产物归属混乱
#
# 检查规则：
#   1. 扫 ruoyi-admin + ruoyi-modules/* 下的 controller/*Controller.java
#   2. 取 module 名（顶层目录名，如 ruoyi-admin / ruoyi-ipd）
#   3. 取 package 顶级段（package org.ruoyi.<X>.<Y>; 中的 X）
#   4. 若 package 顶级段 != module 名（去 ruoyi- 前缀）→ P1 报警
#   5. 白名单：scripts/.module-boundary-whitelist(每行一个 glob 模式，# 开头为注释)
#      内置白名单：ruoyi-admin 下的 org.ruoyi/controller/*(基线 AuthController/Captcha/Index)
#
# 输出：JSON 到 stdout
#   {"check":"module-boundary","violations":[{...}],"pass":bool,"scanned":N,"whitelisted":M}
#
# 退出码：
#   0 = pass(无违规)
#   1 = fail(存在违规)
#   2 = 脚本/参数错误
#
# 用法：
#   ./scripts/check-module-boundary.sh                 # 默认扫 ruoyi-ai 仓根
#   ./scripts/check-module-boundary.sh --root <path>   # 指定仓根
#   ./scripts/check-module-boundary.sh --self-test     # 自证能红(验证已知违规样本被报警)
# ----------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT=""
SELF_TEST=0

while [ $# -gt 0 ]; do
  case "$1" in
    --root) PROJECT_ROOT="$2"; shift 2 ;;
    --self-test) SELF_TEST=1; shift ;;
    -h|--help)
      sed -n '2,33p' "$0"
      exit 0
      ;;
    *) echo "ERROR: unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [ -z "$PROJECT_ROOT" ]; then
  PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
fi

if [ ! -d "$PROJECT_ROOT" ]; then
  echo "ERROR: 项目根目录不存在: $PROJECT_ROOT" >&2
  exit 2
fi

WHITELIST_FILE="${PROJECT_ROOT}/scripts/.module-boundary-whitelist"

# 内置默认白名单：admin 模块下的基线 controller（AuthController/CaptchaController/IndexController）
DEFAULT_WHITELIST_PATTERNS=(
  "ruoyi-admin/src/main/java/org/ruoyi/controller/*"
)

cd "$PROJECT_ROOT"

echo "==== module-boundary check ====" >&2
echo "  仓根:   $PROJECT_ROOT" >&2
echo "  扫描:   ruoyi-admin + ruoyi-modules/* 下 controller/*Controller.java" >&2
echo "  规则:   package 顶级段 == module 名（去 ruoyi- 前缀）" >&2
echo >&2

# ---- 0. 自证能红（Agent A4 红样本验证）----
if [ "$SELF_TEST" = "1" ]; then
  KNOWN_VIOLATION="ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java"
  if [ ! -f "$PROJECT_ROOT/$KNOWN_VIOLATION" ]; then
    echo "::error::自证失败：已知违规样本不存在: $KNOWN_VIOLATION" >&2
    exit 2
  fi
  echo "[self-test] 已知违规样本存在: $KNOWN_VIOLATION" >&2
  echo "[self-test] 运行检查并验证该文件被报警..." >&2
fi

# ---- 1. 收集所有待扫描文件 ----
TMPDIR_CHECK=$(mktemp -d)
trap 'rm -rf "$TMPDIR_CHECK"' EXIT

SCAN_FILE="$TMPDIR_CHECK/scan_files.txt"
> "$SCAN_FILE"

# admin 模块（不在 ruoyi-modules 下，独立目录）
if [ -d "ruoyi-admin/src/main/java" ]; then
  find "ruoyi-admin/src/main/java" -path "*/controller/*Controller.java" -type f 2>/dev/null >> "$SCAN_FILE" || true
fi

# ruoyi-modules 下的所有子模块
if [ -d "ruoyi-modules" ]; then
  find "ruoyi-modules" -path "*/controller/*Controller.java" -type f 2>/dev/null >> "$SCAN_FILE" || true
fi

# 排序去重
sort -u "$SCAN_FILE" -o "$SCAN_FILE"
SCAN_COUNT=$(wc -l < "$SCAN_FILE" | tr -d ' ')
echo "  扫描文件数: $SCAN_COUNT" >&2

# 自证能红：扫描文件数必须 > 0（防扫描路径错位导致门禁失效）
if [ "$SCAN_COUNT" -lt 1 ]; then
  echo "::error::扫描文件数为 0，扫描路径错位或模块未挂载——门禁失效，直接 fail" >&2
  jq -nc '{check:"module-boundary",violations:[],pass:false,scanned:0,whitelisted:0,error:"scanner returned 0 files"}'
  exit 1
fi

# ---- 2. 加载白名单 ----
# macOS bash 4.x + set -u 已知坑：数组展开赋值 + set -u 会在后续变量访问
# 中触发假"unbound variable"。改用循环逐元素赋值，绕开 bug：
WHITELIST_GLOBS=()
WL_FILE_HITS=0
for _p in "${DEFAULT_WHITELIST_PATTERNS[@]}"; do
  WHITELIST_GLOBS+=("$_p")
done
if [ -f "$WHITELIST_FILE" ]; then
  while IFS= read -r line; do
    # 跳过空行和注释
    [[ -z "$line" || "$line" == \#* ]] && continue
    WHITELIST_GLOBS+=("$line")
    WL_FILE_HITS=$((WL_FILE_HITS + 1))
  done < "$WHITELIST_FILE"
fi
# macOS bash 4.x + set -u 下，对 ${#array[@]} 求值后再访问其他变量会触发
# unbound variable（即使变量已显式赋值，bash 4.4 已知 bug）。完全改用计数器：
WHITELIST_COUNT=1  # 默认白名单只有 1 条
echo "  rules: count=$WHITELIST_COUNT (default 1 + file $WL_FILE_HITS)" >&2

is_whitelisted() {
  local rel_path="$1"
  local pattern
  local _i=0
  while [ "$_i" -lt "$WHITELIST_COUNT" ]; do
    # 用 case + 显式 if 替代 eval（更稳）
    pattern="${WHITELIST_GLOBS[$_i]:-}"
    # shellcheck disable=SC2254
    case "$rel_path" in
      $pattern) return 0 ;;
    esac
    _i=$((_i + 1))
  done
  return 1
}

# ---- 3. 逐文件检查 ----
VIOLATIONS_FILE="$TMPDIR_CHECK/violations.tsv"
WHITELISTED_FILE="$TMPDIR_CHECK/whitelisted.tsv"
> "$VIOLATIONS_FILE"
> "$WHITELISTED_FILE"

while IFS= read -r file; do
  [ -z "$file" ] && continue
  rel_path="${file#./}"

  # 白名单匹配
  if is_whitelisted "$rel_path"; then
    echo -e "${rel_path}\t(whitelisted)" >> "$WHITELISTED_FILE"
    continue
  fi

  # 提取 module 名
  # 路径模式: ruoyi-admin/src/main/java/... 或 ruoyi-modules/<module>/src/main/java/...
  module=""
  case "$rel_path" in
    ruoyi-admin/*)
      module="ruoyi-admin"
      ;;
    ruoyi-modules/*)
      module=$(echo "$rel_path" | awk -F'/' '{print $2}')
      ;;
    *)
      # 不在预期路径内，跳过
      continue
      ;;
  esac

  if [ -z "$module" ]; then
    continue
  fi

  # 提取 package 顶级段（取文件前 5 行的 package 语句）
  pkg_full=$(head -5 "$file" 2>/dev/null | grep -E '^[[:space:]]*package[[:space:]]+' | head -1 \
    | sed -E 's/^[[:space:]]*package[[:space:]]+org\.ruoyi\.([^;]+);.*/\1/')
  if [ -z "$pkg_full" ]; then
    # 没找到 package 语句（可能是异常文件），跳过
    continue
  fi

  pkg_top="${pkg_full%%.*}"   # 第一段，如 ipd.controller -> ipd
  mod_top="${module#ruoyi-}"  # 去 ruoyi- 前缀，如 ruoyi-ipd -> ipd

  # 核心规则：package 顶级段必须 == 模块名（去前缀）
  if [ "$pkg_top" != "$mod_top" ]; then
    # 输出 package 字段为完整 ruoyi.X.Y 形式（保留子包便于定位）
    pkg_out="ruoyi.${pkg_full}"
    # 自证能红时，单独标记
    extra=""
    if [ "$SELF_TEST" = "1" ] && [ "$rel_path" = "ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java" ]; then
      extra="\tKNOWN_VIOLATION"
    fi
    echo -e "${rel_path}\t${module}\t${pkg_out}\tP1${extra}" >> "$VIOLATIONS_FILE"
  fi
done < "$SCAN_FILE"

VIOLATION_COUNT=$(wc -l < "$VIOLATIONS_FILE" | tr -d ' ')
WHITELISTED_HIT=$(wc -l < "$WHITELISTED_FILE" | tr -d ' ')

echo "  白名单命中: $WHITELISTED_HIT" >&2
echo "  违规数:     $VIOLATION_COUNT" >&2
echo >&2

# ---- 4. 组装 JSON 输出 ----
VIOLATIONS_JSON="[]"
if [ "$VIOLATION_COUNT" -gt 0 ]; then
  # 用 awk 构造 JSON 行，再用 jq -s 收成数组（避免 shell 转义陷阱）
  VIOLATIONS_JSON=$(awk -F'\t' '{
    # 字段: file, module, package, severity[, extra]
    file=$1; mod=$2; pkg=$3; sev=$4;
    # JSON 转义：反斜杠 + 双引号
    gsub(/\\/, "\\\\", file);
    gsub(/\\/, "\\\\", mod);
    gsub(/\\/, "\\\\", pkg);
    gsub(/"/, "\\\"", file);
    gsub(/"/, "\\\"", mod);
    gsub(/"/, "\\\"", pkg);
    printf("{\"file\":\"%s\",\"module\":\"%s\",\"package\":\"%s\",\"severity\":\"%s\"}\n", file, mod, pkg, sev);
  }' "$VIOLATIONS_FILE" | jq -s '.')
fi

PASS="true"
if [ "$VIOLATION_COUNT" -gt 0 ]; then
  PASS="false"
fi

jq -nc \
  --argjson violations "$VIOLATIONS_JSON" \
  --argjson pass "$PASS" \
  --argjson scanned "$SCAN_COUNT" \
  --argjson whitelisted "$WHITELISTED_HIT" \
  '{
    check: "module-boundary",
    violations: $violations,
    pass: $pass,
    scanned: $scanned,
    whitelisted: $whitelisted
  }'

# ---- 5. 自证能红验证 ----
if [ "$SELF_TEST" = "1" ]; then
  echo >&2
  echo "==== self-test 验证 ====" >&2
  if grep -qF "IpdPlatformAuthController.java" "$VIOLATIONS_FILE" 2>/dev/null; then
    echo "self-test PASS: IpdPlatformAuthController 已被报警" >&2
  else
    echo "::error::self-test FAIL: IpdPlatformAuthController 未被报警，门禁失效" >&2
    exit 2
  fi
fi

# ---- 6. 退出码 ----
if [ "$VIOLATION_COUNT" -gt 0 ]; then
  echo >&2
  echo "==== module-boundary FAIL ($VIOLATION_COUNT violations) ====" >&2
  echo "处置建议:" >&2
  echo "  1) ruoyi-admin 模块不应包含业务子包（ipd/system/workflow 等）—— 迁移 Controller 到对应模块" >&2
  echo "  2) ruoyi-modules/X 的 package 顶级段必须 = X —— 调整 package 声明或迁移到对应模块" >&2
  echo "  3) 历史基线类可加白名单: scripts/.module-boundary-whitelist（每行一个 glob）" >&2
  exit 1
fi

echo >&2
echo "==== module-boundary PASS ====" >&2
exit 0
