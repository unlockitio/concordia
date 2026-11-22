#!/usr/bin/env python3
from pathlib import Path
import json,importlib.util,socket,subprocess,tempfile,os,re
here=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('ports',here/'check-ledger-ports.py');ports=importlib.util.module_from_spec(spec);spec.loader.exec_module(ports)
assert ports.ports({})==[41000,41001]
for bad in ('0','1023','65536','abc','-1'):
    try:ports.ports({'LEDGER_HTTP_PORT':bad});raise AssertionError(bad)
    except ValueError:pass
try:ports.ports({'LEDGER_HTTP_PORT':'41000'});raise AssertionError()
except ValueError:pass
with socket.socket() as s:
    s.bind(('127.0.0.1',0));port=s.getsockname()[1]
    try:ports.check({'LEDGER_PORT':str(port),'LEDGER_HTTP_PORT':str(port+1 if port<65535 else port-1)});raise AssertionError()
    except ValueError as e:assert str(port) in str(e)
collection=json.loads((here/'postman/babycdf-ledger-http.postman_collection.json').read_text());assert collection['info']['schema'].endswith('/v2.1.0/collection.json')
allowed={'GET':{'/v2/version','/v2/state/ledger-end','/v2/parties'},'POST':{'/v2/state/active-contracts','/v2/updates'}}
ledger=(here/'ledger.sh').read_text()
for item in collection['item']:
    req=item['request'];assert req['url'].startswith('{{baseUrl}}');path=req['url'].replace('{{baseUrl}}','');assert path in allowed[req['method']] and path in ledger
    assert all(h['key'].lower()!='authorization' for h in req['header'])
    if 'body' in req:json.loads(req['body']['raw'].replace('{{offset}}','1'))
text=json.dumps(collection);assert 'github_pat_' not in text and 'Bearer ' not in text
# Exercise real checked HTTP functions with stub curl and no Docker.
def function(name):return re.search(r'^'+name+r'\(\) \{.*?^\}',ledger,re.M|re.S).group(0)
with tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);curl=root/'curl';curl.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$CALLS"\nprintf \'{"offset":1}\\n200\\n\'\n');curl.chmod(0o755)
    harness='set -eu\nBABYCDF_HTTP_VIA=host\nAPI=http://127.0.0.1:41001\nscratch() { mktemp; }\ndie() { exit 1; }\nrefuse() { exit 2; }\n'+ '\n'.join(function(n) for n in ('http_get_allowed','http_post_allowed','http_curl','http_get','http_post'))+'\nhttp_get "$1"\n'
    env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],CALLS=str(root/'calls'))
    p=subprocess.run(['sh','-c',harness,'stub','/v2/state/ledger-end'],env=env,capture_output=True,text=True);assert p.returncode==0,p.stderr;assert '127.0.0.1:41001' in (root/'calls').read_text()
    (root/'calls').write_text('');p=subprocess.run(['sh','-c',harness,'stub','/v2/commands/submit'],env=env,capture_output=True,text=True);assert p.returncode==2 and not (root/'calls').read_text()
# Reference-only rule rendering, no embedded clauses.
rule=function('cmd_rule')
harness='set -eu\nIMPL=#cap-example-babycdf\nload_state() { :; }\nfatal_raised() { return 1; }\nacs_template() { printf \'%s\\n\' \'[{"cid":"cid","arg":{"draft":{"ruleId":"architecture-review","version":2,"ruleRef":{"location":"rules/rule-v2.json","commit":null,"sha256":"abc"}},"members":{"map":[]}}}]\'; }\n'+rule+'\ncmd_rule\n'
p=subprocess.run(['sh','-c',harness],capture_output=True,text=True);assert p.returncode==0,p.stderr
for expected in ('version  2','location rules/rule-v2.json','commit   none','sha256   abc','document not verified'):assert expected in p.stdout,p.stdout
print('Host HTTP allowlist, ports, ref-only rule and read-only Postman collection passed')
