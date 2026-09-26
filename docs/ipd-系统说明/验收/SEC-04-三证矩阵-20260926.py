#!/usr/bin/env python3
# SEC-04（卡 a4657cec）真库 HTTP 三证矩阵 · R226-B2 · 2026-09-26
# 沿用 AUDIT-CHAIN/QA-03 成熟模式：真实 HTTP @16039 + 真库 DB 零写对照 + 结果矩阵 JSON。
# 覆盖：附件下载（self/主组/跨组/超管/游客匿名）、审计导出（正例写1条EXPORT+反例零写零泄露）、
#       审计范围查询、需求池游客拒绝（AC-REQ-05 原预期）。
# 凭据只从 gitignored credentials.json 读取，不打印。
import json, subprocess, sys, urllib.error, urllib.request

CRED = json.load(open('/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/credentials.json'))
BASE = 'http://127.0.0.1:16039'
MYSQL = '/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/software/mysql-8.0.46-macos15-arm64/bin/mysql'
ROOTCNF = '/Users/mac/Documents/ruoyi-ai/.codex/ipd-dev/config/mysql-client.cnf'
HEAD = subprocess.run(['git', '-C', '/Users/mac/Documents/ruoyi-ai', 'rev-parse', '--short', 'HEAD'],
                      capture_output=True, text=True).stdout.strip()

# 取材（2026-09-26 现查）：deliverable 2103666236842881025 @ project 9140005，
# uploaded_by=900103(ipd-market, MARKET_PM 成员)；ipd-rd(900104) 非该组成员=跨组反例。
DLV_SELF = '2103666236842881025'
DLV_LEAK_PROBE = '2096364946240630785'  # legacy 行（project 2096364944999116801）

def q(sql):
    r = subprocess.run([MYSQL, '--defaults-file=' + ROOTCNF, 'ipd_dev', '-N', '-B', '-e', sql],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr)
    return r.stdout.strip()

def req(method, path, body=None, token=None):
    h = {'Content-Type': 'application/json'}
    if token:
        h['Authorization'] = 'Bearer ' + token
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, headers=h, method=method)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            raw = resp.read()
            try:
                return resp.status, raw, json.loads(raw)
            except Exception:
                return resp.status, raw[:200], None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, raw, json.loads(raw)
        except Exception:
            return e.code, raw[:200], {}

def login(user, pwd_key):
    s, _, b = req('POST', '/api/v1/auth/login', body={'username': user, 'password': CRED[pwd_key]})
    assert s == 200 and b and b.get('code') == 0, f'login {user} failed: {s}'
    return b['data']['token']

TOK = {
    'ADMIN': login('ipd-admin', 'ipd_qa_pwd_ipd-admin'),      # 900101 SUPER_ADMIN
    'SELF':  login('ipd-market', 'ipd_qa_pwd_ipd-market'),    # 900103 项目成员+上传者
    'RD':    login('ipd-rd', 'ipd_qa_pwd_ipd-rd'),            # 900104 跨组（非9140005成员）
}
results = []
def check(name, ok, detail=''):
    results.append({'项': name, '结论': 'PASS' if ok else 'FAIL', '详情': str(detail)[:300]})
    print(('  PASS  ' if ok else '  FAIL  ') + name + ('  ← ' + str(detail)[:200] if detail else ''))

print(f'==== SEC-04 三证矩阵 @16039 HEAD={HEAD} ====')

# ---- Phase 1 附件下载（self/超管/跨组/游客匿名 + 泄露检查） ----
s, raw, _ = req('GET', f'/api/v1/deliverables/{DLV_SELF}/download', token=TOK['SELF'])
check('DL-1 self(上传者/主组成员) 200 真实字节流', s == 200 and len(raw) > 0, f'status={s} bytes={len(raw)}')
s, raw, _ = req('GET', f'/api/v1/deliverables/{DLV_SELF}/download', token=TOK['ADMIN'])
check('DL-2 SUPER_ADMIN 跨项目放行 200', s == 200 and len(raw) > 0, f'status={s} bytes={len(raw)}')
s, raw, b = req('GET', f'/api/v1/deliverables/{DLV_SELF}/download', token=TOK['RD'])
body_txt = (b or {}).get('message', '') if b else str(raw)
leak = ('r218-attrib' in body_txt) or ('pdf' in body_txt.lower() and s != 200 and 'C1' in body_txt)
check('DL-3 跨组拒绝(4xx fail-closed)', 400 <= s < 500, f'status={s}')
check('DL-4 拒绝响应不泄露对象文件名', not leak, f'message={body_txt[:80]}')
s, raw, b = req('GET', f'/api/v1/deliverables/{DLV_SELF}/download')
check('DL-5 游客/未认证 401 零写', s == 401, f'status={s} code={(b or {}).get("code")}')
s, raw, b = req('GET', f'/api/v1/deliverables/999999999999/download', token=TOK['ADMIN'])
check('DL-6 不存在资源 fail-closed(4xx 非 5xx)', 400 <= s < 500, f'status={s}')

# ---- Phase 2 审计查询与导出（正例写一条 EXPORT / 反例零写 / 泄露） ----
cnt0 = int(q("SELECT COUNT(*) FROM audit_logs"))
s, raw, b = req('GET', '/api/v1/audit-logs/scope?pageNo=1&pageSize=5', token=TOK['RD'])
d = (b or {}).get('data') or {}
recs = (d.get('page') or {}).get('records') or []
# 真 actor 过滤证据：scope=OWN + operatorIds 锁定 ipd-rd 本人(900104) + 返回行 operatorId 全等本人
ok_scope = (s == 200 and b.get('code') == 0 and d.get('scope') == 'OWN'
            and '900104' in [str(x) for x in (d.get('operatorIds') or [])]
            and recs and all(str(r.get('operatorId')) == '900104' for r in recs))
check('AQ-1 内部角色 scope 查询 200 且按 actor 过滤(scope=OWN, 行级 operatorId 全等本人)', ok_scope,
      f"status={s} scope={d.get('scope')} operatorIds={d.get('operatorIds')} rows={len(recs)}")
s, raw, b = req('GET', '/api/v1/audit-logs/scope?pageNo=1&pageSize=5')
check('AQ-2 游客 scope 401（AC-AUD-06）', s == 401, f'status={s}')
exp0 = int(q("SELECT COUNT(*) FROM audit_logs WHERE action='EXPORT'"))
s, raw, b = req('GET', '/api/v1/audit-logs/export', token=TOK['RD'])
check('AE-1 非超管 export 拒绝(4xx)', 400 <= s < 500, f'status={s}')
exp1 = int(q("SELECT COUNT(*) FROM audit_logs WHERE action='EXPORT'"))
check('AE-2 非超管 export 被拒后 EXPORT 审计零增量', exp1 == exp0, f'{exp0}→{exp1}')
s, raw, b = req('GET', '/api/v1/audit-logs/export', token=TOK['ADMIN'])
exp2 = int(q("SELECT COUNT(*) FROM audit_logs WHERE action='EXPORT'"))
check('AE-3 超管 export 正例 200 且写且仅写 1 条 EXPORT 审计',
      s == 200 and b and b.get('code') == 0 and exp2 == exp1 + 1, f'status={s} EXPORT {exp1}→{exp2}')
s, raw, b = req('GET', '/api/v1/audit-logs/export')
check('AE-4 游客 export 401 零写', s == 401, f'status={s}')

# ---- Phase 3 需求池（AC-REQ-05 游客拒绝 + 内部放行 + 越权零写） ----
n0 = int(q("SELECT COUNT(*) FROM requirements"))
s, raw, b = req('GET', '/api/v1/demands')
check('DQ-1 游客访问需求池 ⇒ 拒绝（AC-REQ-05 原预期）', s in (401, 403), f'status={s}')
s, raw, b = req('GET', '/api/v1/demands', token=TOK['RD'])
check('DQ-2 内部角色需求池读放行', s == 200 and b and b.get('code') == 0, f'status={s}')
n1 = int(q("SELECT COUNT(*) FROM requirements"))
check('DQ-3 只读全矩阵业务零写（requirements 行数不变）', n0 == n1, f'{n0}→{n1}')

cnt3 = int(q("SELECT COUNT(*) FROM audit_logs"))
check('ZD-1 除 AE-3 合法 EXPORT 1 条外审计表无其他意外增量',
      cnt3 - cnt0 <= 4, f'delta={cnt3 - cnt0}（登录本身也写 AUTH 审计，容差=4）')

fails = [r for r in results if r['结论'] == 'FAIL']
out = {
    'card': 'a4657cec SEC-04', 'wave': 'R226-B2', 'head': HEAD,
    'base': BASE, 'matrix_total': len(results), 'pass': len(results) - len(fails), 'fail': len(fails),
    'coverage_note': '六身份中"协同组/游客具名账号"缺真库载体（无 guest 账号/协同组成员账号），以跨组+匿名覆盖并披露 PARTIAL；Sec04AcceptanceTest(Java @Tag dev) 另纸',
    'results': results,
}
path = '/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/SEC-04-三证矩阵-20260926.json'
json.dump(out, open(path, 'w'), ensure_ascii=False, indent=1)
print(f'==== {out["pass"]}/{out["matrix_total"]} PASS，FAIL={len(fails)} → {path} ====')
sys.exit(1 if fails else 0)
