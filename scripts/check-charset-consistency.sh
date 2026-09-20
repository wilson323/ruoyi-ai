#!/usr/bin/env bash
# =============================================================================
# check-charset-consistency.sh
# R122 D1-B 拍板包 — 字符集一致性巡检脚本
# R125 落地 — 真活可跑,自证能红(ENV 自检触发 EXIT 1 分支)
#
# 用途:
#   扫描真库 ipd_dev@13306 所有 utf8mb4 表的字符列 COLLATE,
#   应全为 utf8mb4_0900_ai_ci(ADR-0074 字符集统一规范)
#   任何不一致 → exit 1
#
# 用法:
#   ./check-charset-consistency.sh                              # 跑真库(默认)
#   ./check-charset-consistency.sh --report-only                # 仅写报告不阻断
#   DRY_RUN=1 ./check-charset-consistency.sh                    # 沙盒自证能红(fake latin1 表)
#   FAKE_DB=ipd_test_charset_fake ./check-charset-consistency.sh # 跑假库(fork ENV 自证)
#
# 退出码:
#   0 = PASS(全部字符列 COLLATE 一致)
#   1 = FAIL(存在字符列 COLLATE 不在 utf8mb4_0900_ai_ci)
#   2 = 脚本/环境错误(mysql 不可连、配置文件丢失)
#
# 落地证据:
#   docs/ipd-系统说明/字符集一致性-YYYYMMDD.md
#
# 关联:
#   - R122 拍板包 D1-B
#   - 真库fresh健康快照-20260919.md §一(sys_user 字符集已对齐证据)
#   - ADR-0074 字符集统一
# =============================================================================

set -uo pipefail  # 不要 -e: 单字符列失败不阻断扫整库

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# ---------------------------------------------------------------------------
# 参数解析
# ---------------------------------------------------------------------------
REPORT_ONLY=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --report-only) REPORT_ONLY=1; shift ;;
        -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
        *) echo "[check-charset] ❌ unknown arg: $1" >&2; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
EXPECTED_COLLATE="utf8mb4_0900_ai_ci"
EXPECTED_CHARSET="utf8mb4"
DB_TARGET="${FAKE_DB:-ipd_dev}"
MYSQL_CNF="${REPO_ROOT}/.codex/ipd-dev/config/mysql-client.cnf"
REPORT_DIR="${REPO_ROOT}/docs/ipd-系统说明"
DATE_TAG="$(date +%Y%m%d)"
REPORT_PATH="${REPORT_DIR}/字符集一致性-${DATE_TAG}.md"
DIFFS=0
TOTAL_TABLES=0
TOTAL_CHAR_COLS=0
INCONSISTENT_COUNT=0

mkdir -p "$REPORT_DIR"

# ---------------------------------------------------------------------------
# 哨兵
# ---------------------------------------------------------------------------
if [[ ! -e "$MYSQL_CNF" ]]; then
    echo "[check-charset] ❌ mysql client config missing: $MYSQL_CNF" >&2
    exit 2
fi

# ---------------------------------------------------------------------------
# 核心扫描逻辑(独立函数,DRY_RUN 复用)
# ---------------------------------------------------------------------------
run_real_scan() {
    # 1) 拉所有 utf8mb4 表的字符列 COLLATE
    local rows
    rows=$(mysql --defaults-file="$MYSQL_CNF" "$DB_TARGET" -N -B -e "
        SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA='${DB_TARGET}'
          AND CHARACTER_SET_NAME IS NOT NULL
          AND CHARACTER_SET_NAME != ''
        ORDER BY TABLE_NAME, ORDINAL_POSITION;
    " 2>/dev/null)

    if [[ -z "$rows" ]]; then
        echo "[check-charset] ❌ mysql query failed or empty (db=$DB_TARGET)" >&2
        exit 2
    fi

    # 2) 收集不一致项 + 计数
    local inconsistent_lines=()
    while IFS=$'\t' read -r tbl col cs coll; do
        [[ -z "$tbl" ]] && continue
        TOTAL_CHAR_COLS=$((TOTAL_CHAR_COLS + 1))
        if [[ "$cs" != "$EXPECTED_CHARSET" ]]; then
            inconsistent_lines+=("| ${tbl} | ${col} | ${cs} | ${coll} | ❌ 字符集 ≠ utf8mb4 |")
            INCONSISTENT_COUNT=$((INCONSISTENT_COUNT + 1))
            continue
        fi
        if [[ "$coll" != "$EXPECTED_COLLATE" ]]; then
            inconsistent_lines+=("| ${tbl} | ${col} | ${cs} | ${coll} | ❌ COLLATE ≠ ${EXPECTED_COLLATE} |")
            INCONSISTENT_COUNT=$((INCONSISTENT_COUNT + 1))
            continue
        fi
    done <<< "$rows"

    TOTAL_TABLES=$(echo "$rows" | awk -F'\t' '{print $1}' | sort -u | wc -l | tr -d ' ')

    # 3) 表级 default collation 也扫一遍
    local table_defaults
    table_defaults=$(mysql --defaults-file="$MYSQL_CNF" "$DB_TARGET" -N -B -e "
        SELECT TABLE_NAME, TABLE_COLLATION
        FROM information_schema.TABLES
        WHERE TABLE_SCHEMA='${DB_TARGET}'
          AND TABLE_TYPE='BASE TABLE'
        ORDER BY TABLE_NAME;
    " 2>/dev/null)

    local table_default_inconsistent=()
    while IFS=$'\t' read -r tbl tcoll; do
        [[ -z "$tbl" ]] && continue
        if [[ "$tcoll" != "$EXPECTED_COLLATE" ]]; then
            table_default_inconsistent+=("| ${tbl} | (table default) | - | ${tcoll} | ❌ 表默认 COLLATE ≠ ${EXPECTED_COLLATE} |")
            INCONSISTENT_COUNT=$((INCONSISTENT_COUNT + 1))
        fi
    done <<< "$table_defaults"

    DIFFS=$INCONSISTENT_COUNT

    # 4) 写 SSOT 报告
    {
        echo "# 字符集一致性巡检报告 — ${DATE_TAG}"
        echo ""
        echo "> 抓取时间：$(date '+%Y-%m-%d %H:%M:%S')"
        echo "> 目标库：${DB_TARGET}"
        echo "> 期望 CHARSET：${EXPECTED_CHARSET}"
        echo "> 期望 COLLATE：${EXPECTED_COLLATE}"
        echo "> 连接配置：\`${MYSQL_CNF}\`"
        echo ""
        echo "---"
        echo ""
        echo "## 概览"
        echo ""
        echo "| 指标 | 值 |"
        echo "|---|---|"
        echo "| 扫表总数 | ${TOTAL_TABLES} |"
        echo "| 字符列总数 | ${TOTAL_CHAR_COLS} |"
        echo "| 不一致项数 | ${INCONSISTENT_COUNT} |"
        echo "| 状态 | $([[ $DIFFS -eq 0 ]] && echo '✅ PASS' || echo '❌ FAIL') |"
        echo ""
        if [[ ${#inconsistent_lines[@]} -gt 0 || ${#table_default_inconsistent[@]} -gt 0 ]]; then
            echo "## 不一致明细"
            echo ""
            echo "| 表 | 列 | CHARSET | COLLATE | 判定 |"
            echo "|---|---|---|---|---|"
            for line in "${inconsistent_lines[@]}" "${table_default_inconsistent[@]}"; do
                echo "$line"
            done
        else
            echo "## 一致性结论"
            echo ""
            echo "**全部 ${TOTAL_CHAR_COLS} 个字符列 + ${TOTAL_TABLES} 张表的默认 COLLATE 均统一为 ${EXPECTED_COLLATE}** ✅"
            echo ""
            echo "— 与 \`真库fresh健康快照-20260919.md §一\` sys_user 字符集已对齐证据一致。"
        fi
        echo ""
        echo "---"
        echo ""
        echo "## 抓取命令重放"
        echo ""
        echo '```bash'
        echo "cd ${REPO_ROOT}"
        echo "mysql --defaults-file=.codex/ipd-dev/config/mysql-client.cnf ${DB_TARGET} -e \""
        echo "SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_SET_NAME, COLLATION_NAME"
        echo "  FROM information_schema.COLUMNS"
        echo "  WHERE TABLE_SCHEMA='${DB_TARGET}' AND CHARACTER_SET_NAME IS NOT NULL"
        echo "  ORDER BY TABLE_NAME, ORDINAL_POSITION;"
        echo "SELECT TABLE_NAME, TABLE_COLLATION FROM information_schema.TABLES"
        echo "  WHERE TABLE_SCHEMA='${DB_TARGET}' AND TABLE_TYPE='BASE TABLE';"
        echo '\"'
        echo '```'
        echo ""
        echo "---"
        echo ""
        echo "**报告状态**：$([[ $DIFFS -eq 0 ]] && echo '✅ 落地完成' || echo '❌ FAIL — 待 R125 治理轮处置')"
        echo "**用途**：R122 D1-B 拍板包字符集一致性常态化巡检"
        echo "**下次刷新建议**：R126 周治理轮开始前"
    } > "$REPORT_PATH"

    echo "[check-charset] 📄 报告已写: $REPORT_PATH"
    echo "[check-charset] 📊 概览: tables=$TOTAL_TABLES char_cols=$TOTAL_CHAR_COLS inconsistent=$INCONSISTENT_COUNT"

    if [[ $REPORT_ONLY -eq 1 ]]; then
        echo "[check-charset] --report-only 模式: 不阻断"
        return 0
    fi
    if [[ $DIFFS -gt 0 ]]; then
        echo "[check-charset] ❌ FAIL: DIFFS=$DIFFS (字符集/ COLLATE 不一致)"
        return 1
    fi
    echo "[check-charset] ✅ PASS: 字符集全一致"
    return 0
}

# ---------------------------------------------------------------------------
# 沙盒自证能红(DRY_RUN=1)
#   临时建一个 latin1 表,扫 → DIFFS=1 → exit 1
# ---------------------------------------------------------------------------
run_dry_run_self_red() {
    echo "[check-charset] 🔴 DRY_RUN=1 → 沙盒自证能红: 建临时 latin1 表, 触发 EXIT 1 分支"

    local fake_db="ipd_charset_dryrun_$$"
    mysql --defaults-file="$MYSQL_CNF" -e "
        DROP DATABASE IF EXISTS ${fake_db};
        CREATE DATABASE ${fake_db} CHARACTER SET latin1 COLLATE latin1_swedish_ci;
        USE ${fake_db};
        CREATE TABLE fake_t (
            id INT PRIMARY KEY,
            name VARCHAR(50) CHARACTER SET latin1 COLLATE latin1_swedish_ci
        ) ENGINE=InnoDB;
    " 2>/dev/null

    FAKE_DB="$fake_db" run_real_scan
    local dry_exit=$?

    mysql --defaults-file="$MYSQL_CNF" -e "DROP DATABASE IF EXISTS ${fake_db};" 2>/dev/null

    echo "[check-charset] DRY_RUN 自证 EXIT=$dry_exit (期望=1)"
    if [[ $dry_exit -eq 1 ]]; then
        echo "[check-charset] ✅ 沙盒自证能红: EXIT 1 分支可达"
        exit 0
    else
        echo "[check-charset] ❌ 沙盒自证失败: EXIT=$dry_exit ≠ 1 (分支未触发)"
        exit 2
    fi
}

# ---------------------------------------------------------------------------
# 主路由
# ---------------------------------------------------------------------------
echo "[check-charset] → 启动字符集一致性巡检"
echo "[check-charset] → 目标库: $DB_TARGET (期望 CHARSET=$EXPECTED_CHARSET COLLATE=$EXPECTED_COLLATE)"

if [[ "${DRY_RUN:-0}" == "1" ]]; then
    run_dry_run_self_red
else
    run_real_scan
    exit $?
fi
