#!/usr/bin/env python3
# R5 fixture修正补丁：按 R218-FAIL归因-20260925 行1-9/12 原位修复 QA08 执行器用例
# 纪律：只改测试资产(r218_qa08*.py)；不碰主代码/yml/SQL/看板卡；首波执行清单记录不动
import sys
D = "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-QA08-SEC04-20260925/"
PAIRS = []
def P(f, old, new):
    PAIRS.append((f, old, new))
def apply():
    files = {}
    for f, old, new in PAIRS:
        t = files.get(f) or open(D + f, encoding="utf-8").read()
        n = t.count(old)
        assert n == 1, ("MATCH!=", f, old[:48], "count=", n)
        files[f] = t.replace(old, new)
    for f, t in files.items():
        open(D + f, "w", encoding="utf-8").write(t)
        print("patched", f, flush=True)
Q = "r218_qa08.py"
P(Q, "WRITTEN = []  # 写库登记清单", "WRITTEN = []  # 写库登记清单\n"
"# [FIX-R5 归因行1/2] 配套产品helper：SOFTWARE项目须1:1挂真实产品(source=PM_NEW)\n"
"def _fixprod(nm, tok):\n"
"    s0, b0, _, _ = L.req(\"POST\", L.B46, \"/api/v1/products\", token=tok, body={\"productName\": nm, \"source\": \"PM_NEW\"})\n"
"    d0 = data_of(b0) if isinstance(data_of(b0), dict) else {}\n"
"    return d0.get(\"id\")")
P(Q, '"targetSalesAmount": 100, "targetChannelCount": 3, "targetNps": 40, "targetSceneCount": 2}',
 '"targetSalesAmount": 100, "targetChannelCount": 3, "targetNps": 40, "targetSceneCount": 2,\n'
 '        "productId": (lambda x: int(x) if x else None)(_fixprod("R218-QA08-配套软件产品R5FIX", tok_admin))}  # [FIX-R5 归因行1/2] 原fixture缺productId致400')
P(Q, "AND status='NOT_STARTED' AND del_flag='0' LIMIT 1\" % PID)",
 "AND status='NOT_STARTED' AND del_flag='0' ORDER BY owner_role='MARKET_PM' DESC,id LIMIT 1\" % PID)  # [FIX-R5 归因行12] 优先取operator可写动作")
P(Q, "        s1, bj1, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/fields\" % LA, token=tok_market, body={\"actualDoneAt\": \"2026-09-25 10:00:00\", \"remark\": \"R218-QA08 轻管完成腿\"})\n"
"        s2, bj2, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218\" % LA, token=tok_market)\n"
"        s3, bj3, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=DONE&reason=r218-qa08\" % LA, token=tok_market)",
 "        LT = {\"MARKET_PM\": tok_market, \"RD_PM\": tok_rd}.get(light[0][2], tok_market)  # [FIX-R5 归因行12] operator与owner_role配对(ROLE_LOCKED)\n"
"        s1, bj1, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/fields\" % LA, token=LT, body={\"actualDoneAt\": \"2026-09-25T10:00:00\"})  # [FIX-R5] 仅白名单字段+ISO日期\n"
"        s2, bj2, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218\" % LA, token=LT)\n"
"        s3, bj3, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=DONE&reason=r218-qa08\" % LA, token=LT)")
P(Q, "    legacy = {\"name\": \"R218-QA08-存量导入项目\", \"templateType\": \"SOFTWARE\", \"level\": \"A\", \"mainGroupId\": 9120002,\n"
"              \"targetSalesAmount\": 200, \"legacyEffectiveAt\": \"2026-08-01\", \"declaredStage\": \"DEV\", \"missingHistoryAck\": True,\n"
"              \"alternativeEvidence\": {\"D01\": \"R218邮件纪要\"}}",
 "    # [FIX-R5 归因行3/4] 原fixture缺 targetMarkets/targetChannelCount 等必填→400；补四基准+挂真实产品+ISO生效日\n"
"    legacy = {\"name\": \"R218-QA08-存量导入项目\", \"templateType\": \"SOFTWARE\", \"level\": \"A\", \"mainGroupId\": 9120002,\n"
"              \"productId\": (lambda x: int(x) if x else None)(_fixprod(\"R218-QA08-存量配套产品R5FIX\", tok_admin)),\n"
"              \"targetMarkets\": json.dumps([\"CONSUMER\"], ensure_ascii=False),\n"
"              \"targetSalesAmount\": 200, \"targetChannelCount\": 5, \"targetNps\": 40, \"targetSceneCount\": 2,\n"
"              \"legacyEffectiveAt\": \"2026-08-01T00:00:00\", \"declaredStage\": \"DEV\", \"missingHistoryAck\": True,\n"
"              \"alternativeEvidence\": {\"D01\": \"R218邮件纪要\"}}")
P(Q, "        s, bj, _, _ = L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=ACTIVE\" % LP, token=tok_admin)\n"
"        L.record(recs, {\"card\":\"AC-PROD-04\",\"case\":\"P4-3历史缺失不阻断后续流转\",\"cmd\":\"POST /projects/%s/status?target=ACTIVE (admin)\" % LP,",
 "        # [FIX-R5 归因行5/6] 状态机DRAFT禁直跳ACTIVE且CONFIRMED不在枚举(ProjectService.java:54-59)→合法链TEAMING→ACTIVE\n"
"        L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=TEAMING\" % LP, token=tok_admin)\n"
"        s, bj, _, _ = L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=ACTIVE\" % LP, token=tok_admin)\n"
"        L.record(recs, {\"card\":\"AC-PROD-04\",\"case\":\"P4-3历史缺失不阻断后续流转(合法链TEAMING→ACTIVE)\",\"cmd\":\"POST /projects/%s/status TEAMING→ACTIVE (admin)\" % LP,")
P(Q, "# 900103 是 9140005 在任 MARKET_PM,代移交把职位交给赵(冻结待移交)？矩阵语义:组长代离职人员发起。反向:目标人=ACTIVE 应拒\n"
"s, bj, _, _ = L.req(\"POST\", L.B46, \"/api/v1/handovers\", token=T[\"ipd-leader\"], body={\"projectId\": 9140005, \"role\": \"MARKET_PM\", \"toPersonId\": int(ZAO), \"note\": \"R218-QA08 代移交验证\", \"onBehalf\": True})",
 "# [FIX-R5 归因行7/8] ①403=assertSameGroupIpd跨组设计守卫(HandoverService.java:173-179)→组长临时换组9120002同组发起后还原；\n"
"#             ②400同人=接手人不能=在任反查(赵)→接手人改胡9110005；复跑走 r218_qa08_fix10.py(自有项目隔离,不触9140005共享夹具)\n"
"L.sql(\"UPDATE persons SET group_id=9120002 WHERE id=900102\")\n"
"try:\n"
"    L._tok.pop((L.B46, \"ipd-leader\"), None)\n"
"    s, bj, _, _ = L.req(\"POST\", L.B46, \"/api/v1/handovers\", token=L.login(L.B46, \"ipd-leader\"), body={\"projectId\": 9140005, \"role\": \"MARKET_PM\", \"toPersonId\": 9110005, \"note\": \"R218-QA08 代移交验证(FIX同组+胡接手)\", \"onBehalf\": True})\n"
"finally:\n"
"    L.sql(\"UPDATE persons SET group_id=900001 WHERE id=900102\")")
P(Q, "\"case\":\"H1-组长代移交正例(onBehalf=true)\",\"cmd\":\"POST /api/v1/handovers (leader组9120002=项目组) toPerson=赵(冻结)\",",
 "\"case\":\"H1-组长代移交正例(onBehalf,FIX:同组+to=胡9110005)\",\"cmd\":\"POST /api/v1/handovers (leader临时组9120002同组) toPerson=胡9110005\",")
P(Q, "    (\"N5-项目报表导出(admin)\", \"/api/v1/report/export/project\", tok_admin, \"ipd-admin\"),",
 "    (\"N5-项目报表导出(admin)\", \"/api/v1/report/export/project?month=2026-09\", tok_admin, \"ipd-admin\"),  # [FIX-R5 归因行9] month为@RequestParam必填(ReportController.java:84-88)")
X = "r218_qa08_r3.py"
P(X, "T = {u: L.login(L.B46, u) for u in (\"ipd-admin\", \"ipd-leader\", \"ipd-market\")}",
 "T = {u: L.login(L.B46, u) for u in (\"ipd-admin\", \"ipd-leader\", \"ipd-market\", \"ipd-rd\")}  # [FIX-R5 归因行12] 增rd供operator配对")
P(X, "        light = L.sql(\"SELECT id FROM stage_actions WHERE project_id=%s AND depth='LIGHT' AND status='NOT_STARTED' AND del_flag='0' LIMIT 1\" % PID)",
 "        # [FIX-R5 归因行12] 取owner_role并与operator配对；fields仅白名单+ISO日期\n"
"        light = L.sql(\"SELECT id,owner_role FROM stage_actions WHERE project_id=%s AND depth='LIGHT' AND status='NOT_STARTED' AND del_flag='0' ORDER BY owner_role='MARKET_PM' DESC,id LIMIT 1\" % PID)")
P(X, "            s1, bj1, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/fields\" % LA, token=T[\"ipd-market\"], body={\"actualDoneAt\": \"2026-09-25 10:00:00\", \"remark\": \"R218轻管腿\"})\n"
"            L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218\" % LA, token=T[\"ipd-market\"])\n"
"            s3, bj3, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=DONE&reason=r218-qa08\" % LA, token=T[\"ipd-market\"])",
 "            lk = {\"MARKET_PM\": \"ipd-market\", \"RD_PM\": \"ipd-rd\"}.get(light[0][1], \"ipd-market\")\n"
"            s1, bj1, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/fields\" % LA, token=T[lk], body={\"actualDoneAt\": \"2026-09-25T10:00:00\"})\n"
"            L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=IN_PROGRESS&reason=r218\" % LA, token=T[lk])\n"
"            s3, bj3, _, _ = L.req(\"POST\", L.B46, \"/api/v1/stage-actions/%s/transit?target=DONE&reason=r218-qa08\" % LA, token=T[lk])")
P(X, "            legacy = {\"name\": \"R218-QA08-存量导入项目\", \"templateType\": \"SOFTWARE\", \"level\": \"A\", \"mainGroupId\": 9120002,\n"
"                      \"productId\": int(P2), \"targetSalesAmount\": 200, \"legacyEffectiveAt\": \"2026-08-01\",\n"
"                      \"declaredStage\": \"DEV\", \"missingHistoryAck\": True, \"alternativeEvidence\": {\"D01\": \"R218邮件纪要\"}}",
 "            # [FIX-R5 归因行3/4] 补 targetMarkets+targetChannelCount 等四基准必填 + ISO生效日\n"
"            legacy = {\"name\": \"R218-QA08-存量导入项目\", \"templateType\": \"SOFTWARE\", \"level\": \"A\", \"mainGroupId\": 9120002,\n"
"                      \"productId\": int(P2), \"targetMarkets\": json.dumps([\"CONSUMER\"], ensure_ascii=False),\n"
"                      \"targetSalesAmount\": 200, \"targetChannelCount\": 5, \"targetNps\": 40, \"targetSceneCount\": 2,\n"
"                      \"legacyEffectiveAt\": \"2026-08-01T00:00:00\",\n"
"                      \"declaredStage\": \"DEV\", \"missingHistoryAck\": True, \"alternativeEvidence\": {\"D01\": \"R218邮件纪要\"}}")
P(X, "                s, bj4, _, _ = L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=ACTIVE\" % LP, token=T[\"ipd-admin\"])\n"
"                L.record(recs, {\"card\":\"AC-PROD-04\",\"case\":\"P4-3R历史缺失不阻断流转\",\"cmd\":\"POST /projects/%s/status?target=ACTIVE\" % LP,",
 "                # [FIX-R5 归因行5/6] 直跳ACTIVE被状态机守卫拒(设计内)→合法链TEAMING→ACTIVE\n"
"                L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=TEAMING\" % LP, token=T[\"ipd-admin\"])\n"
"                s, bj4, _, _ = L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=ACTIVE\" % LP, token=T[\"ipd-admin\"])\n"
"                L.record(recs, {\"card\":\"AC-PROD-04\",\"case\":\"P4-3R历史缺失不阻断流转(合法链TEAMING→ACTIVE)\",\"cmd\":\"POST /projects/%s/status TEAMING→ACTIVE\" % LP,")
P(X, "    s, bj, _, _ = L.req(\"POST\", L.B46, \"/api/v1/handovers\", token=Tl,\n"
"        body={\"projectId\": 9140005, \"role\": \"MARKET_PM\", \"toPersonId\": 2096266884247736321, \"note\": \"R218-QA08 代移交正例\", \"onBehalf\": True})",
 "    # [FIX-R5 归因行8] to原=赵与在任反查同人→400；接手人改胡9110005\n"
"    s, bj, _, _ = L.req(\"POST\", L.B46, \"/api/v1/handovers\", token=Tl,\n"
"        body={\"projectId\": 9140005, \"role\": \"MARKET_PM\", \"toPersonId\": 9110005, \"note\": \"R218-QA08 代移交正例(FIX:胡接手)\", \"onBehalf\": True})")
P(X, "\"cmd\":\"POST /handovers (leader,同组9120002) to=赵市场(RESIGNED)\",\"http\": s,",
 "\"cmd\":\"POST /handovers (leader,同组9120002) to=胡9110005(FIX)\",\"http\": s,")
Y = "r218_qa08_r4.py"
P(Y, "\"targetSalesAmount\":200,\"mainGroupId\":9120002,\"legacyEffectiveAt\":\"2026-08-01T00:00:00\",",
 "\"targetSalesAmount\":200,\"targetChannelCount\":5,\"targetNps\":40,\"targetSceneCount\":2,  # [FIX-R5 归因行4] 渠道商数等四基准必填\n"
 "          \"mainGroupId\":9120002,\"legacyEffectiveAt\":\"2026-08-01T00:00:00\",")
P(Y, "    s, bj2, _, _ = L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=ACTIVE\" % LP, token=T[\"ipd-admin\"])\n"
"    L.record(recs, {\"card\":\"AC-PROD-04\",\"case\":\"P4-3R2历史缺失不阻断状态流转\",\"cmd\":\"POST /projects/%s/status?target=ACTIVE\" % LP,",
 "    # [FIX-R5 归因行5/6] 合法链TEAMING→ACTIVE(直跳为状态机守卫拒绝的设计内行为)\n"
"    L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=TEAMING\" % LP, token=T[\"ipd-admin\"])\n"
"    s, bj2, _, _ = L.req(\"POST\", L.B46, \"/api/v1/projects/%s/status?target=ACTIVE\" % LP, token=T[\"ipd-admin\"])\n"
"    L.record(recs, {\"card\":\"AC-PROD-04\",\"case\":\"P4-3R2历史缺失不阻断状态流转(合法链TEAMING→ACTIVE)\",\"cmd\":\"POST /projects/%s/status TEAMING→ACTIVE\" % LP,")
apply()
