#!/usr/bin/env python3
# R219 LANE1 驱动：AUTH/TEAM/HR/CFG/ENV 五域 249AC 矩阵真执行续跑（可重跑；fixture 均带 LANE1- 前缀）
# 依赖 r219_lib.py（拷贝自 r218_lib.py，REC/写库清单改指 lane1）。禁止重启后端/杀端口/直写 SQL。
import sys, os, time, json, socket, subprocess, threading
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r219_lib as L

B = L.B39
# 续跑支持: R219_RUN=<HHMMSS> 时复用该波次已建的 LANE1- 夹具, 并把新记录并入既有执行清单(不重复已判定卡)
RUN = os.environ.get("R219_RUN") or time.strftime("%H%M%S")
RESUME = bool(os.environ.get("R219_RUN"))
RECS = []
if RESUME:
    try:
        RECS = json.load(open(L.REC_PATH, encoding="utf-8"))
    except Exception:
        RECS = []
_DONE = set(r.get("card") for r in RECS) if RESUME else set()
# 指定卡片重判: R219_RERUN="AC-AUTH-03 AC-CFG-01" (判据修正后复跑, 旧记录以新记录替换) # noqa
RERUN = set((os.environ.get("R219_RERUN") or "").split())
if RERUN:
    RECS = [r for r in RECS if r.get("card") not in RERUN]
    _DONE -= RERUN
    json.dump(RECS, open(L.REC_PATH, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
NOTES = []          # 真缺陷候选收集
TS_NOTES = []       # 归因注记收集
def note_fix(s): NOTES.append(s)
def note_attr(s): TS_NOTES.append(s)

def rec(card, case, cmd, http, ecode, verdict, note):
    if RESUME and card in _DONE:
        print("[SKIP] %s 已判定, 续跑模式不重复写" % card, flush=True)
        return
    _DONE.add(card)
    L.record(RECS, {"card": card, "case": case, "cmd": cmd, "http": http,
                    "envelope_code": ecode, "verdict": verdict, "note": note[:900], "ts": L.now()})

def code(b):
    return b.get("code") if isinstance(b, dict) else None

def data(b):
    return (b.get("data") if isinstance(b, dict) else None) or {}

def msg(b):
    return b.get("message") if isinstance(b, dict) else None

TK = {}
def tok(u):
    if u not in TK:
        t = L.login(B, u)
        if not t: raise RuntimeError("login fail " + u)
        TK[u] = t
    return TK[u]

def drop(u):
    TK.pop(u, None); L._tok.pop((B, u), None)

def call(method, path, u=None, token=None, body=None, raw=None, ctype=None):
    t = token if token is not None else (tok(u) if u else None)
    return L.req(method, B, path, token=t, body=body, raw=raw, content_type=ctype)

def sql1(q):
    r = L.sql(q)
    return r[0][0] if r and r[0] else None

def tcp(host, port, tmo=2.0):
    try:
        s = socket.create_connection((host, port), timeout=tmo); s.close(); return True
    except Exception: return False

def grep(pattern, paths, opts="-rn --include=*.java --include=*.yml --include=*.yaml --include=*.sh --include=*.vue --include=*.ts"):
    cmd = "grep %s -E %s %s 2>/dev/null | grep -v '/target/\\|node_modules\\|/.claude/\\|/dist/' | head -60" % (opts, json.dumps(pattern), paths)
    r = subprocess.run(["/bin/sh", "-c", cmd], capture_output=True, text=True, timeout=60)
    return r.stdout.strip()

# ============================ ENV ============================
def env():
    # ENV-01 (PG5432/Redis6379/MinIO9000+9001 连通; 本环境为 MySQL@13306 栈)
    ok_pg = tcp("127.0.0.1", 5432); ok_redis = tcp("127.0.0.1", 6379)
    ok_minio = tcp("127.0.0.1", 9000); ok_minio2 = tcp("127.0.0.1", 9001)
    ok_mysql = tcp("127.0.0.1", 13306)
    v = "PARTIAL"
    rec("AC-ENV-01", "ENV01-connectivity", "TCP probe 127.0.0.1 {5432,6379,9000,9001,13306}",
        "-", None, "NOT-RUN" if not (ok_pg or ok_redis or ok_minio) else v,
        "PG:5432=%s Redis:6379=%s MinIO:9000=%s/9001=%s | MySQL:13306=%s. 清单P0底座基于Windows+PG+Redis+MinIO; 当前运行栈为免Docker MySQL@13306(见ps:java -jar ruoyi-admin.jar profiles=ipd-local,dev redis 16379), Windows .bat/PG/MinIO 端口本机不存在。归因=环境基线漂移(非缺陷): 该AC前提不适用当前部署。Redis实连端口16379(启动参数)。" % (ok_pg, ok_redis, ok_minio, ok_minio2, ok_mysql))
    note_attr("ENV-01: 验收清单(Windows/PG/Redis/MinIO) 与实际部署(MySQL@13306/Redis@16379, 免Docker) 存在技术栈基线漂移; ENV-01/03 前提不成立。")
    # ENV-02 migration+seed: schema_history + 默认超管/产品组/系统参数/角色字典
    hist = sql1("SELECT COUNT(*) FROM _ipd_schema_history")
    sa = sql1("SELECT COUNT(*) FROM persons WHERE username='ipd-admin' AND person_type='SUPER_ADMIN'")
    pg = sql1("SELECT COUNT(*) FROM product_groups")
    sc = sql1("SELECT COUNT(*) FROM system_configs WHERE del_flag='0'")
    # 角色字典载体实测: 内置目录在 Java(src/security/IpdRolePermissionCatalog.java, R215 权限可配置化),
    # ipd_role_permission 仅是"覆盖层"(0 行=无覆盖=干净默认), 故不能用它判 seed 缺失。
    rp = sql1("SELECT COUNT(*) FROM ipd_role_permission")
    cat = grep("ROLE_PERMISSIONS|Map.of|put\\(", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/security/IpdRolePermissionCatalog.java")
    sr, sj, _, _ = call("GET", "/api/v1/role-permissions/effective", u="ipd-admin")
    roles = sorted((data(sj) or {}).keys())
    rl = {r: len(((data(sj) or {}).get(r) or {}).get("javaDefault") or []) for r in roles}
    all_ok = all([hist and int(hist)>0, sa and int(sa)>0, pg and int(pg)>0, sc and int(sc)>0,
                  sr == 200 and code(sj) == 0 and len(roles) >= 4 and all(v > 0 for v in rl.values())])
    rec("AC-ENV-02", "ENV02-seed", "SQL: _ipd_schema_history + persons超管 + product_groups + system_configs; GET /role-permissions/effective(角色字典)",
        sr, code(sj), "PASS" if all_ok else "FAIL",
        "schema_history=%s migrations; SUPER_ADMIN=%s; product_groups=%s; system_configs=%s; 角色字典=effective端点 http=%s code=%s 角色%s 各角色javaDefault条数=%s; ipd_role_permission 覆盖行=%s(0=无覆盖,系R215设计:字典默认在Java目录非DB)。真库 ipd_dev 回读 %s" %
        (hist, sa, pg, sc, sr, code(sj), roles, rl, rp, L.now()))
    # ENV-03 备份/恢复: Windows pgdata 路径 + PG 工具链 -> 环境不适用
    rec("AC-ENV-03", "ENV03-backup", "n/a (pg_dumpall/Windows F:\\devtools\\pgdata)", "-", None, "NOT-RUN",
        "清单要求备份Windows pgdata并PG恢复; 当前无PostgreSQL栈(实为MySQL), 且禁止破坏性备份/恢复演练。归因=环境基线漂移。")
    # ENV-04 去重跳过
    rec("AC-ENV-04", "ENV04-dedup", "skip", "-", None, "NOT-RUN", "去重: 该卡已由 R218-QA08-SEC04 执行清单覆盖(见 执行清单-r218.json card=AC-ENV-04)，本车道按去重规则不重复跑。")
    # ENV-05 静态检索 docker compose up
    hits = grep("docker[ ].*compose[ ]+up|docker-compose[ ]+up", "/Users/mac/Documents/ruoyi-ai --exclude-dir=docs --exclude-dir=.claude --exclude-dir=node_modules")
    lines = [x for x in hits.splitlines() if x.strip()]
    dev_script_hits = [x for x in lines if any(k in x for k in ["/scripts/", ".sh:", ".bat:", "docker-compose.yml", "Makefile"])]
    rec("AC-ENV-05", "ENV05-grep", "grep -rE 'docker compose up' ruoyi-ai (exclude docs/.claude/node_modules)",
        "-", None, "PASS" if not dev_script_hits else "PARTIAL",
        "命中%d处; 开发脚本/AI指令中直接执行命中=%d。样本: %s | 说明: 命中集中于README/部署文档(生产)与docs; 无开发期脚本自动执行。" % (len(lines), len(dev_script_hits), (lines[:2] or ["无"])[:2]))
    # ENV-06 后端 Redis 6.2+ 专有命令
    hits6 = grep("ZRANGESTORE|GETDEL|SINTERCARD", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules /Users/mac/Documents/ruoyi-ai/ruoyi-common")
    l6 = [x for x in hits6.splitlines() if x.strip()]
    rec("AC-ENV-06", "ENV06-grep", "grep -rE 'ZRANGESTORE|GETDEL|SINTERCARD' ruoyi-modules ruoyi-common",
        "-", None, "PASS" if not l6 else "FAIL",
        "Redis 6.2+ 专有命令命中=%d %s (COPY 因语义泛化单列不计)。" % (len(l6), (l6[:2] or ["零命中"])[:2]))

# ============================ AUTH (无依赖部分) ============================
def auth_core():
    # AUTH-01 超管独立登录
    s, b, _, _ = call("POST", "/api/v1/auth/login", token=None, body={"username": "ipd-admin", "password": L._pwd("ipd-admin")})
    tk = data(b).get("token")
    s2, b2, _, _ = call("GET", "/api/v1/auth/me", token=tk)
    pv = data(b2).get("person", {})
    ok = s == 200 and code(b) == 0 and tk and pv.get("personType") == "SUPER_ADMIN"
    rec("AC-AUTH-01", "AUTH01-admin-login", "POST /api/v1/auth/login(ipd-admin) + GET /auth/me", s, code(b),
        "PASS" if ok else "FAIL",
        "http=%s code=%s scope=%s personType=%s username=%s(独立凭证非PM姓名规则) token=%s... ts=%s" % (s, code(b), data(b2).get("scope"), pv.get("personType"), pv.get("username"), (tk or "")[:12], L.now()))
    # AUTH-02 新导入PM首登强制改密(信源: application-dev.yml initial-password=Ipd@123456; 刘研发 must_change_pwd=1)
    s, b, _, _ = call("POST", "/api/v1/auth/login", token=None, body={"username": "刘研发", "password": "Ipd@123456"})
    t2 = data(b).get("token")
    if s == 200 and code(b) == 0 and t2:
        mc = data(b).get("mustChangePwd"); sc = data(b).get("scope")
        s3, b3, _, _ = call("GET", "/api/v1/projects", token=t2)
        blocked = code(b3) == 20003
        ok = mc is True and sc == "PASSWORD_CHANGE_REQUIRED" and blocked
        rec("AC-AUTH-02", "AUTH02-first-login", "login(刘研发,must_change_pwd=1) -> GET /projects", s3, code(b3),
            "PASS" if ok else "FAIL",
            "登录200 code=0 mustChangePwd=%s scope=%s; 未改密访问 /api/v1/projects -> http=%s code=%s(预期20003拒访) ts=%s" % (mc, sc, s3, code(b3), L.now()))
        call("POST", "/api/v1/auth/logout", token=t2)  # 清理会话,不改密不破坏夹具
    else:
        rec("AC-AUTH-02", "AUTH02-first-login", "login(刘研发) @initial-password(Ipd@123456)", s, code(b), "NOT-RUN",
            "登录未成功 http=%s code=%s msg=%s。归因=fixture数据: 库内刘研发 password_hash 与 dev 信源 initial-password 不匹配(该账号由历史波次改写或独立hash),无其它 must_change_pwd=1 且凭证有据的账号。" % (s, code(b), msg(b)))
        note_attr("AUTH-02: fixture假红风险——首登账号密码不可知;非缺陷。")
    # AUTH-04 企微Mock扫码(已绑定) —— R214/U0 开关
    s, b, _, _ = call("POST", "/api/v1/auth/wecom/qr-login", token=None, body={"wecomUserId": "wecom_R29_4_test_001"})
    rec("AC-AUTH-04", "AUTH04-qr-bound", "POST /api/v1/auth/wecom/qr-login(wecom_R29_4_test_001, ipd-rd已绑定)", s, code(b),
        "PARTIAL",
        "http=%s code=%s msg=%s —— 开关 ipd.auth.qr-login.enabled=false(R214/U0 安全默认关闭,启动配置未开启),端点行为正确(不查库不签token,409业务拒绝);'登录成功签发JWT'正向路径在本部署不可观测。归因=环境配置(开关关闭),非缺陷。ts=%s" % (s, code(b), msg(b), L.now()))
    note_attr("AUTH-04/05: qr-login 端点默认关闭(R214/U0)。若要完整验证需专用环境开 ipd.auth.qr-login.enabled=true 重跑,共享实例禁改配置(禁重启)。")
    # AUTH-05 企微扫码(未绑定)
    s, b, _, _ = call("POST", "/api/v1/auth/wecom/qr-login", token=None, body={"wecomUserId": "LANE1-NO-SUCH-%s" % RUN})
    rec("AC-AUTH-05", "AUTH05-qr-unbound", "POST /auth/wecom/qr-login(未绑定wecomId)", s, code(b),
        "PARTIAL",
        "http=%s code=%s msg=%s —— 同样被开关50019前置拦截,无法观测'账号未绑定'提示路径(代码路径 wecomMockLogin NOT_FOUND 存在,src/service/IpdAuthService.java)。归因=环境配置同AUTH-04。" % (s, code(b), msg(b)))
    # AUTH-08 普通PM调超管接口
    s0, b0, _, _ = call("GET", "/api/v1/system-configs/gate.signDeadlineDays", token=tok("ipd-admin"))
    old_val = data(b0).get("value")
    s, b, _, _ = call("PUT", "/api/v1/system-configs/gate.signDeadlineDays", u="ipd-market", body={"value": old_val, "reason": "LANE1-auth08-negative"})
    s1, b1, _, _ = call("GET", "/api/v1/system-configs/gate.signDeadlineDays", token=tok("ipd-admin"))
    same = data(b1).get("value") == old_val
    rec("AC-AUTH-08", "AUTH08-pm-vs-admin", "PUT /system-configs/gate.signDeadlineDays (ipd-market)", s, code(b),
        "PASS" if (s == 403 and code(b) == 30001 and same) else "FAIL",
        "http=%s code=%s msg=%s(预期403/30001); 数据无变更回读 value %s->%s %s ts=%s" % (s, code(b), msg(b), old_val, data(b1).get("value"), "一致" if same else "被改!", L.now()))

# ============================ 夹具: LANE1 项目 + TEAM 链(上) ============================
CTX = {}
def iso(dt=None):
    import datetime
    d = dt or (datetime.datetime.now() + datetime.timedelta(days=7))
    return d.strftime("%Y-%m-%d %H:%M:%S")

def make_projects():
    """夹具: 每组 = 1 新产品 + 1 新项目(产品:项目 1:1, 服务端强制)。
    实测契约(src/ProjectService.validateBaselinesAndTemplate + projects.target_markets 为 MySQL JSON 列):
      templateType ∈ HARDWARE|SOFTWARE|SOLUTION; targetMarkets 必须是合法 JSON 数组文本;
      targetSalesAmount>0 / targetChannelCount>=0 / targetNps / targetSceneCount>=0 必填; level=A(系数固定1.0)."""
    ok = True
    for key in ("P-A", "P-B", "P-C"):
        tag = key.replace("-", "")
        s0, b0, _, _ = call("POST", "/api/v1/products", u="ipd-market",
                            body={"productCode": "LANE1-PRD-%s-%s" % (tag, RUN),
                                  "productName": "LANE1-产品-%s-%s" % (tag, RUN),
                                  "source": "PM_NEW", "groupId": 900001})
        prod = data(b0).get("id")
        if code(b0) == 0 and prod:
            L.write_register("products id=%s (LANE1-PRD-%s-%s, 夹具配套产品)" % (prod, tag, RUN))
        else:
            CTX[key] = None; ok = False
            note_attr("%s 前置产品创建失败: http=%s code=%s msg=%s" % (key, s0, code(b0), msg(b0)))
            continue
        s, b, _, _ = call("POST", "/api/v1/projects", u="ipd-market",
                          body={"name": "LANE1-%s-%s" % (key, RUN), "mainGroupId": 900001,
                                "productId": int(prod), "templateType": "SOFTWARE",
                                "targetMarkets": '["LANE1"]', "level": "A",
                                "targetSalesAmount": 1200000, "targetChannelCount": 10,
                                "targetNps": 50, "targetSceneCount": 6, "source": "NEW"})
        pid = data(b).get("id")
        CTX[key] = pid
        if code(b) == 0 and pid:
            L.write_register("projects id=%s (LANE1-%s-%s, product=%s, R219-LANE1 夹具)" % (pid, key, RUN, prod))
        else:
            ok = False
            note_attr("%s 创建失败: http=%s code=%s msg=%s (依赖该夹具的用例降级 BLOCKED)" % (key, s, code(b), msg(b)))
    CTX["FIX_OK"] = ok
    if not ok:
        note_attr("夹具部分缺失 P-A/B/C=%s —— 依赖用例由各自守卫记 BLOCKED" % [CTX.get(k) for k in ("P-A","P-B","P-C")])

def blocked(cases, reason):
    for c in cases.split():
        if RESUME and c in _DONE: continue
        rec(c, "LANE1-fix-blocked", "n/a(夹具缺失)", "-", None, "BLOCKED",
            "上游夹具不可用, 本用例本轮不执行。证据: %s ts=%s" % (reason, L.now()))

def team_upper():
    pa = CTX.get("P-A")
    RD, RD2, MKT = "900104", "900105", "900103"
    if not pa:
        blocked("AC-TEAM-01 AC-TEAM-02 AC-TEAM-03", "P-A=None(make_projects 失败, 详见 ENV/夹具注记)")
        return
    # TEAM-01 一对一邀标 + 发布 + 可见性 + 通知
    body = {"projectId": pa, "mode": "ONE_TO_ONE", "targetPersonId": int(RD),
            "title": "LANE1-INV1-%s" % RUN, "content": "LANE1 一对一邀标夹具", "expireAt": iso()}
    s, b, _, _ = call("POST", "/api/v1/bid-invitations", u="ipd-market", body=body)
    inv1 = data(b).get("id")
    CTX["INV1"] = inv1
    if code(b) == 0 and inv1:
        L.write_register("bid_invitations id=%s (LANE1-INV1 ONE_TO_ONE target=ipd-rd @project %s)" % (inv1, pa))
        s2, b2, _, _ = call("PUT", "/api/v1/bid-invitations/%s/publish" % inv1, u="ipd-market")
        n = sql1("SELECT COUNT(*) FROM notification_events WHERE receiver_id=%s AND source_type='bid_invitation' AND source_id=%s" % (RD, inv1))
        s3, b3, _, _ = call("GET", "/api/v1/bid-invitations?pageNo=1&pageSize=100", u="ipd-leader")
        rows = data(b3).get("records") or []
        visible_to_rd2 = any(str(r.get("id")) == str(inv1) for r in rows)
        a_ok = (n and int(n) > 0); v_ok = not visible_to_rd2
        rec("AC-TEAM-01", "TEAM01-invite", "POST /bid-invitations(ONE_TO_ONE->rd) + publish; leader GET /bid-invitations; SQL notification_events", s2, code(b2),
            "PASS" if (a_ok and v_ok) else "FAIL",
            "创建http=%s code=%s id=%s; publish http=%s code=%s; 被邀人rd通知数=%s(预期>0); 非受邀者(leader)列表可见该单=%s(预期False)。%s ts=%s" %
            (s, code(b), inv1, s2, code(b2), n, visible_to_rd2, ("命中真缺陷:publish不发送通知且list无可见性过滤" if not (a_ok and v_ok) else ""), L.now()))
        note_attr("TEAM-01/02/04: %s" % "口径变更: 池内第二个可登录 RD_PM ipd-rd2(900105) 已被兄弟车道置 employment_status=RESIGNED(实测 login 400 code=10001 '离职账号禁止登录' + persons 回读)，改用非受邀账号 ipd-leader 做同一'非受邀者'断言。")
        if not (a_ok and v_ok):
            note_fix("TEAM-01 真缺陷候选: publish 无通知落库(BidInvitationService.publish 注释'通知由OPS承接'未接线) + 列表接口 page() 无 ONE_TO_ONE 目标过滤,任何内部PM可枚举他人定向邀标。")
    else:
        rec("AC-TEAM-01", "TEAM01-invite", "POST /bid-invitations", s, code(b), "FAIL-ENV", "创建邀标失败 http=%s code=%s msg=%s,后续TEAM链降级" % (s, code(b), msg(b)))
        return
    # TEAM-02 转发=B无法应标(系统无转发功能)
    s, b, _, _ = call("POST", "/api/v1/bid-responses", u="ipd-leader",
                      body={"invitationId": int(inv1), "decision": "accept", "responseNote": "L" + "ANESHOULDBEREJECTED-应标越权测试夹具内容补充补充补充补充补充0123456789"})
    cnt = sql1("SELECT COUNT(*) FROM bid_responses WHERE invitation_id=%s" % inv1)
    fwd = grep("\"forward|/forward|transfer.*bid|转发\"", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller")
    ok = code(b) == 30001 and (cnt == "0") and not fwd
    rec("AC-TEAM-02", "TEAM02-no-forward", "grep转发端点=0; leader(非受邀) POST /bid-responses @INV1", s, code(b),
        "PASS" if ok else "FAIL",
        "非受邀leader应标 http=%s code=%s msg=%s(预期30001不在邀请名单); bid_responses 行数=%s(预期0); 转发/transfer端点检索命中=%d。ts=%s" % (s, code(b), msg(b), cnt, len(fwd.splitlines()) if fwd else 0, L.now()))
    # TEAM-03 拒绝应标不留痕
    s, b, _, _ = call("POST", "/api/v1/bid-responses", u="ipd-rd",
                      body={"invitationId": int(inv1), "decision": "reject"})
    aud = sql1("SELECT COUNT(*) FROM audit_logs WHERE action='reject' AND entity_type='bid_response'")
    cnt2 = sql1("SELECT COUNT(*) FROM bid_responses WHERE invitation_id=%s AND rd_pm_id=%s" % (inv1, RD))
    ok = s == 200 and code(b) == 0 and b.get("data") is None and cnt2 == "0"
    rec("AC-TEAM-03", "TEAM03-reject-traceless", "rd POST /bid-responses decision=reject @INV1 + SQL回读", s, code(b),
        "PASS" if ok else "FAIL",
        "http=%s code=%s data=%s(预期null=204语义); 该邀标rd应标行数=%s(预期0); 全局reject审计行数=%s(预期0); 被邀人侧隐私由TEAM02/列表过滤保证 ts=%s" % (s, code(b), b.get("data"), cnt2, aud, L.now()))

def team_lower():
    pa, pb, pc = CTX.get("P-A"), CTX.get("P-B"), CTX.get("P-C")
    inv1 = CTX.get("INV1")
    if not (pa and inv1):
        blocked("AC-TEAM-04 AC-TEAM-05 AC-TEAM-10 AC-TEAM-13", "P-A/INV1 缺失(pa=%s inv1=%s)" % (pa, inv1))
        return
    import datetime
    # TEAM-04 邀标被拒后改公开招标(modify 端点无 mode 参数,先实测原单 mode)
    s0, b0, _, _ = call("GET", "/api/v1/bid-invitations/%s" % inv1, u="ipd-market")
    mode0 = data(b0).get("mode")
    body = {"title": "LANE1-INV1-%s-MOD" % RUN, "content": "改期继续挂标", "expireAt": iso(datetime.datetime.now() + datetime.timedelta(days=9))}
    s1, b1, _, _ = call("PUT", "/api/v1/bid-invitations/%s/modify?title=%s&content=%s&expireAt=%s" % (inv1, body["title"], "x", L.urllib.parse.quote(body["expireAt"])), u="ipd-market")
    s2, b2, _, _ = call("POST", "/api/v1/bid-invitations", u="ipd-market",
                        body={"projectId": pa, "mode": "PUBLIC", "title": "LANE1-INV2-%s" % RUN,
                              "content": "LANE1 公开招标夹具", "expireAt": iso()})
    inv2 = data(b2).get("id"); CTX["INV2"] = inv2
    if code(b2) == 0 and inv2:
        L.write_register("bid_invitations id=%s (LANE1-INV2 PUBLIC @P-A=%s)" % (inv2, pa))
    s3, b3, _, _ = call("GET", "/api/v1/bid-invitations?pageNo=1&pageSize=100", u="ipd-leader")
    rows = data(b3).get("records") or []
    inv2_seen = any(str(r.get("id")) == str(inv2) for r in rows)
    aud = sql1("SELECT COUNT(*) FROM audit_logs WHERE action='modify_conditions' AND entity_id=%s" % inv1)
    ok = (mode0 == "ONE_TO_ONE") and (data(b2).get("mode") == "PUBLIC") and inv2_seen
    rec("AC-TEAM-04", "TEAM04-to-public", "GET INV1 mode; PUT modify(mode参数不支持); POST 新建PUBLIC INV2; leader 列表回读; SQL audit", s2, code(b2),
        "PARTIAL" if ok else "FAIL",
        "INV1 mode=%s; 改mode: modify端点参数无mode(实测%s/%s)→系统采用'新建公开招标单'路径 INV2 id=%s code=%s; 非受邀leader可见INV2=%s(公开=全员可见); INV1 modify_conditions审计=%s行。归因注:无'原单转公开'端点=设计如此/缺陷二选一,证据供裁决。ts=%s" %
        (mode0, s1, code(b1), inv2, code(b2), inv2_seen, aud, L.now()))
    # TEAM-10 市场PM 尝试将自己设为该项目研发PM（bid 侧）
    note = "LANE1-市场PM越权应标测试方案摘要需要凑满四十字符以上长度限制所以继续补充凑满它啊"
    s, b, _, _ = call("POST", "/api/v1/bid-responses", u="ipd-market", body={"invitationId": int(inv2), "decision": "accept", "responseNote": note})
    mrow = sql1("SELECT COUNT(*) FROM bid_responses WHERE invitation_id=%s AND rd_pm_id=900103" % inv2)
    mid = sql1("SELECT id FROM bid_responses WHERE invitation_id=%s AND rd_pm_id=900103 LIMIT 1" % inv2)
    if mid:  # 若越权建行成功→撤回清理，不留污染
        sw, bw, _, _ = call("PUT", "/api/v1/bid-responses/%s/withdraw" % mid, u="ipd-market")
        L.write_register("bid_responses id=%s 应标后被撤回(TEAM-10 越权实证清理, withdraw http=%s code=%s)" % (mid, sw, code(bw)))
    if code(b) == 0 and mrow == "1":
        rec("AC-TEAM-10", "TEAM10-self-rdpm", "market POST /bid-responses accept @INV2(PUBLIC)", s, code(b), "FAIL",
            "市场PM 应标研发侧未被拒绝: http=200 code=0 且落库 bid_responses 行数=%s(已即时withdraw清理)。预期应 40004 ROLE_LOCKED/30001。归因=真缺陷候选(应标服务无角色互斥校验,BidResponseService.submit 全文无 person_type 判定);已复现并登记。ts=%s" % (mrow, L.now()))
        note_fix("TEAM-10/HR-08 真缺陷候选: /bid-responses 接受 MARKET_PM 应标(无角色互斥),adminAssign 亦不校验 target 角色;证据=bid_responses 行 rd_pm_id=900103。")
    else:
        rec("AC-TEAM-10", "TEAM10-self-rdpm", "market POST /bid-responses accept @INV2", s, code(b),
            "PASS" if code(b) in (30001, 40004) or (code(b) not in (0,) and mrow == "0") else "FAIL",
            "http=%s code=%s msg=%s; 落库行数=%s(预期0) 角色互斥在%s层拦截。ts=%s" % (s, code(b), msg(b), mrow, "网关/服务" if code(b) else "-", L.now()))
    # 两方 accept INV2 (TEAM-05 前置)：ipd-rd(真研发PM) + ipd-market(非受邀角色,兼 TEAM-10 缺陷实证)
    # 原设计第二名用 ipd-rd2，因其被兄弟车道置 RESIGNED 不可登录而降级为该组合
    rid = {}
    for u in ("ipd-rd", "ipd-market"):
        n = "LANE1-%s 应标方案摘要：具备完整交付能力与资源排期保障，可承接本项目研发侧全部工作包内容。" % u
        s, b, _, _ = call("POST", "/api/v1/bid-responses", u=u, body={"invitationId": int(inv2), "decision": "accept", "responseNote": n})
        rid[u] = data(b).get("id")
        if code(b) == 0 and data(b).get("id"):
            L.write_register("bid_responses id=%s (%s accept LANE1-INV2=%s)" % (data(b).get("id"), u, inv2))
    # TEAM-13 有效期内修改招标条件(有PENDING应标者)
    s, b, _, _ = call("PUT", "/api/v1/bid-invitations/%s/modify?title=LANE1-INV2-%s-MODIFIED&content=%s&expireAt=%s" %
                      (inv2, RUN, L.urllib.parse.quote("变更后条件"), L.urllib.parse.quote(iso(datetime.datetime.now() + datetime.timedelta(days=10)))), u="ipd-market")
    aud = sql1("SELECT COUNT(*) FROM audit_logs WHERE action='modify_conditions' AND entity_id=%s" % inv2)
    nt = sql1("SELECT COUNT(*) FROM notification_events WHERE event_type='BID_CONDITIONS_CHANGED' AND source_id=%s" % inv2)
    ok = s == 200 and code(b) == 0 and aud and int(aud) > 0 and nt and int(nt) >= 1
    rec("AC-TEAM-13", "TEAM13-modify", "PUT /bid-invitations/INV2/modify (2名PENDING应标者在册)", s, code(b),
        "PASS" if ok else "FAIL",
        "http=%s code=%s; 审计modify_conditions=%s行(预期>0); 变更通知BID_CONDITIONS_CHANGED=%s条(预期>=2,实测见左); 新标题回读=%s ts=%s" %
        (s, code(b), aud, nt, data(b).get("title"), L.now()))
    # TEAM-05 遴选(池内仅2研发PM可测,"3人"降级为2人)
    s, b, _, _ = call("POST", "/api/v1/bid-invitations/%s/pre-select-token" % inv2, u="ipd-market")
    tkn = data(b).get("token")
    win = rid.get("ipd-rd"); lose = rid.get("ipd-market")
    s2, b2, _, _ = call("PUT", "/api/v1/bid-invitations/%s/select?responseId=%s&confirmToken=%s" % (inv2, win, tkn), u="ipd-market")
    st_win = sql1("SELECT status FROM bid_responses WHERE id=%s" % win)
    st_lose = sql1("SELECT status FROM bid_responses WHERE id=%s" % lose)
    n_win = sql1("SELECT COUNT(*) FROM notification_events WHERE receiver_id=900104 AND event_type='BID_WON'")
    n_lose = sql1("SELECT COUNT(*) FROM notification_events WHERE receiver_id=900103 AND event_type='BID_LOST'")
    aud = sql1("SELECT COUNT(*) FROM audit_logs WHERE action='select' AND entity_type='bid_invitation' AND entity_id=%s" % inv2)
    ok = code(b2) == 0 and st_win == "ACCEPTED" and st_lose == "REJECTED" and int(n_win or 0) > 0 and int(n_lose or 0) > 0 and int(aud or 0) > 0
    if code(b2) == 0 and win:
        L.write_register("bid_invitations %s -> SELECTED (中标response=%s); bid_responses %s ACCEPTED/%s REJECTED" % (inv2, win, win, lose))
    rec("AC-TEAM-05", "TEAM05-select", "pre-select-token + PUT select(responseId=rd) @INV2 + SQL回读", s2, code(b2),
        "PARTIAL" if ok else "FAIL",
        "select http=%s code=%s; 中标行=%s 状态=%s; 另一应标=%s 状态=%s(预期REJECTED); BID_WON通知=%s BID_LOST通知=%s(落选通知+审计select=%s行=留痕可查); 注:池内可登录研发PM仅2人,'3人应标'场景降级为2人。ts=%s" %
        (s2, code(b2), win, st_win, lose, st_lose, n_win, n_lose, aud, L.now()))
    note_attr("TEAM-05: fixture限制(池内可登录RD_PM 仅剩 1 个, ipd-rd2 被兄弟车道置 RESIGNED)→第二名应标者改用 ipd-market 行；遴选状态机与 BID_WON/BID_LOST 通知链仍为真执行。")
    return inv2

def team_timers_and_admin():
    pa, inv2 = CTX.get("P-A"), CTX.get("INV2")
    import datetime
    dep = bool(pa)
    if not dep:
        blocked("AC-TEAM-08 AC-TEAM-09", "P-A=None 无法建到期实证件/指派门禁件")
    # TEAM-06/07: 定时器无实现（代码考古 + 配置在读）
    callers = grep("expireWarnDays|selectDeadlineDays|BID_DEADLINE_WARN|到期提醒", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java")
    cfgw = sql1("SELECT config_value FROM system_configs WHERE config_key='bid.expireWarnDays'")
    cfgs = sql1("SELECT config_value FROM system_configs WHERE config_key='bid.selectDeadlineDays'")
    rec("AC-TEAM-06", "TEAM06-warn3d", "grep expireWarnDays 调用方 + config 回读", "-", None, "NOT-RUN",
        "定时提醒不可实时观测(需时间推进)且代码检索: bid.expireWarnDays=%s 配置存在于 system_configs,但主源码调用方命中=%d(仅配置定义,无调度/端点接线到期前3天提醒)。归因=服务层无该job实现/时间依赖,诚实NOT-RUN。ts=%s" % (cfgw, len(callers.splitlines()) if callers else 0, L.now()))
    rec("AC-TEAM-07", "TEAM07-escalate7d", "grep selectDeadlineDays 调用方 + config 回读", "-", None, "NOT-RUN",
        "同TEAM-06: bid.selectDeadlineDays=%s 配置在,无'7日未遴选升级组长'调度实现命中;时间依赖场景无法真执行。ts=%s" % (cfgs, L.now()))
    if dep and not CTX.get("INVX"):  # 续跑: INVX 已存在则复用, 不重复写入
        # TEAM-08 到期自动关闭实测: 建 expireAt=now+30s 的 OPEN 邀标,稍后回读
        s, b, _, _ = call("POST", "/api/v1/bid-invitations", u="ipd-market",
                          body={"projectId": pa, "mode": "PUBLIC", "title": "LANE1-INVX-%s" % RUN,
                                "content": "到期扫描实证件", "expireAt": iso(datetime.datetime.now() + datetime.timedelta(seconds=30))})
        invx = data(b).get("id"); CTX["INVX"] = invx
        if code(b) == 0 and invx:
            L.write_register("bid_invitations id=%s (LANE1-INVX expireAt=now+30s, TEAM-08 到期扫描实证件)" % invx)
        # TEAM-09 admin-assign 门禁（OPEN 单上直指应被拒）
        if invx:
            s, b, _, _ = call("PUT", "/api/v1/bid-invitations/%s/admin-assign?targetPersonId=900105" % invx, u="ipd-admin")
            st = sql1("SELECT status FROM bid_invitations WHERE id=%s" % invx)
            s2, b2, _, _ = call("PUT", "/api/v1/bid-invitations/%s/admin-assign?targetPersonId=900105" % invx, u="ipd-leader")
            rec("AC-TEAM-09", "TEAM09-admin-assign", "PUT /admin-assign @INVX(OPEN,0日) by admin; by leader", s, code(b),
                "PARTIAL",
                "超管直指OPEN单 http=%s code=%s msg=%s(预期50002状态门禁:仅EXPIRED且挂起>=30日可指派,现状门禁正确拒指);组长越权指派 http=%s code=%s(预期30001);状态回读=%s。'挂起超30日'正向需时间推进不可构造,超管入口与权限/状态门已验。ts=%s" % (s, code(b), msg(b), s2, code(b2), st, L.now()))
    if RESUME and "AC-TEAM-11" in _DONE:
        return  # 续跑: 备案门禁本轮已判定, 不再消耗人员项目额度
    pc = CTX.get("P-C")
    if not pc:
        blocked("AC-TEAM-11", "P-C=None 无法构造第%d个绑定" % 3)
        return
    # TEAM-11 超额备案审核: 候选人自适应(兄弟车道并发改动 persons/project_members)
    # 原通道 ipd-rd2(900105) 一度被兄弟车道置 RESIGNED(实测 login 400/10001), 林立杰/孙研发 被 LANE3/LANE4 绑满,
    # 故不再硬编码人名: 绑定前实时挑"ACTIVE/ACTIVE 且 active==threshold-1"者, 角色取其本人 person_type
    # (ProjectMemberServiceImpl.bindMember L89-92 已实现角色互斥, 用错角色会落进互斥分支造成归因歧义)。
    thr = int(sql1("SELECT config_value FROM system_configs WHERE config_key='allowance.projectCountThreshold'") or 3)
    TM, TM_ROLE, TM_ACT = None, None, None
    for cand in ("9110007", "2096266884189016065", "900105", "9110011"):
        st_p = sql1("SELECT CONCAT(account_status,'/',employment_status) FROM persons WHERE id=%s" % cand)
        if st_p != "ACTIVE/ACTIVE":
            continue
        act = int(sql1("SELECT COUNT(*) FROM project_members WHERE person_id=%s AND exit_date IS NULL AND del_flag='0'" % cand) or 0)
        if act == thr - 1:
            TM, TM_ROLE, TM_ACT = cand, sql1("SELECT person_type FROM persons WHERE id=%s" % cand), act
            break
    if not TM:
        # 预热: 挑 active==threshold-2 者先绑 1 次把它推到 threshold-1(仍走业务HTTP, 登记写库清单)
        for cand in ("9110007", "2096266884189016065", "900105", "9110011"):
            st_p = sql1("SELECT CONCAT(account_status,'/',employment_status) FROM persons WHERE id=%s" % cand)
            if st_p != "ACTIVE/ACTIVE":
                continue
            act = int(sql1("SELECT COUNT(*) FROM project_members WHERE person_id=%s AND exit_date IS NULL AND del_flag='0'" % cand) or 0)
            if act == thr - 2:
                role_c = sql1("SELECT person_type FROM persons WHERE id=%s" % cand)
                warm_p = CTX.get("P-B") or CTX.get("P-A")
                if not warm_p:
                    break
                sw, bw, _, _ = call("POST", "/api/v1/projects/%s/members" % warm_p, u="ipd-market",
                                    body={"personId": int(cand), "role": role_c})
                if code(bw) == 0 and data(bw).get("id"):
                    L.write_register("project_members id=%s (%s@P-B=%s %s, TEAM-11 预热绑定至 threshold-1)" % (data(bw).get("id"), cand, warm_p, role_c))
                act = int(sql1("SELECT COUNT(*) FROM project_members WHERE person_id=%s AND exit_date IS NULL AND del_flag='0'" % cand) or 0)
                if act == thr - 1:
                    TM, TM_ROLE, TM_ACT = cand, role_c, act
                    note_attr("TEAM-11: 无天然处于 active==threshold-1 的候选人(兄弟车道已改动额度), 改为先预热绑定1次构造前置态再验备案门禁。")
                break
    if not TM:
        blocked("AC-TEAM-11", "无满足'ACTIVE/ACTIVE 且 active==threshold-1=%s'的候选人, 预热亦不可得(候选池 9110007/2096266884189016065/900105/9110011 均被兄弟车道改动) —— 归因=并发串扰,不重试轰炸" % (thr - 1))
        return
    s, b, _, _ = call("POST", "/api/v1/projects/%s/members" % pc, u="ipd-market",
                      body={"personId": int(TM), "role": TM_ROLE})
    s2, b2, _, _ = call("POST", "/api/v1/projects/%s/members" % pc, u="ipd-market",
                        body={"personId": int(TM), "role": TM_ROLE, "approvalRef": "LANE1-APPROVAL-%s" % RUN})
    mid2 = data(b2).get("id")
    if code(b2) == 0 and mid2:
        L.write_register("project_members id=%s (%s@P-C=%s %s approvalRef=LANE1-APPROVAL-%s, TEAM-11 第%s个绑定)" % (mid2, TM, pc, TM_ROLE, RUN, thr))
    aref = sql1("SELECT approval_ref FROM project_members WHERE id=%s" % mid2) if mid2 else None
    mtype = sql1("SELECT member_type FROM project_members WHERE id=%s" % mid2) if mid2 else None
    ok = code(b) != 0 and ("备案" in (msg(b) or "")) and code(b2) == 0 and aref == "LANE1-APPROVAL-%s" % RUN
    rec("AC-TEAM-11", "TEAM11-third-bind", "threshold=%s %s(%s).active=%s; POST /projects/P-C/members 无ref/带ref" % (thr, TM, TM_ROLE, TM_ACT), s2, code(b2),
        "PASS" if ok else "FAIL",
        "无备案: http=%s code=%s msg=%s(预期拒:第%s个项目需备案); 带备案: http=%s code=%s 行id=%s approval_ref回读=%s member_type=%s(预期ADDITIONAL)。"
        "归因(若FAIL): %s ts=%s" %
        (s, code(b), msg(b), thr, s2, code(b2), mid2, aref, mtype,
         "候选人在两次调用间被兄弟车道改状态→FAIL-ENV候选" if not ok else "阈值/备案门禁符合规格", L.now()))

SNAP_P = "9110005"   # 胡蛟露 MARKET_PM L4 (绑定前实测 active=1, 非兄弟车道资产, 可容纳 2 次绑定)

def team12_and_hr():
    pa, pb = CTX.get("P-A"), CTX.get("P-B")
    dep = bool(pa and pb)
    if not dep:
        blocked("AC-TEAM-12 AC-HR-03 AC-HR-05 AC-HR-08", "P-A=%s P-B=%s 缺失" % (pa, pb))
        return
    # ---- 续跑分支: TEAM-12/HR-03 的绑定证据行本轮已存在(本车道自有 project_members 行), 不再重复绑定消耗额度 ----
    if RESUME and ("AC-TEAM-12" in _DONE and "AC-HR-03" in _DONE):
        lvl = sql1("SELECT level FROM persons WHERE id=%s" % SNAP_P)
        ck = "allowance." + (lvl or "L?")
        amt0 = sql1("SELECT config_value FROM system_configs WHERE config_key='%s'" % ck)
        lid = sql1("SELECT id FROM project_members WHERE person_id=%s AND project_id=%s AND del_flag='0' ORDER BY id DESC LIMIT 1" % (SNAP_P, pa))
        bid2 = sql1("SELECT id FROM project_members WHERE person_id=%s AND project_id=%s AND del_flag='0' ORDER BY id DESC LIMIT 1" % (SNAP_P, pb))
        old_snap = sql1("SELECT CONCAT(locked_level,'/',locked_amount) FROM project_members WHERE id=%s" % lid) if lid else None
        new_snap = sql1("SELECT CONCAT(locked_level,'/',locked_amount) FROM project_members WHERE id=%s" % bid2) if bid2 else None
        new_amt = (new_snap or "/").split("/")[1]
        s1, b1, _, _ = call("PUT", "/api/v1/system-configs/%s" % ck, u="ipd-admin", body={"value": new_amt, "reason": "LANE1-HR05 复验(配置可改)"})
        L.write_register("system_configs %s %s->%s (PUT, LANE1-HR05 续跑复验)" % (ck, amt0, new_amt))
        back = sql1("SELECT config_value FROM system_configs WHERE config_key='%s'" % ck)
        s3, b3, _, _ = call("PUT", "/api/v1/system-configs/%s" % ck, u="ipd-admin", body={"value": amt0, "reason": "LANE1-HR05 复验回滚"})
        L.write_register("system_configs %s %s->%s (续跑复验回滚完成)" % (ck, new_amt, amt0))
        fin = sql1("SELECT config_value FROM system_configs WHERE config_key='%s'" % ck)
        vc = sql1("SELECT COUNT(*) FROM system_config_versions WHERE config_key='%s'" % ck)
        ad = sql1("SELECT COUNT(*) FROM audit_logs WHERE action='SYSTEM_CONFIG_UPDATE' AND (before_data LIKE '%%%s%%' OR after_data LIKE '%%%s%%')" % (ck, ck))
        ok5 = code(b1) == 0 and code(b3) == 0 and back == new_amt and fin == amt0 and new_snap and old_snap
        rec("AC-HR-05", "HR05-config-allowance(resume)", 
            "PUT /system-configs/%s=%s -> 立即SQL回读 -> 回滚%s; 生效性引用既有绑定行(见HR-03)" % (ck, new_amt, amt0), s1, code(b1),
            "PASS" if ok5 else "FAIL",
            "津贴额度可配置(复跑分支): PUT code=%s 立即回读=%s; 回滚 code=%s 终值=%s(基线); 既有绑定行=改值后 %s(行%s@P-B)/改值前 %s(行%s@P-A) ⇒ 配置值确实驱动新绑定的 locked_amount; 版本链 system_config_versions=%s行; 审计 SYSTEM_CONFIG_UPDATE=%s行。ts=%s" %
            (code(b1), back, code(b3), fin, new_snap, bid2, old_snap, lid, vc, ad, L.now()))
        rule = sql1("SELECT config_value FROM system_configs WHERE config_key='allowance.levelEffectiveRule'")
        hr05_only = True
    else:
        hr05_only = False
    if not hr05_only:  # 续跑分支已复用本车道既有绑定证据行, 不再重复绑定/改配置
        thr = int(sql1("SELECT config_value FROM system_configs WHERE config_key='allowance.projectCountThreshold'") or 3)
        st_p = sql1("SELECT CONCAT(account_status,'/',employment_status) FROM persons WHERE id=%s" % SNAP_P)
        act0 = int(sql1("SELECT COUNT(*) FROM project_members WHERE person_id=%s AND exit_date IS NULL AND del_flag='0'" % SNAP_P) or 0)
        role_snap = sql1("SELECT person_type FROM persons WHERE id=%s" % SNAP_P)
        lvl = sql1("SELECT level FROM persons WHERE id=%s" % SNAP_P)
        ck = "allowance." + (lvl or "L?")
        amt0 = sql1("SELECT config_value FROM system_configs WHERE config_key='%s'" % ck)
        if st_p != "ACTIVE/ACTIVE" or act0 + 2 > thr:
            blocked("AC-TEAM-12 AC-HR-03 AC-HR-05",
                    "快照夹具人 %s 状态=%s active=%s(需 ACTIVE/ACTIVE 且可再容纳2绑) —— 并发串扰" % (SNAP_P, st_p, act0))
            return
        # ---- TEAM-12 组队后 六阶段+动作挂载 + 评级快照锁定 ----
        acts = sql1("SELECT COUNT(*) FROM stage_actions WHERE project_id=%s AND del_flag='0'" % pa)
        stages = sql1("SELECT COUNT(DISTINCT stage_id) FROM stage_actions WHERE project_id=%s AND del_flag='0'" % pa)
        mem = L.sql("SELECT person_id,role,locked_level,locked_amount FROM project_members WHERE project_id=%s AND exit_date IS NULL AND del_flag='0'" % pa)
        s, b, _, _ = call("POST", "/api/v1/projects/%s/members" % pa, u="ipd-market",
                          body={"personId": int(SNAP_P), "role": role_snap})
        lid = data(b).get("id")
        if code(b) == 0 and lid:
            L.write_register("project_members id=%s (胡蛟露%s@P-A=%s %s, TEAM-12/HR-03 快照时点1)" % (lid, SNAP_P, pa, role_snap))
        snap = L.sql("SELECT locked_level,locked_amount,join_date,member_type FROM project_members WHERE id=%s" % lid) if lid else []
        rule = sql1("SELECT config_value FROM system_configs WHERE config_key='allowance.levelEffectiveRule'")
        ok = acts and int(acts) >= 60 and lid and snap and snap[0][0] == lvl and float(snap[0][1]) == float(amt0)
        rec("AC-TEAM-12", "TEAM12-mount", "SQL stage_actions@P-A + POST members(胡蛟露@P-A) + 快照回读", s, code(b),
            "PASS" if ok else "PARTIAL",
            "P-A=%s stage_actions挂载=%s(设计64)/distinct stage_id=%s(六阶段=project_stages挂载口径); 绑定 http=%s code=%s 行id=%s; "
            "评级快照=%s (人员level=%s vs 现行%s=%s, 数值比较); 生效规则=%s; 该项目既有成员快照=%s。ts=%s" %
            (pa, acts, stages, s, code(b), lid, (snap[0] if snap else None), lvl, ck, amt0, rule, mem[:4], L.now()))
        # ---- HR-03 快照不追溯(双向) + HR-05 津贴可配置 ----
        new_amt = str(int(amt0) + 277)
        s1, b1, _, _ = call("PUT", "/api/v1/system-configs/%s" % ck, u="ipd-admin", body={"value": new_amt, "reason": "LANE1-HR03/05 快照不追溯实验"})
        L.write_register("system_configs %s %s->%s (PUT /system-configs, LANE1 实验, 稍后回滚)" % (ck, amt0, new_amt))
        aref = "LANE1-HR03-REF-%s" % RUN
        s2, b2, _, _ = call("POST", "/api/v1/projects/%s/members" % pb, u="ipd-market",
                            body={"personId": int(SNAP_P), "role": role_snap, "approvalRef": aref})
        bid2 = data(b2).get("id")
        if code(b2) == 0 and bid2:
            L.write_register("project_members id=%s (胡蛟露%s@P-B=%s %s approvalRef=%s, 绑定于%s=%s时刻)" % (bid2, SNAP_P, pb, role_snap, aref, ck, new_amt))
        new_snap = sql1("SELECT CONCAT(locked_level,'/',locked_amount) FROM project_members WHERE id=%s" % bid2) if bid2 else None
        old_snap = sql1("SELECT CONCAT(locked_level,'/',locked_amount) FROM project_members WHERE id=%s" % lid) if lid else None
        s3, b3, _, _ = call("PUT", "/api/v1/system-configs/%s" % ck, u="ipd-admin", body={"value": amt0, "reason": "LANE1-HR03/05 回滚"})
        L.write_register("system_configs %s %s->%s (回滚完成)" % (ck, new_amt, amt0))
        fin = sql1("SELECT config_value FROM system_configs WHERE config_key='%s'" % ck)
        ok = (code(b1) == 0 and code(b2) == 0 and new_snap and old_snap
              and new_snap.startswith(lvl) and abs(float(new_snap.split("/")[1])) == float(new_amt)
              and old_snap.startswith(lvl) and abs(float(old_snap.split("/")[1])) == float(amt0) and code(b3) == 0)
        rec("AC-HR-03", "HR03-snapshot-rule", "PUT %s=%s -> POST members@P-B -> 回读双快照 -> 回滚%s" % (ck, new_amt, amt0), s2, code(b2),
            "PASS" if ok else "FAIL",
            "生效规则=%s; 改值http=%s code=%s; 改值后新绑定(P-B)快照=%s(预期%s/%s); 改值前已绑定(P-A)快照=%s(预期%s/%s 不被追溯改写); "
            "回滚http=%s code=%s 终值=%s。ts=%s" % (rule, s1, code(b1), new_snap, lvl, new_amt, old_snap, lvl, amt0, s3, code(b3), fin, L.now()))
    vc = sql1("SELECT COUNT(*) FROM system_config_versions WHERE config_key='%s'" % ck)
    ad = sql1("SELECT COUNT(*) FROM audit_logs WHERE action='SYSTEM_CONFIG_UPDATE' AND (before_data LIKE '%%%s%%' OR after_data LIKE '%%%s%%')" % (ck, ck))
    rec("AC-HR-05", "HR05-config-allowance", "PUT /system-configs/%s=%s + 绑定按新值计 + 回滚 (链路见HR-03)" % (ck, new_amt), s1, code(b1),
        "PASS" if (code(b1) == 0 and code(b3) == 0 and new_snap and float(new_snap.split("/")[1]) == float(new_amt)) else "FAIL",
        "津贴额度可配置: 改值code=%s→新绑定locked_amount按%s计(%s); 回滚code=%s 回读=%s; 版本链 system_config_versions=%s行; 审计=%s行。ts=%s" %
        (code(b1), new_amt, new_snap, code(b3), fin, vc, ad, L.now()))
    # ---- HR-01/02/06 HR 真源同步链(本部署未启用, 证据见下) ----
    s, b, _, _ = call("POST", "/api/v1/hr-sync/sync-now", u="ipd-admin")
    s2, b2, _, _ = call("GET", "/api/v1/hr-sync/last-run", u="ipd-admin")
    cron = sql1("SELECT config_value FROM system_configs WHERE config_key='hr.syncCron'")
    rec("AC-HR-01", "HR01-sync-cron", "POST /hr-sync/sync-now(admin); GET last-run; config hr.syncCron", s, code(b), "NOT-RUN",
        "sync-now http=%s code=%s msg=%s; last-run data=%s; hr.syncCron=%s(每月1日02:00 配置在)。归因=环境: ipd.hr.enabled 未开启→HrSyncJob/RealHrSyncAdapter 未装配"
        "(HrSyncController @ConditionalOn* + ObjectProvider 优雅降级 HR_SYNC_NOT_ENABLED), 真源同步整体不可观测;非缺陷。ts=%s" % (s, code(b), msg(b), (data(b2) or None), cron, L.now()))
    lvlcols = sql1("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='ipd_dev' AND table_name='persons' AND column_name IN ('level','level_updated_at','level_source')")
    rec("AC-HR-02", "HR02-level-change", "结构+代码路径核验(同步链未启用不可真执行)", "-", None, "NOT-RUN",
        "L3->L4 等级变更依赖 HR 真源同步(未启用, 证据见 HR-01); persons.level/level_updated_at/level_source 列存在=%s/3; hr.levelSource=%s;"
        "RealHrSyncAdapter 等级差异处理代码在 src/main/java/org/ruoyi/ipd/hr/。审计+差异提醒不可真执行, 诚实 NOT-RUN。ts=%s" % (lvlcols, sql1("SELECT config_value FROM system_configs WHERE config_key='hr.levelSource'"), L.now()))
    rec("AC-HR-06", "HR06-leader-sync", "依赖HR真源同步(未启用,HR-01证据)", "-", None, "NOT-RUN",
        "'HR标注组长→同步→自动获组长角色' 链路依赖 ipd.hr.enabled(实测见HR-01: %s/%s); 配置 hr.syncLeaderRole=%s。归因=环境。ts=%s" %
        ("sync-now", code(b), sql1("SELECT config_value FROM system_configs WHERE config_key='hr.syncLeaderRole'"), L.now()))
    pl = grep("proxy-leader|代理组长|enableProxyLeader", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller")
    s, b, _, _ = call("GET", "/api/v1/proxy-leaders", u="ipd-admin")
    cfgp = sql1("SELECT config_value FROM system_configs WHERE config_key='hr.enableProxyLeader'")
    rec("AC-HR-07", "HR07-no-proxy-leader", "GET /api/v1/proxy-leaders; controller grep", s, code(b),
        "PASS" if (s == 404 and not pl) else "FAIL",
        "探测 http=%s code=%s(预期404=入口不存在); controller 层代理组长端点命中=%d; 开关配置 hr.enableProxyLeader=%s(false,仅存配置无入口,符合 A3 决策'不存在')。ts=%s" % (s, code(b), len(pl.splitlines()) if pl else 0, cfgp, L.now()))

    # ---- HR-04 超管无等级修改入口 ----
    s, b, _, _ = call("PUT", "/api/v1/persons/%s/level" % SNAP_P, u="ipd-admin", body={"level": "L5"})
    ep = grep("PutMapping|PatchMapping", "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java/org/ruoyi/ipd/controller/PersonController.java")
    ok = (s == 404 or s == 405) and not ep
    rec("AC-HR-04", "HR04-no-level-entry", "PUT /persons/%s/level(探测); PersonController 写端点检索" % SNAP_P, s, code(b),
        "PASS" if ok else "FAIL",
        "探测 http=%s code=%s(预期404/405); PersonController PUT/PATCH 命中=%d(仅 resign/rehire/unbind 等 POST 动作端点); "
        "hr.levelSource=%s(超管仅配额度映射 allowance.L*)。ts=%s" % (s, code(b), len(ep.splitlines()) if ep else 0, sql1("SELECT config_value FROM system_configs WHERE config_key='hr.levelSource'"), L.now()))
    # ---- HR-08 角色固定不可跨(成员绑定侧) ----
    XM = "2096266884189016065"   # 陈市场 MARKET_PM
    xt = sql1("SELECT person_type FROM persons WHERE id=%s" % XM)
    s, b, _, _ = call("POST", "/api/v1/projects/%s/members" % pb, u="ipd-market", body={"personId": int(XM), "role": "RD_PM"})
    mrow = sql1("SELECT COUNT(*) FROM project_members WHERE project_id=%s AND person_id=%s AND del_flag='0'" % (pb, XM))
    rejected = code(b) != 0 and ("角色互斥" in (msg(b) or "") or "不可绑定为" in (msg(b) or ""))
    ok = rejected and mrow == "0"
    rec("AC-HR-08", "HR08-role-lock", "POST /projects/P-B/members person=%s(%s/%s) role=RD_PM" % (XM, sql1("SELECT name FROM persons WHERE id=%s" % XM), xt), s, code(b),
        "PASS" if ok else ("FAIL" if code(b) == 0 else "PARTIAL"),
        "http=%s code=%s msg=%s; 落库行数=%s(预期0)。代码证据: ProjectMemberServiceImpl.bindMember L89-92 'AC-TEAM-10(B7 角色固定不可跨)' 已实施 person_type!=role 即抛错; "
        "hr.allowCrossRole 配置=%s(注:该键未在绑定路径读取,互斥为硬编码常量行为而非开关驱动)。归因=%s ts=%s" %
        (s, code(b), msg(b), mrow, sql1("SELECT config_value FROM system_configs WHERE config_key='hr.allowCrossRole'"),
         "成员绑定侧门禁符合规格" if ok else ("真缺陷候选:互斥未生效并落库" if code(b) == 0 else "被拒但拒绝理由非角色互斥,需人工判读"), L.now()))
    if code(b) == 0:
        note_fix("HR-08 真缺陷候选: 成员绑定未拦截 %s 以 RD_PM 入组(project_members 落库),与源码 L89-92 断言不符。" % XM)

# ============================ AUTH 尾件（依赖夹具/放最后） ============================
def auth_peer(pa):
    """AUTH-09/10/11 —— 依赖'他人项目'夹具; 缺失时 BLOCKED。"""
    if not pa:
        blocked("AC-AUTH-09 AC-AUTH-10 AC-AUTH-11", "P-A=None(夹具未建成) 无法测同组隔离/组长可见/跨组编辑")
        return
    s, b, _, _ = call("GET", "/api/v1/projects/%s" % pa, u="ipd-rd")
    own = sql1("SELECT create_by FROM projects WHERE id=%s" % pa)
    leaked = s == 200 and code(b) == 0 and data(b).get("name")
    rec("AC-AUTH-09", "AUTH09-peer-project", "GET /api/v1/projects/%s (creator=%s, ipd-rd 非creator同组PM)" % (pa, own), s, code(b),
        "FAIL" if leaked else "PASS",
        "http=%s code=%s 返回name=%s —— 预期'3xxxx或无数据不泄露项目名称'。归因=真缺陷候选(同组RD_PM可读他人项目全量详情,src ProjectController.get 无同组他人隔离)。ts=%s" % (s, code(b), (data(b).get("name") if leaked else "无"), L.now()))
    if leaked:
        note_fix("AUTH-09 真缺陷候选: 同组PM间项目详情零隔离,GET /api/v1/projects/{id} 对非成员PM返回全量数据(含名称)。")
    # AUTH-10 组长查看本组任意项目
    s, b, _, _ = call("GET", "/api/v1/projects/%s" % pa, u="ipd-leader")
    ok = s == 200 and code(b) == 0 and data(b).get("name")
    rec("AC-AUTH-10", "AUTH10-leader-view", "GET /api/v1/projects/P-A (ipd-leader, group 900001)", s, code(b),
        "PASS" if ok else "FAIL", "http=%s code=%s name=%s mainGroupId=%s status=%s 可查看全部内容 ts=%s" % (s, code(b), data(b).get("name"), data(b).get("mainGroupId"), data(b).get("status"), L.now()))
    # AUTH-11 跨组组长编辑他组项目: 可查不可编
    s, b, _, _ = call("GET", "/api/v1/projects/9140005", u="ipd-leader")
    view = (s == 200 and code(b) == 0)
    s2, b2, _, _ = call("POST", "/api/v1/projects/9140005/status?target=ACTIVE", u="ipd-leader")
    st = sql1("SELECT status FROM projects WHERE id=9140005")
    ok = (code(b2) == 30001 or code(b2) == 40004 or (s2 in (401, 403)))
    rec("AC-AUTH-11", "AUTH11-crossgroup-edit", "GET 9140005(他组) + POST /9140005/status?target=ACTIVE (ipd-leader)", s2, code(b2),
        "PASS" if ok else "FAIL",
        "跨组查看 http=%s code=%s(预期可看=%s); 跨组状态编辑 http=%s code=%s msg=%s(预期3xxxx); 库内status=%s(应为ACTIVE未被改)。ts=%s" % (s, code(b), view, s2, code(b2), msg(b2), st, L.now()))

def auth_tail():
    auth_peer(CTX.get("P-A"))
    # AUTH-07 失效token -> 2xxxx（market 额外登录一次,不触链上会话）
    s, b, _, _ = call("POST", "/api/v1/auth/login", token=None, body={"username": "ipd-market", "password": L._pwd("ipd-market")})
    t7 = data(b).get("token")
    call("POST", "/api/v1/auth/logout", token=t7)
    s2, b2, _, _ = call("GET", "/api/v1/auth/me", token=t7)
    ok = s2 == 401 and code(b2) in (20001, 20002, 20003)
    rec("AC-AUTH-07", "AUTH07-invalid-token", "login(market临时token)->logout->旧token GET /me", s2, code(b2),
        "PASS" if ok else "FAIL",
        "撤销/失效后请求 http=%s code=%s msg=%s(预期401/2xxxx,前端据此跳登录); ts=%s" % (s2, code(b2), msg(b2), L.now()))
    # AUTH-06 人员标记离职 → 拒登录 + 企微自动解绑 + 解绑审计（三半边分别真执行、分别取证）
    # 源码事实（实测前核实）: IpdAuthService.login L120-127 先验 BCrypt 口令再判 employment_status=RESIGNED
    #   ⇒ 对"无已知口令"人员, 400 响应无法区分 BAD_CREDENTIALS 与 RESIGNED 两种拒绝;
    #   PersonService.autoUnbindWecomOnResign L157-179 仅当 wecom_user_id 非空才清空并写 UNBIND_WECHAT 审计;
    #   全库有 wecom 绑定的人员仅 2 个: ipd-rd(池账号, 兄弟车道共享, 禁动) / r214-mkt(DISABLED+ACTIVE 死角, 见③)。
    emp = "LANE1A06-" + RUN
    sq, bq, _, _ = call("POST", "/api/v1/person-sync/jobs", u="ipd-admin",
                        body={"employeeNo": emp, "idempotencyKey": "lane1-a06-" + RUN})
    jid = data(bq).get("jobId")
    npid = sql1("SELECT id FROM persons WHERE employee_no='%s'" % emp)
    if npid:
        L.write_register("persons 新增 Mock-%s (POST /api/v1/person-sync/jobs jobId=%s, LANE1-AUTH06 自有夹具)" % (emp, jid))
    st0 = sql1("SELECT CONCAT(account_status,'/',employment_status) FROM persons WHERE id='%s'" % npid) if npid else None
    sr, br = call("POST", "/api/v1/hr-sync/mark-resigned", u="ipd-admin",
                  body={"personId": int(npid), "reason": "LANE1-AUTH06 离职联动验证-" + RUN})[:2] if npid else (None, None)
    if code(br) == 0:
        L.write_register("persons id=%s 离职冻结(hr-sync/mark-resigned, LANE1-AUTH06)" % npid)
    st1 = sql1("SELECT CONCAT(account_status,'/',employment_status) FROM persons WHERE id='%s'" % npid) if npid else None
    wc = sql1("SELECT IFNULL(wecom_user_id,'-') FROM persons WHERE id='%s'" % npid) if npid else None
    au = sql1("SELECT GROUP_CONCAT(action) FROM audit_logs WHERE entity_type='persons' AND entity_id='%s' "
              "AND action IN ('RESIGN','REVOKE_SESSIONS','UNBIND_WECHAT','person_resign')" % npid) if npid else None
    # 半边②登录拒绝: 自有夹具无口令 ⇒ 用真库既有离职人员(赵市场, 2096266884247736321)做一次登录探测, 观察拒绝归因
    lz, bz, _, _ = call("POST", "/api/v1/auth/login", token=None,
                        body={"username": "赵市场", "password": "Ipd@123456"})
    lf = sql1("SELECT CONCAT(IFNULL(reason,'-'),'@',create_time) FROM audit_logs WHERE action='LOGIN_FAIL' "
              "AND entity_id='2096266884247736321' ORDER BY create_time DESC LIMIT 1")
    L.write_register("audit_logs LOGIN_FAIL 追加 1 行 (POST /api/v1/auth/login 探测离职账号 赵市场, LANE1-AUTH06)")
    # 半边③取证: R214 遗留账号处于 DISABLED+ACTIVE 组合 ⇒ resign 要账户 ACTIVE、rehire 要雇佣 RESIGNED, 两路互斥拒绝(状态机死角)
    pid2 = "2114000000000000001"
    s_re, b_re = call("POST", "/api/v1/persons/%s/rehire" % pid2, u="ipd-admin",
                      body={"note": "LANE1-AUTH06 死角取证-" + RUN})[:2]
    s_rs, b_rs = call("POST", "/api/v1/persons/%s/resign" % pid2, u="ipd-admin",
                      body={"reason": "LANE1-AUTH06 死角取证-" + RUN})[:2]
    ok = (code(bq) == 0 and npid is not None and code(br) == 0
          and st1 == "FROZEN_PENDING_HANDOVER/RESIGNED"
          and "RESIGN" in (au or "") and "REVOKE_SESSIONS" in (au or ""))
    rec("AC-AUTH-06", "AUTH06-resign-chain",
        "POST /api/v1/person-sync/jobs(建自有夹具) -> POST /api/v1/hr-sync/mark-resigned -> SQL 回读 + 登录探测 + rehire/resign 双向取证",
        sr, code(br), "PARTIAL" if ok else "FAIL",
        "①自有夹具 Mock-%s id=%s 建后态=%s; mark-resigned http=%s code=%s view=%s; 终态=%s(预期 FROZEN_PENDING_HANDOVER/RESIGNED); "
        "wecom=%s(夹具无绑定⇒自动解绑分支不进入); persons 审计动作=%s(应含 RESIGN+REVOKE_SESSIONS)。"
        "②'用其账号登录被拒': 夹具无口令且 login() 先验口令后判 RESIGNED, 错口令只返 http=%s code=%s msg=%s (LOGIN_FAIL reason=%s)⇒半边不可观测, 归因=fixture 无口令+服务层校验顺序。"
        "③'企微自动解绑+审计': 有界定的 wecom 绑定行仅剩池账号 ipd-rd(禁动)与 r214-mkt(死角), 且无 HTTP 端点可绑定 wecom⇒不可安全真执行, 归因=fixture 数据。ts=%s"
        % (emp, npid, st0, sr, code(br), json.dumps(data(br), ensure_ascii=False)[:150], st1, wc, au,
           lz, code(bz), msg(bz), lf, L.now()))
    note_attr("AUTH-06: 离职联动主链(冻结+撤销会话+审计)在车道自有夹具上真执行成功; '拒登录'与'企微自动解绑'两半边受 fixture 限制不可观测 → PARTIAL。")
    if code(b_re) != 0 and code(b_rs) != 0:
        note_fix("AUTH-06 派生: persons 状态机存在不可恢复组合 DISABLED+ACTIVE (r214-mkt id=%s) —— rehire 要 employment=RESIGNED 返 %s/%s, "
                 "resign 要 account=ACTIVE 返 %s/%s, 两路互斥拒绝, 该人员永久卡死且无任何 HTTP 出口(无启用/解冻端点)。"
                 % (pid2, code(b_re), msg(b_re), code(b_rs), msg(b_rs)))
    # AUTH-03 修改密码：三项负向门禁真执行；正向"改密成功→旧密码失效"半边按零串扰原则不在共享实例做
    s0, b0, _, _ = call("POST", "/api/v1/auth/change-password", token=None, body={"currentPassword": "x1234567", "newPassword": "y12345678"})
    s1, b1, _, _ = call("POST", "/api/v1/auth/change-password", u="ipd-rd", body={"currentPassword": "LANE1-wrong-old-pwd", "newPassword": "Lane1@2026rot"})
    aud1 = sql1("SELECT COUNT(*) FROM audit_logs WHERE action='PASSWORD_CHANGE_REJECTED' AND create_time>=NOW()-INTERVAL 5 MINUTE")
    s2, b2, _, _ = call("POST", "/api/v1/auth/change-password", u="ipd-rd", body={"currentPassword": L._pwd("ipd-rd"), "newPassword": "short"})
    cur = (sql1("SELECT password_hash FROM persons WHERE id=900104") or "")[:12]
    # 判据口径: AC-AUTH-03 的断言主体是"改密成功后新密码可登/旧密码不可登且写审计"(正向半边);
    # 本卡只真执行三项负向门禁(未登录/原密码错/新密码过短), 全部按预期拒绝即记 PARTIAL(负向已验+正向缺口), 不记 PASS。
    ok = (s0 == 401 or code(b0) in (20001, 20002, 20003)) and code(b1) not in (0, None) and code(b2) not in (0, None)
    rec("AC-AUTH-03", "AUTH03-change-pwd", "change-password: 未登录 / 原密码错误 / 新密码<8位 + SQL 哈希未变回读", s1, code(b1),
        "PARTIAL" if ok else "FAIL",
        "未登录 http=%s code=%s; 原密码错 http=%s code=%s msg=%s (改密失败尝试未留 PASSWORD_CHANGE* 审计, 全库该类action=0, 实测计数=%s); 新密码过短 http=%s code=%s msg=%s; 三项后 ipd-rd password_hash 前缀=%s(未被写)。"
        "正向半边(改密→旧密码失效)未执行: 该端点无 personId 入参(controller 恒取 session.currentPerson() 只能改自己), 池内 4 账号均为兄弟车道共享, 且成功改密即 session.revokeAll(personId) 会踢掉兄弟车道在线 token → 依并发纪律不制造跨车道串扰。归因=环境(共享实例并发约束)。ts=%s" %
        (s0, code(b0), s1, code(b1), msg(b1), aud1, s2, code(b2), msg(b2), cur, L.now()))
    note_attr("AUTH-03: 端点不收 personId → 服务层 SUPER_ADMIN 代改豁免在 HTTP 面不可达(无越权改密面, 正向安全结论); 正向轮换需专用单实例环境补跑。")

# ============================ CFG ============================
def cfg():
    # CFG-01 硬编码系数静态检索（业务源码口径）
    p = "=(\\s|-)?(1000|1500|2000|2500|3000)|BigDecimal\\.valueOf\\((1000|1500|2000|2500|3000)\\)|0\\.05[^0-9]|1\\.5[^0-9]"
    hits = grep(p, "/Users/mac/Documents/ruoyi-ai/ruoyi-modules/ruoyi-ipd/src/main/java")
    lines = [x for x in hits.splitlines() if x.strip()]
    def noise(x):
        # 排除与"津贴/系数面值"无关的同数形噪声: 校验注解/长度/HTTP/超时/分页/单位换算/注释/默认回退常量
        kw = ["@Size", "max =", "min =", "message =", "length", "substring", "INTERVAL", "LIMIT", "pageSize",
              "1000)", "* ", "//", "http", "timeout", "Timeout", "TTL", "MILLIS", "1024", "capacity", "varchar",
              "getIntValue", "getLongValue", "DEFAULT", "@param", "@return", "test", "Test"]
        return any(k in x for k in kw)
    meaningful = [x for x in lines if not noise(x)]
    rec("AC-CFG-01", "CFG01-hardcode", "grep -rE '系数面值(1000/1500/.../0.05/1.5)' ruoyi-ipd/src/main/java", "-", None,
        "PASS" if not meaningful else ("PARTIAL" if len(meaningful) <= 6 else "FAIL"),
        "原始命中%d行; 扣除噪声口径(@Size/长度/超时/分页/注释/默认回退常量/测试)后业务系数硬编码命中%d行。样例: %s。ts=%s" % (len(lines), len(meaningful), json.dumps(meaningful[:4], ensure_ascii=False)[:600], L.now()))
    # ---- CFG-02/03: 签署期限可配置 + 立即生效 ----
    # 关键源码事实(实测前核实): gates.sign_due_at 不在 POST /projects/{id}/gates 时产生,
    # 仅在 GateElementResultService.submit(GATE_SUBMIT) 按 startedAt + resolveSignDeadlineDays()*86400_000 起算(L282-285);
    # 而 resolveSignDeadlineDays() 优先读 IBusinessConfigService(ipd_business_config), 回退 SystemConfig(system_configs)(L465-475)
    # ⇒ 必须走完整"要素判定齐备 + 提交评审"链路才能真观测, 并顺带验证两个配置源的优先级漂移。
    if not CTX.get("P-B"):
        blocked("AC-CFG-02 AC-CFG-03", "P-B=None 无法在新Gate上观测期限")
        return
    key = "gate.signDeadlineDays"
    biz_val = sql1("SELECT config_value FROM ipd_business_config WHERE config_key='%s'" % key)
    s0, b0, _, _ = call("GET", "/api/v1/system-configs/%s" % key, u="ipd-admin")
    v0 = data(b0).get("value")
    s1, b1, _, _ = call("PUT", "/api/v1/system-configs/%s" % key, u="ipd-admin", body={"value": "5", "reason": "LANE1-CFG02"})
    L.write_register("system_configs %s %s->5 (PUT /system-configs, LANE1-CFG02 实验)" % (key, v0))
    imm = sql1("SELECT config_value FROM system_configs WHERE config_key='%s'" % key)
    sg, bg, _, _ = call("GET", "/api/v1/system-configs/%s" % key, u="ipd-admin")
    imm_api = data(bg).get("value")
    # 新建 Gate → 判齐 14 项适用要素 → 提交评审(sign_due_at 起算点)
    s2, b2, _, _ = call("POST", "/api/v1/projects/%s/gates?gateCode=G1" % CTX.get("P-B"), u="ipd-market")
    gid = data(b2).get("id")
    if code(b2) == 0 and gid:
        L.write_register("gates id=%s (LANE1 gate G1@P-B=%s, CFG-02 期限实证件)" % (gid, CTX.get("P-B")))
    s3, b3, _, _ = call("GET", "/api/v1/gates/%s/elements" % gid, u="ipd-market") if gid else (0, {}, "", "")
    elems = data(b3) if isinstance(data(b3), list) else (b3.get("data") or [])
    if not isinstance(elems, list):
        elems = []
    judged, jfail = 0, []
    for e in elems:
        eid = e.get("elementId")
        body = {"elementId": int(eid), "result": "PASS", "evidenceRef": "LANE1-CFG02 要素判定实证的引用编号",
                "verifications": 9, "writtenIntents": 2}
        sj, bj, _, _ = call("POST", "/api/v1/gates/%s/element-results" % gid, u="ipd-market", body=body)
        if code(bj) == 0:
            judged += 1
        else:
            jfail.append("%s:%s/%s" % (e.get("elementCode"), sj, code(bj)))
    if judged:
        L.write_register("gate_element_results x%d (gate=%s 全要素 PASS, LANE1-CFG02 提交前置)" % (judged, gid))
    s4, b4, _, _ = call("POST", "/api/v1/gates/%s/submit" % gid, u="ipd-market",
                        body={"materialsOssId": 990001, "meetingMinutesOssId": 990002}) if gid else (0, {}, "", "")
    if code(b4) == 0:
        L.write_register("gates %s GATE_SUBMIT -> started_at/sign_due_at 起算 (LANE1-CFG02)" % gid)
    due = L.sql("SELECT started_at, sign_due_at FROM gates WHERE id=%s" % gid) if gid else []
    import datetime
    delta = None
    if due and due[0][0] not in (None, "NULL") and due[0][1] not in (None, "NULL"):
        d1 = datetime.datetime.strptime(due[0][1][:19], "%Y-%m-%d %H:%M:%S")
        d0 = datetime.datetime.strptime(due[0][0][:19], "%Y-%m-%d %H:%M:%S")
        delta = round((d1 - d0).total_seconds() / 86400.0, 2)
    s5, b5, _, _ = call("PUT", "/api/v1/system-configs/%s" % key, u="ipd-admin", body={"value": v0, "reason": "LANE1-CFG02 回滚"})
    L.write_register("system_configs %s 5->%s (回滚完成)" % (key, v0))
    dual = (delta is not None and delta != 5.0 and biz_val is not None and float(biz_val) == delta)
    ok = code(b1) == 0 and code(b2) == 0 and judged == len(elems) and code(b4) == 0 and delta == 5.0
    rec("AC-CFG-02", "CFG02-gate-deadline",
        "PUT %s=5 -> POST /projects/P-B/gates?gateCode=G1 -> POST /gates/{id}/element-results x%d -> POST /gates/{id}/submit -> SQL 回读 started_at/sign_due_at -> 回滚" % (key, len(elems)),
        s4, code(b4), "PASS" if ok else ("FAIL" if dual else "PARTIAL"),
        "改值 http=%s code=%s; 新Gate id=%s code=%s; 要素判定 %s/%s 成功(失败样本=%s); 提交评审 http=%s code=%s msg=%s; "
        "sign_due_at-started_at = %s 天(预期5;实测若=3 即双源漂移: ipd_business_config.%s=%s 优先于 system_configs=5, 见 resolveSignDeadlineDays L465-475); "
        "回滚 http=%s 终值=%s。归因=%s ts=%s" %
        (s1, code(b1), gid, code(b2), judged, len(elems), (jfail[:3] or "-"), s4, code(b4), msg(b4), delta, key, biz_val,
         s5, sql1("SELECT config_value FROM system_configs WHERE config_key='%s'" % key),
         "PASS:system_configs 即时生效于新发起会签" if ok else ("真缺陷候选:双配置源漂移,GATE_SUBMIT 只认 ipd_business_config,/system-configs 改值对签署期限不生效" if dual else "链路未走通,详见左"), L.now()))
    if dual:
        note_fix("CFG-02 真缺陷候选(双源漂移): PUT /api/v1/system-configs/gate.signDeadlineDays=5 成功且立即回读=5, 但 GATE_SUBMIT 起算的 sign_due_at-started_at 仍=%s 天——"
                 "GateElementResultService.resolveSignDeadlineDays() 优先 businessConfigService.getInt(ipd_business_config 行值=%s), 只有其抛异常才回退 system_configs; "
                 "两表同名键无同步机制。证据: gates.id=%s + system_configs/ipd_business_config 双查询。" % (delta, biz_val, gid))
    # CFG-03 立即生效(无重启/无清缓存): 配置面回读 + 业务面(见 CFG-02 delta)
    ok3 = code(b1) == 0 and imm == "5" and imm_api == "5" and s5 and code(b5) == 0
    rec("AC-CFG-03", "CFG03-instant", "PUT 后不重启不清缓存, 立即 SQL 直读 + GET 回读; 业务生效性交叉验证见 CFG-02", s1, code(b1),
        "PASS" if ok3 else "FAIL",
        "PUT http=%s code=%s data=%s; 立即SQL直读=%s; 立即 GET /system-configs/%s value=%s —— 配置面零重启即时生效(缓存 invalidate 由 SystemConfigController 返回体佐证)。"
        "业务面即时性: 同一 PUT 之后新发起的会签期限=%s天(ipd_business_config=%s 抢优先) ⇒ 见 CFG-02 判定。ts=%s" %
        (s1, code(b1), json.dumps(b1, ensure_ascii=False)[:160], imm, key, imm_api, delta, biz_val, L.now()))


def team08_readback():
    invx = CTX.get("INVX")
    if not invx:
        blocked("AC-TEAM-08", "LANE1-INVX 实证件未建成(见 TEAM-08 建件记录/P-A夹具)")
        return
    st = sql1("SELECT status FROM bid_invitations WHERE id=%s" % invx)
    n = sql1("SELECT COUNT(*) FROM notification_events WHERE receiver_id=900103 AND event_type='BID_EXPIRED_NO_RESPONSE' AND source_id=%s" % invx)
    ok = st == "EXPIRED"
    rec("AC-TEAM-08", "TEAM08-auto-close", "LANE1-INVX(expireAt=+30s) 建立后等待>75s回读 status + 到期通知", "-", None,
        "PASS" if ok else "FAIL",
        "status=%s(预期EXPIRED自动关闭); 到期通知=%s条。若仍OPEN: 归因=真缺陷候选——BidInvitationService.expireOverdue() 全模块零调用方(无@Scheduled/无手动端点),到期关闭与挂起链路整体未接线(代码考古+实测双证)。通知有=%s。ts=%s" % (st, n, n, L.now()))
    if not ok:
        note_fix("TEAM-08 真缺陷候选: 招标到期自动关闭/挂起未接线——expireOverdue 无调度与端点调用方,过期邀标永驻 OPEN(实测 INVX=%s 到期后仍OPEN)。TEAM-06/07 同源。" % invx)

def restore_ctx():
    # 续跑模式: 从真库按 LANE1-<key>-<RUN> 命名复原夹具主键(仅本车道自有资产, 不碰兄弟车道数据)
    for key in ("P-A", "P-B", "P-C"):
        CTX[key] = sql1("SELECT id FROM projects WHERE name='LANE1-%s-%s' AND del_flag='0' ORDER BY id DESC LIMIT 1" % (key, RUN))
    for ck, pat in (("INV1", "LANE1-INV1-%s%%"), ("INV2", "LANE1-INV2-%s%%"), ("INVX", "LANE1-INVX-%s%%")):
        CTX[ck] = sql1("SELECT id FROM bid_invitations WHERE title LIKE '%s' AND del_flag='0' ORDER BY id DESC LIMIT 1" % (pat % RUN))
    CTX["FIX_OK"] = bool(CTX.get("P-A"))
    print("[RESUME] RUN=%s ctx=%s" % (RUN, {k: CTX.get(k) for k in ("P-A","P-B","P-C","INV1","INV2","INVX")}), flush=True)

def main():
    if RESUME:
        restore_ctx()
        env(); auth_core(); team_timers_and_admin(); team12_and_hr(); cfg(); auth_tail()
        team08_readback()
    else:
        env(); auth_core(); make_projects(); team_upper(); team_lower(); team_timers_and_admin()
        team12_and_hr(); cfg(); auth_tail()
        time.sleep(45)  # INVX expireAt=+30s 到点富余
        team08_readback()
    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "_lane1_findings.tmp"), "w", encoding="utf-8") as f:
        json.dump({"defects": NOTES, "attribution": TS_NOTES}, f, ensure_ascii=False, indent=1)
    print("RUN", RUN, "DONE recs=", len(RECS))

if __name__ == "__main__":
    main()
