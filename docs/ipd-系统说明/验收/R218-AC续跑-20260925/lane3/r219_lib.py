#!/usr/bin/env python3
# R219 lane3 公共库（拷贝自 R218-QA08 r218_lib.py；REC_PATH/写库清单改指 lane3 自有文件；BASE=B39 16039）
import hashlib, json, subprocess, threading, time, urllib.request, urllib.error, uuid
from datetime import datetime, timezone, timedelta

REPO = "/Users/mac/Documents/ruoyi-ai"
CNF  = REPO + "/.codex/ipd-dev/config/mysql-client.cnf"
MYSQL = "mysql"
B39 = "http://127.0.0.1:16039"   # 本波次 fresh jar（含 R218 全部代码 + 上传链修复），唯一执行基线
BASE = B39
TZ = timezone(timedelta(hours=8))
ACCOUNTS_FILE = REPO + "/.codex/ipd-dev/config/bootstrap-accounts.json"

def now(): return datetime.now(TZ).isoformat(timespec="seconds")

import re as _re
_DEVACC = REPO + "/.codex/ipd-dev/config/dev-accounts.yaml"
def _pwd(username):
    # 测试账号凭证来源：既有验收配置 dev-accounts.yaml 表格（凭证仅内存使用，绝不打印）
    t = open(_DEVACC, encoding="utf-8").read()
    for u, pw in _re.findall(r"\|\s*`(ipd-[a-z0-9-]+|sysadmin)`\s*\|\s*`([^`]+)`", t):
        if u == username: return pw
    raise KeyError(username)

_tok = {}
def login(base, username):
    key = (base, username)
    if key in _tok: return _tok[key]
    s, b, _, _ = req("POST", base, "/api/v1/auth/login", body={"username": username, "password": _pwd(username)})
    tok = None
    if s == 200 and isinstance(b, dict) and b.get("code") == 0:
        tok = (b.get("data") or {}).get("token")
    _tok[key] = tok
    return tok

def req(method, base, path, token=None, body=None, raw=None, content_type=None, timeout=25):
    url = base + path
    data = None
    headers = {}
    if raw is not None:
        data = raw
        headers["Content-Type"] = content_type
    elif body is not None:
        data = json.dumps(body, ensure_ascii=False).encode()
        headers["Content-Type"] = "application/json"
    if token: headers["Authorization"] = "Bearer " + token
    r = urllib.request.Request(url, method=method, data=data)
    for k, v in headers.items(): r.add_header(k, v)
    try:
        with urllib.request.urlopen(r, timeout=timeout) as resp:
            rb = resp.read()
            try: bj = json.loads(rb.decode("utf-8", "replace"))
            except Exception: bj = None
            return resp.status, bj, dict(resp.headers), rb
    except urllib.error.HTTPError as e:
        rb = e.read()
        try: bj = json.loads(rb.decode("utf-8", "replace"))
        except Exception: bj = None
        return e.code, bj, dict(e.headers or {}), rb
    except Exception as e:
        return -1, {"error": str(e)[:160]}, {}, b""

def sql(q):
    r = subprocess.run([MYSQL, "--defaults-extra-file=" + CNF, "ipd_dev", "-N", "-B", "-e", q],
                       capture_output=True, text=True, timeout=30)
    if r.returncode != 0:
        raise RuntimeError("SQL-ERR " + r.stderr.strip()[:200])
    return [line.split("\t") for line in r.stdout.strip().splitlines() if line != ""]

def multipart(fields, file_field, filename, file_bytes, ctype):
    bd = uuid.uuid4().hex
    body = b""
    for k, v in fields.items():
        body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n" % (bd, k, v)).encode()
    body += ("--%s\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n" % (bd, file_field, filename, ctype)).encode()
    body += file_bytes + ("\r\n--%s--\r\n" % bd).encode()
    return body, "multipart/form-data; boundary=" + bd

# 执行记录（lane3 自有）
REC_LOCK = threading.Lock()
REC_PATH = REPO + "/docs/ipd-系统说明/验收/R218-AC续跑-20260925/lane3/执行清单-lane3.json"
# 写库登记清单（lane3 自有）：凡走 HTTP 的写操作逐条登记
WRI_LOCK = threading.Lock()
WRI_PATH = REPO + "/docs/ipd-系统说明/验收/R218-AC续跑-20260925/lane3/写库清单-lane3.json"

def record(recs, case):
    case.setdefault("ts", now())
    recs.append(case)
    with REC_LOCK:
        with open(REC_PATH, "w") as f:
            json.dump(recs, f, ensure_ascii=False, indent=1)
    v = case["verdict"]
    print("[%s] %s %s -> %s %s" % (v, case["card"], case["case"],
          "http=" + str(case.get("http")), (case.get("note") or "")[:110]), flush=True)

_wri_cache = None
def write_reg(op, method, path, entity="", note=""):
    """登记一次 HTTP 写操作（POST/PUT/DELETE 到业务表）。"""
    global _wri_cache
    with WRI_LOCK:
        if _wri_cache is None:
            try:
                _wri_cache = json.load(open(WRI_PATH))
            except Exception:
                _wri_cache = []
        _wri_cache.append({"ts": now(), "lane": "lane3", "op": op, "method": method,
                           "path": path, "entity": entity, "note": note})
        with open(WRI_PATH, "w") as f:
            json.dump(_wri_cache, f, ensure_ascii=False, indent=1)

def code_of(bj): return bj.get("code") if isinstance(bj, dict) else None
def msg_of(bj):  return (bj.get("message") or bj.get("msg") or "") if isinstance(bj, dict) else ""
def data_of(bj): return (bj.get("data") if isinstance(bj, dict) else None)
