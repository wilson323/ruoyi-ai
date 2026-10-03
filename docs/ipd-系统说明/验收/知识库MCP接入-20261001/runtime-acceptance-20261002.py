#!/usr/bin/env python3
"""Read-only runtime inventory. Only POST allowed is local Person login; no business writes."""
import json,re,urllib.request,urllib.error,datetime,pathlib
ROOT=pathlib.Path('/Users/mac/Documents/ruoyi-ai')
BASE='http://127.0.0.1:16039/api/v1'
OUT=pathlib.Path(__file__).with_name('runtime-readonly-20261002.json')
PROJECT='9140005'
def redact(v):
    if isinstance(v,dict):
        return {k:('[redacted]' if (k.lower() in ['token','accesstoken','refreshtoken','idtoken','authorization'] or any(s in k.lower() for s in ['password','apikey','endpoint','url','secret'])) else redact(x)) for k,x in v.items()}
    if isinstance(v,list):return [redact(x) for x in v]
    if isinstance(v,str):return re.sub(r'https?://[^\s\"<>]+','[remote-address-redacted]',v)
    return v
def call(method,path,token=None,body=None):
    assert method=='GET' or (method=='POST' and path=='/auth/login'), 'business mutation forbidden'
    h={'Accept':'application/json'}
    if token:h['Authorization']='Bearer '+token
    if body is not None:h['Content-Type']='application/json'
    req=urllib.request.Request(BASE+path,data=json.dumps(body).encode() if body is not None else None,headers=h,method=method)
    try:
        with urllib.request.urlopen(req,timeout=20) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:
        try:return e.code,json.load(e)
        except Exception:return e.code,{'error':'non-json HTTP response'}
    except Exception as e:return -1,{'error':type(e).__name__}
def accounts():
    cfg=ROOT/'.codex/ipd-dev/config';d=json.load(open(cfg/'credentials.json'));out={}
    for k,v in d.get('accounts',{}).items():
        if isinstance(v,dict) and v.get('password'):out[k]=v['password']
    for k,v in d.items():
        if k.startswith('ipd_qa_pwd_'):out.setdefault(k[len('ipd_qa_pwd_'):],v)
    for line in (cfg/'dev-accounts.yaml').read_text().splitlines():
        m=re.match(r'\s*\|\s*`(ipd-[a-z]+)`\s*\|\s*`([^`]+)`',line)
        if m:out.setdefault(m[1],m[2])
    return out
def main():
    report={'timestampUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'projectId':PROJECT,'scope':'Person login + GET only','accounts':[]}
    for username,password in accounts().items():
        status,data=call('POST','/auth/login',body={'username':username,'password':password})
        row={'account':username,'loginHttp':status,'loginCode':data.get('code')};report['accounts'].append(row)
        if data.get('code')!=0:continue
        session=data.get('data') or {};token=session.get('token') or session.get('accessToken')
        row['personSession']=redact({k:v for k,v in session.items() if 'token' not in k.lower()})
        if not token:continue
        row['reads']={}
        for path in ['/projects/'+PROJECT,'/projects/'+PROJECT+'/agent-capabilities','/projects/'+PROJECT+'/agent-runs?limit=5','/ai-documents?projectId='+PROJECT]:
            http,payload=call('GET',path,token);row['reads'][path]={'http':http,'payload':redact(payload)}
    OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print(json.dumps({'evidence':str(OUT),'accounts':[{'account':x['account'],'loginHttp':x['loginHttp'],'loginCode':x['loginCode'],'readCodes':{k:v['payload'].get('code') for k,v in x.get('reads',{}).items()}} for x in report['accounts']]},ensure_ascii=False))

if __name__ == '__main__':
    main()
