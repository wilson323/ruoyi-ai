#!/usr/bin/env python3
"""Controlled concurrency checks; fake Maven never touches real targets."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

SOURCE = Path(__file__).resolve().parents[1] / 'mvn-locked.sh'
with tempfile.TemporaryDirectory(prefix='ipd-maven-lock-test-') as td:
    tmp = Path(td)
    bindir = tmp / 'bin'
    bindir.mkdir()
    fake = bindir / 'mvn'
    fake.write_text('''#!/usr/bin/env python3
import os, time
from pathlib import Path
active=Path(os.environ['PROBE_ACTIVE'])
try:
 active.mkdir()
except FileExistsError:
 Path(os.environ['PROBE_COLLISION']).touch()
with open(os.environ['PROBE_EVENTS'],'a') as f: f.write('start\\n')
time.sleep(0.6)
try: active.rmdir()
except FileNotFoundError: pass
with open(os.environ['PROBE_EVENTS'],'a') as f: f.write('end\\n')
raise SystemExit(int(os.environ.get('PROBE_EXIT','0')))
''')
    fake.chmod(0o755)
    env = dict(os.environ, PATH=f'{bindir}:{os.environ["PATH"]}',
               RUOYI_MVN_LOCK_DIR=str(tmp / 'locks'),
               PROBE_ACTIVE=str(tmp / 'active'),
               PROBE_COLLISION=str(tmp / 'collision'),
               PROBE_EVENTS=str(tmp / 'events'))
    roots=[]
    for label in ('one','two'):
        root=tmp/label
        (root/'scripts').mkdir(parents=True)
        shutil.copy(SOURCE, root/'scripts/mvn-locked.sh')
        roots.append(root)
    def launch(root,args,wrapper=True,extra=None):
        return subprocess.Popen((['bash',str(root/'scripts/mvn-locked.sh')] if wrapper else ['mvn'])+args,
                                env=env | (extra or {}), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    def pair(root1,root2,args1,args2,wrapper=True):
        for name in ('events','collision'):
            (tmp/name).unlink(missing_ok=True)
        a=launch(root1,args1,wrapper)
        deadline=time.monotonic()+5
        while not (tmp/'active').exists():
            assert time.monotonic()<deadline, 'fake Maven did not start'
            time.sleep(.01)
        b=launch(root2,args2,wrapper)
        assert a.wait(timeout=12)==0 and b.wait(timeout=12)==0
        return (tmp/'collision').exists()
    assert pair(roots[0],roots[0],['test'],['package'],False), 'negative control must collide'
    cases=[(['test','-pl','a'],['test-compile','-pl','a']),
           (['package','-pl','a','-am'],['test','-pl','b']),
           (['-amd','test','-pl','a'],['-pl','b','compile']),
           (['test'],['package','-pl','a,b'])]
    for first,second in cases:
        assert not pair(roots[0],roots[0],first,second), f'overlap: {first} / {second}'
    assert pair(roots[0],roots[1],['test'],['test']), 'independent worktrees should run concurrently'
    held=launch(roots[0],['test'])
    deadline=time.monotonic()+5
    while not (tmp/'active').exists():
        assert time.monotonic()<deadline
        time.sleep(.01)
    assert launch(roots[0],['package'],extra={'RUOYI_MVN_LOCK_WAIT':'0'}).wait(timeout=5)==75
    assert held.wait(timeout=5)==0, 'timeout must preserve current lock owner'
    assert launch(roots[0],['test'],extra={'PROBE_EXIT':'7'}).wait(timeout=5)==7
    assert launch(roots[0],['test']).wait(timeout=5)==0, 'failure must release lock'
    print('PASS: negative collision control; 4 same-worktree phase/reactor combinations; independent worktrees; timeout refusal, exit preservation and release')
