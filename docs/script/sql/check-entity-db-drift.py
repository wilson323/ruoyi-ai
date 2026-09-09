#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
entity↔真库 drift-check 门禁（IPD 蜂群根治轮 2026-09-07 落地）

背景：仓库无 Flyway/Liquibase，docs/script/sql/update/** 全靠人工 apply，
"SQL 已 commit ≠ 对象已生效"。实体加字段但 DDL 漏迁移时，Mock 单测全绿
（Mockito stub 绕过真 SQL），真库一跑即爆 Unknown column。本脚本做
「domain 实体字段 ↔ information_schema 真库列」全量比对，作为 apply 后/
验收前的独立回读门禁。

用法（二选一）：
  1) docker exec 通道（推荐，宿主映射 3306）:
     python3 check-entity-db-drift.py --docker ruoyi-ai-mysql --db ipd_dev
  2) socket/cnf 通道:
     python3 check-entity-db-drift.py --cnf <mysql-client.cnf> --db ipd_dev

退出码：0=全部对齐；1=存在 drift（CI 可直接作门禁）。
扫描范围：ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain/*.java
识别规则：
  - @TableName(value="t") 取表名
  - @TableField("c") 显式列映射优先
  - 否则驼峰→下划线推导（MyBatis-Plus 默认 map-underscore-to-camel-case）
  - @TableField(exist = false) 的字段跳过（非持久化）
"""
import argparse
import os
import re
import subprocess
import sys

ENTITY_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '../../..', 'ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/domain')
ENTITY_DIR = os.path.normpath(ENTITY_DIR)


def to_snake(name: str) -> str:
    s1 = re.sub(r'(.)([A-Z][a-z]+)', r'\1_\2', name)
    return re.sub(r'([a-z0-9])([A-Z])', r'\1_\2', s1).lower()


def scan_entities():
    """返回 {entity_file: (table, [column,...])}"""
    entities = {}
    for f in sorted(os.listdir(ENTITY_DIR)):
        if not f.endswith('.java'):
            continue
        with open(os.path.join(ENTITY_DIR, f), encoding='utf-8') as fp:
            content = fp.read()
        m = re.search(r'@TableName\(value\s*=\s*"([^"]+)"', content)
        if not m:
            continue
        table = m.group(1)
        columns = {}
        # 逐字段块解析：捕获注解块 + 紧随的字段声明
        for fm in re.finditer(
                r'((?:@\w+(?:\([^)]*\))?\s*\n)*)\s*private\s+(?!static)'
                r'(?:final\s+)?[\w.<>\[\]]+\s+(\w+)\s*;', content):
            ann, field = fm.group(1) or '', fm.group(2)
            if field == 'serialVersionUID':
                continue
            if re.search(r'@TableField\([^)]*exist\s*=\s*false', ann):
                continue  # 非持久化字段
            explicit = re.search(r'@TableField\(\s*(?:value\s*=\s*)?"(\w+)"', ann)
            columns[explicit.group(1) if explicit else to_snake(field)] = field
        entities[f] = (table, list(columns.keys()))
    return entities


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
            db_cols.setdefault(t, set()).add(c)
    return db_cols


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--docker', help='docker 容器名（容器内 mysql -uroot $MYSQL_ROOT_PASSWORD）')
    ap.add_argument('--cnf', help='mysql client cnf（socket 通道）')
    ap.add_argument('--db', default='ipd_dev')
    args = ap.parse_args()
    if not args.docker and not args.cnf:
        ap.error('必须提供 --docker 或 --cnf 之一')

    entities = scan_entities()
    db_cols = fetch_db_columns(args)
    problems = 0
    for f, (table, cols) in entities.items():
        tcols = db_cols.get(table)
        if tcols is None:
            print(f'  ✗ {f} -> {table} 整表不存在')
            problems += 1
            continue
        missing = [c for c in cols if c not in tcols]
        if missing:
            print(f'  ✗ {f} -> {table} 缺 {len(missing)} 列: {missing}')
            problems += 1
    total = len(entities)
    if problems == 0:
        print(f'✓ drift-check 通过：实体 {total} 个，全部与真库对齐')
        sys.exit(0)
    print(f'✗ drift-check 失败：实体 {total} 个，异常 {problems} 个')
    sys.exit(1)


if __name__ == '__main__':
    main()
