#!/usr/bin/env python3
# R218 QA-08 R5修正复跑轮(fix10)：AC域10条fixture假红(归因行1-9,12)按已验证通过路径复跑转绿
# 纪律：只跑这10条；不碰SEC-04/A1上传与9140005共享夹具；不写执行清单/不改看板卡；凭证仅内存绝不打印
# 环境：B39=http://127.0.0.1:16039(PID 61307,env -i无代理)；fixture与 r218_qa08*.py 内 FIX-R5 注释一一对应
import json, os, sys
sys.path.insert(0, "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-QA08-SEC04-20260925")
import r218_lib as L
B = L.B39
OUT = "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-FAIL归因-20260925"
SUF = os.environ.get("F10SUF", "-0925F10")  # 复跑批次后缀(重跑换新后缀防对象名冲突)
def ce(b): return b.get("code") if isinstance(b, dict) else None
def ms(b): return (b.get("message") or b.get("msg") or "") if isinstance(b, dict) else str(b)[:80]
def did(b):
    d = b.get("data") if isinstance(b, dict) else None
    if isinstance(d, dict): return d.get("id") or (d.get("project") or {}).get("id")
    return d if isinstance(d, (int, str)) and d else None
G, W = [], []
def R(no, card, case, orig, fix, cmd, ok, res, **kw):
    r = {"seq": no, "card": card, "case": case, "original_fail_assertion": orig, "fixture_fix": fix,
         "retest_cmd": cmd, "retest_result": res, "verdict": "PASS" if ok else "FAIL", "ts": L.now()}
    r.update(kw); G.append(r)
    print(("PASS " if ok else "FAIL ") + "#%s %s | %s" % (no, case, str(res)[:160]), flush=True)
def mkprod(tok, nm):
    s, b, _, _ = L.req("POST", B, "/api/v1/products", token=tok, body={"productName": nm, "source": "PM_NEW"})
    return did(b)
def proj_full(nm, prod):
    return {"name": nm, "templateType": "SOFTWARE", "level": "A", "mainGroupId": 9120002, "productId": int(prod),
            "targetMarkets": json.dumps(["CONSUMER"], ensure_ascii=False), "targetSalesAmount": 100,
            "targetChannelCount": 3, "targetNps": 40, "targetSceneCount": 2}
def legacy_full(nm, prod):
    return {"name": nm, "productId": int(prod), "templateType": "SOFTWARE", "level": "A", "mainGroupId": 9120002,
            "targetMarkets": json.dumps(["CONSUMER"], ensure_ascii=False), "targetSalesAmount": 200,
            "targetChannelCount": 5, "targetNps": 40, "targetSceneCount": 2,
            "legacyEffectiveAt": "2026-08-01T00:00:00", "declaredStage": "DEV", "missingHistoryAck": True,
            "alternativeEvidence": {"D01": "R218-FIX10邮件纪要"}}
def chain(tok, pid):
    s1, b1, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=TEAMING" % pid, token=tok)
    s2, b2, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=ACTIVE" % pid, token=tok)
    fin = L.sql("SELECT status FROM projects WHERE id=%s" % pid)[0][0]
    return "TEAMING:%s/%s ACTIVE:%s/%s 终态=%s" % (s1, ce(b1), s2, ce(b2), fin), fin == "ACTIVE"
T = {u: L.login(B, u) for u in ("ipd-admin", "ipd-leader", "ipd-market", "ipd-rd")}
assert all(T.values()), "login failed @16039"
TA = T["ipd-admin"]
# C1/C2 AC-PROD-03(归因行1/2)：补目标销售额等四基准 + 挂真实产品(1:1)
PA = mkprod(TA, "R218-FIX10-产品A" + SUF); PB = mkprod(TA, "R218-FIX10-产品B" + SUF)
W += ["products id=%s (FIX10产品A,PM_NEW)" % PA, "products id=%s (FIX10产品B,PM_NEW)" % PB]
n1 = "R218-FIX10-SW项目A" + SUF
s, b, _, _ = L.req("POST", B, "/api/v1/projects", token=TA, body=proj_full(n1, PA)); P1 = did(b)
n2 = "R218-FIX10-SW项目B" + SUF
s2, b2, _, _ = L.req("POST", B, "/api/v1/projects", token=TA, body=proj_full(n2, PB)); P2 = did(b2)
if P1: W.append("projects id=%s (%s +全套stages/actions)" % (P1, n1))
if P2: W.append("projects id=%s (%s,HAND-01c/IPD-03隔离载体)" % (P2, n2))
R(1, "AC-PROD-03", "P3-1F创建SOFTWARE项目(销售额补齐)", "400 立项目标销售额必填且须大于 0（四基准/奖金池基数）",
  "fixture补 targetSalesAmount=100 且四基准(targetMarkets/channelCount/nps/sceneCount)全齐",
  "POST /api/v1/projects (admin) 全必填 @16039", s == 200 and ce(b) == 0 and P1,
  "http=%s code=%s project_id=%s msg=%s" % (s, ce(b), P1, ms(b)), original_case="P3-1创建SOFTWARE项目", row=1)
R(2, "AC-PROD-03", "P3-1F2创建SOFTWARE项目(挂真实产品)", "400 项目必须归属产品（产品:项目 = 1:1）",
  "fixture先 POST /products(source=PM_NEW) 建产品再补 productId(1:1各挂一件)",
  "POST /api/v1/projects (admin) productId=%s @16039" % PB, s2 == 200 and ce(b2) == 0 and P2,
  "http=%s code=%s project_id=%s msg=%s" % (s2, ce(b2), P2, ms(b2)), original_case="P3-1创建SOFTWARE项目", row=2)
# C3/C4 AC-PROD-04 存量导入(归因行3/4)：补 targetMarkets / targetChannelCount 等必填
PC = mkprod(TA, "R218-FIX10-产品C" + SUF); PD = mkprod(TA, "R218-FIX10-产品D" + SUF)
W += ["products id=%s (FIX10产品C,PM_NEW)" % PC, "products id=%s (FIX10产品D,PM_NEW)" % PD]
n3 = "R218-FIX10-存量导入-1" + SUF
s, b, _, _ = L.req("POST", B, "/api/v1/projects/legacy-import", token=TA, body=legacy_full(n3, PC)); L1 = did(b)
n4 = "R218-FIX10-存量导入-2" + SUF
s2, b2, _, _ = L.req("POST", B, "/api/v1/projects/legacy-import", token=TA, body=legacy_full(n4, PD)); L2 = did(b2)
if L1: W.append("projects id=%s (%s,LEGACY)" % (L1, n3))
if L2: W.append("projects id=%s (%s,LEGACY)" % (L2, n4))
src1 = L.sql("SELECT source,status FROM projects WHERE id=%s" % L1)[0] if L1 else ["-", "-"]
src2 = L.sql("SELECT source,status FROM projects WHERE id=%s" % L2)[0] if L2 else ["-", "-"]
R(3, "AC-PROD-04", "P4-1F存量导入正例(目标市场补齐)", "400 目标市场必填（驱动认证清单 M1）",
  "fixture补 targetMarkets=[CONSUMER] + productId + ISO生效日(legacyEffectiveAt=2026-08-01T00:00:00)",
  "POST /api/v1/projects/legacy-import (admin) ack=true declaredStage=DEV @16039",
  bool(L1) and ce(b) == 0, "http=%s code=%s project_id=%s source=%s status=%s msg=%s" % (s, ce(b), L1, src1[0], src1[1], ms(b)),
  original_case="P4-1R存量导入正例", row=3)
R(4, "AC-PROD-04", "P4-1F2存量导入正例(渠道商数补齐)", "400 立项目标渠道商数必填且不可为负",
  "fixture在补市场基础上再补 targetChannelCount=5 及 targetNps/targetSceneCount",
  "POST /api/v1/projects/legacy-import (admin) 四基准全齐 @16039",
  bool(L2) and ce(b2) == 0, "http=%s code=%s project_id=%s source=%s status=%s msg=%s" % (s2, ce(b2), L2, src2[0], src2[1], ms(b2)),
  original_case="P4-1R2存量导入正例(targetMarkets补齐)", row=4)
# C5/C6 AC-PROD-04 状态流转(归因行5/6)：修正非法链 → 合法链 DRAFT→TEAMING→ACTIVE
c1res, c1ok = chain(TA, L1) if L1 else ("项目未建", False)
c2res, c2ok = chain(TA, L2) if L2 else ("项目未建", False)
R(5, "AC-PROD-04", "P4-3F历史缺失不阻断流转(合法链)", "400 状态机非法迁移: DRAFT → ACTIVE（原用例DRAFT直跳ACTIVE）",
  "步骤修正：先 target=TEAMING 再 target=ACTIVE（ProjectService.java:54-59 合法迁移）",
  "POST /projects/%s/status?target=TEAMING → ACTIVE (admin) + db回读" % L1, c1ok, c1res,
  original_case="P4-3R3历史缺失不阻断状态流转", row=5)
hm = L.sql("SELECT COUNT(*) FROM stage_actions WHERE project_id=%s AND history_mark IS NOT NULL AND del_flag='0'" % L2)[0][0] if L2 else "?"
R(6, "AC-PROD-04", "P4-3F2链去CONFIRMED终验(合法链)", "400 状态机非法迁移: DRAFT → CONFIRMED（原用例链含CONFIRMED，该态不在projects枚举）",
  "移除 CONFIRMED 步骤，链改为 TEAMING→ACTIVE；同时回读 history_mark 行证明历史缺失不阻断",
  "POST /projects/%s/status TEAMING→ACTIVE (admin) + mysql history_mark回读" % L2, c2ok and int(hm) >= 1,
  "%s history_mark行=%s(LEGACY缺失标记)" % (c2res, hm), original_case="P4-3R4历史缺失项目沿合法链流转至ACTIVE", row=6)
# C7/C8 AC-HAND-01c(归因行7/8)：跨组→同组发起 + 接手人改胡9110005；载体=自有项目P2隔离夹具(不触9140005共享区/不干扰后台SEC-04复跑)
if P2:
    L.sql("UPDATE project_members SET del_flag='1' WHERE project_id=%s AND role='MARKET_PM' AND del_flag='0'" % P2)
    MID = 2103700000000000001
    L.sql("DELETE FROM project_members WHERE id=%s" % MID)
    L.sql("INSERT INTO project_members (id,project_id,person_id,role,member_type,locked_level,locked_amount,join_date,create_time,tenant_id,del_flag) VALUES (%s,%s,2096266884247736321,'MARKET_PM','FORMAL','B',0,'2026-09-25',NOW(),'000000','0')" % (MID, P2))
    W.append("project_members id=%s (FIX10在任绑定赵@P2=%s MARKET_PM;同角色bootstrap行已软删)" % (MID, P2))
def ho(tok, who):
    s, b, _, _ = L.req("POST", B, "/api/v1/handovers", token=tok,
        body={"projectId": int(P2), "role": "MARKET_PM", "toPersonId": 9110005, "note": "R218-FIX10 " + who, "onBehalf": True})
    return s, b, did(b)
def cancel(hid, tok):
    s, b, _, _ = L.req("POST", B, "/api/v1/handovers/%s/cancel" % hid, token=tok,
        body={"reason": "R218-FIX10 验收闭环清理", "confirmation": "确认撤销该移交"})
    fin = L.sql("SELECT status FROM handover_records WHERE id=%s" % hid)[0][0]
    return s, ce(b), fin
if P2:
    L.sql("UPDATE persons SET group_id=9120002 WHERE id=900102")  # 组长换组=同组发起(assertSameGroupIpd设计内)
    try:
        L._tok.pop((B, "ipd-leader"), None)
        s7, b7, H1 = ho(L.login(B, "ipd-leader"), "组长同组代移交(FIX10)")
        if H1:
            W.append("handover_records id=%s (FIX10#7 组长同组赵→胡,随即cancel)" % H1)
            sc, cc, fin = cancel(H1, L.login(B, "ipd-leader"))
        else:
            sc, cc, fin = None, None, "-"
    finally:
        L.sql("UPDATE persons SET group_id=900001 WHERE id=900102")
        L._tok.pop((B, "ipd-leader"), None)
    ok7 = s7 == 200 and ce(b7) == 0 and bool(H1) and fin == "ROLLED_BACK"
    R(7, "AC-HAND-01c", "H1F组长代移交正例(同组发起)", "403 无权操作（原用例组长在900001组打9120002组项目，跨组守卫设计内）",
      "跨组→同组：发起前把组长900102临时置9120002，发起后还原900001；接手人=胡9110005",
      "POST /api/v1/handovers (leader同组) projectId=%s to=9110005 onBehalf + cancel + db回读" % P2, ok7,
      "submit=%s/%s cancel=%s/%s 终态=%s msg=%s" % (s7, ce(b7), sc, cc, fin, ms(b7)),
      original_case="H1-组长代移交正例(onBehalf=true)", row=7)
else:
    s7, b7, H1, sc, cc, fin, ok7 = None, None, None, None, None, "-", False
    R(7, "AC-HAND-01c", "H1F组长代移交正例(同组发起)", "403 无权操作", "见C7主体", "skipped(P2未建)", False, "前置载体缺失", original_case="H1-组长代移交正例(onBehalf=true)", row=7)
if P2:
    s8, b8, H2 = ho(TA, "超管代移交-胡接手正例(FIX10)")
    rows_mid = L.sql("SELECT person_id,exit_date FROM project_members WHERE project_id=%s AND role='MARKET_PM' AND del_flag='0' ORDER BY id" % P2) if H2 else []
    if H2:
        W.append("handover_records id=%s (FIX10#8 赵→胡 onBehalf COMPLETED,随即cancel)" % H2)
        sc8, cc8, fin8 = cancel(H2, TA)
        rows_af = L.sql("SELECT person_id,exit_date FROM project_members WHERE project_id=%s AND role='MARKET_PM' AND del_flag='0' ORDER BY id" % P2)
    else:
        sc8, cc8, fin8, rows_af = None, None, "-", []
    ok8 = s8 == 200 and ce(b8) == 0 and bool(H2) and fin8 == "ROLLED_BACK"
else:
    s8, b8, H2, sc8, cc8, fin8, rows_mid, rows_af, ok8 = None, None, None, None, None, "-", [], [], False
R(8, "AC-HAND-01c", "H1RF接手人修正正例(to=胡9110005)", "400 接手人不能与原负责人相同（原fixture接手人=赵，与服务端反查在任同人）",
  "接手人由赵2096266884247736321改为胡9110005；成功后随即cancel复位(ROLLED_BACK)，绑定回到复跑前态",
  "POST /api/v1/handovers (admin) projectId=%s to=9110005 onBehalf + cancel + 绑定回读" % P2, ok8,
  "submit=%s/%s cancel=%s/%s 终态=%s 移交后=%s 撤销后=%s" % (s8, ce(b8), sc8, cc8, fin8, str(rows_mid)[:90], str(rows_af)[:90]),
  original_case="H1R-组长代移交正例(onBehalf)", row=8)
# C9 AC-INC-34(归因行9)：加 month 必填参数
s9, b9, h9, rb9 = L.req("GET", B, "/api/v1/report/export/project?month=2026-09", token=TA, timeout=40)
R(9, "AC-INC-34", "N5F项目报表导出(month补齐)", "400 ct=application/json 144bytes（缺 month 参数，端点契约=month必填/productId可选/keyword可选）",
  "URL补 month=2026-09；原用例臆测 projectId 参数属建档错误",
  "GET /api/v1/report/export/project?month=2026-09 (admin) @16039",
  s9 == 200 and ce(b9) == 0 and len(rb9) > 1000, "http=%s code=%s bytes=%d ct=%s msg=%s" % (s9, ce(b9), len(rb9), (h9.get("Content-Type") or "")[:28], ms(b9)),
  original_case="N5-项目报表导出(admin)", row=9)
# C10 AC-IPD-03(归因行12)：operator 与动作 owner_role 配对 + fields 仅白名单(actualDoneAt,ISO)
acts = L.sql("SELECT id,action_code,owner_role FROM stage_actions WHERE project_id=%s AND depth='LIGHT' AND status='NOT_STARTED' AND del_flag='0' ORDER BY owner_role,id" % P1) if P1 else []
tgt = next((a for a in acts if a[2] == "MARKET_PM"), acts[0] if acts else None)
ok10, res10 = False, "无LIGHT动作"
if tgt:
    tu = "ipd-market" if tgt[2] == "MARKET_PM" else "ipd-rd"
    s1, b1, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/fields" % tgt[0], token=T[tu], body={"actualDoneAt": "2026-09-25T11:00:00"})
    s2, b2, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218f10" % tgt[0], token=T[tu])
    s3, b3, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/transit?target=DONE&reason=r218f10" % tgt[0], token=T[tu])
    st = L.sql("SELECT status,actual_done_at FROM stage_actions WHERE id=%s" % tgt[0])[0]
    W.append("stage_actions id=%s (%s LIGHT %s 完成流转 status=%s)" % (tgt[0], tu, tgt[1], st[0]))
    ok10 = st[0] == "DONE" and bool(st[1])
    res10 = "fields=%s IN_PROGRESS=%s DONE=%s db=%s/%s" % (s1, s2, s3, st[0], st[1])
R(10, "AC-IPD-03", "L1F轻管fields+transit DONE(operator配对)", "409 envelope=40004 角色固定不可跨（ROLE_LOCKED角色锁）db=NOT_STARTED",
  "operator按动作owner_role配对(%s→%s动作%s)；fields体仅白名单actualDoneAt且ISO(原混入remark+非ISO日期)" % (tu if tgt else "?", tgt[2] if tgt else "?", tgt[1] if tgt else "?"),
  "POST /stage-actions/%s/fields + transit IN_PROGRESS + transit DONE + db回读" % (tgt[0] if tgt else "-"), ok10, res10,
  original_case="L1/L1R-轻管fields+transit DONE(服务腿)", row=12)
green = sum(1 for g in G if g["verdict"] == "PASS")
doc = {"batch": "R218-QA08-R5-fixture修正fix10-20260925", "env": B + " (PID 61307, env -i 无代理)",
       "scope": "AC域10条fixture假红(归因行1-9,12)修正复跑；AC-REQ-09/AC-PROD-09两条真缺陷不在本轮",
       "discipline": "未触SEC-04/A1上传与9140005共享夹具；执行清单-r218.json/看板卡零改动",
       "total": len(G), "green": green, "cases": G}
with open(OUT + "/fixture修正-转绿记录.json", "w", encoding="utf-8") as _f:
    json.dump(doc, _f, ensure_ascii=False, indent=1)
wp = OUT + "/写库清单-归因复测.json"
old = json.load(open(wp, encoding="utf-8")) if os.path.exists(wp) else []
with open(wp, "w", encoding="utf-8") as _f:
    json.dump(old + W, _f, ensure_ascii=False, indent=1)
print("FIX10 done green=%s/%s writes+=%s" % (green, len(G), len(W)))
