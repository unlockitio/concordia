#!/usr/bin/env python3
"""Bounded atomic Canton JSON API ACS pagination. Tokens are opaque JSON strings."""
import json,sys,subprocess,time
MAX_PAGES=1000
MAX_CONTRACTS=10000
MAX_BYTES=20_000_000

def strict_object(pairs):
    result={}
    for key,value in pairs:
        if key in result:raise ValueError('duplicate JSON key')
        result[key]=value
    return result

def collect(base,request,fetch,clock=time.monotonic):
    if not isinstance(request,dict) or 'activeAtOffset' not in request or 'eventFormat' not in request:raise ValueError('invalid ACS request')
    end=request['activeAtOffset'];seen=set();rows=[];total=0;token=None;contracts={};received=0
    if isinstance(end,bool) or not str(end).isdigit():raise ValueError('invalid ACS offset')
    deadline=clock()+120
    for _ in range(MAX_PAGES):
        remaining=deadline-clock()
        if remaining<=0:raise ValueError('ACS total deadline')
        body=dict(request,maxPageSize=200)
        if token is not None:body['pageToken']=token
        raw=fetch(base+'/v2/state/active-contracts-page',body,min(15,remaining));total+=len(raw)
        if clock()>=deadline:raise ValueError('ACS total deadline')
        if total>MAX_BYTES:raise ValueError('ACS byte bound')
        page=json.loads(raw,object_pairs_hook=strict_object)
        if clock()>=deadline:raise ValueError('ACS total deadline')
        if not isinstance(page,dict) or set(page)-{'activeContracts','activeAtOffset','nextPageToken'} or not isinstance(page.get('activeContracts'),list):raise ValueError('invalid ACS page')
        offset=page.get('activeAtOffset')
        if isinstance(offset,bool) or not isinstance(offset,(str,int)) or not str(offset).isdigit() or str(offset)!=str(end):raise ValueError('ACS offset mismatch')
        received+=len(page['activeContracts'])
        if received>MAX_CONTRACTS:raise ValueError('ACS contract bound')
        for entry in page['activeContracts']:
            if not isinstance(entry,dict):raise ValueError('invalid ACS entry')
            wrapper=entry.get('contractEntry')
            if not isinstance(wrapper,dict) or set(wrapper)!={'JsActiveContract'}:raise ValueError('incomplete/invalid ACS reassignment')
            active=wrapper['JsActiveContract']
            if not isinstance(active,dict) or not isinstance(active.get('createdEvent'),dict):raise ValueError('invalid ACS created event')
            created=active['createdEvent'];cid=created.get('contractId')
            if not isinstance(cid,str) or not cid:raise ValueError('missing full CID')
            canonical=json.dumps(entry,sort_keys=True,separators=(',',':'))
            if cid in contracts:
                if contracts[cid]!=canonical:raise ValueError('conflicting duplicate CID')
                continue
            contracts[cid]=canonical;rows.append(entry)
        if len(rows)>MAX_CONTRACTS:raise ValueError('ACS contract bound')
        token=page.get('nextPageToken')
        if clock()>=deadline:raise ValueError('ACS total deadline')
        if 'nextPageToken' not in page or token is None:return rows
        if not isinstance(token,str) or not token or len(token)>16384 or token in seen:raise ValueError('invalid/nonprogressing ACS token')
        seen.add(token)
    raise ValueError('ACS page bound')
def main():
    base=sys.argv[1].rstrip('/');request=json.load(sys.stdin,object_pairs_hook=strict_object);publication_deadline=time.monotonic()+120
    def fetch(url,body,timeout):
        prefix=[]
        import os
        if os.environ.get('BABYCDF_HTTP_VIA','exec')=='exec':prefix=['docker','compose','-p',os.environ.get('BABYCDF_PROJECT','babycdf-live'),'exec','-T','canton']
        p=subprocess.run(prefix+['curl','-q','-sS','-o','/dev/stdout','-w','\n%{http_code}','--max-time',str(timeout),'-H','Content-Type: application/json','-X','POST','--data-binary',json.dumps(body,separators=(',',':')),url],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=timeout)
        if p.returncode:
            status=p.stderr.decode('utf-8','replace')
            import re
            match=re.search(r'(?:error: |HTTP[^0-9]*)([45][0-9]{2})',status)
            raise ValueError('HTTP '+match.group(1) if match else 'ACS HTTP request failed')
        raw,separator,status=p.stdout.rstrip(b'\n').rpartition(b'\n')
        if not separator or not status.isdigit() or len(status)!=3:raise ValueError('invalid HTTP status')
        if not status.startswith(b'2'):raise ValueError('HTTP '+status.decode('ascii'))
        return raw
    try:
        rows=collect(base,request,fetch)
        encoded=json.dumps(rows,separators=(',',':'))+'\n'
        if time.monotonic()>=publication_deadline:raise ValueError('ACS publication deadline')
    except (ValueError,KeyError,TypeError,subprocess.TimeoutExpired) as error:
        category=str(error) if str(error).startswith('HTTP ') and str(error)[5:].isdigit() else 'invalid or incomplete page'
        print('BLOCKED: '+category+'; incomplete HTTP ACS pagination',file=sys.stderr);return 2
    sys.stdout.write(encoded);return 0
if __name__=='__main__':sys.exit(main())
