#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""D3 容量实测探针 — SLO v1（2026-09-29 owner 拍板开测）。

测量项：
  SLO#1 并发容量：≥50 并发会话错误率 0（≥5 轮）
  SLO#2 首 token：P95 ≤3s（SSE 真流式 GET /api/v1/ai-copilot/chat/stream，含模型侧）
  SLO#3 核心 DB 查询：只读列表端点 p95 ≤200ms（≥5 轮；projects 离群单列不并入判定）
  SLO#4 向量检索：p95 ≤500ms（Weaviate 空库则判 BLOCKED，不做无意义查询）
  SLO#5 预算熔断：不在此脚本（需可逆 DB 测试写入，单独流程执行并登记）

测量纪律（D3 §7.1）：≥5 轮、报中位数+方差；每轮记录背景流量（sys-info.log
request_completed 增量）披露共存干扰；SLO#1 先做双 token 互踢探测决定
「50 独立会话」还是「单会话 50 并发」口径并如实披露。

副作用声明：#1/#3 为 GET；#2 为 GET SSE 但后端会真实调用 MiniMax 模型并写
usage/审计记录（按 R214 测试数据政策留库登记）。凭据从 credentials.json
读取，输出与落盘一律脱敏。D 不改任何 A/B/C 源码。
"""
import concurrent.futures as cf
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from statistics import mean, median

REPO = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
BASE = "http://127.0.0.1:16039"
CRED = REPO / ".codex/ipd-dev/config/credentials.json"
LOG = REPO / "logs/sys-info.log"
ROUNDS = 5

LIST_TARGETS = [
    "/api/v1/projects?pageNum=1&pageSize=10",
    "/api/v1/products?pageNum=1&pageSize=10",
    "/api/v1/audit-logs?pageNum=1&pageSize=10",
    "/api/v1/stage-actions?pageNum=1&pageSize=10",
    "/api/v1/demands?pageNum=1&pageSize=10",
    "/api/v1/gate-elements?pageNum=1&pageSize=10",
    "/api/v1/auth/me",
]
OUTLIER = "/api/v1/projects?pageNum=1&pageSize=10"  # §1.1 离群单列
CONC_PATH = "/api/v1/projects?pageNum=1&pageSize=10"
CONC_LEVEL = 50
SSE_PATH = "/api/v1/ai-copilot/chat/stream?message=" + urllib.request.quote(
    "请用一句话说明项目管理的价值。")
SSE_ROUNDS = 6


def login(quiet=False):
    cred = json.loads(CRED.read_text())
    pwd = cred.get("ipd_qa_pwd_ipd-admin")
    body = json.dumps({"username": "ipd-admin", "password": pwd}).encode()
    req = urllib.request.Request(BASE + "/api/v1/auth/login", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        d = json.load(urllib.request.urlopen(req, timeout=20))
    except urllib.error.HTTPError as e:
        msg = e.read(300).decode("utf-8", "replace")
        if not quiet:
            print(f"  [login] HTTP {e.code}: {msg[:80]}")
        return None
    except Exception:
        return None
    if d.get("code") != 0:
        return None
    return d["data"]["token"]


def hit(path, token, timeout=30):
    req = urllib.request.Request(BASE + path,
                                 headers={"Authorization": f"Bearer {token}",
                                          "Accept": "application/json"})
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status, raw = r.status, r.read(4 * 1024 * 1024)
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read(4 * 1024 * 1024)
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


def noise():
    """背景流量代理：sys-info.log 行数 + request_completed 计数（只读尾部）。"""
    try:
        tail = subprocess.run(["tail", "-400", str(LOG)], capture_output=True,
                              text=True, timeout=10).stdout
        return tail.count("request_completed")
    except Exception:
        return -1


def sse_first_token(token, timeout=40):
    """返回 (first_byte_ms, first_meta_ms, first_delta_ms, done/error 标记)。"""
    req = urllib.request.Request(BASE + SSE_PATH,
                                 headers={"Authorization": f"Bearer {token}",
                                          "Accept": "text/event-stream"})
    t0 = time.perf_counter()
    fb = meta = delta = None
    terminal = None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            fb = (time.perf_counter() - t0) * 1000
            event = None
            for raw in r:
                line = raw.decode("utf-8", "replace").strip()
                if line.startswith("event:"):
                    event = line[6:].strip()
                    now = (time.perf_counter() - t0) * 1000
                    if event == "meta" and meta is None:
                        meta = now
                    elif event == "delta" and delta is None:
                        delta = now
                    elif event in ("done", "error"):
                        terminal = event
                        break
    except Exception as e:
        terminal = f"ERR:{type(e).__name__}"
    return fb, meta, delta, terminal


def main():
    out = {"generated_at": time.strftime("%Y-%m-%d %H:%M:%S"), "base": BASE,
           "rounds": ROUNDS}
    token = login()
    if not token:
        print("[FATAL] 登录失败（可能限流窗口内，稍后重试）", file=sys.stderr)
        return 3
    print(f"[*] 登录 OK（token 脱敏）")

    # —— SLO#1 会话口径：登录防爆破限流实测（连续第 6 次 login 触发 10001）——
    # 50 独立会话不可行；自适应建会话池（最多 5，失败即停），池内均分 50 并发。
    pool = [token]
    for _ in range(4):
        t = login(quiet=True)
        if not t:
            break
        pool.append(t)
        time.sleep(0.2)
    if len(pool) == 1:
        mode = "单会话 50 并发（登录限流，独立会话不可行）"
    else:
        mode = f"{len(pool)} 独立会话 × 均分 50 并发"
    out["session_kick_probe"] = {
        "mode": mode,
        "session_pool": len(pool),
        "note": "登录防爆破限流实测：连续第 6 次 login 即 400 code=10001「登录尝试过于频繁」"
                "⇒ 50 独立会话口径不可行，SLO#1 按并发请求口径测并披露"}
    print(f"[*] 会话口径：{mode}")

    # —— SLO#3 只读列表 p95（每轮每端点 10 样本串行，共 ROUNDS 轮）——
    print(f"\n=== SLO#3 只读列表 p95（{ROUNDS} 轮 × 10 样本/端点/轮）===")
    slo3 = {}
    for p in LIST_TARGETS:
        round_p95, samples = [], []
        for _ in range(ROUNDS):
            vals = sorted(hit(p, token)[0] for _ in range(10))
            round_p95.append(pct(vals, 95))
            samples += vals
        rec = {"round_p95": [round(v, 1) for v in round_p95],
               "p95_median": round(median(round_p95), 1),
               "p95_min": round(min(round_p95), 1), "p95_max": round(max(round_p95), 1),
               "p50_all": round(pct(sorted(samples), 50), 1),
               "stdev": round((max(round_p95) - min(round_p95)), 1)}
        if p == OUTLIER:
            rec["note"] = "§1.1 离群（固定开销），单列不并入判定"
        else:
            rec["pass_200ms"] = rec["p95_median"] <= 200
        slo3[p] = rec
        tag = "离群单列" if p == OUTLIER else ("PASS" if rec.get("pass_200ms") else "FAIL")
        print(f"  [{tag}] {p}\n     p95 中位={rec['p95_median']}ms "
              f"({rec['p95_min']}–{rec['p95_max']}，极差{rec['stdev']}ms)")
    out["slo3_readonly_list"] = slo3

    # —— SLO#1 并发容量（CONC_LEVEL 并发 × ROUNDS 轮，错误率必须 0）——
    print(f"\n=== SLO#1 并发容量（{CONC_LEVEL} 并发 × {ROUNDS} 轮）===")
    slo1 = {"mode": mode, "rounds": []}
    for rd in range(ROUNDS):
        n = noise()
        per = CONC_LEVEL // len(pool)
        jobs = [t for t in pool for _ in range(per)]
        jobs += [pool[0]] * (CONC_LEVEL - len(jobs))
        with cf.ThreadPoolExecutor(max_workers=CONC_LEVEL) as ex:
            res = list(ex.map(lambda t: hit(CONC_PATH, t), jobs[:CONC_LEVEL]))
        vals = sorted(r[0] for r in res)
        errs = [r for r in res if r[1] != 200 or r[2] != 0]
        rec = {"n": CONC_LEVEL, "errors": len(errs), "error_rate": len(errs) / CONC_LEVEL,
               "p50": round(pct(vals, 50), 1), "p95": round(pct(vals, 95), 1),
               "max": round(vals[-1], 1), "bg_noise_req_completed_tail": n}
        slo1["rounds"].append(rec)
        print(f"  轮{rd+1}: 错误 {len(errs)}/{CONC_LEVEL}  p50={rec['p50']} "
              f"p95={rec['p95']} max={rec['max']} ms  背景噪声={n}")
        time.sleep(1)
    tot_err = sum(r["errors"] for r in slo1["rounds"])
    slo1["total_errors"] = tot_err
    slo1["pass"] = tot_err == 0
    p95s = sorted(r["p95"] for r in slo1["rounds"])
    slo1["p95_median_of_rounds"] = round(median(p95s), 1)
    slo1["p95_range"] = [round(p95s[0], 1), round(p95s[-1], 1)]
    print(f"  ⇒ 错误合计 {tot_err}（SLO#1 {'PASS' if slo1['pass'] else 'FAIL'}）"
          f"  轮间 p95 中位={slo1['p95_median_of_rounds']}ms")
    out["slo1_concurrency"] = slo1

    # —— SLO#2 首 token（SSE 真流式，含模型侧）——
    print(f"\n=== SLO#2 首 token（SSE × {SSE_ROUNDS} 轮）===")
    ft, tt, dd, terms = [], [], [], []
    for rd in range(SSE_ROUNDS):
        n = noise()
        fb, meta, delta, terminal = sse_first_token(token)
        if delta is not None:
            dd.append(delta)
        if meta is not None:
            tt.append(meta)
        if fb is not None:
            ft.append(fb)
        terms.append(terminal)
        print(f"  轮{rd+1}: 首字节={fb and round(fb,1)} meta={meta and round(meta,1)} "
              f"首delta={delta and round(delta,1)} ms  终帧={terminal}  背景噪声={n}")
        time.sleep(2)
    slo2 = {"first_byte_ms": [round(v, 1) for v in ft],
            "first_meta_ms": [round(v, 1) for v in tt],
            "first_delta_ms": [round(v, 1) for v in dd],
            "terminals": terms}
    if dd:
        s2 = sorted(dd)
        slo2.update({"p95": round(pct(s2, 95), 1), "median": round(median(s2), 1),
                     "min": round(s2[0], 1), "max": round(s2[-1], 1),
                     "pass_3s": pct(s2, 95) <= 3000,
                     "n_valid_first_delta": len(dd)})
    else:
        slo2.update({"verdict": "无有效首 delta（全走意图兜底或错误）— 见 terminals，"
                                "按「无可测对象/未测得」披露，不得判 PASS"})
    out["slo2_first_token"] = slo2
    if dd:
        print(f"  ⇒ 首 delta 中位={slo2['median']}ms p95={slo2['p95']}ms "
              f"（{'PASS' if slo2['pass_3s'] else 'FAIL'}，n={len(dd)}/{SSE_ROUNDS}）")

    # —— SLO#4 向量检索可测性探测（空库即 BLOCKED，不做无意义查询）——
    try:
        raw = subprocess.run(["curl", "-s", "-m", "5",
                              "http://127.0.0.1:28080/v1/objects?limit=1"],
                             capture_output=True, text=True, timeout=10).stdout
        objs = json.loads(raw).get("objects") or []
        schema_raw = subprocess.run(["curl", "-s", "-m", "5",
                                     "http://127.0.0.1:28080/v1/schema"],
                                    capture_output=True, text=True, timeout=10).stdout
        classes = json.loads(schema_raw).get("classes") or []
        out["slo4_vector"] = {"weaviate_objects_sample": len(objs),
                              "weaviate_schema_classes": len(classes),
                              "verdict": "BLOCKED（空库，无可测对象）" if not objs and not classes
                                         else "有数据，需补 nearVector 查询轮次"}
    except Exception as e:
        out["slo4_vector"] = {"verdict": f"BLOCKED（探测失败 {type(e).__name__}）"}
    print(f"\n=== SLO#4 向量检索：{out['slo4_vector']['verdict']} ===")

    out["slo5_budget"] = "不在此脚本（需可逆 DB 测试写入，单独流程）"
    (HERE / "d3-capacity-probe.json").write_text(
        json.dumps(out, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n[OUT] {HERE / 'd3-capacity-probe.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
