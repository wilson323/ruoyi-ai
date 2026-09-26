#!/usr/bin/env python3
# lane3 夹具工厂：产品/项目/成员/人员（LANE3- 前缀，全走 HTTP）
import sys, json, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r219_lib as L

FX = os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures.json")
fx = json.load(open(FX)) if os.path.exists(FX) else {}
T = {u: L.login(L.B39, u) for u in ("ipd-admin", "ipd-leader", "ipd-market", "ipd-rd")}

def mk_product(name, tag):
    s, bj, _, _ = L.req("POST", L.B39, "/api/v1/products", token=T["ipd-market"],
        body={"productName": name, "productCode": tag, "modelCode": tag + "-M1", "source": "PM_NEW", "groupId": 900001})
    d = L.data_of(bj) or {}
    L.write_reg("product.create", "POST", "/api/v1/products", entity=name)
    print("product", name, s, L.code_of(bj), d.get("id"))
    return d.get("id")

def mk_project(name, pid, template, markets, level, sales, chan, nps, scenes):
    body = {"name": name, "productId": pid, "templateType": template, "targetMarkets": markets,
            "level": level, "targetSalesAmount": sales, "targetChannelCount": chan,
            "targetNps": nps, "targetSceneCount": scenes, "mainGroupId": 900001}
    s, bj, _, _ = L.req("POST", L.B39, "/api/v1/projects", token=T["ipd-admin"], body=body)
    d = L.data_of(bj) or {}
    L.write_reg("project.create", "POST", "/api/v1/projects", entity=name)
    print("project", name, s, L.code_of(bj), d.get("id") if isinstance(d, dict) else d, L.msg_of(bj)[:60])
    return str(d.get("id")) if isinstance(d, dict) and d.get("id") else None

def bind_member(proj, person_id, role):
    s, bj, _, _ = L.req("POST", L.B39, "/api/v1/projects/%s/members" % proj, token=T["ipd-admin"],
                        body={"personId": int(person_id), "role": role})
    L.write_reg("member.bind", "POST", "/api/v1/projects/%s/members" % proj, entity="%s:%s" % (person_id, role))
    print("member", proj, person_id, role, s, L.code_of(bj), L.msg_of(bj)[:60])
    return s, L.code_of(bj)

def sync_person(empNo):
    s, bj, _, _ = L.req("POST", L.B39, "/api/v1/person-sync/jobs", token=T["ipd-admin"],
                        body={"employeeNo": empNo, "idempotencyKey": empNo})
    L.write_reg("person.sync", "POST", "/api/v1/person-sync/jobs", entity=empNo)
    print("person-sync", empNo, s, L.code_of(bj))
    rows = L.sql("SELECT id FROM persons WHERE employee_no='%s' AND del_flag='0'" % empNo)
    return rows[0][0] if rows else None

# --- KPI 项目：双PM=ipd-market/ipd-rd，组 900001 ---
if not fx.get("kpi_proj"):
    p = fx.get("kpi_prod") or mk_product("LANE3-KPI产品-0925b", "LANE3-KPIb")
    j = mk_project("LANE3-KPI验证项目-0925b", p, "SOFTWARE", '["CN"]', "A", 5000000, 80, 50, 4)
    fx["kpi_prod"], fx["kpi_proj"] = p, j
    if j:
        bind_member(j, 900103, "MARKET_PM"); bind_member(j, 900104, "RD_PM")
# --- HAND 夹具人员（Mock MARKET_PM 三枚） ---
for k, e in (("hand_from", "LANE3-HND-F01"), ("hand_to", "LANE3-HND-T01"), ("hand_to2", "LANE3-HND-T02")):
    if not fx.get(k):
        fx[k] = sync_person(e)
# --- HAND 项目 3 个（P1..P3），成员=hand_from(MARKET_PM) ---
if not fx.get("hand_projs") or not all(fx.get("hand_projs")):
    hs = []
    for i in (1, 2, 3):
        p = mk_product("LANE3-HAND产品%d-0925b" % i, "LANE3-HND%d b".replace(" ","") % i)
        j = mk_project("LANE3-HAND项目%d-0925b" % i, p, "SOFTWARE", '["CN"]', "B", 2000000, 10, 30, 2)
        hs.append(j)
        if j:
            bind_member(j, fx["hand_from"], "MARKET_PM")
    fx["hand_projs"] = hs
json.dump(fx, open(FX, "w"), ensure_ascii=False, indent=1)
print("FIXTURES:", json.dumps(fx, ensure_ascii=False))
