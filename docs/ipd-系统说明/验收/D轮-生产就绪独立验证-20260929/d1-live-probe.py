#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
D1 LIVE 探针 — 把五态中的 LIVE_PASS 从「未判定」变成实测值。

只做 HTTP GET（幂等语义），不写业务库、不改源码。
凭据源：.codex/ipd-dev/config/credentials.json 的 ipd_qa_pwd_*（**输出与文档一律脱敏**）。

背景（本轮实测的关键事实）：
  运行态后端 jar 构建于 2026-09-28 20:30，而源码 HEAD 已到 09-29 02:16，
  中间 9 个提交。故「源码 100% 命中」不等于「运行态可用」：
    GET /api/v1/ipd/product-lines      → HTTP 404 code=50001 资源不存在
    GET /api/v1/sub-stages             → HTTP 404 code=50001
    GET /api/v1/auth/me                → HTTP 200 code=0 ok
  本脚本就是为量化这一落差而存在。

判定口径：
  LIVE_PASS   HTTP 200 且 code == 0
  LIVE_AUTHZ  HTTP 200 且 code 属权限/参数类业务错误（端点存在，本账号无权或参数不足）
  LIVE_404    HTTP 404 / code 50001  → 运行态未部署该路由
  LIVE_405    HTTP 405 或框架 405 包络 → 路径存在但动词不匹配（GET 探测的正常副产物）
  LIVE_ERR    其他（5xx、非 JSON、超时）
"""
import json
import subprocess
import sys
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:16039"
CRED = REPO / ".codex/ipd-dev/config/credentials.json"
VAR = "{VAR}"

# GET 探测时会填充的真实 ID 来源（只读 SELECT，取每类第一条）
ID_SQL = {
    VAR: "SELECT id FROM projects WHERE del_flag='0' ORDER BY id LIMIT 1",
}


def login():
    cred = json.loads(CRED.read_text())
    pwd = cred.get("ipd_qa_pwd_ipd-admin")
    if not pwd:
        return None, "credentials.json 无 ipd_qa_pwd_ipd-admin"
    body = json.dumps({"username": "ipd-admin", "password": pwd}).encode()
    req = urllib.request.Request(
        BASE + "/api/v1/auth/login", data=body,
        headers={"Content-Type": "application/json"})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=20))
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"
    if d.get("code") != 0:
        return None, f"login code={d.get('code')} msg={d.get('message')}"
    return d["data"]["token"], None


def q1(sql):
    cnf = REPO / ".codex/ipd-dev/config/mysql-client.cnf"
    try:
        out = subprocess.run(
            ["mysql", f"--defaults-extra-file={cnf}", "-N", "-B", "ipd_dev", "-e", sql],
            capture_output=True, text=True, timeout=30).stdout.strip()
        return out.splitlines()[0] if out else None
    except Exception:
        return None


MAX_BODY = 4 * 1024 * 1024   # 必须读完整 body：本轮踩过 read()[:600] 截断 JSON
                             # 导致 /api/v1/auth/me（2616 字节）被误判 NON_JSON 的坑


def probe(token, path):
    req = urllib.request.Request(BASE + path, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            status, raw = r.status, r.read(MAX_BODY)
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read(MAX_BODY)
    except Exception as e:
        return "LIVE_ERR", f"{type(e).__name__}"
    try:
        d = json.loads(raw)
    except Exception:
        # 非 JSON 是真实契约异常（如导出流/裸数组），记录 content-type 供诊断
        head = raw[:60].decode("utf-8", "replace").replace("\n", " ")
        return "LIVE_NONJSON", f"HTTP{status} bytes={len(raw)} head={head!r}"
    code = d.get("code")
    msg = str(d.get("message") or d.get("msg") or "")[:40]
    if status == 200 and code == 0:
        return "LIVE_PASS", f"code=0 {msg}"
    if status == 404 or code == 50001:
        return "LIVE_404", f"HTTP{status} code={code} {msg}"
    if status == 405 or code == 405:
        return "LIVE_405", f"HTTP{status} code={code} {msg}"
    if status in (400, 409) or (isinstance(code, int) and code in (10001, 50002)):
        # 路由存在，仅因缺参/业务前置不满足而拒 ⇒ 端点已部署的证据
        return "LIVE_REACHABLE", f"HTTP{status} code={code} {msg}"
    if status == 200 and isinstance(code, int) and code != 0:
        return "LIVE_AUTHZ", f"code={code} {msg}"
    return "LIVE_ERR", f"HTTP{status} code={code} {msg}"


def main():
    # 直接探测**前端全部调用路径**（而非 DOC-06 的 273 规格路由）：
    # DOC-06 是 2026-09-05 快照，不含 product-lines / sub-stage 等新路由，
    # 两者交集极小，用它探测会漏掉最关键的运行态 404。
    import importlib.util
    spec = importlib.util.spec_from_file_location("d1m", HERE / "d1-trace-matrix.py")
    d1m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(d1m)

    fe_calls, _ = d1m.scan_frontend_calls(d1m.WEB_REPO)
    be_routes, _ = d1m.scan_backend_routes(d1m.REPO)
    print(f"[*] 前端调用路径={len(fe_calls)}，后端源码映射={len(be_routes)}")

    token, err = login()
    if not token:
        print(f"[FATAL] 登录失败：{err}", file=sys.stderr)
        return 3
    print(f"[*] 登录成功 token_len={len(token)}（凭据已脱敏，不落盘）")

    real_id = q1(ID_SQL[VAR])
    print(f"[*] 路径变量填充用真实 ID：{'取得' if real_id else '未取得（含路径变量的路由将跳过）'}")

    targets = []
    for p in sorted(fe_calls):
        src_state = "SOURCE_OK" if p in be_routes else "SRC_MISSING"
        if VAR in p:
            if not real_id:
                continue
            p = p.replace(VAR, str(real_id))
        targets.append((p, src_state))

    print(f"[*] 待 GET 探测（前端在调且可构造）：{len(targets)} 条\n")

    results, states = [], Counter()
    cross = Counter()
    for p, src_state in targets:
        st, detail = probe(token, p)
        states[st] += 1
        cross[(src_state, st)] += 1
        results.append({"path": p, "source_state": src_state,
                        "live_state": st, "detail": detail})

    print("=== LIVE 探测结果分布 ===")
    for k, v in states.most_common():
        print(f"  {k:12s} {v}")

    print("\n=== 源码态 × 运行态 交叉表（关键落差）===")
    for (ss, ls), n in sorted(cross.items(), key=lambda x: (-x[1], x[0])):
        flag = "  ⚠ 源码有但运行态 404" if ss == "SOURCE_OK" and ls == "LIVE_404" else ""
        print(f"  {ss:12s} → {ls:12s} {n}{flag}")

    gap = [r for r in results if r["source_state"] == "SOURCE_OK" and r["live_state"] == "LIVE_404"]
    # 404 再拆：含填充 ID 的可能是「资源不存在」而非「路由不存在」
    route_404 = [r for r in gap if str(real_id) not in r["path"]]
    res_404 = [r for r in gap if str(real_id) in r["path"]]
    print(f"\n=== 🔴 源码有实现但运行态 404：{len(gap)} 条 ===")
    print(f"  其中路由级 404（不含填充 ID，= 真未部署）：{len(route_404)} 条")
    for r in route_404:
        print(f"    {r['path']}")
    print(f"  其中资源级 404（含填充 ID，= 该 ID 在对应资源表不存在，**不得当路由缺失**）：{len(res_404)} 条")
    for r in res_404[:15]:
        print(f"    {r['path']}")

    live_pass = [r["path"] for r in results if r["live_state"] == "LIVE_PASS"]
    print(f"\n=== ✅ LIVE_PASS（HTTP200 + code=0）：{len(live_pass)} 条，前 20 ===")
    for p in live_pass[:20]:
        print(f"  {p}")

    out = {
        "base": BASE,
        "login": "OK（凭据源 credentials.json:ipd_qa_pwd_ipd-admin，已脱敏）",
        "probed": len(targets),
        "live_state_counts": dict(states),
        "source_x_live": {f"{a}->{b}": n for (a, b), n in cross.items()},
        "source_ok_but_runtime_404": [r["path"] for r in gap],
        "route_level_404": [r["path"] for r in route_404],
        "resource_level_404": [r["path"] for r in res_404],
        "live_pass_paths": live_pass,
        "results": results,
    }
    (HERE / "d1-live-probe.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n[OUT] {HERE/'d1-live-probe.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
