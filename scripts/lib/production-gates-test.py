#!/usr/bin/env python3
"""Local fake HTTP/compose contracts; never reads credentials or runs real services."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, HTTPServer

ROOT=Path(__file__).resolve().parents[2]
def load(path,name):
 spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
probe=load(ROOT/'scripts/lib/prod-http-probe.py','probe')
class Handler(BaseHTTPRequestHandler):
 status=200
 redirect=None
 payload={'code':0,'message':'成功','data':{'chain':'OK','broken':[],'hashBroken':[],'gaps':[]}}
 def do_GET(self):
  status=self.status if self.headers.get('Authorization')=='Bearer fixture-session' else 401
  self.send_response(status)
  if self.redirect: self.send_header('Location',self.redirect)
  self.end_headers();self.wfile.write(json.dumps(self.payload).encode())
 def log_message(self,*args): pass
server=HTTPServer(('127.0.0.1',0),Handler)
thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
base=f'http://127.0.0.1:{server.server_port}'
def expected(ok,audit=True):
 try: probe.probe(base,'/api/v1/audit-logs/verify','fixture-session',audit);result=True
 except Exception: result=False
 assert result==ok
class RedirectTarget(BaseHTTPRequestHandler):
 requests=[]
 def do_GET(self):
  self.requests.append(self.headers.get('Authorization'))
  self.send_response(200);self.end_headers();self.wfile.write(b'{"code":0,"message":"OK","data":{}}')
 def log_message(self,*args): pass
redirect_server=HTTPServer(('127.0.0.1',0),RedirectTarget)
redirect_thread=threading.Thread(target=redirect_server.serve_forever,daemon=True);redirect_thread.start()
try:
 for status in (301,302,303,307,308):
  Handler.status=status;Handler.redirect=f'http://127.0.0.1:{redirect_server.server_port}/target'
  expected(False,False)
 assert not RedirectTarget.requests, 'redirect target received a request or session'
 Handler.status=200;Handler.redirect=None
 expected(True)
 assert probe.probe(base,'/api/v1/audit-logs/verify','Bearer fixture-session',True)
 try: probe.probe(base,'/api/v1/projects','')
 except ValueError: pass
 else: raise AssertionError('missing session accepted')
 Handler.payload={'code':0,'message':'成功','data':{'person':{'id':'900001'},'scope':'FULL'}}
 assert probe.probe(base,'/api/v1/auth/me','fixture-session')
 for data in ({'person':{'id':900001},'scope':'FULL'},
              {'person':{'id':''},'scope':'FULL'},
              {'person':{'id':'900001'},'scope':'PASSWORD_CHANGE_REQUIRED'},
              {'id':'900001','scope':'FULL'}, {'person':'fake','scope':'FULL'}):
  Handler.payload={'code':0,'message':'成功','data':data}
  try: probe.probe(base,'/api/v1/auth/me','fixture-session')
  except ValueError: pass
  else: raise AssertionError('invalid Person accepted')
 for payload in ({'code':0}, {'code':0,'data':{}}, {'code':0,'message':'成功'},
                 {'code':0,'message':'成功','data':None}, {'code':0,'message':None,'data':{}}):
  Handler.payload=payload;expected(False,False)
 Handler.payload={'code':0,'message':'成功','data':{'chain':'OK','broken':[],'hashBroken':[],'gaps':[]}}
 Handler.status=401;expected(False)
 Handler.status=200;Handler.payload={'code':10001,'data':{}};expected(False,False)
 for chain in ('GAP','HASH_BROKEN','BROKEN'):
  Handler.payload={'code':0,'message':'成功','data':{'chain':chain,'total':10000,'broken':[],'hashBroken':[],'gaps':[]}};expected(False)
 Handler.payload={'code':0,'message':'成功','data':{'chain':'OK'}};expected(False)
 Handler.payload={'code':0,'message':'成功','data':{'chain':'OK','broken':[1],'hashBroken':[],'gaps':[]}};expected(False)
finally:
 server.shutdown();server.server_close();thread.join()
 redirect_server.shutdown();redirect_server.server_close();redirect_thread.join()
with tempfile.TemporaryDirectory(prefix='ipd-prod-gates-') as td:
 tmp=Path(td)
 gate=load(ROOT/'scripts/check-done-gate.py','done_gate')
 # Use a random-port fake server; production code must request only the formal backend.
 class DoneHandler(BaseHTTPRequestHandler):
  business={'code':0,'message':'成功','data':{}}
  person={'code':0,'message':'成功','data':{'person':{'id':'900001'},'scope':'FULL'}}
  redirect=False
  requests=[]
  def do_GET(self):
   self.requests.append(self.path)
   self.send_response(302 if self.redirect else 200)
   if self.redirect: self.send_header('Location',f'http://127.0.0.1:{done_server.server_port}/target')
   self.end_headers()
   self.wfile.write(json.dumps(self.person if self.path=='/api/v1/auth/me' else self.business).encode())
  def log_message(self,*args): pass
 done_server=HTTPServer(('127.0.0.1',0),DoneHandler)
 done_thread=threading.Thread(target=done_server.serve_forever,daemon=True);done_thread.start()
 done_base=f'http://127.0.0.1:{done_server.server_port}'
 original_probe=probe.probe
 def routed_probe(request_base,path,token,audit=False):
  assert request_base=='http://127.0.0.1:16039', 'non-formal backend requested'
  return original_probe(done_base,path,token,audit)
 original_urlopen=gate.urllib.request.urlopen
 def routed_legacy(request,*args,**kwargs):
  # Route the previous implementation too, proving HTTP200/code failure used to pass.
  import urllib.request
  url=request if isinstance(request,str) else request.full_url
  path=__import__('urllib.parse',fromlist=['urlsplit']).urlsplit(url).path
  headers={} if isinstance(request,str) else dict(request.header_items())
  return original_urlopen(urllib.request.Request(done_base+path,headers=headers),*args,**kwargs)
 try:
  gate.CARD_TO_ENDPOINTS={'fixture':['/api/v1/projects']}
  with patch.dict(os.environ,{'IPD_GATE_TOKEN':'fixture-session','BACKEND_URL':'http://127.0.0.1:16049'}):
   with contextlib.ExitStack() as stack:
    stack.enter_context(patch.object(gate.urllib.request,'urlopen',side_effect=routed_legacy))
    if hasattr(gate,'HTTP_PROBE'): stack.enter_context(patch.object(gate.HTTP_PROBE,'probe',side_effect=routed_probe))
    for invalid in (10001,200,False,'0'):
     DoneHandler.business={'code':invalid,'message':'失败','data':{}}
     assert not gate.check_http_endpoints('fixture')['pass'], f'done gate accepted invalid code {invalid!r}'
    DoneHandler.business={'code':0,'message':'成功','data':{}}
    DoneHandler.requests=[]
    assert gate.check_http_endpoints('fixture')['pass']
    assert DoneHandler.requests==['/api/v1/auth/me','/api/v1/projects']
    for person in ({'person':{'id':'900001'},'scope':'PASSWORD_CHANGE_REQUIRED'}, {}, {'person':{'id':900001},'scope':'FULL'}):
     DoneHandler.person={'code':0,'message':'成功','data':person}
     assert not gate.check_http_endpoints('fixture')['pass']
    DoneHandler.person={'code':0,'message':'成功','data':{'person':{'id':'900001'},'scope':'FULL'}}
    for invalid in ({'code':0,'message':'成功'}, {'code':0,'message':'成功','data':None}, {'code':0,'data':{}}):
     DoneHandler.business=invalid
     assert not gate.check_http_endpoints('fixture')['pass']
    DoneHandler.business={'code':0,'message':'成功','data':{}}
    DoneHandler.redirect=True;DoneHandler.requests=[]
    assert not gate.check_http_endpoints('fixture')['pass']
    assert '/target' not in DoneHandler.requests
    DoneHandler.redirect=False
    with patch.dict(os.environ,{'IPD_GATE_TOKEN':''}):
     DoneHandler.requests=[]
     assert not gate.check_http_endpoints('fixture')['pass']
     assert not DoneHandler.requests
    assert not gate.check_http_endpoints('unmapped')['pass']
    assert gate.check_http_endpoints('unmapped')['state']=='UNVERIFIED'
 finally:
  done_server.shutdown();done_server.server_close();done_thread.join()
 gate.SQL_DIR=tmp/'sql';gate.SQL_DIR.mkdir()
 (gate.SQL_DIR/'P2-8-1-example.sql').write_text('CREATE TABLE fixture(id int);')
 assert gate.check_ddl_apply('P2-8.1')['state']=='UNVERIFIED'
 assert not gate.check_ddl_apply('P2-8.1')['pass']
 assert not gate.check_ddl_apply('OTHER')['pass']
 gate.CARD_TO_TABLES={'fixture':['fixture_table']}
 with patch.object(gate.subprocess,'check_output',side_effect=[b'fixture-only',subprocess.CalledProcessError(1,['fixture'])]):
  failure=gate.check_business_tables('fixture')
  assert not failure['pass'] and failure['rows']['fixture_table']=='QUERY_FAILED'
 gate.check_acceptance_report=lambda _: {'pass':True}
 gate.check_business_tables=lambda _: {'pass':True}
 gate.check_http_endpoints=lambda _: {'pass':True}
 gate.check_test_type=lambda *_: {'pass':True}
 old=sys.argv;sys.argv=['done','P2-8.1']
 try:
  with contextlib.redirect_stdout(io.StringIO()):
   try: gate.main()
   except SystemExit as e: assert e.code==2
 finally: sys.argv=old
 (tmp/'scripts').mkdir();(tmp/'bin').mkdir()
 shutil.copy(ROOT/'scripts/start-prod.sh',tmp/'scripts/start-prod.sh')
 (tmp/'.env').write_text('IPD_DB_URL=fixture\nIPD_DB_USER=fixture\nIPD_DB_PASSWORD=fixture\nIPD_JWT_SECRET_KEY=fixture\n')
 for name,body in [('lsof','exit 1'),('docker-compose','[ "$1" = ps ] && echo fixture-container\nexit 0'),('docker','echo healthy')]:
  f=tmp/'bin'/name;f.write_text('#!/bin/sh\n'+body+'\n');f.chmod(0o755)
 env=dict(os.environ,PATH=f'{tmp/"bin"}:{os.environ["PATH"]}')
 for rc in (1,0):
  (tmp/'scripts/verify-prod.sh').write_text(f'#!/bin/sh\nexit {rc}\n')
  result=subprocess.run(['bash',str(tmp/'scripts/start-prod.sh')],env=env,capture_output=True,text=True,timeout=5)
  assert (result.returncode==0)==(rc==0)
  assert ('[DONE]' in result.stdout)==(rc==0)
print('PASS: done gate formal16039/FULL Person/code0/missing mapping/missing session negatives; 301/302/303/307/308 rejected without contacting target; authenticated HTTP/code0; audit four-state/missing diagnostics negatives; SQL existence stays unverified; startup honors authenticated container health and verification exit')
