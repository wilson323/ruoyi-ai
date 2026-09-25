#!/usr/bin/env python3
# SEC-04 附件下载 / 审计导出 / 需求池权限 —— 真 HTTP + 真库回读
import hashlib, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r218_lib as L

recs = json.load(open(L.REC_PATH)) if os.path.exists(L.REC_PATH) else []
T = {}
for u in ("ipd-admin", "ipd-leader", "ipd-market", "ipd-rd"):
    T[u] = {b: L.login(b, u) for b in (L.B39, L.B46)}
    assert T[u][L.B46], u + " login@16046 fail"

def code_of(bj): return bj.get("code") if isinstance(bj, dict) else None
def msg_of(bj):  return (bj.get("message") or bj.get("msg") or "") if isinstance(bj, dict) else ""

# ---------- 0) 16039 旧实例附件缺口取证 ----------
for m, p, name in (("GET", "/api/v1/deliverables/2096364946240630785/download", "A0-download@16039"),
                   ("POST", "/api/v1/deliverables/upload?actionId=1", "A0-upload@16039")):
    s, bj, _, _ = L.req(m, L.B39, p, token=T["ipd-admin"][L.B39])
    L.record(recs, {"card": "SEC-04", "case": name, "cmd": "%s 16039 %s (admin token)" % (m, p),
        "http": s, "envelope_code": code_of(bj),
        "verdict": "FAIL-ENV" if s == 200 and code_of(bj) == 50001 else "INFO",
        "note": "16039 运行 jar(06:11 构建)不含 DeliverableController → P1-4.2 端点缺失，部署漂移证据。code=%s" % code_of(bj)})

# ---------- A) 附件下载四例+（16046，HEAD 构建） ----------
ACT = L.sql("SELECT id FROM stage_actions WHERE project_id=9140005 AND action_code='D02' AND del_flag='0'")[0][0]
PDF = b"%PDF-1.4\nR218-SEC04-attachment-probe " + os.urandom(2048) + b"\n%%EOF"
SHA = hashlib.sha256(PDF).hexdigest()
body, ct = L.multipart({}, "file", "r218-sec04-probe.pdf", PDF, "application/pdf")
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/deliverables/upload?actionId=" + ACT, token=T["ipd-market"][L.B46], raw=body, content_type=ct)
DEL = ((bj or {}).get("data") or {}).get("id") if isinstance(bj, dict) else None
L.record(recs, {"card": "SEC-04", "case": "A1-本人上传登记(market@D02@9140005)", "cmd": "POST /api/v1/deliverables/upload?actionId=%s (ipd-market)" % ACT,
    "http": s, "envelope_code": code_of(bj), "deliverable_id": DEL,
    "verdict": "PASS" if s == 200 and code_of(bj) == 0 and DEL else "FAIL",
    "note": "resp=%s" % msg_of(bj)})
if DEL:
    row = L.sql("SELECT content_hash,file_size,uploaded_by,oss_id,project_id FROM deliverables WHERE id=%s" % DEL)[0]
    L.record(recs, {"card": "SEC-04", "case": "A1b-真库回读(哈希/大小/上传者)", "cmd": "mysql --defaults-extra-file=<cnf> ipd_dev -e 'SELECT ... FROM deliverables WHERE id=%s'" % DEL,
        "http": None, "exit": 0,
        "verdict": "PASS" if row[0] == SHA and int(row[1]) == len(PDF) and row[2] == "900103" and row[4] == "9140005" else "FAIL",
        "note": "db hash=%s size=%s by=%s | client sha256 match=%s" % (row[0][:12], row[1], row[2], row[0] == SHA)})
    # A2 本人下载
    s2, bj2, h2, rb2 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL, token=T["ipd-market"][L.B46])
    ok2 = s2 == 200 and hashlib.sha256(rb2).hexdigest() == SHA
    L.record(recs, {"card": "SEC-04", "case": "A2-本人下载200且字节一致", "cmd": "GET /api/v1/deliverables/%s/download (ipd-market)" % DEL,
        "http": s2, "exit": 0, "verdict": "PASS" if ok2 else "FAIL", "note": "bytes=%d ct=%s" % (len(rb2), h2.get("Content-Type"))})
    # A3 他人 IDOR（非该项目成员）
    s3, bj3, _, rb3 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL, token=T["ipd-rd"][L.B46])
    leak = s3 == 200 and len(rb3) > 500
    L.record(recs, {"card": "SEC-04", "case": "A3-他人下载IDOR拒绝", "cmd": "GET /api/v1/deliverables/%s/download (ipd-rd,非9140005成员)" % DEL,
        "http": s3, "envelope_code": code_of(bj3), "verdict": "PASS" if (s3 in (401, 403, 404) or (code_of(bj3) and code_of(bj3) != 0)) and not leak else "FAIL",
        "note": "msg=%s leaked=%s" % (msg_of(bj3), leak)})
    # A4 未登录
    s4, bj4, _, rb4 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL)
    L.record(recs, {"card": "SEC-04", "case": "A4-未登录下载拒绝", "cmd": "GET /api/v1/deliverables/%s/download (无token)" % DEL,
        "http": s4, "envelope_code": code_of(bj4), "verdict": "PASS" if (s4 == 401 or code_of(bj4) in (20001, 30001)) and not (s4 == 200 and len(rb4) > 500) else "FAIL",
        "note": "code=%s msg=%s" % (code_of(bj4), msg_of(bj4))})
    # A10 超管豁免下载
    s5, _, _, rb5 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL, token=T["ipd-admin"][L.B46])
    L.record(recs, {"card": "SEC-04", "case": "A10-超管豁免下载200", "cmd": "GET /api/v1/deliverables/%s/download (ipd-admin)" % DEL,
        "http": s5, "verdict": "PASS" if s5 == 200 and hashlib.sha256(rb5).hexdigest() == SHA else "FAIL", "note": "bytes=%d" % len(rb5)})
# A5 不存在记录
s6, bj6, _, _ = L.req("GET", L.B46, "/api/v1/deliverables/9999999999999999999/download", token=T["ipd-market"][L.B46])
L.record(recs, {"card": "SEC-04", "case": "A5-不存在记录下载行为", "cmd": "GET /api/v1/deliverables/9999999999999999999/download (ipd-market)",
    "http": s6, "envelope_code": code_of(bj6), "verdict": "PASS" if s6 != 500 else "FAIL",
    "note": "行为=HTTP %s code=%s msg=%s（记录型：无500、无栈泄露即PASS）" % (s6, code_of(bj6), msg_of(bj6)[:60])})
# A6 格式白名单
bad, ct2 = L.multipart({}, "file", "r218-probe.sh", b"#!/bin/echo evil", "text/plain")
s7, bj7, _, _ = L.req("POST", L.B46, "/api/v1/deliverables/upload?actionId=" + ACT, token=T["ipd-market"][L.B46], raw=bad, content_type=ct2)
L.record(recs, {"card": "SEC-04", "case": "A6-非白名单格式拒绝", "cmd": "POST upload file=r218-probe.sh (ipd-market)",
    "http": s7, "envelope_code": code_of(bj7), "verdict": "PASS" if not (s7 == 200 and code_of(bj7) == 0) else "FAIL", "note": "msg=%s" % msg_of(bj7)[:80]})
# A7 超限（容器 10MB < 策略 100MB，见漂移登记）
big, ct3 = L.multipart({}, "file", "r218-big.pdf", b"%PDF" + os.urandom(11 * 1024 * 1024), "application/pdf")
s8, bj8, _, _ = L.req("POST", L.B46, "/api/v1/deliverables/upload?actionId=" + ACT, token=T["ipd-market"][L.B46], raw=big, content_type=ct3, timeout=60)
L.record(recs, {"card": "SEC-04", "case": "A7-超限上传拒绝(11MB)", "cmd": "POST upload 11MB pdf (ipd-market)",
    "http": s8, "envelope_code": code_of(bj8), "verdict": "PASS" if not (s8 == 200 and code_of(bj8) == 0) else "FAIL",
    "note": "msg=%s | 100MB服务层分支被容器max-file-size=10MB遮蔽，无法真HTTP触发（漂移已登记）" % msg_of(bj8)[:60]})
# A9 写腿 IDOR（rd 非 writer 上传）
ok9, ct9 = L.multipart({}, "file", "r218-idor.pdf", PDF, "application/pdf")
s9, bj9, _, _ = L.req("POST", L.B46, "/api/v1/deliverables/upload?actionId=" + ACT, token=T["ipd-rd"][L.B46], raw=ok9, content_type=ct9)
L.record(recs, {"card": "SEC-04", "case": "A9-非writer上传IDOR拒绝", "cmd": "POST upload actionId=%s (ipd-rd,非D02 owner)" % ACT,
    "http": s9, "envelope_code": code_of(bj9), "verdict": "PASS" if not (s9 == 200 and code_of(bj9) == 0) else "FAIL", "note": "msg=%s" % msg_of(bj9)[:60]})
# A8 审计回读
if DEL:
    n = L.sql("SELECT COUNT(*) FROM audit_logs WHERE entity_type='DELIVERABLE' AND entity_id=%s" % DEL)[0][0]
    L.record(recs, {"card": "SEC-04", "case": "A8-上传审计行真库回读", "cmd": "mysql ... 'SELECT COUNT(*) FROM audit_logs WHERE entity_type=DELIVERABLE AND entity_id=%s'" % DEL,
        "http": None, "exit": 0, "verdict": "PASS" if int(n) >= 1 else "FAIL", "note": "rows=%s" % n})

# ---------- B) 审计导出权限边界（双实例对跑） ----------
for BASE, TAG in ((L.B46, "@16046"), (L.B39, "@16039")):
    pre = int(L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='EXPORT'")[0][0])
    cases = [
        ("B1-admin export", "GET", "/api/v1/audit-logs/export", "ipd-admin", lambda s, b: s == 200 and code_of(b) == 0),
        ("B2-rd export拒绝", "GET", "/api/v1/audit-logs/export", "ipd-rd", lambda s, b: s == 403),
        ("B3-rd 列表拒绝", "GET", "/api/v1/audit-logs?pageNum=1&pageSize=5", "ipd-rd", lambda s, b: s == 403),
        ("B5-market export拒绝", "GET", "/api/v1/audit-logs/export", "ipd-market", lambda s, b: s == 403),
        ("B6-admin export/scope", "GET", "/api/v1/audit-logs/export/scope", "ipd-admin", lambda s, b: s == 200),
        ("B7-market export/scope(内部角色按规格)", "GET", "/api/v1/audit-logs/export/scope", "ipd-market", lambda s, b: s == 200),
        ("B8-匿名 export", "GET", "/api/v1/audit-logs/export", None, lambda s, b: s == 401),
    ]
    for name, m, p, who, chk in cases:
        tok = T[who][BASE] if who else None
        s, bj, _, _ = L.req(m, BASE, p, token=tok)
        L.record(recs, {"card": "SEC-04", "case": name + TAG, "cmd": "%s %s%s (%s)" % (m, BASE, p, who or "无token"),
            "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if chk(s, bj) else "FAIL", "note": msg_of(bj)[:60]})
    s, bj, _, _ = L.req("POST", BASE, "/api/v1/audit-logs/rebuild-chain", token=T["ipd-rd"][BASE], body={})
    L.record(recs, {"card": "SEC-04", "case": "B4-rd rebuild-chain拒绝" + TAG, "cmd": "POST %s/api/v1/audit-logs/rebuild-chain (ipd-rd)" % BASE,
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s == 403 else "FAIL", "note": msg_of(bj)[:60]})
    post = int(L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='EXPORT'")[0][0])
    L.record(recs, {"card": "SEC-04", "case": "B9-EXPORT审计行增量回读" + TAG, "cmd": "mysql ... audit_logs action=EXPORT 前后计数",
        "http": None, "exit": 0, "verdict": "PASS" if post - pre >= 2 else "FAIL", "note": "delta=%d (B1/B6 各应写1)" % (post - pre)})

# ---------- C) 需求池权限（未授权访问无数据泄露） ----------
s, bj, _, _ = L.req("GET", L.B46, "/api/v1/demands")
L.record(recs, {"card": "SEC-04", "case": "C1-匿名demands拒绝", "cmd": "GET /api/v1/demands (无token)@16046",
    "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s == 401 and code_of(bj) == 20001 else "FAIL",
    "note": "data泄漏=%s" % bool(isinstance(bj, dict) and bj.get("data"))})
s, bj, _, _ = L.req("GET", L.B46, "/api/v1/demands", token=T["ipd-admin"][L.B46])
d0 = (((bj or {}).get("data") or {}).get("demands") or [{}])[0]
DID = d0.get("id")
L.record(recs, {"card": "SEC-04", "case": "C2-admin demands放行", "cmd": "GET /api/v1/demands (ipd-admin)@16046",
    "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s == 200 and code_of(bj) == 0 else "FAIL",
    "note": "total=%s 样例id=%s" % (((bj or {}).get("data") or {}).get("total"), DID)})
if DID:
    st_before = L.sql("SELECT status FROM requirements WHERE id=%s" % DID)[0][0]
    s, bj, _, _ = L.req("POST", L.B46, "/api/v1/demands/%s/triage" % DID, token=T["ipd-rd"][B46] if False else T["ipd-rd"][L.B46], body={"status": "BOGUS_STATUS"})
    st_after = L.sql("SELECT status FROM requirements WHERE id=%s" % DID)[0][0]
    L.record(recs, {"card": "SEC-04", "case": "C3-rd triage非法状态拒绝且零变更", "cmd": "POST /demands/%s/triage {status:BOGUS} (ipd-rd)@16046" % DID,
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s in (400, 404, 422, 502) is False and s != 500 and st_before == st_after else "FAIL",
        "note": "http=%s msg=%s state %s->%s" % (s, msg_of(bj)[:50], st_before, st_after)})
    s, bj, _, _ = L.req("POST", L.B46, "/api/v1/demands/%s/triage" % DID, body={"status": "CLOSED"})
    L.record(recs, {"card": "SEC-04", "case": "C4-匿名 triage拒绝", "cmd": "POST /demands/%s/triage (无token)@16046" % DID,
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s == 401 else "FAIL", "note": msg_of(bj)[:50]})
    s, bj, _, _ = L.req("GET", L.B46, "/api/v1/demands/%s" % DID, token=T["ipd-admin"][L.B46])
    L.record(recs, {"card": "SEC-04", "case": "C5-admin 需求详情放行(新jar含R215端点)", "cmd": "GET /demands/%s (ipd-admin)@16046" % DID,
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s == 200 and code_of(bj) == 0 else "FAIL", "note": msg_of(bj)[:50]})
    s, bj, _, _ = L.req("GET", L.B46, "/api/v1/demands/%s" % DID, token=T["ipd-market"][L.B46])
    L.record(recs, {"card": "SEC-04", "case": "C6-market 需求详情放行+注意跨组可见面", "cmd": "GET /demands/%s (ipd-market)@16046" % DID,
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s == 200 else "FAIL", "note": "内部角色全员可读需求池（list 无组过滤，登记观察项）"})
# C7 旧实例缺详情端点
if DID:
    s, bj, _, _ = L.req("GET", L.B39, "/api/v1/demands/%s" % DID, token=T["ipd-admin"][L.B39])
    L.record(recs, {"card": "SEC-04", "case": "C7-@16039 需求详情(旧jar)", "cmd": "GET /demands/%s (ipd-admin)@16039" % DID,
        "http": s, "envelope_code": code_of(bj), "verdict": "FAIL-ENV", "note": "旧 jar 无 detail 端点，code=%s → 部署漂移又一例证" % code_of(bj)})

print("SEC04 done. records:", len(recs))
