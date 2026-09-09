#!/usr/bin/env python3
# IPD entity↔SQL-update 静态 drift 扫描（本地版，与 .github/workflows/ipd-drift-check.yml 同逻辑）
# 2026-09-09 E4：从 CI workflow 抽出，供 PostToolUse hook 在实体写入后即时提醒。
# 提示型（exit 0）；双层失效自检失败时 exit 2（实体表数 < 10 = 解析器坏了，不是代码坏了）。
import os, re, sys

ROOT = os.environ.get('CLAUDE_PROJECT_DIR', os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..')))
ENT_DIR = os.path.join(ROOT, 'ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain')
SQL_DIR = os.path.join(ROOT, 'docs/script/sql/update')

tbl_args_pat = re.compile(r'@TableName\s*\(([^)]*)\)')
tbl_val_pat = re.compile(r'value\s*=\s*"(\w+)"')
tbl_bare_pat = re.compile(r'"(\w+)"')
tbl_field_pat = re.compile(r'@TableField\s*\(\s*(?:value\s*=\s*)?"(\w+)"')

fields_by_table = {}
if not os.path.isdir(ENT_DIR):
    print(f'::error::ipd-drift-scan 实体目录不存在: {ENT_DIR}（路径错位/模块未挂载——门禁自身坏了）', file=sys.stderr)
    sys.exit(2)
for f in sorted(os.listdir(ENT_DIR)):
    if not f.endswith('.java'):
        continue
    txt = open(os.path.join(ENT_DIR, f), encoding='utf-8').read()
    am = tbl_args_pat.search(txt)
    if not am:
        continue
    tm = tbl_val_pat.search(am.group(1)) or tbl_bare_pat.search(am.group(1))
    if not tm:
        continue
    cols = set(tbl_field_pat.findall(txt))
    for ln in txt.splitlines():
        ln = ln.strip()
        m = re.match(r'private\s+\w[\w<>,\s\[\]]+\s+([a-z][A-Za-z0-9]+)\s*[;=]', ln)
        if m and m.group(1) != 'serialVersionUID':
            cols.add(re.sub(r'(?<!^)(?=[A-Z])', '_', m.group(1)).lower())
    fields_by_table[tm.group(1)] = sorted(cols)

if len(fields_by_table) < 10:
    print(f'::error::ipd-drift-scan 解析出实体表数 {len(fields_by_table)} < 10，注解解析器失效（门禁自身坏了）', file=sys.stderr)
    sys.exit(2)

sql_text = ''
for f in os.listdir(SQL_DIR):
    if f.endswith('.sql'):
        sql_text += open(os.path.join(SQL_DIR, f), encoding='utf-8').read() + '\n'

base_cols = {'id', 'create_by', 'create_time', 'update_by', 'update_time', 'del_flag', 'remark', 'tenant_id', 'version'}
missing = []
for tbl, cols in fields_by_table.items():
    if tbl not in sql_text:
        missing.append((tbl, 'no_table_in_sql_update'))
        continue
    miss_cols = [c for c in cols if c not in sql_text and not c.endswith('_id') and c not in base_cols]
    if miss_cols:
        missing.append((tbl, f'missing_cols_{len(miss_cols)}/{len(cols)}'))

print(f'[ipd-drift-scan] 实体表数: {len(fields_by_table)}')
if missing:
    print(f'[ipd-drift-scan] ::warning::以下实体在 docs/script/sql/update/ 无完整迁移:')
    for tbl, reason in missing:
        print(f'  - {tbl}: {reason}')
else:
    print('[ipd-drift-scan] 所有实体在 SQL update 目录均有对应迁移片段')
