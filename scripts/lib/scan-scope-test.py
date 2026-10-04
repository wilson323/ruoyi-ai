#!/usr/bin/env python3
"""Real fixture mutations for archive exclusion checks, without touching the repository."""
from pathlib import Path
import shutil
import subprocess
import tempfile

source=Path(__file__).resolve().parents[1]/'check-scan-scope-excludes.sh'
with tempfile.TemporaryDirectory(prefix='ipd-scan-scope-') as td:
    root=Path(td)
    (root/'scripts').mkdir()
    shutil.copy(source,root/'scripts/check-scan-scope-excludes.sh')
    subprocess.run(['git','init','-q',str(root)],check=True)
    fixture=root/'scripts/fixture.sh'
    def check(text,expected):
        fixture.write_text(text)
        subprocess.run(['git','-C',str(root),'add','scripts'],check=True)
        result=subprocess.run(['bash',str(root/'scripts/check-scan-scope-excludes.sh')],capture_output=True,text=True)
        assert result.returncode==expected, result.stdout+result.stderr
    check('find . -not -path "*/.codex/*" -not -path "*/.harness/*"\n',0)
    check('find . -not -path "*/.codex/*"\n# .harness exclusion documented only\n',1)
    check('find .\n# -not -path "*/.codex/*" -not -path "*/.harness/*"\n',1)
    check('find ./docs -name "*.md"\n',0)
    check('find "$BACKEND_ROOT" -not -path "*/.codex/*"\n',1)
    print('PASS: both archives excluded; missing harness rejected; comments rejected; scoped find allowed; backend root rejected')
