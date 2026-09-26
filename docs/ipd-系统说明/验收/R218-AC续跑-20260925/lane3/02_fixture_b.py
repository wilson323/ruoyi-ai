#!/usr/bin/env python3
# lane3 夹具工厂B：KPI 双PM补齐 + HAND P3 approvalRef 绑定
import sys, json, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r219_lib as L

FX = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures.json")
fx = json.load(open(FX))
TA = L.login(L.B39, "ipd-admin")

def bind_member(proj, person_id, role, approval_ref=None):
    body = {"personId": int(person_id), "role": role}
    if approval_ref: body["approvalRef"] = approval_ref
    s, bj, _, _ = L.req("POST", L.B39, "/api/v1/projects/%s/members" % proj, token=TA, body=body)
    L.write_reg("member.bind", "POST", "/api/v1/projects/%s/members" % proj, entity="%s:%s" % (person_id, role))
    print("member", proj, person_id, role, s, L.code_of(bj), L.msg_of(bj)[:70])
    return s, L.code_of(bj)

def sync_person(empNo):
    s, bj, _, _ = L.req("POST", L.B39, "/api/v1/person-sync/jobs", token=TA,
                        body={"employeeNo": empNo, "idempotencyKey": empNo})
    L.write_reg("person.sync", "POST", "/api/v1/person-sync/jobs", entity=empNo)
    rows = L.sql("SELECT id FROM persons WHERE employee_no='%s' AND del_flag='0'" % empNo)
    return rows[0][0] if rows else None

# KPI 项目双PM：market=新Mock、rd=刘研发(0项,RD_PM)
if fx.get("kpi_proj"):
    mkt = sync_person("LANE3-KPI-MKT1")
    fx["kpi_mkt_person"] = mkt
    bind_member(fx["kpi_proj"], mkt, "MARKET_PM")
    bind_member(fx["kpi_proj"], "2096266884222570498", "RD_PM")
# HAND P3：第3个绑定走 approvalRef
hp = fx.get("hand_projs") or []
if len(hp) >= 3 and hp[2]:
    bind_member(hp[2], fx["hand_from"], "MARKET_PM", approval_ref="LANE3-APPROVAL-0925")
json.dump(fx, open(FX, "w"), ensure_ascii=False, indent=1)
# 回读验证
rows = L.sql("SELECT pm.person_id, pm.role, pm.exit_date FROM project_members pm WHERE pm.project_id='%s' AND pm.del_flag='0'" % fx["kpi_proj"])
print("KPI members:", rows)
json.dump(fx, open(FX, "w"), ensure_ascii=False, indent=1)
