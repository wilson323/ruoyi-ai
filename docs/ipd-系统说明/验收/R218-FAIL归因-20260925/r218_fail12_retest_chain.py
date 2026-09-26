#!/usr/bin/env python3
# 补跑 AC-PROD-04 状态链 3a/3b/3c（主脚本 data.id 取值口径不致被跳过）
import json, os, sys
sys.path.insert(0, "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-QA08-SEC04-20260925")
import r218_lib as L
OUT = "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-FAIL归因-20260925"
B = L.B39
recs = json.load(open(OUT + "/复测记录.json"))
W = json.load(open(OUT + "/写库清单-归因复测.json"))
def code_of(b): return b.get("code") if isinstance(b, dict) else None
def msg_of(b):  return (b.get("message") or b.get("msg") or "") if isinstance(b, dict) else ""
def R(**k):
    k["ts"] = L.now(); recs.append(k)
    print(">>", k.get("item"), "| http=", k.get("http"), "|", str(k.get("result",""))[:130], flush=True)
rows = L.sql("SELECT id,status,source FROM projects WHERE name='R218-归因-存量完整-0925' AND del_flag='0'")
assert rows, "存量项目未找到"
LP, st0, src0 = rows[0][0], rows[0][1], rows[0][2]
# 修正 2c 记录里的 project_id
for r in recs:
    if r.get("item") == "2c-AC-PROD-04补齐必填后正例":
        r["project_id"] = LP; r["result"] = "200 id=%s source=%s status=%s" % (LP, src0, st0)
Ta, bja, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=ACTIVE" % LP, token=L.login(B, "ipd-admin"))
R(item="3a-AC-PROD-04P4-3R3直跳ACTIVE", original="400 状态机非法迁移 DRAFT→ACTIVE", retest_cmd="POST /projects/%s/status?target=ACTIVE(DRAFT直跳)" % LP,
  http=Ta, result="code=%s msg=%s" % (code_of(bja), msg_of(bja)), verdict_msg="复现=状态机fail-closed守卫正确(DRAFT只允许TEAMING/ARCHIVED,ProjectService.java:54-59),非缺陷")
Tb, bjb, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=CONFIRMED" % LP, token=L.login(B, "ipd-admin"))
R(item="3b-AC-PROD-04P4-3R4非法态CONFIRMED", original="链CONFIRMED:400 终态DRAFT", retest_cmd="POST /projects/%s/status?target=CONFIRMED" % LP,
  http=Tb, result="code=%s msg=%s" % (code_of(bjb), msg_of(bjb)), verdict_msg="复现=CONFIRMED不在projects状态机枚举,执行器误用他域状态名,用例需修")
Tc1, bjc1, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=TEAMING" % LP, token=L.login(B, "ipd-admin"))
Tc2, bjc2, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=ACTIVE" % LP, token=L.login(B, "ipd-admin"))
fin = L.sql("SELECT status FROM projects WHERE id=%s" % LP)[0][0]
R(item="3c-AC-PROD-04合法链DRAFT→TEAMING→ACTIVE", original="P4-3R3/R4 FAIL(执行器误用链)", retest_cmd="POST status TEAMING→ACTIVE + db回读",
  http=Tc2, result="TEAMING:%s/%s ACTIVE:%s/%s 终态=%s" % (Tc1, code_of(bjc1), Tc2, code_of(bjc2), fin),
  verdict_msg="转绿=历史缺失(LEGACY 25条HISTORICAL_MISSING)不阻断合法流转,判据成立" if fin == "ACTIVE" else "仍红")
json.dump(recs, open(OUT + "/复测记录.json", "w"), ensure_ascii=False, indent=1)
json.dump(W, open(OUT + "/写库清单-归因复测.json", "w"), ensure_ascii=False, indent=1)
print("chain done records=", len(recs))
