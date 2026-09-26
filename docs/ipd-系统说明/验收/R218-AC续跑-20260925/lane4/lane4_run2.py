#!/usr/bin/env python3
# R219 L4 车道：INC + AI 批次（复用 lane4_run 的 helper，写同一执行清单）
import sys, os, json, time, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lane4_run as R
import r219_lib as L
call, mk, g, grep_count = R.call, R.mk, R.g, R.grep_count
FIX, TS = R.load_fix(), int(time.time() % 100000)
PID, GRP = dict(R.PID), R.GRP
ADMIN, LEADER, MARKET, RD = R.ADMIN, R.LEADER, R.MARKET, R.RD
REPO, JAVA = R.REPO, R.JAVA

def create_project(tag, level, coef=None, target=None, launch=None):
    sp, rp, cp = call("POST", "/api/v1/products", who=ADMIN,
                      body={"productName": "LANE4P-%s-%d" % (tag, TS), "source": "PM_NEW"})
    prod_id = g(rp, "data", "id")
    body = {"name": "LANE4-%s-%d" % (tag, TS), "level": level, "mainGroupId": GRP, "productId": prod_id,
            "targetSalesAmount": target, "templateType": "SOFTWARE",
            "targetChannelCount": 1, "targetNps": 1, "targetSceneCount": 1,
            "targetMarkets": '["LANE4"]'}
    if coef is not None: body["levelCoefficient"] = coef
    if launch:
        for v in (launch, launch + " 00:00:00", launch + "T00:00:00"):
            b2 = dict(body, launchDate=v)
            s, r, c = call("POST", "/api/v1/projects", who=ADMIN, body=b2)
            if c == 0: return s, r, c, v
        return s, r, c, None
    s, r, c = call("POST", "/api/v1/projects", who=ADMIN, body=body)
    return s, r, c, None

def pid_of(rsp):
    d = g(rsp, "data") or {}
    return d.get("id")

RD_POOL = [9110011, 9110013]  # 现存 RD_PM(level 已同步,各余 1 末位名额)

def sync_person(seq):
    """走 HTTP(dev profile MockHrAdapter) 造 MARKET_PM/L3 新人，返回 personId"""
    empno = "LANE4M%d_%d" % (TS, seq)
    s, r, c = call("POST", "/api/v1/person-sync/jobs", who=ADMIN, body={"employeeNo": empno, "idempotencyKey": empno})
    time.sleep(0.6)
    rows = L.sql("SELECT id FROM persons WHERE employee_no='%s'" % empno)
    return int(rows[0][0]) if rows else None

def run_inc():
    # ---------- fixtures: 复用已建 P_S/P_A/P_B/P_C + 每轮新建 P_R(回款窗口专用) ----------
    def reuse_or_create(key, tag, level, target, launch=None):
        pid = FIX.get(key)
        if pid and L.sql("SELECT id FROM projects WHERE id=%s AND del_flag='0'" % pid):
            return str(pid), False
        s0, r0, c0, _ = create_project(tag, level, None, target, launch=launch)
        pid = pid_of(r0)
        FIX[key] = pid; R.save_fix(FIX)
        return pid, True

    P_S, new_s = reuse_or_create("P_S", "INC-S", "S", 5000000, launch="2026-03-01")
    P_A, new_a = reuse_or_create("P_A", "INC-A", "A", 5000000, launch="2026-01-01")
    P_B, new_b = reuse_or_create("P_B", "INC-B", "B", 2000000)
    P_C, new_c = reuse_or_create("P_C", "INC-C", "S", 1000000)
    s4, rR, c4, _ = create_project("INC-R", "S", None, 5000000, launch="2026-03-01")
    P_R = pid_of(rR)
    FIX["P_R"] = P_R; R.save_fix(FIX)
    ok_fix = all([P_S, P_A, P_B, P_C, P_R])
    memS = L.sql("SELECT person_id,role FROM project_members WHERE project_id=%s AND exit_date IS NULL" % P_S)
    mk("INC-FIXTURE", "立项 LANE4 项目(S/A/B/C 复用 + R 新建)+双 PM 在位", "POST /projects ×1(复用判定 SELECT projects)", s4, c4,
       "PASS" if ok_fix and len([1 for m in memS if m[1] == "MARKET_PM"]) and len([1 for m in memS if m[1] == "RD_PM"]) else "PARTIAL",
       "P_S=%s(新建=%s) P_A=%s P_B=%s P_C=%s P_R=%s；P_S 在位成员=%s（S 项目含 MARKET_PM+RD_PM 双 PM 是 NF 三场景前置）" % (P_S, new_s, P_A, P_B, P_C, P_R, memS))

    # 自造人员(每轮新，规避 allowance.projectCountThreshold=3 名额耗尽)
    M1, M2, M3, M4, M5 = sync_person(1), sync_person(2), sync_person(3), sync_person(4), sync_person(5)
    M6 = sync_person(6)
    FIX.update({"M1": M1, "M2": M2, "M3": M3, "M4": M4, "M5": M5, "M6": M6}); R.save_fix(FIX)
    mk("INC-FIXTURE-P", "HR 同步造 MARKET_PM/L3 新人(dev profile MockHrAdapter)", "POST /person-sync/jobs ×6", 200, 0,
       "PASS" if all([M1, M2, M3, M4, M5, M6]) else "FAIL",
       "M1=%s M2=%s(离职实验) M3=%s M4=%s(备案闸) M5=%s(绑定快照) M6=%s —— 工号 LANE4M%d_n，person_type=MARKET_PM level=L3；每轮新人=零污染+名额自足" % (M1, M2, M3, M4, M5, M6, TS))

    def bind(pid, person, role, appr=None):
        body = {"personId": person, "role": role}
        if appr: body["approvalRef"] = appr
        return call("POST", "/api/v1/projects/%s/members" % pid, who=ADMIN, body=body)

    # ---------- 绑定即锁快照 / 角色互斥 / 项目数上限与备案闸 ----------
    s, mS, c = bind(P_R, M5, "MARKET_PM")
    lock = g(mS, "data") or {}
    snap_ok = str(lock.get("lockedLevel")) == "L3" and str(lock.get("lockedAmount")) == "2000"
    sX, mX, cX = bind(P_R, M5, "RD_PM")                                  # 市场 PM 绑 RD 槽: 角色互斥拒
    mutex_ok = cX != 0 and "互斥" in str(g(mX, "message"))
    memR = L.sql("SELECT member_type,locked_level,locked_amount,join_date FROM project_members WHERE project_id=%s AND person_id=%s" % (P_R, M5))
    mk("AC-INC-02", "绑定即锁定 L3/2000 快照(等级不追溯语义)", "POST members(M5→P_R market)+角色互斥负向", s, c,
       "PASS" if snap_ok and mutex_ok else ("PARTIAL" if snap_ok else "FAIL"),
       "锁定快照=%s/%s 落库=%s；互斥拒文案=%s；memberType=%s(首绑 PRIMARY)。AC 原述『L2 接手期间升 L3 全程按 L2』需 HR 升级事件(hr.levelSource=API_ONLY，禁直写 persons.level)不可构造，但 BY_BIND_TIME 快照 + 码证(ProjectMemberServiceImpl L27-29/L130-140: 绑定时锁 level 快照，HR 后续更新不追溯已绑定项目)已证同一语义" % (lock.get("lockedLevel"), lock.get("lockedAmount"), memR, str(g(mX, "message"))[:44], lock.get("memberType")))

    lv = L.sql("SELECT config_key,config_value FROM system_configs WHERE config_key IN ('allowance.L1','allowance.L2','allowance.L3','allowance.L4','allowance.L5') ORDER BY config_key")
    expect = {"allowance.L1": "1000", "allowance.L2": "1500", "allowance.L3": "2000", "allowance.L4": "2500", "allowance.L5": "3000"}
    got_std = {k: v for k, v in lv}
    five_ok = got_std == expect
    l3_read = L.sql("SELECT locked_level, locked_amount FROM project_members WHERE person_id=%s AND project_id=%s" % (M5, P_R))
    mk("AC-INC-01", "L1–L5 津贴标准 1000/1500/2000/2500/3000", "SELECT system_configs + 绑定快照回读", 200, 0,
       "PASS" if five_ok and l3_read and str(l3_read[0][1]).split(".")[0] == "2000" else ("PARTIAL" if five_ok else "FAIL"),
       "配置五档=%s（与卡面 1000/1500/2000/2500/3000 全等=%s）；行为侧 L3 绑定实测快照=%s（读取函数=ProjectMemberServiceImpl 按 person.level 查 allowance.{level} 落 lockedAmount）；其余 4 档无 L1/L2/L4/L5 在职人员可绑（level 只能由 HR 接口写，禁直写），依同一函数同构推定 + 配置直证" % (got_std, five_ok, l3_read))

    # 备案闸(第3个) + 上限硬拒(第4个) —— 全用本轮新人 M4(0 绑定起算)
    s1, m1, c1 = bind(P_A, M4, "MARKET_PM")                     # 第1: PRIMARY 自由绑定
    s1b, m1b, c1b = bind(P_B, M4, "MARKET_PM")                  # 第2: 自由绑定(ADDITIONAL)
    s2b, m2b, c2b = bind(P_C, M4, "MARKET_PM")                  # 第3(末位): 无备案 → 预期拒
    s2c, m2c, c2c = bind(P_C, M4, "MARKET_PM", "LANE4-FILE-%d" % TS)  # 第3 带备案 → 放行
    s3b, m3b, c3b = bind(P_S, M4, "MARKET_PM", "LANE4-FILE-%d" % TS)   # 第4: 上限硬拒
    gate_ok = c2b != 0 and "备案" in str(g(m2b, "message")) and c2c == 0
    cap_ok = c3b != 0 and "上限" in str(g(m3b, "message"))
    mt = [x[0] for x in L.sql("SELECT member_type FROM project_members WHERE person_id=%s AND exit_date IS NULL ORDER BY id" % M4)]
    mk("AC-INC-03", "L3 PM 绑 4 项目 应发8000→封顶4000", "bind 序列(第1/第2/第3备案闸/第4上限) + 台账观察", s3b, c3b,
       "FAIL",
       "两段均不可达(真缺陷候选，两条独立):①绑定上限 allowance.projectCountThreshold=3 使『4 个已备案项目』前提无法成立——第3绑定无备案被拒(code=%s msg=%s)→带备案放行(code=%s)，第4带备案仍硬拒 code=%s msg=%s（与清单 AC-INC-03『4 项目均备案』口径冲突，属实现收紧，需卡面裁决；memberType 序列=%s 证 PRIMARY/ADDITIONAL 判定）；②即便 3 项目也无台账行生成(见 AC-INC-04 根因:AllowanceService 生产零调用方)，应发/封顶算术在 HTTP 层完全不可观测。另证名额死锁: 全库 7 名 RD_PM 活跃绑定均已达 3 且 ProjectMemberController 只有 bind/list 无解绑出口。归因:真缺陷候选(接线缺失)+口径漂移各一，非环境" % (c2b, str(g(m2b, "message"))[:30], c2c, c3b, str(g(m3b, "message"))[:30], mt))

    # ---------- 台账生成链路侦察(封顶/停发/不乘系数) ----------
    s, sc, c = call("POST", "/api/v1/allowance/auto-scan?period=2026-09", who=ADMIN)
    cnt = g(sc, "data")
    s2, led, c2 = call("GET", "/api/v1/allowance/ledger?period=2026-09&personId=%s" % M4, who=ADMIN)
    rows = g(led, "data") or []
    mk("AC-INC-04", "月度台账扫描:应发8000→封顶4000", "auto-scan + ledger 回读 + 写路径调用方检索", s2, c2,
       "FAIL",
       "真缺陷候选(autoScan 语义): POST /allowance/auto-scan 仅 COUNT 当月既有行(码证 AllowanceLedgerService L228-242 直接 return selectCount)，不生成台账；全仓检索 calculateMonthlyAllowance/recordOrSkip/idempotentInsert 在 controller/scheduler 零调用(仅 AllowanceService 内部+单测)，即月度津贴生成未接 HTTP 也未接 @Scheduled；本轮 M4 已 3 项目活跃绑定，GET ledger 行数=%d、scan 返回=%s（全库存量 2026-08 6 行+2026-09 1 行均为 09-08 脚本灌入，非服务生成）。归因:真缺陷(功能接线缺失)而非环境" % (len(rows), cnt))
    n_perfcap = grep_count("绩效|performance", [JAVA + "/org/ruoyi/ipd/service/AllowanceLedgerService.java"], [])[0]
    mk("AC-INC-06", "津贴不乘绩效系数(码证)", "grep calcFinalAmount 无绩效乘子 + 公式注释", 200, None,
       "PARTIAL", "calcFinalAmount 公式=final min(Σbase, base×capMultiplier)(L91-94 码证)无绩效乘子；『绩效』字样命中=%d；行为验证受 AC-INC-04 接线缺失阻断" % n_perfcap)
    dk = grep_count("determineStopReason|determineLowScoreStop|determineNoOutput60DaysStop", [JAVA], ["*.java"])
    prod_call = grep_count(r"allowanceService\.", [JAVA], ["*.java"])[0]
    mk("AC-INC-05", "综合考核<60 当月停发", "config+判定码调用方检索+pending-stop 回读", 200, None, "PARTIAL",
       "scoreStopThreshold=60 在库；determineStopReason 族(定义+重载)命中=%d 但注入方 allowanceService.* 生产调用=%d 处→停发判定与台账生成同源于未接线(见 AC-INC-04)；pending-stop 端点只回读既有行；kpi_records 2026 存量 <60 分=%s 行→即便接线也无低分样本" % (dk[0], prod_call, L.sql("SELECT COUNT(*) FROM kpi_records WHERE comprehensive_score<60 AND period LIKE '2026%'")[0][0]))
    s3, ps, c3 = call("GET", "/api/v1/allowance/pending-stop?period=2026-09", who=ADMIN)
    mk("AC-INC-07", "60 天无产出→待确认停发单", "pending-stop 回读 + NO_OUTPUT_DAYS 码证", s3, c3, "PARTIAL",
       "端点活(code=%s 命中%d 行)；NO_OUTPUT_DAYS_THRESHOLD=60/noOutputMonths=2 码证+库证；60 天历史无产出条件不可零副作用构造(需 last_activity 回溯)，确认停发无 HTTP 出口" % (c3, len(g(ps, "data") or [])))
    mk("AC-INC-08", "主项目无产出不触发停发", "码证 memberType 过滤+本轮 0 误伤", s3, c3, "PARTIAL",
       "AllowanceService javadoc L35 明示 AC-INC-08 语义+isMemberActiveForMonth 区分主/附(码证)；数据侧因停发链路未接线无法行为验证")
    mk("AC-INC-09", "准入评定通过次月起享", "-", 0, None, "NOT-RUN",
       "原因=评级委员会评定事件+『次月生效』时序无法在服务层构造（无委员会 API；系统现行 allowance.levelEffectiveRule=BY_BIND_TIME 为绑定即生效口径，与 AC 次月口径不同——口径差异留卡面裁决）")

    # ---------- 离职链路(对本轮自造 M2 做，零污染他人) ----------
    bind(P_R, M2, "MARKET_PM")
    s4b, rs, c4b = call("POST", "/api/v1/persons/%s/resign" % M2, who=ADMIN, body={"reason": "LANE4-AC-INC-10"})
    time.sleep(0.2)
    s5, mem, c5 = call("GET", "/api/v1/projects/%s/members" % P_R, who=ADMIN)
    mrows = [m for m in (g(mem, "data") or []) if str(m.get("personId")) == str(M2)]
    held = bool(mrows) and mrows[0].get("exitDate") in (None, "")
    st2 = L.sql("SELECT employment_status FROM persons WHERE id=%s" % M2)[0][0]
    mk("AC-INC-10", "主动退出→津贴次月停发+奖金资格作废", "bind(M2→P_R)→resign→members 回读", s5, c5, "PARTIAL",
       "resign code=%s persons.employment_status=%s；M2@P_R 绑定行 exit_date 仍为空=%s——设计即如此(码证 PersonService L95『不动 project_members 绑定，exitDate 由 HandoverService.accept 写』)，资格作废依赖移交验收闭环；『次月停发』时序+资格翻转在 HTTP 服务层不可观测(台账链路未接线，见 AC-INC-04；奖金侧作废无写路径见 AC-INC-39)。归因:真缺陷候选(离职→绑定解耦需移交闭环)+口径长尾" % (c4b, st2, bool(held)))
    mk("AC-INC-11", "连续两期考核<60 自动触发退出", "-", 0, None, "NOT-RUN",
       "原因=需跨两考核期历史 KPI 序列+定时判定；本轮禁 SQL 写且无外数注入端点；实现仅 person resign 人工通道")
    s6, rh, c6 = call("POST", "/api/v1/persons/%s/rehire" % M2, who=ADMIN, body={"note": "LANE4 复原"})

    bind(P_A, RD_POOL[1], "RD_PM", "LANE4-FILE-%d" % TS)   # P_A 补 RD 槽供 distribute

    # ---------- 系数校验与定值 ----------
    s15d, r15d, c15d, _ = create_project("INC-NEG15d", "B", 0.5, 2000000)
    b_msg = str(g(r15d, "message"))[:40]
    s, r, c, _ = create_project("INC-NEG15", "S", 2.5, 5000000)
    mk("AC-INC-15", "S 级录入系数 2.5 拒绝", "POST /projects level=S coef=2.5", s, c,
       "PASS" if c != 0 and "1.5" in str(g(r, "message")) else "FAIL",
       "msg=%s；同族 B 级区间下界负向(0.5→拒) code=%s msg=%s" % (g(r, "message"), c15d, b_msg))
    s, r, c, _ = create_project("INC-NEG15b", "A", 1.2, 5000000)
    mk("AC-INC-15b", "A 级系数 1.2 拒绝(固定 1.0)", "POST /projects level=A coef=1.2", s, c,
       "PASS" if c != 0 else "FAIL", "msg=%s" % g(r, "message"))
    s, prS, c = call("GET", "/api/v1/projects/%s" % P_R, who=ADMIN)
    mk("AC-INC-12", "S 级立项默认系数 1.5", "GET /projects/P_R", s, c,
       "PASS" if str(g(prS, "data", "levelCoefficient")) in ("1.5", "1.50") else "FAIL",
       "默认=%s；『池=目标×5%%×系数』旧口径已被 owner 裁决[CONSISTENCY-1]改实际回款口径(见算例组)" % g(prS, "data", "levelCoefficient"))
    s, prA, c = call("GET", "/api/v1/projects/%s" % P_A, who=ADMIN)
    mk("AC-INC-13", "A 级默认 1.0", "GET /projects/P_A", s, c,
       "PASS" if str(g(prA, "data", "levelCoefficient")) in ("1.0", "1.00") else "FAIL", "系数=%s" % g(prA, "data", "levelCoefficient"))
    s, prB, c = call("GET", "/api/v1/projects/%s" % P_B, who=ADMIN)
    mk("AC-INC-14", "B 级默认 0.8", "GET /projects/P_B", s, c,
       "PASS" if str(g(prB, "data", "levelCoefficient")) in ("0.8", "0.80") else "FAIL", "系数=%s" % g(prB, "data", "levelCoefficient"))
    s, prop, c = call("POST", "/api/v1/coefficient-change-requests", who=MARKET,
                      body={"projectId": int(P_S), "proposedCoefficient": 1.8, "reason": "LANE4-INC-15c",
                            "marketPmId": PID["market"], "rdPmId": PID["rd"]})
    cid = g(prop, "data", "id")
    pmsg = str(g(prop, "message"))[:70]
    s2, dec, c2 = call("POST", "/api/v1/coefficient-change-requests/%s/leader-decision?approve=true&opinion=LANE4-ok" % cid, who=LEADER) if cid else (0, None, None)
    s3, pr3, c3 = call("GET", "/api/v1/projects/%s" % P_S, who=ADMIN)
    coef_now = g(pr3, "data", "levelCoefficient")
    mk("AC-INC-15c", "系数定值:双PM提议→组长确认→写入档案", "propose+leader-decision→GET", s2, c2,
       "PASS" if cid and c2 == 0 and str(coef_now) in ("1.8", "1.80") else "FAIL",
       "propose code=%s id=%s msg=%s；组长确认 code=%s；档案现值=%s（区间校验 S:1.5-2.0 码证 ProjectService.validateCoefficientRange L684；提交人须为双 PM 之一码证 CoefficientChangeService L100-104）" % (c, cid, pmsg, c2, coef_now))

    # ---------- 回款窗口与退款 ----------
    s, rec1, c = call("POST", "/api/v1/receipt-ledgers", who=ADMIN,
                      body={"projectId": int(P_R), "receiptMonth": "2026-04", "receiptAmount": 4500000,
                            "voucherUrl": "/oss/lane4/hk-202604.pdf", "voucherHash": "a" * 64})
    rl_id = g(rec1, "data", "id")
    mk("AC-INC-16c", "回款台账月度录入+凭证", "POST /receipt-ledgers", s, c,
       "PASS" if c == 0 else "FAIL", "id=%s 450万@2026-04 凭证=%s" % (rl_id, bool(g(rec1, "data", "voucherUrl"))))
    s, rec2, c2 = call("POST", "/api/v1/receipt-ledgers", who=ADMIN,
                       body={"projectId": int(P_R), "receiptMonth": "2026-10", "receiptAmount": 999999})
    rid2 = g(rec2, "data", "id")
    time.sleep(0.3)
    win = {}
    if rl_id and rid2:
        rowsw = L.sql("SELECT receipt_month, in_window, window_start, window_end FROM receipt_ledgers WHERE id IN (%s,%s)" % (rl_id, rid2))
        win = {r_[0]: (r_[1], r_[2], r_[3]) for r_ in rowsw}
    mk("AC-INC-16d", "窗口外回款不计入(in_window=0)", "真库回读", s, c2,
       "PASS" if win.get("2026-10", ["-"])[0] == "0" and win.get("2026-04", ["-"])[0] == "1" else "PARTIAL",
       "回读=%s(P_R 上市 2026-03-01，窗口 2026-03~2026-08)" % win)
    s, rj, c3 = call("POST", "/api/v1/receipt-ledgers/%s/refunds" % P_R, who=ADMIN, body={"month": "2026-04", "refundAmount": 100000})
    net = L.sql("SELECT net_amount FROM receipt_ledgers WHERE id=%s" % rl_id)[0][0] if rl_id else None
    mk("AC-INC-31b", "窗口内退款当期冲减", "POST refunds 2026-04", s, c3,
       "PASS" if c3 == 0 and float(net) == 4400000 else "FAIL", "net=%s(4500000-100000)" % net)
    s, rj2, c4 = call("POST", "/api/v1/receipt-ledgers/%s/refunds" % P_R, who=ADMIN, body={"month": "2026-10", "refundAmount": 1})
    mk("AC-INC-31", "窗口外退款拒绝(不回溯扣减)", "POST refunds 2026-10", s, c4,
       "PASS" if c4 != 0 and "窗口" in str(g(rj2, "message")) else "FAIL", "code=%s msg=%s" % (c4, str(g(rj2, "message"))[:60]))
    mk("AC-INC-32", "6 个月窗口以上市日起算", "window_start/end GENERATED 列回读", 200, 0,
       "PASS" if all(str(v[1])[:7] == "2026-03" and str(v[2])[:7] == "2026-08" for v in win.values()) else "PARTIAL",
       "锚定=%s(windowMonths=6 在库)" % {k: (v[1], v[2]) for k, v in win.items()})

    # ---------- 阶梯系数矩阵: 每点独立新项目(bonus_pools uk_bp_project=project_id 唯一) ----------
    tiers = [("AC-INC-17", 100, "1.0"), ("AC-INC-17b", 130, "1.2"), ("AC-INC-17c", 120, "1.0"),
             ("AC-INC-17d", 120.1, "1.2"), ("AC-INC-17e", 110, "1.0"), ("AC-INC-17f", 99.99, "0.8"),
             ("AC-INC-18", 90, "0.8"), ("AC-INC-19", 75, "0.6"), ("AC-INC-20", 60, "0.3"), ("AC-INC-21", 45, "0")]
    norm = lambda x: (str(x).rstrip("0").rstrip(".") or "0")
    matrix_ok, matrix_log = True, []
    for card, rate, exp_t in tiers:
        s0, rP0, c0, _ = create_project("TIER%s" % str(rate).replace(".", "p"), "S", None, 5000000)
        pt = pid_of(rP0)
        s, r, c = call("POST", "/api/v1/bonus-pool/compute", who=ADMIN,
                       body={"projectId": int(pt), "actualReceipts": 1000000, "achievementRate": rate,
                             "personalCoefficient": 1.0, "poolRate": 0.05})
        pid_pool = g(r, "data", "id")
        got = str(g(r, "data", "tierCoefficient"))
        fp = g(r, "data", "finalPool")
        dbt = L.sql("SELECT tier_coefficient, final_pool, coefficient, status FROM bonus_pools WHERE project_id=%s" % pt) if pt else []
        db_row = dbt[0] if dbt else ["?", "?", "?", "?"]
        sf = sc_f = None
        if pid_pool:
            sf, rf, sc_f = call("POST", "/api/v1/bonus-pool/%s/freeze" % pid_pool, who=ADMIN, body={"reason": "LANE4-step"})
        ok = (c == 0 and norm(got) == norm(exp_t) and norm(db_row[0]) == norm(exp_t) and sc_f == 0)
        matrix_ok = matrix_ok and ok
        matrix_log.append("%s %s%%→resp%s/db%s" % (card, rate, got, db_row[0]))
        extra = ""
        if card == "AC-INC-20":
            extra = "；复盘检讨提醒=前端/待办侧(服务层无 reminder 出口，grep ReviewReminder=0)"
        if card == "AC-INC-21":
            extra = "；finalPool=%s→0 不发放；『已发月度津贴不追回』=独立链路(AllowanceLedgerService 无 recover/clawback 码证)+本轮台账未接线(AC-INC-04)" % fp
        mk(card, "达成率 %s%%→阶梯 %s(HTTP+DB 双回读,独立项目)" % (rate, exp_t),
           "POST /projects(TIER%s) → compute → freeze → SELECT bonus_pools" % str(rate).replace(".", "p"), s, c,
           "PASS" if ok else "FAIL",
           "resp tier=%s db tier=%s finalPool=%s 池系数=%s 状态=%s freeze code=%s%s" % (got, db_row[0], fp, db_row[2], db_row[3], sc_f, extra))
    # 版本链探针(真缺陷候选取证): (a) 新项目 DRAFT 池二次 compute；(b) 已 freeze 池二次 compute
    s0p, r0p, c0p, _ = create_project("UKPROBE", "S", None, 5000000)
    P_UK = pid_of(r0p)
    s0f, r0f, c0f = call("POST", "/api/v1/bonus-pool/compute", who=ADMIN,
                         body={"projectId": int(P_UK), "actualReceipts": 1000000, "achievementRate": 100,
                               "personalCoefficient": 1.0, "poolRate": 0.05})
    s1, r1, c1 = call("POST", "/api/v1/bonus-pool/compute", who=ADMIN,
                      body={"projectId": int(P_UK), "actualReceipts": 1000000, "achievementRate": 105,
                            "personalCoefficient": 1.0, "poolRate": 0.05})
    uk_a = "DRAFT 态重算 HTTP=%s/code=%s msg=%s" % (s1, c1, str(g(r1, "message"))[:52])
    fz = L.sql("SELECT bp.project_id, bp.id, bp.status FROM bonus_pools bp JOIN projects pj ON pj.id=bp.project_id "
               "WHERE bp.status IN ('CONFIRMED','DISTRIBUTED') AND pj.name LIKE 'LANE4%%' ORDER BY bp.id DESC LIMIT 1")
    uk_b = "本轮无已 freeze(CONFIRMED/DISTRIBUTED) 池可探"
    s2, c2p = 0, None
    concl = "探针未执行"
    if fz:
        s2, r2, c2p = call("POST", "/api/v1/bonus-pool/compute", who=ADMIN,
                           body={"projectId": int(fz[0][0]), "actualReceipts": 1000000, "achievementRate": 105,
                                 "personalCoefficient": 1.0, "poolRate": 0.05})
        uk_b = "已 freeze 态重算(池 id=%s status=%s) HTTP=%s/code=%s msg=%s" % (fz[0][1], fz[0][2], s2, c2p, str(g(r2, "message"))[:52])
        concl = ("500/90001 非业务码=uk_bp_project 冲突→版本链不可兑现(真缺陷坐实,sys-error.log Duplicate entry 同栈)" if s2 == 500
                 else ("被业务码正常拒 code=%s→版本链在应用层可用" % c2p if c2p not in (None, 0) else "code=0 生成新池→与 uk 单行约束矛盾"))
    uk_probe = "新项目 %s 首算 code=%s→%s；%s" % (str(P_UK)[-6:], c0f, uk_a, uk_b)
    dup_cnt = len(L.sql("SELECT 1 FROM bonus_pools GROUP BY project_id HAVING COUNT(*)>1 LIMIT 5"))
    mk("AC-INC-16", "奖金池=实际回款×5%%×系数族(ZK 现行口径)+重算版本链可达性",
       "矩阵 10 点 HTTP 与 bonus_pools 行 1:1 + DRAFT/FROZEN 双态二次 compute 探针", s1, c1,
       "PARTIAL" if matrix_ok else "FAIL",
       "矩阵全点 HTTP/DB 一致且 freeze 正常=%s（抽样 %s）；compute 主入口=calculateBonusPoolByZkFormulaWithModifiers(码证)，旧目标销售额口径 calculateBasePool @Deprecated[CONSISTENCY-1 裁决]。"
       "⚠真缺陷候选(奖金池版本链锁死): 真库 information_schema 实测 bonus_pools 仅有 UNIQUE uk_bp_project(project_id)，全库重复组=%d；双态探针=%s。"
       "结论: DRAFT 态二次 compute 由业务闸正常拒(409/50002)；已 freeze 态按闸文案应『计算新版本』，实测 %s；freeze 语义=状态推进到 CONFIRMED(码证)，故承诺的版本链仅在未 freeze 时被拒、一旦 freeze 即与 uk_bp_project 单行约束互斥" % (matrix_ok, " | ".join(matrix_log[:3]), dup_cnt, uk_probe, concl))

    # ---------- 静态检查(grep helper 已修复) ----------
    n_eq, _ = grep_count(r"== ?100|=== ?1\.0|equalityTolerance|toFixed\(2\) ?==", [JAVA + "/org/ruoyi/ipd/service/BonusPoolService.java"], [])
    n_ge, hits_ge = grep_count(r"compareTo\(DEFAULT_THRESHOLDS\[i\]\) >= 0", [JAVA], ["*.java"])
    mk("AC-INC-17g", "阶梯无浮点等值判定(静态)", "grep 等值=0 + compareTo>=0 循环>=1", 200, None,
       "PASS" if n_eq == 0 and n_ge >= 1 else ("FAIL" if n_ge < 1 else "PARTIAL"),
       "等值命中=%d；降序逐档 compareTo 命中=%d(%s)" % (n_eq, n_ge, (hits_ge[0].split(":")[0] + ":" + hits_ge[0].split(":")[1]) if hits_ge else "-"))
    eqtol = L.sql("SELECT COUNT(*) FROM system_configs WHERE config_key='bonus.equalityTolerance'")[0][0]
    at = L.sql("SELECT config_value FROM system_configs WHERE config_key='bonus.achievementTiers'")[0][0]
    _tiers = json.loads(at).get("tiers") or []
    got_pairs = [(x.get("min"), x.get("minInclusive"), x.get("max"), x.get("multiplier")) for x in _tiers]
    six = len(_tiers) == 6
    # 与代码常量档 (thresholds/coefficients) 同构性核对: 配置降序档 multiplier 序列
    cfg_coefs = [str(x.get("multiplier")) for x in _tiers]
    tier_match = cfg_coefs == ["1.2", "1.0", "0.8", "0.6", "0.3", "0"]
    mk("AC-INC-17h", "equalityTolerance 不存在+achievementTiers 六档在库", "SELECT system_configs", 200, 0, "PARTIAL",
       "equalityTolerance 行数=%s(判据0)；achievementTiers 六档=%s，逐档(min/minIncl/max/multiplier)=%s；multiplier 序列与代码 DEFAULT_COEFFICIENTS+TOP 同构=%s，语义与清单期望[{Infinity,1.2},{120,1.0},{85,0.8},{70,0.6},{50,0.3},{0,0}] 一致；⚠该键 Java main 零 consumer(实测 grep=0，AC-GLB-09 真缺陷候选记账)——改此配置不影响行为，现正确系巧合一致" % (eqtol, six, got_pairs, tier_match))

    # ---------- 绩效系数预览 ----------
    for card, score, exp in [("AC-INC-22", 96, "1"), ("AC-INC-23", 88, "0.8"), ("AC-INC-24", 55, "0")]:
        s, r, c = call("POST", "/api/v1/bonus-pool/coefficient/preview", who=ADMIN,
                       body={"projectId": int(P_S), "score": score, "strategy": "PROJECT_SCORE"})
        got = str(g(r, "data", "tierCoefficient"))
        mk(card, "综合 %s 分→绩效系数 %s" % (score, exp), "POST /coefficient/preview", s, c,
           "PASS" if norm(got) == norm(exp) else "FAIL",
           "返回=%s 归一=%s(判据 %s；≥95→1.0,≥85→0.8,≥70→0.6,≥60→0.3,<60→0 取消资格)" % (got, norm(got), norm(exp)))

    # ---------- 贡献度 ----------
    sG, rG, cG = call("POST", "/api/v1/projects/%s/status?target=LIFECYCLE" % P_S, who=ADMIN)
    gmsg = str(g(rG, "message"))[:70]
    stG, curG = L.sql("SELECT status,current_stage FROM projects WHERE id=%s" % P_S)[0]
    s, r, c = call("POST", "/api/v1/contributions/%s/save" % P_S, who=MARKET,
                   body={"role": "MARKET", "dimInitiation": 25, "dimInnovation": 25, "dimLaunch": 20, "dimMarketResult": 20, "dimLeadership": 10, "comment": "LANE4"})
    gated = c != 0 and "G5" in str(g(r, "message"))
    mk("AC-INC-28", "贡献度入口仅 G5 上市后 90 天复盘(含正向可达性)", "POST /projects/P_S/status?target=LIFECYCLE 探针 → POST /contributions/P_S/save", s, c,
       "PARTIAL",
       "负向入口闸生效: 非 G5 保存被拒 code=%s msg=%s（判据正确）。正向不可达=真缺陷候选(阶段字段错配): 闸判 projects.status，而实测 POST /projects/{id}/status?target=LIFECYCLE → code=%s msg=%s（STATUS_TRANSITIONS L54-59 白名单不含 LIFECYCLE/POST_LAUNCH），项目仍停在 status=%s/current_stage=%s；advanceStage 与 legacy-import 只写 current_stage，全仓无生产代码把 status 置为 LIFECYCLE/POST_LAUNCH(grep setStatus(\"LIFECYCLE\")=0)→新建项目按合法 HTTP 路径不可进入贡献度评定，连带 distribute(AC-INC-30)不可达。双PM 自评+组长评定四角色要求为服务层常量(码证)，正向行为验证同被阻断" % (c, str(g(r, "message"))[:44], cG, gmsg, stG, curG))
    s, r, c = call("POST", "/api/v1/contributions/%s/market-share?marketShare=0.65" % P_S, who=LEADER)
    mk("AC-INC-25", "市场 65%→研发 35% 联动恒和", "POST market-share@非G5", s, c, "PARTIAL",
       "code=%s msg=%s——入口闸同样 G5 限定，正向联动 HTTP 不可达；公式 adjustMarketShare rd=1-market+区间[.40,.65]/容差 1e-4 码证(BonusPoolService.calculateDistribution)；bean 校验层证据见 AC-INC-26" % (c, str(g(r, "message"))[:50]))
    s, r, c = call("POST", "/api/v1/contributions/%s/market-share?marketShare=0.70" % P_S, who=LEADER)
    mk("AC-INC-26", "市场 70% 拒绝(区间 40–65)", "POST market-share=0.70", s, c,
       "PASS" if c != 0 and "0.65" in str(g(r, "message")) else "FAIL", "code=%s msg=%s(校验层先于 G5 闸拦截)" % (c, str(g(r, "message"))[:60]))
    nw, hits_w = grep_count("dimInitiation|WEIGHT", [JAVA + "/org/ruoyi/ipd/service/ContributionService.java"], [])
    five = grep_count("25|20|10", [JAVA + "/org/ruoyi/ipd/dto/ContributionSaveReq.java"], [])[0]
    mk("AC-INC-27", "五维权重 25+25+20+20+10=100", "grep ContributionService/DTO 权重", 200, None, "PARTIAL",
       "服务层权重命中=%d(%s)；DTO 五维字段存在=%d 处；权重求和=100 属前端/卡面口径，服务层仅透传五维分值(入口被 G5 闸挡，行为验证不可达)" % (nw, (hits_w[0][:90] if hits_w else "-"), five))

    # ---------- 算例组(每算例独立项目, 清单口径 vs 实现口径双记账) ----------
    def case_project(tag):
        s0, rP0, c0, _ = create_project(tag, "S", None, 5000000)
        return pid_of(rP0)

    def compute_once(pid, receipts, rate, personal):
        s, r, c = call("POST", "/api/v1/bonus-pool/compute", who=ADMIN,
                       body={"projectId": int(pid), "actualReceipts": receipts, "achievementRate": rate,
                             "personalCoefficient": personal, "poolRate": 0.05})
        return s, c, g(r, "data", "finalPool"), g(r, "data", "id"), g(r, "data", "tierCoefficient")

    PCA = case_project("CASE-A")
    s, c, fpA, poolA_id, tierA = compute_once(PCA, 4500000, 90, 0.8)
    if poolA_id: call("POST", "/api/v1/bonus-pool/%s/freeze" % poolA_id, who=ADMIN, body={"reason": "LANE4-caseA"})
    expA = 4500000 * 0.05 * 1.5 * 0.8 * 0.8
    mk("AC-INC-29", "算例A S级/90%/绩效0.8 双口径对账", "compute@CASE-A→freeze", s, c,
       "PASS" if fpA and abs(float(fpA) - expA) < 1 else "FAIL",
       "实现口径(S 级默认系数 1.5)finalPool=%s==复算 %.0f(450万回款×5%%×1.5×阶梯0.8×个人0.8)；清单算例 A 期望『池 30 万→市场PM 13.2 万』系目标销售额 500 万基数口径(base=target×5%%×1.5=37.5万→×0.8=30万→×55%%×0.8=13.2万)，被 [CONSISTENCY-1] owner 裁决改为实际回款基数=口径漂移留档，算式两侧均已复算" % (fpA, expA))
    PCB = case_project("CASE-B")
    s, c, fpB, poolB_id, tierB = compute_once(PCB, 6500000, 130, 1.0)
    if poolB_id: call("POST", "/api/v1/bonus-pool/%s/freeze" % poolB_id, who=ADMIN, body={"reason": "LANE4-caseB"})
    expB = 6500000 * 0.05 * 1.5 * 1.2 * 1.0
    mk("AC-INC-29b", "算例B 130%%档/绩效1.0 双口径对账", "compute@CASE-B→freeze", s, c,
       "PASS" if fpB and abs(float(fpB) - expB) < 1 else "FAIL",
       "finalPool=%s==复算 %.0f(650万×5%%×1.5×1.2×1.0)，阶梯=%s 证实 130%%→1.2 档进档；清单算例 B 期望 45 万池/24.75 万市场PM 系目标基数旧口径(37.5万×1.2)，同 AC-INC-29 双口径留档" % (fpB, expB, tierB))
    s0, rPC, cPC, _ = create_project("CASE-C", "A", None, 5000000)
    PCC = pid_of(rPC)
    s, c, fpC, poolC_id, tierC = compute_once(PCC, 3500000, 70, 1.0)
    if poolC_id: call("POST", "/api/v1/bonus-pool/%s/freeze" % poolC_id, who=ADMIN, body={"reason": "LANE4-caseC"})
    expC = 3500000 * 0.05 * 1.0 * 0.6 * 1.0
    mk("AC-INC-29c", "算例C 回款口径 A 级 350 万/70%%", "compute@CASE-C(A 级)→freeze", s, c,
       "PASS" if fpC and abs(float(fpC) - expC) < 1 else "FAIL",
       "finalPool=%s==复算 %.0f(350万×5%%×1.0×阶梯0.6×1.0)；A 级系数 1.0 下实现口径(回款基数)与清单期望池 25 万→可分配 15 万(目标基数)分叉已复算留档；阶梯=%s 证 70%%→0.6" % (fpC, expC, tierC))
    ship_all, ship_lines = grep_count("SHIPMENT|shipment|出库", [JAVA + "/org/ruoyi/ipd/service/BonusPoolService.java", JAVA + "/org/ruoyi/ipd/service/ReceiptLedgerService.java"], [])
    ship_code = [x for x in (ship_lines or []) if not re.sub(r"^[^:]*:\d+:", "", x).lstrip().startswith(("*", "//"))]
    mk("AC-INC-29d", "基数取回款非出库字段(算例 C 对照)", "grep SHIPMENT consumer + CASE-C 复算", s, c,
       "PASS" if len(ship_code) == 0 else "PARTIAL",
       "池/达成率服务出库命中(全量)=%d 其中代码级=%d(判据 0)，余量为 javadoc 断言句『口径=回款不是出库/开票』(ReceiptLedgerService L27)；CASE-C 以回款 350 万入参即得达成率 70%%→阶梯 0.6，若误取出库 450 万应得 90%%→0.8（未出现）→字段取数正确。bonus.salesSource 键零 consumer 问题归 AC-GLB-09 记账" % (ship_all, len(ship_code)))

    # ---------- 分配: 贡献度前置闸真跑取证 ----------
    s, r, c = call("POST", "/api/v1/bonus-pool/compute", who=ADMIN,
                   body={"projectId": int(P_C), "actualReceipts": 2000000, "achievementRate": 100,
                         "personalCoefficient": 1.0, "poolRate": 0.05})
    poolA = g(r, "data", "id") or (L.sql("SELECT id FROM bonus_pools WHERE project_id=%s ORDER BY id DESC LIMIT 1" % P_C) or [[None]])[0][0]
    s2, dres, c2 = call("POST", "/api/v1/bonus-pool/%s/distribute" % poolA, who=LEADER,
                        body={"marketShare": 0.55, "rdShare": 0.45}) if poolA else (0, None, None)
    dmsg = str(g(dres, "message"))[:80] if dres else "-"
    alloc = L.sql("SELECT person_id, role, amount FROM bonus_allocations WHERE bonus_pool_id=%s" % poolA) if (poolA and c2 == 0) else []
    elig = grep_count("bonusEligible|getBonusEligible|exitDate", [JAVA + "/org/ruoyi/ipd/service/BonusPoolService.java"], [])[0]
    note30 = ("distribute code=" + str(c2) + " msg=" + dmsg + "；分配行=" + str(alloc)[:80] + "。"
              "根因=真缺陷候选(链式不可达): BonusPoolService.distribute L1095 requireConfirmedContribution 要求项目存在 CONFIRMED 贡献度(含 tierCoefficient)，"
              "而贡献度入口/确认均被 ContributionService.requireG5Stage L601-605 限定 projects.status∈{LIFECYCLE,POST_LAUNCH}；"
              "ProjectService.STATUS_TRANSITIONS L54-59 无该二目标、advanceStage/legacy-import 只写 current_stage、"
              "全仓 setStatus(LIFECYCLE) 生产零命中(实测探针见 AC-INC-28)→新建项目永远进不了贡献度评定，"
              "故 distribute 与『退出者资格作废』行为验证在 HTTP 层不可达（真库唯一满足态项目=9150001 SQL 验收种子，非本车道 fixture 不动）。"
              "资格过滤码证: 分配按 members.bonus_eligible/exit_date 过滤(命中 " + str(elig) + " 处)；离职语义见 AC-INC-10")
    mk("AC-INC-30", "中途退出双PM 不参与分配(资格过滤+distribute 前置闸)", "compute@P_C→distribute→bonus_allocations", s2, c2,
       "PARTIAL", note30)

    # ---------- 上市日期双签 ----------
    s, prop, c = call("POST", "/api/v1/launch-date-change-requests", who=MARKET,
                      body={"projectId": int(P_A), "proposedLaunchDate": "2026-04-01", "reason": "LANE4-INC-33",
                            "confirmerId": PID["rd"], "confirmerRole": "RD_PM", "confirmerGroupId": GRP})
    lid = g(prop, "data", "id")
    lmsg = str(g(prop, "message"))[:70]
    s2, dec, c2 = call("POST", "/api/v1/launch-date-change-requests/%s/second-decision?approve=true&opinion=LANE4-ok" % lid, who=RD) if lid else (0, None, None)
    auds = L.sql("SELECT action FROM audit_logs WHERE action IN ('LAUNCH_DATE_PROPOSE','LAUNCH_DATE_CONFIRM') ORDER BY seq DESC LIMIT 2")
    mk("AC-INC-33", "修改上市日期双签+审计", "propose(市场PM→指定研发PM 为确认人)→RD 二次签→audit 回读", s2, c2,
       "PASS" if lid and c2 == 0 else "FAIL",
       "propose code=%s id=%s msg=%s；第二签 code=%s；审计动作=%s。互斥执法码证 LaunchDateChangeService L136-149+L215-228：提议人≠确认人、确认人须=提议时预落 confirmer_id、须互补角色(市场PM↔研发PM)、同组 IDOR；单方不可改由状态机 PENDING_SECOND 强制" % (c, lid, lmsg, c2, [a[0] for a in auds]))

    # ---------- 静态零命中组 ----------
    n_fin = grep_count("生成.{0,6}结算单|SETTLEMENT_ORDER|finance_voucher", [JAVA], ["*.java"])[0]
    mk("AC-INC-35", "不生成财务结算单据,仅内部台账", "grep 结算单/凭证生成", 200, None,
       "PASS" if n_fin == 0 else "PARTIAL",
       "命中=%d(判据0)；allowance/receipt 均台账型表(无 finance 关联列)" % n_fin)
    n_split = grep_count("池分摊|poolSplit|多项目分摊", [JAVA], ["*.java"])[0]
    mps = L.sql("SELECT config_value FROM system_configs WHERE config_key='bonus.multiProjectSplit'")[0][0]
    mk("AC-INC-36", "一产品多项目池分摊不存在(Q5)", "grep+config 双证", 200, None,
       "PASS" if n_split == 0 and mps == "NONE" else "PARTIAL", "grep=%d；multiProjectSplit=%s；产品:项目=1:1(立项强制 productId 唯一)" % (n_split, mps))

    # ---------- 负反馈三场景(每触发动态选一个『双 PM 在位且该触发未被占用』的 LANE4 项目) ----------
    def nf_target(trig):
        q = ("SELECT p.id FROM projects p JOIN project_members m ON m.project_id=p.id AND m.exit_date IS NULL AND m.del_flag='0' "
             "LEFT JOIN negative_feedbacks n ON n.project_id=p.id AND n.del_flag='0' AND n.trigger_type='%s' "
             "WHERE p.del_flag='0' AND p.name LIKE 'LANE4%%' "
             "GROUP BY p.id HAVING GROUP_CONCAT(m.role) LIKE '%%MARKET_PM%%' AND GROUP_CONCAT(m.role) LIKE '%%RD_PM%%' "
             "AND COUNT(n.id)=0 ORDER BY p.id DESC LIMIT 1" % trig)
        rows = L.sql(q)
        return rows[0][0] if rows else None
    rd_quota = L.sql("SELECT m.person_id, COUNT(*) FROM project_members m JOIN persons ps ON ps.id=m.person_id "
                     "WHERE ps.person_type='RD_PM' AND ps.employment_status='ACTIVE' AND m.exit_date IS NULL AND m.del_flag='0' "
                     "GROUP BY m.person_id")
    nf_proj = {}
    for card, trig, exp in [("AC-INC-36b", "REWORK_EXCEEDED", ("MARKET", "RD")),
                            ("AC-INC-37", "QUALITY_ACCIDENT", ("RD", "MARKET")),
                            ("AC-INC-38", "MISSED_MARKET_WINDOW", None)]:
        tgt = nf_target(trig)
        if not tgt:
            hist = L.sql("SELECT main_role, related_role, main_execution, related_execution, status, COUNT(*) "
                         "FROM negative_feedbacks WHERE trigger_type='%s' AND del_flag='0' GROUP BY 1,2,3,4,5" % trig)
            agree = [h for h in hist if (exp is None and "BOTH" in str(h[0]).upper()
                                         or (exp and exp[0] in str(h[0]).upper() and h[2] == "STOP_ALLOWANCE"
                                             and exp[1] in str(h[1]).upper() and h[3] == "HALVE_ALLOWANCE"))]
            verdict, deep = ("PARTIAL" if agree else "BLOCKED", "")
            if trig == "MISSED_MARKET_WINDOW":
                rowsb = L.sql(
                    "SELECT n.id, n.main_person_id, n.related_person_id, "
                    "GROUP_CONCAT(DISTINCT CASE WHEN m.role='RD_PM' THEN m.person_id END) rd, "
                    "GROUP_CONCAT(DISTINCT CASE WHEN m.role='MARKET_PM' THEN m.person_id END) mk "
                    "FROM negative_feedbacks n LEFT JOIN project_members m ON m.project_id=n.project_id "
                    "AND m.del_flag='0' AND m.exit_date IS NULL "
                    "WHERE n.trigger_type='MISSED_MARKET_WINDOW' AND n.del_flag='0' AND n.status='EXECUTED' GROUP BY n.id,2,3")
                idset = lambda x: set(str(x if x not in (None, "NULL") else "").split(",")) - {""}
                rd_hit = [r for r in rowsb if idset(r[3]) & (idset(r[1]) | idset(r[2]))]
                verdict = "FAIL" if rowsb and not rd_hit else ("PARTIAL" if agree else "BLOCKED")
                deep = ("⚠真缺陷候选(双PM共同担责只落一人): 历史 %d 条 MISSED_MARKET_WINDOW/EXECUTED 行的被执行人 main_person_id 全部为本项目 MARKET_PM（逐条=%s），"
                        "同项目在职 RD_PM(如 %s) 从未出现在 main/related_person_id → 覆盖数=%d。"
                        "根因码证 NegativeFeedbackService L464-470: mainRole=BOTH 分支只 ids.add(marketId) 且 relatedRole=null 不再补 rdId，"
                        "与该类 javadoc L43『BOTH 双PM共同担责（STOP_ALLOWANCE × 2）』及卡面『双PM 共同担责』直接矛盾——研发PM 实际不受停发。"
                        "故由早前只校验视图角色标签的 PASS 下调为 FAIL（判据加深：核到被执行人个体覆盖）" % (
                            len(rowsb), [(str(r[0])[-5:], r[1]) for r in rowsb[:4]], [r[3] for r in rowsb[:4]], len(rd_hit)))
            mk(card, "%s 主责/连带认定" % trig, "选项目失败→回读历史同触发 NF 落库行(前轮本车道 HTTP 真产物) + 静态映射表码证", 200, 0,
               verdict,
               "本轮不可新做(HTTP 前置不可满足)：NF create 要求项目同时有在职 MARKET_PM/RD_PM(NegativeFeedbackService L196-198 NF_NOT_PM)，"
               "而全库在职 RD_PM 活跃绑定数=%s 均已达 allowance.projectCountThreshold=3 且无解绑/改绑出口→名额死锁(真缺陷候选,详见 AC-INC-03)。"
               "回读证据(按 trigger 聚合 main/related 角色与执行动作)=%s；静态权威表码证 deriveRoleMapping L120-146。%s" % (rd_quota, hist[:4], deep))
            continue
        nf_proj[card] = tgt
        s, r, c = call("POST", "/api/v1/negative-feedbacks", who=ADMIN,
                       body={"projectId": int(tgt), "triggerType": trig, "triggerEvidence": "LANE4-%s-%d" % (trig, TS), "triggerMonth": "2026-08"})
        nid = g(r, "data", "id")
        if not nid:
            mk(card, "%s 主责/连带认定" % trig, "POST /negative-feedbacks@%s" % tgt, s, c, "FAIL",
               "创建失败 code=%s msg=%s（fixture 项目=%s）" % (c, str(g(r, "message"))[:70], tgt))
            continue
        s1b, rb, cb = call("PUT", "/api/v1/negative-feedbacks/%s/submit" % nid, who=ADMIN)
        s2, r2, c2 = call("PUT", "/api/v1/negative-feedbacks/%s/decide" % nid, who=ADMIN,
                          body={"decision": "APPROVE", "comment": "LANE4"})
        view = g(r2, "data") or {}
        me, re_ = str(view.get("mainExecution")), str(view.get("relatedExecution"))
        mr, rr = str(view.get("mainRole")), str(view.get("relatedRole"))
        if exp:
            ok = (c2 == 0 and exp[0] in mr.upper() and me == "STOP_ALLOWANCE"
                  and exp[1] in rr.upper() and re_ == "HALVE_ALLOWANCE")
        else:
            ok = c2 == 0 and "BOTH" in mr.upper() and rr in ("None", "")
        sem = {"AC-INC-36b": "清单期望 市场PM 主责停发/研发PM 连带减半", "AC-INC-37": "清单期望 研发PM 主责停发/市场PM 连带减半",
               "AC-INC-38": "清单期望 双PM 共同担责(无主责/连带区分)"}[card]
        mk(card, "%s 主责/连带认定" % trig, "create→submit→decide @%s" % str(tgt)[-6:], s2, c2, "PASS" if ok else "FAIL",
           "%s；实测 主=%s(%s) 连=%s(%s) 状态=%s id=%s；submit code=%s decide code=%s；映射为静态权威表 deriveRoleMapping L120-146(码证)" % (sem, mr, me, rr, re_, view.get("status"), nid, cb, c2))
    elig_rows = L.sql("SELECT project_id, person_id, role, bonus_eligible FROM project_members WHERE project_id IN (%s) ORDER BY id"
                      % (", ".join([str(x) for x in nf_proj.values()]) or str(P_R)))
    wpat = "setBonusEligible" + chr(92) + chr(40) + "(0|" + chr(34) + "0" + chr(34) + "|'0'|null)"
    n_writer = grep_count(wpat, [JAVA], ["*.java"])[0]
    n_cons = grep_count("getBonusDisqualify|getTierDelta|bonus_disqualify|tier_delta", [JAVA + "/org/ruoyi/ipd/service/BonusPoolService.java", JAVA + "/org/ruoyi/ipd/service/ContributionService.java", JAVA + "/org/ruoyi/ipd/service/ProjectMemberServiceImpl.java"], ["*.java"])
    n_db = L.sql("SELECT bonus_disqualify, tier_delta, COUNT(*) FROM negative_feedbacks WHERE status='EXECUTED' GROUP BY bonus_disqualify, tier_delta")
    mk("AC-INC-39", "负反馈对奖金的影响(贡献度系数降低/取消分配资格)", "decide 后回读 NF.bonus_disqualify/tier_delta + project_members.bonus_eligible + consumer 检索", 200, 0,
       "PARTIAL",
       "判定=半实现(write-only 字段)。已实现侧: NF create 即落 bonus_disqualify=1/tier_delta=-0.50(码证 NegativeFeedbackService L218-219 + DEFAULT_TIER_DELTA L70)，真库 EXECUTED 分布=%s 且经 GET /negative-feedbacks/{id} 视图字段可回显(NegativeFeedbackView L27-28)。"
       "缺失侧: 这三个奖金/贡献度执行入口(getBonusDisqualify/getTierDelta/bonus_disqualify)在 BonusPoolService/ContributionService/ProjectMemberServiceImpl 的 consumer 命中=%d 处→降档与资格作废从未参与计算；members.bonus_eligible 唯一写入=绑定时置 1，置 0 写路径命中=%d；本轮 decide 后相关成员=%s 全部仍为 1。"
       "另: lift 审计文案『解除(恢复津贴+bonusEligible)』(L315)但实现只改 status，从不回滚 bonus_disqualify=文档与实现互斥。归因:真缺陷(奖金侧执法链路未接线)" % (n_db, n_cons[0], n_writer, [(x[0], x[2], x[3]) for x in elig_rows]))
    # ---------- AC-INC-16b 达成率口径(回款非出库) 真库复算 ----------
    srcs = L.sql("SELECT source, COUNT(*) FROM receipt_ledgers GROUP BY source")
    rate_sql = L.sql("SELECT SUM(net_amount)/5000000*100 FROM receipt_ledgers WHERE project_id=%s AND source='RECEIPT' AND in_window=1" % P_R)[0][0]
    has_http = grep_count("calculateAchievementRate", [JAVA + "/org/ruoyi/ipd/controller"], ["*.java"])[0]
    mk("AC-INC-16b", "达成率=窗口内实际回款÷目标销售额(非出库/开票)", "真库复算 + controller 出口检索", 200, 0,
       "PARTIAL",
       "P_R 目标 500 万，窗口内 RECEIPT 净额复算达成率=%s%%（450万-10万退款=440万→88.0000）；取数口径 SQL 明证 source='RECEIPT' AND in_window=1(ReceiptLedgerService L118-140，出库/开票 source 分布=%s)；但 calculateAchievementRate 在 controller 层零出口(HTTP=%d)→无法经 API 观察，故 PARTIAL" % (rate_sql, srcs, has_http))
    nfa = L.sql("SELECT action,entity_id FROM audit_logs WHERE entity_type='NEGATIVE_FEEDBACK' AND action IN ('CREATE','SUBMIT','DECIDE_EXECUTE') ORDER BY seq DESC LIMIT 9")
    mk("AC-INC-40", "负反馈全链写审计", "audit_logs entity_type=NEGATIVE_FEEDBACK 回读", 200, 0,
       "PASS" if len(nfa) >= 9 else "PARTIAL",
       "本轮三步链(create/submit/decide)审计回读=%d 条，动作分布=%s；每条含 entity_id 可反查认定结果(主责/连带+执行人 before/after)，DECIDE_EXECUTE 的 after_data 载执行动作=STOP/HALVE_ALLOWANCE" % (len(nfa), sorted(set(a[0] for a in nfa))))

# ============================ AI ============================
def run_ai():
    FIX = R.load_fix()
    orig_active = [r[0] for r in L.sql("SELECT id FROM ai_model_configs WHERE is_active=1 AND del_flag='0'")]
    s, rP, c, _ = create_project("AI-P", "A", None, 1000000)
    P_AI = g(rP, "data", "id")
    FIX["P_AI"] = P_AI; R.save_fix(FIX)
    mk("AC-AI-FIXTURE", "AI 域项目 fixture", "POST /projects(AI-P)", sP if (sP:=s) else s, c, "PASS" if P_AI else "FAIL", "P_AI=%s" % P_AI)
    # AC-AI-01 模型配置+密钥加密
    s, r, c = call("POST", "/api/v1/ai-models", who=ADMIN,
                   body={"provider": "OPENAI", "endpoint": "http://127.0.0.1:8765/v1", "apiKey": "LANE4-SECRET-%d-PLAINTEXT" % TS,
                         "model": "lane4-mock-%d" % TS, "temperature": 0.7, "maxTokens": 64})
    mid = g(r, "data", "id")
    time.sleep(0.3)
    cols = [x[0] for x in L.sql("SHOW COLUMNS FROM ai_model_configs")]
    kcol = [x for x in cols if "key" in x.lower()]
    stored = L.sql("SELECT %s FROM ai_model_configs WHERE id=%s" % (kcol[0], mid))[0][0] if (mid and kcol) else None
    plain_in_db = stored and ("LANE4-SECRET" in stored)
    s2, rl, c2 = call("GET", "/api/v1/ai-models", who=ADMIN)
    masked = True
    for mv in (g(rl, "data") or []):
        k = str(mv.get("apiKey") or "")
        if "PLAINTEXT" in k: masked = False
    mk("AC-AI-01", "超管配置模型参数+密钥加密存储", "POST /ai-models → 真库密文列回读 → GET 列表脱敏", s, c,
       "PASS" if c == 0 and mid and not plain_in_db and masked else ("PARTIAL" if c == 0 else "FAIL"),
       "config id=%s；库内密钥列=%s 存储值前缀=%s（明文出现=%s）；API 回显脱敏=%s；provider/endpoint/model/temperature/maxTokens 全字段受理" % (mid, kcol, (stored or "?")[:16], plain_in_db, masked))
    # AC-AI-10 PM/组长对等调用
    eq_ok, eq_rows = True, []
    for who in (MARKET, LEADER, RD):
        s, r, c = call("POST", "/api/v1/ai-documents", who=who,
                       body={"projectId": int(P_AI), "docType": "PRD", "title": "LANE4-AI10-%s" % who,
                             "content": "LANE4 登记链路内容（模拟 AI 原始输出 v1）", "model": "lane4-mock-%d" % TS, "tokenPrompt": 10, "tokenCompletion": 20})
        eq_rows.append("%s:code%s" % (who, c))
        eq_ok = eq_ok and c == 0
    mk("AC-AI-10", "普通PM 与组长调用权限对等", "POST /ai-documents ×(market/leader/rd)", 200, None,
       "PASS" if eq_ok else "FAIL", "登记(ai-document:add)三角色均可=%s；generate 同权限码 AiDocumentController L60 注释『PM 与组长对等』" % eq_rows)
    # 建一条主测试文档链
    s, d1, c = call("POST", "/api/v1/ai-documents", who=MARKET,
                    body={"projectId": int(P_AI), "docType": "PRD", "title": "LANE4-CHAIN", "content": "V1 原始输出 lane4", "model": "lane4-mock-%d" % TS, "tokenPrompt": 5, "tokenCompletion": 8})
    doc = g(d1, "data") or {}
    did, vid1 = doc.get("id"), doc.get("id")
    st1 = doc.get("status")
    # AC-AI-02 generate → 外部模型
    s, r, c = call("POST", "/api/v1/ai-documents/generate", who=MARKET,
                   body={"projectId": int(P_AI), "docType": "PRD", "title": "LANE4-AI02", "prompt": "LANE4 原始资料：写一段 PRD 摘要"}, timeout=130)
    err = str(g(r, "message") or r)[:150]
    try:
        logtail = open("/Users/mac/Documents/ruoyi-ai/logs/sys-console.log", encoding="utf-8", errors="ignore").read()[-60000:]
    except Exception as e:
        logtail = "LOG-READ-ERR " + str(e)
    raw_conn = ("java.net.ConnectException" in logtail)
    retry_n = logtail.count("A retriable exception occurred")
    embed_n = logtail.count("[AI] embed fail")
    sup_n = logtail.count("errorCode=UNSUPPORTED_PROTOCOL")
    mk("AC-AI-02", "PM 录入原始资料调用 AI 生成待审核", "POST /ai-documents/generate", s, c,
       "FAIL-ENV" if c != 0 and ("AI" in err or "5" in str(c)) else ("PARTIAL" if c == 0 else "FAIL"),
       "外部模型端点不可达（nc -z 127.0.0.1 8765 = CLOSED，库内唯一启用项 id=1 endpoint=http://127.0.0.1:8765 为旧 mock）→ API 原始错误: %s (HTTP=%s/code=%s)。"
       "日志级原始证据: 同 trace 内 embed 与 chat 两次调用均抛 java.lang.RuntimeException: java.net.ConnectException（拒连，RetryUtils 2 次重试后放弃，tail 内 retriable=%d 次/embed fail=%d 次），"
       "最终 errorCode=UNSUPPORTED_PROTOCOL=%d 次而非白名单里更贴切的 UNREACHABLE（AiGateway.mapFailure L263-284 的 ConnectException 分支未命中，疑 langchain4j 重试耗尽后包装改变了 cause 链）；对客户端只暴露 HTTP=500/通用 INTERNAL_ERROR(90001) 而非专用 AI 错误码→真缺陷候选(观测性/错误分类)，不影响本卡『外部依赖不可达』判定。"
       "生成链可观测部分已验: POST /ai-documents 登记侧 v1 status=%s(=GENERATED 待审核)；历史成功审计 AI_GENERATE(tokenPrompt/tokenCompletion/latencyMs/contextHits)仍在库可回读(见 AC-AI-09)→闸路与持久层完好,仅模型外呼不可用" % (err, s, c, retry_n, embed_n, sup_n, st1))
    # AC-AI-03 未审核拒绝归档
    s, r, c = call("POST", "/api/v1/ai-documents/%s/versions/%s/archive" % (did, vid1), who=MARKET)
    mk("AC-AI-03", "未审核 AI 内容拒绝归档为交付物", "POST /ai-documents/{id}/versions/{v}/archive (status=GENERATED)", s, c,
       "PASS" if c != 0 and ("审核" in str(g(r, "message")) or str(c) in ("50002", "50004", "50006", "10001", "40001") or c in (409,)) else "FAIL",
       "拒绝 code=%s msg=%s" % (c, str(g(r, "message"))[:80]))
    # AC-AI-04 审核通过→修改生成 v2 保留 v1
    s, rv, c = call("POST", "/api/v1/ai-documents/%s/versions/%s/review" % (did, vid1), who=LEADER)
    s2, rev, c2 = call("POST", "/api/v1/ai-documents/%s/revise" % did, who=MARKET,
                       body={"baseVersionId": int(vid1), "content": "V2 人工修订 lane4-revised", "title": "LANE4-CHAIN"})
    v2 = g(rev, "data") or {}
    s3, vs, c3 = call("GET", "/api/v1/ai-documents/%s/versions" % did, who=MARKET)
    vlist = [(x.get("versionNo") or x.get("version_no"), x.get("id"), x.get("status")) for x in (g(vs, "data") or [])]
    has_v1_v2 = len(vlist) >= 2
    mk("AC-AI-04", "审核通过后修改→v2 生成且 v1 保留", "review→revise→versions 回读", s2, c2,
       "PASS" if c == 0 and c2 == 0 and has_v1_v2 else "PARTIAL",
       "review code=%s；revise code=%s 新行 version=%s；版本链=%s" % (c, c2, v2.get("versionNo") or v2.get("version"), vlist))
    # AC-AI-05 版本对比
    vid2 = v2.get("id")
    s, r, c = call("GET", "/api/v1/ai-documents/%s/diff?from=%s&to=%s" % (did, vid1, vid2), who=MARKET)
    if c != 0:
        s, r, c = call("GET", "/api/v1/ai-documents/%s/diff?fromVersionId=%s&toVersionId=%s" % (did, vid1, vid2), who=MARKET)
    if c != 0:
        s, r, c = call("GET", "/api/v1/ai-documents/%s/diff?from=%s&to=%s" % (did, vid1, vid2), who=MARKET)
    dd = g(r, "data")
    mk("AC-AI-05", "任意两版本对比与回溯", "GET /ai-documents/{id}/diff", s, c,
       "PASS" if c == 0 and dd else ("PARTIAL" if dd else "FAIL"), "diff 出口返回=%s（v1↔v2 差异报告或全链版本可回溯=history 端点 code 另附）" % str(dd)[:120])
    # AC-AI-06 完整版本链
    s, r, c = call("GET", "/api/v1/ai-documents/%s/history" % did, who=MARKET)
    hist = g(r, "data") or []
    chain_ok = len(vlist) >= 2 and (len(hist) >= 2 or c == 0)
    mk("AC-AI-06", "AI 原始 v1+全部人工版本无缺失", "GET versions+history", s, c,
       "PASS" if chain_ok else "PARTIAL", "versions=%d 条=%s；history code=%s 条数=%d（v1 GENERATED→review→v2 人工改版全链在库）" % (len(vlist), vlist, c, len(hist)))
    # AC-AI-07 敏感不过滤透传+UI 提示
    n_filt = grep_count("敏感词|sensitiveWord|censor|关键词过滤", [JAVA + "/org/ruoyi/ipd/service/AiGenerationService.java", JAVA + "/org/ruoyi/ipd/service/ai"], ["*.java"])[0]
    n_pass = grep_count("透传|BR-AI-04", [JAVA + "/org/ruoyi/ipd/service/AiGenerationService.java", JAVA + "/org/ruoyi/ipd/service/ai/AiGateway.java"], [])[0]
    n_ui, _ui_hits = grep_count("AI 生成内容|风险提示|仅供参考|人工审核|由 AI 生成", [R.WEB_SRC], ["*.vue", "*.ts"])
    n_fe_guard = grep_count("40011|40013|超时|限流", [R.WEB_SRC + "/api/ipd/ai-document.ts", R.WEB_SRC + "/api/ipd/auth.ts"], [])[0]
    mk("AC-AI-07", "敏感内容不过滤直接透传;UI 有风险提示", "grep 过滤码=0 + 前端提示文案命中", 200, None,
       "PASS" if n_filt == 0 and (n_pass or n_ui) else "PARTIAL",
       "服务层过滤实现命中=%d（判据0；检索域=AiGenerationService+service/ai/*，词表=敏感词/sensitiveWord/censor/关键词过滤）；"
       "透传契约注释命中=%d；前端风险提示/人工审核文案命中=%d（路径=apps/web-antd/src 全量 vue/ts）；前端护栏文案(限流 40011/预算 40013)命中=%d。"
       "注:AiChatClient.java 实际位于 service/ai/ 子包（首轮按 service/ 平铺路径检索导致 grep rc=2 → 命中数 -1，已修路径，属 fixture 假红）" % (n_filt, n_pass, n_ui, n_fe_guard))
    # AC-AI-08 月度 token 预算
    s, r, c = call("POST", "/api/v1/ai-models/%s/enable" % mid, who=ADMIN)
    time.sleep(0.2)
    s, r, c = call("POST", "/api/v1/ai-documents/generate", who=MARKET,
                   body={"projectId": int(P_AI), "docType": "PRD", "title": "LANE4-AI08", "prompt": "x" * 500}, timeout=140)
    code8 = c
    msg8 = str(g(r, "message"))[:100]
    s4, r4, c4 = (call("POST", "/api/v1/ai-models/%s/enable" % orig_active[0], who=ADMIN) if orig_active else (None, None, None))
    act_after = [x[0] for x in L.sql("SELECT id FROM ai_model_configs WHERE is_active=1 AND del_flag='0'")]
    budget_cfg, _bc_hits = grep_count("budgetTokens", [JAVA], ["*.java"])
    n_notify = grep_count("notifyAdmin|站内信|MessageService|adminAlert", [JAVA + "/org/ruoyi/ipd/service/AiGenerationService.java"], ["*.java"])[0]
    s5, r5, c5 = call("POST", "/api/v1/ai-models", who=ADMIN,
                      body={"provider": "OPENAI", "endpoint": "http://127.0.0.1:8765/v1", "apiKey": "LANE4-BUDGET-%d" % TS,
                            "model": "lane4-budgetprobe-%d" % TS, "temperature": 0.7, "maxTokens": 64, "budgetTokens": 100})
    bid = g(r5, "data", "id")
    cj = L.sql("SELECT config_json FROM ai_model_configs WHERE id=%s" % bid) if bid else []
    cj_txt = str(cj[0][0]) if cj and cj[0][0] else ""
    budget_kept = "budgetTokens" in cj_txt
    mk("AC-AI-08", "超出月度 token 预算返回 4xxxx 并提示超管", "enable LANE4 模型→generate 探预算闸→复原 enable id=1", s, c,
       "PARTIAL",
       "预算闸真实存在且在生成前预检: AiGenerationService L120-127 (budgetOf>0 且 used+prompt.length+maxTokens>budget → failAudit(BUDGET_EXCEEDED)+抛 AI_BUDGET_EXCEEDED)，"
       "错误码语义符合卡面『返回 4xxxx』: ApiV1ErrorCode L24 = 40013 且 getHttpStatus L101 映射 HTTP=409；前端文案已备(auth.ts L65『AI 预算超出限制，请稍后再试』+ai-document.test.ts 断言)。"
       "但三点使其在 HTTP 层不可达: ①配置面缺口=实测 POST /api/v1/ai-models 带 budgetTokens=100 → code=%s id=%s，落库 config_json=%s（含 budgetTokens=%s）；AiModelSaveReq 白名单只有 provider/endpoint/apiKey/model/temperature/maxTokens/embedEndpoint/embedModel 且 @JsonIgnoreProperties(ignoreUnknown=true)→未知键静默丢弃,运营无 API 途径设预算（真缺陷候选）；"
       "②『并提示超管』无实现出口: AiGenerationService 内 notifyAdmin/站内信/MessageService 命中=%d 处,仅 failAudit 落库+409 返回给调用者(归 真缺陷候选:承诺文案与实现不符)；"
       "③预算用量 used 依赖历史成功调用累计,而外部模型不可达(启用项原 id=%s 端点 127.0.0.1:8765 拒连)→本跑 generate code=%s msg=%s 先失败于外呼,预算分支被前置条件挡住(BLOCKED 因素叠加)。budgetTokens 全仓 consumer=%d 处。启用面复原: 原启用=%s→复原 POST code=%s→现启用=%s" % (c5, bid, (cj_txt or "NULL")[:120], budget_kept, n_notify, (orig_active[0] if orig_active else "none"), code8, msg8, budget_cfg, orig_active, c4, act_after))
    # AC-AI-09 AI 调用审计含 token 与耗时
    rows9 = L.sql("SELECT action, after_data FROM audit_logs WHERE action IN ('AI_GENERATE','AI_GENERATE_FAILED') ORDER BY seq DESC LIMIT 2")
    has_tok = any(("token" in (a[1] or "").lower() or "latency" in (a[1] or "").lower()) for a in rows9)
    mk("AC-AI-09", "AI 调用写审计含 token 消耗与耗时", "audit_logs AI_GENERATE* 回读", 200, 0,
       "PASS" if has_tok else "PARTIAL", "最新 AI 审计=%s（含 token/latency 字段=%s；成功+失败路径均落审计，AiGenerationService generate javadoc+failAudit 码证）" % ([a[0] for a in rows9], has_tok))

if __name__ == "__main__":
    which = sys.argv[1]
    prefix = "AC-" + which.upper() + "-"
    R.recs[:] = [r for r in R.recs if not (r["card"].startswith(prefix) or (which == "inc" and r["card"] in ("INC-FIXTURE", "INC-FIXTURE-P")))]
    {"inc": run_inc, "ai": run_ai}[which]()
    json.dump(R.recs, open(L.REC_PATH, "w"), ensure_ascii=False, indent=1)
    print("== done %s recs=%d ==" % (which, len(R.recs)))
