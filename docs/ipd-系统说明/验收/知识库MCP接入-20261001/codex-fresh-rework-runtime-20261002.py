#!/usr/bin/env python3
"""Local acceptance helper. Modes are separate; importing never logs in or mutates."""
import argparse
import datetime
import hashlib
import importlib.util
import json
import pathlib
import re
import subprocess
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).parent
PROJECT = '2103659612308828162'
PREVIOUS = '2106061095016882177'
TARGET = '2106061816428781569'
TERMINAL = {'SUCCEEDED', 'FAILED', 'CANCELLED'}

def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['inventory', 'create', 'poll', 'apply'])
    parser.add_argument('--account', default='ipd-admin')
    parser.add_argument('--previous-run-id', default=PREVIOUS)
    parser.add_argument('--target-document-id', default=TARGET)
    parser.add_argument('--diagnostic-only', action='store_true')
    parser.add_argument('--refresh-pending', action='store_true')
    parser.add_argument('--model-id')
    parser.add_argument('--tool', action='append', default=[])
    parser.add_argument('--run-id')
    parser.add_argument('--artifact-id')
    parser.add_argument('--quality-verdict-file', type=pathlib.Path)
    parser.add_argument('--loaded-jar-sha256')
    parser.add_argument('--timeout', type=int, default=900)
    args = parser.parse_args()
    previous_id, target_id = args.previous_run_id, args.target_document_id
    assert previous_id.isdigit() and target_id.isdigit(), 'Previous/target IDs must be decimal strings'
    assert not (args.diagnostic_only and args.mode == 'apply'), 'Diagnostic artifacts must not be applied by this helper'
    assert not args.refresh_pending or (args.mode == 'create' and not args.diagnostic_only), 'Pending refresh is a separate create mode'
    if args.mode in {'create', 'apply'}:
        assert args.loaded_jar_sha256 and re.fullmatch('[a-fA-F0-9]{64}', args.loaded_jar_sha256), 'A must supply verified loaded new JAR hash'
    if args.mode == 'create':
        assert args.model_id and args.model_id.isdigit() and args.tool, 'Select current available model/tools after inventory'
        if args.diagnostic_only:
            assert len(args.tool) == 1 and args.tool[0].startswith('FastGPT-mcp-'), 'Diagnostic mode selects exactly one existing product-line knowledge service'
    if args.mode in {'poll', 'apply'}:
        assert args.run_id and args.run_id.isdigit()
    if args.mode == 'apply':
        assert args.artifact_id and re.fullmatch('[A-Za-z0-9_-]+', args.artifact_id)
        assert args.quality_verdict_file, 'A independent quality verdict required'
        verdict = json.loads(args.quality_verdict_file.read_text())
        assert verdict.get('reviewer') == 'A' and verdict.get('verdict') == 'ALLOW_APPLY'
        assert str(verdict.get('runId')) == args.run_id and verdict.get('artifactId') == args.artifact_id
        assert re.fullmatch('[a-fA-F0-9]{64}', verdict.get('contentSha256', ''))
    spec = importlib.util.spec_from_file_location('readonly', HERE / 'runtime-acceptance-20261002.py')
    r = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(r)
    _, login = r.call('POST', '/auth/login', body={'username': args.account, 'password': r.accounts()[args.account]})
    assert login.get('code') == 0
    token = login['data'].get('token') or login['data'].get('accessToken')
    assert token
    def call(method, path, body=None):
        assert path.startswith(('/agent-runs/', '/ai-documents/', '/projects/' + PROJECT))
        headers = {'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'}
        request = urllib.request.Request(r.BASE + path, method=method, headers=headers,
            data=json.dumps(body).encode() if body is not None else None)
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return {'http': response.status, 'body': json.load(response)}
        except urllib.error.HTTPError as error:
            return {'http': error.code, 'body': json.load(error)}
    def ok(response):
        assert response['http'] == 200 and response['body'].get('code') == 0
        return response['body']['data']
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    report = {'atUtc': stamp, 'mode': args.mode, 'projectId': PROJECT,
              'loadedJarSha256': args.loaded_jar_sha256, 'diagnosticOnly': args.diagnostic_only,
              'previousRunId': previous_id, 'targetDocumentId': target_id}
    if args.mode in {'inventory', 'create'}:
        capabilities = call('GET', '/projects/' + PROJECT + '/agent-capabilities')
        ok(capabilities)
        previous = ok(call('GET', '/agent-runs/' + previous_id))
        versions = ok(call('GET', '/ai-documents/' + target_id + '/versions'))
        head = max(versions, key=lambda row: int(row['versionNo']))
        assert str(head['projectId']) == PROJECT and head['docType'] == 'MARKET_RESEARCH'
        head_id = str(head['id'])
        assert head_id.isdigit()
        query = 'SELECT id,project_id,doc_type,status,version_no FROM ai_documents WHERE id=' + head_id + ';'
        db = subprocess.run(['/opt/homebrew/bin/mysql', '--defaults-extra-file=' + str(r.ROOT / '.codex/ipd-dev/config/mysql-client.cnf'),
            '--batch', '--raw', '--skip-column-names', 'ipd_dev', '-e', query], capture_output=True, text=True, check=True)
        values = db.stdout.strip().split('\t')
        expected = [head_id, PROJECT, head['docType'], head['status'], str(head['versionNo'])]
        assert values == expected, 'Fresh HTTP and DB head disagree'
        archives = previous.get('artifactArchives', [])
        assert previous['status'] in TERMINAL and previous.get('actionCode') == 'C02'
        assert str(previous.get('projectId')) == PROJECT
        assert any(str(item.get('documentId')) == target_id for item in archives), 'Target must belong to previous APPLIED artifact'
        previous_query = 'SELECT id,project_id,action_code,status FROM ipd_agent_run WHERE id=' + previous_id + '; SELECT COUNT(*) FROM ipd_agent_artifact_version WHERE run_id=' + previous_id + " AND document_id=" + target_id + " AND status='APPLIED';"
        previous_db = subprocess.run(['/opt/homebrew/bin/mysql', '--defaults-extra-file=' + str(r.ROOT / '.codex/ipd-dev/config/mysql-client.cnf'),
            '--batch', '--raw', '--skip-column-names', 'ipd_dev', '-e', previous_query], capture_output=True, text=True, check=True)
        previous_rows = previous_db.stdout.strip().splitlines()
        assert previous_rows[0].split('\t') == [previous_id, PROJECT, 'C02', previous['status']]
        assert int(previous_rows[1]) > 0, 'DB previous/target artifact relation disagrees with HTTP'

        report.update({'capabilities': capabilities, 'previous': {k: previous.get(k) for k in ['runId', 'projectId', 'status', 'actionCode', 'artifactArchives']},
            'versions': [{k: row.get(k) for k in ['id','parentVersionId','versionNo','status','contentSha256','reviewComment']} for row in versions],
            'httpDbHeadMatch': True, 'dbHead': expected})
        if args.mode == 'create':
            if args.diagnostic_only:
                assert head['status'] in {'REJECTED', 'GENERATED'}, 'Diagnostic base must be current rejected or pending-review head'
                message = '本次仅做知识库安全诊断：仅实际调用本次唯一选定知识库工具一次，问题严格为「竞品」。不调用其他工具、不补查或重试。只报告本次只读查询状态与资料是否取得，缺失写未取得；不比较产品，不生成业务完成结论，不宣称C02完成或Gate通过，不定档、不审核。'
            elif args.refresh_pending:
                assert head['status'] == 'GENERATED', 'Pending refresh requires the actual current pending-review head'
                message = '当前待审核缺项稿的知识库查询故障已修复。本次另开关联资料刷新尝试，按本次冻结C02技能取得项目知识和已选产品线知识库资料，生成可独立检查的竞品分析资料稿。逐条区分项目已审核文档、系统知识片段和其他产品资料；缺失事实写未取得，不编造数字、不把其他产品参数用于当前产品。数字引用保持来源原值，不报告检索相似度等技术指标。本次核对反馈：用途未声明，按技能默认四维齐全、篇幅克制；不要把可选用途列为缺项或阻塞。面向使用者用白话注明来源类别和资料编号，不显示内部字段名或状态枚举。保留当前文档待审核状态，不退回、不审核、不自动定档，不宣称动作完成或Gate通过。'
            else:
                assert head['status'] == 'REJECTED' and head.get('reviewComment'), 'Use actual current rejection, never reject automatically'
                message = '依据本次明确关联文档当前退回意见重新核对资料。实际调用本次已选工具，逐条区分项目已审核文档、系统知识片段和其他产品资料；缺失事实写未取得，不编造数字、不把其他产品参数用于当前产品，不宣称动作完成或Gate通过。'
            payload = {'capabilityPackCode': 'market-research', 'capabilityPackVersion': 'v1',
                'modelConfigId': args.model_id, 'skillNames': ['competitor-analysis-ipd'], 'toolIds': args.tool,
                'actionCode': 'C02', 'previousRunId': previous_id, 'targetDocumentId': target_id, 'baseVersionId': head_id,
                'idempotencyKey': ('codex-diagnostic-' if args.diagnostic_only else 'codex-fresh-rework-') + stamp,
                'message': message}
            report['request'] = payload
            report['response'] = call('POST', '/projects/' + PROJECT + '/agent-runs', payload)
    elif args.mode == 'poll':
        cursor, deadline = 0, time.monotonic() + args.timeout
        report.update({'runId': args.run_id, 'sources': [], 'usage': [], 'artifacts': []})
        while time.monotonic() < deadline:
            run = ok(call('GET', '/agent-runs/' + args.run_id))
            page = ok(call('GET', '/agent-runs/' + args.run_id + '/events?afterSeq=' + str(cursor)))
            for event in page.get('events', []):
                payload = event.get('payload') or {}
                if not isinstance(payload, dict):
                    continue
                if event.get('type') == 'SOURCE':
                    entry = {k: payload.get(k) for k in ['tool', 'toolCallId','retrievalStatus','citationStatus','hits','sourceEvidence','reasonCode','mcpFailureStage','mcpFailureReason','mcpErrorTypes','mcpSdkFrames']}
                    # RunHandle deliberately removes citationText and supplies its length/hash.
                    # Missing metadata is unknown; it must not become a fabricated empty citation.
                    entry.update({'seq': event['seq'], 'citationChars': payload.get('citationChars'),
                                  'citationSha256': payload.get('citationSha256')})
                    report['sources'].append(entry)
                elif event.get('type') == 'STEP' and payload.get('kind') == 'MODEL_CALL':
                    report['usage'].append({k: payload.get(k) for k in ['kind','inputTokens','outputTokens']})
                elif event.get('type') == 'ARTIFACT':
                    report['artifacts'].append({k: payload.get(k) for k in ['artifactId','versionId','version','title','contentHash','status']})
            cursor = max(cursor, int(page.get('nextSeq', cursor)))
            report['run'] = {k: run.get(k) for k in ['runId','status','errorCode','actionCode','configSnapshot','artifactArchives']}
            report['terminal'] = bool(page.get('terminal')) and run['status'] in TERMINAL
            if report['terminal']:
                break
            time.sleep(2)  # Empty pages are not completion.
        report['timedOutWaiting'] = not report.get('terminal', False)
    else:
        report.update({'runId': args.run_id, 'artifactId': args.artifact_id, 'qualityVerdict': verdict})
        current = ok(call('GET', '/agent-runs/' + args.run_id))
        assert current['status'] == 'SUCCEEDED', 'Only completed quality-reviewed run may apply'
        cursor, matching_hashes = 0, []
        while True:
            page = ok(call('GET', '/agent-runs/' + args.run_id + '/events?afterSeq=' + str(cursor)))
            for event in page.get('events', []):
                payload = event.get('payload') or {}
                if event.get('type') == 'ARTIFACT' and isinstance(payload, dict) and payload.get('artifactId') == args.artifact_id:
                    matching_hashes.append(payload.get('contentHash'))
            next_cursor = int(page.get('nextSeq', cursor))
            if page.get('terminal') or next_cursor <= cursor:
                break
            cursor = next_cursor
        assert matching_hashes and matching_hashes[-1].lower() == verdict['contentSha256'].lower(), 'A verdict hash does not match current artifact event'
        # Exactly one apply. No retries or parallel calls after ambiguous outcomes.
        report['response'] = call('POST', '/agent-runs/' + args.run_id + '/artifacts/' + args.artifact_id + '/apply', {})
        versions = ok(call('GET', '/ai-documents/' + target_id + '/versions'))
        report['versionsAfter'] = [{k: row.get(k) for k in ['id','parentVersionId','versionNo','status','model','tokenPrompt','tokenCompletion','contentSha256']} for row in versions]
        response_data = report['response']['body'].get('data') or {}
        document_id = str(response_data.get('documentId', ''))
        if report['response']['body'].get('code') == 0:
            created = next(row for row in versions if str(row['id']) == document_id)
            assert created['contentSha256'].lower() == verdict['contentSha256'].lower(), 'Applied content differs from A-reviewed artifact'
    out = HERE / ('codex-fresh-rework-' + args.mode + '-' + stamp + '.json')
    out.write_text(json.dumps(r.redact(report), ensure_ascii=False, indent=2))
    print(json.dumps({'evidence': str(out), 'mode': args.mode, 'terminal': report.get('terminal'),
        'responseCode': (report.get('response') or {}).get('body', {}).get('code')}, ensure_ascii=False))

if __name__ == '__main__':
    main()
