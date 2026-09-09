#!/usr/bin/env python3
"""Reconcile the repository plan with its isolated local Vibe Kanban board."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
PLAN = ROOT / 'docs/ipd-系统说明/开发计划-看板镜像.md'
STATE = ROOT / '.codex/vibe-kanban'
BASE = 'http://127.0.0.1:62250'
PROJECT = 'ruoyi-ai'
STATES = {'todo': '⬜', 'inprogress': '▶', 'inreview': '◇', 'done': '✅', 'cancelled': '⊘'}
# R6 reconcile 扩展（2026-09-06）：在原有命名外补齐 commit 命名体系——
# HIGH/MEDIUM/LOW-n[.n]、SEC-<word>系列、ROOT-Rn、GOVERNANCE-n、CONSISTENCY-n、
# DDL-*、FE-PARITY、qa0n-X、Rn、REFLECTION-n、GUARD-n、WAVE*、AI-REG-nn。
# batch-sync-commits.py 的严格提取与本正则保持同一来源（importlib 加载本模块）。
KEY = re.compile(r'^(?:'
                 r'P[0-4]-\d+(?:\.\d+)?'
                 r'|(?:OPS-VK|AUD(?:-GOV)?|DOC|SEC(?:-API)?|DATA|API|OPS|QA|RISK|DB|DEF)-\d+(?:\.\d+)?'
                 r'|HIGH-\d+(?:\.\d+)?'
                 r'|MEDIUM-\d+(?:\.\d+)?'
                 r'|LOW-\d+(?:\.\d+)?'
                 r'|SEC-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*'
                 r'|ROOT-R\d+'
                 r'|GOVERNANCE-\d+'
                 r'|CONSISTENCY-\d+'
                 r'|DDL-[A-Z0-9]+(?:-[A-Z0-9]+)*'
                 r'|FE-PARITY'
                 r'|qa0\d-[A-Z]'
                 r'|R\d'
                 r'|REFLECTION-\d+'
                 r'|GUARD-\d+'
                 r'|WAVE\d+(?:-[A-Z0-9]+)*'
                 r'|AI-REG-\d+'
                 r')$')
PRIORITIES = {'U0': '紧急', 'U1': '高', 'U2': '中', 'U3': '后续', 'P1': 'P1', 'P2': 'P2', 'P3': 'P3', '汇总': '汇总'}

# 2026-09-08 根源治理（reconcile 轮）：同步语义收窄为「status + plan block 权威段」双仲裁。
# - 身份匹配放宽为 title 含 [KEY]（闭括号锚定，合法 key 字符集不含 ]，前缀包含不可能误匹配）；
# - title/desc 在 block 外的治理注记合法：不触发 drift，apply 永不改写看板现 title，desc 只重写 [begin..end] 段；
# - plan block 现承载全部镜像负载（旧版为空 block + 头部旧布局，首次 apply 自动迁移并保留头尾注记）；
# - priority 标签/title 文案修订不再推送已有卡（仅 create 时生效），status 仍是零容忍字段；
# - unmanaged 中 done/cancelled 终态卡不阻塞 has_drift（历史存档），下列显式豁免的活跃卡同理。
EXEMPT_UNMANAGED = frozenset({
    'e30c86a2-2263-49ca-ad4d-218c644ccb08',  # WB-17-1 兄弟会话在途，收口时补镜像登记
    '6a3f9b4d-55cc-46be-8067-89064b2a0e99',  # P-DATA-gap-2 业务裁决未落地（镜像缺口表 487 行）
    '67ffc283-13d6-4426-b3e0-fec823f23f84',  # P-DATA-gap-1 业务裁决未落地（镜像缺口表 486 行）
    '73fb9329-3f9f-45f4-adbb-aad5fd723ca7',  # AUD-GOV-B-FIX-PACK-3 长期 backlog 容器，已裁决维持 unmanaged
    # 2026-09-08 reconcile 证据核验轮清理：
    # - ROOT-1 已被兄弟会话改名 ROOT-R6 + done + 镜像补行（KEY 正则 ROOT-R\d+ 匹配），移除
    # - AM-BASELINE-TTL/AM-HELPER/AM-GUARD/AM-CLOCK-2/AM-CLOCK-1/AM-GRANT/AM-SQL 7 张兄弟根除落地轮全部 done，
    #   移除（收口未做镜像补行与扩 KEY 正则因未影响 check 结果 — done 终态不阻塞）
})


def has_external_blocker(source_status):
    # History is evidence, not the current execution decision. A resolved
    # blocker retained after "前态记录" must not keep a card unclaimable.
    current = source_status.split('；前态记录：', 1)[0]
    return bool(re.search(r'BLOCKED_(?:ENVIRONMENT|DEPENDENCY|PERMISSION)|等待owner|待业务裁决', current))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError('Local board unexpectedly redirected; refusing to follow')


def api(path, method='GET', payload=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={'Content-Type': 'application/json'})
    with urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect).open(request, timeout=15) as response:
        if 'application/json' not in response.headers.get('Content-Type', ''):
            raise RuntimeError(f'{path}: expected JSON, not an HTML fallback')
        result = json.load(response)
    if result.get('success') is not True:
        raise RuntimeError(f'{path}: {result.get("message", result)}')
    return result['data']


def plan():
    rows = []
    for line_index, line in enumerate(PLAN.read_text().splitlines()):
        parts = [part.strip() for part in line.strip().strip('|').split('|')]
        if not parts or not KEY.fullmatch(parts[0]):
            continue
        if len(parts) == 3:
            # Three historical rows omitted the pipe before the completion cell.
            # R6: 状态符号允许起头（无前置空白），如映射段「| R6 | 治理卡 | ✅ done |」。
            match = re.search(r'(?:^|\s)(✅|▶|⬜|◐|◇|⊘)', parts[2])
            if not match:
                raise ValueError(f'Plan row {parts[0]} has no status cell')
            parts = parts[:2] + [parts[2][:match.start()].strip(), parts[2][match.start():].strip()]
        if len(parts) not in (4, 10):
            raise ValueError(f'Plan row {parts[0]} has {len(parts)} cells')
        key, title, acceptance, source_status = parts[:4]
        priority, dependencies, owner, paths, validation, source = parts[4:] if len(parts) == 10 else ('汇总', '—', '待认领', '—', '—', '历史计划')
        if priority not in PRIORITIES:
            raise ValueError(f'Unknown priority in {key}: {priority}')
        state = next((name for name, mark in STATES.items() if source_status.startswith(mark)), None)
        if state is None and source_status.startswith('◐'):
            state = 'todo'  # Partial/deferred is not an active execution claim.
        if state is None:
            raise ValueError(f'Unknown status in {key}: {source_status}')
        rows.append(dict(key=key, title=title, acceptance=acceptance,
                         source_status=source_status, status=state, line=line_index,
                         priority=priority, dependencies=dependencies, owner=owner,
                         allowedPaths=paths, validation=validation, source=source, extended=len(parts) == 10))
    if len({row['key'] for row in rows}) != len(rows) or not rows:
        raise ValueError('Empty plan or duplicate source IDs')
    keys = {row['key'] for row in rows}
    graph = {row['key']: re.findall(r'(?:P[0-4]-\d+(?:\.\d+)?|(?:OPS-VK|AUD|DOC|SEC|DATA|API|OPS|QA)-\d+(?:\.\d+)?)', row['dependencies']) for row in rows}
    def visit(key, trail):
        if key in trail:
            raise ValueError('Cyclic task dependency: ' + ' -> '.join(trail + [key]))
        for dep in graph[key]:
            if dep not in keys:
                raise ValueError(f'{key}: missing dependency {dep}')
            visit(dep, trail + [key])
    for key in keys:
        visit(key, [])
    return rows


def get_project(create=False):
    matches = [p for p in api('/api/projects') if p['name'] == PROJECT]
    if len(matches) > 1:
        raise RuntimeError('Duplicate project names; resolve identity before continuing')
    if not matches:
        if not create:
            return None
        matches = [api('/api/projects', 'POST', {
            'name': PROJECT, 'repositories': [{'display_name': 'ruoyi-ai', 'git_repo_path': str(ROOT)}]})]
    project = matches[0]
    if project.get('remote_project_id'):
        raise RuntimeError('Project is linked to a remote board; local-only mode required')
    repos = api(f'/api/projects/{project["id"]}/repositories')
    if len(repos) != 1 or repos[0]['path'] != str(ROOT):
        raise RuntimeError('Project repository does not match the canonical checkout')
    return project


def description(row):
    key = row['key']
    body = (f'优先级：{row["priority"]} {PRIORITIES[row["priority"]]}\n'
            f'依赖：{row["dependencies"]}\n责任泳道：{row["owner"]}\n认领：以源状态及卡后人工备注为准；未明确认领则待认领。\n\n'
            f'allowedPaths：{row["allowedPaths"]}\n\n'
            f'验收要点：{row["acceptance"]}\n\n'
            f'验证命令/步骤：{row["validation"]}\n'
            '命令中的新增测试名/脚本为该卡交付要求，未实现前不得当作现成通过证据；命令不存在、零测试或仅Mock均不得证明真实业务验收。\n\n'
            f'需求/缺口依据：{row["source"]}\n\n'
            f'源状态与证据：{row["source_status"]}\n\n'
            f'计划编号：{key}\n来源：docs/ipd-系统说明/开发计划-看板镜像.md\n\n'
            '此处同步源文档记录；历史已完成项不代表本次重新验收。\n'
            '执行者在当前宿主机项目中工作，完成前补充真实验证证据并同步源文档。')
    return f'<!-- ruoyi-plan:{key}:begin -->\n{body}\n<!-- ruoyi-plan:{key}:end -->'


def block_re(key):
    return rf'<!-- ruoyi-plan:{re.escape(key)}:begin -->.*?<!-- ruoyi-plan:{re.escape(key)}:end -->'


def find_task(tasks, key):
    token = f'[{key}]'
    hits = [t for t in tasks if token in (t.get('title') or '')]
    if len(hits) > 1:
        detail = '; '.join(f"{t['id'][:8]}:{(t.get('title') or '')[:50]}" for t in hits)
        raise RuntimeError(f'Multiple board cards match [{key}]: {detail}')
    return hits[0] if hits else None


def needs_update(task, row):
    """Drift 谓词：status 严格相等 + plan block 权威段与镜像生成一致（CRLF 归一）。"""
    if task.get('status') != row['status']:
        return True
    existing = (task.get('description') or '').replace('\r\n', '\n')
    m = re.search(block_re(row['key']), existing, re.S)
    if not m:
        if f"<!-- ruoyi-plan:{row['key']}:begin -->" in existing:
            raise RuntimeError(f'{row["key"]}: incomplete plan markers; refusing to guess')
        return True  # 无 block：旧布局待迁移
    return m.group(0) != description(row)


def rebuild_desc(existing, row):
    """保留 block 外注记，只重写权威段；空 block 旧布局迁移时保留头尾注记。"""
    existing = existing.replace('\r\n', '\n')
    key = row['key']
    fresh = description(row)
    m = re.search(block_re(key), existing, re.S)
    if m:
        inner = m.group(0)
        stripped = re.sub(rf'<!-- ruoyi-plan:{re.escape(key)}:(?:begin|end) -->', '', inner).strip()
        if stripped:  # 非空 block：直接替换权威段
            return existing[:m.start()] + fresh + existing[m.end():]
        # 空 block（旧版布局）：头部注记在首个「优先级：」之前，尾部注记在 end 标记之后
        head = existing
        anchor = existing.find('优先级：')
        if anchor > 0:
            head = existing[:anchor]
        elif anchor == 0:
            head = ''
        else:
            head = existing[:m.start()]
        tail = existing[m.end():]
        return ((head.rstrip() + '\n\n') if head.strip() else '') + fresh + ((tail) if tail.strip() else '')
    # 无 block 的同身份卡：原有内容全部保留为头部，新权威段追加（不丢弃任何看板内容）
    return ((existing.rstrip() + '\n\n') if existing.strip() else '') + fresh


def reconcile(apply=False):
    rows = plan()
    source_hash = hashlib.sha256(PLAN.read_bytes()).hexdigest()
    project = get_project()
    mapping_file = STATE / 'mapping.json'
    if apply and project and mapping_file.exists():
        previous = json.loads(mapping_file.read_text())
        if previous.get('project_id') == project['id']:
            omitted = sorted(set(previous['task_ids']) - {row['key'] for row in rows})
            if omitted:
                raise RuntimeError(
                    f'Plan omits {len(omitted)} previously mapped IDs '
                    f'({", ".join(omitted[:10])}); refusing all sync writes. '
                    'Restore or explicitly reconcile the plan before retrying.')
    if project is None and apply:
        project = get_project(create=True)
    tasks = api(f'/api/tasks?project_id={project["id"]}') if project else []
    actions, mapping = [], {}
    claimed = {}  # 单射守卫：一张看板卡只允许被一个 key 认领，防「汇总卡枚举子卡 token 被静默收养」
    for row in rows:
        key = row['key']
        task = find_task(tasks, key)
        if task:
            if task['id'] in claimed:
                raise RuntimeError(
                    f'Board card {task["id"][:8]} matched by both [{claimed[task["id"]]}] and [{key}]: {task["title"][:60]}')
            claimed[task['id']] = key
        priority_label = '汇总' if row['priority'] == '汇总' else f'{row["priority"]} {PRIORITIES[row["priority"]]}'
        canonical_title = f'[{key}] [{priority_label}] {row["title"]}'
        action = 'create' if task is None else ('update' if needs_update(task, row) else 'unchanged')
        if apply and action != 'unchanged':
            if hashlib.sha256(PLAN.read_bytes()).hexdigest() != source_hash:
                raise RuntimeError('Plan changed during synchronization; rerun from the current plan')
            if task is None:
                task = api('/api/tasks', 'POST', {**dict(title=canonical_title, description=description(row), status=row['status']), 'project_id': project['id']})
            else:
                # 永不改写看板现 title（保治理注记）；desc 只重写权威段，头尾注记保留
                task = api(f'/api/tasks/{task["id"]}', 'PUT', dict(
                    title=task['title'],
                    description=rebuild_desc(task.get('description') or '', row),
                    status=row['status']))
        if task:
            mapping[key] = task['id']
        actions.append({'key': key, 'action': action, 'status': row['status']})
    if apply and any(a['action'] not in ('unchanged', 'create') for a in actions):
        # 读回校验用 LIST 端点（单卡 GET 可能返回空描述）与同一 drift 谓词
        verify = {t['id']: t for t in api(f'/api/tasks?project_id={project["id"]}')}
        for row in rows:
            tid = mapping.get(row['key'])
            if tid and needs_update(verify[tid], row):
                raise RuntimeError(f'{row["key"]}: read-back verification failed')
    # Same-project cards outside the SSOT must remain visible to the check.
    # Do not delete or adopt them implicitly: retain their identity for review.
    mapped_ids = set(mapping.values())
    unmanaged_cards = [{k: task.get(k) for k in ('id', 'title', 'status')}
                       for task in tasks if task['id'] not in mapped_ids]
    blocking = [c for c in unmanaged_cards
                if c['status'] not in ('done', 'cancelled') and c['id'] not in EXEMPT_UNMANAGED]
    result = {'project_id': project['id'] if project else None, 'source_sha256': source_hash,
              'total': len(rows), 'counts': dict(Counter(a['action'] for a in actions)),
              'statuses': dict(Counter(r['status'] for r in rows)), 'actions': actions,
              'board_total': len(tasks),  # Actual API snapshot, before any sync writes.
              'unmanaged_cards': unmanaged_cards, 'unmanaged_blocking': blocking,
              'has_drift': bool(blocking) or any(a['action'] != 'unchanged' for a in actions)}
    if apply:
        STATE.mkdir(parents=True, exist_ok=True)
        mapping_file.write_text(json.dumps({**result, 'task_ids': mapping}, ensure_ascii=False, indent=2) + '\n')
    return result


def set_status(key, status, note):
    rows = plan()
    row = next((r for r in rows if r['key'] == key), None)
    if not row:
        raise ValueError(f'No task with source ID {key}')
    if not note.strip() or '\n' in note or '|' in note:
        raise ValueError('A single-line owner/blocker/evidence note without a pipe is required')
    if get_project() is None:
        raise RuntimeError('Initialize the local project with sync --apply first')
    if row['source_status'].startswith(f'{STATES[status]} {note}；'):
        return reconcile(apply=True)
    original = PLAN.read_text()
    lines = original.splitlines()
    prior = row['source_status']
    source_status = f'{STATES[status]} {note}；前态记录：{prior}'
    cells = [key, row['title'], row['acceptance'], source_status]
    if row['extended']:
        cells += [row['priority'], row['dependencies'], row['owner'], row['allowedPaths'], row['validation'], row['source']]
    lines[row['line']] = '| ' + ' | '.join(cells) + ' |'
    STATE.mkdir(parents=True, exist_ok=True)
    backup = STATE / ('plan-before-' + hashlib.sha256(original.encode()).hexdigest()[:12] + '.md')
    if not backup.exists():
        backup.write_text(original)
    PLAN.write_text('\n'.join(lines) + '\n')
    # The Markdown remains authoritative if the API fails; retry sync after recovery.
    return reconcile(apply=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('plan')
    sub.add_parser('check')
    sub.add_parser('list')
    queue_cmd = sub.add_parser('queue')
    queue_cmd.add_argument('--limit', type=int, default=20)
    queue_cmd.add_argument('--ready', action='store_true', help='Only tasks without unfinished or external dependencies')
    sync = sub.add_parser('sync')
    sync.add_argument('--apply', action='store_true')
    set_cmd = sub.add_parser('set')
    set_cmd.add_argument('key')
    set_cmd.add_argument('status', choices=STATES)
    set_cmd.add_argument('--note', required=True)
    args = parser.parse_args()
    if args.command == 'plan':
        result = plan()
    elif args.command == 'queue':
        rows = plan()
        completed = {r['key'] for r in rows if r['status'] == 'done'}
        queue = []
        for r in rows:
            if r['priority'] == '汇总' or r['status'] in ('done', 'cancelled'):
                continue
            deps = [d for d in re.findall(KEY.pattern[1:-1], r['dependencies']) if d not in completed]
            external = has_external_blocker(r['source_status'])
            queue.append({k: r[k] for k in ('key', 'priority', 'title', 'status', 'owner', 'line')}
                         | {'blocked_by': deps, 'external_blocker': r['source_status'] if external else None})
        queue.sort(key=lambda r: (r['priority'], bool(r['blocked_by'] or r['external_blocker']), r['line']))
        if args.ready:
            queue = [r for r in queue if not r['blocked_by'] and not r['external_blocker'] and r['status'] == 'todo']
        result = queue[:max(0, args.limit)]
    elif args.command == 'list':
        p = get_project()
        result = api(f'/api/tasks?project_id={p["id"]}') if p else []
    elif args.command == 'set':
        result = set_status(args.key, args.status, args.note)
    else:
        result = reconcile(apply=args.command == 'sync' and args.apply)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.command == 'check' and result['has_drift']:
        raise SystemExit(1)
