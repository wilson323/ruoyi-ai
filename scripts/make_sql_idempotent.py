#!/usr/bin/env python3
"""
make_sql_idempotent.py — R30+ 治理：把 docs/script/sql/update/*.sql 的非幂等语句批量转为幂等模板。

v2（2026-09-11，dry-run 反馈修复）：
  - 多动作 ALTER 按顶层逗号拆成原子动作，每动作独立守卫（v1 只守卫首个 ADD COLUMN，
    DROP 后重放会 1060 Duplicate column——实测 p254 双列）
  - ALTER ADD [UNIQUE] INDEX/KEY/CONSTRAINT 误判为 ADD COLUMN 修复（v1 把 UNIQUE 当列名查
    COLUMNS 永假 → 重放 1061 Duplicate key——实测 p1-10-1/p161/p333）
  - 新增 DROP INDEX / DROP COLUMN 守卫（v1 裸 DROP INDEX 二跑 1091——实测 p333）

转换模板：
  A. ALTER TABLE t ADD COLUMN c ...      → information_schema.COLUMNS 守卫 + PREPARE stmt
  B. ALTER TABLE t ADD [UNIQUE] INDEX/KEY i → information_schema.STATISTICS 守卫 + PREPARE stmt
  C. ALTER TABLE t DROP INDEX/KEY i      → STATISTICS=1 才 DROP 的守卫 + PREPARE stmt
  D. ALTER TABLE t DROP [COLUMN] c       → COLUMNS=1 才 DROP 的守卫 + PREPARE stmt
  E. CREATE [UNIQUE] INDEX i ON t ...    → STATISTICS 守卫 + PREPARE stmt
  F. CREATE TABLE t ...                  → CREATE TABLE IF NOT EXISTS t ...
  G. INSERT INTO t ...                   → INSERT IGNORE INTO t ...

用法（在 ruoyi-ai 仓根）：
  python3 scripts/make_sql_idempotent.py docs/script/sql/update/<file>.sql   # 单文件
  python3 scripts/make_sql_idempotent.py --all-fail                           # 跑 check_ddl_idempotent.sh FAIL 清单
  python3 scripts/make_sql_idempotent.py --dry-run <file>                     # 仅预览不写

依赖：scripts/check_ddl_idempotent.sh（--all-fail 模式）。
不做的事：UPDATE / DELETE / MODIFY / CHANGE（本身幂等）；存储过程内部语句。
"""
import re
import subprocess
import sys
import os

# --- 语句边界：按分号分句（容忍字符串/注释内分号，栈式字符串状态） ---


def split_statements(content: str):
    """按分号分句，容忍单/双引号字符串与 -- 注释、/* */ 块注释内的分号。"""
    stmts = []
    cur = []
    in_squote = False
    in_dquote = False
    in_line_comment = False
    in_block_comment = False
    i = 0
    n = len(content)
    while i < n:
        ch = content[i]
        nxt = content[i + 1] if i + 1 < n else ""

        if in_line_comment:
            cur.append(ch)
            if ch == "\n":
                in_line_comment = False
            i += 1
            continue
        if in_block_comment:
            cur.append(ch)
            if ch == "*" and nxt == "/":
                cur.append(nxt)
                in_block_comment = False
                i += 2
                continue
            i += 1
            continue
        if in_squote:
            cur.append(ch)
            if ch == "\\" and nxt:
                cur.append(nxt)
                i += 2
                continue
            if ch == "'":
                if nxt == "'":  # escaped ''
                    cur.append(nxt)
                    i += 2
                    continue
                in_squote = False
            i += 1
            continue
        if in_dquote:
            cur.append(ch)
            if ch == "\\" and nxt:
                cur.append(nxt)
                i += 2
                continue
            if ch == '"':
                in_dquote = False
            i += 1
            continue

        # not in string/comment
        if ch == "-" and nxt == "-":
            in_line_comment = True
            cur.append(ch)
            i += 1
            continue
        if ch == "#":
            in_line_comment = True
            cur.append(ch)
            i += 1
            continue
        if ch == "/" and nxt == "*":
            in_block_comment = True
            cur.append(ch)
            i += 1
            continue
        if ch == "'":
            in_squote = True
            cur.append(ch)
            i += 1
            continue
        if ch == '"':
            in_dquote = True
            cur.append(ch)
            i += 1
            continue
        if ch == ";":
            stmts.append("".join(cur).strip())
            cur = []
            i += 1
            continue
        cur.append(ch)
        i += 1

    tail = "".join(cur).strip()
    if tail:
        stmts.append(tail)
    return stmts


# --- 语句模式识别 ---


def parse_create_index(stmt: str):
    m = re.search(
        r"CREATE\s+(UNIQUE\s+)?INDEX\s+(?:`?)(\w+)(?:`?)\s+ON\s+(?:`?)(\w+)(?:`?)",
        stmt,
        re.I | re.S,
    )
    if not m:
        return None
    return (m.group(1) or "").strip(), m.group(2), m.group(3)


def parse_create_table(stmt: str):
    m = re.search(r"CREATE\s+TABLE\s+(?!IF\s+NOT)(?:`?)(\w+)(?:`?)", stmt, re.I)
    if not m:
        return None
    return m.group(1)


def parse_insert_into(stmt: str):
    m = re.search(r"^INSERT\s+(?!IGNORE\b)INTO\s+(?:`?)(\w+)(?:`?)", stmt, re.I)
    if not m:
        return None
    return m.group(1)


# --- 多动作 ALTER 拆分（v2 核心：每动作独立守卫，杜绝"只守卫第一列"缺口） ---

INDEX_KEYWORDS = {"UNIQUE", "INDEX", "KEY", "CONSTRAINT", "PRIMARY", "FOREIGN", "FULLTEXT", "SPATIAL", "CHECK"}


def split_top_level(s: str, sep: str = ","):
    """顶层逗号拆分，容忍单引号字符串（含 '' 转义）、-- 行注释、括号内逗号（索引列清单）。"""
    parts, buf = [], []
    i, n = 0, len(s)
    in_s = False
    depth = 0
    while i < n:
        ch = s[i]
        if in_s:
            buf.append(ch)
            if ch == "'":
                if i + 1 < n and s[i + 1] == "'":
                    buf.append("'")
                    i += 2
                    continue
                in_s = False
            i += 1
            continue
        if ch == "'":
            in_s = True
            buf.append(ch)
            i += 1
            continue
        if ch == "-" and i + 1 < n and s[i + 1] == "-":
            j = s.find("\n", i)
            j = n if j == -1 else j
            buf.append(s[i:j])
            i = j
            continue
        if ch == "(":
            depth += 1
        elif ch == ")" and depth > 0:
            depth -= 1
        if ch == sep and depth == 0:
            parts.append("".join(buf))
            buf = []
            i += 1
            continue
        buf.append(ch)
        i += 1
    parts.append("".join(buf))
    return parts


def decompose_alter(stmt: str):
    """把 ALTER TABLE（可能多动作）拆成原子动作列表。

    返回 [(kind, table, name, atomic_sql), ...]；
    kind ∈ add_column / add_index / drop_index / drop_column / passthrough。
    atomic_sql 是重组后的独立 ALTER TABLE 单动作语句（不带尾分号）。
    """
    m = re.match(r"\s*ALTER\s+TABLE\s+`?(\w+)`?\s+(.+)$", stmt, re.I | re.S)
    if not m:
        return [("passthrough", None, None, stmt)]
    table, body = m.group(1), m.group(2)
    atoms = []
    for seg in split_top_level(body):
        s = seg.strip()
        if not s:
            continue
        maddidx = re.match(
            r"(?i)ADD\s+((?:UNIQUE|FULLTEXT|SPATIAL)\s+)?(?:INDEX|KEY)\s+`?(\w+)`?", s)
        madduq = re.match(r"(?i)ADD\s+(UNIQUE|FULLTEXT|SPATIAL)\s+`?(\w+)`?\s*\(", s)
        maddcol = re.match(r"(?i)ADD\s+COLUMN\s+`?(\w+)`?", s)
        maddany = re.match(r"(?i)ADD\s+`?(\w+)`?", s)
        mdropidx = re.match(r"(?i)DROP\s+(?:INDEX|KEY)\s+`?(\w+)`?\s*$", s)
        mdropcol = re.match(r"(?i)DROP\s+COLUMN\s+`?(\w+)`?\s*$", s)
        atomic = f"ALTER TABLE {table} {s}"
        if maddidx:
            atoms.append(("add_index", table, maddidx.group(2), atomic))
        elif madduq:
            atoms.append(("add_index", table, madduq.group(2), atomic))
        elif maddcol:
            atoms.append(("add_column", table, maddcol.group(1), atomic))
        elif maddany and maddany.group(1).upper() not in INDEX_KEYWORDS:
            atoms.append(("add_column", table, maddany.group(1), atomic))
        elif mdropidx:
            atoms.append(("drop_index", table, mdropidx.group(1), atomic))
        elif mdropcol:
            atoms.append(("drop_column", table, mdropcol.group(1), atomic))
        else:
            atoms.append(("passthrough", table, None, atomic))
    return atoms


# --- 语句与注释分离：语句开头纯注释行剥离，避免被吞进 PREPARE 字符串 ---


def split_leading_comments(stmt: str):
    """返回 (leading_comments, sql_body)。leading_comments 是开头连续的注释行（含空行）。"""
    lines = stmt.split("\n")
    comment_lines = []
    i = 0
    while i < len(lines):
        stripped = lines[i].strip()
        if not stripped or stripped.startswith("--") or stripped.startswith("#") or stripped.startswith("/*") or stripped.startswith("*"):
            comment_lines.append(lines[i])
            i += 1
        else:
            break
    return "\n".join(comment_lines), "\n".join(lines[i:]).strip()


# --- 幂等模板（语句内单引号统一转义为 ''，PREPARE 字符串字面量安全） ---


def tpl_alter_guard(stmt: str, table: str, column: str) -> str:
    """ADD COLUMN → information_schema.COLUMNS 守卫 + PREPARE。"""
    escaped = stmt.replace("'", "''")
    return f"""-- [idem-guard: ALTER {table}.{column}]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' AND COLUMN_NAME='{column}');
SET @ddl := IF(@col_exists=0,
  '{escaped}',
  'SELECT ''{table}.{column} exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt"""


def tpl_alter_add_index_guard(stmt: str, table: str, index: str) -> str:
    """ALTER ADD [UNIQUE] INDEX/KEY → information_schema.STATISTICS 守卫 + PREPARE。"""
    escaped = stmt.replace("'", "''")
    return f"""-- [idem-guard: ADD INDEX {index} ON {table}]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' AND INDEX_NAME='{index}');
SET @ddl := IF(@idx_exists=0,
  '{escaped}',
  'SELECT ''{table}.{index} exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt"""


def tpl_drop_index_guard(stmt: str, table: str, index: str) -> str:
    """DROP INDEX → STATISTICS=1 才 DROP（缺席则跳过），二跑不再 1091。"""
    escaped = stmt.replace("'", "''")
    return f"""-- [idem-guard: DROP INDEX {index} ON {table}]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' AND INDEX_NAME='{index}');
SET @ddl := IF(@idx_exists=1,
  '{escaped}',
  'SELECT ''{table}.{index} absent, skip drop'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt"""


def tpl_drop_column_guard(stmt: str, table: str, column: str) -> str:
    """DROP COLUMN → COLUMNS=1 才 DROP（缺席则跳过）。"""
    escaped = stmt.replace("'", "''")
    return f"""-- [idem-guard: DROP COLUMN {table}.{column}]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' AND COLUMN_NAME='{column}');
SET @ddl := IF(@col_exists=1,
  '{escaped}',
  'SELECT ''{table}.{column} absent, skip drop'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt"""


def tpl_create_index_guard(stmt: str, unique: str, index: str, table: str) -> str:
    escaped = stmt.replace("'", "''")
    uniq_label = f"{unique + ' ' if unique else ''}INDEX"
    return f"""-- [idem-guard: CREATE {uniq_label} {index} ON {table}]
SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' AND INDEX_NAME='{index}');
SET @ddl := IF(@idx_exists=0,
  '{escaped}',
  'SELECT ''{table}.{index} exists, skip'' AS msg');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt"""


def tpl_create_table_if_not_exists(stmt: str, table: str) -> str:
    return re.sub(r"CREATE\s+TABLE\s+", f"CREATE TABLE IF NOT EXISTS ", stmt, count=1, flags=re.I)


def tpl_insert_ignore(stmt: str, table: str) -> str:
    return re.sub(r"^INSERT\s+INTO\s+", "INSERT IGNORE INTO ", stmt, count=1, flags=re.I)


# --- 主流程 ---


def transform_file(path: str, dry_run: bool = False) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    stmts = split_statements(content)
    stats = {"alter": 0, "index": 0, "drop_index": 0, "drop_col": 0, "table": 0, "insert": 0, "skip": 0}
    out_stmts = []

    for stmt in stmts:
        if not stmt:
            continue
        # 跳过纯注释 / 空语句
        stripped_lines = [ln for ln in stmt.split("\n") if ln.strip() and not ln.strip().startswith(("--", "#", "/*", "*"))]
        if not stripped_lines:
            out_stmts.append(stmt)
            continue

        leading, sql_body = split_leading_comments(stmt)
        if not sql_body:
            out_stmts.append(stmt)
            continue

        prefix = (leading + "\n\n") if leading else ""

        # ALTER TABLE（含多动作）：拆原子，逐动作守卫
        if re.match(r"\s*ALTER\s+TABLE\b", sql_body, re.I):
            atoms = decompose_alter(sql_body)
            parts = []
            for kind, table, name, asql in atoms:
                if kind == "add_column":
                    parts.append(tpl_alter_guard(asql, table, name))
                    stats["alter"] += 1
                elif kind == "add_index":
                    parts.append(tpl_alter_add_index_guard(asql, table, name))
                    stats["index"] += 1
                elif kind == "drop_index":
                    parts.append(tpl_drop_index_guard(asql, table, name))
                    stats["drop_index"] += 1
                elif kind == "drop_column":
                    parts.append(tpl_drop_column_guard(asql, table, name))
                    stats["drop_col"] += 1
                else:
                    parts.append(asql)
                    stats["skip"] += 1
            out_stmts.append(prefix + ";\n\n".join(parts))
            continue

        idx_m = parse_create_index(sql_body)
        tbl_m = parse_create_table(sql_body)
        ins_m = parse_insert_into(sql_body)

        if idx_m:
            unique, index, table = idx_m
            out_stmts.append(prefix + tpl_create_index_guard(sql_body, unique, index, table))
            stats["index"] += 1
        elif tbl_m:
            # CREATE TABLE 保留原注释 + 改 IF NOT EXISTS
            out_stmts.append(prefix + tpl_create_table_if_not_exists(sql_body, tbl_m))
            stats["table"] += 1
        elif ins_m:
            out_stmts.append(prefix + tpl_insert_ignore(sql_body, ins_m))
            stats["insert"] += 1
        else:
            out_stmts.append(stmt)
            stats["skip"] += 1

    new_content = ";\n\n".join(out_stmts)
    if new_content and not new_content.endswith(";"):
        new_content += ";"
    new_content += "\n"

    if not dry_run and new_content != content:
        with open(path, "w", encoding="utf-8") as f:
            f.write(new_content)

    return stats


def main():
    args = sys.argv[1:]
    dry_run = False
    files = []

    if not args:
        print(__doc__)
        sys.exit(2)

    if args[0] == "--all-fail":
        # 跑 check_ddl_idempotent.sh 拿 FAIL 清单
        script = os.path.join(os.path.dirname(__file__), "check_ddl_idempotent.sh")
        out = subprocess.run(["bash", script], capture_output=True, text=True).stdout
        for line in out.splitlines():
            if line.strip().startswith("FAIL: "):
                files.append(line.strip().split("FAIL: ", 1)[1].strip())
        if not files:
            print("check_ddl_idempotent.sh reported no FAIL — nothing to transform")
            sys.exit(0)
    else:
        if args[0] == "--dry-run":
            dry_run = True
            args = args[1:]
        files = args

    total = {"alter": 0, "index": 0, "drop_index": 0, "drop_col": 0, "table": 0, "insert": 0, "skip": 0}
    for f in files:
        if not os.path.exists(f):
            print(f"  SKIP (not found): {f}")
            continue
        stats = transform_file(f, dry_run=dry_run)
        print(
            f"  {'[DRY] ' if dry_run else ''}{f}: ALTER={stats['alter']} INDEX={stats['index']} "
            f"DROP_IDX={stats['drop_index']} DROP_COL={stats['drop_col']} "
            f"TABLE={stats['table']} INSERT={stats['insert']} SKIP={stats['skip']}"
        )
        for k in total:
            total[k] += stats[k]

    print()
    print(f"TOTAL: ALTER={total['alter']} INDEX={total['index']} DROP_IDX={total['drop_index']} "
          f"DROP_COL={total['drop_col']} TABLE={total['table']} "
          f"INSERT={total['insert']} SKIP={total['skip']}  (files={len(files)})")


if __name__ == "__main__":
    main()
