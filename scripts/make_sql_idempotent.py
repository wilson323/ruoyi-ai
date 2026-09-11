#!/usr/bin/env python3
"""
make_sql_idempotent.py — R30+ 治理：把 docs/script/sql/update/*.sql 的非幂等语句批量转为幂等模板。

四类转换（对应 docs/ipd-系统说明/治理/记忆清理-20260911.md §Top 5 #2）：
  A. ALTER TABLE <t> ADD [COLUMN] <c> ... → information_schema.COLUMNS 守卫 + PREPARE stmt
  B. CREATE [UNIQUE] INDEX <i> ON <t> ... → information_schema.STATISTICS 守卫 + PREPARE stmt
  C. CREATE TABLE <t> ... → CREATE TABLE IF NOT EXISTS <t> ...
  D. INSERT INTO <t> ... → INSERT IGNORE INTO <t> ...（种子数据，依赖 PK 跳重）

用法（在 ruoyi-ai 仓根）：
  python3 scripts/make_sql_idempotent.py docs/script/sql/update/<file>.sql   # 单文件
  python3 scripts/make_sql_idempotent.py --all-fail                           # 跑 check_ddl_idempotent.sh FAIL 清单
  python3 scripts/make_sql_idempotent.py --dry-run <file>                     # 仅预览不写

依赖：scripts/check_ddl_idempotent.sh（--all-fail 模式）。
不做的事：UPDATE / DELETE / DROP（本身幂等或需独立设计）；存储过程内部语句。
"""
import re
import subprocess
import sys
import os

# --- 语句边界：按分号分句（容忍字符串/注释内分号，简化实现按行扫描 + 栈式字符串状态） ---


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


def parse_alter_add_column(stmt: str):
    """匹配 ALTER TABLE <t> ADD [COLUMN] <c>，返回 (table, column) 或 None。"""
    # 去掉前导注释行
    body = re.sub(r"^[^-]*(?=ALTER)", "", stmt, flags=re.S) if "--" in stmt.split("\n")[0] else stmt
    m = re.search(
        r"ALTER\s+TABLE\s+(?:`?)(\w+)(?:`?)\s+ADD\s+(?:COLUMN\s+)?(?:`?)(\w+)(?:`?)",
        stmt,
        re.I | re.S,
    )
    if not m:
        return None
    return m.group(1), m.group(2)


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


# --- 语句与注释分离：把语句开头的纯注释行剥离，避免被吞进 PREPARE 字符串 ---


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


# --- 幂等模板 ---


def tpl_alter_guard(stmt: str, table: str, column: str) -> str:
    """ALTER → information_schema.COLUMNS 守卫 + PREPARE。stmt 内单引号转义为 ''。"""
    escaped = stmt.replace("'", "''")
    # PREPARE 里语句不能含裸换行影响（MySQL 字符串字面量允许换行），保留原样
    return f"""-- [idem-guard: ALTER {table}.{column}]
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}' AND COLUMN_NAME='{column}');
SET @ddl := IF(@col_exists=0,
  '{escaped}',
  'SELECT ''{table}.{column} exists, skip'' AS msg');
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
    stats = {"alter": 0, "index": 0, "table": 0, "insert": 0, "skip": 0}
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

        alter_m = parse_alter_add_column(sql_body)
        idx_m = parse_create_index(sql_body)
        tbl_m = parse_create_table(sql_body)
        ins_m = parse_insert_into(sql_body)

        if alter_m:
            table, column = alter_m
            guard = tpl_alter_guard(sql_body, table, column)
            out_stmts.append((leading + "\n\n" if leading else "") + guard)
            stats["alter"] += 1
        elif idx_m:
            unique, index, table = idx_m
            guard = tpl_create_index_guard(sql_body, unique, index, table)
            out_stmts.append((leading + "\n\n" if leading else "") + guard)
            stats["index"] += 1
        elif tbl_m:
            # CREATE TABLE 保留原注释 + 改 IF NOT EXISTS
            out_stmts.append((leading + "\n\n" if leading else "") + tpl_create_table_if_not_exists(sql_body, tbl_m))
            stats["table"] += 1
        elif ins_m:
            out_stmts.append((leading + "\n\n" if leading else "") + tpl_insert_ignore(sql_body, ins_m))
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

    total = {"alter": 0, "index": 0, "table": 0, "insert": 0, "skip": 0}
    for f in files:
        if not os.path.exists(f):
            print(f"  SKIP (not found): {f}")
            continue
        stats = transform_file(f, dry_run=dry_run)
        print(
            f"  {'[DRY] ' if dry_run else ''}{f}: ALTER={stats['alter']} INDEX={stats['index']} "
            f"TABLE={stats['table']} INSERT={stats['insert']} SKIP={stats['skip']}"
        )
        for k in total:
            total[k] += stats[k]

    print()
    print(f"TOTAL: ALTER={total['alter']} INDEX={total['index']} TABLE={total['table']} "
          f"INSERT={total['insert']} SKIP={total['skip']}  (files={len(files)})")


if __name__ == "__main__":
    main()