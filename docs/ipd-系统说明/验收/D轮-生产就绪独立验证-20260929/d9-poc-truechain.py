import json, urllib.request, urllib.error, time, re, sys
BASE="http://127.0.0.1:16039"
CRED=json.load(open(".codex/ipd-dev/config/credentials.json"))
out={"base":BASE,"ts":time.strftime("%Y-%m-%dT%H:%M:%S%z"),"cases":[]}
def http(method,path,body=None,headers=None,timeout=90):
    req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body else None,
        method=method,headers={"Content-Type":"application/json",**(headers or {})})
    try:
        with urllib.request.urlopen(req,timeout=timeout) as r:
            return r.status, r.read().decode("utf-8","replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8","replace")
    except Exception as e:
        return -1, f"{type(e).__name__}: {e}"
# case1 未登录
st,bd=http("GET","/poc/kernel/chat/stream?text=hi&projectId=P1&agentId=emp-a1&sessionId=S-noauth")
out["cases"].append({"id":"noauth_fail_closed","status":st,"body_head":bd[:200],"scope_frames":re.findall(r"event:scope",bd)})
# IPD login -> platform token
st,bd=http("POST","/api/v1/auth/login",{"username":"ipd-admin","password":CRED["ipd_qa_pwd_ipd-admin"]})
tok=json.loads(bd)["data"]["token"] if st==200 else None
out["ipd_login"]="OK" if tok else f"FAIL {st} {bd[:150]}"
st,bd=http("POST","/api/v1/auth/platform-token",headers={"Authorization":f"Bearer {tok}"})
pt=cli=None
if st==200:
    d=json.loads(bd); pt=d["data"]["token"]; cli=d["data"]["clientId"]
    out["platform_exchange"]=f"OK platformUser={d['data'].get('platformUser')}"
else:
    out["platform_exchange"]=f"FAIL {st} {bd[:150]}"; sys.exit(json.dump(out,open("/tmp/poc-truechain-20260929.json","w")) or 2)
h={"Authorization":f"Bearer {pt}","clientid":cli}
uid=None
# 取会话 userId（platformUser 即 sys_user 用户名，身份数值从 scope 帧断言）
# case2 登录态真链 SSE
st,bd=http("GET","/poc/kernel/chat/stream?text=%E5%8F%AA%E5%9B%9E%E5%A4%8D%E4%B8%A4%E4%B8%AA%E5%AD%97%EF%BC%9A%E6%94%B6%E5%88%B0&projectId=P1&agentId=emp-a1&sessionId=S-auth-1",headers=h)
scopes=re.findall(r"data:(pP1:u\d+@aemp-a1:s\S+)",bd)
deltas=len(re.findall(r"text_delta",bd))
out["cases"].append({"id":"auth_scope_folding","status":st,"scope_frames":scopes[:2],"text_delta_frames":deltas,"has_kernel_error":"KERNEL_ERROR" in bd,"err_head":(re.search(r"KERNEL_ERROR[^\n]{0,240}",bd).group(0)[:240] if "KERNEL_ERROR" in bd else None),"resp_len":len(bd),"tail":bd[-200:] if len(bd)>400 else None})
# case3 ':' 注入 fail-closed
st,bd=http("GET","/poc/kernel/chat/stream?text=x&projectId=P1%3Aevil&agentId=emp-a1&sessionId=S-bad",headers=h,timeout=30)
out["cases"].append({"id":"colon_fail_closed","status":st,"scope_leak":":evil" in bd,"has_kernel_error":"KERNEL_ERROR" in bd,"err_head":(re.search(r"KERNEL_ERROR[^\n]{0,240}",bd).group(0)[:240] if "KERNEL_ERROR" in bd else None),"body_head":bd[:200]})
json.dump(out,open("/tmp/poc-truechain-20260929.json","w"),ensure_ascii=False,indent=1)
print(json.dumps(out,ensure_ascii=False,indent=1)[:1600])
