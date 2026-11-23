#!/usr/bin/env python3
"""Real pagination subprocess with stub curl, no sockets or containers."""
import pathlib,tempfile,subprocess,os,json,unittest
HERE=pathlib.Path(__file__).resolve().parent
class Cli(unittest.TestCase):
 def testLedgerContractsSecondPage413HasNoStdoutOrHeading(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=pathlib.Path(tmp);log=root/'calls';docker=root/'docker'
   docker.write_text('''#!/usr/bin/env python3
import sys,json,pathlib,os
a=sys.argv[1:]
if 'ps' in a:print('container');sys.exit(0)
if any('/v2/state/ledger-end' in x for x in a):sys.stdout.write('{"offset":99}\\n200');sys.exit(0)
if not any('/v2/state/active-contracts-page' in x for x in a):sys.exit(88)
assert a[a.index('curl')+1]=='-q'
b=json.loads(a[a.index('--data-binary')+1]);p=pathlib.Path(os.environ['REQUEST_LOG']);old=p.read_text().splitlines() if p.exists() else [];p.open('a').write(json.dumps(b)+'\\n')
if old and os.environ.get('NULL_SUCCESS')=='1':sys.stdout.write(json.dumps({'activeContracts':[],'activeAtOffset':99,'nextPageToken':None})+'\\n200')
elif old:sys.stdout.write('{"error":"limit"}\\n413')
else:sys.stdout.write(json.dumps({'activeContracts':[],'activeAtOffset':99,'nextPageToken':'opaque'})+'\\n200')
''');docker.chmod(0o755)
   p=subprocess.run(['sh',str(HERE/'ledger.sh'),'http','contracts'],text=True,capture_output=True,env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],BABYCDF_HTTP_VIA='exec',REQUEST_LOG=str(log)))
   self.assertNotEqual(0,p.returncode);self.assertEqual('',p.stdout);self.assertNotIn('Active contracts visible',p.stdout+p.stderr);self.assertIn('HTTP 413',p.stderr);self.assertEqual(2,len(log.read_text().splitlines()))
   success_log=root/'success-calls'
   success=subprocess.run(['sh',str(HERE/'ledger.sh'),'http','contracts'],text=True,capture_output=True,env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],BABYCDF_HTTP_VIA='exec',REQUEST_LOG=str(success_log),NULL_SUCCESS='1'))
   self.assertEqual(0,success.returncode,success.stderr);self.assertIn('Active contracts visible',success.stdout);self.assertEqual(2,len(success_log.read_text().splitlines()))
 def testOpaqueMultiPageAndSecond413Atomic(self):
  for fail in (False,True):
   with tempfile.TemporaryDirectory() as tmp:
    root=pathlib.Path(tmp);stub=root/'curl';log=root/'requests'
    stub.write_text('''#!/usr/bin/env python3
import sys,json,os,pathlib
args=sys.argv[1:];body=json.loads(args[args.index('--data-binary')+1]);p=pathlib.Path(os.environ['REQUEST_LOG']);previous=p.read_text().splitlines() if p.exists() else [];p.open('a').write(json.dumps(body)+'\\n')
if previous and os.environ['FAIL_SECOND']=='1':print('{"error":"too many"}\\n413');sys.exit(0)
page={'activeAtOffset':99,'activeContracts':[{'contractEntry':{'JsActiveContract':{'createdEvent':{'contractId':'cid'+str(len(previous))}}}}]}
if not previous:page['nextPageToken']='opaque+/== token'
else:page['nextPageToken']=None
sys.stdout.write(json.dumps(page)+'\\n200');sys.exit(0)
print(json.dumps(page)+'\\n200')
''');stub.chmod(0o755)
    request={'activeAtOffset':99,'eventFormat':{'filtersForAnyParty':{'cumulative':[]},'verbose':True}}
    p=subprocess.run(['python3',str(HERE/'http-acs-pages.py'),'http://same'],input=json.dumps(request),text=True,capture_output=True,env=dict(os.environ,PATH=str(root)+os.pathsep+os.environ['PATH'],BABYCDF_HTTP_VIA='host',REQUEST_LOG=str(log),FAIL_SECOND='1' if fail else '0'))
    rows=[json.loads(x) for x in log.read_text().splitlines()];self.assertEqual(2,len(rows),p.stderr);self.assertEqual('opaque+/== token',rows[1]['pageToken']);self.assertEqual(rows[0]['eventFormat'],rows[1]['eventFormat']);self.assertEqual(99,rows[1]['activeAtOffset'])
    if fail:self.assertNotEqual(0,p.returncode);self.assertEqual('',p.stdout);self.assertIn('HTTP 413',p.stderr)
    else:self.assertEqual(0,p.returncode,p.stderr);self.assertEqual(2,len(json.loads(p.stdout)))
if __name__=='__main__':unittest.main()
