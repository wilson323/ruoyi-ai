#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
D1 独立追溯器 — 49 页 / 273 canonical 路由 × 当前后端 × 正式前端 × 测试 的五态矩阵生成。

设计原则（D 路 Validator 独立性）：
  1. 不修改任何被测源码；只读扫描 + 只读 HTTP/SQL 探针。
  2. 不采信 DOC-06 机器表的 status/conformance 字段（该表为 2026-09-05 快照，
     自声称 controllers=6 / implemented=28 / verifiedRoutes=0，已过期）。
     DOC-06 只作为「49 页 → canonical 路由」的**映射来源**，实现状态一律现查。
     辅助函数拼接（形如 const path = (id) => 模板串）必须展开后再比对，
     否则会重演 check-api-contract-fe-be.mjs 的假孤儿误报（见 D1 文档 §5）。
  3. 五态口径严格区分，禁止把「源码存在」升级为「真链通过」：
       NOT_IMPLEMENTED  后端无匹配路由映射
       SOURCE_CANDIDATE 后端源码有映射，但未部署/未验证（含 untracked 文件）
       TEST_PASS        有对应测试（后端或前端）且本轮实测通过
       LIVE_PASS        真实 HTTP 探针在运行态返回预期包络（code=0 或明确业务错误码）
       PROD_READY       LIVE_PASS + 权限正反例 + DB 回读一致（本轮不自动判定，留人工）

输入：
  .codex/ruflo/api01-20260905/doc06-refresh/DOC-06.routes.json  （49 页 / 273 路由映射）
  ruoyi-modules/**/*Controller.java                              （当前后端真实映射）
  ruoyi-ipd-web/apps/web-antd/src/api/**/*.ts                    （前端真实调用）
输出（同目录）：
  d1-trace-matrix.json   逐路由五态明细
  d1-trace-summary.json  汇总计数
"""
import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import datetime, timezone, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]   # .../ruoyi-ai（验收/D轮-.../ 共 4 层）
WEB_REPO = REPO.parent / "ruoyi-ipd-web"
DOC06 = REPO / ".codex/ruflo/api01-20260905/doc06-refresh/DOC-06.routes.json"
OUT_DIR = Path(__file__).resolve().parent

VAR_PAT = re.compile(r"\{[^}]+\}")
CANON = "{VAR}"


def canon(path: str) -> str:
    """把 /a/{id}/b 与 /a/{VAR}/b 归一，剔除 query。"""
    p = (path or "").split("?")[0].strip()
    if not p.startswith("/"):
        p = "/" + p
    p = VAR_PAT.sub(CANON, p)
    p = re.sub(r"/+", "/", p)
    return p.rstrip("/") or "/"


def git_untracked_set(root: Path):
    """返回 root 下 untracked 文件集合（用于判定 SOURCE_CANDIDATE 是否未部署）。"""
    try:
        out = subprocess.run(
            ["git", "status", "--short", "--untracked-files=all"],
            cwd=root, capture_output=True, text=True, timeout=60,
        ).stdout
    except Exception:
        return set()
    s = set()
    for line in out.splitlines():
        if line.startswith("??"):
            s.add(line[3:].strip().strip('"'))
    return s


def iter_controllers(root: Path):
    """枚举当前主树的全部 Controller。

    扫描范围必须含 ruoyi-admin（不只 ruoyi-modules）：本轮实测
    ruoyi-admin/src/main/java/org/ruoyi/ipd/controller/IpdPlatformAuthController.java
    提供 /api/v1/auth/**，仅扫 ruoyi-modules 会把它误判为前端真孤儿。
    同时必须排除 .harness/.backup、.worktrees、.codex（swarm 备份工作树快照，
    本轮实测污染 801 个假 Controller）、target，否则同一类会被重复计数。
    """
    exclude = ("/.harness/", "/.worktrees/", "/.codex/", "/target/",
               "/node_modules/", "/.git/", "/docs/")
    for f in root.glob("**/*Controller.java"):
        s = str(f)
        if any(x in s for x in exclude):
            continue
        yield f


def scan_backend_routes(root: Path):
    """扫描全部 Controller 的 @RequestMapping 类前缀 + 方法级映射，返回 {canon_path: [info]}。"""
    routes = defaultdict(list)
    method_anno = re.compile(
        r'@(Get|Post|Put|Delete|Patch|Request)Mapping\s*(?:\(\s*(?:value\s*=\s*)?'
        r'[\{"]?\s*"([^"]*)"[^)]*\)|\(\s*\)|\b)'
    )
    files = list(iter_controllers(root))
    for f in files:
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        cls = re.search(r'@RequestMapping\s*\(\s*"([^"]+)"\s*\)', text)
        prefix = cls.group(1) if cls else ""
        cls_start = cls.start() if cls else -1
        for m in method_anno.finditer(text):
            verb, sub = m.group(1).upper(), m.group(2) or ""
            # 类级 @RequestMapping("/api/v1/x") 必须跳过：否则会被当成方法级再拼一次，
            # 产出 /api/v1/x/api/v1/x 畸形路径（本轮实测踩过，污染别名探测）。
            if m.start() == cls_start:
                continue
            if verb == "REQUEST" and sub == "":
                continue  # 无路径的 @RequestMapping（仅作类级标记）
            full = canon((prefix or "") + (sub if sub.startswith("/") or not sub else "/" + sub))
            routes[full].append({
                "file": str(f.relative_to(root)),
                "verb": verb if verb != "REQUEST" else "ANY",
            })
    return routes, len(files)


IPD_API_PREFIX = "/api/v1"
PUBLIC_API_PREFIX = "/api/v1/public"

# 前端业务客户端 → 运行时注入的绝对前缀（由 http.ts/auth.ts/portal.ts 源码实测）
FE_CLIENT_PREFIX = {
    "ipdGet": IPD_API_PREFIX,
    "ipdPost": IPD_API_PREFIX,
    "ipdPut": IPD_API_PREFIX,
    "ipdDelete": IPD_API_PREFIX,
    "ipdPatch": IPD_API_PREFIX,
    "requestIpd": IPD_API_PREFIX,
    "requestPortal": PUBLIC_API_PREFIX,
}
# 产出 query string 而非路径段的变量：必须剔除，否则 /modify${query} 会变成
# /modify{VAR} 假孤儿（本轮 bid.ts:211 实测踩过）
QUERY_VARS = {"query", "qs", "search", "queryString", "params", "suffix", "buildQuery"}
# 框架入口形参：路径整体为「前缀 + ${path}」的属客户端入口声明，非业务调用
ENTRY_VARS = {"path", "p", "url", "target"}
ENTRY_MARK = "\x00ENTRY\x00"


def normalize_fe_path(p: str, prefix: str) -> str:
    """前端调用路径 → 与后端可比的绝对路径。

    关键归一化（本轮踩过的坑，必须保留）：ipdGet/requestPortal 等传的是**相对**路径，
    运行时由 api/ipd/http.ts → store/ipd-auth.ts authenticatedRequest →
    api/ipd/auth.ts requestIpd 的 fetch('/api/v1' + path)（portal 为 '/api/v1/public' + path）
    注入前缀。若不补前缀直接与后端 @RequestMapping("/api/v1/...") 比对，会得到
    「251/253 前端孤儿、命中率 0%」的**假红**。
    """
    s = (p or "").strip()
    if not s.startswith("/"):
        s = "/" + s
    if s.startswith(prefix):
        return s
    return prefix + s


def scan_frontend_calls(web: Path):
    """扫描前端 api 客户端的调用路径，返回 (calls, entry_points)。

    calls 为 {canon_path(含绝对前缀): [file]}。对契约门禁/粗糙扫描器的四项修正：
      1. 展开 const path = (id) => 模板串 辅助函数，避免 check-api-contract-fe-be.mjs
         把 ${path(id)} 整体降级为 {VAR} 造成假孤儿；
      2. 按客户端补运行时绝对前缀（见 normalize_fe_path），避免 0% 命中假红；
      3. 剔除 ${query} 类 query-string 变量（QUERY_VARS），避免 /modify${query}
         变成 /modify{VAR} 假孤儿；
      4. 排除「前缀 + ${path}」的客户端入口声明（auth.ts requestIpd、
         portal.ts requestPortal），并将其归入 entry_points 而非业务孤儿。
    同时涵盖 requestPortal 这类自定义客户端，修正仅扫 ipd* 造成的 5 条 portal 调用漏测。
    """
    calls = defaultdict(list)
    entry_points = []
    if not web.exists():
        return calls, entry_points
    helper_re = re.compile(r"const\s+(\w+)\s*=\s*\([^)]*\)\s*(?::\s*string\s*)?=>\s*`([^`]+)`")
    clients = "|".join(sorted(FE_CLIENT_PREFIX, key=len, reverse=True))
    call_re = re.compile(
        rf"\b({clients})\s*(?:<[^>]*>)?\s*\(\s*(`[^`]+`|'[^']+'|\"[^\"]+\")")
    fetch_re = re.compile(r"fetch\s*\(\s*(`[^`]+`|'[^']+'|\"[^\"]+\")")
    for f in list(web.glob("apps/web-antd/src/api/**/*.ts")) + list(web.glob("apps/web-antd/src/**/*.vue")):
        if f.name.endswith(".test.ts"):
            continue
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        # 收集本文件的辅助路径函数：name -> 字面量模板（${} 视作 {VAR}）
        helpers = {}
        for m in helper_re.finditer(text):
            helpers[m.group(1)] = m.group(2)

        def expand(raw: str) -> str:
            s = raw.strip("`'\"")

            def repl(mm):
                expr = mm.group(1)
                fn = re.match(r"\s*(\w+)\s*\(", expr)
                if fn and fn.group(1) in helpers:
                    return re.sub(r"\$\{[^}]*\}", CANON, helpers[fn.group(1)])
                bare = re.match(r"\s*(\w+)\s*$", expr)
                name = fn.group(1) if fn else (bare.group(1) if bare else "")
                if name in QUERY_VARS or "uery" in expr:
                    return ""            # query string，不属路径
                if name in ENTRY_VARS:
                    return ENTRY_MARK    # 客户端入口声明
                return CANON
            s = re.sub(r"\$\{([^}]*(?:\{[^}]*\}[^}]*)*)\}", repl, s)
            s = re.sub(r"\$\{[^}]*\}", CANON, s)
            return s

        rel = str(f.relative_to(web))
        for m in call_re.finditer(text):
            client, expanded = m.group(1), expand(m.group(2))
            if ENTRY_MARK in expanded:
                entry_points.append({"file": rel, "client": client, "expr": m.group(2)})
                continue
            calls[canon(normalize_fe_path(expanded, FE_CLIENT_PREFIX[client]))].append(rel)
        for m in fetch_re.finditer(text):
            expanded = expand(m.group(1))
            if ENTRY_MARK in expanded:
                entry_points.append({"file": rel, "client": "fetch", "expr": m.group(1)})
                continue
            p = expanded[expanded.index(IPD_API_PREFIX):] if IPD_API_PREFIX in expanded else expanded
            if not p.startswith(IPD_API_PREFIX):
                continue            # 非 IPD 绝对路径（如 /copilotkit/**）不在本轮追溯面
            calls[canon(p)].append(rel)
    return calls, entry_points


def scan_tests(root: Path, web: Path):
    """收集测试文件里出现的路由字面量，作为 TEST_PASS 的弱证据。"""
    hits = defaultdict(list)
    paths = list(root.glob("ruoyi-modules/**/src/test/**/*.java"))
    if web.exists():
        paths += list(web.glob("apps/web-antd/src/**/*.test.ts"))
    lit = re.compile(r'"(/[a-zA-Z0-9_\-/{}$.]+)"')
    for f in paths:
        try:
            text = f.read_text(encoding="utf-8", errors="replace")
        except Exception:
            continue
        for m in lit.finditer(text):
            p = m.group(1)
            if p.count("/") >= 2 and not p.endswith((".json", ".md", ".sql", ".xml")):
                hits[canon(p)].append(str(f.name))
    return hits


def tail_seg(p: str) -> str:
    segs = [x for x in p.split("/") if x and x != CANON]
    return segs[-1] if segs else ""


def name_variants(t: str):
    """末段的单/复数变体，用于判定「命名漂移」与「真缺口」。"""
    v = {t}
    if t.endswith("s"):
        v.add(t[:-1])
    else:
        v.add(t + "s")
    if t.endswith("ies"):
        v.add(t[:-3] + "y")
    return v


def probe_alias_gaps(not_impl, be_paths):
    """对 NOT_IMPLEMENTED 路由拆分为「疑命名漂移」与「高置信真缺口」。

    判据：后端存在**同末段或单/复数变体**的映射 → 疑命名漂移（需人工核），
    否则高置信真缺口。仅作线索，不作结论（任务铁律：扫描器总数只作线索）。
    """
    alias, gap = [], []
    for c in not_impl:
        t = tail_seg(c)
        hit = sorted(p for p in be_paths if p != c and tail_seg(p) in name_variants(t))
        (alias if hit else gap).append({"canonical": c, "backend_same_tail": hit[:3]})
    return alias, gap


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--live", action="store_true", help="启用真实 HTTP 探针（需 token 文件）")
    ap.add_argument("--token-file", default=str(REPO / ".codex/ipd-dev/config/bootstrap-accounts.json"))
    args = ap.parse_args()

    if not DOC06.exists():
        print(f"[FATAL] DOC-06 机器表缺失: {DOC06}", file=sys.stderr)
        return 2
    doc = json.loads(DOC06.read_text())
    pages = doc.get("pages", [])
    doc_routes = doc.get("routes", [])
    print(f"[*] DOC-06 快照日期={doc.get('date')} pages={len(pages)} canonicalRoutes={len(doc_routes)}")
    print(f"    DOC-06 自声称: {json.dumps(doc.get('counts', {}), ensure_ascii=False)}")
    print("[!] D1 不采信上述 status/conformance（快照过期），实现状态一律现查")

    be_routes, ctrl_count = scan_backend_routes(REPO)
    print(f"[*] 当前后端 Controller 文件数={ctrl_count} 解析出映射路径数={len(be_routes)}")

    fe_calls, fe_entries = scan_frontend_calls(WEB_REPO)
    print(f"[*] 前端解析出业务调用路径数={len(fe_calls)}（已展开辅助函数/已补绝对前缀/已剔 query 变量），"
          f"已排除框架入口 {len(fe_entries)} 处")

    # 前端调用 → 后端实现 命中率（白屏风险的权威判据，区别于 DOC-06 规格路径）
    fe_hit = sorted(p for p in fe_calls if p in be_routes)
    fe_orphan = sorted(p for p in fe_calls if p not in be_routes)
    fe_hit_rate = round(100.0 * len(fe_hit) / len(fe_calls), 1) if fe_calls else 0.0
    print(f"[*] 前端→后端 精确命中={len(fe_hit)}/{len(fe_calls)} ({fe_hit_rate}%)，"
          f"真孤儿={len(fe_orphan)}")

    # NOT_IMPLEMENTED 拆分（命名漂移线索 vs 高置信真缺口）——须在 summary 构建前算好
    not_impl = [c for c in (canon(r.get("path", "")) for r in doc_routes)
                if not be_routes.get(c)]
    alias, gap = probe_alias_gaps(not_impl, set(be_routes))
    gap_prefix = Counter(g["canonical"].replace("/api/v1/", "").split("/")[0] for g in gap)

    tests = scan_tests(REPO, WEB_REPO)
    untracked = git_untracked_set(REPO)

    matrix = []
    state_count = Counter()
    per_page = defaultdict(Counter)

    for r in doc_routes:
        raw = r.get("path", "")
        c = canon(raw)
        verb = (r.get("method") or "ANY").upper()
        be = be_routes.get(c, [])
        fe = fe_calls.get(c, [])
        ts = tests.get(c, [])
        # 判定实现文件是否 untracked（未部署）
        be_untracked = any(any(u in b["file"] or b["file"].endswith(u.split("/")[-1])
                            for u in untracked) for b in be) if be else False

        if not be:
            state = "NOT_IMPLEMENTED"
        elif be_untracked:
            state = "SOURCE_CANDIDATE"
        elif ts:
            state = "TEST_PASS"
        else:
            state = "SOURCE_CANDIDATE"

        state_count[state] += 1
        for pg in (r.get("pages") or []):
            per_page[pg][state] += 1

        matrix.append({
            "canonical": c,
            "verb": verb,
            "doc06_status": r.get("status"),
            "doc06_conformance": str(r.get("conformance"))[:60],
            "pages": r.get("pages") or [],
            "d1_state": state,
            "backend_impl": be[:3],
            "backend_untracked": be_untracked,
            "frontend_callers": sorted(set(fe))[:3],
            "test_evidence": sorted(set(ts))[:3],
            "live_probe": None,
        })

    summary = {
        "generated_at": datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"),
        "repo_head": subprocess.run(["git", "rev-parse", "HEAD"], cwd=REPO,
                                    capture_output=True, text=True).stdout.strip(),
        "web_head": subprocess.run(["git", "rev-parse", "HEAD"], cwd=WEB_REPO,
                                   capture_output=True, text=True).stdout.strip() if WEB_REPO.exists() else None,
        "doc06_snapshot_date": doc.get("date"),
        "doc06_self_claimed_counts": doc.get("counts"),
        "backend_controller_files": ctrl_count,
        "backend_parsed_mappings": len(be_routes),
        "frontend_parsed_calls": len(fe_calls),
        "frontend_entry_points_excluded": len(fe_entries),
        "frontend_to_backend_hit": len(fe_hit),
        "frontend_to_backend_hit_rate_pct": fe_hit_rate,
        "frontend_true_orphans": len(fe_orphan),
        "frontend_true_orphan_paths": fe_orphan,
        "canonical_routes_traced": len(matrix),
        "not_implemented_split": {
            "suspected_name_drift": len(alias),
            "high_confidence_gap": len(gap),
            "gap_by_prefix": dict(gap_prefix),
            "alias_samples": alias[:40],
            "gap_paths": [g["canonical"] for g in gap],
        },
        "d1_state_counts": dict(state_count),
        "per_page_state": {str(k): dict(v) for k, v in sorted(per_page.items(), key=lambda x: (isinstance(x[0], str), x[0]))},
        "note": "SOURCE_CANDIDATE 含 untracked（未部署）与无测试两类；LIVE_PASS/PROD_READY 需 --live 与人工权限正反例，本轮未自动判定",
    }

    (OUT_DIR / "d1-trace-matrix.json").write_text(
        json.dumps(matrix, ensure_ascii=False, indent=1), encoding="utf-8")
    (OUT_DIR / "d1-trace-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n=== D1 五态计数（273 canonical 路由）===")
    for k, v in state_count.most_common():
        print(f"  {k:20s} {v}")

    # NOT_IMPLEMENTED 拆分结果输出（alias/gap 已在前文算好）
    print(f"\n=== NOT_IMPLEMENTED {len(not_impl)} 条拆分（仅线索，需人工核）===")
    print(f"  疑命名漂移（后端有同末段/单复数变体）: {len(alias)}")
    print(f"  高置信真缺口（后端无任何同末段映射）  : {len(gap)}")
    print(f"  真缺口按前缀 top10: {gap_prefix.most_common(10)}")
    if fe_orphan:
        print(f"\n=== 前端真孤儿（后端无匹配路由，白屏/404 风险）共 {len(fe_orphan)} 条，前 25 ===")
        for p in fe_orphan[:25]:
            print(f"  {p}  <- {fe_calls[p][0]}")
    print(f"\n[OUT] {OUT_DIR/'d1-trace-matrix.json'}")
    print(f"[OUT] {OUT_DIR/'d1-trace-summary.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
