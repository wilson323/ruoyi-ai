#!/usr/bin/env python3
"""Exact CT3 read inventory only. No HTTP, login, model, or mutation implementation."""
import argparse, datetime, json, pathlib, subprocess, uuid
ROOT = pathlib.Path('/Users/mac/Documents/ruoyi-ai')
PROJECT = '2106072674957529089'
LINE = '2106072393398095874'
DOCUMENT = '2106072675242741762'
QUERIES = {
 'persons': 'SELECT id,person_type,group_id,level,account_status,employment_status,del_flag FROM persons WHERE id IN (900101,900102,900103,900104)',
 'project': f'SELECT id,name,status,product_id,product_line_id,main_group_id,create_by,current_stage,del_flag FROM projects WHERE id={PROJECT}',
 'line': f'SELECT id,line_code,status,leader_person_id,del_flag FROM product_lines WHERE id={LINE}',
 'line_members': f'SELECT id,person_id,status,del_flag FROM product_line_members WHERE product_line_id={LINE}',
 'project_members': f'SELECT id,person_id,role,exit_date,del_flag FROM project_members WHERE project_id={PROJECT}',
 'document': f'SELECT id,project_id,doc_type,status,parent_version_id,version_no FROM ai_documents WHERE id={DOCUMENT}',
 'stages': f'SELECT id,stage_code,status FROM project_stages WHERE project_id={PROJECT}',
 'actions': f'SELECT id,stage_id,action_code,owner_role,depth,status,confirmed_by FROM stage_actions WHERE project_id={PROJECT}',
 'candidate_group': 'SELECT id,group_name,del_flag FROM product_groups WHERE id=900001',
 'models': 'SELECT id,provider,model_name,is_active,del_flag FROM ai_model_configs WHERE del_flag=0',
}
def inventory():
 result = {}
 for label, query in QUERIES.items():
  sql = 'START TRANSACTION READ ONLY; ' + query + '; ROLLBACK;'
  proc = subprocess.run(['/opt/homebrew/bin/mysql', '--defaults-extra-file='+str(ROOT/'.codex/ipd-dev/config/mysql-client.cnf'), '--protocol=TCP', '-h127.0.0.1', '-P13306', '-D', 'ipd_dev', '--batch', '--raw', '-e', sql], capture_output=True, text=True)
  if proc.returncode:
   # Do not forward raw client diagnostics, which could contain connection metadata.
   raise RuntimeError('read inventory failed: '+label+'; exit='+str(proc.returncode))
  lines = proc.stdout.splitlines()
  result[label] = [dict(zip(lines[0].split('\t'), line.split('\t'))) for line in lines[1:]] if lines else []
 return result
def create_qa_plan(data):
 persons = {r['id']: r for r in data['persons']}
 for person, role in [('900101','SUPER_ADMIN'),('900102','GROUP_LEADER'),('900103','MARKET_PM'),('900104','RD_PM')]:
  row = persons.get(person, {})
  if row.get('person_type') != role or row.get('group_id') != '900001' or row.get('account_status') != 'ACTIVE' or row.get('employment_status') != 'ACTIVE' or row.get('del_flag') != '0':
   raise RuntimeError('QA Person role/group/activity precondition failed: '+person)
  if person in ('900103','900104') and row.get('level') not in ('L1','L2','L3','L4','L5'):
   raise RuntimeError('QA PM allowance level unavailable: '+person)
 if not data['candidate_group'] or data['candidate_group'][0]['del_flag'] != '0':
  raise RuntimeError('QA group unavailable')
 suffix = uuid.uuid4().hex[:12]
 calls = [{'actorPersonId':'900101','method':'POST','path':'/api/v1/ipd/product-lines','body':{'code':'qa-member-'+suffix,'name':'QA同组非成员独立空间-'+suffix},'expected':'ACTIVE line with returned string id; no leader yet'}]
 for person in ('900102','900103','900104'):
  calls += [{'actorPersonId':person,'method':'POST','path':'/api/v1/ipd/product-lines/{returnedLineId}/join-applications','body':None,'expected':'PENDING membership'}, {'actorPersonId':'900101','method':'POST','path':'/api/v1/ipd/product-lines/{returnedLineId}/join-applications/'+person+'/review','body':{'approve':True},'expected':'ACTIVE membership; reviewer SUPER_ADMIN before leader appointment'}]
 calls += [{'actorPersonId':'900101','method':'PUT','path':'/api/v1/ipd/product-lines/{returnedLineId}/leader/900102','body':None,'expected':'leader900102 is ACTIVE line member; no global role change'}, {'actorPersonId':'900103','method':'POST','path':'/api/v1/projects','body':{'name':'QA同组非成员独立项目-'+suffix,'productId':None,'templateType':'SOFTWARE','targetMarkets':'["CN"]','level':'A','targetSalesAmount':1,'targetChannelCount':0,'targetNps':0,'targetSceneCount':0,'mainGroupId':'900001','marketPmId':'900103','rdPmId':'900104','productLineId':'{returnedLineId}'},'expected':'PENDING_START; creator900103 MARKET_PM + member900104 RD_PM; candidate900102 NOT project member; stages/actions zero'}]
 return {'status':'DRY_RUN_NOT_SENT','calls':calls,'identity':'Actual DB Person identities checked; root must authenticate each actor and read session role/permissions, never forge role headers','placeholderRule':'Only returnedLineId substitution is required; never infer id','qaBaselines':'1/0/0/0 and SOFTWARE/CN/A are explicit synthetic QA inputs, not sales or product facts','allowancePreflight':'Existing allowance.Lx for actual PM levels must be positive; missing config is blocker, no config mutation','rootStartGate':{'automatic':False,'actorPersonId':'900102','method':'POST','path':'/api/v1/projects/{returnedProjectId}/approve-start','requires':'Explicit separate root call after readback of exact line/project/owner/nonmember/same group and AI effects review'},'compensation':'Keep new QA records and audits. Before start optionally root may deactivate dedicated line through existing API; after start preserve products/stages/actions/tasks/artifacts/events/cert/audit; no SQL rewind/delete. Membership leave/remove are separate legitimate audited operations, no automatic cleanup.','acceptance':'Creation alone is not permission acceptance; actual C05 valid fields/transit/accept negative tests are separate; empty graph/400 is not 403 evidence.'}

if __name__ == '__main__':
 parser = argparse.ArgumentParser()
 parser.add_argument('--mode', choices=['dry-run','inventory','create-qa','mutation'], default='dry-run')
 parser.add_argument('--dry-run', action='store_true', help='Required for create-qa; mutation sender deliberately absent')
 args = parser.parse_args()
 if args.mode == 'create-qa' and not args.dry_run:
  parser.error('create-qa requires --dry-run; root must review exact requests before any independent API sender is implemented')
 if args.mode == 'mutation':
  parser.error('Mutation is not implemented. Explicit root orchestration must supply a reviewed API sequence; SQL approval/DONE and audit deletion are prohibited.')
 data = inventory()
 if args.mode == 'create-qa':
  print(json.dumps(create_qa_plan(data), ensure_ascii=False, indent=2))
  raise SystemExit(0)
 print(json.dumps({'status':'READ_ONLY_INVENTORY_NOT_ACCEPTANCE','mode':args.mode,'time':datetime.datetime.now(datetime.timezone.utc).isoformat(),'database':'ipd_dev','inventory':data,'sessionEvidence':'DB Person inventory only; credentials files are not authenticated sessions. Root must verify actual Person session before any API mutation.','mutationGate':'EXPLICIT_ROOT_CALL_ONLY; no mutation code exists','contract':{'approvalApi':f'POST /api/v1/projects/{PROJECT}/approve-start','approver':'exact current line leader; SUPER_ADMIN only if leader is absent','negativeCandidate':'900102 only after fresh same-group and nonmember readback; current main_group must not be inferred from person role','lightActionCandidate':'C05 / RD_PM / CONCEPT / LIGHT; actual instantiated id required','negativeApi':'POST /api/v1/stage-actions/{actualId}/fields and /transit; accept requires legally DONE first to avoid state 400 masking member denial','completion':'Real RD_PM project member supplies actualDoneAt through fields and legally transits; do not invent completion dates/evidence or write SQL DONE','approvalEffects':['product creation and projects product_id/status/current_stage','six project_stages and catalog stage_actions','project certification synchronization','PROJECT_START_APPROVE audit','onBootstrapped may insert AI task and dispatch engine after commit; model/AI outputs and events may follow'],'compensation':'Approval is not a reversible fixture toggle. Preserve products/stages/actions/tasks/documents/events/audit. Restore only separately authorized temporary membership/group fields after fresh conflict checks; do not delete or rewind approval history.','alternative':'If inactive CT3 is unsuitable, root may create an independently named QA project using POST /api/v1/projects on a verified ACTIVE QA line with same real users, mainGroupId and member PMs, then exact leader approve-start; no business project reuse.'}},ensure_ascii=False,indent=2))
