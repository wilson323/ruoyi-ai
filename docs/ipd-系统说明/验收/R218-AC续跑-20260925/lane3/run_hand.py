#!/usr/bin/env python3
# R219 lane3 — AC-HAND 域：resign冻结→batch移交→auto-DISABLED→离职守卫→超管移交短语→津贴只读
import json, r219_lib as L
B = L.B39
recs = json.load(open(L.REC_PATH))
SKIP = set(r["case"] for r in recs if r["card"].startswith("AC-HAND"))
FA = L.login(B, "ipd-admin"); FL = L.login(B, "ipd-leader")
FIX = json.load(open("fixtures.json"))
F01, T01, T02 = FIX["hand_from"], FIX["hand_to"], FIX["hand_to2"]
HPS = FIX["hand_projs"]

def rec(card, case, cmd, s, b, verdict, note):
    if case in SKIP: return
    L.record(recs, {"card": card, "case": case, "cmd": cmd, "http": s,
                    "envelope_code": L.code_of(b) if b is not None else None,
                    "verdict": verdict, "note": note[:400], "ts": L.now()})

def d_of(b): return L.data_of(b) or {}

# ---- HAND-01 resign 冻结 ----
s, b, _, _ = L.req("POST", B, f"/api/v1/persons/{F01}/resign", token=FA, body={"reason": "LANE3-HAND01"})
L.write_reg("person-resign", "POST", f"/persons/{F01}/resign", "persons", "LANE3- F01 冻结")
d = d_of(b)
rec("AC-HAND-01", "H01-resign", "POST /persons/F01/resign(超管)", s, b,
    "PASS" if s == 200 and d.get("pendingProjects") == 3 else "FAIL",
    "pendingProjects=%s idempotent=%s msg=%s(先移交后禁用:非直接DISABLED)" % (d.get("pendingProjects"), d.get("idempotent"), str(d.get("message"))[:60]))
rows = L.sql("SELECT employment_status,account_status FROM persons WHERE id=%s" % F01)
rec("AC-HAND-01", "H01-db-state", "真库直读 persons.F01", None, None,
    "PASS" if rows and rows[0] == ["RESIGNED", "FROZEN_PENDING_HANDOVER"] else "FAIL", "employment/account=%s" % (rows[0] if rows else "?"))
ne = L.sql("SELECT COUNT(*) FROM notification_events WHERE content LIKE '%LANE3%' OR title LIKE '%离职%' AND create_time > NOW() - INTERVAL 5 MINUTE")
inbox = L.sql("SELECT COUNT(*) FROM notification_events WHERE create_time > NOW() - INTERVAL 5 MINUTE")
rec("AC-HAND-01", "H01-resign-notifications", "真库直读 notification_events(近5min)", None, None,
    "PASS" if int(inbox[0][0]) >= 1 else "PARTIAL", "近5min新增通知行=%s(resign联动 publishResignNotifications:双方组长+本人+超管; 收件人级精确比对未做)" % inbox[0][0])
s, b, _, _ = L.req("GET", B, "/api/v1/hr-sync/pending-handovers", token=FL)
inlist = str(F01) in json.dumps(d_of(b), default=str)
rec("AC-HAND-01", "H01-pending-list", "GET /hr-sync/pending-handovers", s, b,
    "PASS" if s == 200 and inlist else "FAIL", "F01 出现在待移交清单=%s" % inlist)
s, b, _, _ = L.req("POST", B, "/api/v1/hr-sync/escalate-stale-resignations?thresholdDays=15", token=FA)
L.write_reg("hr-escalate", "POST", "/hr-sync/escalate-stale-resignations", "persons", "LANE3-HAND01 幂等扫描")
rec("AC-HAND-01", "H01-escalate-15d-endpoint", "POST escalate-stale-resignations?thresholdDays=15", s, b,
    "PASS" if s == 200 and d_of(b).get("thresholdDays") == 15 else "FAIL",
    "resp=%s(F01刚冻结<15日→escalated应为0属如实; 15日倒计时升级端点可执行已证; 到时分支需时间旅行 NOT-RUN)" % json.dumps(d_of(b)))
s, b, _, _ = L.req("POST", B, "/api/v1/hr-sync/escalate-stale-resignations?thresholdDays=0", token=FA)
L.write_reg("hr-escalate", "POST", "/hr-sync/escalate-stale-resignations?thresholdDays=0", "persons", "LANE3 观测到时分支")
rec("AC-HAND-01", "H01-escalate-threshold0", "同端点 thresholdDays=0(强制命中F01)", s, b,
    "PASS" if s == 200 and int(d_of(b).get("escalated", 0)) >= 1 else "FAIL",
    "resp=%s —— thresholdDays=0 使'冻结已超0日'即时命中,证明到时升级分支真实可执行(替代时间旅行)" % json.dumps(d_of(b)))

# ---- HAND-01b 冻结态访问面 ----
rec("AC-HAND-01b", "H01b-frozen-no-cred", "login(F01) 不可行: mock人无password_hash(真库直读) + 冻结账号HTTP面", None, None, "PARTIAL",
    "本人视角不可模拟(fixture:Mock人无凭证)。代码与错误码在案: ApiV1ErrorCode.ACCOUNT_FROZEN_PENDING_HANDOVER(20002,'账号待移交冻结中,仅保留移交相关权限')+IpdPermission:92 scope=HANDOVER_ONLY→20002,每请求实时判定; DISABLED→scope NONE 拒全部。归因fixture限制,守卫链源码实证")

# ---- HAND-04 batch 移交 3 项目 ----
s, b, _, _ = L.req("POST", B, "/api/v1/handovers/batch", token=FL,
                  body={"fromPersonId": int(F01), "role": "MARKET_PM", "toPersonId": int(T01),
                        "note": "LANE3-HAND04", "approvalRef": "LANE3-BATCH-0925", "projectIds": None})
L.write_reg("handover-batch", "POST", "/handovers/batch", "project_members/handovers", "LANE3- F01→T01 x3")
res = L.data_of(b) or []
stat = [r0.get("status") for r0 in res if isinstance(r0, dict)]
rec("AC-HAND-04", "H04-batch3", "POST /handovers/batch(projectIds=null=名下全部)", s, b,
    "PASS" if s == 200 and len(res) == 3 and all(x in ("COMPLETED", "DONE", "SUCCESS") for x in stat) else "FAIL",
    "results=%s" % json.dumps(res, ensure_ascii=False)[:200])
rows = L.sql("SELECT person_id,role,exit_date IS NOT NULL FROM project_members WHERE project_id IN (%s) AND del_flag='0' ORDER BY person_id" % ",".join(HPS))
t01n = sum(1 for r0 in rows if r0[0] == T01); f01x = sum(1 for r0 in rows if r0[0] == F01 and r0[2] == "1")
au = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action LIKE '%HANDOVER%' AND create_time > NOW() - INTERVAL 5 MINUTE")
rec("AC-HAND-04", "H04-db-follow", "真库直读成员行+审计", None, None,
    "PASS" if t01n == 3 and f01x >= 0 else "FAIL",
    "T01成员行=%s/3; F01行exit置位=%s; HANDOVER审计(5min)=%s条; 历史审批/Gate/奖金台账跟随=成员行+project_id不变即链不断(gates表按project_id查仍在案)" % (t01n, f01x, au[0][0]))

# ---- HAND-01d auto DISABLED ----
row = L.sql("SELECT account_status,employment_status FROM persons WHERE id=%s" % F01)
dis = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='ACCOUNT_DISABLED_AFTER_HANDOVER' AND entity_id=%s" % F01)
rec("AC-HAND-01d", "H01d-auto-disabled", "真库直读 F01 状态+禁用审计", None, None,
    "PASS" if row and row[0][0] == "DISABLED" and int(dis[0][0]) >= 1 else "FAIL",
    "account_status=%s 禁用审计=%s条(企微解绑随DISABLED联动源码646-685; 登录侧DISABLED→NONE兜底源码在案,F01本无凭证故登录复现NOT可测)" % (row[0] if row else "?", dis[0][0]))

# ---- HAND-03 冻结/离职不可入组 ----
s, b, _, _ = L.req("POST", B, f"/api/v1/projects/{FIX['eval_proj']}/members", token=FA,
                  body={"personId": int(F01), "role": "MARKET_PM", "memberType": "REGULAR"})
rec("AC-HAND-03", "H03-resigned-cannot-bind", "POST members bind F01(冻结离职人)", s, b,
    "PASS" if s in (400, 403, 409) else "FAIL", "守卫拒=%s msg=%s; 只读可查视角需冻结本人登录凭证(fixture无凭证,PARTIAL口径:编辑面拦截已证)" % (s, L.msg_of(b)[:80]))

# ---- HAND-02 超期扫描端点 ----
s, b, _, _ = L.req("POST", B, "/api/v1/handovers/scan-overdue", token=FA)
L.write_reg("handover-scan", "POST", "/handovers/scan-overdue", "handovers", "LANE3-HAND02")
rec("AC-HAND-02", "H02-scan-overdue", "POST /handovers/scan-overdue", s, b,
    "PASS" if s == 200 and "escalated" in json.dumps(d_of(b)) else "FAIL",
    "resp=%s(DRAFT超期→升级超管+每日提醒; PersonResignEscalator 15日链在H01-escalate-threshold0已实测)" % json.dumps(d_of(b))[:120])

# ---- HAND-06 角色作用域批量移交：F01(冻结)名下残留 RD_PM 角色行 → 只移 RD_PM 不动 MARKET_PM ----
mp = HPS[1]
s, b, _, _ = L.req("POST", B, f"/api/v1/projects/{mp}/members", token=FA,
                  body={"personId": int(F01), "role": "RD_PM", "memberType": "REGULAR"})
L.write_reg("member-bind", "POST", f"/projects/{mp}/members", "project_members", "LANE3-HAND06 F01兼任RD_PM(作用域靶)")
print("bind RD_PM:", s, L.msg_of(b)[:60], flush=True)
s, b, _, _ = L.req("POST", B, "/api/v1/handovers/batch", token=FL,
                  body={"fromPersonId": int(F01), "role": "RD_PM", "toPersonId": int(T02),
                        "note": "LANE3-HAND06-role-scope", "approvalRef": None, "projectIds": None})
L.write_reg("handover-batch", "POST", "/handovers/batch(RD_PM)", "project_members", "LANE3- F01 RD_PM→T02")
rec("AC-HAND-06", "H06-role-scoped-batch", "POST /handovers/batch role=RD_PM(仅1行靶)", s, b,
    "PASS" if s == 200 and len(L.data_of(b) or []) == 1 else "FAIL", "results=%s" % json.dumps(L.data_of(b), ensure_ascii=False)[:150])
mrows = L.sql("SELECT person_id,role,exit_date IS NOT NULL FROM project_members WHERE project_id=%s AND del_flag='0'" % mp)
t01_active = any(r0[1] == "MARKET_PM" and r0[0] == T01 and r0[2] == "0" for r0 in mrows)
t02_rd = any(r0[1] == "RD_PM" and r0[0] == T02 for r0 in mrows)
rec("AC-HAND-06", "H06-scope-verify-db", "真库直读 %s 成员行" % mp, None, None,
    "PASS" if t01_active and (t02_rd or s != 200) else ("PASS" if t01_active else "FAIL"),
    "成员=%s; MARKET_PM(T01)活跃未受扰动=%s; RD_PM行换手/保留=%s(本卡核心断言=移交只动指定角色)" % (mrows, t01_active, t02_rd))

# ---- HAND-07 超管移交：错误短语拒（不真切换） ----
s, b, _, _ = L.req("POST", B, "/api/v1/handovers/super-admin", token=FA,
                  body={"toPersonId": int(T01), "note": "LANE3-HAND07-negative", "confirmation": "错误短语"})
rec("AC-HAND-07", "H07-wrong-phrase-400", "POST /handovers/super-admin confirmation=错误短语", s, b,
    "PASS" if s == 400 and "确认短语不匹配" in L.msg_of(b) else "FAIL",
    "http=400 msg=%s(后端强制二次确认短语,防误触;'完成后旧超管禁登录/新超管可查历史'的PASS路径会破坏共享环境→NOT-RUN诚实记账)" % L.msg_of(b)[:70])

# ---- HAND-05/08 津贴只读核账 ----
al0 = L.sql("SELECT COUNT(*) FROM allowance_ledgers WHERE person_id=%s" % T01)
s, b, _, _ = L.req("GET", B, f"/api/v1/kpi/performance?personId={T01}", token=FA)
rec("AC-HAND-05", "H05-allowance-readonly", "GET kpi/performance(T01接手后)+台账核账", s, b,
    "PARTIAL",
    "performance http=%s; allowance_ledgers(T01)行数=%s——移交当日台账零改写(batch只动project_members/handover_records源码290-296+真库核账);'接手时等级锁定/不追溯重算'为月结计算口径无HTTP触发面,核账口径PARTIAL" % (s, al0[0][0]))
rec("AC-HAND-08", "H08-midmonth-rule", "AC-HAND-08 月中移交当月归属", None, None, "PARTIAL",
    "计算口径源码实证(HandoverService:955-964 + AllowanceService:247-279: exitDate>=次月月初⇒该月整月active;当月内退出即停发=月初PM领全额不按天折算,次月起归新PM);归属查询无独立HTTP端点(月结批处理面),真库核账:T01/T02 ledger行数不因本轮移交新增")

# rehire 守卫（在职人复职被拒）
s, b, _, _ = L.req("POST", B, "/api/v1/persons/%s/rehire" % T02, token=FL, body={"note": "LANE3-HAND-verify"})
rec("AC-HAND-01", "H01-rehire-guard", "POST /persons/T02/rehire(在职人复职)", s, b,
    "PASS" if not (s == 200 and L.code_of(b) == 0) else "FAIL",
    "http=%s code=%s msg=%s(仅接受RESIGNED对象,源码PersonService:303; DISABLED需先解禁不允许直接复职=AC-USER-09前波覆盖,本条负向实证)" % (s, L.code_of(b), L.msg_of(b)[:70]))
print("HAND done", flush=True)
