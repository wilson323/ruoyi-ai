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
# 用法:
#   ./scripts/check-contract-tri-source.sh                  # 默认模式
#   ./scripts/check-contract-tri-source.sh --json-only      # 仅输出 JSON
#   ./scripts/check-contract-tri-source.sh --strict         # 任何漂移即 fail
#   ./scripts/check-contract-tri-source.sh --direction A    # 仅跑单方向
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
BACKEND_CTRL_DIR="${REPO_ROOT}/ruoyi-admin/src/main/java/org/ruoyi/ipd/controller"

FE_BE_CONTRACT_SCRIPT="${SCRIPT_DIR}/check-api-contract-fe-be.mjs"

MIN_LEN=4
TEMP_DIR="$(mktemp -d -t contract-tri-source.XXXXXX)"
trap 'rm -rf "${TEMP_DIR}"' EXIT

JSON_ONLY=false
STRICT=false
DIRECTION_FILTER=""

THRESHOLD_A=5
THRESHOLD_B=0
THRESHOLD_C=10

while [[ $# -gt 0 ]]; do
    case "$1" in
        --json-only)  JSON_ONLY=true; shift ;;
        --strict)     STRICT=true; THRESHOLD_A=0; THRESHOLD_C=0; shift ;;
        --direction)  DIRECTION_FILTER="$2"; shift 2 ;;
        --help|-h)
            sed -n '2,30p' "$0"; exit 0 ;;
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
    if [[ ! -d "${BACKEND_CTRL_DIR}" ]] || [[ -z "$(ls -A "${BACKEND_CTRL_DIR}"/*.java 2>/dev/null)" ]]; then
        echo "[FATAL] sentinel failed: ${BACKEND_CTRL_DIR} has no Controller" >&2
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

extract_endpoints() {
    local source="$1"
    local output="$2"
    : > "${output}"
    # R39 修复:grep 无匹配时返回 1,需要 || true 避免 set -u 下参数展开失败
    grep -ohE '/api/v[0-9]+/[a-zA-Z][a-zA-Z0-9/_-]*' "${source}" 2>/dev/null \
        | awk -v min="${MIN_LEN}" 'length($0) >= min' \
        | sort -u >> "${output}" || true
}

extract_table_names() {
    local source="$1"
    local output="$2"
    : > "${output}"
    grep -ohE '\b[a-z]+(_[a-z]+){2,}\b' "${source}" 2>/dev/null \
        | awk -v min="${MIN_LEN}" 'length($0) >= min' \
        | grep -vE '^(http|https|www|false|true|null|undefined)$' \
        | sort -u >> "${output}" || true
}

extract_status_fields() {
    local source="$1"
    local output="$2"
    : > "${output}"
    grep -hE 'status|state|状态' "${source}" 2>/dev/null \
        | grep -vE '^\s*(#|`|http)' \
        | grep -ohE '\b(pending|approved|rejected|in_progress|completed|archived|draft|submitted|reviewed|closed)\b' \
        | sort -u >> "${output}" || true
}

extract_backend_endpoints() {
    local output="$1"
    : > "${output}"
    if [[ -d "${BACKEND_CTRL_DIR}" ]]; then
        grep -rohE '@(Post|Get|Put|Delete|Request)Mapping\("[^"]+"\)' \
            "${BACKEND_CTRL_DIR}" 2>/dev/null \
            | grep -ohE '"[^"]+"' | tr -d '"' \
            | grep -E '^/' \
            | sort -u >> "${output}" || true
    fi
}

extract_frontend_calls() {
    local fe_root="/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/api/ipd"
    local output="$1"
    : > "${output}"
    if [[ -d "${fe_root}" ]]; then
        grep -rohE '/api/v[0-9]+/[a-zA-Z][a-zA-Z0-9/_-]*' "${fe_root}" 2>/dev/null \
            | sort -u >> "${output}" || true
    fi
}

SPEC_ENDPOINTS="${TEMP_DIR}/spec_endpoints.txt"
SPEC_TABLES="${TEMP_DIR}/spec_tables.txt"
SPEC_STATUSES="${TEMP_DIR}/spec_statuses.txt"
CONTRACT_ENDPOINTS="${TEMP_DIR}/contract_endpoints.txt"
CONTRACT_TABLES="${TEMP_DIR}/contract_tables.txt"
CONTRACT_STATUSES="${TEMP_DIR}/contract_statuses.txt"
CODE_ENDPOINTS="${TEMP_DIR}/code_endpoints.txt"
FE_CALLS="${TEMP_DIR}/fe_calls.txt"

extract_endpoints "${SPEC_FILE}" "${SPEC_ENDPOINTS}"
[[ -d "${SPEC_DIR}" ]] && for f in "${SPEC_DIR}"/*.md; do
    [[ -f "${f}" ]] && extract_endpoints "${f}" "${SPEC_ENDPOINTS}"
done
extract_table_names "${SPEC_FILE}" "${SPEC_TABLES}"
extract_status_fields "${SPEC_FILE}" "${SPEC_STATUSES}"

for f in "${CONTRACT_DIR}"/*.md; do
    [[ -f "${f}" ]] && {
        extract_endpoints "${f}" "${CONTRACT_ENDPOINTS}"
        extract_table_names "${f}" "${CONTRACT_TABLES}"
        extract_status_fields "${f}" "${CONTRACT_STATUSES}"
    }
done

extract_backend_endpoints "${CODE_ENDPOINTS}"
extract_frontend_calls "${FE_CALLS}"

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

    comm -23 "${CONTRACT_ENDPOINTS}" "${CODE_ENDPOINTS}" > "${contract_only}"
    comm -13 "${CONTRACT_ENDPOINTS}" "${CODE_ENDPOINTS}" > "${code_only}"
    comm -12 "${CONTRACT_ENDPOINTS}" "${CODE_ENDPOINTS}" > "${matches}"

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

    comm -23 "${CODE_ENDPOINTS}" "${FE_CALLS}" > "${code_unused}"
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
    local b_total=$(( b_contract + b_code ))
    local c_total=$(( c_unused + c_unimpl ))

    if [[ ${a_total} -gt ${THRESHOLD_A} ]] || [[ ${b_total} -gt ${THRESHOLD_B} ]] || [[ ${c_total} -gt ${THRESHOLD_C} ]]; then
        pass="false"
    fi
    if [[ "${STRICT}" == "true" && $(( a_total + b_total + c_total )) -gt 0 ]]; then
        pass="false"
    fi

    local a_spec_list a_contract_list b_contract_list b_code_list c_unused_list c_unimpl_list
    a_spec_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/A_spec_only.txt" 2>/dev/null || echo '[]')
    a_contract_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/A_contract_only.txt" 2>/dev/null || echo '[]')
    b_contract_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/B_contract_only.txt" 2>/dev/null || echo '[]')
    b_code_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/B_code_only.txt" 2>/dev/null || echo '[]')
    c_unused_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/C_code_unused.txt" 2>/dev/null || echo '[]')
    c_unimpl_list=$(jq -R -s 'split("\n")|map(select(length>0))' < "${TEMP_DIR}/C_spec_unimpl.txt" 2>/dev/null || echo '[]')

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
      "code_only": ${b_code_list},
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
