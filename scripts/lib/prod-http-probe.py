#!/usr/bin/env python3
"""Read-only IPD HTTP probe. IPD_GATE_TOKEN accepts a raw token or full Bearer header; neither is printed."""
import json
import os
import sys
import urllib.request
from urllib.parse import urlparse


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Only the requested endpoint may supply HTTP200 evidence or receive its session."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def probe(base, path, token, audit=False):
    parsed=urlparse(base)
    if parsed.scheme not in ('http','https') or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('invalid backend address')
    token=token.strip()
    if not token or '\r' in token or '\n' in token:
        raise ValueError('Person session required')
    authorization=token if token.lower().startswith('bearer ') else 'Bearer '+token
    request=urllib.request.Request(base.rstrip('/')+path,headers={'Authorization':authorization})
    with urllib.request.build_opener(NoRedirect()).open(request,timeout=5) as response:
        if response.status!=200:
            raise ValueError('HTTP 200 required')
        payload=json.loads(response.read(2*1024*1024))
    if not isinstance(payload,dict) or type(payload.get('code')) is not int or payload['code']!=0:
        raise ValueError('IPD code0 envelope required')
    if not isinstance(payload.get('message'),str) or not payload['message'].strip() or payload.get('data') is None:
        raise ValueError('IPD message/data envelope required')
    data=payload['data']
    if path=='/api/v1/auth/me':
        person=data.get('person') if isinstance(data,dict) else None
        if (not isinstance(person,dict) or not isinstance(person.get('id'),str)
                or not person['id'].strip() or data.get('scope')!='FULL'):
            raise ValueError('Full Person session required')
    if audit:
        data=payload.get('data')
        if not isinstance(data,dict) or data.get('chain')!='OK':
            raise ValueError('audit chain is not OK')
        if any(data.get(name)!=[] for name in ('broken','hashBroken','gaps')):
            raise ValueError('audit chain diagnostics missing or nonempty')
    return True

if __name__=='__main__':
    try:
        probe(os.environ.get('BACKEND_URL','http://127.0.0.1:16039'),sys.argv[1],
              os.environ.get('IPD_GATE_TOKEN',''),len(sys.argv)>2 and sys.argv[2]=='audit')
    except Exception:
        print('UNVERIFIED/FAIL: authenticated HTTP contract or audit evidence unavailable',file=sys.stderr)
        sys.exit(1)
