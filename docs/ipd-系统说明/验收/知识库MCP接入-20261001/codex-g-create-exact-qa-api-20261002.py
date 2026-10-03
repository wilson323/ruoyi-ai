#!/usr/bin/env python3
"""Root-reviewed exact nine-step local QA creation. No start/model/cleanup APIs."""
import argparse,datetime,importlib.util,json,pathlib,subprocess,urllib.request,urllib.error
HERE=pathlib.Path(__file__).parent
ROOT=pathlib.Path('/Users/mac/Documents/ruoyi-ai')
OUT=HERE/'codex-g-create-exact-qa-api-evidence-20261002.json'
CODE='qa-member-7d37b33e5fd2'; NAME='QA同组非成员独立项目-7d37b33e5fd2'
def db(sql):
 r=subprocess.run(['/opt/homebrew/bin/mysql','--defaults-extra-file='+str(ROOT/'.codex/ipd-dev/config/mysql-client.cnf'),'--protocol=TCP','-h127.0.0.1','-P13306','-D','ipd_dev','--batch','--raw','--skip-column-names','-e','START TRANSACTION READ ONLY; '+sql+'; ROLLBACK;'],capture_output=True,text=True)
 if r.returncode:raise RuntimeError('read_only_db_failed')
 return [line.split('\t') for line in r.stdout.splitlines()]
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--execute-root-reviewed-nine',action='store_true');args=ap.parse_args()
 if not args.execute_root_reviewed_nine:ap.error('exact root-reviewed invocation required')
 spec=importlib.util.spec_from_file_location('safe',HERE/'runtime-acceptance-20261002.py');safe=importlib.util.module_from_spec(spec);spec.loader.exec_module(safe)
 report={'status':'RUNNING','time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'local ipd_dev exact approved QA creation only','sessions':[],'steps':[],'syntheticInputs':True,'startExecuted':False,'modelCalled':False,'cleanupPerformed':False}
 def save():OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2))
 tokens={}
 def call(actor,method,path,body=None):
  req=urllib.request.Request(safe.BASE+(path[len('/api/v1'):] if path.startswith('/api/v1/') else path),method=method,headers={'Authorization':'Bearer '+tokens[actor],'Content-Type':'application/json'},data=json.dumps(body).encode() if body is not None else None)
  try:
   with urllib.request.urlopen(req,timeout=30) as resp:return resp.status,json.load(resp)
  except urllib.error.HTTPError as exc:
   try:return exc.code,json.load(exc)
   except Exception:return exc.code,{'code':'NON_JSON'}
  except Exception as exc:return -1,{'code':'UNKNOWN_RESULT','safeErrorType':type(exc).__name__}
 try:
  report['preexisting']=db("SELECT id,line_code,line_name,status,leader_person_id FROM product_lines WHERE line_code='"+CODE+"'; SELECT id,name,status,create_by FROM projects WHERE name='"+NAME+"'")
  if report['preexisting']:raise RuntimeError('exact_fixture_already_exists_read_before_reuse_no_duplicate_write')
  report['personDb']=db('SELECT id,person_type,group_id,account_status,employment_status,level FROM persons WHERE id IN (900101,900102,900103,900104) ORDER BY id')
  assert len(report['personDb'])==4 and all(row[2:5]==['900001','ACTIVE','ACTIVE'] for row in report['personDb'])
  report['allowance']=db("SELECT p.id,p.level,c.config_key,c.config_value FROM persons p LEFT JOIN system_configs c ON c.config_key=CONCAT('allowance.',p.level) AND c.del_flag=0 WHERE p.id IN (900103,900104)")
  assert len(report['allowance'])==2 and all(int(row[3])>0 for row in report['allowance'])
  for actor,user,role in [('900101','ipd-admin','SUPER_ADMIN'),('900102','ipd-leader','GROUP_LEADER'),('900103','ipd-market','MARKET_PM'),('900104','ipd-rd','RD_PM')]:
   http,login=safe.call('POST','/auth/login',body={'username':user,'password':safe.accounts()[user]})
   assert http==200 and login.get('code')==0
   tokens[actor]=login['data'].get('token') or login['data'].get('accessToken'); assert tokens[actor]
   h,me=call(actor,'GET','/auth/me'); person=(me.get('data') or {}).get('person') or {}
   row={'actor':actor,'http':h,'code':me.get('code'),'person':{k:person.get(k) for k in ['id','personType','groupId','accountStatus','employmentStatus','level']}};report['sessions'].append(row);save()
   assert h==200 and me.get('code')==0 and str(person.get('id'))==actor and person.get('personType')==role and str(person.get('groupId'))=='900001' and person.get('accountStatus')=='ACTIVE' 
  plan=json.loads((HERE/'codex-g-create-qa-request-dry-run-20261002.json').read_text())
  assert len(plan['calls'])==9 and plan['calls'][0]['body']['code']==CODE and plan['calls'][-1]['body']['name']==NAME
  line_id=None;project_id=None
  for n,step in enumerate(plan['calls'],1):
   path=step['path'].replace('{returnedLineId}',str(line_id));body=step['body']
   if body is not None:body=json.loads(json.dumps(body).replace('{returnedLineId}',str(line_id)))
   assert 'approve-start' not in path and 'agent-runs' not in path
   h,response=call(step['actorPersonId'],step['method'],path,body)
   data=response.get('data') or {};row={'step':n,'actor':step['actorPersonId'],'method':step['method'],'path':path,'request':body,'http':h,'code':response.get('code'),'response':{k:data.get(k) for k in ['id','code','name','status','leaderPersonId','personId','reviewedBy','mainGroupId','productId','createBy']}};report['steps'].append(row);save()
   if h!=200 or response.get('code')!=0:
    report['failureLookup']=db("SELECT id,line_code,line_name,status,leader_person_id FROM product_lines WHERE line_code='"+CODE+"'; SELECT id,name,status,create_by FROM projects WHERE name='"+NAME+"'")
    raise RuntimeError('api_step_failed_or_unknown_'+str(n))
   if n==1:line_id=str(data['id']);assert line_id.isdigit();report['lineId']=line_id
   if n==9:project_id=str(data['id']);assert project_id.isdigit();report['projectId']=project_id
  report['finalDb']=db('SELECT id,line_code,line_name,status,leader_person_id FROM product_lines WHERE id='+line_id+'; SELECT person_id,status FROM product_line_members WHERE product_line_id='+line_id+' AND del_flag=0 ORDER BY person_id; SELECT id,name,status,create_by,main_group_id,product_line_id,product_id FROM projects WHERE id='+project_id+'; SELECT person_id,role,exit_date FROM project_members WHERE project_id='+project_id+' AND del_flag=0 ORDER BY person_id; SELECT COUNT(*) FROM project_stages WHERE project_id='+project_id+'; SELECT COUNT(*) FROM stage_actions WHERE project_id='+project_id)
  report['finalHttp']={}
  for label,actor,path in [('project','900103','/projects/'+project_id),('members','900103','/projects/'+project_id+'/members'),('stages','900103','/projects/'+project_id+'/stages'),('leaderLineProjects','900102','/ipd/product-lines/'+line_id+'/projects')]:
   h,body=call(actor,'GET',path);report['finalHttp'][label]={'http':h,'body':safe.redact(body)}
  rows=report['finalDb'];assert rows[0][3:] == ['ACTIVE','900102'];assert any(row==['900103','MARKET_PM','NULL'] for row in rows) and any(row==['900104','RD_PM','NULL'] for row in rows);assert not any(len(row)==3 and row[0]=='900102' for row in rows)
  project=[row for row in rows if len(row)==7][0];assert project[2:] == ['PENDING_START','900103','900001',line_id,'NULL'];assert rows[-2:]==[['0'],['0']]
  report['status']='CREATED_PENDING_START_VERIFIED_NOT_PERMISSION_ACCEPTANCE'
 except Exception as exc:
  report['status']='PARTIAL_STOPPED';report['safeFailureType']=type(exc).__name__;report['safeFailure']=str(exc) if isinstance(exc,RuntimeError) else 'precondition_or_validation_failed'
 finally:save()
 print(json.dumps({'status':report['status'],'steps':len(report['steps']),'lineId':report.get('lineId'),'projectId':report.get('projectId'),'evidence':str(OUT)}))
if __name__=='__main__':main()
