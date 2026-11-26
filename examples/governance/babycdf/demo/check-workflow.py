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
if 'manualExistingRound runId 3 False' not in body('proposalVote'):
    errors.append('proposalVote: must dispatch the existing-round manual vote')
# Step 4 dispatches through the explicit round selector. The entry point must
# still reach the existing-round manual vote and nothing else, so the whole
# chain is pinned: milestoneVote -> milestoneVoteFor -> manualExistingRoundFor,
# with the step and milestone flag unchanged. Checking only the entry point
# would let the selector wrapper quietly dispatch somewhere else.
# body() only extracts `= do` blocks, so a one-line definition is invisible to
# it and the search would run on into the next function. These two dispatches
# are one-liners, so they are matched as whole definition lines instead.
def defines(line):
    return re.search(r'^' + re.escape(line) + r'\s*$', live, re.M)

if not defines('milestoneVote runId = milestoneVoteFor (ManualRoundInput runId "")'):
    errors.append('milestoneVote: must dispatch the existing-round manual vote')
if 'manualExistingRoundFor input.runId 4 True selector' not in body('milestoneVoteFor'):
    errors.append('milestoneVoteFor: must dispatch the existing-round manual vote')
if not defines('manualExistingRound runId step milestone = manualExistingRoundFor runId step milestone None'):
    errors.append('manualExistingRound: must delegate to the selector form with no selector')
# The selector must narrow an already validated set, and absence of a selector
# must not be resolved by guessing. A silent earliest/latest/head pick would
# satisfy the dispatch checks above while deciding a governance question.
selector_body = body('manualExistingRoundFor')
if 'selected = filter requested eligible' not in selector_body:
    errors.append('manualExistingRoundFor: selector must filter the eligible set')
if 'length selected == 1' not in selector_body:
    errors.append('manualExistingRoundFor: must require exactly one selected round')
for guess in ('DA.List.head', 'DA.List.last eligible', 'DA.List.sortOn', 'SourceRoundHead',
              'head eligible', 'take 1 eligible'):
    if guess in selector_body:
        errors.append('manualExistingRoundFor: must not pick a round implicitly: ' + guess)
manual = '\n'.join(body(name) for name in ['manualExistingRound', 'manualIdentityValid', 'manualProposalPin', 'validateManualRoundBallots', 'proposalVote', 'milestoneVote'])
for forbidden in ['requestIntake', 'ProposeAgenda', 'IntakeRequest', 'fixtureSource', 'sourceForAgenda', 'waitUntilClosed', 'sleep (', 'createCmd']:
    if forbidden in manual:
        errors.append('manual existing-round path must not contain ' + forbidden)
for removed in ['ManualRoundPending', 'ManualRoundResolved', 'ManualRoundCompleted', 'manualCanResume', 'castManualMissing']:
    if removed in manual:
        errors.append('manual existing-round path must not restore ' + removed)
for required in ['query @AgendaProposal', 'queryInterface @VoteTopic', 'toInterfaceContractId @VoteTopic ref', 'query @AgendaAction', 'query @AgendaRoundReceipt', 'beforeMutation < proposal.closesAt', 'coreQuorum quorumSize state.core', 'manualProposalPin s', 'exerciseCmd state.governor ResolveAgenda', 'exerciseCmd execution Executable_Execute']:
    if required not in manual:
        errors.append('manual existing-round path missing ' + required)
manual_phase = body('manualExistingRound')
invitation = manual_phase.find('inviteBallotsFor step opener proposalCid')
for check in ['validateManualRoundBallots proposal existingRound', 'b.vote == Count.Absent', 'forA_ rights', 'queryContractId opener ref', 'beforeMutation < proposal.closesAt']:
    position = manual_phase.find(check)
    if position < 0 or invitation < 0 or position >= invitation:
        errors.append('manual existing-round preflight must precede invitation: ' + check)
if manual_phase.find('castYesBy step opener voters ballots') <= invitation:
    errors.append('manual casting must follow invitation and preflight')
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
