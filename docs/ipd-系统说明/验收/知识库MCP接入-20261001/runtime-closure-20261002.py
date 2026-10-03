#!/usr/bin/env python3
"""Authorized local IPD acceptance. Explicit modes; stable idempotency keys; no approval/Gate operations."""
import importlib.util,json,sys,urllib.request,urllib.error,pathlib,datetime,concurrent.futures,time,urllib.parse
HERE=pathlib.Path(__file__).parent
spec=importlib.util.spec_from_file_location('readonly',HERE/'runtime-acceptance-20261002.py')
r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
if len(sys.argv) < 2:
    raise SystemExit("usage: runtime-execution-20261002.py create-local|create-mcp|poll RUN_ID|apply-concurrent RUN_ID ARTIFACT_ID")
mode=sys.argv[1]
if mode not in {"create-local", "create-local-correction", "create-mcp", "create-mcp-clean", "poll", "apply-concurrent"}:
    raise SystemExit("unsupported mode")
username='ipd-market'
_,session=r.call('POST','/auth/login',body={'username':username,'password':r.accounts()[username]})
assert session.get('code')==0,'Person login failed'
token=session['data'].get('token') or session['data'].get('accessToken')
assert token, 'missing session token'
def request(method,path,body=None):
    assert path.startswith('/agent-runs/') or path=='/projects/9140005/agent-runs'
    req=urllib.request.Request(r.BASE+path,data=json.dumps(body).encode() if body is not None else None,
       headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method=method)
    try:
        with urllib.request.urlopen(req,timeout=40) as response:return {'http':response.status,'body':json.load(response)}
    except urllib.error.HTTPError as e:return {'http':e.code,'body':json.load(e)}
report={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'mode':mode,'personAccount':username}
if mode in ['create-local','create-local-correction','create-mcp','create-mcp-clean']:
    tools=['project_knowledge_search']
    message='请根据项目事实和已有退回意见，检索海康威视资料，形成简短的竞品证据核验草稿。准确引用研发投入原句、年份、金额和研发费用率，指出资料对本项目智能锁联动分析的局限及未取得事项。不要宣称完成动作或通过评审。'
    if mode=='create-local-correction':
        message='上次草稿因加入未获支持的数字和推算被拒绝。请重新调用知识检索，仅核验海康威视资料中的研发投入一行；输出简短草稿，带已有退回意见、来源文件名、该行原句和对本项目适用性的一句局限。不要扩充其他公司或财务指标，不做换算和计算；未取得原始年报正文应明说。不要宣布动作完成或评审通过。'
    if mode in ['create-mcp','create-mcp-clean']:
        tools=['FastGPT-mcp-69cce91596a40120630b2017']
        message='请基于当前项目事实，调用本次已选熵基互联知识库查询智能锁联动相关产品能力。生成简短的资料核验草稿：区分远端应用回答和原始出处；查不到就明确说明未取得，不编造参数，不宣称动作完成或评审通过。'
    payload={'capabilityPackCode':'market-research','capabilityPackVersion':'v1','modelConfigId':'2104885081318375426','skillNames':['competitor-analysis-ipd'],'toolIds':tools,'actionCode':'C02','message':message,'idempotencyKey':'codex-as-closure-20261002-'+mode}
    report['request']=payload;report['response']=request('POST','/projects/9140005/agent-runs',payload)
elif mode=='poll':
    run=sys.argv[2];assert run.isdigit()
    report['run']=request('GET','/agent-runs/'+run)
    events=[];cursor=0
    for _ in range(100):
        page=request('GET',f'/agent-runs/{run}/events?afterSeq={cursor}')
        assert page['body'].get('code')==0
        data=page['body']['data'];events.extend(data.get('events',[]));n=data.get('nextSeq',cursor)
        report['terminal']=data.get('terminal');report['nextSeq']=n
        if data.get('terminal'):break
        if n <= cursor:
            break
        cursor=n
    report['events']=[e for e in events if e.get('eventType') != 'TEXT_DELTA']
    report['run']=request('GET','/agent-runs/'+run)
    report['pollIncomplete']=not bool(report.get('terminal'))
elif mode=='apply-concurrent':
    run,artifact=sys.argv[2:4];assert run.isdigit() and artifact.strip()
    # Logical artifactId from ARTIFACT event; versionId is not an apply identifier.
    artifact=urllib.parse.quote(artifact.strip(), safe='')
    path=f'/agent-runs/{run}/artifacts/{artifact}/apply'
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        report['responses']=list(pool.map(lambda _:request('POST',path,{}),range(3)))
else:raise ValueError('unsupported mode')
output=HERE/('closure-runtime-'+mode+('-'+sys.argv[2] if len(sys.argv)>2 else '')+'-20261002.json')
output.write_text(json.dumps(r.redact(report),ensure_ascii=False,indent=2))
summary={'evidence':str(output),'mode':mode}
if 'response' in report:summary['response']=report['response']
if 'run' in report:summary.update({'run':report['run']['body'].get('data'),'terminal':report.get('terminal'),'events':len(report['events'])})
if 'responses' in report:summary['responses']=report['responses']
print(json.dumps(r.redact(summary),ensure_ascii=False))
