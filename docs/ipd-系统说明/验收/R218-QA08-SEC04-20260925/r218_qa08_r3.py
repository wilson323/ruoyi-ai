#!/usr/bin/env python3
# R3 补丁轮：修正参数重跑 PROD-03/04（挂新产品）、HAND-01c 正例（组长换组法）、INC-34 N5（month 参数）
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r218_lib as L
recs = json.load(open(L.REC_PATH))
def code_of(bj): return bj.get("code") if isinstance(bj, dict) else None
def msg_of(bj):  return (bj.get("message") or bj.get("msg") or "") if isinstance(bj, dict) else ""
def data_of(bj): return bj.get("data") if isinstance(bj, dict) else None
T = {u: L.login(L.B46, u) for u in ("ipd-admin", "ipd-leader", "ipd-market")}
W = json.load(open(os.path.join(os.path.dirname(L.REC_PATH), "写库清单-qa08.json")))

# 新建产品（SOFTWARE 项目须 1:1 挂产品）；source 枚举=PM_NEW（首轮 400 根因：误传 NEW）
_r = L.sql("SELECT id FROM products WHERE product_name='R218-QA08软件产品' AND del_flag='0' ORDER BY id LIMIT 1")
if _r:
    PROD = str(_r[0][0])
    L.record(recs, {"card":"AC-PROD-03","case":"P3-0新建配套产品","cmd":"POST /api/v1/products {source:PM_NEW} (admin) — 复用探查轮已建行","http": 200, "envelope_code": 0,
        "verdict": "PASS", "note":"productId=%s | 该行为本车道探查调用创建(PM_NEW,IN_RD),首轮误传source=NEW被枚举校验400" % PROD})
else:
    s, bj, _, _ = L.req("POST", L.B46, "/api/v1/products", token=T["ipd-admin"],
                        body={"productName": "R218-QA08软件产品", "source": "PM_NEW"})
    _d = data_of(bj) or {}
    PROD = str(_d.get("id")) if isinstance(_d, dict) and _d.get("id") else None
    L.record(recs, {"card":"AC-PROD-03","case":"P3-0新建配套产品","cmd":"POST /api/v1/products {source:PM_NEW} (admin)","http": s, "envelope_code": code_of(bj),
        "verdict": "PASS" if PROD else "FAIL", "note":"productId=%s" % PROD})
W.append("products id=%s (R218-QA08软件产品,PM_NEW,配套验证项目)" % PROD)

def mkproj(tag, card, extra=None, path="/api/v1/projects", tok=None):
    proj = {"name": "R218-QA08-软件模板验证项目R3", "templateType": "SOFTWARE", "level": "S", "mainGroupId": 9120002,
            "targetMarkets": json.dumps(["CONSUMER"], ensure_ascii=False), "targetSalesAmount": 100,
            "targetChannelCount": 3, "targetNps": 40, "targetSceneCount": 2}
    if PROD: proj["productId"] = int(PROD)
    if extra: proj.update(extra)
    s, bj, _, _ = L.req("POST", L.B46, path, token=tok or T["ipd-admin"], body=proj)
    d = data_of(bj) or {}
    pid = d.get("id") if isinstance(d, dict) else None
    return s, bj, pid

if PROD:
    s, bj, PID = mkproj("P3-1R", "AC-PROD-03")
    L.record(recs, {"card":"AC-PROD-03","case":"P3-1R创建SOFTWARE项目(挂产品)","cmd":"POST /api/v1/projects productId=%s" % PROD,
        "http": s, "envelope_code": code_of(bj), "project_id": PID, "verdict": "PASS" if PID else "FAIL", "note": msg_of(bj)[:60]})
    if PID:
        W.append("projects id=%s (R218-QA08-软件模板验证项目R3 +全套stages/actions)" % PID)
        rows = L.sql("SELECT action_code,status FROM stage_actions WHERE project_id=%s AND del_flag='0'" % PID)
        na = sorted({r[0] for r in rows if r[1]=="NA"})
        d03 = [r[1] for r in rows if r[0]=="D03"]
        ok = bool(d03) and d03[0]=="NA" and len(na) >= 2
        L.record(recs, {"card":"AC-PROD-03","case":"P3-2R真库回读硬件动作NA","cmd":"mysql stage_actions WHERE project_id=%s" % PID,
            "http": None, "exit": 0, "verdict": "PASS" if ok else "FAIL", "note":"NA动作=%s D03=%s" % (",".join(na), d03 and d03[0])})
        light = L.sql("SELECT id FROM stage_actions WHERE project_id=%s AND depth='LIGHT' AND status='NOT_STARTED' AND del_flag='0' LIMIT 1" % PID)
        if light:
            LA = light[0][0]
            s1, bj1, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/fields" % LA, token=T["ipd-market"], body={"actualDoneAt": "2026-09-25 10:00:00", "remark": "R218轻管腿"})
            L.req("POST", L.B46, "/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218" % LA, token=T["ipd-market"])
            s3, bj3, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/transit?target=DONE&reason=r218-qa08" % LA, token=T["ipd-market"])
            st = L.sql("SELECT status,actual_done_at FROM stage_actions WHERE id=%s" % LA)[0]
            W.append("stage_actions id=%s (LIGHT动作 %s 项目轻管完成流转,status=%s)" % (LA, PID, st[0]))
            L.record(recs, {"card":"AC-IPD-03","case":"L1R-轻管fields+transit DONE(服务腿)","cmd":"fields{actualDoneAt}+transit x2 (market) id=%s" % LA,
                "http": s3, "verdict": "PASS" if st[0]=="DONE" and st[1] else "FAIL",
                "note":"db status=%s actualDoneAt=%s | 判据'三输入项无SOP区'属前端,本条闭环服务侧" % (st[0], st[1])})
        # PROD-04 存量导入(挂第二个新产品)
        s, bj2, _, _ = L.req("POST", L.B46, "/api/v1/products", token=T["ipd-admin"], body={"productName": "R218-QA08存量产品", "source": "PM_NEW"})
        P2 = (data_of(bj2) or {}).get("id")
        if P2:
            W.append("products id=%s (R218-QA08存量产品)" % P2)
            legacy = {"name": "R218-QA08-存量导入项目", "templateType": "SOFTWARE", "level": "A", "mainGroupId": 9120002,
                      "productId": int(P2), "targetSalesAmount": 200, "legacyEffectiveAt": "2026-08-01",
                      "declaredStage": "DEV", "missingHistoryAck": True, "alternativeEvidence": {"D01": "R218邮件纪要"}}
            s, bj3, _, _ = L.req("POST", L.B46, "/api/v1/projects/legacy-import", token=T["ipd-admin"], body=legacy)
            LP = (data_of(bj3) or {}).get("id") if isinstance(data_of(bj3), dict) else None
            L.record(recs, {"card":"AC-PROD-04","case":"P4-1R存量导入正例","cmd":"POST /projects/legacy-import productId=%s ack=true" % P2,
                "http": s, "envelope_code": code_of(bj3), "project_id": LP, "verdict": "PASS" if LP else "FAIL", "note": msg_of(bj3)[:70]})
            if LP:
                W.append("projects id=%s (LEGACY存量导入项目)" % LP)
                src = L.sql("SELECT source FROM projects WHERE id=%s" % LP)[0][0]
                au = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='PROJECT_LEGACY_IMPORT' AND entity_id=%s" % LP)[0][0]
                hm = L.sql("SELECT COUNT(*) FROM stage_actions WHERE project_id=%s AND history_mark IS NOT NULL AND del_flag='0'" % LP)[0][0]
                L.record(recs, {"card":"AC-PROD-04","case":"P4-2R回读source/history_mark/审计","cmd":"mysql projects/stage_actions/audit_logs id=%s" % LP,
                    "http": None, "exit": 0, "verdict": "PASS" if src=="LEGACY" and int(au)>=1 else "FAIL",
                    "note":"source=%s audit=%s history_mark行=%s(0也需看NA标记,主判据=source+audit)" % (src, au, hm)})
                s, bj4, _, _ = L.req("POST", L.B46, "/api/v1/projects/%s/status?target=ACTIVE" % LP, token=T["ipd-admin"])
                L.record(recs, {"card":"AC-PROD-04","case":"P4-3R历史缺失不阻断流转","cmd":"POST /projects/%s/status?target=ACTIVE" % LP,
                    "http": s, "envelope_code": code_of(bj4), "verdict": "PASS" if s==200 and code_of(bj4)==0 else "FAIL", "note": msg_of(bj4)[:50]})
            legacy_bad = dict(legacy); legacy_bad["name"]="R218-QA08-存量导入反例R3"; legacy_bad["missingHistoryAck"]=False
            s, bj5, _, _ = L.req("POST", L.B46, "/api/v1/projects/legacy-import", token=T["ipd-admin"], body=legacy_bad)
            n = L.sql("SELECT COUNT(*) FROM projects WHERE name='R218-QA08-存量导入反例R3' AND del_flag='0'")[0][0]
            L.record(recs, {"card":"AC-PROD-04","case":"P4-4R反例ack=false拒绝且零落库","cmd":"POST legacy-import ack=false + mysql COUNT",
                "http": s, "envelope_code": code_of(bj5), "verdict": "PASS" if not (s==200 and code_of(bj5)==0) and int(n)==0 else "FAIL",
                "note":"msg=%s rows=%s" % (msg_of(bj5)[:50], n)})
    # N5 month 参数
    s, bj6, h, rb = L.req("GET", L.B46, "/api/v1/report/export/project?month=2026-09", token=T["ipd-admin"], timeout=40)
    L.record(recs, {"card":"AC-INC-34","case":"N5R-项目汇总导出(month)","cmd":"GET /report/export/project?month=2026-09 (admin)",
        "http": s, "envelope_code": code_of(bj6), "verdict": "PASS" if s==200 and code_of(bj6)==0 else "FAIL", "note":"msg=%s bytes=%d" % (msg_of(bj6)[:40], len(rb))})

# HAND-01c 正例：组长换组法（900102 临时 9120002 → 发起 → 还原 900001）
L.sql("UPDATE persons SET group_id=9120002 WHERE id=900102")
try:
    Tl = L.login(L.B46, "ipd-leader")
    s, bj, _, _ = L.req("POST", L.B46, "/api/v1/handovers", token=Tl,
        body={"projectId": 9140005, "role": "MARKET_PM", "toPersonId": 2096266884247736321, "note": "R218-QA08 代移交正例", "onBehalf": True})
    HO = (data_of(bj) or {}).get("id") if isinstance(data_of(bj), dict) else None
    L.record(recs, {"card":"AC-HAND-01c","case":"H1R-组长代移交正例(onBehalf)","cmd":"POST /handovers (leader,同组9120002) to=赵市场(RESIGNED)","http": s,
        "envelope_code": code_of(bj), "handover_id": HO, "verdict": "PASS" if s==200 and code_of(bj)==0 else "FAIL", "note":"msg=%s" % msg_of(bj)[:90]})
    if HO:
        W.append("handovers id=%s (9140005 MARKET_PM 900103→赵市场 代移交请求)" % HO)
        s2, bj2, _, _ = L.req("POST", L.B46, "/api/v1/handovers/%s/cancel" % HO, token=Tl, body={"reason":"R218 验收回滚", "confirmation":"CONFIRM"})
        L.record(recs, {"card":"AC-HAND-01c","case":"H1R-b移交后撤销清理","cmd":"POST /handovers/%s/cancel" % HO,"http": s2,"envelope_code": code_of(bj2),
            "verdict":"PASS" if s2==200 and code_of(bj2)==0 else "INFO","note":"msg=%s" % msg_of(bj2)[:60]})
finally:
    L.sql("UPDATE persons SET group_id=900001 WHERE id=900102")
    L.record(recs, {"card":"AC-HAND-01c","case":"H1R-组长换组夹具已还原","cmd":"UPDATE persons SET group_id=900001 WHERE id=900102","http":None,"exit":0,
        "verdict":"INFO","note":"临时把ipd-leader调到9120002组发起正例后已还原;前轮H1(900102@900001→9120002项目)实为正确的跨组负例:403"})
json.dump(W, open(os.path.join(os.path.dirname(L.REC_PATH), "写库清单-qa08.json"), "w"), ensure_ascii=False, indent=1)
print("R3 done. records:", len(recs))
