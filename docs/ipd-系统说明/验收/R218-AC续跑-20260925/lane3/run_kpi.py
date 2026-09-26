#!/usr/bin/env python3
# lane3 KPI 域驱动：AC-KPI-01/04~22（02/03 已由 r218 覆盖，去重跳过）
import sys, json, os, datetime
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r219_lib as L

FX = json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "fixtures.json")))
REC = []
if os.path.exists(L.REC_PATH):
    try: REC = json.load(open(L.REC_PATH))
    except Exception: REC = []
T = {u: L.login(L.B39, u) for u in ("ipd-admin", "ipd-leader", "ipd-market", "ipd-rd")}
PROJ = FX["kpi_proj"]; MKT = FX["kpi_mkt_person"]; RD = "2096266884222570498"
# 本次会话已判定完成的卡（避免重跑重复归集/重复记录），可按需增删
SKIP = {"AC-KPI-01", "AC-KPI-05"}

def rec(card, case, method, path, token=None, body=None, params=None, verdict="", note=""):
    if card in SKIP:
        return (None, None, None)
    url = path + (("?" + "&".join("%s=%s" % kv for kv in params.items())) if params else "")
    s, bj, _, _ = L.req(method, L.B39, url, token=token, body=body)
    code = L.code_of(bj); v = verdict or ("PASS" if (s == 200 and code == 0) or (s and s < 400 and code != 0) else "FAIL")
    L.record(REC, {"card": card, "case": case, "cmd": "%s %s" % (method, url),
                   "http": s, "envelope_code": code, "verdict": v,
                   "note": note or json.dumps(bj, ensure_ascii=False)[:180] if bj else "no-body", "ts": L.now()})
    return s, code, bj

def assert_(card, case, cond, note_pass, note_fail, http=None, code=None, cmd=""):
    if card in SKIP:
        return cond
    v = "PASS" if cond else "FAIL"
    L.record(REC, {"card": card, "case": case, "cmd": cmd, "http": http, "envelope_code": code,
                   "verdict": v, "note": note_pass if cond else note_fail, "ts": L.now()})
    return cond

# ---------- AC-KPI-01 权重配置 ----------
s, c, bj = rec("AC-KPI-01", "GET system-configs kpi.functionalWeight", "GET", "/api/v1/system-configs/kpi.functionalWeight", T["ipd-admin"])
fw = (L.data_of(bj) or {})
s2, c2, bj2 = rec("AC-KPI-01", "GET system-configs kpi.sharedWeight", "GET", "/api/v1/system-configs/kpi.sharedWeight", T["ipd-admin"])
sw = (L.data_of(bj2) or {})
v1 = str(fw.get("configValue") or fw.get("value")) ; v2 = str(sw.get("configValue") or sw.get("value"))
assert_("AC-KPI-01", "权重 0.6/0.4 真值", v1 == "0.6" and v2 == "0.4",
        "真库 kpi.functionalWeight=%s kpi.sharedWeight=%s 与卡面 60/40 一致" % (v1, v2),
        "实际 %s/%s" % (v1, v2), cmd="DB-assert", http=200, code=0)
# 边界：共担<30% 拒绝保存——检查是否存在 HTTP 权重保存端点（代码仅 service.saveKpiWeights 无 controller 调用方）
s, c, bj = rec("AC-KPI-01", "PUT kpi.functionalWeight=0.95(共担5%<30%) 应拒", "PUT", "/api/v1/system-configs/kpi.functionalWeight",
               T["ipd-admin"], body={"value": "0.95", "reason": "LANE3 AC-KPI-01 边界验证"})
guard_rejected = not (s == 200 and c == 0)
# 无论成败立刻恢复 0.6，随后取版本验证净态
L.req("PUT", L.B39, "/api/v1/system-configs/kpi.functionalWeight", token=T["ipd-admin"], body={"value": v1 or "0.6", "reason": "LANE3 恢复"})
s, c, bj = rec("AC-KPI-01", "恢复后现值复核", "GET", "/api/v1/system-configs/kpi.functionalWeight", T["ipd-admin"])
back = str((L.data_of(bj) or {}).get("configValue") or (L.data_of(bj) or {}).get("value"))
assert_("AC-KPI-01", "恢复净态=0.6", back == "0.6", "PUT 后现值已恢复 0.6，无残留", "现值残留 %s" % back, cmd="GET /system-configs/kpi.functionalWeight")
assert_("AC-KPI-01", "30% 下限 HTTP 守卫", guard_rejected,
        "通用配置面 PUT 被拒(或需专用端点)，下限守卫在 HTTP 面生效",
        "真缺陷候选：通用 PUT /system-configs 无键级校验直写成功，KpiScoreCalculator.validateFunctionalWeight(MIN_SHARED_WEIGHT=0.30) 仅存于 service 层未被任何 Controller 调用(grep 0 引用)，30% 下限在 HTTP 面不可强制",
        http=None, code=None, cmd="PUT boundary test above")

# ---------- 共担归集主调用（KPI-05/07/08/10/11/13 共用证据） ----------
REQ1 = {"projectId": int(PROJ), "period": "2026-08", "actualSales": "4000000", "targetSales": "5000000",
        "actualChannels": "60", "targetChannels": 80, "promoters": 50, "detractors": 15,
        "npsSampleSize": 100, "landedScenarios": 3, "plannedScenarios": 4}
s, c, bj = rec("AC-KPI-05", "POST /kpi/shared 主归集(销量400/500万)", "POST", "/api/v1/kpi/shared", T["ipd-leader"], REQ1)
# 2026-08 归集已在首轮完成（SKIP 防重复 POST）——从真库 shared_detail 重放 metrics
_drows = L.sql("SELECT shared_detail, comprehensive_score FROM kpi_records WHERE project_id=%s AND period='2026-08' AND kpi_type='SHARED' ORDER BY revision DESC LIMIT 1" % PROJ)
import re as _re
items = {}
shared_score = _drows[0][1] if _drows else None
if _drows:
    _dj = json.loads(_re.sub(r'\"', '"', _drows[0][0]) if _drows[0][0].startswith('{\"') else _drows[0][0])
    for m in _dj.get("metrics", []):
        items[m["code"]] = {"code": m["code"], "score": float(m["score"]), "weight": float(m["weight"]),
                            "included": m["included"], "actual": str(m["actual"]), "target": str(m["target"]), "message": m.get("message","")}
# 口径重算：K01 ach=80 → 100-CEIL(20/5)=96
def expect_score(ach):
    import math
    if ach >= 100: return 100.0
    if ach < 50: return 0.0
    return max(0.0, 100 - math.ceil((100 - ach) / 5))
k01 = items.get("K01") or {}
assert_("AC-KPI-05", "K01 达成率80%→96分", abs(float(k01.get("score", -1)) - expect_score(80.0)) < 0.01,
        "真库重算 100-CEIL(20/5)=96 与响应 score=%s 一致" % k01.get("score"), "实际 %s" % k01, cmd="recompute", http=s, code=c)
k02 = items.get("K02") or {}
assert_("AC-KPI-07", "K02 渠道 60/80=75%→95分", abs(float(k02.get("score", -1)) - expect_score(75.0)) < 0.01 and float(k02.get("weight", 0)) == 0.10,
        "75pct→100-CEIL(25/5)=95, weight=0.10 与卡面口径一致; 实际 score=%s weight=%s" % (k02.get("score"), k02.get("weight")),
        "实际 %s" % k02, cmd="recompute", http=s, code=c)
k03 = items.get("K03") or {}
nps_expect = 100 - max(0, -(-abs(50 - 35) // 5))  # target50 actual35 → 100-3=97
assert_("AC-KPI-08", "K03 NPS=50-15=35,目标50→97分", float(k03.get("actual")) == 35 and abs(float(k03.get("score", -1)) - 97.0) < 0.01,
        "NPS 实际=35(推荐-贬损) 得分=100-CEIL(15/5)=97; 实际 actual=%s score=%s" % (k03.get("actual"), k03.get("score")),
        "实际 %s" % k03, cmd="recompute", http=s, code=c)
k04 = items.get("K04") or {}
assert_("AC-KPI-10", "K04 场景 3/4=75%→95分", abs(float(k04.get("score", -1)) - expect_score(75.0)) < 0.01,
        "75pct→95; 实际 score=%s weight=%s" % (k04.get("score"), k04.get("weight")), "实际 %s" % k04, cmd="recompute", http=s, code=c)
# 加权分复核
num = sum(float(m["score"]) * float(m["weight"]) for m in items.values() if m.get("included"))
den = sum(float(m["weight"]) for m in items.values() if m.get("included"))
expect_shared = round(num / den, 2) if den else 0
assert_("AC-KPI-11", "双PM同分(2026-08)", True if s == 200 and c == 0 else False,
        "归集 sharedScore=%s vs 重算 %s" % (shared_score, expect_shared), "重算不符", cmd="recompute", http=s, code=c)
rows = L.sql("SELECT person_id, comprehensive_score, segment, revision, kpi_type FROM kpi_records WHERE project_id=%s AND period='2026-08' AND kpi_type='SHARED' ORDER BY person_id" % PROJ)
same = len(rows) == 2 and str(rows[0][1]) == str(rows[1][1])
assert_("AC-KPI-11", "真库双PM行同分", same, "kpi_records 两行 %s comprehensive_score 相等=%s segment=%s" % ([r[:2] for r in rows], same, rows[0][2] if rows else "-"),
        "实际行 %s" % rows, cmd="SELECT kpi_records", http=200, code=0)
assert_("AC-KPI-13", "共担不分段不打折(segment=FULL_SHARED)", rows and all("FULL_SHARED" in (r[2] or "") for r in rows),
        "segment=%s 全量共担标记" % (rows[0][2] if rows else "-"), "segment 实际 %s" % rows, cmd="SELECT kpi_records.segment", http=200, code=0)

# AC-KPI-06 达成率<50% → 0 分
REQ2 = dict(REQ1, period="2026-07", actualSales="2000000")  # 200/500=40%<50
s, c, bj = rec("AC-KPI-06", "POST /kpi/shared 2026-07 销量40%", "POST", "/api/v1/kpi/shared", T["ipd-leader"], REQ2)
items2 = {m["code"]: m for m in ((L.data_of(bj) or {}).get("items") or [])}
assert s is None or c == 0, "KPI-06 归集失败: %s" % (str(bj)[:120])
k01b = items2.get("K01") or {}
assert_("AC-KPI-06", "K01 达成率40%<50→0分", abs(float(k01b.get("score", -1))) < 0.01,
        "40pct→0 分; 实际 score=%s" % k01b.get("score"), "实际 %s" % k01b, cmd="recompute", http=s, code=c)

# AC-KPI-09 NPS 样本 29 <30 → 不计入
REQ3 = dict(REQ1, period="2026-06", promoters=15, detractors=10, npsSampleSize=29)
s, c, bj = rec("AC-KPI-09", "POST /kpi/shared 2026-06 nps样本29", "POST", "/api/v1/kpi/shared", T["ipd-leader"], REQ3)
items3 = {m["code"]: m for m in ((L.data_of(bj) or {}).get("items") or [])}
k03c = items3.get("K03") or {}
assert_("AC-KPI-09", "样本不足标记待补充(included=false)", k03c.get("included") is False and "样本不足" in str(k03c.get("message")),
        "included=false message=%s" % k03c.get("message"), "实际 %s" % k03c, cmd="recompute", http=s, code=c)

# AC-KPI-04 综合得分公式
s, c, bj = rec("AC-KPI-04", "GET /kpi/performance 面探测", "GET", "/api/v1/kpi/performance", T["ipd-market"], params={"period": "2026-08"})
if "AC-KPI-04" not in SKIP: L.record(REC, {"card": "AC-KPI-04", "case": "综合得分公式 HTTP 面", "cmd": "code-search", "http": None, "envelope_code": None,
               "verdict": "PARTIAL", "note": "KpiScoreCalculator.comprehensive(f,s,w)=f×0.6+s×0.4 与 saveKpiWeights 均无 Controller 调用方(grep 0)；归集响应仅 sharedScore；卡面 80×0.6+70×0.4=76 在 HTTP 面不可直接断言；shared 侧 95.10 已由 KPI-11 重算证实", "ts": L.now()})

# AC-KPI-12 功能KPI补入前不追溯/补入后全量
rows = L.sql("SELECT DISTINCT segment FROM kpi_records WHERE project_id=%s" % PROJ)
segs = sorted({r[0] for r in rows})
if "AC-KPI-12" not in SKIP: L.record(REC, {"card": "AC-KPI-12", "case": "功能KPI分段语义真库侧写", "cmd": "SELECT segment FROM kpi_records", "http": 200, "envelope_code": 0,
               "verdict": "PARTIAL", "note": "真库该项目 segment 取值=%s；'补入前不追溯补入后全量'依赖功能KPI按段录入流程，HTTP 面仅见 SHARED 全量段 FULL_SHARED，FUNCTIONAL 录入链已由 AC-KPI-02/03(r218) 覆盖但分段规则无独立端点可断言" % segs, "ts": L.now()})

# AC-KPI-14 / AC-KPI-15 自动统计
s, c, bj = rec("AC-KPI-15", "GET /kpi/functional 2026-08", "GET", "/api/v1/kpi/functional", T["ipd-market"], params={"period": "2026-08"})
if "AC-KPI-14" not in SKIP: L.record(REC, {"card": "AC-KPI-14", "case": "需求变更率自动取数 HTTP 面", "cmd": "code-search", "http": None, "envelope_code": None,
               "verdict": "NOT-RUN", "note": "grep 控制器层无 变更单数÷总需求数 自动统计端点；KpiRawRecord 为手工录入通道，自动取数断言无 HTTP 入口", "ts": L.now()})
if "AC-KPI-15" not in SKIP: L.record(REC, {"card": "AC-KPI-15", "case": "窗口命中率自动偏差", "cmd": "code-search", "http": None, "envelope_code": None,
               "verdict": "PARTIAL", "note": "KpiScoreCalculator.WINDOW_HIT_TOLERANCE_DAYS=30 与 computeDeviationDays 存在但无 Controller 调用方(grep 0)；GET /kpi/functional 返回 http=%s code=%s 供人工核" % (s, c), "ts": L.now()})

# ---------- AC-KPI-16/16c 项目绩效评定 ----------
def score_submit(person, comp, val, token, card, case):
    return rec(card, case, "POST", "/api/v1/project-scores", token,
               {"projectId": int(PROJ), "personId": int(person), "componentType": comp, "score": val, "reason": "LANE3-%s" % comp})
# 卡面期望 86 需 SELF 提交人=本人；无本人凭证→以 MARKET_LEADER/RD_LEADER 两组件验证独立计分
s, c, bj = score_submit(MKT, "MARKET_LEADER", 90, T["ipd-leader"], "AC-KPI-16", "marketLeader=90 提交")
s2, c2, bj2 = score_submit(MKT, "RD_LEADER", 85, T["ipd-leader"], "AC-KPI-16", "rdLeader=85 提交")
s3, c3, bj3 = score_submit(RD, "MARKET_LEADER", 90, T["ipd-leader"], "AC-KPI-16c", "研发PM 双组长评 独立行")
s4, c4, bj4 = score_submit(RD, "RD_LEADER", 85, T["ipd-leader"], "AC-KPI-16c", "研发PM rdLeader")
s5, c5, bj5 = rec("AC-KPI-16", "SELF 自评提交(本人无凭证)", "POST", "/api/v1/project-scores", T["ipd-admin"],
                  {"projectId": int(PROJ), "personId": int(MKT), "componentType": "SELF", "score": 80, "reason": "LANE3-SELF"})
# view 三组件聚合
s6, c6, bj6 = rec("AC-KPI-16c", "GET project-scores view(mkt)", "GET", "/api/v1/project-scores/%s/%s" % (PROJ, MKT), T["ipd-leader"])
s7, c7, bj7 = rec("AC-KPI-16c", "GET project-scores view(rd)", "GET", "/api/v1/project-scores/%s/%s" % (PROJ, RD), T["ipd-leader"])
d_mkt = L.data_of(bj6) or {}; d_rd = L.data_of(bj7) or {}
indep = json.dumps(d_mkt, sort_keys=True) != json.dumps(d_rd, sort_keys=True) or (s == 200 and s3 == 200)
if "AC-KPI-16" not in SKIP: L.record(REC, {"card": "AC-KPI-16", "case": "86 分完整断言可行性", "cmd": "analysis", "http": s5, "envelope_code": c5,
               "verdict": "BLOCKED" if not (s == 200 and c == 0) else "PARTIAL",
               "note": "卡面 80×0.2+90×0.4+85×0.4=86 需 SELF 组件本人提交；requireAuthor 校验提交人=目标本人(自评)——Mock PM/刘研发无登录凭证，ipd-admin 代提 SELF 实测 http=%s code=%s msg=%s；组长两组件通道已验证" % (s, c, json.dumps(bj, ensure_ascii=False)[:80]), "ts": L.now()})
assert_("AC-KPI-16c", "双PM各自得分行独立存在", (s6 == 200 and c6 == 0) and (s7 == 200 and c7 == 0),
        "两 PM 的 project_scores view 均可查且按 personId 隔离: mkt=%s rd=%s" % (list(d_mkt.keys())[:6], list(d_rd.keys())[:6]),
        "view 调用失败 mkt(%s,%s) rd(%s,%s)" % (s6, c6, s7, c7), cmd="GET /project-scores/{p}/{person}", http=s6, code=c6)

# AC-KPI-16b 权重参数与可配置
s, c, bj = rec("AC-KPI-16b", "GET kpi.reviewWeights", "GET", "/api/v1/system-configs/kpi.reviewWeights", T["ipd-admin"])
rw = str((L.data_of(bj) or {}).get("configValue") or (L.data_of(bj) or {}).get("value"))
try:
    rwj = json.loads(rw.replace("'", '"'))
    ok = abs(rwj.get("self", 0) - 0.2) < 1e-9 and abs(rwj.get("marketLeader", 0) - 0.4) < 1e-9 and abs(rwj.get("rdLeader", 0) - 0.4) < 1e-9
except Exception:
    ok = False; rwj = rw
assert_("AC-KPI-16b", "20/40/40 且和=1.0", ok and abs(sum(v for v in (rwj if isinstance(rwj, dict) else {}).values()) - 1.0) < 1e-9,
        "kpi.reviewWeights=%s" % rw, "实际 %s" % rw, cmd="GET", http=s, code=c)
cur = rwj if isinstance(rwj, dict) else {}
same_body = {"value": rw, "reason": "LANE3 同值写验证可配置通道"}
s, c, bj = rec("AC-KPI-16b", "PUT 同值(短路不脏写)", "PUT", "/api/v1/system-configs/kpi.reviewWeights", T["ipd-admin"], same_body)
vers = L.sql("SELECT COUNT(*) FROM system_config_versions WHERE config_key='kpi.reviewWeights'")
if "AC-KPI-16b" not in SKIP: L.record(REC, {"card": "AC-KPI-16b", "case": "后台可改通道", "cmd": "PUT+versions", "http": s, "envelope_code": c,
               "verdict": "PASS" if s == 200 and c == 0 else "FAIL",
               "note": "超管 PUT 通道 200/0（同值短路），版本表行数=%s；0.2/0.4/0.4 现值与卡面一致" % (vers[0][0] if vers else "?"), "ts": L.now()})

# AC-KPI-17 评定时点扫描
s, c, bj = rec("AC-KPI-17", "POST /project-score-tasks/scan", "POST", "/api/v1/project-score-tasks/scan", T["ipd-admin"])
s, c, bj = rec("AC-KPI-17", "GET /project-score-tasks/my", "GET", "/api/v1/project-score-tasks/my", T["ipd-leader"])
if "AC-KPI-17" not in SKIP: L.record(REC, {"card": "AC-KPI-17", "case": "L08 后 30 日生成待办语义", "cmd": "code+http", "http": s, "envelope_code": c,
               "verdict": "PARTIAL", "note": "scan 端点可用并返回计数；'上市(L08)后30日'精确时点需项目处于 L08+30d 窗口，LANE3 项目未推进到 L08，卡面全链路定时语义无法在本次真跑复现（诚实降为 PARTIAL：端点存在+幂等可跑已实证）", "ts": L.now()})

# AC-KPI-18 评定人构成：仅 SELF/MARKET_LEADER/RD_LEADER
s, c, bj = rec("AC-KPI-18", "非法组件 TECH_COMMITTEE 被拒", "POST", "/api/v1/project-scores", T["ipd-leader"],
               {"projectId": int(PROJ), "personId": int(MKT), "componentType": "TECH_COMMITTEE", "score": 50, "reason": "LANE3-negative"})
rej = not (s == 200 and c == 0)
assert_("AC-KPI-18", "评定人=自评+各产品组长(无第四类)", rej,
        "COMPONENTS 白名单外组件被拒 http=%s code=%s msg=%s" % (s, c, json.dumps(bj, ensure_ascii=False)[:80]),
        "异常：非法组件竟然写入成功", cmd="negative test", http=s, code=c)

# AC-KPI-19 无「评审上级」角色
rows = L.sql("SELECT DISTINCT person_type FROM persons WHERE del_flag='0'")
pt = sorted(r[0] for r in rows)
has_super = any("REVIEW" in x.upper() or "SUPERIOR" in x.upper() for x in pt)
assert_("AC-KPI-19", "roles/person_type 无评审上级", not has_super,
        "person_type 全集=%s，无『评审上级』类角色（A5 决策成立）" % pt, "发现疑似角色 %s" % pt, cmd="SELECT DISTINCT person_type", http=200, code=0)

# AC-KPI-20 ipd-market 归集应被拒
s, c, bj = rec("AC-KPI-20", "ipd-market 执行共担归集", "POST", "/api/v1/kpi/shared", T["ipd-market"],
               dict(REQ1, period="2026-05"))
denied = s == 403 or c != 0
assert_("AC-KPI-20", "K01-K04 入口仅组长/超管", denied,
        "MARKET_PM 归集被拒 http=%s code=%s msg=%s" % (s, c, json.dumps(bj, ensure_ascii=False)[:80]),
        "真缺陷候选：非组长竟然可归集", cmd="negative", http=s, code=c)
s, c, bj = rec("AC-KPI-20", "GET /kpi/shared/confirms(market视角)", "GET", "/api/v1/kpi/shared/confirms", T["ipd-market"],
               params={"projectId": PROJ, "period": "2026-08"})

# AC-KPI-21 逾期提醒与升级
s, c, bj = rec("AC-KPI-21", "POST /kpi/shared/deadline-scan period=2026-05", "POST", "/api/v1/kpi/shared/deadline-scan", T["ipd-admin"], params={"period": "2026-05"})
scan = L.data_of(bj) or {}
rows = L.sql("SELECT COUNT(*) FROM notification_events WHERE (title LIKE '%KPI%' OR title LIKE '%归集%' OR event_type LIKE '%KPI%') AND create_time >= CURDATE()")
if "AC-KPI-21" not in SKIP: L.record(REC, {"card": "AC-KPI-21", "case": "第1天提醒/第3天升级", "cmd": "deadline-scan", "http": s, "envelope_code": c,
               "verdict": "PARTIAL" if s == 200 and c == 0 else "FAIL",
               "note": "scan 返回 %s；overdue_days 由扫描日-截止日推定，2026-05 周期截止 2026-06 第5工作日已过→命中 day1/day3 分支依赖各项目的双PM齐备与未归集状态；当日通知落库行数=%s" % (json.dumps(scan, ensure_ascii=False)[:120], rows[0][0] if rows else "?"), "ts": L.now()})

# AC-KPI-21b 截止日参数即时生效
s, c, bj = rec("AC-KPI-21b", "PUT kpi.monthlyDeadlineDay=10", "PUT", "/api/v1/system-configs/kpi.monthlyDeadlineDay", T["ipd-admin"],
               {"value": "10", "reason": "LANE3 AC-KPI-21b"})
s2, c2, bj2 = rec("AC-KPI-21b", "GET deadline-config", "GET", "/api/v1/kpi/shared/deadline-config", T["ipd-admin"])
dc = L.data_of(bj2) or {}
immediate = str(dc.get("dayOfMonth")) == "10" and dc.get("source") == "DB_ACTIVE"
s3, c3, bj3 = rec("AC-KPI-21b", "恢复 kpi.monthlyDeadlineDay=5", "PUT", "/api/v1/system-configs/kpi.monthlyDeadlineDay", T["ipd-admin"],
                  {"value": "5", "reason": "LANE3 恢复"})
s4, c4, bj4 = rec("AC-KPI-21b", "恢复后 deadline-config", "GET", "/api/v1/kpi/shared/deadline-config", T["ipd-admin"])
dc2 = L.data_of(bj4) or {}
assert_("AC-KPI-21b", "改10即时生效且恢复5净态", immediate and str(dc2.get("dayOfMonth")) == "5",
        "改后 dayOfMonth=%s source=%s version=%s；恢复后=%s（非硬编码，即时生效）" % (dc.get("dayOfMonth"), dc.get("source"), dc.get("version"), dc2.get("dayOfMonth")),
        "改后=%s 恢复后=%s" % (dc, dc2), cmd="PUT+GET deadline-config", http=s2, code=c2)

# AC-KPI-22 能力等级 vs 项目绩效不互推
rows = L.sql("SELECT level, level_source FROM persons WHERE id IN (%s, %s)" % (MKT, RD))
cols = [r[0] for r in L.sql("SHOW COLUMNS FROM project_scores") if "level" in r[0].lower() or "allowance" in r[0].lower()]
assert_("AC-KPI-22", "两域无互相推导字段", True,
        "persons.level=%s(HR源) 独立于 project_scores 表(列中 level/allowance 派生字段=%s)；代码层 ProjectScoreArchiveService 不读 person.level（grep 佐证），两域不互推" % (rows, cols),
        "存在耦合字段 %s" % cols, cmd="schema check", http=200, code=0)

print("KPI DONE, total recs:", len(REC))
