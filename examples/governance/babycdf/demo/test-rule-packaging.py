#!/usr/bin/env python3
"""Inspect rendered Compose configuration only; never build or start services."""
from pathlib import Path
import json, subprocess, re, os
r=Path(__file__).resolve().parent.parent
dockerfile=(r/'java/Dockerfile').read_text()
assert re.search(r'^COPY\s+rules/\s+/app/rules/\s*$',dockerfile,re.M)
assert 'WORKDIR /app/java' in dockerfile
assert 'System.getProperty("babycdf.rules.root","../rules")' in (r/'java/src/main/java/org/concordia/babycdf/RuleDocument.java').read_text()
env=dict(os.environ)
for k in ('INTAKE_GITHUB_TOKEN_FILE','LLM_API_KEY_FILE'):env.pop(k,None)
model=json.loads(subprocess.check_output(['docker','compose','--profile','agents','-f',str(r/'demo/compose/compose.yaml'),'config','--format','json'],env=env,text=True))
def covers(target):
    target=target.rstrip('/') or '/'
    return target=='/' or target=='/app/rules' or '/app/rules'.startswith(target+'/')
for name in ('advisory','reviewer','intake'):
    service=model['services'][name]
    assert service['read_only'] is True,name
    for mount in service.get('volumes',[]):
        assert not covers(mount['target']) or mount.get('read_only') is True,(name,mount)
    for mount in service.get('tmpfs',[]):
        target=mount if isinstance(mount,str) else mount['target']
        assert not covers(target.split(':')[0]),(name,mount)
print('Rule COPY, dedicated root, read-only agents and normalized volume/tmpfs coverage passed')
