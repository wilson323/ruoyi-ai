# -*- coding: utf-8 -*-
# LANE3 PROD 域驱动：AC-PROD-01/02/06/07/08/10/11/12/13（03/04/05 r218已覆盖，09 已立缺陷卡→跳过）
import json
import r219_lib as L

B = L.BASE
RECS = json.load(open(L.REC_PATH))
SKIP = {r["case"] for r in RECS if r["card"].startswith("AC-PROD")}
AD = L.login(B, "ipd-admin"); MK = L.login(B, "ipd-market")

def rec(card, case, cmd, http, ec, verdict, note):
    if case in SKIP:
        print("skip dup", case); return
    L.record(RECS, {"card": card, "case": case, "cmd": cmd, "http": http,
                    "envelope_code": ec, "verdict": verdict, "note": note[:300]})

def mkprod(name, source="PM_NEW", model=None, tok=None):
    body = {"productName": name, "source": source, "modelCode": model}
    s, b, _, _ = L.req("POST", B, "/api/v1/products", token=tok or AD, body=body)
    d = L.data_of(b) or {}
    if s in (200,) and L.code_of(b) == 0:
        L.write_reg("product-create", "POST", "/api/v1/products", "products", name)
    return s, b, (d.get("id") if isinstance(d, dict) else None)

def mkproj(name, pid, tpl="HARDWARE", markets=None):
    body = {"name": name, "productId": int(pid), "templateType": tpl,
            "targetMarkets": json.dumps(markets or ["CN"], ensure_ascii=False), "level": "A", "levelCoefficient": 1.0,
            "targetSalesAmount": 1000000, "targetChannelCount": 3, "targetNps": 40, "targetSceneCount": 5}
    s, b, _, _ = L.req("POST", B, "/api/v1/projects", token=AD, body=body)
    if s == 200 and L.code_of(b) == 0:
        L.write_reg("project-create", "POST", "/api/v1/projects", "projects", name)
    d = L.data_of(b)
    return s, b, (d.get("id") if isinstance(d, dict) else None)

# ===== PROD-07 在研产品：PM 新增可建、可提需求 =====
s, b, p7 = mkprod("LANE3-PROD07在研-0926", source="PM_NEW", tok=MK)
st7 = (L.sql("SELECT status FROM products WHERE id=%s" % p7) or [["?"]])[0][0] if p7 else "?"
rec("AC-PROD-07", "PROD07-pm-create-in-rd", "POST /products source=PM_NEW (ipd-market)", s, L.code_of(b),
    "PASS" if L.code_of(b) == 0 and st7 == "IN_RD" else "FAIL",
    "PM 新增产品 id=%s 真库 status=%s（期望 IN_RD=在研）msg=%s" % (p7, st7, L.msg_of(b)[:60]))
# 在研产品下游客提需求
s2, b2, _, _ = L.req("POST", B, "/api/v1/public/demands", body={
    "customerName": "LANE3-PROD07客户", "feedbackPerson": "LANE3-PROD07反馈",
    "productId": int(p7) if p7 else None, "functionalRequirement": "在研产品下提交需求验证 AC-PROD-07 后半句"})
qc7 = (L.data_of(b2) or {}).get("queryCode") if isinstance(L.data_of(b2), dict) else None
rec("AC-PROD-07", "PROD07-demand-under-in-rd", "POST /public/demands productId=%s(IN_RD)" % p7, s2, L.code_of(b2),
    "PASS" if L.code_of(b2) == 0 and qc7 else "FAIL",
    "真缺陷候选：submit 门为 !\"ACTIVE\".equals(status)→40401 产品已下架，而 PM 新增默认 IN_RD/批量导入 ON_SALE 均≠ACTIVE ⇒ 在研/在售产品在游客门户不可提需求（本例 code=%s msg=%s）；需 changeStatus=ACTIVE 旁路才可" % (L.code_of(b2), L.msg_of(b2)[:50]))

# ===== PROD-06 超管批量导入在售 =====
items = [{"productCode": "LANE3-P6A", "productName": "LANE3-PROD06导入A-0926", "modelCode": "LANE3-M6A"},
         {"productCode": "LANE3-P6B", "productName": "LANE3-PROD06导入B-0926", "modelCode": "LANE3-M6B"},
         {"productCode": "LANE3-P6C", "productName": "LANE3-PROD06缺型号-0926", "modelCode": None}]
s, b, _, _ = L.req("POST", B, "/api/v1/products/batch-import", token=AD, body=items)
L.write_reg("batch-import", "POST", "/api/v1/products/batch-import", "products", "LANE3-PROD06 x3")
rows = L.data_of(b) or []
db = L.sql("SELECT product_name,status FROM products WHERE product_code LIKE 'LANE3-P6%' AND del_flag='0'")
rec("AC-PROD-06", "PROD06-batch-import-onsale", "POST /products/batch-import x3(含1条缺modelCode)", s, L.code_of(b),
    "PASS" if L.code_of(b) == 0 and any(v == "ON_SALE" for _, v in db) else "FAIL",
    "返回行=%s 真库=%s；ADMIN_IMPORT→ON_SALE(在售)，缺 modelCode 行按『必须填写 modelCode』处置见返回明细" % (json.dumps(rows, ensure_ascii=False)[:180], db))
s, b, _, _ = L.req("POST", B, "/api/v1/products/batch-import", token=MK, body=items[:1])
rec("AC-PROD-06", "PROD06-non-admin-rejected", "POST /products/batch-import (ipd-market)", s, L.code_of(b),
    "PASS" if s in (401, 403) or L.code_of(b) not in (0, None) else "FAIL",
    "requireAdmin 守卫：http=%s code=%s msg=%s" % (s, L.code_of(b), L.msg_of(b)[:60]))

# ===== PROD-01 产品↔项目 1:1 =====
s, b, pa = mkprod("LANE3-PROD01主产品-0926")
s2, b2, proj_a = mkproj("LANE3-PROD01项目A-0926", pa)
s3, b3, proj_b = mkproj("LANE3-PROD01项目B-0926", pa)
rec("AC-PROD-01", "PROD01-second-project-rejected", "POST /projects productId=%s 第二次" % pa, "%s/%s" % (s2, s3),
    L.code_of(b3), "PASS" if L.code_of(b3) not in (0, None) else "FAIL",
    "A项目建成功=%s；同产品再立项B被拒 http=%s code=%s msg=%s（1:1 Q5）" % (proj_a, s3, L.code_of(b3), L.msg_of(b3)[:80]))
s4, b4, _, _ = L.req("POST", B, "/api/v1/products/%s/bind-project?projectId=%s" % (pa, proj_a), token=AD)
rec("AC-PROD-01", "PROD01-rebind-same-idempotent", "POST bind-project 同项目重绑", s4, L.code_of(b4),
    "PASS" if L.code_of(b4) == 0 else "FAIL", "同 id 重绑幂等成功不写二次审计：code=%s msg=%s" % (L.code_of(b4), L.msg_of(b4)[:50]))
s5, b5, pb = mkprod("LANE3-PROD01第二产品-0926")
s6, b6, _, _ = L.req("POST", B, "/api/v1/products/%s/bind-project?projectId=%s" % (pb, proj_a), token=AD)
rec("AC-PROD-01", "PROD01-project-side-conflict", "产品B bind 已占用的项目A", s6, L.code_of(b6),
    "PASS" if L.code_of(b6) not in (0, None) else "FAIL",
    "project 端条件 UPDATE isNull(productId) affected=0 → 409：code=%s msg=%s" % (L.code_of(b6), L.msg_of(b6)[:80]))

# ===== PROD-02 硬件模板项目挂载 =====
s, b, pid = mkprod("LANE3-PROD02硬件产品-0926")
s2, b2, hw_proj = mkproj("LANE3-PROD02硬件项目-0926", pid, tpl="HARDWARE", markets=["CN", "SA"])
act = L.sql("SELECT COUNT(*),SUM(status='NA'),SUM(action_code='C12'),SUM(is_bio_feature='1') FROM stage_actions WHERE project_id=%s" % hw_proj)[0] if hw_proj else ["0","0","0","0"]
sw_na = L.sql("SELECT action_code,status FROM stage_actions WHERE project_id=%s AND action_code IN ('P05','D04','V04')" % hw_proj) if hw_proj else []
rec("AC-PROD-02", "PROD02-hw-mount-catalog", "POST /projects templateType=HARDWARE id=%s" % hw_proj, s2, L.code_of(b2),
    "PASS" if L.code_of(b2) == 0 and act[0] == "69" and act[2] == "1" else "FAIL",
    "真库：挂载 %s 行(目录全集69) NA=%s C12存在=%s bio标记=%s；硬件适用=69-3(SW专属)-…，SW专属行状态=%s" % (act[0], act[1], act[2], act[3], sw_na))

# ===== PROD-13 C12 不可取消 =====
c12 = (L.sql("SELECT id,is_bio_feature,status FROM stage_actions WHERE project_id=%s AND action_code='C12'" % hw_proj) or [["?", "?", "?"]])[0]
s, b, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/transit?target=NA&reason=LANE3-test" % c12[0], token=AD)
L.write_reg("c12-na-attempt", "POST", "/api/v1/stage-actions/%s/transit" % c12[0], "stage_actions", "PROD13 负向")
st_after = (L.sql("SELECT status FROM stage_actions WHERE id=%s" % c12[0]) or [["?"]])[0][0]
rec("AC-PROD-13", "PROD13-c12-no-cancel", "POST transit C12→NA (涉生物项目)", s, L.code_of(b),
    "PASS" if L.code_of(b) not in (0, None) and st_after != "NA" else "FAIL",
    "C12 id=%s bio=%s；NA 被拒 msg=%s 真库 status=%s（不可取消守卫）" % (c12[0], c12[1], L.msg_of(b)[:80], st_after))
s, b, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/transit?target=NA" % c12[0], token=AD)
rec("AC-PROD-13", "PROD13-na-requires-reason", "POST transit NA 无reason", s, L.code_of(b),
    "PASS" if L.code_of(b) not in (0, None) else "FAIL", "NA 必填原因守卫：msg=%s" % L.msg_of(b)[:80])

# ===== PROD-10/11 目标市场自动带出认证 =====
lv = None
if hw_proj:
    s, b, _, _ = L.req("GET", B, "/api/v1/projects/%s/cert-items" % hw_proj, token=AD)
    lv = L.data_of(b) or {}
    auto = [(i.get("countryCode"), i.get("certName"), i.get("source")) for i in (lv.get("items") or lv.get("list") or [])] if isinstance(lv, dict) else []
    has_saso = any(c == "SA" and "SABER" in (n or "") for c, n, _ in auto)
    rec("AC-PROD-10", "PROD10-sa-saber-auto", "GET /projects/%s/cert-items (markets CN+SA)" % hw_proj, s, L.code_of(b),
        "PASS" if has_saso else ("FAIL" if L.code_of(b) == 0 else "FAIL"),
        "AUTO项=%s；期望含 CN(CCC/SRRC/等保/个保法)+SA(SABER/SASO)" % auto[:10])
# PROD-11 巴西/印度/韩国
s, b, pbr = mkprod("LANE3-PROD11产品-0926")
s2, b2, br_proj = mkproj("LANE3-PROD11三国项目-0926", pbr, markets=["BR", "IN", "KR"])
s3, b3, _, _ = L.req("GET", B, "/api/v1/projects/%s/cert-items" % br_proj, token=AD)
lv3 = L.data_of(b3) or {}
auto3 = sorted({(i.get("countryCode"), i.get("certName")) for i in (lv3.get("items") or lv3.get("list") or [])} ) if isinstance(lv3, dict) else []
ok11 = all(any(x[0] == cc for x in auto3) for cc in ("BR", "IN", "KR"))
rec("AC-PROD-11", "PROD11-br-in-kr-auto", "GET /projects/%s/cert-items (BR/IN/KR)" % br_proj, s3, L.code_of(b3),
    "PASS" if ok11 and all(n in json.dumps(auto3) for n in ("ANATEL", "BIS", "KC")) else "FAIL",
    "带出=%s；期望 ANATEL/BIS/KC 三国齐" % auto3[:12])
s4, b4, _, _ = L.req("POST", B, "/api/v1/projects/%s/cert-items/sync" % br_proj, token=AD)
rec("AC-PROD-11", "PROD11-sync-idempotent", "POST cert-items/sync 二次", s4, L.code_of(b4),
    "PASS" if L.code_of(b4) == 0 else "FAIL", "sync 返回新增=%s（幂等应为0或稳定）" % (L.data_of(b4),))

# ===== PROD-12 手工补充认证项 =====
s, b, _, _ = L.req("POST", B, "/api/v1/projects/%s/cert-items" % (br_proj or hw_proj), token=AD,
                   body={"countryCode": "JP", "countryName": "日本", "certName": "LANE3-手工补充认证-0926",
                         "certAuthority": "LANE3-机构", "isMandatory": "0"})
L.write_reg("cert-manual-add", "POST", "/api/v1/projects/%s/cert-items" % br_proj, "project_cert_items", "PROD12")
row = L.sql("SELECT source,cert_name FROM project_cert_items WHERE project_id=%s AND cert_name='LANE3-手工补充认证-0926' AND del_flag='0'" % (br_proj or hw_proj))
rec("AC-PROD-12", "PROD12-manual-cert-add", "POST cert-items 手工项(JP)", s, L.code_of(b),
    "PASS" if L.code_of(b) == 0 and row and row[0][0] == "MANUAL" else "FAIL",
    "真库=%s（source=MANUAL 与 AUTO 并存）" % row)

# ===== PROD-08 其他/不确定：不路由 =====
s, b, _, _ = L.req("POST", B, "/api/v1/public/demands", body={
    "customerName": "LANE3-PROD08客户", "feedbackPerson": "LANE3-PROD08反馈",
    "productId": 9130006, "functionalRequirement": "其他/不确定产品不触发路由 AC-PROD-08"})
qc8 = (L.data_of(b) or {}).get("queryCode") if isinstance(L.data_of(b), dict) else None
r8 = L.sql("SELECT status,market_p…id,project_id FROM requirements WHERE query_code='%s'" % qc8) if qc8 else []
rec("AC-PROD-08", "PROD08-no-project-no-route", "POST /public/demands productId=9130006(ACTIVE无项目)", s, L.code_of(b),
    "PASS" if qc8 and r8 and r8[0][1] == "NULL" and r8[0][2] == "NULL" else ("FAIL-ENV" if L.code_of(b) == 40011 else "FAIL"),
    "resolveDualPm 返回 no-project：真库 status=%s mkt=%s rd=%s project=%s；卡片期望状态『待指派』但库内仍 SUBMITTED→口径差异记 FAIL 于下行" % tuple(r8[0]) if r8 else "提交失败 code=%s msg=%s" % (L.code_of(b), L.msg_of(b)[:80]))
if qc8 and r8 and r8[0][0] == "SUBMITTED":
    rec("AC-PROD-08", "PROD08-unassigned-status-wording", "sql 核对『待指派』口径", 200, 0, "FAIL",
        "真缺陷候选：BR-PROD-08/AC-PROD-08 期望需求状态=『待指派(UNASSIGNED)』，routeDualPm/submit 注释也提及 UNASSIGNED 态，但游客提交无路由时实际写库 status=SUBMITTED（仅带双PM空）——『待指派』状态在提交路径不可达")

print("PROD done. hw_proj=%s br_proj=%s p7=%s pa=%s" % (hw_proj, br_proj, p7, pa))
