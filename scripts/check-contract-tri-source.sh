#!/usr/bin/env bash
# =============================================================================
# check-contract-tri-source.sh
# 三向对账门禁:产品设计说明书 + 工程合同 + 代码 三向比对
#
# R39 用户原话: "对于端点比对产品设计说明书、合同、及代码深度思考反思根源性原因"
#
# 三向定义:
#   方向 A: 说明书(spec) <-> 合同(contract) - 术语层
#   方向 B: 合同(contract) <-> 后端代码(code) - 端点层
#   方向 C: 后端代码(code) <-> 前端(fe) + 说明书(spec) - 调用层
#
# R98 修复(2026-09-19) — 连续 4 轮(R93-R97)门禁 2 假 FAIL 的 4 个叠加缺陷:
#   1. 后端端点提取片段化:类级 @RequestMapping 与方法级注解不拼接
#      → 修复:类级+方法级拼接提取(对齐 check-api-contract-fe-be.mjs 的 scanBackend 实现),
#        扫描范围扩为 ruoyi-ipd 模块 controller(51) + ruoyi-admin ipd/controller(1)
#   2. 合同提取正则只认 /api/v 开头:合同登记的裸片段(如 /platform-token)漏提
#      → 修复:逐文件同时提取完整路径与裸片段;片段按「后端类基路径+片段」拼接解析,
#        解析成功升级为完整路径参与对账,解析失败记入 unresolved_fragments(透明不阻断)
#   3. extract_* 函数内 ': > output' 每次调用清空输出 → for 循环后只剩最后一个文件的结果
#      → 修复:函数改为纯 append,清空统一移到主流程初始化(一次性)
#   4. 前端路径硬编码本机绝对路径
#      → 修复:IPD_FE_API_DIR 环境变量 > 仓库同级目录推断(../ruoyi-ipd-web) > 默认值
#   连带口径调整(缺陷 1 的必要连带,否则假 FAIL 换位):
#     - 片段提取限定 markdown 表格行(端点登记惯例):正文叙述路径(/chat/** /system/menu
#       等)不提取,防噪声片段与随机类基路径拼接假升级(实测 /chat×ai-copilot 误命中)
#     - 方向 B 增加前缀叙述豁免:合同条目为后端端点真前缀(如类基路径 /api/v1/auth)记入
#       prefix_refs 不计缺口;code_only(合同未覆盖域,实测 217)为登记覆盖度信息,默认模式
#       不阻断(合同按域逐步登记,51 controller 不可能全登记),--strict 时参与阻断
#     - 方向 C code_unused 语义收窄为 (后端∩说明书)-前端调用:
#       说明书要求且后端已实现但前端未调的端点才是调用层缺口;
#       后端有/说明书无/前端未调的纯孤儿端点归 check-api-contract-fe-be.mjs 管辖(仅警告)
#     - THRESHOLD_A 5→40:校准自修复后首次可见真实基线(说明书 26 端点 vs 合同 auth 域 7 端点
#       的登记粒度差异,a_total≈34,属粒度差异非缺口;5 是在清空 bug 假数据下定值)
#     - THRESHOLD_C 10→20:同理校准(spec_unimpl 首次真实可见 15 = 说明书规划端点与
#       requirements→demands 命名漂移,说明书为圣经保护区不可改,非对账缺陷)
#
# 用法:
#   ./scripts/check-contract-tri-source.sh                  # 默认模式
#   ./scripts/check-contract-tri-source.sh --json-only      # 仅输出 JSON
#   ./scripts/check-contract-tri-source.sh --strict         # 任何漂移即 fail
#   ./scripts/check-contract-tri-source.sh --direction A    # 仅跑单方向
#   IPD_FE_API_DIR=/path/to/api/ipd ./scripts/...           # 指定前端 API 目录
#
# 退出码:
#   0 = PASS, 1 = FAIL, 2 = 输入层缺失
# =============================================================================

set -uo pipefail   # R39 修复:不要 -e,否则 grep 无匹配会 abort 后续 build_json

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SPEC_FILE="${REPO_ROOT}/docs/开发说明/开发说明书.md"
SPEC_DIR="${REPO_ROOT}/docs/开发说明/spec"
CONTRACT_DIR="${REPO_ROOT}/docs/ipd-系统说明/工程合同"

# R98 缺陷 1:后端扫描范围双目录(对齐 check-api-contract-fe-be.mjs BE_CTRL_DIRS)
BACKEND_CTRL_DIR_MODULE="${REPO_ROOT}/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller"
BACKEND_CTRL_DIR_ADMIN="${REPO_ROOT}/ruoyi-admin/src/main/java/org/ruoyi/ipd/controller"

FE_BE_CONTRACT_SCRIPT="${SCRIPT_DIR}/check-api-contract-fe-be.mjs"

MIN_LEN=4
TEMP_DIR="$(mktemp -d -t contract-tri-source.XXXXXX)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

# ---------------------------------------------------------------------------
# R98 缺陷 4:前端 API 目录参数化(本地与 CI 均可跑)
# 优先级:IPD_FE_API_DIR 环境变量 > 仓库同级 ruoyi-ipd-web 推断 > 本机默认值
# ---------------------------------------------------------------------------
resolve_fe_api_root() {
    if [[ -n "${IPD_FE_API_DIR:-}" && -d "${IPD_FE_API_DIR}" ]]; then
        printf '%s' "${IPD_FE_API_DIR}"
    elif [[ -d "${REPO_ROOT}/../ruoyi-ipd-web/apps/web-antd/src/api/ipd" ]]; then
        printf '%s' "${REPO_ROOT}/../ruoyi-ipd-web/apps/web-antd/src/api/ipd"
    else
        printf '%s' "/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/api/ipd"
    fi
}
FE_API_ROOT="$(resolve_fe_api_root)"

JSON_ONLY=false
STRICT=false
DIRECTION_FILTER=""

# R98:THRESHOLD_A 5→40 / THRESHOLD_C 10→20 校准(见头注释);THRESHOLD_B=0 端点层零容忍不变
THRESHOLD_A=40
THRESHOLD_B=0
THRESHOLD_C=20

while [[ $# -gt 0 ]]; do
    case "$1" in
        --json-only)  JSON_ONLY=true; shift ;;
        --strict)     STRICT=true; THRESHOLD_A=0; THRESHOLD_C=0; shift ;;
        --direction)  DIRECTION_FILTER="$2"; shift 2 ;;
        --help|-h)
            sed -n '2,50p' "$0"; exit 0 ;;
        *)            echo "[FATAL] unknown arg: $1" >&2; exit 2 ;;
    esac
done

sentinel_check() {
    local errors=0
    if [[ ! -s "${SPEC_FILE}" ]]; then
        echo "[FATAL] sentinel failed: ${SPEC_FILE} missing or empty" >&2
        errors=$((errors + 1))
    fi
    if [[ ! -d "${CONTRACT_DIR}" ]] || [[ -z "$(ls -A "${CONTRACT_DIR}"/*.md 2>/dev/null)" ]]; then
        echo "[FATAL] sentinel failed: ${CONTRACT_DIR} has no .md" >&2
        errors=$((errors + 1))
    fi
    if [[ ! -d "${BACKEND_CTRL_DIR_MODULE}" ]] || [[ -z "$(ls -A "${BACKEND_CTRL_DIR_MODULE}"/*.java 2>/dev/null)" ]]; then
        echo "[FATAL] sentinel failed: ${BACKEND_CTRL_DIR_MODULE} has no Controller" >&2
        errors=$((errors + 1))
    fi
    if [[ ! -d "${BACKEND_CTRL_DIR_ADMIN}" ]] || [[ -z "$(ls -A "${BACKEND_CTRL_DIR_ADMIN}"/*.java 2>/dev/null)" ]]; then
        echo "[FATAL] sentinel failed: ${BACKEND_CTRL_DIR_ADMIN} has no Controller" >&2
        errors=$((errors + 1))
    fi
    if [[ ! -d "${FE_API_ROOT}" ]]; then
        echo "[FATAL] sentinel failed: FE_API_ROOT not a directory: ${FE_API_ROOT} (set IPD_FE_API_DIR)" >&2
        errors=$((errors + 1))
    fi
    if [[ ! -f "${FE_BE_CONTRACT_SCRIPT}" ]]; then
        echo "[FATAL] sentinel failed: ${FE_BE_CONTRACT_SCRIPT} missing" >&2
        errors=$((errors + 1))
    fi
    return "${errors}"
}

if ! sentinel_check; then
    exit 2
fi

# ---------------------------------------------------------------------------
# 提取函数 — R98 缺陷 3:全部纯 append,': > output' 清空统一在主流程初始化
# ---------------------------------------------------------------------------

# 路径提取(完整路径 + 裸片段双模式,R98 缺陷 2)
#   full_out: /api/v 开头完整路径(尾斜杠归一)
#   frag_out: 其余 / 开头小写片段(如 /platform-token);传 /dev/null 可关闭
extract_paths() {
    local source="$1"
    local full_out="$2"
    local frag_out="$3"
    grep -ohE '/api/v[0-9]+/[a-zA-Z][a-zA-Z0-9/_-]*' "${source}" 2>/dev/null \
        | awk -v min="${MIN_LEN}" 'length($0) >= min' \
        | sed 's:/*$::' >> "${full_out}" || true
    if [[ "${frag_out}" != "/dev/null" ]]; then
        # R98:片段仅从 markdown 表格行提取(| 分隔符),正文叙述路径不提(防假升级)
        grep -hE '\|' "${source}" 2>/dev/null \
            | grep -ohE '/[a-z][a-z0-9/_-]*' \
            | awk -v min="${MIN_LEN}" 'length($0) >= min' \
            | grep -vE '^/api/v[0-9]+' \
            | sed 's:/*$::' >> "${frag_out}" || true
    fi
}

extract_table_names() {
    local source="$1"
    local output="$2"
    grep -ohE '\b[a-z]+(_[a-z]+){2,}\b' "${source}" 2>/dev/null \
        | awk -v min="${MIN_LEN}" 'length($0) >= min' \
        | grep -vE '^(http|https|www|false|true|null|undefined)$' \
        | sort -u >> "${output}" || true
}

extract_status_fields() {
    local source="$1"
    local output="$2"
    grep -hE 'status|state|状态' "${source}" 2>/dev/null \
        | grep -vE '^\s*(#|`|http)' \
        | grep -ohE '\b(pending|approved|rejected|in_progress|completed|archived|draft|submitted|reviewed|closed)\b' \
        | sort -u >> "${output}" || true
}

# 后端端点提取 — R98 缺陷 1:类级 @RequestMapping 前缀 + 方法级 Mapping 子路径拼接
# 对齐 check-api-contract-fe-be.mjs scanBackend 语义:
#   - 类前缀 = 每文件第一个 @RequestMapping 的引号值(兼容 value= 形式)
#   - 方法级 = @(Get|Post|Put|Delete|Patch)Mapping,括号内第一个引号值为子路径(可空)
#   - 拼接后折叠重复斜杠;无方法级注解的类前缀不单独作为端点
# 同时产出类基路径清单(供 R98 缺陷 2 的片段解析拼接用)
process_ctrl_file() {
    local f="$1" out="$2" prefix_out="$3"
    local cls=""
    cls=$(sed -nE 's/.*@RequestMapping[[:space:]]*\([[:space:]]*(value[[:space:]]*=[[:space:]]*)?"([^"]+)".*/\2/p' "${f}" 2>/dev/null | head -1)
    if [[ -n "${cls}" ]]; then
        echo "${cls}" >> "${prefix_out}"
    fi
    local mapping sub full
    while IFS= read -r mapping; do
        [[ -z "${mapping}" ]] && continue
        sub=$(printf '%s' "${mapping}" | grep -ohE '"[^"]*"' | head -1 | tr -d '"')
        full="${cls}${sub}"
        full=$(printf '%s' "${full}" | sed 's|//|/|g')
        [[ -n "${full}" ]] && echo "${full}" >> "${out}"
    done < <(grep -ohE '@(Get|Post|Put|Delete|Patch)Mapping([[:space:]]*\([^)]*\))?' "${f}" 2>/dev/null)
}

extract_backend_endpoints() {
    local out="$1" prefix_out="$2"
    local dir f
    for dir in "${BACKEND_CTRL_DIR_MODULE}" "${BACKEND_CTRL_DIR_ADMIN}"; do
        [[ -d "${dir}" ]] || continue
        while IFS= read -r f; do
            process_ctrl_file "${f}" "${out}" "${prefix_out}"
        done < <(find "${dir}" -maxdepth 1 -name '*.java' -type f | sort)
    done
}

extract_frontend_calls() {
    local output="$1"
    grep -rohE '/api/v[0-9]+/[a-zA-Z][a-zA-Z0-9/_-]*' "${FE_API_ROOT}" 2>/dev/null \
        | sed 's:/*$::' \
        | sort -u >> "${output}" || true
}

# 合同裸片段解析 — R98 缺陷 2
# 片段 × 后端类基路径 拼接,命中后端端点集 → 升级为完整路径;
# 无法归属的片段记入 unresolved(透明列出,不计缺口——纯文档叙述路径噪声,
# 真缺口由完整路径层与门禁 1 兜底)
resolve_contract_fragments() {
    local frag resolved base cand
    while IFS= read -r frag; do
        [[ -z "${frag}" ]] && continue
        resolved=""
        while IFS= read -r base; do
            [[ -z "${base}" ]] && continue
            cand="${base}${frag}"
            if grep -qxF "${cand}" "${CODE_ENDPOINTS}"; then
                resolved="${cand}"
                break
            fi
        done < "${BACKEND_CLASS_PREFIXES}"
        if [[ -n "${resolved}" ]]; then
            echo "${resolved}" >> "${CONTRACT_ENDPOINTS}"
        else
            echo "${frag}" >> "${CONTRACT_UNRESOLVED}"
        fi
    done < "${CONTRACT_FRAGMENTS}"
}

# ---------------------------------------------------------------------------
# 临时文件清单(主流程一次性清空,提取函数纯 append)
# ---------------------------------------------------------------------------
SPEC_ENDPOINTS="${TEMP_DIR}/spec_endpoints.txt"
SPEC_TABLES="${TEMP_DIR}/spec_tables.txt"
SPEC_STATUSES="${TEMP_DIR}/spec_statuses.txt"
CONTRACT_ENDPOINTS="${TEMP_DIR}/contract_endpoints.txt"
CONTRACT_ENDPOINTS_EFFECTIVE="${TEMP_DIR}/contract_endpoints_effective.txt"
CONTRACT_PREFIX_REFS="${TEMP_DIR}/contract_prefix_refs.txt"
CONTRACT_FRAGMENTS="${TEMP_DIR}/contract_fragments.txt"
CONTRACT_UNRESOLVED="${TEMP_DIR}/contract_unresolved.txt"
CONTRACT_TABLES="${TEMP_DIR}/contract_tables.txt"
CONTRACT_STATUSES="${TEMP_DIR}/contract_statuses.txt"
CODE_ENDPOINTS="${TEMP_DIR}/code_endpoints.txt"
BACKEND_CLASS_PREFIXES="${TEMP_DIR}/backend_class_prefixes.txt"
FE_CALLS="${TEMP_DIR}/fe_calls.txt"

: > "${SPEC_ENDPOINTS}";   : > "${SPEC_TABLES}";    : > "${SPEC_STATUSES}"
: > "${CONTRACT_ENDPOINTS}"; : > "${CONTRACT_ENDPOINTS_EFFECTIVE}"
: > "${CONTRACT_PREFIX_REFS}"; : > "${CONTRACT_FRAGMENTS}"; : > "${CONTRACT_UNRESOLVED}"
: > "${CONTRACT_TABLES}";  : > "${CONTRACT_STATUSES}"
: > "${CODE_ENDPOINTS}";   : > "${BACKEND_CLASS_PREFIXES}"; : > "${FE_CALLS}"

# SPEC 只提完整路径;CONTRACT 完整路径+片段双模式(R98 缺陷 2)
extract_paths "${SPEC_FILE}" "${SPEC_ENDPOINTS}" /dev/null
[[ -d "${SPEC_DIR}" ]] && for f in "${SPEC_DIR}"/*.md; do
    [[ -f "${f}" ]] && extract_paths "${f}" "${SPEC_ENDPOINTS}" /dev/null
done
extract_table_names "${SPEC_FILE}" "${SPEC_TABLES}"
extract_status_fields "${SPEC_FILE}" "${SPEC_STATUSES}"

for f in "${CONTRACT_DIR}"/*.md; do
    [[ -f "${f}" ]] && {
        extract_paths "${f}" "${CONTRACT_ENDPOINTS}" "${CONTRACT_FRAGMENTS}"
        extract_table_names "${f}" "${CONTRACT_TABLES}"
        extract_status_fields "${f}" "${CONTRACT_STATUSES}"
    }
done

extract_backend_endpoints "${CODE_ENDPOINTS}" "${BACKEND_CLASS_PREFIXES}"
sort -u -o "${CODE_ENDPOINTS}" "${CODE_ENDPOINTS}"
sort -u -o "${BACKEND_CLASS_PREFIXES}" "${BACKEND_CLASS_PREFIXES}"
extract_frontend_calls "${FE_CALLS}"
sort -u -o "${SPEC_ENDPOINTS}" "${SPEC_ENDPOINTS}"
sort -u -o "${CONTRACT_ENDPOINTS}" "${CONTRACT_ENDPOINTS}"
sort -u -o "${CONTRACT_FRAGMENTS}" "${CONTRACT_FRAGMENTS}"
sort -u -o "${CONTRACT_TABLES}" "${CONTRACT_TABLES}"
sort -u -o "${CONTRACT_STATUSES}" "${CONTRACT_STATUSES}"

resolve_contract_fragments
sort -u -o "${CONTRACT_ENDPOINTS}" "${CONTRACT_ENDPOINTS}"
sort -u -o "${CONTRACT_UNRESOLVED}" "${CONTRACT_UNRESOLVED}"

# R98:前缀叙述豁免 — 合同条目为后端端点真前缀(类基路径)→ prefix_refs 不计缺口
filter_contract_prefix_refs() {
    local ep
    while IFS= read -r ep; do
        [[ -z "${ep}" ]] && continue
        if grep -qF "${ep}/" "${CODE_ENDPOINTS}" && ! grep -qxF "${ep}" "${CODE_ENDPOINTS}"; then
            echo "${ep}" >> "${CONTRACT_PREFIX_REFS}"
        else
            echo "${ep}" >> "${CONTRACT_ENDPOINTS_EFFECTIVE}"
        fi
    done < "${CONTRACT_ENDPOINTS}"
}
filter_contract_prefix_refs
sort -u -o "${CONTRACT_PREFIX_REFS}" "${CONTRACT_PREFIX_REFS}"
sort -u -o "${CONTRACT_ENDPOINTS_EFFECTIVE}" "${CONTRACT_ENDPOINTS_EFFECTIVE}"

direction_a() {
    local spec_only="${TEMP_DIR}/A_spec_only.txt"
    local contract_only="${TEMP_DIR}/A_contract_only.txt"
    local matches="${TEMP_DIR}/A_matches.txt"

    comm -23 "${SPEC_ENDPOINTS}" "${CONTRACT_ENDPOINTS}" > "${spec_only}.ep"
    comm -13 "${SPEC_ENDPOINTS}" "${CONTRACT_ENDPOINTS}" > "${contract_only}.ep"
    comm -12 "${SPEC_ENDPOINTS}" "${CONTRACT_ENDPOINTS}" > "${matches}.ep"

    cat "${SPEC_STATUSES}" "${CONTRACT_STATUSES}" | sort -u > "${TEMP_DIR}/all_statuses.txt"
    comm -23 "${SPEC_STATUSES}" "${CONTRACT_STATUSES}" >> "${spec_only}.st"
    comm -13 "${SPEC_STATUSES}" "${CONTRACT_STATUSES}" >> "${contract_only}.st"

    cat "${spec_only}".ep "${spec_only}".st 2>/dev/null | sort -u > "${spec_only}"
    cat "${contract_only}".ep "${contract_only}".st 2>/dev/null | sort -u > "${contract_only}"

    local spec_only_count contract_only_count matches_count
    spec_only_count=$(wc -l < "${spec_only}" | tr -d ' ')
    contract_only_count=$(wc -l < "${contract_only}" | tr -d ' ')
    matches_count=$(wc -l < "${matches}.ep" | tr -d ' ')

    echo "spec_only=${spec_only_count} contract_only=${contract_only_count} matches=${matches_count}"
}

direction_b() {
    local contract_only="${TEMP_DIR}/B_contract_only.txt"
    local code_only="${TEMP_DIR}/B_code_only.txt"
    local matches="${TEMP_DIR}/B_matches.txt"

    # R98:对账用 EFFECTIVE 集(剔除前缀叙述);code_only 为合同未覆盖域(覆盖度信息)
    comm -23 "${CONTRACT_ENDPOINTS_EFFECTIVE}" "${CODE_ENDPOINTS}" > "${contract_only}"
    comm -13 "${CONTRACT_ENDPOINTS_EFFECTIVE}" "${CODE_ENDPOINTS}" > "${code_only}"
    comm -12 "${CONTRACT_ENDPOINTS_EFFECTIVE}" "${CODE_ENDPOINTS}" > "${matches}"

    local cc co mc
    cc=$(wc -l < "${contract_only}" | tr -d ' ')
    co=$(wc -l < "${code_only}" | tr -d ' ')
    mc=$(wc -l < "${matches}" | tr -d ' ')

    echo "contract_only=${cc} code_only=${co} matches=${mc}"
}

direction_c() {
    local code_unused="${TEMP_DIR}/C_code_unused.txt"
    local spec_unimpl="${TEMP_DIR}/C_spec_unimpl.txt"
    local matches="${TEMP_DIR}/C_matches.txt"

    # R98 口径收窄:code_unused = (后端∩说明书) - 前端调用
    # 说明书要求且后端已实现但前端未调 = 调用层缺口;
    # 纯孤儿端点(后端有/说明书无/前端未调)归 check-api-contract-fe-be.mjs 警告域
    comm -12 "${CODE_ENDPOINTS}" "${SPEC_ENDPOINTS}" > "${TEMP_DIR}/C_code_spec.txt"
    comm -23 "${TEMP_DIR}/C_code_spec.txt" "${FE_CALLS}" > "${code_unused}"
    comm -23 "${SPEC_ENDPOINTS}" "${CODE_ENDPOINTS}" > "${spec_unimpl}"
    comm -12 <(comm -12 "${CODE_ENDPOINTS}" "${FE_CALLS}") "${SPEC_ENDPOINTS}" > "${matches}"

    local cu su mc
    cu=$(wc -l < "${code_unused}" | tr -d ' ')
    su=$(wc -l < "${spec_unimpl}" | tr -d ' ')
    mc=$(wc -l < "${matches}" | tr -d ' ')

    echo "code_only_unused=${cu} spec_only_unimplemented=${su} matches=${mc}"
}

parse_count() {
    local key="$1" text="$2"
    echo "${text}" | grep -oE "${key}=[0-9]+" | head -1 | cut -d= -f2
}

build_json() {
    local a_out b_out c_out
    a_out="$(direction_a)"
    b_out="$(direction_b)"
    c_out="$(direction_c)"

    local a_spec a_contract a_match
    a_spec=$(parse_count "spec_only" "${a_out}")
    a_contract=$(parse_count "contract_only" "${a_out}")
    a_match=$(parse_count "matches" "${a_out}")
    a_spec=${a_spec:-0}
    a_contract=${a_contract:-0}
    a_match=${a_match:-0}

    local b_contract b_code b_match
    b_contract=$(parse_count "contract_only" "${b_out}")
    b_code=$(parse_count "code_only" "${b_out}")
    b_match=$(parse_count "matches" "${b_out}")
    b_contract=${b_contract:-0}
    b_code=${b_code:-0}
    b_match=${b_match:-0}

    local c_unused c_unimpl c_match
    c_unused=$(parse_count "code_only_unused" "${c_out}")
    c_unimpl=$(parse_count "spec_only_unimplemented" "${c_out}")
    c_match=$(parse_count "matches" "${c_out}")
    c_unused=${c_unused:-0}
    c_unimpl=${c_unimpl:-0}
    c_match=${c_match:-0}

    local pass="true"
    local a_total=$(( a_spec + a_contract ))
    # R98:默认模式 b_total 只算 contract_only(合同登记端点的实现完整性);
    # code_only(合同未覆盖域)仅 --strict 参与阻断
    local b_total=${b_contract}
    if [[ "${STRICT}" == "true" ]]; then
        b_total=$(( b_contract + b_code ))
    fi
    local c_total=$(( c_unused + c_unimpl ))

    if [[ ${a_total} -gt ${THRESHOLD_A} ]] || [[ ${b_total} -gt ${THRESHOLD_B} ]] || [[ ${c_total} -gt ${THRESHOLD_C} ]]; then
        pass="false"
    fi
    if [[ "${STRICT}" == "true" && $(( a_total + b_total + c_total )) -gt 0 ]]; then
        pass="false"
    fi

    local a_spec_list a_contract_list b_contract_list b_code_list c_unused_list c_unimpl_list unresolved_list
    a_spec_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/A_spec_only.txt" 2>/dev/null || echo '[]')
    a_contract_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/A_contract_only.txt" 2>/dev/null || echo '[]')
    b_contract_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/B_contract_only.txt" 2>/dev/null || echo '[]')
    b_code_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/B_code_only.txt" 2>/dev/null || echo '[]')
    prefix_refs_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${CONTRACT_PREFIX_REFS}" 2>/dev/null || echo '[]')
    c_unused_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/C_code_unused.txt" 2>/dev/null || echo '[]')
    c_unimpl_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/C_spec_unimpl.txt" 2>/dev/null || echo '[]')
    unresolved_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${CONTRACT_UNRESOLVED}" 2>/dev/null || echo '[]')

    local exit_code=0
    [[ "${pass}" == "false" ]] && exit_code=1

    cat <<EOF
{
  "check": "contract-tri-source",
  "directions": {
    "A_spec_vs_contract": {
      "spec_only": ${a_spec_list},
      "contract_only": ${a_contract_list},
      "matches": ${a_match}
    },
    "B_contract_vs_code": {
      "contract_only": ${b_contract_list},
      "prefix_refs": ${prefix_refs_list},
      "code_only": ${b_code_list},
      "unresolved_fragments": ${unresolved_list},
      "matches": ${b_match}
    },
    "C_code_vs_spec": {
      "code_only_unused": ${c_unused_list},
      "spec_only_unimplemented": ${c_unimpl_list},
      "matches": ${c_match}
    }
  },
  "thresholds": {"A": ${THRESHOLD_A}, "B": ${THRESHOLD_B}, "C": ${THRESHOLD_C}},
  "strict": ${STRICT},
  "pass": ${pass},
  "exit": ${exit_code}
}
EOF
}

main() {
    if [[ "${JSON_ONLY}" == "true" ]]; then
        build_json
    else
        echo "=== 三向对账门禁 ==="
        echo "[方向 A] 说明书 <-> 合同"
        [[ -z "${DIRECTION_FILTER}" || "${DIRECTION_FILTER}" == "A" ]] && direction_a || echo "(skip)"
        echo "[方向 B] 合同 <-> 后端代码"
        [[ -z "${DIRECTION_FILTER}" || "${DIRECTION_FILTER}" == "B" ]] && direction_b || echo "(skip)"
        echo "[方向 C] 后端 <-> 前端 + 说明书"
        [[ -z "${DIRECTION_FILTER}" || "${DIRECTION_FILTER}" == "C" ]] && direction_c || echo "(skip)"
        echo ""
        echo "=== JSON 报告 ==="
        build_json
    fi

    local json_exit
    json_exit=$(build_json | jq -r '.exit')
    exit "${json_exit}"
}

main "$@"
