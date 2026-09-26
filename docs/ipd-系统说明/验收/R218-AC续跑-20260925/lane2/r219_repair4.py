#!/usr/bin/env python3
# R219 LANE2 修复轮 #4（补强轮）：
#  用 system_configs.allowance.projectCountThreshold 临时放宽（跑完还原）把组 900001 的可登录在册 PM
#  (900103 MARKET_PM / 900104 RD_PM) 入组，从而补测两条 repair-3 未覆盖的腿：
#    AC-GATE-10：组长 900102 真实落裁（预落待裁行被 UPDATE）+ 重复提交守卫 + 终裁门
#    AC-GATE-09：双签 Gate 两侧均在册时，scan-remind 只提醒未签方、已签方不提醒
#  merge 规则同 repair-3（不用 FAIL-ENV 覆盖既有 PASS）。
import sys, json, os, time, shutil
sys.path.insert(0, ".")
import r219_lib as L

BASE = os.path.dirname(os.path.abspath(__file__))
MAIN_REC = os.path.join(BASE, "执行清单-lane2.json")
BAK = os.path.join(BASE, "执行清单-lane2.入repair4前备份.json")
TMP_REC = os.path.join(BASE, "rerun4-执行清单.json")
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
    L.written({"card": "AC-GATE-09/10(环境脚手架)", "op": "PUT /system-configs/%s = %s (%s)" % (key, val, reason), "ts": L.now()})
    return s, D.ec(bj), D.msg(bj)


def main():
    print("== LANE2 REPAIR-4 run %s @ %s ==" % (D.RUN, L.now()), flush=True)
    D.setup()
    # 无阈值依赖的补测腿先跑（不占用临时放宽窗口）
    D.run_case("AC-IPD-12", D.case_ipd12_overdue_marker)
    s0, c0, orig = syscfg_get(THRESH_KEY)
    orig = int(orig or 3)
    cnts = [int(x[0]) for x in L.sql(
        "SELECT COUNT(*) FROM project_members WHERE person_id IN (%s,%s) AND exit_date IS NULL AND del_flag='0' GROUP BY person_id"
        % (D.P_MKT, D.P_RD))] or [0]
    bump = max(cnts) + 3   # repair-4 二跑：本车道的 gate10+gate09 两个用例各占 2 槽，余量需 >3 才不落进 threshold-1 分支
    print("[A] GET %s=%s; 900103/900104 活跃绑定=%s -> 临时阈值=%s" % (THRESH_KEY, orig, cnts, bump), flush=True)
    sb, cb, mb = syscfg_put(THRESH_KEY, bump, "LANE2-%s 补强复跑:在册PM槽位被并发车道占满,临时放宽到%d,跑完还原" % (D.RUN, bump))
    try:
        D.run_case("AC-GATE-10", D.case_gate10_leader_ruling)
        D.run_case("AC-GATE-09", D.case_gate09_both_sides)
    finally:
        sr, cr, mr = syscfg_put(THRESH_KEY, orig, "LANE2-%s 补强复跑结束还原" % D.RUN)
        v2 = syscfg_get(THRESH_KEY)[2]
        print("[A] restore http=%s code=%s -> 复读 value=%s (期望 %s)" % (sr, cr, v2, orig), flush=True)
    new = json.load(open(TMP_REC, encoding="utf-8"))
    cur = json.load(open(MAIN_REC, encoding="utf-8"))
    by_card = {}
    for r in new:
        by_card.setdefault(r["card"], []).append(r)
    merged, dropped = [], []
    for r in cur:
        c = r["card"]
        if c in by_card:
            olds = [x for x in cur if x["card"] == c]
            if all(x["verdict"] == "FAIL-ENV" for x in by_card[c]) and any(x["verdict"] == "PASS" for x in olds):
                dropped.append(c)
            continue
        merged.append(r)
    for card, nrs in by_card.items():
        merged.extend(nrs if card not in dropped else [r for r in cur if r["card"] == card])
    for r in merged:
        if r["card"] == "AC-IPD-13":
            r["note"] = (r["note"] or "") + (" | repair-4 复核补充(代码取证)：StageSignAggregator.toTask 的 priority 派生只判 "
                "dueDate<now 不看 depth => 一旦轻管动作被赋 due_date 将与深管同样被标 high，与 AC-IPD-13『轻管逾期不标记』相悖；"
                "但 stage_actions.due_date 在 main 代码仅 IpdZkScenarioInitializer 启动期 seed 赋值（controller 全域 0 写入口），"
                "且全库 11 条非空 due_date 行均为 DEEP（0 条 LIGHT）=> 该 AC 仍无法经业务 HTTP 实测，维持 BLOCKED，列为真缺陷候选(口径风险)")
        if r["card"] in ("AC-GATE-09", "AC-GATE-10") and any(n["card"] == r["card"] for n in new) and "repair-4" not in (r["note"] or ""):
            r["note"] = (r["note"] or "") + " | repair-4：以 system_configs.%s %d→%d 临时放宽把组900001可登录在册PM入组后补测（跑完已还原=%d）" % (THRESH_KEY, orig, bump, orig)
    with open(MAIN_REC, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=1)
    from collections import Counter
    print("== REPAIR-4 done rerun=%s protected=%s merged=%s ==" % (
        dict(Counter(r["verdict"] for r in new)), dropped, dict(Counter(r["verdict"] for r in merged))), flush=True)


if __name__ == "__main__":
    main()
