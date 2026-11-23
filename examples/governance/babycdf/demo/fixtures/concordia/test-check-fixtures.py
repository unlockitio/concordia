#!/usr/bin/env python3
"""Offline adversarial validator tests; modifies only fresh temporary copies."""
import pathlib,tempfile,shutil,subprocess,json,hashlib
source=pathlib.Path(__file__).resolve().parent

def run(change):
 with tempfile.TemporaryDirectory() as directory:
  root=pathlib.Path(directory)/'fixtures';shutil.copytree(source,root)
  change(root)
  result=subprocess.run(['python3',str(root/'check-fixtures.py')],capture_output=True,text=True)
  assert result.returncode!=0,result.stdout

def inject(root,key):
 path=root/'injected.json';path.write_text(json.dumps({key:'innocent-looking'}))
 with (root/'SHA256SUMS').open('a') as out:out.write(hashlib.sha256(path.read_bytes()).hexdigest()+'  injected.json\n')
for key in ['access_token','token','password','secret','authorization','private_key','client_secret']:
 for variant in [key,key.upper(),key.title()]:run(lambda root,key=variant:inject(root,key))
run(lambda root:(root/'issue-540.json').unlink())
run(lambda root:(root/'SHA256SUMS').write_text((root/'SHA256SUMS').read_text()+(root/'SHA256SUMS').read_text().splitlines()[0]+'\n'))
run(lambda root:(root/'unexpected.json').write_text('{}'))
def nested(root):
 (root/'nested').mkdir();(root/'nested/capture.json').write_text('{"Secret":"value"}')
run(nested)
print('PASS:21 sensitive-key cases,missing,duplicate,unexpected,nested inventory rejected')
