#!/usr/bin/env python3
# 一次性可行性探针：临时放宽 allowance.projectCountThreshold 后，被占满槽位的 ACTIVE PM 能否入组。
import sys, os, json
sys.path.insert(0, ".")
import r219_lib as L
if os.path.exists(L.WRITTEN_PATH):
    L.WRITTEN.extend(json.load(open(L.WRITTEN_PATH, encoding="utf-8")))
import r219_lane2_gate_ipd as D   # 只用工厂函数，不写执行记录（RECS 不落盘）
KEY = "allowance.projectCountThreshold"
D.setup()
def get():
    s, bj, _, _ = L.req("GET", L.B39, "/api/v1/system-configs/" + KEY, token=D.TOK[D.ADM])
    return s, D.ec(bj), (D.data(bj) or {}).get("value")
def put(v):
    s, bj, _, _ = L.req("PUT", L.B39, "/api/v1/system-configs/" + KEY, token=D.TOK[D.ADM],
                        body={"value": str(v), "reason": "LANE2-%s 阈值放宽可行性探针" % D.RUN})
    L.written({"card": "probe(非AC)", "op": "PUT /system-configs/%s=%s" % (KEY, v), "ts": L.now()})
    return s, D.ec(bj), D.msg(bj)
print("GET", get())
print("PUT5", put(5), "GET", get())
s, bj, pid, prod = D.mk_project("PROBE-THR")
print("mk_project", s, D.ec(bj), pid)
L.written({"card": "probe(非AC)", "op": "POST /projects LANE2-%s-PROBE-THR pid=%s" % (D.RUN, pid), "ts": L.now()})
sb, bb = D.bind_member(pid, D.P_M1, "MARKET_PM")
print("bind 9110005", sb, D.ec(bb), D.msg(bb)[:120])
L.written({"card": "probe(非AC)", "op": "POST /projects/%s/members bind 9110005 (probe)" % pid, "ts": L.now()})
sb2, bb2 = D.bind_member(pid, D.P_M2, "MARKET_PM")
print("bind 陈市场", sb2, D.ec(bb2), D.msg(bb2)[:120])
print("restore", put(3), "GET", get())
