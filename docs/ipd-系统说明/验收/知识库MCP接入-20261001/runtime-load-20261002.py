#!/usr/bin/env python3
"""Load the verified immutable local candidate only when 16039 has no listener."""
from pathlib import Path
import subprocess,hashlib,shutil,json,datetime,os,zipfile,io,sys,signal,time
base=Path(__file__).resolve().parents[4]
ev=Path(__file__).resolve().parent
assert base.name=='ruoyi-ai'
listeners=subprocess.run(['lsof','-tiTCP:16039','-sTCP:LISTEN'],capture_output=True,text=True).stdout.strip()
replace_pid=sys.argv[1] if len(sys.argv)>1 else None
assert not listeners or listeners==replace_pid,'16039 listener differs from explicitly inspected PID'
if listeners:
 command=subprocess.check_output(['ps','-ww','-p',listeners,'-o','command='],text=True)
 assert '-jar' in command and 'ruoyi-admin' in command,'not expected project backend'
mysql=next((base/'.codex/ipd-dev/software').glob('mysql-*/bin/mysql'))
query="SELECT COUNT(*) FROM ipd_agent_run WHERE status IN ('PENDING','RUNNING','WAITING_APPROVAL','CANCEL_REQUESTED') AND del_flag='0'"
count=subprocess.run([str(mysql),'--defaults-extra-file='+str(base/'.codex/ipd-dev/config/mysql-client.cnf'),'--batch','--skip-column-names','ipd_dev','-e',query],capture_output=True,text=True,check=True).stdout.strip()
assert count=='0','active runs prevent loading'
source=base/'ruoyi-admin/target/ruoyi-admin.jar'
expected=json.loads((ev/'候选包核对-20261002.json').read_text())['jarSha256']
assert hashlib.sha256(source.read_bytes()).hexdigest()==expected,'candidate changed'
with zipfile.ZipFile(source) as archive:
 for module in ['ipd','chat']:
  nested=archive.read(f'BOOT-INF/lib/ruoyi-{module}-3.1.0.jar')
  actual=(base/f'ruoyi-modules/ruoyi-{module}/target/ruoyi-{module}-3.1.0.jar').read_bytes()
  assert hashlib.sha256(nested).digest()==hashlib.sha256(actual).digest(),'stale nested module'
  if module=='ipd':
   with zipfile.ZipFile(io.BytesIO(nested)) as component:
    code=component.read('org/ruoyi/ipd/agent/service/ProjectAgentInterruptedRunCloser.class')
    assert b'Autowired' in code and b'diagnoseCandidates' in code and b'transition' not in code,'unsafe or uninjected startup component'
candidate=base/'.codex/ipd-dev/backups'/('ruoyi-admin.codex-global-20261002-'+expected[:12]+'.jar')
if candidate.exists():
 assert hashlib.sha256(candidate.read_bytes()).hexdigest()==expected,'immutable candidate mismatch'
else:
 shutil.copy2(source,candidate)
args=['/Users/mac/tools/jdk-17/Contents/Home/bin/java','-Xmx2g','-Duser.timezone=Asia/Shanghai','-jar',str(candidate),'--spring.profiles.active=ipd-local,dev','--spring.config.additional-location=file:'+str(base/'.codex/ipd-dev/config/application-ipd-local.yml'),'--spring.data.redis.host=127.0.0.1','--spring.data.redis.port=16379','--server.port=16039','--server.address=127.0.0.1']
log=base/'.codex/ipd-dev/logs/codex-global-20261002.log';log.parent.mkdir(exist_ok=True,parents=True)
if listeners:
 os.kill(int(listeners),signal.SIGTERM)
 for attempt in range(40):
  if subprocess.run(['lsof','-tiTCP:16039','-sTCP:LISTEN'],capture_output=True,text=True).stdout.strip()=='':break
  time.sleep(.25)
 else:raise RuntimeError('listener did not stop; no force kill')
with log.open('ab') as f:
 process=subprocess.Popen(args,cwd=base,stdin=subprocess.DEVNULL,stdout=f,stderr=subprocess.STDOUT,start_new_session=True)
record={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'oldPid':listeners or None,'newPid':process.pid,'jar':str(candidate),'sha256':expected,'activeRunsBefore':0,'log':str(log),'status':'STARTING'}
(ev/'运行包加载-20261002.json').write_text(json.dumps(record,indent=2,ensure_ascii=False));print(json.dumps(record,ensure_ascii=False))
