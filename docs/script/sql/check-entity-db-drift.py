#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
entity↔真库 drift-check 门禁（IPD 蜂群根治轮 2026-09-07 落地；
2026-09-28 门禁失明补齐：全仓实体目录 + 裸 @TableName 形态 + 反向漂移 + 白名单基线）。

背景：仓库无 Flyway/Liquibase，docs/script/sql/update/** 全靠人工 apply，
"SQL 已 commit ≠ 对象已生效"。实体加字段但 DDL 漏迁移时，Mock 单测全绿
（Mockito stub 绕过真 SQL），真库一跑即爆 Unknown column。本脚本做
「domain 实体字段 ↔ information_schema 真库列」全量比对，作为 apply 后/
验收前的独立回读门禁。

2026-09-28 修三重失明（历史 EXIT=0 属空洞通过，证据见
docs/script/sql/update/kb-partA-ddl-draft-20260928.sql 头部「证据更正」段）：
  ① 实体目录只扫 ruoyi-ipd → 现按模块枚举（默认 ruoyi-ipd + ruoyi-chat，
     --modules 可扩），递归子包（domain/entity 目录段）；
  ② @TableName 只认 value= 形态 → 兼容裸 @TableName("t") 与
     @TableName(value = "t", autoResultMap = true)；
  ③ 只算「实体有/库缺」单向 → 新增反向漂移「库有列/实体无列」，
     既存差异经白名单基线（entity-db-drift-baseline.json，与本脚本同目录）
     豁免；白名单只减不增（ratchet）：新增漂移即红，过期条目（不再漂移）也红，
     逼基线同步收缩。父类 BaseEntity/TenantEntity 的持久化审计列自动并入
     实体应有列，避免继承体系全面误报。

用法（二选一，调用方式与 scripts/check-ddl-applied.sh 保持兼容）：
  1) docker exec 通道（推荐，宿主映射 3306）:
     python3 check-entity-db-drift.py --docker ruoyi-ai-mysql --db ipd_dev
  2) socket/cnf 通道:
     python3 check-entity-db-drift.py --cnf <mysql-client.cnf> --db ipd_dev
可选：
  --table <t>       只比对指定表（wrapper 透传用）
  --modules <m,m>   实体模块清单（默认 ruoyi-ipd,ruoyi-chat）
  --baseline <p>    反向漂移白名单 JSON（默认本目录 entity-db-drift-baseline.json）

退出码：0=全部对齐；1=存在 drift（或白名单过期/失效自检不过）；2=脚本自身错误。
识别规则：
  - @TableName("t") / @TableName(value = "t") 取表名（两种形态都认）
  - @TableField("c") / @TableField(value = "c") / @TableField("`c`") 显式列优先
  - @TableId(value = "id") 显式主键列
  - 否则驼峰→下划线推导（MyBatis-Plus 默认 map-underscore-to-camel-case）
  - @TableField(exist = false) 的字段跳过（非持久化）
  - extends BaseEntity → 并入 create_dept/create_by/create_time/update_by/update_time
    extends TenantEntity → 再并入 tenant_id（TenantEntity 继承 BaseEntity）
"""
import argparse
import json
import os
import re
import subprocess
import sys

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.normpath(os.path.join(SCRIPT_DIR, '../../..'))
DEFAULT_MODULES = ['ruoyi-ipd', 'ruoyi-chat']
DEFAULT_BASELINE = os.path.join(SCRIPT_DIR, 'entity-db-drift-baseline.json')

# 父类持久化列（org.ruoyi.common.mybatis.core.domain.BaseEntity /
# org.ruoyi.common.tenant.core.TenantEntity），扫描时按 extends 简名并入
SUPERCLASS_COLS = {
    'BaseEntity': ['create_dept', 'create_by', 'create_time', 'update_by', 'update_time'],
    'TenantEntity': ['create_dept', 'create_by', 'create_time', 'update_by', 'update_time', 'tenant_id'],
}


def to_snake(name: str) -> str:
    s1 = re.sub(r'(.)([A-Z][a-z]+)', r'\1_\2', name)
    return re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', s1).lower()


def iter_entity_files(modules):
    """按模块枚举实体 .java：ruoyi-modules/<m>/src/main/java 下递归，
    仅收路径中含 /domain/ 或 /entity/ 目录段的文件（排除 target/ 编译产物）。"""
    for mod in modules:
        base = os.path.join(REPO_ROOT, 'ruoyi-modules', mod, 'src', 'main', 'java')
        if not os.path.isdir(base):
            print(f'  ⚠ 模块实体根不存在，跳过: ruoyi-modules/{mod}/src/main/java', file=sys.stderr)
            continue
        for root, dirs, files in os.walk(base):
            dirs[:] = [d for d in dirs if d != 'target']
            rel = os.path.relpath(root, base)
            parts = rel.split(os.sep)
            if 'domain' not in parts and 'entity' not in parts:
                continue
            for f in sorted(files):
                if f.endswith('.java'):
                    yield os.path.join(root, f)


def parse_entity(path):
    """解析单个实体文件 → (table, [column,...]) 或 None（非 @TableName 实体）。"""
    with open(path, encoding='utf-8') as fp:
        content = fp.read()
    # 兼容裸 @TableName("t") 与 @TableName(value = "t", ...) 两种形态
    m = re.search(r'@TableName\(\s*(?:value\s*=\s*)?"([^"]+)"', content)
    if not m:
        return None
    table = m.group(1)
    columns = {}
    # 父类审计列并入（按 extends 简名；TenantEntity 已含 BaseEntity 全集）
    sup = re.search(r'class\s+\w+\s+extends\s+(\w+)', content)
    if sup and sup.group(1) in SUPERCLASS_COLS:
        for c in SUPERCLASS_COLS[sup.group(1)]:
            columns[c] = f'inherited:{sup.group(1)}'
    # 逐字段块解析：捕获注解块 + 紧随的字段声明
    for fm in re.finditer(
            r'((?:@\w+(?:\([^)]*\))?\s*\n)*)\s*private\s+(?!static)'
            r'(?:final\s+)?[\w.<>\[\]]+\s+(\w+)\s*;', content):
        ann, field = fm.group(1) or '', fm.group(2)
        if field == 'serialVersionUID':
            continue
        if re.search(r'@TableField\([^)]*exist\s*=\s*false', ann):
            continue  # 非持久化字段
        explicit = re.search(r'@TableField\(\s*(?:value\s*=\s*)?"`?(\w+)`?"', ann)
        if explicit:
            columns[explicit.group(1)] = field
            continue
        tid = re.search(r'@TableId\(\s*(?:value\s*=\s*)?"`?(\w+)`?"', ann)
        if tid:
            columns[tid.group(1)] = field
            continue
        columns[to_snake(field)] = field
    return table, list(columns.keys())


def scan_entities(modules):
    """返回 {table: {'files': [...], 'cols': set(...)}}；同表多实体取列并集并告警。"""
    entities = {}
    n_files = 0
    for path in iter_entity_files(modules):
        n_files += 1
        parsed = parse_entity(path)
        if parsed is None:
            continue
        table, cols = parsed
        rel = os.path.relpath(path, REPO_ROOT)
        ent = entities.setdefault(table.lower(), {'files': [], 'cols': set()})
        if ent['files']:
            print(f'  ⚠ 同表多实体（列取并集）: {table} <- {rel} 与 {ent["files"][-1]}', file=sys.stderr)
        ent['files'].append(rel)
        ent['cols'].update(cols)
    return entities, n_files


def fetch_db_columns(args):
    """返回 {table: set(columns)}"""
    if args.docker:
        cmd = ['docker', 'exec', args.docker, 'sh', '-c',
               'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" %s -N -e '
               '"SELECT CONCAT(table_name,\'|\',column_name) '
               'FROM information_schema.columns WHERE table_schema=\'%s\'"' % (args.db, args.db)]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            sys.exit(f'docker exec 查询失败: {r.stderr[:500]}')
        out = r.stdout
    else:
        import configparser
        import pymysql
        cfg = configparser.ConfigParser()
        cfg.read(args.cnf)
        conn = pymysql.connect(
            user=cfg.get('client', 'user'), password=cfg.get('client', 'password'),
            unix_socket=cfg.get('client', 'socket'), database=args.db, charset='utf8mb4')
        cur = conn.cursor()
        cur.execute("SELECT table_name, column_name FROM information_schema.columns "
                    "WHERE table_schema=database()")
        rows = cur.fetchall()
        conn.close()
        out = '\n'.join(f'{t}|{c}' for t, c in rows)
    db_cols = {}
    for line in out.strip().split('\n'):
        if '|' in line:
            t, c = line.split('|', 1)
            db_cols.setdefault(t.lower(), set()).add(c)
    return db_cols


def load_baseline(path):
    """返回 ({table: set(cols)}, 原始dict)；文件缺失 → 空白名单（零豁免，fail-closed）。"""
    if not os.path.isfile(path):
        print(f'  ⚠ 白名单基线不存在（反向漂移将零豁免）: {os.path.relpath(path, REPO_ROOT)}',
              file=sys.stderr)
        return {}, {}
    with open(path, encoding='utf-8') as fp:
        data = json.load(fp)
    bl = {}
    for t, spec in data.get('tables', {}).items():
        bl[t.lower()] = set(spec.get('columns', {}).keys())
    missing_ok = {t.lower() for t in data.get('missing_tables', {})}
    return bl, data, missing_ok


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--docker', help='docker 容器名（容器内 mysql -uroot $MYSQL_ROOT_PASSWORD）')
    ap.add_argument('--cnf', help='mysql client cnf（socket 通道）')
    ap.add_argument('--db', default='ipd_dev')
    ap.add_argument('--table', help='只比对指定表')
    ap.add_argument('--modules', default=','.join(DEFAULT_MODULES),
                    help=f'实体模块清单，逗号分隔（默认 {" ".join(DEFAULT_MODULES)}）')
    ap.add_argument('--baseline', default=DEFAULT_BASELINE,
                    help='反向漂移白名单 JSON 路径')
    args = ap.parse_args()
    if not args.docker and not args.cnf:
        ap.error('必须提供 --docker 或 --cnf 之一')

    modules = [m.strip() for m in args.modules.split(',') if m.strip()]
    entities, n_files = scan_entities(modules)

    # 扫描失效自检：默认模块集下实体文件过少 = 路径错位/模块未挂载，门禁拒绝空洞通过
    if modules == DEFAULT_MODULES and n_files < 10:
        print(f'✗ 失效自检不过：扫描实体文件仅 {n_files} 个（<10），实体目录错位或模块未挂载')
        sys.exit(2)
    if not entities:
        print('✗ 失效自检不过：未解析到任何 @TableName 实体')
        sys.exit(2)

    db_cols = fetch_db_columns(args)
    baseline, _, missing_ok = load_baseline(args.baseline)

    targets = {t: e for t, e in entities.items() if not args.table or t == args.table.lower()}
    if args.table and not targets:
        print(f'✗ 指定表不在实体扫描域: {args.table}（模块: {",".join(modules)}）')
        sys.exit(2)

    problems = 0
    stale_baseline = 0
    exempt_total = 0
    for t in sorted(targets):
        ent, files = targets[t], targets[t]['files']
        tcols = db_cols.get(t)
        if tcols is None:
            if t in missing_ok:
                print(f'  · {t} 表级缺失白名单豁免（实体存在/真库无表，见基线 missing_tables 段理由）')
                continue
            print(f'  ✗ {" ".join(files)} -> {t} 整表不存在于真库')
            problems += 1
            continue
        # 正向：实体有 / 库缺
        missing = sorted(c for c in ent['cols'] if c not in tcols)
        if missing:
            print(f'  ✗ {" ".join(files)} -> {t} 缺 {len(missing)} 列（实体有/库缺）: {missing}')
            problems += 1
        # 反向：库有 / 实体无（白名单豁免；白名单只减不增）
        reverse = sorted(c for c in tcols if c not in ent['cols'])
        wl = baseline.get(t, set())
        exempt = [c for c in reverse if c in wl]
        leak = [c for c in reverse if c not in wl]
        if leak:
            print(f'  ✗ {" ".join(files)} -> {t} 反向漂移 {len(leak)} 列（库有/实体无且不在白名单）: {leak}')
            problems += 1
        if exempt:
            exempt_total += len(exempt)
            print(f'  · {t} 白名单豁免 {len(exempt)} 列: {sorted(exempt)}')
        stale = sorted(c for c in wl if c not in tcols or c in ent['cols'])
        if stale:
            print(f'  ✗ {t} 白名单过期 {len(stale)} 条（列已映射或已不存在，须从基线删除）: {stale}')
            problems += 1
            stale_baseline += len(stale)

    for t in sorted(missing_ok):
        if t in db_cols and t in targets:
            print(f'  ✗ missing_tables 白名单过期: {t}（表已存在于真库，须从基线删除）')
            problems += 1
            stale_baseline += 1

    total = len(targets)
    mod_desc = ','.join(modules)
    if problems == 0:
        print(f'✓ drift-check 通过：模块[{mod_desc}] 实体 {total} 个（扫描文件 {n_files}），'
              f'正反向均与真库对齐；白名单豁免 {exempt_total} 列')
        sys.exit(0)
    print(f'✗ drift-check 失败：模块[{mod_desc}] 实体 {total} 个（扫描文件 {n_files}），'
          f'异常 {problems} 处（含白名单过期 {stale_baseline} 条）')
    print('修复指引：')
    print('  1. 缺列 → docs/script/sql/update/ 加 ALTER TABLE ADD COLUMN（幂等模板）后 apply')
    print('  2. 反向漂移 → 补实体字段映射；确属刻意不映射的既存差异 →')
    print(f'     收录进 {os.path.relpath(args.baseline, REPO_ROOT)} 并附理由+日期')
    print('  3. 白名单过期 → 实体已映射/列已删除的条目立即从基线删除（只减不增）')
    sys.exit(1)


if __name__ == '__main__':
    main()
