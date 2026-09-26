#!/usr/bin/env bash
# scripts/check-shell-var-multibyte.sh — 机械检查：$VAR 紧跟非 ASCII 字节（bash 变量名吞字节）
# 来源：R224（2026-09-25）根因复盘。全仓 16 个脚本 38 行同类缺陷一次性修复后，本门禁防再犯。
#
# 病根（bash 3.2.57 macOS / bash 5.x 同样存在，实测于本机）：
#   变量名解析会吞掉紧随其后的多字节字符首字节，导致
#     ① 变量名变成"原名字 + 1 字节"→ 未定义 → 展开为空
#     ② 该字符只剩残缺后缀字节 → 输出乱码
#   实测证据：printf "（:$B）"  → efbc88 3a bc89   （16039 整个消失，"）"缺 ef 首字节）
#             printf "（:${B}）" → efbc88 3136303339 efbc89  （正常）
#   受害样本（修复前）：check-e2e-fe-be.sh 报告里端口消失；check-dispatch-sequence.sh
#   输出 last_wt= 后跟残缺字节；check-permission-single-source.sh 等 3 个在跑的门禁同理。
#
# 修法：一律写成 ${VAR}。该写法在单引号内不会被展开，因此对任意上下文都是无害的。
#
# 用法：
#   bash scripts/check-shell-var-multibyte.sh              # 扫全部 tracked shell（*.sh + hook 脚本）
#   bash scripts/check-shell-var-multibyte.sh a.sh b.sh    # 只扫指定文件（pre-commit 传 staged）
#   bash scripts/check-shell-var-multibyte.sh --self-test  # 夹具自测：护栏必须真会拦
#
# 退出码：0 通过 / 1 发现违例 / 2 环境错（无 python3 或不在 git 仓）
# 整行注释不计违例（注释不会被 bash 展开）；行尾注释仍计入。

set -eo pipefail

SELF_TEST=0
if [ "${1:-}" = "--self-test" ]; then
  SELF_TEST=1
  shift
fi
TARGETS=("$@")

command -v python3 >/dev/null 2>&1 || { echo "❌ 需要 python3" >&2; exit 2; }

scan_one() {
  # 输出 "文件:行号:内容"，无违例则无输出
  python3 - "$@" <<'PY'
import re, sys
pat = re.compile(r'\$(?:[A-Za-z_][A-Za-z0-9_]*|[0-9]+)(?=[^\x00-\x7f])')
for f in sys.argv[1:]:
    try:
        lines = open(f, encoding='utf-8').read().splitlines()
    except (OSError, UnicodeDecodeError) as e:
        print(f"ENVERR {f}: {e}", file=sys.stderr)
        continue
    for i, line in enumerate(lines, 1):
        if line.lstrip().startswith('#'):
            continue
        if pat.search(line):
            print(f"{f}:{i}:{line.strip()[:120]}")
PY
}

list_tracked() {
  # 除 *.sh 外，hook 脚本无后缀但确实会被执行，同样纳入扫描范围
  # -z 必不可少：中文路径在默认 core.quotePath 下会被引号化而扫不到（R224 实测踩过）
  git ls-files -z '*.sh' '.githooks/*' '.claude/helpers/pre-commit' '.claude/helpers/post-commit' \
    | tr '\0' '\n'
}

run_scan() {
  local files=()
  if [ "${#TARGETS[@]}" -gt 0 ]; then
    files=("${TARGETS[@]}")
  else
    if ! git rev-parse --git-dir >/dev/null 2>&1; then
      echo "❌ 不在 git 仓内，且未指定文件参数" >&2
      exit 2
    fi
    # bash 3.2 无 mapfile；用进程替身避免管道子 shell 导致数组不回传
    files=()
    while IFS= read -r p; do
      files+=("$p")
    done < <(list_tracked)
  fi
  scan_one "${files[@]}"
}

if [ "$SELF_TEST" = "1" ]; then
  echo "[self-test] check-shell-var-multibyte.sh 夹具自测"
  sb="$(mktemp -d)"; trap 'rm -rf "$sb"' EXIT
  # 关键：违例形态（$VAR 紧跟全角标点）在**本文件源码里**不能真的写出来。
  # 本脚本自身是 tracked *.sh，一旦字面写出违例形态就会被自家门禁拦下
  # （R224 首次提交实测被拦：夹具第 86/112 行），所以夹具内容一律运行时拼接。
  rparen='）'
  hint='（必填）'
  # 夹具 1：已知违例 → 必须被拦下
  printf '%s\n' '#!/usr/bin/env bash' 'B=16039' "echo \"后端存活（:\$B${rparen}\"" > "$sb/bad.sh"
  # 夹具 2：正确写法 + 整行注释里的违例 → 必须放行
  cat > "$sb/good.sh" <<'FIX'
#!/usr/bin/env bash
B=16039
echo "后端存活（:${B}）"
# 注释里出现 $B）不算违例，注释不展开
FIX
  bad_hits=$(scan_one "$sb/bad.sh" || true)
  good_hits=$(scan_one "$sb/good.sh" || true)
  if [ -z "$bad_hits" ]; then
    echo "  ❌ T1 夹具违例未被发现 → 门禁失效"; exit 1
  fi
  echo "  ✅ T1 夹具违例被拦下（$(printf '%s\n' "$bad_hits" | wc -l | tr -d ' ') 行）"
  if [ -n "$good_hits" ]; then
    echo "  ❌ T2 正确写法被误报"; printf '%s\n' "$good_hits"; exit 1
  fi
  echo "  ✅ T2 正确写法 + 注释零误报"
  if scan_one "$sb/bad.sh" "$sb/good.sh" | grep -q "good.sh"; then
    echo "  ❌ T3 多文件扫描串味"; exit 1
  fi
  echo "  ✅ T3 多文件扫描只报违例文件"
  # 夹具 4：位置参数形式 $1 紧跟非 ASCII
  printf '%s\n' '#!/usr/bin/env bash' "echo \"用法 \$1${hint}\"" > "$sb/positional.sh"
  if [ -z "$(scan_one "$sb/positional.sh" || true)" ]; then
    echo "  ❌ T4 位置参数违例未被发现"; exit 1
  fi
  echo "  ✅ T4 位置参数 \$1 紧跟非 ASCII 被拦下"
  echo "[self-test] 4/4 PASS"
  exit 0
fi

if [ "${#TARGETS[@]}" -eq 0 ] && ! git rev-parse --git-dir >/dev/null 2>&1; then
  echo "❌ 不在 git 仓内且未指定文件" >&2
  exit 2
fi

hits=$(run_scan || true)
count=0
if [ -n "$hits" ]; then
  count=$(printf '%s\n' "$hits" | wc -l | tr -d ' ')
fi
echo "[shell-var-multibyte] 扫描 $( [ "${#TARGETS[@]}" -gt 0 ] && printf '%d 个指定文件' "${#TARGETS[@]}" || printf 'tracked shell 脚本')，违例 ${count} 行"
if [ "$count" -gt 0 ]; then
  printf '%s\n' "$hits" | sed 's/^/  ❌ /'
  echo "🔴 \$VAR 紧跟非 ASCII 会被 bash 吞掉首字节（变量展开为空 + 乱码）。修法：改写为 \${VAR}"
  exit 1
fi
echo "✅ 无 \$VAR 紧跟非 ASCII 违例"
exit 0
