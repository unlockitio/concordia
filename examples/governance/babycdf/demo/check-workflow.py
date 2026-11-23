#!/usr/bin/env python3
"""Source-only workflow invariants; no ledger or build invocation."""
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1])
live = (root / 'daml/Cap/Examples/BabyCdf/Live.daml').read_text()
runner = (root / 'run-demo.sh').read_text()
errors = []

def body(name):
    match = re.search(r'^' + name + r' .*?= do\n(.*?)(?=^\w[^\n]* :|\Z)', live, re.M | re.S)
    if not match:
        errors.append('missing function ' + name)
        return ''
    return re.sub(r'--[^\n]*', '', match.group(1))

for step, name in enumerate(('bootstrap', 'ruleVote', 'proposalVote', 'milestoneVote', 'couponAllocate'), 1):
    text = body(name)
    initial = re.search(r'phase 1 "INITIAL STATE"' if step == 1 else r'initialState ' + str(step) + r'\b', text)
    mutation = re.search(r'\b(submit|trySubmit|submitMustFail|bootstrapAdvisoryGovernor|allocateParty|createUser|grantUserRights)\b', text)
    if not initial or (mutation and initial.start() > mutation.start()):
        errors.append(f'step {step}: INITIAL STATE must precede first mutation')
    if not re.search(r'finalState ' + str(step) + r'\b', text):
        errors.append(f'step {step}: missing FINAL STATE')

for helper in ('initialState', 'finalState'):
    text = body(helper)
    for template in ('EffectiveRule', 'ApprovedGrantProposal', 'ApprovedMilestone', 'DemoAnchor'):
        if 'query @' + template not in text:
            errors.append(helper + ': missing live query ' + template)
    if 'governanceEvidence parties' not in text:
        errors.append(helper + ': missing interface queries')
    # A constant replacing a query-derived tally must not look like live evidence.
    if re.search(r'\b(?:grants|milestones|versions|steps|e)\s*=\s*\d+\b', text):
        errors.append(helper + ': hard-coded state count')

all_block = re.search(r'^  all\).*?(?=^  [a-z*][^\n]*\))', runner, re.M | re.S)
if not all_block:
    errors.append('missing all dispatch')
elif re.search(r'^\s*(?:run_opt_step\b|[^#\n]*(?:run-demo\.sh|\$0)[^\n]*\bopt-step\b)', all_block.group(0), re.M):
    errors.append('all must exclude optional rounds')
for name in ('staleRuleChange', 'credentialRefusalsWith', 'noQuorumWith', 'doubleVote', 'outsiderRefusals'):
    opt = body(name)
    if re.search(r'\badvance\b|archiveCmd.*anchor|createCmd DemoAnchor', opt):
        errors.append(name + ': optional round must not advance main anchor')
if not re.search(r'for n in 2 3 4 5; do', all_block.group(0)) or 'for n in 2 3 4 5 6' in all_block.group(0):
    errors.append('all must finish at step 5')
for name, source, suffix in [('proposalVote', 'proposal-184', ':grant'), ('milestoneVote', 'milestone-540', ':blocked-milestone')]:
    text = body(name)
    if not re.search(r'requestIntake state runId "' + source + r'" \(runId <> "' + suffix + r'"\)', text):
        errors.append(name + ': missing Java Intake request with preserved round ID')
    if 'proposal <- submit' in text:
        errors.append(name + ': successful proposal must not bypass Java Intake')
if 'awaitIntake state.opener roundId boundAgenda 120' not in body('requestIntake'):
    errors.append('Intake wait must remain bounded')
catalogue = body('sourceCatalogueCounts')
for required in ('query @Cap.Examples.BabyCdf.SourceCatalogue.SourceObservationCredential opener',
                 'item.opener == opener', 'Set.fromList', 'item.observation.number',
                 'SourceProposal', 'SourceMilestone',
                 'pure (length items, Set.size keys, proposals, milestones)'):
    if required not in catalogue:
        errors.append('catalogue: missing query-derived count seam ' + required)
if re.search(r'\b(?:items|keys|proposals|milestones)\s*=\s*\d+\b', catalogue):
    errors.append('catalogue: hard-coded count')
for name in ('proposalVote', 'milestoneVote'):
    text = body(name)
    if text.count('sourceCatalogueEvidence state.opener') != 1:
        errors.append(name + ': expected one opener-scoped catalogue snapshot')
for error in errors:
    print('FAIL: ' + error)
if errors:
    sys.exit(1)
print('PASS: workflow ordering, query-derived state and optional isolation')
