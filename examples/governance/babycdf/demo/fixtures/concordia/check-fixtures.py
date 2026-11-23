#!/usr/bin/env python3
"""Reproducible offline sanitizer/validator. --sanitize rewrites JSON and hashes."""
import pathlib,json,re,hashlib,sys,ipaddress
root=pathlib.Path(__file__).resolve().parent
patterns=[r'[\w.+-]+@[\w-]+\.[\w.-]+',r'ghp_[A-Za-z0-9_]+',r'github_pat_[A-Za-z0-9_]+',r'(?i)Bearer\s+\S+',r'(?i)Authorization',r'(?i)token\s*=',r'https?://(?:localhost|127\.|10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)[^\s"<>]*',r'(?<![\w])(?:\d{1,3}\.){3}\d{1,3}(?![\w])',r'(?<![\w])(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:]+']
# Public GitHub identities are retained as evidence, not authorization.
logins=set()
def sanitize(value):
 if isinstance(value,dict):return {k:sanitize(v) for k,v in value.items() if k.lower() not in {'email','authorization'}}
 if isinstance(value,list):return [sanitize(v) for v in value]
 if isinstance(value,str):
  for pattern in patterns:value=re.sub(pattern,'[redacted]',value)
 return value
files=sorted(root.rglob('*.json'))
if '--sanitize' in sys.argv:
 for path in files:path.write_text(json.dumps(sanitize(json.loads(path.read_text())),ensure_ascii=False,sort_keys=True,indent=2)+'\n')
 (root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in files))
expected={}
for line in (root/'SHA256SUMS').read_text().splitlines():
 parts=line.split('  ',1)
 if len(parts)!=2 or not re.fullmatch(r'[0-9a-f]{64}',parts[0]) or parts[1] in expected:raise SystemExit('invalid/duplicate checksum entry')
 expected[parts[1]]=parts[0]
if any(p.parent!=root for p in files):raise SystemExit('unexpected nested capture inventory')
sensitive={'access_token','token','password','secret','authorization','private_key','client_secret'}
def inspect_keys(v):
 if isinstance(v,dict):
  for k,x in v.items():
   if k.lower() in sensitive:raise SystemExit('sensitive fixture field')
   inspect_keys(x)
 elif isinstance(v,list):
  for x in v:inspect_keys(x)
for path in files:
 raw=path.read_text();inspect_keys(json.loads(raw))
 for pattern in patterns:
  if re.search(pattern,raw):raise SystemExit('unsafe fixture pattern in '+path.name+' '+pattern)
 if expected.get(path.name)!=hashlib.sha256(path.read_bytes()).hexdigest():raise SystemExit('checksum mismatch '+path.name)
 def collect(v):
  if isinstance(v,dict):
   if isinstance(v.get('login'),str):logins.add(v['login'])
   for x in v.values():collect(x)
  elif isinstance(v,list):
   for x in v:collect(x)
 collect(json.loads(raw))
if set(expected)!=set(p.name for p in files):raise SystemExit('checksum coverage mismatch')
allow=root/'PUBLIC-LOGINS.json'
if '--sanitize' in sys.argv:allow.write_text(json.dumps(sorted(logins),indent=2)+'\n')
if '--sanitize' in sys.argv:
 files=sorted(root.rglob('*.json'))
 (root/'SHA256SUMS').write_text(''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in files))
if allow.exists() and not logins.issubset(set(json.loads(allow.read_text()))):raise SystemExit('unlisted public login')
manifest=json.loads((root/'concordia-amendments.json').read_text())
if set(manifest)!={'scheme','amendments'} or manifest['scheme']!='babycdf-amendments/v1' or manifest['amendments']!=[]:raise SystemExit('real amendment fixture must remain explicitly empty without evidence')
print('PASS: all fixture JSON scanned; hashes complete; public logins allowlisted; empty amendment manifest valid')
