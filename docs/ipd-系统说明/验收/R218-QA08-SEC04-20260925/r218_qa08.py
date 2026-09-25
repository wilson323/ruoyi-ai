#!/usr/bin/env python3
# QA-08 本轮可执行子集（历史「等前端16条」中服务层可闭环者 + 附件相关条目）
import json, os, sys, time, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r218_lib as L

recs = json.load(open(L.REC_PATH)) if os.path.exists(L.REC_PATH) else []
def code_of(bj): return bj.get("code") if isinstance(bj, dict) else None
def msg_of(bj):  return (bj.get("message") or bj.get("msg") or "") if isinstance(bj, dict) else ""
def data_of(bj): return bj.get("data") if isinstance(bj, dict) else None
T = {u: L.login(L.B46, u) for u in ("ipd-admin", "ipd-leader", "ipd-market", "ipd-rd")}
WRITTEN = []  # 写库登记清单

# ---- SEC-04 下载腿替代验证（存量交付物，不需要 OSS 上传通道） ----
STOCK = "2096364946240630785"
tok_admin = T["ipd-admin"] if isinstance(T["ipd-admin"], str) else T["ipd-admin"][L.B46]
tok_rd = T["ipd-rd"] if isinstance(T["ipd-rd"], str) else T["ipd-rd"][L.B46]
tok_market = T["ipd-market"] if isinstance(T["ipd-market"], str) else T["ipd-market"][L.B46]
s, bj, h, rb = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % STOCK, token=tok_admin)
L.record(recs, {"card":"SEC-04","case":"D1-超管下载存量交付物(对象缺失路径)","cmd":"GET /deliverables/2096364946240630785/download (admin)@16046",
    "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if not (s == 500) and b"at java" not in rb[:300] else "FAIL",
    "note":"HTTP %s code=%s msg=%s（该交付物 file_url=NULL 指向对象可能不存在,验对象存在校验规整度;无500无栈泄露即PASS）" % (s, code_of(bj), msg_of(bj)[:60])})
s, bj, _, rb = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % STOCK, token=tok_rd)
L.record(recs, {"card":"SEC-04","case":"D2-非成员下载存量交付物IDOR拒绝","cmd":"GET /deliverables/2096364946240630785/download (ipd-rd,非成员)@16046",
    "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if (s in (401,403,404) or code_of(bj) not in (0,None)) and not (s==200 and len(rb)>200) else "FAIL",
    "note":"msg=%s" % msg_of(bj)[:60]})
s, bj, _, rb = L.req("GET", L.B46, "/api/v1/deliverables/%s/download" % STOCK)
L.record(recs, {"card":"SEC-04","case":"D3-匿名下载存量交付物拒绝","cmd":"GET /deliverables/2096364946240630785/download (无token)@16046",
    "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s==401 or code_of(bj) in (20001,30001) else "FAIL", "note":"code=%s" % code_of(bj)})

# ---- AC-PROD-03 新建 SOFTWARE 项目：硬件动作 NA ----
proj = {"name": "R218-QA08-软件模板验证项目", "templateType": "SOFTWARE", "level": "S",
        "mainGroupId": 9120002, "targetMarkets": json.dumps(["CONSUMER"], ensure_ascii=False),
        "targetSalesAmount": 100, "targetChannelCount": 3, "targetNps": 40, "targetSceneCount": 2}
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/projects", token=tok_admin, body=proj)
PID = data_of(bj) or {}
PID = PID.get("id") if isinstance(PID, dict) else None
L.record(recs, {"card":"AC-PROD-03","case":"P3-1创建SOFTWARE项目","cmd":"POST /api/v1/projects (admin) templateType=SOFTWARE",
    "http": s, "envelope_code": code_of(bj), "project_id": PID,
    "verdict": "PASS" if s==200 and code_of(bj)==0 and PID else "FAIL", "note": msg_of(bj)[:60]})
if PID:
    WRITTEN.append("projects id=%s (SOFTWARE 项目+全套 stages/actions, 由 POST /api/v1/projects 服务端生成)" % PID)
    rows = L.sql("SELECT action_code,depth,status FROM stage_actions WHERE project_id=%s AND del_flag='0'" % PID)
    na = sorted({r[0] for r in rows if r[2]=="NA"})
    d03 = [r for r in rows if r[0]=="D03"]
    ok = bool(d03) and d03[0][2]=="NA" and ("D05" in na or "E01" in na or "V05" in na or "T01" in na)
    L.record(recs, {"card":"AC-PROD-03","case":"P3-2真库回读硬件动作NA","cmd":"mysql ... stage_actions WHERE project_id=%s" % PID,
        "http": None, "exit": 0, "verdict": "PASS" if ok else "FAIL",
        "note":"NA动作=%s | D03(手板/EVT)=%s | 断言:硬件专属动作存在且status=NA" % (",".join(na), d03 and d03[0][2])})
    # 轻管完成腿 (AC-IPD-03 服务部分) 用新项目 LIGHT 动作
    light = L.sql("SELECT id,depth,owner_role,status FROM stage_actions WHERE project_id=%s AND depth='LIGHT' AND status='NOT_STARTED' AND del_flag='0' LIMIT 1" % PID)
    if light:
        LA = light[0][0]
        s1, bj1, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/fields" % LA, token=tok_market, body={"actualDoneAt": "2026-09-25 10:00:00", "remark": "R218-QA08 轻管完成腿"})
        s2, bj2, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218" % LA, token=tok_market)
        s3, bj3, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/transit?target=DONE&reason=r218-qa08" % LA, token=tok_market)
        st = L.sql("SELECT status,actual_done_at FROM stage_actions WHERE id=%s" % LA)[0]
        WRITTEN.append("stage_actions id=%s (LIGHT动作 fields/transit 完成流转, status=%s)" % (LA, st[0]))
        L.record(recs, {"card":"AC-IPD-03","case":"L1-轻管动作fields+transit DONE(服务腿)","cmd":"POST /stage-actions/%s/fields{actualDoneAt} + transit IN_PROGRESS + transit DONE (market)" % LA,
            "http": s3, "envelope_code": code_of(bj3), "verdict": "PASS" if st[0]=="DONE" else ("FAIL" if code_of(bj3)==0 and st[0]!="DONE" else "FAIL"),
            "note":"fields=%s/%s transit=%s/%s done=%s/%s db: status=%s actualDoneAt=%s | UI字段三输入项部分仍属前端" % (s1,code_of(bj1),s2,code_of(bj2),s3,code_of(bj3),st[0],st[1])})
    else:
        L.record(recs, {"card":"AC-IPD-03","case":"L1-轻管动作fields+transit DONE(服务腿)","cmd":"SOFTWARE项目无LIGHT动作","http":None,"verdict":"BLOCKED","note":"新项目动作目录中未找到NOT_STARTED的LIGHT动作,无法走轻管腿"})
    # 跨组写负例 (AC-AUTH-03 服务等价腿)
    s, bj, _, _ = L.req("POST", L.B46, "/api/v1/projects/%s/status?target=ACTIVE" % PID, token=tok_rd)
    L.record(recs, {"card":"AC-AUTH-03","case":"A3-跨组非成员改状态拒绝(IDOR腿)","cmd":"POST /projects/%s/status?target=ACTIVE (ipd-rd,组900001≠9120002)" % PID,
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s==403 or code_of(bj) in (30001,40001) else "FAIL", "note":"msg=%s | 矩阵AC-AUTH-03为改密场景(需UI),本条记IDOR等价腿,改密主腿BLOCKED(前端+验证码链)"})

# ---- AC-PROD-04 存量导入 ----
if PID:
    legacy = {"name": "R218-QA08-存量导入项目", "templateType": "SOFTWARE", "level": "A", "mainGroupId": 9120002,
              "targetSalesAmount": 200, "legacyEffectiveAt": "2026-08-01", "declaredStage": "DEV", "missingHistoryAck": True,
              "alternativeEvidence": {"D01": "R218邮件纪要"}}
    s, bj, _, _ = L.req("POST", L.B46, "/api/v1/projects/legacy-import", token=tok_admin, body=legacy)
    LP = data_of(bj) or {}
    LP = LP.get("id") if isinstance(LP, dict) else None
    L.record(recs, {"card":"AC-PROD-04","case":"P4-1存量导入正例(ack=true)","cmd":"POST /api/v1/projects/legacy-import (admin) declaredStage=DEV",
        "http": s, "envelope_code": code_of(bj), "project_id": LP, "verdict": "PASS" if s==200 and code_of(bj)==0 and LP else "FAIL", "note": msg_of(bj)[:60]})
    if LP:
        WRITTEN.append("projects id=%s (LEGACY导入项目)" % LP)
        src = L.sql("SELECT source FROM projects WHERE id=%s" % LP)[0][0]
        hm = L.sql("SELECT COUNT(*) FROM stage_actions WHERE project_id=%s AND (history_mark IS NOT NULL OR status='NA') AND del_flag='0'" % LP)[0][0]
        au = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='PROJECT_LEGACY_IMPORT' AND entity_id=%s" % LP)[0][0]
        L.record(recs, {"card":"AC-PROD-04","case":"P4-2回读source/history_mark/审计","cmd":"mysql projects+stage_actions+audit_logs WHERE id=%s" % LP,
            "http": None, "exit": 0, "verdict": "PASS" if src=="LEGACY" and int(au)>=1 else "FAIL",
            "note":"source=%s history/NA行=%s audit(PROJECT_LEGACY_IMPORT)=%s" % (src, hm, au)})
        s, bj, _, _ = L.req("POST", L.B46, "/api/v1/projects/%s/status?target=ACTIVE" % LP, token=tok_admin)
        L.record(recs, {"card":"AC-PROD-04","case":"P4-3历史缺失不阻断后续流转","cmd":"POST /projects/%s/status?target=ACTIVE (admin)" % LP,
            "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s==200 and code_of(bj)==0 else "FAIL", "note":"msg=%s" % msg_of(bj)[:50]})
    legacy_bad = dict(legacy); legacy_bad["name"]="R218-QA08-存量导入反例"; legacy_bad["missingHistoryAck"]=False
    s, bj, _, _ = L.req("POST", L.B46, "/api/v1/projects/legacy-import", token=tok_admin, body=legacy_bad)
    L.record(recs, {"card":"AC-PROD-04","case":"P4-4反例ack=false拒绝","cmd":"POST legacy-import missingHistoryAck=false (admin)",
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if not (s==200 and code_of(bj)==0) else "FAIL", "note":"msg=%s" % msg_of(bj)[:60]})
    n = L.sql("SELECT COUNT(*) FROM projects WHERE name='R218-QA08-存量导入反例' AND del_flag='0'")[0][0]
    L.record(recs, {"card":"AC-PROD-04","case":"P4-5反例零落库回读","cmd":"mysql COUNT projects name=R218-QA08-存量导入反例","http":None,"exit":0,
        "verdict":"PASS" if int(n)==0 else "FAIL", "note":"rows=%s" % n})

# ---- AC-IPD-05 深管交付物必传 ----
ACT = L.sql("SELECT id FROM stage_actions WHERE project_id=9140005 AND action_code='D02' AND del_flag='0'")[0][0]
cur = L.sql("SELECT status FROM stage_actions WHERE id=%s" % ACT)[0][0]
if cur == "NOT_STARTED":
    L.req("POST", L.B46, "/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218-qa08" % ACT, token=tok_market)
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/transit?target=DONE&reason=r218-qa08" % ACT, token=tok_market)
ok = s != 500 and code_of(bj) not in (0, None)
L.record(recs, {"card":"AC-IPD-05","case":"I5-深管DONE无交付物被拒(BR-IPD-03)","cmd":"POST /stage-actions/%s/transit?target=DONE (market,D02 DEEP,无交付物)" % ACT,
    "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if ok else "FAIL",
    "note":"msg=%s | 必传正向腿(上传后可DONE)依赖OSS上传,A1已BLOCKED,本条仅验强制拒绝侧" % msg_of(bj)[:70]})
WRITTEN.append("stage_actions id=%s (D02 推进到 IN_PROGRESS, transit 状态流转)" % ACT)

# ---- AC-HAND-01c 组长代移交 ----
ZAO = "2096266884247736321"
L.sql("INSERT INTO project_members (id,project_id,person_id,role,member_type,locked_level,locked_amount,join_date,create_time,tenant_id,del_flag) VALUES (918000000000000001,9140005,%s,'MARKET_PM','FORMAL','B',0,'2026-08-01',NOW(),'000000','0')" % ZAO)
WRITTEN.append("project_members id=918000000000000001 (赵市场2096266884247736321 @9140005 MARKET_PM 在任绑定, HAND-01c夹具, 故意保留:移交成功即被系统改写)")
# 900103 是 9140005 在任 MARKET_PM,代移交把职位交给赵(冻结待移交)？矩阵语义:组长代离职人员发起。反向:目标人=ACTIVE 应拒
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/handovers", token=T["ipd-leader"], body={"projectId": 9140005, "role": "MARKET_PM", "toPersonId": int(ZAO), "note": "R218-QA08 代移交验证", "onBehalf": True})
HO = data_of(bj) or {}
HO_ID = HO.get("id") if isinstance(HO, dict) else None
L.record(recs, {"card":"AC-HAND-01c","case":"H1-组长代移交正例(onBehalf=true)","cmd":"POST /api/v1/handovers (leader组9120002=项目组) toPerson=赵(冻结)",
    "http": s, "envelope_code": code_of(bj), "handover_id": HO_ID, "verdict": "PASS" if s==200 and code_of(bj)==0 else "FAIL", "note":"msg=%s" % msg_of(bj)[:80]})
s2, bj2, _, _ = L.req("POST", L.B46, "/api/v1/handovers", token=tok_admin, body={"projectId": 9140005, "role": "MARKET_PM", "toPersonId": int(ZAO), "note": "R218 跨组负例", "onBehalf": True})
L.record(recs, {"card":"AC-HAND-01c","case":"H2-跨组代移交应拒(admin豁免对照组)","cmd":"POST /handovers (admin组900001≠9120002) 同参数",
    "http": s2, "envelope_code": code_of(bj2), "verdict": "INFO", "note":"admin按IpdIdorGuard语义SUPER_ADMIN豁免跨组→预期放行,作为豁免语义对照;真正跨组组长负例见H3"})
s3, bj3, _, _ = L.req("POST", L.B46, "/api/v1/handovers", token=T["ipd-leader"], body={"projectId": 9140005, "role": "RD_PM", "toPersonId": 900104, "note": "R218 目标人ACTIVE负例", "onBehalf": True})
L.record(recs, {"card":"AC-HAND-01c","case":"H3-目标人ACTIVE被拒","cmd":"POST /handovers toPerson=900104(ACTIVE) (leader)",
    "http": s3, "envelope_code": code_of(bj3), "verdict": "PASS" if not (s3==200 and code_of(bj3)==0) else "FAIL", "note":"msg=%s" % msg_of(bj3)[:80]})

# ---- AC-INC-34 报表导出 ----
cases = [
    ("N1-津贴台账导出(admin)", "/api/v1/report/export/allowance?month=2026-09", tok_admin, "ipd-admin"),
    ("N2-津贴台账导出(market内部角色)", "/api/v1/report/export/allowance?month=2026-09", tok_market, "ipd-market"),
    ("N3-奖金台账导出(admin)", "/api/v1/report/export/bonus?projectId=9140005", tok_admin, "ipd-admin"),
    ("N4-奖金台账导出(rd应拒)", "/api/v1/report/export/bonus?projectId=9140005", tok_rd, "ipd-rd"),
    ("N5-项目报表导出(admin)", "/api/v1/report/export/project", tok_admin, "ipd-admin"),
]
for name, path, tok, who in cases:
    s, bj, h, rb = L.req("GET", L.B46, path, token=tok, timeout=40)
    ct = h.get("Content-Type") or ""
    is_file = ("sheet" in ct) or ("octet" in ct) or (rb[:2] == b"PK") or ("excel" in ct)
    okd = (s==200 and (is_file or code_of(bj)==0)) or (s==403 and "应拒" in name)
    L.record(recs, {"card":"AC-INC-34","case":name,"cmd":"GET %s (%s)@16046" % (path, who),
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if okd else "FAIL",
        "note":"ct=%s bytes=%d 格式规范判据:文件流或code=0;'Excel格式规范'视觉项留前端" % (ct[:40], len(rb))})

# ---- AC-REQ-09 需求池删除双层审核 ----
RID = L.sql("SELECT id FROM requirements ORDER BY id DESC LIMIT 1")[0][0]
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/deletion-requests", token=T["ipd-leader"], body={"entityType":"requirements","entityId":int(RID),"reason":"R218-QA08 AC-REQ-09验证","snapshot":"{}"})
L.record(recs, {"card":"AC-REQ-09","case":"R9-1需求池提交删除申请","cmd":"POST /deletion-requirements entityType=requirements (leader) rid=%s" % RID,
    "http": s, "envelope_code": code_of(bj), "verdict": "FAIL" if (s==200 and code_of(bj)==0) is False and "不" in msg_of(bj) or code_of(bj) not in (0,None) else "PASS",
    "note":"msg=%s | DeletionRequestServiceImpl 允许实体集=projects/products/persons/cert_templates/gates,不含requirements→需求池无法走双层审核,与矩阵'强制双层审核'不符,判FAIL(现象级)" % msg_of(bj)[:80]})

# ---- AC-KPI-02/03 功能KPI录入 ----
s, bj, _, _ = L.req("GET", L.B46, "/api/v1/kpi/functional-metrics/codes", token=tok_market)
codes = data_of(bj)
L.record(recs, {"card":"AC-KPI-02","case":"K0-codes目录","cmd":"GET /kpi/functional-metrics/codes (market)","http": s, "envelope_code": code_of(bj),
    "verdict": "PASS" if s==200 and code_of(bj)==0 and codes else "FAIL", "note":"codes=%s" % str(codes)[:120]})
def kpi_leg(tag, who, tok, code_hint):
    code = None
    if isinstance(codes, list):
        flat = [c if isinstance(c, str) else (c.get("code") or c.get("metricCode")) for c in codes]
        flat = [c for c in flat if c]
        code = flat[0] if flat else None
    if not code: return L.record(recs, {"card":tag,"case":"K-%s 录入" % who,"cmd":"codes解析失败","http":None,"verdict":"BLOCKED","note":"无法从/codes响应解析metricCode"})
    body = {"projectId": 9140005, "metricCode": code, "period": "2026-Q3", "metricValue": 88.8, "targetValue": 90, "scaleVersion": "R218", "remark": "QA-08录入验证"}
    s, bj, _, _ = L.req("PUT", L.B46, "/api/v1/kpi/functional-metrics", token=tok, body=body)
    got = None
    s2, bj2, _, _ = L.req("GET", L.B46, "/api/v1/kpi/functional-metrics?projectId=9140005&period=2026-Q3", token=tok)
    txt = json.dumps(bj2, ensure_ascii=False) if bj2 else ""
    hit = ("88.8" in txt) or (code in txt and s2==200)
    WRITTEN.append("kpi_functional_metrics (projectId=9140005 metric=%s period=2026-Q3 value=88.8, %s 录入)" % (code, who))
    L.record(recs, {"card":tag,"case":"K-%s upsert+回读" % who,"cmd":"PUT /kpi/functional-metrics code=%s + GET 回读 (%s)" % (code, who),
        "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s==200 and code_of(bj)==0 and hit else "FAIL",
        "note":"put=%s/%s get=%s hit=%s" % (s, code_of(bj), s2, hit)})
kpi_leg("AC-KPI-02", "market", tok_market, "MKT")
kpi_leg("AC-KPI-03", "rd", tok_rd, "RD")

# ---- BLOCKED / NOT-RUN 记账 ----
L.record(recs, {"card":"AC-PROD-05","case":"P5-补入市场PM自动生成场景定义复核待办(14天)","cmd":"grep -rn 'SCENARIO_REVIEW|场景定义复核待办' ruoyi-ipd/src/main/java → 无HTTP触发腿;ProjectBootstrapService 14天复核仅LEGACY路径","http":None,"verdict":"BLOCKED","note":"服务层未发现'补入MARKET_PM→生成待办'实现;bind端点(POST /projects/{id}/members)存在但无待办生成钩子证据。需服务层补齐或提供待办表名后重验"})
L.record(recs, {"card":"AC-PROD-09","case":"P9-待指派超5工作日提醒组长","cmd":"GuestDemandService.notifyOverdueUnassigned():354 grep 无调用方(无@Scheduled/无HTTP端点)","http":None,"verdict":"FAIL","note":"扫描方法已实现但从未被调度/暴露→兜底提醒实际永不触发;且AC文本要求'提醒产品组组长'而实现注释为'通知超管',双重偏差"})
for ac, why in (("AC-ENV-04","纯前端断网UI表现,本车道不开浏览器"),("AC-IPD-06","纯视觉对比判据"),("AC-IPD-29","五阶段视图映射为前端渲染;服务侧stage目录已存在但未构UI断言"),
                ("AC-KPI-02-ui","录入界面部分"),("AC-AUTH-03","改密流程含验证码/旧密钥登出等UI链,服务侧IDOR等价腿已另记")):
    if ac.endswith("-ui"): continue
    if ac != "AC-AUTH-03":
        L.record(recs, {"card":ac,"case":"本轮判定","cmd":"-","http":None,"verdict":"BLOCKED","note":why})
L.record(recs, {"card":"AC-IPD-04","case":"本轮判定","cmd":"-","http":None,"verdict":"NOT-RUN","note":"轻管SOP不强制展示属前端;服务侧sop_id可空腿未单列"})
L.record(recs, {"card":"AC-REQ-06","case":"本轮判定","cmd":"-","http":None,"verdict":"NOT-RUN","note":"全生命周期链在249矩阵中历史已PASS(QA-03轮),本轮未重复"})

print("WRITTEN:", json.dumps(WRITTEN, ensure_ascii=False, indent=1))
json.dump(WRITTEN, open(os.path.join(os.path.dirname(L.REC_PATH), "写库清单-qa08.json"), "w"), ensure_ascii=False, indent=1)
print("QA08 subset done. records:", len(recs))
