from pathlib import Path
import hashlib,zipfile,io,json,subprocess,datetime
b=Path('/Users/mac/Documents/ruoyi-ai'); ev=Path(__file__).parent
h=lambda data:hashlib.sha256(data).hexdigest()
j=b/'ruoyi-admin/target/ruoyi-admin.jar'
with zipfile.ZipFile(j) as z:
 names=z.namelist(); modules={}
 for m in ['ipd','chat','aiflow']:
  name=f'BOOT-INF/lib/ruoyi-{m}-3.1.0.jar';data=z.read(name)
  assert h(data)==h((b/f'ruoyi-modules/ruoyi-{m}/target/ruoyi-{m}-3.1.0.jar').read_bytes()),m
  modules[m]={'path':name,'sha256':h(data)}
  if m=='ipd':
   with zipfile.ZipFile(io.BytesIO(data)) as c:
    src=b/'ruoyi-modules/ruoyi-ipd/src/main/resources'
    for f in (src/'ipd-skills').rglob('*'):
     if f.is_file():assert h(c.read(str(f.relative_to(src))))==h(f.read_bytes()),str(f)
 assert any('agentscope-core-2.0.3.jar' in x for x in names)
 assert not any('langgraph' in x.lower() or 'langchain4j' in x.lower() for x in names)
 assert any('redisson-3.51.0.jar' in x for x in names)
 deps=[x for x in names if x.startswith('BOOT-INF/lib/')]
files={}
for base in ['ruoyi-modules/ruoyi-ipd/src','ruoyi-modules/ruoyi-chat/src','ruoyi-modules/ruoyi-aiflow/src']:
 for f in (b/base).rglob('*'):
  if f.is_file():files[str(f.relative_to(b))]=h(f.read_bytes())
for f in b.rglob('pom.xml'):
 if not any(x in f.parts for x in ['.git','target','.codex','.worktrees']):files[str(f.relative_to(b))]=h(f.read_bytes())
r={'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'tested current worktree candidate; not clean HEAD acceptance','head':subprocess.check_output(['git','rev-parse','HEAD'],cwd=b,text=True).strip(),'jarSha256':h(j.read_bytes()),'jar':str(j),'modules':modules,'dependencies':deps,'sourceHashes':files,'status':'PENDING_RUNTIME_VALIDATION'}
(ev/'codex-candidate-current-20261002.json').write_text(json.dumps(r,ensure_ascii=False,indent=2));print(json.dumps({'jarSha256':r['jarSha256'],'modules':modules,'sourceFiles':len(files)}))
