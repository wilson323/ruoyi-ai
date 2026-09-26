#!/usr/bin/env python3
# R219 LANE2 修复轮 #2：gate11_12(产品ACTIVE) + gate18(update去enabled)，merge 回正式清单
import sys, json, os, shutil
sys.path.insert(0, ".")
import r219_lib as L
BASE = os.path.dirname(os.path.abspath(__file__))
MAIN_REC = os.path.join(BASE, "执行清单-lane2.json")
BAK = os.path.join(BASE, "执行清单-lane2.run1备份.json")
TMP_REC = os.path.join(BASE, "rerun2-执行清单.json")
if os.path.exists(L.WRITTEN_PATH):
    L.WRITTEN.extend(json.load(open(L.WRITTEN_PATH, encoding="utf-8")))
L.REC_PATH = TMP_REC
import r219_lane2_gate_ipd as D

def main():
    print("== LANE2 REPAIR-2 run %s @ %s ==" % (D.RUN, L.now()), flush=True)
    D.setup()
    D.run_case("AC-GATE-11", D.case_gate11_12)
    D.run_case("AC-GATE-18", D.case_gate18)
    new = json.load(open(TMP_REC, encoding="utf-8"))
    cur = json.load(open(MAIN_REC, encoding="utf-8"))
    rerun_cards = {r["card"] for r in new}
    merged = [r for r in cur if r["card"] not in rerun_cards] + new
    with open(MAIN_REC, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=1)
    from collections import Counter
    print("== REPAIR-2 done rerun=%s merged=%s ==" % (dict(Counter(r["verdict"] for r in new)), dict(Counter(r["verdict"] for r in merged))), flush=True)

if __name__ == "__main__":
    main()
