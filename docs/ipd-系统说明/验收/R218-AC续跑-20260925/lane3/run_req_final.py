# -*- coding: utf-8 -*-
# REQ/PROD 收口补跑：游客提交配额窗口重置后，用 ACTIVE+有项目 产品 9130001(proj 9140001, 双PM在职) 重验
import json, sys
import r219_lib as L

B = L.BASE
RECS = json.load(open(L.REC_PATH))
SKIP = {r["case"] for r in RECS if r["card"].startswith(("AC-REQ", "AC-PROD"))}
P_ACT, PRJ_ACT = 9130001, 9140001

def rec(card, case, cmd, http, ec, verdict, note):
    if case in SKIP: print("skip dup", case); return
    L.record(RECS, {"card": card, "case": case, "cmd": cmd, "http": http,
                    "envelope_code": ec, "verdict": verdict, "note": note[:300]})

def submit(**kw):
    body = {"customerName": "LANE3-客户-" + kw.pop("_t", "f1"), "feedbackPerson": "LANE3-反馈人-x",
            "contact": "13900000001", "productId": P_ACT, "rawModel": "LANE3-M-F",
            "functionalRequirement": "补跑验证：ACTIVE有项目产品游客提交与双PM路由"}
    body.update(kw)
    s, b, _, _ = L.req("POST", B, "/api/v1/public/demands", body=body)
    return s, b

# 0) 额度探测：1 次真提交
s, b = submit(_t="probe")
code = L.code_of(b)
if code == 40011:
    print("STILL-RATE-LIMITED"); sys.exit(2)
d = L.data_of(b)
qc = d if isinstance(d, str) else (d.get("code") or d.get("queryCode") if isinstance(d, dict) else (d[0].get("queryCode") if isinstance(d, list) and d and isinstance(d[0], dict) else None))
if not qc:
    print("PROBE-FAIL", code, str(b)[:200]); sys.exit(3)
L.write_reg("guest-submit", "POST", "/api/v1/public/demands", "requirements", "REQ01 probe " + qc)

# 1) REQ-01 重验（正确产品：ACTIVE+有项目+双PM在职）
row = L.sql("SELECT status,project_id,market_pm_id,rd_pm_id,submitter_name FROM requirements WHERE query_code='%s'" % qc)
st, pj, mp, rp, sn = (row[0] + ["-"] * 5)[:5] if row else ("-",) * 5
rec("AC-REQ-01", "REQ01-guest-submit-active-product", "POST /public/demands productId=9130001", s, code,
    "PASS" if code == 0 and len(qc) == 8 else "FAIL",
    "免登录→8位码 %s；真库 status=%s project=%s(产品1:1带出) 双PM自动路由 mkt=%s rd=%s submitter=%s（前一条用IN_RD夹具属fixture假红）" % (qc, st, pj, mp, rp, sn))
rid = (L.sql("SELECT id FROM requirements WHERE query_code='%s'" % qc) or [["0"]])[0][0]
rec("AC-REQ-03", "REQ03-route-dual-pm-on-submit", "sql 核对 %s 自动路由" % qc, 200, 0,
    "PASS" if mp not in ("-", "NULL", "") and rp not in ("-", "NULL", "") and st == "SUBMITTED" else "FAIL",
    "AC-REQ-03 游客提交即路由：resolveDualPm 取项目 %s 在职 MARKET_PM=%s/RD_PM=%s 写双PM+routed_at；状态保持 SUBMITTED 等受理" % (PRJ_ACT, mp, rp))

# 2) trace 真码可读 + 脱敏
s2, b2, _, _ = L.req("GET", B, "/api/v1/public/demands/" + qc)
tv = L.data_of(b2) or {}
leak = any(k in json.dumps(tv, ensure_ascii=False) for k in ("marketPm", "rdPm", "contact", "备注", "remark"))
rec("AC-REQ-04", "REQ04-trace-real-code", "GET /public/demands/%s" % qc, s2, L.code_of(b2),
    "PASS" if L.code_of(b2) == 0 and isinstance(tv, dict) and not leak else "FAIL",
    "keys=%s 内部人员/联系方式泄露=%s" % (list(tv.keys()) if isinstance(tv, dict) else tv, leak))

# 3) PROD-08：productId=null『其他/不确定』（若还有额度）
s3, b3 = submit(_t="other", productId=None, functionalRequirement="其他/不确定情形三：不选产品提交 AC-PROD-08")
d3 = L.data_of(b3)
qc8 = d3 if isinstance(d3, str) else (d3.get("code") or d3.get("queryCode") if isinstance(d3, dict) else (d3[0].get("queryCode") if isinstance(d3, list) and d3 and isinstance(d3[0], dict) else None))
if qc8:
    r8 = L.sql("SELECT status,IFNULL(market_pm_id,'NULL'),IFNULL(rd_pm_id,'NULL'),IFNULL(project_id,'NULL') FROM requirements WHERE query_code='%s'" % qc8)[0]
    rec("AC-PROD-08", "PROD08-null-product-no-route", "POST /public/demands productId=null", s3, L.code_of(b3),
        "PASS" if r8[1] == "NULL" and r8[2] == "NULL" else "FAIL",
        "不选产品不触发路由：真库 status=%s mkt=%s rd=%s project=%s；『待指派』词不达意——库内无 UNASSIGNED 态（全库 status 枚举=%s）" % (r8[0], r8[1], r8[2], r8[3],
            [x[0] for x in L.sql("SELECT status,COUNT(*) FROM requirements GROUP BY status")]))
    rec("AC-REQ-02", "REQ02-rate-limit-consumed-note", "配额账：本车道游客提交共 %s" % qc8, "-", "-", "PARTIAL",
        "REQ02-rate-limit-10per-hour 早前在 fixture 假红(40401)下顺带证得 40011 生效；本轮额度用于主链验证，限流精确第11次口径不再重复消耗（兄弟车道注意：127.0.0.1 提交池 1h 滑窗）")
else:
    rec("AC-PROD-08", "PROD08-null-product-no-route", "POST /public/demands productId=null", s3, L.code_of(b3),
        "FAIL-ENV" if L.code_of(b3) == 40011 else "FAIL",
        "本条被本车道探测消耗后的限流拦截 code=%s；不路由行为已由 PROD08-words 记录（productId=9130006 无项目 同口径）" % L.code_of(b3))

# 4) REQ-06/REQ-08 主链：triage→link-project→变更单双签
AD = L.login(B, "ipd-admin")
s, b, _, _ = L.req("POST", B, "/api/v1/demands/%s/triage" % rid, token=AD, body={"status": "ACCEPTED"})
L.write_reg("triage", "POST", "/api/v1/demands/%s/triage" % rid, "requirements", "REQ06")
rec("AC-REQ-06", "REQ06-triage-accepted", "POST /demands/%s/triage ACCEPTED" % rid, s, L.code_of(b),
    "PASS" if L.code_of(b) == 0 else "FAIL", "受理=仅改状态（双PM已由提交路由齐）msg=%s" % L.msg_of(b)[:60])
s, b, _, _ = L.req("POST", B, "/api/v1/demands/%s/link-project" % rid, token=AD, body={"projectId": PRJ_ACT})
st = (L.sql("SELECT status FROM requirements WHERE id=%s" % rid) or [["?"]])[0][0]
L.write_reg("link-project", "POST", "/api/v1/demands/%s/link-project" % rid, "requirements", "REQ06")
rec("AC-REQ-06", "REQ06-link-project-scheduled", "POST /demands/%s/link-project %s" % (rid, PRJ_ACT), s, L.code_of(b),
    "PASS" if L.code_of(b) == 0 and st == "SCHEDULED" else "FAIL", "已受理+双PM齐→关联后置 SCHEDULED 真库=%s" % st)
SNAP = json.dumps({"范围": "LANE3-F", "成本": "LANE3-F", "时限": "LANE3-F", "质量": "LANE3-F"}, ensure_ascii=False)
s, b, _, _ = L.req("POST", B, "/api/v1/requirement-changes", token=AD,
                   body={"requirementId": int(rid), "changeType": "SCOPE", "reason": "LANE3-REQ08双签",
                         "beforeSnapshot": SNAP, "afterSnapshot": SNAP})
cid = (L.data_of(b) or {}).get("id") if isinstance(L.data_of(b), dict) else None
L.write_reg("req-change", "POST", "/api/v1/requirement-changes", "requirement_changes", "LANE3-REQ08 id=%s" % cid)
if cid:
    s2, b2, _, _ = L.req("PUT", B, "/api/v1/requirement-changes/%s/submit" % cid, token=AD)
    st2 = (L.sql("SELECT status FROM requirement_changes WHERE id=%s" % cid) or [["?"]])[0][0]
    rec("AC-REQ-08", "REQ08-create-submit", "POST create+PUT submit id=%s" % cid, "%s/%s" % (s, s2), L.code_of(b2),
        "PASS" if st2 == "PENDING_SIGN" else "FAIL", "四维度快照(范围/成本/时限/质量)齐→DRAFT→PENDING_SIGN 真库=%s" % st2)
    MK = L.login(B, "ipd-market")
    s3, b3, _, _ = L.req("PUT", B, "/api/v1/requirement-changes/%s/sign?decision=REJECT&opinion=LANE3" % cid, token=MK)
    st3 = (L.sql("SELECT status,signatures FROM requirement_changes WHERE id=%s" % cid) or [["?", "?"]])[0]
    rec("AC-REQ-08", "REQ08-reject-shortcut", "PUT sign REJECT (MARKET_PM)", s3, L.code_of(b3),
        "PASS" if st3[0] == "REJECTED" else "FAIL", "任一否决⇒整体REJECTED：status=%s signatures=%s" % (st3[0], st3[1]))
    s4, b4, _, _ = L.req("PUT", B, "/api/v1/requirement-changes/%s/sign?decision=APPROVE" % cid, token=L.login(B, "ipd-rd"))
    rec("AC-REQ-08", "REQ08-sign-on-terminal", "PUT sign on REJECTED", s4, L.code_of(b4),
        "PASS" if L.code_of(b4) not in (0, None) else "FAIL", "终态再签拒 code=%s msg=%s" % (L.code_of(b4), L.msg_of(b4)[:60]))
else:
    rec("AC-REQ-08", "REQ08-create-submit", "POST /requirement-changes", s, L.code_of(b), "FAIL",
        "创建失败 http=%s code=%s msg=%s" % (s, L.code_of(b), L.msg_of(b)[:150]))
print("REQ-FINAL done qc=%s rid=%s" % (qc, rid))
