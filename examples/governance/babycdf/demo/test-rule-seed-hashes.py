#!/usr/bin/env python3
"""Runner computes exact document hashes for script input, not package literals."""
from pathlib import Path
import hashlib,json,subprocess,tempfile
r=Path(__file__).resolve().parent.parent
runner=(r/'demo/run-demo.sh').read_text()
start=runner.index("import hashlib, json, sys\n")
end=runner.index('\nPY',start)
program=runner[start:end]
with tempfile.TemporaryDirectory() as tmp:
    output=Path(tmp)/'input.json'
    subprocess.run(['python3','-',str(r/'rules'),str(output),str(30*24*60*60)],input=program,text=True,check=True)
    configured=json.loads(output.read_text())
    assert configured['credentialValiditySeconds']==30*24*60*60
    value=configured['input']
    for version,key in [(1,'initialRuleRef'),(2,'proposedRuleRef')]:
        ref=value[key]
        assert ref==dict(location=f'rules/rule-v{version}.json',commit=None,sha256=hashlib.sha256((r/f'rules/rule-v{version}.json').read_bytes()).hexdigest())
        print(f'v{version} computed runner RuleRef MATCH {ref["sha256"]}')
live=(r/'demo/daml/Cap/Examples/BabyCdf/Live.daml').read_text()
assert 'input.initialRuleRef' in live and 'input.proposedRuleRef' in live
assert '"live v2" state.proposedRuleRef' in live
for version in (1,2):
    digest=hashlib.sha256((r/f'rules/rule-v{version}.json').read_bytes()).hexdigest()
    assert digest not in (r/'impl/daml/Cap/Examples/BabyCdf/RuleVote.daml').read_text()
print('Rule text hashes are runtime input, not compiled package constants')
