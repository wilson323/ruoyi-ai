#!/usr/bin/env python3
"""Explicit authorized local acceptance modes; never approve documents or Gate."""
import concurrent.futures
import datetime
import importlib.util
import json
import pathlib
import sys
import re
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location('readonly', HERE / 'runtime-acceptance-20261002.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)
PROJECT = '2103659612308828162'
PREVIOUS = '2106061095016882177'
DOCUMENT = '2106061816428781569'
mode = sys.argv[1]
assert mode in {'inventory', 'reject', 'create', 'poll', 'apply', 'stale-create'}
_, login = r.call('POST', '/auth/login', body={'username': 'ipd-admin', 'password': r.accounts()['ipd-admin']})
assert login.get('code') == 0
token = login['data'].get('token') or login['data'].get('accessToken')

def call(method, path, body=None):
    assert path.startswith(('/agent-runs/', '/ai-documents/', '/projects/' + PROJECT))
    req = urllib.request.Request(r.BASE + path, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(req, timeout=45) as response:
            return {'http': response.status, 'body': json.load(response)}
    except urllib.error.HTTPError as error:
        return {'http': error.code, 'body': json.load(error)}

report = {'at': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'mode': mode,
          'projectId': PROJECT, 'previousRunId': PREVIOUS, 'targetDocumentId': DOCUMENT}
if mode in {'inventory', 'reject', 'create', 'stale-create'}:
    report['versionsBefore'] = call('GET', '/ai-documents/' + DOCUMENT + '/versions')
    assert report['versionsBefore']['body'].get('code') == 0
    versions = report['versionsBefore']['body']['data']
    head = max(versions, key=lambda row: row['versionNo'])
    for row in versions:
        row.pop('content', None)  # original history stays in authoritative DB; avoid preserving model reasoning
    if mode == 'inventory':
        report['previous'] = call('GET', '/agent-runs/' + PREVIOUS)
        report['capabilities'] = call('GET', '/projects/' + PROJECT + '/agent-capabilities')
    elif mode == 'reject':
        assert str(head['id']) == DOCUMENT and head['status'] in {'GENERATED', 'REJECTED'}
        report['response'] = call('POST', '/ai-documents/' + DOCUMENT + '/versions/' + str(head['id']) + '/reject',
            {'comment': '退回：上一版受工程仓库AGENTS规约误注入影响，未实际调用本次已选MCP工具，并捏造挂载工具数量。请实际调用本次两项已选工具，按真实返回区分项目已审核文档、系统产品知识库和其他产品资料；不得要求产品线展示名映射，不得推测工具数量，不得将EC-100等其他型号套入当前产品；缺失事实明确未取得，不下业务完成或Gate结论。'})
    else:
        if mode == 'create':
            assert head['status'] == 'REJECTED' and str(head['id']) == DOCUMENT
        payload = {'capabilityPackCode': 'market-research', 'capabilityPackVersion': 'v1',
            'modelConfigId': '2104885081318375426', 'skillNames': ['competitor-analysis-ipd'],
            'toolIds': ['project_knowledge_search', 'FastGPT-mcp-693fdc63b24e7762a0dbfe86'],
            'actionCode': 'C02', 'previousRunId': PREVIOUS, 'targetDocumentId': DOCUMENT,
            'baseVersionId': DOCUMENT,
            'message': '根据本次明确关联原文档的退回意见重新核对并修订。请实际调用本次两项工具，简短输出资料核对和缺项；正确区分权限内项目已审核文档、系统产品知识库及远端MCP其他产品资料，不把其他产品事实套入当前产品。缺失事实明确未取得，不推算数字，不宣称动作完成或Gate通过。',
            'idempotencyKey': 'ipd-handoff2-context-v3-20261002-' + mode}
        report['request'] = payload
        report['response'] = call('POST', '/projects/' + PROJECT + '/agent-runs', payload)
elif mode == 'poll':
    run = sys.argv[2]
    assert run.isdigit()
    report['run'] = call('GET', '/agent-runs/' + run)
    events, cursor = [], 0
    for _ in range(100):
        page = call('GET', '/agent-runs/' + run + '/events?afterSeq=' + str(cursor))
        assert page['body'].get('code') == 0
        data = page['body']['data']
        events.extend(data.get('events', []))
        next_cursor = data.get('nextSeq', cursor)
        report['terminal'] = data.get('terminal')
        if data.get('terminal') or next_cursor <= cursor:
            break
        cursor = next_cursor
    report['events'] = [e for e in events if e.get('type') not in {'TEXT_DELTA','THINK_DELTA','REASONING_DELTA'}]
    report['eventCount'] = len(events)
elif mode == 'apply':
    run, artifact = sys.argv[2:4]
    assert run.isdigit() and artifact.isalnum()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        report['responses'] = list(pool.map(lambda _: call('POST', '/agent-runs/' + run + '/artifacts/' + artifact + '/apply', {}), range(3)))
    report['versionsAfter'] = call('GET', '/ai-documents/' + DOCUMENT + '/versions')

out = HERE / ('handoff2-context-v3-' + mode + ('-' + sys.argv[2] if len(sys.argv) > 2 else '') + '-20261002.json')
if out.exists():
    out = out.with_name(out.stem + '-' + datetime.datetime.now(datetime.timezone.utc).strftime('%H%M%S%f') + out.suffix)
out.write_text(json.dumps(r.redact(report), ensure_ascii=False, indent=2))
summary = {'evidence': str(out), 'mode': mode}
if 'response' in report:
    summary['response'] = {'http':report['response']['http'],'code':report['response']['body'].get('code'),'data':report['response']['body'].get('data') if mode in {'create','stale-create'} else None}
if 'responses' in report:
    summary['responses'] = [{'http':x['http'],'code':x['body'].get('code'),'documentId':(x['body'].get('data') or {}).get('documentId')} for x in report['responses']]
if 'run' in report:
    summary['run'] = {k:v for k,v in (report['run']['body'].get('data') or {}).items() if k in {'runId','status','errorCode'}}
    summary['terminal'] = report.get('terminal')
    summary['eventCount'] = report.get('eventCount')
print(json.dumps(r.redact(summary), ensure_ascii=False))
