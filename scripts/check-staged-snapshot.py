#!/usr/bin/env python3
"""Validate exported Git indexes; never substitute working-tree contents."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def clean_env():
    env = os.environ.copy()
    for name in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR'):
        env.pop(name, None)
    return env


def index_env(root):
    env = clean_env()
    inherited = os.environ.get('GIT_INDEX_FILE')
    if inherited:
        index = Path(inherited).absolute()
        gitdir = Path(subprocess.check_output(
            ['git', '-C', str(root), 'rev-parse', '--absolute-git-dir'],
            env=env, text=True).strip()).resolve()
        # git commit --only creates its transaction index inside this Git dir.
        # Never apply one repository's index to the other repository.
        if index.is_file() and index.parent.resolve() == gitdir:
            env['GIT_INDEX_FILE'] = str(index)
    return env


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], env=index_env(root))


def fingerprint(root):
    path = Path(os.fsdecode(git(root, 'rev-parse', '--git-path', 'index')).strip())
    if not path.is_absolute():
        path = root / path
    return hashlib.sha256(path.read_bytes()).hexdigest()


def export_index(root, dest):
    entries = git(root, 'ls-files', '--stage', '-z').split(b'\0')
    objects = {}
    ids = list(dict.fromkeys(e.split(b'\t', 1)[0].split()[1] for e in entries if e))
    raw = subprocess.check_output(['git', '-C', str(root), 'cat-file', '--batch'], input=b'\n'.join(ids) + b'\n', env=clean_env())
    offset = 0
    for oid in ids:
        end = raw.index(b'\n', offset)
        header = raw[offset:end].split()
        if len(header) != 3 or header[1] != b'blob':
            raise RuntimeError('unsupported or missing indexed object')
        size = int(header[2])
        objects[oid] = raw[end + 1:end + 1 + size]
        offset = end + 2 + size
    for entry in entries:
        if not entry:
            continue
        meta, raw_path = entry.split(b'\t', 1)
        mode, oid, stage = meta.split()
        if stage != b'0':
            raise RuntimeError('unmerged index cannot be validated')
        path = Path(os.fsdecode(raw_path))
        if path.is_absolute() or '..' in path.parts or path.parts[0] == '.git':
            raise RuntimeError('unsafe index path')
        target = dest / path
        target.parent.mkdir(parents=True, exist_ok=True)
        # Do not allow an earlier indexed symlink to redirect subsequent writes.
        if any((dest / p).is_symlink() for p in path.parents if p != Path('.')):
            raise RuntimeError('indexed symlink used as a parent directory')
        data = objects[oid]
        if mode == b'120000':
            link = os.fsdecode(data)
            if os.path.isabs(link) or not (target.parent / link).resolve().is_relative_to(dest.resolve()):
                raise RuntimeError('indexed symlink escapes snapshot')
            os.symlink(link, target)
        elif mode in (b'100644', b'100755'):
            target.write_bytes(data)
            target.chmod(0o755 if mode == b'100755' else 0o644)
        else:
            raise RuntimeError('unsupported index entry mode')
    for path in dest.rglob('*'):
        if path.is_symlink() and not path.exists():
            raise RuntimeError('dangling indexed symlink: ' + str(path.relative_to(dest)))
    gitdir = os.fsdecode(git(root, 'rev-parse', '--absolute-git-dir')).strip()
    (dest / '.git').write_text('gitdir: ' + gitdir + '\n')


def validate(be, fe, shell_only=False):
    roots = [be] if shell_only else [be, fe]
    before = {root: fingerprint(root) for root in roots}
    try:
        with tempfile.TemporaryDirectory(prefix='ipd-index-') as directory:
            base = Path(directory)
            snapshots = []
            for i, root in enumerate(roots):
                snapshot = base / str(i)
                snapshot.mkdir()
                export_index(root, snapshot)
                snapshots.append(snapshot)
            env = clean_env()
            env.pop('IPD_FE_API_DIR', None)
            env['GIT_WORK_TREE'] = str(snapshots[0])
            if shell_only:
                files = [os.fsdecode(p) for p in git(be, 'diff', '--cached', '--name-only', '--diff-filter=ACM', '-z').split(b'\0') if p.endswith(b'.sh')]
                checker = snapshots[0] / 'scripts/check-shell-var-multibyte.sh'
                if not checker.is_file() or checker.is_symlink():
                    raise RuntimeError('indexed shell checker missing')
                if not files:
                    return 0
                command = ['bash', str(checker), *files]
            else:
                checker = snapshots[0] / 'scripts/check-api-contract-fe-be.mjs'
                if not checker.is_file() or checker.is_symlink() or not shutil.which('node'):
                    raise RuntimeError('indexed contract checker or node missing')
                command = ['node', str(checker), '--be-root', str(snapshots[0]), '--fe-root', str(snapshots[1] / 'apps/web-antd')]
            return subprocess.run(command, cwd=snapshots[0], env=env, check=False).returncode
    finally:
        if any(fingerprint(root) != before[root] for root in roots):
            raise RuntimeError('index changed during validation; retry on a stable index')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--be-root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--fe-root', type=Path)
    parser.add_argument('--shell-only', action='store_true')
    args = parser.parse_args()
    be = args.be_root.resolve()
    fe = (args.fe_root or be.parent / 'ruoyi-ipd-web').resolve()
    try:
        result = validate(be, fe, args.shell_only)
        print('[staged-snapshot] ' + ('PASS' if result == 0 else 'FAIL') + ': index contents only')
        raise SystemExit(result)
    except (OSError, subprocess.SubprocessError, RuntimeError) as error:
        print('[staged-snapshot] ERROR: ' + str(error))
        raise SystemExit(2)
