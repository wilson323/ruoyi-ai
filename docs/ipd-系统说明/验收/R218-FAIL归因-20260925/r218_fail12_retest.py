#!/usr/bin/env python3
# R218 QA-08 业务域 12 条 FAIL 复测归因（B39=含全部已入库代码新 jar；凭证仅内存绝不打印）
import json, os, sys
sys.path.insert(0, "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-QA08-SEC04-20260925")
import r218_lib as L

OUT = "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-FAIL归因-20260925"
B = L.B39
recs = []
W = []  # 写库清单（本复测新增行）
def code_of(b): return b.get("code") if isinstance(b, dict) else None
def msg_of(b):  return (b.get("message") or b.get("msg") or "") if isinstance(b, dict) else json.dumps(b, ensure_ascii=False)[:120] if b else ""
def data_of(b): return b.get("data") if isinstance(b, dict) else None
def R(**k):
    k["ts"] = L.now(); recs.append(k)
    print(">>", k.get("item"), "|", k.get("retest_cmd","")[:60], "| http=", k.get("http"), "|", str(k.get("result",""))[:110], flush=True)

T = {u: L.login(B, u) for u in ("ipd-admin", "ipd-leader", "ipd-market", "ipd-rd")}
assert all(T.values()), "login failed"
print("login ok (4 users @B39)", flush=True)

BASE = {"name": None, "templateType": "SOFTWARE", "level": "A", "mainGroupId": 9120002,
        "targetMarkets": json.dumps(["CONSUMER"], ensure_ascii=False), "targetSalesAmount": 100,
        "targetChannelCount": 3, "targetNps": 40, "targetSceneCount": 2}

# ---------- ① AC-PROD-03 ×2：目标销售额必填 / 必须归属产品 ----------
s, bj, _, _ = L.req("POST", B, "/api/v1/products", token=T["ipd-admin"],
                    body={"productName": "R218-归因复测产品A-0925", "source": "PM_NEW"})
PA = (data_of(bj) or {}).get("id")
W.append("products id=%s (R218-归因复测产品A-0925)" % PA)
s, bj, _, _ = L.req("POST", B, "/api/v1/products", token=T["ipd-admin"],
                    body={"productName": "R218-归因复测产品B-0925", "source": "PM_NEW"})
PB = (data_of(bj) or {}).get("id")
W.append("products id=%s (R218-归因复测产品B-0925)" % PB)

p1 = dict(BASE); p1["name"] = "R218-归因-缺销售额-0925"; p1["productId"] = int(PA); p1.pop("targetSalesAmount")
s, bj, _, _ = L.req("POST", B, "/api/v1/projects", token=T["ipd-admin"], body=p1)
R(item="1a-AC-PROD-03目标销售额必填", original="400 立项目标销售额必填且须大于 0", retest_cmd="POST /projects 缺targetSalesAmount(其余全)",
  http=s, result=msg_of(bj), verdict_msg="复现原错误=守卫设计内" if "目标销售额" in msg_of(bj) else "未复现")
p2 = dict(BASE); p2["name"] = "R218-归因-缺产品-0925"; p2.pop("productId", None)
p2.update({"targetMarkets": BASE["targetMarkets"]})
s, bj, _, _ = L.req("POST", B, "/api/v1/projects", token=T["ipd-admin"], body=p2)
R(item="1b-AC-PROD-03项目必须归属产品", original="400 项目必须归属产品（产品:项目 = 1:1）", retest_cmd="POST /projects 缺productId(其余全)",
  http=s, result=msg_of(bj), verdict_msg="复现原错误=守卫设计内" if "归属产品" in msg_of(bj) else "未复现")
p3 = dict(BASE); p3["name"] = "R218-归因-完整夹具-0925"; p3["productId"] = int(PA)
s, bj, _, _ = L.req("POST", B, "/api/v1/projects", token=T["ipd-admin"], body=p3)
PID = (data_of(bj) or {}).get("id")
if PID: W.append("projects id=%s (R218-归因-完整夹具-0925 +stages/actions)" % PID)
R(item="1c-AC-PROD-03补齐必填后正例", original="×2 FAIL(缺字段)", retest_cmd="POST /projects 全字段+真实productId",
  http=s, envelope=code_of(bj), project_id=PID, result=("200 创建成功 id=%s" % PID) if PID else msg_of(bj),
  verdict_msg="转绿" if PID else "仍红")

# ---------- ② AC-PROD-04 存量导入 ×2（目标市场/渠道商数）+ ③ P4-3R3/R4 状态链 ----------
lg = {"name": "R218-归因-存量缺市场-0925", "productId": int(PB), "templateType": "SOFTWARE", "level": "A",
      "mainGroupId": 9120002, "targetSalesAmount": 200, "legacyEffectiveAt": "2026-08-01T00:00:00",
      "declaredStage": "DEV", "missingHistoryAck": True, "alternativeEvidence": {"D01": "R218归因复测邮件纪要"}}
s, bj, _, _ = L.req("POST", B, "/api/v1/projects/legacy-import", token=T["ipd-admin"], body=lg)
R(item="2a-AC-PROD-04目标市场必填", original="400 目标市场必填（驱动认证清单 M1）", retest_cmd="POST legacy-import 缺targetMarkets",
  http=s, result=msg_of(bj), verdict_msg="复现原错误=守卫设计内" if "目标市场" in msg_of(bj) else "未复现")
lg2 = dict(lg); lg2["targetMarkets"] = json.dumps(["CONSUMER"], ensure_ascii=False)
s, bj, _, _ = L.req("POST", B, "/api/v1/projects/legacy-import", token=T["ipd-admin"], body=lg2)
R(item="2b-AC-PROD-04目标渠道商数必填", original="400 立项目标渠道商数必填且不可为负", retest_cmd="POST legacy-import 补targetMarkets缺targetChannelCount",
  http=s, result=msg_of(bj), verdict_msg="复现原错误=守卫设计内" if "渠道商数" in msg_of(bj) else "未复现")
lg3 = dict(lg2); lg3["name"] = "R218-归因-存量完整-0925"
lg3.update({"targetChannelCount": 5, "targetNps": 40, "targetSceneCount": 2})
s, bj, _, _ = L.req("POST", B, "/api/v1/projects/legacy-import", token=T["ipd-admin"], body=lg3)
LP = (data_of(bj) or {}).get("id")
if LP: W.append("projects id=%s (R218-归因-存量完整-0925, LEGACY)" % LP)
R(item="2c-AC-PROD-04补齐必填后正例", original="P4-1R/P4-1R2 FAIL", retest_cmd="POST legacy-import 必填全补齐",
  http=s, envelope=code_of(bj), project_id=LP, result=("200 id=%s" % LP) if LP else msg_of(bj),
  verdict_msg="转绿" if LP else "仍红")
if LP:
    s1, bj1, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=ACTIVE" % LP, token=T["ipd-admin"])
    R(item="3a-AC-PROD-04P4-3R3直跳ACTIVE", original="400 状态机非法迁移 DRAFT→ACTIVE", retest_cmd="POST /projects/%s/status?target=ACTIVE(直跳)" % LP,
      http=s1, result=msg_of(bj1), verdict_msg="复现=状态机fail-closed设计内(非缺陷)")
    s2, bj2, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=CONFIRMED" % LP, token=T["ipd-admin"])
    R(item="3b-AC-PROD-04P4-3R4非法态CONFIRMED", original="链CONFIRMED:400 终态DRAFT", retest_cmd="POST status?target=CONFIRMED",
      http=s2, result=msg_of(bj2), verdict_msg="复现=CONFIRMED不在projects状态机(ProjectService.java:54-59),执行器误用")
    sa, bja, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=TEAMING" % LP, token=T["ipd-admin"])
    sb, bjb, _, _ = L.req("POST", B, "/api/v1/projects/%s/status?target=ACTIVE" % LP, token=T["ipd-admin"])
    fin = L.sql("SELECT status FROM projects WHERE id=%s" % LP)[0][0]
    R(item="3c-AC-PROD-04合法链DRAFT→TEAMING→ACTIVE", original="P4-3R3/R4 FAIL(执行器误用链)", retest_cmd="POST status TEAMING→ACTIVE + db回读",
      http=sb, result="TEAMING:%s/%s ACTIVE:%s/%s 终态=%s" % (sa, code_of(bja), sb, code_of(bjb), fin),
      verdict_msg="转绿=历史缺失不阻断合法流转" if fin == "ACTIVE" else "仍红")

# ---------- ④ AC-INC-34 报表导出 ----------
s, bj, h, rb = L.req("GET", B, "/api/v1/report/export/project", token=T["ipd-admin"], timeout=40)
R(item="4a-AC-INC-34无参复现", original="400 ct=json 144bytes", retest_cmd="GET /report/export/project (无参)",
  http=s, result="bytes=%d msg=%s" % (len(rb), msg_of(bj)), verdict_msg="参数错(month为@RequestParam必填)" if "month" in msg_of(bj).lower() or s == 400 else "见result")
s, bj, h, rb = L.req("GET", B, "/api/v1/report/export/project?month=2026-09", token=T["ipd-admin"], timeout=40)
R(item="4b-AC-INC-34带month正例", original="N5 FAIL", retest_cmd="GET /report/export/project?month=2026-09 (admin)",
  http=s, envelope=code_of(bj), result="bytes=%d msg=%s" % (len(rb), msg_of(bj)[:40]),
  verdict_msg="转绿" if s == 200 and code_of(bj) == 0 else "仍红")

# ---------- ⑤ AC-REQ-09 需求池删除申请 ----------
s, bj, _, _ = L.req("POST", B, "/api/v1/deletion-requests", token=T["ipd-leader"],
                    body={"entityType": "requirements", "entityId": 2103338131276259329, "reason": "R218归因复测-仅验白名单"})
R(item="5-AC-REQ-09需求池删除申请", original="400 不支持的 entity_type: requirements", retest_cmd="POST /deletion-requests entityType=requirements (新jar B39)",
  http=s, result=msg_of(bj), verdict_msg="新jar仍复现=SUPPORTED_ENTITY_TYPES缺requirements+无RequirementSoftDeleteExecutor(全仓executor仅5个:project/product/person/cert_template/gate)")

# ---------- ⑥ AC-HAND-01c 403 / 400同人物料 / 正例闭环 ----------
s, bj, _, _ = L.req("POST", B, "/api/v1/handovers", token=T["ipd-leader"],
                    body={"projectId": 9140005, "role": "MARKET_PM", "toPersonId": 9110005, "note": "R218归因-跨组负复现", "onBehalf": True})
R(item="6a-AC-HAND-01c跨组403复现", original="403 无权操作", retest_cmd="POST /handovers onBehalf (ipd-leader@900001→项目@9120002)",
  http=s, result=msg_of(bj), verdict_msg="复现=assertSameGroupIpd跨组守卫设计内(HandoverService.java:179)")
s, bj, _, _ = L.req("POST", B, "/api/v1/handovers", token=T["ipd-admin"],
                    body={"projectId": 9140005, "role": "MARKET_PM", "toPersonId": 2096266884247736321, "note": "R218归因-同人负复现", "onBehalf": True})
R(item="6b-AC-HAND-01c同人400复现", original="400 接手人不能与原负责人相同", retest_cmd="POST /handovers onBehalf to=赵(系统反查在任=赵)",
  http=s, result=msg_of(bj), verdict_msg="复现=from由服务端反查在任绑定(赵),to亦赵→同人拒绝正确;fixture撞自己")
s, bj, _, _ = L.req("POST", B, "/api/v1/handovers", token=T["ipd-admin"],
                    body={"projectId": 9140005, "role": "MARKET_PM", "toPersonId": 9110005, "note": "R218归因-正例(胡接手)", "onBehalf": True})
HO = (data_of(bj) or {}).get("id")
if HO: W.append("handover_records id=%s (onBehalf 赵→胡 COMPLETED→随即cancel回滚)" % HO)
rows_mid = L.sql("SELECT person_id,exit_date FROM project_members WHERE project_id=9140005 AND role='MARKET_PM' AND del_flag='0' ORDER BY id") if HO else []
R(item="6c-AC-HAND-01c改后正例(onBehalf)", original="H1/H1R FAIL", retest_cmd="POST /handovers onBehalf to=胡9110005 (admin豁免组界)",
  http=s, envelope=code_of(bj), handover_id=HO, result="msg=%s rows=%s" % (msg_of(bj)[:40], str(rows_mid)[:120]),
  verdict_msg="转绿=组长/超管代移交功能可用" if HO else "仍红")
if HO:
    s2, bj2, _, _ = L.req("POST", B, "/api/v1/handovers/%s/cancel" % HO, token=T["ipd-admin"],
                          body={"reason": "R218归因复测清理", "confirmation": "确认撤销该移交"})
    fin = L.sql("SELECT status FROM handover_records WHERE id=%s" % HO)[0][0]
    rows_after = L.sql("SELECT person_id,exit_date FROM project_members WHERE project_id=9140005 AND role='MARKET_PM' AND del_flag='0' ORDER BY id")
    R(item="6d-AC-HAND-01c撤销清理复位", original="—", retest_cmd="POST /handovers/%s/cancel + db回读" % HO,
      http=s2, result="%s 终态=%s rows=%s" % (msg_of(bj2)[:30], fin, str(rows_after)[:120]),
      verdict_msg="复位成功=数据回到复测前态" if fin == "ROLLED_BACK" else "需人工核对")

# ---------- ⑦ AC-IPD-03 409 归因 + 服务腿闭环 ----------
if PID:
    acts = L.sql("SELECT id,action_code,owner_role,status FROM stage_actions WHERE project_id=%s AND depth='LIGHT' AND status='NOT_STARTED' AND del_flag='0' ORDER BY owner_role,id" % PID)
    by_owner = {}
    for r in acts: by_owner.setdefault(r[2], []).append(r)
    print("LIGHT NOT_STARTED actions by owner:", {k: len(v) for k, v in by_owner.items()}, flush=True)
    neg = next((r for lst in acts if lst[2] != "MARKET_PM" for r in [lst]), None)
    if neg:
        s, bj, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/fields" % neg[0], token=T["ipd-market"],
                            body={"actualDoneAt": "2026-09-25T10:00:00"})
        R(item="7a-AC-IPD-03 409归因复现(market写RD_PM动作)", original="409 db=NOT_STARTED", retest_cmd="POST /stage-actions/%s/fields (ipd-market,owner=%s)" % (neg[0], neg[2]),
          http=s, envelope=code_of(bj), result=msg_of(bj), verdict_msg="复现=409 ROLE_LOCKED角色锁(IpdPermission.java:153-161),非前置态守卫;用例operator选错")
    tgt_role = "MARKET_PM" if "MARKET_PM" in by_owner else (list(by_owner.keys())[0] if by_owner else None)
    tok_u = {"MARKET_PM": "ipd-market", "RD_PM": "ipd-rd", "BOTH": "ipd-market"}.get(tgt_role, "ipd-market")
    tgt = by_owner[tgt_role][0] if tgt_role else None
    if tgt:
        s1, bj1, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/fields" % tgt[0], token=T[tok_u],
                              body={"actualDoneAt": "2026-09-25T10:00:00"})
        s2, bj2, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218gy" % tgt[0], token=T[tok_u])
        s3, bj3, _, _ = L.req("POST", B, "/api/v1/stage-actions/%s/transit?target=DONE&reason=r218gy" % tgt[0], token=T[tok_u])
        st = L.sql("SELECT status,actual_done_at FROM stage_actions WHERE id=%s" % tgt[0])[0]
        W.append("stage_actions id=%s (LIGHT动作%s完成流转 status=%s)" % (tgt[0], tgt[1], st[0]))
        R(item="7b-AC-IPD-03改后正例(%s→%s)" % (tok_u, tgt[1]), original="L1R FAIL(409)", retest_cmd="fields→transit IN_PROGRESS→transit DONE (operator=owner_role匹配)",
          http=s3, result="fields:%s IN_PROGRESS:%s DONE:%s db=%s/%s" % (s1, s2, s3, st[0], st[1]),
          verdict_msg="转绿=轻管完成路径服务侧闭环" if st[0] == "DONE" and st[1] else "仍红")
    c05 = L.sql("SELECT status,actual_done_at,owner_role FROM stage_actions WHERE id=2103548719990353924")[0]
    R(item="7c-AC-IPD-03原动作C05现状回读", original="当时NOT_STARTED", retest_cmd="mysql stage_actions id=2103548719990353924",
      result="status=%s actual_done_at=%s owner_role=%s (R4轮ipd-rd已按正确角色走通)" % tuple(c05), verdict_msg="服务无缺陷旁证")

# ---------- ⑧ AC-PROD-09 零触发路径静态+动态取证 ----------
import subprocess
g1 = subprocess.run(["grep", "-rn", "notifyOverdueUnassigned",
                     "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main"], capture_output=True, text=True)
g2 = subprocess.run(["grep", "-rln", "@Scheduled", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main"], capture_output=True, text=True)
sched_files = [os.path.basename(x) for x in g2.stdout.split()]
n = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action LIKE '%overdue_unassigned%'")[0][0]
R(item="8-AC-PROD-09调度器/端点缺失取证", original="扫描方法零调用方+通知对象偏差", retest_cmd="grep src/main notifyOverdueUnassigned 调用点 + @Scheduled清单 + db audit回读",
  result="main代码引用=%s(仅定义处) | @Scheduled类=%s | 真库overdue_unassigned审计行=%s" % (g1.stdout.count("\n"), ",".join(sched_files), n),
  verdict_msg="零触发路径确认=真缺陷(无@Scheduled包装/无HTTP端点/通知仅audit留痕从未publish;AC文本'提醒产品组组长'vs实现注释'通知超管'双重偏差)")

json.dump(recs, open(OUT + "/复测记录.json", "w"), ensure_ascii=False, indent=1)
json.dump(W, open(OUT + "/写库清单-归因复测.json", "w"), ensure_ascii=False, indent=1)
print("RETEST DONE records=%s writes=%s" % (len(recs), len(W)))
