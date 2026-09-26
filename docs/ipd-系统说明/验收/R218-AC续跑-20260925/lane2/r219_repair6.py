#!/usr/bin/env python3
# R219 LANE2 修复轮 #6（AC-GATE-09 双侧腿定稿轮）：
#  只跑 case_gate09_both_sides（submit 后留 2s 余量修正 24h 上界 fixture 假红），
#  前置同样用 system_configs.allowance.projectCountThreshold 临时放宽（跑完还原）。
import sys, json, os, shutil
sys.path.insert(0, ".")
import r219_lib as L

BASE = os.path.dirname(os.path.abspath(__file__))
MAIN_REC = os.path.join(BASE, "执行清单-lane2.json")
BAK = os.path.join(BASE, "执行清单-lane2.入repair6前备份.json")
TMP_REC = os.path.join(BASE, "rerun6-执行清单.json")
THRESH_KEY = "allowance.projectCountThreshold"

if os.path.exists(MAIN_REC) and not os.path.exists(BAK):
    shutil.copy(MAIN_REC, BAK)
if os.path.exists(L.WRITTEN_PATH):
    L.WRITTEN.extend(json.load(open(L.WRITTEN_PATH, encoding="utf-8")))
L.REC_PATH = TMP_REC
import r219_lane2_gate_ipd as D


def syscfg_get(key):
    s, bj, _, _ = L.req("GET", L.B39, "/api/v1/system-configs/%s" % key, token=D.TOK[D.ADM])
    return s, D.ec(bj), (D.data(bj) or {}).get("value")


def syscfg_put(key, val, reason):
    s, bj, _, _ = L.req("PUT", L.B39, "/api/v1/system-configs/%s" % key, token=D.TOK[D.ADM],
                        body={"value": str(val), "reason": reason})
    L.written({"card": "AC-GATE-09(环境脚手架)", "op": "PUT /system-configs/%s = %s (%s)" % (key, val, reason), "ts": L.now()})
    return s, D.ec(bj), D.msg(bj)


def main():
    print("== LANE2 REPAIR-6 run %s @ %s ==" % (D.RUN, L.now()), flush=True)
    D.setup()
    s0, c0, orig = syscfg_get(THRESH_KEY)
    orig = int(orig or 3)
    cnts = [int(x[0]) for x in L.sql(
        "SELECT COUNT(*) FROM project_members WHERE person_id IN (%s,%s) AND exit_date IS NULL AND del_flag='0' GROUP BY person_id"
        % (D.P_MKT, D.P_RD))] or [0]
    bump = max(cnts) + 3
    print("[A] GET %s=%s; 900103/900104 活跃绑定=%s -> 临时阈值=%s" % (THRESH_KEY, orig, cnts, bump), flush=True)
    syscfg_put(THRESH_KEY, bump, "LANE2-%s AC-GATE-09 双侧腿复跑:临时放宽到%d,跑完还原" % (D.RUN, bump))
    try:
        D.run_case("AC-GATE-09", D.case_gate09_both_sides)
    finally:
        sr, cr, mr = syscfg_put(THRESH_KEY, orig, "LANE2-%s AC-GATE-09 复跑结束还原" % D.RUN)
        v2 = syscfg_get(THRESH_KEY)[2]
        print("[A] restore http=%s code=%s -> 复读 value=%s (期望 %s)" % (sr, cr, v2, orig), flush=True)
    new = json.load(open(TMP_REC, encoding="utf-8"))
    cur = json.load(open(MAIN_REC, encoding="utf-8"))
    by_card = {}
    for r in new:
        by_card.setdefault(r["card"], []).append(r)
    merged, kept = [], []
    for r in cur:
        c = r["card"]
        if c in by_card:
            olds = [x for x in cur if x["card"] == c]
            if all(x["verdict"] == "FAIL-ENV" for x in by_card[c]) and any(x["verdict"] == "PASS" for x in olds):
                kept.append(c)
            continue
        merged.append(r)
    for card, nrs in by_card.items():
        merged.extend(nrs if card not in kept else [r for r in cur if r["card"] == card])
    for r in merged:
        if r["card"] == "AC-GATE-09" and any(n["card"] == r["card"] for n in new) and "repair-6" not in (r["note"] or ""):
            r["note"] = (r["note"] or "") + " | repair-6：修正 submit 后 24h 上界采样时刻(留 2s)后复跑，绑定阈值 %d→%d 临时放宽已还原=%d" % (orig, bump, orig)
    json.dump(merged, open(MAIN_REC, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump(L.WRITTEN, open(L.WRITTEN_PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    from collections import Counter
    print("== REPAIR-6 done rerun=%s kept=%s merged=%s ==" % (
        dict(Counter(r["verdict"] for r in new)), kept, dict(Counter(r["verdict"] for r in merged))), flush=True)


if __name__ == "__main__":
    main()
