#!/usr/bin/env python3
# R212 L1 端点级逐条矩阵生成器（只读扫描，输出 markdown）
import os, re, json, collections

BE_DIRS = [
    "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller",
    "/Users/mac/Documents/ruoyi-ai/ruoyi-admin/src/main/java/org/ruoyi/ipd/controller",
]
FE_API_DIR = "/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src/api/ipd"
BE_TEST_DIR = "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/test"

M_METHOD = re.compile(r'@(Get|Post|Put|Delete|Patch)Mapping\b')
REQ_CLS  = re.compile(r'@RequestMapping\s*(?:\(\s*)?(?:value\s*=\s*)?"([^"]+)"')
PERM     = re.compile(r'@SaCheckPermission\s*\(\s*(?:value\s*=\s*)?(?:"([^"]+)"|\{[^}]*"([^"]+)"|(\w+)\.)')
LOGIN    = re.compile(r'@SaCheckLogin')

def norm_vars(p):
    # {id} / ${encodeURIComponent(x)} / ${y} -> {X}
    p = re.sub(r'\$\{[^}]*\}', '{X}', p)
    p = re.sub(r'\{[^}]*\}', '{X}', p)
    p = re.sub(r':\w+(?=/|$)', '{X}', p)
    return p.rstrip('/') if len(p) > 1 else p

# ---- 后端端点全集 ----
endpoints = []  # (METHOD, raw_path, norm, controller_file, line, perm)
for d in BE_DIRS:
    if not os.path.isdir(d):
        continue
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".java"):
            continue
        path = os.path.join(d, fn)
        src = open(path, encoding="utf-8").read().splitlines()
        # class-level prefix
        prefix = ""
        for i, ln in enumerate(src[:80]):
            m = REQ_CLS.search(ln)
            if m and ("class " in "\n".join(src[i:i+6])):
                prefix = m.group(1)
                break
        i = 0
        while i < len(src):
            m = M_METHOD.search(src[i])
            if not m:
                i += 1
                continue
            verb = m.group(1).upper()
            line_txt = src[i]
            # 多行 value= 形式：下一行找字符串
            sub = ""
            mm = re.search(r'Mapping\s*\(\s*(?:value\s*=\s*)?"([^"]+)"', line_txt)
            if not mm and "Mapping(" in line_txt and '"' not in line_txt:
                for j in range(i+1, min(i+3, len(src))):
                    mm2 = re.search(r'"([^"]+)"', src[j])
                    if mm2:
                        sub = mm2.group(1)
                        break
            elif mm:
                sub = mm.group(1)
            full = (sub if sub.startswith("/api") else (prefix + sub if sub else prefix)) or prefix
            if not full.startswith("/api/v1"):
                full = "/api/v1" + full
            # 权限注解：向上收集（连续注解行），向下到方法签名
            perm = ""
            k = i - 1
            ctx_up = []
            while k >= 0 and (src[k].strip().startswith("@") or src[k].strip() == ""):
                if src[k].strip().startswith("@"):
                    ctx_up.append(src[k])
                k -= 1
            up = "\n".join(reversed(ctx_up))
            pm = PERM.search(up)
            if pm:
                perm = pm.group(1) or pm.group(2) or "const-ref"
            elif LOGIN.search(up):
                perm = "@SaCheckLogin"
            if not perm:
                perm = "(类级/兜底)"
            endpoints.append((verb, full, norm_vars(full), fn, i+1, perm))
            i += 1

uniq = {}
for e in endpoints:
    uniq[(e[0], e[2])] = e
endpoints = list(uniq.values())
print(f"BE endpoints unique: {len(endpoints)}")

# ---- 前端调用全集 ----
fe_calls = set()
fe_by_norm = collections.defaultdict(set)  # (METHOD, norm) -> {files}
CALL = re.compile(r'(?:ipd|client|requestIpd|baseRequestClient|requestClient)\s*\.\s*(get|post|put|delete|patch)|(?:ipd)(Get|Post|Put|Delete)\s*(?:<[^>()]*>)?\s*\(')
SQ = r"'(/[^']+)'"
DQ = r'"(/[^"]+)"'
BT = r'`(/[^`]+)`'
PATH_RE = re.compile(f'{SQ}|{DQ}|{BT}')
for root, _, files in os.walk(FE_API_DIR):
    for fn in files:
        if not fn.endswith(".ts") or fn.endswith(".test.ts"):
            continue
        for i, ln in enumerate(open(os.path.join(root, fn), encoding="utf-8")):
            m = CALL.search(ln)
            if not m:
                continue
            verb = (m.group(1) or m.group(2) or "").upper()
            if not verb:
                continue
            pm = PATH_RE.search(ln)
            if not pm:
                # 多行：下一行找 path 字符串
                all_lines = open(os.path.join(root, fn), encoding="utf-8").read().split("\n")
                if i + 1 < len(all_lines):
                    pm = PATH_RE.search(all_lines[i+1])
            if not pm:
                continue
            raw = pm.group(1) or pm.group(2) or pm.group(3)
            if not raw.startswith("/api/v1"):
                raw = "/api/v1" + raw
            fe_calls.add((verb, norm_vars(raw)))
            fe_by_norm[(verb, norm_vars(raw))].add(fn)
print(f"FE unique calls: {len(fe_calls)}")

# ---- 测试映射：controller 名 -> 测试文件数 ----
test_files = []
for root, _, files in os.walk(BE_TEST_DIR):
    for fn in files:
        if fn.endswith(".java"):
            test_files.append((fn, open(os.path.join(root, fn), encoding="utf-8").read()))
ctrl_test_count = {}
for d in BE_DIRS:
    if not os.path.isdir(d):
        continue
    for fn in sorted(os.listdir(d)):
        if not fn.endswith(".java"):
            continue
        cname = fn[:-5]
        seg = cname.replace("Controller", "")
        n = sum(1 for tf, body in test_files if cname in body or (seg and seg in tf))
        ctrl_test_count[cname] = n

# ---- 交叉前的旧逻辑已替换（见下方 mjs 权威判定） ----
legacy_matched = be_norm_placeholder = None
if True:
    pass
# 测试映射：controller 名 -> 测试文件数
mjs = json.load(open("/tmp/r212-contract.json"))
mjs_orphan = {(o["method"], norm_vars(re.sub(r'\{VAR\}', '{X}', o["path"]))) for o in mjs["orphan_endpoints"]}
mjs_mismatch = {norm_vars(m["canonical"].replace("{VAR}", "{X}")): m for m in mjs["field_mismatches"]}
be_norm = {(v, n) for v, _raw, n, *_ in endpoints}
matched = be_norm - mjs_orphan
orphan_be = be_norm & mjs_orphan
orphan_fe = sorted({f"{v} {p}" for v, p in fe_calls if p.startswith('/api/v1') and (v, p) not in be_norm})
print(f"matched={len(matched)} orphan_be={len(orphan_be)} (mjs={len(mjs_orphan)})")

# 前端 caller 文件映射：用 mjs field_mismatch 的 feFile + 粗粒度 grep（按路径首段）
fe_seg_files = collections.defaultdict(set)
for root, _, files in os.walk(FE_API_DIR):
    for fn in files:
        if not fn.endswith(".ts") or fn.endswith(".test.ts"):
            continue
        body = open(os.path.join(root, fn), encoding="utf-8").read()
        for seg in re.findall(r"['\"`]/([a-z][a-z0-9-]+)", body):
            fe_seg_files[seg].add(fn.replace(".ts", ""))

# 与 mjs 门禁交叉核对
mjs = json.load(open("/tmp/r212-contract.json"))
mjs_orphan = {(o["method"], norm_vars(re.sub(r'\{VAR\}', '{X}', o["path"]))) for o in mjs["orphan_endpoints"]}
diff = orphan_be ^ mjs_orphan
print(f"mjs orphan={len(mjs_orphan)} 口径差异={len(diff)}")

# ---- 输出 markdown ----
by_ctrl = collections.defaultdict(list)
for v, raw, nrm, fn, ln, perm in endpoints:
    by_ctrl[fn[:-5]].append((v, raw, nrm, ln, perm))

def seg_of(path):
    m = re.match(r"/api/v1/([a-z0-9-]+)", path)
    return m.group(1) if m else ""

lines = ["| Controller | 方法+路径 | 权限注解 | 前端调用源 | 单测引用 | 状态 |",
         "|---|---|---|---|---|---|"]
for ctrl in sorted(by_ctrl):
    for v, raw, nrm, ln, perm in sorted(by_ctrl[ctrl]):
        is_orphan = (v, nrm) in orphan_be
        seg = seg_of(raw)
        callers = sorted(fe_seg_files.get(seg, []))
        fe_txt = ("、".join(callers[:3])) if callers else "—"
        if is_orphan:
            fe_txt = "⚠️无调用"
        elif nrm in mjs_mismatch:
            mm = mjs_mismatch[nrm]
            fe_txt = f"{mm['feFile'].split('/')[-1].replace('.ts','')}:{mm['feLine']} (变量名错位 {mm['feVars']}vs{mm['beVars']})"
        n_test = ctrl_test_count.get(ctrl, 0)
        st = "🔴BE孤儿" if is_orphan else ("⚠️字段错位" if nrm in mjs_mismatch else "✅双契约通")
        lines.append(f"| {ctrl} | {v} `{raw}` | {perm} | {fe_txt} | {n_test} | {st} |")
open("/tmp/r212_l1_table.md", "w", encoding="utf-8").write("\n".join(lines))
json.dump({"endpoints": len(endpoints), "matched": len(matched),
           "orphan_be": len(orphan_be), "orphan_fe_check": len(orphan_fe)},
          open("/tmp/r212_l1_summary.json", "w"))
print("written /tmp/r212_l1_table.md", "fe-side-suspect-orphan(仅参考):", len(orphan_fe))
