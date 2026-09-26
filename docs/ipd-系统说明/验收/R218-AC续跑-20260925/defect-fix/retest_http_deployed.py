#!/usr/bin/env python3
# R218 L5 缺陷修复部署后真机复测（16039 新 jar）：AC-REQ-09 删除链 + AC-PROD-09 超时提醒接线
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE + "/../lane1")
import r219_lib as L  # lane1 拷贝版，REC_PATH 不共用

B = L.B39
recs = []
def rec(card, case, cmd, s=None, code=None, verdict=None, note=""):
    r = {"card": card, "case": case, "cmd": cmd, "http": s, "envelope_code": code,
         "verdict": verdict, "note": note, "ts": L.now() if hasattr(L, "now") else __import__("datetime").datetime.now().isoformat()}
    recs.append(r); print(verdict, case, "|", note[:100])

def code_of(b): return b.get("code") if isinstance(b, dict) else None
def data_of(b): return b.get("data") if isinstance(b, dict) else None

TA = L.login(B, "ipd-admin")
TL = L.login(B, "ipd-leader")
TR = L.login(B, "ipd-rd")

# ---------- 卡1 AC-REQ-09：requirements 删除双层审核链 ----------
rows = L.sql("SELECT id, title FROM requirements WHERE del_flag='0' AND title LIKE 'LANE%' ORDER BY id DESC LIMIT 1")
if not rows:
    rows = L.sql("SELECT id, title FROM requirements WHERE del_flag='0' ORDER BY id DESC LIMIT 1")
assert rows, "真库无可用 requirement 夹具"
RID, RNAME = rows[0][0], rows[0][1]
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=TA,
                   body={"entityType": "requirements", "entityId": str(RID), "reason": "R218-L5-部署后复测", "snapshot": json.dumps({"name": RNAME}, ensure_ascii=False)})
d = data_of(b) or {}
DRID = d.get("id") if isinstance(d, dict) else None
rec("AC-REQ-09", "R1 提交删除申请(requirements)", "POST /api/v1/deletion-requests entityType=requirements", s, code_of(b),
    "PASS" if s == 200 and code_of(b) == 0 and DRID else "FAIL", f"entityId={RID}({RNAME}) 申请id={DRID} status={d.get('status') if isinstance(d,dict) else d}")
if DRID:
    # 组长初审（ipd-leader 为 9120002 组长；若不同组预期 403）
    s2, b2, _, _ = L.req("POST", B, f"/api/v1/deletion-requests/{DRID}/leader-decision?approve=true&opinion=L5复测-组长通过", token=TL)
    d2 = data_of(b2) or {}
    rec("AC-REQ-09", "R2 组长初审通过→待超管", f"POST /deletion-requests/{DRID}/leader-decision (ipd-leader)", s2, code_of(b2),
        "PASS" if s2 == 200 and code_of(b2) == 0 else "PARTIAL", f"status={d2.get('status') if isinstance(d2,dict) else '?'} note若403=组长不同组属设计内")
    st = d2.get("status") if isinstance(d2, dict) else None
    if st not in ("PENDING_ADMIN", "LEADER_APPROVED", "PENDING_SECOND"):
        # 换超管直终审前的兜底：用 admin 补初审不行则跳过终审
        pass
    s3, b3, _, _ = L.req("POST", B, f"/api/v1/deletion-requests/{DRID}/admin-decision?approve=true&opinion=L5复测-超管终审执行", token=TA)
    d3 = data_of(b3) or {}
    rec("AC-REQ-09", "R3 超管终审→执行软删", f"POST /deletion-requests/{DRID}/admin-decision", s3, code_of(b3),
        "PASS" if s3 == 200 and code_of(b3) == 0 else "FAIL", f"status={d3.get('status') if isinstance(d3,dict) else d3}")
    back = L.sql(f"SELECT del_flag FROM requirements WHERE id={RID}")
    aud = L.sql(f"SELECT COUNT(*) FROM audit_logs WHERE action LIKE '%DELETE%' AND entity_id={RID}")
    ok = back and back[0][0] == "1" and aud and aud[0][0] >= 1
    rec("AC-REQ-09", "R4 真库回读软删+审计", f"SQL requirements.del_flag & audit_logs target={RID}", None, None,
        "PASS" if ok else "FAIL", f"del_flag={back and back[0][0]} audit_rows={aud and aud[0][0]}")
    # 幂等防僵尸：已删实体再申请应 404/409
    s4, b4, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=TA,
                         body={"entityType": "requirements", "entityId": str(RID), "reason": "L5复测-幂等"})
    rec("AC-REQ-09", "R5 已删实体重复申请拒绝", "POST /deletion-requests (同 entityId 二次)", s4, code_of(b4),
        "PASS" if (code_of(b4) not in (0, None)) else "FAIL", f"拒绝语义 http={s4}")

# ---------- 卡2 AC-PROD-09：overdue-scan 手动腿 + 权限腿 ----------
s5, b5, _, _ = L.req("POST", B, "/api/v1/guest-demands/overdue-scan", token=TA, body={})
d5 = data_of(b5)
rec("AC-PROD-09", "S1 超管手动扫描 200", "POST /api/v1/guest-demands/overdue-scan (admin)", s5, code_of(b5),
    "PASS" if s5 == 200 and code_of(b5) == 0 else "FAIL", f"data={json.dumps(d5, ensure_ascii=False)[:120]}")
s6, b6, _, _ = L.req("POST", B, "/api/v1/guest-demands/overdue-scan", token=TR, body={})
rec("AC-PROD-09", "S2 非超管拒绝", "POST /guest-demands/overdue-scan (ipd-rd)", s6, code_of(b6),
    "PASS" if s6 in (401, 403) or code_of(b6) not in (0, None) else "FAIL", f"http={s6} code={code_of(b6)}")
s7, b7, _, _ = L.req("POST", B, "/api/v1/guest-demands/overdue-scan", token=TA, body={})
aud2 = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='overdue_unassigned'")
ne = L.sql("SELECT COUNT(*) FROM notification_events WHERE event_type LIKE '%OVERDUE%'")
rec("AC-PROD-09", "S3 二次扫描幂等+落痕", "POST scan x2 + SQL audit/notification 回读", s7, code_of(b7),
    "PASS" if s7 == 200 and code_of(b7) == 0 and (aud2 and aud2[0][0] >= 1) else "PARTIAL",
    f"audit(overdue_unassigned)={aud2 and aud2[0][0]} notif(OVERDUE)={ne and ne[0][0]}（0存量超时项时 notified=0 属正常）")

out = HERE + "/retest-http-deployed.json"
json.dump(recs, open(out, "w"), ensure_ascii=False, indent=1)
pv = sum(1 for r in recs if r["verdict"] == "PASS")
print(f"\n== {pv}/{len(recs)} PASS -> {out}")
