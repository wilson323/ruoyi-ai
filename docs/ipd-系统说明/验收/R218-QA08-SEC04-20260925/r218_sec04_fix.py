#!/usr/bin/env python3
# R218 SEC-04 A链重跑（根因修复：minio容器 Exited(255) 2 weeks ago -> docker start minio, health=200）
# 同时修正 C3 断言式（首轮为执行器 Python bug：链式比较恒 False 误判 FAIL）
import hashlib, json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r218_lib as L

recs = json.load(open(L.REC_PATH)) if os.path.exists(L.REC_PATH) else []

def code_of(bj): return bj.get("code") if isinstance(bj, dict) else None
def msg_of(bj):  return (bj.get("message") or bj.get("msg") or "") if isinstance(bj, dict) else ""

L.record(recs, {"card": "SEC-04", "case": "A0b-上传挂起根因=minio容器停摆(已恢复)",
    "cmd": "docker ps -a | grep minio; docker start minio; curl -m5 http://127.0.0.1:9000/minio/health/live",
    "http": None, "exit": 0, "verdict": "PASS",
    "note": "16046日志: OssException 'not made within 120 seconds of doBlockingWrite' @OssClient.upload:209; "
            "sys_oss_config endpoint=http://127.0.0.1:9000 但端口无监听; minio容器 Exited(255) 2 weeks ago; "
            "启动后 health=200。属环境故障非代码缺陷(但暴露: OSS不可达时上传占住120s且返回HTTP500,规整度待查)"})

T = {u: L.login(L.B46, u) for u in ("ipd-admin", "ipd-market", "ipd-rd")}

ACT = L.sql("SELECT id FROM stage_actions WHERE project_id=9140005 AND action_code='D02' AND del_flag='0'")[0][0]
PDF = b"%PDF-1.4\nR218-SEC04-attachment-probe " + os.urandom(2048) + b"\n%%EOF"
SHA = hashlib.sha256(PDF).hexdigest()

body, ct = L.multipart({}, "file", "r218-sec04-probe.pdf", PDF, "application/pdf")
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/deliverables/upload?actionId=" + ACT, token=T["ipd-market"], raw=body, content_type=ct, timeout=90)
DEL = ((bj or {}).get("data") or {}).get("id") if isinstance(bj, dict) else None
L.record(recs, {"card": "SEC-04", "case": "A1R-本人上传登记(market@D02@9140005)", "cmd": "POST /api/v1/deliverables/upload?actionId=%s (ipd-market)@16046" % ACT,
    "http": s, "envelope_code": code_of(bj), "deliverable_id": DEL,
    "verdict": "PASS" if s == 200 and code_of(bj) == 0 and DEL else "FAIL", "note": "msg=%s" % msg_of(bj)[:80]})

if DEL:
    row = L.sql("SELECT content_hash,file_size,uploaded_by,project_id,oss_id FROM deliverables WHERE id=%s" % DEL)[0]
    L.record(recs, {"card": "SEC-04", "case": "A1bR-真库回读(哈希/大小/上传者)", "cmd": "mysql --defaults-extra-file=<cnf> ipd_dev 'SELECT content_hash,file_size,uploaded_by,project_id,oss_id FROM deliverables WHERE id=%s'" % DEL,
        "http": None, "exit": 0,
        "verdict": "PASS" if row[0] == SHA and int(row[1]) == len(PDF) and str(row[2]) == "900103" and str(row[3]) == "9140005" else "FAIL",
        "note": "db hash==client sha256:%s size=%s by=%s proj=%s oss=%s" % (row[0] == SHA, row[1], row[2], row[3], row[4])})
    s2, _, h2, rb2 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL, token=T["ipd-market"])
    L.record(recs, {"card": "SEC-04", "case": "A2R-本人下载200且字节一致", "cmd": "GET /api/v1/deliverables/%s/download (ipd-market)@16046" % DEL,
        "http": s2, "exit": 0, "verdict": "PASS" if s2 == 200 and hashlib.sha256(rb2).hexdigest() == SHA else "FAIL",
        "note": "bytes=%d ct=%s sha_match=%s" % (len(rb2), h2.get("Content-Type"), hashlib.sha256(rb2).hexdigest() == SHA)})
    s3, bj3, _, rb3 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL, token=T["ipd-rd"])
    leak = s3 == 200 and len(rb3) > 500
    L.record(recs, {"card": "SEC-04", "case": "A3R-他人下载IDOR拒绝", "cmd": "GET /api/v1/deliverables/%s/download (ipd-rd,非成员)@16046" % DEL,
        "http": s3, "envelope_code": code_of(bj3), "verdict": "PASS" if (s3 in (401, 403, 404) or (code_of(bj3) not in (0, None))) and not leak else "FAIL",
        "note": "msg=%s leaked=%s" % (msg_of(bj3)[:60], leak)})
    s4, bj4, _, rb4 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL)
    L.record(recs, {"card": "SEC-04", "case": "A4R-未登录下载拒绝", "cmd": "GET /api/v1/deliverables/%s/download (无token)@16046" % DEL,
        "http": s4, "envelope_code": code_of(bj4), "verdict": "PASS" if (s4 == 401 or code_of(bj4) in (20001, 30001)) and not (s4 == 200 and len(rb4) > 500) else "FAIL",
        "note": "code=%s msg=%s" % (code_of(bj4), msg_of(bj4)[:60])})
    s5, _, _, rb5 = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % DEL, token=T["ipd-admin"])
    L.record(recs, {"card": "SEC-04", "case": "A10R-超管豁免下载200", "cmd": "GET /api/v1/deliverables/%s/download (ipd-admin)@16046" % DEL,
        "http": s5, "verdict": "PASS" if s5 == 200 and hashlib.sha256(rb5).hexdigest() == SHA else "FAIL", "note": "bytes=%d" % len(rb5)})
    n = L.sql("SELECT COUNT(*) FROM audit_logs WHERE entity_type='DELIVERABLE' AND entity_id=%s" % DEL)[0][0]
    L.record(recs, {"card": "SEC-04", "case": "A8R-上传审计行真库回读", "cmd": "mysql ... 'SELECT COUNT(*) FROM audit_logs WHERE entity_type=DELIVERABLE AND entity_id=%s'" % DEL,
        "http": None, "exit": 0, "verdict": "PASS" if int(n) >= 1 else "FAIL", "note": "audit rows=%s" % n})

s6, bj6, _, rb6 = L.req("GET", L.B46, "/api/v1/deliverables/900000000000000001/download", token=T["ipd-market"])
L.record(recs, {"card": "SEC-04", "case": "A5R-不存在记录下载行为", "cmd": "GET /api/v1/deliverables/900000000000000001/download (ipd-market)@16046",
    "http": s6, "envelope_code": code_of(bj6), "verdict": "PASS" if s6 != 500 and b"Exception" not in rb6[:400] else "FAIL",
    "note": "HTTP %s code=%s msg=%s (无500、无栈泄露即PASS)" % (s6, code_of(bj6), msg_of(bj6)[:60])})

d0 = L.sql("SELECT id FROM requirements ORDER BY id DESC LIMIT 1")[0][0]
st_before = L.sql("SELECT status FROM requirements WHERE id=%s" % d0)[0][0]
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/demands/%s/triage" % d0, token=T["ipd-rd"], body={"status": "BOGUS_STATUS"})
st_after = L.sql("SELECT status FROM requirements WHERE id=%s" % d0)[0][0]
L.record(recs, {"card": "SEC-04", "case": "C3R-rd triage非法状态拒绝且零变更(修正断言)", "cmd": "POST /demands/%s/triage {status:BOGUS_STATUS} (ipd-rd)@16046" % d0,
    "http": s, "envelope_code": code_of(bj),
    "verdict": "PASS" if (s in (400, 422)) and st_before == st_after else "FAIL",
    "note": "http=%s msg=%s state %s->%s | 首轮C3为执行器链式比较bug误判FAIL" % (s, msg_of(bj)[:50], st_before, st_after)})

print("done. DEL =", DEL)
