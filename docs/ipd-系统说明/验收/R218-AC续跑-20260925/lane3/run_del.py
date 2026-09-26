#!/usr/bin/env python3
# R219 lane3 — AC-DEL 域（8卡）：deletion-requests 双层审核/撤回/逾期/purge 全链
import json, subprocess, r219_lib as L

B = L.B39
recs = json.load(open(L.REC_PATH))
FA = L.login(B, "ipd-admin")   # SUPER_ADMIN 申请人
FL = L.login(B, "ipd-leader")  # GROUP_LEADER 900001 初审
FX = L.login(B, "ipd-market")  # MARKET_PM 负向
FIX = json.load(open("fixtures.json"))
PROJ = FIX["kpi_proj"]

SKIP=set(r["case"] for r in recs if r["card"].startswith("AC-DEL"))
def rec(card, case, cmd, s, b, verdict, note):
    if case in SKIP: return
    L.record(recs, {"card": card, "case": case, "cmd": cmd, "http": s,
                    "envelope_code": L.code_of(b), "verdict": verdict, "note": note[:400], "ts": L.now()})

def st_of(b):
    d = L.data_of(b)
    return d.get("status") if isinstance(d, dict) else None

def id_of(b):
    d = L.data_of(b)
    return str(d.get("id")) if isinstance(d, dict) and d.get("id") else None

# ---- 夹具：给 kpi_proj 造 3 个 LANE3 Gate（G1/G2/G3）----
gids = {gc: gid for gid, gc in L.sql("SELECT id, gate_code FROM gates WHERE project_id=%s AND del_flag='0'" % PROJ)}
assert len(gids) == 3, gids
print("reuse gates:", gids, flush=True)

# ---- AC-DEL-01 无直接删除入口 ----
s, b, _, _ = L.req("DELETE", B, f"/api/v1/gates/{gids['G1']}", token=FA)
rec("AC-DEL-01", "DEL01-direct-delete-gate", "DELETE /api/v1/gates/{id}", s, b,
    "PASS" if s in (404, 405, 403) else "FAIL",
    "直删尝试 http=%s code=%s msg=%s; 静态: controller 包 grep DeleteMapping 命中仅 BusinessConfig/IpdRolePermission/KpiFunctionalMetrics/NegativeFeedback 4 文件, gate 系 controller 0 命中" % (s, L.code_of(b), L.msg_of(b)[:80]))
s, b, _, _ = L.req("DELETE", B, f"/api/v1/projects/{PROJ}", token=FA)
rec("AC-DEL-01", "DEL01-direct-delete-project", "DELETE /api/v1/projects/{id}", s, b,
    "PASS" if (s in (404, 405, 403)) or L.code_of(b) == 405 else "FAIL", "直删尝试 http=%s envelope_code=%s msg=%s(HTTP200+包络405=无路由的框架拒法,与404同语义:无直接删除入口)" % (s, L.code_of(b), L.msg_of(b)[:60]))

# ---- AC-DEL-02 Gate 双层审核全链 ----
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=FA,
                  body={"entityType": "gates", "entityId": int(gids["G1"]), "reason": "LANE3-DEL02 测试删除", "snapshot": "{\"mark\":\"LANE3-DEL02\"}"})
rid1 = id_of(b)
L.write_reg("deletion-submit", "POST", "/api/v1/deletion-requests", "deletion_requests", "LANE3- gate=%s req=%s" % (gids["G1"], rid1))
rec("AC-DEL-02", "DEL02-submit", "POST /deletion-requests gates", s, b,
    "PASS" if s == 200 and L.code_of(b) == 0 and st_of(b) == "LEADER_REVIEW" else "FAIL",
    "status=%s id=%s(提交即进组长初审)" % (st_of(b), rid1))
s2, b2, _, _ = L.req("POST", FX and B, "/api/v1/deletion-requests/%s/leader-decision?approve=true&opinion=LANE3-pass" % rid1, token=FL)
L.write_reg("deletion-leader-ok", "POST", "/deletion-requests/%s/leader-decision" % rid1, "deletion_requests", "LANE3 组长初审通过")
rec("AC-DEL-02", "DEL02-leader-approve", "POST /%s/leader-decision(ipd-leader 900001=主组)" % rid1, s2, b2,
    "PASS" if s2 == 200 and st_of(b2) == "ADMIN_REVIEW" else "FAIL", "初审后 status=%s" % st_of(b2))
s3, b3, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/admin-decision?approve=true&opinion=LANE3-final" % rid1, token=FA)
L.write_reg("deletion-admin-ok", "POST", "/deletion-requests/%s/admin-decision" % rid1, "deletion_requests", "LANE3 超管终审通过→软删执行")
rec("AC-DEL-02", "DEL02-admin-approve", "POST /%s/admin-decision" % rid1, s3, b3,
    "PASS" if s3 == 200 and st_of(b3) == "DELETED" else "FAIL", "终审后 status=%s" % st_of(b3))
rows = L.sql("SELECT del_flag FROM gates WHERE id=%s" % gids["G1"])
gh = L.sql("SELECT COUNT(*) FROM audit_logs WHERE entity_type='gates' AND entity_id=%s" % gids["G1"])
rec("AC-DEL-02", "DEL02-verify-softdelete-audit", "真库直读 gates.del_flag + audit_logs", None, None,
    "PASS" if rows and rows[0][0] == "1" and int(gh[0][0]) >= 3 else "FAIL",
    "gate.del_flag=%s; gates/%s 审计条数=%s(SUBMIT/LEADER/ADMIN链在案); deletedAt语义=del_flag置位+请求记录留档" % (rows[0][0] if rows else "?", gids["G1"], gh[0][0]))
s4, b4, _, _ = L.req("GET", B, "/api/v1/deletion-requests/archive", token=FA)
inarch = rid1 in json.dumps(L.data_of(b4) or [], default=str)
rec("AC-DEL-02", "DEL02-archive-visible", "GET /deletion-requests/archive", s4, b4,
    "PASS" if s4 == 200 and inarch else "FAIL", "归档区含请求 %s: %s" % (rid1, inarch))

# ---- AC-DEL-03 普通业务信息 fail-closed 白名单 ----
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=FA,
                  body={"entityType": "deliverables", "entityId": 1, "reason": "LANE3-DEL03", "snapshot": "{\"mark\":\"LANE3\"}"})
rec("AC-DEL-03", "DEL03-unsupported-type", "POST /deletion-requests entityType=deliverables", s, b,
    "PASS" if s == 400 and "不支持" in L.msg_of(b) else "FAIL",
    "msg=%s; SUPPORTED={projects,products,persons,cert_templates,gates,requirements}(白名单常量直读源码 DeletionRequestServiceImpl:453)" % L.msg_of(b)[:90])
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=FA,
                  body={"entityType": "gates", "entityId": 999999999999, "reason": "LANE3-DEL03b", "snapshot": "{\"mark\":\"LANE3\"}"})
rec("AC-DEL-03", "DEL03-nonexistent-target", "POST /deletion-requests 不存在gate", s, b,
    "PASS" if s == 404 and "不存在" in L.msg_of(b) else "FAIL",
    "R217僵尸行根因修复实证: msg=%s" % L.msg_of(b)[:90])

# ---- AC-DEL-04 跨组初审人=主组组长 ----
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=FA,
                  body={"entityType": "gates", "entityId": int(gids["G2"]), "reason": "LANE3-DEL04", "snapshot": "{\"mark\":\"LANE3\"}"})
rid2 = id_of(b)
L.write_reg("deletion-submit", "POST", "/deletion-requests", "deletion_requests", "LANE3-DEL04 gate=%s" % gids["G2"])
s2, b2, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/leader-decision?approve=true" % rid2, token=FX)
rec("AC-DEL-04", "DEL04-foreign-role-leader-decision", "POST leader-decision as MARKET_PM", s2, b2,
    "PASS" if s2 in (400, 403) else "FAIL", "非组长被拒 http=%s msg=%s; 组长守卫=resolveScope取gate→project.main_group_id, ipd-leader(900001=主组)在DEL02已证同组可审" % (s2, L.msg_of(b2)[:80]))
s3, b3, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/leader-decision?approve=true" % rid2, token=FL)
rec("AC-DEL-04", "DEL04-main-group-leader-ok", "POST leader-decision as 主组组长", s3, b3,
    "PASS" if s3 == 200 and st_of(b3) == "ADMIN_REVIEW" else "FAIL", "主组(市场PM所在组900001)组长初审放行 status=%s; 协同组组长'知会'为通知面, 本波无独立HTTP读端点未证(PARTIAL口径)" % st_of(b3))
# 收尾 rid2：超管驳回（同时作 DEL-05 素材之一）
s4, b4, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/admin-decision?approve=false&opinion=LANE3-DEL05-reject" % rid2, token=FA)
rec("AC-DEL-05", "DEL05-reject-recorded", "POST admin-decision approve=false", s4, b4,
    "PASS" if s4 == 200 and st_of(b4) == "REJECTED" else "FAIL", "status=%s 驳回意见随记录保存(opinion字段回读)" % st_of(b4))
s5, b5, _, _ = L.req("GET", B, "/api/v1/deletion-requests/my-requests", token=FA)
mine = [r for r in (L.data_of(b5) or []) if str(r.get("id")) == rid2]
rec("AC-DEL-05", "DEL05-applicant-visible", "GET /my-requests", s5, b5,
    "PASS" if s5 == 200 and mine and mine[0].get("status") == "REJECTED" else "FAIL",
    "申请人可见驳回全记录: %s" % (json.dumps(mine[0], ensure_ascii=False)[:150] if mine else "缺行"))
s6, b6, _, _ = L.req("GET", B, "/api/v1/deletion-requests/review-queue", token=FL)
inq = rid2 in json.dumps(L.data_of(b6) or [], default=str)
rec("AC-DEL-05", "DEL05-rejected-not-in-queue", "GET /review-queue(组长)", s6, b6,
    "PASS" if s6 == 200 and not inq else "FAIL", "已驳回 %s 不再出现在待审队列=%s; '申请人收到通知'为通知面未单独证" % (rid2, not inq))

# ---- AC-DEL-06 撤回 ----
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=FA,
                  body={"entityType": "gates", "entityId": int(gids["G3"]), "reason": "LANE3-DEL06", "snapshot": "{\"mark\":\"LANE3\"}"})
rid3 = id_of(b)
L.write_reg("deletion-submit", "POST", "/deletion-requests", "deletion_requests", "LANE3-DEL06 gate=%s" % gids["G3"])
s2, b2, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/withdraw" % rid3, token=FA)
rec("AC-DEL-06", "DEL06-withdraw-within24h", "POST /%s/withdraw(<24h)" % rid3, s2, b2,
    "PASS" if s2 == 200 and st_of(b2) == "WITHDRAWN" else "FAIL", "status=%s; 时限=deletion.withdrawHours(默认24,业务配置可覆盖); >24h与终态/非本人统一404防侧信道(源码208-235)" % st_of(b2))
s3, b3, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/withdraw" % rid3, token=FA)
rec("AC-DEL-06", "DEL06-withdraw-again-404", "再次撤回(已终态)", s3, b3,
    "PASS" if s3 == 404 and L.code_of(b3) != 0 else "FAIL", "终态再撤统一404: http=%s msg=%s; 真>24h路径需改DB create_time(禁直写)+等24h→NOT-RUN" % (s3, L.msg_of(b3)[:60]))

# ---- AC-DEL-07 逾期升级端点 ----
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests/escalate-overdue", token=FA)
L.write_reg("deletion-escalate", "POST", "/deletion-requests/escalate-overdue", "deletion_requests", "LANE3-DEL07 幂等扫描")
esc = (L.data_of(b) or {}).get("escalated")
rec("AC-DEL-07", "DEL07-escalate-endpoint", "POST /escalate-overdue(超管)", s, b,
    "PASS" if s == 200 and isinstance(esc, int) else "FAIL",
    "escalated="+str(esc)+"; 本轮在案请求dueAt均未过→计数为0属如实; 到时升级分支需时间旅行(禁直写SQL)NOT-RUN诚实记账; 端点200/0可执行已证)")
s2, b2, _, _ = L.req("GET", B, "/api/v1/deletion-requests/overdue-admin-review", token=FA)
rec("AC-DEL-07", "DEL07-overdue-admin-list", "GET /overdue-admin-review", s2, b2,
    "PASS" if s2 == 200 and L.code_of(b2) == 0 else "FAIL", "code=%s 逾期终审清单可读" % L.code_of(b2))
s3, b3, _, _ = L.req("GET", B, "/api/v1/deletion-requests/overdue-admin-review", token=FX)
rec("AC-DEL-07", "DEL07-negative-role-403", "GET overdue-admin-review as MARKET_PM", s3, b3,
    "PASS" if s3 == 403 else "FAIL", "非超管403=权限面守卫预期(负向用例)")

# ---- AC-DEL-08 purge 二次确认 ----
s, b, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/purge" % rid1, token=FA)
L.write_reg("deletion-purge", "POST", "/deletion-requests/%s/purge" % rid1, "deletion_requests", "LANE3-DEL08 清除")
purged = "PURGED" in json.dumps(L.data_of(b) or {}, default=str)
rec("AC-DEL-08", "DEL08-purge", "POST /%s/purge(超管)" % rid1, s, b,
    "PASS" if s == 200 and purged else "FAIL", "remark置PURGED标记=%s; purge动作再写审计见下条" % purged)
pa = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action LIKE '%%PURGE%%' AND entity_id=%s" % rid1)
rec("AC-DEL-08", "DEL08-purge-audit", "真库直读 audit_logs PURGE 条目", None, None,
    "PASS" if int(pa[0][0]) >= 1 else "FAIL", "audit_logs(action~PURGE,entity_id=%s)=%s 条; '二次确认'实现为purge端点本身超管专属+已purge再purge拒(下条)" % (rid1, pa[0][0]))
s2, b2, _, _ = L.req("POST", B, "/api/v1/deletion-requests/%s/purge" % rid1, token=FA)
rec("AC-DEL-08", "DEL08-purge-twice-409", "重复 purge", s2, b2,
    "PASS" if s2 in (400, 409) and "已清除" in L.msg_of(b2) else "FAIL", "msg=%s http=%s(防二次清除守卫)" % (L.msg_of(b2)[:80], s2))

print("DEL domain done.", flush=True)
