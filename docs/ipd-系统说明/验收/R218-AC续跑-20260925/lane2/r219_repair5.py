#!/usr/bin/env python3
# R219 LANE2 修复轮 #5（限流窗口等待轮）：只针对 AC-GATE-11 / AC-GATE-12
#  唯一入口 POST /api/v1/public/demands 受服务端 IP 固定小时窗限流（10 次/窗，三车道共享 127.0.0.1）。
#  零落库探针（productId 不存在 → 404/50001，位于 tryAcquire 之后、不落 requirements）判断配额是否轮换；
#  放开即跑 case_gate11_12（含 AC-GATE-11 两腿 + AC-GATE-12 双签腿）；到点仍 429 则两条卡诚实记 FAIL-ENV。
import sys, json, os, time, shutil
sys.path.insert(0, ".")
import r219_lib as L

BASE = os.path.dirname(os.path.abspath(__file__))
MAIN_REC = os.path.join(BASE, "执行清单-lane2.json")
BAK = os.path.join(BASE, "执行清单-lane2.入repair5前备份.json")
TMP_REC = os.path.join(BASE, "rerun5-执行清单.json")
WAIT_SEC = int(os.environ.get("L2_WAIT_SEC", "3600"))
POLL_SEC = int(os.environ.get("L2_POLL_SEC", "240"))

if os.path.exists(MAIN_REC) and not os.path.exists(BAK):
    shutil.copy(MAIN_REC, BAK)
if os.path.exists(L.WRITTEN_PATH):
    L.WRITTEN.extend(json.load(open(L.WRITTEN_PATH, encoding="utf-8")))
L.REC_PATH = TMP_REC

import r219_lane2_gate_ipd as D


def probe():
    s, bj, _, _ = L.req("POST", L.B39, "/api/v1/public/demands", body={
        "customerName": "LANE2-%s-PROBE5" % D.RUN, "feedbackPerson": "LANE2", "contact": "000",
        "productId": 999999999999, "rawModel": "M1",
        "functionalRequirement": "LANE2 限流配额探针 六个字以上"})
    return s, D.ec(bj), D.msg(bj)


def main():
    print("== LANE2 REPAIR-5 run %s @ %s (wait<=%ss poll=%ss) ==" % (D.RUN, L.now(), WAIT_SEC, POLL_SEC), flush=True)
    D.setup()
    deadline = time.time() + WAIT_SEC
    probes, freed = [], False
    while True:
        st, ecv, ms = probe()
        probes.append((L.now(), st, ecv, ms[:50]))
        print("[B] probe %s http=%s code=%s msg=%s" % (L.now(), st, ecv, ms[:50]), flush=True)
        if ecv != 40011:
            freed = True
            break
        if time.time() > deadline:
            print("[B] 等待到点仍限流 -> AC-GATE-11/12 记 FAIL-ENV", flush=True)
            break
        time.sleep(POLL_SEC)
    if freed:
        D.run_case("AC-GATE-11", D.case_gate11_12)
    tail = "; ".join("%s http=%s code=%s" % (p[0], p[1], p[2]) for p in probes[-6:])
    have = {r["card"] for r in json.load(open(TMP_REC, encoding="utf-8"))} if os.path.exists(TMP_REC) else set()
    if "AC-GATE-12" not in have:
        D.rec("AC-GATE-12", "变更双签→需求池已采纳(ADOPTED)", "POST /public/demands -> triage -> link-project -> requirement-changes -> sign x2",
              probes[-1][1] if probes else None, None, "FAIL-ENV",
              "前置入口 POST /api/v1/public/demands 仍被服务端 IP 限流(429/40011, 10 次/固定小时窗, 三车道共享 127.0.0.1)，"
              "本卡唯一造数入口即此端点，借用库内他人 requirements 会造成跨车道串扰故不采用。探针序列(零落库: productId 不存在→404/50001)：%s" % tail,
              ecd=probes[-1][2] if probes else None)
    new = json.load(open(TMP_REC, encoding="utf-8"))
    cur = json.load(open(MAIN_REC, encoding="utf-8"))
    by_card = {}
    for r in new:
        by_card.setdefault(r["card"], []).append(r)
    merged = [r for r in cur if r["card"] not in by_card]
    for card, nrs in by_card.items():
        olds = [r for r in cur if r["card"] == card]
        if all(r["verdict"] == "FAIL-ENV" for r in nrs) and any(r["verdict"] == "PASS" for r in olds):
            merged.extend(olds)
        else:
            merged.extend(nrs)
    with open(MAIN_REC, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=1)
    with open(L.WRITTEN_PATH, "w", encoding="utf-8") as f:
        json.dump(L.WRITTEN, f, ensure_ascii=False, indent=1)
    from collections import Counter
    print("== REPAIR-5 done freed=%s probes=%d rerun=%s merged=%s ==" % (
        freed, len(probes), dict(Counter(r["verdict"] for r in new)),
        dict(Counter(r["verdict"] for r in merged))), flush=True)


if __name__ == "__main__":
    main()
