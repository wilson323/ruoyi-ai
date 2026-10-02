#!/usr/bin/env python3
"""Deterministic staged/worktree divergence controls; temporary repositories only."""
import importlib.util
from pathlib import Path
import subprocess
import os
from unittest.mock import patch
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('snapshot', Path(__file__).with_name('check-staged-snapshot.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SnapshotTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.be = Path(self.temp.name) / 'be'
        self.fe = Path(self.temp.name) / 'fe'
        for root in (self.be, self.fe):
            root.mkdir()
            self.git(root, 'init', '-q')
            self.git(root, 'config', 'user.email', 'fixture@example.invalid')
            self.git(root, 'config', 'user.name', 'Fixture')
            (root / 'contract.txt').write_text('good')
            self.git(root, 'add', '.')
            self.git(root, 'commit', '-qm', 'fixture')
        scripts = self.be / 'scripts'
        scripts.mkdir()
        (scripts / 'check-api-contract-fe-be.mjs').write_text('''import fs from 'node:fs';
import {resolve} from 'node:path';
import {execFileSync} from 'node:child_process';
const be=process.argv[process.argv.indexOf('--be-root')+1];
const fe=resolve(process.argv[process.argv.indexOf('--fe-root')+1],'../..');
if(execFileSync('git',['-C',be,'show','HEAD:contract.txt'],{encoding:'utf8'})!=='good')process.exit(2);
process.exit([be,fe].every(p=>fs.readFileSync(p+'/contract.txt','utf8')==='good')?0:1);
''')
        self.git(self.be, 'add', '.')

    def git(self, root, *args):
        return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.STDOUT)

    def check(self, expected):
        before = [module.fingerprint(r) for r in (self.be, self.fe)]
        self.assertEqual(module.validate(self.be, self.fe), expected)
        self.assertEqual(before, [module.fingerprint(r) for r in (self.be, self.fe)])

    def test_good_index_bad_worktree_passes(self):
        for root in (self.be, self.fe):
            (root / 'contract.txt').write_text('bad')
        self.check(0)

    def test_bad_index_good_worktree_rejected(self):
        for root in (self.be, self.fe):
            (root / 'contract.txt').write_text('bad')
            self.git(root, 'add', 'contract.txt')
            (root / 'contract.txt').write_text('good')
            self.check(1)
            (root / 'contract.txt').write_text('good')
            self.git(root, 'add', 'contract.txt')

    def test_missing_indexed_checker_rejected(self):
        self.git(self.be, 'rm', '--cached', 'scripts/check-api-contract-fe-be.mjs')
        with self.assertRaisesRegex(RuntimeError, 'checker'):
            module.validate(self.be, self.fe)

    def test_shell_bad_index_good_worktree_rejected(self):
        checker = self.be / 'scripts/check-shell-var-multibyte.sh'
        checker.write_text('#!/bin/bash\n[ "$(cat "$1")" = good ]\n')
        target = self.be / 'bad.sh'
        target.write_text('bad')
        self.git(self.be, 'add', '.')
        target.write_text('good')
        before = module.fingerprint(self.be)
        self.assertEqual(module.validate(self.be, self.fe, True), 1)
        self.assertEqual(before, module.fingerprint(self.be))
        target.write_text('good')
        self.git(self.be, 'add', 'bad.sh')
        self.assertEqual(module.validate(self.be, self.fe, True), 0)

    def test_dangling_indexed_symlink_rejected(self):
        (self.be / 'link').symlink_to('missing')
        self.git(self.be, 'add', 'link')
        with self.assertRaisesRegex(RuntimeError, 'dangling'):
            module.validate(self.be, self.fe)

    def test_valid_indexed_symlink_passes(self):
        (self.be / 'link').symlink_to('contract.txt')
        self.git(self.be, 'add', 'link')
        self.check(0)

    def test_commit_transaction_index_rejected_without_cross_repo_leak(self):
        index = self.be / '.git' / 'next-index-fixture'
        index.write_bytes((self.be / '.git' / 'index').read_bytes())
        (self.be / 'contract.txt').write_text('bad')
        env = os.environ.copy()
        env['GIT_INDEX_FILE'] = str(index)
        subprocess.check_call(['git', '-C', str(self.be), 'add', 'contract.txt'], env=env)
        before = [(root / '.git' / 'index').read_bytes() for root in (self.be, self.fe)]
        with patch.dict(os.environ, {'GIT_INDEX_FILE': str(index)}):
            self.assertEqual(module.validate(self.be, self.fe), 1)
        self.assertEqual(before, [(root / '.git' / 'index').read_bytes() for root in (self.be, self.fe)])

    def test_good_commit_transaction_index_ignores_bad_regular_index(self):
        index = self.be / '.git' / 'next-index-fixture'
        index.write_bytes((self.be / '.git' / 'index').read_bytes())
        (self.be / 'contract.txt').write_text('bad')
        self.git(self.be, 'add', 'contract.txt')
        with patch.dict(os.environ, {'GIT_INDEX_FILE': str(index)}):
            self.assertEqual(module.validate(self.be, self.fe), 0)

    def test_inherited_git_environment_ignored(self):
        with patch.dict(os.environ, {name: '/nonexistent' for name in ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR')}):
            self.check(0)

    def test_untracked_gate_unicode_space_index_contents(self):
        source = Path(__file__).resolve().parents[1] / '.claude/hooks/check-pre-commit.sh'
        text = source.read_text()
        function = text[text.index('run_untracked_gate() {'):text.index('\n# ---------------------------------------------------------------------------\n# 门禁 1:')]
        target = self.be / '中文 空格.md'
        missing = self.be / '未跟踪 目标.md'
        missing.write_text('fixture')
        target.write_text('引用：未跟踪 目标.md')
        self.git(self.be, 'add', target.name)
        target.write_text('工作区已移除引用')
        runner = self.be / 'gate.sh'
        runner.write_text('set -uo pipefail\nREPO_ROOT="$1"\nPASSED=0; FAILED=0\n' + function + '\nrun_untracked_gate\n[ "$FAILED" = 0 ]\n')
        run = lambda: subprocess.run(['/bin/bash', str(runner), str(self.be)], capture_output=True, text=True)
        result = run()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('中文 空格.md', result.stdout)
        self.assertIn('未跟踪 目标.md', result.stdout)
        self.git(self.be, 'add', target.name)
        target.write_text('引用：未跟踪 目标.md')
        result = run()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('门禁 0 PASS', result.stdout)

    def run_untracked_fixture(self):
        text = (Path(__file__).resolve().parents[1] / '.claude/hooks/check-pre-commit.sh').read_text()
        function = text[text.index('run_untracked_gate() {'):text.index('\n# ---------------------------------------------------------------------------\n# 门禁 1:')]
        runner = Path(self.temp.name) / 'gate.sh'
        runner.write_text('set -uo pipefail\nREPO_ROOT="$1"\nPASSED=0; FAILED=0\n' + function + '\nrun_untracked_gate\n[ "$FAILED" = 0 ]\n')
        return subprocess.run(['/bin/bash', str(runner), str(self.be)], capture_output=True, text=True)

    def test_untracked_gate_empty_collection_bash32(self):
        result = self.run_untracked_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('无 untracked 文件', result.stdout)
        self.assertNotIn('unbound variable', result.stderr)

    def test_untracked_gate_nonempty_without_hits_bash32(self):
        (self.be / '无引用 目标.md').write_text('fixture')
        result = self.run_untracked_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('无 untracked 引用', result.stdout)
        self.assertNotIn('unbound variable', result.stderr)

    def test_missing_shell_checker_rejected(self):
        with self.assertRaisesRegex(RuntimeError, 'shell checker missing'):
            module.validate(self.be, self.fe, True)

    def test_indexed_checker_failure_rejected(self):
        checker = self.be / 'scripts/check-api-contract-fe-be.mjs'
        checker.write_text('process.exit(7);')
        self.git(self.be, 'add', '.')
        checker.write_text('process.exit(0);')
        self.check(7)

    def run_ratchet_fixture(self):
        text = (Path(__file__).resolve().parents[1] / '.claude/hooks/check-pre-commit.sh').read_text()
        function = text[text.index('run_ratchet_gate() {'):text.index('\n# ---------------------------------------------------------------------------\n# 门禁 4：')]
        checker = self.be / 'scripts/check-staged-snapshot.py'
        checker.write_text(Path(module.__file__).read_text())
        self.git(self.be, 'add', str(checker.relative_to(self.be)))
        runner = Path(self.temp.name) / 'ratchet.sh'
        runner.write_text('set -uo pipefail\nREPO_ROOT="$1"\nIPD_FE_REPO_ROOT="$2"\nPASSED=0; FAILED=0; SKIPPED=0\n' + function + '\nrun_ratchet_gate\n[ "$FAILED" = 0 ]\n')
        return subprocess.run(['/bin/bash', str(runner), str(self.be), str(self.fe)], capture_output=True, text=True)

    def test_ratchet_hook_good_index_bad_worktree_passes(self):
        for root in (self.be, self.fe):
            (root / 'contract.txt').write_text('bad')
        result = self.run_ratchet_fixture()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('门禁 3 PASS', result.stdout)

    def test_ratchet_hook_bad_index_good_worktree_rejected(self):
        for root in (self.be, self.fe):
            (root / 'contract.txt').write_text('bad')
            self.git(root, 'add', 'contract.txt')
            (root / 'contract.txt').write_text('good')
        result = self.run_ratchet_fixture()
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn('门禁 3 FAIL', result.stdout)


if __name__ == '__main__':
    unittest.main()
