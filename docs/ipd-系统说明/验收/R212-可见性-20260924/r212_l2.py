#!/usr/bin/env python3
# R212 L2 数据表级逐条对账（真库只读 + 代码扫描）
import os, re, subprocess, json, collections

REPO = "/Users/mac/Documents/ruoyi-ai"
FE_SRC = "/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src"
MYSQL = ["/opt/homebrew/bin/mysql", "--defaults-file=.codex/ipd-dev/config/mysql-client.cnf", "-N", "-B", "-e"]

def sql(q):
    r = subprocess.run(MYSQL + [q], cwd=REPO, capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError(r.stderr[:300])
    return [ln.split("\t") for ln in r.stdout.strip().splitlines()]

# 1) 真库表全集 + 行数（COUNT 精确）
tables = [t[0] for t in sql("SELECT table_name FROM information_schema.tables WHERE table_schema='ipd_dev' ORDER BY table_name")]
counts = dict((r[0], int(r[1])) for r in sql(
    "SELECT table_name, table_rows FROM information_schema.tables WHERE table_schema='ipd_dev'"))
# 精确 COUNT 仅对估算为 0 的表复核（估算 0 可能不准），大表用估算
zero_check = [t for t, c in counts.items() if c == 0]
if zero_check:
    union = " UNION ALL ".join(f"SELECT '{t}' t, COUNT(*) c FROM `ipd_dev`.`{t}`" for t in zero_check)
    for r in sql(union):
        counts[r[0]] = int(r[1])
print(f"tables={len(tables)} zero-row={sum(1 for c in counts.values() if c==0)}")

# 2) Entity @TableName 映射
entity_map = {}  # table -> [entity files]
domain_files = []
SCAN_ROOTS = [f"{REPO}/ruoyi-modules", f"{REPO}/ruoyi-common", f"{REPO}/ruoyi-admin"]
for REPO_JAVA in SCAN_ROOTS:
    for root, _, files in os.walk(REPO_JAVA):
        if "/test/" in root or "/target/" in root:
            continue
        for fn in files:
            if fn.endswith(".java"):
                fp = os.path.join(root, fn)
                body = open(fp, encoding="utf-8").read()
                domain_files.append((fn, fp, body))
                m = re.search(r'@TableName\((?:value\s*=\s*)?"([^"]+)"', body)
                if m and "Service" not in fn and "Dto" not in fn:
                    entity_map.setdefault(m.group(1), []).append(fn[:-5])
# 部分实体无 @TableName（驼峰转下划线约定）——按类名推断
for fn, fp, body in domain_files:
    if fn.endswith(".java") and ("/domain/" in fp or "/entity/" in fp) and ("Service" not in fn and "Dto" not in fn and "VO" not in fn):
        cls = fn[:-5]
        if not any(cls in v for v in entity_map.values()):
            guess = re.sub(r"(?<!^)(?=[A-Z])", "_", cls).lower()
            if guess in counts:
                entity_map.setdefault(guess, []).append(cls)

# 3) Mapper 映射（实体名 -> XxxMapper 存在性）
mappers = {fn[:-11] for fn, _, _ in domain_files if fn.endswith("Mapper.java")}
# 4) Service 映射
services = {fn[:-15].replace("Impl", "") for fn, _, _ in domain_files if fn.endswith("ServiceImpl.java")}
# 5) Controller 全集（按域关键词）
ctrls = [fn[:-5] for fn, fp, _ in domain_files if fn.endswith("Controller.java") and "/ipd/" in fp]

# 6) tenant.excludes
yml = open(f"{REPO}/ruoyi-admin/src/main/resources/application.yml", encoding="utf-8").read()
m = re.search(r"excludes:(.*?)(?=\n\s{2,}\S|\Z)", yml[yml.find("tenant:"):], re.S)
tenant_excludes = set(re.findall(r"^\s*-\s*(\w+)", m.group(1), re.M)) if m else set()

# 7) 前端引用代理：api/ipd 业务 ts 文件名片段 + 视图片段
fe_hint = set()
for root, _, files in os.walk(f"{FE_SRC}/api/ipd"):
    for fn in files:
        if fn.endswith(".ts") and not fn.endswith(".test.ts"):
            fe_hint.add(fn[:-3].replace("-", "_").replace("ipd_", ""))
for root, _, files in os.walk(f"{FE_SRC}/views/ipd"):
    for fn in files:
        if fn.endswith(".vue"):
            fe_hint.add(fn[:-4].lower())

rows = []
tables_with_tenant_col = {r[0] for r in sql("SELECT table_name FROM information_schema.columns WHERE table_schema='ipd_dev' AND column_name='tenant_id'")}
for t in sorted(tables):
    ents = entity_map.get(t, [])
    ent = ents[0] if ents else ""
    seg = t.rstrip("s").replace("_", "-")
    has_mapper = (ent in mappers) if ent else False
    # 兜底：表名相关 mapper 名
    if not has_mapper and ent:
        has_mapper = any(ent.lower().startswith(x.lower()) or x.lower().startswith(ent.lower()) for x in mappers)
    has_service = bool(ent) and any(ent in s or s in ent for s in services)
    dom = t.split("_")[0]
    has_ctrl = any(dom.replace("_", "").lower() in c.lower() for c in ctrls)
    fe_ref = any(dom in f or f in dom for f in fe_hint if len(f) > 3)
    rows.append((t, counts.get(t, -1), ent or "❌无Entity", "✓" if has_mapper else "❌",
                 "✓" if has_service else "❌", "✓" if has_ctrl else "—", "✓" if fe_ref else "—",
                 "excluded" if t in tenant_excludes else ("有tenant_id列" if t in tables_with_tenant_col else "无tenant_id")))

lines = ["| 表 | 行数 | Entity | Mapper | Service | Controller(域) | 前端域 | tenant |", "|---|---|---|---|---|---|---|---|"]
for r in rows:
    lines.append("| " + " | ".join(str(x) for x in r) + " |")
open("/tmp/r212_l2_table.md", "w", encoding="utf-8").write("\n".join(lines))

# 8) bonus_pools 外键扫描 + projects 完整性
fk = sql("SELECT p.id, p.project_id, (SELECT COUNT(*) FROM ipd_dev.projects pr WHERE pr.id=p.project_id) ok FROM ipd_dev.bonus_pools p")
dangling = [(a, b) for a, b, ok in fk if ok == "0"]
print("bonus_pools dangling project_id:", dangling)
alloc = sql("SELECT bonus_pool_id, COUNT(*) FROM ipd_dev.bonus_allocations GROUP BY 1") if "bonus_allocations" in tables else []
alloc_orphan = [a for a, _ in alloc if a not in {x[0] for x in fk}]
json.dump({"tables": len(tables), "zero_row": [t for t in tables if counts.get(t) == 0],
           "no_entity": [r[0] for r in rows if r[2].startswith("❌")],
           "dangling_pools": dangling, "alloc_orphan_pool_refs": alloc_orphan},
          open("/tmp/r212_l2_summary.json", "w"), ensure_ascii=False)
print("written /tmp/r212_l2_table.md")
