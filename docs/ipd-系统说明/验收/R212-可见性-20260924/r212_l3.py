#!/usr/bin/env python3
# R212 L3 前端路由级逐条对账 v2（就近回溯解析）
import re, os, json

WEB = "/Users/mac/Documents/ruoyi-ipd-web/apps/web-antd/src"
src = open(f"{WEB}/router/routes/modules/ipd.ts", encoding="utf-8").read()
lines = src.splitlines()

routes = []
for i, ln in enumerate(lines):
    m = re.search(r"(?<![\w])(?<!active)path:\s*'([^']+)'", ln)
    if not m:
        continue
    indent = (len(ln) - len(ln.lstrip())) // 2
    path = m.group(1)
    # 向上回溯 12 行找块头（component/name/meta）
    back = "\n".join(lines[max(0, i-12):i])
    comp = ""
    cm = re.findall(r"import\('([^']+)'\)", back)
    if cm:
        comp = cm[-1]
    nm = re.findall(r"name:\s*'([^']+)'", back)
    name = nm[-1] if nm else ""
    tm = re.findall(r"title:\s*'([^']+)'", back)
    title = tm[-1] if tm else ""
    am = re.findall(r"PAGE_PERMISSIONS\['([^']+)'\]", back)
    access = am[-1] if am else ""
    hide = "hideInMenu: true" in back.split("name:")[-1] if "name:" in back else "hideInMenu: true" in back
    routes.append({"indent": indent, "path": path, "name": name, "comp": comp,
                   "title": title, "access": access, "hide": bool(hide), "line": i+1})

# 拼完整路径
p_stack = {}
for r in routes:
    ind = r["indent"]
    p = r["path"]
    if p.startswith("/"):
        r["full"] = p
        p_stack = {k: v for k, v in p_stack.items() if k < ind}
        p_stack[ind] = p
    else:
        parent = p_stack.get(ind-1, p_stack.get(max([k for k in p_stack if k < ind], default=0), ""))
        if not p_stack or ind-1 not in p_stack:
            cand = [v for k, v in sorted(p_stack.items()) if k < ind]
            parent = cand[-1] if cand else ""
        else:
            parent = p_stack[ind-1]
        r["full"] = f"{parent.rstrip('/')}/{p}"
        p_stack[ind] = r["full"]

def vue_info(comp):
    if not comp:
        return ("(布局/redirect)", [])
    fp = os.path.join(WEB, comp.replace("#/", "").replace("@/", ""))
    if not os.path.isfile(fp):
        return ("❌文件缺失", [])
    body = open(fp, encoding="utf-8").read()
    apis = sorted(set(re.findall(r"from\s+'[^']*/api/ipd/([^']+)'", body)))
    return (os.path.relpath(fp, WEB), apis)

seen = set()
out = ["| 完整路径 | 标题 | 组件文件 | 权限映射键 | 菜单隐藏 | api/ipd 依赖 | 行号 |",
       "|---|---|---|---|---|---|---|"]
n_leaf = 0
for r in routes:
    if r["full"] in seen:
        continue
    seen.add(r["full"])
    f, apis = vue_info(r["comp"])
    if r["comp"]:
        n_leaf += 1
    out.append(f"| `{r['full']}` | {r['title']} | {f} | {r['access'] or '—'} | {'是' if r['hide'] else '否'} | {'、'.join(apis) or '—'} | {r['line']} |")
open("/tmp/r212_l3_table.md", "w", encoding="utf-8").write("\n".join(out))
json.dump([{k: r[k] for k in ("full","title","access","hide")} for r in routes if r["full"] not in ("/ipd",)],
          open("/tmp/r212_l3_routes.json","w"), ensure_ascii=False)
missing = [r["full"] for r in routes if vue_info(r["comp"])[0] == "❌文件缺失"]
print(f"parsed {len(routes)} route entries, leaf-with-component {n_leaf}")
print("missing vue files:", missing)
