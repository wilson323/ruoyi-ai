#!/usr/bin/env python3
# R218 SEC-04 环境类 FAIL 复测归因（只写归因产物；凭证仅内存使用，绝不打印）
import hashlib, json, os, re, subprocess, sys
LIB = "/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R218-QA08-SEC04-20260925"
sys.path.insert(0, LIB)
import r218_lib as L

OUT = os.path.dirname(os.path.abspath(__file__)) + "/sec04-env-复测记录.json"
recs = json.load(open(OUT)) if os.path.exists(OUT) else []

def save():
    with open(OUT, "w") as f:
        json.dump(recs, f, ensure_ascii=False, indent=1)

def add(**kw):
    kw.setdefault("ts", L.now())
    recs.append(kw); save()
    print("[%s] %s" % (kw.get("case"), json.dumps({k: kw[k] for k in kw if k not in ("case",)}, ensure_ascii=False)[:400]), flush=True)

def code_of(bj): return bj.get("code") if isinstance(bj, dict) else None
def msg_of(bj):  return (bj.get("message") or bj.get("msg") or (bj.get("error") if isinstance(bj, dict) else "")) if isinstance(bj, dict) else ""

# 0) 环境基线快照
mh = subprocess.run(["curl", "-s", "-m", "5", "-o", "/dev/null", "-w", "%{http_code}",
                     "http://127.0.0.1:9000/minio/health/live"], capture_output=True, text=True).stdout
dp = subprocess.run(["docker", "ps", "--format", "{{.Names}}|{{.Status}}|{{.Image}}"], capture_output=True, text=True).stdout
minio_line = next((l for l in dp.splitlines() if "minio" in l), "NOT-FOUND")
add(scope="env-baseline", case="E1-新16039存活+minio容器状态",
    cmd="docker ps (只读，不重启他人容器); curl :9000/minio/health/live",
    result={"http_16039_pm_directory": subprocess.run(["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", L.B39 + "/api/v1/pm/directory"], capture_output=True, text=True).stdout,
            "minio_container": minio_line, "minio_health_9000": mh},
    attribution="待复测")

# 1) A1/A1R 复测：ipd-market 在新 16039 重跑上传登记
T = {}
for u in ("ipd-market", "ipd-admin"):
    T[u] = L.login(L.B39, u)
    add(scope="prep", case="PRE-登录%s@新16039" % u, cmd="POST /api/v1/auth/login (%s)" % u,
        result={"http": 200 if T[u] else "?", "token_obtained": bool(T[u])}, attribution="前置")

ACT = L.sql("SELECT id FROM stage_actions WHERE project_id=9140005 AND action_code='D02' AND del_flag='0'")[0][0]
PDF = b"%PDF-1.4\nR218-FAIL-ATTRIB-20260925-probe " + os.urandom(2048) + b"\n%%EOF"
SHA = hashlib.sha256(PDF).hexdigest()
body, ct = L.multipart({}, "file", "r218-attrib-probe.pdf", PDF, "application/pdf")
s, bj, _, rb = L.req("POST", L.B39, "/api/v1/deliverables/upload?actionId=" + ACT, token=T["ipd-market"], raw=body, content_type=ct, timeout=90)
DEL = ((bj or {}).get("data") or {}).get("id") if isinstance(bj, dict) else None
add(scope="A1/A1R x5", case="R-本人上传登记(market@D02@9140005)@新16039",
    cmd="POST %s/api/v1/deliverables/upload?actionId=%s (ipd-market, multipart pdf 2KB)" % (L.B39, ACT),
    original_error="http=-1 连接层失败(首波5条: A1@16039旧jar x1, A1R@16046 x4)",
    result={"http": s, "envelope_code": code_of(bj), "msg": msg_of(bj)[:120], "deliverable_id": DEL},
    attribution="")

if s == -1:
    # 定位：应用拒连 vs minio 侧 —— 先纯连接探针
    probe = subprocess.run(["curl", "-sS", "-m", "10", "-o", "/dev/null",
                            "-w", "connect=%{time_connect}s http=%{http_code}", L.B39 + "/api/v1/auth/login"],
                           capture_output=True, text=True)
    verbose = subprocess.run(["curl", "-v", "-m", "90", "-X", "POST",
                              L.B39 + "/api/v1/deliverables/upload?actionId=" + ACT,
                              "-H", "Authorization: Bearer " + (T["ipd-market"] or ""),
                              "-F", "file=@/dev/null;filename=probe.pdf;type=application/pdf"],
                             capture_output=True, text=True)
    vb = re.sub(r"Authorization: Bearer \S+", "Authorization: Bearer <REDACTED>", verbose.stderr)
    add(scope="A1/A1R x5", case="R2-curl-verbose定位", cmd="curl -v POST upload (-H token已脱敏; -F /dev/null占位)",
        result={"connect_probe": probe.stdout + probe.stderr[:200], "curl_verbose_tail": vb[-2000:]},
        attribution="")
else:
    add(scope="A1/A1R x5", case="R2-连接层探针(证明应用可连)",
        cmd="curl -m10 16039/api/v1/auth/login", result={"probe": subprocess.run(
            ["curl", "-sS", "-m", "10", "-o", "/dev/null", "-w", "connect=%{time_connect}s http=%{http_code}", L.B39 + "/api/v1/auth/login"], capture_output=True, text=True).stdout},
        attribution="应用侧连接正常，无拒连")

# DB 回读 + 下载闭环（若上传成功）
if DEL:
    row = L.sql("SELECT content_hash,file_size,uploaded_by,project_id,oss_id,create_time FROM deliverables WHERE id=%s" % DEL)[0]
    s2, _, h2, rb2 = L.req("GET", L.B39, "/api/v1/deliverables/%s/download" % DEL, token=T["ipd-market"])
    add(scope="A1/A1R x5", case="R3-真库回读+本人下载字节一致",
        cmd="mysql ipd_dev 'SELECT ... FROM deliverables WHERE id=%s'; GET /deliverables/%s/download@16039" % (DEL, DEL),
        result={"db_hash_match": row[0] == SHA, "db_size": row[1], "uploaded_by": row[2], "project_id": row[3],
                "oss_id": row[4], "download_http": s2, "download_bytes": len(rb2), "download_sha_match": hashlib.sha256(rb2).hexdigest() == SHA},
        attribution="")
    n = L.sql("SELECT COUNT(*) FROM audit_logs WHERE entity_type='DELIVERABLE' AND entity_id=%s" % DEL)[0][0]
    add(scope="A1/A1R x5", case="R4-上传审计行回读", cmd="mysql audit_logs DELIVERABLE/%s count" % DEL,
        result={"rows": int(n)}, attribution="")

# 2) C7 复测：新 jar demand detail 端点
DID = "2103338131276259329"  # 真库 requirements 实际存在(id查得 del_flag=0)
for path, tag in (("/api/v1/demands/%s" % DID, "原FAIL命令路径"), ("/api/v1/demands/%s/detail" % DID, "用户指定detail路径")):
    s3, bj3, _, _ = L.req("GET", L.B39, path, token=T["ipd-admin"])
    add(scope="C7", case="R-C7 需求详情%s@新16039" % tag, cmd="GET %s%s (ipd-admin)" % (L.B39, path),
        original_error="http=404 code=50001 (旧jar无端点)",
        result={"http": s3, "envelope_code": code_of(bj3), "msg": msg_of(bj3)[:100],
                "data_id": (((bj3 or {}).get("data") or {}) or {}).get("id") if isinstance((bj3 or {}).get("data"), dict) else None},
        attribution="")

# 3) sysadmin 附带核查（登录实测 + 真库只读；已在探索阶段完成SQL，这里补一次登录留证）
s4, bj4, _, _ = L.req("POST", L.B39, "/api/v1/auth/login", body={"username": "sysadmin", "password": L._pwd("sysadmin")})
add(scope="sysadmin核查", case="R-sysadmin登录@新16039", cmd="POST /api/v1/auth/login {username:sysadmin}",
    original_error="报密码错(ipd-admin正常)",
    result={"http": s4, "envelope_code": code_of(bj4), "msg": msg_of(bj4)[:80]}, attribution="")

q = ("SELECT id,username,account_status,employment_status,del_flag,must_change_pwd,create_time,update_time,last_login_at "
     "FROM persons WHERE username='sysadmin' OR name='sysadmin' OR employee_no='sysadmin'")
r1 = L.sql(q)
q2 = "SELECT COUNT(*) FROM persons WHERE id BETWEEN 80001 AND 80999"
r2 = L.sql(q2)
q3 = "SELECT user_id,user_name,status,del_flag,create_time,update_time FROM sys_user WHERE user_name LIKE '%sysadmin%'"
r3 = L.sql(q3)
add(scope="sysadmin核查", case="R-真库只读核对(persons/sys_user)",
    cmd="mysql ipd_dev SELECT persons WHERE username/name/employee_no='sysadmin' (含软删); id 80001-80999 计数; sys_user LIKE '%sysadmin%'",
    result={"persons_sysadmin_rows": r1, "persons_id_80001_80999_count": int(r2[0][0]), "sys_user_sysadmin_rows": r3,
            "note": "persons现存 SUPER_ADMIN: 900101 ipd-admin(update_time=2026-09-26 07:57:14) 及 2096266884100935682 系统管理员(username=中文'系统管理员', 非'sysadmin')"},
    attribution="")

print("retest done ->", OUT)
