#!/usr/bin/env python3
"""Live narration identifies documents; clause wording remains runtime data."""
from pathlib import Path
import json,re
r=Path(__file__).resolve().parent.parent
live=(r/'demo/daml/Cap/Examples/BabyCdf/Live.daml').read_text()
spoken='\n'.join(line for line in live.splitlines() if re.search(r'\b(business|debug|outcomeLine)\b',line))
for stale in ('execution hooks','auditable-state-and-acceptance','v1 required:','v2 requires:'):
    assert stale not in spoken,stale
for version in (1,2):
    for clause in json.loads((r/f'rules/rule-v{version}.json').read_text())['clauses']:
        assert clause['text'] not in spoken,clause['id']
assert 'activeRule.draft.ruleRef.location' in live and 'DA.Text.take 12' in live
print('Live rule narration is reference-only; no runtime clause text compiled')
