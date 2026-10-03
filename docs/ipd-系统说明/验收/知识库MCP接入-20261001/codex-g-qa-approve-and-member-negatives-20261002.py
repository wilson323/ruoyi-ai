import importlib.util,pathlib,json,urllib.request,urllib.error,hashlib,datetime
HERE=pathlib.Path(__file__).parent;PID='2106098805312069634';LINE='2106098453477072898';OUT=HERE/'codex-g-qa-approve-and-member-negatives-20261002.json'
def load(name,file):
 sp=importlib.util.spec_from_file_location(name,HERE/file);m=importlib.util.module_from_spec(sp);sp.loader.exec_module(m);return m
qa=load('qa','codex-g-create-exact-qa-api-20261002.py');safe=load('safe','runtime-acceptance-20261002.py')
report={'status':'RUNNING','runWindowJarSha256':'a44e858e9c72ccb2da0bf54a5ad2eeddd0026872a920feb250ce07a37e4a1470','pid':31351,'projectId':PID,'lineId':LINE,'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'negatives':[],'retainedQaAuditAndOutputs':True}
def save():OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2))
try:
 pre=qa.db('SELECT status,create_by,main_group_id,product_line_id FROM projects WHERE id='+PID+'; SELECT leader_person_id,status FROM product_lines WHERE id='+LINE+'; SELECT COUNT(*) FROM project_members WHERE project_id='+PID+' AND person_id=900102 AND exit_date IS NULL AND del_flag=0; SELECT id,model_name FROM ai_model_configs WHERE is_active=1 AND del_flag=0; SELECT group_id,person_type,account_status,employment_status FROM persons WHERE id=900102')
 report['preflight']=pre;assert pre[0]==['PENDING_START','900103','900001',LINE] and pre[1]==['900102','ACTIVE'] and pre[2]==['0'] and pre[3]==['2104885081318375426','MiniMax-M3'] and pre[4]==['900001','GROUP_LEADER','ACTIVE','ACTIVE']
 h,l=safe.call('POST','/auth/login',body={'username':'ipd-leader','password':safe.accounts()['ipd-leader']});assert h==200 and l['code']==0;token=l['data']['token']
 def call(method,path,body=None):
  req=urllib.request.Request(safe.BASE+path,method=method,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},data=json.dumps(body).encode() if body is not None else None)
  try:
   with urllib.request.urlopen(req,timeout=40) as r:return {'http':r.status,'body':safe.redact(json.load(r))}
  except urllib.error.HTTPError as e:return {'http':e.code,'body':safe.redact(json.load(e))}
  except Exception as e:return {'http':-1,'safeErrorType':type(e).__name__}
 me=call('GET','/auth/me');p=me['body']['data']['person'];assert p['id']=='900102' and p['groupId']=='900001' and p['personType']=='GROUP_LEADER';report['session']={k:p[k] for k in ['id','personType','groupId','accountStatus']}
 report['approve']=call('POST','/projects/'+PID+'/approve-start');save()
 graph=qa.db('SELECT status,current_stage,product_id,main_group_id,create_by FROM projects WHERE id='+PID+'; SELECT COUNT(*) FROM project_stages WHERE project_id='+PID+'; SELECT COUNT(*) FROM stage_actions WHERE project_id='+PID+'; SELECT p.id,p.product_line_id,p.project_id,p.status FROM products p JOIN projects j ON j.product_id=p.id WHERE j.id='+PID+'; SELECT person_id,role FROM project_members WHERE project_id='+PID+' AND exit_date IS NULL AND del_flag=0 ORDER BY person_id; SELECT id,action_code,depth,status,stage_id FROM stage_actions WHERE project_id='+PID+" AND action_code='C05'")
 report['graph']=graph;assert report['approve']['http']==200 and report['approve']['body']['code']==0;assert graph[0][0:2]==['TEAMING','CONCEPT'] and graph[1:3]==[['6'],['69']];assert graph[3][1:]==[LINE,PID,'IN_RD'];assert graph[4:6]==[['900103','MARKET_PM'],['900104','RD_PM']]
 aid=graph[6][0];assert aid.isdigit() and graph[6][1:4]==['C05','LIGHT','NOT_STARTED']
 def snapshot():
  # Concurrent C01 background writes excluded; protect exact project/stages/product/member and C05 rows.
  return qa.db('SELECT * FROM projects WHERE id='+PID+'; SELECT * FROM project_stages WHERE project_id='+PID+' ORDER BY id; SELECT * FROM stage_actions WHERE id='+aid+'; SELECT * FROM project_members WHERE project_id='+PID+' ORDER BY id; SELECT p.* FROM products p JOIN projects j ON j.product_id=p.id WHERE j.id='+PID)
 for name,path,body in [('advance','/projects/'+PID+'/advance-stage',None),('stageAcceptance','/projects/'+PID+'/stage-acceptance',None),('c05Fields','/stage-actions/'+aid+'/fields',{'actualDoneAt':'2026-10-02'}),('c05Transit','/stage-actions/'+aid+'/transit?target=IN_PROGRESS',None)]:
  before=snapshot();r=call('POST',path,body);after=snapshot();digest=lambda x:hashlib.sha256(json.dumps(x,separators=(',',':')).encode()).hexdigest();row={'name':name,'path':path,'response':r,'beforeSha256':digest(before),'afterSha256':digest(after),'exactProtectedBusinessRowsUnchanged':before==after,'hashScope':'projects/project_stages/products/project_members/exactC05; excludes independently scheduled C01/docs/tasks/notifications'};report['negatives'].append(row);save()
  if r.get('http')!=403 or r.get('body',{}).get('code')!=30001 or before!=after:raise RuntimeError('negative_not_verified_'+name)
 report['taskAndDocReadback']=qa.db('SELECT id,action_code,trigger_type,status,attempt,triggered_by,create_by,ai_doc_id FROM ai_agent_tasks WHERE project_id='+PID+' ORDER BY id; SELECT id,doc_type,status,model,token_prompt,token_completion FROM ai_documents WHERE project_id='+PID+' ORDER BY id; SELECT action_code,status,confirmed_by FROM stage_actions WHERE project_id='+PID+" AND action_code IN ('C01','C05') ORDER BY action_code")
 report['status']='APPROVED_GRAPH_AND_FOUR_MEMBER_NEGATIVES_VERIFIED';report['windowReleased']=True
except Exception as e:
 report['status']='PARTIAL_STOPPED';report['safeFailureType']=type(e).__name__;report['safeFailure']=str(e) if isinstance(e,RuntimeError) else 'precondition_or_validation_failed';report['windowReleased']=True
finally:save()
print(json.dumps({'status':report['status'],'negativeCount':len(report['negatives']),'safeFailure':report.get('safeFailure'),'evidence':str(OUT)}))
