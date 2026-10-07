#!/bin/bash
# scripts/gen-doc-index.sh
# 生成《文档总索引》：对 docs/ 下每一份 md 标注 有效性 / 时效 / 路由。
#
# 设计立场（2026-10-07 owner 拍板「方案 A：只做索引，不删任何东西」）：
#   本脚本**只读**，不删不改任何文档。目的是回答三个问题：
#     1) 想了解 X，该看哪一份？（路由）
#     2) 这份还算数吗？（有效性）
#     3) 它是什么时候写的、还跟得上代码吗？（时效）
#
# 分类判据全部客观可复核——只用 mtime / git 最后提交时间 / 文件名日期串 /
# 是否落在机器产物目录，**不做主观内容判断**（AI 判断「这份过没过期」
# 本身就是本仓反复出现的偏差来源，见 docs/ipd-系统说明/AI偏差-根因分析与根除方案-20261007.md）。
#
# 有效性三档的判据：
#   现行   —— 近 14 天内有 git 提交，且文件名不含退役/废弃/废弃标记
#   历史   —— 超过 14 天未更新，或位于 _archive 类目录
#   存疑   —— 命中退役关键词（退役/废弃/deprecated/已删除）但仍留在原地
#
# 用法：bash scripts/gen-doc-index.sh > docs/ipd-系统说明/文档总索引.md
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 1
NOW="$(date +%s)"
CUTOFF_DAYS=14

# ---- 退役/废弃标记（用于标「存疑」；这些是本仓真实发生过的退役事件）----
RETIRED_RE='退役|废弃|已废弃|deprecated|已删除|作废|不再使用'
# ---- 机器产物目录（内容由工具生成，不承载决策）----
MACHINE_DIRS='lint-reports|_probes|_archive'

classify() { # classify <相对路径> <最后git提交时间戳或空>
  local path="$1" last="$2" d days state=""
  # state 必须先初始化：case 不匹配时不会给它赋值，而 set -u 下未定义变量
  # 引用即崩——2026-10-07 实测踩到，症状是「表格行数正常、分类列全空」，
  # 比直接报错更坏，因为它看起来成功。
  case "$path" in
    */lint-reports/*|*/_probes/*|*/_archive/*) state="机器产物" ;;
  esac
  local mtime
  mtime=$(stat -f %m "$path" 2>/dev/null || echo 0)
  # 取「文件 mtime」与「git 最后提交时间」里较晚的那个作为实际最后被改时间
  last_ts="$mtime"
  if [ -n "$last" ] && [ "$last" -gt "$last_ts" ] 2>/dev/null; then last_ts="$last"; fi
  days=$(( (NOW - last_ts) / 86400 ))

  if [ -n "$state" ]; then
    printf '%s\t%s\t%s' "$state" "${days}d" "$last_ts"
    return
  fi
  if head -40 "$path" 2>/dev/null | grep -qE "$RETIRED_RE"; then
    printf '%s\t%s\t%s' "存疑-命中退役标记" "${days}d" "$last_ts"
  elif [ "$days" -le "$CUTOFF_DAYS" ]; then
    printf '%s\t%s\t%s' "现行" "${days}d" "$last_ts"
  else
    printf '%s\t%s\t%s' "历史" "${days}d" "$last_ts"
  fi
}

# ---- 路由表：想了解什么 → 先看哪一份 ----
cat <<'HEADER'
# 文档总索引（2026-10-07 自动生成 · 只读索引，未删除任何文件）

> **本文件是路由，不是权威。** 它回答「想了解 X 该先看哪一份」，
> 不回答「系统现在是什么状态」——后者目前没有单一权威文档，这本身是已知缺陷。
>
> **生成方式**：`bash scripts/gen-doc-index.sh > docs/ipd-系统说明/文档总索引.md`
> 判据只用 mtime / git 最后提交时间 / 文件名 / 目录位置，**不做主观内容判断**。

## 一、先看这几份（路由表）

| 你想知道 | 先看这里 | 为什么是它 |
|---|---|---|
| 这个项目要改成什么 | `README-IPD-OVERRIDE.md` | 改造方向总览，优先级高于根 README |
| 工程规范 / 建测 / 提交规则 | `AGENTS.md` | 每日维护（最近 10-07） |
| 自动化栈 / 钩子 / 门禁 | `CLAUDE.md` §自动化栈 | 每日维护，含守卫清单 |
| 产品设计（业务圣经，禁改） | `docs/开发说明/spec/_公共规范.md` + `_导航地图.md` | G-04 硬约束 |
| 67 个有效动作 | `docs/ipd-系统说明/外部资源/IPD系统_六阶段标准动作清单_v3.md` | LC01/LC03 已退役标注 |
| 11 条硬约束 G-01~G-11 | `docs/ipd-系统说明/外部资源/IPD系统_开发执行规则_AI必读.md` | 唯一权威 |
| 某项验收是否做过 | `docs/ipd-系统说明/验收/`（328 份） | 分散在子目录，按需求编号命名 |
| RuoYi-AI 基线实现 | `docs/wiki/wiki/index.md`（70 篇） | 改造前先看 |
| AI 偏差根因与已落地机制 | `docs/ipd-系统说明/AI偏差-根因分析与根除方案-20261007.md` | 2026-10-07 新增 |

## 二、规模快照（生成时刻实测）

HEADER

printf '| 目录 | 份数 | 体积 |\n|---|---:|---|\n'
for d in docs/*/; do
  n=$(find "$d" -name '*.md' 2>/dev/null | wc -l | tr -d ' ')
  s=$(du -sh "$d" 2>/dev/null | cut -f1)
  [ "$n" = "0" ] && continue
  printf '| `%s` | %s | %s |\n' "$d" "$n" "$s"
done
TOTAL_N=$(find docs -name '*.md' 2>/dev/null | wc -l | tr -d ' ')
TOTAL_S=$(du -sh docs 2>/dev/null | cut -f1)
printf '| **合计** | **%s** | **%s** |\n' "$TOTAL_N" "$TOTAL_S"

cat <<'MID'

## 三、按目录索引

MID

ROWS=0; CLASSIFIED=0
printf '| 分类 | 路径 | 最后改动 | 证据 |\n|---|---|---|---|\n'

# 一次性取全仓每个文件的最后提交时间戳。
# 逐份调 `git log -1 -- <file>` 在 1117 份上要 ~60s（实测 20 份 1.1s），
# 改成一次 --name-only 扫描后回填，实测快两个数量级。
#
# ⚠ 2026-10-07 踩过的坑：时间戳取错了 awk 字段。
#   `git log --format='C %at'` 输出形如：
#       C 1791057553
#       <空行>
#       docs/agents/domain.md
#   awk 按空白切分后 $1="C"、$2="1791057553"，写 substr($1,3) 得到的是
#   **空串**——于是全部 1117 份都标成「未入 git」，而实际上 git log 明明查得到。
#   这就是「读数形状没验就采信」的第四例：产出看起来完整（表格 1117 行），
#   但**证据列整体是假的**。所以下面加阳性对照。
GITMAP="$(mktemp "${TMPDIR:-/tmp}/gitmap.XXXXXX")"
trap 'rm -f "$GITMAP"' EXIT
git -c core.quotePath=false log --format='C %at' --name-only -- docs 2>/dev/null \
  | awk '/^C [0-9]+$/{ts=$2; next} NF>0{ if(!($0 in m)) m[$0]=ts } END{ for(k in m) print k"\t"m[k] }' \
  > "$GITMAP"

# ---- 阳性对照：形态 + 覆盖率双重把关 ----
# ① 形态：样本时间戳须是纯数字
# ② 覆盖率：gitmap 里命中的文档数必须接近文档总数。
#    这条是为 2026-10-07 踩的坑设的：git 默认 core.quotePath=true 会把中文路径
#    转义成八进制（docs/ipd-\347\263\273...），而 find 给出的是原始 UTF-8，
#    两者永远对不上 → 1120 份文档里有 1085 份被误标成「未入 git」。
#    症状极隐蔽：表格完整、行数正常，只有「证据」列整体是假的。
#    故必须用真实中文路径做阳性对照。
GM_N=$(wc -l < "$GITMAP" | tr -d ' ')
GM_SAMPLE=$(head -1 "$GITMAP" 2>/dev/null | cut -f2)
if [ "$GM_N" -lt 100 ] || ! printf '%s' "$GM_SAMPLE" | grep -qE '^[0-9]{9,}$'; then
  echo "ERROR: git 时间戳表解析失败（$GM_N 行，样本=[$GM_SAMPLE]）。" >&2
  echo "  拒绝产出带假证据列的索引。" >&2
  exit 3
fi

# 覆盖率对照：拿一份已知在 git 里的中文路径文档做探针
PROBE_REL="docs/ipd-系统说明/log.md"
PROBE_TS=$(awk -F'\t' -v k="$PROBE_REL" '$1==k{print $2; exit}' "$GITMAP" 2>/dev/null)
if [ -z "$PROBE_TS" ]; then
  echo "ERROR: 中文路径探针 [$PROBE_REL] 在 gitmap 中查不到。" >&2
  echo "  最可能原因：git 的 core.quotePath 把中文路径转义了（-c core.quotePath=false 未生效）。" >&2
  echo "  拒绝产出——那会让全部中文路径文档被误标成「未入 git」。" >&2
  exit 3
fi
echo "gen-doc-index: git 时间戳表 $GM_N 行；中文路径探针命中 ts=$PROBE_TS [形态+覆盖率已验]" >&2

for f in $(find docs -name '*.md' -not -path '*/raw/*' 2>/dev/null | sort); do
  rel="${f#./}"
  last=$(awk -F'\t' -v k="$rel" '$1==k{print $2; exit}' "$GITMAP" 2>/dev/null)
  info=$(classify "$rel" "$last")
  state=$(printf '%s' "$info" | cut -f1)
  age=$(printf '%s' "$info" | cut -f2)
  if [ -n "$last" ]; then
    ev="git $last"
  else
    ev="未入 git"
  fi
  printf '| %s | `%s` | %s | %s |\n' "$state" "$rel" "$age" "$ev"
  ROWS=$((ROWS+1))
  case "$state" in
    现行|历史|存疑-命中退役标记|机器产物) CLASSIFIED=$((CLASSIFIED+1)) ;;
    *) echo "ERROR: 分类失败 state=[$state] path=$rel" >&2 ;;
  esac
done

# ---- 产出形状自检（2026-10-07 踩坑后加）----
# 症状回顾：classify 因 set -u 崩在「引用未定义变量」，表格照样输出 1137 行，
# 但**分类列全空**——一个「看起来成功的坏产出」。所以必须自己数一遍：
# 打出的行数必须等于成功分类的行数，少一条就退出非 0。
if [ "$ROWS" -eq 0 ]; then
  echo "ERROR: 一行都没产出，拒绝交付空索引。" >&2
  exit 3
fi
if [ "$ROWS" != "$CLASSIFIED" ]; then
  echo "ERROR: 产出 $ROWS 行但只分类成功 $CLASSIFIED 行——存在空分类，拒绝交付。" >&2
  exit 3
fi
echo "gen-doc-index: 共 $ROWS 份文档，全部完成分类（现行/历史/存疑/机器产物）" >&2

cat <<'FOOTER'

## 四、怎么读这份索引

- **现行** —— 近 14 天有 git 提交，通常反映当前状态，可直接引用。
- **历史** —— 超过 14 天未动。它仍是**证据**（很多审计结论的来源），
  但**不能直接当现状**——参见记忆 `audit-report-snapshot-not-live-state`：
  本仓实测过审计报告里的 P0 结论已被自己的证据推翻。引用前必须用当前字节复核。
- **存疑-命中退役标记** —— 文件前 40 行出现「退役/废弃/已删除」等词。
  这类文档**很可能是有效记录**（登记了某个东西被拆掉），也可能是漏改的活文档，
  无法仅凭关键词区分——需要人工看。这正是「噪音过滤不了」的根源：判断本身要读内容。
- **机器产物** —— 由 lint / probe 工具生成，不承载决策，可忽略。

## 五、这份索引没有解决的问题（诚实登记）

1. **没有单一权威现状文档。** `README-IPD-OVERRIDE.md`（方向）、`AGENTS.md`（工程）、
   `CLAUDE.md`（自动化）、`spec/`（产品）、`log.md`（台账，2.7MB/16127 行）
   五者都自称权威，合起来仍回答不了「系统现在是什么状态」。
2. **255 条未闭环账。** `log.md` 中含「未做/待拍板/遗留/待补」的登记有 255 条，
   常挂不消。
3. **重复登记。** 同一 commit `b1e8e713` 在 log.md 出现 65 次，日期 `20260920` 出现 260 次。
4. **已退役内容仍有回声。** 「奖金池」在 docs/ipd-系统说明/*.md 中仍有 98 份提及，
   而 owner 已于 2026-10-03 拆除（动作 69→67）。
5. **拍板仍然找不到单点。** `拍板决策包/` 21 份 + `决策包/` 5 份，但「当前生效口径」
   没有单点，每次开工仍需重新翻阅——这是「总在拍板」的机制性原因。
FOOTER