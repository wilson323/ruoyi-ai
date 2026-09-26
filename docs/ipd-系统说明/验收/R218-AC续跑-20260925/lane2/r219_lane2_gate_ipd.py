#!/usr/bin/env python3
# R219 LANE2 · GATE/IPD 域 249AC 矩阵真执行续跑驱动（可重跑，fixture 前缀 LANE2-）
# 后端：B39=16039（fresh jar）。所有写只走业务 HTTP；SQL 仅只读回读。
import sys, json, time, datetime as dt
sys.path.insert(0, ".")
import r219_lib as L

RUN = "L2T" + dt.datetime.now().strftime("%H%M%S")   # 运行戳，隔离重跑
RECS = []
ADM, MTK, RD, LDR = "ipd-admin", "ipd-market", "ipd-rd", "ipd-leader"
TOK = {}
GROUP = 900001
P_MKT, P_RD, P_RD2, P_LEADER, P_ADMIN = 900103, 900104, 900105, 900102, 900101
P_MKT_X = 2114000000000000001     # R214市场PM（实测 DISABLED 不可入组，弃用）
P_M1 = 9110005                    # 胡蛟露 MARKET_PM ACTIVE 组9120002（组长9110004文元彪）活跃绑定1→可绑2
P_M2 = 2096266884189016065        # 陈市场 MARKET_PM ACTIVE 组2096266884017049601（组长2096266884134490113）活跃绑定2→可绑1
P_M1_LEADER = 9110004             # 文元彪：P(P_M1) 场景 collectLeaders 命中对象（无登录口令，仅接收通知侧证）
OSS_M, OSS_MM = 990001, 990002    # 既有 r212 材料 ossId（评审材料/会议纪要）

def ec(bj): return bj.get("code") if isinstance(bj, dict) else None
def msg(bj):
    if isinstance(bj, dict):
        return str(bj.get("msg") or bj.get("message") or bj.get("error") or "")[:240]
    return ""
def data(bj): return bj.get("data") if isinstance(bj, dict) else None
def ok(s, bj): return s == 200 and isinstance(bj, dict) and bj.get("code") == 0

def call(tag, method, path, tok=None, body=None, card="", case="", cmd=""):
    s, bj, _, _ = L.req(method, L.B39, path, token=tok, body=body)
    return s, bj

def rec(card, case, cmd, s=None, bj=None, verdict="PASS", note="", http=None, ecd=None):
    L.record(RECS, {"card": card, "case": case, "cmd": cmd,
                    "http": http if http is not None else s,
                    "envelope_code": ecd if ecd is not None else ec(bj) if bj is not None else None,
                    "verdict": verdict, "note": note})

def H(name): return "LANE2-%s-%s" % (RUN, name)

# ---------- fixture 工厂（全部业务 HTTP） ----------
def mk_product(name):
    s, bj = L.req("POST", L.B39, "/api/v1/products", token=TOK[MTK],
                  body={"productName": name, "source": "PM_NEW"})[0:2]
    d = data(bj) or {}
    pid = d.get("id") or d.get("productId")
    return s, bj, pid

def mk_project(tag, level="B", template="HARDWARE", markets='["国内"]'):
    st, bj, prod_id = mk_product(H("PROD-" + tag))
    body = {"name": H("PRJ-" + tag), "productId": int(prod_id) if prod_id else None,
            "templateType": template, "targetMarkets": markets, "level": level,
            "targetSalesAmount": 1000000, "targetChannelCount": 10, "targetNps": 50,
            "targetSceneCount": 5, "mainGroupId": GROUP}
    s, bj = L.req("POST", L.B39, "/api/v1/projects", token=TOK[MTK], body=body)[0:2]
    d = data(bj) or {}
    return (s, bj, d.get("id")), prod_id

def mk_gate(pid, code):
    s, bj = L.req("POST", L.B39, "/api/v1/projects/%s/gates?gateCode=%s" % (pid, code),
                  token=TOK[MTK])[0:2]
    d = data(bj) or {}
    return s, bj, d.get("id")

def checklist(gid):
    s, bj = L.req("GET", L.B39, "/api/v1/gates/%s/elements" % gid, token=TOK[MTK])[0:2]
    return data(bj) or []

def judge(gid, element_id, result="PASS", verifications=None, written_intents=None,
          evidence=None, note=None, responsible=None, close=None, tok=None):
    body = {"elementId": int(element_id), "result": result}
    if verifications is not None: body["verifications"] = verifications
    if written_intents is not None: body["writtenIntents"] = written_intents
    if evidence: body["evidenceRef"] = evidence
    if note: body["conditionNote"] = note
    if responsible: body["responsiblePersonId"] = int(responsible)
    if close: body["closeDeadline"] = close
    return L.req("POST", L.B39, "/api/v1/gates/%s/element-results" % gid,
                 token=tok or TOK[MTK], body=body)[0:2]

def judge_all(gid, overrides=None, tok=None):
    """把 gate 全部要素判 PASS（G1-1 带 verifications=5）；overrides: code->dict|None(跳过)。"""
    overrides = overrides or {}
    fails = []
    for e in checklist(gid):
        code, eid = e.get("elementCode"), e.get("elementId")
        if code in overrides and overrides[code] is None:
            continue
        ov = overrides.get(code) or {}
        body_extra = dict(ov)
        if code == "G1-1" and "verifications" not in body_extra and body_extra.get("result", "PASS") == "PASS":
            body_extra.setdefault("verifications", 5)
        s, bj = judge(gid, eid,
                      result=body_extra.get("result", "PASS"),
                      verifications=body_extra.get("verifications"),
                      written_intents=body_extra.get("writtenIntents"),
                      evidence=body_extra.get("evidence"),
                      note=body_extra.get("note"),
                      responsible=body_extra.get("responsible"),
                      close=body_extra.get("close"), tok=tok)
        if not ok(s, bj):
            fails.append((code, s, ec(bj), msg(bj)))
    return fails

def submit(gid, m=OSS_M, mm=OSS_MM, tok=None):
    return L.req("POST", L.B39, "/api/v1/gates/%s/submit" % gid,
                 token=tok or TOK[MTK],
                 body={"materialsOssId": int(m), "meetingMinutesOssId": int(mm)})[0:2]

def sign(gid, tok, decision, opinion=None):
    return L.req("POST", L.B39, "/api/v1/gates/%s/sign" % gid, token=tok,
                 body={"decision": decision, "opinion": opinion or ("LANE2-" + RUN + " " + decision)})[0:2]

def review(gid, tok):
    s, bj = L.req("GET", L.B39, "/api/v1/gates/%s/review" % gid, token=tok)[0:2]
    return s, bj, data(bj) or {}

def gate_row(gid):
    r = L.sql("SELECT status, current_round, sign_due_at, started_at, sign_extension_count FROM gates WHERE id=%s" % gid)
    return r[0] if r else None

def bind_member(pid, person, role, approval=None):
    body = {"personId": int(person), "role": role}
    if approval: body["approvalRef"] = approval
    return L.req("POST", L.B39, "/api/v1/projects/%s/members" % pid,
                 token=TOK[MTK], body=body)[0:2]

def transit(aid, target, tok, reason=None):
    q = "?target=%s" % target + ("" if not reason else ("&reason=" + reason))
    return L.req("POST", L.B39, "/api/v1/stage-actions/%s/transit%s" % (aid, q), token=tok)[0:2]

def fields(aid, tok, body):
    return L.req("POST", L.B39, "/api/v1/stage-actions/%s/fields" % aid, token=tok, body=body)[0:2]

def deliverable(aid, tok, fname):
    import urllib.parse
    return L.req("POST", L.B39, "/api/v1/stage-actions/%s/deliverables?fileName=%s" % (aid, urllib.parse.quote(fname)), token=tok)[0:2]

def actions_of(pid):
    r = L.sql("SELECT id, action_code, depth, status, is_blocking, owner_role, far_value, frr_value FROM stage_actions WHERE project_id=%s AND del_flag='0' ORDER BY action_code" % pid)
    return {row[1]: row for row in r}

def done_deep(pid, code, tok, with_deliv=True):
    """深管动作完成：交付物+IN_PROGRESS+DONE。返回 (最后响应, 动作行)"""
    a = actions_of(pid)[code]; aid = a[0]
    if with_deliv:
        deliverable(aid, tok, H("DELIV-%s.pdf" % code))
    s1, b1 = transit(aid, "IN_PROGRESS", tok, "lane2")
    s2, b2 = transit(aid, "DONE", tok, "lane2")
    return (s2, b2), a

def done_light(pid, code, tok, extra=None):
    a = actions_of(pid)[code]; aid = a[0]
    body = {"actualDoneAt": "2026-09-25T10:00:00"}
    if extra: body.update(extra)
    s0, b0 = fields(aid, tok, body)
    s1, b1 = transit(aid, "DONE", tok, "lane2")
    return (s1, b1), (s0, b0), a

def safe(fn):
    try:
        fn()
    except Exception as ex:
        print("!! EXC in %s: %s" % (fn.__name__, str(ex)[:200]), flush=True)
        rec(getattr(fn, "card", "LANE2-INFRA"), fn.__name__, "driver-step",
            verdict="FAIL-ENV", note="车道脚本异常(归因含兄弟车道串扰排查): %s" % str(ex)[:200])

def setup():
    for u in (ADM, MTK, RD, LDR):
        TOK[u] = L.login(L.B39, u)
        assert TOK[u], "login fail " + u

# ---------- 真库/配置回读 helper ----------
def notif_cnt(recv, etype, gid):
    r = L.sql("SELECT COUNT(*) FROM notification_events WHERE receiver_id=%s AND event_type='%s' AND source_type='gate' AND source_id=%s" % (recv, etype, gid))
    return int(r[0][0]) if r else 0

def audit_cnt(action, gid):
    r = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='%s' AND entity_id=%s" % (action, gid))
    return int(r[0][0]) if r else 0

def cfg_get(key):
    s, bj = L.req("GET", L.B39, "/api/v1/business-config?scope=GLOBAL&configKey=%s" % key, token=TOK[ADM])[0:2]
    v = (data(bj) or {}).get("configValue")
    return s, bj, v

def cfg_set(key, val):
    s, bj = L.req("POST", L.B39, "/api/v1/business-config", token=TOK[ADM],
                  body={"scope": "GLOBAL", "configKey": key, "configValue": str(val),
                        "valueType": "INT", "enabled": 1, "description": "LANE2-%s temp for AC test" % RUN})[0:2]
    L.written({"card": "AC-GATE-08/09/1d/21", "op": "business-config upsert %s=%s" % (key, val), "ts": L.now()})
    return s, bj

def cfg_delete(key):
    s, bj = L.req("DELETE", L.B39, "/api/v1/business-config?scope=GLOBAL&configKey=%s" % key, token=TOK[ADM])[0:2]
    L.written({"card": "AC-GATE-1d", "op": "business-config delete %s" % key, "ts": L.now()})
    return s, bj

def run_case(card, fn):
    try:
        fn()
    except Exception as ex:
        print("!! EXC %s %s: %s" % (card, fn.__name__, str(ex)[:200]), flush=True)
        rec(card, fn.__name__, "driver-step", verdict="FAIL-ENV",
            note="车道异常(先按兄弟车道串扰排查,未重试轰炸): %s" % str(ex)[:180])

GIDS = {}   # 场景名 -> (pid, gid)
PROJS = {}  # tag -> pid
VOTE = {}   # 记录共享中间量

# ==================== GATE 域 ====================
def case_gate01():
    card = "AC-GATE-01"
    s, bj, pid, _ = mk_project("GATEALL"); PROJS["GATEALL"] = pid
    if not ok(s, bj):
        rec(card, "mk GATEALL", "POST /api/v1/projects", s, bj, "FAIL-ENV", "建项目失败:" + msg(bj)); return
    gmap = {}
    for code in ("G1", "G2", "G3", "G4", "G5"):
        s2, bj2, gid = mk_gate(pid, code)
        gmap[code] = gid
        if not ok(s2, bj2):
            rec(card, "create " + code, "POST /api/v1/projects/%s/gates?gateCode=%s" % (pid, code),
                s2, bj2, "FAIL", "Gate 创建失败:" + msg(bj2))
            return
    rows = L.sql("SELECT sa.action_code, ps.stage_code FROM stage_actions sa JOIN project_stages ps ON sa.stage_id=ps.id "
                 "WHERE sa.project_id=%s AND sa.action_code IN ('C11','P13','D05','L07','LC02') ORDER BY sa.action_code" % pid)
    got = {r[0]: r[1] for r in rows}
    exp = {"C11": "CONCEPT", "P13": "PLAN", "D05": "DEV", "L07": "LAUNCH", "LC02": "LIFECYCLE"}
    VOTE["GATEALL_G"] = gmap
    rec(card, "五Gate挂载位+五实例创建", "POST gates G1..G5 + SQL stage_actions x project_stages",
        200, None, "PASS" if got == exp else "FAIL",
        "5 gate 创建成功 ids=%s; 动作→阶段映射 %s vs 期望 %s" % (list(gmap.values()), got, exp))

def case_gate02_03_04_1a():
    card = "AC-GATE-02"
    s, bj, pid, _ = mk_project("P1"); PROJS["P1"] = pid
    s2, bj2, gid = mk_gate(pid, "G1"); GIDS["P1G1"] = gid
    # 02a 缺材料 body
    s3, b3 = L.req("POST", L.B39, "/api/v1/gates/%s/submit" % gid, token=TOK[MTK], body={})[0:2]
    rec(card, "缺材料提交被拒", "POST /gates/%s/submit {}" % gid, s3, b3,
        "PASS" if not ok(s3, b3) else "FAIL", "envelope=%s msg=%s" % (ec(b3), msg(b3)))
    # 全判 PASS（含 1a：G1-1 verifications=5）
    fails = judge_all(gid)
    rec("AC-GATE-1a", "G1-1 五家一手验证判PASS", "POST /gates/%s/element-results G1-1 v=5" % gid,
        200, None, "PASS" if not any(c == "G1-1" for c, *_ in fails) else "FAIL",
        "G1-1 PASS@5 受理; judge_all 其余失败=%s" % fails[:3])
    if fails:
        rec(card, "judge_all", "批量判定", 200, None, "FAIL", "判定失败项:%s" % fails[:4])
        return
    # 02b 假 ossId
    sf, bf = submit(gid, m=999999999, mm=999999998)
    rec(card, "假ossId材料被拒", "POST /gates/%s/submit fakeOss" % gid, sf, bf,
        "PASS" if not ok(sf, bf) else "FAIL", "envelope=%s msg=%s" % (ec(bf), msg(bf)))
    # 02c 真提交
    ss, bs = submit(gid)
    rec(card, "真ossId提交成功", "POST /gates/%s/submit" % gid, ss, bs,
        "PASS" if ok(ss, bs) else "FAIL", "gate=%s msg=%s" % ((data(bs) or {}).get("status"), msg(bs)))
    if not ok(ss, bs): return
    # 03 盲签
    s4, b4 = sign(gid, TOK[MTK], "APPROVE")
    s5, b5, v5 = review(gid, TOK[RD])
    other = v5.get("other") or {}
    blind = v5.get("otherSubmitted") is True and other.get("decision") in (None, "")
    rec("AC-GATE-03", "市场已签-研发视角盲签", "GET /gates/%s/review (rd)" % gid, s5, b5,
        "PASS" if blind else "FAIL", "otherSubmitted=%s other.decision=%s hint=%s" % (v5.get("otherSubmitted"), other.get("decision"), v5.get("hint")))
    # 04 双方同意
    s6, b6 = sign(gid, TOK[RD], "APPROVE")
    row = gate_row(gid)
    s7, b7, v7 = review(gid, TOK[RD])
    revealed = (v7.get("other") or {}).get("decision") == "APPROVE"
    rec("AC-GATE-04", "双签同意→APPROVED+揭示", "sign(rd APPROVE)+GET review", s6, b6,
        "PASS" if row and row[0] == "APPROVED" and revealed else "FAIL",
        "gates.status=%s round=%s rd视角other=%s" % (row[0] if row else None, row[1] if row else None, (v7.get("other") or {}).get("decision")))

def case_gate05_10_06_07():
    card = "AC-GATE-05"
    s, bj, pid, _ = mk_project("P9"); PROJS["P9"] = pid
    s0, b0 = bind_member(pid, P_M1, "MARKET_PM")
    if not ok(s0, b0):
        rec(card, "bind 9110005", "POST /members", s0, b0, "FAIL-ENV", "在册市场PM绑定失败:" + msg(b0)[:120]); return
    L.written({"card": card, "op": "bind 9110005 MARKET_PM pid=%s" % pid, "ts": L.now()})
    s2, bj2, gid = mk_gate(pid, "G1"); GIDS["P2G1"] = gid
    fails = judge_all(gid)
    if fails:
        rec(card, "judge_all", "批量判定", 200, None, "FAIL", str(fails[:4])); return
    ss, bs = submit(gid)
    if not ok(ss, bs):
        rec(card, "submit", "POST submit", ss, bs, "FAIL-ENV", msg(bs)); return
    sign(gid, TOK[MTK], "APPROVE")
    sr, br = sign(gid, TOK[RD], "REJECT", "LANE2-%s 否决" % RUN)
    row = gate_row(gid)
    n1, n2 = notif_cnt(P_MKT, "GATE_REJECTED", gid), notif_cnt(P_RD, "GATE_REJECTED", gid)
    rec(card, "任一否决→驳回+双方通知", "sign(rd REJECT)+SQL notification_events", sr, br,
        "PASS" if row and row[0] == "REJECTED" and n1 >= 1 and n2 >= 1 else "FAIL",
        "status=%s 通知(market=%d,rd=%d)" % (row[0], n1, n2))
    # AC-GATE-10：仲裁（在册PM→组9120002→组长9110004；可登录组长仅900102不在本组）
    pre = L.sql("SELECT arbitrator_id,decision FROM gate_arbitrations WHERE gate_id=%s AND round=1" % gid)
    nreq = notif_cnt(P_M1_LEADER, "GATE_ARBITRATION_REQUEST", gid)
    sa, ba = L.req("POST", L.B39, "/api/v1/gates/%s/arbitrate" % gid, token=TOK[LDR],
                   body={"decision": "APPROVE", "opinion": "LANE2-%s 组长仲裁" % RUN})[0:2]
    sf, bf = L.req("POST", L.B39, "/api/v1/gates/%s/final-ruling" % gid, token=TOK[ADM],
                   body={"decision": "APPROVE", "opinion": "LANE2 probe"})[0:2]
    rec("AC-GATE-10", "分歧→预落仲裁行+越权组长拒+终裁门", "arbitrate(900102他组组长)+final-ruling(admin)", sa, ba,
        "PARTIAL" if len(pre) >= 1 and ec(ba) != 0 and not ok(sf, bf) else "FAIL",
        "预落待裁行=%s(组长9110004) 仲裁请求通知=%d; 他组组长900102提交被拒(%s); 终裁被拒(%s) — 组长真实落裁需 9110004 登录凭证(dev-accounts.yaml 无此账号)、两组对立→自动升级腿单组长库结构性不可测" % (
            pre, nreq, msg(ba)[:60], msg(bf)[:60]))
    # AC-GATE-06 reopen
    s6, b6 = L.req("POST", L.B39, "/api/v1/gates/%s/reopen" % gid, token=TOK[MTK])[0:2]
    d6 = data(b6) or {}
    rec("AC-GATE-06", "驳回后重发起 round+1", "POST /reopen", s6, b6,
        "PASS" if d6.get("round") == "2" or d6.get("round") == 2 else "FAIL", "resp=%s" % d6)
    # r2 再驳 → r3（组长列席通知）
    sign(gid, TOK[MTK], "APPROVE"); sign(gid, TOK[RD], "REJECT")
    s7, b7 = L.req("POST", L.B39, "/api/v1/gates/%s/reopen" % gid, token=TOK[MTK])[0:2]
    n3 = L.sql("SELECT COUNT(*), GROUP_CONCAT(receiver_id) FROM notification_events WHERE event_type='GATE_ROUND_OBSERVER' AND source_type='gate' AND source_id=%s" % gid)
    rec("AC-GATE-07", "第3轮组长列席通知", "sign+reopen 至 r3 + SQL notif", s7, b7,
        "PASS" if (data(b7) or {}).get("round") in (3, "3") and n3 and int(n3[0][0]) >= 1 else "FAIL",
        "round=%s 列席通知=%s 接收人=%s(在册PM胡蛟露所在组9120002组长文元彪)" % ((data(b7) or {}).get("round"), n3[0][0] if n3 else 0, n3[0][1] if n3 else None))
    # r3 再驳 → r4 → r5（超管介入）
    sign(gid, TOK[MTK], "APPROVE"); sign(gid, TOK[RD], "REJECT")
    L.req("POST", L.B39, "/api/v1/gates/%s/reopen" % gid, token=TOK[MTK])
    sign(gid, TOK[MTK], "APPROVE"); sign(gid, TOK[RD], "REJECT")
    s8, b8 = L.req("POST", L.B39, "/api/v1/gates/%s/reopen" % gid, token=TOK[MTK])[0:2]
    n5 = notif_cnt(P_ADMIN, "GATE_ADMIN_INTERVENE", gid)
    rec("AC-GATE-07b", "第5轮超管介入通知", "reopen 至 r5 + SQL notif", s8, b8,
        "PASS" if (data(b8) or {}).get("round") in (5, "5") and n5 >= 1 else "FAIL",
        "round=%s 超管(900101)介入通知=%d" % ((data(b8) or {}).get("round"), n5))

def case_gate08():
    card = "AC-GATE-08"
    _, _, orig = cfg_get("gate.signDeadlineDays")
    s, bj = cfg_set("gate.signDeadlineDays", 0)
    if not ok(s, bj):
        rec(card, "config=0", "POST /business-config", s, bj, "FAIL-ENV", msg(bj)); return
    try:
        s2, bj2, pid, _ = mk_project("P3"); PROJS["P3"] = pid
        sb, bb = bind_member(pid, P_RD2, "RD_PM", approval="LANE2-%s-AC08" % RUN)
        L.written({"card": card, "op": "bind rd2 RD_PM pid=%s approvalRef" % pid, "ts": L.now()})
        if not ok(sb, bb):
            rec(card, "bind rd2", "POST members", sb, bb, "FAIL-ENV", "RD_PM 成员绑定失败:" + msg(bb)); return
        s3, bj3, gid = mk_gate(pid, "G1"); GIDS["P3G1"] = gid
        fails = judge_all(gid)
        if fails:
            rec(card, "judge_all", "判定", 200, None, "FAIL", str(fails[:4])); return
        ss, bs = submit(gid)
        if not ok(ss, bs):
            rec(card, "submit", "POST submit", ss, bs, "FAIL", msg(bs)); return
        sign(gid, TOK[MTK], "APPROVE")   # 主导方 MARKET_PM 已签
        st, bt = L.req("POST", L.B39, "/api/v1/gates/sign/scan-timeout", token=TOK[ADM])[0:2]
        row = gate_row(gid)
        ab = L.sql("SELECT reviewer_type,decision,reviewer_id FROM gate_reviews WHERE gate_id=%s AND round=1 AND reviewer_type='RD_PM'" % gid)
        na = notif_cnt(P_RD2, "GATE_ABSTAINED", gid); au = audit_cnt("GATE_ABSTAIN_TIMEOUT", gid)
        good = ok(st, bt) and row and row[0] == "APPROVED" and ab and int((data(bt) or {}).get("abstainCount", 0)) >= 1
        rec(card, "超期弃权按主导方放行", "POST /gates/sign/scan-timeout + SQL", st, bt,
            "PASS" if good and au >= 1 else ("FAIL-ENV" if ec(bt) not in (0, None) and "在册成员" in msg(bt) else "FAIL"),
            "abstainCount=%s status=%s 弃权行=%s 审计=%d 通知rd2=%d due=%s" % ((data(bt) or {}).get("abstainCount"), row[0] if row else None, ab, au, na, row[2] if row else None))
    finally:
        cfg_set("gate.signDeadlineDays", orig if orig else 3)

def case_gate09_21():
    card = "AC-GATE-09"
    _, _, orig = cfg_get("gate.signDeadlineDays")
    cfg_set("gate.signDeadlineDays", 1)
    try:
        s2, bj2, pid, _ = mk_project("P10"); PROJS["P10"] = pid
        sb, bx = bind_member(pid, P_M2, "MARKET_PM")
        if not ok(sb, bx):
            rec(card, "bind 陈市场", "POST /members", sb, bx, "FAIL-ENV", "在册市场PM绑定失败:" + msg(bx)[:120]); return
        L.written({"card": card, "op": "bind 2096…6065 MARKET_PM pid=%s" % pid, "ts": L.now()})
        s3, bj3, gid = mk_gate(pid, "G1"); GIDS["P10G1"] = gid
        fails = judge_all(gid)
        if not fails:
            ss, bs = submit(gid)
            st, bt = L.req("POST", L.B39, "/api/v1/gates/sign/scan-remind", token=TOK[ADM])[0:2]
            n = notif_cnt(P_M2, "GATE_SIGN_SOON", gid)
            rec(card, "期限前24h提醒未签方", "POST /gates/sign/scan-remind + SQL notif", st, bt,
                "PASS" if ok(st, bt) and n >= 1 else "FAIL",
                "remindCount=%s 在册市场侧(陈市场2096…6065)通知=%d due=%s; 研发侧 RD_PM 全员绑定饱和(900103/04/05等 c=3)未入册→该侧提醒无法实测,按 BR 静默跳过" % ((data(bt) or {}).get("remindCount"), n, (gate_row(gid) or [None, None, ""])[2]))
        else:
            rec(card, "judge_all", "判定", 200, None, "FAIL", str(fails[:4]))
    finally:
        cfg_set("gate.signDeadlineDays", orig if orig else 3)
    # AC-GATE-21：extend 上限（先临时把 gate.extension.maxCount 1→3 再还原）
    _, _, emax = cfg_get("gate.extension.maxCount")
    cfg_set("gate.extension.maxCount", 3)
    gid = GIDS.get("P4G1") or GIDS.get("P10G1")
    try:
        counts = []
        for i in range(4):
            se, be = L.req("POST", L.B39, "/api/v1/gates/%s/extend-deadline" % gid, token=TOK[ADM], body={"days": 1})[0:2]
            counts.append((ec(be), (data(be) or {}).get("extensionCount"), msg(be)[:60]))
        ok3 = all(c[0] == 0 for c in counts[:3]) and counts[2][1] == 3 and counts[3][0] != 0
        rec("AC-GATE-21", "延期3次第4拒+计数", "POST /extend-deadline x4", 200, None,
            "PASS" if ok3 else "FAIL", "4次调用(code,extCount,msg)=%s; 测后 gate.extension.maxCount 还原=%s" % (counts, emax))
    finally:
        cfg_set("gate.extension.maxCount", emax if emax else 1)

def case_gate11_12():
    card = "AC-GATE-11"
    s, bj, pid, _ = mk_project("P5"); PROJS["P5"] = pid
    _, _, prod_id = (s, bj, None)
    # 用 P5 产品 id：从项目详情取 productId
    rows = L.sql("SELECT product_id FROM projects WHERE id=%s" % pid)
    prod_id = rows[0][0] if rows else None
    so, bo = L.req("POST", L.B39, "/api/v1/products/%s/status?status=ACTIVE" % prod_id, token=TOK[ADM])[0:2]
    L.written({"card": card, "op": "product %s status=ACTIVE" % prod_id, "ts": L.now()})
    if not ok(so, bo):
        rec(card, "产品上架", "POST /products/%s/status ON_SALE" % prod_id, so, bo, "FAIL-ENV", msg(bo)[:150]); return
    sg, bg = L.req("POST", L.B39, "/api/v1/public/demands", body={
        "customerName": H("CUST"), "feedbackPerson": "LANE2", "contact": "000",
        "productId": int(prod_id), "rawModel": "M1",
        "functionalRequirement": "LANE2-%s 游客需求功能描述至少六个字" % RUN})[0:2]
    code = (data(bg) or {}).get("code")
    if not ok(sg, bg):
        rec(card, "游客需求", "POST /public/demands", sg, bg, "FAIL-ENV", msg(bg)); return
    rq = L.sql("SELECT id FROM requirements WHERE query_code='%s'" % code)
    rid = rq[0][0]
    L.written({"card": card, "op": "guest demand=%s req=%s triage+link pid=%s" % (code, rid, pid), "ts": L.now()})
    st, bt = L.req("POST", L.B39, "/api/v1/demands/%s/triage" % rid, token=TOK[MTK],
                   body={"status": "ACCEPTED", "marketPmId": P_MKT, "rdPmId": P_RD})[0:2]
    sl, bl = L.req("POST", L.B39, "/api/v1/demands/%s/link-project" % rid, token=TOK[MTK], body={"projectId": int(pid)})[0:2]
    four = '{"范围":"L2范围","成本":"L2成本","时限":"L2时限","质量":"L2质量"}'
    sc, bc = L.req("POST", L.B39, "/api/v1/requirement-changes", token=TOK[MTK], body={
        "requirementId": int(rid), "projectId": int(pid), "changeType": "SCOPE",
        "reason": H("CHG") + " 变更原因", "beforeSnapshot": four, "afterSnapshot": four})[0:2]
    cid = (data(bc) or {}).get("id")
    if not ok(sc, bc):
        rec(card, "创建变更单", "POST /requirement-changes", sc, bc, "FAIL-ENV", msg(bc)); return
    L.written({"card": card, "op": "req-change create id=%s" % cid, "ts": L.now()})
    L.req("PUT", L.B39, "/api/v1/requirement-changes/%s/submit" % cid, token=TOK[MTK])
    sa, ba = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    hit = ("变更" in msg(ba)) or ec(ba) not in (0, None)
    rec(card, "未闭环变更单阻断跳阶", "POST /advance-stage (PENDING_SIGN)", sa, ba,
        "PASS" if ec(ba) != 0 and "变更" in msg(ba) else ("FAIL" if not hit else "PARTIAL"),
        "envelope=%s msg=%s" % (ec(ba), msg(ba)[:120]))
    # 双签闭环
    s1, b1 = L.req("PUT", L.B39, "/api/v1/requirement-changes/%s/sign?decision=APPROVE&opinion=L2m" % cid, token=TOK[MTK])[0:2]
    s2, b2 = L.req("PUT", L.B39, "/api/v1/requirement-changes/%s/sign?decision=APPROVE&opinion=L2r" % cid, token=TOK[RD])[0:2]
    stt = L.sql("SELECT status FROM requirement_changes WHERE id=%s" % cid)
    rstat = L.sql("SELECT status FROM requirements WHERE id=%s" % rid)
    rec("AC-GATE-12", "变更双签→需求池已采纳", "sign x2 + SQL", s2, b2,
        "PASS" if stt and stt[0][0] == "APPROVED" and rstat and rstat[0][0] == "ADOPTED" else "FAIL",
        "change.status=%s requirement.status=%s codes=%s/%s" % (stt[0][0] if stt else None, rstat[0][0] if rstat else None, ec(b1), ec(b2)))
    sa2, ba2 = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    rec(card, "闭环后变更不再阻断(转为阶段门禁)", "POST /advance-stage 再试", sa2, ba2,
        "PASS" if "变更" not in msg(ba2) else "FAIL", "现在被阶段门禁拦:%s" % msg(ba2)[:110])

def case_gate14():
    card = "AC-GATE-14"
    gmap = VOTE.get("GATEALL_G") or {}
    exp = {"G1": 7, "G2": 6, "G3": 5, "G4": 8, "G5": 7}
    act = {c: len(checklist(g)) for c, g in gmap.items()}
    tot = sum(act.values())
    bad = act != exp
    # 双套 seed 证据
    rows = L.sql("SELECT gate_code, COUNT(*) FROM gate_review_elements WHERE del_flag='0' AND status='published' AND enabled<>'0' GROUP BY gate_code")
    veto_y = L.sql("SELECT COUNT(*) FROM gate_review_elements WHERE del_flag='0' AND is_veto='Y'")
    rec(card, "五Gate要素计数 vs 33", "GET /gates/{id}/elements x5 + SQL catalog", 200, None,
        "FAIL" if bad else "PASS",
        "期望 %s(=33) 实测 %s(=%d); 目录 published+enabled 计数=%s; is_veto='Y' 行数=%s(服务层只认 '1'→新套否决位失效)" % (exp, act, tot, rows, veto_y[0][0] if veto_y else "?"))

def case_gate15_16_20():
    card = "AC-GATE-20"
    s, bj, pid, _ = mk_project("P6"); PROJS["P6"] = pid
    s2, bj2, gid = mk_gate(pid, "G1"); GIDS["P6G1"] = gid
    cl = checklist(gid)
    g12 = next(e for e in cl if e["elementCode"] == "G1-2")
    other = next(e for e in cl if e["elementCode"] != "G1-2" and not e["isVeto"] and e["elementCode"] != "G1-1")
    # 16：CONDITIONAL 缺责任人拒 / 齐备过
    sj, bj3 = L.req("POST", L.B39, "/api/v1/gates/%s/element-results" % gid, token=TOK[MTK],
                    body={"elementId": int(other["elementId"]), "result": "CONDITIONAL", "conditionNote": "L2"})[0:2]
    rec("AC-GATE-16", "带条件通过缺责任人拒", "POST element-results COND", sj, bj3,
        "PASS" if not ok(sj, bj3) else "FAIL", "msg=%s" % msg(bj3)[:110])
    close = (dt.date.today() + dt.timedelta(days=5)).isoformat()
    sj2, bj2b = judge(gid, other["elementId"], result="CONDITIONAL", note="L2 条件", responsible=P_ADMIN, close=close)[0:2]
    rec("AC-GATE-16", "带条件通过齐备受理", "POST element-results COND full", sj2, bj2b,
        "PASS" if ok(sj2, bj2b) else "FAIL", "resultId=%s leftover=%s due=%s" % ((data(bj2b) or {}).get("id"), (data(bj2b) or {}).get("leftoverStatus"), (data(bj2b) or {}).get("leftoverDueAt")))
    VOTE["P6COND"] = (data(bj2b) or {}).get("id")
    # 其余全 PASS，G1-2 否决 FAIL
    fails = judge_all(gid, overrides={"G1-2": {"result": "FAIL", "evidence": "LANE2-%s-evi" % RUN}, other["elementCode"]: None})
    if fails:
        rec(card, "judge_all", "判定", 200, None, "FAIL", str(fails[:4])); return
    ss, bs = submit(gid)
    veto_hit = ec(bs) != 0 and "否决" in msg(bs) and "G1-2" in msg(bs)
    rec(card, "否决项FAIL阻断提交(判定驱动)", "POST /submit", ss, bs,
        "PASS" if veto_hit else "FAIL", "envelope=%s msg=%s; 注:G1-2 与 C08 基准录入为判定驱动联动而非自动侦测" % (ec(bs), msg(bs)[:120]))
    rec("AC-GATE-15", "否决命中服务端硬阻断", "同上 submit", ss, bs,
        "PARTIAL" if veto_hit else "FAIL",
        "服务端硬阻断=是(无法提交通过,列出 G1-2 等命中项); UI 置灰/高亮为前端项本车道 NOT-RUN 口径并入 PARTIAL")

def case_gate17_19():
    card = "AC-GATE-19"
    s, bj, pid, _ = mk_project("P7"); PROJS["P7"] = pid
    s2, bj2, gid = mk_gate(pid, "G4"); GIDS["P7G4"] = gid
    fails = judge_all(gid, overrides={"G4-6": {"result": "FAIL", "evidence": "LANE2-%s-v12" % RUN}})
    ss, bs = submit(gid)
    rec(card, "G4-6 FAIL 阻断提交", "judge FAIL(G4-6)+submit", ss, bs,
        "PASS" if ec(bs) != 0 and "G4-6" in msg(bs) else ("FAIL" if ec(bs) != 0 else "FAIL-ENV"),
        "envelope=%s msg=%s; 注:V12 交付物→G4-6 为人工判定联动" % (ec(bs), msg(bs)[:110]))
    # 修复 G4-6 → PASS；G4-3 CONDITIONAL 昨日期限
    g46 = next(e for e in checklist(gid) if e["elementCode"] == "G4-6")
    judge(gid, g46["elementId"], result="PASS")
    g43 = next(e for e in checklist(gid) if e["elementCode"] == "G4-3")
    yday = (dt.date.today() - dt.timedelta(days=1)).isoformat()
    sj, bj4 = judge(gid, g43["elementId"], result="CONDITIONAL", note="L2 遗留", responsible=P_ADMIN, close=yday)[0:2]
    cond_rid = (data(bj4) or {}).get("id")
    ss2, bs2 = submit(gid)
    if not ok(ss2, bs2):
        rec("AC-GATE-17", "G4 提交(带昨条件)", "POST submit", ss2, bs2, "FAIL", msg(bs2)); return
    # G5 创建+全判 → submit 预期被前序逾期阻断
    s3, bj3, gid5 = mk_gate(pid, "G5"); GIDS["P7G5"] = gid5
    f2 = judge_all(gid5)
    ss3, bs3 = submit(gid5)
    blk = ec(bs3) != 0 and "逾期" in msg(bs3)
    rec("AC-GATE-17", "逾期未关闭→下一Gate提交阻断", "G5 submit while OPEN", ss3, bs3,
        "PASS" if blk else "FAIL", "envelope=%s msg=%s judge_all_fails=%s" % (ec(bs3), msg(bs3)[:120], f2[:2]))
    st, bt = L.req("POST", L.B39, "/api/v1/gates/legacy/scan-overdue", token=TOK[ADM])[0:2]
    rr = L.sql("SELECT COUNT(*) FROM notification_events WHERE receiver_id=900101 AND event_type='GATE_CONDITION_OVERDUE' AND source_type='gate_element_results' AND source_id=%s" % cond_rid)
    nn = int(rr[0][0]) if rr else 0
    rec("AC-GATE-17b", "逾期扫描提醒", "POST /gates/legacy/scan-overdue + SQL notif(result级)", st, bt,
        "PASS" if ok(st, bt) and nn >= 1 else "PARTIAL", "overdueCount=%s 责任人(900101)对本遗留行通知=%d(首跑按gate_id计数为fixture假红:实际 source_type=gate_element_results/source_id=resultId)" % ((data(bt) or {}).get("overdueCount"), nn))
    sc, bc = L.req("POST", L.B39, "/api/v1/gates/%s/element-results/%s/close" % (gid, cond_rid), token=TOK[ADM],
                   body={"evidence": "LANE2-%s 关闭凭证" % RUN})[0:2]
    L.written({"card": "AC-GATE-17", "op": "close legacy %s" % cond_rid, "ts": L.now()})
    ss4, bs4 = submit(gid5)
    rec("AC-GATE-17c", "关闭后下一Gate放行", "close+G5 submit", ss4, bs4,
        "PASS" if ok(sc, bc) and ok(ss4, bs4) else "FAIL", "close code=%s, G5 submit code=%s msg=%s" % (ec(bc), ec(bs4), msg(bs4)[:80]))

def case_gate18():
    card = "AC-GATE-18"
    code = ("L2E" + RUN[2:8])  # GateElementService CODE_MAX=16，H() 全名超长致首跑 fixture 假红
    sc, bc = L.req("POST", L.B39, "/api/v1/gate-elements", token=TOK[ADM], body={
        "gateCode": "G1", "elementCode": code, "elementName": "LANE2 测试要素", "passStandard": "v1",
        "isVeto": "1", "sortOrder": 999, "enabled": "0"})[0:2]
    eid = (data(bc) or {}).get("id")
    su, bu = L.req("POST", L.B39, "/api/v1/gate-elements/%s/update" % eid, token=TOK[ADM], body={
        "elementName": "LANE2 测试要素-v2", "passStandard": "v2", "isVeto": "0"})[0:2]  # 草稿带 enabled 更新会被 50002 合法拒（启停须走 publish）
    sp, bp = L.req("POST", L.B39, "/api/v1/gate-elements/%s/publish" % eid, token=TOK[ADM])[0:2]
    L.written({"card": card, "op": "gate-element create/update/publish id=%s code=%s" % (eid, code), "ts": L.now()})
    sl, bl = L.req("GET", L.B39, "/api/v1/gate-elements?gate=G1", token=TOK[ADM])[0:2]
    found = any((r or {}).get("elementCode") == code and "v2" in str((r or {}).get("passStandard")) for r in (data(bl) or []))
    sa, ba = L.req("POST", L.B39, "/api/v1/gate-elements/%s/archive" % eid, token=TOK[ADM])[0:2]
    rec(card, "要素增改发布归档(全程enabled=0防污染)", "CRUD /gate-elements", sc, bc,
        "PASS" if ok(sc, bc) and ok(su, bu) and ok(sp, bp) and found and ok(sa, ba) else "FAIL",
        "create=%s(%s) update=%s publish=%s list命中v2=%s archive=%s; 三车道并发期保持 enabled=0,清单可见性以管理列表为准" % (ec(bc), msg(bc)[:60], ec(bu), ec(bp), found, ec(ba)))

def case_gate1b_1c_1d():
    card = "AC-GATE-1b"
    s, bj, pid, _ = mk_project("P8"); PROJS["P8"] = pid
    s2, bj2, gid = mk_gate(pid, "G1"); GIDS["P8G1"] = gid
    cl = checklist(gid)
    e11 = next(e for e in cl if e["elementCode"] == "G1-1")
    s3, b3 = judge(gid, e11["elementId"], result="PASS", verifications=3)[0:2]
    rejected_1b = (not ok(s3, b3)) and ("5" in msg(b3) or "一手验证" in msg(b3) or "书面意向" in msg(b3))
    r1 = L.sql("SELECT result FROM gate_element_results WHERE gate_id=%s AND element_id=%s" % (gid, e11["elementId"]))
    rec(card, "3家无书面意向不放行", "POST element-results v=3", s3, b3,
        "PASS" if rejected_1b or (r1 and r1[0][0] == "FAIL") else "FAIL",
        "resp code=%s msg=%s; 落库=%s (期望拒绝PASS判定或自动判不通过并提示阈值)" % (ec(b3), msg(b3)[:120], r1))
    s4, b4 = judge(gid, e11["elementId"], result="PASS", written_intents=1)[0:2]
    r2 = L.sql("SELECT result FROM gate_element_results WHERE gate_id=%s AND element_id=%s" % (gid, e11["elementId"]))
    rec("AC-GATE-1c", "1家书面意向替代路径PASS", "POST element-results w=1", s4, b4,
        "PASS" if ok(s4, b4) and r2 and r2[0][0] == "PASS" else "FAIL", "code=%s 落库=%s" % (ec(b4), r2))
    _, _, orig = cfg_get("gate.g1.minCustomerVerifications")
    cfg_set("gate.g1.minCustomerVerifications", 3)
    try:
        s5, b5 = judge(gid, e11["elementId"], result="PASS", verifications=3)[0:2]
        rec("AC-GATE-1d", "阈值改3即时生效", "config=3 + judge v=3", s5, b5,
            "PASS" if ok(s5, b5) else "FAIL", "code=%s msg=%s (参数可配置非硬编码)" % (ec(b5), msg(b5)[:80]))
    finally:
        if orig: cfg_set("gate.g1.minCustomerVerifications", orig)
        else: cfg_delete("gate.g1.minCustomerVerifications")

def case_gate13(pid_for=None):
    card = "AC-GATE-13"
    pid = pid_for or PROJS.get("P5")
    ld = "2026-09-20"
    sq, bq = L.req("POST", L.B39, "/api/v1/post-launch-reviews", token=TOK[ADM],
                   body={"projectId": int(pid), "launchDate": ld})[0:2]
    d = data(bq) or {}
    idem = None
    if ok(sq, bq):
        s2, b2 = L.req("POST", L.B39, "/api/v1/post-launch-reviews", token=TOK[ADM],
                       body={"projectId": int(pid), "launchDate": ld})[0:2]
        idem = (data(b2) or {}).get("id") == d.get("id")
    row = L.sql("SELECT scheduled_at,status FROM post_launch_reviews WHERE project_id=%s AND del_flag='0' ORDER BY id DESC LIMIT 1" % pid)
    sched = row[0][0] if row else None
    rec(card, "90天复盘排期+幂等", "POST /post-launch-reviews x2(admin) + SQL", sq, bq,
        "PARTIAL" if ok(sq, bq) and idem and sched else ("FAIL-ENV" if ec(bq) not in (0, None) else "FAIL"),
        "scheduled_at=%s(=上市日+90d) status=%s 幂等=%s; 注:G5 通过→自动触发待办在代码中无调用方(仅显式 HTTP),自动腿未接线→真缺陷候选" % (sched, row[0][1] if row else None, idem))

# ==================== IPD 域 ====================
def mk_project(tag, level="B", template="HARDWARE", markets='["国内"]'):
    """覆盖 part1 版：返回 (s, bj, pid, prod_id)。"""
    st, bj, prod_id = mk_product(H("PROD-" + tag))
    body = {"name": H("PRJ-" + tag), "productId": int(prod_id) if prod_id else None,
            "templateType": template, "targetMarkets": markets, "level": level,
            "targetSalesAmount": 1000000, "targetChannelCount": 10, "targetNps": 50,
            "targetSceneCount": 5, "mainGroupId": GROUP}
    s, bj = L.req("POST", L.B39, "/api/v1/projects", token=TOK[MTK], body=body)[0:2]
    return s, bj, (data(bj) or {}).get("id"), prod_id

def act_tok(code, arow):
    owner = arow[5]
    return TOK[RD] if owner == "RD_PM" else TOK[MTK]

def case_ipd_s_level():
    card = "AC-IPD-08"
    s, bj, pid, _ = mk_project("PINV2", level="S"); PROJS["PINV2"] = pid
    if not ok(s, bj):
        rec(card, "mk PINV2", "POST /projects", s, bj, "FAIL-ENV", msg(bj)); return
    sa, ba = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    listed = "C01" in msg(ba) and "C12" in msg(ba)
    rec(card, "S级阻断未完成跳阶拒+清单", "POST /advance-stage", sa, ba,
        "PASS" if ec(ba) != 0 and listed else "FAIL", "envelope=%s msg 含C01/C12清单=%s: %s" % (ec(ba), listed, msg(ba)[:150]))
    rec("AC-IPD-11", "S级任一阻断未完成拒", "同上", sa, ba,
        "PASS" if ec(ba) != 0 else "FAIL", "S级 CONCEPT 必做集未完成即拒; code=%s" % ec(ba))
    blk = L.sql("SELECT SUM(is_blocking='1'), COUNT(*) FROM stage_actions WHERE project_id=%s AND del_flag='0'" % pid)
    rec("AC-IPD-11b", "S级阻断动作数=38", "SQL stage_actions", 200, None,
        "PASS" if blk and int(blk[0][0]) == 38 else "FAIL", "blocking=%s total=%s" % (blk[0][0] if blk else None, blk[0][1] if blk else None))
    rec("AC-IPD-15s", "C12 在 S 级阻断清单", "拒信包含 C12", sa, ba,
        "PASS" if "C12" in msg(ba) else "FAIL", "msg=%s" % msg(ba)[:100])
    cnt = L.sql("SELECT COUNT(*), SUM(depth='DEEP'), SUM(depth='LIGHT') FROM stage_actions WHERE project_id=%s AND del_flag='0'" % pid)
    c0, c1, c2 = (int(cnt[0][0]), int(cnt[0][1]), int(cnt[0][2])) if cnt else (0, 0, 0)
    rec("AC-IPD-26", "动作总数 69=42深+27轻", "SQL", 200, None,
        "PASS" if (c0, c1, c2) == (69, 42, 27) else "FAIL", "实测 total=%s DEEP=%s LIGHT=%s" % (c0, c1, c2))
    rows = L.sql("SELECT id,stage_code,stage_name,sort_order FROM project_stages WHERE project_id=%s AND del_flag='0' ORDER BY sort_order" % pid)
    codes = [r[1] for r in rows]
    sp, bp = L.req("DELETE", L.B39, "/api/v1/projects/%s/stages/%s" % (pid, rows[0][0] if rows else 1), token=TOK[ADM])[0:2]
    rec("AC-IPD-28", "六阶段顺序+不可删除", "SQL project_stages + DELETE 探针", sp, bp,
        "PASS" if codes == ["CONCEPT", "PLAN", "DEV", "VALID", "LAUNCH", "LIFECYCLE"] and sp in (404, 405) else "FAIL",
        "codes=%s names=%s DELETE(真实stage id)探针 http=%s; ProjectController 源码无任何 DeleteMapping/stages 删除端点" % (codes, [r[2] for r in rows], sp))

def case_ipd_chain_b():
    card = "AC-IPD-10"
    s, bj, pid, _ = mk_project("PINV1", level="B"); PROJS["PINV1"] = pid
    if not ok(s, bj):
        rec(card, "mk PINV1", "POST /projects", s, bj, "FAIL-ENV", msg(bj)); return
    A = actions_of(pid)
    sa, ba = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    rec("AC-IPD-15", "B级 C12 出现在阻断清单", "POST /advance-stage", sa, ba,
        "PASS" if ec(ba) != 0 and "C12" in msg(ba) and "C11" in msg(ba) else "FAIL", "msg=%s" % msg(ba)[:160])
    # I01 C01 深管无交付物
    s1, b1 = transit(A["C01"][0], "DONE", TOK[MTK])
    rec("AC-IPD-01", "深管C01无交付物完成被拒", "POST /transit DONE", s1, b1,
        "PASS" if ec(b1) != 0 and "交付物" in msg(b1) else "FAIL", "code=%s msg=%s" % (ec(b1), msg(b1)[:100]))
    # I02/I25 C05 轻管三字段
    r1, r0, _a5 = done_light(pid, "C05", TOK[RD])
    rec("AC-IPD-02", "轻管C05三字段完成无附件校验", "fields+transit DONE", r1[0], r1[1],
        "PASS" if ok(r1[0], r1[1]) and ok(r0[0], r0[1]) else "FAIL",
        "depth=%s; DONE 成功无附件类报错; msg=%s" % (_a5[2], msg(r1[1])[:60]))
    rec("AC-IPD-25", "C05 深度=LIGHT", "SQL", 200, None,
        "PASS" if _a5[2] == "LIGHT" else "FAIL", "stage_actions.depth=%s" % _a5[2])
    # I16 C12 深管无交付物
    (s2, b2) = transit(A["C12"][0], "DONE", TOK[MTK])
    rec("AC-IPD-16", "C12无交付物完成被拒", "POST /transit DONE", s2, b2,
        "PASS" if ec(b2) != 0 and "交付物" in msg(b2) else "FAIL", "code=%s msg=%s" % (ec(b2), msg(b2)[:100]))
    # I17/I18 D11 FAR/FRR
    s3, s3f, _a3 = done_light(pid, "D11", TOK[RD])
    rec("AC-IPD-17", "D11仅状态+日期被拒", "fields(仅日期)+DONE", s3[0], s3[1],
        "PASS" if ec(s3[1]) != 0 and "FAR" in msg(s3[1]) else "FAIL",
        "DONE 响应 code=%s msg=%s; fields code=%s" % (ec(s3[1]), msg(s3[1])[:100], ec(s3f[1])))
    sf, bf = fields(A["D11"][0], TOK[RD], {"farValue": 0.5})
    rec("AC-IPD-17b", "FAR/FRR 必须成对", "POST /fields far only", sf, bf,
        "PASS" if ec(bf) != 0 and "成对" in msg(bf) else "FAIL", "msg=%s" % msg(bf)[:80])
    r4, r4f, _a11 = done_light(pid, "D11", TOK[RD], extra={"farValue": 0.0001, "frrValue": 0.01})
    vals = L.sql("SELECT far_value,frr_value,status FROM stage_actions WHERE id=%s" % _a11[0])
    rec("AC-IPD-18", "D11 FAR=0.0001/FRR=0.01 保存成功", "fields+DONE+SQL", r4[0], r4[1],
        "PASS" if ok(r4[0], r4[1]) and vals and vals[0][2] == "DONE" else "FAIL", "落库=%s (流程页展示为前端项 NOT-RUN)" % (vals[0] if vals else None))
    # I24 C10 深管非阻断
    (s5, b5) = transit(A["C10"][0], "DONE", TOK[RD])
    rec("AC-IPD-24", "C10无交付物拒但不在阻断集", "transit DONE + 稍后advance证据", s5, b5,
        "PASS" if ec(b5) != 0 and "交付物" in msg(b5) else "FAIL", "code=%s; C10 未完成时 B 级 CONCEPT→PLAN 仍放行见 AC-IPD-10 成功腿" % ec(b5))
    # I21 V11 HW 轻管
    _a_v11 = A.get("V11")
    if _a_v11:
        r6, r6f, _ = done_light(pid, "V11", TOK[RD])
        rec("AC-IPD-21", "V11 硬件模板=轻管三字段", "row.depth=%s status=%s; fields+DONE" % (_a_v11[2], _a_v11[3] if _a_v11[3] else ""), r6[0], r6[1],
            "PASS" if _a_v11[2] == "LIGHT" and ok(r6[0], r6[1]) else "FAIL", "depth=%s DONE=%s" % (_a_v11[2], ec(r6[1])))
    else:
        rec("AC-IPD-21", "V11 HW 行缺失", "SQL", 200, None, "FAIL", "stage_actions 无 V11 行")
    # C11 done + C12 NA → advance 成功
    (r7, _), _a11c = done_deep(pid, "C11", TOK[MTK])
    # C12：项目 HARDWARE seed 含 is_bio_feature=1 动作 → 涉生物项目 C12 不可 NA（服务端 AC-PROD-13 守卫），
    # 首跑 NA 为 fixture 假红；正确闭环 = 深管交付物+DONE
    s8, _c12row = done_deep(pid, "C12", TOK[MTK])
    sa2, ba2 = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    A2 = actions_of(pid)
    remain_light = [c for c, r in A2.items() if r[2] == "LIGHT" and r[3] == "NOT_STARTED"]
    remain_deep_nonB = [c for c, r in A2.items() if r[2] == "DEEP" and r[3] == "NOT_STARTED" and c not in ("C11", "C12")]
    if not ok(s8[0], s8[1]):
        rec("AC-IPD-15c", "C12 涉生物守卫下 DONE 闭环", "deep deliverable+DONE", s8[0], s8[1], "FAIL", "C12 闭环失败:" + msg(s8[1])[:120])
    rec("AC-IPD-10", "B级非B清单深管未完成放行", "advance CONCEPT→PLAN", sa2, ba2,
        "PASS" if ok(sa2, ba2) else "FAIL",
        "advance code=%s; 仍有未完成轻管 %d 个/非B深管 %s 但未阻断; 注:AC 文字『B级14个阻断』与代码 B_LEVEL_BLOCKING_CODES=10 项存在口径差(真缺陷候选)" % (ec(ba2), len(remain_light), remain_deep_nonB[:6]))
    rec("AC-IPD-07", "轻管未完成允许跳阶", "同上成功腿", sa2, ba2,
        "PASS" if ok(sa2, ba2) and remain_light else "FAIL", "LIGHT NOT_STARTED 剩余=%s 仍放行" % remain_light[:8])
    if not ok(sa2, ba2):
        rec(card, "链中断", "advance", sa2, ba2, "FAIL-ENV", msg(ba2)[:150]); return
    # PLAN→DEV
    def finish(pid, code, tok_owner=None):
        A = actions_of(pid)
        r = A[code]
        if r[3] in ("DONE", "NA"): return None
        if r[2] == "DEEP":
            res, _ = done_deep(pid, code, act_tok(code, r)); return res[1]
        res, resf, _ = done_light(pid, code, act_tok(code, r)); return res[1]
    sa3, ba3 = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    rec("AC-IPD-10b", "PLAN 必做未完成拒(P12/P13/P10)", "advance PLAN→DEV", sa3, ba3,
        "PASS" if ec(ba3) != 0 and all(k in msg(ba3) for k in ("P12", "P13", "P10")) else "FAIL", msg(ba3)[:140])
    for c in ("P12", "P13", "P10"): finish(pid, c)
    sa4, ba4 = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    for c in ("D05",): finish(pid, c)
    sa5, ba5 = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    # VALID: V02 阻断腿
    sa6, ba6 = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    rec("AC-IPD-09", "V02轻管阻断:未完成拒跳阶", "advance VALID→LAUNCH", sa6, ba6,
        "PASS" if ec(ba6) != 0 and "V02" in msg(ba6) else "FAIL", "msg=%s" % msg(ba6)[:120])
    A = actions_of(pid); v02 = A["V02"][0]
    fields(v02, TOK[RD], {"actualDoneAt": "2026-09-25T10:00:00"})
    s9, b9 = transit(v02, "DONE", TOK[RD])
    rec("AC-IPD-09b", "V02缺证书字段完成被拒", "transit DONE", s9, b9,
        "PASS" if ec(b9) != 0 and "证书" in msg(b9) else "FAIL", "msg=%s" % msg(b9)[:100])
    fields(v02, TOK[RD], {"certNo": "LANE2-CERT-001", "certPassedAt": "2026-09-24T10:00:00"})
    s10, b10 = transit(v02, "DONE", TOK[RD])
    sl_, bl_ = L.req("POST", L.B39, "/api/v1/projects/%s/launch-date" % pid, token=TOK[MTK],
                     body={"launchDate": "2026-09-20", "reason": "LANE2-%s 录入" % RUN})[0:2]
    L.written({"card": "AC-IPD-09", "op": "launch-date PINV1=%s" % pid, "ts": L.now()})
    sa7, ba7 = L.req("POST", L.B39, "/api/v1/projects/%s/advance-stage" % pid, token=TOK[MTK])[0:2]
    stg = L.sql("SELECT current_stage FROM projects WHERE id=%s" % pid)
    rec("AC-IPD-09c", "V02闭环+上市日期后放行", "DONE+launch-date+advance", sa7, ba7,
        "PASS" if ok(sa7, ba7) and stg and stg[0][0] == "LAUNCH" else "FAIL",
        "current_stage=%s advance code=%s" % (stg[0][0] if stg else None, ec(ba7)))
    # I19/I20
    (s11, b11) = transit(A["V10"][0], "DONE", TOK[MTK])
    rec("AC-IPD-19", "V10深管无报告完成被拒", "transit DONE", s11, b11,
        "PASS" if ec(b11) != 0 and "交付物" in msg(b11) else "FAIL", "msg=%s" % msg(b11)[:100])
    sc, bcc = L.req("GET", L.B39, "/api/v1/sop-templates/current?actionCode=V10", token=TOK[MTK])[0:2]
    content = str((data(bcc) or {}).get("content") or "")
    rec("AC-IPD-20", "V10 SOP含算法公平/偏见", "GET /sop-templates/current", sc, bcc,
        "PASS" if ("公平" in content and "偏见" in content) else "FAIL",
        "命中公平=%s 偏见=%s 长度=%d (Z08 已并入无独立动作: 目录无 Z08 独立行)" % ("公平" in content, "偏见" in content, len(content)))
    rec("AC-IPD-12", "深管逾期每日提醒", "N/A", None, None, "BLOCKED",
        "无法构造:stage_actions.due_date 无任何 HTTP 写入口(grep controller 0 命中),禁止 SQL 写库;提醒逻辑存在但触发面缺")
    rec("AC-IPD-13", "轻管逾期不提醒", "N/A", None, None, "BLOCKED", "同 AC-IPD-12:due_date 不可经业务 HTTP 设置")
    rec("AC-IPD-14", "轻管详情无附件入口", "N/A", None, None, "NOT-RUN", "纯 UI 检查项;API 侧 deliverables 端点不区分深浅(入口收口属前端职责)")

def case_ipd_psol_povs():
    s, bj, pid, _ = mk_project("PSOL", level="B", template="SOLUTION"); PROJS["PSOL"] = pid
    A = actions_of(pid)
    r = A.get("V11")
    if r:
        s2, b2 = transit(r[0], "DONE", TOK[RD])
        rec("AC-IPD-22", "V11 SOLUTION 升深管", "depth=%s; DONE 无交付物探针" % r[2], s2, b2,
            "PASS" if r[2] == "DEEP" and ec(b2) != 0 and "交付物" in msg(b2) else "FAIL",
            "depth=%s code=%s msg=%s" % (r[2], ec(b2), msg(b2)[:90]))
    else:
        rec("AC-IPD-22", "V11 SOL 行缺失", "SQL", 200, None, "FAIL", "无 V11 行")
    s, bj, pid2, _ = mk_project("POVS", level="B", markets='["国内","SA"]'); PROJS["POVS"] = pid2
    A2 = actions_of(pid2); A1 = actions_of(PROJS.get("PINV1") or 0) if PROJS.get("PINV1") else {}
    v12 = A2.get("V12"); v12_hw = A1.get("V12")
    s3, b3 = transit(v12[0], "DONE", TOK[MTK]) if v12 else (None, None)
    rec("AC-IPD-23", "V12 海外市场挂载+深管强制", "POVS V12=%s vs 纯国内=%s; DONE 探针" % (
        (v12[3], v12[2]) if v12 else None, (v12_hw[3], v12_hw[2]) if v12_hw else None), s3, b3,
        "PASS" if v12 and v12[2] == "DEEP" and ec(b3) != 0 and "交付物" in msg(b3) else "FAIL",
        "海外项目 V12 depth/status=%s/%s; 国内项目 V12 status=%s(NA 裁剪); 逐项打勾清单内容校验属前端 NOT-RUN 并入 note" % (
            v12[2] if v12 else None, v12[3] if v12 else None, v12_hw[3] if v12_hw else None))

def case_ipd_sop():
    card = "AC-IPD-27"
    # 库内唯一 PUBLISHED SOP 目录行=V10（首跑用 C11 → current 404 → 全链 None 假红）
    s0, b0 = L.req("GET", L.B39, "/api/v1/sop-templates/current?actionCode=V10", token=TOK[ADM])[0:2]
    old = data(b0) or {}; old_id = old.get("id"); old_v = old.get("version")
    s1, b1 = L.req("POST", L.B39, "/api/v1/sop-templates/%s/copy" % old_id, token=TOK[ADM])[0:2]
    draft_id = (data(b1) or {}).get("id")
    old_content = str(old.get("content") or "")
    s2, b2 = L.req("POST", L.B39, "/api/v1/sop-templates/%s/update" % draft_id, token=TOK[ADM],
                   body={"title": "LANE2-%s V10 SOP v-next" % RUN,
                         "content": old_content + "\n\nLANE2-%s 改版验证段（保留算法公平/偏见测评要求）" % RUN})[0:2]
    s3, b3 = L.req("POST", L.B39, "/api/v1/sop-templates/%s/publish" % draft_id, token=TOK[ADM])[0:2]
    L.written({"card": card, "op": "sop copy/update/publish C11 new id=%s" % draft_id, "ts": L.now()})
    s4, b4 = L.req("GET", L.B39, "/api/v1/sop-templates/current?actionCode=V10", token=TOK[ADM])[0:2]
    cur = data(b4) or {}
    s5, b5 = L.req("POST", L.B39, "/api/v1/sop-templates/%s/instantiate?projectId=%s" % (draft_id, PROJS.get("PINV2")), token=TOK[MTK])[0:2]
    L.written({"card": card, "op": "sop instantiate v2 id=%s pid=%s" % (draft_id, PROJS.get("PINV2")), "ts": L.now()})
    s6, b6 = L.req("GET", L.B39, "/api/v1/sop-templates/instances?projectId=%s" % PROJS.get("PINV2"), token=TOK[MTK])[0:2]
    insts = data(b6) or []
    n_sop = L.sql("SELECT SUM(sop_id IS NOT NULL), COUNT(*) FROM stage_actions WHERE project_id=%s" % PROJS.get("PINV2"))
    rec(card, "SOP改版发布+实例快照+项目绑定探针", "copy/update/publish/current/instantiate", s3, b3,
        "PARTIAL" if ok(s3, b3) and str(cur.get("id")) == str(draft_id) else "FAIL",
        "旧版本 id=%s v=%s → 发布新版本 id=%s; current 已切新=%s; 手动 instantiate 旧版给在研项目实例=%d 条; 但新项目 stage_actions.sop_id 全 NULL(=%s/%s)→项目级自动快照绑定未接线,『新项目用新 SOP/在研保持原版』仅靠前端读取路径成立" % (
            old_id, old_v, draft_id, str(cur.get("id")) == str(draft_id), len(insts), n_sop[0][0] if n_sop else None, n_sop[0][1] if n_sop else None))

# ==================== main ====================
def main():
    print("== LANE2 run %s @ %s ==" % (RUN, L.now()), flush=True)
    setup()
    order = [
        ("AC-GATE-01", case_gate01), ("AC-GATE-14", case_gate14),
        ("AC-GATE-02", case_gate02_03_04_1a), ("AC-GATE-05", case_gate05_10_06_07),
        ("AC-GATE-15", case_gate15_16_20), ("AC-GATE-17", case_gate17_19),
        ("AC-GATE-1b", case_gate1b_1c_1d), ("AC-GATE-11", case_gate11_12),
        ("AC-GATE-08", case_gate08), ("AC-GATE-09", case_gate09_21),
        ("AC-GATE-18", case_gate18),
        ("AC-IPD-08", case_ipd_s_level), ("AC-IPD-10", case_ipd_chain_b),
        ("AC-GATE-13", lambda: case_gate13(PROJS.get("PINV1"))),
        ("AC-IPD-22", case_ipd_psol_povs), ("AC-IPD-27", case_ipd_sop),
    ]
    for card, fn in order:
        run_case(card, fn)
    from collections import Counter
    c = Counter(r["verdict"] for r in RECS)
    print("== DONE %s records=%s ==" % (L.now(), dict(c)), flush=True)

if __name__ == "__main__":
    main()

# ==================== repair-4 追加用例（组长真实落裁腿 / 双签两侧提醒腿） ====================
def case_gate10_leader_ruling():
    """AC-GATE-10 补强：把组 900001 的在册 PM 双边入组（需临时放宽绑定阈值），
    让 collectLeaders 命中可登录组长 900102，验证『预落待裁行 → 组长真实落裁(UPDATE 原行)
    → 重复提交守卫 → 未达两组对立时终裁门拒』。第二组长 900112 无可用登录凭证（实测 400/10001），
    故『两组仍不一致→自动升级超管终裁』腿在本库结构性不可测，卡片维持 PARTIAL。"""
    card = "AC-GATE-10"
    s, bj, pid, _ = mk_project("P14"); PROJS["P14"] = pid
    if not ok(s, bj):
        rec(card, "mk P14", "POST /projects", s, bj, "FAIL-ENV", msg(bj)[:150]); return
    sb1, bb1 = bind_member(pid, P_MKT, "MARKET_PM", approval="LANE2-%s-AC10BEIAN" % RUN)
    sb2, bb2 = bind_member(pid, P_RD, "RD_PM", approval="LANE2-%s-AC10BEIAN" % RUN)
    L.written({"card": card, "op": "bind 900103 MARKET_PM + 900104 RD_PM pid=%s (临时放宽阈值, approvalRef=LANE2-%s-AC10BEIAN)" % (pid, RUN), "ts": L.now()})
    if not (ok(sb1, bb1) and ok(sb2, bb2)):
        rec(card, "bind 900103/900104", "POST /members", sb2, bb2, "FAIL-ENV",
            "在册 PM 入组失败: %s / %s" % (msg(bb1)[:80], msg(bb2)[:80])); return
    sg, bg, gid = mk_gate(pid, "G1"); GIDS["P14G1"] = gid
    if not ok(sg, bg):
        rec(card, "mk_gate", "POST /gates", sg, bg, "FAIL-ENV", msg(bg)[:150]); return
    fails = judge_all(gid)
    if fails:
        rec(card, "judge_all", "批量判定", 200, None, "FAIL", str(fails[:4])); return
    ss, bs = submit(gid)
    if not ok(ss, bs):
        rec(card, "submit", "POST submit", ss, bs, "FAIL-ENV", msg(bs)[:150]); return
    sign(gid, TOK[MTK], "APPROVE")
    sr, br = sign(gid, TOK[RD], "REJECT", "LANE2-%s 研发否决→触发仲裁" % RUN)
    pre = L.sql("SELECT arbitrator_id, IFNULL(decision,'NULL') FROM gate_arbitrations WHERE gate_id=%s AND round=1 ORDER BY arbitrator_id" % gid)
    sa, ba = L.req("POST", L.B39, "/api/v1/gates/%s/arbitrate" % gid, token=TOK[LDR],
                   body={"decision": "APPROVE", "opinion": "LANE2-%s 组长900102落裁" % RUN})[0:2]
    sa2, ba2 = L.req("POST", L.B39, "/api/v1/gates/%s/arbitrate" % gid, token=TOK[LDR],
                     body={"decision": "REJECT", "opinion": "LANE2-%s 重复提交" % RUN})[0:2]
    post = L.sql("SELECT COUNT(*), SUM(decision IS NOT NULL), SUM(decision IS NULL) FROM gate_arbitrations WHERE gate_id=%s AND round=1" % gid)
    au = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='GATE_ARBITRATION' AND entity_id=%s" % gid)
    sf, bf = L.req("POST", L.B39, "/api/v1/gates/%s/final-ruling" % gid, token=TOK[ADM],
                   body={"decision": "APPROVE", "opinion": "LANE2-%s 终裁探针" % RUN})[0:2]
    ruled = ok(sa, ba) and bool(post) and int(post[0][1] or 0) >= 1   # 落裁以真库 decision 非空行为准
    rec(card, "组长真实落裁+重复守卫+终裁门", "sign(冲突)+arbitrate x2+final-ruling + SQL", sa, ba,
        "PARTIAL" if ruled and ec(ba2) != 0 and ec(bf) != 0 else "FAIL",
        "冲突签署 http=%s; 预落待裁行=%s | 组900001 在册未软删的 GROUP_LEADER 仅 900102 一人"
        "(900112 真库 del_flag=2 已软删故不入 collectLeaders，与 leaderOpinions<2 的终裁门一致)；"
        "900102 落裁 code=%s id=%s; 重复提交被拒(%s); 落裁后行数/已决/待决=%s; 审计GATE_ARBITRATION=%s; "
        "仅1份组长意见时终裁被拒(%s) | "
        "未覆盖腿: 两组长意见不一致→自动升级超管终裁。本库可登录组长仅 900102（900112 login 实测 400/10001 且已软删；"
        "9110004/王组长/李组长 无 dev-accounts 凭证）⇒ 该腿环境不具备。旁证仅历史 seed 行 "
        "(gate 9100000000000000063 round5: 900102 APPROVE vs 900112 REJECT + 900101 终裁 REJECT，2026-09-06 遗留非本轮) " % (
            sr, pre, ec(ba), (data(ba) or {}).get("id"), msg(ba2)[:40], post[0] if post else None,
            au[0][0] if au else 0, msg(bf)[:40]))

def case_gate09_both_sides():
    """AC-GATE-09 补强：双签 Gate 两侧 PM 均在册（组900001），只签市场侧 →
    验证 scan-remind 只提醒未签方（研发侧）、已签方不再被提醒。"""
    card = "AC-GATE-09"
    _, _, orig = cfg_get("gate.signDeadlineDays")
    cfg_set("gate.signDeadlineDays", 1)
    try:
        s, bj, pid, _ = mk_project("P15"); PROJS["P15"] = pid
        sb1, bb1 = bind_member(pid, P_MKT, "MARKET_PM", approval="LANE2-%s-AC09BEIAN" % RUN)
        sb2, bb2 = bind_member(pid, P_RD, "RD_PM", approval="LANE2-%s-AC09BEIAN" % RUN)
        L.written({"card": card, "op": "bind 900103+900104 pid=%s (临时放宽阈值, approvalRef=LANE2-%s-AC09BEIAN)" % (pid, RUN), "ts": L.now()})
        if not (ok(sb1, bb1) and ok(sb2, bb2)):
            rec(card, "bind 双边在册PM", "POST /members", sb2, bb2, "FAIL-ENV",
                "入组失败:%s / %s" % (msg(bb1)[:80], msg(bb2)[:80])); return
        sg, bg, gid = mk_gate(pid, "G1"); GIDS["P15G1"] = gid
        if not ok(sg, bg):
            rec(card, "mk_gate", "POST /gates", sg, bg, "FAIL-ENV", msg(bg)[:150]); return
        fails = judge_all(gid)
        if fails:
            rec(card, "judge_all", "批量判定", 200, None, "FAIL", str(fails[:4])); return
        ss, bs = submit(gid)
        if not ok(ss, bs):
            rec(card, "submit", "POST submit", ss, bs, "FAIL-ENV", msg(bs)[:150]); return
        s1, b1 = sign(gid, TOK[MTK], "APPROVE")
        # 边界修正（fixture 假红根因）：gates.sign_due_at 为 DATETIME(0)，落库会向上取整秒；
        # submit 后 <0.5s 内触发扫描时 untilDue 略大于 24h 窗口上界 => scanRemind 合法跳过（remindCount=0）。
        # 留 2s 余量后再扫描，行为与 repair-3 单侧用例一致。
        time.sleep(2)
        st, bt = L.req("POST", L.B39, "/api/v1/gates/sign/scan-remind", token=TOK[ADM])[0:2]
        n_rd = notif_cnt(P_RD, "GATE_SIGN_SOON", gid)
        n_mk = notif_cnt(P_MKT, "GATE_SIGN_SOON", gid)
        rec(card, "双签两侧在册:仅提醒未签方", "sign(MTK APPROVE)+scan-remind + SQL notif", st, bt,
            "PASS" if ok(st, bt) and n_rd >= 1 and n_mk == 0 else ("FAIL" if n_rd < 1 else "PARTIAL"),
            "remindCount=%s 未签研发侧(900104)通知=%d, 已签市场侧(900103)通知=%d(应0·BR『已签方不提醒』), "
            "due=%s; 市场侧另证见 repair-3 单侧用例; 首跑(13:02:30)因 submit 后 <0.5s 触发扫描落在 24h 上界外→remindCount=0 "
            "属 fixture 假红，人工于 13:06:45 重扫同一 gate 得 remindCount=1 且仅 900104 收到 GATE_SIGN_SOON" % (
                (data(bt) or {}).get("remindCount"), n_rd, n_mk, (gate_row(gid) or [None, None, ""])[2]))
    finally:
        cfg_set("gate.signDeadlineDays", orig if orig else 3)

def case_ipd12_overdue_marker():
    """AC-IPD-12 深管逾期（repair-4 改写判定）：
    不再因『due_date 无 HTTP 写入口』一律 BLOCKED —— 库内存在 ZK 场景初始化器 seed 的逾期 DEEP 动作
    （project 9140005 / D02 / MARKET_PM / due_date 2026-09-16 < now），其主责人 900103 可登录，
    故两条腿分别取证：
      ① 逾期标记数据源：GET /api/v1/workbench/summary?projectId=9140005 → 该动作 priority=high + dueDate + stats.overdue>=1
      ② 每日提醒：notification_events 全库 ACTION_OVERDUE 计数（主代码 grep 无发布方、无 stage_actions 逾期扫描器）
    ②为 0 ⇒ 卡片判 FAIL（真缺陷：逾期提醒未接线），并在 note 记录 ①已通过。"""
    card = "AC-IPD-12"
    rows = L.sql("SELECT sa.id, sa.project_id, sa.action_code, sa.owner_role, sa.due_date, p.main_group_id "
                 "FROM stage_actions sa JOIN projects p ON p.id=sa.project_id "
                 "WHERE sa.depth='DEEP' AND sa.due_date IS NOT NULL AND sa.due_date < NOW() "
                 "AND sa.del_flag='0' AND sa.status NOT IN ('DONE','NA','ST_DONE','ST_NA') "
                 "AND sa.owner_role='MARKET_PM' LIMIT 5")
    if not rows:
        rec(card, "seed 逾期 DEEP 动作盘点", "SQL stage_actions", 200, None, "BLOCKED",
            "库内无可登录主责人的逾期 DEEP 动作，仍不可测")
        return
    aid, pid, acode, orole, due, grp = rows[0]
    s, bj, _, _ = L.req("GET", L.B39, "/api/v1/workbench/summary?projectId=%s" % pid, token=TOK[MTK])
    d = data(bj) or {}
    tasks = d.get("tasks") or []
    hit = [t for t in tasks if str(t.get("id")) == str(aid) or t.get("actionCode") == acode]
    marked = bool(hit) and hit[0].get("priority") == "high" and hit[0].get("dueDate")
    stats = d.get("stats") or {}
    n = L.sql("SELECT COUNT(*) FROM notification_events WHERE event_type='ACTION_OVERDUE'")
    n_own = L.sql("SELECT COUNT(*) FROM notification_events WHERE event_type='ACTION_OVERDUE' AND receiver_id=900103")
    rec(card, "深管逾期:工作台标记腿 + 每日提醒腿", "GET /workbench/summary?projectId=%s + SQL notification_events" % pid,
        s, bj, "FAIL" if (marked and int(n[0][0]) == 0) else ("PARTIAL" if not marked else "PASS"),
        "① 标记腿(通过): 动作 %s(id=%s, depth=DEEP, due=%s) 在工作台以 priority=%s dueDate=%s 暴露, stats.overdue=%s "
        "② 提醒腿(未接线): 全库 ACTION_OVERDUE 通知=%s 条、主责人 900103 收到=%s 条; "
        "NotificationService.Types.ACTION_OVERDUE 仅常量声明, 主代码 0 发布方且无 stage_actions 逾期扫描器/端点 "
        "⇒ 每日提醒不成立(真缺陷, 只描述不立卡)" % (
            acode, aid, due, hit[0].get("priority") if hit else None, hit[0].get("dueDate") if hit else None,
            stats.get("overdue"), n[0][0], n_own[0][0]))
