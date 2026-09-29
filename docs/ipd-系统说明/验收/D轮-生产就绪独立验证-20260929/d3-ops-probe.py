#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
D3 容量与运维探针 — 只读实测，不写业务库、不改源码、不触发任何变更类端点。

覆盖任务 §6 D3 要求的可实测部分：
  1. 版本落差：运行态 jar 构建时间 vs 源码 HEAD（计划判据 6「证据指向同一运行版本」）
  2. 延迟基线：关键只读端点串行 p50/p95/max
  3. 分页标度性：pageSize 1/10/50/100 → 区分「数据量成本」与「固定每请求开销」
  4. 并发退化：N 路并发的 p50/p95/max 与错误率
  5. 失败停止：非法输入/不存在资源/未认证/未授权 actuator 的响应时延（fail-fast 证据）

**SLO 阈值不在本脚本内判定**：任务要求「与业务先定容量/SLO」，业务未约定前
D 拒绝自行发明阈值（见 D3 文档 §7）。本脚本只产出实测数字，判定留给业务。

安全约束（本轮踩过 SSRF 探针写入业务库的坑，见 D1 §11）：
  - 只用 GET；绝不调用 POST/PUT/DELETE/PATCH
  - 绝不探测 /actuator/shutdown 之类变更端点（只读 401 存在性，用 GET 不用 POST）
  - 凭据从 credentials.json 读取，**输出与落盘一律脱敏**
"""
import concurrent.futures as cf
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from statistics import mean

REPO = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:16039"
CRED = REPO / ".codex/ipd-dev/config/credentials.json"
JAR = REPO / "ruoyi-admin/target/ruoyi-admin.jar"
MAX_BODY = 4 * 1024 * 1024

# 关键只读端点（全部 GET，幂等）
LATENCY_TARGETS = [
    "/api/v1/auth/me",
    "/api/v1/projects?pageNum=1&pageSize=10",
    "/api/v1/products?pageNum=1&pageSize=10",
    "/api/v1/audit-logs?pageNum=1&pageSize=10",
    "/api/v1/stage-actions?pageNum=1&pageSize=10",
    "/api/v1/demands?pageNum=1&pageSize=10",
    "/api/v1/gate-elements?pageNum=1&pageSize=10",
]
# 分页标度性探针：延迟随 pageSize 增长 ⇒ 数据量成本；平坦 ⇒ 固定每请求开销
SCALING_PATH = "/api/v1/projects?pageNum=1&pageSize={sz}"
SCALING_SIZES = [1, 10, 50, 100]
CONCURRENCY_PATH = "/api/v1/projects?pageNum=1&pageSize=10"
CONCURRENCY_LEVELS = [10, 20, 50]
# 失败停止探针：期望「快速拒绝」，用响应时延证明未进入昂贵路径
FAILFAST_TARGETS = [
    ("未认证访问受保护端点", "/api/v1/auth/me", None),
    ("不存在的资源 ID", "/api/v1/projects/99999999999", "TOKEN"),
    ("非法分页参数", "/api/v1/products?pageNum=-1&pageSize=99999", "TOKEN"),
    ("不存在的路由", "/api/v1/nonexistent-route-xyz", "TOKEN"),
    ("actuator 用 IPD token（应 401，非 200）", "/actuator/health", "TOKEN"),
    ("actuator/shutdown 用 IPD token GET（应 401/405，绝不 200）", "/actuator/shutdown", "TOKEN"),
]


def login():
    cred = json.loads(CRED.read_text())
    pwd = cred.get("ipd_qa_pwd_ipd-admin")
    if not pwd:
        return None, "credentials.json 无 ipd_qa_pwd_ipd-admin"
    body = json.dumps({"username": "ipd-admin", "password": pwd}).encode()
    req = urllib.request.Request(BASE + "/api/v1/auth/login", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=20))
    except Exception as e:
        return None, f"{type(e).__name__}: {e}"
    if d.get("code") != 0:
        return None, f"login code={d.get('code')} msg={d.get('message')}"
    return d["data"]["token"], None


def hit(path, token=None, timeout=20):
    """返回 (elapsed_ms, http_status, envelope_code)。读完整 body 防截断误判。"""
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(BASE + path, headers=headers)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, raw = r.status, r.read(MAX_BODY)
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read(MAX_BODY)
    except Exception as e:
        return (time.perf_counter() - t0) * 1000, f"ERR:{type(e).__name__}", None
    ms = (time.perf_counter() - t0) * 1000
    try:
        return ms, status, json.loads(raw).get("code")
    except Exception:
        return ms, status, "NON_JSON"


def pct(sorted_vals, p):
    if not sorted_vals:
        return 0.0
    i = min(len(sorted_vals) - 1, max(0, int(round(p / 100 * len(sorted_vals))) - 1))
    return sorted_vals[i]


def run(args, timeout=60):
    """子进程一律用**列表参数**，不用 shell=True：避免管道/glob 带来的注入面
    （静态扫描 HIGH 告警）。需要管道或计数的地方改用 Python 原生实现。"""
    try:
        r = subprocess.run(args, capture_output=True, text=True, timeout=timeout, cwd=REPO)
        return r.stdout.strip()
    except Exception as e:
        return f"ERR:{e}"


def count_files(pattern, root="."):
    """代替 `ls <glob> | wc -l`，用 Python glob 实现。"""
    try:
        return len(list((REPO / root).glob(pattern)))
    except Exception:
        return -1


def version_gap():
    """运行态 jar vs 源码 HEAD 的落差（计划判据 6「证据指向同一运行版本」的直接核验）。"""
    jar_mtime = JAR.stat().st_mtime if JAR.exists() else None
    head = run(["git", "log", "-1", "--format=%h %ad %s", "--date=format:%m-%d %H:%M"])
    pid = run(["lsof", "-nP", "-iTCP:16039", "-sTCP:LISTEN", "-t"]).split("\n")[0].strip()
    started = run(["ps", "-p", pid, "-o", "lstart="]) if pid else "无监听"
    since = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(jar_mtime)) if jar_mtime else None
    if since:
        log = run(["git", "log", f"--since={since}", "--format=%h %ad %s",
                   "--date=format:%m-%d %H:%M"])
        commits = [ln for ln in log.split("\n") if ln.strip()]
    else:
        commits = []
    return {
        "jar_mtime": since or "jar 不存在",
        "jar_pid": pid or "无",
        "jar_proc_started": started.strip(),
        "source_head": head,
        "commits_since_jar": len(commits),
        "commits_detail": commits,
    }


def main():
    out = {"base": BASE, "generated_at": time.strftime("%Y-%m-%d %H:%M:%S")}

    print("=== 1. 运行态版本落差 ===")
    out["version_gap"] = version_gap()
    for k, v in out["version_gap"].items():
        print(f"  {k}: {v}")

    token, err = login()
    if not token:
        print(f"[FATAL] 登录失败：{err}", file=sys.stderr)
        return 3
    out["login"] = f"OK token_len={len(token)}（凭据脱敏，不落盘）"
    print(f"\n[*] 登录成功 token_len={len(token)}（凭据脱敏）")

    print("\n=== 2. 关键只读端点延迟（串行 n=10）===")
    lat = {}
    for p in LATENCY_TARGETS:
        vals = sorted(hit(p, token)[0] for _ in range(10))
        lat[p] = {"min": round(vals[0], 2), "p50": round(pct(vals, 50), 2),
                  "p95": round(pct(vals, 95), 2), "max": round(vals[-1], 2),
                  "mean": round(mean(vals), 2)}
        print(f"  {p}\n     min={vals[0]:.1f} p50={pct(vals,50):.1f} "
              f"p95={pct(vals,95):.1f} max={vals[-1]:.1f} ms")
    out["latency_serial"] = lat

    print("\n=== 3. 分页标度性（区分数据量成本 vs 固定开销）===")
    scaling = {}
    for sz in SCALING_SIZES:
        vals = sorted(hit(SCALING_PATH.format(sz=sz), token)[0] for _ in range(5))
        scaling[sz] = round(pct(vals, 50), 2)
        print(f"  pageSize={sz:<4} p50={pct(vals,50):.1f} ms")
    spread = max(scaling.values()) - min(scaling.values())
    # 判据必须看「最大页 vs 最小页」的**方向**，不能只看极差：
    # 本轮实测 pageSize=1 → 72ms、100 → 55.3ms（最大页反而更快，属首样本冷缓存），
    # 若用极差/最大值判会误得出「随分页增长」的相反结论（D 自检缺陷 8）。
    small, large = scaling[SCALING_SIZES[0]], scaling[SCALING_SIZES[-1]]
    growth = (large - small) / small * 100 if small else 0
    if growth > 25:
        verdict = (f"随分页增长 +{growth:.0f}% ⇒ 数据量成本为主，"
                   f"需核是否缺索引/全表扫")
    else:
        verdict = (f"**平坦**（pageSize {SCALING_SIZES[0]}→{SCALING_SIZES[-1]} 仅 {growth:+.0f}%）"
                   f"⇒ 延迟主体是**固定每请求开销**（与返回行数无关），"
                   f"疑 N+1 / 权限重算 / 每请求远程调用")
    print(f"  pageSize {SCALING_SIZES[0]}={small:.1f}ms → {SCALING_SIZES[-1]}={large:.1f}ms"
          f"（增长 {growth:+.1f}%，极差 {spread:.1f}ms）\n  → {verdict}")
    out["pagination_scaling"] = {"samples": scaling, "growth_pct": round(growth, 1),
                                 "spread_ms": round(spread, 2), "verdict": verdict}

    print("\n=== 4. 并发退化（错误率 + 分位）===")
    conc = {}
    for n in CONCURRENCY_LEVELS:
        with cf.ThreadPoolExecutor(max_workers=n) as ex:
            res = list(ex.map(lambda _: hit(CONCURRENCY_PATH, token), range(n)))
        vals = sorted(r[0] for r in res)
        errs = [r for r in res if not isinstance(r[1], int) or r[1] >= 500]
        conc[n] = {"p50": round(pct(vals, 50), 2), "p95": round(pct(vals, 95), 2),
                   "max": round(vals[-1], 2), "errors": len(errs), "n": n}
        print(f"  并发 {n:>3} 路: p50={pct(vals,50):.1f} p95={pct(vals,95):.1f} "
              f"max={vals[-1]:.1f} ms  错误/5xx={len(errs)}")
    out["concurrency"] = conc

    print("\n=== 5. 失败停止（fail-fast）===")
    ff = []
    for label, path, tok in FAILFAST_TARGETS:
        ms, status, code = hit(path, token if tok == "TOKEN" else None)
        ff.append({"case": label, "path": path, "ms": round(ms, 2),
                   "http": status, "code": code})
        print(f"  {label}\n     {path} → HTTP {status} code={code} {ms:.2f} ms")
    out["failfast"] = ff

    # 运维面只读事实（不做主观评分，评分在文档里给依据）
    print("\n=== 6. 运维面只读事实 ===")
    cnf = ".codex/ipd-dev/config/mysql-client.cnf"
    mysql_ro = ["mysql", f"--defaults-extra-file={cnf}", "-N", "-B"]
    ops = {}
    ops["db_tables_size"] = run(mysql_ro + [
        "-e", "SELECT COUNT(*), ROUND(SUM(data_length+index_length)/1024/1024,2), "
              "SUM(table_rows) FROM information_schema.tables WHERE table_schema='ipd_dev';"])
    ops["sql_update_scripts"] = count_files("*.sql", "docs/script/sql/update")
    # Flyway/Liquibase：直接读 POM 文本判存在性，避免 shell grep 管道
    pom_hits = []
    for pom in ("pom.xml", "ruoyi-admin/pom.xml"):
        p = REPO / pom
        if p.exists():
            t = p.read_text(encoding="utf-8", errors="replace").lower()
            if "flyway" in t or "liquibase" in t:
                pom_hits.append(pom)
    ops["flyway_or_liquibase"] = pom_hits or "无（⇒ 121 个 SQL 脚本靠人工 apply）"
    ops["weaviate_ready"] = run(["curl", "-s", "-m", "5", "-o", "/dev/null", "-w", "%{http_code}",
                                 "http://127.0.0.1:28080/v1/.well-known/ready"])
    ops["weaviate_objects"] = run(["curl", "-s", "-m", "5",
                                   "http://127.0.0.1:28080/v1/objects?limit=1"])[:120]
    ops["ai_model_configs"] = run(mysql_ro + ["ipd_dev", "-e",
        "SELECT COUNT(*), SUM(is_active=1), GROUP_CONCAT(DISTINCT provider) FROM ai_model_configs;"])
    ops["product_lines_seed"] = run(mysql_ro + ["ipd_dev", "-e",
        "SELECT COUNT(*) FROM product_lines;"])
    ops["backup_artifacts"] = [str(p.relative_to(REPO)) for p in
                               (REPO / ".codex/ipd-dev").rglob("*backup*/*.sql")]
    ops["prometheus_scraper_running"] = run(
        ["docker", "ps", "--format", "{{.Names}} {{.Image}}"])
    # 脱敏 converter：用 Python 遍历源码目录，不用 shell grep
    mask_hits = []
    for root in ("ruoyi-common", "ruoyi-framework", "ruoyi-modules", "ruoyi-admin"):
        for p in (REPO / root).rglob("*.java"):
            sp = str(p)
            if "/target/" in sp or "/.codex/" in sp:
                continue
            try:
                t = p.read_text(encoding="utf-8", errors="replace")
            except Exception:
                continue
            if "desensit" in t.lower() or "Masking" in t:
                mask_hits.append(sp)
    ops["masking_converters_in_java"] = mask_hits
    for k, v in ops.items():
        print(f"  {k}: {v if v not in ('', [], None) else '(空)'}")
    out["ops_facts"] = ops

    out["slo_thresholds"] = "BLOCKED — 业务未约定容量/SLO 阈值，D 拒绝自行发明（见 D3 文档 §7）"
    print(f"\n[*] SLO 判定：{out['slo_thresholds']}")

    (HERE / "d3-ops-probe.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"[OUT] {HERE/'d3-ops-probe.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
