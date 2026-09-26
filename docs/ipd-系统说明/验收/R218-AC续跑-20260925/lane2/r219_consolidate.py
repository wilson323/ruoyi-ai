#!/usr/bin/env python3
# R219 LANE2 收尾整合（只读磁盘产物，不发业务写请求）：
#  1) 按 (card, case) 键把各修复轮临时清单里被 card 级 merge 挤掉的"另一条断言腿"记录补回正式清单；
#  2) union 写库清单（修复轮进程持有旧内存列表会全量 dump 覆盖，故与 written-repair4快照.json 合并去重）；
#  3) 校验：卡号覆盖、8 字段齐全、计数汇总打印。
import json, os, re
from collections import Counter

BASE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(BASE, "执行清单-lane2.json")
WRIT = os.path.join(BASE, "写库清单-lane2.json")
# 只从 repair-3 及以后补回：rerun1/rerun2 里未进主清单的 4 条是"同卡中途早退记录"
# （bind 失败/游客需求 429 等，已被后续轮次的同 case 终态取代），补回会造成同一断言腿双记账，故排除。
FILES = ["rerun3-执行清单.json", "rerun4-执行清单.json", "rerun5-执行清单.json"]
SNAPS = ["written-repair4快照.json", "written-repair5快照.json"]


def load(p):
    return json.load(open(p, encoding="utf-8")) if os.path.exists(p) else []


def main():
    recs = load(MAIN)
    keys = {(r["card"], r["case"]) for r in recs}
    added = []
    for f in FILES:
        for r in load(os.path.join(BASE, f)):
            k = (r["card"], r["case"])
            if k in keys:
                continue
            keys.add(k)
            recs.append(dict(r, note=(r.get("note") or "") + " | 收尾整合：该卡另一断言腿记录，由 %s 补回" % f))
            added.append(k)
    # 统一按卡号排序，便于核对
    def key(r):
        m = re.match(r"AC-(GATE|IPD)-([0-9]+)(.*)", r["card"])
        return (m.group(1) if m else "Z", int(m.group(2)) if m else 999, m.group(3) if m else "", r["case"])
    recs.sort(key=key)
    with open(MAIN + ".pre-consolidate", "w", encoding="utf-8") as fh:
        json.dump(load(MAIN), fh, ensure_ascii=False, indent=1)
    json.dump(recs, open(MAIN, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    seen, wout = set(), []
    for f in [WRIT] + SNAPS:
        for e in load(os.path.join(BASE, f)) if f == WRIT else load(os.path.join(BASE, os.path.basename(f))):
            k = (e.get("card"), e.get("op"), e.get("ts"))
            if k in seen:
                continue
            seen.add(k)
            wout.append(e)
    wout.sort(key=lambda e: (e.get("ts") or "", e.get("card") or ""))
    json.dump(wout, open(WRIT, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    pool = open("/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/外部资源/IPD系统_验收清单.md", encoding="utf-8").read()
    cards = sorted(set(re.findall(r"AC-(?:GATE|IPD)-[0-9a-z]+", pool)))
    cov = {r["card"] for r in load("/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-QA08-SEC04-20260925/执行清单-r218.json")}
    have = {r["card"] for r in recs}
    missing = [c for c in cards if c not in have and c not in cov]
    badfields = [r["card"] for r in recs if set(r) < {"card", "case", "cmd", "http", "envelope_code", "verdict", "note", "ts"}]
    dom = lambda p: dict(Counter(r["verdict"] for r in recs if r["card"].startswith(p)))
    print("records=%d cards=%d 补回=%s" % (len(recs), len(have), added))
    print("GATE:", dom("AC-GATE-"))
    print("IPD :", dom("AC-IPD-"))
    print("合计:", dict(Counter(r["verdict"] for r in recs)))
    print("MISSING:", missing, "| 字段缺失:", badfields)
    print("written=%d" % len(wout))


if __name__ == "__main__":
    main()
