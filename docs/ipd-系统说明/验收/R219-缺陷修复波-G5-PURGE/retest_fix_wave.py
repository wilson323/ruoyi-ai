#!/usr/bin/env python3
# R219 缺陷修复波（e697a401 G5字段错配 + b494f56e purge守卫塌缩）部署后真机复测
# 环境：16039 新 jar（PID 11914，内嵌 ruoyi-ipd sha=ec907c8a...），基线复用 lane1 r219_lib
import json, os, sys, datetime
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE + "/../R218-AC续跑-20260925/lane1")
import r219_lib as L

B = L.B39
recs = []

def rec(card, case, cmd, s=None, code=None, verdict=None, note=""):
    r = {"card": card, "case": case, "cmd": cmd, "http": s, "envelope_code": code,
         "verdict": verdict, "note": note, "ts": datetime.datetime.now().isoformat()}
    recs.append(r)
    print(verdict, "|", case, "|", note[:140])

def code_of(b): return b.get("code") if isinstance(b, dict) else None
def data_of(b): return b.get("data") if isinstance(b, dict) else None
def msg_of(b): return b.get("message") or b.get("msg") or "" if isinstance(b, dict) else str(b)

TA = L.login(B, "ipd-admin")
TM = L.login(B, "ipd-market")
TR = L.login(B, "ipd-rd")

SAVE_BODY = {"role": "MARKET_PM", "dimInitiation": 80, "dimInnovation": 90,
             "dimLaunch": 70, "dimMarketResult": 85, "dimLeadership": 95,
             "comment": "R219修复波复测"}

# ---------- 卡1 e697a401：G5 门控改判 current_stage ----------
# N1 归档守卫：9140001 (status=ARCHIVED, current_stage=LIFECYCLE) → 50007 且 msg 含"归档"
s, b, _, _ = L.req("POST", B, "/api/v1/contributions/9140001/save", token=TM, body=SAVE_BODY)
ok = s in (403, 409) and code_of(b) == 50007 and "归档" in msg_of(b)
rec("AC-INC-28", "N1 归档项目(LIFECYCLE+ARCHIVED)关闭·新码生效", "POST /contributions/9140001/save",
    s, code_of(b), "PASS" if ok else "FAIL", f"msg={msg_of(b)[:80]}")

# N2 字段错位修正证据：CONCEPT+DRAFT 项目 → msg 必须回显 currentStage=CONCEPT（旧码回显 status=DRAFT）
crows = L.sql("SELECT id FROM projects WHERE current_stage='CONCEPT' AND status='DRAFT' AND del_flag='0' ORDER BY id LIMIT 1")
CID = crows[0][0]
s, b, _, _ = L.req("POST", B, f"/api/v1/contributions/{CID}/save", token=TM, body=SAVE_BODY)
ok = code_of(b) == 50007 and "currentStage=CONCEPT" in msg_of(b)
rec("AC-INC-28", "N2 DRAFT项目拒绝且回显 currentStage(判错字段→判对字段铁证)", f"POST /contributions/{CID}/save",
    s, code_of(b), "PASS" if ok else "FAIL", f"msg={msg_of(b)[:80]}")

# P1 正向：9150001 (current_stage=LIFECYCLE, 非归档, ipd-market=在职MARKET_PM)
#   该种子贡献度历史已 CONFIRMED，saveSelf 会过 G5 门控后撞下游"已确认不可修改"（50002）。
#   判据：错误码 != 50007(CONTRIB_NOT_G5_STAGE) 即证明 G5 入口已开放（修复前恒 50007）。
s, b, _, _ = L.req("POST", B, "/api/v1/contributions/9150001/save", token=TM, body=SAVE_BODY)
d = data_of(b) or {}
gate_opened = code_of(b) != 50007
rec("AC-INC-28", "P1 LIFECYCLE项目G5入口开放(过门控→非50007)", "POST /contributions/9150001/save",
    s, code_of(b), "PASS" if gate_opened else "FAIL",
    f"过G5门控={gate_opened} 下游态={code_of(b)}:{msg_of(b)[:40]} status={d.get('status') if isinstance(d,dict) else '-'}")

# P2 联动：market-share（AC-INC-25）——同样判过 G5 门控（非 50007）
s, b, _, _ = L.req("POST", B, "/api/v1/contributions/9150001/market-share?marketShare=0.55", token=TM)
d = data_of(b) or {}
rec("AC-INC-25", "P2 比例联动入口可达(过G5门控非50007)", "POST /contributions/9150001/market-share",
    s, code_of(b), "PASS" if code_of(b) != 50007 else "FAIL",
    f"code={code_of(b)}:{msg_of(b)[:40]}")

# P3 回读：GET contribution 200 非空
s, b, _, _ = L.req("GET", B, "/api/v1/contributions/9150001", token=TM)
d = data_of(b) or {}
rec("AC-INC-28", "P3 GET 回读评定记录", "GET /contributions/9150001",
    s, code_of(b), "PASS" if s == 200 and code_of(b) == 0 and d else "FAIL",
    f"status={d.get('status') if isinstance(d,dict) else '?'}")

# 真库回读：contributions 行存在
crows = L.sql("SELECT status, market_share FROM contributions WHERE project_id=9150001 AND del_flag='0' ORDER BY id DESC LIMIT 1")
rec("AC-INC-28", "P4 真库回读 contributions", "SQL contributions project=9150001", None, None,
    "PASS" if crows else "FAIL", f"row={crows and crows[0]}")

# ---------- 卡2 b494f56e：purge 原子守卫 NULL 安全 ----------
# 动态取一条 DELETED 且 remark IS NULL 的申请（排除本轮已 purge 的），保证 A2 首清可复现
cand = L.sql("SELECT id FROM deletion_requests WHERE status='DELETED' AND remark IS NULL ORDER BY id DESC LIMIT 1")
if not cand:
    cand = L.sql("SELECT id FROM deletion_requests WHERE status='DELETED' AND remark NOT LIKE 'PURGED_BY_SUPER_ADMIN%' ORDER BY id DESC LIMIT 1")
assert cand, "真库无 remark IS NULL 的 DELETED 候选行"
DRID = cand[0][0]
# A1 归档区列表可见（DEF-8 已修，回归确认候选可得）
s, b, _, _ = L.req("GET", B, "/api/v1/deletion-requests/archive", token=TA)
lst = data_of(b) or []
visible = any(str(r.get("id")) == str(DRID) for r in lst)
rec("AC-DEL-08", "A1 归档区列表含 NULL-remark 候选", "GET /deletion-requests/archive",
    s, code_of(b), "PASS" if s == 200 and visible else "FAIL", f"候选数={len(lst)} 含{DRID}={visible}")

# A2 首清成功（修复前恒 409）
s, b, _, _ = L.req("POST", B, f"/api/v1/deletion-requests/{DRID}/purge", token=TA)
d = data_of(b) or {}
ok = s == 200 and code_of(b) == 0 and str(d.get("remark", "")).startswith("PURGED_BY_SUPER_ADMIN:")
rec("AC-DEL-08", "A2 首次 purge 200(修复前恒409)", f"POST /deletion-requests/{DRID}/purge",
    s, code_of(b), "PASS" if ok else "FAIL", f"remark={d.get('remark') if isinstance(d,dict) else msg_of(b)[:60]}")

# A3 真库回读：remark 打标 + 审计 DELETE_ARCHIVE_PURGE（历史 COUNT 恒 0）
crows = L.sql(f"SELECT remark FROM deletion_requests WHERE id={DRID}")
aud = L.sql("SELECT COUNT(*) FROM audit_logs WHERE action='DELETE_ARCHIVE_PURGE'")
ok = crows and str(crows[0][0]).startswith("PURGED_BY_SUPER_ADMIN:") and aud and int(aud[0][0]) >= 1
rec("AC-DEL-08", "A3 真库回读 remark打标+审计行(历史0→≥1)", "SQL deletion_requests.remark & audit_logs", None, None,
    "PASS" if ok else "FAIL", f"remark={crows and crows[0][0]} purge_audit_count={aud and aud[0][0]}")

# A4 幂等：二次 purge 409
s, b, _, _ = L.req("POST", B, f"/api/v1/deletion-requests/{DRID}/purge", token=TA)
rec("AC-DEL-08", "A4 已清除二次 purge 拒绝", f"POST /deletion-requests/{DRID}/purge (repeat)",
    s, code_of(b), "PASS" if code_of(b) not in (0, None) else "FAIL", f"http={s} msg={msg_of(b)[:60]}")

# A5 权限：非超管 purge → 必须被拦（http!=200 或未清）。若返 500 裸 Spring 体属 lane3 D-3 权限包络既有缺陷，非本波范围
s, b, _, _ = L.req("POST", B, f"/api/v1/deletion-requests/{DRID}/purge", token=TR)
blocked = s != 200
env_ok = isinstance(b, dict) and "code" in (b or {})
rec("AC-DEL-08", "A5 非超管 purge 被拦", "POST purge (ipd-rd)",
    s, code_of(b) if env_ok else None,
    "PASS" if (blocked and env_ok) else ("PARTIAL" if blocked else "FAIL"),
    f"拦下={blocked} 包络正常={env_ok}" + ("" if env_ok else " → 裸500属lane3 D-3权限包络既有缺陷(非本波范围)"))

# ---------- 落盘 ----------
out = os.path.join(HERE, "retest-ledger.json")
old = []
if os.path.exists(out):
    old = json.load(open(out, encoding="utf-8"))
json.dump(old + recs, open(out, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
from collections import Counter
print("== SUMMARY ==", dict(Counter(r["verdict"] for r in recs)))
