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
if 'run_step 2' not in all_block.group(0) or 'exit $?' not in all_block.group(0):
    errors.append('integrated all must stop after Architecture step 2')
for name, step, milestone in [('proposalVote', 3, 'False'), ('milestoneVote', 4, 'True')]:
    text = body(name)
    if f'manualExistingRound runId {step} {milestone}' not in text:
        errors.append(name + ': must dispatch the existing-round manual phase machine')
manual = '\n'.join(body(name) for name in ['manualExistingRound', 'manualIdentityValid', 'manualCanResume', 'manualProposalPin', 'manualRoundBallots', 'validateManualRoundBallots', 'castManualMissing', 'proposalVote', 'milestoneVote'])
for forbidden in ['requestIntake', 'ProposeAgenda', 'IntakeRequest', 'fixtureSource', 'sourceForAgenda', 'waitUntilClosed', 'sleep (']:
    if forbidden in manual:
        errors.append('manual existing-round path must not contain ' + forbidden)
for required in ['query @AgendaProposal', 'query @AgendaAction', 'query @AgendaRoundReceipt', 'query @ManualRoundCompleted', 'query @ManualRoundResolved', 'query @ManualRoundPending', 'now < proposal.closesAt', 'PENDING:', 'coreQuorum quorumSize state.core', 'manualProposalPin s']:
    if required not in manual:
        errors.append('manual existing-round path missing ' + required)
manual_phase = body('manualExistingRound')
prevalidate = manual_phase.find('validateManualRoundBallots proposal existingRound')
checkpoint_create = manual_phase.find('createCmd ManualRoundPending')
if prevalidate < 0 or checkpoint_create < 0 or prevalidate >= checkpoint_create:
    errors.append('manual existing ballots must be fully validated before checkpoint creation')
if 'Existing votes conflict with this manual tally; no checkpoint or cast was created.' not in manual_phase[:checkpoint_create]:
    errors.append('manual existing votes must be compatible before checkpoint creation')
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
