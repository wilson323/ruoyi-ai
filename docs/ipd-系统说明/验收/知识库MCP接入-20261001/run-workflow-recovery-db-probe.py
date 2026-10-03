#!/usr/bin/env python3
"""Explicit local-only synthetic DB acceptance, retaining probe history. No Maven/DDL/deletion."""
import argparse, configparser, hashlib, json, os, pathlib, subprocess, tempfile, uuid, zipfile
p=argparse.ArgumentParser();p.add_argument('--jar',required=True);p.add_argument('--execute',action='store_true');p.add_argument('--out',required=True);a=p.parse_args()
if not a.execute: raise SystemExit('Pass --execute for authorized local synthetic writes; no DDL/deletion.')
root=pathlib.Path('/Users/mac/Documents/ruoyi-ai');src=pathlib.Path(__file__).with_name('WorkflowRecoveryDbProbe.java')
jar=pathlib.Path(a.jar).resolve();config=configparser.ConfigParser(interpolation=None);config.read(root/'.codex/ipd-dev/config/mysql-client.cnf')
env=os.environ.copy();env['IPD_PROBE_DB_USER']=config['client']['user'].strip('"\'');env['IPD_PROBE_DB_PASSWORD']=config['client']['password'].strip('"\'')
java=pathlib.Path('/Users/mac/tools/jdk-17/Contents/Home/bin');tmp=pathlib.Path(tempfile.mkdtemp(prefix='ipd-f-recovery-probe-'));libs=tmp/'lib';libs.mkdir()
with zipfile.ZipFile(jar) as z:
    names=[n for n in z.namelist() if n.startswith('BOOT-INF/lib/') and n.endswith('.jar')]
    for n in names:(libs/pathlib.PurePosixPath(n).name).write_bytes(z.read(n))
cp=str(libs/'*');run=lambda cmd:subprocess.run(cmd,env=env,text=True,capture_output=True)
compile_result=run([str(java/'javac'),'-cp',cp,'-d',str(tmp),str(src)])
if compile_result.returncode: raise SystemExit('Probe compilation failed:\n'+compile_result.stderr)
result={'scope':'production WorkflowExecutionPlan/JdbcCheckpointSaver/WfState + real local MySQL across fresh JVMs; JDBC mapper adapter; synthetic read-only runners; not full WorkflowEngine HTTP acceptance','adminJarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'sourceSha256':hashlib.sha256(src.read_bytes()).hexdigest(),'retainedHistory':True,'temporaryDirectory':str(tmp),'cases':[]}
for effect in ['readonly','effect']:
    thread='fprobe'+uuid.uuid4().hex[:26]
    case={'thread':thread,'kind':effect,'phases':[]};result['cases'].append(case)
    for phase in ['fail','resume']:
        r=run([str(java/'java'),'-cp',str(tmp)+os.pathsep+cp,'WorkflowRecoveryDbProbe',thread,phase,effect])
        case['phases'].append({'phase':phase,'exitCode':r.returncode,'stdout':r.stdout,'stderr':r.stderr})
        pathlib.Path(a.out).write_text(json.dumps(result,ensure_ascii=False,indent=2))
        if r.returncode: raise SystemExit('Probe failed; inspect evidence '+a.out)
result['status']='VERIFIED_LOCAL_SYNTHETIC_DB_PROBE';pathlib.Path(a.out).write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps({'status':result['status'],'threads':[c['thread'] for c in result['cases']],'evidence':a.out},ensure_ascii=False))
