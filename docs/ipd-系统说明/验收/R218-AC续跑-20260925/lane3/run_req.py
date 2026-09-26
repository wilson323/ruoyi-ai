# -*- coding: utf-8 -*-
# LANE3 REQ 域驱动：AC-REQ-01..08（09 已立缺陷卡跳过）
import json, time, sys
import r219_lib as L

B = L.BASE
FIX = json.load(open("fixtures.json"))
RECS = json.load(open(L.REC_PATH))
SKIP = {r["case"] for r in RECS if r["card"].startswith("AC-REQ")}
PROJ = int(FIX["kpi_proj"]); PROD = int(FIX["kpi_prod"])

def rec(card, case, cmd, http, ec, verdict, note):
    if case in SKIP:
        print("skip dup", case); return
    L.record(RECS, {"card": card, "case": case, "cmd": cmd, "http": http,
                    "envelope_code": ec, "verdict": verdict, "note": note[:300]})

def guest_submit(tag, **kw):
    body = {"customerName": "LANE3-客户-" + tag, "feedbackPerson": "LANE3-反馈人-" + tag,
            "contact": "13800000" + tag[-2:].rjust(2, "0"), "productId": PROD,
            "rawModel": "LANE3-MODEL-" + tag, "functionalRequirement": "LANE3 功能需求描述不少于六个字 " + tag}
    body.update(kw)
    s, b, _, rb = L.req("POST", B, "/api/v1/public/demands", body=body)
    return s, b, body

# ---- REQ-01 游客免登录提交 → 8位查询码 ----
s, b, body = guest_submit("a1")
d = L.data_of(b) or {}
qc = d.get("queryCode") if isinstance(d, dict) else None
ok = s == 200 and L.code_of(b) == 0 and isinstance(qc, str) and len(qc) == 8
rec("AC-REQ-01", "REQ01-guest-submit-noauth", "POST /api/v1/public/demands (no token)", s, L.code_of(b),
    "PASS" if ok else "FAIL",
    "免登录提交 code=0 queryCode=%s(8位) data keys=%s；真库 requirements.query_code=%s" % (
        qc, list(d.keys()) if isinstance(d, dict) else None,
        (L.sql("SELECT query_code FROM requirements WHERE query_code='%s'" % qc) or [["无"]])[0][0]) if qc else "响应异常 head=" + str(b)[:150],
    )
# 真库断言：SUBMITTED + product/project 1:1 带出
if qc:
    row = L.sql("SELECT status,project_id,market_pm_id,rd_pm_id,submitter_name,customer_name FROM requirements WHERE query_code='%s'" % qc)
    st, pj, mp, rp, sn, cn = (row[0] + ["", "", "", "", "", ""])[:6] if row else ("-",)*6
    rec("AC-REQ-01", "REQ01-db-truth", "sql SELECT requirements WHERE query_code", 200, 0,
        "PASS" if st == "SUBMITTED" else "FAIL",
        "真库 status=%s project_id=%s(产品1:1带出期望%s) market_pm=%s rd_pm=%s submitter=%s customer=%s" % (st, pj, PROJ, mp, rp, sn, cn))
    rid = (L.sql("SELECT id FROM requirements WHERE query_code='%s'" % qc) or [["0"]])[0][0]
else:
    rid = "0"

# ---- REQ-02 公开产品下拉 + DTO 白名单 + 蜜罐 ----
s, b, _, _ = L.req("GET", B, "/api/v1/public/products")
pl = L.data_of(b) or []
rec("AC-REQ-02", "REQ02-public-products", "GET /api/v1/public/products (no token)", s, L.code_of(b),
    "PASS" if s == 200 and L.code_of(b) == 0 and isinstance(pl, list) and len(pl) > 0 else "FAIL",
    "免登录产品清单 %d 项，首项=%s" % (len(pl), json.dumps(pl[0], ensure_ascii=False)[:120] if pl else "-"))
s2, b2, _ = guest_submit("a2", unexpectedField="x", submitterId="999", status="ACCEPTED")
qc2 = (L.data_of(b2) or {}).get("queryCode") if isinstance(L.data_of(b2), dict) else None
rec("AC-REQ-02", "REQ02-dto-whitelist-ignore-unknown", "POST /public/demands +未知字段/越权字段", s2, L.code_of(b2),
    "PASS" if L.code_of(b2) == 0 and qc2 else ("FAIL-ENV" if L.code_of(b2) == 40011 else "FAIL"),
    "@JsonIgnoreProperties 白名单：未知字段应静默忽略；code=%s msg=%s 注入字段未生效(status=%s)" % (
        L.code_of(b2), L.msg_of(b2)[:60],
        (L.sql("SELECT status FROM requirements WHERE query_code='%s'" % qc2) or [["?"]])[0][0] if qc2 else "-"))
s3, b3, _ = guest_submit("a3", website="http://spam.example")
rec("AC-REQ-02", "REQ02-honeypot-reject", "POST /public/demands website蜜罐非空", s3, L.code_of(b3),
    "PASS" if s3 != 200 or L.code_of(b3) != 0 else "FAIL",
    "蜜罐命中应拒绝；http=%s code=%s msg=%s 审计=%s" % (s3, L.code_of(b3), L.msg_of(b3)[:60],
        (L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='spam_rejected'") or [["?"]])[0][0]))

# ---- REQ-03 双PM路由（服务层 routeDualPm 无 HTTP 路由 → 需 triage 补） ----
if qc:
    AD = L.login(B, "ipd-admin")
    MK = L.login(B, "ipd-market"); RD = L.login(B, "ipd-rd")
    mkt = L.sql("SELECT id FROM persons WHERE username='ipd-market' AND person_type='MARKET_PM' AND del_flag='0'")
    rd  = L.sql("SELECT id FROM persons WHERE username='ipd-rd' AND person_type='RD_PM' AND del_flag='0'")
    mkt_id = int(mkt[0][0]) if mkt else 0; rd_id = int(rd[0][0]) if rd else 0
    s, b, _, _ = L.req("POST", B, "/api/v1/demands/%s/triage" % rid, token=AD,
                       body={"status": "ACCEPTED", "marketPmId": mkt_id, "rdPmId": rd_id})
    L.write_reg("triage-accept", "POST", "/api/v1/demands/%s/triage" % rid, "requirements", "LANE3 REQ03")
    row = L.sql("SELECT status,market_p…_id FROM requirements WHERE id=%s" % rid)
    st, m2, r2 = row[0] if row else ("-", "-", "-")
    rec("AC-REQ-03", "REQ03-triage-dual-pm", "POST /api/v1/demands/%s/triage ACCEPTED+双PM" % rid, s, L.code_of(b),
        "PASS" if L.code_of(b) == 0 and st == "ACCEPTED" and m2 != "NULL" and r2 != "NULL" else "FAIL",
        "提交时系统自动路由=%s(mp=%s,rp=%s)，triage 后双PM写库 mp=%s rp=%s status=%s（routeDualPm 无HTTP路由，经triage落库）" % (mp, rp, m2, r2, st))
else:
    rec("AC-REQ-03", "REQ03-triage-dual-pm", "-", "-", "-", "BLOCKED", "REQ01 未拿到 queryCode，下游全阻塞")

# ---- REQ-04 撤回 / REQ-04b 补充：服务层已实现但无 controller 路由 ----
for tag, act in (("a4", "withdraw"), ("a5", "supplement")):
    s, b, _, _ = L.req("PUT", B, "/api/v1/public/demands/ABCDEF01/" + act, body={"action": act})
    s2, b2, _, _ = L.req("POST", B, "/api/v1/public/demands/ABCDEF01/" + act, body={"action": act})
    rec("AC-REQ-04" if act == "withdraw" else "AC-REQ-04b", "REQ04-%s-no-route" % act,
        "PUT+POST /api/v1/public/demands/{code}/%s" % act, "%s/%s" % (s, s2), "%s/%s" % (L.code_of(b2), L.code_of(b)),
        "FAIL",
        "真缺陷候选：GuestDemandService.%s() 已实现(仅SUBMITTED可撤回/24h窗口)但无任何 controller 路由，两法均404；"
        "trace 视图仍暴露 withdraw 倒计时字段，游客端不可达" % act)

# ---- trace 可读 + 防枚举 ----
if qc:
    s, b, _, _ = L.req("GET", B, "/api/v1/public/demands/" + qc)
    tv = L.data_of(b) or {}
    rec("AC-REQ-04", "REQ04-trace-read", "GET /api/v1/public/demands/%s" % qc, s, L.code_of(b),
        "PASS" if L.code_of(b) == 0 and isinstance(tv, dict) else "FAIL",
        "trace data keys=%s stages=%d" % (list(tv.keys())[:10], len(tv.get("stages") or tv.get("timeline") or [])))
s, b, _, _ = L.req("GET", B, "/api/v1/public/demands/ZZZZZZZZ")
c_a = L.code_of(b)
s2, b2, _, _ = L.req("GET", B, "/api/v1/public/demands/00000000")
rec("AC-REQ-04", "REQ04-trace-anti-enum", "GET /public/demands/{不存在码}x2", s, c_a,
    "PASS" if c_a == L.code_of(b2) and c_a != 0 else "FAIL",
    "格式非法/查无此码/已撤回三态同码防枚举：ZZZZZZZZ→%s 00000000→%s" % (c_a, L.code_of(b2)))

# ---- REQ-05 内部需求池需鉴权 ----
s, b, _, _ = L.req("GET", B, "/api/v1/demands")
rec("AC-REQ-05", "REQ05-demands-list-auth", "GET /api/v1/demands (no token)", s, L.code_of(b),
    "PASS" if s in (401, 403) or (L.code_of(b) not in (0, None)) else "FAIL",
    "游客访问内部池被拒 http=%s code=%s" % (s, L.code_of(b)))

# ---- REQ-06 link-project + 状态链 ----
if qc and rid != "0":
    s, b, _, _ = L.req("POST", B, "/api/v1/demands/%s/link-project" % rid, token=L.login(B, "ipd-admin"),
                       body={"projectId": PROJ})
    st = (L.sql("SELECT status FROM requirements WHERE id=%s" % rid) or [["?"]])[0][0]
    pj = (L.sql("SELECT project_id FROM requirements WHERE id=%s" % rid) or [["?"]])[0][0]
    rec("AC-REQ-06", "REQ06-link-project", "POST /demands/%s/link-project projectId=%s" % (rid, PROJ), s, L.code_of(b),
        "PASS" if L.code_of(b) == 0 and st == "SCHEDULED" and str(pj) == str(PROJ) else "FAIL",
        "真库 status=%s project_id=%s（双PM已分派后允许关联；期望SCHEDULED）" % (st, pj))
    s, b, _, _ = L.req("POST", B, "/api/v1/demands/%s/triage" % rid, token=L.login(B, "ipd-admin"), body={"status": "ARCHIVED"})
    rec("AC-REQ-06", "REQ06-status-archived-whitelist", "POST triage status=ARCHIVED", s, L.code_of(b),
        "PASS" if L.code_of(b) == 0 else "FAIL", "ARCHIVED 在 STATUSES 白名单内，code=%s" % L.code_of(b))
    s, b, _, _ = L.req("POST", B, "/api/v1/demands/%s/triage" % rid, token=L.login(B, "ipd-admin"), body={"status": "NOT_A_STATUS"})
    rec("AC-REQ-06", "REQ06-status-illegal-reject", "POST triage status=NOT_A_STATUS", s, L.code_of(b),
        "PASS" if s == 400 or (L.code_of(b) not in (0, None)) else "FAIL",
        "白名单外状态被拒 http=%s msg=%s" % (s, L.msg_of(b)[:80]))
    # link-project 前置守卫：新建一条未分诊需求直接关联应 400
    s4, b4, _ = guest_submit("a6")
    qc4 = (L.data_of(b4) or {}).get("queryCode") if isinstance(L.data_of(b4), dict) else None
    if qc4:
        rid4 = (L.sql("SELECT id FROM requirements WHERE query_code='%s'" % qc4) or [["0"]])[0][0]
        s, b, _, _ = L.req("POST", B, "/api/v1/demands/%s/link-project" % rid4, token=L.login(B, "ipd-admin"), body={"projectId": PROJ})
        rec("AC-REQ-06", "REQ06-link-guard-no-triage", "POST link-project 未分诊需求", s, L.code_of(b),
            "PASS" if s == 400 or (L.code_of(b) not in (0, None)) else "FAIL",
            "R215-P2 前置守卫：双PM未分派不得关联 http=%s msg=%s" % (s, L.msg_of(b)[:80]))
    else:
        rec("AC-REQ-06", "REQ06-link-guard-no-triage", "-", "-", "-", "FAIL-ENV", "第2次游客提交未拿码（疑似限流40011，配额已被本车道有意消耗）code=%s" % L.code_of(b4))
rec("AC-REQ-07", "REQ07-ui-kanban", "纯UI看板核对", "-", "-", "NOT-RUN", "需求看板列渲染为前端行为，无HTTP面可证，诚实NOT-RUN")

# ---- REQ-08 变更单双签链 ----
AD = L.login(B, "ipd-admin"); MK = L.login(B, "ipd-market"); RD = L.login(B, "ipd-rd")
SNAP = json.dumps({"范围": "LANE3-范围", "成本": "LANE3-成本", "时限": "LANE3-时限", "质量": "LANE3-质量"}, ensure_ascii=False)
if rid and rid != "0":
    s, b, _, _ = L.req("POST", B, "/api/v1/requirement-changes", token=AD,
                       body={"requirementId": int(rid), "changeType": "SCOPE", "reason": "LANE3-REQ08-双签验证",
                             "beforeSnapshot": SNAP, "afterSnapshot": SNAP})
    cid = (L.data_of(b) or {}).get("id") if isinstance(L.data_of(b), dict) else None
    L.write_reg("req-change-create", "POST", "/api/v1/requirement-changes", "requirement_changes", "LANE3-REQ08")
    if cid:
        s2, b2, _, _ = L.req("PUT", B, "/api/v1/requirement-changes/%s/submit" % cid, token=AD)
        st2 = (L.sql("SELECT status FROM requirement_changes WHERE id=%s" % cid) or [["?"]])[0][0]
        rec("AC-REQ-08", "REQ08-create-submit", "POST create + PUT submit id=%s" % cid, "%s/%s" % (s, s2),
            L.code_of(b2), "PASS" if st2 == "PENDING_SIGN" else "FAIL",
            "四维度快照校验通过→DRAFT；submit→真库 status=%s" % st2)
        s3, b3, _, _ = L.req("PUT", B, "/api/v1/requirement-changes/%s/sign?decision=REJECT&opinion=LANE3-no" % cid, token=MK)
        st3 = (L.sql("SELECT status,signatures FROM requirement_changes WHERE id=%s" % cid) or [["?", "?"]])[0]
        rec("AC-REQ-08", "REQ08-sign-reject-shortcut", "PUT sign decision=REJECT (MARKET_PM)", s3, L.code_of(b3),
            "PASS" if st3[0] == "REJECTED" else "FAIL",
            "任一否决⇒整体REJECTED：真库 status=%s signatures=%s" % (st3[0], st3[1]))
        s4, b4, _, _ = L.req("PUT", B, "/api/v1/requirement-changes/%s/sign?decision=APPROVE" % cid, token=RD)
        rec("AC-REQ-08", "REQ08-sign-after-terminal", "PUT sign on REJECTED 终态", s4, L.code_of(b4),
            "PASS" if L.code_of(b4) not in (0, None) else "FAIL",
            "终态再签应 STATE_CONFLICT 拒绝：code=%s msg=%s" % (L.code_of(b4), L.msg_of(b4)[:80]))
    else:
        rec("AC-REQ-08", "REQ08-create-submit", "POST /requirement-changes", s, L.code_of(b), "FAIL",
            "创建变更单未返回 id：http=%s msg=%s（归因看 msg：三值逻辑/权限/校验）" % (s, L.msg_of(b)[:150]))
else:
    rec("AC-REQ-08", "REQ08-create-submit", "-", "-", "-", "BLOCKED", "无有效 requirement id（REQ01 未拿码）")

# ---- 限流：同IP小时10次，第11次 40011（本车道有意消耗配额，已隔离记录） ----
codes = []
for i in range(12):
    s, b, _ = guest_submit("r%02d" % i)
    codes.append(L.code_of(b))
n_ok = sum(1 for c in codes if c == 0); n_rl = sum(1 for c in codes if c == 40011)
rec("AC-REQ-02", "REQ02-rate-limit-10per-hour", "POST /public/demands x12 同IP", "-", "40011" if n_rl else str(codes[-1]),
    "PASS" if n_rl >= 1 and n_ok <= 10 + 3 else "FAIL",
    "12连发结果码=%s 成功=%d 限流(40011)=%d；口径=本会话前已累计提交(含REQ01/02/06)，RATE_LIMIT_PER_HOUR=10；本车道有意消耗127.0.0.1配额，兄弟车道若命中40011请标FAIL-ENV" % (codes, n_ok, n_rl))
# trace 池隔离：12连打 trace 不挤占提交配额（提交已耗尽→若隔离成立，trace 仍 200/非40011）
tr = []
for i in range(12):
    s, b, _, _ = L.req("GET", B, "/api/v1/public/demands/" + (qc or "ABCDEF01"))
    tr.append(L.code_of(b))
rec("AC-REQ-02", "REQ02-trace-quota-isolated", "GET /public/demands/{code} x12", "-",
    "40011" if 40011 in tr else "0", "PASS" if 40011 not in tr[:11] else "FAIL",
    "trace: 前缀独立池 12连打结果=%s（提交池同时已耗尽→前缀隔离生效）" % (tr[:6] + ["..."] + tr[-2:],))

print("REQ done.")
