#!/usr/bin/env python3
"""Exercise the real v5 guard against mutations in private temporary copies."""
import os
import pathlib
import shutil
import subprocess
import tempfile

here = pathlib.Path(__file__).resolve().parent
base = os.environ.get('BABYCDF_BUILD_ROOT')
if not base or not pathlib.Path(base).is_dir():
    raise SystemExit('BABYCDF_BUILD_ROOT must name an existing approved temporary directory')
work = pathlib.Path(tempfile.mkdtemp(prefix='workflow-tamper-', dir=base))
print('Artifacts:', work, flush=True)
files = ['test-narration.sh', 'check-workflow.py', 'run-demo.sh',
         'daml/Cap/Examples/BabyCdf/Live.daml', 'daml/Cap/Examples/BabyCdf/LiveVote.daml']
live = 'daml/Cap/Examples/BabyCdf/Live.daml'
rule = '../impl/daml/Cap/Examples/BabyCdf/RuleVote.daml'

cases = [
    ('second-agenda', live, '  initialState 2 witnesses', '  initialState 2 witnesses\n  extra <- propose (runId <> ":extra")'),
    ('missing-initial', live, '  initialState 2 witnesses', '  pure ()'),
    ('late-initial', live, '  initialState 2 witnesses', '  ignored <- submit state.opener $ createCmd DemoAnchor state\n  initialState 2 witnesses'),
    ('optional-in-all', 'run-demo.sh', '  all)', '  all)\n    run_opt_step 1'),
    ('optional-advances', live, '  (_, state) <- findAnchorAtLeast runId 2', '  (anchorRef, state) <- findAnchorAtLeast runId 2\n  advance anchorRef state 3'),
    ('credential-advances', live, 'credentialRefusalsWith closeWindow runId = do',
     'credentialRefusalsWith closeWindow runId = do\n  advance undefined undefined 6'),
    ('quorum-advances', live, 'noQuorumWith closeWindow runId = do',
     'noQuorumWith closeWindow runId = do\n  advance undefined undefined 6'),
    ('double-advances', live, 'doubleVote runId = do',
     'doubleVote runId = do\n  advance undefined undefined 6'),
    ('outsider-advances', live, 'outsiderRefusals runId = do',
     'outsiderRefusals runId = do\n  advance undefined undefined 6'),
    ('hard-coded-count', live, '      grants = distinctCount grantRows', '      grants = 7'),
    ('catalogue-constant-count', live,
     '  pure (length items, Set.size keys, proposals, milestones)',
     '  pure (300, 300, 200, 100)'),
    ('catalogue-query-removed', live,
     '  rows <- query @Cap.Examples.BabyCdf.SourceCatalogue.SourceItem opener',
     '  let rows = []'),
    ('weakened-count', rule, 'atLeastPresent 3', 'atLeastPresent 2'),
    ('reader-member-read', live, 'let required = [CanReadAs contract.opener]',
     'let required = [CanReadAs member, CanReadAs contract.opener]'),
    ('reader-act-as', live, 'let required = [CanReadAs contract.opener]',
     'let required = [CanActAs contract.opener]'),
    ('reader-rights-unchecked', live,
     'assert (length actual == length required && all (`elem` actual) required)', 'pure ()'),
    ('intake-member-act-as', live, 'let intakeRequired = [CanActAs contract.opener]',
     'let intakeRequired = [CanActAs member]'),
]

def fixture(name):
    dest = work / name / 'demo'
    for relative in files + [rule]:
        target = dest / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(here / relative, target)
    return dest

for name, relative, old, new in [('baseline', '', '', '')] + cases:
    dest = fixture(name)
    if relative:
        target = dest / relative
        text = target.read_text()
        if text.count(old) != 1:
            raise AssertionError(f'{name}: mutation target not unique: {text.count(old)}')
        target.write_text(text.replace(old, new, 1))
    result = subprocess.run(['sh', str(dest / 'test-narration.sh')], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (dest.parent / 'guard.log').write_text(result.stdout)
    expected = result.returncode == 0 if name == 'baseline' else result.returncode != 0
    print(('PASS' if expected else 'FAIL') + ': ' + name + f' (guard exit {result.returncode})', flush=True)
    if not expected:
        raise AssertionError(result.stdout)
print(f'{len(cases) + 1}/{len(cases) + 1} baseline and tamper cases passed; no live files mutated')
