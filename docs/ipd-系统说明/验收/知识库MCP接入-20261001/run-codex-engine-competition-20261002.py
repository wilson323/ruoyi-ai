import pathlib,tempfile,zipfile,subprocess,os,configparser,json,hashlib,select,time,datetime
r=pathlib.Path("/Users/mac/Documents/ruoyi-ai");e=r/"docs/ipd-系统说明/验收/知识库MCP接入-20261001";jar=r/".codex/ipd-dev/backups/ruoyi-admin.codex-takeover-20261002-7ddc60b6125a.jar";tmp=pathlib.Path(tempfile.mkdtemp(prefix="ipd-engine-competition-"));libs=tmp/"lib";libs.mkdir();classes=tmp/"classes";classes.mkdir();out=e/"codex-e-engine-two-jvm-competition-20261002.json"
res={"status":"RUNNING","atUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"jar":str(jar),"jarSha256":hashlib.sha256(jar.read_bytes()).hexdigest(),"temporaryDirectory":str(tmp),"database":"127.0.0.1:13306/ipd_dev","cases":[],"sourceHashes":{}}
assert res["jarSha256"]=="7ddc60b6125aca829450013540cb36976afd429f74a906838aceb17bdbfc5017"
with zipfile.ZipFile(jar) as z:
 for n in z.namelist():
  if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"):(libs/pathlib.PurePosixPath(n).name).write_bytes(z.read(n))
aiflow=next(libs.glob("ruoyi-aiflow-*.jar"));res["aiflowJarSha256"]=hashlib.sha256(aiflow.read_bytes()).hexdigest()
with zipfile.ZipFile(aiflow) as z:
 res["loadedClassHashes"]={n:hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist() if n.endswith(("WorkflowEngine.class","WorkflowExecutionPlan.class","WorkflowRuntimeService.class","JdbcCheckpointSaver.class"))}
for name in ["WorkflowEngineRecoveryProbe.java","WorkflowEngineCompetitionProbe.java"]:res["sourceHashes"][name]=hashlib.sha256((e/name).read_bytes()).hexdigest()
java="/Users/mac/tools/jdk-17/Contents/Home/bin/";cp=str(classes)+os.pathsep+str(libs/"*");compiled=subprocess.run([java+"javac","-cp",cp,"-d",str(classes),str(e/"WorkflowEngineRecoveryProbe.java"),str(e/"WorkflowEngineCompetitionProbe.java")],capture_output=True,text=True);res["compileExitCode"]=compiled.returncode
if compiled.returncode:res["compileDiagnostic"]=compiled.stderr;out.write_text(json.dumps(res,indent=2));raise SystemExit("compile failed")
c=configparser.ConfigParser(interpolation=None);c.read(r/".codex/ipd-dev/config/mysql-client.cnf");env=os.environ.copy();env["IPD_PROBE_DB_USER"]=c["client"]["user"].strip("\"'");env["IPD_PROBE_DB_PASSWORD"]=c["client"]["password"].strip("\"'")
def save():out.write_text(json.dumps(res,indent=2))
def cmd(phase,uuid="-"):return [java+"java","-cp",cp,"WorkflowEngineCompetitionProbe",phase,uuid]
def run(phase,uuid="-"):
 p=subprocess.run(cmd(phase,uuid),capture_output=True,text=True,env=env,timeout=40);record={"phase":phase,"exitCode":p.returncode,"stdout":p.stdout,"stderr":p.stderr};save();return record
def fixture():
 record=run("prepare");assert record["exitCode"]==0,"fixture failed";line=next(l for l in record["stdout"].splitlines() if l.startswith("PROBE_RESULT "));uuid=line.split("runtimeUuid=")[1].split()[0];return uuid,record
def owner(uuid):
 p=subprocess.Popen(cmd("owner",uuid),env=env,text=True,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE);lines=[];deadline=time.time()+30
 while time.time()<deadline:
  if select.select([p.stdout],[],[],1)[0]:
   line=p.stdout.readline();lines.append(line)
   if line.startswith("ENGINE_OWNER_HELD"):return p,lines
   if not line:break
 p.terminate();remaining,err=p.communicate(timeout=10);raise RuntimeError("owner failed readiness: "+"".join(lines)+remaining+err)
try:
 for terminate in (False,True):
  case={"kind":"owner_exit_recovery" if terminate else "concurrent_resume_zero_writes","phases":[]};res["cases"].append(case);uuid,record=fixture();case["runtimeUuid"]=uuid;case["phases"].append(record);save();p,lines=owner(uuid);case["ownerProbePid"]=p.pid
  contender=run("contender",uuid);case["phases"].append(contender);save();assert contender["exitCode"]==0 and "ENGINE_LOSER_ZERO_WRITES" in contender["stdout"],"loser mutated or failed"
  if terminate:p.terminate();stdout,stderr=p.communicate(timeout=15)
  else:stdout,stderr=p.communicate("\n",timeout=30)
  case["phases"].append({"phase":"owner_terminate" if terminate else "owner_release","exitCode":p.returncode,"stdout":"".join(lines)+stdout,"stderr":stderr});save()
  if terminate:
   recovery=run("recover",uuid);case["phases"].append(recovery);assert recovery["exitCode"]==0 and "ENGINE_RECOVERY_SUCCEEDED" in recovery["stdout"]
  else:assert p.returncode==0 and "ENGINE_RECOVERY_SUCCEEDED" in stdout
  terminal=run("terminal",uuid);case["phases"].append(terminal);assert terminal["exitCode"]==0 and "ENGINE_TERMINAL_ZERO_WRITES" in terminal["stdout"];case["passed"]=True;save()
 res["status"]="VERIFIED_ENGINE_QA_COMPETITION_SLICE";res["limitations"]=["Start/End QA only; no model/tool/external effect","No durable atomic fencing","Message persistence is current TODO; null chat session and SSE","No HTTP resume endpoint or live API recovery acceptance"]
except Exception as error:
 res["status"]="FAILED";res["failure"]=str(error);raise
finally:save()
print(json.dumps({"status":res["status"],"out":str(out),"cases":len(res["cases"])}))
