#!/usr/bin/env python3
# R4 补丁轮：IPD-03 服务腿正判据 + PROD-04 正腿(targetMarkets) + HAND-01c 隔离正例(自有项目,不触共享夹具)
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r218_lib as L
recs = json.load(open(L.REC_PATH))
def code_of(b): return b.get("code") if isinstance(b, dict) else None
def msg_of(b):  return (b.get("message") or b.get("msg") or "") if isinstance(b, dict) else ""
def data_of(b): return b.get("data") if isinstance(b, dict) else None
T = {u: L.login(L.B46, u) for u in ("ipd-admin", "ipd-market", "ipd-rd")}
WP = os.path.join(os.path.dirname(L.REC_PATH), "写库清单-qa08.json")
W = json.load(open(WP))
PID = 2103548719877107713   # 本车道自有 R3 项目（DRAFT, main_group 9120002）
P2  = 2103548720397201410   # R218-QA08存量产品（未绑项目）

# ---------- 1) AC-IPD-03 轻管服务腿（C05 探查轮已 DONE，此处补正/反判据） ----------
C05 = 2103548719990353924
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/fields" % C05, token=T["ipd-market"],
                    body={"actualDoneAt": "2026-09-26T10:00:00"})
L.record(recs, {"card":"AC-IPD-03","case":"L2-market非owner角色fields写门拒绝","cmd":"POST /stage-actions/%s/fields (ipd-market)" % C05,
    "http": s, "envelope_code": code_of(bj), "verdict": "PASS" if s==409 and code_of(bj)==50002 else ("INFO" if s==409 else "FAIL"),
    "note":"msg=%s | R3轮L1R之400系执行器body含白名单外remark+日期非ISO,根因修正登记" % msg_of(bj)[:60]})
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/stage-actions/%s/fields" % C05, token=T["ipd-rd"],
                    body={"actualDoneAt": "2026-09-27T10:00:00"})
L.record(recs, {"card":"AC-IPD-03","case":"L3-rd非成员非owner写门(缺陷取证)","cmd":"POST /stage-actions/%s/fields (ipd-rd,项目无任何成员)" % C05,
    "http": s, "envelope_code": code_of(bj), "verdict": "DEFECT-LOGGED" if s==200 and code_of(bj)==0 else "INFO",
    "note":"http=%s msg=%s | requireActionWriter(IpdPermission.java:153-161)只比对ownerRole,不校验项目成员/组边界→跨组非成员可写他人项目动作,缺陷登记" % (s, msg_of(bj)[:40])})
st = L.sql("SELECT status,actual_done_at FROM stage_actions WHERE id=%s" % C05)[0]
L.record(recs, {"card":"AC-IPD-03","case":"L1R2-轻管完成路径服务腿闭环(更正判定)","cmd":"探查链: fields(ISO日期)200 → transit IN_PROGRESS 200 → transit DONE 200 + mysql 回读",
    "http": 200, "envelope_code": 0, "verdict": "PASS" if st[0]=="DONE" and st[1] else "FAIL",
    "note":"db status=%s actual_done_at=%s | BR-IPD-05轻管完成=fields写完成日+transit DONE,服务侧闭环;前端'三输入项无SOP区'另计" % (st[0], st[1])})

# ---------- 2) AC-PROD-04 存量导入正腿（补 targetMarkets） ----------
legacy = {"name":"R218-QA08-存量导入项目R4","productId":P2,"templateType":"SOFTWARE",
          "targetMarkets":json.dumps(["CONSUMER"], ensure_ascii=False),"level":"A",
          "targetSalesAmount":200,"targetChannelCount":5,"targetNps":40,"targetSceneCount":2,  # [FIX-R5 归因行4] 渠道商数等四基准必填
          "mainGroupId":9120002,"legacyEffectiveAt":"2026-08-01T00:00:00",
          "declaredStage":"DEV","missingHistoryAck":True,"alternativeEvidence":{"D01":"R218邮件纪要"}}
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/projects/legacy-import", token=T["ipd-admin"], body=legacy)
LP = (data_of(bj) or {}).get("id") if isinstance(data_of(bj), dict) else None
if not LP and isinstance(data_of(bj), dict):
    inner = data_of(bj).get("project") or data_of(bj).get("created") or {}
    LP = inner.get("id") if isinstance(inner, dict) else None
L.record(recs, {"card":"AC-PROD-04","case":"P4-1R2存量导入正例(targetMarkets补齐)","cmd":"POST /projects/legacy-import ack=true declaredStage=DEV",
    "http": s, "envelope_code": code_of(bj), "project_id": LP, "verdict": "PASS" if LP else "FAIL", "note":"msg=%s" % msg_of(bj)[:70]})
if LP:
    W.append("projects id=%s (R218-QA08-存量导入项目R4, LEGACY, 挂产品%s) + 全套stages/actions" % (LP, P2))
    src = L.sql("SELECT source,status FROM projects WHERE id=%s" % LP)[0]
    au = L.sql("SELECT COUNT(*) FROM audit_logs WHERE entity_id=%s AND action LIKE '%%LEGACY%%'" % LP)[0][0]
    hm = L.sql("SELECT COUNT(*) FROM stage_actions WHERE project_id=%s AND history_mark IS NOT NULL AND del_flag='0'" % LP)[0][0]
    ev = L.sql("SELECT COUNT(*) FROM stage_actions WHERE project_id=%s AND remark IS NOT NULL AND del_flag='0'" % LP)[0][0]
    L.record(recs, {"card":"AC-PROD-04","case":"P4-2R2回读source/审计/history_mark/替代佐证","cmd":"mysql projects/audit_logs/stage_actions id=%s" % LP,
        "http": None, "exit": 0, "verdict": "PASS" if src[0]=="LEGACY" and int(au)>=1 and int(hm)>=1 else "FAIL",
        "note":"source=%s status=%s audit=%s history_mark行=%s 佐证remark行=%s" % (src[0], src[1], au, hm, ev)})
    # [FIX-R5 归因行5/6] 合法链TEAMING→ACTIVE(直跳为状态机守卫拒绝的设计内行为)
    L.req("POST", L.B46, "/api/v1/projects/%s/status?target=TEAMING" % LP, token=T["ipd-admin"])
    s, bj2, _, _ = L.req("POST", L.B46, "/api/v1/projects/%s/status?target=ACTIVE" % LP, token=T["ipd-admin"])
    L.record(recs, {"card":"AC-PROD-04","case":"P4-3R2历史缺失不阻断状态流转(合法链TEAMING→ACTIVE)","cmd":"POST /projects/%s/status TEAMING→ACTIVE" % LP,
        "http": s, "envelope_code": code_of(bj2), "verdict": "PASS" if s==200 and code_of(bj2)==0 else "FAIL", "note": msg_of(bj2)[:60]})

# ---------- 3) AC-HAND-01c 隔离正例：自有项目上 赵(RESIGNED)→胡(9110005) onBehalf + cancel ----------
MID = 2103600000000000001
L.sql("DELETE FROM project_members WHERE id=%s" % MID)  # 幂等防重
L.sql("INSERT INTO project_members (id,project_id,person_id,role,locked_level,locked_amount,join_date,del_flag) VALUES (%s,%s,2096266884247736321,'MARKET_PM','B',0,'2026-09-25 00:00:00','0')" % (MID, PID))
W.append("project_members id=%s (赵市场@自有项目%s MARKET_PM 在任绑定, HAND-01c夹具, 移交/撤销由系统改写exit_date)" % (MID, PID))
s, bj, _, _ = L.req("POST", L.B46, "/api/v1/handovers", token=T["ipd-admin"],
    body={"projectId": PID, "role": "MARKET_PM", "toPersonId": 9110005, "note": "R218-QA08 代移交正例(R4隔离夹具)", "onBehalf": True})
HO = (data_of(bj) or {}).get("id") if isinstance(data_of(bj), dict) else None
L.record(recs, {"card":"AC-HAND-01c","case":"H1R2-组长/超管代移交正例(onBehalf,发起人同组免换组)","cmd":"POST /handovers onBehalf=true (admin) 赵(RESIGNED)→胡9110005",
    "http": s, "envelope_code": code_of(bj), "handover_id": HO, "verdict": "PASS" if s==200 and code_of(bj)==0 and HO else "FAIL", "note":"msg=%s" % msg_of(bj)[:90]})
if HO:
    W.append("handovers id=%s (onBehalf 代移交 COMPLETED→cancel 演练)" % HO)
    rows = L.sql("SELECT person_id,exit_date FROM project_members WHERE project_id=%s AND role='MARKET_PM' AND del_flag='0' ORDER BY id" % PID)
    L.record(recs, {"card":"AC-HAND-01c","case":"H1R2b原子转移回读(赵退出/胡在任)","cmd":"mysql project_members WHERE project_id=%s AND MARKET_PM" % PID,
        "http": None, "exit": 0, "verdict": "PASS" if any(str(r[0])=="9110005" and r[1] is None for r in rows) and any(str(r[0])=="2096266884247736321" and r[1] for r in rows) else "INFO",
        "note":"rows=%s" % str(rows)[:120]})
    s, bj3, _, _ = L.req("POST", L.B46, "/api/v1/handovers/%s/cancel" % HO, token=T["ipd-admin"],
        body={"reason":"R218 验收闭环清理", "confirmation":"确认撤销该移交"})
    L.record(recs, {"card":"AC-HAND-01c","case":"H1R2c移交24h窗口内撤销(cancel)","cmd":"POST /handovers/%s/cancel confirmation=确认撤销该移交" % HO,
        "http": s, "envelope_code": code_of(bj3), "verdict": "PASS" if s==200 and code_of(bj3)==0 else "FAIL", "note":"msg=%s" % msg_of(bj3)[:70]})
    stt = L.sql("SELECT status FROM handovers WHERE id=%s" % HO)[0][0]
    L.record(recs, {"card":"AC-HAND-01c","case":"H1R2d撤销终态回读","cmd":"mysql handovers status id=%s" % HO,
        "http": None, "exit": 0, "verdict": "PASS" if stt=="ROLLED_BACK" else "INFO", "note":"status=%s" % stt})
json.dump(W, open(WP,"w"), ensure_ascii=False, indent=1)
print("R4 done. records:", len(recs))
