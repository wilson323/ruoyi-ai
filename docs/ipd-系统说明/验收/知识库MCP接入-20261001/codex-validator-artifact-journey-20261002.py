#!/usr/bin/env python3
"""Local acceptance only. Preserves fixtures; never applies documents or business approvals."""
import argparse, datetime, hashlib, importlib.util, json, os, pathlib, re
import urllib.error, urllib.parse, urllib.request

HERE = pathlib.Path(__file__).parent
spec = importlib.util.spec_from_file_location('readonly', HERE / 'runtime-acceptance-20261002.py')
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['prepare', 'create', 'poll', 'resume-plan', 'download'])
    parser.add_argument('--run')
    parser.add_argument('--version')
    args = parser.parse_args()
    sha = os.environ.get('IPD_NATIVE_CANDIDATE_SHA', '')
    assert re.fullmatch('[0-9a-f]{64}', sha), 'immutable loaded candidate SHA required'
    report = {'candidateSha256': sha, 'mode': args.mode,
              'at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
              'fixturePolicy': 'retain; no document apply, owner publication, review or Gate'}
    def login(account):
        status, body = r.call('POST', '/auth/login', body={'username': account, 'password': r.accounts()[account]})
        assert status == 200 and body.get('code') == 0, 'Person login failed'
        return body['data'].get('token') or body['data'].get('accessToken')
    token = login('ipd-market')
    def request(method, path, body=None, session=token, binary=False):
        assert path.startswith('/agent-runs/') or path.startswith('/projects/9140005/')
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(r.BASE + path, data=data, method=method,
              headers={'Authorization': 'Bearer ' + session, 'Content-Type': 'application/json'})
        try:
            response = urllib.request.urlopen(req, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            mime = response.headers.get_content_type()
            if binary and response.status == 200 and mime == 'application/octet-stream':
                return {'http': response.status, 'mime': mime, 'byteLength': len(raw),
                        'sha256': hashlib.sha256(raw).hexdigest()}
            return {'http': response.status, 'mime': mime, 'body': json.loads(raw)}
    def events(run):
        result, cursor = [], 0
        for _ in range(100):
            page = request('GET', f'/agent-runs/{run}/events?afterSeq={cursor}')
            assert page['body'].get('code') == 0
            data = page['body']['data']
            result.extend(data.get('events', []))
            next_seq = data.get('nextSeq', cursor)
            if next_seq <= cursor or data.get('terminal'):
                return result
            cursor = next_seq
        raise AssertionError('event pagination incomplete')
    if args.mode == 'prepare':
        report['capabilities'] = request('GET', '/projects/9140005/agent-capabilities')
    elif args.mode == 'create':
        message = ('按已确认计划执行\n'
                   '1. 本次仅验收隔离工作区测试附件；不要文档定档、审核、技能发布、Gate、外部消息或业务动作批准，不推断业务事实或操作权限。\n'
                   '2. 调用 write_file 写 validator-report.txt，正文为 IPD native artifact acceptance；必须实际写文件，不只在聊天展示。\n'
                   '3. 调用 plan_enter({})；随后 plan_write({"content":"完整记录本次测试文件、交付步骤、风险和验证方法"})。\n'
                   '4. 调用 plan_exit({"summary":"仅交付本次隔离测试附件"})，停在官方权限 ASK，等待同一发起人再次确认，不提前交付。\n'
                   '5. 官方 ASK 确认恢复后，调用 deliver_artifact 交付 validator-report.txt。\n'
                   '6. 调用 execute 在隔离工作区创建 validator-binary.bin，实际字节准确为 00 01 ff；随后调用 deliver_artifact 交付该文件。')
        report['request'] = {'capabilityPackCode': 'market-research', 'capabilityPackVersion': 'v1',
            'modelConfigId': '2104885081318375426', 'skillNames': [],
            'toolIds': [], 'message': message, 'idempotencyKey': 'codex-validator-artifact-' + sha[:20]}
        report['create'] = request('POST', '/projects/9140005/agent-runs', report['request'])
    else:
        assert args.run and re.fullmatch('[0-9]+', args.run), 'string run ID required'
        run = args.run
        report['runId'] = run
        report['detail'] = request('GET', f'/agent-runs/{run}')
        rows = events(run)
        report['events'] = [e for e in rows if e.get('type', e.get('eventType')) != 'TEXT_DELTA']
        if args.mode == 'resume-plan':
            waits = [e for e in rows if e.get('payload', {}).get('reason') == 'AGUI_INTERRUPT']
            assert waits, 'no persisted official interruption'
            wait = waits[-1]
            pending = wait['payload']['interrupts']
            assert pending and all(i.get('reason') == 'tool_call' and i.get('metadata', {}).get('toolName') == 'plan_exit' for i in pending.values()), 'only test plan_exit confirmation authorized'
            report['resumeRequest'] = {'expectedPauseSeq': wait['seq'], 'aguiInput': {
                'threadId': run, 'runId': run, 'messages': [], 'tools': [], 'context': [],
                'state': {}, 'forwardedProps': {}, 'resume': [
                    {'interruptId': key, 'status': 'resolved', 'payload': {'approved': True}} for key in pending]}}
            report['resume'] = request('POST', f'/agent-runs/{run}/resume', report['resumeRequest'])
        if args.mode == 'download':
            assert args.version and re.fullmatch('[0-9]+', args.version), 'string version ID required'
            matches = [e['payload'] for e in rows if e.get('payload', {}).get('versionId') == args.version and e['payload'].get('attachmentOrigin') == 'IPD_NATIVE_DELIVERY_V1']
            assert len(matches) == 1, 'unique genuine native origin required'
            path = f'/agent-runs/{run}/artifacts/versions/{args.version}/download'
            report['ownerDownload'] = request('GET', path, binary=True)
            attachment = matches[0]['attachment']
            assert report['ownerDownload'].get('sha256') == matches[0]['contentHash']
            assert report['ownerDownload'].get('byteLength') == attachment['byteLength']
            others = [name for name in r.accounts() if name != 'ipd-market']
            assert others, 'second real Person needed'
            report['nonOwnerAccount'] = others[0]
            report['nonOwnerDownload'] = request('GET', path, session=login(others[0]), binary=True)
            assert report['nonOwnerDownload']['http'] == 403, 'nonowner must receive HTTP403'
            report['history'] = request('GET', '/projects/9140005/agent-runs?limit=100')
    output = HERE / ('codex-validator-artifact-' + sha[:16] + '-' + args.mode + ('-' + args.run if args.run else '') + '-' + datetime.datetime.now().strftime('%H%M%S%f') + '.json')
    output.write_text(json.dumps(r.redact(report), ensure_ascii=False, indent=2))
    print(json.dumps({'evidence': str(output), 'mode': args.mode}, ensure_ascii=False))

if __name__ == '__main__':
    main()
