#!/usr/bin/env python3
# R219 LANE2 修复轮 #1：只重跑首跑失败/假红的 case，按 card 合并回正式执行清单
import sys, json, os, shutil
sys.path.insert(0, ".")
import r219_lib as L

BASE = os.path.dirname(os.path.abspath(__file__))
MAIN_REC = os.path.join(BASE, "执行清单-lane2.json")
BAK = os.path.join(BASE, "执行清单-lane2.run1备份.json")
TMP_REC = os.path.join(BASE, "rerun1-执行清单.json")

if os.path.exists(MAIN_REC) and not os.path.exists(BAK):
    shutil.copy(MAIN_REC, BAK)
# 预载既有写库登记（lib.written 全量 dump，需增量保留）
if os.path.exists(L.WRITTEN_PATH):
    L.WRITTEN.extend(json.load(open(L.WRITTEN_PATH, encoding="utf-8")))
# 重定向 record 输出到临时文件，避免覆盖全量
L.REC_PATH = TMP_REC

import r219_lane2_gate_ipd as D

def main():
    print("== LANE2 REPAIR-1 run %s @ %s ==" % (D.RUN, L.now()), flush=True)
    D.setup()
    order = [
        ("AC-GATE-05", D.case_gate05_10_06_07),
        ("AC-GATE-11", D.case_gate11_12),
        ("AC-GATE-09", D.case_gate09_21),
        ("AC-GATE-18", D.case_gate18),
        ("AC-GATE-17", D.case_gate17_19),
        ("AC-IPD-08", D.case_ipd_s_level),          # 为 SOP instantiate 重建 PINV2（记录同步刷新）
        ("AC-IPD-10", D.case_ipd_chain_b),
        ("AC-GATE-13", lambda: D.case_gate13(D.PROJS.get("PINV1"))),
        ("AC-IPD-27", D.case_ipd_sop),
    ]
    for card, fn in order:
        D.run_case(card, fn)
    # ---- merge：新记录按 card 覆盖旧记录，未重跑 card 原样保留 ----
    new = json.load(open(TMP_REC, encoding="utf-8"))
    old = json.load(open(BAK, encoding="utf-8")) if os.path.exists(BAK) else []
    rerun_cards = {r["card"] for r in new}
    merged = [r for r in old if r["card"] not in rerun_cards] + new
    with open(MAIN_REC, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=1)
    from collections import Counter
    c = Counter(r["verdict"] for r in new)
    cm = Counter(r["verdict"] for r in merged)
    print("== REPAIR-1 done rerun=%s merged=%s ==" % (dict(c), dict(cm)), flush=True)

if __name__ == "__main__":
    main()
