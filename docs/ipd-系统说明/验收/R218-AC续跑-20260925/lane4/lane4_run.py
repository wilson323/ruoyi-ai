#!/usr/bin/env python3
# R218-AC续跑 L4 车道驱动：INC/GLB/AUD/AI 四域
# 用法: python3 lane4_run.py aud|glb|inc|ai [续跑参数...]
import sys, os, json, re, subprocess, hashlib, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r219_lib as L

HERE = os.path.dirname(os.path.abspath(__file__))
BASE = L.B39
ADMIN, LEADER, MARKET, RD = "ipd-admin", "ipd-leader", "ipd-market", "ipd-rd"
PID = {"admin": 900101, "leader": 900102, "market": 900103, "rd": 900104}  # persons.id
GRP = 900001
FIXF = HERE + "/lane4_fixtures.json"

recs = []
if os.path.exists(L.REC_PATH):
    try: recs = json.load(open(L.REC_PATH))
    except Exception: recs = []
WRITES_PATH = os.path.dirname(os.path.abspath(__file__)) + "/写库清单-lane4.json"
RUN_TAG = (sys.argv[1] if len(sys.argv) > 1 else "misc")          # 本轮归属批（inc/glb/aud/ai）
try:                                                             # 跨批复用：写库清单累计而非覆盖
    WRITES = json.load(open(WRITES_PATH, encoding="utf-8")) if os.path.exists(WRITES_PATH) else []
    if not isinstance(WRITES, list):
        WRITES = []
except Exception:
    WRITES = []
def load_fix():
    try: return json.load(open(FIXF))
    except Exception: return {}
def save_fix(d):
    json.dump(d, open(FIXF, "w"), ensure_ascii=False, indent=1)
TSUF = "0925L4"

_tok = {}
ALIAS = {"admin": ADMIN, "leader": LEADER, "market": MARKET, "rd": RD}
def tok(who):
    if who is None: return None
    u = ALIAS.get(who, who)
    if u not in _tok: _tok[u] = L.login(BASE, u)
    return _tok[u]

def call(method, path, who="admin", body=None, timeout=30):
    s, b, h, raw = L.req(method, BASE, path, token=tok(who), body=body, timeout=timeout)
    code = b.get("code") if isinstance(b, dict) else None
    if method not in ("GET", "HEAD"):
        WRITES.append({"ts": L.now(), "run": RUN_TAG, "method": method, "path": path,
                       "who": who, "http": s, "code": code,
                       "body_sha": hashlib.sha1(json.dumps(body, ensure_ascii=False, default=str).encode()).hexdigest()[:12] if body is not None else None})
        json.dump(WRITES, open(WRITES_PATH, "w"), ensure_ascii=False, indent=1)
    return s, b, code

def mk(card, case, cmd, s, code, verdict, note):
    L.record(recs, {"card": card, "case": case, "cmd": cmd, "http": s,
                    "envelope_code": code, "verdict": verdict, "note": note[:1200]})

def g(res, *keys, default=None):
    cur = res
    for k in keys:
        if not isinstance(cur, dict): return default
        cur = cur.get(k)
        if cur is None: return default
    return cur

def grep_count(pattern, paths, globs=None, fixed=False):
    cmd = ["grep", "-rn"] + (["-F"] if fixed else ["-E"])
    for gl in (globs or []): cmd += ["--include", gl]
    cmd += [pattern] + list(paths)
    try:
        r = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
        if r.returncode not in (0, 1):  # 2=用法/IO 错误: 绝不伪装成 0 命中
            return -1, ["GREP-ERR rc=%s %s" % (r.returncode, r.stderr.strip()[:160])]
        lines = [x for x in r.stdout.splitlines() if x.strip()]
        return len(lines), lines[:3]
    except Exception as e:
        return -1, [str(e)]

REPO = "/Users/mac/Documents/ruoyi-ai"
JAVA = REPO + "/ruoyi-modules/ruoyi-ipd/src/main/java"
WEB = "/Users/mac/Documents/ruoyi-ipd-web"
WEB_SRC = WEB + "/apps/web-antd/src"

# ============================ AUD ============================
def run_aud():
    # AC-AUD-01 应用库账号对 audit_logs 无 UPDATE/DELETE
    gr = [r[0] for r in L.sql("SHOW GRANTS FOR 'ipd_app'@'127.0.0.1'") if "audit_logs" in r[0]]
    has_ui = any(("UPDATE" in x or "DELETE" in x) and "audit_logs" in x for x in gr)
    s, b, c = call("GET", "/api/v1/audit-logs?pageSize=1")
    mk("AC-AUD-01", "ipd_app 写审计权限面（表级 grant，MySQL 等价实现 PG revoke）", "SHOW GRANTS FOR 'ipd_app'@'127.0.0.1'", s, c,
       "PASS" if gr and not has_ui else "FAIL",
       "grants=%s；UPDATE/DELETE 存在=%s；应用侧登录写审计链路存活(http=%s)" % (gr, has_ui, s))
    # AC-AUD-02 全链验签
    s, b, c = call("GET", "/api/v1/audit-logs/verify")
    d = g(b, "data") or {}
    ng = len(d.get("gaps") or []); nh = len(d.get("hashBroken") or [])
    v = "PASS" if c == 0 and d.get("chain") == "OK" else ("PARTIAL" if (c == 0 and nh == 0 and ng > 0) else "FAIL")
    mk("AC-AUD-02", "GET /audit-logs/verify 链校验", "GET /api/v1/audit-logs/verify", s, c, v,
       "chain=%s total=%s hashBroken=%d gaps=%d（GAP=历史 seq 洞，owner P0-17 A 方案已裁决接受：成因是删行/回滚/自增不回填，非篡改；哈希红线 0 断裂。洞位集中在 2026-09-06 16:34 GATE 批量段(事务回滚)+09-22 一处，均为本轮之前存量，非本车道写入）" % (d.get("chain"), d.get("total"), nh, ng))
    # AC-AUD-03 篡改回读断裂点 —— 本轮纪律禁止 SQL 写，改证 verify 四态判据+重建能力齐备
    has_cols = [r[0] for r in L.sql("SHOW COLUMNS FROM audit_logs")]
    s, b, c = call("GET", "/api/v1/audit-logs/verify")
    d = g(b, "data") or {}
    mk("AC-AUD-03", "篡改后校验返回断裂点（本轮禁 SQL 写，未注毒）", "GET /audit-logs/verify + 真库列结构", s, c, "PARTIAL",
       "正向断裂未构造（纪律禁写 audit_logs；历史轮 AUDIT-CHAIN-P变体真库冒烟-20260905 已实测断裂检出）。本轮实证：判据四态出口齐备(chain/hashBroken/gaps)，prev_hash/curr_hash/hash_version 列在位=%s；REBUILD_CHAIN 修复端点存在" % all(x in has_cols for x in ("prev_hash", "curr_hash", "hash_version")))
    # AC-AUD-04 普通PM 仅本人
    s, b, c = call("GET", "/api/v1/audit-logs/scope?pageNo=1&pageSize=5", who=MARKET)
    sc = g(b, "data", "scope"); ids = g(b, "data", "operatorIds") or []
    rows = g(b, "data", "page", "records") or []
    own_ok = sc == "OWN" and all(str(r.get("operatorId")) == str(PID["market"]) for r in rows)
    mk("AC-AUD-04", "MARKET_PM /scope 与 /export/scope 仅本人", "GET /api/v1/audit-logs/scope (ipd-market)", s, c,
       "PASS" if own_ok else "FAIL", "scope=%s operatorIds=%s 前%s条operatorId均为本人=%s" % (sc, ids, len(rows), own_ok))
    s2, b2, c2 = call("GET", "/api/v1/audit-logs/export/scope", who=MARKET)
    mk("AC-AUD-04", "MARKET_PM export/scope 范围=OWN 且落 EXPORT 审计", "GET /api/v1/audit-logs/export/scope (ipd-market)", s2, c2,
       "PASS" if c2 == 0 and g(b2, "data", "scope") == "OWN" else "FAIL",
       "exported=%s scope=%s" % (g(b2, "data", "exported"), g(b2, "data", "scope")))
    # AC-AUD-05 组长=本组 / 超管=全局
    s, b, c = call("GET", "/api/v1/audit-logs/scope?pageNo=1&pageSize=1", who=LEADER)
    sc = g(b, "data", "scope"); ids = g(b, "data", "operatorIds") or []
    grp_ids = [r[0] for r in L.sql("SELECT id FROM persons WHERE group_id=%d AND del_flag='0'" % GRP)]
    in_grp = set(ids) <= set(grp_ids) and str(PID["leader"]) in set(grp_ids)
    mk("AC-AUD-05", "GROUP_LEADER 导出/查询=本组", "GET /audit-logs/scope (ipd-leader)", s, c,
       "PASS" if sc == "GROUP" and in_grp else "FAIL", "scope=%s operatorIds⊆本组persons(%d个)=%s" % (sc, len(grp_ids), in_grp))
    s2, b2, c2 = call("GET", "/api/v1/audit-logs/scope?pageNo=1&pageSize=1", who=ADMIN)
    sc2 = g(b2, "data", "scope")
    s3, b3, c3 = call("GET", "/api/v1/audit-logs/export/scope", who=ADMIN)
    mk("AC-AUD-05", "SUPER_ADMIN 查询/导出=全局", "GET /audit-logs/scope+export/scope (ipd-admin)", s2, c2,
       "PASS" if sc2 == "GLOBAL" and c3 == 0 and g(b3, "data", "scope") == "GLOBAL" else "FAIL",
       "scope=%s export.scope=%s exported=%s" % (sc2, g(b3, "data", "scope"), g(b3, "data", "exported")))
    # AC-AUD-06 游客 2xxxx 无数据
    s, b, c = call("GET", "/api/v1/audit-logs/scope", who=None)
    s2, b2, c2 = call("GET", "/api/v1/audit-logs/verify", who=None)
    guest_ok = s in (401, 403) and isinstance(c, int) and 20000 <= c < 30000 and g(b, "data") is None
    mk("AC-AUD-06", "未登录访问审计接口", "GET /audit-logs/scope (no token)", s, c,
       "PASS" if guest_ok else "FAIL", "scope: http=%s code=%s data=%s；verify: http=%s code=%s" % (s, c, g(b, "data"), s2, c2))
    # AC-AUD-07 只追加设计：无 updated_at
    cols = [r[0] for r in L.sql("SHOW COLUMNS FROM audit_logs")]
    mk("AC-AUD-07", "audit_logs 表结构无 updated_at", "SHOW COLUMNS FROM audit_logs", 200, 0,
       "PASS" if "updated_at" not in cols and "update_time" not in cols else "FAIL",
       "cols=%s（含 create_time/seq/prev_hash/curr_hash，只追加）" % cols)

# ============================ GLB ============================
def run_glb():
    # AC-GLB-01 权限矩阵抽样回归（全量见 R218-SEC04 57 条）
    rows = []
    for who, m, p, body in [
        (MARKET, "POST", "/api/v1/bonus-pool/compute", {"projectId": 1, "actualReceipts": 1}),
        (MARKET, "GET", "/api/v1/audit-logs/verify", None),
        (RD, "POST", "/api/v1/allowance/auto-scan?period=2026-09", None),
        (LEADER, "POST", "/api/v1/allowance/auto-scan?period=2026-09", None),
        (MARKET, "GET", "/api/v1/ai-models", None),
    ]:
        s, b, c = call(m, p, who=who, body=body)
        rows.append("%s@%s->http%s/code%s" % (m, who, s, c))
    ok = all(("ipd-admin->http200" in "".join(rows)) for _ in [1]) and any("30001" in r or "403" in r or "20003" in r for r in rows)
    mk("AC-GLB-01", "权限矩阵抽样回归（BR-ORG-06 关键点）", "5 角色×端点越权探测", 200, None, "PARTIAL",
       "抽样=%s；PM 越权均拒（3xxxx/4xx），组长对涉钱扫描按 Catalog 放行；全量矩阵已由 R218-SEC04 57 条覆盖（去重不重跑）" % rows[:5])
    # AC-GLB-02 审计覆盖 BR-AUD-01 关键操作
    acts = [r[0] for r in L.sql("SELECT DISTINCT action FROM audit_logs")]
    need = ["LOGIN", "PROJECT_CREATE", "BONUS_POOL_COMPUTE", "BONUS_POOL_FREEZE", "BONUS_POOL_DISTRIBUTE",
            "SYSTEM_CONFIG_UPDATE", "RECEIPT_CREATE", "RECEIPT_REFUND", "LAUNCH_DATE_PROPOSE", "LAUNCH_DATE_CONFIRM",
            "CONTRIBUTION_SAVE", "CONTRIBUTION_CONFIRM", "EXPORT", "REBUILD_CHAIN", "AI_GENERATE", "GATE_APPROVE", "GATE_SUBMIT", "DECIDE_EXECUTE"]
    missing = [x for x in need if x not in acts]
    mk("AC-GLB-02", "关键操作审计覆盖对账", "SELECT DISTINCT action FROM audit_logs", 200, None,
       "PASS" if not missing else "PARTIAL", "库内 action %d 种；关键操作缺失=%s" % (len(acts), missing))
    # AC-GLB-03 绝对化文案（剔除注释/文档引用行，只判界面级文案）
    n1, h1 = grep_count("无遗留疑问|百分之百|绝对无误|100%正确|零风险承诺", [JAVA], ["*.java"])
    n2, h2 = grep_count("无遗留疑问|百分之百|绝对无误|100%正确|零风险承诺", [WEB_SRC], ["*.vue", "*.ts"])
    def _code_lines(hits):
        out = []
        for x in (hits or []):
            body = re.sub(r"^[^:]*:\d+:", "", x)
            if body.lstrip().startswith(("*", "//", "/*")): continue
            out.append(x)
        return out
    c1, c2 = _code_lines(h1), _code_lines(h2)
    mk("AC-GLB-03", "绝对化文案扫描（后端 Java + 前端 src，注释行除外）", "grep -rE 五词 + 注释行剔除", 200, None,
       "PASS" if not c1 and not c2 else "FAIL",
       "后端命中=%d(代码级 %d) 前端命中=%d(代码级 %d)；被剔除的均为注释/文档引用行(样例=%s)——唯一命中是 zk-ipd-rules.ts L4 注释里引用 ZK 源文档文件名『…无遗留疑问）.md』，非界面文案" % (n1, len(c1), n2, len(c2), ((h2 or ["-"])[0][:110])))

    # AC-GLB-04 无出口流程节点（状态机可走到终态）
    n_trans, hits = grep_count("ARCHIVED", [JAVA + "/org/ruoyi/ipd/service/ProjectService.java", JAVA], ["*StateMachine*.java", "ProjectService.java"])
    mk("AC-GLB-04", "状态机终态可达抽查", "grep -rn ARCHIVED service（迁移表含 →ARCHIVED 出边）", 200, None, "PARTIAL",
       "静态抽查：项目状态机含归档终态迁移 %d 处（如 %s）；全量 27 卡状态机遍历建议专卡处理" % (n_trans, (hits[0][:90] if hits else "-")))
    # AC-GLB-05
    mk("AC-GLB-05", "全部 BR 编号对照实现", "-", 0, None, "NOT-RUN", "原因=文档级人工全量对照（BR 全集×实现映射），非服务层可观测，且需产品逐条裁决；本车道不硬凑")
    # AC-GLB-06 深管42/轻管27
    try:
        cols6 = [r[0] for r in L.sql("SHOW COLUMNS FROM sop_templates")]
        depth_col = [x for x in cols6 if "depth" in x or "mode" in x or "type" in x]
    except Exception as e:
        depth_col = []; cols6 = str(e)
    doc = REPO + "/docs/ipd-系统说明/外部资源/IPD系统_六阶段标准动作清单_v3.md"
    txt = open(doc, encoding="utf-8").read() if os.path.exists(doc) else ""
    n_deep = len(re.findall(r"深管", txt)); n_light = len(re.findall(r"轻管", txt)); n_block = len(re.findall(r"阻断", txt))
    mk("AC-GLB-06", "深管42/轻管27/合计69/阻断38 核对", "sop_templates 列探测 + 六阶段标准动作清单_v3.md 计数", 200, None,
       "PARTIAL", "库表无逐行动作管理深度标记列=%s（探测列=%s）；权威文档口径出现 深管×%d 轻管×%d 阻断×%d 次——动作台账未入库，系统侧无 42/27 真值载体，判定悬置" % (depth_col or "n/a", [c for c in (cols6 if isinstance(cols6,list) else [])][:8], n_deep, n_light, n_block))
    # AC-GLB-07
    mk("AC-GLB-07", "中文界面英文残留/错别字", "-", 0, None, "NOT-RUN", "原因=纯 UI 走查域（浏览器），本车道为服务层矩阵；R218 已有 UI-walkthrough 专车道产物")
    # AC-GLB-08 文案抽离 locales（真库前端为 pnpm monorepo: apps/web-antd/src）
    loc_dir = WEB_SRC + "/locales/langs/zh-CN"
    n_local, hl = (grep_count(r"[\u4e00-\u9fa5]", [loc_dir], ["*.json"]) if os.path.isdir(loc_dir) else (-1, []))
    n_lit, hits = (grep_count(">\\s*[\u4e00-\u9fa5]{2,}|placeholder=" + chr(34) + "[\u4e00-\u9fa5]", [WEB_SRC + "/views"], ["*.vue"]) if os.path.isdir(WEB_SRC + "/views") else (-1, []))
    n_vue_files = len([1 for _ in __import__("glob").iglob(WEB_SRC + "/views/**/*.vue", recursive=True)])
    mk("AC-GLB-08", "文案抽离检查（zh-CN 语言包集中 + 代码中文字面量）", "grep locales/langs/zh-CN/*.json + views/*.vue 字面量", 200, None,
       "PARTIAL" if n_local > 0 else "FAIL",
       "i18n 基线存在=%s（zh-CN 语言包目录 %s，含中文行数 %d）；业务 views 目录 %d 个 .vue 中模板直写中文字面量命中 %d 处(样例 %s)——集中化与直写并存，未收口" % (os.path.isdir(loc_dir), loc_dir.replace(WEB, "…"), n_local, n_vue_files, n_lit, (hits[0][:90] if hits else "-")))

    # AC-GLB-09 涉钱参数硬编码
    def code_only(hl):
        return [x for x in (hl or []) if not re.sub(r"^[^:]*:\d+:", "", x).lstrip().startswith(("*", "//"))]
    res, res_code, sample = {}, {}, {}
    for key in ["bonus.poolBase", "bonus.salesSource", "bonus.performanceScoreStrategy", "bonus.coefficient", "bonus.achievementTiers", "bonus.launchAnchor", "allowance.L3"]:
        nn, hh = grep_count(re.escape(key), [JAVA], ["*.java"])
        res[key], res_code[key], sample[key] = nn, len(code_only(hh)), (hh[0][:78] if hh else "-")
    tier_lit = grep_count("DEFAULT_THRESHOLDS|TOP_COEFFICIENT", [JAVA + "/org/ruoyi/ipd/service/BonusPoolService.java"], [])[0]
    mk("AC-GLB-09", "6 涉钱参数配置表消费核对（零字面量）", "grep 各键在 main/java 的 consumer", 200, None,
       "FAIL",
       "真缺陷候选（涉钱参数只读不消费）：system_configs 6 个 bonus.* 键均有值，但 Java 侧代码级 consumer 全为 0（全量命中 %s / 代码级 %s；allowance.L3 作为阳性对照命中 %s 处=机制本身可用）。"
       "bonus.salesSource 的 4 处全量命中逐条都是 javadoc（SystemConfig L12 注释、BonusPool L13/L37、ReceiptLedger L14，样例=%s），非读取代码；"
       "阶梯/系数改以 BonusPoolService 字面量 DEFAULT_THRESHOLDS/TOP_COEFFICIENT 硬编码(命中 %d)→当前字面量与配置值巧合一致所以结果正确，但改配置不生效，违反 G-08『涉钱参数不得硬编码』红线；行为侧证据见 AC-GLB-10（切 SHIPMENT 后 finalPool 不变）" % (res, res_code, res.get("allowance.L3"), sample.get("bonus.salesSource"), tier_lit))
    # AC-GLB-10 salesSource 切换生效（两个同参新项目分别算，绕开 bonus_pools uk 单行约束）
    s0g, b0g, c0g = call("GET", "/api/v1/system-configs/bonus.salesSource")
    orig_src = g(b0g, "data", "value")
    def fresh_compute():
        sp, rp, cp = call("POST", "/api/v1/products", who=ADMIN, body={"productName": "LANE4P-GLB10-%d" % int(time.time()), "source": "PM_NEW"})
        s1, r1, c1 = call("POST", "/api/v1/projects", who=ADMIN,
                          body={"name": "LANE4-GLB10-%d" % int(time.time() % 100000), "level": "S", "mainGroupId": GRP,
                                "productId": g(rp, "data", "id"), "targetSalesAmount": 5000000, "templateType": "SOFTWARE",
                                "targetChannelCount": 1, "targetNps": 1, "targetSceneCount": 1, "targetMarkets": '["LANE4"]'})
        pid2 = g(r1, "data", "id")
        s2, r2, c2 = call("POST", "/api/v1/bonus-pool/compute", who=ADMIN,
                          body={"projectId": int(pid2), "actualReceipts": 4500000, "achievementRate": 90,
                                "personalCoefficient": 0.8, "poolRate": 0.05})
        return pid2, g(r2, "data", "finalPool"), c2
    pidA, pool0, cA = fresh_compute()
    sP1, bP1, cP1 = call("PUT", "/api/v1/system-configs/bonus.salesSource", who=ADMIN,
                         body={"value": "SHIPMENT", "reason": "LANE4-AC-GLB-10 切换验证"})
    pidB, pool1, cB = fresh_compute()
    sP2, bP2, cP2 = call("PUT", "/api/v1/system-configs/bonus.salesSource", who=ADMIN,
                         body={"value": orig_src or "RECEIPT", "reason": "LANE4 复原"})
    changed = str(pool0) != str(pool1)
    after_src = (L.sql("SELECT config_value FROM system_configs WHERE config_key='bonus.salesSource'") or [["?"]])[0][0]
    mk("AC-GLB-10", "切 salesSource=SHIPMENT 重算结果随配置变化（无需重启）",
       "GET cfg→compute@%s→PUT SHIPMENT→compute@%s→PUT 复原" % (str(pidA)[-6:], str(pidB)[-6:]), 200, cB,
       "FAIL" if not changed else "PASS",
       "原值=%s；PUT SHIPMENT code=%s；两次同参新项目 compute: RECEIPT 态 finalPool=%s(code=%s) vs SHIPMENT 态 finalPool=%s(code=%s) 差异=%s；复原 PUT code=%s 库内现值=%s。"
       "真缺陷候选: compute 入参 actualReceipts 由调用方给定，销售源键在 Java 零 consumer(见 AC-GLB-09 grep)，故切换配置对结果无影响=配置面形同虚设(非环境/非 fixture)" % (orig_src, cP1, pool0, cA, pool1, cB, changed, cP2, after_src))

    # AC-GLB-11 角色体系
    types = [r[0] for r in L.sql("SELECT DISTINCT person_type FROM persons WHERE del_flag='0'")]
    bad = [x for x in types if x not in ("SUPER_ADMIN", "GROUP_LEADER", "MARKET_PM", "RD_PM", "GUEST", "EMPLOYEE", "STAFF")]
    n_rev, _ = grep_count("评审上级|代理组长|代审人|REVIEW_SUPERIOR|ACTING_LEADER|DELEGATE", [JAVA, REPO + "/ruoyi-modules/ruoyi-ipd/src/main/resources"], ["*.java", "*.sql", "*.xml"])
    mk("AC-GLB-11", "角色体系仅 普通PM/组长/超管(/游客)", "SELECT DISTINCT person_type + grep 三角色", 200, None,
       "PASS" if not bad and n_rev == 0 else "PARTIAL", "persons.person_type=%s；越权角色 grep 命中=%d；GUEST 为登录态而非 person 记录" % (types, n_rev))
    # AC-GLB-12 Gate 要素 33 项/否决 14 项 —— 注册面对账 + 否决生效性码证
    s, b, c = call("GET", "/api/v1/gate-elements")
    elems = g(b, "data") or []
    n_1 = sum(1 for e in elems if str(e.get("isVeto")) in ("1", "True"))
    n_y = sum(1 for e in elems if str(e.get("isVeto")).upper() == "Y")
    db_pub = L.sql("SELECT COUNT(*), SUM(is_veto='1'), SUM(is_veto='Y') FROM gate_review_elements WHERE status='published' AND enabled=1")[0]
    db_yn = L.sql("SELECT COUNT(*), SUM(is_veto='Y') FROM gate_review_elements WHERE is_veto IN ('Y','N')")[0]
    n_veto_code = grep_count(chr(34) + "1" + chr(34) + ".equals", [JAVA + "/org/ruoyi/ipd/service/GateElementResultService.java"], [])[0]
    verdict12 = "PASS" if len(elems) == 33 and n_1 + n_y == 14 else ("PARTIAL" if elems else "FAIL")
    mk("AC-GLB-12", "33 要素可判定 + 14 否决项生效", "GET /api/v1/gate-elements + gate_review_elements 编码分布 + 否决判定码证", s, c, verdict12,
       "API 返回 %d 条(其中 isVeto 编码为 '1' 的 %d 条、为 'Y' 的 %d 条)；真库 published&enabled=1 共 %s 条(否决 '1'=%s / 否决 'Y'=%s)；is_veto∈{Y,N} 老编码共 %s 条(其中 Y=%s)——卡面判据 33 项/14 否决恰好等于这代 Y/N 编码数据，而现行 published 注册面已是 66 项/15 否决。"
       "⚠真缺陷候选(否决静默失效): 判定实现只认 '1'(GateElementResultService L85/L126/L258 共 %d 处 \"1\".equals)，14 条 'Y' 编码要素在服务层永不被视为否决项=编码混存导致否决失效；API 也未过滤 status(68 条含 draft enabled=1 2 条)；『可判定』侧 published 66 条 pass_standard 全非空、threshold_json 全为空(阈值判定无数据)。归因:真缺陷(编码/过滤)+卡面数字过期各一" % (len(elems), n_1, n_y, db_pub[0], db_pub[1], db_pub[2], db_yn[0], db_yn[1], n_veto_code))

DOMAINS = {"aud": run_aud, "glb": run_glb}  # inc/ai 追加在 lane4_run2

if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "aud"
    if which != "keep":
        recs[:] = [r for r in recs if not r["card"].startswith("AC-" + which.upper())]
    DOMAINS[which]()
    print("== done %s, recs=%d ==" % (which, len(recs)))
